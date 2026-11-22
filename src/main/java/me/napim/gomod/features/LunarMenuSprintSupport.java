package me.napim.gomod.features;

import me.napim.gomod.gui.GomodSocialGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;

/** Lets Lunar apply its own sprint input when Gomod supplies movement through a screen. */
public final class LunarMenuSprintSupport {
    private LunarMenuSprintSupport() { }

    public static boolean allowNativeSprint(boolean nativeScreenAllowed) {
        if (nativeScreenAllowed) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null || mc.thePlayer == null || mc.theWorld == null
                    || RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()
                    || GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc)) {
                return false;
            }
            if (mc.currentScreen instanceof GuiChat) {
                return ChatMovement.isMovementInputAllowedNow(mc);
            }
            if (LunarUiInputState.isOverlayContext(mc)) {
                return GomodMenuSettings.isLunarMenuControlsEnabled();
            }
            if (mc.currentScreen instanceof GomodSocialGUI
                    && ((GomodSocialGUI) mc.currentScreen).shouldAllowCompactDockMovement()) {
                return true;
            }
            if (mc.currentScreen != null
                    && mc.currentScreen.getClass().getName().startsWith("me.napim.gomod.gui.")) {
                return GomodMenuSettings.isMoveWhileMenuEnabled();
            }
            return GomodMenuSettings.isMinecraftMenuControlsEnabled()
                    && GomodMenuSettings.isSafeMinecraftMenuScreen(mc.currentScreen);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
