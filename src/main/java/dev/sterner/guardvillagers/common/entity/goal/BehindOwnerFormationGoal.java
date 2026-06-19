package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

public class BehindOwnerFormationGoal extends Goal {

    private static final double IDLE_FOLLOW_DIST = 5.0;
    private static final double IDLE_CLOSE_DIST = 3.0;
    private static final double COMBAT_STAND_DIST = 5.0;
    private static final double COMBAT_REPOSITION_THRESHOLD = 3.0;

    private final GuardEntity guard;

    public BehindOwnerFormationGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (!guard.isFollowing() || !guard.isHired()) return false;
        if (guard.getFollowFormation() != GuardVillagersConfig.FollowFormation.BEHIND) return false;
        LivingEntity owner = guard.getOwner();
        return owner != null && owner.isAlive();
    }

    @Override
    public boolean shouldContinue() {
        return canStart();
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity owner = guard.getOwner();
        if (owner == null) return;

        LivingEntity combatTarget = guard.getTarget();

        if (combatTarget != null && combatTarget.isAlive()) {
            tickCombat(owner, combatTarget);
        } else {
            tickIdle(owner);
        }
    }

    private void tickIdle(LivingEntity owner) {
        Vec3d behindOwner = behindOwnerPos(owner, null, IDLE_FOLLOW_DIST);
        double dist = guard.getPos().distanceTo(behindOwner);

        guard.getLookControl().lookAt(owner, 30f, 30f);

        if (dist > IDLE_CLOSE_DIST) {
            guard.getNavigation().startMovingTo(behindOwner.x, behindOwner.y, behindOwner.z, 0.7);
        } else {
            guard.getNavigation().stop();
        }
    }

    private void tickCombat(LivingEntity owner, LivingEntity target) {
        float castRange = guard.getSpellManager().getBestCastableCombatSong(target)
                .map(s -> guard.getSpellManager().effectiveCastRange(s.entry()))
                .orElse(0f);

        guard.getLookControl().lookAt(target, 30f, 30f);

        if (castRange <= 0) {
            // Melee guard — release MOVE so the melee goal can navigate to the target
            setControls(EnumSet.of(Control.LOOK));
            guard.getNavigation().stop();
            return;
        }

        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        double standDist = Math.min(castRange * 0.8, 12.0);
        Vec3d standPos = behindOwnerPos(owner, target, standDist);
        double distToStand = guard.getPos().distanceTo(standPos);

        if (distToStand > COMBAT_REPOSITION_THRESHOLD) {
            guard.getNavigation().startMovingTo(standPos.x, standPos.y, standPos.z, 1.0);
        } else {
            guard.getNavigation().stop();
        }
    }

    private Vec3d behindOwnerPos(LivingEntity owner, LivingEntity target, double dist) {
        Vec3d away;
        if (target != null) {
            away = owner.getPos().subtract(target.getPos()).normalize();
        } else {
            float yaw = (float) Math.toRadians(owner.getYaw());
            away = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        return owner.getPos().add(away.multiply(dist));
    }
}
