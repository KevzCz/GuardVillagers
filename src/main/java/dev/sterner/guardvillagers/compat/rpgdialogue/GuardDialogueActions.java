package dev.sterner.guardvillagers.compat.rpgdialogue;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardOrder;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import net.minecraft.util.Identifier;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueAction;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueContext;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueManager;

final class GuardDialogueActions {

    private GuardDialogueActions() {}

    static void register() {
        DialogueAction.register(GuardVillagers.id("hire"), Hire.CODEC);
        DialogueAction.register(GuardVillagers.id("open_own_dialogue"), OpenOwnDialogue.CODEC);
        DialogueAction.register(GuardVillagers.id("open_inventory"), OpenInventory.CODEC);
        DialogueAction.register(GuardVillagers.id("order"), Order.CODEC);
        DialogueAction.register(GuardVillagers.id("dismiss"), Dismiss.CODEC);
    }

    record Hire() implements DialogueAction {
        static final MapCodec<Hire> CODEC = MapCodec.unit(new Hire());

        @Override
        public MapCodec<? extends DialogueAction> codec() {
            return CODEC;
        }

        @Override
        public void run(DialogueContext context) {
            GuardDialogueCompat.guardOf(context).ifPresent(guard -> {
                if (GuardHiring.canBeHired(guard) && GuardHiring.payFromInventory(context.player(), guard)) {
                    guard.hireBy(context.player());
                }
            });
        }
    }

    record OpenOwnDialogue() implements DialogueAction {
        static final MapCodec<OpenOwnDialogue> CODEC = MapCodec.unit(new OpenOwnDialogue());

        @Override
        public MapCodec<? extends DialogueAction> codec() {
            return CODEC;
        }

        @Override
        public boolean opensScreen() {
            return true;
        }

        @Override
        public void run(DialogueContext context) {
            GuardDialogueCompat.guardOf(context).ifPresent(guard -> {
                Identifier dialogue = GuardDialogueCompat.ownDialogueOf(guard);
                if (dialogue != null) {
                    DialogueManager.carryOn(context.player(), dialogue, guard);
                }
            });
        }
    }

    record OpenInventory() implements DialogueAction {
        static final MapCodec<OpenInventory> CODEC = MapCodec.unit(new OpenInventory());

        @Override
        public MapCodec<? extends DialogueAction> codec() {
            return CODEC;
        }

        @Override
        public boolean opensScreen() {
            return true;
        }

        @Override
        public void run(DialogueContext context) {
            GuardDialogueCompat.guardOf(context).ifPresent(guard -> {
                if (guard.canPlayerManage(context.player())) {
                    guard.openGui(context.player());
                }
            });
        }
    }

    record Order(GuardOrder order) implements DialogueAction {
        static final MapCodec<Order> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                GuardOrder.CODEC.fieldOf("order").forGetter(Order::order)
        ).apply(instance, Order::new));

        @Override
        public MapCodec<? extends DialogueAction> codec() {
            return CODEC;
        }

        @Override
        public void run(DialogueContext context) {
            GuardDialogueCompat.guardOf(context).ifPresent(guard -> {
                if (GuardDialogueCompat.canCommand(guard, context.player())) {
                    order.give(guard, context.player());
                }
            });
        }
    }

    record Dismiss() implements DialogueAction {
        static final MapCodec<Dismiss> CODEC = MapCodec.unit(new Dismiss());

        @Override
        public MapCodec<? extends DialogueAction> codec() {
            return CODEC;
        }

        @Override
        public void run(DialogueContext context) {
            GuardDialogueCompat.guardOf(context).ifPresent(guard -> guard.dismissBy(context.player()));
        }
    }
}
