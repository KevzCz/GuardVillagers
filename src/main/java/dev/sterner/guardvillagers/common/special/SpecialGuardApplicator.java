package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GrantedSpell;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardSpellScrollRoller;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.spell_engine.item.ScrollItem;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpecialGuardApplicator {

    public static final String NAME_PLACEHOLDER = "{name}";

    private SpecialGuardApplicator() {}

    public static void apply(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        guard.setSpecialGuardType(definition.id());
        guard.setAppliedDefinition(definition);
        guard.setConfigOverrides(new LinkedHashMap<>(definition.configOverrides()));
        guard.setTreatAsHeroOfTheVillage(definition.heroOfTheVillage());
        guard.setHireableOverride(definition.hireable());
        guard.setFollowHeroOverride(definition.followHero());
        guard.setBlockGui(definition.blockGui());
        guard.setFollowable(definition.followable());
        guard.setLockEquipment(definition.lockEquipment());
        guard.setImmutableEquipment(definition.immutableEquipment());
        guard.setAttackPlayers(definition.attackPlayers());
        guard.setAttackMobs(definition.attackMobs());
        guard.setHiringItemsOverride(definition.hiringItems());
        guard.setHiringCostOverride(definition.hiringCost());
        guard.setSkipLootTables(!definition.useLootTables());
        guard.setApplyEquipmentOverridesAfterLoot(definition.useLootTables() && hasEquipment(definition));
        guard.setEquipmentDropChanceOverride(definition.dropChance());
        guard.setDeathLootTable(definition.deathLootTable());

        if (!definition.deathDrops().isEmpty()) {
            List<ItemStack> resolvedDrops = new ArrayList<>();
            for (JsonElement element : definition.deathDrops()) {
                ItemStack stack = rollItemStack(element, guard, world);
                if (stack != null && !stack.isEmpty()) {
                    resolvedDrops.add(stack);
                }
            }
            guard.setDeathDropItems(resolvedDrops);
        }

        String rawName = resolveDisplayName(definition, guard);
        if (rawName != null) {
            Text name = GuardTextUtils.parseColorName(rawName);
            if (name != null) {
                guard.setCustomName(name);
                guard.setCustomNameVisible(definition.nameVisible());
            }
        }

        if (definition.variant() != null) {
            guard.setGuardEntityVariant(definition.variant());
        }
        if (definition.patrolling() != null) {
            guard.setPatrolling(definition.patrolling());
        }
        if (definition.following() != null) {
            guard.setFollowing(definition.following());
        }
        if (definition.blockGui() && !guard.isHired()) {
            guard.setFollowing(false);
            guard.setPatrolling(false);
            guard.setOwnerId(null);
        }

        applyAttributes(guard, definition);
        applyEntityData(guard, definition);
        applyCommandTags(guard, definition);

        if (!definition.useLootTables()) {
            applyEquipment(guard, definition, world);
            applyInventory(guard, definition, world);
            guard.spawnWithArmor = false;
            guard.spellCastGraceTicks = 40;
        }

        applyGrantedSpells(guard, definition, world);
        applySpellPool(guard, definition, world);
        applyEffects(guard, definition, world);

        guard.getSpellManager().refresh();
        guard.setHealth(guard.getMaxHealth());
    }

    @Nullable
    private static String resolveDisplayName(SpecialGuardDefinition definition, GuardEntity guard) {
        String template = definition.displayName();
        List<String> names = definition.names();
        if (names.isEmpty()) {
            return template;
        }
        String chosen = names.get(guard.getRandom().nextInt(names.size()));
        if (template == null) {
            return chosen;
        }
        return template.contains(NAME_PLACEHOLDER) ? template.replace(NAME_PLACEHOLDER, chosen) : template;
    }

    private static void applySpellPool(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        SpecialGuardDefinition.SpellPoolEntry pool = definition.spellPool();
        if (pool == null || world.getRandom().nextFloat() >= pool.chance()) {
            return;
        }
        ItemStack scroll = GuardSpellScrollRoller.rollFromPools(guard, world, pool.pools(), pool.minTier(), pool.maxTier());
        if (scroll == null) {
            return;
        }
        guard.guardInventory.setStack(GuardEntity.SPELL_SLOT_INDEX, scroll);
        guard.setSpellSlotStack(scroll);
    }

    private static void applyEffects(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        if (definition.effects().isEmpty()) {
            return;
        }
        List<GuardPermanentEffect> rolled = new ArrayList<>();
        for (SpecialGuardDefinition.EffectEntry entry : definition.effects()) {
            if (entry.chance() >= 1.0f || world.getRandom().nextFloat() < entry.chance()) {
                rolled.add(entry.effect());
            }
        }
        guard.setPermanentEffects(rolled);
    }

    public static void applyEquipmentOverrides(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        if (!hasEquipment(definition)) {
            return;
        }
        applyEquipment(guard, definition, world);
    }

    private static boolean hasEquipment(SpecialGuardDefinition definition) {
        return !definition.equipment().isEmpty() || !definition.loadouts().isEmpty();
    }

    private static Map<String, JsonElement> resolveEquipment(GuardEntity guard, SpecialGuardDefinition definition) {
        Map<String, JsonElement> merged = new LinkedHashMap<>(definition.equipment());
        SpecialGuardDefinition.Loadout loadout = pickLoadout(definition.loadouts(), guard);
        if (loadout != null) {
            merged.putAll(loadout.slots());
        }
        return merged;
    }

    @Nullable
    private static SpecialGuardDefinition.Loadout pickLoadout(List<SpecialGuardDefinition.Loadout> loadouts, GuardEntity guard) {
        if (loadouts.isEmpty()) {
            return null;
        }
        int total = 0;
        for (SpecialGuardDefinition.Loadout loadout : loadouts) {
            total += loadout.weight();
        }
        int roll = guard.getRandom().nextInt(total);
        for (SpecialGuardDefinition.Loadout loadout : loadouts) {
            roll -= loadout.weight();
            if (roll < 0) {
                return loadout;
            }
        }
        return loadouts.getLast();
    }

    private static void applyEquipment(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        for (Map.Entry<String, JsonElement> entry : resolveEquipment(guard, definition).entrySet()) {
            EquipmentSlot slot = equipmentSlot(entry.getKey());
            if (slot == null) {
                continue;
            }
            ItemStack stack = rollItemStack(entry.getValue(), guard, world);
            if (stack == null) {
                continue;
            }
            guard.equipStack(slot, stack);
            syncInventorySlot(guard, slot, stack);
        }
    }

    private static void applyInventory(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        for (SpecialGuardDefinition.InventorySlot slot : definition.inventory()) {
            if (slot.slot() < 0 || slot.slot() >= guard.guardInventory.size()) {
                continue;
            }
            ItemStack stack = rollItemStack(slot.itemConfig(), guard, world);
            if (stack == null) {
                continue;
            }
            guard.guardInventory.setStack(slot.slot(), stack);
            if (slot.slot() == GuardEntity.SPELL_SLOT_INDEX) {
                guard.setSpellSlotStack(stack);
                GuardVillagers.LOGGER.debug("Applied spell slot item {} for special guard {}",
                        Registries.ITEM.getId(stack.getItem()), guard.getSpecialGuardType());
            } else if (slot.slot() == 5) {
                guard.equipStack(EquipmentSlot.MAINHAND, stack);
            } else if (slot.slot() == 4) {
                guard.equipStack(EquipmentSlot.OFFHAND, stack);
            }
        }
    }

    private static void applyGrantedSpells(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        if (definition.spells().isEmpty()) {
            return;
        }
        List<GrantedSpell> resolved = new ArrayList<>();
        for (SpecialGuardDefinition.GrantedSpellEntry entry : definition.spells()) {
            if (entry.chance() < 1.0f && world.getRandom().nextFloat() >= entry.chance()) {
                continue;
            }
            resolved.add(new GrantedSpell(entry.spellId(), entry.passiveOnly()));
        }
        if (!resolved.isEmpty()) {
            guard.setGrantedSpells(resolved);
        }
    }

    private static void applyAttributes(GuardEntity guard, SpecialGuardDefinition definition) {
        for (Map.Entry<String, Double> entry : definition.attributes().entrySet()) {
            Identifier attributeId = Identifier.tryParse(entry.getKey());
            if (attributeId == null) {
                continue;
            }
            RegistryEntry<EntityAttribute> attribute = Registries.ATTRIBUTE.getEntry(attributeId).orElse(null);
            if (attribute == null) {
                continue;
            }
            EntityAttributeInstance instance = guard.getAttributeInstance(attribute);
            if (instance != null) {
                instance.setBaseValue(entry.getValue());
            }
        }
        applyOverrideAttribute(guard, definition, GuardEffectiveConfig.HEALTH_MODIFIER_KEY, EntityAttributes.GENERIC_MAX_HEALTH, GuardEffectiveConfig.healthModifier(guard));
        applyOverrideAttribute(guard, definition, GuardEffectiveConfig.SPEED_MODIFIER_KEY, EntityAttributes.GENERIC_MOVEMENT_SPEED, GuardEffectiveConfig.speedModifier(guard));
        applyOverrideAttribute(guard, definition, GuardEffectiveConfig.FOLLOW_RANGE_MODIFIER_KEY, EntityAttributes.GENERIC_FOLLOW_RANGE, GuardEffectiveConfig.followRangeModifier(guard));
    }

    private static void applyOverrideAttribute(GuardEntity guard, SpecialGuardDefinition definition, String overrideKey, RegistryEntry<EntityAttribute> attribute, double value) {
        if (!guard.getConfigOverrides().containsKey(overrideKey) || value <= 0 || definesAttribute(definition, attribute)) {
            return;
        }
        EntityAttributeInstance instance = guard.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static boolean definesAttribute(SpecialGuardDefinition definition, RegistryEntry<EntityAttribute> attribute) {
        for (String key : definition.attributes().keySet()) {
            Identifier attributeId = Identifier.tryParse(key);
            if (attributeId != null && attribute.matchesId(attributeId)) {
                return true;
            }
        }
        return false;
    }

    private static void applyEntityData(GuardEntity guard, SpecialGuardDefinition definition) {
        if (definition.entityData() == null) {
            return;
        }
        NbtCompound custom = guard.getSpecialEntityData();
        for (Map.Entry<String, JsonElement> entry : definition.entityData().entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    custom.putBoolean(entry.getKey(), value.getAsBoolean());
                } else if (value.getAsJsonPrimitive().isNumber()) {
                    custom.putDouble(entry.getKey(), value.getAsDouble());
                } else {
                    custom.putString(entry.getKey(), value.getAsString());
                }
            }
        }
        if (custom.getBoolean("Persistent")) {
            guard.setPersistent();
        }
    }

    private static void applyCommandTags(GuardEntity guard, SpecialGuardDefinition definition) {
        for (String tag : definition.commandTags()) {
            guard.addCommandTag(tag);
        }
        guard.addCommandTag("guardvillagers:special_guard");
        guard.addCommandTag("guardvillagers:special_guard/" + definition.id().getNamespace() + "/" + definition.id().getPath());
    }

    @Nullable
    private static EquipmentSlot equipmentSlot(String key) {
        return switch (key.toLowerCase()) {
            case "head", "helmet" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            case "mainhand", "main_hand" -> EquipmentSlot.MAINHAND;
            case "offhand", "off_hand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    private static void syncInventorySlot(GuardEntity guard, EquipmentSlot slot, ItemStack stack) {
        switch (slot) {
            case HEAD -> guard.guardInventory.setStack(0, stack.copy());
            case CHEST -> guard.guardInventory.setStack(1, stack.copy());
            case LEGS -> guard.guardInventory.setStack(2, stack.copy());
            case FEET -> guard.guardInventory.setStack(3, stack.copy());
            case OFFHAND -> guard.guardInventory.setStack(4, stack.copy());
            case MAINHAND -> guard.guardInventory.setStack(5, stack.copy());
            default -> {}
        }
    }

    @Nullable
    private static ItemStack rollItemStack(JsonElement config, GuardEntity guard, ServerWorld world) {
        JsonElement resolved = SpecialGuardItemRoll.resolveItemJson(config, guard.getRandom());
        if (resolved == null) {
            return null;
        }
        return parseItemStack(resolved, world);
    }

    @Nullable
    private static ItemStack parseItemStack(JsonElement element, ServerWorld world) {
        var parsed = ItemStack.CODEC.parse(JsonOps.INSTANCE, element).result();
        if (parsed.isPresent()) {
            return parsed.get();
        }
        return parseScrollFallback(element, world);
    }

    @Nullable
    private static ItemStack parseScrollFallback(JsonElement element, ServerWorld world) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        if (!obj.has("id")) {
            return null;
        }
        Identifier itemId = Identifier.tryParse(obj.get("id").getAsString());
        if (itemId == null) {
            return null;
        }

        ItemStack stack = ItemStack.EMPTY;
        if (ScrollItem.ID.equals(itemId) || itemId.getPath().contains("spell_scroll")) {
            var scrollItem = Registries.ITEM.get(ScrollItem.ID);
            if (scrollItem != null) {
                stack = new ItemStack(scrollItem);
            }
        }
        if (stack.isEmpty()) {
            var item = Registries.ITEM.get(itemId);
            if (item == null) {
                return null;
            }
            int count = obj.has("count") ? Math.max(1, obj.get("count").getAsInt()) : 1;
            stack = new ItemStack(item, count);
        }

        var withComponents = ItemStack.CODEC.parse(JsonOps.INSTANCE, element);
        return withComponents.result().orElse(stack);
    }
}
