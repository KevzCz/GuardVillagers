package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.TimeHelper;
import net.minecraft.util.math.intprovider.UniformIntProvider;

public abstract class BaseRangedSpellGoal extends BaseSpellGoal {
    protected static final UniformIntProvider PATH_DELAY = TimeHelper.betweenSeconds(1, 2);

    protected final double speedModifier;
    protected final float attackRadius;
    protected final float attackRadiusSqr;

    protected int seeTime;
    protected int updatePathDelay;
    protected int attackDelay;

    public BaseRangedSpellGoal(GuardEntity guard, double speedModifier, float attackRadius) {
        super(guard);
        this.speedModifier = speedModifier;
        this.attackRadius = attackRadius;
        this.attackRadiusSqr = attackRadius * attackRadius;
    }

    @Override
    public void start() {
        guard.setAttacking(true);
        seeTime = 0;
        updatePathDelay = 0;
        attackDelay = 0;
    }

    @Override
    public void stop() {
        super.stop();
        guard.setAttacking(false);
        seeTime = 0;
        updatePathDelay = 0;
    }

    protected void updateCombatMovement(LivingEntity target, boolean inAimPhase, boolean canRun) {
        updateCombatMovement(target, inAimPhase, canRun, this.attackRadius);
    }

    protected void updateCombatMovement(LivingEntity target, boolean inAimPhase, boolean canRun, float castRange) {
        boolean canSee = guard.getVisibilityCache().canSee(target);

        var mv = CombatMovementHelper.applyRangedCombatMovement(
                guard,
                target,
                canSee,
                this.seeTime,
                this.updatePathDelay,
                this.attackDelay,
                inAimPhase,
                canRun,
                this.speedModifier,
                castRange
        );

        this.seeTime = mv.seeTime();
        this.updatePathDelay = mv.updatePathDelay();
    }

    protected boolean isValidTarget() {
        LivingEntity target = guard.getTarget();
        return target != null && target.isAlive();
    }
}