package dev.sterner.guardvillagers.compat.rpgdialogue;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.entity.GuardOrder;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import net.minecraft.util.Identifier;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueCondition;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueContext;

import java.util.List;

final class GuardDialogueConditions {

    private GuardDialogueConditions() {}

    static void register() {
        DialogueCondition.register(GuardVillagers.id("can_hire"), CanHire.CODEC);
        DialogueCondition.register(GuardVillagers.id("can_afford"), CanAfford.CODEC);
        DialogueCondition.register(GuardVillagers.id("is_owner"), IsOwner.CODEC);
        DialogueCondition.register(GuardVillagers.id("hired_by_other"), HiredByOther.CODEC);
        DialogueCondition.register(GuardVillagers.id("is_hero"), IsHero.CODEC);
        DialogueCondition.register(GuardVillagers.id("can_command"), CanCommand.CODEC);
        DialogueCondition.register(GuardVillagers.id("can_manage"), CanManage.CODEC);
        DialogueCondition.register(GuardVillagers.id("has_own_dialogue"), HasOwnDialogue.CODEC);
        DialogueCondition.register(GuardVillagers.id("has_order"), HasOrder.CODEC);
        DialogueCondition.register(GuardVillagers.id("can_order"), CanOrder.CODEC);
        DialogueCondition.register(GuardVillagers.id("special"), Special.CODEC);
        DialogueCondition.register(GuardVillagers.id("has_greeting"), HasGreeting.CODEC);
    }

    record CanHire() implements DialogueCondition {
        static final MapCodec<CanHire> CODEC = MapCodec.unit(new CanHire());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(GuardHiring::canBeHired).orElse(false);
        }
    }

    record CanAfford() implements DialogueCondition {
        static final MapCodec<CanAfford> CODEC = MapCodec.unit(new CanAfford());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> GuardHiring.canAfford(context.player(), guard)).orElse(false);
        }
    }

    record IsOwner() implements DialogueCondition {
        static final MapCodec<IsOwner> CODEC = MapCodec.unit(new IsOwner());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> guard.isHired() && guard.isOwnedBy(context.player())).orElse(false);
        }
    }

    record HiredByOther() implements DialogueCondition {
        static final MapCodec<HiredByOther> CODEC = MapCodec.unit(new HiredByOther());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> guard.isHired() && !guard.isOwnedBy(context.player())).orElse(false);
        }
    }

    record IsHero() implements DialogueCondition {
        static final MapCodec<IsHero> CODEC = MapCodec.unit(new IsHero());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> GuardDialogueCompat.isHeroFor(guard, context.player())).orElse(false);
        }
    }

    record CanCommand() implements DialogueCondition {
        static final MapCodec<CanCommand> CODEC = MapCodec.unit(new CanCommand());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> GuardDialogueCompat.canCommand(guard, context.player())).orElse(false);
        }
    }

    record CanManage() implements DialogueCondition {
        static final MapCodec<CanManage> CODEC = MapCodec.unit(new CanManage());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> guard.canPlayerManage(context.player())).orElse(false);
        }
    }

    record HasOwnDialogue() implements DialogueCondition {
        static final MapCodec<HasOwnDialogue> CODEC = MapCodec.unit(new HasOwnDialogue());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> GuardDialogueCompat.ownDialogueOf(guard) != null).orElse(false);
        }
    }

    record HasOrder(GuardOrder order) implements DialogueCondition {
        static final MapCodec<HasOrder> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                GuardOrder.CODEC.fieldOf("order").forGetter(HasOrder::order)
        ).apply(instance, HasOrder::new));

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(order::isActive).orElse(false);
        }
    }

    record Special(List<Identifier> types) implements DialogueCondition {
        static final MapCodec<Special> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Identifier.CODEC.listOf().optionalFieldOf("types", List.of()).forGetter(Special::types)
        ).apply(instance, Special::new));

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context)
                    .map(guard -> guard.getSpecialGuardType() != null && (types.isEmpty() || types.contains(guard.getSpecialGuardType())))
                    .orElse(false);
        }
    }

    record HasGreeting() implements DialogueCondition {
        static final MapCodec<HasGreeting> CODEC = MapCodec.unit(new HasGreeting());

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> !GuardDialogueCompat.greetingsOf(guard).isEmpty()).orElse(false);
        }
    }

    record CanOrder(GuardOrder order) implements DialogueCondition {
        static final MapCodec<CanOrder> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                GuardOrder.CODEC.fieldOf("order").forGetter(CanOrder::order)
        ).apply(instance, CanOrder::new));

        @Override
        public MapCodec<? extends DialogueCondition> codec() {
            return CODEC;
        }

        @Override
        public boolean test(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context)
                    .map(guard -> GuardDialogueCompat.canCommand(guard, context.player()) && order.isAvailable(guard))
                    .orElse(false);
        }
    }
}
