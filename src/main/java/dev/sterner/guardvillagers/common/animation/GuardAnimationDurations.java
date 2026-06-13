package dev.sterner.guardvillagers.common.animation;

import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class GuardAnimationDurations implements SimpleSynchronousResourceReloadListener {

    public static final GuardAnimationDurations INSTANCE = new GuardAnimationDurations();

    private static final int DEFAULT_TICKS = 20;
    private static final String FOLDER = "player_animations";
    private static final Map<String, KeyframeAnimation> ANIMATIONS = new HashMap<>();
    private static final Map<String, Integer> RETURN_TICKS = new HashMap<>();
    private static final Map<String, Boolean> LOOPED = new HashMap<>();

    private GuardAnimationDurations() {}

    public static boolean hasAnimation(@Nullable String animationId) {
        return animationId != null && !animationId.isEmpty() && ANIMATIONS.containsKey(animationId);
    }

    public static int durationTicks(@Nullable String animationId, float speed) {
        return playLengthTicks(animationId, beginTick(animationId), endTick(animationId), speed);
    }

    public static boolean isLooped(@Nullable String animationId) {
        if (animationId == null || animationId.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(LOOPED.get(animationId));
    }

    public static int returnSyncTick(@Nullable String animationId) {
        if (animationId == null || animationId.isEmpty()) {
            return DEFAULT_TICKS;
        }
        Integer parsed = RETURN_TICKS.get(animationId);
        if (parsed != null && parsed >= 0) {
            return parsed;
        }
        return castSyncTick(animationId);
    }

    public static int castSyncTick(@Nullable String animationId) {
        KeyframeAnimation animation = get(animationId);
        if (animation == null) {
            return DEFAULT_TICKS;
        }
        int begin = animation.beginTick;
        int end = animation.endTick > begin ? animation.endTick : animation.stopTick;
        if (end > begin) {
            return end;
        }
        return Math.max(DEFAULT_TICKS, animation.stopTick - begin);
    }

    public static int beginTick(@Nullable String animationId) {
        KeyframeAnimation animation = get(animationId);
        return animation != null ? animation.beginTick : 0;
    }

    public static int endTick(@Nullable String animationId) {
        KeyframeAnimation animation = get(animationId);
        if (animation == null) {
            return DEFAULT_TICKS;
        }
        int begin = animation.beginTick;
        int stop = animation.stopTick > 0 ? animation.stopTick : animation.endTick;
        return stop > begin ? stop : begin + DEFAULT_TICKS;
    }

    private static int playLengthTicks(@Nullable String animationId, int begin, int stop, float speed) {
        if (animationId == null || animationId.isEmpty()) {
            return DEFAULT_TICKS;
        }
        if (stop <= begin) {
            return DEFAULT_TICKS;
        }
        return Math.max(1, Math.round((stop - begin) / Math.max(speed, 0.1f)));
    }

    @Nullable
    private static KeyframeAnimation get(@Nullable String animationId) {
        if (animationId == null || animationId.isEmpty()) {
            return null;
        }
        return ANIMATIONS.get(animationId);
    }

    @Override
    public Identifier getFabricId() {
        return Identifier.of("guardvillagers", "animation_durations");
    }

    @Override
    public void reload(ResourceManager manager) {
        ANIMATIONS.clear();
        RETURN_TICKS.clear();
        LOOPED.clear();
        manager.findResources(FOLDER, id -> id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    String path = id.getPath();
                    String name = path.substring(FOLDER.length() + 1, path.length() - ".json".length());
                    String animId = id.getNamespace() + ":" + name;
                    try (InputStream is = resource.getInputStream()) {
                        byte[] bytes = is.readAllBytes();
                        KeyframeAnimation anim = GuardAnimationParsing.firstOrNull(
                                GuardAnimationParsing.deserialize(bytes));
                        if (anim != null) {
                            ANIMATIONS.put(animId, anim);
                        }
                        parseMetadata(animId, bytes);
                    } catch (Exception ignored) {
                    }
                });
    }

    private static void parseMetadata(String animId, byte[] bytes) {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject animation = metadataBlock(root);
            if (animation == null) {
                return;
            }
            if (animation.has("returnTick")) {
                RETURN_TICKS.put(animId, animation.get("returnTick").getAsInt());
            }
            if (animation.has("isLoop")) {
                LOOPED.put(animId, parseLoopFlag(animation.get("isLoop")));
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static JsonObject metadataBlock(JsonObject root) {
        if (root.has("animation")) {
            return root.getAsJsonObject("animation");
        }
        if (root.has("emote")) {
            return root.getAsJsonObject("emote");
        }
        return null;
    }

    private static boolean parseLoopFlag(com.google.gson.JsonElement value) {
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            String text = value.getAsString();
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return false;
    }
}
