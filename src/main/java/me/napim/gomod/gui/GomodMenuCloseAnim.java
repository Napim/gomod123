package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import me.napim.gomod.render.GuiBackdropRenderer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Renders a post-close Zoom Pop animation as a HUD overlay.
 * Triggered immediately when any gomod GUI closes to game, so the player
 * is freed from the GUI right away while the visual still plays.
 */
public final class GomodMenuCloseAnim {

    private static final long DURATION_MS = 180L;

    private static volatile long startTime = -1L;
    private static volatile GuiScreen closingScreen = null;
    private static volatile boolean renderingCapturedScreen = false;
    private static int capturedTextureId = -1;
    private static final boolean WEAVE_RUNTIME = detectWeaveRuntime();

    private GomodMenuCloseAnim() {}

    /** Call this just before calling mc.displayGuiScreen(null) from any gomod screen. */
    public static void trigger() {
        trigger(Minecraft.getMinecraft() != null ? Minecraft.getMinecraft().currentScreen : null);
    }

    public static void trigger(GuiScreen screen) {
        releaseCapturedTexture();
        if (WEAVE_RUNTIME) {
            // Lunar's render hand-off flashes when a post-close fullscreen layer is composited over its first world
            // frame. The menu backdrop itself already supplies the transition, so close immediately and cleanly.
            closingScreen = null;
            startTime = -1L;
            return;
        }
        // glCopyTexImage2D forces Lunar's render pipeline to synchronise and copy a full-resolution framebuffer on
        // the close frame. That stall is longer than much of this short animation on high-resolution displays.
        // Weave uses the lightweight zoom/fade below; Forge keeps its existing screen redraw fallback.
        int captured = -1;
        capturedTextureId = captured;
        closingScreen = screen;
        startTime = System.currentTimeMillis();
    }

    public static boolean isActive() {
        return startTime >= 0L;
    }

    public static boolean isRenderingCapturedScreen() {
        return renderingCapturedScreen;
    }

    /** Called from ForgeEventBridge on every RenderGameOverlayEvent.Post while in-game. */
    public static void renderIfActive(float partialTicks) {
        long start = startTime;
        if (start < 0L) return;

        long elapsed = System.currentTimeMillis() - start;
        if (elapsed >= DURATION_MS) {
            startTime = -1L;
            closingScreen = null;
            releaseCapturedTexture();
            return;
        }

        float raw   = MenuAnimationManager.clamp01(elapsed / (float) DURATION_MS);
        float scale = 1f - MenuAnimationManager.easeInBack(raw) * 0.32f;
        float alpha = (1f - raw) * 0.28f;
        float backdropAlpha = 1f - raw;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        ScaledResolution sr = new ScaledResolution(mc);
        int w = sr.getScaledWidth();
        int h = sr.getScaledHeight();
        int mouseX = Mouse.getX() * w / Math.max(1, mc.displayWidth);
        int mouseY = h - (Mouse.getY() * h / Math.max(1, mc.displayHeight)) - 1;

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);

        GuiScreen screen = closingScreen;
        boolean hasCapturedFrame = capturedTextureId > 0;
        if (!hasCapturedFrame && screen != null) {
            GuiBackdropRenderer.renderCustomBackdrop(screen, w, h, backdropAlpha);
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0f);
        GlStateManager.scale(scale, scale, 1f);
        GlStateManager.translate(-cx, -cy, 0f);

        if (hasCapturedFrame) {
            drawCapturedFrame(w, h);
        } else if (screen != null) {
            try {
                renderingCapturedScreen = true;
                screen.drawScreen(mouseX, mouseY, partialTicks);
            } catch (Throwable ignored) {
            } finally {
                renderingCapturedScreen = false;
            }
        }

        GlStateManager.popMatrix();
        int a = (int) (alpha * 255f);
        if (a > 0) {
            Gui.drawRect(0, 0, w, h, (a << 24) | 0x101010);
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    /** Copy the currently displayed gomod menu once; subsequent animation frames only draw this texture. */
    private static int captureCurrentFramebuffer() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.displayWidth <= 0 || mc.displayHeight <= 0 || mc.getFramebuffer() == null) {
                return -1;
            }
            mc.getFramebuffer().bindFramebuffer(false);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int texture = GL11.glGenTextures();
            if (texture <= 0) {
                return -1;
            }
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                        0, 0, mc.displayWidth, mc.displayHeight, 0);
                return texture;
            } catch (Throwable captureFailure) {
                GL11.glDeleteTextures(texture);
                return -1;
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            }
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void drawCapturedFrame(int width, int height) {
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(capturedTextureId);
        GlStateManager.color(1f, 1f, 1f, 1f);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer vertices = tessellator.getWorldRenderer();
        vertices.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        // glCopyTexImage2D copies from OpenGL's bottom-left origin; flip V for GUI top-left coordinates.
        vertices.pos(0.0D, height, 0.0D).tex(0.0D, 0.0D).endVertex();
        vertices.pos(width, height, 0.0D).tex(1.0D, 0.0D).endVertex();
        vertices.pos(width, 0.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
        vertices.pos(0.0D, 0.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
        tessellator.draw();
    }

    private static void releaseCapturedTexture() {
        int texture = capturedTextureId;
        capturedTextureId = -1;
        if (texture > 0) {
            try {
                GL11.glDeleteTextures(texture);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean detectWeaveRuntime() {
        try {
            Class.forName("net.weavemc.api.ModInitializer", false, GomodMenuCloseAnim.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
