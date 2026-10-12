package dev.sterner.guardvillagers.common.contract;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public enum ContractState {
    SEALED,
    ACTIVE,
    COOLING,
    SPENT;

    private static final float PREDICATE_STEP = 0.1F;

    public float predicateValue() {
        return ordinal() * PREDICATE_STEP;
    }

    public static ContractState of(ItemStack stack, @Nullable World world) {
        ContractTerms terms = stack.get(GuardContractComponents.CONTRACT_TERMS);
        ContractProgress progress = stack.get(GuardContractComponents.CONTRACT_PROGRESS);
        if (terms == null || progress == null) {
            return SEALED;
        }
        if (progress.active() > 0) {
            return ACTIVE;
        }
        if (progress.isSpent(terms)) {
            return SPENT;
        }
        if (world != null && world.getTime() < progress.cooldownUntil()) {
            return COOLING;
        }
        return SEALED;
    }
}
