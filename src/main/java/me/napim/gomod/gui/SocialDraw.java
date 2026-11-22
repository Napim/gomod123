package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

/**
 * Modern primitives for the Social UI: rounded rects, soft shadows, gradients,
 * accent glows, and pill outlines. Uses raw GL11 because vanilla 1.8.9
 * {@link net.minecraft.client.gui.Gui#drawRect} only paints axis-aligned solid
 * rectangles.
 *
 * <p>The rounded-rect implementation splits each shape into 3 quads + 4 corner
 * fans — the same as before. {@code GL_POLYGON_SMOOTH} is intentionally
 * <em>not</em> enabled: that flag anti-aliases every polygon edge against
 * transparent rather than compositing same-coloured neighbours, which is what
 * caused the visible seam lines in earlier builds.
 */
public final class SocialDraw {

    private SocialDraw() {}

    private static final int CORNER_SEGS = 12;
    private static final int CIRCLE_SEGS = 32;
    private static boolean scissorActive;

    // ---------------------------------------------------------------- scissor

    public static void enableScissor(int guiLeft, int guiTop, int guiRight, int guiBottom) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || guiRight <= guiLeft || guiBottom <= guiTop) return;
        IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        int vx = vp.get(0);
        int vy = vp.get(1);
        int vh = Math.max(1, vp.get(3));
        ScaledResolution sr = new ScaledResolution(mc);
        int scale = Math.max(1, sr.getScaleFactor());
        int sh    = Math.max(1, sr.getScaledHeight());
        int x = guiLeft  * scale;
        int w = Math.max(1, (guiRight  - guiLeft)  * scale);
        int h = Math.max(1, (guiBottom - guiTop)   * scale);
        int y = (sh - guiBottom) * scale;
        if (vy != 0) y = vy + (vh - guiBottom * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(vx + x, y, w, h);
        scissorActive = true;
    }

    public static void disableScissor() {
        if (scissorActive) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scissorActive = false;
        }
    }

    // --------------------------------------------------------------- shapes

    /**
     * Solid-colour rounded rectangle.
     * Drawn as three quads (centre cross) plus four corner fans.
     * No polygon-smoothing — seams between same-colour polygons are invisible.
     */
    public static void roundedRect(float l, float t, float r, float b, float radius, int argb) {
        if (r <= l || b <= t) return;
        float maxR = Math.min((r - l) * 0.5f, (b - t) * 0.5f);
        if (radius > maxR) radius = maxR;
        if (radius <= 0.5f) { fillRect(l, t, r, b, argb); return; }
        prepare();
        applyColor(argb);
        // Centre cross
        quad(l + radius, t,          r - radius, b         );
        quad(l,          t + radius, l + radius, b - radius);
        quad(r - radius, t + radius, r,          b - radius);
        // Corner fans
        cornerFan(l + radius, t + radius, radius, 180, 270);
        cornerFan(r - radius, t + radius, radius, 270, 360);
        cornerFan(r - radius, b - radius, radius,   0,  90);
        cornerFan(l + radius, b - radius, radius,  90, 180);
        restore();
    }

    /** Alias — flat fill, no border ring. */
    public static void roundedFill(float l, float t, float r, float b, float radius, int fill) {
        roundedRect(l, t, r, b, radius, fill);
    }

    /**
     * Rounded rect with a 1 px border drawn as a 1 px inset second rect.
     * Pass {@code border == 0} to skip the border.
     */
    public static void roundedBorder(float l, float t, float r, float b, float radius, int fill, int border) {
        if (r <= l || b <= t) return;
        if (border == 0) { roundedRect(l, t, r, b, radius, fill); return; }
        roundedRect(l, t, r, b, radius, border);
        float inset = 1f;
        roundedRect(l + inset, t + inset, r - inset, b - inset,
                Math.max(0f, radius - inset), fill);
    }

    /** Filled axis-aligned rectangle. */
    public static void fillRect(float l, float t, float r, float b, int argb) {
        if (r <= l || b <= t) return;
        prepare();
        applyColor(argb);
        quad(l, t, r, b);
        restore();
    }

    /** Vertical colour gradient top→bottom. */
    public static void verticalGradient(float l, float t, float r, float b, int top, int bottom) {
        if (r <= l || b <= t) return;
        prepare();
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glBegin(GL11.GL_QUADS);
        color(top);    GL11.glVertex2f(r, t); GL11.glVertex2f(l, t);
        color(bottom); GL11.glVertex2f(l, b); GL11.glVertex2f(r, b);
        GL11.glEnd();
        GL11.glShadeModel(GL11.GL_FLAT);
        restore();
    }

    /**
     * Vertical gradient clipped to rounded top corners. Unlike drawing a gradient only through the
     * centre strip, this follows the corner arcs and does not leave solid-colour bands at the sides.
     */
    public static void roundedTopGradient(float l, float t, float r, float b, float radius,
                                          int top, int bottom) {
        if (r <= l || b <= t) return;
        float maxR = Math.min((r - l) * 0.5F, b - t);
        float rad = Math.max(0.0F, Math.min(radius, maxR));
        int steps = Math.max(1, (int) Math.ceil(b - t));
        prepare();
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i < steps; i++) {
            float y0 = t + (b - t) * i / steps;
            float y1 = t + (b - t) * (i + 1) / steps;
            float yMid = (y0 + y1) * 0.5F;
            float inset = 0.0F;
            if (rad > 0.0F && yMid < t + rad) {
                float dy = yMid - (t + rad);
                inset = rad - (float) Math.sqrt(Math.max(0.0F, rad * rad - dy * dy));
            }
            int col = blend(top, bottom, (yMid - t) / Math.max(0.001F, b - t));
            color(col);
            GL11.glVertex2f(l + inset, y0);
            GL11.glVertex2f(r - inset, y0);
            GL11.glVertex2f(r - inset, y1);
            GL11.glVertex2f(l + inset, y1);
        }
        GL11.glEnd();
        restore();
    }

    /** Rounded vertical gradient (simplified: fill bottom, blend top half). */
    public static void roundedGradient(float l, float t, float r, float b, float radius, int top, int bottom) {
        roundedRect(l, t, r, b, radius, bottom);
        verticalGradient(l + radius, t, r - radius, t + (b - t) * 0.5f, top, transparent(blend(top, bottom, 0f)));
    }

    /**
     * Self-message bubble: flat base colour + very subtle top sheen only in the
     * flat centre strip (avoids rectangular edge artifacts at arc boundaries).
     */
    public static void roundedTopSheen(float l, float t, float r, float b, float radius, int base, int sheenTop) {
        roundedRect(l, t, r, b, radius, base);
        float strip = Math.max(0f, radius);
        float mid   = t + (b - t) * 0.45f;
        if (mid > t + 1f) {
            verticalGradient(l + strip, t + 1f, r - strip, mid,
                    withAlpha(sheenTop, 90), transparent(sheenTop));
        }
    }

    /** Flat panel fill (no extra chrome). */
    public static void bevelPanel(float l, float t, float r, float b, float radius, int fill) {
        roundedRect(l, t, r, b, radius, fill);
    }

    /** Badge/button chrome — fill + optional outline, no gradient. */
    public static void buttonChrome(float l, float t, float r, float b, float radius,
                                    int fill, int glossTop, int outline, boolean shadow) {
        if (shadow) dropShadow(l, t, r, b, 2f, SocialTheme.SHADOW);
        if (outline != 0) {
            roundedBorder(l, t, r, b, radius, fill, outline);
        } else {
            roundedRect(l, t, r, b, radius, fill);
        }
    }

    /** Pill (fully-rounded rect). */
    public static void pill(float l, float t, float r, float b, int argb) {
        roundedRect(l, t, r, b, (b - t) * 0.5f, argb);
    }

    /** Soft multi-layer drop shadow. */
    public static void dropShadow(float l, float t, float r, float b, float spread, int argb) {
        int layers = Math.max(2, (int) spread);
        int baseA = (argb >>> 24) & 0xFF;
        if (baseA == 0) baseA = 80;
        for (int i = layers; i >= 1; i--) {
            float pad   = (i / (float) layers) * spread;
            int   alpha = Math.max(2, (int) (baseA * (1f - (i - 1f) / layers) / layers));
            int   col   = (alpha << 24) | (argb & 0x00FFFFFF);
            roundedRect(l - pad, t - pad + spread * 0.4f,
                        r + pad, b + pad + spread * 0.4f, pad + 4f, col);
        }
    }

    /** Subtle 1 px top-edge highlight on panels. */
    public static void topHighlight(float l, float t, float r, float radius, int argb) {
        fillRect(l + radius, t, r - radius, t + 1f, argb);
    }

    /** Filled circle. */
    public static void circle(float cx, float cy, float radius, int argb) {
        prepare();
        applyColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int s = 0; s <= CIRCLE_SEGS; s++) {
            double a = Math.PI * 2.0 * s / CIRCLE_SEGS;
            GL11.glVertex2f(cx + (float) Math.cos(a) * radius,
                            cy + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
        restore();
    }

    /** Layered circles for smooth status indicators. */
    public static void smoothCircle(float cx, float cy, float radius, int argb) {
        int baseA = (argb >>> 24) & 0xFF;
        if (baseA == 0) baseA = 255;
        for (int ring = 2; ring >= 1; ring--) {
            float rr = radius + ring * 0.55f;
            int a = Math.max(6, (int) (baseA * 0.22f / ring));
            circle(cx, cy, rr, withAlpha(argb, a));
        }
        circle(cx, cy, radius, argb);
    }

    /** Glow halo. */
    public static void glow(float cx, float cy, float radius, int argb) {
        int layers = 6;
        int baseA = (argb >>> 24) & 0xFF;
        if (baseA == 0) baseA = 64;
        for (int i = layers; i >= 1; i--) {
            float rr = radius * (i / (float) layers);
            int a = Math.max(2, (int) (baseA * (1f - (i - 1f) / layers) / layers));
            circle(cx, cy, rr, (a << 24) | (argb & 0x00FFFFFF));
        }
    }

    /** Compat alias. */
    public static void softRoundedOutline(float l, float t, float r, float b, float radius, int fill, int stroke) {
        roundedBorder(l, t, r, b, radius, fill, stroke);
    }

    /** Compat alias. */
    public static void roundedOutline(float l, float t, float r, float b, float radius, int fill, int stroke) {
        roundedBorder(l, t, r, b, radius, fill, stroke);
    }

    // ---------------------------------------------------------------- colour

    public static int blend(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 0xFF, ab = (b >>> 24) & 0xFF;
        int ra = (a >> 16) & 0xFF, rb = (b >> 16) & 0xFF;
        int ga = (a >>  8) & 0xFF, gb = (b >>  8) & 0xFF;
        int ba =  a        & 0xFF, bb =  b        & 0xFF;
        return (((int)(aa + (ab - aa) * t)) << 24)
             | (((int)(ra + (rb - ra) * t)) << 16)
             | (((int)(ga + (gb - ga) * t)) <<  8)
             |  ((int)(ba + (bb - ba) * t));
    }

    public static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    public static int transparent(int argb) {
        return argb & 0x00FFFFFF;
    }

    // ---------------------------------------------------------------- internals

    private static void prepare() {
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        // GL_POLYGON_SMOOTH and GL_LINE_SMOOTH are intentionally NOT enabled:
        // they anti-alias each polygon edge against transparent rather than
        // compositing neighbours, which produced visible seam lines through
        // every rounded-rect in earlier builds.
    }

    private static void restore() {
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    private static void applyColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>>  8) & 0xFF) / 255f;
        float b = ( argb         & 0xFF) / 255f;
        GL11.glColor4f(r, g, b, a);
    }

    private static void color(int argb) { applyColor(argb); }

    private static void quad(float l, float t, float r, float b) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(r, t);
        GL11.glVertex2f(l, t);
        GL11.glVertex2f(l, b);
        GL11.glVertex2f(r, b);
        GL11.glEnd();
    }

    private static void cornerFan(float cx, float cy, float radius, int startDeg, int endDeg) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= CORNER_SEGS; i++) {
            double a = Math.toRadians(startDeg + (endDeg - startDeg) * (i / (double) CORNER_SEGS));
            GL11.glVertex2f(cx + (float) Math.cos(a) * radius,
                            cy + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
    }
}
