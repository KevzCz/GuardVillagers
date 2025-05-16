package dev.sterner.guardvillagers.mixin.accessor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(CrossbowItem.class)
public interface CrossbowItemAccessor {
    @Invoker("shootAll")
    void callShootAll(
            World world,
            LivingEntity shooter,
            Hand hand,
            ItemStack crossbow,
            float speed,
            float divergence,
            @Nullable LivingEntity target
    );
}


