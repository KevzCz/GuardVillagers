package dev.sterner.guardvillagers.common.entity.goal.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.fx.Sound;
import net.spell_engine.utils.SoundHelper;
import org.jetbrains.annotations.Nullable;

public final class GuardSpellSounds {

    private GuardSpellSounds() {}

    public static void play(World world, LivingEntity entity, @Nullable Sound sound) {
        if (sound == null || world.isClient()) {
            return;
        }
        SoundHelper.playSound(world, entity, sound);
    }

    public static void playCastStart(LivingEntity entity, Spell spell) {
        if (spell.active == null || spell.active.cast == null) {
            return;
        }
        play(entity.getWorld(), entity, spell.active.cast.start_sound);
    }

    public static void playRelease(LivingEntity entity, Spell spell) {
        if (spell.release == null) {
            return;
        }
        play(entity.getWorld(), entity, spell.release.sound);
    }

    @Nullable
    public static Sound channelLoopSound(Spell spell) {
        if (spell.active == null || spell.active.cast == null) {
            return null;
        }
        return spell.active.cast.sound;
    }
}
