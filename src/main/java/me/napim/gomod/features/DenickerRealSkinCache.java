package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.util.MojangProfileApi;
import me.napim.gomod.util.PlayerSkinTextures;
import me.napim.gomod.features.TabDuplicateNickResolver;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches real Mojang skins for denicked players when
 * {@link ConfigManager#isDenickerRevealRealSkinEnabled()} is on.
 */
public final class DenickerRealSkinCache {
    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 12000;
    private static final Map<String, CachedSkin> BY_REAL_NAME = new ConcurrentHashMap<>();
    private static final Map<String, CachedSkin> BY_MOJANG_UUID = new ConcurrentHashMap<>();

    private DenickerRealSkinCache() {
    }

    public static void ensurePrefetchMojangUuid(UUID mojangUuid, String fallbackName) {
        if (mojangUuid == null) {
            return;
        }
        String uuidKey = normalizeUuid(mojangUuid);
        CachedSkin cached = BY_MOJANG_UUID.get(uuidKey);
        if (cached != null && (cached.skinLocation != null || cached.failed)) {
            return;
        }
        scheduleLoadForMojangUuid(mojangUuid, fallbackName);
    }

    public static boolean isLoadingForMojangUuid(UUID mojangUuid) {
        if (mojangUuid == null) {
            return false;
        }
        CachedSkin cached = BY_MOJANG_UUID.get(normalizeUuid(mojangUuid));
        return cached != null && cached.loading;
    }

    public static boolean isDefaultPlayerSkin(ResourceLocation skin) {
        if (skin == null) {
            return true;
        }
        String domain = skin.getResourceDomain();
        String path = skin.getResourcePath();
        if (domain == null || path == null || !"minecraft".equalsIgnoreCase(domain)) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        return lowerPath.endsWith("steve.png") || lowerPath.endsWith("alex.png");
    }

    public static void ensurePrefetchRealName(String realName) {
        if (realName == null || realName.trim().isEmpty()) {
            return;
        }
        resolveSkinForRealName(realName.trim());
    }

    public static ResourceLocation resolveSkinForRealName(String realName) {
        if (realName == null || realName.trim().isEmpty()) {
            return null;
        }
        String key = realName.trim().toLowerCase(Locale.ROOT);
        CachedSkin cached = BY_REAL_NAME.get(key);
        if (cached != null && cached.skinLocation != null) {
            return cached.skinLocation;
        }
        scheduleLoadForRealName(realName.trim());
        return DefaultPlayerSkin.getDefaultSkin(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + realName.trim()).getBytes(StandardCharsets.UTF_8)));
    }

    public static String resolveSkinTypeForRealName(String realName) {
        if (realName == null || realName.trim().isEmpty()) {
            return "default";
        }
        String key = realName.trim().toLowerCase(Locale.ROOT);
        CachedSkin cached = BY_REAL_NAME.get(key);
        if (cached != null && cached.skinType != null && !cached.skinType.isEmpty()) {
            return cached.skinType;
        }
        return DefaultPlayerSkin.getSkinType(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + realName.trim()).getBytes(StandardCharsets.UTF_8)));
    }

    public static ResourceLocation resolveSkinForMojangUuid(UUID mojangUuid, String fallbackName) {
        if (mojangUuid == null) {
            return null;
        }
        String uuidKey = normalizeUuid(mojangUuid);
        CachedSkin cached = BY_MOJANG_UUID.get(uuidKey);
        if (cached != null && cached.skinLocation != null) {
            return cached.skinLocation;
        }
        scheduleLoadForMojangUuid(mojangUuid, fallbackName);
        return DefaultPlayerSkin.getDefaultSkin(mojangUuid);
    }

    /** Returns a cached official Mojang cape texture, or starts loading it and returns null for now. */
    public static ResourceLocation resolveCapeForMojangUuid(UUID mojangUuid, String fallbackName) {
        if (mojangUuid == null) {
            return null;
        }
        CachedSkin cached = BY_MOJANG_UUID.get(normalizeUuid(mojangUuid));
        if (cached != null && cached.capeLocation != null) {
            return cached.capeLocation;
        }
        scheduleLoadForMojangUuid(mojangUuid, fallbackName);
        return null;
    }

    public static String resolveSkinTypeForMojangUuid(UUID mojangUuid, String fallbackName) {
        if (mojangUuid == null) {
            return "default";
        }
        String uuidKey = normalizeUuid(mojangUuid);
        CachedSkin cached = BY_MOJANG_UUID.get(uuidKey);
        if (cached != null && cached.skinType != null && !cached.skinType.isEmpty()) {
            return cached.skinType;
        }
        return DefaultPlayerSkin.getSkinType(mojangUuid);
    }

    private static void scheduleLoadForRealName(String realName) {
        String key = realName.toLowerCase(Locale.ROOT);
        CachedSkin existing = BY_REAL_NAME.get(key);
        if (existing != null && (existing.loading || existing.skinLocation != null || existing.failed)) {
            return;
        }
        BY_REAL_NAME.put(key, CachedSkin.loading());
        Thread loader = new Thread(() -> loadForRealName(realName), "gomod-denicker-skin-" + key);
        loader.setDaemon(true);
        loader.start();
    }

    private static void scheduleLoadForMojangUuid(UUID mojangUuid, String fallbackName) {
        String uuidKey = normalizeUuid(mojangUuid);
        CachedSkin existing = BY_MOJANG_UUID.get(uuidKey);
        if (existing != null && (existing.loading || existing.skinLocation != null || existing.failed)) {
            return;
        }
        BY_MOJANG_UUID.put(uuidKey, CachedSkin.loading());
        Thread loader = new Thread(() -> loadForMojangUuid(mojangUuid, fallbackName), "gomod-denicker-skin-" + uuidKey);
        loader.setDaemon(true);
        loader.start();
    }

    private static void loadForRealName(String realName) {
        try {
            MojangUsernameResolver.MojangProfile profile = MojangUsernameResolver.resolveProfile(realName);
            if (profile == null || profile.id == null || profile.id.isEmpty()) {
                BY_REAL_NAME.put(realName.toLowerCase(Locale.ROOT), CachedSkin.failed());
                return;
            }
            UUID uuid = parseUndashedUuid(profile.id);
            if (uuid == null) {
                BY_REAL_NAME.put(realName.toLowerCase(Locale.ROOT), CachedSkin.failed());
                return;
            }
            loadAndApplyForUuid(uuid, profile.name != null ? profile.name : realName);
        } catch (Throwable ignored) {
            BY_REAL_NAME.put(realName.toLowerCase(Locale.ROOT), CachedSkin.failed());
        }
    }

    private static void loadForMojangUuid(UUID mojangUuid, String fallbackName) {
        try {
            loadAndApplyForUuid(mojangUuid, fallbackName);
        } catch (Throwable ignored) {
            BY_MOJANG_UUID.put(normalizeUuid(mojangUuid), CachedSkin.failed());
        }
    }

    private static void loadAndApplyForUuid(UUID uuid, String fallbackName) {
        SkinPayload payload = fetchSkinPayload(uuid);
        if (payload == null) {
            BY_MOJANG_UUID.put(normalizeUuid(uuid), CachedSkin.failed());
            if (fallbackName != null && !fallbackName.trim().isEmpty()) {
                BY_REAL_NAME.put(fallbackName.trim().toLowerCase(Locale.ROOT), CachedSkin.failed());
            }
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> applySkinPayload(uuid, fallbackName, payload));
    }

    private static void applySkinPayload(UUID uuid, String fallbackName, SkinPayload payload) {
        ResourceLocation skinLocation = registerDynamicSkin(uuid, payload.image);
        if (skinLocation == null) {
            CachedSkin failed = CachedSkin.failed();
            BY_MOJANG_UUID.put(normalizeUuid(uuid), failed);
            if (fallbackName != null && !fallbackName.trim().isEmpty()) {
                BY_REAL_NAME.put(fallbackName.trim().toLowerCase(Locale.ROOT), failed);
            }
            return;
        }
        ResourceLocation capeLocation = registerDynamicCape(uuid, payload.capeImage);
        CachedSkin loaded = new CachedSkin(skinLocation, payload.skinType, capeLocation, false, false);
        BY_MOJANG_UUID.put(normalizeUuid(uuid), loaded);
        if (fallbackName != null && !fallbackName.trim().isEmpty()) {
            BY_REAL_NAME.put(fallbackName.trim().toLowerCase(Locale.ROOT), loaded);
            pushLoadedSkinToNetworkPlayerInfos(fallbackName.trim(), loaded);
        }
    }

    private static void pushLoadedSkinToNetworkPlayerInfos(String realName, CachedSkin loaded) {
        if (realName == null || realName.isEmpty() || loaded == null || loaded.skinLocation == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String resolved = TabDuplicateNickResolver.resolveRealNameForDenicker(
                    info.getGameProfile().getId(), info.getGameProfile().getName());
            if (resolved == null || !resolved.equalsIgnoreCase(realName)) {
                continue;
            }
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setLocationSkin(loaded.skinLocation);
            accessor.gomod$setSkinType(loaded.skinType == null ? "default" : loaded.skinType);
            accessor.gomod$setPlayerTexturesLoaded(true);
        }
    }

    private static SkinPayload fetchSkinPayload(UUID uuid) {
        JsonObject sessionProfile = fetchJson(MojangProfileApi.profileJsonUrl(normalizeUuid(uuid)));
        if (sessionProfile == null) {
            return null;
        }
        String skinUrl = null;
        String capeUrl = null;
        String skinType = DefaultPlayerSkin.getSkinType(uuid);
        if (sessionProfile.has("properties")) {
            JsonArray properties = sessionProfile.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject property = element.getAsJsonObject();
                if (!property.has("name") || !"textures".equals(property.get("name").getAsString())
                        || !property.has("value")) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(property.get("value").getAsString()),
                        StandardCharsets.UTF_8);
                JsonObject texturesRoot = new JsonParser().parse(decoded).getAsJsonObject();
                if (!texturesRoot.has("textures") || !texturesRoot.get("textures").isJsonObject()) {
                    break;
                }
                JsonObject textures = texturesRoot.getAsJsonObject("textures");
                if (textures.has("SKIN") && textures.get("SKIN").isJsonObject()) {
                    JsonObject skin = textures.getAsJsonObject("SKIN");
                    if (skin.has("url")) {
                        skinUrl = skin.get("url").getAsString();
                    }
                    if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                        JsonObject metadata = skin.getAsJsonObject("metadata");
                        if (metadata.has("model")) {
                            skinType = metadata.get("model").getAsString();
                        }
                    }
                }
                if (textures.has("CAPE") && textures.get("CAPE").isJsonObject()) {
                    JsonObject cape = textures.getAsJsonObject("CAPE");
                    if (cape.has("url")) {
                        capeUrl = cape.get("url").getAsString();
                    }
                }
                break;
            }
        }
        if (skinUrl == null || skinUrl.isEmpty()) {
            return null;
        }
        BufferedImage image = downloadImage(skinUrl);
        if (image == null) {
            return null;
        }
        BufferedImage capeImage = capeUrl == null || capeUrl.isEmpty() ? null : downloadImage(capeUrl);
        return new SkinPayload(image, skinType, capeImage);
    }

    private static ResourceLocation registerDynamicSkin(UUID uuid, BufferedImage image) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null || image == null) {
                return null;
            }
            BufferedImage upgraded = PlayerSkinTextures.upgradeLegacySkin(image);
            ResourceLocation location = new ResourceLocation("gomod123", "denicker_real/" + normalizeUuid(uuid));
            mc.getTextureManager().loadTexture(location, new DynamicTexture(upgraded));
            return location;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation registerDynamicCape(UUID uuid, BufferedImage image) {
        if (image == null) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return null;
            }
            ResourceLocation location = new ResourceLocation("gomod123", "denicker_cape/" + normalizeUuid(uuid));
            mc.getTextureManager().loadTexture(location, new DynamicTexture(image));
            return location;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BufferedImage downloadImage(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestProperty("User-Agent", "gomod123-denicker-skin");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static JsonObject fetchJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setRequestProperty("User-Agent", "gomod123-denicker-skin");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader).getAsJsonObject();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static UUID parseUndashedUuid(String undashed) {
        if (undashed == null || undashed.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(undashed.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeUuid(UUID uuid) {
        return uuid == null ? "" : uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static final class SkinPayload {
        private final BufferedImage image;
        private final String skinType;
        private final BufferedImage capeImage;

        private SkinPayload(BufferedImage image, String skinType, BufferedImage capeImage) {
            this.image = image;
            this.skinType = skinType == null || skinType.isEmpty() ? "default" : skinType;
            this.capeImage = capeImage;
        }
    }

    private static final class CachedSkin {
        private final ResourceLocation skinLocation;
        private final String skinType;
        private final ResourceLocation capeLocation;
        private final boolean loading;
        private final boolean failed;

        private CachedSkin(ResourceLocation skinLocation, String skinType, ResourceLocation capeLocation,
                           boolean loading, boolean failed) {
            this.skinLocation = skinLocation;
            this.skinType = skinType;
            this.capeLocation = capeLocation;
            this.loading = loading;
            this.failed = failed;
        }

        private static CachedSkin loading() {
            return new CachedSkin(null, null, null, true, false);
        }

        private static CachedSkin failed() {
            return new CachedSkin(null, null, null, false, true);
        }
    }
}
