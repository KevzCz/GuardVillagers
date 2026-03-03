package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.api.spell.fx.ParticleBatch;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public abstract class BaseSpellGoal extends Goal {
    protected final GuardEntity guard;
    protected final Map<Identifier, Integer> spellCooldowns = new HashMap<>();

    protected Identifier currentSpellId;
    protected RegistryEntry<Spell> cachedSpellEntry;
    protected int windUpTicks;
    protected int channelTicksLeft;
    protected int castingDelayTicks;
    protected boolean isChanneled;
    protected boolean spellFired;

    public BaseSpellGoal(GuardEntity guard) {
        this.guard = guard;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public void stop() {
        guard.stopUsingItem();
        guard.setCastingSpell(false);
        guard.getNavigation().stop();
        resetSpellState();
    }

    protected void resetSpellState() {
        currentSpellId = null;
        cachedSpellEntry = null;
        windUpTicks = 0;
        channelTicksLeft = 0;
        castingDelayTicks = 0;
        isChanneled = false;
        spellFired = false;
    }

    protected void tickCooldowns() {
        spellCooldowns.replaceAll((id, time) -> Math.max(time - 1, 0));
    }

    protected boolean isSpellOnCooldown(Identifier spellId) {
        return spellCooldowns.getOrDefault(spellId, 0) > 0;
    }

    protected void startWindup(Identifier spellId, RegistryEntry<Spell> entry) {
        this.currentSpellId = spellId;
        this.cachedSpellEntry = entry;
        Spell spell = entry.value();
        this.windUpTicks = getWindUpTicks(spell);
        this.isChanneled = isSpellChanneled(spell);
        this.channelTicksLeft = getChannelDuration(spell);
        this.spellFired = false;

        guard.setCurrentHand(Hand.MAIN_HAND);
        guard.setCastingSpell(true);
    }

    protected int getWindUpTicks(Spell spell) {
        // For channeled spells, use a short wind-up (1 second) before channeling starts
        // The channel duration is separate from the wind-up
        if (isSpellChanneled(spell)) {
            // Channeled spells start channeling after 1 second wind-up
            float hasteModifier = SpellPower.getHaste(guard, spell.school);
            return Math.max(10, (int)(20 / hasteModifier));
        }
        
        // For non-channeled spells, use the full cast duration
        if (spell.active != null && spell.active.cast != null) {
            float hasteDuration = SpellHelper.getCastDuration(guard, spell);
            return Math.max(1, (int)(hasteDuration * 20));
        }
        return 20;
    }

    /**
     * Get the total duration of a channeled spell in ticks.
     * This is how long the channeling phase lasts after wind-up completes.
     */
    protected int getChannelDuration(Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0) {
            // Duration is the total time, convert to ticks
            float hasteDuration = SpellHelper.getCastDuration(guard, spell);
            return Math.max(1, (int)(hasteDuration * 20));
        }
        return 0;
    }

    /**
     * Get the interval between spell fires during channeling.
     * For a spell with duration=8s and channel_ticks=8, this fires every 1 second (20 ticks).
     */
    protected int getChannelFireInterval(Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0) {
            float hasteDuration = SpellHelper.getCastDuration(guard, spell);
            int totalTicks = Math.max(1, (int)(hasteDuration * 20));
            int channelTicks = spell.active.cast.channel_ticks;
            return Math.max(1, totalTicks / channelTicks);
        }
        return 20; // Default 1 second
    }

    protected int getCooldownTicks(Spell spell) {
        int baseTicks;
        if (cachedSpellEntry != null) {
            baseTicks = (int)(SpellHelper.getCooldownDuration(guard, cachedSpellEntry) * 20);
        } else if (spell.cost != null && spell.cost.cooldown != null) {
            baseTicks = (int)(spell.cost.cooldown.duration * 20);
        } else {
            baseTicks = 20;
        }
        // Apply cooldown reduction from MODIFIER spells
        if (cachedSpellEntry != null) {
            baseTicks = guard.getSpellManager().getAugmentedCooldownTicks(cachedSpellEntry, baseTicks);
        }
        return baseTicks;
    }

    protected boolean isSpellChanneled(Spell spell) {
        return spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0;
    }

    protected Optional<RegistryEntry.Reference<Spell>> getSpellEntry(Identifier spellId) {
        return SpellRegistry.from(guard.getWorld()).getEntry(spellId);
    }

    protected void playSpellSound(Spell spell) {
        if (spell.release != null && spell.release.sound != null) {
            Identifier soundId = Identifier.tryParse(spell.release.sound.id());
            if (soundId != null) {
                SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                guard.getWorld().playSound(null, guard.getBlockPos(), sound, SoundCategory.PLAYERS, 1.0F, 1.0F);
            }
        }
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

        if (!(guard.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        Vec3d guardPos = guard.getPos();
        Vec3d eyePos = guard.getEyePos();
        Vec3d lookVec = guard.getRotationVector();

        for (ParticleBatch particleBatch : spell.active.cast.particles) {
            if (particleBatch.particle_id == null) continue;

            Identifier particleId = Identifier.tryParse(particleBatch.particle_id);
            if (particleId == null) continue;

            Vec3d origin = switch (particleBatch.origin) {
                case FEET -> guardPos;
                case CENTER -> new Vec3d(guardPos.x, guardPos.y + guard.getHeight() * 0.5, guardPos.z);
                case LAUNCH_POINT -> eyePos;
                case GROUND -> new Vec3d(guardPos.x, guardPos.y, guardPos.z);
            };

            int count = (int) particleBatch.count;
            double minSpeed = particleBatch.min_speed;
            double maxSpeed = particleBatch.max_speed > 0 ? particleBatch.max_speed : minSpeed;

            if (particleBatch.shape == ParticleBatch.Shape.CONE && particleBatch.angle > 0) {
                spawnConeParticles(serverWorld, particleId, origin, lookVec,
                        count, minSpeed, maxSpeed, particleBatch.angle);
            } else {
                spawnSimpleParticles(serverWorld, particleId, origin,
                        count, minSpeed, maxSpeed);
            }
        }
    }

    private void spawnConeParticles(ServerWorld world, Identifier particleId, Vec3d origin,
                                    Vec3d direction, int count, double minSpeed, double maxSpeed, float angle) {
        double halfAngleRad = Math.toRadians(angle / 2.0);
        Random random = world.getRandom();

        ParticleEffect particleEffect = getParticleEffect(particleId);
        if (particleEffect == null) return;

        for (int i = 0; i < count; i++) {
            double pitch = (random.nextDouble() - 0.5) * 2 * halfAngleRad;
            double yaw = (random.nextDouble() - 0.5) * 2 * halfAngleRad;

            double cosPitch = Math.cos(pitch);
            Vec3d velocity = new Vec3d(
                    direction.x * cosPitch * Math.cos(yaw) - direction.z * Math.sin(yaw),
                    direction.y * cosPitch + Math.sin(pitch),
                    direction.z * cosPitch * Math.cos(yaw) + direction.x * Math.sin(yaw)
            );

            double speed = minSpeed + (maxSpeed - minSpeed) * random.nextDouble();
            velocity = velocity.normalize().multiply(speed);

            world.spawnParticles(
                    particleEffect,
                    origin.x, origin.y, origin.z,
                    0,
                    velocity.x, velocity.y, velocity.z,
                    speed * 0.5
            );
        }
    }

    private void spawnSimpleParticles(ServerWorld world, Identifier particleId, Vec3d origin,
                                      int count, double minSpeed, double maxSpeed) {
        Random random = world.getRandom();

        ParticleEffect particleEffect = getParticleEffect(particleId);
        if (particleEffect == null) return;

        for (int i = 0; i < count; i++) {
            double speed = minSpeed + (maxSpeed - minSpeed) * random.nextDouble();
            Vec3d velocity = new Vec3d(
                    (random.nextDouble() - 0.5) * 2,
                    (random.nextDouble() - 0.5) * 2,
                    (random.nextDouble() - 0.5) * 2
            ).normalize().multiply(speed);

            world.spawnParticles(
                    particleEffect,
                    origin.x, origin.y, origin.z,
                    0,
                    velocity.x, velocity.y, velocity.z,
                    speed * 0.5
            );
        }
    }

    private ParticleEffect getParticleEffect(Identifier particleId) {
        var particleType = Registries.PARTICLE_TYPE.get(particleId);
        if (particleType instanceof ParticleEffect effect) {
            return effect;
        }
        return ParticleTypes.FLAME;
    }
}