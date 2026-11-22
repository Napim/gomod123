package me.napim.gomod.features;

import net.minecraft.client.gui.Gui;

/** Small lifetime meter for gomod Social top-right toast notifications. */
public final class SocialToastProgressBar {
    private SocialToastProgressBar() {}

    public static void draw(int left, int bottom, int width, long showStartMs, long introMs, long holdMs, float alpha) {
        if (width <= 2 || showStartMs <= 0L) {
            return;
        }
        long total = Math.max(1L, introMs + holdMs);
        float p = Math.max(0.0F, Math.min(1.0F, (System.currentTimeMillis() - showStartMs) / (float) total));
        int bgAlpha = Math.max(0, Math.min(255, (int) (105.0F * alpha)));
        int fillAlpha = Math.max(0, Math.min(255, (int) (210.0F * alpha)));
        int y0 = bottom - 3;
        Gui.drawRect(left + 1, y0, left + width - 1, y0 + 2, (bgAlpha << 24) | 0x202020);
        Gui.drawRect(left + 1, y0, left + 1 + Math.round((width - 2) * p), y0 + 2, (fillAlpha << 24) | 0x55CCFF);
    }
}
