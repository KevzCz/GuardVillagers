package dev.sterner.guardvillagers.common.special;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
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

    private SpecialGuardApplicator() {}

    public static void apply(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        guard.setSpecialGuardType(definition.id());
        guard.setConfigOverrides(new LinkedHashMap<>(definition.configOverrides()));
        guard.setTreatAsHeroOfTheVillage(definition.heroOfTheVillage());
        guard.setHireableOverride(definition.hireable());
        guard.setFollowHeroOverride(definition.followHero());
        guard.setBlockGui(definition.blockGui());
        guard.setLockEquipment(definition.lockEquipment());
        guard.setImmutableEquipment(definition.immutableEquipment());
        guard.setAttackPlayers(definition.attackPlayers());
        guard.setAttackMobs(definition.attackMobs());
        guard.setHiringItemOverride(definition.hiringItem());
        guard.setHiringCostOverride(definition.hiringCost());
        guard.setSkipLootTables(!definition.useLootTables());
        guard.setApplyEquipmentOverridesAfterLoot(definition.useLootTables() && !definition.equipment().isEmpty());
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

        if (definition.displayName() != null) {
            Text name = GuardTextUtils.parseColorName(definition.displayName());
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

        guard.getSpellManager().refresh();
    }

    public static void applyEquipmentOverrides(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        if (definition.equipment().isEmpty()) {
            return;
        }
        applyEquipment(guard, definition, world);
    }

    private static void applyEquipment(GuardEntity guard, SpecialGuardDefinition definition, ServerWorld world) {
        for (Map.Entry<String, JsonElement> entry : definition.equipment().entrySet()) {
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
