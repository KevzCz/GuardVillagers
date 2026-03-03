package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.intprovider.UniformIntProvider;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public abstract class BaseZoneGoal extends Goal {
    protected static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);
    protected static final float DEFAULT_ZONE_RADIUS = 5.0F;

    protected final GuardEntity guard;
    protected final double speed;
    protected final double searchRange;

    protected MarkerEntity zoneRef;
    protected float zoneRadius;
    protected UUID zoneId;
    protected Vec3d zoneAnchor;

    protected int updatePathDelay;
    protected int stuckTicks;
    protected int progressCheckTick;
    protected Vec3d progressPos;

    public BaseZoneGoal(GuardEntity guard, double speed, double searchRange) {
        this.guard = guard;
        this.speed = speed;
        this.searchRange = searchRange;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    protected void resetTracking() {
        updatePathDelay = 0;
        stuckTicks = 0;
        progressCheckTick = 0;
        progressPos = guard.getPos();
        zoneRef = null;
        zoneAnchor = null;
        zoneId = null;
    }

    protected boolean findAndCacheZone() {
        if (zoneRef == null || !zoneRef.isAlive()) {
            MarkerEntity zone = HolyZoneHelper.findNearby(guard, searchRange);
            if (zone == null) return false;

            zoneRef = zone;
            zoneRadius = HolyZoneHelper.radiusOf(zone, DEFAULT_ZONE_RADIUS);
            zoneId = zone.getUuid();
            zoneAnchor = null;
            return true;
        }
        return true;
    }

    protected boolean isInsideZone() {
        return zoneRef != null && HolyZoneHelper.inside(guard, zoneRef, zoneRadius);
    }

    protected boolean moveTowardsZone() {
        if (--updatePathDelay <= 0) {
            HolyZoneHelper.steerTowardsIfOutside(guard, zoneRef, zoneRadius, speed);
            updatePathDelay = PATH_DELAY.get(guard.getRandom());
            return true;
        }
        return false;
    }

    protected boolean moveToPosition(Vec3d pos) {
        if (--updatePathDelay <= 0) {
            guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, speed);
            updatePathDelay = PATH_DELAY.get(guard.getRandom());
            return true;
        }
        return false;
    }

    protected void lookAtTarget(LivingEntity target) {
        if (target != null) {
            guard.lookAtEntity(target, 30.0F, 30.0F);
        } else if (zoneRef != null) {
            Vec3d center = zoneRef.getPos();
            guard.getLookControl().lookAt(center.x, center.y, center.z);
        }
    }

    protected void progressWatchdog() {
        int age = guard.age;

        if (progressCheckTick == 0) {
            progressCheckTick = age;
            progressPos = guard.getPos();
            return;
        }

        if (age - progressCheckTick >= 20) {
            double moved2 = guard.getPos().squaredDistanceTo(progressPos);
            if (moved2 < 0.05D) {
                stuckTicks += 20;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 10);
            }
            progressCheckTick = age;
            progressPos = guard.getPos();
        }

        if (stuckTicks > 80) {
            onProgressWatchdogTimeout();
        }
    }

    protected void onProgressWatchdogTimeout() {
        guard.getNavigation().stop();
    }

    protected Vec3d computeBestAnchor(MarkerEntity zone, float radius) {
        Vec3d center = zone.getPos();
        double anchorY = guard.getY();
        double margin = 0.75D;

        Vec3d[] seeds = {
                new Vec3d(center.x, anchorY, center.z),
                new Vec3d(center.x + 0.75, anchorY, center.z),
                new Vec3d(center.x - 0.75, anchorY, center.z),
                new Vec3d(center.x, anchorY, center.z + 0.75),
                new Vec3d(center.x, anchorY, center.z - 0.75),
                new Vec3d(center.x + 1.25, anchorY, center.z + 0.25),
                new Vec3d(center.x - 1.25, anchorY, center.z - 0.25),
                new Vec3d(center.x + 0.25, anchorY, center.z - 1.25),
                new Vec3d(center.x - 0.25, anchorY, center.z + 1.25)
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

        if (best != null) return best;

        for (int ring = 1; ring <= 3; ring++) {
            double step = 0.75D;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;

                    Vec3d p = new Vec3d(
                            center.x + dx * step,
                            anchorY,
                            center.z + dz * step
                    );

                    if (center.squaredDistanceTo(p) > maxR2) continue;
                    if (!isSpotFree(p)) continue;

                    double d2 = center.squaredDistanceTo(p);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = p;
                    }
                }
            }
            if (best != null) break;
        }

        return best;
    }

    protected boolean isSpotFree(Vec3d pos) {
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