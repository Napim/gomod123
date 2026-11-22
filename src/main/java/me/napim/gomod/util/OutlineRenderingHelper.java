package me.napim.gomod.util;

/**
 * Utility class to share outline rendering state between mixins.
 * This cannot be a mixin because mixins cannot be referenced directly via Class.forName().
 */
public class OutlineRenderingHelper {
    private static boolean isInOutlineMode = false;

    /**
     * True while drawing /glowplayer2 into the entity-outline FBO ({@code renderOutlines=true}).
     * Nametags must be skipped or the outline shader glows the nameplate too.
     */
    private static boolean suppressNametagInOutlineFramebuffer = false;

    /**
     * When set, {@link net.minecraft.client.renderer.GlStateManager#color} during
     * {@code renderOutlines} draws (suppress nametag branch) biases toward this RGB — used for
     * Player Queuer chameleon outlines saturated red.
     */
    private static int forcedChameleonOutlineTintRgb = -1;

    /** RGB color for the current entity being rendered in the outline pass. */
    private static int currentGlowColorRgb = 0xFFFFFF;

    /**
     * True while {@link net.minecraft.client.renderer.RenderGlobal} is drawing into the entity-outline FBO
     * (glowplayer, queuer, teammate glow, etc.). Used so armor layers write depth even when global depth
     * mask was cleared for the pass.
     */
    private static boolean modEntityOutlinePassActive;

    public static void setOutlineMode(boolean enabled) {
        isInOutlineMode = enabled;
    }

    public static boolean isInOutlineMode() {
        return isInOutlineMode;
    }

    public static void setSuppressNametagInOutlineFramebuffer(boolean enabled) {
        suppressNametagInOutlineFramebuffer = enabled;
    }

    public static boolean shouldSuppressNametagInOutlineFramebuffer() {
        return suppressNametagInOutlineFramebuffer;
    }

    public static void setForcedChameleonOutlineTintRgb(int rgbOrMinusOne) {
        forcedChameleonOutlineTintRgb = (rgbOrMinusOne < 0) ? -1 : (rgbOrMinusOne & 0xFFFFFF);
    }

    public static boolean hasForcedChameleonOutlineTint() {
        return forcedChameleonOutlineTintRgb >= 0;
    }

    public static int getForcedChameleonOutlineTintRgb() {
        return forcedChameleonOutlineTintRgb >= 0 ? forcedChameleonOutlineTintRgb : 0xFFFFFF;
    }

    /** Snapshot for {@link me.napim.gomod.features.GlowPlayer#buildOutlinePipelineBugReport()}. */
    public static void setModEntityOutlinePassActive(boolean active) {
        modEntityOutlinePassActive = active;
    }

    public static boolean isModEntityOutlinePassActive() {
        return modEntityOutlinePassActive;
    }

    public static String debugDumpCompact() {
        return "OutlineRenderingHelper{inOutlineMode=" + isInOutlineMode
                + ",suppressNametag=" + suppressNametagInOutlineFramebuffer
                + ",forcedChameleonTint=" + forcedChameleonOutlineTintRgb
                + ",outlineFboPass=" + modEntityOutlinePassActive
                + ",flatGlowRgb=0x" + String.format("%06X", currentGlowColorRgb & 0xFFFFFF) + "}";
    }

    /**
     * Clears leak-prone client state before {@code RenderGlobal.renderEntities} runs the normal
     * world pass (and again defensively after our outline FBO pass).
     */
    public static void resetLeakProneOutlineClientState() {
        ForcedSolidTintTextureEnv.forceReset();
        suppressNametagInOutlineFramebuffer = false;
        isInOutlineMode = false;
        forcedChameleonOutlineTintRgb = -1;
        modEntityOutlinePassActive = false;
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null && mc.getRenderManager() != null) {
                mc.getRenderManager().setRenderOutlines(false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Called by RenderGlobalMixin before each entity's renderEntitySimple so that
     *  RendererLivingEntityMixin can re-apply the correct flat glow color after the
     *  entity renderer's internal getColorMultiplier() call resets it. */
    public static void setGlowColorRgb(int rgb) {
        currentGlowColorRgb = rgb;
    }

    public static int getGlowColorRgb() {
        return currentGlowColorRgb;
    }
}
