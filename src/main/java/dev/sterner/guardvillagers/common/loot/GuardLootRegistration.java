package dev.sterner.guardvillagers.common.loot;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.loot.condition.LootConditionType;
import net.minecraft.loot.entry.LootPoolEntryType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class GuardLootRegistration {

    public static final LootConditionType ITEM_REGISTERED =
            new LootConditionType(ItemRegisteredLootCondition.CODEC);
    public static final LootConditionType ANY_ITEMS_REGISTERED =
            new LootConditionType(AnyItemsRegisteredLootCondition.CODEC);

    private GuardLootRegistration() {}

    public static void register() {
        Registry.register(
                Registries.LOOT_POOL_ENTRY_TYPE,
                GuardVillagers.id("optional_item"),
                OptionalItemEntry.TYPE
        );
        Registry.register(
                Registries.LOOT_POOL_ENTRY_TYPE,
                GuardVillagers.id("optional_one_of"),
                OptionalOneOfEntry.TYPE
        );
        Registry.register(
                Registries.LOOT_CONDITION_TYPE,
                GuardVillagers.id("item_registered"),
                ITEM_REGISTERED
        );
        Registry.register(
                Registries.LOOT_CONDITION_TYPE,
                GuardVillagers.id("any_items_registered"),
                ANY_ITEMS_REGISTERED
        );
    }
}
