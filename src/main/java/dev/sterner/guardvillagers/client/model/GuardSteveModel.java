package dev.sterner.guardvillagers.client.model;


import dev.sterner.guardvillagers.common.entity.GuardEntity;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.MathHelper;

public class GuardSteveModel extends PlayerEntityModel<GuardEntity> {
    public GuardSteveModel(ModelPart root) {
        super(root, false);
    }

    @Override
    public void setAngles(GuardEntity entityIn, float limbSwing, float limbSwingAmount, float ageInTicks, float netbipedHeadYaw, float bipedHeadPitch) {
        super.setAngles(entityIn, limbSwing, limbSwingAmount, ageInTicks, netbipedHeadYaw, bipedHeadPitch);
        if (entityIn.getKickTicks() > 0) {
            float f1 = 1.0F - (float) MathHelper.abs(10 - 2 * entityIn.getKickTicks()) / 10.0F;
            this.rightLeg.pitch = MathHelper.lerp(f1, this.rightLeg.pitch, -1.40F);
        }
        if (entityIn.getMainArm() == Arm.RIGHT) {
            this.eatingAnimationRightHand(Hand.MAIN_HAND, entityIn, ageInTicks);
            this.eatingAnimationLeftHand(Hand.OFF_HAND, entityIn, ageInTicks);
        } else {
            this.eatingAnimationRightHand(Hand.OFF_HAND, entityIn, ageInTicks);
            this.eatingAnimationLeftHand(Hand.MAIN_HAND, entityIn, ageInTicks);
        }
        
        boolean hasCastingFlag = false;
        boolean isMeleeCasting = false;
        try {
            hasCastingFlag = entityIn.isCastingSpell();
            isMeleeCasting = entityIn.isCastingMeleeSpell();
        } catch (Throwable ignored) {}

        ItemStack mainHand = entityIn.getMainHandStack();
        boolean isHoldingStaff = !mainHand.isEmpty() && mainHand.getItem() instanceof net.spell_engine.api.item.weapon.StaffItem;
        boolean usingWandNow = entityIn.isUsingItem() && (isHoldingStaff || entityIn.isPriest());

        int swingTicks = entityIn.getSpellSwingTicks();
        float t = ageInTicks * 0.25F + entityIn.getId() * 0.10F;

        boolean useMeleeAnimation = isMeleeCasting || (hasCastingFlag && !isHoldingStaff && !entityIn.isPriest());
        
        if (hasCastingFlag && useMeleeAnimation) {
            
            if (swingTicks > 0) {
                float swingProgress = 1.0F - (swingTicks / 10.0F);
                float easedProgress = swingProgress * swingProgress;
                
                float startPitch = -2.5F;
                float endPitch = 1.0F;
                this.rightArm.pitch = startPitch + (endPitch - startPitch) * easedProgress;
                this.rightArm.yaw = 0.2F - 0.3F * easedProgress;
                this.rightArm.roll = -0.1F + 0.4F * easedProgress;
                
                this.leftArm.pitch = -0.4F + 0.3F * easedProgress;
                this.leftArm.yaw = 0.3F;
                this.leftArm.roll = -0.15F;
            } else {
                float tensionPulse = MathHelper.sin(t * 2.0F) * 0.08F;
                
                this.rightArm.pitch = -2.3F + tensionPulse;
                this.rightArm.yaw = 0.2F + 0.05F * MathHelper.sin(t * 1.5F);
                this.rightArm.roll = -0.1F;
                
                this.leftArm.pitch = -0.5F;
                this.leftArm.yaw = 0.35F;
                this.leftArm.roll = -0.2F;
            }
            
            this.hat.copyTransform(this.head);
        }
        else if ((hasCastingFlag && !useMeleeAnimation) || usingWandNow) {
            
            if (swingTicks > 0) {
                float swingProgress = 1.0F - (swingTicks / 10.0F);
                float easedProgress = swingProgress < 0.5F 
                    ? 2.0F * swingProgress * swingProgress 
                    : 1.0F - (float)Math.pow(-2.0F * swingProgress + 2.0F, 2) / 2.0F;
                
                float startPitch = -1.8F;
                float midPitch = 0.3F;
                float endPitch = 0.6F;
                
                if (easedProgress < 0.65F) {
                    float localProgress = easedProgress / 0.65F;
                    this.rightArm.pitch = startPitch + (midPitch - startPitch) * localProgress;
                } else {
                    float localProgress = (easedProgress - 0.65F) / 0.35F;
                    this.rightArm.pitch = midPitch + (endPitch - midPitch) * localProgress;
                }
                
                this.rightArm.yaw = 0.8F - 1.2F * easedProgress;
                this.rightArm.roll = 0.3F + 0.5F * easedProgress;
                
                this.leftArm.pitch = -0.8F + 0.3F * easedProgress;
                this.leftArm.yaw = -0.3F;
                this.leftArm.roll = -0.2F;
            } else {
                float swirlS = MathHelper.sin(t);
                float swirlC = MathHelper.cos(t);
                float raise = -1.4F;
                
                this.rightArm.pitch = raise + 0.2F * MathHelper.sin(t * 0.5F);
                this.rightArm.yaw = 0.5F + 0.4F * swirlS;
                this.rightArm.roll = 0.4F + 0.3F * swirlC;
                
                this.leftArm.pitch = -0.9F;
                this.leftArm.yaw = -0.2F;
                this.leftArm.roll = -0.25F + 0.1F * swirlC;
            }
            
            this.hat.copyTransform(this.head);
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