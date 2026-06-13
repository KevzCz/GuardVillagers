package dev.sterner.guardvillagers;

import dev.sterner.guardvillagers.client.animation.GuardAnimationLoader;
import dev.sterner.guardvillagers.client.sound.GuardSpellCastSoundClient;
import dev.sterner.guardvillagers.common.animation.GuardAnimationDurations;
import dev.sterner.guardvillagers.client.model.*;
import dev.sterner.guardvillagers.client.renderer.*;
import dev.sterner.guardvillagers.client.screen.*;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.rendering.v1.*;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.client.*;
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.model.*;

import static dev.sterner.guardvillagers.GuardVillagers.*;

public class GuardVillagersClient implements ClientModInitializer {

    public static EntityModelLayer GUARD = new EntityModelLayer(GuardVillagers.id( "guard"), "main");
    public static EntityModelLayer GUARD_STEVE = new EntityModelLayer(GuardVillagers.id( "guard_steve"), "main");
    public static EntityModelLayer GUARD_ARMOR_OUTER = new EntityModelLayer(GuardVillagers.id( "guard_armor_outer"), "main");
    public static EntityModelLayer GUARD_ARMOR_INNER = new EntityModelLayer(GuardVillagers.id( "guard_armor_inner"), "main");

    @Override
    public void onInitializeClient() {
        GuardSpellCastSoundClient.register();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(GuardAnimationLoader.INSTANCE);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(GuardAnimationDurations.INSTANCE);
        HandledScreens.register(GUARD_SCREEN_HANDLER, GuardVillagerScreen::new);
        EntityModelLayerRegistry.registerModelLayer(GUARD, GuardVillagerModel::createBodyLayer);
        EntityModelLayerRegistry.registerModelLayer(GUARD_STEVE, GuardSteveModel::createMesh);
        EntityModelLayerRegistry.registerModelLayer(GUARD_ARMOR_OUTER, GuardArmorModel::createOuterArmorLayer);
        EntityModelLayerRegistry.registerModelLayer(GUARD_ARMOR_INNER, GuardArmorModel::createInnerArmorLayer);
        EntityRendererRegistry.register(GUARD_VILLAGER, GuardRenderer::new);

        WorldRenderEvents.AFTER_TRANSLUCENT.register((context) -> {
            VertexConsumerProvider.Immediate vcProvider =
                    MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
            GuardBeamRenderer.renderGuardBeams(
                    context,
                    context.matrixStack(),
                    vcProvider,
                    context.camera(),
                    15728880,
                    context.tickCounter().getTickDelta(true)
            );
        });

    }
}
