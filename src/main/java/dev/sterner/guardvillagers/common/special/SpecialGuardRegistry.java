package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sterner.guardvillagers.GuardVillagers;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SpecialGuardRegistry implements SimpleSynchronousResourceReloadListener {

    public static final SpecialGuardRegistry INSTANCE = new SpecialGuardRegistry();
    private static final String FOLDER = "guardvillagers/special_guards";
    private static final int DEFAULT_SPAWN_WEIGHT = 100;

    private final Map<Identifier, SpecialGuardDefinition> definitions = new LinkedHashMap<>();

    private SpecialGuardRegistry() {}

    @Override
    public Identifier getFabricId() {
        return GuardVillagers.id("special_guards");
    }

    @Override
    public void reload(ResourceManager manager) {
        definitions.clear();
        manager.findResources(FOLDER, path -> path.getPath().endsWith(".json")).forEach((id, resource) -> {
            try {
                Identifier definitionId = fileIdToDefinitionId(id);
                if (definitionId == null) {
                    return;
                }
                JsonObject root = JsonParser.parseReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!isRequiredModLoaded(root)) {
                    GuardVillagers.LOGGER.debug(
                            "Skipping special guard {} (required mod '{}' not loaded)",
                            definitionId,
                            root.get("required_mod").getAsString()
                    );
                    return;
                }
                definitions.put(definitionId, SpecialGuardDefinition.parse(definitionId, root));
            } catch (Exception error) {
                GuardVillagers.LOGGER.error("Failed to load special guard {}", id, error);
            }
        });
        GuardVillagers.LOGGER.info("Loaded {} special guard definitions", definitions.size());
    }

    private static boolean isRequiredModLoaded(JsonObject root) {
        if (!root.has("required_mod") || !root.get("required_mod").isJsonPrimitive()) {
            return true;
        }
        String modId = root.get("required_mod").getAsString().trim();
        if (modId.isEmpty()) {
            return true;
        }
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Nullable
    private static Identifier fileIdToDefinitionId(Identifier fileId) {
        String prefix = FOLDER + "/";
        String path = fileId.getPath();
        if (!path.startsWith(prefix) || !path.endsWith(".json")) {
            return null;
        }
        String name = path.substring(prefix.length(), path.length() - ".json".length());
        return Identifier.of(fileId.getNamespace(), name);
    }

    public Collection<SpecialGuardDefinition> all() {
        return definitions.values();
    }

    public Optional<SpecialGuardDefinition> get(Identifier id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @Nullable
    public SpecialGuardDefinition pick(ServerWorld world, BlockPos pos, Random random) {
        List<SpecialGuardDefinition> matching = new ArrayList<>();
        for (SpecialGuardDefinition definition : definitions.values()) {
            if (definition.spawnWeight() <= 0) {
                continue;
            }
            if (!matches(world, pos, definition)) {
                continue;
            }
            if (definition.maxPerVillage() != null) {
                int typeCount = SpecialGuardVillageTracker.countSpecialGuardsNear(world, pos, definition.id());
                if (typeCount >= definition.maxPerVillage()) {
                    continue;
                }
            }
            matching.add(definition);
        }
        if (matching.isEmpty()) {
            return null;
        }

        int totalWeight = DEFAULT_SPAWN_WEIGHT;
        for (SpecialGuardDefinition definition : matching) {
            totalWeight += definition.spawnWeight();
        }

        int roll = random.nextInt(totalWeight);
        if (roll < DEFAULT_SPAWN_WEIGHT) {
            return null;
        }

        roll -= DEFAULT_SPAWN_WEIGHT;
        for (SpecialGuardDefinition definition : matching) {
            roll -= definition.spawnWeight();
            if (roll < 0) {
                return definition;
            }
        }
        return matching.getLast();
    }

    private static boolean matches(ServerWorld world, BlockPos pos, SpecialGuardDefinition definition) {
        if (!definition.dimensionFilters().isEmpty()) {
            Identifier dimensionId = world.getRegistryKey().getValue();
            boolean matched = false;
            for (Identifier filter : definition.dimensionFilters()) {
                if (filter.equals(dimensionId)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        if (!definition.biomeFilters().isEmpty()) {
            RegistryEntry<Biome> biome = world.getBiome(pos);
            boolean matched = false;
            for (Identifier filter : definition.biomeFilters()) {
                if (matchesBiomeFilter(biome, filter)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchesBiomeFilter(RegistryEntry<Biome> biome, Identifier filter) {
        if (filter.getPath().startsWith("#")) {
            Identifier tagId = Identifier.of(filter.getNamespace(), filter.getPath().substring(1));
            TagKey<Biome> tag = TagKey.of(RegistryKeys.BIOME, tagId);
            return biome.isIn(tag);
        }
        RegistryKey<Biome> biomeKey = RegistryKey.of(RegistryKeys.BIOME, filter);
        return biome.matchesKey(biomeKey);
    }
}
