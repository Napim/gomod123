package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

public final class GomodMenuTransitions {
    private static final String GOMOD_GUI_PACKAGE = "me.napim.gomod.gui.";

    private GomodMenuTransitions() {
    }

    public static boolean isGomodScreen(GuiScreen screen) {
        return screen != null && screen.getClass().getName().startsWith(GOMOD_GUI_PACKAGE);
    }

    public static void closeToGame() {
        closeToGame(Minecraft.getMinecraft());
    }

    public static void closeToGame(Minecraft mc) {
        if (mc == null) {
            return;
        }
        GomodMenuCloseAnim.trigger(mc.currentScreen);
        mc.displayGuiScreen(null);
    }

    public static void openMainMenuWithoutAnimation(Minecraft mc) {
        if (mc == null) {
            return;
        }
        mc.displayGuiScreen(GomodGUI.forInternalNavigation());
    }

    public static void returnToParentOrMainMenu(Minecraft mc, GuiScreen parent) {
        if (mc == null) {
            return;
        }
        if (parent != null) {
            if (parent instanceof GomodHudEditorGUI) {
                ((GomodHudEditorGUI) parent).clearWidgetSelection();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        openMainMenuWithoutAnimation(mc);
    }
}
