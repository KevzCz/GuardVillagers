package dev.sterner.guardvillagers.client.renderer;

import dev.sterner.guardvillagers.GuardVillagers;
import dev.sterner.guardvillagers.GuardVillagersClient;
import dev.sterner.guardvillagers.GuardVillagersConfig;
import dev.sterner.guardvillagers.client.animation.GuardAnimationState;
import dev.sterner.guardvillagers.client.model.GuardArmorModel;
import dev.sterner.guardvillagers.client.model.GuardSteveModel;
import dev.sterner.guardvillagers.client.model.GuardVillagerModel;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import dev.sterner.guardvillagers.common.entity.GuardItemTags;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.RotationAxis;
import org.jetbrains.annotations.Nullable;

public class GuardRenderer extends BipedEntityRenderer<GuardEntity, BipedEntityModel<GuardEntity>> {

    private final BipedEntityModel<GuardEntity> steve;
    private final BipedEntityModel<GuardEntity> normal = this.getModel();

    public GuardRenderer(EntityRendererFactory.Context context) {
        super(context, new GuardVillagerModel(context.getPart(GuardVillagersClient.GUARD)), 0.5F);
        this.steve = new GuardSteveModel(context.getPart(GuardVillagersClient.GUARD_STEVE));
        if (GuardVillagersConfig.useSteveModel)
            this.model = steve;
        else
            this.model = normal;
        this.addFeature(new ArmorFeatureRenderer<>(this, !GuardVillagersConfig.useSteveModel ?
                new GuardArmorModel(context.getPart(GuardVillagersClient.GUARD_ARMOR_INNER)) : new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)), !GuardVillagersConfig.useSteveModel ?
                new GuardArmorModel(context.getPart(GuardVillagersClient.GUARD_ARMOR_OUTER)) : new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));

    }

    @Override
    public void render(GuardEntity entityIn, float entityYaw, float partialTicks, MatrixStack matrixStackIn, VertexConsumerProvider bufferIn, int packedLightIn) {
        if (entityIn.isBeingViewedInGui) return;
        this.setModelVisibilities(entityIn);

        
        float spin = entityIn.getCastAnimationSpin();
        if (spin != 0f && entityIn.isCastingSpell()) {
            long worldTime = entityIn.getWorld().getTime();
            int ticks;
            float channelInterval;

            var process = entityIn.getSpellCastProcess();
            if (process != null) {
                ticks = process.spellCastTicksSoFar(worldTime);
                channelInterval = process.channelInterval(entityIn);
            } else {
                ticks = (int) Math.max(worldTime - entityIn.getCastStartedAt(), 0L);
                channelInterval = entityIn.getCastChannelInterval();
            }

            if (channelInterval > 0) {
                float turn = spin / (channelInterval / 20f);
                float degrees = turn * ticks + partialTicks * turn;
                matrixStackIn.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(degrees));
            }
        }

        super.render(entityIn, entityYaw, partialTicks, matrixStackIn, bufferIn, packedLightIn);
    }

    private void setModelVisibilities(GuardEntity entityIn) {
        BipedEntityModel<GuardEntity> guardmodel = this.getModel();
        ItemStack itemstack = entityIn.getMainHandStack();
        ItemStack itemstack1 = entityIn.getOffHandStack();
        guardmodel.setVisible(true);
        BipedEntityModel.ArmPose bipedmodel$armpose = this.getArmPose(entityIn, itemstack, itemstack1,
                Hand.MAIN_HAND);
        BipedEntityModel.ArmPose bipedmodel$armpose1 = this.getArmPose(entityIn, itemstack, itemstack1,
                Hand.OFF_HAND);
        guardmodel.sneaking = entityIn.isSneaking();

        
        guardmodel.rightArmPose = bipedmodel$armpose;
        guardmodel.leftArmPose = bipedmodel$armpose1;

        GuardAnimationState animState = GuardAnimationState.getOrCreate(entityIn);
        if (animState.isDrivingPose(entityIn) && !isDrawingBow(entityIn)) {
            guardmodel.rightArmPose = BipedEntityModel.ArmPose.ITEM;
            guardmodel.leftArmPose = BipedEntityModel.ArmPose.EMPTY;
        }
    }

    private static boolean isDrawingBow(GuardEntity entity) {
        if (entity.getItemUseTimeLeft() <= 0) {
            return false;
        }
        return entity.getMainHandStack().getUseAction() == UseAction.BOW;
    }

    private BipedEntityModel.ArmPose getArmPose(GuardEntity entityIn, ItemStack itemStackMain, ItemStack itemStackOff, Hand handIn) {
        BipedEntityModel.ArmPose bipedmodel$armpose = BipedEntityModel.ArmPose.EMPTY;
        ItemStack itemstack = handIn == Hand.MAIN_HAND ? itemStackMain : itemStackOff;
        if (!itemstack.isEmpty()) {
            bipedmodel$armpose = BipedEntityModel.ArmPose.ITEM;
            if (entityIn.getItemUseTimeLeft() > 0) {
                UseAction useaction = itemstack.getUseAction();
                switch (useaction) {
                    case BLOCK:
                        bipedmodel$armpose = BipedEntityModel.ArmPose.BLOCK;
                        break;
                    case BOW:
                        bipedmodel$armpose = BipedEntityModel.ArmPose.BOW_AND_ARROW;
                        break;
                    case SPEAR:
                        bipedmodel$armpose = BipedEntityModel.ArmPose.THROW_SPEAR;
                        break;
                    case CROSSBOW:
                        if (handIn == entityIn.getActiveHand()) {
                            bipedmodel$armpose = BipedEntityModel.ArmPose.CROSSBOW_CHARGE;
                        }
                        break;
                    default:
                        bipedmodel$armpose = entityIn.isCastingSpell() && handIn == Hand.MAIN_HAND
                                ? BipedEntityModel.ArmPose.EMPTY
                                : BipedEntityModel.ArmPose.ITEM;
                        break;
                }
            } else if (entityIn.isCastingSpell() && handIn == Hand.MAIN_HAND) {
                bipedmodel$armpose = BipedEntityModel.ArmPose.EMPTY;
            } else {
                boolean flag1 = GuardItemTags.isCrossbowLikeWeapon(itemStackMain);
                boolean flag2 = GuardItemTags.isCrossbowLikeWeapon(itemStackOff);
                if (flag1 && entityIn.isAttacking()) {
                    bipedmodel$armpose = BipedEntityModel.ArmPose.CROSSBOW_HOLD;
                }

                if (flag2 && itemStackMain.getItem().getUseAction(itemStackMain) == UseAction.NONE
                        && entityIn.isAttacking()) {
                    bipedmodel$armpose = BipedEntityModel.ArmPose.CROSSBOW_HOLD;
                }
            }
        }
        return bipedmodel$armpose;
    }

    @Override
    protected void scale(GuardEntity entitylivingbaseIn, MatrixStack matrixStackIn, float partialTickTime) {
        matrixStackIn.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void setupTransforms(GuardEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta, float scale) {
        super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
        GuardAnimationState state = GuardAnimationState.getOrCreate(entity);
        if (state.isDrivingPose(entity)) {
            state.applier.setTickDelta(tickDelta);
            GuardAnimationState.applyBodyTransforms(matrices, state.applier);
        }
    }

    @Nullable
    @Override
    public Identifier getTexture(GuardEntity entity) {
        return !GuardVillagersConfig.useSteveModel
                ? GuardVillagers.id(
                "textures/entity/guard/guard_" + entity.getGuardVariant() + ".png")
                : GuardVillagers.id(
                "textures/entity/guard/guard_steve_" + entity.getGuardVariant() + ".png");
    }
}