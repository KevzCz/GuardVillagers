package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class MeleeRetreatForHealingGoal extends Goal {

    private int shieldHoldTicks = 0;
    private static final float TRIGGER_HP_FRACTION = 0.40F;
    private static final float RELEASE_HP_FRACTION = 0.70F;
    private static final double PRIEST_SEARCH_RANGE = 24.0D;
    private static final double ZONE_SEARCH_RANGE   = 32.0D;
    private static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    private final GuardEntity guard;
    private final double speed;

    private int updatePathDelay;
    private int poseToggleDelay;
    private int progressCheckTick;
    private Vec3d progressPos;
    private int stuckTicks;

    private MarkerEntity zoneRef;
    private float zoneRadius;
    private UUID zoneId;
    private Vec3d zoneAnchor;

    public MeleeRetreatForHealingGoal(GuardEntity guard, double speed) {
        this.guard = guard;
        this.speed = speed;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isCastingSpell() || guard.isEating()) return false;
        if (hasFoodInOffhand()) return false;

        float hpFrac = guard.getHealth() / guard.getMaxHealth();
        if (hpFrac > TRIGGER_HP_FRACTION) return false;

        return findNearestPriest(PRIEST_SEARCH_RANGE) != null;
    }

    @Override
    public boolean shouldContinue() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isCastingSpell()) return false;

        float hpFrac = guard.getHealth() / guard.getMaxHealth();
        if (hpFrac >= RELEASE_HP_FRACTION) return false;

        return findNearestPriest(PRIEST_SEARCH_RANGE) != null;
    }

    @Override
    public void start() {
        updatePathDelay = 0;
        poseToggleDelay = 0;
        stuckTicks = 0;
        progressCheckTick = 0;
        progressPos = guard.getPos();
        zoneRef = null;
        zoneAnchor = null;
        zoneId = null;
        shieldHoldTicks = 0;
    }


    @Override
    public void stop() {
        guard.getNavigation().stop();
        guard.stopUsingItem();
        if (guard.getPose() == EntityPose.CROUCHING) {
            guard.setPose(EntityPose.STANDING);
        }
    }


    @Override
    public void tick() {
        togglePoseOccasionally();

        boolean handledByZone = retreatIntoHolyZone();
        if (handledByZone) {
            boolean movingToZone = zoneRef != null && !HolyZoneHelper.inside(guard, zoneRef, zoneRadius);
            boolean notAnchored  = zoneRef != null && !movingToZone &&
                    (zoneAnchor == null || guard.getPos().squaredDistanceTo(zoneAnchor) > 1.5D);

            boolean enemyThreat = false;
            LivingEntity enemy = guard.getTarget();
            if (enemy != null && enemy.isAlive()) {
                enemyThreat = guard.getVisibilityCache().canSee(enemy) &&
                        guard.squaredDistanceTo(enemy) < (12.0D * 12.0D);
            }

            boolean shouldRaise = movingToZone || notAnchored || enemyThreat;
            updateShield(shouldRaise);
            watchdogProgress();
            return;
        }

        GuardEntity priest = findNearestPriest(PRIEST_SEARCH_RANGE);
        if (priest != null) {
            if (--updatePathDelay <= 0) {
                guard.getNavigation().startMovingTo(priest, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
            guard.lookAtEntity(priest, 30.0F, 30.0F);

            boolean enemyThreat = false;
            LivingEntity enemy = guard.getTarget();
            if (enemy != null && enemy.isAlive()) {
                enemyThreat = guard.getVisibilityCache().canSee(enemy) &&
                        guard.squaredDistanceTo(enemy) < (12.0D * 12.0D);
            }
            updateShield(true /* moving toward priest */ || enemyThreat);

            watchdogProgress();
            return;
        }

        LivingEntity enemy = guard.getTarget();
        Vec3d pos = (enemy != null)
                ? NoPenaltyTargeting.findFrom(guard, 16, 7, enemy.getPos())
                : NoPenaltyTargeting.find(guard, 16, 7);

        if (pos != null) {
            if (--updatePathDelay <= 0) {
                guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
            updateShield(true);
        } else {
            guard.getNavigation().stop();
            updateShield(false);
        }

        if (enemy != null) {
            guard.lookAtEntity(enemy, 30.0F, 30.0F);
        }
        watchdogProgress();
    }


    private boolean retreatIntoHolyZone() {
        if (zoneRef == null || !zoneRef.isAlive()) {
            MarkerEntity z = HolyZoneHelper.findNearby(guard, ZONE_SEARCH_RANGE);
            if (z == null) return false;
            zoneRef = z;
            zoneRadius = HolyZoneHelper.radiusOf(z, 5.0F);
            zoneId = z.getUuid();
            zoneAnchor = null;
        }

        boolean inside = HolyZoneHelper.inside(guard, zoneRef, zoneRadius);
        if (!inside) {
            if (--updatePathDelay <= 0) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zoneRef, zoneRadius, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
            return true;
        }

        if (zoneAnchor == null) {
            zoneAnchor = computeBestAnchor(zoneRef, zoneRadius);
            if (zoneAnchor == null) {
                return true;
            }
        }

        double d2 = guard.getPos().squaredDistanceTo(zoneAnchor);
        if (d2 > 1.0D) {
            if (--updatePathDelay <= 0) {
                guard.getNavigation().startMovingTo(zoneAnchor.x, zoneAnchor.y, zoneAnchor.z, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
        } else {
            guard.getNavigation().stop();
        }
        return true;
    }

    private void updateShield(boolean raise) {
        ItemStack off = guard.getOffHandStack();
        if (!off.isOf(Items.SHIELD)) return;

        if (raise) {
            if (guard.isCastingSpell()) guard.setCastingSpell(false);

            if (!guard.isUsingItem() || guard.getActiveHand() != Hand.OFF_HAND) {
                guard.setCurrentHand(Hand.OFF_HAND);
            }
        } else {
            if (guard.isUsingItem() && guard.getActiveHand() == Hand.OFF_HAND) {
                guard.stopUsingItem();
            }
        }
    }



    private void togglePoseOccasionally() {
        if (--poseToggleDelay <= 0) {
            poseToggleDelay = 50 + guard.getRandom().nextInt(20);
            guard.setPose(guard.getPose() == EntityPose.STANDING ? EntityPose.CROUCHING : EntityPose.STANDING);
        }
    }

    private GuardEntity findNearestPriest(double range) {
        Box box = guard.getBoundingBox().expand(range);
        List<GuardEntity> guards = guard.getWorld().getEntitiesByClass(
                GuardEntity.class, box,
                g -> g != guard && g.isAlive() && isHealer(g)
        );
        if (guards.isEmpty()) return null;

        return guards.stream()
                .min(Comparator.comparingDouble(g -> g.squaredDistanceTo(guard)))
                .orElse(null);
    }

    private boolean isHealer(GuardEntity g) {
        if (g.isHoldingHolyFocus()) return true;
        String skill = g.getHolySkill();
        return skill != null && !skill.isEmpty() &&
                (skill.contains("heal") || skill.contains("barrier") || skill.contains("circle"));
    }

    private boolean hasFoodInOffhand() {
        ItemStack off = guard.getOffHandStack();
        return off.get(DataComponentTypes.FOOD) != null;
    }

    private void watchdogProgress() {
        int age = guard.age;
        if (progressCheckTick == 0) {
            progressCheckTick = age;
            progressPos = guard.getPos();
            return;
        }
        if (age - progressCheckTick >= 20) {
            double moved2 = guard.getPos().squaredDistanceTo(progressPos);
            if (moved2 < 0.05D) stuckTicks += 20; else stuckTicks = Math.max(0, stuckTicks - 10);
            progressCheckTick = age;
            progressPos = guard.getPos();
        }
        if (stuckTicks > 80) {
            guard.getNavigation().stop();
        }
    }

    private Vec3d computeBestAnchor(MarkerEntity zone, float radius) {
        Vec3d center = zone.getPos();
        double anchorY = guard.getY();
        double margin = 0.75D;

        Vec3d[] seeds = new Vec3d[] {
                new Vec3d(center.x, anchorY, center.z),
                new Vec3d(center.x + 0.75, anchorY, center.z),
                new Vec3d(center.x - 0.75, anchorY, center.z),
                new Vec3d(center.x, anchorY, center.z + 0.75),
                new Vec3d(center.x, anchorY, center.z - 0.75)
        };

        double maxR2 = Math.max(0.0D, (radius - margin) * (radius - margin));
        Vec3d best = null;
        double bestD2 = Double.MAX_VALUE;

        for (Vec3d p : seeds) {
            if (center.squaredDistanceTo(p) > maxR2) continue;
            if (!isSpotFree(p)) continue;
            double d2 = center.squaredDistanceTo(p);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = p;
            }
        }
        return best;
    }

    private boolean isSpotFree(Vec3d pos) {
        double w = guard.getWidth();
        double h = guard.getHeight();
        Box box = new Box(
                pos.x - w * 0.5, pos.y, pos.z - w * 0.5,
                pos.x + w * 0.5, pos.y + h, pos.z + w * 0.5
        );

        if (!guard.getWorld().isSpaceEmpty(box)) return false;

        List<LivingEntity> others = guard.getWorld().getEntitiesByClass(
                LivingEntity.class,
                box.expand(0.15D, 0.0D, 0.15D),
                e -> e.isAlive() && e != guard
        );
        return others.isEmpty();
    }
}
