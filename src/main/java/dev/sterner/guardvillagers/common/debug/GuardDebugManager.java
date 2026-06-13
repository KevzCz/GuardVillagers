package dev.sterner.guardvillagers.common.debug;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.goal.GuardEntityMeleeGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.BaseSpellGoal;
import dev.sterner.guardvillagers.common.entity.goal.spell.GuardMeleeSpellCastGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.spell_engine.internals.casting.SpellCast;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.WeakHashMap;

public class GuardDebugManager {
    private static final Set<UUID> debugEnabledPlayers = new HashSet<>();
    private static final Map<Integer, UUID> watchedGuards = new HashMap<>();
    
    private static final Map<UUID, UUID> animationDebugGuards = new HashMap<>();
    private static final WeakHashMap<GuardEntity, String> lastGoalSnapshot = new WeakHashMap<>();
    private static final WeakHashMap<GuardEntity, AnimSnapshot> lastAnimSnapshot = new WeakHashMap<>();

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

    public static void toggleAnimationDebug(ServerPlayerEntity player, GuardEntity guard) {
        UUID playerId = player.getUuid();
        UUID current = animationDebugGuards.get(playerId);
        if (guard.getUuid().equals(current)) {
            animationDebugGuards.remove(playerId);
            lastAnimSnapshot.remove(guard);
            player.sendMessage(Text.literal("Animation debug off for Guard #" + guard.getId()).formatted(Formatting.RED), false);
            return;
        }
        animationDebugGuards.put(playerId, guard.getUuid());
        lastAnimSnapshot.remove(guard);
        player.sendMessage(Text.literal("Animation debug on for Guard #" + guard.getId()
                + " (events only; run again to disable)").formatted(Formatting.GREEN), false);
        broadcastAnimation(guard, "watching — " + AnimSnapshot.capture(guard).summary(guard), Formatting.GRAY);
    }

    public static boolean isAnimationDebugging(ServerPlayerEntity player, GuardEntity guard) {
        UUID watched = animationDebugGuards.get(player.getUuid());
        return watched != null && watched.equals(guard.getUuid());
    }

    public static boolean hasAnimationDebugWatchers(GuardEntity guard) {
        if (animationDebugGuards.isEmpty()) {
            return false;
        }
        UUID guardId = guard.getUuid();
        return animationDebugGuards.containsValue(guardId);
    }

    public static void setWatchedGuard(ServerPlayerEntity player, GuardEntity guard) {
        if (!debugEnabledPlayers.contains(player.getUuid())) {
            player.sendMessage(Text.literal("Enable debug mode first with /guardvillagers debug").formatted(Formatting.RED), false);
            return;
        }
        watchedGuards.put(player.getUuid().hashCode(), guard.getUuid());
        player.sendMessage(Text.literal("Now watching Guard #" + guard.getId()).formatted(Formatting.YELLOW), false);
    }

    public static GuardEntity getWatchedGuard(ServerPlayerEntity player) {
        UUID watchedId = watchedGuards.get(player.getUuid().hashCode());
        if (watchedId == null) return null;
        var entity = player.getServerWorld().getEntity(watchedId);
        return entity instanceof GuardEntity g ? g : null;
    }

    public static boolean isDebugging(ServerPlayerEntity player) {
        return debugEnabledPlayers.contains(player.getUuid());
    }

    public static boolean isWatching(ServerPlayerEntity player, GuardEntity guard) {
        UUID watched = watchedGuards.get(player.getUuid().hashCode());
        return watched != null && watched.equals(guard.getUuid());
    }

    
    public static boolean hasWatchers(GuardEntity guard) {
        if (debugEnabledPlayers.isEmpty()) {
            return false;
        }
        UUID guardId = guard.getUuid();
        for (UUID playerId : debugEnabledPlayers) {
            UUID watched = watchedGuards.get(playerId.hashCode());
            if (watched == null || watched.equals(guardId)) {
                return true;
            }
        }
        return false;
    }

    
    public static void tickAnimationDebug(GuardEntity guard) {
        if (!hasAnimationDebugWatchers(guard)) {
            return;
        }

        AnimSnapshot current = AnimSnapshot.capture(guard);
        AnimSnapshot previous = lastAnimSnapshot.get(guard);
        if (previous == null) {
            lastAnimSnapshot.put(guard, current);
            return;
        }
        if (current.equals(previous)) {
            return;
        }

        for (String event : current.describeTransitions(previous, guard)) {
            broadcastAnimation(guard, event, eventColor(event));
        }
        lastAnimSnapshot.put(guard, current);
    }

    public static void broadcastAnimation(GuardEntity guard, String message, Formatting color) {
        if (guard.getWorld().isClient()) {
            return;
        }
        UUID guardId = guard.getUuid();
        for (Map.Entry<UUID, UUID> entry : animationDebugGuards.entrySet()) {
            if (!guardId.equals(entry.getValue())) {
                continue;
            }
            var player = guard.getWorld().getServer().getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            player.sendMessage(
                    Text.literal("[Anim #" + guard.getId() + "] ").formatted(Formatting.DARK_GRAY)
                            .append(Text.literal(message).formatted(color)),
                    false
            );
        }
    }

    private static Formatting eventColor(String event) {
        if (event.startsWith("⚔") || event.startsWith("▶ melee goal")) {
            return Formatting.YELLOW;
        }
        if (event.startsWith("■")) {
            return Formatting.DARK_GRAY;
        }
        if (event.startsWith("▶ CAST") || event.startsWith("▶ spell")) {
            return Formatting.AQUA;
        }
        if (event.startsWith("▶ HOLD") || event.startsWith("▶ SWING")) {
            return Formatting.GREEN;
        }
        if (event.startsWith("▶ RELEASE")) {
            return Formatting.GOLD;
        }
        if (event.startsWith("■ spell") || event.startsWith("■ clips")) {
            return Formatting.GRAY;
        }
        return Formatting.LIGHT_PURPLE;
    }

    @Nullable
    private static String shortAnimId(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static boolean isGoalRunning(GuardEntity guard, Class<? extends Goal> goalClass) {
        for (PrioritizedGoal prioritized : guard.goalSelector.getGoals()) {
            if (prioritized.isRunning() && goalClass.isInstance(prioritized.getGoal())) {
                return true;
            }
        }
        return false;
    }

    private static final class AnimSnapshot {
        private final String cast;
        private final String hold;
        private final String swing;
        private final String release;
        private final boolean casting;
        private final boolean meleeCast;
        private final int sequence;
        private final String proc;
        private final boolean meleeGoal;
        private final boolean spellMeleeGoal;
        private final boolean handSwinging;

        private AnimSnapshot(
                String cast, String hold, String swing, String release,
                boolean casting, boolean meleeCast, int sequence, String proc,
                boolean meleeGoal, boolean spellMeleeGoal, boolean handSwinging
        ) {
            this.cast = cast;
            this.hold = hold;
            this.swing = swing;
            this.release = release;
            this.casting = casting;
            this.meleeCast = meleeCast;
            this.sequence = sequence;
            this.proc = proc;
            this.meleeGoal = meleeGoal;
            this.spellMeleeGoal = spellMeleeGoal;
            this.handSwinging = handSwinging;
        }

        static AnimSnapshot capture(GuardEntity guard) {
            SpellCast.Process process = guard.getSpellCastProcess();
            String procLabel = "-";
            if (process != null) {
                int soFar = process.spellCastTicksSoFar(guard.getWorld().getTime());
                procLabel = soFar + "/" + process.length();
            }
            return new AnimSnapshot(
                    shortAnimId(guard.getCastAnimationId()),
                    shortAnimId(guard.getCastHoldAnimationId()),
                    shortAnimId(guard.getSwingAnimationId()),
                    shortAnimId(guard.getReleaseAnimationId()),
                    guard.isCastingSpell(),
                    guard.isCastingMeleeSpell(),
                    guard.getAnimationSequence(),
                    procLabel,
                    isGoalRunning(guard, GuardEntityMeleeGoal.class),
                    isGoalRunning(guard, GuardMeleeSpellCastGoal.class),
                    guard.handSwinging
            );
        }

        String summary(GuardEntity guard) {
            return String.format(
                    "t=%d age=%d cast=%s hold=%s swing=%s rel=%s casting=%s seq=%d proc=%s meleeGoal=%s spellGoal=%s",
                    guard.getWorld().getTime(), guard.age,
                    label(cast), label(hold), label(swing), label(release),
                    casting, sequence, proc,
                    meleeGoal, spellMeleeGoal
            );
        }

        private static String label(@Nullable String value) {
            return value == null ? "-" : value;
        }

        List<String> describeTransitions(AnimSnapshot prev, GuardEntity guard) {
            List<String> events = new ArrayList<>();
            long tick = guard.getWorld().getTime();
            int age = guard.age;

            if (!prev.spellMeleeGoal && spellMeleeGoal) {
                events.add("▶ spell melee goal active (t=" + tick + " age=" + age + ")");
            } else if (prev.spellMeleeGoal && !spellMeleeGoal) {
                events.add("■ spell melee goal stopped (t=" + tick + " age=" + age + ")");
            }

            if (!prev.meleeGoal && meleeGoal) {
                events.add("▶ melee goal active (t=" + tick + " age=" + age + ")");
            } else if (prev.meleeGoal && !meleeGoal) {
                events.add("■ melee goal stopped (t=" + tick + " age=" + age + ")");
            }

            if (!prev.casting && casting) {
                events.add("▶ spell casting began (proc=" + proc + " seq=" + sequence + ")");
            } else if (prev.casting && !casting) {
                events.add("■ spell casting ended (seq=" + sequence + ")");
            }

            if (prev.cast == null && cast != null) {
                events.add("▶ CAST " + cast + " (proc=" + proc + " seq=" + sequence + ")");
            } else if (prev.cast != null && cast == null) {
                events.add("■ CAST ended (was " + prev.cast + ")");
            } else if (!Objects.equals(prev.cast, cast) && cast != null) {
                events.add("▶ CAST " + cast + " (was " + prev.cast + ")");
            }

            if (prev.hold == null && hold != null) {
                events.add("▶ HOLD " + hold + " (seq=" + sequence + ")");
            } else if (prev.hold != null && hold == null) {
                events.add("■ HOLD ended (was " + prev.hold + ")");
            }

            if (prev.swing == null && swing != null) {
                events.add("▶ SWING " + swing + " (seq=" + sequence + ")");
            } else if (prev.swing != null && swing == null) {
                events.add("■ SWING ended (was " + prev.swing + ")");
            } else if (!Objects.equals(prev.swing, swing) && swing != null) {
                events.add("▶ SWING " + swing + " (was " + prev.swing + ")");
            }

            if (prev.release == null && release != null) {
                events.add("▶ RELEASE " + release + " (seq=" + sequence + ")");
            } else if (prev.release != null && release == null) {
                events.add("■ RELEASE ended (was " + prev.release + ")");
            }

            boolean hadClip = prev.cast != null || prev.hold != null || prev.swing != null || prev.release != null;
            boolean hasClip = cast != null || hold != null || swing != null || release != null;
            if (hadClip && !hasClip && !casting) {
                events.add("■ clips cleared → idle (seq=" + sequence + ")");
            }

            if (!prev.handSwinging && handSwinging && !casting && cast == null && hold == null && swing == null) {
                events.add("⚔ vanilla melee weapon swing (t=" + tick + " age=" + age + ")");
            }

            if (events.isEmpty() && sequence != prev.sequence) {
                events.add("seq " + prev.sequence + "→" + sequence
                        + " proc=" + proc
                        + " cast=" + label(cast)
                        + " hold=" + label(hold)
                        + " swing=" + label(swing)
                        + " rel=" + label(release));
            }

            return events;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AnimSnapshot other)) {
                return false;
            }
            return casting == other.casting
                    && meleeCast == other.meleeCast
                    && sequence == other.sequence
                    && meleeGoal == other.meleeGoal
                    && spellMeleeGoal == other.spellMeleeGoal
                    && handSwinging == other.handSwinging
                    && Objects.equals(cast, other.cast)
                    && Objects.equals(hold, other.hold)
                    && Objects.equals(swing, other.swing)
                    && Objects.equals(release, other.release)
                    && Objects.equals(proc, other.proc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cast, hold, swing, release, casting, meleeCast, sequence, proc,
                    meleeGoal, spellMeleeGoal, handSwinging);
        }
    }

    
    public static void tickGoalDebug(GuardEntity guard) {
        if (!hasWatchers(guard)) {
            return;
        }

        String snapshot = describeRunningGoals(guard);
        String previous = lastGoalSnapshot.get(guard);
        boolean changed = !Objects.equals(snapshot, previous);
        if (!changed && guard.age % 40 != 0) {
            return;
        }

        lastGoalSnapshot.put(guard, snapshot);
        broadcast(guard, "🎯 Goals: " + snapshot, Formatting.AQUA);
    }

    private static String describeRunningGoals(GuardEntity guard) {
        StringBuilder sb = new StringBuilder();
        for (PrioritizedGoal prioritized : guard.goalSelector.getGoals()) {
            if (!prioritized.isRunning()) {
                continue;
            }
            Goal goal = prioritized.getGoal();
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(goal.getClass().getSimpleName());
            sb.append('@').append(prioritized.getPriority());
            if (goal instanceof BaseSpellGoal spellGoal) {
                String spell = spellGoal.getActiveSpellLabel();
                if (spell != null) {
                    sb.append('[').append(spell).append(']');
                }
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
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

    

    public static void clearAllCooldowns(GuardEntity guard) {
        forEachSpellGoal(guard, BaseSpellGoal::clearAllCooldowns);
    }

    public static void clearCooldown(GuardEntity guard, Identifier spellId) {
        forEachSpellGoal(guard, g -> g.clearCooldown(spellId));
    }

    public static void setCooldown(GuardEntity guard, Identifier spellId, int ticks) {
        forEachSpellGoal(guard, g -> g.setCooldown(spellId, ticks));
    }

    public static Map<Identifier, Integer> getMergedCooldowns(GuardEntity guard) {
        return guard.getSpellCooldowns();
    }

    private static void forEachSpellGoal(GuardEntity guard, java.util.function.Consumer<BaseSpellGoal> action) {
        for (PrioritizedGoal pg : guard.goalSelector.getGoals()) {
            if (pg.getGoal() instanceof BaseSpellGoal bsg) {
                action.accept(bsg);
            }
        }
    }
}
