package me.napim.gomod.features;

import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Barrier block textures. Two styles only:
 * <ul>
 *     <li>{@link #FULL} — vanilla red-X barrier from {@code minecraft:textures/blocks/barrier.png}.</li>
 *     <li>{@link #OUTLINE} — procedural N-pixel perimeter line on every face, color + thickness driven
 *         by {@link ConfigManager}.</li>
 * </ul>
 *
 * <p>{@link #OUTLINE} is generated at upload time (per face size), so the configured color and
 * thickness can change without touching disk. A static
 * {@code /assets/gomod/textures/barrier_style_outline.png} matching the defaults still ships as a
 * fallback for the full-resource-reload path.
 */
public final class BarrierVisibilityStyles {

    /** Default outline color (pure red, fully opaque) — kept in sync with the shipped PNG. */
    public static final int DEFAULT_OUTLINE_COLOR_ARGB = 0xFFFF0000;
    /** Default outline thickness in source pixels at 16×16 base resolution. */
    public static final int DEFAULT_OUTLINE_THICKNESS = 1;
    /** Hard clamps for thickness so it can never eat the whole face / go negative. */
    public static final int MIN_OUTLINE_THICKNESS = 1;
    public static final int MAX_OUTLINE_THICKNESS = 6;

    public static final String FULL = "FULL";
    public static final String OUTLINE = "OUTLINE";

    public static final List<String> ORDER = Collections.unmodifiableList(Arrays.asList(FULL, OUTLINE));

    private static final Map<String, String> ID_TO_TITLE;
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> t = new LinkedHashMap<String, String>();
        t.put(FULL, "Vanilla");
        t.put(OUTLINE, "Edge outline");
        ID_TO_TITLE = Collections.unmodifiableMap(t);

        Map<String, String> a = new LinkedHashMap<String, String>();
        a.put("DEFAULT", FULL);
        a.put("VANILLA", FULL);
        a.put("CLASSIC", FULL);
        a.put("BORDER", OUTLINE);
        a.put("EDGE", OUTLINE);
        a.put("EDGES", OUTLINE);
        a.put("LINE", OUTLINE);
        a.put("BOX", OUTLINE);
        // Retired ids from older builds — all map to a known style so old configs keep working.
        a.put("ESP", FULL);
        a.put("WIRE", OUTLINE);
        a.put("VOID", FULL);
        a.put("PCB", FULL);
        a.put("AURA", OUTLINE);
        a.put("HAZARD", OUTLINE);
        a.put("GRID", OUTLINE);
        ALIASES = Collections.unmodifiableMap(a);
    }

    private BarrierVisibilityStyles() {}

    public static String getTitle(String id) {
        String n = normalize(id);
        String title = ID_TO_TITLE.get(n);
        return title != null ? title : n;
    }

    /** Accepts {@code outline}, {@code OUTLINE}, aliases, etc. Unknown → {@link #FULL}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return FULL;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return FULL;
        }
        String up = s.toUpperCase(Locale.ROOT);
        if (ALIASES.containsKey(up)) {
            return ALIASES.get(up);
        }
        if (ID_TO_TITLE.containsKey(up)) {
            return up;
        }
        return FULL;
    }

    public static boolean isFull(String id) {
        return FULL.equals(normalize(id));
    }

    public static String next(String current) {
        String c = normalize(current);
        int i = ORDER.indexOf(c);
        if (i < 0) {
            i = 0;
        }
        return ORDER.get((i + 1) % ORDER.size());
    }

    public static String prev(String current) {
        String c = normalize(current);
        int i = ORDER.indexOf(c);
        if (i < 0) {
            i = 0;
        }
        return ORDER.get((i + ORDER.size() - 1) % ORDER.size());
    }

    /** {@code minecraft:textures/blocks/barrier.png} for FULL, {@code gomod:textures/barrier_style_outline.png} otherwise. */
    public static ResourceLocation textureLocation(String id) {
        String n = normalize(id);
        if (FULL.equals(n)) {
            return new ResourceLocation("minecraft", "textures/blocks/barrier.png");
        }
        return new ResourceLocation("gomod", "textures/barrier_style_" + n.toLowerCase(Locale.ROOT) + ".png");
    }

    /** Classpath path the resource pack uses to override {@code minecraft:textures/blocks/barrier.png}. */
    public static String classpathOverridePng(String id) {
        String n = normalize(id);
        if (FULL.equals(n)) {
            return null;
        }
        return "/assets/gomod/textures/barrier_style_" + n.toLowerCase(Locale.ROOT) + ".png";
    }

    /** Clamp a thickness value into the supported range. */
    public static int clampThickness(int requested) {
        if (requested < MIN_OUTLINE_THICKNESS) {
            return MIN_OUTLINE_THICKNESS;
        }
        if (requested > MAX_OUTLINE_THICKNESS) {
            return MAX_OUTLINE_THICKNESS;
        }
        return requested;
    }

    /**
     * Build the OUTLINE texture for the atlas slot. Thickness is scaled with face size so it stays
     * visually proportional on HD packs (e.g. 32× face → 2× thickness).
     *
     * @param w           sprite width  (mip-0 size in the atlas)
     * @param h           sprite height (mip-0 size in the atlas)
     * @param thickness16 desired thickness expressed at the canonical 16×16 base
     * @param argb        fully-baked ARGB color (alpha encoded in the high byte)
     */
    public static BufferedImage generateOutline(int w, int h, int thickness16, int argb) {
        int t = clampThickness(thickness16);
        // Scale thickness up if the atlas sprite is larger than 16 (HD packs); clamp so we still
        // see a transparent interior even at thickness 6.
        int scale = Math.max(1, Math.min(w, h) / 16);
        int tx = Math.max(1, t * scale);
        int maxAllowed = Math.max(1, Math.min(w, h) / 2 - 1);
        if (tx > maxAllowed) {
            tx = maxAllowed;
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean onBorder = x < tx || y < tx || x >= w - tx || y >= h - tx;
                img.setRGB(x, y, onBorder ? argb : 0x00000000);
            }
        }
        return img;
    }
}
