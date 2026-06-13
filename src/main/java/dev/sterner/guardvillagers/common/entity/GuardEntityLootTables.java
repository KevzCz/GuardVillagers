package dev.sterner.guardvillagers.common.entity;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextType;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class GuardEntityLootTables {
    public static final LootContextType SLOT = LootContextTypes.register("slot", (builder) -> {
        builder.allow(LootContextParameters.THIS_ENTITY);
    });

    public static final RegistryKey<LootTable> GUARD_MAIN_HAND = registerLootTable("entities/guard_main_hand");
    public static final RegistryKey<LootTable> GUARD_OFF_HAND = registerLootTable("entities/guard_off_hand");
    public static final RegistryKey<LootTable> GUARD_HELMET = registerLootTable("entities/guard_helmet");
    public static final RegistryKey<LootTable> GUARD_CHEST = registerLootTable("entities/guard_chestplate");
    public static final RegistryKey<LootTable> GUARD_LEGGINGS = registerLootTable("entities/guard_legs");
    public static final RegistryKey<LootTable> GUARD_FEET = registerLootTable( "entities/guard_feet");
    public static final RegistryKey<LootTable> GUARD_SPELL_SLOT = registerLootTable("entities/guard_spell_slot");

    public static RegistryKey<LootTable> registerLootTable(String id) {
        return RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(GuardVillagers.MODID, id));
    }
}
