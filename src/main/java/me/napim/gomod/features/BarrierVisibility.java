package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.client.Minecraft;

/**
 * Client-side visible barrier blocks (ported from BarrierVisibility weave mod).
 * When enabled, barrier blocks use a normal block model instead of being invisible.
 *
 * <p>The barrier blockstate/model/texture live inside the gomod jar under
 * {@code assets/minecraft/...}; Forge auto-mounts the mod jar as a resource pack so those
 * are served without any extra plumbing — provided {@link me.napim.gomod.mixins.BlockStateMapperBarrierMixin}
 * runs (redirect on the mapper blacklist so barrier gets real models, matching the original weave mod).
 */
public final class BarrierVisibility {

    /** Hide barriers for this long after {@link #PRE_CAGE_CHAT_TRIGGER} (pregame, before cage teleport). */
    private static final long PRE_CAGE_SUPPRESS_MS = 4000L;
    private static final String PRE_CAGE_CHAT_TRIGGER = "The game starts in 2 seconds";

    private static long preCageSuppressUntilMs = 0L;

    private BarrierVisibility() {}

    /**
     * Whether barriers should stay vanilla/invisible during Blitz cage phase when
     * {@link ConfigManager#isBarrierBlocksShownInCages()} is false — sidebar "cages open"
     * countdown and a short pre-teleport window after {@link #PRE_CAGE_CHAT_TRIGGER}.
     */
    public static boolean isCageHideWindowActive(Minecraft mc) {
        if (ConfigManager.isBarrierBlocksShownInCages()) {
            return false;
        }
        return isBlitzCageContext(mc);
    }

    public static boolean isPreCageSuppressActive() {
        return System.currentTimeMillis() < preCageSuppressUntilMs;
    }

    /** Called from the chat packet handler when Hypixel prints the 2-second countdown line. */
    public static void onChatMessage(String message) {
        if (message == null || !message.contains(PRE_CAGE_CHAT_TRIGGER)) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        preCageSuppressUntilMs = System.currentTimeMillis() + PRE_CAGE_SUPPRESS_MS;
        BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
    }

    public static void resetPreCageSuppress() {
        preCageSuppressUntilMs = 0L;
    }

    /**
     * True during Blitz cage countdown (sidebar "cages open" plus the short pre-teleport window).
     * Independent of barrier visibility settings — used by block overlay and similar cage-only behavior.
     */
    public static boolean isBlitzCageContext(Minecraft mc) {
        if (isPreCageSuppressActive()) {
            return true;
        }
        return mc != null && GameContext.isBlitzCagePhaseSidebar(mc);
    }

    /**
     * Whether barrier visibility should actually render right now — master toggle plus
     * cage-phase override when {@link ConfigManager#isBarrierBlocksShownInCages()} is false.
     */
    public static boolean isEffectivelyEnabled() {
        if (!ConfigManager.isBarrierVisibilityEnabled()) {
            return false;
        }
        if (ConfigManager.isBarrierBlocksShownInCages()) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (isCageHideWindowActive(mc)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void setBarrierBlocksShownInCagesWithRendererReload(boolean shown) {
        ConfigManager.setBarrierBlocksShownInCages(shown);
        BarrierVisibilityRangeTicker.invalidate();
        BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
    }

    public static void setEnabledWithRendererReload(boolean enabled) {
        ConfigManager.setBarrierVisibilityEnabled(enabled);
        ensureClasspathPackInstalled();
        BarrierVisibilityModelFix.requestRecheck();
        BarrierVisibilityRangeTicker.invalidate();
        // Do not call RenderGlobal#loadRenderers() here — it tears down every chunk renderer
        // and freezes for seconds. One markBlockRangeForRenderUpdate over view distance is enough
        // for shouldSideBeRendered to re-run when chunks rebuild.
        BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
    }

    public static void toggleWithRendererReload() {
        setEnabledWithRendererReload(!ConfigManager.isBarrierVisibilityEnabled());
    }

    /**
     * Apply a barrier texture style ({@link BarrierVisibilityStyles}).
     *
     * <p>Fast path: {@link BarrierVisibilityAtlasHook#applyStyleNow(String)} uploads pixels
     * into the existing atlas slot (no full pack reload).
     *
     * <p>Slow fallback: if the atlas is not ready yet, {@code refreshResources()} once.
     */
    public static void setStyleWithRendererReload(String style) {
        ConfigManager.setBarrierVisibilityStyle(style);
        ensureClasspathPackInstalled();
        boolean liveSwap = BarrierVisibilityAtlasHook.applyStyleNow(ConfigManager.getBarrierVisibilityStyle());
        if (!liveSwap) {
            refreshAllResources();
            reloadChunkRenderers();
        }
    }

    /** Cycle {@link BarrierVisibilityStyles#ORDER} (used by GUI + legacy {@code /bv style}). */
    public static void toggleStyleWithRendererReload() {
        setStyleWithRendererReload(BarrierVisibilityStyles.next(ConfigManager.getBarrierVisibilityStyle()));
    }

    /** Update outline thickness and live-swap pixels (no full pack reload). Clamped 1..MAX. */
    public static void setOutlineThicknessWithRendererReload(int thickness) {
        ConfigManager.setBarrierOutlineThickness(thickness);
        ensureClasspathPackInstalled();
        boolean liveSwap = BarrierVisibilityAtlasHook.applyStyleNow(ConfigManager.getBarrierVisibilityStyle());
        if (!liveSwap) {
            refreshAllResources();
            reloadChunkRenderers();
        }
    }

    /** Update outline color (alpha forced opaque) and live-swap pixels. */
    public static void setOutlineColorWithRendererReload(int argb) {
        ConfigManager.setBarrierOutlineColorArgb(argb);
        ensureClasspathPackInstalled();
        boolean liveSwap = BarrierVisibilityAtlasHook.applyStyleNow(ConfigManager.getBarrierVisibilityStyle());
        if (!liveSwap) {
            refreshAllResources();
            reloadChunkRenderers();
        }
        if (ConfigManager.getBarrierVisibilityOpacityPercent() < 100) {
            BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
        }
    }

    /**
     * Barrier texture opacity (10–100%). Re-uploads atlas mips and re-meshes chunks so texel alpha
     * matches the cutout alpha test.
     */
    public static void setOpacityPercentWithRendererReload(int percent) {
        ConfigManager.setBarrierVisibilityOpacityPercent(percent);
        ensureClasspathPackInstalled();
        boolean liveSwap = BarrierVisibilityAtlasHook.applyStyleNow(ConfigManager.getBarrierVisibilityStyle());
        if (!liveSwap) {
            refreshAllResources();
            reloadChunkRenderers();
        }
        BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
    }

    /** Toggle proximity-based visibility mode and trigger a chunk re-mesh on the next tick. */
    public static void setRangeEnabledWithRendererReload(boolean enabled) {
        ConfigManager.setBarrierVisibilityRangeEnabled(enabled);
        BarrierVisibilityRangeTicker.invalidate();
    }

    /** Set proximity radius (1..150) and trigger a chunk re-mesh on the next tick. */
    public static void setRangeWithRendererReload(int range) {
        ConfigManager.setBarrierVisibilityRange(range);
        BarrierVisibilityRangeTicker.invalidate();
    }

    /** Toggle the "always show the barrier my crosshair is on" override and refresh the ticker. */
    public static void setShowAtCursorWithRendererReload(boolean enabled) {
        ConfigManager.setBarrierVisibilityShowAtCursor(enabled);
        BarrierVisibilityRangeTicker.invalidate();
    }

    /** Switch into / out of "Aimed only" mode and rebuild all chunks so existing meshes update. */
    public static void setAimedOnlyWithRendererReload(boolean enabled) {
        ConfigManager.setBarrierVisibilityAimedOnly(enabled);
        BarrierVisibilityRangeTicker.invalidate();
        BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
    }

    public static void reloadChunkRenderers() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.renderGlobal != null) {
                mc.renderGlobal.loadRenderers();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureClasspathPackInstalled() {
        try {
            GomodResourcePackInjector.ensureInstalled();
        } catch (Throwable ignored) {
        }
    }

    private static void refreshAllResources() {
        try {
            BarrierVisibilityAtlasHook.invalidateSavedStyleApplication();
            BarrierVisibilityAtlasHook.clearPngCache();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.isCallingFromMinecraftThread()) {
                mc.refreshResources();
            } else if (mc != null) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mc.refreshResources();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
