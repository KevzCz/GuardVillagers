package dev.sterner.guardvillagers.common.debug;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class GuardDebugManager {
    private static final Set<UUID> debugEnabledPlayers = new HashSet<>();
    private static final Map<Integer, UUID> watchedGuards = new HashMap<>();

    public static void toggleDebug(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        if (debugEnabledPlayers.contains(playerId)) {
            debugEnabledPlayers.remove(playerId);
            watchedGuards.remove(playerId.hashCode());
            player.sendMessage(Text.literal("Guard debug mode disabled").formatted(Formatting.RED), false);
        } else {
            debugEnabledPlayers.add(playerId);
            player.sendMessage(Text.literal("Guard debug mode enabled").formatted(Formatting.GREEN), false);
        }
    }

    public static void setWatchedGuard(ServerPlayerEntity player, GuardEntity guard) {
        if (!debugEnabledPlayers.contains(player.getUuid())) {
            player.sendMessage(Text.literal("Enable debug mode first with /guardvillagers debug").formatted(Formatting.RED), false);
            return;
        }
        watchedGuards.put(player.getUuid().hashCode(), guard.getUuid());
        player.sendMessage(Text.literal("Now watching Guard #" + guard.getId()).formatted(Formatting.YELLOW), false);
    }

    public static boolean isDebugging(ServerPlayerEntity player) {
        return debugEnabledPlayers.contains(player.getUuid());
    }

    public static boolean isWatching(ServerPlayerEntity player, GuardEntity guard) {
        UUID watched = watchedGuards.get(player.getUuid().hashCode());
        return watched != null && watched.equals(guard.getUuid());
    }

    public static void broadcast(GuardEntity guard, String message, Formatting color) {
        if (guard.getWorld().isClient()) return;

        for (UUID playerId : debugEnabledPlayers) {
            var player = guard.getWorld().getServer().getPlayerManager().getPlayer(playerId);
            if (player != null) {
                UUID watched = watchedGuards.get(playerId.hashCode());
                if (watched == null || watched.equals(guard.getUuid())) {
                    player.sendMessage(
                            Text.literal("[Guard #" + guard.getId() + "] ").formatted(Formatting.GRAY)
                                    .append(Text.literal(message).formatted(color)),
                            false
                    );
                }
            }
        }
    }
}