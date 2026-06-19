package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.ai.goal.Goal;

public class KickGoal extends Goal {

    public final GuardEntity guard;
    private boolean hasKicked = false;

    public KickGoal(GuardEntity guard) {
        this.guard = guard;
    }

    @Override
    public boolean canStart() {
        return guard.getTarget() != null && guard.getTarget().distanceTo(guard) <= 2.5D && guard.getMainHandStack().getItem().isUsedOnRelease(guard.getMainHandStack()) && !guard.isBlocking() && guard.kickCoolDown == 0 && !guard.isSpellCastBusy();
    }

    @Override
    public boolean shouldContinue() {
        return !hasKicked;
    }

    @Override
    public void start() {
        hasKicked = false;
        guard.setKicking(true);
        if (guard.kickTicks <= 0) {
            guard.kickTicks = 10;
        }
        guard.tryAttack(guard.getTarget());
        hasKicked = true;
    }

    @Override
    public void stop() {
        hasKicked = false;
        guard.setKicking(false);
        guard.kickCoolDown = 50;
    }
}