package me.napim.gomod.features;

/**
 * Simple runtime flags to detect whether key mixins are actually executing.
 * Used to enable event-based fallbacks when Lunar's Genesis bake skips mixins.
 */
public final class MixinStatus {
    private MixinStatus() {}

    /**
     * Cleared on each {@code GuiIngame.renderGameOverlay} HEAD; set when the overlay TAIL finishes.
     * {@link me.napim.gomod.features.HudRenderFallback} draws only when this stays false for the frame.
     */
    public static volatile boolean guiIngameOverlayRenderedThisFrame = false;
    /** True once the GuiIngame mixin HEAD has run; set before Forge's overlay Post event. */
    public static volatile boolean guiIngameHookActive = false;
    /** True once {@code GuiIngameMixin} overlay TAIL has completed at least once ({@code /gomod status}). */
    public static volatile boolean guiIngameActive = false;
    public static volatile boolean perspectiveRendererActive = false;
    public static volatile boolean pauseMenuActive = false;
    public static volatile boolean guiContainerActive = false;
    public static volatile boolean slotMixinActive = false;
    public static volatile boolean fontRendererActive = false;
    /** Incremented when {@link me.napim.gomod.features.WaterClarity} patches chunk water vertex alpha. */
    public static volatile long waterClarityVertexPatches = 0L;

    /**
     * When true, {@link LocalDisguise#replaceRenderedText} is not applied in {@link FontRendererMixin}.
     * Used for Hypixel prefix catalog lines that intentionally contain fabricated {@code [VIP]} / {@code [MVP]} previews:
     * generic disguise rewrite would strip those brackets when the disguise target has no rank.
     */
    public static volatile boolean skipFontRendererDisguiseReplace = false;
}
