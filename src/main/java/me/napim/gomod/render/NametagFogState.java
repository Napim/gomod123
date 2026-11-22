package me.napim.gomod.render;

import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.mixins.EntityRendererFogInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/** Keeps late world nameplates under the fog Minecraft configured for this render pass. */
public final class NametagFogState {
    // glGetFloat checks for 16 remaining floats, including when reading a four-component color.
    private static final FloatBuffer FOG_COLOR_BUFFER = BufferUtils.createFloatBuffer(16);
    private static volatile Snapshot worldPassFog;

    private NametagFogState() { }

    /** Capture Minecraft's active fog before late overlays can turn it off. Only needed during Blindness. */
    public static void captureWorldPass() {
        if (!VisibleSpecs.isBlindnessNametagLimitActive()) {
            worldPassFog = null;
            return;
        }
        worldPassFog = Snapshot.capture();
    }

    /** Temporarily restore the fog captured at world-render start; returns the caller's previous fog state. */
    public static Snapshot restoreWorldPassTemporarily() {
        Snapshot previous = Snapshot.capture();
        if (VisibleSpecs.isBlindnessNametagLimitActive() && restoreVanillaFog()) {
            return previous;
        }
        Snapshot target = worldPassFog;
        if (target != null) {
            target.apply();
        }
        return previous;
    }

    private static boolean restoreVanillaFog() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.entityRenderer == null || !(mc.entityRenderer instanceof EntityRendererFogInvoker)) {
                return false;
            }
            float partialTicks = mc.timer == null ? 1.0F : mc.timer.renderPartialTicks;
            ((EntityRendererFogInvoker) mc.entityRenderer).gomod$invokeSetupFog(0, partialTicks);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public static void restore(Snapshot snapshot) {
        if (snapshot != null) {
            snapshot.apply();
        }
    }

    public static Snapshot captureCurrent() {
        return Snapshot.capture();
    }

    public static void restoreCurrent(Snapshot snapshot) {
        restore(snapshot);
    }

    public static final class Snapshot {
        private final boolean enabled;
        private final int mode;
        private final float start;
        private final float end;
        private final float density;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        private Snapshot(boolean enabled, int mode, float start, float end, float density,
                float red, float green, float blue, float alpha) {
            this.enabled = enabled;
            this.mode = mode;
            this.start = start;
            this.end = end;
            this.density = density;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        private static Snapshot capture() {
            try {
                FOG_COLOR_BUFFER.clear();
                GL11.glGetFloat(GL11.GL_FOG_COLOR, FOG_COLOR_BUFFER);
                return new Snapshot(GL11.glIsEnabled(GL11.GL_FOG), GL11.glGetInteger(GL11.GL_FOG_MODE),
                        GL11.glGetFloat(GL11.GL_FOG_START), GL11.glGetFloat(GL11.GL_FOG_END),
                        GL11.glGetFloat(GL11.GL_FOG_DENSITY), FOG_COLOR_BUFFER.get(0), FOG_COLOR_BUFFER.get(1),
                        FOG_COLOR_BUFFER.get(2), FOG_COLOR_BUFFER.get(3));
            } catch (Throwable unavailable) {
                return null;
            }
        }

        private void apply() {
            try {
                if (enabled) {
                    GlStateManager.enableFog();
                    GL11.glEnable(GL11.GL_FOG);
                } else {
                    GlStateManager.disableFog();
                    GL11.glDisable(GL11.GL_FOG);
                }
                GlStateManager.setFog(mode);
                GlStateManager.setFogStart(start);
                GlStateManager.setFogEnd(end);
                GlStateManager.setFogDensity(density);
                GL11.glFogi(GL11.GL_FOG_MODE, mode);
                GL11.glFogf(GL11.GL_FOG_START, start);
                GL11.glFogf(GL11.GL_FOG_END, end);
                GL11.glFogf(GL11.GL_FOG_DENSITY, density);
                FOG_COLOR_BUFFER.clear();
                FOG_COLOR_BUFFER.put(red).put(green).put(blue).put(alpha).flip();
                GL11.glFog(GL11.GL_FOG_COLOR, FOG_COLOR_BUFFER);
            } catch (Throwable ignored) {
            }
        }
    }
}
