package me.napim.gomod.features;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;

/**
 * While chat or a GUI is open, vanilla can frustum-cull moving players until the mouse moves.
 * This fix drains LWJGL mouse deltas without moving the on-screen cursor. The old missing-player
 * redraw path is intentionally disabled because it paints live world actors into menus.
 */
public final class IdleGuiPlayerRenderFix {

    private static final Set<Integer> RENDERED_ENTITY_IDS = new HashSet<>();

    private IdleGuiPlayerRenderFix() {
    }

    public static boolean shouldApply(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        GuiScreen screen = mc.currentScreen;
        if (screen == null) {
            return false;
        }
        // Chat is an in-game overlay, not a paused/full GUI. Re-rendering "missed" players here can double-paint
        // their model, nametag, and ground shadow, making them look darker/more opaque while chat is open.
        return !(screen instanceof GuiChat);
    }

    public static boolean shouldRenderMissingPlayers(Minecraft mc) {
        // Keep the idle-GUI mouse/camera sync, but do not replay world players while menus are open.
        // That fallback draws live actors into ESC/gomod screens and makes them look like part of the GUI.
        return false;
    }

    public static void beginFrame() {
        RENDERED_ENTITY_IDS.clear();
    }

    public static void markRendered(int entityId) {
        RENDERED_ENTITY_IDS.add(entityId);
    }

    public static boolean wasRenderedThisFrame(int entityId) {
        return RENDERED_ENTITY_IDS.contains(entityId);
    }
}
