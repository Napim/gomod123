package me.napim.gomod.features;

/**
 * Distinct colors for multiple routes on the same map (ARGB for labels; RGB for GL trails).
 */
public final class ChestRoutePalette {
    /** 0xRRGGBB — first path is pink. */
    private static final int[] COLORS = {
            0xFF69B4, // hot pink
            0x3399FF, // blue
            0x33FFCC, // cyan
            0xFFDD33, // yellow
            0xCC66FF, // purple
            0xFF6633, // orange
            0x66FF66, // lime
            0xFF99CC, // light pink
            0x66CCFF,
            0xFFFFFF
    };

    private ChestRoutePalette() {
    }

    public static int rgbForIndex(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    /** Stable color from path name (same name → same color on reload). */
    public static int rgbForPathName(String pathName) {
        if (pathName == null) {
            return COLORS[0];
        }
        int h = pathName.toLowerCase().hashCode();
        return COLORS[Math.floorMod(h, COLORS.length)];
    }
}
