package dev.sterner.guardvillagers.common.special;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

public final class GuardTextUtils {

    private GuardTextUtils() {}

    @Nullable
    public static Text parseColorName(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.indexOf('&') >= 0) {
            return Text.literal(raw.replace('&', '\u00A7'));
        }
        return Text.literal(raw);
    }

    public static MutableText coloredLiteral(String raw) {
        Text parsed = parseColorName(raw);
        return parsed != null ? parsed.copy() : Text.literal(raw);
    }
}
