package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.GuardCombatRole;
import dev.sterner.guardvillagers.common.ai.HolyZoneHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.util.Hand;

public class GuardEntityMeleeGoal extends MeleeAttackGoal {
    public final GuardEntity guard;

    public GuardEntityMeleeGoal(GuardEntity guard, double speedIn, boolean useLongMemory) {
        super(guard, speedIn, useLongMemory);
        this.guard = guard;
    }

    @Override
    public boolean canStart() {
        return shouldUseMeleeCombat()
                && !GuardItemTags.isCrossbowLikeWeapon(this.guard.getMainHandStack())
                && this.guard.getTarget() != null
                && !guard.isSpellCastBusy()
                && !this.guard.isEating()
                && super.canStart();
    }

    private boolean shouldUseMeleeCombat() {
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (GuardCombatRole.isDedicatedSupport(guard)) {
            return false;
        }
        var manager = guard.getSpellManager();
        if (manager.hasCastablePhysicalMeleeSpell()) {
            return true;
        }
        // Staff/mage archetypes keep distance; StaffCasterDefensiveGoal handles reactive staff bashes.
        return !manager.shouldUseProjectileCasting();
    }

    @Override
    public boolean shouldContinue() {
        return super.shouldContinue()
                && !guard.isSpellCastBusy()
                && this.guard.getTarget() != null;
    }

    @Override
    public void start() {
        super.start();
        guard.setAttacking(true);
    }

    @Override
    public void stop() {
        super.stop();
        guard.setAttacking(false);
    }

    @Override
    public void tick() {
        LivingEntity target = guard.getTarget();
        if (target != null) {
            double distanceSq = guard.squaredDistanceTo(target);

            guard.lookAtEntity(target, 30.0F, 30.0F);

            if (distanceSq < 1.0D) {

                guard.getMoveControl().strafeTo(-0.5F, 0.0F);
                guard.getNavigation().stop();
            } else if (this.path != null && distanceSq <= 4.0D) {
                guard.getNavigation().stop();
            }

            super.tick();
        }
    }

    @Override
    protected void attack(LivingEntity target) {
        if (guard.isInAttackRange(target) && this.getCooldown() <= 0) {
            this.resetCooldown();
            this.guard.stopUsingItem();
            if (guard.shieldCoolDown == 0) this.guard.shieldCoolDown = 8;
            this.guard.swingHand(Hand.MAIN_HAND);
            this.guard.tryAttack(target);
        }
    }
}
