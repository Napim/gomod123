package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;

/**
 * Minecraft 1.8 / OptiFine 64×32 cape sheet: left half = back, right half = front (mirrored wing layout).
 */
public final class CapeEditorRegions {

    public static final int SHEET_W = 64;
    public static final int SHEET_H = 32;
    public static final int HALF_W = 32;

    /** Back half of the sheet (x 0–31). */
    public static final int BACK_HALF_X = 0;
    /** Front / inside half (x 32–63). */
    public static final int FRONT_HALF_X = 32;

    /** Legacy narrow strip still used by some capes; full half is the editor default. */
    public static final int BACK_STRIP_X = 1;
    public static final int BACK_STRIP_W = 10;
    public static final int FRONT_STRIP_X = 33;
    public static final int FRONT_STRIP_W = 10;

    private CapeEditorRegions() {
    }

    public static boolean isBackHalfPixel(int px, int py) {
        return py >= 0 && py < SHEET_H && px >= BACK_HALF_X && px < BACK_HALF_X + HALF_W;
    }

    public static boolean isFrontHalfPixel(int px, int py) {
        return py >= 0 && py < SHEET_H && px >= FRONT_HALF_X && px < FRONT_HALF_X + HALF_W;
    }

    public static boolean isBackWearPixel(int px, int py) {
        return CapeWearMask.isBackWearPixel(px, py);
    }

    public static boolean isFrontWearPixel(int px, int py) {
        return CapeWearMask.isFrontWearPixel(px, py);
    }

    public static boolean isWearPixel(int px, int py) {
        return CapeWearMask.isWearPixel(px, py);
    }

    public static boolean isWearPixel(int px, int py, boolean backFace) {
        return backFace ? isBackWearPixel(px, py) : isFrontWearPixel(px, py);
    }

    public static boolean isFoldPixelOnHalf(int px, int py, boolean backFace) {
        if (py < 0 || py >= SHEET_H) {
            return false;
        }
        if (backFace) {
            return isBackHalfPixel(px, py) && !isBackWearPixel(px, py);
        }
        return isFrontHalfPixel(px, py) && !isFrontWearPixel(px, py);
    }

    public static void drawChecker(int x, int y, int w, int h) {
        int cell = 6;
        for (int cy = y; cy < y + h; cy += cell) {
            for (int cx = x; cx < x + w; cx += cell) {
                boolean light = ((cx - x) / cell + (cy - y) / cell) % 2 == 0;
                Gui.drawRect(cx, cy, Math.min(cx + cell, x + w), Math.min(cy + cell, y + h),
                        light ? 0xFF2A3340 : 0xFF1A2228);
            }
        }
    }

}
