package dev.sterner.guardvillagers.mixin.accessor;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("timeUntilRegen")
    void guardvillagers$setTimeUntilRegen(int timeUntilRegen);
}
