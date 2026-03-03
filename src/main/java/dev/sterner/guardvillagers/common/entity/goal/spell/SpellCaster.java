package dev.sterner.guardvillagers.common.entity.goal.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.spell_engine.api.spell.Spell;

import java.util.Optional;

public interface SpellCaster {
    boolean isCastingSpell();
    void setCastingSpell(boolean casting);

    Optional<RegistryEntry.Reference<Spell>> getSpellEntry(Identifier spellId);

    default void startCasting() {
        setCastingSpell(true);
    }

    default void stopCasting() {
        setCastingSpell(false);
    }
}