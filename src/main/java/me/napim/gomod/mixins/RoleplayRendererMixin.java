package me.napim.gomod.mixins;

import me.napim.gomod.features.RoleplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles roleplay mode: swaps the render view entity to a target player so the
 * camera follows them, with smooth yaw tracking and sneak-key exit.
 */
@Mixin(EntityRenderer.class)
public abstract class RoleplayRendererMixin {

    // Edge-detection for sneak key so we exit roleplay on key-down, not every frame
    private boolean gomod$rp_sneakWasDown = false;
    // Saved renderViewEntity while orientCamera runs under roleplay
    private Entity gomod$rp_savedViewEntity = null;

    // Store real rotation before temporarily swapping to target's rotation
    private float gomod$rp_realYaw = 0.0f;
    private float gomod$rp_realPrevYaw = 0.0f;

    // Smoothed head-yaw tracking for roleplay (networked entities only update
    // rotationYawHead in single-tick jumps, unlike body yaw which is spread
    // over 3 ticks by EntityOtherPlayerMP).  We apply our own smoothing here.
    private float gomod$rp_smoothYaw = 0.0f;
    private float gomod$rp_smoothYawPrev = 0.0f;
    private boolean gomod$rp_smoothInit = false;
    private long gomod$rp_lastNanos = 0L;

    // Preserve the real local-player rotation so roleplay stays view-only.
    private float gomod$rp_localYaw = 0.0f;
    private float gomod$rp_localPrevYaw = 0.0f;
    private float gomod$rp_localPitch = 0.0f;
    private float gomod$rp_localPrevPitch = 0.0f;
    private float gomod$rp_localYawHead = 0.0f;
    private float gomod$rp_localPrevYawHead = 0.0f;
    private boolean gomod$rp_savedLocalRotation = false;

    /** Exit roleplay when the sneak key is freshly pressed (edge detect = only once per press). */
    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gomod$roleplaySneakExit(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            if (RoleplayMode.isEnabled()) {
                boolean sneakDown = org.lwjgl.input.Keyboard.isKeyDown(
                        mc.gameSettings.keyBindSneak.getKeyCode());
                if (sneakDown && !gomod$rp_sneakWasDown) {
                    RoleplayMode.disable();
                }
                gomod$rp_sneakWasDown = sneakDown;
            } else {
                gomod$rp_sneakWasDown = false;
            }
        } catch (Throwable t) {
            System.err.println("[gomod] RoleplayRendererMixin sneak exit error: " + t.getMessage());
        }
    }

    /**
     * Swap renderViewEntity to the roleplay target for the ENTIRE frame so that
     * orientCamera, ActiveRenderInfo, chunk culling, and world rendering all treat
     * the target as the camera origin.  Restored unconditionally in the RETURN inject.
     */
    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gomod$roleplayFrameSwapHead(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            if (!RoleplayMode.isEnabled()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            if (mc.thePlayer instanceof EntityPlayerSP) {
                EntityPlayerSP local = mc.thePlayer;
                gomod$rp_localYaw = local.rotationYaw;
                gomod$rp_localPrevYaw = local.prevRotationYaw;
                gomod$rp_localPitch = local.rotationPitch;
                gomod$rp_localPrevPitch = local.prevRotationPitch;
                gomod$rp_localYawHead = local.rotationYawHead;
                gomod$rp_localPrevYawHead = local.prevRotationYawHead;
                gomod$rp_savedLocalRotation = true;
            }
            EntityPlayer target = RoleplayMode.getTargetPlayer();
            if (target == null) return;
            if (gomod$rp_savedViewEntity != null) return; // already swapped
            gomod$rp_savedViewEntity = mc.getRenderViewEntity();
            mc.setRenderViewEntity(target);
            if (target instanceof net.minecraft.entity.EntityLivingBase) {
                net.minecraft.entity.EntityLivingBase living =
                        (net.minecraft.entity.EntityLivingBase) target;
                RoleplayMode.setSmoothCameraAngles(living.rotationYawHead, living.rotationPitch);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] RoleplayRendererMixin frame swap HEAD error: " + t.getMessage());
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void gomod$roleplayFrameSwapReturn(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (gomod$rp_savedLocalRotation && mc != null && mc.thePlayer instanceof EntityPlayerSP) {
                EntityPlayerSP local = mc.thePlayer;
                local.rotationYaw = gomod$rp_localYaw;
                local.prevRotationYaw = gomod$rp_localPrevYaw;
                local.rotationPitch = gomod$rp_localPitch;
                local.prevRotationPitch = gomod$rp_localPrevPitch;
                local.rotationYawHead = gomod$rp_localYawHead;
                local.prevRotationYawHead = gomod$rp_localPrevYawHead;
            }
            gomod$rp_savedLocalRotation = false;

            if (gomod$rp_savedViewEntity == null) return;
            if (mc != null) {
                mc.setRenderViewEntity(gomod$rp_savedViewEntity);
            }
            gomod$rp_savedViewEntity = null;
        } catch (Throwable t) {
            System.err.println("[gomod] RoleplayRendererMixin frame swap RETURN error: " + t.getMessage());
        }
    }

    /**
     * Override body yaw with smoothed head yaw so the camera points where the player's
     * head is actually looking, not where the body is facing.
     */
    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void gomod$roleplayOrientHead(float partialTicks, CallbackInfo ci) {
        try {
            if (!RoleplayMode.isEnabled()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getRenderViewEntity() == null) return;
            Entity viewEntity = mc.getRenderViewEntity();
            if (!(viewEntity instanceof net.minecraft.entity.EntityLivingBase)) return;

            net.minecraft.entity.EntityLivingBase living =
                    (net.minecraft.entity.EntityLivingBase) viewEntity;
            gomod$rp_realYaw = living.rotationYaw;
            gomod$rp_realPrevYaw = living.prevRotationYaw;

            // --- Smoothed head yaw ---
            // Networked entities receive head-yaw in discrete jumps (1 tick).
            // Body yaw is spread over ~3 ticks by EntityOtherPlayerMP.
            // We match that smoothness with time-based exponential decay.
            float rawYaw = living.rotationYawHead;
            long now = System.nanoTime();
            if (!gomod$rp_smoothInit) {
                gomod$rp_smoothYaw = rawYaw;
                gomod$rp_smoothYawPrev = rawYaw;
                gomod$rp_lastNanos = now;
                gomod$rp_smoothInit = true;
            } else {
                float dt = (now - gomod$rp_lastNanos) / 1_000_000_000.0f;
                gomod$rp_lastNanos = now;
                // Time constant ~0.08s — roughly 1.5 ticks, fast yet smooth.
                float alpha = 1.0f - (float) Math.exp(-dt / 0.08f);
                float delta = MathHelper.wrapAngleTo180_float(
                        rawYaw - gomod$rp_smoothYaw);
                gomod$rp_smoothYawPrev = gomod$rp_smoothYaw;
                gomod$rp_smoothYaw += delta * alpha;
            }

            living.rotationYaw = gomod$rp_smoothYaw;
            living.prevRotationYaw = gomod$rp_smoothYawPrev;

            // Publish smoothed angles so RenderManagerMixin can orient nametags consistently.
            RoleplayMode.setSmoothCameraAngles(gomod$rp_smoothYaw, living.rotationPitch);
        } catch (Throwable t) {
            System.err.println("[gomod] RoleplayRendererMixin orientCamera HEAD error: " + t.getMessage());
        }
    }

    @Inject(method = "orientCamera", at = @At("RETURN"))
    private void gomod$roleplayOrientReturn(float partialTicks, CallbackInfo ci) {
        try {
            // Roleplay: restore the body yaw we overrode in the HEAD inject.
            if (RoleplayMode.isEnabled()) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.getRenderViewEntity() instanceof net.minecraft.entity.EntityLivingBase) {
                    net.minecraft.entity.EntityLivingBase living =
                            (net.minecraft.entity.EntityLivingBase) mc.getRenderViewEntity();
                    living.rotationYaw = gomod$rp_realYaw;
                    living.prevRotationYaw = gomod$rp_realPrevYaw;
                }
            } else {
                // Reset smoothing state when roleplay is off so re-enabling gets a clean start.
                gomod$rp_smoothInit = false;
            }
        } catch (Throwable t) {
            System.err.println("[gomod] RoleplayRendererMixin orientCamera RETURN error: " + t.getMessage());
        }
    }
}
