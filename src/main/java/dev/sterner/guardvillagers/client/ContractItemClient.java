package dev.sterner.guardvillagers.client;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.contract.ContractState;
import dev.sterner.guardvillagers.common.contract.ContractTerms;
import dev.sterner.guardvillagers.common.contract.GuardContractComponents;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.util.math.ColorHelper;

public final class ContractItemClient {

    private static final String STATE_PREDICATE = "contract_state";
    private static final int RIBBON_TINT_INDEX = 1;
    private static final int NO_TINT = -1;

    private ContractItemClient() {}

    public static void register() {
        ModelPredicateProviderRegistry.register(GuardVillagers.CONTRACT, GuardVillagers.id(STATE_PREDICATE),
                (stack, world, entity, seed) -> ContractState.of(stack, world != null ? world : entity != null ? entity.getWorld() : null).predicateValue());
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> {
            if (tintIndex != RIBBON_TINT_INDEX) {
                return NO_TINT;
            }
            ContractTerms terms = stack.get(GuardContractComponents.CONTRACT_TERMS);
            return ColorHelper.Argb.fullAlpha(terms != null ? terms.ribbon() : ContractTerms.DEFAULT_RIBBON);
        }, GuardVillagers.CONTRACT);
    }
}
