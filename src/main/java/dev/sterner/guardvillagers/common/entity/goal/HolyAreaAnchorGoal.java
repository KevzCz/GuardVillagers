package dev.sterner.guardvillagers.common.entity.goal;

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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public class HolyAreaAnchorGoal extends Goal {
    private static final double DEFAULT_SEARCH_RANGE = 32.0D;
    private static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    private final GuardEntity guard;
    private final double speed;
    private final double searchRange;
    private final Predicate<GuardEntity> shouldRun;

    private MarkerEntity zoneRef;
    private float zoneRadius;
    private UUID zoneId;

    private boolean anchoredForThisZone;
    private boolean computedAnchor;
    private Vec3d anchorPos;

    private int updatePathDelay;
    private int stuckTicks;
    private int lastProgressCheckTick;
    private Vec3d lastProgressPos;

    public HolyAreaAnchorGoal(GuardEntity guard, double speed, double searchRange, Predicate<GuardEntity> shouldRun) {
        this.guard = guard;
        this.speed = speed;
        this.searchRange = searchRange;
        this.shouldRun = shouldRun;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public HolyAreaAnchorGoal(GuardEntity guard, double speed, Predicate<GuardEntity> shouldRun) {
        this(guard, speed, DEFAULT_SEARCH_RANGE, shouldRun);
    }

    @Override
    public boolean canStart() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (shouldRun != null && !shouldRun.test(guard)) return false;

        MarkerEntity zone = HolyZoneHelper.findNearby(guard, this.searchRange);
        if (zone == null) return false;

        UUID id = zone.getUuid();
        if (zoneId != null && Objects.equals(zoneId, id) && anchoredForThisZone) return false;

        zoneRef = zone;
        zoneRadius = HolyZoneHelper.radiusOf(zone, 5.0F);
        zoneId = id;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (zoneRef == null || !zoneRef.isAlive()) return false;
        if (anchoredForThisZone) return false;
        return true;
    }

    @Override
    public void start() {
        updatePathDelay = 0;
        stuckTicks = 0;
        computedAnchor = false;
        anchorPos = null;
        lastProgressCheckTick = 0;
        lastProgressPos = guard.getPos();
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (zoneRef == null || !zoneRef.isAlive()) {
            anchoredForThisZone = true;
            return;
        }

        boolean inside = HolyZoneHelper.inside(guard, zoneRef, zoneRadius);

        if (!inside) {
            if (--updatePathDelay <= 0) {
                HolyZoneHelper.steerTowardsIfOutside(guard, zoneRef, zoneRadius, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
            LivingEntity tgt = guard.getTarget();
            if (tgt != null) {
                guard.lookAtEntity(tgt, 30.0F, 30.0F);
            } else {
                Vec3d c = zoneRef.getPos();
                guard.getLookControl().lookAt(c.x, c.y, c.z);
            }
            progressWatchdog();
            return;
        }

        if (!computedAnchor) {
            anchorPos = computeBestAnchor(zoneRef, zoneRadius);
            computedAnchor = true;
            if (anchorPos != null) {
                guard.getNavigation().startMovingTo(anchorPos.x, anchorPos.y, anchorPos.z, speed);
            } else {
                anchoredForThisZone = true;
                guard.getNavigation().stop();
                return;
            }
        }

        if (anchorPos != null) {
            double dist2 = guard.getPos().squaredDistanceTo(anchorPos);
            if (dist2 <= 1.0D) {
                anchoredForThisZone = true;
                guard.getNavigation().stop();
                return;
            }

            if (--updatePathDelay <= 0) {
                guard.getNavigation().startMovingTo(anchorPos.x, anchorPos.y, anchorPos.z, speed);
                updatePathDelay = PATH_DELAY.get(guard.getRandom());
            }
            progressWatchdog();
        }
    }

    private void progressWatchdog() {
        int age = guard.age;
        if (lastProgressCheckTick == 0) {
            lastProgressCheckTick = age;
            lastProgressPos = guard.getPos();
            return;
        }
        if (age - lastProgressCheckTick >= 20) {
            double moved2 = guard.getPos().squaredDistanceTo(lastProgressPos);
            if (moved2 < 0.05D) {
                stuckTicks += 20;
            } else {
                stuckTicks = Math.max(0, stuckTicks - 10);
            }
            lastProgressCheckTick = age;
            lastProgressPos = guard.getPos();
        }
        if (stuckTicks > 80) {
            anchoredForThisZone = true;
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

    public static Predicate<GuardEntity> rangedOrCaster() {
        return g -> {
            String key = g.getMainHandStack().getItem().getTranslationKey();
            boolean bowLike = key.contains("bow") || key.contains("crossbow");
            boolean wandLike = key.contains("wand_") || key.contains("staff_");
            boolean hasHoly = g.isHoldingHolyFocus();
            return bowLike || wandLike || hasHoly;
        };
    }
}
