package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ProjectileEntity.class)
public abstract class ProjectileEntityMixin extends Entity {
    protected ProjectileEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Shadow public abstract Entity getOwner();

    @Inject(method = "canHit(Lnet/minecraft/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void guardvillagers$passThroughGuards(Entity target, CallbackInfoReturnable<Boolean> cir) {
        Entity owner = this.getOwner();
        if (owner instanceof GuardEntity && target instanceof GuardEntity) {
            cir.setReturnValue(false);
        }
    }
}
