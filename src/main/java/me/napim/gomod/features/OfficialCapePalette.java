package me.napim.gomod.features;

import me.napim.gomod.gui.CapeEditorRegions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Colors sampled from official Mojang cape textures (wear regions) for the custom cape editor.
 */
public final class OfficialCapePalette {

    private static final int MAX_COLORS = 96;
    private static final int MIN_ALPHA = 140;

    /** Fallback when capes are not downloaded yet. */
    private static final int[] FALLBACK = {
            0x00000000,
            0xFF000000, 0xFF1A1A1A, 0xFF333333, 0xFF4D4D4D, 0xFF666666, 0xFF808080, 0xFF999999,
            0xFFB3B3B3, 0xFFCCCCCC, 0xFFFFFFFF,
            0xFF8B0000, 0xFFB22222, 0xFFDC143C, 0xFFFF4500, 0xFFFF6347, 0xFFFF8C00,
            0xFFFFD700, 0xFFFFA500, 0xFFFFFF00, 0xFFADFF2F, 0xFF32CD32, 0xFF006400,
            0xFF228B22, 0xFF00FF7F, 0xFF20B2AA, 0xFF008B8B, 0xFF00CED1, 0xFF1E90FF,
            0xFF0000CD, 0xFF00008B, 0xFF4169E1, 0xFF4B0082, 0xFF800080, 0xFF9932CC,
            0xFFDA70D6, 0xFFFF00FF, 0xFFFF1493, 0xFFFF69B4, 0xFF8B4513, 0xFFA0522D,
            0xFFD2691E, 0xFFF4A460, 0xFFDEB887, 0xFFEEE8AA, 0xFFF0E68C, 0xFFE6E6FA,
    };

    private static volatile int[] cached;

    private OfficialCapePalette() {
    }

    public static int[] colorsForEditor() {
        int[] local = cached;
        if (local != null && local.length > 0) {
            return local;
        }
        synchronized (OfficialCapePalette.class) {
            if (cached != null && cached.length > 0) {
                return cached;
            }
            cached = buildPaletteLocked();
            return cached;
        }
    }

    /** Call after catalog textures may have finished downloading. */
    public static void refreshAsync() {
        Thread t = new Thread(OfficialCapePalette::rebuildQuietly, "gomod-cape-palette");
        t.setDaemon(true);
        t.start();
    }

    private static void rebuildQuietly() {
        try {
            PlayerStandaloneCape.ensureCatalogLoaded();
            int[] built = buildPaletteLocked();
            if (built.length > FALLBACK.length) {
                synchronized (OfficialCapePalette.class) {
                    cached = built;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int[] buildPaletteLocked() {
        LinkedHashSet<Integer> set = new LinkedHashSet<Integer>();
        set.add(0x00000000);
        for (int c : FALLBACK) {
            if (c != 0) {
                set.add(c);
            }
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getTextureManager() != null) {
                for (ResourceLocation rl : PlayerStandaloneCape.getOfficialCapeTextureLocationsForPalette()) {
                    sampleWearColors(mc, rl, set);
                }
            }
        } catch (Throwable ignored) {
        }
        List<Integer> list = new ArrayList<Integer>(set);
        if (list.size() > MAX_COLORS) {
            list = list.subList(0, MAX_COLORS);
        }
        list.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                if (a == 0) {
                    return -1;
                }
                if (b == 0) {
                    return 1;
                }
                int ha = hue(a);
                int hb = hue(b);
                if (ha != hb) {
                    return ha - hb;
                }
                return Integer.compare(a, b);
            }
        });
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static void sampleWearColors(Minecraft mc, ResourceLocation rl, LinkedHashSet<Integer> set) {
        if (rl == null || set.size() >= MAX_COLORS) {
            return;
        }
        BufferedImage img = loadImage(mc, rl);
        if (img == null) {
            return;
        }
        img = PlayerCustomCapes.normalizeCapeImage(img);
        int w = Math.min(CapeEditorRegions.SHEET_W, img.getWidth());
        int h = Math.min(CapeEditorRegions.SHEET_H, img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!CapeEditorRegions.isBackWearPixel(x, y) && !CapeEditorRegions.isFrontWearPixel(x, y)) {
                    continue;
                }
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < MIN_ALPHA) {
                    continue;
                }
                set.add(quantize(argb));
                if (set.size() >= MAX_COLORS) {
                    return;
                }
            }
        }
    }

    private static int quantize(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = ((argb >>> 16) & 0xFF) & 0xF8;
        int g = ((argb >>> 8) & 0xFF) & 0xF8;
        int b = (argb & 0xFF) & 0xF8;
        if (a < MIN_ALPHA) {
            a = 0;
        } else if (a < 255) {
            a = 255;
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int hue(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max == min) {
            return 0;
        }
        float h;
        float d = max - min;
        if (max == r) {
            h = (g - b) / d;
        } else if (max == g) {
            h = 2f + (b - r) / d;
        } else {
            h = 4f + (r - g) / d;
        }
        h *= 60f;
        if (h < 0) {
            h += 360f;
        }
        return (int) h;
    }

    private static BufferedImage loadImage(Minecraft mc, ResourceLocation rl) {
        ITextureObject tex = mc.getTextureManager().getTexture(rl);
        if (tex instanceof ThreadDownloadImageData) {
            return readDownloadedImage((ThreadDownloadImageData) tex);
        }
        return null;
    }

    private static BufferedImage readDownloadedImage(ThreadDownloadImageData dl) {
        for (String name : new String[]{"bufferedImage", "field_110560_d"}) {
            try {
                Field f = dl.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(dl);
                if (v instanceof BufferedImage) {
                    return (BufferedImage) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
