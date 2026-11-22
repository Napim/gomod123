package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;

/** Runs and draws Social toasts above the title screen, where the in-world HUD is hidden behind the menu. */
public final class GomodSocialMainMenuNotifications {
    private static final IncomingScreenshotOverlay SCREENSHOT = new IncomingScreenshotOverlay();
    private static final IncomingFriendRequestOverlay FRIEND_REQUEST = new IncomingFriendRequestOverlay();
    private static final IncomingFriendDmOverlay FRIEND_DM = new IncomingFriendDmOverlay();
    private static final IncomingPublicPingOverlay PUBLIC_MESSAGE = new IncomingPublicPingOverlay();
    private static final IncomingCapeDesignOverlay CAPE_DESIGN = new IncomingCapeDesignOverlay();

    private GomodSocialMainMenuNotifications() {}

    /** Tick fallback for clients that do not emit platform tick events while sitting on the title screen. */
    public static void tickIfMainMenu(Minecraft mc) {
        tickIfMenu(mc);
    }

    /** Tick fallback for every no-world menu (server list, title screen, launcher screens, and Social itself). */
    public static void tickIfMenu(Minecraft mc) {
        if (!isMenuScreen(mc, mc == null ? null : mc.currentScreen)) {
            return;
        }
        SCREENSHOT.onTick(null);
        FRIEND_REQUEST.onTick(null);
        FRIEND_DM.onTick(null);
        PUBLIC_MESSAGE.onTick(null);
        CAPE_DESIGN.onTick(null);
    }

    /** Called after {@link GuiMainMenu#drawScreen(int, int, float)} so toasts stay above the menu. */
    public static void renderIfMainMenu(GuiScreen screen) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isCurrentMainMenu(mc, screen) || mc.fontRendererObj == null) {
            return;
        }
        renderToasts(mc);
    }

    /** Draw Social toasts over any no-world GUI, including Multiplayer and the Social screen itself. */
    public static void renderIfMenu(GuiScreen screen) {
        Minecraft mc = Minecraft.getMinecraft();
        // GuiMainMenu has its own mixin hook; skip it here so it is not drawn twice.
        if (screen instanceof GuiMainMenu || !isMenuScreen(mc, screen) || mc.fontRendererObj == null) {
            return;
        }
        renderToasts(mc);
    }

    /** Shared renderer for the title-screen and generic-menu hooks. */
    private static void renderToasts(Minecraft mc) {
        if (ClientTickHub.isFeatureEnabled("IncomingScreenshotOverlay")
                && ConfigManager.isIncomingScreenshotOverlayEnabled()
                && IncomingScreenshotOverlay.isShowing()) {
            IncomingScreenshotOverlay.render(mc, false);
        }
        if (ClientTickHub.isFeatureEnabled("IncomingFriendRequestOverlay")
                && IncomingFriendRequestOverlay.isShowing()) {
            IncomingFriendRequestOverlay.render(mc);
        }
        if (ClientTickHub.isFeatureEnabled("IncomingFriendDmOverlay")
                && IncomingFriendDmOverlay.isShowing()) {
            IncomingFriendDmOverlay.render(mc);
        }
        if (ClientTickHub.isFeatureEnabled("IncomingPublicPingOverlay")
                && IncomingPublicPingOverlay.isShowing()) {
            IncomingPublicPingOverlay.render(mc);
        }
        if (ClientTickHub.isFeatureEnabled("IncomingCapeDesignOverlay")
                && IncomingCapeDesignOverlay.isShowing()) {
            IncomingCapeDesignOverlay.render(mc);
        }
    }

    private static boolean isCurrentMainMenu(Minecraft mc, GuiScreen screen) {
        return mc != null && screen instanceof GuiMainMenu && mc.currentScreen == screen;
    }

    /** True when the active GUI is a no-world menu rather than an in-game screen. */
    public static boolean isMenuScreen(Minecraft mc, GuiScreen screen) {
        return mc != null && screen != null && mc.currentScreen == screen
                && mc.theWorld == null && mc.thePlayer == null;
    }
}
