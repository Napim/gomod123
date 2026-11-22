package me.napim.gomod.features;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Some mods (e.g. PolyBlur Phosphor) run {@code matrixMode(GL_TEXTURE)}, push/pop on the texture stack, and never
 * switch back to modelview mode before returning. Deferred entity draws then apply transforms to the
 * wrong matrix stack (third person &quot;giant feet&quot; / zoomed mesh).
 */
public final class DeferredFlushEntityDrawMatrixGuard {

    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final float[] worldModelView = new float[16];
    private static final float[] worldProjection = new float[16];
    private static boolean hasWorldMatrices = false;

    public static final class Snap {
        final int savedMode;

        Snap(int savedMode) {
            this.savedMode = savedMode;
        }
    }

    private DeferredFlushEntityDrawMatrixGuard() {}

    /**
     * Capture the world camera matrices at {@code RenderGlobal.renderEntities} HEAD. Late opacity flushes run after
     * translucent terrain and some clients leave a modified projection/modelview there, which can draw the local body
     * as a zoomed mesh near the camera.
     */
    public static void captureWorldMatrices() {
        int mode = GL11.GL_MODELVIEW;
        boolean ok = false;
        try {
            mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        } catch (Throwable ignored) {
        }
        try {
            readMatrix(GL11.GL_MODELVIEW, GL11.GL_MODELVIEW_MATRIX, worldModelView);
            readMatrix(GL11.GL_PROJECTION, GL11.GL_PROJECTION_MATRIX, worldProjection);
            ok = true;
        } catch (Throwable ignored) {
            ok = false;
        } finally {
            try {
                GL11.glMatrixMode(mode);
                GlStateManager.matrixMode(mode);
            } catch (Throwable ignored) {
            }
            hasWorldMatrices = ok;
        }
    }

    /** Reads current matrix mode from GL, then forces {@link GL11#GL_MODELVIEW} for {@code RenderManager} draws. */
    public static Snap pushModelViewForEntityDraw() {
        int mode = GL11.GL_MODELVIEW;
        try {
            mode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        } catch (Throwable ignored) {
        }
        try {
            // Raw GL first: some mods leave GL in TEXTURE mode while GlStateManager's cached mode still says
            // MODELVIEW, so GlStateManager.matrixMode(MODELVIEW) becomes a no-op and entity transforms hit the wrong stack.
            if (hasWorldMatrices) {
                loadMatrix(GL11.GL_PROJECTION, worldProjection);
                loadMatrix(GL11.GL_MODELVIEW, worldModelView);
            } else {
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
            }
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        } catch (Throwable ignored) {
        }
        return new Snap(mode);
    }

    /**
     * Restores the saved matrix mode. If the saved mode is {@link GL11#GL_TEXTURE}, assume a mod leak and leave
     * {@link GL11#GL_MODELVIEW} active so the rest of the world pass matches vanilla expectations.
     */
    public static void popAfterEntityDraw(Snap snap) {
        if (snap == null) {
            return;
        }
        int restore = snap.savedMode;
        if (restore == GL11.GL_TEXTURE) {
            restore = GL11.GL_MODELVIEW;
        }
        try {
            GL11.glMatrixMode(restore);
            GlStateManager.matrixMode(restore);
        } catch (Throwable ignored) {
        }
    }

    private static void readMatrix(int mode, int pname, float[] out) {
        GL11.glMatrixMode(mode);
        MATRIX_BUFFER.clear();
        GL11.glGetFloat(pname, MATRIX_BUFFER);
        for (int i = 0; i < 16; i++) {
            out[i] = MATRIX_BUFFER.get(i);
        }
    }

    private static void loadMatrix(int mode, float[] values) {
        GL11.glMatrixMode(mode);
        MATRIX_BUFFER.clear();
        for (int i = 0; i < 16; i++) {
            MATRIX_BUFFER.put(values[i]);
        }
        MATRIX_BUFFER.flip();
        GL11.glLoadMatrix(MATRIX_BUFFER);
    }
}
