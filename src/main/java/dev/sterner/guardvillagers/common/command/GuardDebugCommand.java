package dev.sterner.guardvillagers.common.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
        );
    }

    private static int toggleDebug(CommandContext<ServerCommandSource> context) {
        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
            GuardDebugManager.toggleDebug(player);
            return 1;
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
}