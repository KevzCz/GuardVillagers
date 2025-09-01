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
        /* ----- /HOLY ZONE OVERRIDE ----- */


        int s = seeTime;
        boolean hasSeenRecently = s > 0;
        if (canSee != hasSeenRecently) s = 0;
        if (canSee) ++s; else --s;

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
        // apply override only for GuardEntity
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

    public record MovementResult(int seeTime, int updatePathDelay) {}
    public record BowMovementResult(int targetSeeingTicker, int combatTicks, boolean movingToLeft, boolean backward) {}
}
