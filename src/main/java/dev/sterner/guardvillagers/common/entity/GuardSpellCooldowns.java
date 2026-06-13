package dev.sterner.guardvillagers.common.entity;

import net.minecraft.registry.entry.RegistryEntry;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.registry.SpellRegistry;
import net.spell_engine.internals.SpellCooldownManager;
import net.spell_engine.utils.PatternMatching;

import java.util.List;

public final class GuardSpellCooldowns {

    private GuardSpellCooldowns() {}

    public static void applyCooldownImpacts(GuardEntity guard, List<Spell.Impact> impacts) {
        if (impacts == null || impacts.isEmpty()) {
            return;
        }

        SpellCooldownManager manager = guard.getCooldownManager();
        boolean modified = false;

        for (Spell.Impact impact : impacts) {
            if (impact.action == null || impact.action.type != Spell.Impact.Action.Type.COOLDOWN) {
                continue;
            }
            Spell.Impact.Action.Cooldown cooldown = impact.action.cooldown;
            if (cooldown == null) {
                continue;
            }
            if (cooldown.actives != null) {
                modified |= modifyCooldowns(
                        guard.getSpellManager().activeSpellEntries(),
                        cooldown.actives,
                        manager
                );
            }
            if (cooldown.passives != null) {
                modified |= modifyCooldowns(
                        guard.getSpellManager().passiveSpellEntries(),
                        cooldown.passives,
                        manager
                );
            }
        }

        if (modified) {
            manager.update(false);
        }
    }

    private static boolean modifyCooldowns(
            List<RegistryEntry<Spell>> spells,
            Spell.Impact.Action.Cooldown.Modify modifier,
            SpellCooldownManager cooldownManager
    ) {
        boolean modifiedAny = false;
        for (RegistryEntry<Spell> spell : spells) {
            if (!PatternMatching.matches(spell, SpellRegistry.KEY, modifier.id)) {
                continue;
            }
            int duration = cooldownManager.getCooldownDuration(spell);
            int updatedDuration = (int) ((duration + modifier.duration_add) * modifier.duration_multiplier);
            if (updatedDuration != duration) {
                cooldownManager.setDurationLeft(spell, updatedDuration);
                modifiedAny = true;
            }
        }
        return modifiedAny;
    }
}
