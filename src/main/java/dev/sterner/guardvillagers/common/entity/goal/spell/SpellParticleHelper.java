package dev.sterner.guardvillagers.common.entity.goal.spell;

import net.spell_engine.api.spell.fx.ParticleGroup;

import java.util.List;

public final class SpellParticleHelper {

    private SpellParticleHelper() {}

    public static List<ParticleGroup> sanitize(List<ParticleGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return groups;
        }
        return groups.stream()
                .filter(group -> group != null && group.batch != null && group.batch.anchor != null)
                .toList();
    }

    public static boolean isEmpty(List<ParticleGroup> groups) {
        return groups == null || groups.isEmpty();
    }
}
