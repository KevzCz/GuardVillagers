package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellHelper;
import net.spell_power.api.SpellPower;

import java.util.List;

public record SpellContext(
        Identifier spellId,
        RegistryEntry<Spell> entry,
        Spell spell,
        LivingEntity caster,
        LivingEntity target,
        SpellHelper.ImpactContext impactContext
) {
    /**
     * Returns the augmented impacts list for this spell context.
     * If the caster is a GuardEntity, MODIFIER spell PREPEND/APPEND impacts are applied.
     * Falls back to spell.impacts for non-guard casters.
     */
    public List<Spell.Impact> getImpacts() {
        if (caster instanceof GuardEntity guard) {
            return guard.getSpellManager().getAugmentedImpacts(entry);
        }
        return spell.impacts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Identifier spellId;
        private RegistryEntry<Spell> entry;
        private Spell spell;
        private LivingEntity caster;
        private LivingEntity target;
        private SpellHelper.ImpactContext impactContext;

        public Builder spellId(Identifier id) {
            this.spellId = id;
            return this;
        }

        public Builder entry(RegistryEntry<Spell> entry) {
            this.entry = entry;
            this.spell = entry.value();
            return this;
        }

        public Builder caster(LivingEntity caster) {
            this.caster = caster;
            return this;
        }

        public Builder target(LivingEntity target) {
            this.target = target;
            return this;
        }

        // File: SpellContext.java
        public Builder buildImpactContext() {
            if (spell != null && caster != null) {
                SpellPower.Result powerResult = SpellPower.getSpellPower(spell.school, caster);

                if (caster instanceof GuardEntity guard && !caster.getWorld().isClient()) {
                    double rawAttribute = 0.0;
                    if (spell.school.attributeEntry != null && guard.getAttributes().hasAttribute(spell.school.attributeEntry)) {
                        rawAttribute = guard.getAttributeValue(spell.school.attributeEntry);
                    }

                    // Apply modifier spell power bonuses
                    if (entry != null) {
                        SpellPower.Result augmented = guard.getSpellManager().getAugmentedPower(entry);
                        if (augmented.baseValue() != powerResult.baseValue()
                                || augmented.criticalChance() != powerResult.criticalChance()
                                || augmented.criticalDamage() != powerResult.criticalDamage()) {
                            GuardDebugManager.broadcast(guard,
                                    "⚡ Modifier power applied: " + powerResult.baseValue() + " → " + augmented.baseValue(),
                                    Formatting.GOLD);
                            powerResult = augmented;
                        }
                    }

                    GuardDebugManager.broadcast(guard,
                            "🔍 Power Debug - School: " + spell.school.id +
                                    " | Raw Attribute: " + rawAttribute +
                                    " | Calculated Power: " + powerResult.baseValue() +
                                    " | Item: " + guard.getMainHandStack().getItem().getName().getString(),
                            Formatting.YELLOW);
                }

                this.impactContext = new SpellHelper.ImpactContext()
                        .power(powerResult)
                        .position(caster.getEyePos())
                        .target(SpellHelper.focusMode(spell));
            }
            return this;
        }

        public SpellContext build() {
            return new SpellContext(spellId, entry, spell, caster, target, impactContext);
        }
    }
}