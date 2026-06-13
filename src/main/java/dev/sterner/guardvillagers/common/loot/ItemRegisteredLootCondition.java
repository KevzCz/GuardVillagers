package dev.sterner.guardvillagers.common.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.condition.LootConditionType;
import net.minecraft.loot.context.LootContext;
import net.minecraft.util.Identifier;

public record ItemRegisteredLootCondition(Identifier item) implements LootCondition {

    public static final MapCodec<ItemRegisteredLootCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Identifier.CODEC.fieldOf("item").forGetter(ItemRegisteredLootCondition::item))
                    .apply(instance, ItemRegisteredLootCondition::new));

    @Override
    public LootConditionType getType() {
        return GuardLootRegistration.ITEM_REGISTERED;
    }

    @Override
    public boolean test(LootContext context) {
        return LootItemChecks.isRegistered(context, item);
    }
}
