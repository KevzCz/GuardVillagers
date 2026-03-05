package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.*;
import dev.sterner.guardvillagers.common.entity.*;
import dev.sterner.guardvillagers.common.entity.goal.spell.*;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.registry.entry.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.spell_engine.api.spell.*;
import net.spell_engine.api.spell.registry.*;

import java.util.*;

public class GuardCastSpellGoal extends BaseRangedSpellGoal {
    private static final float ATTACK_RADIUS = 16.0F;

    private final ConditionalSpellGoal conditionalSpells;

    private SpellState spellState = SpellState.UNCHARGED;
    private double wantedX;
    private double wantedY;
    private double wantedZ;

    private enum SpellState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        CASTING,
        FIND_NEW_POSITION
    }

    public GuardCastSpellGoal(GuardEntity guard) {
        super(guard, 1.0D, ATTACK_RADIUS);
        this.conditionalSpells = new ConditionalSpellGoal(guard);
        registerSpellConditions();
    }

    private void registerSpellConditions() {
        conditionalSpells.registerHealthThreshold("wizards:frost_shield", 0.3f);
    }

    @Override
    public boolean canStart() {
        if (!guard.getSpellManager().shouldUseProjectileCasting()) {
            return false;
        }

        if (!isInCombat()) {
            return false;
        }

        if (hasValidConditionalSpell()) {
            return true;
        }

        return isValidTarget();
    }

    @Override
    public boolean shouldContinue() {
        return (canStart() || !guard.getNavigation().isIdle() || spellState != SpellState.UNCHARGED)
                && guard.getSpellManager().shouldUseProjectileCasting();
    }

    @Override
    public void start() {
        super.start();
        this.spellState = SpellState.UNCHARGED;
    }

    @Override
    public void stop() {
        super.stop();
        this.spellState = SpellState.UNCHARGED;
        this.cachedSpellEntry = null;
    }

    @Override
    public void tick() {
        tickCooldowns();

        LivingEntity target = guard.getTarget();

        boolean inAimPhase = spellState == SpellState.CHARGING || spellState == SpellState.CHARGED;
        boolean canRun = spellState == SpellState.UNCHARGED;

        if (target != null && target.isAlive()) {
            boolean canSee = guard.getVisibilityCache().canSee(target);
            updateCombatMovement(target, inAimPhase, canRun);

            if (friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire) {
                this.spellState = SpellState.FIND_NEW_POSITION;
            }
        }

        switch (this.spellState) {
            case FIND_NEW_POSITION -> handleFindNewPosition();
            case UNCHARGED -> handleUncharged(target);
            case CHARGING -> handleCharging();
            case CHARGED -> handleCharged();
            case CASTING -> handleCasting(target);
        }
    }

    private void handleFindNewPosition() {
        guard.stopUsingItem();
        guard.setCastingSpell(false);

        if (findNewPosition()) {
            guard.getNavigation().startMovingTo(wantedX, wantedY, wantedZ,
                    guard.isSneaking() ? 0.5D : 1.2D);
        }

        this.spellState = SpellState.UNCHARGED;
    }

    private void handleUncharged(LivingEntity target) {
        Identifier spellId = getNextCastableSpell(target);
        if (spellId == null) return;

        Optional<RegistryEntry.Reference<Spell>> optEntry =
                SpellRegistry.from(guard.getWorld()).getEntry(spellId);

        if (optEntry.isEmpty()) return;

        cachedSpellEntry = optEntry.get();
        Spell spell = cachedSpellEntry.value();
        currentSpellId = spellId;
        windUpTicks = getWindUpTicks(spell);

        guard.setCurrentHand(Hand.MAIN_HAND);
        guard.setCastingSpell(true);

        this.spellState = SpellState.CHARGING;
    }

    private void handleCharging() {
        if (!guard.isUsingItem()) {
            guard.setCurrentHand(Hand.MAIN_HAND);
        }

        if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (--windUpTicks <= 0) {
            this.spellState = SpellState.CHARGED;
        }
    }

    private void handleCharged() {
        if (currentSpellId == null || isSpellOnCooldown(currentSpellId)) return;
        if (cachedSpellEntry == null) return;

        Spell spell = cachedSpellEntry.value();
        isChanneled = isSpellChanneled(spell);
        channelTicksLeft = getChannelDuration(spell);
        castingDelayTicks = 0;
        spellFired = false;

        spellState = SpellState.CASTING;
    }

    private void handleCasting(LivingEntity target) {
        if (currentSpellId == null || cachedSpellEntry == null) return;

        Spell spell = cachedSpellEntry.value();

        if (isChanneled) {
            handleChanneledCast(target, spell);
        } else {
            handleSingleCast(target, spell);
        }
    }

    private void handleChanneledCast(LivingEntity target, Spell spell) {
        if (channelTicksLeft > 0) {
            if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                guard.setActiveBeam(spell.target.beam);
                guard.setCastingSpell(true);

                if (target != null) {
                    guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                }
            }

            if (channelTicksLeft % 2 == 0) {
                spawnCastingParticles(spell);
            }

            if (channelTicksLeft == getChannelDuration(spell)) {
                castSpell(target, spell);
                castingDelayTicks = getChannelFireInterval(spell);
                channelTicksLeft--;
            } else {
                if (--castingDelayTicks <= 0) {
                    castSpell(target, spell);
                    castingDelayTicks = getChannelFireInterval(spell);
                }
                channelTicksLeft--;
            }
        } else {
            guard.setActiveBeam(null);
            finishCasting();
        }
    }

    private void finishCasting() {
        guard.stopUsingItem();
        guard.setActiveBeam(null);

        int baseCooldown = getCooldownTicks(cachedSpellEntry.value());
        spellCooldowns.put(currentSpellId, baseCooldown);

        spellState = SpellState.UNCHARGED;
        guard.setCastingSpell(false);
    }

    private void handleSingleCast(LivingEntity target, Spell spell) {
        if (!spellFired) {
            castSpell(target, spell);
            spellFired = true;
        } else {
            finishCasting();
        }
    }



    private void castSpell(LivingEntity target, Spell spell) {
        SpellContext context = createSpellContext(currentSpellId, cachedSpellEntry, target).build();

        if (spell.deliver == null) {
            SpellDelivery.castDirect(context);
            guard.swingHand(Hand.MAIN_HAND, true);
            return;
        }

        switch (spell.deliver.type) {
            case SHOOT_ARROW, PROJECTILE -> {
                int channelOffset = isChanneled ? (getChannelDuration(spell) - channelTicksLeft) : 0;
                SpellDelivery.castProjectile(context, channelOffset);
            }
            case METEOR -> SpellDelivery.castMeteor(context);
            case CLOUD -> SpellDelivery.castCloud(context);
            case CUSTOM -> SpellDelivery.castCustom(context);
            case DIRECT -> {
                if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                    SpellDelivery.castBeam(context);
                } else if (spell.target != null && spell.target.type == Spell.Target.Type.AREA) {
                    SpellDelivery.castAreaCone(context);
                } else {
                    SpellDelivery.castDirect(context);
                }
            }
            case AFFECT_ARROW -> SpellDelivery.castAffectArrow(context);
            case MELEE -> SpellDelivery.castDirect(context);
            case STASH_EFFECT -> SpellDelivery.castStashEffect(context);
        }

        guard.swingHand(Hand.MAIN_HAND, true);
    }

    private Identifier getNextCastableSpell(LivingEntity target) {
        var manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> supportSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.SUPPORT,
                        s -> conditionalSpells.canCastSpell(s.spellId())
                                && !isSpellOnCooldown(s.spellId())
                                && isArchetypeCompatibleWithStaff(s));

        if (supportSpell.isPresent()) {
            return supportSpell.get().spellId();
        }

        if (target == null || !target.isAlive()) {
            return null;
        }

        boolean canSee = guard.getVisibilityCache().canSee(target);
        if (!canSee) {
            return null;
        }

        Optional<GuardSpellManager.CategorizedSpell> areaSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.AREA,
                        s -> {
                            boolean isNotAlsoHealing = manager.getSpells(GuardSpellManager.SpellCategory.HEALING)
                                    .stream()
                                    .noneMatch(healing -> healing.spellId().equals(s.spellId()));

                            return isNotAlsoHealing
                                    && conditionalSpells.canCastSpell(s.spellId())
                                    && !isSpellOnCooldown(s.spellId())
                                    && isArchetypeCompatibleWithStaff(s);
                        });

        if (areaSpell.isPresent()) {
            return areaSpell.get().spellId();
        }

        Optional<GuardSpellManager.CategorizedSpell> projectileSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.PROJECTILE,
                        s -> conditionalSpells.canCastSpell(s.spellId())
                                && !isSpellOnCooldown(s.spellId())
                                && isArchetypeCompatibleWithStaff(s));

        return projectileSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean isArchetypeCompatibleWithStaff(GuardSpellManager.CategorizedSpell spell) {
        Spell s = spell.entry().value();

        if (s.school == null || s.school.archetype == null) {
            return true;
        }
        return s.school.archetype == net.spell_power.api.SpellSchool.Archetype.MAGIC;
    }

    private boolean hasValidConditionalSpell() {
        var manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> supportSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.SUPPORT,
                        s -> conditionalSpells.canCastSpell(s.spellId()) && !isSpellOnCooldown(s.spellId()));

        return supportSpell.isPresent();
    }

    private boolean isInCombat() {
        LivingEntity target = guard.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }

        return guard.isAttacking();
    }

    private boolean friendlyInLineOfSight() {
        List<Entity> nearby = guard.getWorld().getOtherEntities(guard, guard.getBoundingBox().expand(5.0D));

        for (Entity entity : nearby) {
            if (entity == guard.getTarget()) continue;

            boolean isFriendly = entity.getType() == EntityType.VILLAGER
                    || entity.getType() == dev.sterner.guardvillagers.GuardVillagers.GUARD_VILLAGER
                    || entity.getType() == EntityType.IRON_GOLEM
                    || entity == guard.getOwner();

            if (isFriendly && guard.canSee(entity) && guard.distanceTo(entity) <= 4.0D) {
                Vec3d toFriend = entity.getPos().subtract(guard.getPos()).normalize();
                Vec3d facing = guard.getRotationVector();
                if (facing.dotProduct(toFriend) > 0.9D) return true;
            }
        }

        return false;
    }

    private boolean findNewPosition() {
        Vec3d pos = getNewPosition();
        if (pos != null) {
            this.wantedX = pos.x;
            this.wantedY = pos.y;
            this.wantedZ = pos.z;
            return true;
        }
        return false;
    }

    private Vec3d getNewPosition() {
        return isValidTarget()
                ? NoPenaltyTargeting.findFrom(guard, 16, 7, guard.getTarget().getPos())
                : NoPenaltyTargeting.find(guard, 16, 7);
    }
}