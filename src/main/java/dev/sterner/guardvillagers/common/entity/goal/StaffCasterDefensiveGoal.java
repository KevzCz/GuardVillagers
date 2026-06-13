package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.ai.GuardCombatRole;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.Hand;

import java.util.EnumSet;

/**
 * Staff/mage backline behavior when all combat magic is on cooldown.
 * Strafes and holds range; only swings when the enemy closes in (like a crossbow kick).
 */
public class StaffCasterDefensiveGoal extends Goal {

    private final GuardEntity guard;
    private int seeTime;
    private int updatePathDelay;
    private int staffBashCooldown;

    public StaffCasterDefensiveGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        return shouldHoldDefensiveLine();
    }

    @Override
    public boolean shouldContinue() {
        return shouldHoldDefensiveLine();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void start() {
        seeTime = 0;
        updatePathDelay = 0;
        guard.setAttacking(true);
    }

    @Override
    public void stop() {
        seeTime = 0;
        updatePathDelay = 0;
        guard.setAttacking(false);
    }

    @Override
    public void tick() {
        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }

        if (staffBashCooldown > 0) {
            staffBashCooldown--;
        }

        boolean canSee = guard.getVisibilityCache().canSee(target);
        var movement = CombatMovementHelper.applyStaffDefensiveMovement(
                guard, target, canSee, seeTime, updatePathDelay);
        seeTime = movement.seeTime();
        updatePathDelay = movement.updatePathDelay();

        if (staffBashCooldown <= 0 && guard.isInAttackRange(target)) {
            staffBashCooldown = 20;
            guard.stopUsingItem();
            if (guard.shieldCoolDown == 0) {
                guard.shieldCoolDown = 8;
            }
            guard.swingHand(Hand.MAIN_HAND);
            guard.tryAttack(target);
        }
    }

    private boolean shouldHoldDefensiveLine() {
        if (guard.isSpellCastBusy() || guard.isEating() || guard.isBlocking()) {
            return false;
        }
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (GuardCombatRole.isDedicatedSupport(guard)) {
            return false;
        }
        var manager = guard.getSpellManager();
        if (!manager.shouldUseProjectileCasting() || manager.hasCastablePhysicalMeleeSpell()) {
            return false;
        }
        LivingEntity target = guard.getTarget();
        return isHostileCombatTarget(target) && !manager.hasAnyCastableCombatMagic(target);
    }

    private boolean isHostileCombatTarget(LivingEntity target) {
        return target != null && target.isAlive() && guard.canTarget(target);
    }
}
