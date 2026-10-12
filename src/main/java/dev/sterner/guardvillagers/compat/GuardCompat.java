package dev.sterner.guardvillagers.compat;

import dev.sterner.guardvillagers.compat.rpgdialogue.GuardDialogueCompat;
import net.fabricmc.loader.api.FabricLoader;

public final class GuardCompat {

    public static final String RPG_DIALOGUE = "rpg_dialogue";
    public static final String RPG_VILLAGERS = "rpg_villagers";

    private GuardCompat() {}

    public static boolean isRpgDialogueLoaded() {
        return FabricLoader.getInstance().isModLoaded(RPG_DIALOGUE);
    }

    public static boolean isRpgVillagersLoaded() {
        return FabricLoader.getInstance().isModLoaded(RPG_VILLAGERS);
    }

    public static void init() {
        if (isRpgDialogueLoaded()) {
            GuardDialogueCompat.init();
        }
    }
}
