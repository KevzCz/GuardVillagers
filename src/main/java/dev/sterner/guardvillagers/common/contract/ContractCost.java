package dev.sterner.guardvillagers.common.contract;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

public record ContractCost(List<String> items, int count) {

    public static final ContractCost FREE = new ContractCost(List.of(), 0);
    private static final int DEFAULT_COUNT = 1;

    public static final Codec<ContractCost> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("items", List.of()).forGetter(ContractCost::items),
            Codec.INT.optionalFieldOf("count", DEFAULT_COUNT).forGetter(ContractCost::count)
    ).apply(instance, ContractCost::new));

    public boolean isFree() {
        return items.isEmpty() || count <= 0;
    }
}
