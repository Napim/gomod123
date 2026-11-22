package me.napim.gomod.features;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * {@link net.minecraft.client.renderer.RenderGlobal#renderBlockLayer} for {@code TRANSLUCENT} is often invoked
 * while {@code GL_POLYGON_OFFSET} is enabled (vanilla / OptiFine / Patcher push it around the translucent pass).
 * Drawing deferred entities at {@code renderBlockLayer(TRANSLUCENT)} TAIL still runs inside that window, so the
 * offset biases depth and the local player can look zoomed or dominated by feet in third person.
 */
public final class DeferredFlushPolygonOffsetGuard {

    public static final class Snap {
        final float factor;
        final float units;
        final boolean fill;
        final boolean line;

        Snap(float factor, float units, boolean fill, boolean line) {
            this.factor = factor;
            this.units = units;
            this.fill = fill;
            this.line = line;
        }
    }

    private DeferredFlushPolygonOffsetGuard() {}

    /** Snapshot current offset factors/caps and disable polygon offset before {@code RenderManager#renderEntitySimple}. */
    public static Snap pushClearForEntityDraw() {
        float factor = 0f;
        float units = 0f;
        boolean fill = false;
        boolean line = false;
        try {
            factor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            units = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            fill = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            line = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_LINE);
        } catch (Throwable ignored) {
        }
        try {
            GlStateManager.disablePolygonOffset();
        } catch (Throwable ignored) {
        }
        return new Snap(factor, units, fill, line);
    }

    /** Restore caps/factors saved in {@link #pushClearForEntityDraw()} so the translucent pass can finish normally. */
    public static void popAfterEntityDraw(Snap snap) {
        if (snap == null) {
            return;
        }
        try {
            if (snap.fill || snap.line) {
                GlStateManager.doPolygonOffset(snap.factor, snap.units);
                GlStateManager.enablePolygonOffset();
            } else {
                GlStateManager.disablePolygonOffset();
            }
        } catch (Throwable ignored) {
        }
    }
}
