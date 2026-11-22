package me.napim.gomod.util;

import me.napim.gomod.features.KillCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads {@code gomod123:...} HUD PNGs into the {@link net.minecraft.client.renderer.texture.TextureManager}
 * from the mod JAR when the resource pack path is unavailable (common on Lunar before / without
 * {@link GomodResourcePackInjector} refresh).
 */
public final class GomodClasspathTextures {

    private static final class Entry {
        final ResourceLocation bindLocation;
        final int width;
        final int height;

        Entry(ResourceLocation bindLocation, int width, int height) {
            this.bindLocation = bindLocation;
            this.width = width;
            this.height = height;
        }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();
    /** Avoid doing classpath/resource-manager IO every frame for a genuinely unavailable texture. */
    private static final ConcurrentHashMap<String, Long> FAILED_UNTIL = new ConcurrentHashMap<String, Long>();
    /** Missing assets are useful diagnostics once; thousands of identical stderr lines are not. */
    private static final Set<String> LOGGED_MISSING = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private static final long FAILED_RETRY_MS = 30000L;
    private static volatile boolean hudPreloadQueued;
    private static volatile ResourceLocation[] hudPreloadQueue;
    private static volatile int hudPreloadCursor;
    private static final int HUD_PRELOAD_BATCH_SIZE = 4;

    private GomodClasspathTextures() {
    }

    /** Preloads common More HUD / kill-effect icons across several client ticks (avoids main-menu hitches). */
    public static void schedulePreloadHudIcons() {
        if (hudPreloadQueued) {
            return;
        }
        hudPreloadQueued = true;
        hudPreloadQueue = buildHudPreloadQueue();
        hudPreloadCursor = 0;
    }

    /** Call once per client tick while {@link #schedulePreloadHudIcons()} is active. */
    public static void tickIncrementalHudPreload(Minecraft mc) {
        if (mc == null || !hudPreloadQueued) {
            return;
        }
        ResourceLocation[] queue = hudPreloadQueue;
        if (queue == null || queue.length == 0 || hudPreloadCursor < 0) {
            hudPreloadQueued = false;
            return;
        }
        int end = Math.min(hudPreloadCursor + HUD_PRELOAD_BATCH_SIZE, queue.length);
        for (int i = hudPreloadCursor; i < end; i++) {
            ensureLoaded(mc, queue[i]);
        }
        hudPreloadCursor = end;
        if (hudPreloadCursor >= queue.length) {
            hudPreloadCursor = -1;
            hudPreloadQueued = false;
        }
    }

    private static ResourceLocation[] buildHudPreloadQueue() {
        String[] starSlugs = KillCounter.getKnownBlitzStarTextureSlugsSorted();
        ResourceLocation[] queue = new ResourceLocation[12 + (starSlugs != null ? starSlugs.length : 0)];
        int i = 0;
        queue[i++] = new ResourceLocation("gomod123", "textures/hud/potions/instant_health.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/hud/potions/instant_damage.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/hud/potions/saturation.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/hud/blitz/brewing_stand.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/flame.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/gravedigger.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/levelup.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/regeneration.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/resistance.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/speed.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/killeffects/killeffectrandom.png");
        queue[i++] = new ResourceLocation("gomod123", "textures/settingbutton.png");
        if (starSlugs != null) {
            for (String slug : starSlugs) {
                if (slug == null || slug.isEmpty()) {
                    continue;
                }
                queue[i++] = new ResourceLocation("gomod123", "textures/blitzstars/star_" + slug + ".png");
            }
        }
        if (i == queue.length) {
            return queue;
        }
        ResourceLocation[] trimmed = new ResourceLocation[i];
        System.arraycopy(queue, 0, trimmed, 0, i);
        return trimmed;
    }

    public static void preloadHudIcons(Minecraft mc) {
        if (mc == null) {
            return;
        }
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/hud/potions/instant_health.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/hud/potions/instant_damage.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/hud/potions/saturation.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/hud/blitz/brewing_stand.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/flame.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/gravedigger.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/levelup.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/regeneration.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/resistance.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/speed.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/killeffects/killeffectrandom.png"));
        ensureLoaded(mc, new ResourceLocation("gomod123", "textures/settingbutton.png"));
    }

    /** TAB QoL settings preview: kill-effect icons + all Blitz star TAB icons. */
    public static void preloadTabQoLPreviewIcons(Minecraft mc) {
        if (mc == null) {
            return;
        }
        ResourceLocation[] queue = buildHudPreloadQueue();
        for (ResourceLocation loc : queue) {
            ensureLoaded(mc, loc);
        }
    }

    public static ResourceLocation ensureLoaded(Minecraft mc, ResourceLocation packLocation) {
        if (mc == null || packLocation == null) {
            return packLocation;
        }
        String key = packLocation.toString();
        Entry hit = CACHE.get(key);
        if (hit != null) {
            return hit.bindLocation;
        }
        Long retryAt = FAILED_UNTIL.get(key);
        if (retryAt != null && System.currentTimeMillis() < retryAt.longValue()) {
            return packLocation;
        }
        synchronized (CACHE) {
            hit = CACHE.get(key);
            if (hit != null) {
                return hit.bindLocation;
            }
            retryAt = FAILED_UNTIL.get(key);
            if (retryAt != null && System.currentTimeMillis() < retryAt.longValue()) {
                return packLocation;
            }
            hit = loadEntry(mc, packLocation);
            if (hit != null) {
                CACHE.put(key, hit);
                // Callers sometimes retain ensureLoaded's generated runtime location and pass it back to
                // bindTexture later. Treat that location as an alias instead of looking for runtime/... in the JAR.
                CACHE.put(hit.bindLocation.toString(), hit);
                FAILED_UNTIL.remove(key);
                return hit.bindLocation;
            }
            FAILED_UNTIL.put(key, Long.valueOf(System.currentTimeMillis() + FAILED_RETRY_MS));
        }
        return packLocation;
    }

    public static void bindTexture(Minecraft mc, ResourceLocation packLocation) {
        if (mc == null || packLocation == null) {
            return;
        }
        ResourceLocation bind = ensureLoaded(mc, packLocation);
        try {
            mc.getTextureManager().bindTexture(bind);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Uploads a packaged image directly under the resource key a native renderer will bind.
     * This is for integrations (such as Lunar sprays) that need the texture immediately but
     * cannot rely on the Gomod pack being part of the already-created live resource manager.
     * Must be called on Minecraft's client thread because DynamicTexture performs a GL upload.
     */
    public static boolean loadPackagedTextureDirect(Minecraft mc, ResourceLocation sourceLocation,
                                                    ResourceLocation bindLocation) {
        if (mc == null || sourceLocation == null || bindLocation == null
                || !mc.isCallingFromMinecraftThread()) {
            return false;
        }
        BufferedImage image = readImageFromClasspath(sourceLocation);
        if (image == null) {
            return false;
        }
        try {
            mc.getTextureManager().loadTexture(bindLocation, new DynamicTexture(image));
            return mc.getTextureManager().getTexture(bindLocation) != null;
        } catch (Throwable t) {
            System.err.println("[gomod][classpath-tex] direct load failed " + sourceLocation
                    + ": " + t.getMessage());
            return false;
        }
    }

    public static int getWidth(ResourceLocation packLocation, int fallback) {
        Entry e = CACHE.get(packLocation != null ? packLocation.toString() : "");
        return e != null ? e.width : fallback;
    }

    public static int getHeight(ResourceLocation packLocation, int fallback) {
        Entry e = CACHE.get(packLocation != null ? packLocation.toString() : "");
        return e != null ? e.height : fallback;
    }

    private static Entry loadEntry(Minecraft mc, ResourceLocation packLocation) {
        BufferedImage img = readImageFromClasspath(packLocation);
        if (img == null) {
            if (tryResourcePack(mc, packLocation)) {
                return new Entry(packLocation, 256, 256);
            }
            return null;
        }
        ResourceLocation runtime = runtimeLocation(packLocation);
        try {
            DynamicTexture dyn = new DynamicTexture(img);
            mc.getTextureManager().loadTexture(runtime, dyn);
            return new Entry(runtime, img.getWidth(), img.getHeight());
        } catch (Throwable t) {
            System.err.println("[gomod][classpath-tex] load failed " + packLocation + ": " + t.getMessage());
            return null;
        }
    }

    private static boolean tryResourcePack(Minecraft mc, ResourceLocation loc) {
        try {
            if (mc.getResourceManager() != null) {
                IResource res = mc.getResourceManager().getResource(loc);
                if (res != null) {
                    InputStream in = res.getInputStream();
                    if (in != null) {
                        in.close();
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static BufferedImage readImageFromClasspath(ResourceLocation loc) {
        String path = "/assets/" + loc.getResourceDomain() + "/" + loc.getResourcePath();
        InputStream in = GomodClasspathTextures.class.getResourceAsStream(path);
        if (in == null) {
            in = ClasspathGomodResourcePack.class.getResourceAsStream(path);
        }
        if (in == null) {
            if (LOGGED_MISSING.add(path)) {
                System.err.println("[gomod][classpath-tex] missing on classpath: " + path);
            }
            return null;
        }
        try {
            return ImageIO.read(in);
        } catch (Throwable t) {
            System.err.println("[gomod][classpath-tex] decode failed " + path + ": " + t.getMessage());
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int[] readDimensionsFromClasspath(ResourceLocation loc) {
        BufferedImage img = readImageFromClasspath(loc);
        if (img != null) {
            return new int[] {img.getWidth(), img.getHeight()};
        }
        return new int[] {256, 256};
    }

    private static ResourceLocation runtimeLocation(ResourceLocation packLocation) {
        String path = packLocation.getResourcePath();
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        path = path.replace('\\', '/').replace('/', '_');
        return new ResourceLocation(packLocation.getResourceDomain(), "runtime/" + path);
    }
}
