package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers. common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net. minecraft.entity.mob.HostileEntity;
import net.spell_engine.internals.target. EntityRelations;
import net.spell_engine.internals. target.SpellTarget;
import org.spongepowered. asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered. asm.mixin.injection. Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRelations.class)
public class EntityRelationsMixin {
    @Inject(at = @At("RETURN"), method = "actionAllowed", cancellable = true)
    private static void actionAllowed$lneWizards(SpellTarget.FocusMode focusMode, SpellTarget.Intent intent, LivingEntity attacker, Entity target, CallbackInfoReturnable<Boolean> cir) {
        // If the attacker is a GuardEntity
        if(attacker instanceof GuardEntity guard) {
            // Allow harmful spells against their actual target or hostile entities
            if (intent == SpellTarget.Intent.HARMFUL) {
                // Allow damage to the guard's current target
                if (guard.getTarget() == target) {
                    cir.setReturnValue(true);
                    return;
                }
                // Allow damage to hostile entities
                if (target instanceof HostileEntity) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Block harmful spells against friendlies (non-hostile, non-target entities)
            if (intent == SpellTarget.Intent.HARMFUL && attacker != target && !(target instanceof HostileEntity)) {
                cir.setReturnValue(false);
            }
        }
    }
}