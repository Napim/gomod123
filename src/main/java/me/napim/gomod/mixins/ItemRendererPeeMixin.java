package me.napim.gomod.mixins;

import me.napim.gomod.features.PeeEffectManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the first-person hand/item centered during /pee instead of using vanilla side offsets
 * (especially when blocking, eating, bowing, or holding blocks).
 */
@Mixin(ItemRenderer.class)
public class ItemRendererPeeMixin {

    private boolean gomod$peeArmAnglesSaved;
    private float gomod$peeSavedRenderArmPitch;
    private float gomod$peeSavedPrevRenderArmPitch;
    private float gomod$peeSavedRenderArmYaw;
    private float gomod$peeSavedPrevRenderArmYaw;

    private boolean gomod$skipVanillaHeldTransform() {
        return PeeEffectManager.shouldOverrideFirstPersonHeldItem();
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void gomod$peeSyncArmAnglesHead(float partialTicks, CallbackInfo ci) {
        if (!PeeEffectManager.shouldOverrideFirstPersonHeldItem()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        gomod$peeSavedRenderArmPitch = player.renderArmPitch;
        gomod$peeSavedPrevRenderArmPitch = player.prevRenderArmPitch;
        gomod$peeSavedRenderArmYaw = player.renderArmYaw;
        gomod$peeSavedPrevRenderArmYaw = player.prevRenderArmYaw;

        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        player.renderArmPitch = pitch;
        player.prevRenderArmPitch = pitch;
        player.renderArmYaw = yaw;
        player.prevRenderArmYaw = yaw;
        gomod$peeArmAnglesSaved = true;
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("RETURN"))
    private void gomod$peeRestoreArmAnglesReturn(float partialTicks, CallbackInfo ci) {
        if (!gomod$peeArmAnglesSaved) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            EntityPlayerSP player = mc.thePlayer;
            player.renderArmPitch = gomod$peeSavedRenderArmPitch;
            player.prevRenderArmPitch = gomod$peeSavedPrevRenderArmPitch;
            player.renderArmYaw = gomod$peeSavedRenderArmYaw;
            player.prevRenderArmYaw = gomod$peeSavedPrevRenderArmYaw;
        }
        gomod$peeArmAnglesSaved = false;
    }

    @Inject(method = {"doBlockTransformations", "func_178103_d"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipPeeBowTransform(CallbackInfo ci) {
        if (gomod$skipVanillaHeldTransform()) {
            ci.cancel();
        }
    }

    @Inject(method = {"doItemUsedTransformations", "func_178105_d"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipPeeSwingTransform(float swingProgress, CallbackInfo ci) {
        if (gomod$skipVanillaHeldTransform()) {
            ci.cancel();
        }
    }

    @Inject(method = {"transformFirstPersonItem", "func_178096_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipPeeHeldTransform(float equippedProgress, float swingProgress, CallbackInfo ci) {
        if (gomod$skipVanillaHeldTransform()) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                float ageInTicks = mc.thePlayer.ticksExisted;
                PeeEffectManager.applyFirstPersonHeldItemTransform(
                        equippedProgress, swingProgress, ageInTicks);
            }
            ci.cancel();
        }
    }

    @Inject(method = {"performDrinking", "func_178104_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipPeeUseTransform(
            net.minecraft.client.entity.AbstractClientPlayer player,
            float partialTicks,
            CallbackInfo ci) {
        if (gomod$skipVanillaHeldTransform()) {
            ci.cancel();
        }
    }

    @Inject(method = {"doBowTransformations", "func_178098_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipPeeDrinkTransform(
            float partialTicks,
            net.minecraft.client.entity.AbstractClientPlayer player,
            CallbackInfo ci) {
        if (gomod$skipVanillaHeldTransform()) {
            ci.cancel();
        }
    }

}
