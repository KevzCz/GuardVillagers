package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.mixin.accessor.EntityAccessor;
import dev.sterner.guardvillagers.common.entity.LivingEntitySpellMeleeAccess;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySpellMeleeMixin implements LivingEntitySpellMeleeAccess {

    @Shadow
    private int hurtTime;

    @Shadow
    private long lastDamageTime;

    @Override
    public void guardvillagers$clearMeleeDamageInvulnerability() {
        this.hurtTime = 0;
        this.lastDamageTime = 0L;
        ((EntityAccessor) this).guardvillagers$setTimeUntilRegen(0);
    }
}
