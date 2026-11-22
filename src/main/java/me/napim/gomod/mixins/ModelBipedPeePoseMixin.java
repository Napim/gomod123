package me.napim.gomod.mixins;

import me.napim.gomod.features.PeeEffectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the goofy pose to local and relayed gomod players while /pee is active.
 */
@Mixin(ModelBiped.class)
public class ModelBipedPeePoseMixin {

    @Shadow public ModelRenderer bipedRightArm;
    @Shadow public ModelRenderer bipedLeftArm;
    @Shadow public ModelRenderer bipedBody;
    @Shadow public ModelRenderer bipedHead;

    @Inject(method = {"setRotationAngles", "func_78087_a"}, at = @At("TAIL"), require = 0)
    private void gomod$applyPeePose(float limbSwing, float limbSwingAmount, float ageInTicks,
                                    float netHeadYaw, float headPitch, float scaleFactor,
                                    Entity entityIn, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || !(entityIn instanceof EntityPlayer)) {
                return;
            }
            if (!PeeEffectManager.isActiveForPlayer(entityIn)) {
                return;
            }

            float wiggle = (float) Math.sin(ageInTicks * 0.55f) * 0.06f;
            boolean localPlayer = entityIn.getEntityId() == mc.thePlayer.getEntityId();
            boolean thirdPerson = !localPlayer || (mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0);
            float sideWiggle = thirdPerson ? (float) Math.sin(ageInTicks * 0.85f) * 0.18f : 0.0f;
            float bodyTwist = thirdPerson ? (float) Math.sin(ageInTicks * 0.70f) * 0.10f : 0.0f;

            // Both hands close to groin area, with slight idle wiggle.
            bipedRightArm.rotateAngleX = -1.22f + wiggle;
            bipedRightArm.rotateAngleY = -0.38f + sideWiggle;
            bipedRightArm.rotateAngleZ = 0.30f;

            bipedLeftArm.rotateAngleX = -1.18f - wiggle;
            bipedLeftArm.rotateAngleY = 0.38f + sideWiggle;
            bipedLeftArm.rotateAngleZ = -0.30f;

            // Tiny forward hunch for comedic "peeing" posture.
            bipedBody.rotateAngleX = 0.08f;
            bipedBody.rotateAngleY += bodyTwist;
            bipedHead.rotateAngleX -= 0.06f;
        } catch (Throwable ignored) {
        }
    }
}

