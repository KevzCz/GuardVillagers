package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellExecution;
import net.spell_engine.internals.delivery.ProjectileLauncher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ProjectileLauncher.class)
public class SpellHelperGuardMixin {

    @Redirect(
            method = "shootProjectile(Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/spell_engine/internals/SpellExecution$ImpactContext;I)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/spell_engine/api/spell/Spell$Delivery$ShootProjectile;direct_towards_target:Z",
                    remap = false
            )
    )
    private static boolean guardvillagers$directTowardsTargetForGuards(
            Spell.Delivery.ShootProjectile config,
            World world,
            LivingEntity caster,
            Entity target,
            RegistryEntry<Spell> spellEntry,
            SpellExecution.ImpactContext context,
            int channelOffset
    ) {
        if (caster instanceof GuardEntity && target != null && target.isAlive()) {
            return true;
        }
        return config.direct_towards_target;
    }

    @Redirect(
            method = "shootProjectile(Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/spell_engine/internals/SpellExecution$ImpactContext;I)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/spell_engine/api/spell/Spell$Delivery$ShootProjectile;inherit_shooter_yaw:Z",
                    remap = false
            )
    )
    private static boolean guardvillagers$inheritYawForGuards(
            Spell.Delivery.ShootProjectile config,
            World world,
            LivingEntity caster,
            Entity target,
            RegistryEntry<Spell> spellEntry,
            SpellExecution.ImpactContext context,
            int channelOffset
    ) {
        if (caster instanceof GuardEntity) {
            return true;
        }
        return config.inherit_shooter_yaw;
    }

    @Redirect(
            method = "shootProjectile(Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/spell_engine/internals/SpellExecution$ImpactContext;I)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/spell_engine/api/spell/Spell$Delivery$ShootProjectile;inherit_shooter_pitch:Z",
                    remap = false
            )
    )
    private static boolean guardvillagers$inheritPitchForGuards(
            Spell.Delivery.ShootProjectile config,
            World world,
            LivingEntity caster,
            Entity target,
            RegistryEntry<Spell> spellEntry,
            SpellExecution.ImpactContext context,
            int channelOffset
    ) {
        if (caster instanceof GuardEntity) {
            return true;
        }
        return config.inherit_shooter_pitch;
    }
}
