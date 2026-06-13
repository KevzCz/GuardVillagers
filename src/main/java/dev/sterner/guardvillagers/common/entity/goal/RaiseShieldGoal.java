package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.util.Hand;

public class RaiseShieldGoal extends Goal {

    public final GuardEntity guard;

    public RaiseShieldGoal(GuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canStart() {
        if (guard.isSpellCastBusy()) return false;
        return !CrossbowItem.isCharged(guard.getMainHandStack()) &&
                guard.hasShield() &&
                raiseShield() && guard.shieldCoolDown == 0;
    }

    @Override
    public boolean shouldContinue() {
        return this.canStart();
    }

    @Override
    public void start() {
        if (guard.hasShield())
            guard.setCurrentHand(Hand.OFF_HAND);
    }

    @Override
    public void stop() {
        if (!GuardVillagersConfig.guardAlwaysShield)
            guard.stopUsingItem();
    }

    protected boolean raiseShield() {
        LivingEntity target = guard.getTarget();
        if (target == null || guard.shieldCoolDown != 0) {
            return false;
        }

        boolean ranged = GuardItemTags.isRangedDamageWeapon(guard.getMainHandStack());
        boolean magicCaster = guard.getSpellManager().shouldUseProjectileCasting()
                && !guard.getSpellManager().hasCastablePhysicalMeleeSpell();

        if (GuardVillagersConfig.guardAlwaysShield) {
            return true;
        }
        if (target instanceof CreeperEntity || target instanceof RavagerEntity) {
            return true;
        }
        if (target instanceof RangedAttackMob && guard.distanceTo(target) >= 5.0D && !ranged) {
            return true;
        }
        if (magicCaster) {
            return false;
        }
        return guard.distanceTo(target) <= 4.0D;
    }
}