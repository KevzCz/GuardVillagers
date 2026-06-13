package dev.sterner.guardvillagers.client.sound;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardSpellSounds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.fx.Sound;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.client.sound.SpellCastingSound;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class GuardSpellCastSoundClient {

    private static final Map<Integer, ActiveLoop> ACTIVE_LOOPS = new HashMap<>();

    private GuardSpellCastSoundClient() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GuardSpellCastSoundClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            clearAll(client.getSoundManager());
            return;
        }

        Set<Integer> stillCasting = new HashSet<>();
        for (GuardEntity guard : client.world.getEntitiesByClass(
                GuardEntity.class,
                client.player.getBoundingBox().expand(64.0),
                GuardEntity::isSpellCastBusy)) {
            stillCasting.add(guard.getId());
            Spell spell = resolveCastingSpell(client, guard);
            Sound loop = spell != null ? GuardSpellSounds.channelLoopSound(spell) : null;
            updateLoop(client.getSoundManager(), guard, loop);
        }

        Iterator<Map.Entry<Integer, ActiveLoop>> it = ACTIVE_LOOPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ActiveLoop> entry = it.next();
            if (!stillCasting.contains(entry.getKey())) {
                stopLoop(client.getSoundManager(), entry.getValue());
                it.remove();
            }
        }
    }

    @Nullable
    private static Spell resolveCastingSpell(MinecraftClient client, GuardEntity guard) {
        Spell fromProcess = guard.getCurrentSpell();
        if (fromProcess != null) {
            return fromProcess;
        }
        Identifier spellId = guard.getSyncedCastSpellId();
        if (spellId == null) {
            return null;
        }
        return SpellRegistry.from(client.world)
                .getEntry(spellId)
                .map(entry -> entry.value())
                .orElse(null);
    }

    private static void updateLoop(SoundManager soundManager, GuardEntity guard, @Nullable Sound loopSound) {
        String soundId = loopSound != null ? loopSound.id() : null;
        if (soundId == null || soundId.isEmpty()) {
            ActiveLoop existing = ACTIVE_LOOPS.remove(guard.getId());
            if (existing != null) {
                stopLoop(soundManager, existing);
            }
            return;
        }

        ActiveLoop active = ACTIVE_LOOPS.get(guard.getId());
        if (active != null && soundId.equals(active.soundId)) {
            return;
        }

        if (active != null) {
            stopLoop(soundManager, active);
        }

        Identifier eventId = Identifier.tryParse(soundId);
        if (eventId == null || !Registries.SOUND_EVENT.containsId(eventId)) {
            return;
        }

        SpellCastingSound castingSound = new SpellCastingSound(
                guard,
                eventId,
                loopSound.volume(),
                loopSound.randomizedPitch()
        );
        soundManager.play(castingSound);
        ACTIVE_LOOPS.put(guard.getId(), new ActiveLoop(soundId, castingSound));
    }

    private static void stopLoop(SoundManager soundManager, ActiveLoop active) {
        soundManager.stop(active.sound);
    }

    private static void clearAll(SoundManager soundManager) {
        for (ActiveLoop active : ACTIVE_LOOPS.values()) {
            stopLoop(soundManager, active);
        }
        ACTIVE_LOOPS.clear();
    }

    private record ActiveLoop(String soundId, SpellCastingSound sound) {}
}
