package me.napim.gomod.features;

import me.napim.gomod.util.GomodResourcePackInjector;
import me.napim.gomod.util.GomodClasspathTextures;
import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Registers the Gomod-packaged spray in Lunar's native spray catalog. */
public final class LunarCustomSpray {

    public static final String NATIVE_MANAGER_CLASS =
            "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ORHOIICCIHIHCRHOCHIOHORHOCCIIO";
    public static final String NATIVE_ENTRY_CLASS =
            "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.IIHOCCORIIHOORIOHCCRHRRCCCHIHR."
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH";
    public static final String NATIVE_RESOURCE_CLASS =
            "com.moonsworth.lunar.IHHHICHHRICRCIOHCIOHHOHIOIORRH."
                    + "OIORRCRHOIHCIORCOOOORICHCIRHHI.OIORRCRHOIHCIORCOOOORICHCIRHHI";

    public static final int SPRAY_ID = 2_000_418_002;
    public static final String SPRAY_NAME = "F off";
    public static final String TEXTURE_PATH = "textures/sprays/foff_gopark2.png";
    public static final String RESOURCE_LOCATION = "gomod:" + TEXTURE_PATH;
    public static final int KEEP_YOURSELF_SAFE_SPRAY_ID = 2_000_418_003;
    public static final String KEEP_YOURSELF_SAFE_SPRAY_NAME = "Keep Yourself Safe";
    public static final String KEEP_YOURSELF_SAFE_TEXTURE_PATH = "textures/sprays/keep_yourself_safe.png";
    public static final String KEEP_YOURSELF_SAFE_RESOURCE_LOCATION = "gomod:" + KEEP_YOURSELF_SAFE_TEXTURE_PATH;

    private static final Map<Class<?>, String[]> NATIVE_LAYOUTS = new ConcurrentHashMap<Class<?>, String[]>();
    private static final AtomicBoolean WHEEL_TEXTURE_FAILURE_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean REGISTRATION_TASK_QUEUED = new AtomicBoolean();
    private static final ThreadLocal<Integer> CUSTOM_ENTRY_CONSTRUCTION_DEPTH = new ThreadLocal<Integer>();
    private static final Map<Integer, String> WHEEL_TEXTURE_DATA_URIS =
            new ConcurrentHashMap<Integer, String>();

    private LunarCustomSpray() {
    }

    /** Names and types come from the verified constructor/map/factory calls in this runtime. */
    public static void register(Object manager, String field, String entry, String resource, String factory) {
        if (manager == null) return;
        NATIVE_LAYOUTS.put(manager.getClass(), new String[]{field, entry, resource, factory});
        register(manager);
    }

    /** Called after Lunar reloads its downloaded {@code sprays.json} catalog. */
    public static void register(Object nativeManager) {
        if (nativeManager == null) {
            return;
        }

        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable failure) {
            logFailure(SPRAY_NAME, "Minecraft client unavailable: "
                    + failure.getClass().getSimpleName());
            return;
        }
        if (mc == null) {
            return;
        }
        if (!mc.isCallingFromMinecraftThread()) {
            if (!REGISTRATION_TASK_QUEUED.compareAndSet(false, true)) {
                return;
            }
            try {
                mc.addScheduledTask(() -> {
                    try {
                        registerOnClientThread(mc, nativeManager);
                    } finally {
                        REGISTRATION_TASK_QUEUED.set(false);
                    }
                });
            } catch (Throwable failure) {
                REGISTRATION_TASK_QUEUED.set(false);
                logFailure(SPRAY_NAME, "could not schedule client-thread registration: "
                        + failure.getClass().getSimpleName());
            }
            return;
        }
        registerOnClientThread(mc, nativeManager);
    }

    private static void registerOnClientThread(Minecraft mc, Object nativeManager) {
        boolean originalPresent = registerSpray(mc, nativeManager, SPRAY_ID, SPRAY_NAME,
                TEXTURE_PATH, RESOURCE_LOCATION);
        boolean safetySprayPresent = registerSpray(mc, nativeManager,
                KEEP_YOURSELF_SAFE_SPRAY_ID, KEEP_YOURSELF_SAFE_SPRAY_NAME,
                KEEP_YOURSELF_SAFE_TEXTURE_PATH, KEEP_YOURSELF_SAFE_RESOURCE_LOCATION);
        if (originalPresent || safetySprayPresent) {
            // The images are served from the Gomod resource pack, never from .lunarclient
            // or a Lunar-hosted URL.
            GomodResourcePackInjector.ensureInstalled();
        }
    }

    private static boolean registerSpray(Minecraft mc, Object nativeManager, int sprayId,
                                         String sprayName, String texturePath,
                                         String resourceLocation) {
        try {
            String[] layout = NATIVE_LAYOUTS.get(nativeManager.getClass());
            if (layout == null) throw new IllegalStateException("Native spray layout was not captured");
            Field catalogField = nativeManager.getClass().getDeclaredField(layout[0]);
            catalogField.setAccessible(true);
            Object catalog = catalogField.get(nativeManager);
            if (catalog == null) {
                throw new IllegalStateException("Lunar spray catalog map is null");
            }

            ResourceLocation sprayTexture = new ResourceLocation("gomod", texturePath);
            if (!GomodClasspathTextures.loadPackagedTextureDirect(mc, sprayTexture, sprayTexture)) {
                throw new IllegalStateException("packaged spray texture could not be uploaded to the live texture manager");
            }

            Method get = catalog.getClass().getMethod("get", int.class);
            Object existing = get.invoke(catalog, sprayId);
            if (existing != null) {
                Object existingName = LunarNativeCatalogRuntime.value(existing, 1);
                if (!sprayName.equals(existingName)) {
                    logFailure(sprayName, "reserved spray ID collision; custom entry was not installed");
                    return false;
                }
                return true;
            }

            ClassLoader loader = nativeManager.getClass().getClassLoader();
            Class<?> resourceType = Class.forName(layout[2], false, loader);
            Object resource = resourceType.getMethod(layout[3], String.class)
                    .invoke(null, resourceLocation);

            Class<?> entryType = Class.forName(layout[1], false, loader);
            Constructor<?> constructor = entryType.getDeclaredConstructor(
                    int.class, String.class, resourceType, int.class,
                    float.class, float.class, float.class, float.class,
                    boolean.class, boolean.class, boolean.class, int.class);
            constructor.setAccessible(true);
            Object spray;
            beginCustomEntryConstruction();
            try {
                spray = constructor.newInstance(
                        sprayId, sprayName, resource, 0xFFFFFF,
                        1.0f, 1.0f, 0.0f, 0.0f,
                        false, false, false, -1);
            } finally {
                endCustomEntryConstruction();
            }

            Method put = catalog.getClass().getMethod("put", int.class, Object.class);
            put.invoke(catalog, sprayId, spray);
            System.out.println("[gomod] Registered custom Lunar spray: " + sprayName
                    + " (id=" + sprayId + ", asset=" + resourceLocation
                    + ", texture=direct-loaded)");
            return true;
        } catch (Exception failure) {
            logFailure(sprayName, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    private static void logFailure(String sprayName, String reason) {
        System.err.println("[gomod] Could not register custom Lunar spray "
                + sprayName + ": " + reason);
    }

    /**
     * Scopes the namespace rewrite to our reflective entry construction. This lets the Mixin
     * use a single-argument ModifyArg hook instead of Mixin's generated Args helper, which Lunar's
     * isolated Genesis classloader cannot resolve on startup.
     */
    public static void beginCustomEntryConstruction() {
        Integer depth = CUSTOM_ENTRY_CONSTRUCTION_DEPTH.get();
        CUSTOM_ENTRY_CONSTRUCTION_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void endCustomEntryConstruction() {
        Integer depth = CUSTOM_ENTRY_CONSTRUCTION_DEPTH.get();
        if (depth == null || depth <= 1) {
            CUSTOM_ENTRY_CONSTRUCTION_DEPTH.remove();
        } else {
            CUSTOM_ENTRY_CONSTRUCTION_DEPTH.set(depth - 1);
        }
    }

    public static boolean isConstructingCustomEntry() {
        Integer depth = CUSTOM_ENTRY_CONSTRUCTION_DEPTH.get();
        return depth != null && depth > 0;
    }

    public static String rewriteNamespace(String namespace) {
        return "lunar-jit".equals(namespace) && isConstructingCustomEntry() ? "gomod" : namespace;
    }

    public static JsonElement wheelEntry(JsonElement json, Object entry) {
        if (json == null || !json.isJsonObject() || entry == null) return json;
        try {
            int id = ((Number) LunarNativeCatalogRuntime.value(entry, 0)).intValue();
            if (!isCustomSprayId(id)) return json;
            String image = getWheelTextureDataUri(id);
            if (image != null) json.getAsJsonObject().addProperty("texture", image);
        } catch (Exception failure) {
            if (WHEEL_TEXTURE_FAILURE_LOGGED.compareAndSet(false, true))
                System.err.println("[gomod] Native custom spray wheel layout unavailable: " + failure.getClass().getSimpleName());
        }
        return json;
    }

    /**
     * Lunar's spray wheel is a WebOSR page and uses the catalog's {@code texture} value as an
     * HTML image URL. Its file origin cannot read assets from this mod JAR, so expose this one
     * packaged images as cached data URIs for the UI while leaving the native ResourceLocation
     * unchanged for in-world spray rendering.
     */
    public static boolean isCustomSprayId(int sprayId) {
        return sprayId == SPRAY_ID || sprayId == KEEP_YOURSELF_SAFE_SPRAY_ID;
    }

    public static String getWheelTextureDataUri(int sprayId) {
        String texturePath;
        if (sprayId == SPRAY_ID) {
            texturePath = TEXTURE_PATH;
        } else if (sprayId == KEEP_YOURSELF_SAFE_SPRAY_ID) {
            texturePath = KEEP_YOURSELF_SAFE_TEXTURE_PATH;
        } else {
            return null;
        }

        Integer cacheKey = Integer.valueOf(sprayId);
        String cached = WHEEL_TEXTURE_DATA_URIS.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (LunarCustomSpray.class) {
            cached = WHEEL_TEXTURE_DATA_URIS.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            String resourcePath = "/assets/gomod/" + texturePath;
            try (InputStream input = LunarCustomSpray.class.getResourceAsStream(resourcePath)) {
                if (input == null) {
                    throw new IllegalStateException("packaged image is missing: " + resourcePath);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                cached = "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
                WHEEL_TEXTURE_DATA_URIS.put(cacheKey, cached);
                return cached;
            } catch (Exception failure) {
                if (WHEEL_TEXTURE_FAILURE_LOGGED.compareAndSet(false, true)) {
                    System.err.println("[gomod] Could not prepare custom Lunar spray wheel image: "
                            + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
                return null;
            }
        }
    }
}
