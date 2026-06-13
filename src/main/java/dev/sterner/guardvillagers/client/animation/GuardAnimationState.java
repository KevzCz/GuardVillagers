package dev.sterner.guardvillagers.client.animation;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import dev.sterner.guardvillagers.common.animation.GuardAnimationDurations;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

@Environment(EnvType.CLIENT)
public class GuardAnimationState {

    private static final WeakHashMap<GuardEntity, GuardAnimationState> STATES = new WeakHashMap<>();

    public static GuardAnimationState getOrCreate(GuardEntity entity) {
        return STATES.computeIfAbsent(entity, GuardAnimationState::new);
    }

    public final AnimationStack stack = new AnimationStack();
    public final AnimationApplier applier = new AnimationApplier(stack);
    private final ModifierLayer<IAnimation> castLayer = new ModifierLayer<>();
    private final ModifierLayer<IAnimation> swingLayer = new ModifierLayer<>();
    private final ModifierLayer<IAnimation> releaseLayer = new ModifierLayer<>();
    private final SpeedModifier castSpeed = new SpeedModifier();
    private final SpeedModifier swingSpeed = new SpeedModifier();
    private final SpeedModifier releaseSpeed = new SpeedModifier();

    public int lastAge = -1;
    private int lastSequence = -1;
    private String currentCastId = null;
    private String currentHoldId = null;
    private String currentSwingId = null;
    private String currentReleaseId = null;
    private boolean currentCastLooped = false;
    private boolean forceSwingReplay = false;

    private GuardAnimationState(GuardEntity entity) {
        stack.addAnimLayer(300, castLayer);
        castLayer.addModifier(castSpeed, 0);
        stack.addAnimLayer(350, swingLayer);
        swingLayer.addModifier(swingSpeed, 0);
        stack.addAnimLayer(400, releaseLayer);
        releaseLayer.addModifier(releaseSpeed, 0);
    }

    public void updateFor(GuardEntity entity) {
        if (!entity.isAlive()) {
            hardReset();
            return;
        }

        String castId = nullIfEmpty(entity.getCastAnimationId());
        String holdId = nullIfEmpty(entity.getCastHoldAnimationId());
        String swingId = nullIfEmpty(entity.getSwingAnimationId());
        String releaseId = nullIfEmpty(entity.getReleaseAnimationId());

        int sequence = entity.getAnimationSequence();
        if (sequence != lastSequence) {
            lastSequence = sequence;
            forceSwingReplay = true;
        }

        if (castId == null && holdId == null && swingId == null && releaseId == null) {
            if (stack.isActive() || currentCastId != null || currentHoldId != null
                    || currentSwingId != null || currentReleaseId != null) {
                hardReset();
            }
            return;
        }

        boolean overlayActive = swingId != null || releaseId != null;

        if (releaseId != null) {
            syncReleasePlayback(entity, releaseId);
            clearLayerIfFinished(releaseLayer);
            if (holdId == null && castId == null && currentCastId != null) {
                sustainCastUnderOverlay(currentCastId);
            }
        } else {
            clearReleaseLayer();
        }

        if (swingId != null) {
            syncSwingPlayback(entity, swingId);
            clearLayerIfFinished(swingLayer);
        } else {
            clearSwingLayer();
        }

        if (castId != null) {
            if (!overlayActive) {
                syncCastPlayback(entity, castId);
            } else {
                castSpeed.speed = entity.getCastAnimationSpeed();
                sustainCastUnderOverlay(castId);
            }
        } else if (holdId != null) {
            if (!overlayActive) {
                syncCastHold(holdId);
            } else {
                sustainCastUnderOverlay(holdId);
            }
        } else if (!overlayActive && (currentCastId != null || currentHoldId != null)) {
            clearCastLayer();
        }

        stack.tick();
    }

    private void syncCastPlayback(GuardEntity entity, String castId) {
        currentHoldId = null;
        boolean channelLoop = shouldLoopCastClip(entity);
        KeyframeAnimation resolved = resolveAnimation(castId);
        boolean effectiveLoop = isLoopedCast(castId, channelLoop, resolved);
        if (!Objects.equals(castId, currentCastId) || effectiveLoop != currentCastLooped) {
            currentCastId = castId;
            currentCastLooped = effectiveLoop;
            applyCastAnimation(entity, castId, channelLoop);
        } else {
            castSpeed.speed = effectiveLoop
                    ? entity.getCastAnimationSpeed()
                    : castSpeed.speed;
        }
    }

    private void syncCastHold(String holdId) {
        currentCastId = null;
        currentCastLooped = false;
        if (!Objects.equals(holdId, currentHoldId)) {
            currentHoldId = holdId;
            applyApexHold(holdId);
        }
    }

    private void syncSwingPlayback(GuardEntity entity, String swingId) {
        if (!Objects.equals(swingId, currentSwingId) || forceSwingReplay) {
            forceSwingReplay = false;
            currentSwingId = swingId;
            applySwingAnimation(entity, swingId);
        } else {
            swingSpeed.speed = entity.getSwingAnimationSpeed();
        }
    }

    private void syncReleasePlayback(GuardEntity entity, String releaseId) {
        if (!Objects.equals(releaseId, currentReleaseId)) {
            currentReleaseId = releaseId;
            applyReleaseAnimation(entity, releaseId);
        } else {
            releaseSpeed.speed = entity.getCastAnimationSpeed();
        }
    }

    private void clearCastLayer() {
        if (currentCastId == null && currentHoldId == null && castLayer.getAnimation() == null) {
            return;
        }
        currentCastId = null;
        currentHoldId = null;
        currentCastLooped = false;
        castSpeed.speed = 1f;
        castLayer.setAnimation(null);
    }

    private void clearSwingLayer() {
        if (currentSwingId == null && swingLayer.getAnimation() == null) {
            return;
        }
        currentSwingId = null;
        swingSpeed.speed = 1f;
        swingLayer.setAnimation(null);
    }

    private void clearReleaseLayer() {
        if (currentReleaseId == null && releaseLayer.getAnimation() == null) {
            return;
        }
        currentReleaseId = null;
        releaseSpeed.speed = 1f;
        releaseLayer.setAnimation(null);
    }

    private void clearLayerIfFinished(ModifierLayer<IAnimation> layer) {
        if (layer.size() > 0) {
            return;
        }
        IAnimation anim = layer.getAnimation();
        if (anim instanceof KeyframeAnimationPlayer player && !player.isActive()) {
            layer.setAnimation(null);
        }
    }

    public boolean isDrivingPose(GuardEntity entity) {
        return stack.isActive();
    }

    public boolean shouldApplyBodyTransforms() {
        return stack.isActive();
    }

    private static boolean hasTrackedClips(GuardEntity entity) {
        return !entity.getCastAnimationId().isEmpty()
                || !entity.getCastHoldAnimationId().isEmpty()
                || !entity.getSwingAnimationId().isEmpty()
                || !entity.getReleaseAnimationId().isEmpty();
    }

    private void sustainCastUnderOverlay(String castId) {
        IAnimation anim = castLayer.getAnimation();
        if (anim instanceof KeyframeAnimationPlayer player && player.isActive()) {
            if (Objects.equals(castId, currentCastId) || Objects.equals(castId, currentHoldId)) {
                return;
            }
        }
        if (Objects.equals(castId, currentHoldId)) {
            return;
        }
        currentCastId = null;
        currentCastLooped = false;
        currentHoldId = castId;
        applyApexHold(castId);
    }

    private static KeyframeAnimationPlayer newClipPlayer(KeyframeAnimation built) {
        return new KeyframeAnimationPlayer(built, built.beginTick, readLoopFlag(built));
    }

    private static boolean readLoopFlag(KeyframeAnimation animation) {
        return animation.isInfinite || animation.mutableCopy().isLooped;
    }

    private void hardReset() {
        lastSequence = -1;
        forceSwingReplay = false;
        currentCastId = null;
        currentHoldId = null;
        currentSwingId = null;
        currentReleaseId = null;
        currentCastLooped = false;
        castLayer.setAnimation(null);
        swingLayer.setAnimation(null);
        releaseLayer.setAnimation(null);
        castSpeed.speed = 1f;
        swingSpeed.speed = 1f;
        releaseSpeed.speed = 1f;
        for (int i = 0; i < 5; i++) {
            stack.tick();
        }
    }

    @Nullable
    private static String nullIfEmpty(@Nullable String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    @Nullable
    private static KeyframeAnimation resolveAnimation(String animId) {
        Identifier id = Identifier.of(animId);
        Optional<KeyframeAnimation> fromRegistry = PlayerAnimationRegistry.getAnimationOptional(id)
                .map(a -> (KeyframeAnimation) a);
        if (fromRegistry.isPresent()) {
            return fromRegistry.get();
        }
        return GuardAnimationLoader.get(animId);
    }

    private static boolean shouldLoopCastClip(GuardEntity entity) {
        return entity.getCastChannelTickCount() > 0 || entity.isBeaming();
    }

    private static boolean isLoopedCast(String animId, boolean channelLoop, @Nullable KeyframeAnimation animation) {
        if (channelLoop) {
            return true;
        }
        if (animation != null && readLoopFlag(animation)) {
            return true;
        }
        return GuardAnimationDurations.isLooped(animId);
    }

    private void applyApexHold(String animId) {
        KeyframeAnimation animation = resolveAnimation(animId);
        if (animation == null) {
            castLayer.setAnimation(null);
            return;
        }

        int apex = effectiveReturnTick(animation, animId);
        int begin = animation.beginTick;
        int holdTick = Math.max(begin, apex - 1);

        var copy = animation.mutableCopy();
        copy.beginTick = holdTick;
        copy.endTick = Math.max(holdTick + 1, apex);
        copy.stopTick = copy.endTick;
        copy.isLooped = false;
        copy.torso.fullyEnablePart(true);

        KeyframeAnimation built = copy.build();
        castSpeed.speed = 1f;
        castLayer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(0, Ease.LINEAR),
                newClipPlayer(built)
        );
    }

    private static int effectiveReturnTick(KeyframeAnimation animation, String animId) {
        int begin = animation.beginTick;
        int end = animation.endTick > begin ? animation.endTick : animation.stopTick;
        if (animation.returnToTick >= begin && animation.returnToTick <= end) {
            return animation.returnToTick;
        }
        int mapped = GuardAnimationDurations.returnSyncTick(animId);
        if (mapped >= begin && mapped <= end) {
            return mapped;
        }
        return end;
    }

    private void applyCastAnimation(GuardEntity entity, String animId, boolean channelLoop) {
        KeyframeAnimation animation = resolveAnimation(animId);
        if (animation == null) {
            castLayer.setAnimation(null);
            return;
        }

        boolean loop = isLoopedCast(animId, channelLoop, animation);

        var copy = animation.mutableCopy();
        copy.torso.fullyEnablePart(true);
        copy.head.fullyEnablePart(true);
        if (loop) {
            copy.isLooped = true;
            copy.returnTick = animation.returnToTick;
        }

        KeyframeAnimation built = copy.build();
        castSpeed.speed = entity.getCastAnimationSpeed();
        castLayer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(0, Ease.LINEAR),
                newClipPlayer(built)
        );
    }

    private void applySwingAnimation(GuardEntity entity, String animId) {
        KeyframeAnimation animation = resolveAnimation(animId);
        if (animation == null) {
            swingLayer.setAnimation(null);
            return;
        }

        var copy = animation.mutableCopy();
        copy.torso.fullyEnablePart(true);
        KeyframeAnimation built = copy.build();
        swingSpeed.speed = entity.getSwingAnimationSpeed();
        swingLayer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(0, Ease.LINEAR),
                newClipPlayer(built)
        );
    }

    private void applyReleaseAnimation(GuardEntity entity, String animId) {
        KeyframeAnimation animation = resolveAnimation(animId);
        if (animation == null) {
            releaseLayer.setAnimation(null);
            return;
        }

        var copy = animation.mutableCopy();
        copy.torso.fullyEnablePart(true);
        KeyframeAnimation built = copy.build();
        releaseSpeed.speed = entity.getCastAnimationSpeed();
        releaseLayer.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(0, Ease.LINEAR),
                newClipPlayer(built)
        );
    }

    public static void resetModelDefaults(
            ModelPart head, ModelPart body,
            ModelPart rightArm, ModelPart leftArm,
            ModelPart rightLeg, ModelPart leftLeg,
            @Nullable ModelPart hat) {
        head.resetTransform();
        body.resetTransform();
        rightArm.resetTransform();
        leftArm.resetTransform();
        rightLeg.resetTransform();
        leftLeg.resetTransform();
        if (hat != null) {
            hat.resetTransform();
        }
    }

    public void clearEmoteSupplier(BipedEntityModel<?> model) {
        if (model instanceof IMutableModel mutable) {
            var supplier = mutable.getEmoteSupplier();
            if (supplier != null) {
                supplier.set(null);
            }
        }
    }

    public void applyToModel(BipedEntityModel<?> model, float partialTick, GuardEntity entity) {
        if (!stack.isActive() && hasTrackedClips(entity)) {
            stack.tick();
        }
        if (!stack.isActive()) {
            clearEmoteSupplier(model);
            return;
        }

        applier.setTickDelta(partialTick);
        applier.updatePart("head", model.head);
        if (model.hat != null) {
            model.hat.copyTransform(model.head);
        }
        applier.updatePart("leftArm", model.leftArm);
        applier.updatePart("rightArm", model.rightArm);
        applier.updatePart("leftLeg", model.leftLeg);
        applier.updatePart("rightLeg", model.rightLeg);
        applier.updatePart("torso", model.body);

        if (model instanceof IMutableModel mutable) {
            mutable.getEmoteSupplier().set(applier);
        }
    }

    public static void applyBodyTransforms(MatrixStack matrices, AnimationApplier applier) {
        if (!applier.isActive()) {
            return;
        }

        Vec3f scale = applier.get3DTransform("body", TransformType.SCALE, new Vec3f(1f, 1f, 1f));
        matrices.scale(scale.getX(), scale.getY(), scale.getZ());

        Vec3f pos = applier.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
        matrices.translate(pos.getX(), pos.getY() + 0.7f, pos.getZ());

        Vec3f rot = applier.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotation(rot.getZ()));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(rot.getY()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotation(rot.getX()));

        matrices.translate(0f, -0.7f, 0f);
    }
}
