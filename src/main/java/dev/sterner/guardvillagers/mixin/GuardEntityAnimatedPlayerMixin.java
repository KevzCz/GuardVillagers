package dev.sterner.guardvillagers.mixin;

import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.sterner.guardvillagers.client.animation.GuardAnimationState;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuardEntity.class)
public class GuardEntityAnimatedPlayerMixin implements IAnimatedPlayer {

    @Override
    public AnimationStack getAnimationStack() {
        return GuardAnimationState.getOrCreate((GuardEntity) (Object) this).stack;
    }

    @Override
    public AnimationApplier playerAnimator_getAnimation() {
        return GuardAnimationState.getOrCreate((GuardEntity) (Object) this).applier;
    }

    @Override
    public IAnimation playerAnimator_getAnimation(Identifier id) {
        return null;
    }

    @Override
    public IAnimation playerAnimator_setAnimation(Identifier id, IAnimation animation) {
        return null;
    }
}
