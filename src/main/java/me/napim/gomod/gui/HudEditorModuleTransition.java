package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudFeature;
import net.minecraft.client.Minecraft;

/**
 * Animates a HUD row from its HUD Editor anchor into the module settings preview slot
 * so the element is not drawn twice during navigation.
 */
public final class HudEditorModuleTransition {

    public static final long DURATION_MS = 300L;

    private static MoreHudSettingsGUI.Panel activePanel;
    private static int fromAnchorX;
    private static int fromAnchorY;
    private static long startTimeMs = -1L;

    private HudEditorModuleTransition() {
    }

    public static void begin(MoreHudSettingsGUI.Panel panel, int editorAnchorX, int editorAnchorY) {
        if (panel == null) {
            clear();
            return;
        }
        activePanel = panel;
        fromAnchorX = editorAnchorX;
        fromAnchorY = editorAnchorY;
        startTimeMs = System.currentTimeMillis();
    }

    public static void clear() {
        activePanel = null;
        startTimeMs = -1L;
    }

    public static boolean isActive(MoreHudSettingsGUI.Panel panel) {
        if (panel == null || activePanel != panel || startTimeMs < 0L) {
            return false;
        }
        return System.currentTimeMillis() - startTimeMs < DURATION_MS;
    }

    /**
     * While on a module settings screen (or during its entry animation), hide the in-world overlay
     * for that row so only the gallery preview is visible.
     */
    public static boolean shouldHideWorldHud(MoreHudSettingsGUI.Panel panel) {
        if (panel == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.currentScreen instanceof MoreHudSettingsGUI) {
            MoreHudSettingsGUI screen = (MoreHudSettingsGUI) mc.currentScreen;
            if (screen.getPanel() == panel) {
                return true;
            }
            if (panel == MoreHudSettingsGUI.Panel.POTIONS
                    && screen.getPanel() == MoreHudSettingsGUI.Panel.FLAME
                    && ConfigManager.isMoreHudPotionsShowFlamingArrows()) {
                return true;
            }
        }
        return isActive(panel);
    }

    public static void resolvePreviewAnchor(MoreHudSettingsGUI.Panel panel, int targetAnchorX, int targetAnchorY,
            int[] outXY) {
        if (outXY == null || outXY.length < 2) {
            return;
        }
        if (!isActive(panel)) {
            outXY[0] = targetAnchorX;
            outXY[1] = targetAnchorY;
            return;
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        float raw = MenuAnimationManager.clamp01(elapsed / (float) DURATION_MS);
        float t = MenuAnimationManager.easeOutBack(raw);
        outXY[0] = Math.round(fromAnchorX + (targetAnchorX - fromAnchorX) * t);
        outXY[1] = Math.round(fromAnchorY + (targetAnchorY - fromAnchorY) * t);
        if (raw >= 1f) {
            clear();
        }
    }

    public static void drawPreview(Minecraft mc, MoreHudSettingsGUI.Panel panel, int targetAnchorX, int targetAnchorY) {
        if (mc == null || panel == null) {
            return;
        }
        int[] anchor = new int[2];
        resolvePreviewAnchor(panel, targetAnchorX, targetAnchorY, anchor);
        MoreHudFeature.renderSettingsGalleryPreview(mc, panel, anchor[0], anchor[1]);
    }
}
