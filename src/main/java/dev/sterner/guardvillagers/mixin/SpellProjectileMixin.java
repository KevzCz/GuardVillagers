package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import net.minecraft.entity.Entity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.EntityHitResult;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.entity.SpellProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpellProjectile.class)
public class SpellProjectileMixin {

    @Inject(
            method = "onEntityHit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/spell_engine/internals/impact/SpellImpacts;projectileImpact(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/spell_engine/internals/SpellExecution$ImpactContext;)Z",
                    shift = At.Shift.AFTER
            )
    )
    private void guardvillagers$onProjectileHit(EntityHitResult entityHitResult, CallbackInfo ci) {
        SpellProjectile self = (SpellProjectile) (Object) this;
        Entity owner = self.getOwner();

        if (owner instanceof GuardEntity guard && !guard.getWorld().isClient()) {
            Entity target = entityHitResult.getEntity();
            RegistryEntry<Spell> spellEntry = self.getSpellEntry();

            if (spellEntry != null && target != null) {
                SpellDelivery.triggerPassiveSpellsPublic(guard, target, spellEntry, false);
                SpellDelivery.triggerStashedEffectsPublic(guard, target, spellEntry);
            }
        }
    }
}