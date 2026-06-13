package dev.sterner.guardvillagers.common.special;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

public final class SpecialGuardVillageTracker {

    public static final int VILLAGE_GUARD_RADIUS = 96;

    private SpecialGuardVillageTracker() {}

    public static int countSpecialGuardsNear(ServerWorld world, BlockPos pos, @Nullable Identifier typeFilter) {
        Box box = new Box(pos).expand(VILLAGE_GUARD_RADIUS);
        return world.getEntitiesByClass(GuardEntity.class, box, guard -> {
            if (guard.getSpecialGuardType() == null) {
                return false;
            }
            return typeFilter == null || typeFilter.equals(guard.getSpecialGuardType());
        }).size();
    }
}
