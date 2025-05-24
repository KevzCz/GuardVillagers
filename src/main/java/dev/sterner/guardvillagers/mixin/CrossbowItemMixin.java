package dev.sterner.guardvillagers.mixin;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    @Inject(
            method = "createArrowEntity",
            at = @At("RETURN"),
            cancellable = true
    )
    private void applyCustomCrossbowDamage(
            World world,
            LivingEntity shooter,
            ItemStack weaponStack,
            ItemStack projectileStack,
            boolean critical,
            CallbackInfoReturnable<ProjectileEntity> cir
    ) {
        ProjectileEntity projectile = cir.getReturnValue();

        // Only apply scaling if it's your custom mob (not player)
        if (shooter instanceof GuardEntity && projectile instanceof PersistentProjectileEntity arrow) {
            Identifier rangedDamageId = Identifier.of("ranged_weapon", "damage");

            Registries.ATTRIBUTE.getEntry(rangedDamageId).ifPresent(attr -> {
                if (shooter.getAttributes().hasAttribute(attr)) {
                    double value = shooter.getAttributeValue(attr);
                    arrow.setDamage(arrow.getDamage() + value / 3);
                }
            });
        }
    }
}

