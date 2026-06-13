package dev.sterner.guardvillagers.common.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.condition.LootConditionType;
import net.minecraft.loot.context.LootContext;
import net.minecraft.util.Identifier;

import java.util.List;

public record AnyItemsRegisteredLootCondition(List<Identifier> items) implements LootCondition {

    public static final MapCodec<AnyItemsRegisteredLootCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Identifier.CODEC.listOf().fieldOf("items").forGetter(AnyItemsRegisteredLootCondition::items))
                    .apply(instance, AnyItemsRegisteredLootCondition::new));

    @Override
    public LootConditionType getType() {
        return GuardLootRegistration.ANY_ITEMS_REGISTERED;
    }

    @Override
    public boolean test(LootContext context) {
        return LootItemChecks.anyRegistered(context, items);
    }
}
