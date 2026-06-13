package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.intprovider.UniformIntProvider;

public final class CombatMovementHelper {
    private static final UniformIntProvider PATHFINDING_DELAY_RANGE = TimeHelper.betweenSeconds(1, 2);

    private CombatMovementHelper() {}

    public static MovementResult applyRangedCombatMovement(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int cooldownTicks,
            boolean inAimPhase,
            boolean canRun
    ) {
        if (guard.isCastingMeleeSpell()) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            guard.getLookControl().lookAt(target, 30.0F, 30.0F);
            return new MovementResult(seeTime, updatePathDelay);
        }

        var zone = HolyZoneHelper.findNearby(guard, 32.0);
        if (zone != null) {
            float r = HolyZoneHelper.radiusOf(zone, 5.0F);

            int s = seeTime;
            boolean hadSight = s > 0;
            if (canSee != hadSight) s = 0;
            s = canSee ? (s + 1) : (s - 1);

            if (!HolyZoneHelper.inside(guard, zone, r)) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zone, r, canRun ? 1.15D : 0.8D);
                guard.lookAtEntity(target, 30.0F, 30.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                HolyZoneHelper.debugLog(guard, "applyRangedCombatMovement: RETURN to zone");
                return new MovementResult(s, updatePathDelay);
            } else {
                HolyZoneHelper.stopInside(guard, zone, r);
                HolyZoneHelper.softLeashInside(guard, zone, r);
                guard.lookAtEntity(target, 30.0F, 30.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                HolyZoneHelper.debugLog(guard, "applyRangedCombatMovement: HOLD in zone");
                return new MovementResult(s, updatePathDelay);
            }
        }

        int s = seeTime;
        boolean hasSeenRecently = s > 0;
        if (canSee != hasSeenRecently) s = 0;
        if (canSee) ++s; else --s;

        if (guard.getSpellManager().shouldUseProjectileCasting()
                && !guard.getSpellManager().hasCastablePhysicalMeleeSpell()
                && !SpellbladeCombatHelper.isActive(guard)) {
            var backline = SupportBacklineHelper.desiredBacklinePosition(guard, target);
            if (backline.isPresent()) {
                var pos = backline.get();
                int upd = updatePathDelay;
                if (guard.squaredDistanceTo(pos.x, pos.y, pos.z) > 4.0D) {
                    --upd;
                    if (upd <= 0) {
                        guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, canRun ? 1.0D : 0.5D);
                        upd = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
                    }
                } else {
                    guard.getNavigation().stop();
                }
                guard.lookAtEntity(target, 30.0F, 30.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                return new MovementResult(s, upd);
            }
        }

        double distanceSq = guard.squaredDistanceTo(target);
        float forward = 0.0F;
        float sideways = 0.0F;

        if (inAimPhase) {
            if (guard.getRandom().nextInt(10) == 0) {
                sideways = guard.getRandom().nextBoolean() ? 0.5F : -0.5F;
            }
            forward = guard.isUsingItem() ? -0.5F : -0.1F;
            guard.getMoveControl().strafeTo(sideways, forward);
        }

        if (distanceSq <= 4.0D) {
            guard.getMoveControl().strafeTo(guard.isUsingItem() ? -0.5F : -3.0F, 0.0F);
        }

        if (guard.getRandom().nextInt(50) == 0) {
            guard.setPose(guard.getPose() == EntityPose.STANDING ? EntityPose.CROUCHING : EntityPose.STANDING);
        }

        boolean needsToMove = (distanceSq > 16.0F * 16.0F || s < 5) && cooldownTicks == 0;
        int upd = updatePathDelay;

        if (needsToMove) {
            --upd;
            if (upd <= 0) {
                guard.getNavigation().startMovingTo(target, canRun ? 1.0D : 0.5D);
                upd = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
            }
        } else {
            upd = 0;
            guard.getNavigation().stop();
        }

        guard.lookAtEntity(target, 30.0F, 30.0F);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);

        return new MovementResult(s, upd);
    }

    public static MovementResult applyStaffDefensiveMovement(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay
    ) {
        var zone = HolyZoneHelper.findNearby(guard, 32.0);
        if (zone != null) {
            float r = HolyZoneHelper.radiusOf(zone, 5.0F);

            int s = seeTime;
            boolean hadSight = s > 0;
            if (canSee != hadSight) {
                s = 0;
            }
            s = canSee ? (s + 1) : (s - 1);

            if (!HolyZoneHelper.inside(guard, zone, r)) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zone, r, 0.8D);
            } else {
                HolyZoneHelper.stopInside(guard, zone, r);
                HolyZoneHelper.softLeashInside(guard, zone, r);
            }
            guard.lookAtEntity(target, 30.0F, 30.0F);
            guard.getLookControl().lookAt(target, 30.0F, 30.0F);
            return new MovementResult(s, updatePathDelay);
        }

        int s = seeTime;
        boolean hasSeenRecently = s > 0;
        if (canSee != hasSeenRecently) {
            s = 0;
        }
        if (canSee) {
            ++s;
        } else {
            --s;
        }

        var backline = SupportBacklineHelper.desiredBacklinePosition(guard, target);
        if (backline.isPresent()) {
            var pos = backline.get();
            int upd = updatePathDelay;
            if (guard.squaredDistanceTo(pos.x, pos.y, pos.z) > 4.0D) {
                --upd;
                if (upd <= 0) {
                    guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, 0.8D);
                    upd = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
                }
            } else {
                guard.getNavigation().stop();
            }
            guard.lookAtEntity(target, 30.0F, 30.0F);
            guard.getLookControl().lookAt(target, 30.0F, 30.0F);
            return new MovementResult(s, upd);
        }

        double distanceSq = guard.squaredDistanceTo(target);
        guard.getNavigation().stop();
        if (distanceSq <= 4.0D) {
            guard.getMoveControl().strafeTo(-3.0F, guard.getRandom().nextBoolean() ? 0.3F : -0.3F);
        } else if (distanceSq <= 16.0D * 16.0D) {
            float sideways = guard.getRandom().nextBoolean() ? 0.5F : -0.5F;
            guard.getMoveControl().strafeTo(-0.2F, sideways);
        } else {
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
        }

        guard.lookAtEntity(target, 30.0F, 30.0F);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);
        return new MovementResult(s, 0);
    }

    public static MovementResult applyRangedCombatMovement(
            MobEntity mob,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int cooldownTicks,
            boolean inAimPhase,
            boolean canRun,
            double speedModifier,
            float attackRadius
    ) {
        if (mob instanceof GuardEntity guard) {
            var zone = HolyZoneHelper.findNearby(guard, 32.0);
            if (zone != null) {
                float r = HolyZoneHelper.radiusOf(zone, 5.0F);
                if (!HolyZoneHelper.inside(guard, zone, r)) {
                    HolyZoneHelper.steerTowardsIfOutside(guard, zone, r, canRun ? speedModifier : speedModifier * 0.5D);
                    HolyZoneHelper.debugLog(guard, "applyRangedCombatMovement: RETURN to zone");
                    return new MovementResult(seeTime, updatePathDelay);
                } else {
                    HolyZoneHelper.stopInside(guard, zone, r);
                    HolyZoneHelper.softLeashInside(guard, zone, r);
                    HolyZoneHelper.debugLog(guard, "applyRangedCombatMovement: RETURN to zone");
                    return new MovementResult(seeTime, updatePathDelay);
                }
            }
        }

        int s = seeTime;
        boolean hadSight = s > 0;
        if (canSee != hadSight) s = 0;
        if (canSee) ++s; else --s;

        double distanceSq = mob.squaredDistanceTo(target);

        if (distanceSq <= 4.0D) {
            mob.getMoveControl().strafeTo(mob.isUsingItem() ? -0.5F : -3.0F, 0.0F);
        }

        if (mob.getRandom().nextInt(50) == 0) {
            mob.setPose(mob.getPose() == EntityPose.STANDING ? EntityPose.CROUCHING : EntityPose.STANDING);
        }

        boolean needsToMove = (distanceSq > attackRadius * attackRadius || s < 5) && cooldownTicks == 0;
        int upd = updatePathDelay;

        if (needsToMove) {
            --upd;
            if (upd <= 0) {
                mob.getNavigation().startMovingTo(target, canRun ? speedModifier : speedModifier * 0.5D);
                upd = PATHFINDING_DELAY_RANGE.get(mob.getRandom());
            }
        } else {
            upd = 0;
            mob.getNavigation().stop();
        }

        mob.lookAtEntity(target, 30.0F, 30.0F);
        mob.getLookControl().lookAt(target, 30.0F, 30.0F);

        return new MovementResult(s, upd);
    }

    public static BowMovementResult applyBowOrbitMovement(
            MobEntity actor,
            LivingEntity target,
            double speed,
            float squaredRange,
            int targetSeeingTicker,
            int combatTicks,
            boolean movingToLeft,
            boolean backward
    ) {
        if (actor instanceof GuardEntity guard) {
            if (guard.isCastingSpell() || guard.isCastingMeleeSpell()) {
                actor.getNavigation().stop();
                actor.getMoveControl().strafeTo(0.0F, 0.0F);
                actor.getLookControl().lookAt(target, 30.0F, 30.0F);
                return new BowMovementResult(targetSeeingTicker, combatTicks, movingToLeft, backward);
            }

            var zone = HolyZoneHelper.findNearby(guard, 32.0);
            if (zone != null) {
                float r = HolyZoneHelper.radiusOf(zone, 5.0F);
                if (!HolyZoneHelper.inside(guard, zone, r)) {
                    HolyZoneHelper.steerTowardsIfOutside(guard, zone, r, speed);
                    HolyZoneHelper.debugLog(guard, "applyBowOrbitMovement: RETURN to zone");
                    return new BowMovementResult(targetSeeingTicker, combatTicks, movingToLeft, backward);
                } else {
                    HolyZoneHelper.stopInside(guard, zone, r);
                    HolyZoneHelper.softLeashInside(guard, zone, r);
                    HolyZoneHelper.debugLog(guard, "applyBowOrbitMovement: RETURN to zone");
                    return new BowMovementResult(targetSeeingTicker, combatTicks, movingToLeft, backward);
                }
            }
        }

        int s = targetSeeingTicker;
        double distanceSq = actor.squaredDistanceTo(target);
        boolean canSee = actor.getVisibilityCache().canSee(target);
        boolean hasSeenRecently = s > 0;

        if (canSee != hasSeenRecently) s = 0;
        s = canSee ? (s + 1) : (s - 1);

        if (distanceSq > squaredRange || s < 20) {
            actor.getNavigation().startMovingTo(target, speed);
            combatTicks = -1;
        } else {
            actor.getNavigation().stop();
            combatTicks++;
        }

        if (combatTicks >= 20) {
            if (actor.getRandom().nextFloat() < 0.3f) movingToLeft = !movingToLeft;
            if (actor.getRandom().nextFloat() < 0.3f) backward = !backward;
            combatTicks = 0;
        }

        if (combatTicks > -1) {
            if (distanceSq > squaredRange * 0.75f) {
                backward = false;
            } else if (distanceSq < squaredRange * 0.25f) {
                backward = true;
            }
            actor.getMoveControl().strafeTo(backward ? -0.5f : 0.5f, movingToLeft ? 0.5f : -0.5f);
        }

        actor.lookAtEntity(target, 30.0f, 30.0f);
        actor.getLookControl().lookAt(target, 30.0f, 30.0f);

        return new BowMovementResult(s, combatTicks, movingToLeft, backward);
    }

    public static MeleeMovementResult applyMeleeCombatMovement(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int strafeCooldown,
            boolean strafeLeft,
            boolean inCastPhase,
            float meleeRange
    ) {
        return applyMeleeCombatMovement(
                guard, target, canSee, seeTime, updatePathDelay,
                strafeCooldown, strafeLeft, inCastPhase, meleeRange, 1.0f);
    }

    public static MeleeMovementResult applyMeleeCombatMovement(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int strafeCooldown,
            boolean strafeLeft,
            boolean inCastPhase,
            float meleeRange,
            float channelMovementSpeed
    ) {
        var zone = HolyZoneHelper.findNearby(guard, 32.0);
        if (zone != null) {
            float r = HolyZoneHelper.radiusOf(zone, 5.0F);
            if (!HolyZoneHelper.inside(guard, zone, r)) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zone, r, 1.2D);
                guard.lookAtEntity(target, 30.0F, 30.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                HolyZoneHelper.debugLog(guard, "applyMeleeCombatMovement: RETURN to zone");
                return new MeleeMovementResult(seeTime, updatePathDelay, strafeCooldown, strafeLeft);
            } else {
                HolyZoneHelper.stopInside(guard, zone, r);
                HolyZoneHelper.softLeashInside(guard, zone, r);
                guard.lookAtEntity(target, 30.0F, 30.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                HolyZoneHelper.debugLog(guard, "applyMeleeCombatMovement: HOLD in zone");
                return new MeleeMovementResult(seeTime, updatePathDelay, strafeCooldown, strafeLeft);
            }
        }

        int s = seeTime;
        boolean hadSight = s > 0;
        if (canSee != hadSight) s = 0;
        if (canSee) ++s; else --s;

        double distSq = guard.squaredDistanceTo(target);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);

        int upd = updatePathDelay;
        int strafeCd = strafeCooldown;
        boolean strafeDir = strafeLeft;

        if (inCastPhase) {
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            if (channelMovementSpeed <= 0f) {
                guard.getNavigation().stop();
                return new MeleeMovementResult(s, upd, strafeCd, strafeDir);
            }
            float moveScale = Math.max(1.0f, channelMovementSpeed);
            if (moveScale > 1.0f && distSq > meleeRange * meleeRange * 0.25) {
                guard.getNavigation().startMovingTo(target, moveScale * 0.55D);
            } else {
                guard.getNavigation().stop();
            }
            return new MeleeMovementResult(s, upd, strafeCd, strafeDir);
        }

        boolean needsToApproach = distSq > meleeRange * meleeRange || s < 5;

        if (needsToApproach) {
            --upd;
            if (upd <= 0) {
                guard.getNavigation().startMovingTo(target, 1.2D);
                upd = 4 + guard.getRandom().nextInt(7);
            }
        } else {
            upd = 0;
            guard.getNavigation().stop();

            if (--strafeCd <= 0) {
                if (guard.getRandom().nextInt(10) == 0) {
                    strafeDir = !strafeDir;
                }
                strafeCd = 20 + guard.getRandom().nextInt(20);
            }

            float forward = 0.0F;
            if (distSq < 2.0D) {
                forward = -0.3F;
            } else if (distSq > 3.5D * 3.5D) {
                forward = 0.3F;
            }

            guard.getMoveControl().strafeTo(forward, strafeDir ? 0.4F : -0.4F);
        }

        return new MeleeMovementResult(s, upd, strafeCd, strafeDir);
    }

    public static MeleeMovementResult applySpellbladeMovement(
            GuardEntity guard,
            LivingEntity target,
            SpellbladeCombatHelper.Action action,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int strafeCooldown,
            boolean strafeLeft,
            boolean inCastPhase
    ) {
        if (inCastPhase) {
            guard.getLookControl().lookAt(target, 30.0F, 30.0F);
            if (action == SpellbladeCombatHelper.Action.MELEE_SPELL) {
                return applyMeleeCombatMovement(
                        guard, target, canSee, seeTime, updatePathDelay,
                        strafeCooldown, strafeLeft, true, SpellbladeCombatHelper.MELEE_SPELL_RANGE);
            }
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            return new MeleeMovementResult(seeTime, updatePathDelay, strafeCooldown, strafeLeft);
        }

        return switch (action) {
            case MELEE_SPELL, MELEE_DUEL, PURSUE, WAIT -> applySpellbladeMeleeChase(
                    guard, target, canSee, seeTime, strafeCooldown, strafeLeft);
            case OPEN_FOR_MAGIC -> applySpellbladeOpenForMagic(
                    guard, target, seeTime, updatePathDelay, strafeCooldown, strafeLeft);
            case MAGIC_SPELL -> applySpellbladeMagicApproach(
                    guard, target, canSee, seeTime, updatePathDelay, strafeCooldown, strafeLeft);
            case REPOSITION -> {
                guard.getMoveControl().strafeTo(0.0F, 0.0F);
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
                yield new MeleeMovementResult(seeTime, updatePathDelay, strafeCooldown, strafeLeft);
            }
            default -> applySpellbladeMeleeChase(
                    guard, target, canSee, seeTime, strafeCooldown, strafeLeft);
        };
    }

    private static MeleeMovementResult applySpellbladeMeleeChase(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int strafeCooldown,
            boolean strafeLeft
    ) {
        int s = seeTime;
        boolean hadSight = s > 0;
        if (canSee != hadSight) {
            s = 0;
        }
        if (canSee) {
            s++;
        } else {
            s--;
        }

        float dist = guard.distanceTo(target);
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);
        guard.getMoveControl().strafeTo(0.0F, 0.0F);

        boolean shouldClose = dist > SpellbladeCombatHelper.BLADE_RANGE + 0.35F || !canSee || s < 3;
        if (shouldClose) {
            guard.getNavigation().startMovingTo(target, 1.2D);
        } else {
            guard.getNavigation().stop();
        }

        return new MeleeMovementResult(s, 0, strafeCooldown, strafeLeft);
    }

    private static MeleeMovementResult applySpellbladeOpenForMagic(
            GuardEntity guard,
            LivingEntity target,
            int seeTime,
            int updatePathDelay,
            int strafeCooldown,
            boolean strafeLeft
    ) {
        double distSq = guard.squaredDistanceTo(target);
        float comfortMinSq = SpellbladeCombatHelper.MAGIC_COMFORT_MIN * SpellbladeCombatHelper.MAGIC_COMFORT_MIN;
        guard.getLookControl().lookAt(target, 30.0F, 30.0F);

        if (distSq < comfortMinSq) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(-0.55F, guard.getRandom().nextBoolean() ? 0.2F : -0.2F);
        } else {
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            int upd = updatePathDelay;
            --upd;
            if (upd <= 0) {
                guard.getNavigation().startMovingTo(target, 1.0D);
                upd = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
            }
            return new MeleeMovementResult(seeTime, upd, strafeCooldown, strafeLeft);
        }

        return new MeleeMovementResult(seeTime, updatePathDelay, strafeCooldown, strafeLeft);
    }

    private static MeleeMovementResult applySpellbladeMagicApproach(
            GuardEntity guard,
            LivingEntity target,
            boolean canSee,
            int seeTime,
            int updatePathDelay,
            int strafeCooldown,
            boolean strafeLeft
    ) {
        int s = seeTime;
        boolean hadSight = s > 0;
        if (canSee != hadSight) {
            s = 0;
        }
        if (canSee) {
            s++;
        } else {
            s--;
        }

        double distSq = guard.squaredDistanceTo(target);
        float comfortMinSq = SpellbladeCombatHelper.MAGIC_COMFORT_MIN * SpellbladeCombatHelper.MAGIC_COMFORT_MIN;
        float comfortMaxSq = SpellbladeCombatHelper.MAGIC_COMFORT_MAX * SpellbladeCombatHelper.MAGIC_COMFORT_MAX;
        int upd = updatePathDelay;

        if (distSq < comfortMinSq) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(-0.45F, 0.0F);
        } else if (distSq > comfortMaxSq || s < 5) {
            --upd;
            if (upd <= 0) {
                guard.getNavigation().startMovingTo(target, 1.0D);
                upd = PATHFINDING_DELAY_RANGE.get(guard.getRandom());
            }
        } else {
            upd = 0;
            guard.getNavigation().stop();
        }

        guard.getLookControl().lookAt(target, 30.0F, 30.0F);
        return new MeleeMovementResult(s, upd, strafeCooldown, strafeLeft);
    }

    public record MovementResult(int seeTime, int updatePathDelay) {}
    public record BowMovementResult(int targetSeeingTicker, int combatTicks, boolean movingToLeft, boolean backward) {}
    public record MeleeMovementResult(int seeTime, int updatePathDelay, int strafeCooldown, boolean strafeLeft) {}
}
