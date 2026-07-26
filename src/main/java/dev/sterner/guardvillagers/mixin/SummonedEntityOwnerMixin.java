package dev.sterner.guardvillagers.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.spell_engine.entity.SummonedEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(SummonedEntity.class)
public abstract class SummonedEntityOwnerMixin {

    @Inject(method = "getOwner", at = @At("RETURN"), cancellable = true)
    private void guardvillagers$resolveNonPlayerOwner(CallbackInfoReturnable<LivingEntity> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        SummonedEntity self = (SummonedEntity) (Object) this;
        UUID ownerUuid = self.getOwnerUuid();
        if (ownerUuid == null || !(self.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        Entity owner = serverWorld.getEntity(ownerUuid);
        if (owner instanceof LivingEntity living && living != self) {
            cir.setReturnValue(living);
        }
    }
}
