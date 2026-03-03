package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_power.api.SpellSchool;

import java.util.Optional;

public class GuardMeleeSpellCastGoal extends BaseSpellGoal {
    private static final float MELEE_RANGE = 4.0F;

    private SpellState spellState = SpellState.UNCHARGED;

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
        if (!hasMeleeSpellAvailable()) {
            return false;
        }

        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        double distSq = guard.squaredDistanceTo(target);
        return distSq <= MELEE_RANGE * MELEE_RANGE && guard.getVisibilityCache().canSee(target);
    }

    @Override
    public boolean shouldContinue() {
        return (canStart() || !guard.getNavigation().isIdle() || spellState != SpellState.UNCHARGED)
                && guard.getTarget() != null;
    }

    @Override
    public void start() {
        super.start();
        this.spellState = SpellState.UNCHARGED;
    }

    @Override
    public void stop() {
        super.stop();
        this.spellState = SpellState.UNCHARGED;
        guard.setCastingSpell(false);
    }

    @Override
    public void tick() {
        tickCooldowns();

        LivingEntity target = guard.getTarget();
        if (target == null || !target.isAlive()) {
            stop();
            return;
        }

        guard.getLookControl().lookAt(target, 30.0F, 30.0F);

        switch (this.spellState) {
            case UNCHARGED -> handleUncharged(target);
            case CHARGING -> handleCharging();
            case CHARGED -> handleCharged();
            case CASTING -> handleCasting(target);
        }
    }

    private void handleUncharged(LivingEntity target) {
        Identifier spellId = getNextCastableSpell();
        if (spellId == null) return;

        Optional<RegistryEntry.Reference<Spell>> optEntry =
                SpellRegistry.from(guard.getWorld()).getEntry(spellId);

        if (optEntry.isEmpty()) return;

        cachedSpellEntry = optEntry.get();
        Spell spell = cachedSpellEntry.value();
        currentSpellId = spellId;
        windUpTicks = getWindUpTicks(spell);

        SpellSchool.Archetype archetype = spell.school != null && spell.school.archetype != null
                ? spell.school.archetype
                : SpellSchool.Archetype.MELEE;

        guard.setCurrentHand(Hand.MAIN_HAND);
        guard.setCastingSpell(true);
        guard.setCastingMeleeSpell(archetype == SpellSchool.Archetype.MELEE);

        this.spellState = SpellState.CHARGING;
    }

    private void handleCharging() {
        if (!guard.isUsingItem()) {
            guard.setCurrentHand(Hand.MAIN_HAND);
        }

        if (cachedSpellEntry != null && windUpTicks % 2 == 0) {
            spawnCastingParticles(cachedSpellEntry.value());
        }

        if (--windUpTicks <= 0) {
            this.spellState = SpellState.CHARGED;
        }
    }

    private void handleCharged() {
        if (currentSpellId == null || isSpellOnCooldown(currentSpellId)) return;
        if (cachedSpellEntry == null) return;

        Spell spell = cachedSpellEntry.value();
        isChanneled = isSpellChanneled(spell);
        channelTicksLeft = getChannelDuration(spell);
        castingDelayTicks = 0;
        spellFired = false;

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
            finishCasting();
        }
    }

    private void handleSingleCast(LivingEntity target, Spell spell) {
        if (!spellFired) {
            castSpell(target, spell);
            spellFired = true;
        } else {
            finishCasting();
        }
    }

    private void castSpell(LivingEntity target, Spell spell) {
        SpellContext context = createSpellContext(currentSpellId, cachedSpellEntry, target).build();

        if (spell.deliver == null) {
            SpellDelivery.castDirect(context);
            guard.swingHand(Hand.MAIN_HAND, true);
            return;
        }

        switch (spell.deliver.type) {
            case PROJECTILE -> SpellDelivery.castProjectile(context, 0);
            case SHOOT_ARROW -> SpellDelivery.castProjectile(context, 0);
            case METEOR -> SpellDelivery.castMeteor(context);
            case CLOUD -> SpellDelivery.castCloud(context);
            case CUSTOM -> SpellDelivery.castCustom(context);
            case DIRECT -> SpellDelivery.castDirect(context);
            case MELEE -> SpellDelivery.castDirect(context);
            case STASH_EFFECT -> SpellDelivery.castStashEffect(context);
        }

        guard.swingHand(Hand.MAIN_HAND, true);
    }

    private void finishCasting() {
        guard.stopUsingItem();

        int baseCooldown = getCooldownTicks(cachedSpellEntry.value());
        spellCooldowns.put(currentSpellId, baseCooldown);

        spellState = SpellState.UNCHARGED;
        guard.setCastingSpell(false);

    }

    private Identifier getNextCastableSpell() {
        var manager = guard.getSpellManager();

        Optional<GuardSpellManager.CategorizedSpell> meleeSpell =
                manager.getBestSpell(GuardSpellManager.SpellCategory.MELEE,
                        s -> !isSpellOnCooldown(s.spellId())
                                && isArchetypeCompatibleWithMelee(s));

        return meleeSpell.map(GuardSpellManager.CategorizedSpell::spellId).orElse(null);
    }

    private boolean isArchetypeCompatibleWithMelee(GuardSpellManager.CategorizedSpell spell) {
        Spell s = spell.entry().value();

        if (s.school == null || s.school.archetype == null) {
            return true;
        }
        return s.school.archetype == SpellSchool.Archetype.MELEE;
    }

    private boolean hasMeleeSpellAvailable() {
        return guard.getSpellManager().getSpells(GuardSpellManager.SpellCategory.MELEE)
                .stream()
                .anyMatch(s -> !isSpellOnCooldown(s.spellId()));
    }


}