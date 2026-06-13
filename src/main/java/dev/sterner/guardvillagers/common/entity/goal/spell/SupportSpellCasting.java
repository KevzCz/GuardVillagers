package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

public final class SupportSpellCasting {

    private SupportSpellCasting() {}

    @Nullable
    public static LivingEntity resolveDeliveryTarget(Spell spell, @Nullable LivingEntity assignedTarget) {
        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.STASH_EFFECT) {
            return null;
        }
        if (spell.target == null) {
            return assignedTarget;
        }
        return switch (spell.target.type) {
            case AREA, CASTER -> null;
            default -> assignedTarget;
        };
    }

    @Nullable
    public static LivingEntity resolveLookTarget(@Nullable LivingEntity assignedTarget, @Nullable LivingEntity combatTarget) {
        if (assignedTarget != null && assignedTarget.isAlive()) {
            return assignedTarget;
        }
        return combatTarget;
    }

    public static void deliver(
            GuardEntity guard,
            Identifier spellId,
            RegistryEntry<Spell> entry,
            @Nullable LivingEntity assignedTarget,
            int channelIndex
    ) {
        Spell spell = entry.value();
        LivingEntity deliveryTarget = resolveDeliveryTarget(spell, assignedTarget);
        SpellContext context = SpellContext.builder()
                .spellId(spellId)
                .entry(entry)
                .caster(guard)
                .target(deliveryTarget)
                .buildImpactContext()
                .build();
        SpellDelivery.deliverSpell(context, channelIndex);
    }
}
