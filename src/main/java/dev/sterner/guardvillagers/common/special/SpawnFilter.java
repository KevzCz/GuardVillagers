package dev.sterner.guardvillagers.common.special;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public record SpawnFilter(Identifier id, boolean tag) {

    private static final String TAG_PREFIX = "#";

    @Nullable
    public static SpawnFilter parse(String raw) {
        String trimmed = raw.trim();
        boolean isTag = trimmed.startsWith(TAG_PREFIX);
        Identifier id = Identifier.tryParse(isTag ? trimmed.substring(TAG_PREFIX.length()) : trimmed);
        return id == null ? null : new SpawnFilter(id, isTag);
    }

    @Override
    public String toString() {
        return tag ? TAG_PREFIX + id : id.toString();
    }
}
