package dev.sterner.guardvillagers.common.special;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public record GuardPermanentEffect(Identifier effectId, int amplifier, boolean ambient, boolean showParticles) {

    private static final String KEY_ID = "Id";
    private static final String KEY_AMPLIFIER = "Amplifier";
    private static final String KEY_AMBIENT = "Ambient";
    private static final String KEY_SHOW_PARTICLES = "ShowParticles";

    public void ensureApplied(LivingEntity entity) {
        Registries.STATUS_EFFECT.getEntry(effectId).ifPresent(effect -> {
            StatusEffectInstance current = entity.getStatusEffect(effect);
            if (current == null || current.getAmplifier() < amplifier || !current.isInfinite()) {
                entity.addStatusEffect(new StatusEffectInstance(effect, StatusEffectInstance.INFINITE, amplifier, ambient, showParticles));
            }
        });
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_ID, effectId.toString());
        nbt.putInt(KEY_AMPLIFIER, amplifier);
        nbt.putBoolean(KEY_AMBIENT, ambient);
        nbt.putBoolean(KEY_SHOW_PARTICLES, showParticles);
        return nbt;
    }

    @Nullable
    public static GuardPermanentEffect fromNbt(NbtCompound nbt) {
        Identifier id = Identifier.tryParse(nbt.getString(KEY_ID));
        if (id == null) {
            return null;
        }
        return new GuardPermanentEffect(id, nbt.getInt(KEY_AMPLIFIER), nbt.getBoolean(KEY_AMBIENT), nbt.getBoolean(KEY_SHOW_PARTICLES));
    }
}
