package dev.sterner.guardvillagers.common.contract;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Uuids;

import java.util.UUID;

public record ContractProgress(UUID contractId, int usesSpent, long cooldownUntil, int active) {

    public static final Codec<ContractProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Uuids.CODEC.fieldOf("contract_id").forGetter(ContractProgress::contractId),
            Codec.INT.optionalFieldOf("uses_spent", 0).forGetter(ContractProgress::usesSpent),
            Codec.LONG.optionalFieldOf("cooldown_until", 0L).forGetter(ContractProgress::cooldownUntil),
            Codec.INT.optionalFieldOf("active", 0).forGetter(ContractProgress::active)
    ).apply(instance, ContractProgress::new));

    public static final PacketCodec<RegistryByteBuf, ContractProgress> PACKET_CODEC = PacketCodecs.registryCodec(CODEC);

    public static ContractProgress fresh() {
        return new ContractProgress(UUID.randomUUID(), 0, 0L, 0);
    }

    public ContractProgress withActive(int active) {
        return new ContractProgress(contractId, usesSpent, cooldownUntil, active);
    }

    public ContractProgress afterSummon(long cooldownUntil, int active) {
        return new ContractProgress(contractId, usesSpent + 1, cooldownUntil, active);
    }

    public ContractProgress reset() {
        return new ContractProgress(contractId, 0, 0L, active);
    }

    public boolean isSpent(ContractTerms terms) {
        return !terms.hasUnlimitedUses() && usesSpent >= terms.uses();
    }
}
