package dev.sterner.guardvillagers.common.entity.goal.spell;

import net.spell_engine.api.spell.fx.ParticleBatch;

import java.util.Arrays;

public final class SpellParticleHelper {

    private SpellParticleHelper() {}

    public static ParticleBatch[] sanitize(ParticleBatch[] batches) {
        if (batches == null || batches.length == 0) {
            return batches;
        }
        return Arrays.stream(batches)
                .filter(batch -> batch != null && batch.origin != null)
                .toArray(ParticleBatch[]::new);
    }

    public static boolean isEmpty(ParticleBatch[] batches) {
        return batches == null || batches.length == 0;
    }
}
