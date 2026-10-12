package dev.sterner.guardvillagers.compat.rpgdialogue;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import dev.sterner.guardvillagers.common.special.SpecialGuardDefinition;
import dev.sterner.guardvillagers.compat.GuardCompat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueContext;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueManager;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueSpeakers;
import net.pixeldreamstudios.rpgdialogue.dialogue.Kind;
import net.pixeldreamstudios.rpgdialogue.dialogue.SpeakerBindings;
import net.pixeldreamstudios.rpgdialogue.dialogue.SpeakerRoute;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public final class GuardDialogueCompat {

    public static final Identifier HUB = GuardVillagers.id("guard");
    public static final Identifier HUB_RPG_VILLAGERS = GuardVillagers.id("guard_rpg_villagers");
    private static final int ROUTE_PRIORITY = -100;
    private static final boolean ROUTE_INTERRUPTS = true;

    private GuardDialogueCompat() {}

    public static void init() {
        GuardDialogueConditions.register();
        GuardDialogueActions.register();
        GuardDialogueValues.register();
        SpeakerBindings.route(new SpeakerRoute(new Kind.Exactly(GuardVillagers.GUARD_VILLAGER), ROUTE_PRIORITY, ROUTE_INTERRUPTS, GuardDialogueCompat::dialogueFor));
        if (GuardCompat.isRpgVillagersLoaded()) {
            DialogueSpeakers.NAMING.register(GuardSpeakerNames::nameOf);
        }
    }

    @Nullable
    private static Identifier dialogueFor(ServerPlayerEntity player, Entity entity) {
        if (!(entity instanceof GuardEntity guard) || player.shouldCancelInteraction() || guard.getTarget() == player) {
            return null;
        }
        if (GuardHiring.canBeHired(guard) && GuardHiring.matches(player.getMainHandStack(), GuardHiring.entriesFor(guard))) {
            return null;
        }
        if (GuardCompat.isRpgVillagersLoaded() && DialogueManager.has(HUB_RPG_VILLAGERS)) {
            return HUB_RPG_VILLAGERS;
        }
        return HUB;
    }

    static Optional<GuardEntity> guardOf(DialogueContext context) {
        return context.targetAs(GuardEntity.class);
    }

    @Nullable
    static Identifier ownDialogueOf(GuardEntity guard) {
        return guard.getSpecialDefinition()
                .map(SpecialGuardDefinition::dialogue)
                .filter(DialogueManager::has)
                .orElse(null);
    }

    static List<String> greetingsOf(GuardEntity guard) {
        return guard.getSpecialDefinition().map(SpecialGuardDefinition::greetings).orElse(List.of());
    }

    static boolean isHeroFor(GuardEntity guard, PlayerEntity player) {
        return !guard.isHired() && guard.playerHasHeroInteractionAccess(player) && guard.canPlayerUseHeroControls(player);
    }

    static boolean canCommand(GuardEntity guard, PlayerEntity player) {
        return guard.isHired() ? guard.isOwnedBy(player) : isHeroFor(guard, player);
    }
}
