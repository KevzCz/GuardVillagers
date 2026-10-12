package dev.sterner.guardvillagers.common.contract;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.entry.LeafEntry;
import net.minecraft.loot.entry.LootPoolEntryType;
import net.minecraft.loot.function.LootFunction;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.function.Consumer;

public class ContractLootEntry extends LeafEntry {

    public static final MapCodec<ContractLootEntry> CODEC = RecordCodecBuilder.mapCodec(instance ->
            addLeafFields(instance)
                    .and(Identifier.CODEC.fieldOf("table").forGetter(entry -> entry.table))
                    .apply(instance, ContractLootEntry::new)
    );

    public static final LootPoolEntryType TYPE = new LootPoolEntryType(CODEC);

    private final Identifier table;

    private ContractLootEntry(int weight, int quality, List<LootCondition> conditions, List<LootFunction> functions,
                              Identifier table) {
        super(weight, quality, conditions, functions);
        this.table = table;
    }

    public static LeafEntry.Builder<?> builder(Identifier table) {
        return builder((weight, quality, conditions, functions) ->
                new ContractLootEntry(weight, quality, conditions, functions, table));
    }

    @Override
    public LootPoolEntryType getType() {
        return TYPE;
    }

    @Override
    protected void generateLoot(Consumer<ItemStack> lootConsumer, LootContext context) {
        if (!GuardVillagersConfig.contractsEnabled || !GuardVillagersConfig.contractLootEnabled) {
            return;
        }
        for (ContractLootRegistry.Rule rule : ContractLootRegistry.INSTANCE.forTable(table)) {
            if (context.getRandom().nextFloat() >= rule.chance()) {
                continue;
            }
            ContractLootRegistry.pick(rule.contracts(), context.getRandom()).ifPresent(terms -> {
                ItemStack stack = new ItemStack(GuardVillagers.CONTRACT);
                stack.set(GuardContractComponents.CONTRACT_TERMS, terms);
                lootConsumer.accept(stack);
            });
        }
    }
}
