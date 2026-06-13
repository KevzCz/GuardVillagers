package dev.sterner.guardvillagers.common.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

public class OptionalOneOfEntry extends LeafEntry {

    public static final MapCodec<OptionalOneOfEntry> CODEC = RecordCodecBuilder.mapCodec(instance ->
            addLeafFields(instance)
                    .and(Identifier.CODEC.listOf().fieldOf("items").forGetter(entry -> entry.itemIds))
                    .apply(instance, OptionalOneOfEntry::new)
    );

    public static final LootPoolEntryType TYPE = new LootPoolEntryType(CODEC);

    private final List<Identifier> itemIds;

    private OptionalOneOfEntry(
            int weight,
            int quality,
            List<LootCondition> conditions,
            List<LootFunction> functions,
            List<Identifier> itemIds
    ) {
        super(weight, quality, withAvailabilityCondition(conditions, itemIds), functions);
        this.itemIds = List.copyOf(itemIds);
    }

    @Override
    public LootPoolEntryType getType() {
        return TYPE;
    }

    @Override
    protected void generateLoot(Consumer<ItemStack> lootConsumer, LootContext context) {
        var registry = context.getWorld().getRegistryManager().getWrapperOrThrow(RegistryKeys.ITEM);
        List<RegistryKey<Item>> available = new ArrayList<>();
        for (Identifier itemId : itemIds) {
            RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, itemId);
            if (registry.getOptional(key).isPresent()) {
                available.add(key);
            }
        }
        if (available.isEmpty()) {
            return;
        }

        RegistryKey<Item> picked = available.get(context.getRandom().nextInt(available.size()));
        ItemStack stack = new ItemStack(registry.getOrThrow(picked).value());
        for (LootFunction function : functions) {
            stack = function.apply(stack, context);
        }
        lootConsumer.accept(stack);
    }

    private static List<LootCondition> withAvailabilityCondition(
            List<LootCondition> conditions,
            List<Identifier> itemIds
    ) {
        List<LootCondition> merged = new ArrayList<>(conditions.size() + 1);
        merged.addAll(conditions);
        merged.add(new AnyItemsRegisteredLootCondition(itemIds));
        return merged;
    }
}
