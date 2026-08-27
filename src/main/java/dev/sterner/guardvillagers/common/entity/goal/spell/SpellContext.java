package dev.sterner.guardvillagers.common.entity.goal.spell;

import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.internals.SpellExecution;
import net.spell_engine.internals.target.SpellIntents;
import net.spell_power.api.SpellPower;

import java.util.List;

public record SpellContext(
        Identifier spellId,
        RegistryEntry<Spell> entry,
        Spell spell,
        LivingEntity caster,
        LivingEntity target,
        SpellExecution.ImpactContext impactContext
) {
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
        private SpellExecution.ImpactContext impactContext;

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

        public Builder buildImpactContext() {
            if (spell != null && caster != null) {
                SpellPower.Result powerResult = SpellPower.getSpellPower(spell.school, caster);

                if (caster instanceof GuardEntity guard && !caster.getWorld().isClient()) {
                    if (entry != null) {
                        SpellPower.Result augmented = guard.getSpellManager().getAugmentedPower(entry);
                        if (augmented.baseValue() != powerResult.baseValue()
                                || augmented.criticalChance() != powerResult.criticalChance()
                                || augmented.criticalDamage() != powerResult.criticalDamage()) {
                            if (GuardDebugManager.hasWatchers(guard)) {
                                double before = powerResult.baseValue();
                                GuardDebugManager.broadcast(guard,
                                        "⚡ Modifier power applied: " + before + " → " + augmented.baseValue(),
                                        Formatting.GOLD);
                            }
                            powerResult = augmented;
                        }
                    }

                    if (GuardDebugManager.hasWatchers(guard)) {
                        double rawAttribute = 0.0;
                        if (spell.school.attributeEntry != null
                                && guard.getAttributes().hasAttribute(spell.school.attributeEntry)) {
                            rawAttribute = guard.getAttributeValue(spell.school.attributeEntry);
                        }
                        GuardDebugManager.broadcast(guard,
                                "🔍 Power Debug - School: " + spell.school.id +
                                        " | Raw Attribute: " + rawAttribute +
                                        " | Calculated Power: " + powerResult.baseValue() +
                                        " | Item: " + guard.getMainHandStack().getItem().getName().getString(),
                                Formatting.YELLOW);
                    }
                }

                this.impactContext = new SpellExecution.ImpactContext()
                        .power(powerResult)
                        .position(caster.getEyePos())
                        .target(SpellIntents.focusMode(spell));
            }
            return this;
        }

        public SpellContext build() {
            return new SpellContext(spellId, entry, spell, caster, target, impactContext);
        }
    }
}