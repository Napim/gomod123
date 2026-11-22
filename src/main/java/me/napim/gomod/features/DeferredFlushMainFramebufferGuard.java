package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.EXTFramebufferObject;

import java.nio.IntBuffer;

/**
 * Shader post chains (e.g. PolyBlur Phosphor) may leave a non-main framebuffer bound. Deferred entity draws
 * must hit the same buffer as the translucent terrain pass or depth/colour composite incorrectly.
 */
public final class DeferredFlushMainFramebufferGuard {

    public static final class Snap {
        final boolean rebound;
        final int savedFramebuffer;
        final int v0;
        final int v1;
        final int v2;
        final int v3;

        Snap(boolean rebound, int savedFramebuffer, int v0, int v1, int v2, int v3) {
            this.rebound = rebound;
            this.savedFramebuffer = savedFramebuffer;
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
        }
    }

    private DeferredFlushMainFramebufferGuard() {}

    /** If the current FBO is not Minecraft's main framebuffer, bind main and remember state for {@link #leave}. */
    public static Snap enter(Minecraft mc) {
        if (mc == null || mc.getFramebuffer() == null || !OpenGlHelper.framebufferSupported) {
            return new Snap(false, 0, 0, 0, 0, 0);
        }
        int v0 = 0;
        int v1 = 0;
        int v2 = 0;
        int v3 = 0;
        try {
            IntBuffer vp = BufferUtils.createIntBuffer(4);
            GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
            v0 = vp.get(0);
            v1 = vp.get(1);
            v2 = vp.get(2);
            v3 = vp.get(3);
        } catch (Throwable ignored) {
        }
        int savedFb = 0;
        int mainFb = mc.getFramebuffer().framebufferObject;
        try {
            savedFb = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        } catch (Throwable ignored) {
            savedFb = mainFb;
        }
        boolean rebound = savedFb != mainFb;
        if (rebound) {
            try {
                mc.getFramebuffer().bindFramebuffer(true);
            } catch (Throwable ignored) {
                return new Snap(false, savedFb, v0, v1, v2, v3);
            }
        }
        return new Snap(rebound, savedFb, v0, v1, v2, v3);
    }

    public static void leave(Snap snap) {
        if (snap == null || !snap.rebound) {
            return;
        }
        try {
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, snap.savedFramebuffer);
            GlStateManager.viewport(snap.v0, snap.v1, snap.v2, snap.v3);
        } catch (Throwable ignored) {
        }
    }
}
