package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class SupportBacklineHelper {

    private static final double BACKLINE_OFFSET = 4.0D;
    private static final double MIN_ENEMY_DISTANCE = 7.0D;
    private static final double RETREAT_ANCHOR_RADIUS = 5.0D;

    private SupportBacklineHelper() {}

    public static Optional<Vec3d> desiredBacklinePosition(GuardEntity self, LivingEntity enemy) {
        GuardEntity frontline = findFrontlineGuard(self, enemy);
        Vec3d enemyPos = enemy.getPos();

        if (frontline != null) {
            Vec3d frontPos = frontline.getPos();
            Vec3d awayFromEnemy = frontPos.subtract(enemyPos);
            if (awayFromEnemy.lengthSquared() < 0.01D) {
                awayFromEnemy = self.getPos().subtract(enemyPos);
            }
            awayFromEnemy = awayFromEnemy.normalize();
            return Optional.of(frontPos.add(awayFromEnemy.multiply(BACKLINE_OFFSET)));
        }

        Vec3d selfPos = self.getPos();
        Vec3d away = selfPos.subtract(enemyPos);
        if (away.lengthSquared() < 0.01D) {
            away = new Vec3d(self.getRandom().nextGaussian(), 0, self.getRandom().nextGaussian()).normalize();
        }
        double dist = Math.max(MIN_ENEMY_DISTANCE, Math.sqrt(self.squaredDistanceTo(enemy)));
        return Optional.of(enemyPos.add(away.normalize().multiply(dist)));
    }

    public static void moveToward(GuardEntity guard, Vec3d target, double speed, int pathDelay) {
        if (pathDelay > 0) {
            return;
        }
        guard.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
    }

    public static void applyCastMovement(
            GuardEntity guard,
            LivingEntity enemy,
            @Nullable SpellMovementProfile profile,
            boolean anchorForRetreat
    ) {
        guard.getLookControl().lookAt(enemy, 30.0F, 30.0F);
        guard.lookAtEntity(enemy, 30.0F, 30.0F);

        if (anchorForRetreat) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
            return;
        }

        float castMove = profile != null ? profile.movementSpeed() : 0.0F;
        if (castMove <= 0.0F) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, -0.1F);
            return;
        }

        desiredBacklinePosition(guard, enemy).ifPresent(pos -> {
            double distSq = guard.squaredDistanceTo(pos.x, pos.y, pos.z);
            if (distSq > 2.0D) {
                guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, Math.min(1.1D, castMove * 0.65D));
            } else {
                guard.getNavigation().stop();
                guard.getMoveControl().strafeTo(guard.getRandom().nextBoolean() ? 0.25F : -0.25F, -0.05F);
            }
        });
    }

    public static boolean shouldAnchorForRetreatingAllies(GuardEntity self) {
        Box box = self.getBoundingBox().expand(RETREAT_ANCHOR_RADIUS);
        List<GuardEntity> nearby = self.getWorld().getEntitiesByClass(
                GuardEntity.class,
                box,
                g -> g != self && g.isAlive() && g.getHealth() / g.getMaxHealth() < 0.55F
        );
        return !nearby.isEmpty();
    }

    @Nullable
    private static GuardEntity findFrontlineGuard(GuardEntity self, LivingEntity enemy) {
        Box box = self.getBoundingBox().expand(24.0D);
        List<GuardEntity> fighters = self.getWorld().getEntitiesByClass(
                GuardEntity.class,
                box,
                g -> g != self
                        && g.isAlive()
                        && g.getTarget() == enemy
                        && !GuardCombatRole.isDedicatedSupport(g)
        );
        if (fighters.isEmpty()) {
            return null;
        }
        return fighters.stream()
                .min(Comparator.comparingDouble(g -> g.squaredDistanceTo(enemy)))
                .orElse(null);
    }

    public record SpellMovementProfile(float movementSpeed) {
        @Nullable
        public static SpellMovementProfile from(net.spell_engine.api.spell.Spell spell) {
            if (spell.active != null && spell.active.cast != null) {
                return new SpellMovementProfile(spell.active.cast.movement_speed);
            }
            return new SpellMovementProfile(0.0F);
        }
    }
}
