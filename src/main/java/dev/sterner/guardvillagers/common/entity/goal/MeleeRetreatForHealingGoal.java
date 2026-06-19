package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.GuardCombatRole;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseZoneGoal;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class MeleeRetreatForHealingGoal extends BaseZoneGoal {
    private static final float TRIGGER_HP_FRACTION = 0.40F;
    private static final float RELEASE_HP_FRACTION = 0.70F;
    private static final double PRIEST_SEARCH_RANGE = 24.0D;
    private static final double ZONE_SEARCH_RANGE = 32.0D;

    private int poseToggleDelay;

    public MeleeRetreatForHealingGoal(GuardEntity guard, double speed) {
        super(guard, speed, ZONE_SEARCH_RANGE);
    }

    @Override
    public boolean canStart() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isSpellCastBusy() || guard.isEating()) return false;
        if (hasFoodInOffhand()) return false;

        float hpFrac = guard.getHealth() / guard.getMaxHealth();
        if (hpFrac > TRIGGER_HP_FRACTION) return false;

        return findNearestPriest(PRIEST_SEARCH_RANGE) != null;
    }

    @Override
    public boolean shouldContinue() {
        if (guard.isRemoved() || !guard.isAlive()) return false;
        if (guard.isSpellCastBusy()) return false;

        float hpFrac = guard.getHealth() / guard.getMaxHealth();
        if (hpFrac >= RELEASE_HP_FRACTION) return false;

        return findNearestPriest(PRIEST_SEARCH_RANGE) != null;
    }

    @Override
    public void start() {
        resetTracking();
        poseToggleDelay = 0;
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

        if (handleZoneRetreat()) {
            progressWatchdog();
            return;
        }

        GuardEntity priest = findNearestPriest(PRIEST_SEARCH_RANGE);
        if (priest != null) {
            handlePriestRetreat(priest);
            progressWatchdog();
            return;
        }

        handleFallbackRetreat();
        progressWatchdog();
    }

    private boolean handleZoneRetreat() {
        if (!findAndCacheZone()) return false;

        boolean inside = isInsideZone();

        if (!inside) {
            moveTowardsZone();
            updateShield(true);
            return true;
        }

        if (zoneAnchor == null) {
            zoneAnchor = computeBestAnchor(zoneRef, zoneRadius);
            if (zoneAnchor == null) return true;
        }

        double dist2 = guard.getPos().squaredDistanceTo(zoneAnchor);
        if (dist2 > 1.0D) {
            setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
            moveToPosition(zoneAnchor);
        } else {
            guard.getNavigation().stop();
            setControls(EnumSet.of(Goal.Control.LOOK));
        }

        boolean shouldRaise = determineShieldState();
        updateShield(shouldRaise);

        return true;
    }

    private void handlePriestRetreat(GuardEntity priest) {
        double dist2 = guard.squaredDistanceTo(priest);
        if (dist2 > 4.0D) {
            setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
            moveToPosition(priest.getPos());
        } else {
            guard.getNavigation().stop();
            setControls(EnumSet.of(Goal.Control.LOOK));
        }
        guard.lookAtEntity(priest, 30.0F, 30.0F);

        boolean shouldRaise = true;
        LivingEntity enemy = guard.getTarget();
        if (enemy != null && enemy.isAlive()) {
            shouldRaise = guard.getVisibilityCache().canSee(enemy)
                    && guard.squaredDistanceTo(enemy) < (12.0D * 12.0D);
        }

        updateShield(shouldRaise);
    }

    private void handleFallbackRetreat() {
        LivingEntity enemy = guard.getTarget();
        Vec3d pos = (enemy != null)
                ? NoPenaltyTargeting.findFrom(guard, 16, 7, enemy.getPos())
                : NoPenaltyTargeting.find(guard, 16, 7);

        if (pos != null) {
            setControls(EnumSet.of(Goal.Control.MOVE, Goal.Control.LOOK));
            moveToPosition(pos);
            updateShield(true);
        } else {
            guard.getNavigation().stop();
            setControls(EnumSet.of(Goal.Control.LOOK));
            updateShield(false);
        }

        if (enemy != null) {
            guard.lookAtEntity(enemy, 30.0F, 30.0F);
        }
    }

    private boolean determineShieldState() {
        boolean movingToZone = zoneRef != null && !isInsideZone();
        boolean notAnchored = zoneRef != null && !movingToZone
                && (zoneAnchor == null || guard.getPos().squaredDistanceTo(zoneAnchor) > 1.5D);

        LivingEntity enemy = guard.getTarget();
        boolean enemyThreat = enemy != null && enemy.isAlive()
                && guard.getVisibilityCache().canSee(enemy)
                && guard.squaredDistanceTo(enemy) < (12.0D * 12.0D);

        return movingToZone || notAnchored || enemyThreat;
    }

    private void updateShield(boolean raise) {
        if (!guard.hasShield()) return;

        if (raise) {
            if (guard.isSpellCastBusy()) {
                return;
            }

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
            EntityPose current = guard.getPose();
            if (current == EntityPose.STANDING) {
                guard.setPose(EntityPose.CROUCHING);
            } else if (current == EntityPose.CROUCHING) {
                guard.setPose(EntityPose.STANDING);
            }
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
        return GuardCombatRole.isRetreatHealer(g);
    }

    private boolean hasFoodInOffhand() {
        ItemStack off = guard.getOffHandStack();
        return off.get(DataComponentTypes.FOOD) != null;
    }
}