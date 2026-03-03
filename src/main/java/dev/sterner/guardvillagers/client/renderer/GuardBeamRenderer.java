package dev.sterner.guardvillagers.client.renderer;

import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.client.beam.BeamEmitterEntity;
import net.spell_engine.client.compatibility.ShaderCompatibility;
import net.spell_engine.client.render.BeamRenderer;
import net.spell_engine.client.util.Color;
import net.spell_engine.internals.SpellHelper;
import net.spell_engine.internals.delivery.Beam;
import net.spell_engine.utils.TargetHelper;

import java.util.List;

public class GuardBeamRenderer {

    public static void renderGuardBeams(WorldRenderContext context, MatrixStack matrices,
                                        VertexConsumerProvider.Immediate vertexConsumers,
                                        Camera camera, int light, float delta) {
        Entity focusedEntity = camera.getFocusedEntity();
        if (focusedEntity == null) return;

        int renderDistance = MinecraftClient.getInstance().options.getViewDistance().getValue() * 24;
        int squaredRenderDistance = renderDistance * renderDistance;

        List<GuardEntity> allGuards = context.world().getEntitiesByClass(
                GuardEntity.class,
                focusedEntity.getBoundingBox().expand(renderDistance),
                guard -> guard.squaredDistanceTo(focusedEntity) < squaredRenderDistance
        );


        List<GuardEntity> guards = allGuards.stream()
                .filter(guard -> guard.getBeam() != null)
                .toList();


        if (guards.isEmpty()) return;

        matrices.push();
        Vec3d camPos = camera.getPos();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        for (GuardEntity guard : guards) {
            renderSingleGuardBeam(matrices, vertexConsumers, guard, delta);
        }

        vertexConsumers.draw();
        matrices.pop();
    }

    private static void renderSingleGuardBeam(MatrixStack matrices,
                                              VertexConsumerProvider.Immediate vertexConsumers,
                                              GuardEntity guard,
                                              float delta) {
        float launchHeight = SpellHelper.launchHeight(guard);
        Vec3d offset = new Vec3d(0.0, launchHeight, SpellHelper.launchPointOffsetDefault);

        matrices.push();
        Vec3d pos = new Vec3d(guard.prevX, guard.prevY, guard.prevZ).lerp(guard.getPos(), delta);
        matrices.translate(pos.x, pos.y, pos.z);

        Vec3d from = guard.getPos().add(0.0, launchHeight, 0.0);

        Vec3d lookVector = Vec3d.fromPolar(guard.prevPitch, guard.prevYaw)
                .lerp(Vec3d.fromPolar(guard.getPitch(), guard.getYaw()), delta)
                .normalize();

        Beam.Position beamPosition = TargetHelper.castBeam(guard, lookVector, 32.0F);
        Vec3d to = from.add(lookVector.multiply(beamPosition.length()));

        Spell.Target.Beam beamAppearance = guard.getBeam();

        renderBeamGeometry(matrices, vertexConsumers, beamAppearance, from, to, offset, guard.getWorld().getTime(), delta);

        ((BeamEmitterEntity) guard).setLastRenderedBeam(new Beam.Rendered(beamPosition, beamAppearance));

        matrices.pop();
    }

    private static void renderBeamGeometry(MatrixStack matrices,
                                           VertexConsumerProvider vertexConsumers,
                                           Spell.Target.Beam beam,
                                           Vec3d from,
                                           Vec3d to,
                                           Vec3d offset,
                                           long time,
                                           float tickDelta) {
        float absoluteTime = (float) Math.floorMod(time, 40) + tickDelta;
        matrices.push();
        matrices.translate(0.0, offset.y, 0.0);

        Vec3d beamVector = to.subtract(from);
        float length = (float) beamVector.length();
        beamVector = beamVector.normalize();

        float pitch = (float) Math.acos(beamVector.y);
        float yaw = (float) Math.atan2(beamVector.z, beamVector.x);

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((1.5707964F - yaw) * 57.295776F));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch * 57.295776F));
        matrices.translate(0.0, offset.z, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(absoluteTime * 2.25F - 45.0F));

        Identifier texture = Identifier.of(beam.texture_id);
        Color.IntFormat outerColor = Color.IntFormat.fromLongRGBA(beam.color_rgba);
        Color.IntFormat innerColor = Color.IntFormat.fromLongRGBA(beam.inner_color_rgba);

        BeamRenderer.LayerSet renderLayers;
        if (ShaderCompatibility.isVanillaRenderSystem()) {
            renderLayers = BeamRenderer.vanilla(texture);
        } else {
            Spell.Target.Beam.Luminance luminance = ShaderCompatibility.isShaderPackInUse()
                    ? (net.spell_engine.client.SpellEngineClient.config.renderBeamsHighLuminance
                    ? beam.luminance
                    : Spell.Target.Beam.Luminance.MEDIUM)
                    : Spell.Target.Beam.Luminance.LOW;
            renderLayers = BeamRenderer.layerSetFor(texture, luminance);
        }

        BeamRenderer.renderBeam(
                matrices,
                vertexConsumers,
                time,
                tickDelta,
                beam.flow,
                true,
                innerColor,
                outerColor,
                renderLayers,
                0.0F,
                length,
                beam.width
        );

        matrices.pop();
    }
}