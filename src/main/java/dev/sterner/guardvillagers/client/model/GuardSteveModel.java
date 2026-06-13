package dev.sterner.guardvillagers.client.model;

import dev.sterner.guardvillagers.client.animation.GuardAnimationState;
import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.MathHelper;

public class GuardSteveModel extends PlayerEntityModel<GuardEntity> {
    public GuardSteveModel(ModelPart root) {
        super(root, false);
    }

    @Override
    public void setAngles(GuardEntity entityIn, float limbSwing, float limbSwingAmount, float ageInTicks, float netbipedHeadYaw, float bipedHeadPitch) {
        GuardAnimationState state = GuardAnimationState.getOrCreate(entityIn);

        if (entityIn.age != state.lastAge) {
            state.updateFor(entityIn);
            state.lastAge = entityIn.age;
        }

        boolean poseBlendActive = state.isDrivingPose(entityIn);

        state.clearEmoteSupplier(this);

        GuardAnimationState.resetModelDefaults(
                this.head, this.body,
                this.rightArm, this.leftArm,
                this.rightLeg, this.leftLeg,
                this.hat);

        if (poseBlendActive) {
            super.setAngles(entityIn, limbSwing, limbSwingAmount, ageInTicks, 0.0F, 0.0F);
        } else {
            super.setAngles(entityIn, limbSwing, limbSwingAmount, ageInTicks, netbipedHeadYaw, bipedHeadPitch);
        }
        if (entityIn.getKickTicks() > 0) {
            float f1 = 1.0F - (float) MathHelper.abs(10 - 2 * entityIn.getKickTicks()) / 10.0F;
            this.rightLeg.pitch = MathHelper.lerp(f1, this.rightLeg.pitch, -1.40F);
        }
        this.eatingAnimationRightHand(Hand.MAIN_HAND, entityIn, ageInTicks);
        this.eatingAnimationLeftHand(Hand.OFF_HAND, entityIn, ageInTicks);

        if (poseBlendActive) {
            state.applyToModel(this, ageInTicks - (float) entityIn.age, entityIn);
        }
    }

    public static TexturedModelData createMesh() {
        ModelData meshdefinition = PlayerEntityModel.getTexturedModelData(Dilation.NONE, false);
        return TexturedModelData.of(meshdefinition, 64, 64);
    }

    public void eatingAnimationRightHand(Hand hand, GuardEntity entity, float ageInTicks) {
        ItemStack itemstack = entity.getStackInHand(hand);
        boolean drinkingoreating = itemstack.getUseAction() == UseAction.EAT
                || itemstack.getUseAction() == UseAction.DRINK;
        if (entity.isEating() && drinkingoreating
                || entity.getItemUseTimeLeft() > 0 && drinkingoreating && entity.getActiveHand() == hand) {
            this.rightArm.yaw = -0.5F;
            this.rightArm.pitch = -1.3F;
            this.rightArm.roll = MathHelper.cos(ageInTicks) * 0.1F;
            this.head.pitch = MathHelper.cos(ageInTicks) * 0.2F;
            this.head.yaw = 0.0F;
            this.hat.copyTransform(head);
        }
    }

    public void eatingAnimationLeftHand(Hand hand, GuardEntity entity, float ageInTicks) {
        ItemStack itemstack = entity.getStackInHand(hand);
        boolean drinkingoreating = itemstack.getUseAction() == UseAction.EAT
                || itemstack.getUseAction() == UseAction.DRINK;
        if (entity.isEating() && drinkingoreating
                || entity.getItemUseTimeLeft() > 0 && drinkingoreating && entity.getActiveHand() == hand) {
            this.leftArm.yaw = 0.5F;
            this.leftArm.pitch = -1.3F;
            this.leftArm.roll = MathHelper.cos(ageInTicks) * 0.1F;
            this.head.pitch = MathHelper.cos(ageInTicks) * 0.2F;
            this.head.yaw = 0.0F;
            this.hat.copyTransform(head);
        }
    }
}