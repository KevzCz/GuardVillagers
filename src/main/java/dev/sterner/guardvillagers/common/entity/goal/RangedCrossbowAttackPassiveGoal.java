package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseRangedSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.ConditionalSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardCastVisuals;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardSpellTimings;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import dev.sterner.guardvillagers.mixin.accessor.CrossbowItemAccessor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class RangedCrossbowAttackPassiveGoal<T extends PathAwareEntity & RangedAttackMob & CrossbowUser>
        extends BaseRangedSpellGoal {

    private final T mob;
    private final ConditionalSpellGoal conditionalSpells;
    private int chargingTime = 0;

    private double wantedX;
    private double wantedY;
    private double wantedZ;

    private CrossbowState crossbowState = CrossbowState.UNCHARGED;
    private SpellCastState spellCastState = SpellCastState.NONE;
    private int spellAttackCooldown = 0;

    private enum CrossbowState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        READY_TO_ATTACK,
        FIND_NEW_POSITION
    }

    private enum SpellCastState {
        NONE,
        WINDING_UP,
        CHANNELING
    }

    public RangedCrossbowAttackPassiveGoal(T mob, double speedModifier, float attackRadius) {
        super((GuardEntity) mob, speedModifier, attackRadius);
        this.mob = mob;
        this.conditionalSpells = new ConditionalSpellGoal((GuardEntity) mob);
        this.setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (mob instanceof GuardEntity guard && guard.isCastingMeleeSpell()) {
            return false;
        }
        return isValidTarget() && isHoldingCrossbow();
    }

    @Override
    public boolean shouldContinue() {
        if (mob instanceof GuardEntity guard && guard.isCastingMeleeSpell()) {
            return false;
        }
        return isValidTarget() && isHoldingCrossbow() && (canStart() || !mob.getNavigation().isIdle());
    }

    @Override
    protected boolean shouldInterruptCastOnStop() {
        if (spellCastState != SpellCastState.NONE || spellAttackCooldown > 0) {
            return false;
        }
        return super.shouldInterruptCastOnStop();
    }

    @Override
    public void start() {
        super.start();
        crossbowState = CrossbowState.UNCHARGED;
        chargingTime = 0;
    }

    @Override
    public void stop() {
        super.stop();

        if (mob.isUsingItem()) {
            mob.stopUsingItem();
            mob.setCharging(false);
        }
        mob.setPose(EntityPose.STANDING);

        resetSpellState();
        crossbowState = CrossbowState.UNCHARGED;
        chargingTime = 0;
        spellAttackCooldown = 0;
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return;

        tickCooldowns();

        if (spellAttackCooldown > 0) {
            spellAttackCooldown--;
        }

        boolean canSee = mob.getVisibilityCache().canSee(target);
        boolean inAimPhase = mob.isUsingItem() || spellCastState != SpellCastState.NONE;
        boolean canRun = crossbowState == CrossbowState.UNCHARGED && spellCastState == SpellCastState.NONE;

        updateCombatMovement(target, inAimPhase, canRun);

        if (spellCastState != SpellCastState.NONE) {
            mob.getLookControl().lookAt(target, 30.0F, 30.0F);
            maintainCrossbowChargeDuringSpell();
            handleSpellCasting(target);
            return;
        }

        if (canSee && spellAttackCooldown <= 0 && !guard.isCastingSpell() && tryStartCrossbowSpellCast(target)) {
            return;
        }

        if (friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire) {
            crossbowState = CrossbowState.FIND_NEW_POSITION;
        }

        handleCrossbowState(target, canSee);
    }

    private void handleCrossbowState(LivingEntity target, boolean canSee) {
        switch (crossbowState) {
            case FIND_NEW_POSITION -> handleFindNewPosition();
            case UNCHARGED -> handleUncharged();
            case CHARGING -> handleCharging();
            case CHARGED -> handleCharged();
            case READY_TO_ATTACK -> handleReadyToAttack(target, canSee);
        }
    }

    private void handleFindNewPosition() {
        mob.stopUsingItem();
        mob.setCharging(false);

        if (findNewPosition()) {
            mob.getNavigation().startMovingTo(wantedX, wantedY, wantedZ, mob.isSneaking() ? 0.5D : 1.2D);
        }

        crossbowState = CrossbowState.UNCHARGED;
    }

    private void handleUncharged() {
        if (seeTime > 0) {
            mob.setCurrentHand(getCrossbowHand());
            mob.setCharging(true);
            crossbowState = CrossbowState.CHARGING;
        }
    }

    private void handleCharging() {
        chargingTime++;
        int useTime = mob.getItemUseTime();
        ItemStack itemStack = mob.getActiveItem();

        if (useTime >= 25 || CrossbowItem.isCharged(itemStack) || chargingTime > 60) {
            mob.stopUsingItem();
            mob.setCharging(false);
            attackDelay = 10 + mob.getRandom().nextInt(5);
            crossbowState = CrossbowState.CHARGED;
            chargingTime = 0;
        }
    }

    private void handleCharged() {
        if (--attackDelay <= 0) {
            crossbowState = CrossbowState.READY_TO_ATTACK;
        }
    }

    private void handleReadyToAttack(LivingEntity target, boolean canSee) {
        if (!canSee) return;
        performCrossbowShot(target);
    }

    private boolean tryStartCrossbowSpellCast(LivingEntity target) {
        Identifier spellId = pickCrossbowSpellId();
        if (spellId == null) {
            return false;
        }
        return castSpellFromId(spellId, target);
    }

    private Identifier pickCrossbowSpellId() {
        Identifier stashSpellId = getStashEffectSpellId();
        if (stashSpellId != null && !isSpellOnCooldown(stashSpellId)) {
            return stashSpellId;
        }

        Identifier supportSpellId = getSupportSpellId();
        if (supportSpellId != null && !isSpellOnCooldown(supportSpellId)) {
            return supportSpellId;
        }

        Identifier spellId = getCrossbowSpellId();
        if (spellId == null || isSpellOnCooldown(spellId)) {
            return null;
        }
        return spellId;
    }

    private void maintainCrossbowChargeDuringSpell() {
        Hand hand = getCrossbowHand();
        if (!mob.isUsingItem()) {
            mob.setCurrentHand(hand);
            mob.setCharging(true);
        }

        ItemStack crossbow = mob.getStackInHand(hand);
        if (mob.getItemUseTime() >= 5 && !CrossbowItem.isCharged(crossbow)) {
            ItemStack ammo = mob.getProjectileType(crossbow);
            if (ammo.isEmpty()) {
                ammo = new ItemStack(Items.ARROW);
            }
            crossbow.set(
                    DataComponentTypes.CHARGED_PROJECTILES,
                    ChargedProjectilesComponent.of(List.of(ammo.copyWithCount(1)))
            );
        }
    }

    private Identifier getStashEffectSpellId() {
        if (!(mob instanceof GuardEntity guard)) return null;

        java.util.function.Predicate<GuardSpellManager.CategorizedSpell> isStashArrowSpell = spell -> {
            Spell s = spell.entry().value();
            return s.deliver != null
                    && s.deliver.type == Spell.Delivery.Type.STASH_EFFECT
                    && !isSpellOnCooldown(spell.spellId());
        };

        GuardSpellManager manager = guard.getSpellManager();
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = manager.getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                isStashArrowSpell
        );
        if (bestSpell.isEmpty()) {
            bestSpell = manager.getBestSpell(GuardSpellManager.SpellCategory.SUPPORT, isStashArrowSpell);
        }

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean castSpellFromId(Identifier spellId, LivingEntity target) {
        return getSpellEntry(spellId).map(entry -> {
            Spell spell = entry.value();
            currentSpellId = spellId;
            cachedSpellEntry = entry;

            windUpTicks = getWindUpTicks(spell);
            isChanneled = isSpellChanneled(spell);
            channelTicksLeft = getChannelDuration(spell);
            castingDelayTicks = 0;

            mob.setCurrentHand(getCrossbowHand());
            if (windUpTicks > 0) {
                spellCastState = SpellCastState.WINDING_UP;
                GuardCastVisuals.beginCrossbowCast(guard, entry, spell);
            } else if (isChanneled && channelTicksLeft > 0) {
                spellCastState = SpellCastState.CHANNELING;
                GuardCastVisuals.beginCrossbowCast(guard, entry, spell);
                configureChannelCastVisuals(spell);
            } else {
                finishCrossbowSpellCast(target, spell, entry);
            }
            return true;
        }).orElse(false);
    }

    private void handleSpellCasting(LivingEntity target) {
        if (cachedSpellEntry == null) {
            resetSpellState();
            return;
        }

        Spell spell = cachedSpellEntry.value();

        switch (spellCastState) {
            case WINDING_UP -> {
                if (windUpTicks % 2 == 0) {
                    spawnCastingParticles(spell);
                }

                if (--windUpTicks <= 0) {
                    if (isChanneled && channelTicksLeft > 0) {
                        spellCastState = SpellCastState.CHANNELING;
                        configureChannelCastVisuals(spell);
                    } else {
                        finishCrossbowSpellCast(target, spell, cachedSpellEntry);
                    }
                }
            }

            case CHANNELING -> {
                if (channelTicksLeft % 2 == 0) {
                    spawnCastingParticles(spell);
                }

                if (channelTicksLeft > 0) {
                    if (channelTicksLeft == getChannelDuration(spell) || --castingDelayTicks <= 0) {
                        deliverCrossbowSpell(target, spell, cachedSpellEntry);
                        castingDelayTicks = getChannelFireInterval(spell);
                    }
                    channelTicksLeft--;
                } else {
                    completeCrossbowSpellCast(spell);
                }
            }
        }
    }

    private void finishCrossbowSpellCast(LivingEntity target, Spell spell, RegistryEntry<Spell> entry) {
        deliverCrossbowSpell(target, spell, entry);
        completeCrossbowSpellCast(spell);
    }

    private void deliverCrossbowSpell(LivingEntity target, Spell spell, RegistryEntry<Spell> entry) {
        int channelOffset = isChanneled ? Math.max(0, getChannelDuration(spell) - channelTicksLeft - 1) : 0;
        guard.setChannelTickIndex(channelOffset);
        SpellContext context = createSpellContext(currentSpellId, entry, target).build();
        SpellDelivery.deliverSpell(context, channelOffset);
        clearCrossbowCharge();
    }

    private void completeCrossbowSpellCast(Spell spell) {
        mob.stopUsingItem();
        mob.setCharging(false);
        if (!GuardCastVisuals.hasReleaseAnimation(spell)) {
            GuardCastVisuals.completeCastWithRelease(guard, spell);
        }
        scheduleSpellCooldownOnComplete(currentSpellId, spell);
        spellAttackCooldown = Math.max(10, GuardSpellTimings.postDeliverWaitTicks(guard, spell));
        resetSpellState();
        crossbowState = CrossbowState.UNCHARGED;
        attackDelay = 10 + mob.getRandom().nextInt(5);
    }

    private void performCrossbowShot(LivingEntity target) {
        ItemStack crossbow = mob.getStackInHand(getCrossbowHand());
        Hand hand = getCrossbowHand();
        CrossbowItem crossbowItem = (CrossbowItem) crossbow.getItem();

        if (!CrossbowItem.isCharged(crossbow)) {
            ItemStack ammo = mob.getProjectileType(crossbow);
            if (ammo.isEmpty()) ammo = new ItemStack(Items.ARROW);

            crossbow.set(
                    net.minecraft.component.DataComponentTypes.CHARGED_PROJECTILES,
                    net.minecraft.component.type.ChargedProjectilesComponent.of(List.of(ammo.copyWithCount(1)))
            );
        }

        ((CrossbowItemAccessor) crossbowItem).callShootAll(
                mob.getWorld(), mob, hand, crossbow, 2.0F, 1.0F, target
        );

        mob.setCharging(false);
        crossbowState = CrossbowState.UNCHARGED;
    }

    private void clearCrossbowCharge() {
        ItemStack crossbow = mob.getStackInHand(getCrossbowHand());
        if (!crossbow.isEmpty()) {
            crossbow.remove(net.minecraft.component.DataComponentTypes.CHARGED_PROJECTILES);
        }
    }

    private Identifier getSupportSpellId() {
        if (!(mob instanceof GuardEntity guard)) return null;

        Optional<GuardSpellManager.CategorizedSpell> bestSpell = guard.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.SUPPORT,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect && conditionalSpells.canCastSpell(spell.spellId()) && !isSpellOnCooldown(spell.spellId());
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private Identifier getCrossbowSpellId() {
        if (!(mob instanceof GuardEntity guard)) return null;

        Optional<GuardSpellManager.CategorizedSpell> bestSpell = guard.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect
                            && !isSpellOnCooldown(spell.spellId())
                            && isArchetypeCompatibleWithCrossbow(spell);
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean isArchetypeCompatibleWithCrossbow(GuardSpellManager.CategorizedSpell spell) {
        return GuardSpellManager.isArcheryArchetype(spell.entry().value());
    }

    public void resetSpellState() {
        super.resetSpellState();
        spellCastState = SpellCastState.NONE;
    }

    private boolean isHoldingCrossbow() {
        return mob.isHolding(GuardItemTags::isCrossbowLikeWeapon);
    }

    private Hand getCrossbowHand() {
        return GuardVillagers.getHandWith(mob, item -> GuardItemTags.isCrossbowLikeWeapon(new ItemStack(item)));
    }

    private boolean friendlyInLineOfSight() {
        if (!(mob instanceof GuardEntity guard)) return false;

        List<Entity> nearby = mob.getWorld().getOtherEntities(mob, mob.getBoundingBox().expand(5.0D));

        for (Entity entity : nearby) {
            if (entity == mob.getTarget()) continue;

            boolean isFriendly = entity == guard.getOwner()
                    || entity.getType() == EntityType.VILLAGER
                    || entity.getType() == GuardVillagers.GUARD_VILLAGER
                    || entity.getType() == EntityType.IRON_GOLEM;

            if (isFriendly && mob.canSee(entity) && mob.distanceTo(entity) <= 4.0D) {
                Vec3d facing = mob.getRotationVector();
                Vec3d toEntity = entity.getPos().subtract(mob.getPos()).normalize();
                if (facing.dotProduct(toEntity) > 0.9D) return true;
            }
        }

        return false;
    }

    private boolean findNewPosition() {
        Vec3d pos = isValidTarget()
                ? NoPenaltyTargeting.findFrom(mob, 16, 7, mob.getTarget().getPos())
                : NoPenaltyTargeting.find(mob, 16, 7);

        if (pos != null) {
            wantedX = pos.x;
            wantedY = pos.y;
            wantedZ = pos.z;
            return true;
        }

        return false;
    }
}