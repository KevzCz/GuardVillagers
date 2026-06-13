package dev.sterner.guardvillagers.common.loot;

import net.minecraft.item.Item;
import net.minecraft.loot.context.LootContext;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.List;

final class LootItemChecks {

    private LootItemChecks() {}

    static boolean isRegistered(LootContext context, Identifier itemId) {
        var registry = context.getWorld().getRegistryManager().getWrapperOrThrow(RegistryKeys.ITEM);
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, itemId);
        return registry.getOptional(key).isPresent();
    }

    static boolean anyRegistered(LootContext context, List<Identifier> itemIds) {
        for (Identifier itemId : itemIds) {
            if (isRegistered(context, itemId)) {
                return true;
            }
        }
        return false;
    }
}
