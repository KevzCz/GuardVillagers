package dev.sterner.guardvillagers.common.entity.goal;

import dev.sterner.guardvillagers.common.ai.CombatMovementHelper;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.ConditionalSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardCastVisuals;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellContext;
import dev.sterner.guardvillagers.common.entity.goal.spell.SpellDelivery;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.item.BowItem;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;

import java.util.EnumSet;
import java.util.Optional;

public class RangedBowAttackPassiveGoal<T extends GuardEntity & RangedAttackMob> extends BaseSpellGoal {
    private final T actor;
    private final double speed;
    private final float squaredRange;
    private final ConditionalSpellGoal conditionalSpells;

    private int cooldown = 0;
    private int targetSeeingTicker = 0;
    private int combatTicks = -1;
    private boolean movingToLeft = false;
    private boolean backward = false;

    private enum BowSpellState { NONE, WINDING_UP, CHANNELING }
    private BowSpellState bowSpellState = BowSpellState.NONE;

    public RangedBowAttackPassiveGoal(T actor, double speed, int attackInterval, float range) {
        super(actor);
        this.actor = actor;
        this.speed = speed;
        this.squaredRange = range * range;
        this.conditionalSpells = new ConditionalSpellGoal(actor);
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        if (actor.isCastingMeleeSpell()) {
            return false;
        }
        return actor.getTarget() != null && isHoldingBow();
    }

    @Override
    public boolean shouldContinue() {
        if (actor.isCastingMeleeSpell()) {
            return false;
        }
        return canStart() || !actor.getNavigation().isIdle();
    }

    @Override
    public void start() {
        actor.setAttacking(true);
    }

    @Override
    public void stop() {
        super.stop();
        actor.setAttacking(false);
        actor.clearActiveItem();
        resetBowSpellState();
    }

    @Override
    public void tick() {
        LivingEntity target = actor.getTarget();
        if (target == null || !target.isAlive()) return;

        updateBowMovement(target);
        tickCooldowns();

        if (bowSpellState != BowSpellState.NONE) {
            actor.getLookControl().lookAt(target, 30.0F, 30.0F);
            maintainBowDrawDuringSpell();
            handleBowSpellCasting(target);
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (!actor.isCastingSpell() && tryStartBowSpellCast(target)) {
            return;
        }

        if (actor.isUsingItem()) {
            handleBowDrawn(target);
        } else if (targetSeeingTicker >= -60) {
            if (!actor.isCastingSpell()) {
                GuardCastVisuals.clearTransientVisualClips(actor);
            }
            actor.setCurrentHand(Hand.MAIN_HAND);
        }
    }

    private void updateBowMovement(LivingEntity target) {
        var mv = CombatMovementHelper.applyBowOrbitMovement(
                actor, target, this.speed, this.squaredRange,
                this.targetSeeingTicker, this.combatTicks,
                this.movingToLeft, this.backward
        );

        this.targetSeeingTicker = mv.targetSeeingTicker();
        this.combatTicks = mv.combatTicks();
        this.movingToLeft = mv.movingToLeft();
        this.backward = mv.backward();
    }

    private void handleBowDrawn(LivingEntity target) {
        int useTime = actor.getItemUseTime();
        boolean canSee = actor.getVisibilityCache().canSee(target);

        if (!canSee || useTime < 30) return;

        if (useTime >= 30) {
            actor.clearActiveItem();
            ((RangedAttackMob) actor).shootAt(target, BowItem.getPullProgress(useTime));
        }
    }

    private boolean tryStartBowSpellCast(LivingEntity target) {
        if (!actor.getVisibilityCache().canSee(target)) {
            return false;
        }

        Identifier spellId = pickBowSpellId(target);
        if (spellId == null) {
            return false;
        }

        return getSpellEntry(spellId).map(entry -> {
            Spell spell = entry.value();
            currentSpellId = spellId;
            cachedSpellEntry = entry;
            windUpTicks = getWindUpTicks(spell);
            isChanneled = isSpellChanneled(spell);
            channelTicksLeft = getChannelDuration(spell);
            castingDelayTicks = 0;

            actor.setCurrentHand(Hand.MAIN_HAND);
            if (windUpTicks > 0) {
                bowSpellState = BowSpellState.WINDING_UP;
                GuardCastVisuals.beginBowCast(actor, entry, spell);
            } else if (isChanneled && channelTicksLeft > 0) {
                bowSpellState = BowSpellState.CHANNELING;
                GuardCastVisuals.beginBowCast(actor, entry, spell);
                configureChannelCastVisuals(spell);
            } else {
                finishBowSpellCast(target, spell, entry);
            }
            return true;
        }).orElse(false);
    }

    private void handleBowSpellCasting(LivingEntity target) {
        if (cachedSpellEntry == null) {
            resetBowSpellState();
            return;
        }

        Spell spell = cachedSpellEntry.value();

        switch (bowSpellState) {
            case WINDING_UP -> {
                if (windUpTicks % 2 == 0) {
                    spawnCastingParticles(spell);
                }
                if (--windUpTicks <= 0) {
                    if (isChanneled && channelTicksLeft > 0) {
                        bowSpellState = BowSpellState.CHANNELING;
                        configureChannelCastVisuals(spell);
                    } else {
                        finishBowSpellCast(target, spell, cachedSpellEntry);
                    }
                }
            }
            case CHANNELING -> {
                if (channelTicksLeft % 2 == 0) {
                    spawnCastingParticles(spell);
                }
                if (channelTicksLeft > 0) {
                    if (channelTicksLeft == getChannelDuration(spell) || --castingDelayTicks <= 0) {
                        deliverBowSpell(createSpellContext(currentSpellId, cachedSpellEntry, target).build(), spell);
                        castingDelayTicks = getChannelFireInterval(spell);
                    }
                    channelTicksLeft--;
                } else {
                    completeBowSpellCast(spell);
                }
            }
            default -> resetBowSpellState();
        }
    }

    private void finishBowSpellCast(LivingEntity target, Spell spell, RegistryEntry<Spell> entry) {
        deliverBowSpell(createSpellContext(currentSpellId, entry, target).build(), spell);
        completeBowSpellCast(spell);
    }

    private void completeBowSpellCast(Spell spell) {
        actor.clearActiveItem();
        if (!GuardCastVisuals.hasReleaseAnimation(spell)) {
            GuardCastVisuals.completeCastWithRelease(actor, spell);
        }
        scheduleSpellCooldownOnComplete(currentSpellId, spell);
        resetBowSpellState();
        cooldown = 10;
    }

    private void maintainBowDrawDuringSpell() {
        if (!actor.isUsingItem()) {
            actor.setCurrentHand(Hand.MAIN_HAND);
        }
    }

    private void resetBowSpellState() {
        bowSpellState = BowSpellState.NONE;
        currentSpellId = null;
        cachedSpellEntry = null;
        windUpTicks = 0;
        channelTicksLeft = 0;
        castingDelayTicks = 0;
        isChanneled = false;
    }

    private Identifier pickBowSpellId(LivingEntity ignoredTarget) {
        Identifier stashSpellId = getStashEffectSpellId();
        if (stashSpellId != null && !isSpellOnCooldown(stashSpellId)) {
            return stashSpellId;
        }

        Identifier supportSpellId = getSupportSpellId();
        if (supportSpellId != null && !isSpellOnCooldown(supportSpellId)) {
            return supportSpellId;
        }

        Identifier spellId = getBowSpellId();
        if (spellId == null || isSpellOnCooldown(spellId)) {
            return null;
        }
        return spellId;
    }

    private Identifier getStashEffectSpellId() {
        java.util.function.Predicate<GuardSpellManager.CategorizedSpell> isStashArrowSpell = spell -> {
            Spell s = spell.entry().value();
            return s.deliver != null
                    && s.deliver.type == Spell.Delivery.Type.STASH_EFFECT
                    && !isSpellOnCooldown(spell.spellId());
        };

        GuardSpellManager manager = actor.getSpellManager();
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = manager.getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                isStashArrowSpell
        );
        if (bestSpell.isEmpty()) {
            bestSpell = manager.getBestSpell(GuardSpellManager.SpellCategory.SUPPORT, isStashArrowSpell);
        }

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private void deliverBowSpell(SpellContext context, Spell spell) {
        SpellDelivery.deliverSpell(context, 0);
    }

    private Identifier getSupportSpellId() {
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = actor.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.SUPPORT,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect && conditionalSpells.canCastSpell(spell.spellId()) && !isSpellOnCooldown(spell.spellId());
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private Identifier getBowSpellId() {
        Optional<GuardSpellManager.CategorizedSpell> bestSpell = actor.getSpellManager().getBestSpell(
                GuardSpellManager.SpellCategory.RANGED_BOW,
                spell -> {
                    Spell s = spell.entry().value();
                    boolean isNotStashEffect = s.deliver == null || s.deliver.type != Spell.Delivery.Type.STASH_EFFECT;
                    return isNotStashEffect
                            && !isSpellOnCooldown(spell.spellId())
                            && isArchetypeCompatibleWithBow(spell);
                }
        );

        return bestSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean isArchetypeCompatibleWithBow(GuardSpellManager.CategorizedSpell spell) {
        return GuardSpellManager.isArcheryArchetype(spell.entry().value());
    }

    private boolean isHoldingBow() {
        return GuardItemTags.isBowLikeWeapon(actor.getMainHandStack());
    }
}