package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.fx.ParticleHelper;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.casting.SpellCast;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

public abstract class BaseSpellGoal extends Goal {
    protected final GuardEntity guard;

    protected Identifier currentSpellId;
    protected RegistryEntry<Spell> cachedSpellEntry;
    protected int windUpTicks;
    protected int channelTicksLeft;
    protected int castingDelayTicks;
    protected int channelHitsDelivered;
    protected boolean isChanneled;
    protected boolean spellFired;

    public BaseSpellGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.noneOf(Control.class));
    }

    @Override
    public void stop() {
        guard.stopUsingItem();
        if (shouldInterruptCastOnStop()) {
            guard.interruptSpellCast();
        }
        resetSpellState();
    }

    protected boolean shouldInterruptCastOnStop() {
        return guard.isSpellCastBusy() || currentSpellId != null;
    }

    protected void resetSpellState() {
        currentSpellId = null;
        cachedSpellEntry = null;
        windUpTicks = 0;
        channelTicksLeft = 0;
        castingDelayTicks = 0;
        channelHitsDelivered = 0;
        isChanneled = false;
        spellFired = false;
    }

    protected void tickCooldowns() {
    }

    protected boolean isSpellOnCooldown(Identifier spellId) {
        return guard.isSpellOnCooldown(spellId);
    }

    protected void putSpellCooldown(Identifier spellId, int ticks) {
        guard.getSpellManager().applySharedSpellCooldown(spellId, ticks);
    }

    protected void applySpellCooldownOnComplete(Identifier spellId, Spell spell) {
        if (spellId != null) {
            putSpellCooldown(spellId, getCooldownTicks(spell));
        }
    }

    
    protected void applySpellCooldownOnAbort(Identifier spellId, Spell spell) {
        if (spellId == null) {
            return;
        }
        int ticks = resolveCooldownTicksForProgress(spell, getCastProgressRatio());
        if (ticks > 0) {
            putSpellCooldown(spellId, ticks);
        }
    }

    protected float getCastProgressRatio() {
        SpellCast.Process process = guard.getSpellCastProcess();
        if (process != null) {
            return process.progress(guard.getWorld().getTime()).ratio();
        }
        if (isChanneled && cachedSpellEntry != null) {
            int total = getChannelDuration(cachedSpellEntry.value());
            if (total > 0) {
                return Math.min(1f, (total - channelTicksLeft) / (float) total);
            }
        }
        return spellFired ? 1f : 0f;
    }

    protected int resolveCooldownTicksForProgress(Spell spell, float progressRatio) {
        int fullTicks = getCooldownTicks(spell);
        if (!isProportionalCooldown(spell)) {
            return fullTicks;
        }
        float ratio = Math.min(1f, Math.max(0f, progressRatio));
        if (ratio <= 0f) {
            return 0;
        }
        return Math.max(1, Math.round(fullTicks * ratio));
    }

    protected boolean isProportionalCooldown(Spell spell) {
        return spell.cost != null && spell.cost.cooldown != null && spell.cost.cooldown.proportional;
    }

    protected void abortCastWithProgressCooldown(@Nullable Spell spell) {
        if (currentSpellId != null && spell != null) {
            applySpellCooldownOnAbort(currentSpellId, spell);
            finishAbortCastVisuals(spell);
        } else {
            guard.interruptSpellCast();
        }
        onCastAborted();
    }

    protected void finishAbortCastVisuals(Spell spell) {
        guard.setActiveBeam(null);
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        guard.stopUsingItem();
        GuardCastVisuals.completeCastWithRelease(guard, spell);
    }

    protected void onCastAborted() {
        resetSpellState();
    }

    protected float resolveCastRange(@Nullable Spell spell, float fallbackRange) {
        if (spell != null && spell.range > 0) {
            return spell.range;
        }
        return fallbackRange;
    }

    protected void scheduleSpellCooldownOnComplete(Identifier spellId, Spell spell) {
        if (spellId == null) {
            return;
        }
        applySpellCooldownOnComplete(spellId, spell);
        int wait = GuardSpellTimings.postDeliverWaitTicks(guard, spell);
        if (wait <= 0) {
            guard.setCastingSpell(false);
            guard.setSpellCastProcess(null);
            return;
        }
        GuardCastVisuals.scheduleAfterTicks(guard, wait, () -> {
            if (!guard.isAlive()) {
                return;
            }
            guard.setCastingSpell(false);
            guard.setSpellCastProcess(null);
        });
    }

    public void clearAllCooldowns() {
        guard.clearAllSpellCooldowns();
    }

    public void clearCooldown(Identifier spellId) {
        guard.clearSpellCooldown(spellId);
    }

    public void setCooldown(Identifier spellId, int ticks) {
        guard.setSpellCooldown(spellId, ticks);
    }

    public Map<Identifier, Integer> getCooldowns() {
        return guard.getSpellCooldowns();
    }

    public String getActiveSpellLabel() {
        if (currentSpellId == null) {
            return null;
        }
        return currentSpellId.getPath();
    }

    protected boolean checkCastInterrupt(boolean activelyCasting) {
        if (!activelyCasting) {
            return false;
        }
        if (currentSpellId != null && !guard.getSpellManager().knowsSpell(currentSpellId)) {
            abortActiveCast();
            return true;
        }
        return false;
    }

    protected void abortActiveCast() {
        guard.interruptSpellCast();
        resetSpellState();
    }

    protected void beginSpellCast(Identifier spellId, RegistryEntry<Spell> entry, boolean meleeArchetype) {
        this.currentSpellId = spellId;
        this.cachedSpellEntry = entry;
        Spell spell = entry.value();
        this.windUpTicks = getWindUpTicks(spell);
        this.isChanneled = isSpellChanneled(spell);
        this.channelTicksLeft = getChannelDuration(spell);
        this.spellFired = false;

        GuardCastVisuals.beginCast(guard, entry, spell, meleeArchetype);

        if (GuardDebugManager.hasWatchers(guard)) {
            GuardDebugManager.broadcast(guard,
                    "▶ " + spellId.getPath() + " via " + getClass().getSimpleName(),
                    net.minecraft.util.Formatting.LIGHT_PURPLE);
        }
    }

    protected int getWindUpTicks(Spell spell) {
        return GuardCastVisuals.windUpTicks(guard, spell);
    }

    protected int getChannelDuration(Spell spell) {
        return GuardCastVisuals.channelDurationTicks(guard, spell);
    }

    protected int getChannelFireInterval(Spell spell) {
        return GuardCastVisuals.channelFireIntervalTicks(guard, spell);
    }

    protected int getCooldownTicks(Spell spell) {
        int baseTicks = 0;
        if (spell.cost != null && spell.cost.cooldown != null && spell.cost.cooldown.duration > 0) {
            baseTicks = (int)(spell.cost.cooldown.duration * 20);
        }
        if (baseTicks <= 0) {
            baseTicks = 60;
        }
        if (cachedSpellEntry != null) {
            baseTicks = guard.getSpellManager().getAugmentedCooldownTicks(cachedSpellEntry, baseTicks);
        }
        return Math.max(1, baseTicks);
    }

    public static int resolveCooldownTicks(GuardEntity guard, RegistryEntry<Spell> entry) {
        Spell spell = entry.value();
        int baseTicks = 0;
        if (spell.cost != null && spell.cost.cooldown != null && spell.cost.cooldown.duration > 0) {
            baseTicks = (int) (spell.cost.cooldown.duration * 20);
        }
        if (baseTicks <= 0) {
            baseTicks = 60;
        }
        baseTicks = guard.getSpellManager().getAugmentedCooldownTicks(entry, baseTicks);
        return Math.max(1, baseTicks);
    }

    protected boolean isSpellChanneled(Spell spell) {
        return spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0;
    }

    protected void configureChannelCastVisuals(Spell spell) {
        GuardCastVisuals.beginChannel(guard, spell);
    }

    protected Optional<RegistryEntry.Reference<Spell>> getSpellEntry(Identifier spellId) {
        return SpellRegistry.from(guard.getWorld()).getEntry(spellId);
    }

    protected void playSpellSound(Spell spell) {
        GuardSpellSounds.playRelease(guard, spell);
    }

    protected SpellContext.Builder createSpellContext(Identifier spellId, RegistryEntry<Spell> entry, LivingEntity target) {
        return SpellContext.builder()
                .spellId(spellId)
                .entry(entry)
                .caster(guard)
                .target(target)
                .buildImpactContext();
    }

    protected void spawnCastingParticles(Spell spell) {
        if (spell.active == null || spell.active.cast == null || spell.active.cast.particles == null) {
            return;
        }
        if (guard.getWorld().isClient()) {
            return;
        }
        ParticleHelper.sendBatches(guard, spell.active.cast.particles);
    }

    protected void deliverCastTick(@Nullable LivingEntity assignedTarget) {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return;
        }
        Spell spell = cachedSpellEntry.value();
        int channelIndex = isChanneled
                ? Math.max(0, getChannelDuration(spell) - channelTicksLeft - 1)
                : 0;
        guard.setChannelTickIndex(channelIndex);
        SupportSpellCasting.deliver(guard, currentSpellId, cachedSpellEntry, assignedTarget, channelIndex);
        channelHitsDelivered++;
    }

    

    protected boolean tickChanneledCast(
            @Nullable LivingEntity lookTarget,
            @Nullable LivingEntity deliveryTarget,
            Runnable onComplete
    ) {
        if (currentSpellId == null || cachedSpellEntry == null) {
            return false;
        }

        Spell spell = cachedSpellEntry.value();
        guard.setCastingSpell(true);

        if (channelTicksLeft > 0) {
            if (lookTarget != null && lookTarget.isAlive()) {
                guard.getLookControl().lookAt(lookTarget, 30.0F, 30.0F);
            }
            if (spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
                guard.setActiveBeam(spell.target.beam);
            }
            if (channelTicksLeft % 2 == 0) {
                spawnCastingParticles(spell);
            }

            if (channelTicksLeft == getChannelDuration(spell)) {
                configureChannelCastVisuals(spell);
                deliverCastTick(deliveryTarget);
                castingDelayTicks = getChannelFireInterval(spell);
            } else if (--castingDelayTicks <= 0) {
                deliverCastTick(deliveryTarget);
                castingDelayTicks = getChannelFireInterval(spell);
            }
            channelTicksLeft--;
            return true;
        }

        guard.setActiveBeam(null);
        onComplete.run();
        return false;
    }
}