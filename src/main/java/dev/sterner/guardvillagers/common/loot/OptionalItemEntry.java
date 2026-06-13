package dev.sterner.guardvillagers.common.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootTableReporter;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.entry.LeafEntry;
import net.minecraft.loot.entry.LootPoolEntryType;
import net.minecraft.loot.function.LootFunction;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class OptionalItemEntry extends LeafEntry {

    public static final MapCodec<OptionalItemEntry> CODEC = RecordCodecBuilder.mapCodec(instance ->
            addLeafFields(instance)
                    .and(Identifier.CODEC.fieldOf("name").forGetter(entry -> entry.itemId))
                    .and(Codec.BOOL.optionalFieldOf("required", false).forGetter(entry -> entry.required))
                    .apply(instance, OptionalItemEntry::new)
    );

    public static final LootPoolEntryType TYPE = new LootPoolEntryType(CODEC);

    private final Identifier itemId;
    private final boolean required;

    private OptionalItemEntry(
            int weight,
            int quality,
            List<LootCondition> conditions,
            List<LootFunction> functions,
            Identifier itemId,
            boolean required
    ) {
        super(weight, quality, withAvailabilityCondition(conditions, itemId), functions);
        this.itemId = itemId;
        this.required = required;
    }

    @Override
    public LootPoolEntryType getType() {
        return TYPE;
    }

    @Override
    public void validate(LootTableReporter reporter) {
        super.validate(reporter);
        if (!required) {
            return;
        }
        var items = reporter.getDataLookup().getOrThrow(RegistryKeys.ITEM);
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, itemId);
        if (items.getOptional(key).isEmpty()) {
            reporter.report("Missing required optional_item entry: " + itemId);
        }
    }

    @Override
    protected void generateLoot(Consumer<ItemStack> lootConsumer, LootContext context) {
        var registry = context.getWorld().getRegistryManager().getWrapperOrThrow(RegistryKeys.ITEM);
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, itemId);
        var entry = registry.getOptional(key);
        if (entry.isEmpty()) {
            return;
        }

        ItemStack stack = new ItemStack(entry.get().value());
        for (LootFunction function : functions) {
            stack = function.apply(stack, context);
        }
        lootConsumer.accept(stack);
    }

    private static List<LootCondition> withAvailabilityCondition(
            List<LootCondition> conditions,
            Identifier itemId
    ) {
        List<LootCondition> merged = new ArrayList<>(conditions.size() + 1);
        merged.addAll(conditions);
        merged.add(new ItemRegisteredLootCondition(itemId));
        return merged;
    }
}
