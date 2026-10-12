package dev.sterner.guardvillagers.common.contract;

import dev.sterner.guardvillagers.GuardVillagers;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class GuardContractComponents {

    public static final ComponentType<ContractTerms> CONTRACT_TERMS = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            GuardVillagers.id("contract"),
            ComponentType.<ContractTerms>builder().codec(ContractTerms.CODEC).packetCodec(ContractTerms.PACKET_CODEC).build()
    );

    public static final ComponentType<ContractProgress> CONTRACT_PROGRESS = Registry.register(
            Registries.DATA_COMPONENT_TYPE,
            GuardVillagers.id("contract_progress"),
            ComponentType.<ContractProgress>builder().codec(ContractProgress.CODEC).packetCodec(ContractProgress.PACKET_CODEC).build()
    );

    private GuardContractComponents() {}

    public static void register() {
    }
}
