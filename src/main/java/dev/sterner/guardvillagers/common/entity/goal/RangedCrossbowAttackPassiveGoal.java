package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseRangedSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.ConditionalSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import dev.sterner.guardvillagers.mixin.accessor.CrossbowItemAccessor;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
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
        return isValidTarget() && isHoldingCrossbow();
    }

    @Override
    public boolean shouldContinue() {
        return isValidTarget() && (canStart() || !mob.getNavigation().isIdle()) && isHoldingCrossbow();
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

        boolean canSee = mob.getVisibilityCache().canSee(target);
        boolean inAimPhase = mob.isUsingItem() || spellCastState != SpellCastState.NONE;
        boolean canRun = crossbowState == CrossbowState.UNCHARGED && spellCastState == SpellCastState.NONE;

        updateCombatMovement(target, inAimPhase, canRun);

        if (spellCastState != SpellCastState.NONE) {
            handleSpellCasting(target);
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

        if (trySpellCast(target)) return;

        performCrossbowShot(target);
    }

    private boolean trySpellCast(LivingEntity target) {
        Identifier stashSpellId = getStashEffectSpellId();
        if (stashSpellId != null && !isSpellOnCooldown(stashSpellId)) {
            return castSpellFromId(stashSpellId, target);
        }

        Identifier supportSpellId = getSupportSpellId();
        if (supportSpellId != null && !isSpellOnCooldown(supportSpellId)) {
            return castSpellFromId(supportSpellId, target);
        }

        float spellChance = getScaledSpellChance();
        if (mob.getRandom().nextFloat() >= spellChance) return false;

        Identifier spellId = getCrossbowSpellId();
        if (spellId == null || isSpellOnCooldown(spellId)) return false;

        return castSpellFromId(spellId, target);
    }

    private Identifier getStashEffectSpellId() {
        if (!(mob instanceof GuardEntity guard)) return null;

        Optional<GuardSpellManager.CategorizedSpell> bestSpell = guard.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                spell -> {
                    Spell s = spell.entry().value();
                    return s.deliver != null
                            && s.deliver.type == Spell.Delivery.Type.STASH_EFFECT
                            && !isSpellOnCooldown(spell.spellId());
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private float getScaledSpellChance() {
        if (!(mob instanceof GuardEntity guard)) return 0.15f;

        double rangedDamage = 0.0;

        Optional<RegistryEntry.Reference<net.minecraft.entity.attribute.EntityAttribute>> attrOpt =
                Registries.ATTRIBUTE.getEntry(Identifier.of("ranged_weapon", "damage"));

        if (attrOpt.isPresent()) {
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr = attrOpt.get();
            if (guard.getAttributes().hasAttribute(attr)) {
                rangedDamage = guard.getAttributeValue(attr);
            }
        }

        double minChance = 0.05;
        double maxChance = 0.5;
        double maxDamage = 50.0;

        double chance = minChance + (Math.min(rangedDamage, maxDamage) / maxDamage) * (maxChance - minChance);
        return (float) chance;
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

            if (windUpTicks > 0) {
                spellCastState = SpellCastState.WINDING_UP;
                guard.setCastingSpell(true);
            } else if (isChanneled && channelTicksLeft > 0) {
                spellCastState = SpellCastState.CHANNELING;
                guard.setCastingSpell(true);
            } else {
                castSpell(target, spell, entry);
                spellCooldowns.put(spellId, getCooldownTicks(spell));
                resetSpellState();
                crossbowState = CrossbowState.UNCHARGED;
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
                    } else {
                        castSpell(target, spell, cachedSpellEntry);
                        spellCooldowns.put(currentSpellId, getCooldownTicks(spell));
                        resetSpellState();
                        crossbowState = CrossbowState.UNCHARGED;
                    }
                }
            }

            case CHANNELING -> {
                if (channelTicksLeft % 2 == 0) {
                    spawnCastingParticles(spell);
                }

                if (channelTicksLeft > 0) {
                    if (channelTicksLeft == getChannelDuration(spell) || --castingDelayTicks <= 0) {
                        castSpell(target, spell, cachedSpellEntry);
                        castingDelayTicks = getChannelFireInterval(spell);
                    }
                    channelTicksLeft--;
                } else {
                    spellCooldowns.put(currentSpellId, getCooldownTicks(spell));
                    resetSpellState();
                    crossbowState = CrossbowState.UNCHARGED;
                }
            }
        }
    }

    private void castSpell(LivingEntity target, Spell spell, RegistryEntry<Spell> entry) {
        SpellContext context = createSpellContext(currentSpellId, entry, target).build();

        if (spell.deliver == null) {
            SpellDelivery.castDirect(context);
            clearCrossbowCharge();
            return;
        }

        switch (spell.deliver.type) {
            case SHOOT_ARROW, PROJECTILE -> SpellDelivery.castProjectile(context,
                    isChanneled ? (getChannelDuration(spell) - channelTicksLeft) : 0);
            case METEOR -> SpellDelivery.castMeteor(context);
            case CLOUD -> SpellDelivery.castCloud(context);
            case DIRECT -> SpellDelivery.castDirect(context);
            case STASH_EFFECT -> SpellDelivery.castStashEffect(context);
        }

        clearCrossbowCharge();
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
        Spell s = spell.entry().value();

        if (s.school == null || s.school.archetype == null) {
            return true;
        }

        return s.school.archetype == net.spell_power.api.SpellSchool.Archetype.ARCHERY;
    }

    public void resetSpellState() {
        super.resetSpellState();
        spellCastState = SpellCastState.NONE;
        guard.setCastingSpell(false);
    }

    private boolean isHoldingCrossbow() {
        return mob.isHolding(is -> is.getItem() instanceof CrossbowItem);
    }

    private Hand getCrossbowHand() {
        return GuardVillagers.getHandWith(mob, item -> item instanceof CrossbowItem);
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