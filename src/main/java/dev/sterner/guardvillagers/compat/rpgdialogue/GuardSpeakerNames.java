package dev.sterner.guardvillagers.compat.rpgdialogue;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

final class GuardSpeakerNames {

    private static final String GIVEN_NAME_KEY_PREFIX = "rpg_villagers.name.given.";
    private static final String TITLED_FORM_KEY = "rpg_villagers.name.form.titled";
    private static final String GUARD_TITLE_KEY = "guardvillagers.rpg_villagers.title";
    private static final int GIVEN_NAME_POOL_SIZE = 96;

    private GuardSpeakerNames() {}

    @Nullable
    static Text nameOf(Entity entity) {
        if (!(entity instanceof GuardEntity guard)) {
            return null;
        }
        int index = Math.floorMod(guard.getUuid().hashCode(), GIVEN_NAME_POOL_SIZE);
        return Text.translatable(TITLED_FORM_KEY, Text.translatable(GIVEN_NAME_KEY_PREFIX + index), Text.translatable(GUARD_TITLE_KEY));
    }
}
