package dev.sterner.guardvillagers.common.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.serialization.JsonOps;
import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.common.contract.ContractCost;
import dev.sterner.guardvillagers.common.contract.ContractLootRegistry;
import dev.sterner.guardvillagers.common.contract.ContractProgress;
import dev.sterner.guardvillagers.common.contract.ContractTerms;
import dev.sterner.guardvillagers.common.contract.GuardContractComponents;
import dev.sterner.guardvillagers.common.special.SpecialGuardDefinition;
import dev.sterner.guardvillagers.common.special.SpecialGuardRegistry;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

public final class GuardContractCommand {

    private static final int PERMISSION_LEVEL = 2;
    private static final int RGB_MASK = 0xFFFFFF;
    private static final int HEX_RADIX = 16;
    private static final int PERCENT = 100;
    private static final int FIRST_INDEX = 1;
    private static final String HEX_PREFIX = "0x";
    private static final String TAG_PREFIX = "#";
    private static final String GIVE_TEMPLATE = "/give @s %s[%s=%s]";
    private static final String LOOT_TEMPLATE = "/%s %s %s %s %s";

    private static final String LITERAL_CONTRACT = "contract";
    private static final String LITERAL_GIVE = "give";
    private static final String LITERAL_LOOT = "loot";
    private static final String LITERAL_LIST = "list";
    private static final String LITERAL_SET = "set";
    private static final String LITERAL_RESET = "reset";
    private static final String LITERAL_EXPORT = "export";
    private static final String LITERAL_TERM_DAYS = "term_days";
    private static final String LITERAL_USES = "uses";
    private static final String LITERAL_MAX_ACTIVE = "max_active";
    private static final String LITERAL_COOLDOWN_DAYS = "cooldown_days";
    private static final String LITERAL_RIBBON = "ribbon";
    private static final String LITERAL_COST = "cost";
    private static final String LITERAL_FREE = "free";
    private static final String LITERAL_TAG = "tag";
    private static final String LITERAL_NAME = "name";
    private static final String LITERAL_CLEAR = "clear";
    private static final String LITERAL_DIMENSION = "dimension";
    private static final String LITERAL_ADD = "add";
    private static final String TARGET_SELF = "@s";

    private static final String ARG_TARGETS = "targets";
    private static final String ARG_GUARD = "guard";
    private static final String ARG_FILE = "file";
    private static final String ARG_INDEX = "index";
    private static final String ARG_VALUE = "value";
    private static final String ARG_COUNT = "count";
    private static final String ARG_ITEM = "item";
    private static final String ARG_COLOR = "color";
    private static final String ARG_TEXT = "text";
    private static final String ARG_DIMENSION = "dimension";

    private static final String KEY = "commands.guardvillagers.contract.";
    private static final String MSG_NOT_HOLDING = KEY + "not_holding";
    private static final String MSG_UNKNOWN_GUARD = KEY + "unknown_guard";
    private static final String MSG_UNKNOWN_FILE = KEY + "unknown_file";
    private static final String MSG_BAD_INDEX = KEY + "bad_index";
    private static final String MSG_EMPTY_FILE = KEY + "empty_file";
    private static final String MSG_GIVEN = KEY + "given";
    private static final String MSG_UPDATED = KEY + "updated";
    private static final String MSG_RESET = KEY + "reset";
    private static final String MSG_BAD_COLOR = KEY + "bad_color";
    private static final String MSG_LIST_EMPTY = KEY + "list.empty";
    private static final String MSG_LIST_FILE = KEY + "list.file";
    private static final String MSG_LIST_ENTRY = KEY + "list.entry";
    private static final String MSG_EXPORT_GIVE = KEY + "export.give";
    private static final String MSG_EXPORT_JSON = KEY + "export.json";
    private static final String HINT_COPY = KEY + "hint.copy";
    private static final String HINT_GIVE = KEY + "hint.give";

    private GuardContractCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal(GuardVillagers.MODID)
                .then(CommandManager.literal(LITERAL_CONTRACT)
                        .requires(source -> source.hasPermissionLevel(PERMISSION_LEVEL))
                        .then(giveNode())
                        .then(lootNode())
                        .then(CommandManager.literal(LITERAL_LIST).executes(GuardContractCommand::list))
                        .then(setNode())
                        .then(CommandManager.literal(LITERAL_RESET).executes(GuardContractCommand::reset))
                        .then(CommandManager.literal(LITERAL_EXPORT).executes(GuardContractCommand::export))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> giveNode() {
        return CommandManager.literal(LITERAL_GIVE)
                .then(CommandManager.argument(ARG_TARGETS, EntityArgumentType.players())
                        .then(CommandManager.argument(ARG_GUARD, IdentifierArgumentType.identifier())
                                .suggests(GuardContractCommand::suggestGuards)
                                .executes(context -> give(context, terms -> terms))
                                .then(CommandManager.argument(LITERAL_TERM_DAYS, IntegerArgumentType.integer(1))
                                        .executes(context -> give(context, terms -> terms
                                                .withTermDays(IntegerArgumentType.getInteger(context, LITERAL_TERM_DAYS))))
                                        .then(CommandManager.argument(LITERAL_USES, IntegerArgumentType.integer(0))
                                                .executes(context -> give(context, terms -> terms
                                                        .withTermDays(IntegerArgumentType.getInteger(context, LITERAL_TERM_DAYS))
                                                        .withUses(IntegerArgumentType.getInteger(context, LITERAL_USES))))
                                                .then(CommandManager.argument(LITERAL_MAX_ACTIVE, IntegerArgumentType.integer(1))
                                                        .executes(context -> give(context, terms -> terms
                                                                .withTermDays(IntegerArgumentType.getInteger(context, LITERAL_TERM_DAYS))
                                                                .withUses(IntegerArgumentType.getInteger(context, LITERAL_USES))
                                                                .withMaxActive(IntegerArgumentType.getInteger(context, LITERAL_MAX_ACTIVE)))))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> lootNode() {
        return CommandManager.literal(LITERAL_LOOT)
                .then(CommandManager.argument(ARG_TARGETS, EntityArgumentType.players())
                        .then(CommandManager.argument(ARG_FILE, IdentifierArgumentType.identifier())
                                .suggests(GuardContractCommand::suggestFiles)
                                .executes(context -> loot(context, null))
                                .then(CommandManager.argument(ARG_INDEX, IntegerArgumentType.integer(FIRST_INDEX))
                                        .executes(context -> loot(context, IntegerArgumentType.getInteger(context, ARG_INDEX))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> setNode() {
        return CommandManager.literal(LITERAL_SET)
                .then(intField(LITERAL_TERM_DAYS, 1, ContractTerms::withTermDays))
                .then(intField(LITERAL_USES, 0, ContractTerms::withUses))
                .then(intField(LITERAL_MAX_ACTIVE, 1, ContractTerms::withMaxActive))
                .then(intField(LITERAL_COOLDOWN_DAYS, 0, ContractTerms::withCooldownDays))
                .then(CommandManager.literal(LITERAL_RIBBON)
                        .then(CommandManager.argument(ARG_COLOR, StringArgumentType.word())
                                .suggests(GuardContractCommand::suggestColors)
                                .executes(GuardContractCommand::setRibbon)))
                .then(CommandManager.literal(LITERAL_COST)
                        .then(CommandManager.literal(LITERAL_FREE)
                                .executes(context -> edit(context, LITERAL_COST, terms -> terms.withCost(ContractCost.FREE))))
                        .then(CommandManager.argument(ARG_COUNT, IntegerArgumentType.integer(1))
                                .then(CommandManager.argument(ARG_ITEM, IdentifierArgumentType.identifier())
                                        .suggests((context, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder))
                                        .executes(context -> setCost(context, IdentifierArgumentType.getIdentifier(context, ARG_ITEM).toString())))
                                .then(CommandManager.literal(LITERAL_TAG)
                                        .then(CommandManager.argument(ARG_ITEM, IdentifierArgumentType.identifier())
                                                .suggests((context, builder) -> CommandSource.suggestIdentifiers(
                                                        Registries.ITEM.streamTags().map(tag -> tag.id()), builder))
                                                .executes(context -> setCost(context,
                                                        TAG_PREFIX + IdentifierArgumentType.getIdentifier(context, ARG_ITEM)))))))
                .then(CommandManager.literal(LITERAL_NAME)
                        .then(CommandManager.literal(LITERAL_CLEAR)
                                .executes(context -> edit(context, LITERAL_NAME, terms -> terms.withName(Optional.empty()))))
                        .then(CommandManager.argument(ARG_TEXT, StringArgumentType.greedyString())
                                .executes(context -> edit(context, LITERAL_NAME, terms -> terms
                                        .withName(Optional.of(StringArgumentType.getString(context, ARG_TEXT)))))))
                .then(CommandManager.literal(LITERAL_DIMENSION)
                        .then(CommandManager.literal(LITERAL_CLEAR)
                                .executes(context -> edit(context, LITERAL_DIMENSION, terms -> terms.withDimensions(List.of()))))
                        .then(CommandManager.literal(LITERAL_ADD)
                                .then(CommandManager.argument(ARG_DIMENSION, IdentifierArgumentType.identifier())
                                        .suggests((context, builder) -> CommandSource.suggestIdentifiers(
                                                context.getSource().getServer().getWorldRegistryKeys().stream().map(RegistryKey::getValue), builder))
                                        .executes(GuardContractCommand::addDimension))));
    }

    private interface IntSetter {
        ContractTerms apply(ContractTerms terms, int value);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> intField(String field, int min, IntSetter setter) {
        return CommandManager.literal(field)
                .then(CommandManager.argument(ARG_VALUE, IntegerArgumentType.integer(min))
                        .executes(context -> edit(context, field,
                                terms -> setter.apply(terms, IntegerArgumentType.getInteger(context, ARG_VALUE)))));
    }

    private static int give(CommandContext<ServerCommandSource> context, UnaryOperator<ContractTerms> customize)
            throws CommandSyntaxException {
        Identifier guard = IdentifierArgumentType.getIdentifier(context, ARG_GUARD);
        if (SpecialGuardRegistry.INSTANCE.get(guard).isEmpty()) {
            context.getSource().sendError(Text.translatable(MSG_UNKNOWN_GUARD, guard.toString()));
            return 0;
        }
        return deliver(context, customize.apply(ContractTerms.of(guard)));
    }

    private static int loot(CommandContext<ServerCommandSource> context, Integer index) throws CommandSyntaxException {
        Identifier file = IdentifierArgumentType.getIdentifier(context, ARG_FILE);
        Optional<ContractLootRegistry.Rule> rule = ContractLootRegistry.INSTANCE.get(file);
        if (rule.isEmpty()) {
            context.getSource().sendError(Text.translatable(MSG_UNKNOWN_FILE, file.toString()));
            return 0;
        }
        List<ContractLootRegistry.WeightedContract> contracts = rule.get().contracts();
        if (contracts.isEmpty()) {
            context.getSource().sendError(Text.translatable(MSG_EMPTY_FILE, file.toString()));
            return 0;
        }
        if (index == null) {
            Optional<ContractTerms> picked = ContractLootRegistry.pick(contracts, context.getSource().getWorld().getRandom());
            return picked.isPresent() ? deliver(context, picked.get()) : 0;
        }
        if (index > contracts.size()) {
            context.getSource().sendError(Text.translatable(MSG_BAD_INDEX, file.toString(), index, contracts.size()));
            return 0;
        }
        return deliver(context, contracts.get(index - FIRST_INDEX).terms());
    }

    private static int deliver(CommandContext<ServerCommandSource> context, ContractTerms terms) throws CommandSyntaxException {
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, ARG_TARGETS);
        for (ServerPlayerEntity player : targets) {
            ItemStack stack = new ItemStack(GuardVillagers.CONTRACT);
            stack.set(GuardContractComponents.CONTRACT_TERMS, terms);
            player.getInventory().offerOrDrop(stack);
        }
        context.getSource().sendFeedback(() -> Text.translatable(MSG_GIVEN, targets.size(), terms.displayName()), true);
        return targets.size();
    }

    private static int list(CommandContext<ServerCommandSource> context) {
        List<ContractLootRegistry.Rule> rules = ContractLootRegistry.INSTANCE.all();
        if (rules.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.translatable(MSG_LIST_EMPTY), false);
            return 0;
        }
        for (ContractLootRegistry.Rule rule : rules) {
            context.getSource().sendFeedback(() -> Text.translatable(MSG_LIST_FILE, rule.id().toString(),
                    Math.round(rule.chance() * PERCENT), rule.lootTables().size()).formatted(Formatting.GOLD), false);
            List<ContractLootRegistry.WeightedContract> contracts = rule.contracts();
            for (int i = 0; i < contracts.size(); i++) {
                int index = i + FIRST_INDEX;
                ContractLootRegistry.WeightedContract contract = contracts.get(i);
                String command = String.format(Locale.ROOT, LOOT_TEMPLATE, GuardVillagers.MODID, LITERAL_CONTRACT + " " + LITERAL_LOOT,
                        TARGET_SELF, rule.id(), index);
                MutableText line = Text.translatable(MSG_LIST_ENTRY, index, contract.terms().displayName(), contract.weight())
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable(HINT_GIVE))));
                context.getSource().sendFeedback(() -> line, false);
            }
        }
        return rules.size();
    }

    private static int setRibbon(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(context, ARG_COLOR);
        Optional<Integer> color = parseColor(raw);
        if (color.isEmpty()) {
            context.getSource().sendError(Text.translatable(MSG_BAD_COLOR, raw));
            return 0;
        }
        return edit(context, LITERAL_RIBBON, terms -> terms.withRibbon(color.get()));
    }

    private static int setCost(CommandContext<ServerCommandSource> context, String entry) throws CommandSyntaxException {
        int count = IntegerArgumentType.getInteger(context, ARG_COUNT);
        return edit(context, LITERAL_COST, terms -> terms.withCost(new ContractCost(List.of(entry), count)));
    }

    private static int addDimension(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Identifier dimension = IdentifierArgumentType.getIdentifier(context, ARG_DIMENSION);
        return edit(context, LITERAL_DIMENSION, terms -> {
            List<Identifier> dimensions = new ArrayList<>(terms.dimensions());
            if (!dimensions.contains(dimension)) {
                dimensions.add(dimension);
            }
            return terms.withDimensions(dimensions);
        });
    }

    private static int edit(CommandContext<ServerCommandSource> context, String field, UnaryOperator<ContractTerms> change)
            throws CommandSyntaxException {
        ItemStack stack = heldContract(context);
        if (stack == null) {
            return 0;
        }
        ContractTerms terms = change.apply(stack.get(GuardContractComponents.CONTRACT_TERMS));
        stack.set(GuardContractComponents.CONTRACT_TERMS, terms);
        context.getSource().sendFeedback(() -> Text.translatable(MSG_UPDATED, field), false);
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ItemStack stack = heldContract(context);
        if (stack == null) {
            return 0;
        }
        ContractProgress progress = stack.get(GuardContractComponents.CONTRACT_PROGRESS);
        if (progress != null) {
            stack.set(GuardContractComponents.CONTRACT_PROGRESS, progress.reset());
        }
        context.getSource().sendFeedback(() -> Text.translatable(MSG_RESET), false);
        return 1;
    }

    private static int export(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ItemStack stack = heldContract(context);
        if (stack == null) {
            return 0;
        }
        ContractTerms terms = stack.get(GuardContractComponents.CONTRACT_TERMS);
        String snbt = ContractTerms.CODEC.encodeStart(NbtOps.INSTANCE, terms).getOrThrow().asString();
        String give = String.format(Locale.ROOT, GIVE_TEMPLATE, Registries.ITEM.getId(GuardVillagers.CONTRACT),
                Registries.DATA_COMPONENT_TYPE.getId(GuardContractComponents.CONTRACT_TERMS), snbt);
        JsonElement encoded = ContractTerms.CODEC.encodeStart(JsonOps.INSTANCE, terms).getOrThrow();
        JsonObject entry = new JsonObject();
        entry.add(ContractLootRegistry.KEY_WEIGHT, new JsonPrimitive(ContractLootRegistry.DEFAULT_WEIGHT));
        encoded.getAsJsonObject().entrySet().forEach(field -> entry.add(field.getKey(), field.getValue()));
        context.getSource().sendFeedback(() -> Text.translatable(MSG_EXPORT_GIVE, copyable(give)), false);
        context.getSource().sendFeedback(() -> Text.translatable(MSG_EXPORT_JSON, copyable(entry.toString())), false);
        return 1;
    }

    private static Text copyable(String value) {
        return Text.literal(value).styled(style -> style
                .withColor(Formatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable(HINT_COPY))));
    }

    private static ItemStack heldContract(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ItemStack stack = player.getMainHandStack();
        if (!stack.isOf(GuardVillagers.CONTRACT) || !stack.contains(GuardContractComponents.CONTRACT_TERMS)) {
            context.getSource().sendError(Text.translatable(MSG_NOT_HOLDING));
            return null;
        }
        return stack;
    }

    private static Optional<Integer> parseColor(String raw) {
        DyeColor dye = DyeColor.byName(raw.toLowerCase(Locale.ROOT), null);
        if (dye != null) {
            return Optional.of(dye.getEntityColor() & RGB_MASK);
        }
        try {
            String lower = raw.toLowerCase(Locale.ROOT);
            int value = lower.startsWith(HEX_PREFIX)
                    ? Integer.parseInt(lower.substring(HEX_PREFIX.length()), HEX_RADIX)
                    : Integer.parseInt(lower);
            return Optional.of(value & RGB_MASK);
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private static CompletableFuture<Suggestions> suggestGuards(CommandContext<ServerCommandSource> context,
                                                                SuggestionsBuilder builder) {
        return CommandSource.suggestIdentifiers(
                SpecialGuardRegistry.INSTANCE.all().stream().map(SpecialGuardDefinition::id), builder);
    }

    private static CompletableFuture<Suggestions> suggestFiles(CommandContext<ServerCommandSource> context,
                                                               SuggestionsBuilder builder) {
        return CommandSource.suggestIdentifiers(
                ContractLootRegistry.INSTANCE.all().stream().map(ContractLootRegistry.Rule::id), builder);
    }

    private static CompletableFuture<Suggestions> suggestColors(CommandContext<ServerCommandSource> context,
                                                                SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(Arrays.stream(DyeColor.values()).map(DyeColor::getName), builder);
    }
}
