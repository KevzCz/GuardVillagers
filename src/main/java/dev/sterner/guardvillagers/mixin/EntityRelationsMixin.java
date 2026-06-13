package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.spell_engine.internals.target.EntityRelations;
import net.spell_engine.internals.target.SpellTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRelations.class)
public class EntityRelationsMixin {

    

    @Inject(method = "actionAllowed", at = @At("HEAD"), cancellable = true)
    private static void guardHarmfulActionAllowed(
            SpellTarget.FocusMode focusMode,
            SpellTarget.Intent intent,
            LivingEntity attacker,
            Entity target,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(attacker instanceof GuardEntity guard)) {
            return;
        }
        if (target instanceof LivingEntity living) {
            if (intent == SpellTarget.Intent.HARMFUL) {
                if (target == guard) {
                    cir.setReturnValue(false);
                } else {
                    cir.setReturnValue(living.isAlive() && guard.canTarget(living));
                }
                cir.cancel();
                return;
            }
            if (intent == SpellTarget.Intent.HELPFUL) {
                if (target == guard || living == guard.getOwner()) {
                    cir.setReturnValue(living.isAlive());
                    cir.cancel();
                }
            }
        }
    }
}
