package dev.sterner.guardvillagers.common.entity.goal.spell;

import com.mojang.datafixers.util.Pair;
import dev.sterner.guardvillagers.common.animation.GuardAnimationDurations;
import dev.sterner.guardvillagers.common.debug.GuardDebugManager;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.registry.entry.RegistryEntry;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.fx.PlayerAnimation;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.casting.SpellCast;
import org.jetbrains.annotations.Nullable;

public final class GuardCastVisuals {

    private static final int MIN_RELEASE_TICKS = 5;
    private static final int DEFAULT_RELEASE_TICKS = 20;
    private static final int MELEE_TELEGRAPH_TICKS = 2;

    private GuardCastVisuals() {}

    @Nullable
    public static String resolveAnimationId(GuardEntity guard, @Nullable PlayerAnimation animation) {
        if (animation == null) {
            return null;
        }
        String id = animation.resolve(guard);
        if (id == null || id.isEmpty()) {
            id = animation.id;
        }
        id = resolveInstrumentAnimation(guard, id);
        return (id == null || id.isEmpty()) ? null : id;
    }

    
    @Nullable
    private static String resolveInstrumentAnimation(GuardEntity guard, @Nullable String baseId) {
        if (baseId == null || baseId.isEmpty()) {
            return baseId;
        }
        String suffix = null;
        if (baseId.startsWith("bards_rpg:sing_")) {
            suffix = baseId.substring("bards_rpg:sing_".length());
        } else if (baseId.startsWith("bards_rpg:")) {
            String path = baseId.substring("bards_rpg:".length());
            if (path.startsWith("lute_") || path.startsWith("lyre_") || path.startsWith("harp_")) {
                return baseId;
            }
        }
        if (suffix == null) {
            return baseId;
        }
        String instrument = resolveBardInstrument(guard.getMainHandStack());
        if (instrument == null) {
            return baseId;
        }
        return "bards_rpg:" + instrument + "_" + suffix;
    }

    @Nullable
    static String resolveBardInstrument(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return GuardItemTags.bardInstrumentType(stack);
    }

    @Nullable
    public static String resolveCastAnimationId(GuardEntity guard, Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.animation != null) {
            return resolveAnimationId(guard, spell.active.cast.animation);
        }
        return null;
    }

    @Nullable
    public static String resolveReleaseAnimationId(GuardEntity guard, Spell spell) {
        if (spell.release != null && spell.release.animation != null) {
            return resolveAnimationId(guard, spell.release.animation);
        }
        return null;
    }

    public static boolean hasReleaseAnimation(Spell spell) {
        return spell.release != null && spell.release.animation != null;
    }

    public static boolean isInstantReleaseSpell(Spell spell) {
        if (!hasReleaseAnimation(spell)) {
            return false;
        }
        if (spell.active == null || spell.active.cast == null) {
            return true;
        }
        return spell.active.cast.duration <= 0;
    }

    public static boolean holdsCastThroughDelivery(Spell spell) {
        if (spell.deliver == null) {
            return false;
        }
        if (hasReleaseAnimation(spell) && spell.deliver.delay > 0) {
            return true;
        }
        return holdsCastThroughMeleeStrike(spell);
    }

    
    public static boolean holdsCastThroughMeleeStrike(Spell spell) {
        if (spell.active == null || spell.active.cast == null || spell.active.cast.animation == null) {
            return false;
        }
        if (spell.deliver == null || spell.deliver.type != Spell.Delivery.Type.MELEE
                || spell.deliver.melee == null || spell.deliver.melee.attacks == null) {
            return false;
        }
        return spell.deliver.melee.attacks.stream().anyMatch(a -> a.animation != null);
    }

    public static boolean shouldClearCastAtSpellFire(Spell spell) {
        return !holdsCastThroughDelivery(spell);
    }

    public static void beginBowCast(GuardEntity guard, RegistryEntry<Spell> entry, Spell spell) {
        beginCastInternal(guard, entry, spell, false, false);
    }

    public static void beginCrossbowCast(GuardEntity guard, RegistryEntry<Spell> entry, Spell spell) {
        beginCastInternal(guard, entry, spell, false, false);
    }

    public static void beginCast(GuardEntity guard, RegistryEntry<Spell> entry, Spell spell, boolean meleeArchetype) {
        beginCastInternal(guard, entry, spell, meleeArchetype, true);
    }

    private static void beginCastInternal(
            GuardEntity guard,
            RegistryEntry<Spell> entry,
            Spell spell,
            boolean meleeArchetype,
            boolean stopUsingItem
    ) {
        if (stopUsingItem) {
            guard.stopUsingItem();
        }
        guard.nextAnimationTaskGeneration();
        guard.setReleaseAnimationId(null);
        guard.setSwingAnimationId(null);
        guard.setCastHoldAnimationId(null);
        guard.bumpAnimationSequence();

        SpellCast.Duration details = SpellHelper.getCastTimeDetails(guard, spell);
        Item weapon = guard.getMainHandStack().getItem();
        SpellCast.Process process = new SpellCast.Process(
                guard,
                entry,
                weapon,
                details.speed(),
                details.length(),
                guard.getWorld().getTime()
        );
        guard.setSpellCastProcess(process);
        entry.getKey().ifPresent(key -> guard.setSyncedCastSpellId(key.getValue()));
        guard.setCastingSpell(true);
        guard.setCastingMeleeSpell(meleeArchetype);
        GuardSpellSounds.playCastStart(guard, spell);
        String castAnimId = resolveCastAnimationId(guard, spell);
        guard.setCastAnimationId(castAnimId);
        guard.setCastAnimationSpeed(resolveCastPlaybackSpeed(spell, details, castAnimId));
        logAnimationPhase(guard, "CAST", castAnimId);

        if (SpellHelper.isChanneled(spell)) {
            beginChannel(guard, spell);
        } else if (hasReleaseAnimation(spell) && spell.active != null && spell.active.cast != null) {
            guard.setChannelCastVisuals(
                    0f,
                    guard.getWorld().getTime(),
                    details.length(),
                    0,
                    guard.getCastAnimationSpeed(),
                    spell.active.cast.animation_pitch
            );
        } else if (spell.active != null && spell.active.cast != null
                && spell.active.cast.duration >= 3.0f
                && spell.target != null && spell.target.type == Spell.Target.Type.BEAM) {
            Spell.Active.Cast cast = spell.active.cast;
            float animSpeed = cast.animation != null && cast.animation.speed > 0 ? cast.animation.speed : 1f;
            guard.setChannelCastVisuals(
                    cast.animation_spin,
                    guard.getWorld().getTime(),
                    details.length(),
                    1,
                    details.speed() * animSpeed,
                    cast.animation_pitch
            );
        }

    }

    public static void beginChannel(GuardEntity guard, Spell spell) {
        if (!SpellHelper.isChanneled(spell) || spell.active == null || spell.active.cast == null) {
            return;
        }
        Spell.Active.Cast cast = spell.active.cast;
        SpellCast.Duration details = SpellHelper.getCastTimeDetails(guard, spell);
        float processSpeed = guard.getSpellCastProcess() != null
                ? guard.getSpellCastProcess().speed()
                : details.speed();
        float animSpeed = cast.animation != null && cast.animation.speed > 0 ? cast.animation.speed : 1f;
        guard.setChannelCastVisuals(
                cast.animation_spin,
                guard.getWorld().getTime(),
                details.length(),
                cast.channel_ticks,
                processSpeed * animSpeed,
                cast.animation_pitch
        );
    }

    public static void beginReleasePhase(GuardEntity guard, Spell spell) {
        if (!hasReleaseAnimation(spell)) {
            return;
        }
        String releaseId = resolveReleaseAnimationId(guard, spell);
        if (releaseId == null) {
            return;
        }
        if (releaseId.equals(guard.getReleaseAnimationId())) {
            return;
        }
        PlayerAnimation source = spell.release.animation;
        float speed = releaseAnimationSpeed(guard, spell, source);

        String chargePose = guard.getCastAnimationId();
        if (chargePose == null || chargePose.isEmpty()) {
            chargePose = guard.getCastHoldAnimationId();
        }
        guard.setSwingAnimationId(null);
        guard.setCastAnimationSpeed(speed);
        
        guard.setReleaseAnimationId(releaseId);
        guard.setCastAnimationId(null);
        if (GuardSpellTimings.isMeleeDelivery(spell) && chargePose != null && !chargePose.isEmpty()) {
            guard.setCastHoldAnimationId(chargePose);
        } else {
            guard.setCastHoldAnimationId(null);
        }
        guard.bumpAnimationSequence();
        scheduleReleaseClear(guard, releaseDurationTicks(releaseId, speed));
        logAnimationPhase(guard, "RELEASE", releaseId);
    }

    public static int releaseFollowThroughTicks(GuardEntity guard, Spell spell) {
        if (!hasReleaseAnimation(spell)) {
            return 0;
        }
        String releaseId = resolveReleaseAnimationId(guard, spell);
        if (releaseId == null) {
            return 0;
        }
        PlayerAnimation source = spell.release.animation;
        float speed = releaseAnimationSpeed(guard, spell, source);
        return releaseDurationTicks(releaseId, speed);
    }

    public static void beginCastPoseHold(GuardEntity guard, @Nullable String castAnimId) {
        if (castAnimId == null || castAnimId.isEmpty()) {
            return;
        }
        if (castAnimId.equals(guard.getCastHoldAnimationId())) {
            return;
        }
        guard.nextAnimationTaskGeneration();
        guard.setCastAnimationId(null);
        guard.clearChannelCastVisuals();
        guard.setCastHoldAnimationId(castAnimId);
        guard.bumpAnimationSequence();
        logAnimationPhase(guard, "HOLD", castAnimId);
    }

    public static void endCastPoseHold(GuardEntity guard) {
        if (guard.getCastHoldAnimationId().isEmpty()) {
            return;
        }
        guard.setCastHoldAnimationId(null);
        guard.bumpAnimationSequence();
    }

    public static void completeCastWithRelease(GuardEntity guard, Spell spell) {
        guard.setSpellCastProcess(null);
        guard.setCastingSpell(false);
        if (!guard.getReleaseAnimationId().isEmpty()) {
            return;
        }
        guard.nextAnimationTaskGeneration();
        clearTransientClips(guard);
        guard.bumpAnimationSequence();
    }

    public static void playPerAttackSwing(GuardEntity guard, Spell.Delivery.Melee.Attack attack) {
        if (attack.animation == null) {
            return;
        }
        String animId = resolveAnimationId(guard, attack.animation);
        if (animId == null) {
            logAnimationPhase(guard, "DELIVER (missing anim id)", null);
            return;
        }
        guard.nextAnimationTaskGeneration();
        float speed = effectiveAnimationSpeed(guard, attack.animation);
        int ticks = swingDurationTicks(animId, speed);
        guard.setCastAnimationSpeed(speed);

        
        if (isFullBodyMeleeDeliver(attack)) {
            guard.setSwingAnimationId(null);
            guard.setReleaseAnimationId(animId);
            guard.setCastAnimationId(null);
            guard.setCastHoldAnimationId(null);
            guard.bumpAnimationSequence();
            scheduleReleaseClear(guard, ticks);
            logAnimationPhase(guard, "DELIVER (release layer)", animId);
            return;
        }

        guard.setCastAnimationId(null);
        guard.setCastHoldAnimationId(null);
        guard.setSwingAnimationSpeed(speed);
        guard.setSwingAnimationId(animId);
        guard.bumpAnimationSequence();
        scheduleSwingClear(guard, ticks);
        logAnimationPhase(guard, "DELIVER (swing layer)", animId);
    }

    private static boolean isFullBodyMeleeDeliver(Spell.Delivery.Melee.Attack attack) {
        return attack.forward_momentum > 0
                || attack.movement_slipperiness > 0
                || attack.movement_speed > 0;
    }

    private static void logAnimationPhase(GuardEntity guard, String phase, @Nullable String animId) {
        if (!GuardDebugManager.hasAnimationDebugWatchers(guard)) {
            return;
        }
        String label = animId == null ? "?" : animId.substring(Math.max(0, animId.indexOf(':') + 1));
        GuardDebugManager.broadcastAnimation(guard, "▶ " + phase + " " + label, Formatting.GREEN);
    }

    public static int swingDurationTicks(String animationId, float speed) {
        int begin = GuardAnimationDurations.beginTick(animationId);
        int end = GuardAnimationDurations.endTick(animationId);
        int span = Math.max(1, end - begin);
        return Math.max(MIN_RELEASE_TICKS, Math.round(span / Math.max(speed, 0.1f)));
    }

    public static void clearAll(GuardEntity guard) {
        guard.nextAnimationTaskGeneration();
        guard.setSyncedCastSpellId(null);
        guard.setCastingSpell(false);
        clearTransientClips(guard);
        guard.setCastAnimationId(null);
        guard.clearChannelCastVisuals();
        guard.setSpellCastProcess(null);
        guard.bumpAnimationSequence();
    }

    public static void clearTransientVisualClips(GuardEntity guard) {
        guard.nextAnimationTaskGeneration();
        clearTransientClips(guard);
        guard.bumpAnimationSequence();
    }

    private static void clearTransientClips(GuardEntity guard) {
        guard.setReleaseAnimationId(null);
        guard.setSwingAnimationId(null);
        guard.setCastHoldAnimationId(null);
        guard.setCastAnimationId(null);
    }

    public static void scheduleReleaseClear(GuardEntity guard, int ticks) {
        scheduleCancellable(guard, ticks, () -> {
            guard.setReleaseAnimationId(null);
            guard.setCastAnimationId(null);
            guard.bumpAnimationSequence();
        });
    }

    public static void scheduleSwingClear(GuardEntity guard, int ticks) {
        scheduleCancellable(guard, ticks, () -> {
            guard.setSwingAnimationId(null);
            if (!guard.getCastAnimationId().isEmpty() && guard.getCastChannelTickCount() <= 0) {
                guard.setCastAnimationId(null);
            }
            guard.bumpAnimationSequence();
        });
    }

    private static void scheduleCancellable(GuardEntity guard, int ticks, Runnable action) {
        int generation = guard.animationTaskGeneration;
        scheduleDelayed(guard, ticks, () -> {
            if (guard.animationTaskGeneration == generation) {
                action.run();
            }
        });
    }

    public static void scheduleAfterTicks(GuardEntity guard, int ticks, Runnable action) {
        scheduleDelayed(guard, ticks, action);
    }

    private static void scheduleDelayed(GuardEntity guard, int ticks, Runnable action) {
        guard.delayedTasks.add(new Pair<>(Math.max(1, ticks), action));
    }

    public static void endCastWindUp(GuardEntity guard) {
        if (!guard.getReleaseAnimationId().isEmpty() || !guard.getSwingAnimationId().isEmpty()) {
            return;
        }
        if (!guard.getCastHoldAnimationId().isEmpty()) {
            return;
        }
        boolean changed = !guard.getCastAnimationId().isEmpty();
        guard.setCastAnimationId(null);
        guard.setCastHoldAnimationId(null);
        guard.clearChannelCastVisuals();
        if (changed) {
            guard.bumpAnimationSequence();
        }
    }

    public static float resolveCastPlaybackSpeed(
            Spell spell, SpellCast.Duration details, @Nullable String castAnimId) {
        
        return resolveCastPlaybackSpeed(spell, details.speed());
    }

    public static float resolveCastPlaybackSpeed(Spell spell, float processSpeed) {
        float speed = Math.max(0.1f, processSpeed);
        if (spell.active != null && spell.active.cast != null && spell.active.cast.animation != null) {
            PlayerAnimation source = spell.active.cast.animation;
            if (source.speed > 0) {
                speed *= source.speed;
            }
        }
        return speed;
    }

    public static float effectiveAnimationSpeed(GuardEntity guard, @Nullable PlayerAnimation animation) {
        float castSpeed = guard.getSpellCastProcess() != null
                ? guard.getSpellCastProcess().speed()
                : guard.getCastAnimationSpeed();
        if (castSpeed <= 0) {
            castSpeed = 1f;
        }
        if (animation != null && animation.speed > 0) {
            return castSpeed * animation.speed;
        }
        return castSpeed;
    }

    private static float releaseAnimationSpeed(
            GuardEntity guard, Spell spell, @Nullable PlayerAnimation animation) {
        float speed = guard.getSpellCastProcess() != null
                ? guard.getSpellCastProcess().speed()
                : guard.getCastAnimationSpeed();
        if (speed <= 0) {
            speed = SpellHelper.getCastTimeDetails(guard, spell).speed();
        }
        if (animation != null && animation.speed > 0) {
            speed *= animation.speed;
        }
        return Math.max(0.1f, speed);
    }

    public static int releaseDurationTicks(String animationId, float speed) {
        return Math.max(MIN_RELEASE_TICKS, GuardAnimationDurations.durationTicks(animationId, speed));
    }

    public static int windUpTicks(GuardEntity guard, Spell spell) {
        if (isInstantReleaseSpell(spell)) {
            return 0;
        }
        if (SpellHelper.isChanneled(spell)) {
            return 0;
        }
        if (spell.active != null && spell.active.cast != null && spell.active.cast.duration > 0) {
            return Math.max(1, SpellHelper.getCastTimeDetails(guard, spell).length());
        }
        if (spell.deliver != null && spell.deliver.type == Spell.Delivery.Type.MELEE) {
            return MELEE_TELEGRAPH_TICKS;
        }
        return DEFAULT_RELEASE_TICKS;
    }

    public static int channelDurationTicks(GuardEntity guard, Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0) {
            return Math.max(1, SpellHelper.getCastTimeDetails(guard, spell).length());
        }
        return 0;
    }

    public static int channelFireIntervalTicks(GuardEntity guard, Spell spell) {
        if (spell.active != null && spell.active.cast != null && spell.active.cast.channel_ticks > 0) {
            int totalTicks = Math.max(1, SpellHelper.getCastTimeDetails(guard, spell).length());
            int channelTicks = spell.active.cast.channel_ticks;
            return Math.max(1, totalTicks / channelTicks);
        }
        return DEFAULT_RELEASE_TICKS;
    }
}
