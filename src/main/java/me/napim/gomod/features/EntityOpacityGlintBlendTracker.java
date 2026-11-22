package me.napim.gomod.features;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Tracks enchant-glint blend transitions during self opacity / VisibleSpecs deferred renders.
 * Raw {@code GL11}/{@code GL14} hooks ({@link me.napim.gomod.mixins.Gl11GlBlendFuncMixin},
 * {@link me.napim.gomod.mixins.Gl14GlBlendFuncSeparateMixin}) record state so OptiFine paths that
 * bypass {@link GlStateManager} are still visible to {@link me.napim.gomod.mixins.GlStateManagerMixin}.
 */
public final class EntityOpacityGlintBlendTracker {

    private static final String DEBUG_PROP = "gomod.debugSelfOpacityGlint";
    /** Minecraft enchant glint: {@code tryBlendFuncSeparate(SRC_COLOR, ONE, SRC_COLOR, ONE)}. */
    private static final int GL_SRC_COLOR = 0x0300;

    private static int glintBlendDepth;
    private static int deferredFlushNesting;

    private EntityOpacityGlintBlendTracker() {}

    private static boolean debugEnabled() {
        return Boolean.parseBoolean(System.getProperty(DEBUG_PROP, "false"));
    }

    private static void dbg(String line) {
        if (debugEnabled()) {
            System.err.println("[gomod][glintBlend] " + line);
        }
    }

    public static void resetGlintBlendDepth() {
        glintBlendDepth = 0;
    }

    public static int peekDeferredFlushNesting() {
        return deferredFlushNesting;
    }

    public static int peekGlintBlendDepth() {
        return glintBlendDepth;
    }

    public static void enterDeferredOpacityFlush() {
        deferredFlushNesting++;
    }

    public static void exitDeferredOpacityFlush() {
        if (deferredFlushNesting > 0) {
            deferredFlushNesting--;
        }
    }

    public static void onEntitySimpleHeadOuterEntry() {
        resetGlintBlendDepth();
    }

    /**
     * True while first-person arm/item path should not drive depth from raw GL
     * (see {@link SelfOpacity#beginFirstPersonArm()}).
     */
    public static boolean shouldRecordRawOpenGlBlendForGlint() {
        if (SelfOpacity.isFirstPersonArmActive() || VisibleSpecs.isFirstPersonOpacityArmActive()) {
            return false;
        }
        return deferredFlushNesting > 0
                || SelfOpacity.isGlHooksActive()
                || SelfOpacity.isFirstPersonHeldItemOpacityActive()
                || VisibleSpecs.isOpacityGlHooksActive();
    }

    public static boolean isBlendTrackingActiveForOpacityGlint() {
        return deferredFlushNesting > 0
                || SelfOpacity.isGlHooksActive()
                || VisibleSpecs.isOpacityGlHooksActive()
                || SelfOpacity.isFirstPersonHeldItemOpacityActive()
                || SelfOpacity.isFirstPersonArmActive()
                || VisibleSpecs.isFirstPersonOpacityArmActive();
    }

    public static boolean hasAnyOpacityGlInterceptionState() {
        return deferredFlushNesting > 0
                || SelfOpacity.hasAnyGlInterceptionState()
                || VisibleSpecs.hasAnyGlInterceptionState();
    }

    public static boolean isOpenGlReportingGlintBlend() {
        if (!GL11.glGetBoolean(GL11.GL_BLEND)) {
            return false;
        }
        try {
            int srgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int drgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            return matchesGlintBlend(srgb, drgb, sa, da);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isEnchantGlintBlendActiveForAlphaSkip() {
        return glintBlendDepth > 0 || isOpenGlReportingGlintBlend();
    }

    public static String formatOpenGlBlendFactorsOrError() {
        if (!GL11.glGetBoolean(GL11.GL_BLEND)) {
            return "blend=OFF";
        }
        try {
            int srgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int drgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            return "blend=ON srcRGB=" + srgb + " dstRGB=" + drgb + " srcA=" + sa + " dstA=" + da;
        } catch (Throwable t) {
            return "blend=ON (query failed: " + t.getClass().getSimpleName() + ")";
        }
    }

    public static void onRawGlBlendFunc(int srcFactor, int dstFactor) {
        onRawGlBlendFuncSeparate(srcFactor, dstFactor, srcFactor, dstFactor);
    }

    public static void onRawGlBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (!shouldRecordRawOpenGlBlendForGlint()) {
            return;
        }
        if (matchesGlintBlend(srcRGB, dstRGB, srcAlpha, dstAlpha)) {
            glintBlendDepth++;
            dbg("glint++ depth=" + glintBlendDepth + " factors=" + srcRGB + "," + dstRGB + "," + srcAlpha + "," + dstAlpha);
            return;
        }
        if (matchesStandardOpacityBlend(srcRGB, dstRGB, srcAlpha, dstAlpha) && glintBlendDepth > 0) {
            glintBlendDepth--;
            dbg("glint-- depth=" + glintBlendDepth + " factors=" + srcRGB + "," + dstRGB + "," + srcAlpha + "," + dstAlpha);
        }
    }

    public static void reapplyDeferredOpacityBlendAfterCancelledDisableBlend() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    public static void restoreStandardBlendIfGlintDepthNonZero() {
        if (glintBlendDepth <= 0) {
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        glintBlendDepth = 0;
    }

    private static boolean matchesGlintBlend(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        return srcRGB == GL_SRC_COLOR
                && dstRGB == GL11.GL_ONE
                && srcAlpha == GL_SRC_COLOR
                && dstAlpha == GL11.GL_ONE;
    }

    private static boolean matchesStandardOpacityBlend(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        if (srcRGB != GL11.GL_SRC_ALPHA || dstRGB != GL11.GL_ONE_MINUS_SRC_ALPHA) {
            return false;
        }
        return (srcAlpha == GL11.GL_ONE && dstAlpha == GL11.GL_ZERO)
                || (srcAlpha == GL11.GL_SRC_ALPHA && dstAlpha == GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
}
