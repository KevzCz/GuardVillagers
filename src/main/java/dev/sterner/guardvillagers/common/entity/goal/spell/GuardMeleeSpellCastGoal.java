package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_power.api.SpellSchool;

import java.util.EnumSet;
import java.util.Optional;

public class GuardMeleeSpellCastGoal extends BaseSpellGoal {
    private static final float MELEE_RANGE = 5.0F;
    private static final float PURSUIT_PADDING = 2.0F;

    private SpellState spellState = SpellState.UNCHARGED;

    private int seeTime;
    private int updatePathDelay;
    private int strafeCooldown;
    private boolean strafeLeft;
    private int castFollowThroughTicks;
    private int channelHitsDelivered;

    private enum SpellState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        CASTING
    }

    public GuardMeleeSpellCastGoal(GuardEntity guard) {
        super(guard);
    }

    @Override
    public boolean canStart() {
        if (guard.isSpellCastBusy()) {
            return false;
        }
        if (guard.spellCastGraceTicks > 0) {
            return false;
        }
        if (dev.sterner.guardvillagers.common.entity.GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (!guard.getSpellManager().shouldUseMeleeWeaponCasting()) {
            return false;
        }
        Identifier nextSpell = getNextCastableSpell();
        if (nextSpell == null) {
            return false;
        }
        if (isSelfBuffSpellById(nextSpell)) {
            return true;
        }
        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!guard.getVisibilityCache().canSee(target)) {
            return false;
        }
        double distSq = guard.squaredDistanceTo(target);
        return distSq <= pursuitRange() * pursuitRange();
    }

    @Override
    public boolean shouldContinue() {
        if (dev.sterner.guardvillagers.common.entity.GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (spellState == SpellState.CASTING && spellFired && castFollowThroughTicks <= 0) {
            return false;
        }
        if (spellState == SpellState.CHARGING || spellState == SpellState.CHARGED || spellState == SpellState.CASTING) {
            return true;
        }

        if (spellState != SpellState.UNCHARGED) {
            LivingEntity target = guard.getTarget();
            return target == null || target.isAlive();
        }

        // Self-buff spells can continue without a target
        Identifier nextSpell = getNextCastableSpell();
        if (nextSpell != null && isSelfBuffSpellById(nextSpell)) {
            return true;
        }

        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (getNextCastableSpell() == null) {
            return false;
        }
        double distSq = guard.squaredDistanceTo(target);
        return distSq <= pursuitRange() * pursuitRange();
    }

    @Override
    public void start() {
        super.start();
        this.spellState = SpellState.UNCHARGED;
        this.seeTime = 0;
        this.updatePathDelay = 0;
        this.strafeCooldown = 0;
        this.strafeLeft = guard.getRandom().nextBoolean();
        guard.setAttacking(true);
        updateMovementControls();
    }

    @Override
    public void stop() {
        guard.setAttacking(false);
        if (shouldApplyChannelAbortCooldown()) {
            applySpellCooldownOnAbort(currentSpellId, cachedSpellEntry.value());
            debugCast("⏹ channel aborted — proportional cooldown", Formatting.RED);
        }
        super.stop();
        this.spellState = SpellState.UNCHARGED;
        this.castFollowThroughTicks = 0;
        this.channelHitsDelivered = 0;
    }

    private boolean shouldApplyChannelAbortCooldown() {
        return isChanneled
                && channelHitsDelivered > 0
                && currentSpellId != null
                && cachedSpellEntry != null
                && channelTicksLeft > 0;
    }

    @Override
    protected void resetSpellState() {
        super.resetSpellState();
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
    }

    @Override
    protected boolean shouldInterruptCastOnStop() {
        if (spellState == SpellState.CASTING && spellFired) {
            return false;
        }
        return super.shouldInterruptCastOnStop();
    }

    @Override
    public void tick() {
        if (checkCastInterrupt(spellState != SpellState.UNCHARGED)) {
            spellState = SpellState.UNCHARGED;
            updateMovementControls();
            return;
        }

        updateMovementControls();
        tickCooldowns();

        Spell activeSpell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        float castRange = resolveCastRange(activeSpell, (float) MELEE_RANGE);
        LivingEntity target = spellState == SpellState.UNCHARGED
                ? guard.getTarget()
                : SpellCombatTargeting.resolveHostileCastTarget(guard, castRange);

        if (target == null && shouldAbortCastForMissingTarget() && !isSelfBuffSpell(activeSpell)) {
            debugCast("⚠ no target — aborting with proportional cooldown", Formatting.YELLOW);
            abortCastWithProgressCooldown(activeSpell);
            spellState = SpellState.UNCHARGED;
            updateMovementControls();
            return;
        }

        if (target == null) {
            if (spellState == SpellState.CASTING && castFollowThroughTicks > 0 && cachedSpellEntry != null) {
                handleCasting(null);
                return;
            }
            if (spellState == SpellState.CASTING && spellFired && cachedSpellEntry != null) {
                finishSingleCastCleanup(cachedSpellEntry.value());
                return;
            }
            stop();
            return;
        }

        if (target != null) {
            tickCombatMovement(target);
        }

        switch (this.spellState) {
            case UNCHARGED -> handleUncharged(target);
            case CHARGING -> handleCharging();
            case CHARGED -> handleCharged();
            case CASTING -> handleCasting(target);
        }
    }

    private void tickCombatMovement(LivingEntity target) {
        boolean inCastPhase = spellState == SpellState.CHARGING || spellState == SpellState.CASTING;
        boolean canSee = guard.getVisibilityCache().canSee(target);
        var result = CombatMovementHelper.applyMeleeCombatMovement(
                guard,
                target,
                canSee,
                seeTime,
                updatePathDelay,
                strafeCooldown,
                strafeLeft,
                inCastPhase,
                MELEE_RANGE,
                castMovementSpeed()
        );
        seeTime = result.seeTime();
        updatePathDelay = result.updatePathDelay();
        strafeCooldown = result.strafeCooldown();
        strafeLeft = result.strafeLeft();
    }

    private float castMovementSpeed() {
        if (cachedSpellEntry == null) {
            return 0.0f;
        }
        Spell spell = cachedSpellEntry.value();
        if (spell.active != null && spell.active.cast != null) {
            return spell.active.cast.movement_speed;
        }
        return 0.0f;
    }

    private void updateMovementControls() {
        setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    private void handleUncharged(LivingEntity target) {
        Identifier spellId = getNextCastableSpell();
        if (spellId == null) {
            return;
        }

        Optional<RegistryEntry.Reference<Spell>> optEntry =
                SpellRegistry.from(guard.getWorld()).getEntry(spellId);

        if (optEntry.isEmpty()) {
            return;
        }

        if (!isSelfBuffSpell(optEntry.get().value()) && !isTargetInCastRange(target, optEntry.get())) {
            return;
        }

        beginSpellCast(spellId, optEntry.get(), true);
        Spell spell = optEntry.get().value();
        if (isSpellChanneled(spell)) {
            enterCastingState();
            debugCast("channel=" + channelTicksLeft + " interval=" + getChannelFireInterval(spell), Formatting.AQUA);
        } else {
            this.spellState = SpellState.CHARGING;
            debugCast("windup=" + windUpTicks + " ch=0", Formatting.AQUA);
        }
        updateMovementControls();
    }

    private void handleCharging() {
        if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (--windUpTicks <= 0) {
            enterCastingState();
            Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
            float castRange = resolveCastRange(spell, (float) MELEE_RANGE);
            LivingEntity fireTarget = SpellCombatTargeting.resolveHostileCastTarget(guard, castRange);
            handleCasting(fireTarget);
        }
    }

    private void handleCharged() {
        enterCastingState();
        Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        float castRange = resolveCastRange(spell, (float) MELEE_RANGE);
        LivingEntity fireTarget = SpellCombatTargeting.resolveHostileCastTarget(guard, castRange);
        handleCasting(fireTarget);
    }

    private void enterCastingState() {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return;
        }

        Spell spell = cachedSpellEntry.value();
        isChanneled = isSpellChanneled(spell);
        channelTicksLeft = getChannelDuration(spell);
        castingDelayTicks = 0;
        spellFired = false;

        if (isChanneled) {
            configureChannelCastVisuals(spell);
        }

        spellState = SpellState.CASTING;
        updateMovementControls();
    }

    private void handleCasting(LivingEntity target) {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return;
        }

        Spell spell = cachedSpellEntry.value();

        if (isChanneled && channelTicksLeft <= 0 && castFollowThroughTicks > 0) {
            if (--castFollowThroughTicks <= 0) {
                finishCastingAfterChannel(spell);
            }
            return;
        }

        if (isChanneled) {
            handleChanneledCast(target, spell);
        } else {
            handleSingleCast(target, spell);
        }
    }

    private void handleChanneledCast(LivingEntity target, Spell spell) {
        if (channelTicksLeft > 0) {
            if (channelTicksLeft % 2 == 0) {
                spawnCastingParticles(spell);
            }

            if (channelTicksLeft == getChannelDuration(spell)) {
                castSpell(target, spell);
                castingDelayTicks = getChannelFireInterval(spell);
                channelTicksLeft--;
            } else {
                if (--castingDelayTicks <= 0) {
                    castSpell(target, spell);
                    castingDelayTicks = getChannelFireInterval(spell);
                }
                channelTicksLeft--;
            }
        } else {
            if (!GuardCastVisuals.hasReleaseAnimation(spell)) {
                guard.setSpellCastProcess(null);
                guard.setCastingSpell(false);
                GuardCastVisuals.endCastWindUp(guard);
            }
            castFollowThroughTicks = computeCastFollowThroughTicks(spell);
            if (castFollowThroughTicks <= 0) {
                finishCastingAfterChannel(spell);
            }
        }
    }

    private void handleSingleCast(LivingEntity target, Spell spell) {
        if (!spellFired) {
            castSpell(target, spell);
            spellFired = true;
            if (!shouldKeepCastingFlagThroughFollowThrough(spell)) {
                guard.setSpellCastProcess(null);
                guard.setCastingSpell(false);
                if (GuardCastVisuals.shouldClearCastAtSpellFire(spell)) {
                    GuardCastVisuals.endCastWindUp(guard);
                }
            }
            castFollowThroughTicks = computeCastFollowThroughTicks(spell);
            if (castFollowThroughTicks <= 0) {
                completeSingleCast(spell);
            }
            return;
        }

        if (castFollowThroughTicks > 0 && --castFollowThroughTicks <= 0) {
            completeSingleCast(spell);
        }
    }

    private int computeCastFollowThroughTicks(Spell spell) {
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

    private void completeSingleCast(Spell spell) {
        Identifier spellId = currentSpellId;
        scheduleSpellVisualCleanup(spell);
        if (GuardCastVisuals.hasReleaseAnimation(spell)) {
            GuardCastVisuals.beginReleasePhase(guard, spell);
        }
        GuardCastVisuals.completeCastWithRelease(guard, spell);
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        applySpellCooldownOnComplete(spellId, spell);
        finishSingleCastCleanup(spell);
    }

    private void scheduleSpellVisualCleanup(Spell spell) {
        if (GuardCastVisuals.hasReleaseAnimation(spell)) {
            return;
        }
        if (GuardSpellTimings.isMeleeDelivery(spell)) {
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
            return;
        }
        GuardCastVisuals.completeCastWithRelease(guard, spell);
    }

    private void finishSingleCastCleanup(Spell spell) {
        guard.stopUsingItem();
        spellState = SpellState.UNCHARGED;
        spellFired = false;
        currentSpellId = null;
        cachedSpellEntry = null;
        guard.setChannelTickIndex(0);
        updateMovementControls();
    }

    private void castSpell(LivingEntity target, Spell spell) {
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
            debugCast("channel hit #" + channelHitsDelivered + " idx=" + channelIndex
                    + " left=" + channelTicksLeft, Formatting.GREEN);
        } else if (GuardCastVisuals.holdsCastThroughDelivery(spell)) {
            debugCast("spell fire (hold→release)", Formatting.GOLD);
        } else {
            debugCast("spell fire", Formatting.GOLD);
        }
        LivingEntity deliveryTarget = SupportSpellCasting.resolveDeliveryTarget(spell, target);
        SpellContext context = createSpellContext(currentSpellId, cachedSpellEntry, deliveryTarget).build();
        SpellDelivery.deliverSpell(context, channelIndex);
    }

    private boolean shouldAbortCastForMissingTarget() {
        if (spellState == SpellState.CHARGING || spellState == SpellState.CHARGED) {
            return true;
        }
        if (spellState == SpellState.CASTING && isChanneled && channelTicksLeft > 0) {
            return true;
        }
        return spellState == SpellState.CASTING && !spellFired;
    }

    @Override
    protected void onCastAborted() {
        spellState = SpellState.UNCHARGED;
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        guard.setChannelTickIndex(0);
        updateMovementControls();
        super.onCastAborted();
    }

    private void debugCast(String message, Formatting color) {
        if (currentSpellId == null || !GuardDebugManager.hasAnimationDebugWatchers(guard)) {
            return;
        }
        GuardDebugManager.broadcastAnimation(guard,
                "▶ " + currentSpellId.getPath() + " [" + spellState + "] " + message, color);
    }

    private void finishCastingAfterChannel(Spell spell) {
        guard.stopUsingItem();
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        GuardCastVisuals.completeCastWithRelease(guard, spell);
        applySpellCooldownOnComplete(currentSpellId, spell);
        resetAfterCast();
    }

    private void resetAfterCast() {
        spellState = SpellState.UNCHARGED;
        currentSpellId = null;
        cachedSpellEntry = null;
        spellFired = false;
        castFollowThroughTicks = 0;
        guard.setChannelTickIndex(0);
        updateMovementControls();
    }

    private Identifier getNextCastableSpell() {
        var manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> meleeSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.MELEE,
                        s -> !isSpellOnCooldown(s.spellId())
                                && isArchetypeCompatibleWithMelee(s));

        if (meleeSpell.isPresent()) {
            return meleeSpell.get().spellId();
        }

        return manager.getBestCastableMeleeSelfBuff()
                .map(GuardSpellManager.CategorizedSpell::spellId)
                .orElse(null);
    }

    private boolean isArchetypeCompatibleWithMelee(GuardSpellManager.CategorizedSpell spell) {
        return GuardSpellManager.isMeleeArchetype(spell.entry().value());
    }

    private static boolean isSelfBuffSpell(net.spell_engine.api.spell.Spell spell) {
        if (spell == null) return false;
        return spell.deliver != null && spell.deliver.type == net.spell_engine.api.spell.Spell.Delivery.Type.STASH_EFFECT;
    }

    private boolean isSelfBuffSpellById(Identifier spellId) {
        return getSpellEntry(spellId)
                .map(e -> isSelfBuffSpell(e.value()))
                .orElse(false);
    }

    private float pursuitRange() {
        float max = MELEE_RANGE;
        for (GuardSpellManager.CategorizedSpell categorized
                : guard.getSpellManager().getSpells(GuardSpellManager.SpellCategory.MELEE)) {
            Spell spell = categorized.entry().value();
            if (GuardSpellManager.isOnHitMeleeSpell(spell)) {
                continue;
            }
            if (!isArchetypeCompatibleWithMelee(categorized)) {
                continue;
            }
            float augmented = castRangeFor(categorized.entry());
            if (augmented > max) {
                max = augmented;
            }
        }
        return max + PURSUIT_PADDING;
    }

    private boolean isTargetInCastRange(LivingEntity target, RegistryEntry<Spell> entry) {
        float range = castRangeFor(entry);
        return guard.squaredDistanceTo(target) <= range * range;
    }

    private float castRangeFor(RegistryEntry<Spell> entry) {
        Spell spell = entry.value();
        if (spell.range_mechanic == net.spell_engine.api.spell.Spell.RangeMechanic.MELEE) {
            return MELEE_RANGE;
        }
        float range = guard.getSpellManager().getAugmentedRange(entry);
        return range > 0 ? range : MELEE_RANGE;
    }
}
