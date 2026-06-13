package dev.sterner.guardvillagers.common.entity.goal.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class GuardSpellEffectHelper {

    private GuardSpellEffectHelper() {}

    public static boolean alreadyAffected(@Nullable LivingEntity target, Spell spell) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        List<RegistryEntry<StatusEffect>> effects = extractStatusEffects(spell);
        if (effects.isEmpty()) {
            return false;
        }
        for (RegistryEntry<StatusEffect> effect : effects) {
            if (!target.hasStatusEffect(effect)) {
                return false;
            }
        }
        return true;
    }

    public static boolean allAlliesAlreadyAffected(List<LivingEntity> allies, Spell spell) {
        if (allies.isEmpty()) {
            return false;
        }
        List<RegistryEntry<StatusEffect>> effects = extractStatusEffects(spell);
        if (effects.isEmpty()) {
            return false;
        }
        for (LivingEntity ally : allies) {
            if (!alreadyAffected(ally, spell)) {
                return false;
            }
        }
        return true;
    }

    private static List<RegistryEntry<StatusEffect>> extractStatusEffects(Spell spell) {
        List<RegistryEntry<StatusEffect>> effects = new ArrayList<>();
        if (spell.impacts == null) {
            return effects;
        }
        for (Spell.Impact impact : spell.impacts) {
            if (impact.action == null || impact.action.type != Spell.Impact.Action.Type.STATUS_EFFECT) {
                continue;
            }
            Spell.Impact.Action.StatusEffect status = impact.action.status_effect;
            if (status == null || status.effect_id == null) {
                continue;
            }
            Identifier effectId = Identifier.tryParse(status.effect_id);
            if (effectId == null) {
                continue;
            }
            Registries.STATUS_EFFECT.getEntry(effectId).ifPresent(effects::add);
        }
        return effects;
    }
}
