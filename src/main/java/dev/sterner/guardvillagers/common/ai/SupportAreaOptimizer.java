package dev.sterner.guardvillagers.common.ai;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public final class SupportAreaOptimizer {

    public static final double DEFAULT_MAX_MOVE = 4.0D;

    private SupportAreaOptimizer() {}

    public static Optional<Vec3d> bestPosition(
            GuardEntity guard,
            List<LivingEntity> allies,
            float effectRadius,
            double maxMoveBlocks
    ) {
        if (allies.isEmpty()) {
            return Optional.empty();
        }

        Vec3d origin = guard.getPos();
        World world = guard.getWorld();
        Vec3d best = origin;
        double bestScore = scoreAt(origin, allies, effectRadius);

        int range = (int) Math.ceil(maxMoveBlocks);
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (dx * dx + dz * dz > maxMoveBlocks * maxMoveBlocks) {
                    continue;
                }
                BlockPos block = BlockPos.ofFloored(origin.x + dx, origin.y, origin.z + dz);
                if (!isStandable(world, block)) {
                    continue;
                }
                Vec3d candidate = Vec3d.ofCenter(block);
                double score = scoreAt(candidate, allies, effectRadius);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        if (best.squaredDistanceTo(origin) < 0.25D && bestScore <= scoreAt(origin, allies, effectRadius)) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    public static float resolveEffectRadius(Spell spell) {
        if (spell.area_impact != null && spell.area_impact.radius > 0) {
            return spell.area_impact.radius;
        }
        if (spell.range > 0) {
            return spell.range;
        }
        return 5.0F;
    }

    public static int alliesInRadius(Vec3d center, List<LivingEntity> allies, float radius) {
        double radiusSq = radius * radius;
        int count = 0;
        for (LivingEntity ally : allies) {
            if (ally.getPos().squaredDistanceTo(center) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private static double scoreAt(Vec3d center, List<LivingEntity> allies, float radius) {
        double radiusSq = radius * radius;
        double score = 0;
        for (LivingEntity ally : allies) {
            double distSq = ally.getPos().squaredDistanceTo(center);
            if (distSq > radiusSq) {
                continue;
            }
            double hpFrac = ally.getHealth() / ally.getMaxHealth();
            score += 1.0D + (1.0D - hpFrac) * 2.0D;
        }
        return score;
    }

    private static boolean isStandable(World world, BlockPos pos) {
        return world.getBlockState(pos.down()).isSolidBlock(world, pos.down())
                && world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir();
    }

    public static boolean withinMoveBudget(Vec3d anchor, Vec3d target, double maxMoveBlocks) {
        return anchor.squaredDistanceTo(target) <= maxMoveBlocks * maxMoveBlocks;
    }

    @Nullable
    public static Vec3d clampToMoveBudget(Vec3d anchor, Vec3d target, double maxMoveBlocks) {
        double maxSq = maxMoveBlocks * maxMoveBlocks;
        double distSq = anchor.squaredDistanceTo(target);
        if (distSq <= maxSq) {
            return target;
        }
        Vec3d delta = target.subtract(anchor);
        double len = delta.length();
        if (len < 1.0E-4) {
            return anchor;
        }
        return anchor.add(delta.multiply(maxMoveBlocks / len));
    }
}
