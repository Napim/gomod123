package me.napim.gomod.features;

import me.napim.gomod.gui.GomodMenuKeyboardCaptureGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

public final class GomodMenuSettings {
    private GomodMenuSettings() {}

    /**
     * When a gomod screen implements {@link GomodMenuKeyboardCaptureGuard} and reports active text/key capture,
     * movement sync and drag-look must not run (otherwise WASD moves the player and keys conflict with binds).
     */
    public static boolean shouldSuppressMoveWhileMenuAndDragLook(Minecraft mc) {
        if (LunarUiInputState.isOverlayContext(mc) && LunarUiInputState.isTextInputFocused()) {
            return true;
        }
        if (mc == null || mc.currentScreen == null) {
            return false;
        }
        try {
            if (mc.currentScreen instanceof GomodMenuKeyboardCaptureGuard) {
                return ((GomodMenuKeyboardCaptureGuard) mc.currentScreen).shouldBlockGomodMenuMovementAndDragLook();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isMoveWhileMenuEnabled() {
        return ConfigManager.isGomodMenuMoveEnabled();
    }

    public static void setMoveWhileMenuEnabled(boolean val) {
        ConfigManager.setGomodMenuMoveEnabled(val);
    }

    public static boolean isDragLookEnabled() {
        return ConfigManager.isGomodMenuDragLookEnabled();
    }

    public static void setDragLookEnabled(boolean val) {
        ConfigManager.setGomodMenuDragLookEnabled(val);
    }

    public static boolean isMinecraftMenuControlsEnabled() {
        return ConfigManager.isMinecraftMenuControlsEnabled();
    }

    public static void setMinecraftMenuControlsEnabled(boolean val) {
        ConfigManager.setMinecraftMenuControlsEnabled(val);
    }

    public static boolean isLunarMenuControlsEnabled() {
        return ConfigManager.isLunarMenuControlsEnabled();
    }

    public static void setLunarMenuControlsEnabled(boolean val) {
        ConfigManager.setLunarMenuControlsEnabled(val);
    }

    /**
     * Shared eligibility check for menu drag-look and input hooks that could consume its mouse deltas first.
     */
    public static boolean isMenuDragLookEnabled(Minecraft mc) {
        if (mc == null || mc.currentScreen == null || !isDragLookEnabled()) {
            return false;
        }
        String screenClass = mc.currentScreen.getClass().getName();
        boolean gomodMenu = screenClass.startsWith("me.napim.gomod.gui.")
                && isMoveWhileMenuEnabled()
                && !screenClass.equals("me.napim.gomod.gui.GomodHudEditorGUI");
        boolean minecraftMenu = isMinecraftMenuControlsEnabled()
                && isSafeMinecraftMenuScreen(mc.currentScreen);
        return (gomodMenu || minecraftMenu)
                && !shouldSuppressMoveWhileMenuAndDragLook(mc);
    }

    /** Vanilla menu eligibility shared by movement and right-drag camera control. */
    public static boolean isSafeMinecraftMenuScreen(GuiScreen screen) {
        if (screen == null || screen instanceof GuiContainer || screen instanceof GuiChat) {
            return false;
        }
        String className = screen.getClass().getName();
        if (!className.startsWith("net.minecraft.client.gui.")) {
            return false;
        }
        // These are text-entry/gameplay interaction screens rather than ordinary menus.
        return !className.endsWith("GuiEditSign")
                && !className.endsWith("GuiScreenBook")
                && !className.endsWith("GuiCommandBlock")
                // The controls screen is actively recording a replacement key; forwarding there would fire it.
                && !className.endsWith("GuiControls");
    }
}
