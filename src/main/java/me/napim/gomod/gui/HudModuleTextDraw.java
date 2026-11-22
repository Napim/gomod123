package me.napim.gomod.gui;

import net.minecraft.client.gui.FontRenderer;

/** HUD label draw with optional vanilla drop shadow. */
public final class HudModuleTextDraw {

    private HudModuleTextDraw() {
    }

    public static void draw(FontRenderer fr, String text, int x, int y, int color, boolean shadow) {
        if (fr == null || text == null) {
            return;
        }
        if (shadow) {
            fr.drawStringWithShadow(text, x, y, color);
        } else {
            fr.drawString(text, x, y, color, false);
        }
    }
}
