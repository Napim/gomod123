package me.napim.gomod.features;

/**
 * Runtime strategy for where best-armor slot highlights and (survival-inventory panel) are drawn inside
 * {@link net.minecraft.client.gui.inventory.GuiContainer}.
 *
 * Tuned via {@code /fixbestarmor <1-8>}.
 *
 * Notes:
 * - Lunar/Genesis often bakes/blocks mixins/hook points. For that case, mode 6 (GuiContainer.drawScreen tail)
 *   is the most reliable z-order test.
 * - Mode 7 is a Weave overlay fallback that can appear buried depending on client render order.
 * - Mode 8 draws per-slot overlays at {@code GuiContainer.drawSlot} HEAD to guarantee correct ordering.
 */
public final class BestArmorContainerRenderStrategy {

    /** Classic: {@code drawSlot} {@code @Inject(HEAD)} only. */
    public static final int MODE_DRAW_SLOT_HEAD = 1;
    /** Wrap {@code drawScreen}'s {@code drawSlot} invoke (redirect). */
    public static final int MODE_REDIRECT_DRAW_SCREEN = 2;
    /** Both 1 and 2 (may double-draw highlights if both hooks run). */
    public static final int MODE_BOTH = 3;
    /** After {@code GuiContainer.drawScreen} RETURN: all slots (over items). */
    public static final int MODE_POST_SCREEN_ALL_SLOTS = 4;
    /** After {@code Minecraft} finishes {@code GuiScreen.drawScreen} — works when {@code GuiContainer} mixins do not apply (Lunar). */
    public static final int MODE_MINECRAFT_AFTER_DRAW_SCREEN = 5;
    /** Bytecode hook tail of {@code GuiContainer.drawScreen} so overlays draw after slots/items (best z-order). */
    public static final int MODE_GUICONTAINER_TAIL_HOOK = 6;
    /** Weave {@code RenderGameOverlayEvent.Post} fallback (can be buried vs GUI depending on build). */
    public static final int MODE_WEAVE_OVERLAY_POST_FALLBACK = 7;
    /** Weave hook: per-slot overlay drawn at {@code GuiContainer.drawSlot} HEAD. */
    public static final int MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK = 8;

    private static volatile int current = MODE_MINECRAFT_AFTER_DRAW_SCREEN;

    private BestArmorContainerRenderStrategy() {}

    public static void loadFromConfig() {
        int m = clamp(ConfigManager.getBestArmorContainerRenderStrategy());
        // Mode 7 is a Weave-only overlay fallback and misbehaves on Forge.
        if (m == MODE_WEAVE_OVERLAY_POST_FALLBACK) {
            m = MODE_GUICONTAINER_TAIL_HOOK;
            ConfigManager.setBestArmorContainerRenderStrategy(m);
            System.out.println("[gomod] BestArmor: auto-switched mode 7 -> 6 for Forge");
        }
        current = m;
    }

    public static int get() {
        return current;
    }

    /**
     * @param mode 1-8
     * @return human-readable label for chat
     */
    public static String setAndPersist(int mode) {
        int m = clamp(mode);
        current = m;
        ConfigManager.setBestArmorContainerRenderStrategy(m);
        System.out.println("[gomod] BestArmor container render strategy set to " + m + ": " + describe(m));
        return describe(m);
    }

    public static String describe(int mode) {
        switch (clamp(mode)) {
            case MODE_DRAW_SLOT_HEAD:
                return "1 drawSlot HEAD only";
            case MODE_REDIRECT_DRAW_SCREEN:
                return "2 drawScreen→drawSlot redirect wrap";
            case MODE_BOTH:
                return "3 both HEAD + redirect";
            case MODE_POST_SCREEN_ALL_SLOTS:
                return "4 GuiContainer.drawScreen RETURN (all slots on top)";
            case MODE_MINECRAFT_AFTER_DRAW_SCREEN:
                return "5 after GuiScreen.drawScreen in Minecraft.runTick (recommended for Lunar)";
            case MODE_GUICONTAINER_TAIL_HOOK:
                return "6 GuiContainer.drawScreen tail hook (z-order after slots/items)";
            case MODE_WEAVE_OVERLAY_POST_FALLBACK:
                return "7 Weave RenderGameOverlayEvent.Post fallback (may be buried)";
            case MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK:
                return "8 per-slot GuiContainer.drawSlot HEAD hook (recommended z-order)";
            default:
                return String.valueOf(mode);
        }
    }

    private static int clamp(int mode) {
        if (mode < MODE_DRAW_SLOT_HEAD || mode > MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK) {
            return MODE_MINECRAFT_AFTER_DRAW_SCREEN;
        }
        return mode;
    }
}
