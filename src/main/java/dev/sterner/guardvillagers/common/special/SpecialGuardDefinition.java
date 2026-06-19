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

    private final Identifier id;
    private final int spawnWeight;
    @Nullable private final Integer maxPerVillage;
    private final List<Identifier> biomeFilters;
    private final List<Identifier> dimensionFilters;
    @Nullable private final String displayName;
    private final boolean nameVisible;
    @Nullable private final Integer variant;
    @Nullable private final Boolean hireable;
    private final boolean heroOfTheVillage;
    @Nullable private final Boolean followHero;
    @Nullable private final Boolean patrolling;
    @Nullable private final Boolean following;
    private final boolean blockGui;
    private final boolean lockEquipment;
    private final boolean immutableEquipment;
    private final boolean attackPlayers;
    private final List<Identifier> attackMobs;
    @Nullable private final Identifier hiringItem;
    @Nullable private final Integer hiringCost;
    private final boolean useLootTables;
    @Nullable private final Float dropChance;
    @Nullable private final Identifier deathLootTable;
    private final List<JsonElement> deathDrops;
    private final Map<String, JsonElement> equipment;
    private final List<InventorySlot> inventory;
    private final Map<String, Double> attributes;
    private final Map<String, JsonElement> configOverrides;
    @Nullable private final JsonObject entityData;
    private final List<String> commandTags;

    private SpecialGuardDefinition(
            Identifier id,
            int spawnWeight,
            @Nullable Integer maxPerVillage,
            List<Identifier> biomeFilters,
            List<Identifier> dimensionFilters,
            @Nullable String displayName,
            boolean nameVisible,
            @Nullable Integer variant,
            @Nullable Boolean hireable,
            boolean heroOfTheVillage,
            @Nullable Boolean followHero,
            @Nullable Boolean patrolling,
            @Nullable Boolean following,
            boolean blockGui,
            boolean lockEquipment,
            boolean immutableEquipment,
            boolean attackPlayers,
            List<Identifier> attackMobs,
            @Nullable Identifier hiringItem,
            @Nullable Integer hiringCost,
            boolean useLootTables,
            @Nullable Float dropChance,
            @Nullable Identifier deathLootTable,
            List<JsonElement> deathDrops,
            Map<String, JsonElement> equipment,
            List<InventorySlot> inventory,
            Map<String, Double> attributes,
            Map<String, JsonElement> configOverrides,
            @Nullable JsonObject entityData,
            List<String> commandTags
    ) {
        this.id = id;
        this.spawnWeight = spawnWeight;
        this.maxPerVillage = maxPerVillage;
        this.biomeFilters = biomeFilters;
        this.dimensionFilters = dimensionFilters;
        this.displayName = displayName;
        this.nameVisible = nameVisible;
        this.variant = variant;
        this.hireable = hireable;
        this.heroOfTheVillage = heroOfTheVillage;
        this.followHero = followHero;
        this.patrolling = patrolling;
        this.following = following;
        this.blockGui = blockGui;
        this.lockEquipment = lockEquipment;
        this.immutableEquipment = immutableEquipment;
        this.attackPlayers = attackPlayers;
        this.attackMobs = attackMobs;
        this.hiringItem = hiringItem;
        this.hiringCost = hiringCost;
        this.useLootTables = useLootTables;
        this.dropChance = dropChance;
        this.deathLootTable = deathLootTable;
        this.deathDrops = deathDrops;
        this.equipment = equipment;
        this.inventory = inventory;
        this.attributes = attributes;
        this.configOverrides = configOverrides;
        this.entityData = entityData;
        this.commandTags = commandTags;
    }

    public static SpecialGuardDefinition parse(Identifier id, JsonObject root) {
        JsonObject spawn = objectOrEmpty(root, "spawn");
        JsonObject display = objectOrEmpty(root, "display");
        JsonObject behavior = objectOrEmpty(root, "behavior");
        JsonObject loot = objectOrEmpty(root, "loot");

        int weight = spawn.has("weight") ? spawn.get("weight").getAsInt() : 100;
        Integer maxPerVillage = spawn.has("max_per_village") ? Math.max(0, spawn.get("max_per_village").getAsInt()) : null;
        List<Identifier> biomes = readIdentifierList(spawn.get("biomes"));
        List<Identifier> dimensions = readIdentifierList(spawn.get("dimensions"));

        String name = stringOrNull(display, "name");
        boolean nameVisible = !display.has("name_visible") || display.get("name_visible").getAsBoolean();
        Integer variant = display.has("variant") ? display.get("variant").getAsInt() : null;

        Boolean hireable = behavior.has("hireable") ? behavior.get("hireable").getAsBoolean() : null;
        boolean hero = behavior.has("hero_of_the_village") && behavior.get("hero_of_the_village").getAsBoolean();
        Boolean followHero = behavior.has("follow_hero") ? behavior.get("follow_hero").getAsBoolean() : null;
        Boolean patrolling = behavior.has("patrolling") ? behavior.get("patrolling").getAsBoolean() : null;
        Boolean following = behavior.has("following") ? behavior.get("following").getAsBoolean() : null;
        boolean blockGui = behavior.has("block_gui") && behavior.get("block_gui").getAsBoolean();
        boolean lockEquipment = behavior.has("lock_equipment") && behavior.get("lock_equipment").getAsBoolean();
        boolean immutableEquipment = behavior.has("immutable_equipment") && behavior.get("immutable_equipment").getAsBoolean();
        boolean attackPlayers = behavior.has("attack_players") && behavior.get("attack_players").getAsBoolean();
        List<Identifier> attackMobs = readIdentifierList(behavior.get("attack_mobs"));

        JsonObject hiring = objectOrEmpty(root, "hiring");
        Identifier hiringItem = hiring.has("item") && hiring.get("item").isJsonPrimitive()
                ? Identifier.tryParse(hiring.get("item").getAsString()) : null;
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

        return new SpecialGuardDefinition(
                id,
                Math.max(0, weight),
                maxPerVillage,
                biomes,
                dimensions,
                name,
                nameVisible,
                variant,
                hireable,
                hero,
                followHero,
                patrolling,
                following,
                blockGui,
                lockEquipment,
                immutableEquipment,
                attackPlayers,
                attackMobs,
                hiringItem,
                hiringCost,
                useLootTables,
                dropChance,
                deathLootTable,
                deathDrops,
                equipment,
                inventory,
                attributes,
                configOverrides,
                entityData,
                commandTags
        );
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

    public int spawnWeight() {
        return spawnWeight;
    }

    @Nullable
    public Integer maxPerVillage() {
        return maxPerVillage;
    }

    public List<Identifier> biomeFilters() {
        return biomeFilters;
    }

    public List<Identifier> dimensionFilters() {
        return dimensionFilters;
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

    @Nullable
    public Identifier hiringItem() {
        return hiringItem;
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
}
