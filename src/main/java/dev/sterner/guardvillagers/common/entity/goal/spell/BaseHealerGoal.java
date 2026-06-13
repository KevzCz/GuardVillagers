package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

public abstract class BaseHealerGoal extends BaseSpellGoal {
    protected static final float HEAL_CLUSTER_RADIUS = 6.0F;

    protected BaseHealerGoal(GuardEntity guard) {
        super(guard);
    }

    protected LivingEntity findBestHealableAlly(float radius, float capFrac) {
        Box box = guard.getBoundingBox().expand(radius);
        List<LivingEntity> allies = guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box,
                e -> isAlly(guard, e) && (e.getHealth() / e.getMaxHealth()) < capFrac
        );

        if ((guard.getHealth() / guard.getMaxHealth()) < capFrac) {
            allies.add(guard);
        }

        if (allies.isEmpty()) return null;

        return allies.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
    }

    protected LivingEntity findBestHealableGuardAlly(float radius, float capFrac) {
        Box box = guard.getBoundingBox().expand(radius);
        List<GuardEntity> guards = guard.getWorld().getEntitiesByClass(
                GuardEntity.class, box,
                g -> g.isAlive() && g != guard && (g.getHealth() / g.getMaxHealth()) < capFrac
        );

        if ((guard.getHealth() / guard.getMaxHealth()) < capFrac) {
            guards.add(guard);
        }

        if (guards.isEmpty()) return null;

        return guards.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);
    }

    protected Cluster findHurtAllyCluster(float searchRadius) {
        Box box = guard.getBoundingBox().expand(searchRadius);
        List<LivingEntity> hurtAllies = guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box,
                e -> isAlly(guard, e) && e.getHealth() < e.getMaxHealth()
        );

        if (hurtAllies.isEmpty()) return Cluster.invalid();

        LivingEntity seed = hurtAllies.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);

        if (seed == null) return Cluster.invalid();

        Vec3d sum = Vec3d.ZERO;
        int count = 0;
        for (LivingEntity a : hurtAllies) {
            if (a.squaredDistanceTo(seed) <= (HEAL_CLUSTER_RADIUS * HEAL_CLUSTER_RADIUS)) {
                sum = sum.add(a.getPos());
                count++;
            }
        }

        if (count == 0) return Cluster.invalid();

        return new Cluster(true, sum.multiply(1.0 / count), count);
    }

    protected Cluster findHurtGuardCluster(float searchRadius) {
        Box box = guard.getBoundingBox().expand(searchRadius);
        List<GuardEntity> hurtGuards = guard.getWorld().getEntitiesByClass(
                GuardEntity.class, box,
                g -> g.isAlive() && g.getHealth() < g.getMaxHealth()
        );

        if (guard.getHealth() < guard.getMaxHealth() && !hurtGuards.contains(guard)) {
            hurtGuards.add(guard);
        }

        if (hurtGuards.isEmpty()) return Cluster.invalid();

        GuardEntity seed = hurtGuards.stream()
                .min(Comparator.comparingDouble(a -> a.getHealth() / a.getMaxHealth()))
                .orElse(null);

        if (seed == null) return Cluster.invalid();

        Vec3d sum = Vec3d.ZERO;
        int count = 0;
        for (GuardEntity a : hurtGuards) {
            if (a.squaredDistanceTo(seed) <= (HEAL_CLUSTER_RADIUS * HEAL_CLUSTER_RADIUS)) {
                sum = sum.add(a.getPos());
                count++;
            }
        }

        if (count == 0) return Cluster.invalid();

        return new Cluster(true, sum.multiply(1.0 / count), count);
    }

    protected boolean hasHurtAllyNearby(float radius) {
        if (guard.getHealth() < guard.getMaxHealth()) return true;

        Box box = guard.getBoundingBox().expand(radius);
        return !guard.getWorld().getEntitiesByClass(
                LivingEntity.class, box,
                e -> isAlly(guard, e) && e.getHealth() < e.getMaxHealth()
        ).isEmpty();
    }

    protected static boolean isAlly(GuardEntity self, LivingEntity e) {
        if (e == null || !e.isAlive() || e == self) return false;

        if (self.isHired() && self.isOwner(e)) {
            return true;
        }
        return (e instanceof VillagerEntity)
                || (e instanceof GuardEntity)
                || (e instanceof IronGolemEntity);
    }

    protected static class Cluster {
        public final boolean valid;
        public final Vec3d center;
        public final int size;

        Cluster(boolean v, Vec3d c, int s) {
            valid = v;
            center = c;
            size = s;
        }

        static Cluster invalid() {
            return new Cluster(false, Vec3d.ZERO, 0);
        }
    }
}