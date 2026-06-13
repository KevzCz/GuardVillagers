package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.*;
import dev.sterner.guardvillagers.common.entity.*;
import dev.sterner.guardvillagers.common.entity.goal.spell.*;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.*;
import net.minecraft.registry.entry.*;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.spell_engine.api.spell.*;
import net.spell_engine.api.spell.registry.*;

import java.util.*;

public class GuardCastSpellGoal extends BaseRangedSpellGoal {
    private static final float ATTACK_RADIUS = 16.0F;

    private final ConditionalSpellGoal conditionalSpells;

    private SpellState spellState = SpellState.UNCHARGED;
    private int castFollowThroughTicks = 0;
    private int channelHitsDelivered = 0;
    private int friendlyCheckCooldown = 0;
    private boolean lastFriendlyInSight = false;
    private double wantedX;
    private double wantedY;
    private double wantedZ;

    private enum SpellState {
        UNCHARGED,
        CHARGING,
        CHARGED,
        CASTING,
        FIND_NEW_POSITION
    }

    public GuardCastSpellGoal(GuardEntity guard) {
        super(guard, 1.0D, ATTACK_RADIUS);
        this.conditionalSpells = new ConditionalSpellGoal(guard);
        registerSpellConditions();
    }

    private void registerSpellConditions() {
        conditionalSpells.registerHealthThreshold("wizards:frost_shield", 0.3f);
    }

    @Override
    public boolean canStart() {
        if (guard.isSpellCastBusy()) {
            return false;
        }
        if (guard.spellCastGraceTicks > 0) {
            return false;
        }
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (!guard.getSpellManager().shouldUseProjectileCasting()) {
            return false;
        }
        if (guard.getSpellManager().hasCastablePhysicalMeleeSpell()) {
            return false;
        }

        if (!isInCombat()) {
            return false;
        }

        if (hasValidConditionalSpell()) {
            return true;
        }

        LivingEntity target = guard.getTarget();
        return target != null && target.isAlive() && hasCastableSpell(target);
    }

    @Override
    public boolean shouldContinue() {
        if (GuardItemTags.isSpellbladeWeapon(guard.getMainHandStack())) {
            return false;
        }
        if (!guard.getSpellManager().shouldUseProjectileCasting()) {
            return false;
        }

        
        if (guard.isCastingSpell() && spellState == SpellState.UNCHARGED && currentSpellId == null) {
            return false;
        }
        if (guard.getSpellManager().hasCastablePhysicalMeleeSpell()) {
            return false;
        }

        LivingEntity target = guard.getTarget();
        boolean hasLiveTarget = target != null && target.isAlive();

        if (spellState == SpellState.CHARGING || spellState == SpellState.CHARGED || spellState == SpellState.CASTING) {
            return true;
        }

        if (spellState != SpellState.UNCHARGED) {
            return hasLiveTarget || spellState == SpellState.FIND_NEW_POSITION;
        }

        if (!hasLiveTarget) {
            return false;
        }

        return hasCastableSpell(target) || hasValidConditionalSpell();
    }

    @Override
    public void start() {
        super.start();
        this.spellState = SpellState.UNCHARGED;
    }

    @Override
    public void stop() {
        if (shouldApplyChannelAbortCooldown()) {
            applySpellCooldownOnAbort(currentSpellId, cachedSpellEntry.value());
        }
        super.stop();
        this.spellState = SpellState.UNCHARGED;
        this.cachedSpellEntry = null;
        this.channelHitsDelivered = 0;
        this.friendlyCheckCooldown = 0;
        this.lastFriendlyInSight = false;
    }

    @Override
    protected void resetSpellState() {
        super.resetSpellState();
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
    }

    @Override
    protected boolean shouldInterruptCastOnStop() {
        if (spellState == SpellState.CASTING && (spellFired || channelHitsDelivered > 0)) {
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
        if (checkCastInterrupt(spellState != SpellState.UNCHARGED
                && spellState != SpellState.FIND_NEW_POSITION)) {
            spellState = SpellState.UNCHARGED;
            return;
        }

        tickCooldowns();

        Spell activeSpell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        float castRange = activeSpell != null
                ? resolveCastRange(activeSpell, resolveMovementCastRange())
                : resolveMovementCastRange();
        LivingEntity target = spellState == SpellState.UNCHARGED || spellState == SpellState.FIND_NEW_POSITION
                ? guard.getTarget()
                : SpellCombatTargeting.resolveHostileCastTarget(guard, castRange);

        if (target == null && shouldAbortCastForMissingTarget()) {
            abortCastWithProgressCooldown(activeSpell);
            spellState = SpellState.UNCHARGED;
            return;
        }

        if (target == null && spellState == SpellState.CASTING && castFollowThroughTicks > 0) {
            handleCasting(null);
            return;
        }

        boolean inAimPhase = spellState == SpellState.CHARGING
                || spellState == SpellState.CHARGED
                || spellState == SpellState.CASTING;
        boolean canRun = spellState == SpellState.UNCHARGED;

        if (guard.isCastingMeleeSpell()) {
            guard.getNavigation().stop();
            guard.getMoveControl().strafeTo(0.0F, 0.0F);
        }

        if (target != null && target.isAlive()
                && !(guard.isCastingMeleeSpell() || (guard.isCastingSpell() && spellState == SpellState.UNCHARGED))) {
            boolean canSee = guard.getVisibilityCache().canSee(target);
            updateCombatMovement(target, inAimPhase, canRun, castRange);

            if (friendlyInLineOfSight() && GuardVillagersConfig.friendlyFire
                    && spellState != SpellState.CASTING) {
                this.spellState = SpellState.FIND_NEW_POSITION;
            }
        }

        switch (this.spellState) {
            case FIND_NEW_POSITION -> handleFindNewPosition();
            case UNCHARGED -> handleUncharged(target);
            case CHARGING -> handleCharging();
            case CHARGED -> handleCharged();
            case CASTING -> handleCasting(target);
        }
    }

    private void handleFindNewPosition() {
        guard.stopUsingItem();
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);

        if (findNewPosition()) {
            guard.getNavigation().startMovingTo(wantedX, wantedY, wantedZ,
                    guard.isSneaking() ? 0.5D : 1.2D);
        }

        this.spellState = SpellState.UNCHARGED;
    }

    private void handleUncharged(LivingEntity target) {
        Identifier spellId = getNextCastableSpell(target);
        if (spellId == null) {
            guard.setAttacking(false);
            this.setControls(EnumSet.noneOf(Control.class));
            return;
        }

        Optional<RegistryEntry.Reference<Spell>> optEntry =
                SpellRegistry.from(guard.getWorld()).getEntry(spellId);

        if (optEntry.isEmpty()) return;

        beginSpellCast(spellId, optEntry.get(), false);
        this.spellState = SpellState.CHARGING;
    }

    private void handleCharging() {
        if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (--windUpTicks <= 0) {
            enterCastingState();
            Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
            float castRange = resolveCastRange(spell, resolveMovementCastRange());
            LivingEntity fireTarget = SpellCombatTargeting.resolveHostileCastTarget(guard, castRange);
            handleCasting(fireTarget);
        }
    }

    private void handleCharged() {
        enterCastingState();
        Spell spell = cachedSpellEntry != null ? cachedSpellEntry.value() : null;
        float castRange = resolveCastRange(spell, resolveMovementCastRange());
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
            guard.setCastingSpell(true);
        }

        spellState = SpellState.CASTING;
    }

    private void handleCasting(LivingEntity target) {
        if (currentSpellId == null || cachedSpellEntry == null) return;

        Spell spell = cachedSpellEntry.value();

        if (isChanneled) {
            handleChanneledCast(target, spell);
        } else {
            handleSingleCast(target, spell);
        }
    }

    private void handleChanneledCast(LivingEntity target, Spell spell) {
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
            guard.setActiveBeam(null);
            finishCasting();
        }
    }

    private void finishCasting() {
        guard.stopUsingItem();
        guard.setActiveBeam(null);

        Identifier spellId = currentSpellId;
        Spell spell = cachedSpellEntry.value();
        GuardCastVisuals.completeCastWithRelease(guard, spell);
        applySpellCooldownOnComplete(spellId, spell);
        resetAfterCast();
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
        super.onCastAborted();
    }

    private void resetAfterCast() {
        spellState = SpellState.UNCHARGED;
        currentSpellId = null;
        cachedSpellEntry = null;
        spellFired = false;
        castFollowThroughTicks = 0;
        channelHitsDelivered = 0;
        guard.setChannelTickIndex(0);
    }

    private void handleSingleCast(LivingEntity target, Spell spell) {
        if (!spellFired) {
            castSpell(target, spell);
            spellFired = true;
            guard.setSpellCastProcess(null);

            if (GuardCastVisuals.hasReleaseAnimation(spell)) {
                GuardCastVisuals.beginReleasePhase(guard, spell);
                int deliverDelay = spell.deliver != null ? Math.max(0, spell.deliver.delay) : 0;
                castFollowThroughTicks = deliverDelay + GuardCastVisuals.releaseFollowThroughTicks(guard, spell);
            } else {
                guard.setCastingSpell(false);
                if (GuardCastVisuals.shouldClearCastAtSpellFire(spell)) {
                    GuardCastVisuals.endCastWindUp(guard);
                }
                castFollowThroughTicks = computeCastFollowThroughTicks(spell);
            }

            if (castFollowThroughTicks <= 0) {
                finishCasting();
            }
            return;
        }

        if (castFollowThroughTicks > 0 && --castFollowThroughTicks <= 0) {
            finishCasting();
        }
    }

    private int computeCastFollowThroughTicks(Spell spell) {
        int deliverDelay = spell.deliver != null ? Math.max(0, spell.deliver.delay) : 0;
        if (GuardCastVisuals.hasReleaseAnimation(spell)) {
            return Math.max(GuardSpellTimings.postDeliverWaitTicks(guard, spell), deliverDelay + 2);
        }
        return Math.max(GuardSpellTimings.postDeliverWaitTicks(guard, spell), deliverDelay + 2);
    }

    private void castSpell(LivingEntity target, Spell spell) {
        int channelIndex = isChanneled ? Math.max(0, getChannelDuration(spell) - channelTicksLeft - 1) : 0;
        guard.setChannelTickIndex(channelIndex);
        SpellContext context = createSpellContext(currentSpellId, cachedSpellEntry, target).build();
        SpellDelivery.deliverSpell(context, channelIndex);
        if (isChanneled) {
            channelHitsDelivered++;
        }
    }

    private boolean hasCastableSpell(LivingEntity target) {
        return getNextCastableSpell(target) != null;
    }

    private Identifier getNextCastableSpell(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return null;
        }

        GuardSpellManager manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> offensive = manager.getBestCastableCombatSong(target)
                .filter(s -> guard.getVisibilityCache().canSee(target)
                        && conditionalSpells.canCastSpell(s.spellId())
                        && isArchetypeCompatibleWithMagicWeapon(s));

        if (offensive.isPresent()) {
            return offensive.get().spellId();
        }

        return manager.getBestCastableSelfSupportSpell()
                .filter(s -> conditionalSpells.canCastSpell(s.spellId())
                        && isArchetypeCompatibleWithMagicWeapon(s))
                .map(GuardSpellManager.CategorizedSpell::spellId)
                .orElse(null);
    }

    private float resolveMovementCastRange() {
        LivingEntity target = guard.getTarget();
        if (target == null) {
            return ATTACK_RADIUS;
        }
        return guard.getSpellManager().getBestCastableCombatSong(target)
                .map(s -> guard.getSpellManager().effectiveCastRange(s.entry()))
                .or(() -> guard.getSpellManager().getBestCastableSelfSupportSpell()
                        .map(s -> guard.getSpellManager().effectiveCastRange(s.entry())))
                .orElse(ATTACK_RADIUS);
    }

    private boolean isArchetypeCompatibleWithMagicWeapon(GuardSpellManager.CategorizedSpell spell) {
        return GuardSpellManager.isMagicCastingSpell(spell.entry().value());
    }

    private boolean hasValidConditionalSpell() {
        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) return false;

        var manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> supportSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.SUPPORT,
                        s -> conditionalSpells.canCastSpell(s.spellId())
                                && !isSpellOnCooldown(s.spellId())
                                && GuardSpellManager.isMagicArchetype(s.entry().value()));

        return supportSpell.isPresent();
    }

    private boolean isInCombat() {
        LivingEntity target = guard.getTarget();
        return target != null && target.isAlive();
    }

    private boolean friendlyInLineOfSight() {
        if (--friendlyCheckCooldown > 0) {
            return lastFriendlyInSight;
        }
        friendlyCheckCooldown = 5;

        List<Entity> nearby = guard.getWorld().getOtherEntities(guard, guard.getBoundingBox().expand(5.0D));
        for (Entity entity : nearby) {
            if (entity == guard.getTarget()) continue;

            boolean isFriendly = entity.getType() == EntityType.VILLAGER
                    || entity.getType() == dev.sterner.guardvillagers.GuardVillagers.GUARD_VILLAGER
                    || entity.getType() == EntityType.IRON_GOLEM
                    || entity == guard.getOwner();

            if (isFriendly && guard.canSee(entity) && guard.distanceTo(entity) <= 4.0D) {
                Vec3d toFriend = entity.getPos().subtract(guard.getPos()).normalize();
                Vec3d facing = guard.getRotationVector();
                if (facing.dotProduct(toFriend) > 0.9D) {
                    lastFriendlyInSight = true;
                    return true;
                }
            }
        }

        lastFriendlyInSight = false;
        return false;
    }

    private boolean findNewPosition() {
        Vec3d pos = getNewPosition();
        if (pos != null) {
            this.wantedX = pos.x;
            this.wantedY = pos.y;
            this.wantedZ = pos.z;
            return true;
        }
        return false;
    }

    private Vec3d getNewPosition() {
        return isValidTarget()
                ? NoPenaltyTargeting.findFrom(guard, 16, 7, guard.getTarget().getPos())
                : NoPenaltyTargeting.find(guard, 16, 7);
    }
}