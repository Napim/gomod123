package me.napim.gomod.mixins;

import me.napim.gomod.features.PeeEffectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures /pee pose also applies on ModelPlayer renders for local and relayed players.
 */
@Mixin(ModelPlayer.class)
public class ModelPlayerPeePoseMixin {

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

            ModelBiped model = (ModelBiped) (Object) this;
            PeeEffectManager.applyPlayerModelPoseIfActive(model, ageInTicks, entityIn);
        } catch (Throwable ignored) {
        }
    }
}

