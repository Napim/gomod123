package me.napim.gomod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

/**
 * Helpers to convert GUI-space measurements for optional bottom scissor regions (currently unused: full-screen dim/blur).
 */
public final class GuiBackdropChatReserve {

    private GuiBackdropChatReserve() {}

    /**
     * Converts a bottom exclusion measured in scaled GUI pixels into framebuffer pixels for {@link org.lwjgl.opengl.GL11#glScissor}.
     * Using {@code scaledHeight * scaleFactor} is often wrong with OptiFine/Lunar or when {@code framebufferHeight} != {@code displayHeight}.
     */
    public static int excludeBottomGuiToFramebufferPixels(Minecraft mc, int excludeBottomGui) {
        if (mc == null || excludeBottomGui <= 0) {
            return 0;
        }
        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        int vh = vp.get(3);
        if (vh <= 0) {
            Framebuffer fb = mc.getFramebuffer();
            vh = fb != null ? fb.framebufferHeight : mc.displayHeight;
        }
        if (vh <= 0) {
            return 0;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sh = Math.max(1, sr.getScaledHeight());
        return Math.min(vh, (int) Math.ceil(excludeBottomGui * (double) vh / sh));
    }

    /**
     * Enables scissor so the bottom {@code excludeBottomGui} scaled-GUI pixels are excluded (dim/blur skip). Uses the
     * active GL viewport (needed when OptiFine/Lunar use a sub-viewport for scaled GUI).
     */
    public static void enableBottomChatClearScissor(Minecraft mc, int excludeBottomGui) {
        if (excludeBottomGui <= 0 || mc == null) {
            return;
        }
        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        int vx = vp.get(0);
        int vy = vp.get(1);
        int vw = vp.get(2);
        int vh = Math.max(1, vp.get(3));
        ScaledResolution sr = new ScaledResolution(mc);
        int sh = Math.max(1, sr.getScaledHeight());
        int exPx = Math.min(vh, (int) Math.ceil(excludeBottomGui * (double) vh / sh));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(vx, vy + exPx, vw, vh - exPx);
    }

    public static void disableBottomChatClearScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * @param isGomodGui {@code true} when {@code screen} is in {@code me.napim.gomod.gui.*}
     */
    public static int computeExcludeBottomGuiPixels(Minecraft mc, GuiScreen screen, boolean isGomodGui) {
        // Chat-strip exclusion removed: backdrop blur/dim always cover the full GUI.
        return 0;
    }
}
