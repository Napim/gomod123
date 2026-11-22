package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Copies the main scene depth buffer into the entity-outline framebuffer so outline geometry
 * can depth-test against world blocks (Player Queuer stand-alone targets).
 */
public final class EntityOutlineDepthUtil {
    private static volatile boolean loggedFailure = false;

    private EntityOutlineDepthUtil() {}

    /**
     * Blits depth from {@link Minecraft#getFramebuffer()} into {@code outlineFb}.
     * @return false if blit could not run (caller should skip depth-test for Player Queuer this frame)
     */
    public static boolean tryCopyMainDepthToOutline(Minecraft mc, Framebuffer outlineFb) {
        if (mc == null || mc.getFramebuffer() == null || outlineFb == null) {
            return false;
        }
        try {
            Framebuffer main = mc.getFramebuffer();
            int read = main.framebufferObject;
            int draw = outlineFb.framebufferObject;
            if (read <= 0 || draw <= 0) {
                return false;
            }
            int w = Math.min(main.framebufferWidth, outlineFb.framebufferWidth);
            int h = Math.min(main.framebufferHeight, outlineFb.framebufferHeight);
            if (w <= 0 || h <= 0) {
                return false;
            }
            int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            try {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
                GL30.glBlitFramebuffer(
                        0, 0, w, h,
                        0, 0, w, h,
                        GL11.GL_DEPTH_BUFFER_BIT,
                        GL11.GL_NEAREST);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            }
            outlineFb.bindFramebuffer(false);
            return true;
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                System.err.println("[gomod] EntityOutlineDepthUtil: depth blit failed (need GL30 FBO blit): " + t.getMessage());
            }
            return false;
        }
    }
}
