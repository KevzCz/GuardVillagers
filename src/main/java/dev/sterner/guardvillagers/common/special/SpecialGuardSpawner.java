package dev.sterner.guardvillagers.common.special;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public final class SpecialGuardSpawner {

    private SpecialGuardSpawner() {}

    @Nullable
    public static GuardEntity spawn(
            ServerWorld world,
            SpecialGuardDefinition definition,
            BlockPos pos,
            float yaw,
            SpawnReason spawnReason
    ) {
        GuardEntity guard = GuardVillagers.GUARD_VILLAGER.create(world);
        if (guard == null) {
            return null;
        }

        guard.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
        guard.initialize(world, world.getLocalDifficulty(pos), spawnReason, null);
        guard.setPersistent();
        SpecialGuardApplicator.apply(guard, definition, world);

        if (!world.spawnEntity(guard)) {
            return null;
        }
        return guard;
    }

    public static void configureNaturalSpawn(
            GuardEntity guard,
            ServerWorld world,
            @Nullable VillagerEntity sourceVillager
    ) {
        SpecialGuardDefinition definition = SpecialGuardRegistry.INSTANCE.pick(world, guard.getBlockPos(), world.getRandom());
        if (definition != null) {
            SpecialGuardApplicator.apply(guard, definition, world);
            return;
        }

        if (sourceVillager != null) {
            guard.setCustomName(sourceVillager.getCustomName());
            guard.setCustomNameVisible(sourceVillager.isCustomNameVisible());
        }
    }
}
