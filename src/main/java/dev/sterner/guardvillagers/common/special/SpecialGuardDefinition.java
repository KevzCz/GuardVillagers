package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpecialGuardDefinition {

    public static final int DEFAULT_STRUCTURE_SEARCH_RADIUS = 0;
    private static final String LOADOUT_WEIGHT_KEY = "weight";

    private final Identifier id;
    private final int spawnWeight;
    private final boolean natural;
    @Nullable private final Float spawnChance;
    @Nullable private final Integer maxPerVillage;
    private final List<SpawnFilter> biomeFilters;
    private final List<Identifier> dimensionFilters;
    private final List<SpawnFilter> structureFilters;
    private final int structureSearchRadius;
    @Nullable private final String displayName;
    private final List<String> names;
    private final List<String> greetings;
    private final boolean nameVisible;
    @Nullable private final Integer variant;
    @Nullable private final Boolean hireable;
    private final boolean heroOfTheVillage;
    @Nullable private final Boolean followHero;
    @Nullable private final Boolean patrolling;
    @Nullable private final Boolean following;
    private final boolean blockGui;
    private final boolean followable;
    private final boolean lockEquipment;
    private final boolean immutableEquipment;
    private final boolean attackPlayers;
    private final List<Identifier> attackMobs;
    private final List<String> hiringItems;
    @Nullable private final Integer hiringCost;
    private final boolean useLootTables;
    @Nullable private final Float dropChance;
    @Nullable private final Identifier deathLootTable;
    private final List<JsonElement> deathDrops;
    private final Map<String, JsonElement> equipment;
    private final List<GrantedSpellEntry> spells;
    private final List<InventorySlot> inventory;
    private final Map<String, Double> attributes;
    private final Map<String, JsonElement> configOverrides;
    @Nullable private final JsonObject entityData;
    private final List<String> commandTags;
    private final List<EffectEntry> effects;
    @Nullable private final SpellPoolEntry spellPool;
    private final List<Loadout> loadouts;
    @Nullable private final Identifier dialogue;

    private SpecialGuardDefinition(
            Identifier id,
            int spawnWeight,
            boolean natural,
            @Nullable Float spawnChance,
            @Nullable Integer maxPerVillage,
            List<SpawnFilter> biomeFilters,
            List<Identifier> dimensionFilters,
            List<SpawnFilter> structureFilters,
            int structureSearchRadius,
            @Nullable String displayName,
            List<String> names,
            List<String> greetings,
            boolean nameVisible,
            @Nullable Integer variant,
            @Nullable Boolean hireable,
            boolean heroOfTheVillage,
            @Nullable Boolean followHero,
            @Nullable Boolean patrolling,
            @Nullable Boolean following,
            boolean blockGui,
            boolean followable,
            boolean lockEquipment,
            boolean immutableEquipment,
            boolean attackPlayers,
            List<Identifier> attackMobs,
            List<String> hiringItems,
            @Nullable Integer hiringCost,
            boolean useLootTables,
            @Nullable Float dropChance,
            @Nullable Identifier deathLootTable,
            List<JsonElement> deathDrops,
            Map<String, JsonElement> equipment,
            List<GrantedSpellEntry> spells,
            List<InventorySlot> inventory,
            Map<String, Double> attributes,
            Map<String, JsonElement> configOverrides,
            @Nullable JsonObject entityData,
            List<String> commandTags,
            List<EffectEntry> effects,
            @Nullable SpellPoolEntry spellPool,
            List<Loadout> loadouts,
            @Nullable Identifier dialogue
    ) {
        this.id = id;
        this.spawnWeight = spawnWeight;
        this.natural = natural;
        this.spawnChance = spawnChance;
        this.maxPerVillage = maxPerVillage;
        this.biomeFilters = biomeFilters;
        this.dimensionFilters = dimensionFilters;
        this.structureFilters = structureFilters;
        this.structureSearchRadius = structureSearchRadius;
        this.displayName = displayName;
        this.names = names;
        this.greetings = greetings;
        this.nameVisible = nameVisible;
        this.variant = variant;
        this.hireable = hireable;
        this.heroOfTheVillage = heroOfTheVillage;
        this.followHero = followHero;
        this.patrolling = patrolling;
        this.following = following;
        this.blockGui = blockGui;
        this.followable = followable;
        this.lockEquipment = lockEquipment;
        this.immutableEquipment = immutableEquipment;
        this.attackPlayers = attackPlayers;
        this.attackMobs = attackMobs;
        this.hiringItems = hiringItems;
        this.hiringCost = hiringCost;
        this.useLootTables = useLootTables;
        this.dropChance = dropChance;
        this.deathLootTable = deathLootTable;
        this.deathDrops = deathDrops;
        this.equipment = equipment;
        this.spells = spells;
        this.inventory = inventory;
        this.attributes = attributes;
        this.configOverrides = configOverrides;
        this.entityData = entityData;
        this.commandTags = commandTags;
        this.effects = effects;
        this.spellPool = spellPool;
        this.loadouts = loadouts;
        this.dialogue = dialogue;
    }

    public static SpecialGuardDefinition parse(Identifier id, JsonObject root) {
        JsonObject spawn = objectOrEmpty(root, "spawn");
        JsonObject display = objectOrEmpty(root, "display");
        JsonObject behavior = objectOrEmpty(root, "behavior");
        JsonObject loot = objectOrEmpty(root, "loot");

        int weight = spawn.has("weight") ? spawn.get("weight").getAsInt() : 100;
        boolean natural = !spawn.has("natural") || spawn.get("natural").getAsBoolean();
        Float chance = spawn.has("chance") && spawn.get("chance").isJsonPrimitive()
                ? Math.max(0f, Math.min(1f, spawn.get("chance").getAsFloat()))
                : null;
        Integer maxPerVillage = spawn.has("max_per_village") ? Math.max(0, spawn.get("max_per_village").getAsInt()) : null;
        List<SpawnFilter> biomes = readFilterList(spawn.get("biomes"));
        List<Identifier> dimensions = readIdentifierList(spawn.get("dimensions"));
        List<SpawnFilter> structures = readFilterList(spawn.get("structures"));
        int structureSearchRadius = spawn.has("structure_search_radius")
                ? Math.max(0, spawn.get("structure_search_radius").getAsInt())
                : DEFAULT_STRUCTURE_SEARCH_RADIUS;

        String name = stringOrNull(display, "name");
        List<String> names = readStringList(display.get("names"));
        List<String> greetings = readStringList(display.get("greetings"));
        boolean nameVisible = !display.has("name_visible") || display.get("name_visible").getAsBoolean();
        Integer variant = display.has("variant") ? display.get("variant").getAsInt() : null;

        Boolean hireable = behavior.has("hireable") ? behavior.get("hireable").getAsBoolean() : null;
        boolean hero = behavior.has("hero_of_the_village") && behavior.get("hero_of_the_village").getAsBoolean();
        Boolean followHero = behavior.has("follow_hero") ? behavior.get("follow_hero").getAsBoolean() : null;
        Boolean patrolling = behavior.has("patrolling") ? behavior.get("patrolling").getAsBoolean() : null;
        Boolean following = behavior.has("following") ? behavior.get("following").getAsBoolean() : null;
        boolean blockGui = behavior.has("block_gui") && behavior.get("block_gui").getAsBoolean();
        boolean followable = !behavior.has("followable") || behavior.get("followable").getAsBoolean();
        boolean lockEquipment = behavior.has("lock_equipment") && behavior.get("lock_equipment").getAsBoolean();
        boolean immutableEquipment = behavior.has("immutable_equipment") && behavior.get("immutable_equipment").getAsBoolean();
        boolean attackPlayers = behavior.has("attack_players") && behavior.get("attack_players").getAsBoolean();
        List<Identifier> attackMobs = readIdentifierList(behavior.get("attack_mobs"));

        JsonObject hiring = objectOrEmpty(root, "hiring");
        List<String> hiringItems = new ArrayList<>(readStringList(hiring.get("items")));
        if (hiring.has("item") && hiring.get("item").isJsonPrimitive()) {
            hiringItems.add(0, hiring.get("item").getAsString().trim());
        }
        Integer hiringCost = hiring.has("cost") && hiring.get("cost").isJsonPrimitive()
                ? Math.max(1, hiring.get("cost").getAsInt()) : null;

        boolean useLootTables = !loot.has("use_tables") || loot.get("use_tables").getAsBoolean();
        Float dropChance = loot.has("drop_chance") && loot.get("drop_chance").isJsonPrimitive()
                ? Math.max(0f, Math.min(1f, loot.get("drop_chance").getAsFloat()))
                : null;
        Identifier deathLootTable = null;
        if (loot.has("death_loot_table") && loot.get("death_loot_table").isJsonPrimitive()) {
            deathLootTable = Identifier.tryParse(loot.get("death_loot_table").getAsString());
        }

        List<JsonElement> deathDrops = new ArrayList<>();
        if (loot.has("death_drops") && loot.get("death_drops").isJsonArray()) {
            for (JsonElement el : loot.getAsJsonArray("death_drops")) {
                if (!el.isJsonNull()) {
                    deathDrops.add(el);
                }
            }
        }

        Map<String, JsonElement> equipment = new LinkedHashMap<>();
        JsonObject equipmentRoot = objectOrEmpty(root, "equipment");
        for (Map.Entry<String, JsonElement> entry : equipmentRoot.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                equipment.put(entry.getKey(), entry.getValue());
            }
        }

        List<GrantedSpellEntry> spells = readGrantedSpells(root.get("spells"));

        List<InventorySlot> inventory = new ArrayList<>();
        if (root.has("inventory") && root.get("inventory").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("inventory")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject slotObj = element.getAsJsonObject();
                JsonElement itemConfig = readInventoryItemConfig(slotObj);
                if (itemConfig == null) {
                    continue;
                }
                int slot;
                if (slotObj.has("spell_slot") && slotObj.get("spell_slot").getAsBoolean()) {
                    slot = GuardEntity.SPELL_SLOT_INDEX;
                } else if (slotObj.has("slot")) {
                    slot = slotObj.get("slot").getAsInt();
                } else {
                    continue;
                }
                inventory.add(new InventorySlot(slot, itemConfig));
            }
        }

        Map<String, Double> attributes = new LinkedHashMap<>();
        JsonObject attributesRoot = objectOrEmpty(root, "attributes");
        for (Map.Entry<String, JsonElement> entry : attributesRoot.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                attributes.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }

        Map<String, JsonElement> configOverrides = new LinkedHashMap<>();
        JsonObject configRoot = objectOrEmpty(root, "config_overrides");
        for (Map.Entry<String, JsonElement> entry : configRoot.entrySet()) {
            configOverrides.put(entry.getKey(), entry.getValue());
        }

        JsonObject entityData = root.has("entity_data") && root.get("entity_data").isJsonObject()
                ? root.getAsJsonObject("entity_data")
                : null;

        List<String> commandTags = new ArrayList<>();
        if (root.has("command_tags") && root.get("command_tags").isJsonArray()) {
            for (JsonElement tag : root.getAsJsonArray("command_tags")) {
                if (tag.isJsonPrimitive()) {
                    commandTags.add(tag.getAsString());
                }
            }
        }

        List<EffectEntry> effects = readEffects(root.get("effects"));
        SpellPoolEntry spellPool = readSpellPool(root.get("spell_pool"));
        List<Loadout> loadouts = readLoadouts(root.get("loadouts"));
        String dialogueRaw = stringOrNull(root, "dialogue");
        Identifier dialogue = dialogueRaw != null ? Identifier.tryParse(dialogueRaw.trim()) : null;

        return new SpecialGuardDefinition(
                id,
                Math.max(0, weight),
                natural,
                chance,
                maxPerVillage,
                biomes,
                dimensions,
                structures,
                structureSearchRadius,
                name,
                names,
                greetings,
                nameVisible,
                variant,
                hireable,
                hero,
                followHero,
                patrolling,
                following,
                blockGui,
                followable,
                lockEquipment,
                immutableEquipment,
                attackPlayers,
                attackMobs,
                List.copyOf(hiringItems),
                hiringCost,
                useLootTables,
                dropChance,
                deathLootTable,
                deathDrops,
                equipment,
                spells,
                inventory,
                attributes,
                configOverrides,
                entityData,
                commandTags,
                effects,
                spellPool,
                loadouts,
                dialogue
        );
    }

    private static List<Loadout> readLoadouts(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<Loadout> loadouts = new ArrayList<>();
        for (JsonElement raw : element.getAsJsonArray()) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject obj = raw.getAsJsonObject();
            int weight = obj.has(LOADOUT_WEIGHT_KEY) ? Math.max(0, obj.get(LOADOUT_WEIGHT_KEY).getAsInt()) : 1;
            if (weight <= 0) {
                continue;
            }
            Map<String, JsonElement> slots = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (!entry.getKey().equals(LOADOUT_WEIGHT_KEY) && !entry.getValue().isJsonNull()) {
                    slots.put(entry.getKey(), entry.getValue());
                }
            }
            if (!slots.isEmpty()) {
                loadouts.add(new Loadout(weight, Map.copyOf(slots)));
            }
        }
        return List.copyOf(loadouts);
    }

    private static List<SpawnFilter> readFilterList(@Nullable JsonElement element) {
        List<SpawnFilter> filters = new ArrayList<>();
        for (String raw : readStringList(element)) {
            SpawnFilter filter = SpawnFilter.parse(raw);
            if (filter != null) {
                filters.add(filter);
            }
        }
        return filters;
    }

    private static List<String> readStringList(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonPrimitive() && !entry.getAsString().isBlank()) {
                values.add(entry.getAsString().trim());
            }
        }
        return values;
    }

    private static List<EffectEntry> readEffects(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<EffectEntry> effects = new ArrayList<>();
        for (JsonElement raw : element.getAsJsonArray()) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject obj = raw.getAsJsonObject();
            Identifier effectId = obj.has("id") ? Identifier.tryParse(obj.get("id").getAsString()) : null;
            if (effectId == null) {
                continue;
            }
            int amplifier = obj.has("amplifier") ? Math.max(0, obj.get("amplifier").getAsInt()) : 0;
            boolean ambient = !obj.has("ambient") || obj.get("ambient").getAsBoolean();
            boolean showParticles = obj.has("show_particles") && obj.get("show_particles").getAsBoolean();
            float chance = obj.has("chance") ? Math.max(0f, Math.min(1f, obj.get("chance").getAsFloat())) : 1.0f;
            effects.add(new EffectEntry(new GuardPermanentEffect(effectId, amplifier, ambient, showParticles), chance));
        }
        return effects;
    }

    @Nullable
    private static SpellPoolEntry readSpellPool(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        List<Identifier> pools = readIdentifierList(obj.get("pools"));
        if (pools.isEmpty()) {
            return null;
        }
        int minTier = obj.has("min_tier") ? Math.max(0, obj.get("min_tier").getAsInt()) : 0;
        int maxTier = obj.has("max_tier") ? Math.max(minTier, obj.get("max_tier").getAsInt()) : Integer.MAX_VALUE;
        float chance = obj.has("chance") ? Math.max(0f, Math.min(1f, obj.get("chance").getAsFloat())) : 1.0f;
        return new SpellPoolEntry(pools, minTier, maxTier, chance);
    }

    @Nullable
    private static JsonElement readInventoryItemConfig(JsonObject slotObj) {
        if (slotObj.has("choices")) {
            return slotObj.get("choices");
        }
        JsonElement stack = slotObj.has("stack") ? slotObj.get("stack") : slotObj.get("item");
        if (stack != null && !stack.isJsonNull()) {
            if (slotObj.has("chance") || slotObj.has("weight")) {
                JsonObject wrapper = new JsonObject();
                if (slotObj.has("chance")) {
                    wrapper.add("chance", slotObj.get("chance"));
                }
                if (slotObj.has("weight")) {
                    wrapper.add("weight", slotObj.get("weight"));
                }
                wrapper.add("stack", stack);
                return wrapper;
            }
            return stack;
        }
        if (slotObj.has("chance")) {
            JsonObject wrapper = new JsonObject();
            wrapper.add("chance", slotObj.get("chance"));
            wrapper.addProperty("empty", true);
            return wrapper;
        }
        return null;
    }

    private static JsonObject objectOrEmpty(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonObject()) {
            return root.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    @Nullable
    private static String stringOrNull(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonPrimitive()) {
            return root.get(key).getAsString();
        }
        return null;
    }

    private static List<GrantedSpellEntry> readGrantedSpells(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<GrantedSpellEntry> entries = new ArrayList<>();
        for (JsonElement raw : element.getAsJsonArray()) {
            if (raw.isJsonPrimitive()) {
                Identifier id = Identifier.tryParse(raw.getAsString());
                if (id != null) {
                    entries.add(new GrantedSpellEntry(id, false, 1.0f));
                }
                continue;
            }
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject obj = raw.getAsJsonObject();
            if (!obj.has("id") || !obj.get("id").isJsonPrimitive()) {
                continue;
            }
            Identifier id = Identifier.tryParse(obj.get("id").getAsString());
            if (id == null) {
                continue;
            }
            boolean passiveOnly = obj.has("passive_only") && obj.get("passive_only").getAsBoolean();
            float chance = obj.has("chance")
                    ? Math.max(0f, Math.min(1f, obj.get("chance").getAsFloat()))
                    : 1.0f;
            entries.add(new GrantedSpellEntry(id, passiveOnly, chance));
        }
        return entries;
    }

    private static List<Identifier> readIdentifierList(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<Identifier> ids = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonPrimitive()) {
                continue;
            }
            Identifier id = Identifier.tryParse(entry.getAsString());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    public Identifier id() {
        return id;
    }

    @Nullable
    public Identifier dialogue() {
        return dialogue;
    }

    public boolean natural() {
        return natural;
    }

    public int spawnWeight() {
        return spawnWeight;
    }

    @Nullable
    public Float spawnChance() {
        return spawnChance;
    }

    public List<String> names() {
        return names;
    }

    public List<String> greetings() {
        return greetings;
    }

    public List<EffectEntry> effects() {
        return effects;
    }

    @Nullable
    public SpellPoolEntry spellPool() {
        return spellPool;
    }

    public List<Loadout> loadouts() {
        return loadouts;
    }

    @Nullable
    public Integer maxPerVillage() {
        return maxPerVillage;
    }

    public List<SpawnFilter> biomeFilters() {
        return biomeFilters;
    }

    public List<Identifier> dimensionFilters() {
        return dimensionFilters;
    }

    public List<SpawnFilter> structureFilters() {
        return structureFilters;
    }

    public int structureSearchRadius() {
        return structureSearchRadius;
    }

    @Nullable
    public String displayName() {
        return displayName;
    }

    public boolean nameVisible() {
        return nameVisible;
    }

    @Nullable
    public Integer variant() {
        return variant;
    }

    @Nullable
    public Boolean hireable() {
        return hireable;
    }

    public boolean heroOfTheVillage() {
        return heroOfTheVillage;
    }

    @Nullable
    public Boolean followHero() {
        return followHero;
    }

    @Nullable
    public Boolean patrolling() {
        return patrolling;
    }

    @Nullable
    public Boolean following() {
        return following;
    }

    public boolean followable() {
        return followable;
    }

    public boolean blockGui() {
        return blockGui;
    }

    public boolean lockEquipment() {
        return lockEquipment;
    }

    public boolean immutableEquipment() {
        return immutableEquipment;
    }

    public boolean attackPlayers() {
        return attackPlayers;
    }

    public List<Identifier> attackMobs() {
        return attackMobs;
    }

    public List<String> hiringItems() {
        return hiringItems;
    }

    @Nullable
    public Integer hiringCost() {
        return hiringCost;
    }

    public boolean useLootTables() {
        return useLootTables;
    }

    @Nullable
    public Float dropChance() {
        return dropChance;
    }

    @Nullable
    public Identifier deathLootTable() {
        return deathLootTable;
    }

    public List<JsonElement> deathDrops() {
        return deathDrops;
    }

    public Map<String, JsonElement> equipment() {
        return equipment;
    }

    public List<GrantedSpellEntry> spells() {
        return spells;
    }

    public List<InventorySlot> inventory() {
        return inventory;
    }

    public Map<String, Double> attributes() {
        return attributes;
    }

    public Map<String, JsonElement> configOverrides() {
        return configOverrides;
    }

    @Nullable
    public JsonObject entityData() {
        return entityData;
    }

    public List<String> commandTags() {
        return commandTags;
    }

    public record InventorySlot(int slot, JsonElement itemConfig) {}

    public record GrantedSpellEntry(Identifier spellId, boolean passiveOnly, float chance) {}

    public record EffectEntry(GuardPermanentEffect effect, float chance) {}

    public record SpellPoolEntry(List<Identifier> pools, int minTier, int maxTier, float chance) {}

    public record Loadout(int weight, Map<String, JsonElement> slots) {}
}
