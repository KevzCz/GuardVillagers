package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import net.spell_engine.entity.SpellProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpellProjectile.class)
public abstract class GuardFriendlyFireMixin extends ProjectileEntity {
    public GuardFriendlyFireMixin(EntityType<? extends ProjectileEntity> type, World world) {
        super(type, world);
    }

    @Inject(method = "onEntityHit", at = @At("HEAD"), cancellable = true)
    private void preventGuardFriendlyFire(EntityHitResult hitResult, CallbackInfo ci) {
        Entity hitEntity = hitResult.getEntity();
        Entity shooter = this.getOwner();

        if (shooter instanceof GuardEntity
                && (hitEntity instanceof GuardEntity || hitEntity instanceof VillagerEntity)) {
            ci.cancel();
        }
    }
}
