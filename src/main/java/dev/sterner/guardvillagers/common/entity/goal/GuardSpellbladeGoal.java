package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.ai.SpellbladeCombatHelper;
import dev.sterner.guardvillagers.common.ai.SpellbladeCombatHelper.Posture;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.ConditionalSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardCastVisuals;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardSpellTimings;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellCombatTargeting;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Optional;

public class GuardSpellbladeGoal extends BaseSpellGoal {

    private enum Mode {
        ENGAGE,
        MELEE_CAST,
        MAGIC_CAST,
        MELEE_DUEL,
        REPOSITION
    }

    private enum CastStep {
        IDLE,
        WINDUP,
        CASTING,
        FOLLOW_THROUGH
    }

    private final ConditionalSpellGoal conditionalSpells;

    private Mode mode = Mode.ENGAGE;
    private CastStep castStep = CastStep.IDLE;
    private Posture posture = Posture.MELEE_FOCUS;
    private boolean meleeCastStyle;

    private int seeTime;
    private int updatePathDelay;
    private int strafeCooldown;
    private boolean strafeLeft;
    private int bladeCooldown;
    private int castFollowThroughTicks;
    private int channelHitsDelivered;
    private boolean spellFired;

    public GuardSpellbladeGoal(GuardEntity guard) {
        super(guard);
        this.conditionalSpells = new ConditionalSpellGoal(guard);
        conditionalSpells.registerHealthThreshold("wizards:frost_shield", 0.3f);
    }

    @Override
    public boolean canStart() {
        if (!SpellbladeCombatHelper.isActive(guard)) {
            return false;
        }
        if (guard.isSpellCastBusy()) {
            return false;
        }
        if (guard.spellCastGraceTicks > 0) {
            return false;
        }
        return SpellbladeCombatHelper.inCombat(guard);
    }

    @Override
    public boolean shouldContinue() {
        if (!SpellbladeCombatHelper.isActive(guard)) {
            return false;
        }
        if (mode == Mode.MELEE_CAST || mode == Mode.MAGIC_CAST) {
            if (castStep == CastStep.WINDUP || castStep == CastStep.CASTING || castStep == CastStep.FOLLOW_THROUGH) {
                return true;
            }
        }
        if (mode == Mode.MELEE_DUEL || mode == Mode.REPOSITION) {
            return SpellbladeCombatHelper.inCombat(guard);
        }
        return SpellbladeCombatHelper.inCombat(guard);
    }

    @Override
    public void start() {
        super.start();
        mode = Mode.ENGAGE;
        castStep = CastStep.IDLE;
        posture = SpellbladeCombatHelper.hasMagicReady(guard, guard.getTarget())
                ? Posture.MAGIC_FOCUS
                : Posture.MELEE_FOCUS;
        seeTime = 0;
        updatePathDelay = 0;
        strafeCooldown = 0;
        strafeLeft = guard.getRandom().nextBoolean();
        bladeCooldown = 0;
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        spellFired = false;
        guard.setAttacking(true);
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        enterMeleeDuelIfNeeded();
    }

    @Override
    public void stop() {
        if (shouldApplyChannelAbortCooldown()) {
            applySpellCooldownOnAbort(currentSpellId, cachedSpellEntry.value());
        }
        guard.setAttacking(false);
        guard.setActiveBeam(null);
        super.stop();
        mode = Mode.ENGAGE;
        castStep = CastStep.IDLE;
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        spellFired = false;
    }

    @Override
    protected void resetSpellState() {
        super.resetSpellState();
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        spellFired = false;
    }

    @Override
    protected boolean shouldInterruptCastOnStop() {
        if (castStep == CastStep.CASTING && (spellFired || channelHitsDelivered > 0)) {
            return false;
        }
        return super.shouldInterruptCastOnStop();
    }

    private boolean shouldApplyChannelAbortCooldown() {
        return isChanneled
                && channelHitsDelivered > 0
                && currentSpellId != null
                && cachedSpellEntry != null
                && channelTicksLeft > 0;
    }

    @Override
    public void tick() {
        if (checkCastInterrupt(mode == Mode.MELEE_CAST || mode == Mode.MAGIC_CAST)) {
            resetCastFlow();
            posture = Posture.MELEE_FOCUS;
            enterMeleeDuel();
            return;
        }

        tickCooldowns();
        if (bladeCooldown > 0) {
            bladeCooldown--;
        }

        LivingEntity target = resolveTarget();
        if (target == null && shouldAbortForMissingTarget()) {
            abortCastWithProgressCooldown(cachedSpellEntry != null ? cachedSpellEntry.value() : null);
            resetCastFlow();
            enterMeleeDuel();
            return;
        }

        if (target == null && castStep == CastStep.FOLLOW_THROUGH) {
            tickFollowThrough();
            return;
        }

        if (target != null && mode != Mode.REPOSITION) {
            tickMovement(target);
        }

        switch (mode) {
            case ENGAGE -> tickEngage(target);
            case MELEE_CAST, MAGIC_CAST -> tickCast(target);
            case MELEE_DUEL -> tickMeleeDuel(target);
            case REPOSITION -> tickReposition();
        }
    }

    private void tickEngage(@Nullable LivingEntity target) {
        if (target == null) {
            return;
        }

        if (hasValidConditionalSpell()) {
            Optional<GuardSpellManager.CategorizedSpell> defensive =
                    guard.getSpellManager().getBestSpell(GuardSpellManager.SpellCategory.SUPPORT,
                            s -> conditionalSpells.canCastSpell(s.spellId())
                                    && !isSpellOnCooldown(s.spellId()));
            if (defensive.isPresent()) {
                beginMagicCast(defensive.get());
                return;
            }
        }

        if (posture == Posture.MELEE_FOCUS && SpellbladeCombatHelper.hasMagicReady(guard, target)) {
            posture = Posture.MAGIC_FOCUS;
        }

        SpellbladeCombatHelper.Plan plan = SpellbladeCombatHelper.choosePlan(guard, target, posture);
        applyPlan(plan);
    }

    private void tickMeleeDuel(@Nullable LivingEntity target) {
        if (target == null) {
            mode = Mode.ENGAGE;
            return;
        }

        if (SpellbladeCombatHelper.hasMagicReady(guard, target)) {
            float dist = guard.distanceTo(target);
            if (dist >= SpellbladeCombatHelper.MAGIC_COMFORT_MIN
                    && guard.getVisibilityCache().canSee(target)) {
                posture = Posture.MAGIC_FOCUS;
                Optional<GuardSpellManager.CategorizedSpell> magic =
                        SpellbladeCombatHelper.getMagicSpell(guard, target);
                if (magic.isPresent()) {
                    beginMagicCast(magic.get());
                    return;
                }
            }
            posture = Posture.MAGIC_FOCUS;
            mode = Mode.ENGAGE;
            return;
        }

        if (posture == Posture.MAGIC_FOCUS) {
            posture = Posture.MELEE_FOCUS;
        }

        Optional<GuardSpellManager.CategorizedSpell> melee = SpellbladeCombatHelper.getMeleeSpell(guard);
        if (melee.isPresent() && SpellbladeCombatHelper.inMeleeSpellRange(guard, target, melee.get())) {
            beginMeleeCast(melee.get());
            return;
        }

        if (guard.isInAttackRange(target) && bladeCooldown <= 0) {
            bladeCooldown = 20;
            guard.stopUsingItem();
            if (guard.shieldCoolDown == 0) {
                guard.shieldCoolDown = 8;
            }
            guard.swingHand(Hand.MAIN_HAND);
            guard.tryAttack(target);
        }
    }

    private void applyPlan(SpellbladeCombatHelper.Plan plan) {
        debugPlan(plan.action());
        switch (plan.action()) {
            case REPOSITION -> mode = Mode.REPOSITION;
            case MELEE_SPELL -> {
                if (plan.spell() != null) {
                    beginMeleeCast(plan.spell());
                } else {
                    enterMeleeDuel();
                }
            }
            case MAGIC_SPELL -> {
                if (plan.spell() != null) {
                    beginMagicCast(plan.spell());
                }
            }
            case OPEN_FOR_MAGIC -> mode = Mode.ENGAGE;
            case MELEE_DUEL, PURSUE -> enterMeleeDuel();
            case WAIT -> enterMeleeDuel();
        }
    }

    private void enterMeleeDuelIfNeeded() {
        LivingEntity target = guard.getTarget();
        if (target == null) {
            return;
        }
        if (posture == Posture.MELEE_FOCUS || !SpellbladeCombatHelper.hasMagicReady(guard, target)) {
            enterMeleeDuel();
        }
    }

    private void enterMeleeDuel() {
        posture = Posture.MELEE_FOCUS;
        mode = Mode.MELEE_DUEL;
    }

    private void tickCast(@Nullable LivingEntity target) {
        switch (castStep) {
            case WINDUP -> {
                if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
                    spawnCastingParticles(cachedSpellEntry.value());
                }
                if (--windUpTicks <= 0) {
                    enterCasting();
                }
            }
            case CASTING -> tickCasting(target);
            case FOLLOW_THROUGH -> tickFollowThrough();
            default -> enterMeleeDuel();
        }
    }

    private void tickCasting(@Nullable LivingEntity target) {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return;
        }
        Spell spell = cachedSpellEntry.value();

        if (isChanneled && channelTicksLeft <= 0 && castFollowThroughTicks > 0) {
            if (--castFollowThroughTicks <= 0) {
                completeCast(spell);
            }
            return;
        }

        if (isChanneled) {
            tickChanneledCast(target, spell);
        } else if (!spellFired) {
            fireSpell(target, spell);
            spellFired = true;
            if (!shouldKeepCastingFlagThroughFollowThrough(spell)) {
                guard.setSpellCastProcess(null);
                guard.setCastingSpell(false);
                if (GuardCastVisuals.shouldClearCastAtSpellFire(spell)) {
                    GuardCastVisuals.endCastWindUp(guard);
                }
            }
            castFollowThroughTicks = computeFollowThroughTicks(spell);
            if (castFollowThroughTicks <= 0) {
                completeCast(spell);
            } else {
                castStep = CastStep.FOLLOW_THROUGH;
            }
        }
    }

    private void tickChanneledCast(@Nullable LivingEntity target, Spell spell) {
        if (channelTicksLeft > 0) {
            guard.setCastingSpell(true);
            if (target != null && target.isAlive()) {
                guard.getLookControl().lookAt(target, 30.0F, 30.0F);
            }
            if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                guard.setActiveBeam(spell.target.beam);
            }
            if (channelTicksLeft % 2 == 0) {
                spawnCastingParticles(spell);
            }

            if (channelTicksLeft == getChannelDuration(spell)) {
                if (meleeCastStyle) {
                    configureChannelCastVisuals(spell);
                }
                fireSpell(target, spell);
                castingDelayTicks = getChannelFireInterval(spell);
                channelTicksLeft--;
            } else if (--castingDelayTicks <= 0) {
                fireSpell(target, spell);
                castingDelayTicks = getChannelFireInterval(spell);
                channelTicksLeft--;
            }
            return;
        }

        guard.setActiveBeam(null);
        if (!GuardCastVisuals.hasReleaseAnimation(spell)) {
            guard.setSpellCastProcess(null);
            guard.setCastingSpell(false);
            GuardCastVisuals.endCastWindUp(guard);
        }
        castFollowThroughTicks = computeFollowThroughTicks(spell);
        if (castFollowThroughTicks <= 0) {
            completeCast(spell);
        } else {
            castStep = CastStep.FOLLOW_THROUGH;
        }
    }

    private void tickFollowThrough() {
        if (castFollowThroughTicks > 0 && --castFollowThroughTicks <= 0) {
            if (cachedSpellEntry != null) {
                completeCast(cachedSpellEntry.value());
            } else {
                resetCastFlow();
                enterMeleeDuel();
            }
        }
    }

    private void tickReposition() {
        guard.stopUsingItem();
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        Vec3d pos = guard.getTarget() != null
                ? NoPenaltyTargeting.findFrom(guard, 16, 7, guard.getTarget().getPos())
                : NoPenaltyTargeting.find(guard, 16, 7);
        if (pos != null) {
            guard.getNavigation().startMovingTo(pos.x, pos.y, pos.z, 1.15D);
        }
        mode = Mode.ENGAGE;
    }

    private void tickMovement(LivingEntity target) {
        boolean inCast = castStep == CastStep.WINDUP || castStep == CastStep.CASTING;
        SpellbladeCombatHelper.ModeHint modeHint = switch (mode) {
            case MELEE_CAST -> SpellbladeCombatHelper.ModeHint.MELEE_CAST;
            case MAGIC_CAST -> SpellbladeCombatHelper.ModeHint.MAGIC_CAST;
            case MELEE_DUEL -> SpellbladeCombatHelper.ModeHint.MELEE_DUEL;
            case REPOSITION -> SpellbladeCombatHelper.ModeHint.REPOSITION;
            default -> SpellbladeCombatHelper.ModeHint.ENGAGE;
        };
        boolean magicReady = SpellbladeCombatHelper.hasMagicReady(guard, target);
        SpellbladeCombatHelper.Action moveAction =
                SpellbladeCombatHelper.movementFor(posture, modeHint, inCast, magicReady);

        boolean canSee = guard.getVisibilityCache().canSee(target);
        var result = CombatMovementHelper.applySpellbladeMovement(
                guard, target, moveAction, canSee,
                seeTime, updatePathDelay, strafeCooldown, strafeLeft, inCast);
        seeTime = result.seeTime();
        updatePathDelay = result.updatePathDelay();
        strafeCooldown = result.strafeCooldown();
        strafeLeft = result.strafeLeft();
    }

    private void beginMeleeCast(GuardSpellManager.CategorizedSpell spell) {
        mode = Mode.MELEE_CAST;
        meleeCastStyle = true;
        startCast(spell, true);
    }

    private void beginMagicCast(GuardSpellManager.CategorizedSpell spell) {
        mode = Mode.MAGIC_CAST;
        meleeCastStyle = false;
        startCast(spell, false);
    }

    private void startCast(GuardSpellManager.CategorizedSpell spell, boolean meleeArchetype) {
        currentSpellId = spell.spellId();
        cachedSpellEntry = spell.entry();
        beginSpellCast(spell.spellId(), spell.entry(), meleeArchetype);
        Spell data = spell.entry().value();
        if (isSpellChanneled(data)) {
            isChanneled = true;
            channelTicksLeft = getChannelDuration(data);
            castingDelayTicks = 0;
            spellFired = false;
            if (meleeArchetype) {
                configureChannelCastVisuals(data);
            } else {
                guard.setCastingSpell(true);
            }
            castStep = CastStep.CASTING;
        } else {
            castStep = CastStep.WINDUP;
        }
    }

    private void enterCasting() {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return;
        }
        Spell spell = cachedSpellEntry.value();
        isChanneled = isSpellChanneled(spell);
        channelTicksLeft = getChannelDuration(spell);
        castingDelayTicks = 0;
        spellFired = false;
        if (isChanneled && !meleeCastStyle) {
            configureChannelCastVisuals(spell);
            guard.setCastingSpell(true);
        }
        castStep = CastStep.CASTING;
    }

    private void fireSpell(@Nullable LivingEntity target, Spell spell) {
        if (GuardCastVisuals.holdsCastThroughDelivery(spell)) {
            String holdAnim = GuardCastVisuals.resolveCastAnimationId(guard, spell);
            if (holdAnim == null || holdAnim.isEmpty()) {
                holdAnim = guard.getCastAnimationId();
            }
            GuardCastVisuals.beginCastPoseHold(guard, holdAnim);
        }

        int channelIndex = isChanneled ? channelHitsDelivered : 0;
        guard.setChannelTickIndex(channelIndex);
        if (isChanneled) {
            channelHitsDelivered++;
        }

        LivingEntity deliveryTarget = target;
        if (deliveryTarget == null && cachedSpellEntry != null) {
            float range = resolveCastRange(spell, SpellbladeCombatHelper.MAGIC_MAX_RANGE);
            deliveryTarget = SpellCombatTargeting.resolveHostileCastTarget(guard, range);
        }

        SpellContext context = createSpellContext(currentSpellId, cachedSpellEntry, deliveryTarget).build();
        SpellDelivery.deliverSpell(context, channelIndex);
    }

    private void completeCast(Spell spell) {
        boolean wasMagic = mode == Mode.MAGIC_CAST || !meleeCastStyle;

        if (meleeCastStyle && GuardSpellTimings.isMeleeDelivery(spell)) {
            scheduleMeleeVisualCleanup(spell);
        } else {
            GuardCastVisuals.completeCastWithRelease(guard, spell);
        }
        guard.stopUsingItem();
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        guard.setActiveBeam(null);
        applySpellCooldownOnComplete(currentSpellId, spell);
        resetCastFlow();

        if (wasMagic) {
            posture = Posture.MELEE_FOCUS;
            enterMeleeDuel();
        } else {
            enterMeleeDuel();
        }
    }

    private void scheduleMeleeVisualCleanup(Spell spell) {
        if (GuardCastVisuals.hasReleaseAnimation(spell)) {
            GuardCastVisuals.completeCastWithRelease(guard, spell);
            return;
        }
        int wait = GuardSpellTimings.meleeFollowThroughTicks(guard, spell);
        GuardCastVisuals.scheduleAfterTicks(guard, wait, () -> {
            if (!guard.isAlive()) {
                return;
            }
            if (!guard.getSwingAnimationId().isEmpty() || !guard.getReleaseAnimationId().isEmpty()) {
                return;
            }
            GuardCastVisuals.clearTransientVisualClips(guard);
        });
    }

    private void resetCastFlow() {
        currentSpellId = null;
        cachedSpellEntry = null;
        castStep = CastStep.IDLE;
        spellFired = false;
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        guard.setChannelTickIndex(0);
        isChanneled = false;
        channelTicksLeft = 0;
        castingDelayTicks = 0;
    }

    @Nullable
    private LivingEntity resolveTarget() {
        if (mode == Mode.MELEE_DUEL || mode == Mode.REPOSITION || mode == Mode.ENGAGE) {
            return guard.getTarget();
        }
        float range = cachedSpellEntry != null
                ? resolveCastRange(cachedSpellEntry.value(), SpellbladeCombatHelper.MAGIC_MAX_RANGE)
                : SpellbladeCombatHelper.MAGIC_MAX_RANGE;
        return SpellCombatTargeting.resolveHostileCastTarget(guard, range);
    }

    private boolean shouldAbortForMissingTarget() {
        if (castStep == CastStep.WINDUP) {
            return true;
        }
        if (castStep == CastStep.CASTING && isChanneled && channelTicksLeft > 0) {
            return true;
        }
        return castStep == CastStep.CASTING && !spellFired;
    }

    @Override
    protected void onCastAborted() {
        resetCastFlow();
        enterMeleeDuel();
        super.onCastAborted();
    }

    private boolean isTargetInCastRange(LivingEntity target, RegistryEntry<Spell> entry) {
        float range = SpellbladeCombatHelper.meleeCastRange(guard, entry);
        if (!meleeCastStyle) {
            range = guard.getSpellManager().getAugmentedRange(entry);
            if (range <= 0) {
                range = SpellbladeCombatHelper.MAGIC_MAX_RANGE;
            }
        }
        return guard.squaredDistanceTo(target) <= range * range;
    }

    private int computeFollowThroughTicks(Spell spell) {
        int deliverDelay = spell.deliver != null ? Math.max(0, spell.deliver.delay) : 0;
        int releaseWait = GuardSpellTimings.postDeliverWaitTicks(guard, spell);
        if (isChanneled) {
            return Math.max(GuardSpellTimings.channeledMeleeFollowThroughTicks(guard, spell), releaseWait);
        }
        if (GuardCastVisuals.hasReleaseAnimation(spell) || GuardCastVisuals.holdsCastThroughDelivery(spell)) {
            return Math.max(releaseWait, deliverDelay + releaseWait);
        }
        return Math.max(releaseWait, deliverDelay + 2);
    }

    private static boolean shouldKeepCastingFlagThroughFollowThrough(Spell spell) {
        return GuardCastVisuals.hasReleaseAnimation(spell)
                || GuardCastVisuals.holdsCastThroughDelivery(spell);
    }

    private boolean hasValidConditionalSpell() {
        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return guard.getSpellManager().getBestSpell(GuardSpellManager.SpellCategory.SUPPORT,
                s -> conditionalSpells.canCastSpell(s.spellId())
                        && !isSpellOnCooldown(s.spellId())).isPresent();
    }

    private void debugPlan(SpellbladeCombatHelper.Action action) {
        if (!GuardDebugManager.hasWatchers(guard)) {
            return;
        }
        GuardDebugManager.broadcast(guard,
                "spellblade [" + posture + "] → " + action.name(), Formatting.LIGHT_PURPLE);
    }
}
