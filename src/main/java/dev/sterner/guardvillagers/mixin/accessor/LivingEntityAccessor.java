package dev.sterner.guardvillagers.mixin.accessor;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("lastAttackedTicks")
    void guardvillagers$setLastAttackedTicks(int ticks);

    @Accessor("hurtTime")
    void guardvillagers$setHurtTime(int hurtTime);

    @Accessor("lastDamageTime")
    void guardvillagers$setLastDamageTime(long lastDamageTime);
}
