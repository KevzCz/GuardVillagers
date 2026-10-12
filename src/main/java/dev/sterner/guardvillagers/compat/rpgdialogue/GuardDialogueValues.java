package dev.sterner.guardvillagers.compat.rpgdialogue;

import com.mojang.serialization.MapCodec;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.special.GuardHiring;
import dev.sterner.guardvillagers.common.special.GuardTextUtils;
import net.minecraft.text.Text;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueContext;
import net.pixeldreamstudios.rpgdialogue.dialogue.DialogueValue;

import java.util.List;

final class GuardDialogueValues {

    private static final String HIRE_COST_KEY = "guardvillagers.dialogue.hire_cost";

    private GuardDialogueValues() {}

    static void register() {
        DialogueValue.register(GuardVillagers.id("hire_cost"), HireCost.CODEC);
        DialogueValue.register(GuardVillagers.id("greeting"), Greeting.CODEC);
    }

    record HireCost() implements DialogueValue {
        static final MapCodec<HireCost> CODEC = MapCodec.unit(new HireCost());

        @Override
        public MapCodec<? extends DialogueValue> codec() {
            return CODEC;
        }

        @Override
        public Text resolve(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context)
                    .<Text>map(guard -> Text.translatable(HIRE_COST_KEY, GuardHiring.costFor(guard), GuardHiring.describe(GuardHiring.entriesFor(guard))))
                    .orElse(Text.empty());
        }
    }

    record Greeting() implements DialogueValue {
        static final MapCodec<Greeting> CODEC = MapCodec.unit(new Greeting());

        @Override
        public MapCodec<? extends DialogueValue> codec() {
            return CODEC;
        }

        @Override
        public Text resolve(DialogueContext context) {
            return GuardDialogueCompat.guardOf(context).map(guard -> {
                List<String> greetings = GuardDialogueCompat.greetingsOf(guard);
                if (greetings.isEmpty()) {
                    return Text.empty();
                }
                return GuardTextUtils.translatableOrColored(greetings.get(guard.getRandom().nextInt(greetings.size())));
            }).orElse(Text.empty());
        }
    }
}
