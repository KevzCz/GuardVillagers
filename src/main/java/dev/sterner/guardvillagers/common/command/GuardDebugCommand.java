package dev.sterner.guardvillagers.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.special.SpecialGuardDefinition;
import dev.sterner.guardvillagers.common.special.SpecialGuardRegistry;
import dev.sterner.guardvillagers.common.special.SpecialGuardSpawner;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GuardDebugCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(
                CommandManager.literal("guardvillagers")
                        .then(CommandManager.literal("debug")
                                .executes(GuardDebugCommand::toggleDebug)
                        )
                        .then(CommandManager.literal("watch")
                                .then(CommandManager.argument("guard", EntityArgumentType.entity())
                                        .executes(GuardDebugCommand::watchGuard)
                                )
                        )
                        .then(CommandManager.literal("animation_debug")
                                .then(CommandManager.argument("guard", EntityArgumentType.entity())
                                        .executes(GuardDebugCommand::toggleAnimationDebug)
                                )
                        )
                        .then(CommandManager.literal("cooldown")
                                .then(CommandManager.literal("list")
                                        .executes(GuardDebugCommand::cooldownList)
                                )
                                .then(CommandManager.literal("clear")
                                        .executes(GuardDebugCommand::cooldownClear)
                                )
                                .then(CommandManager.literal("reset")
                                        .then(CommandManager.argument("spell", StringArgumentType.string())
                                                .executes(GuardDebugCommand::cooldownReset)
                                        )
                                )
                                .then(CommandManager.literal("set")
                                        .then(CommandManager.argument("spell", StringArgumentType.string())
                                                .then(CommandManager.argument("ticks", IntegerArgumentType.integer(0))
                                                        .executes(GuardDebugCommand::cooldownSet)
                                                )
                                        )
                                )
                        )
                        .then(CommandManager.literal("special")
                                .requires(source -> source.hasPermissionLevel(2))
                                .then(CommandManager.literal("list")
                                        .executes(GuardDebugCommand::listSpecialGuards))
                                .then(CommandManager.literal("summon")
                                        .then(CommandManager.argument("type", IdentifierArgumentType.identifier())
                                                .suggests(GuardDebugCommand::suggestSpecialGuardTypes)
                                                .executes(GuardDebugCommand::summonSpecialGuard))))
        );
    }

    private static int toggleDebug(CommandContext<ServerCommandSource> context) {
        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
            GuardDebugManager.toggleDebug(player);
            return 1;
        }
        return 0;
    }

    private static int toggleAnimationDebug(CommandContext<ServerCommandSource> context) {
        try {
            var entity = EntityArgumentType.getEntity(context, "guard");
            if (entity instanceof GuardEntity guard) {
                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                    GuardDebugManager.toggleAnimationDebug(player, guard);
                    return 1;
                }
            } else {
                context.getSource().sendError(Text.literal("Target is not a Guard"));
            }
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Failed to get guard: " + e.getMessage()));
        }
        return 0;
    }

    private static int watchGuard(CommandContext<ServerCommandSource> context) {
        try {
            var entity = EntityArgumentType.getEntity(context, "guard");
            if (entity instanceof GuardEntity guard) {
                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                    GuardDebugManager.setWatchedGuard(player, guard);
                    return 1;
                }
            } else {
                context.getSource().sendError(Text.literal("Target is not a Guard"));
            }
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Failed to get guard: " + e.getMessage()));
        }
        return 0;
    }

    

    private static GuardEntity requireWatchedGuard(CommandContext<ServerCommandSource> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayerEntity player)) return null;
        GuardEntity guard = GuardDebugManager.getWatchedGuard(player);
        if (guard == null) {
            context.getSource().sendError(Text.literal("No guard being watched. Use /guardvillagers watch <guard> first.").formatted(Formatting.RED));
        }
        return guard;
    }

    private static int cooldownList(CommandContext<ServerCommandSource> context) {
        GuardEntity guard = requireWatchedGuard(context);
        if (guard == null) return 0;

        Map<Identifier, Integer> cooldowns = GuardDebugManager.getMergedCooldowns(guard);
        long worldTick = guard.getWorld().getTime();
        if (cooldowns.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[Guard #" + guard.getId() + "] No active cooldowns (world tick " + worldTick + ")").formatted(Formatting.GREEN), false);
        } else {
            context.getSource().sendFeedback(() -> Text.literal("[Guard #" + guard.getId() + "] Cooldowns (world tick " + worldTick + "):").formatted(Formatting.AQUA), false);
            cooldowns.forEach((id, ticks) -> {
                if (ticks > 0) {
                    long readyAt = worldTick + ticks;
                    String seconds = String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0);
                    context.getSource().sendFeedback(() ->
                            Text.literal("  " + id + " → " + ticks + " ticks left, ready at tick " + readyAt + " (~" + seconds + "s)").formatted(Formatting.WHITE), false);
                }
            });
        }
        return 1;
    }

    private static int cooldownClear(CommandContext<ServerCommandSource> context) {
        GuardEntity guard = requireWatchedGuard(context);
        if (guard == null) return 0;

        GuardDebugManager.clearAllCooldowns(guard);
        context.getSource().sendFeedback(() ->
                Text.literal("[Guard #" + guard.getId() + "] All spell cooldowns cleared").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int cooldownReset(CommandContext<ServerCommandSource> context) {
        GuardEntity guard = requireWatchedGuard(context);
        if (guard == null) return 0;

        String spellStr = StringArgumentType.getString(context, "spell");
        Identifier spellId = Identifier.tryParse(spellStr);
        if (spellId == null) {
            context.getSource().sendError(Text.literal("Invalid spell identifier: " + spellStr));
            return 0;
        }

        GuardDebugManager.clearCooldown(guard, spellId);
        context.getSource().sendFeedback(() ->
                Text.literal("[Guard #" + guard.getId() + "] Cooldown cleared for " + spellId).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int cooldownSet(CommandContext<ServerCommandSource> context) {
        GuardEntity guard = requireWatchedGuard(context);
        if (guard == null) return 0;

        String spellStr = StringArgumentType.getString(context, "spell");
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        Identifier spellId = Identifier.tryParse(spellStr);
        if (spellId == null) {
            context.getSource().sendError(Text.literal("Invalid spell identifier: " + spellStr));
            return 0;
        }

        GuardDebugManager.setCooldown(guard, spellId, ticks);
        context.getSource().sendFeedback(() ->
                Text.literal("[Guard #" + guard.getId() + "] Cooldown for " + spellId + " set to " + ticks + " ticks").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestSpecialGuardTypes(
            CommandContext<ServerCommandSource> context,
            SuggestionsBuilder builder
    ) {
        return CommandSource.suggestIdentifiers(
                SpecialGuardRegistry.INSTANCE.all().stream().map(SpecialGuardDefinition::id).toList(),
                builder
        );
    }

    private static int listSpecialGuards(CommandContext<ServerCommandSource> context) {
        var definitions = SpecialGuardRegistry.INSTANCE.all();
        if (definitions.isEmpty()) {
            context.getSource().sendFeedback(
                    () -> Text.literal("No special guard definitions loaded.").formatted(Formatting.YELLOW),
                    false
            );
            return 0;
        }

        context.getSource().sendFeedback(
                () -> Text.literal("Loaded special guards (" + definitions.size() + "):").formatted(Formatting.AQUA),
                false
        );
        for (SpecialGuardDefinition definition : definitions) {
            String name = definition.displayName() != null ? definition.displayName() : definition.id().toString();
            context.getSource().sendFeedback(
                    () -> Text.literal("  " + definition.id() + " — " + name + " (weight " + definition.spawnWeight() + ")")
                            .formatted(Formatting.GRAY),
                    false
            );
        }
        return definitions.size();
    }

    private static int summonSpecialGuard(CommandContext<ServerCommandSource> context) {
        Identifier typeId = IdentifierArgumentType.getIdentifier(context, "type");
        SpecialGuardDefinition definition = SpecialGuardRegistry.INSTANCE.get(typeId).orElse(null);
        if (definition == null) {
            context.getSource().sendError(Text.literal("Unknown special guard type: " + typeId));
            return 0;
        }

        ServerWorld world = context.getSource().getWorld();
        Vec3d pos = context.getSource().getPosition();
        float yaw = context.getSource().getRotation().y;

        GuardEntity guard = SpecialGuardSpawner.spawn(world, definition, BlockPos.ofFloored(pos), yaw, SpawnReason.COMMAND);
        if (guard == null) {
            context.getSource().sendError(Text.literal("Failed to spawn special guard: " + typeId));
            return 0;
        }

        context.getSource().sendFeedback(
                () -> Text.literal("Spawned special guard ")
                        .formatted(Formatting.GREEN)
                        .append(Text.literal(typeId.toString()).formatted(Formatting.AQUA))
                        .append(Text.literal(" (entity #" + guard.getId() + ")").formatted(Formatting.GRAY)),
                true
        );
        return 1;
    }
}