package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseZoneGoal;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.function.Predicate;

public class HolyAreaAnchorGoal extends BaseZoneGoal {
    private static final double DEFAULT_SEARCH_RANGE = 32.0D;

    private final Predicate<GuardEntity> shouldRun;
    private boolean anchoredForThisZone;
    private boolean computedAnchor;
    private Vec3d anchorPos;
    private java.util.UUID lastAnchoredZoneId;

    public HolyAreaAnchorGoal(GuardEntity guard, double speed, double searchRange, Predicate<GuardEntity> shouldRun) {
        super(guard, speed, searchRange);
        this.shouldRun = shouldRun;
    }

    public HolyAreaAnchorGoal(GuardEntity guard, double speed, Predicate<GuardEntity> shouldRun) {
        this(guard, speed, DEFAULT_SEARCH_RANGE, shouldRun);
    }

    @Override
    public boolean canStart() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isCastingSpell() || guard.isCastingMeleeSpell()) return false;
        if (shouldRun != null && !shouldRun.test(guard)) return false;

        if (!findAndCacheZone()) return false;

        return !Objects.equals(lastAnchoredZoneId, zoneRef.getUuid()) || !anchoredForThisZone;
    }

    @Override
    public boolean shouldContinue() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isCastingSpell() || guard.isCastingMeleeSpell()) return false;
        if (zoneRef == null || !zoneRef.isAlive()) return false;
        return !anchoredForThisZone;
    }

    @Override
    public void start() {
        resetTracking();
        computedAnchor = false;
        anchorPos = null;
        anchoredForThisZone = false;
        lastAnchoredZoneId = null;
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

        boolean inside = isInsideZone();

        if (!inside) {
            moveTowardsZone();
            lookAtTarget(guard.getTarget());
            progressWatchdog();
            return;
        }

        if (!computedAnchor) {
            anchorPos = computeBestAnchor(zoneRef, zoneRadius);
            computedAnchor = true;

            if (anchorPos == null) {
                anchoredForThisZone = true;
                lastAnchoredZoneId = zoneRef.getUuid();
                guard.getNavigation().stop();
                return;
            }

            guard.getNavigation().startMovingTo(anchorPos.x, anchorPos.y, anchorPos.z, speed);
        }

        if (anchorPos != null) {
            double dist2 = guard.getPos().squaredDistanceTo(anchorPos);

            if (dist2 <= 1.0D) {
                anchoredForThisZone = true;
                lastAnchoredZoneId = zoneRef.getUuid();
                guard.getNavigation().stop();
                return;
            }

            moveToPosition(anchorPos);
            progressWatchdog();
        }
    }

    @Override
    protected void onProgressWatchdogTimeout() {
        anchoredForThisZone = true;
        if (zoneRef != null) lastAnchoredZoneId = zoneRef.getUuid();
        super.onProgressWatchdogTimeout();
    }

    public static Predicate<GuardEntity> rangedOrCaster() {
        return g -> {
            var manager = g.getSpellManager();
            return manager.hasArcherySpells()
                    || manager.hasProjectileSpells()
                    || dev.sterner.guardvillagers.common.ai.GuardCombatRole.isDedicatedSupport(g);
        };
    }
}