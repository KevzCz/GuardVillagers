package dev.sterner.guardvillagers.common.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sterner.guardvillagers.GuardVillagers;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GuardArmorThemeManager implements SimpleSynchronousResourceReloadListener {

    public static final GuardArmorThemeManager INSTANCE = new GuardArmorThemeManager();
    private static final String FOLDER = "guardvillagers/guard_armor_themes";

    private final List<ArmorThemeEntry> entries = new ArrayList<>();

    private GuardArmorThemeManager() {}

    /**
     * Resolved per-slot item IDs for a matched theme. Any slot may be null (skip that slot).
     */
    public record ResolvedArmorTheme(
            @Nullable String head,
            @Nullable String chest,
            @Nullable String legs,
            @Nullable String feet
    ) {}

    private record ArmorThemeEntry(
            List<String> matchItems,
            List<String> matchNamespaces,
            List<String> matchPathContains,
            List<TagKey<Item>> matchItemTags,
            ResolvedArmorTheme theme
    ) {}

    @Override
    public Identifier getFabricId() {
        return GuardVillagers.id("guard_armor_themes");
    }

    @Override
    public void reload(ResourceManager manager) {
        entries.clear();
        manager.findResources(FOLDER, path -> path.getPath().endsWith(".json")).forEach((id, resource) -> {
            try {
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
                ).getAsJsonObject();
                if (root.has("themes") && root.get("themes").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("themes")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject obj = el.getAsJsonObject();

                        ResolvedArmorTheme theme = parseTheme(obj);
                        if (theme == null) continue;

                        List<String> matchItems = readStringList(obj, "match_items");
                        List<String> matchNamespaces = readStringList(obj, "match_namespaces");
                        List<String> matchPathContains = readStringList(obj, "match_path_contains");
                        List<TagKey<Item>> matchItemTags = readItemTagList(obj, "match_item_tags");
                        entries.add(new ArmorThemeEntry(matchItems, matchNamespaces, matchPathContains, matchItemTags, theme));
                    }
                }
            } catch (Exception e) {
                GuardVillagers.LOGGER.error("Failed to load armor theme file {}", id, e);
            }
        });
        GuardVillagers.LOGGER.info("Loaded {} armor theme entries", entries.size());
    }

    @Nullable
    private static ResolvedArmorTheme parseTheme(JsonObject obj) {
        String head  = obj.has("head")  ? obj.get("head").getAsString()  : null;
        String chest = obj.has("chest") ? obj.get("chest").getAsString() : null;
        String legs  = obj.has("legs")  ? obj.get("legs").getAsString()  : null;
        String feet  = obj.has("feet")  ? obj.get("feet").getAsString()  : null;
        if (head == null && chest == null && legs == null && feet == null) return null;
        return new ResolvedArmorTheme(head, chest, legs, feet);
    }

    private static List<String> readStringList(JsonObject obj, String key) {
        List<String> result = new ArrayList<>();
        if (!obj.has(key)) return result;
        JsonElement el = obj.get(key);
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive()) result.add(item.getAsString());
            }
        } else if (el.isJsonPrimitive()) {
            result.add(el.getAsString());
        }
        return result;
    }

    private static List<TagKey<Item>> readItemTagList(JsonObject obj, String key) {
        List<TagKey<Item>> result = new ArrayList<>();
        for (String raw : readStringList(obj, key)) {
            Identifier tagId = Identifier.tryParse(raw.startsWith("#") ? raw.substring(1) : raw);
            if (tagId != null) {
                result.add(TagKey.of(RegistryKeys.ITEM, tagId));
            }
        }
        return result;
    }

    @Nullable
    public ResolvedArmorTheme resolve(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String namespace = id.getNamespace();
        String path = id.getPath();
        String fullId = namespace + ":" + path;

        for (ArmorThemeEntry entry : entries) {
            if (matchesEntry(entry, stack, fullId, namespace, path)) {
                return entry.theme();
            }
        }
        return null;
    }

    private static boolean matchesEntry(ArmorThemeEntry entry, ItemStack stack,
                                        String fullId, String namespace, String path) {
        boolean hasAnyMatcher = !entry.matchItems().isEmpty()
                || !entry.matchNamespaces().isEmpty()
                || !entry.matchPathContains().isEmpty()
                || !entry.matchItemTags().isEmpty();
        if (!hasAnyMatcher) return false;

        if (!entry.matchItems().isEmpty()) {
            boolean found = false;
            for (String match : entry.matchItems()) {
                if (fullId.equals(match)) { found = true; break; }
            }
            if (!found) return false;
        }

        if (!entry.matchNamespaces().isEmpty()) {
            boolean found = false;
            for (String ns : entry.matchNamespaces()) {
                if (namespace.equals(ns)) { found = true; break; }
            }
            if (!found) return false;
        }

        if (!entry.matchPathContains().isEmpty()) {
            boolean found = false;
            for (String fragment : entry.matchPathContains()) {
                if (path.contains(fragment)) { found = true; break; }
            }
            if (!found) return false;
        }

        if (!entry.matchItemTags().isEmpty()) {
            boolean found = false;
            for (TagKey<Item> tag : entry.matchItemTags()) {
                if (stack.isIn(tag)) { found = true; break; }
            }
            if (!found) return false;
        }

        return true;
    }
}
