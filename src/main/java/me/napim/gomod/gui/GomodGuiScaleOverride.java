package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Optional per-user GUI scale for gomod screens.
 * The player's real GUI scale setting is restored as soon as they leave gomod screens.
 */
public final class GomodGuiScaleOverride {
    private static boolean active;
    private static int savedGuiScale;

    private GomodGuiScaleOverride() {
    }

    public static void beforeDisplayGuiScreen(Minecraft mc, GuiScreen nextScreen) {
        if (mc == null || mc.gameSettings == null) {
            return;
        }

        boolean nextIsGomod = isGomodScreen(nextScreen);
        if (!nextIsGomod) {
            restore(mc);
            return;
        }

        int gomodScale = ConfigManager.getGomodMenuGuiScale();
        if (gomodScale < 0) {
            // Inherited mode must never leave an older explicit override active.
            restore(mc);
            return;
        }

        int minecraftGuiScale = getMinecraftGuiScaleSetting(mc);
        if (minecraftGuiScale == gomodScale) {
            // There is no useful distinction between an explicit Large and Minecraft (Large).
            // Collapse it to inherited mode so the choice appears only once and follows later changes.
            ConfigManager.setGomodMenuGuiScale(-1);
            restore(mc);
            return;
        }

        if (active) {
            return;
        }

        savedGuiScale = mc.gameSettings.guiScale;
        mc.gameSettings.guiScale = gomodScale;
        active = true;
    }

    public static void restore(Minecraft mc) {
        if (!active || mc == null || mc.gameSettings == null) {
            return;
        }
        mc.gameSettings.guiScale = savedGuiScale;
        active = false;
    }

    /** The player's real Minecraft setting, even while a gomod override is active. */
    public static int getMinecraftGuiScaleSetting(Minecraft mc) {
        if (active) {
            return savedGuiScale;
        }
        return mc == null || mc.gameSettings == null ? 0 : mc.gameSettings.guiScale;
    }

    private static boolean isGomodScreen(GuiScreen screen) {
        return screen != null && screen.getClass().getName().startsWith("me.napim.gomod.gui.");
    }
}
