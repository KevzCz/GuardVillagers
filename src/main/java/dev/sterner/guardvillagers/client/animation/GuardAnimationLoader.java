package dev.sterner.guardvillagers.client.animation;

import dev.kosmx.playerAnim.core.data.KeyframeAnimation;

import dev.sterner.guardvillagers.common.animation.GuardAnimationParsing;

import net.fabricmc.api.EnvType;

import net.fabricmc.api.Environment;

import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.resource.ResourceManager;

import net.minecraft.util.Identifier;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import java.util.HashMap;

import java.util.Map;

@Environment(EnvType.CLIENT)

public class GuardAnimationLoader implements SimpleSynchronousResourceReloadListener {

    public static final GuardAnimationLoader INSTANCE = new GuardAnimationLoader();

    private static final Logger LOGGER = LoggerFactory.getLogger("guardvillagers/AnimLoader");

    private static final String FOLDER = "player_animations";

    private static final Map<String, KeyframeAnimation> ANIMATIONS = new HashMap<>();

    private GuardAnimationLoader() {}

    @Nullable

    public static KeyframeAnimation get(String animationId) {

        return ANIMATIONS.get(animationId);

    }

    @Override

    public Identifier getFabricId() {

        return Identifier.of("guardvillagers", "guard_animations");

    }

    @Override

    public void reload(ResourceManager manager) {

        ANIMATIONS.clear();

        manager.findResources(FOLDER, id -> id.getPath().endsWith(".json"))

            .forEach((id, resource) -> {

                String path = id.getPath();

                String name = path.substring(FOLDER.length() + 1, path.length() - ".json".length());

                String animId = id.getNamespace() + ":" + name;

                try (var is = resource.getInputStream()) {

                    KeyframeAnimation anim = GuardAnimationParsing.firstOrNull(GuardAnimationParsing.deserialize(is));

                    if (anim != null) {

                        ANIMATIONS.put(animId, anim);

                        LOGGER.debug("Loaded guard animation: {}", animId);

                    } else {

                        LOGGER.warn("Animation file produced no data: {}", animId);

                    }

                } catch (Exception e) {

                    LOGGER.warn("Failed to load guard animation '{}': {}", animId, e.getMessage());

                }

            });

        LOGGER.info("GuardVillagers: loaded {} player animations", ANIMATIONS.size());

    }

}
