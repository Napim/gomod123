package me.napim.gomod.mixins;

import me.napim.gomod.features.ReflectionLogger;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.Selfie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles selfie mode: freezes camera position and rotation so the player
 * can see themselves from a fixed viewpoint in third person.
 */
@Mixin(EntityRenderer.class)
public abstract class SelfieRendererMixin {

    // Selfie mode: Store real entity rotation and position temporarily
    private float gomod$realRotationPitch = 0.0f;
    private float gomod$realRotationYaw = 0.0f;
    private float gomod$realPrevRotationPitch = 0.0f;
    private float gomod$realPrevRotationYaw = 0.0f;
    private double gomod$realPosX = 0.0;
    private double gomod$realPosY = 0.0;
    private double gomod$realPosZ = 0.0;
    private double gomod$realLastTickPosX = 0.0;
    private double gomod$realLastTickPosY = 0.0;
    private double gomod$realLastTickPosZ = 0.0;

    // Suppress vanilla camera effects during selfie
    @Inject(method = "applyBobbing", at = @At("HEAD"), cancellable = true)
    private void gomod$disableBobbing(float partialTicks, CallbackInfo ci) {
        try {
            if (Selfie.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            System.err.println("[gomod] SelfieRendererMixin bobbing error: " + t.getMessage());
        }
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void gomod$disableHurtEffect(float partialTicks, CallbackInfo ci) {
        try {
            if (Selfie.isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable t) {
            System.err.println("[gomod] SelfieRendererMixin hurtEffect error: " + t.getMessage());
        }
    }

    /** Turn off selfie if the player toggles perspective (F5) while selfie is active. */
    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gomod$selfieCheckPerspective(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            if (!Selfie.isEnabled()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            Selfie.refreshAfterWorldTransfer();
            Selfie.beginCameraFrame();
            if (mc.gameSettings != null && mc.gameSettings.thirdPersonView != 1) {
                Selfie.setEnabled(false);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] SelfieRendererMixin perspective check error: " + t.getMessage());
        }
    }

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void gomod$selfieFreezeRotation(float partialTicks, CallbackInfo ci) {
        try {
            // Skip if roleplay mode is active (handled by RoleplayRendererMixin)
            if (RoleplayMode.isEnabled()) return;

            // Only apply if selfie mode is enabled
            if (!Selfie.isEnabled() || Selfie.isCameraFrameSuppressed()) return;

            Minecraft mc = Minecraft.getMinecraft();
            Entity viewEntity = gomod$getCurrentWorldViewEntity(mc);
            if (viewEntity == null) return;

            // Store the real entity rotation and position (what the player is actually doing)
            gomod$realRotationPitch = viewEntity.rotationPitch;
            gomod$realRotationYaw = viewEntity.rotationYaw;
            gomod$realPrevRotationPitch = viewEntity.prevRotationPitch;
            gomod$realPrevRotationYaw = viewEntity.prevRotationYaw;
            gomod$realPosX = viewEntity.posX;
            gomod$realPosY = viewEntity.posY;
            gomod$realPosZ = viewEntity.posZ;
            gomod$realLastTickPosX = viewEntity.lastTickPosX;
            gomod$realLastTickPosY = viewEntity.lastTickPosY;
            gomod$realLastTickPosZ = viewEntity.lastTickPosZ;

            // Temporarily override entity rotation and position with frozen camera values
            viewEntity.rotationPitch = Selfie.getCameraPitch();
            viewEntity.rotationYaw = Selfie.getCameraYaw();
            viewEntity.prevRotationPitch = Selfie.getCameraPrevPitch();
            viewEntity.prevRotationYaw = Selfie.getCameraPrevYaw();

            // Override eye height to prevent shift animation
            try {
                java.lang.reflect.Field eyeHeightField = viewEntity.getClass().getDeclaredField("eyeHeight");
                eyeHeightField.setAccessible(true);
                float frozenEyeHeight = (float)(Selfie.getCameraY() - viewEntity.posY);
                eyeHeightField.setFloat(viewEntity, frozenEyeHeight);
            } catch (Exception e) {
                // Ignore reflection errors for eye height — field may not exist on this build
            }

        } catch (Exception e) {
            ReflectionLogger.logReflectionFailure("SelfieRendererMixin: selfieFreezeRotation", e);
        }
    }

    /**
     * Inject at RETURN of orientCamera to apply position offset and restore real entity rotation.
     */
    @Inject(method = "orientCamera", at = @At("RETURN"))
    private void gomod$selfieFreezePosition(float partialTicks, CallbackInfo ci) {
        try {
            // Skip if roleplay mode is active (handled by RoleplayRendererMixin)
            if (RoleplayMode.isEnabled()) return;

            // Only apply if selfie mode is enabled
            if (!Selfie.isEnabled() || Selfie.isCameraFrameSuppressed()) return;

            Minecraft mc = Minecraft.getMinecraft();
            Entity viewEntity = gomod$getCurrentWorldViewEntity(mc);
            if (viewEntity == null) return;

            // Restore the real entity rotation and position (so gameplay/UI still responds to mouse and movement)
            viewEntity.rotationPitch = gomod$realRotationPitch;
            viewEntity.rotationYaw = gomod$realRotationYaw;
            viewEntity.prevRotationPitch = gomod$realPrevRotationPitch;
            viewEntity.prevRotationYaw = gomod$realPrevRotationYaw;
            viewEntity.posX = gomod$realPosX;
            viewEntity.posY = gomod$realPosY;
            viewEntity.posZ = gomod$realPosZ;
            viewEntity.lastTickPosX = gomod$realLastTickPosX;
            viewEntity.lastTickPosY = gomod$realLastTickPosY;
            viewEntity.lastTickPosZ = gomod$realLastTickPosZ;

            // Apply position offset to move camera to frozen position
            double cameraX = Selfie.getCameraX();
            double cameraY = Selfie.getCameraY();
            double cameraZ = Selfie.getCameraZ();

            // Calculate the current entity base position with interpolation (without live eye-height)
            double baseX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
            double baseY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
            double baseZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;

            // Derive current camera point using captured eye height instead of live eye height
            double currentX = baseX;
            double currentY = baseY + Selfie.getCameraEyeHeight();
            double currentZ = baseZ;

            // Calculate the offset from current position to frozen camera position
            double offsetX = cameraX - currentX;
            double offsetY = cameraY - currentY;
            double offsetZ = cameraZ - currentZ;

            // Apply the position offset to move camera to frozen position
            GL11.glTranslated(-offsetX, -offsetY, -offsetZ);

        } catch (Exception e) {
            ReflectionLogger.logReflectionFailure("SelfieRendererMixin: selfieFreezePosition", e);
        }
    }

    /** Clears the one-frame teleport handoff after the complete camera render. */
    @Inject(method = "updateCameraAndRender", at = @At("TAIL"), require = 0)
    private void gomod$finishSelfieCameraFrame(float partialTicks, long nanoTime, CallbackInfo ci) {
        Selfie.endCameraFrame();
    }

    /**
     * During a server/backend transfer Minecraft can leave the old render-view
     * entity installed for a frame after the new world/player is available.
     * Never use that entity to calculate the camera offset: it belongs to the
     * old world and would make selfie appear stuck at the previous location.
     */
    private static Entity gomod$getCurrentWorldViewEntity(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity != null && viewEntity.worldObj == mc.theWorld) {
            return viewEntity;
        }
        return mc.thePlayer != null && mc.thePlayer.worldObj == mc.theWorld
                ? mc.thePlayer : null;
    }
}
