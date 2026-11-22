package me.napim.gomod.mixins;

import me.napim.gomod.Main;
import me.napim.gomod.features.PlayersPlayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Renders nearest players as a 2x2 split-screen by issuing extra world passes.
 */
@Mixin(EntityRenderer.class)
public abstract class PlayersPlayRendererMixin {

    @Shadow
    public abstract void renderWorld(float partialTicks, long finishTimeNano);

    private boolean gomod$ppSneakWasDown = false;
    private boolean gomod$ppRenderingFeeds = false;
    private boolean gomod$ppCustomPassActive = false;
    private int gomod$ppViewportX = 0;
    private int gomod$ppViewportY = 0;
    private int gomod$ppViewportW = 0;
    private int gomod$ppViewportH = 0;
    private float gomod$pp_localYaw = 0.0f;
    private float gomod$pp_localPrevYaw = 0.0f;
    private float gomod$pp_localPitch = 0.0f;
    private float gomod$pp_localPrevPitch = 0.0f;
    private float gomod$pp_localYawHead = 0.0f;
    private float gomod$pp_localPrevYawHead = 0.0f;
    private boolean gomod$pp_savedLocalRotation = false;

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gomod$playersplaySneakExit(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            if (!PlayersPlayMode.isEnabled()) {
                gomod$ppSneakWasDown = false;
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) return;
            if (mc.thePlayer instanceof EntityPlayerSP) {
                EntityPlayerSP local = mc.thePlayer;
                gomod$pp_localYaw = local.rotationYaw;
                gomod$pp_localPrevYaw = local.prevRotationYaw;
                gomod$pp_localPitch = local.rotationPitch;
                gomod$pp_localPrevPitch = local.prevRotationPitch;
                gomod$pp_localYawHead = local.rotationYawHead;
                gomod$pp_localPrevYawHead = local.prevRotationYawHead;
                gomod$pp_savedLocalRotation = true;
            }

            boolean sneakDown = Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode());
            if (sneakDown && !gomod$ppSneakWasDown) {
                boolean muted = PlayersPlayMode.isChatMutedThisSession();
                PlayersPlayMode.disable();
                PlayersPlayMode.notifyDisabled(muted);
            }
            gomod$ppSneakWasDown = sneakDown;
        } catch (Throwable t) {
            System.err.println("[gomod] PlayersPlay sneak-exit error: " + t.getMessage());
        }
    }

    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void gomod$playersplayRestoreLocalRotation(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!gomod$pp_savedLocalRotation || mc == null || !(mc.thePlayer instanceof EntityPlayerSP)) return;
            EntityPlayerSP local = mc.thePlayer;
            local.rotationYaw = gomod$pp_localYaw;
            local.prevRotationYaw = gomod$pp_localPrevYaw;
            local.rotationPitch = gomod$pp_localPitch;
            local.prevRotationPitch = gomod$pp_localPrevPitch;
            local.rotationYawHead = gomod$pp_localYawHead;
            local.prevRotationYawHead = gomod$pp_localPrevYawHead;
        } catch (Throwable t) {
            System.err.println("[gomod] PlayersPlay restore rotation error: " + t.getMessage());
        } finally {
            gomod$pp_savedLocalRotation = false;
        }
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void gomod$playersplayRenderFeeds(float partialTicks, long nanoTime, CallbackInfo ci) {
        if (!PlayersPlayMode.isEnabled() || gomod$ppRenderingFeeds) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            PlayersPlayMode.disable();
            return;
        }

        EntityPlayer[] targets = PlayersPlayMode.getActiveTargets();

        boolean anyTarget = false;
        for (EntityPlayer target : targets) {
            if (target != null) {
                anyTarget = true;
                break;
            }
        }

        if (!anyTarget && !PlayersPlayMode.isStayMode()) {
            boolean muted = PlayersPlayMode.isChatMutedThisSession();
            PlayersPlayMode.disable();
            if (!muted) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "PlayersPlay disabled: no nearby players.");
            }
            return;
        }

        if (!anyTarget) {
            return;
        }

        Entity originalViewEntity = mc.getRenderViewEntity();
        boolean scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int fbW = mc.displayWidth;
        int fbH = mc.displayHeight;
        try {
            Framebuffer fb = mc.getFramebuffer();
            if (fb != null) {
                fbW = fb.framebufferWidth;
                fbH = fb.framebufferHeight;
            }
        } catch (Throwable ignored) {}
        int[][] viewports = gomod$buildViewports(fbW, fbH);

        try {
            gomod$ppRenderingFeeds = true;
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            for (int i = 0; i < targets.length && i < 4; i++) {
                EntityPlayer target = targets[i];
                if (target == null) continue;

                int[] vp = viewports[i];
                gomod$ppViewportX = vp[0];
                gomod$ppViewportY = vp[1];
                gomod$ppViewportW = vp[2];
                gomod$ppViewportH = vp[3];

                GL11.glScissor(gomod$ppViewportX, gomod$ppViewportY, gomod$ppViewportW, gomod$ppViewportH);
                // Main pass filled the whole buffer (depth + color). Remote POVs need a clean depth
                // buffer inside this pane or the first overlaid quadrant (usually top-left) Z-fights
                // and looks corrupted; other panes looked fine because later passes happened to work.
                GlStateManager.depthMask(true);
                GL11.glClearDepth(1.0D);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GlStateManager.viewport(gomod$ppViewportX, gomod$ppViewportY, gomod$ppViewportW, gomod$ppViewportH);

                gomod$ppCustomPassActive = true;
                PlayersPlayMode.setCurrentRenderTarget(target);
                mc.setRenderViewEntity(target);
                this.renderWorld(partialTicks, nanoTime);
                gomod$ppCustomPassActive = false;
                PlayersPlayMode.setCurrentRenderTarget(null);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] PlayersPlay render error: " + t.getMessage());
        } finally {
            gomod$ppRenderingFeeds = false;
            gomod$ppCustomPassActive = false;
            PlayersPlayMode.setCurrentRenderTarget(null);
            mc.setRenderViewEntity(originalViewEntity);
            GlStateManager.viewport(0, 0, fbW, fbH);
            GL11.glScissor(0, 0, fbW, fbH);
            if (!scissorWasEnabled) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    @Redirect(
            method = "renderWorldPass",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;viewport(IIII)V"
            )
    )
    private void gomod$playersplayOverrideViewport(int x, int y, int width, int height) {
        if (gomod$ppCustomPassActive && PlayersPlayMode.isEnabled()) {
            GlStateManager.viewport(gomod$ppViewportX, gomod$ppViewportY, gomod$ppViewportW, gomod$ppViewportH);
            return;
        }
        GlStateManager.viewport(x, y, width, height);
    }

    /**
     * OpenGL viewport / scissor rectangles in framebuffer pixels (origin bottom-left).
     * Index 0 = top-left quadrant, 1 = top-right, 2 = bottom-left, 3 = bottom-right.
     */
    private static int[][] gomod$buildViewports(int fbWidth, int fbHeight) {
        int halfW = fbWidth / 2;
        int halfH = fbHeight / 2;

        return new int[][]{
                {0, halfH, halfW, fbHeight - halfH},
                {halfW, halfH, fbWidth - halfW, fbHeight - halfH},
                {0, 0, halfW, halfH},
                {halfW, 0, fbWidth - halfW, halfH}
        };
    }
}
