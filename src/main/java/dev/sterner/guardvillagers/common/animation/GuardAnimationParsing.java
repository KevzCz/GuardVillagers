package dev.sterner.guardvillagers.common.animation;

import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.AnimationJson;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

public final class GuardAnimationParsing {

    private GuardAnimationParsing() {}

    public static List<KeyframeAnimation> deserialize(InputStream stream) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return AnimationJson.GSON.fromJson(reader, AnimationJson.getListedTypeToken());
        }
    }

    public static List<KeyframeAnimation> deserialize(byte[] bytes) throws IOException {
        return deserialize(new ByteArrayInputStream(bytes));
    }

    public static KeyframeAnimation firstOrNull(Collection<KeyframeAnimation> anims) {
        if (anims == null || anims.isEmpty()) {
            return null;
        }
        return anims.iterator().next();
    }
}
