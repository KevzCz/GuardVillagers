package dev.sterner.guardvillagers.common.entity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public record GrantedSpell(Identifier spellId, boolean passiveOnly) {

    private static final String KEY_ID = "Id";
    private static final String KEY_PASSIVE_ONLY = "PassiveOnly";

    public GrantedSpell(Identifier spellId) {
        this(spellId, false);
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(KEY_ID, spellId.toString());
        if (passiveOnly) {
            nbt.putBoolean(KEY_PASSIVE_ONLY, true);
        }
        return nbt;
    }

    @Nullable
    public static GrantedSpell fromNbt(NbtCompound nbt) {
        Identifier id = Identifier.tryParse(nbt.getString(KEY_ID));
        if (id == null) {
            return null;
        }
        return new GrantedSpell(id, nbt.getBoolean(KEY_PASSIVE_ONLY));
    }
}
