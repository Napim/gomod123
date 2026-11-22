package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches cape ownership from capes.dev for the local player or a disguise target identifier.
 */
public final class CapesDevCapeLookup {

    private static final String LOAD_URL = "https://api.capes.dev/load/";
    private static final String HISTORY_URL = "https://api.capes.dev/history/";
    private static final String[] HISTORY_TYPES = new String[]{"minecraft", "optifine", "minecraftcapes"};

    private static volatile List<Entry> localPlayerCache;
    private static volatile boolean localFetchStarted;

    private CapesDevCapeLookup() {
    }

    public static final class Entry {
        public final String type;
        public final String label;
        public final String imageUrl;
        public final String textureHash;

        public Entry(String type, String label, String imageUrl) {
            this(type, label, imageUrl, null);
        }

        public Entry(String type, String label, String imageUrl, String textureHash) {
            this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            this.label = label == null ? "Cape" : label;
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.textureHash = textureHash;
        }
    }

    /** Starts a background fetch for the logged-in player if not already started. */
    public static void warmLocalPlayerCache() {
        if (localFetchStarted) {
            return;
        }
        localFetchStarted = true;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            List<Entry> fetched = fetchForIdentifiers(localPlayerIdentifiers());
            synchronized (CapesDevCapeLookup.class) {
                localPlayerCache = fetched;
            }
        });
    }

    public static List<Entry> getCachedLocalPlayerCapes() {
        warmLocalPlayerCache();
        List<Entry> cached = localPlayerCache;
        return cached == null ? Collections.<Entry>emptyList() : cached;
    }

    public static List<Entry> fetchForIdentifiers(List<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<String, Entry>();
        for (String identifier : identifiers) {
            if (identifier == null || identifier.trim().isEmpty()) {
                continue;
            }
            mergeEntries(merged, fetchHistory(identifier.trim()));
            mergeEntries(merged, fetchCurrent(identifier.trim()));
        }
        return new ArrayList<Entry>(merged.values());
    }

    private static List<String> localPlayerIdentifiers() {
        ArrayList<String> ids = new ArrayList<String>(2);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                if (mc.thePlayer.getGameProfile().getId() != null) {
                    ids.add(mc.thePlayer.getGameProfile().getId().toString().replace("-", ""));
                }
                String name = mc.thePlayer.getGameProfile().getName();
                if (name != null && !name.trim().isEmpty()) {
                    ids.add(name.trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private static void mergeEntries(LinkedHashMap<String, Entry> target, List<Entry> entries) {
        if (target == null || entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry == null || entry.imageUrl.isEmpty()) {
                continue;
            }
            String hash = entry.textureHash != null
                    ? entry.textureHash
                    : MinecraftCapeCatalog.textureHashFromUrl(entry.imageUrl);
            String dedupe = hash != null ? entry.type + "|" + hash : entry.type + "|" + entry.imageUrl;
            if (!target.containsKey(dedupe)) {
                target.put(dedupe, entry);
            }
        }
    }

    private static List<Entry> fetchHistory(String identifier) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        for (String type : HISTORY_TYPES) {
            try {
                String responseText = HypixelApiHelper.fetchText(HISTORY_URL + identifier + "/" + type);
                JsonObject root = new JsonParser().parse(responseText).getAsJsonObject();
                if (!root.has("history") || !root.get("history").isJsonArray()) {
                    continue;
                }
                JsonArray history = root.getAsJsonArray("history");
                int labelIndex = 1;
                for (JsonElement element : history) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject capeObject = element.getAsJsonObject();
                    if (!capeObject.has("exists") || !capeObject.get("exists").getAsBoolean()) {
                        continue;
                    }
                    String resolvedType = capeObject.has("type") && !capeObject.get("type").isJsonNull()
                            ? capeObject.get("type").getAsString()
                            : type;
                    if (!isSupportedType(resolvedType)) {
                        continue;
                    }
                    String imageUrl = readImageUrl(capeObject);
                    if (imageUrl == null || imageUrl.isEmpty()) {
                        continue;
                    }
                    String label = null;
                    String textureHash = null;
                    if ("minecraft".equalsIgnoreCase(resolvedType)) {
                        MinecraftCapeCatalog.ResolvedCapeMeta meta = MinecraftCapeCatalog.resolveFromImageUrls(
                                collectResolveImageUrls(capeObject));
                        textureHash = meta.textureHash;
                        label = meta.label;
                        if (textureHash == null) {
                            textureHash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
                        }
                    } else if ("optifine".equalsIgnoreCase(resolvedType)
                            || "minecraftcapes".equalsIgnoreCase(resolvedType)) {
                        label = formatHistoricalLabel(resolvedType, labelIndex);
                        textureHash = readApiCapeHash(capeObject);
                    } else if (MinecraftCapeCatalog.isMojangStyleCapeImageUrl(imageUrl)) {
                        textureHash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
                    }
                    if (label == null || label.isEmpty()) {
                        label = formatHistoricalLabel(resolvedType, labelIndex);
                    }
                    out.add(new Entry(resolvedType, label, imageUrl, textureHash));
                    labelIndex++;
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static List<Entry> fetchCurrent(String identifier) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        try {
            String responseText = HypixelApiHelper.fetchText(LOAD_URL + identifier);
            JsonObject root = new JsonParser().parse(responseText).getAsJsonObject();
            for (Map.Entry<String, JsonElement> mapEntry : root.entrySet()) {
                if (mapEntry == null || mapEntry.getValue() == null || !mapEntry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject capeObject = mapEntry.getValue().getAsJsonObject();
                if (!capeObject.has("exists") || !capeObject.get("exists").getAsBoolean()) {
                    continue;
                }
                String type = capeObject.has("type") && !capeObject.get("type").isJsonNull()
                        ? capeObject.get("type").getAsString()
                        : mapEntry.getKey();
                if (!isSupportedType(type)) {
                    continue;
                }
                String imageUrl = readImageUrl(capeObject);
                if (imageUrl == null || imageUrl.isEmpty()) {
                    continue;
                }
                String label = null;
                String textureHash = null;
                if ("minecraft".equalsIgnoreCase(type)) {
                    MinecraftCapeCatalog.ResolvedCapeMeta meta = MinecraftCapeCatalog.resolveFromImageUrls(
                            collectResolveImageUrls(capeObject));
                    textureHash = meta.textureHash;
                    label = meta.label;
                    if (textureHash == null) {
                        textureHash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
                    }
                } else if ("optifine".equalsIgnoreCase(type) || "minecraftcapes".equalsIgnoreCase(type)) {
                    textureHash = readApiCapeHash(capeObject);
                } else if (MinecraftCapeCatalog.isMojangStyleCapeImageUrl(imageUrl)) {
                    textureHash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
                }
                if (label == null || label.isEmpty()) {
                    label = formatCurrentLabel(type);
                }
                out.add(new Entry(type, label, imageUrl, textureHash));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean isSupportedType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        switch (type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
            case "optifine":
            case "minecraftcapes":
                return true;
            default:
                return false;
        }
    }

    private static String formatCurrentLabel(String type) {
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
                return "Mojang Cape";
            case "optifine":
                return "OptiFine Cape";
            case "minecraftcapes":
                return "MinecraftCapes Cape";
            default:
                return type + " Cape";
        }
    }

    private static String formatHistoricalLabel(String type, int index) {
        String base;
        switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "minecraft":
                base = "Mojang";
                break;
            case "optifine":
                base = "OptiFine";
                return base + " " + index;
            case "minecraftcapes":
                base = "MinecraftCapes";
                break;
            default:
                base = formatCurrentLabel(type).replace(" Cape", "");
                break;
        }
        return index <= 1 ? base + " Current" : base + " " + index;
    }

    private static String readApiCapeHash(JsonObject capeObject) {
        if (capeObject == null || !capeObject.has("hash") || capeObject.get("hash").isJsonNull()) {
            return null;
        }
        String value = capeObject.get("hash").getAsString();
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String readImageUrl(JsonObject capeObject) {
        if (capeObject == null) {
            return null;
        }

        String baseUrl = readImageVariantUrl(capeObject, "base");
        if (baseUrl != null) {
            return baseUrl;
        }

        if (capeObject.has("stillImageUrl") && !capeObject.get("stillImageUrl").isJsonNull()) {
            String value = capeObject.get("stillImageUrl").getAsString();
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }

        if (capeObject.has("imageUrls") && capeObject.get("imageUrls").isJsonObject()) {
            String stillUrl = readImageVariantUrl(capeObject, "still");
            if (stillUrl != null) {
                return stillUrl;
            }
        }

        if (capeObject.has("imageUrl") && !capeObject.get("imageUrl").isJsonNull()) {
            String value = capeObject.get("imageUrl").getAsString();
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String readImageVariantUrl(JsonObject capeObject, String variant) {
        if (capeObject == null || variant == null
                || !capeObject.has("imageUrls") || !capeObject.get("imageUrls").isJsonObject()) {
            return null;
        }
        JsonObject imageUrls = capeObject.getAsJsonObject("imageUrls");
        if (!imageUrls.has(variant) || !imageUrls.get(variant).isJsonObject()) {
            return null;
        }
        JsonObject object = imageUrls.getAsJsonObject(variant);
        String value = readFieldString(object, "full");
        if (value != null) {
            return value;
        }
        value = readFieldString(object, "front");
        if (value != null) {
            return value;
        }
        return readFieldString(object, "back");
    }

    private static String[] collectResolveImageUrls(JsonObject capeObject) {
        java.util.ArrayList<String> urls = new java.util.ArrayList<String>(4);
        if (capeObject == null) {
            return new String[0];
        }
        addUrlIfPresent(urls, readFieldString(capeObject, "stillImageUrl"));
        if (capeObject.has("imageUrls") && capeObject.get("imageUrls").isJsonObject()) {
            JsonObject imageUrls = capeObject.getAsJsonObject("imageUrls");
            if (imageUrls.has("still") && imageUrls.get("still").isJsonObject()) {
                addUrlIfPresent(urls, readFieldString(imageUrls.getAsJsonObject("still"), "full"));
            }
            if (imageUrls.has("base") && imageUrls.get("base").isJsonObject()) {
                addUrlIfPresent(urls, readFieldString(imageUrls.getAsJsonObject("base"), "full"));
            }
        }
        addUrlIfPresent(urls, readFieldString(capeObject, "imageUrl"));
        return urls.toArray(new String[urls.size()]);
    }

    private static void addUrlIfPresent(java.util.ArrayList<String> urls, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        for (String existing : urls) {
            if (url.equals(existing)) {
                return;
            }
        }
        urls.add(url);
    }

    private static String readFieldString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        String value = object.get(field).getAsString();
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
