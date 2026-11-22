package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Optional crowd-sourcing of <b>automatic</b> duplicate-tab denicks (Mojang profile vs visible TAB nick).
 * Manual {@code /nametagnick} and Teams Nick Finder mappings are never sent here.
 * <p>
 * Configure {@link ConfigManager#isDenickCrowdSyncEnabled()}, optional {@link ConfigManager#getDenickCrowdSyncBaseUrl()},
 * and the shipped fallback in {@link DenickCrowdSyncConstants} (see {@link ConfigManager#getDenickCrowdSyncEffectiveBaseUrl()}).
 * HTTPS origin only, no path. Your HTTP service should implement:
 * <ul>
 *   <li>{@code GET {base}/gomod-denick-crowd.json} — JSON object: nick (lowercase) → real Mojang IGN string.</li>
 *   <li>{@code GET {base}/gomod-denick-feed.json} — JSON array of rich denick reports for Discord relays.</li>
 *   <li>{@code POST {base}/gomod-denick-report} — body {@code {"nick","real","gomodSocialId","mcProfileUuid","player"}} (UTF-8 JSON).
 *       Reporter fields are optional but enable per-user history on the relay usage report.</li>
 * </ul>
 * Pulls run immediately on Hypixel world entry, after a successful report POST, on gomod relay inbox polls,
 * when duplicate TAB nicks are detected, and every {@link #PERIODIC_PULL_INTERVAL_MS} as a fallback (not 10 minutes).
 * Each newly learned crowd pair is also written to {@code gomod123/tab-nick-mappings.json} on the client so
 * mappings survive relay restarts without persistent disk.
 */
public final class DenickCrowdSync {

    private static final Pattern MINECRAFT_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern MINECRAFT_TEXTURE_URL = Pattern.compile(
            "^https?://textures\\.minecraft\\.net/texture/([A-Za-z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_MERGED_ENTRIES = 4000;
    /** Fallback refresh while on Hypixel (immediate paths handle join / report / relay / duplicates). */
    private static final long PERIODIC_PULL_INTERVAL_MS = 45L * 1000L;
    /** Minimum spacing between any two pulls (except world enter). */
    private static final long MIN_PULL_SPACING_MS = 2L * 1000L;
    /** Spacing for relay-inbox-triggered pulls (gomod Service poll). */
    private static final long RELAY_PULL_SPACING_MS = 5L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final ConcurrentHashMap<String, String> crowdNickLowerToReal = new ConcurrentHashMap<>();
    private static volatile long lastPullStartedMs = 0L;
    private static final AtomicBoolean pullInFlight = new AtomicBoolean(false);

    private DenickCrowdSync() {}

    /** After local {@link TabDuplicateNickResolver} SESSION map; only Mojang-resolved auto pairs. */
    public static String getCrowdResolvedReal(String nickLower) {
        if (nickLower == null || nickLower.isEmpty()) {
            return null;
        }
        String real = crowdNickLowerToReal.get(nickLower.toLowerCase(Locale.ROOT));
        if (real == null || real.isEmpty() || TabDuplicateNickResolver.isIgnoredFalsePositiveName(real)
                || TabDuplicateNickResolver.isFalsePositiveNickMappingRevoked(nickLower, real)) {
            return null;
        }
        return real;
    }

    /** Forget a pair withdrawn from the relay's authoritative false-positive list. */
    public static void forgetRevokedMapping(String nick, String real) {
        if (nick == null || real == null) return;
        String key = nick.trim().toLowerCase(Locale.ROOT);
        String existing = crowdNickLowerToReal.get(key);
        if (existing != null && existing.equalsIgnoreCase(real.trim())) {
            crowdNickLowerToReal.remove(key, existing);
        }
    }

    /**
     * Hypixel world entered (lobby or game). Pull crowd map immediately so friends' recent denicks are available.
     */
    public static void onHypixelWorldEntered(Minecraft mc) {
        schedulePull(mc, PullTrigger.WORLD_ENTER);
    }

    /**
     * Duplicate TAB nick(s) detected — pull crowd hints before / while Mojang lookups finish.
     */
    public static void onDuplicateTabNicksDetected(Minecraft mc) {
        schedulePull(mc, PullTrigger.DUPLICATE_TAB);
    }

    /**
     * After a successful gomod relay inbox poll (gomod Service connected). Keeps crowd map fresh for online friends.
     */
    public static void maybePullAfterRelayPoll(Minecraft mc) {
        schedulePull(mc, PullTrigger.RELAY_POLL);
    }

    /**
     * Called from the client tick path on Hypixel. Periodic fallback refresh.
     */
    public static void maybePeriodicPull(Minecraft mc) {
        schedulePull(mc, PullTrigger.PERIODIC);
    }

    /**
     * Report an auto-resolved duplicate nick (Mojang username for profile UUID vs TAB nick). Not used for manual hints.
     */
    public static void reportAutoDuplicateResolved(String duplicateNick, String actualName) {
        if (!ConfigManager.isDenickCrowdSyncEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) {
            return;
        }
        String base = normalizeBaseUrl(ConfigManager.getDenickCrowdSyncEffectiveBaseUrl());
        if (base.isEmpty()) {
            return;
        }
        String nick = sanitizeUsername(duplicateNick);
        String real = sanitizeUsername(actualName);
        if (nick == null || real == null || nick.equalsIgnoreCase(real)) {
            return;
        }
        if (TabDuplicateNickResolver.isFalsePositiveNickMappingRevoked(nick, real)) return;
        String nickKey = nick.toLowerCase(Locale.ROOT);
        crowdNickLowerToReal.putIfAbsent(nickKey, real);
        NickSkinReport nickSkin = findNickSkinReport(mc, nick, real);
        DenickReportContext context = captureReportContext(mc);
        // Keep the server-visible capitalization. The local crowd lookup key remains lowercase.
        Thread t = new Thread(() -> postReportQuietly(base, nick, real, nickSkin, context, mc),
                "gomod-denick-crowd-post");
        t.setDaemon(true);
        t.start();
    }

    private enum PullTrigger {
        WORLD_ENTER,
        DUPLICATE_TAB,
        RELAY_POLL,
        PERIODIC,
        AFTER_REPORT
    }

    private static void schedulePull(Minecraft mc, PullTrigger trigger) {
        if (!ConfigManager.isDenickCrowdSyncEnabled()) {
            return;
        }
        String base = normalizeBaseUrl(ConfigManager.getDenickCrowdSyncEffectiveBaseUrl());
        if (base.isEmpty()) {
            return;
        }
        if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) {
            return;
        }
        long now = System.currentTimeMillis();
        long sinceLast = now - lastPullStartedMs;
        switch (trigger) {
            case WORLD_ENTER:
                break;
            case AFTER_REPORT:
            case DUPLICATE_TAB:
                if (sinceLast < MIN_PULL_SPACING_MS) {
                    return;
                }
                break;
            case RELAY_POLL:
                if (sinceLast < RELAY_PULL_SPACING_MS) {
                    return;
                }
                break;
            case PERIODIC:
                if (sinceLast < PERIODIC_PULL_INTERVAL_MS) {
                    return;
                }
                break;
            default:
                if (sinceLast < MIN_PULL_SPACING_MS) {
                    return;
                }
        }
        if (!pullInFlight.compareAndSet(false, true)) {
            return;
        }
        lastPullStartedMs = now;
        final Minecraft mcRef = mc;
        Thread t = new Thread(() -> {
            try {
                pullOnceQuietly(base);
            } finally {
                pullInFlight.set(false);
                scheduleClientRescan(mcRef);
            }
        }, "gomod-denick-crowd-pull");
        t.setDaemon(true);
        t.start();
    }

    private static void postReportQuietly(String base, String nick, String realIgn,
            NickSkinReport nickSkin, DenickReportContext context, Minecraft mc) {
        HttpURLConnection conn = null;
        boolean ok = false;
        try {
            URL url = new URL(base + "/gomod-denick-report");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            JsonObject body = new JsonObject();
            body.addProperty("nick", nick);
            body.addProperty("real", realIgn);
            body.addProperty("detectedAt", context.detectedAtMs);
            body.addProperty("server", context.serverName);
            if (!context.mapName.isEmpty()) {
                body.addProperty("map", context.mapName);
            }
            if (!context.gameId.isEmpty()) {
                body.addProperty("gameId", context.gameId);
            }
            if (!context.queueMode.isEmpty()) {
                body.addProperty("queueMode", context.queueMode);
            }
            if (nickSkin != null) {
                body.addProperty("nickSkinUrl", nickSkin.url);
                body.addProperty("nickSkinTextureHash", nickSkin.textureHash);
                body.addProperty("nickSkinModel", nickSkin.model);
                if (nickSkin.profileUuid != null && !nickSkin.profileUuid.isEmpty()) {
                    body.addProperty("nickProfileUuid", nickSkin.profileUuid);
                }
            }
            SkinReport realSkin = fetchRealSkinReport(realIgn);
            if (realSkin != null) {
                if (realSkin.profileUuid != null && !realSkin.profileUuid.isEmpty()) {
                    body.addProperty("realUuid", realSkin.profileUuid);
                }
                body.addProperty("realSkinUrl", realSkin.url);
                body.addProperty("realSkinTextureHash", realSkin.textureHash);
                body.addProperty("realSkinModel", realSkin.model);
            }
            GomodSocialIdentity.attachPoll(body);
            GomodSocialIdentity.attachRelayPlayer(body, mc);
            byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(raw.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(raw);
            }
            int code = conn.getResponseCode();
            ok = code >= 200 && code < 300;
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (ok) {
            schedulePull(mc, PullTrigger.AFTER_REPORT);
        }
    }

    /** Captures the generated nick skin from the player-info GameProfile before the server handoff can remove it. */
    private static NickSkinReport findNickSkinReport(Minecraft mc, String nick, String realIgn) {
        if (mc == null || mc.getNetHandler() == null || nick == null || realIgn == null) {
            return null;
        }
        try {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                GameProfile profile = info.getGameProfile();
                String resolved = TabDuplicateNickResolver.resolveRealNameForDenicker(
                        profile.getId(), profile.getName());
                if (resolved == null || !resolved.equalsIgnoreCase(realIgn)) {
                    continue;
                }
                String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                if (visible != null && !visible.equalsIgnoreCase(nick)
                        && (profile.getName() == null || !profile.getName().equalsIgnoreCase(nick))) {
                    continue;
                }
                NickSkinReport report = decodeNickSkinProperty(profile);
                if (report != null) {
                    return report;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static NickSkinReport decodeNickSkinProperty(GameProfile profile) {
        if (profile == null || profile.getProperties() == null) {
            return null;
        }
        try {
            Collection<Property> properties = profile.getProperties().get("textures");
            if (properties == null) {
                return null;
            }
            for (Property property : properties) {
                if (property == null || property.getValue() == null || property.getValue().isEmpty()) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
                JsonObject root = new JsonParser().parse(decoded).getAsJsonObject();
                if (!root.has("textures") || !root.get("textures").isJsonObject()) {
                    continue;
                }
                JsonObject textures = root.getAsJsonObject("textures");
                if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                    continue;
                }
                JsonObject skin = textures.getAsJsonObject("SKIN");
                String normalizedUrl = skin.has("url") ? normalizeTextureUrl(skin.get("url").getAsString()) : null;
                if (normalizedUrl == null) {
                    continue;
                }
                java.util.regex.Matcher matcher = MINECRAFT_TEXTURE_URL.matcher(normalizedUrl);
                if (!matcher.matches()) {
                    continue;
                }
                String model = "default";
                if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                    JsonObject metadata = skin.getAsJsonObject("metadata");
                    if (metadata.has("model") && "slim".equalsIgnoreCase(metadata.get("model").getAsString())) {
                        model = "slim";
                    }
                }
                String profileUuid = profile.getId() == null
                        ? ""
                        : profile.getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
                return new NickSkinReport(normalizedUrl, matcher.group(1), model, profileUuid);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static DenickReportContext captureReportContext(Minecraft mc) {
        long detectedAt = System.currentTimeMillis();
        String map = "";
        String gameId = "";
        String mode = "";
        String server = "";
        try {
            if (mc != null && mc.getCurrentServerData() != null
                    && mc.getCurrentServerData().serverIP != null) {
                server = sanitizeContextField(mc.getCurrentServerData().serverIP, 255);
            }
        } catch (Throwable ignored) {
            server = "";
        }
        try {
            map = sanitizeContextField(MapQueuer.getCurrentMap(), 80);
        } catch (Throwable ignored) {
            map = "";
        }
        try {
            gameId = sanitizeContextField(MapQueuer.getCurrentGameId(), 80);
        } catch (Throwable ignored) {
            gameId = "";
        }
        try {
            if (BlitzTeamsDetector.isBlitzTeamsGame(mc)) {
                mode = "teams";
            } else if (GameContext.isBlitzTabListCustomSortContext(mc) || !map.isEmpty() || !gameId.isEmpty()) {
                mode = "solo";
            }
        } catch (Throwable ignored) {
            mode = "";
        }
        return new DenickReportContext(detectedAt, map, gameId, mode, server);
    }

    private static String sanitizeContextField(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("unknown")) {
            return "";
        }
        String cleaned = trimmed.replaceAll("[\\p{Cntrl}<>`\"{}\\\\]", "");
        if (cleaned.length() > maxLen) {
            cleaned = cleaned.substring(0, maxLen);
        }
        return cleaned.trim();
    }

    private static SkinReport fetchRealSkinReport(String realIgn) {
        String real = sanitizeUsername(realIgn);
        if (real == null) {
            return null;
        }
        try {
            URL uuidUrl = new URL("https://api.mojang.com/users/profiles/minecraft/"
                    + URLEncoder.encode(real, "UTF-8"));
            JsonObject uuidProfile = fetchJsonObject(uuidUrl, 64_000);
            if (uuidProfile == null || !uuidProfile.has("id")) {
                return null;
            }
            String uuid = uuidProfile.get("id").getAsString();
            if (uuid == null || uuid.trim().isEmpty()) {
                return null;
            }
            URL profileUrl = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + uuid.replace("-", ""));
            JsonObject sessionProfile = fetchJsonObject(profileUrl, 128_000);
            return decodeSkinProperty(sessionProfile, uuid.replace("-", "").toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject fetchJsonObject(URL url, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream stream = conn.getInputStream();
            byte[] raw = readAllLimited(stream, maxBytes);
            stream.close();
            if (raw.length == 0) {
                return null;
            }
            JsonElement parsed = new JsonParser().parse(new String(raw, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static SkinReport decodeSkinProperty(JsonObject profile, String fallbackUuid) {
        if (profile == null || !profile.has("properties") || !profile.get("properties").isJsonArray()) {
            return null;
        }
        try {
            for (JsonElement element : profile.getAsJsonArray("properties")) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject prop = element.getAsJsonObject();
                if (!prop.has("name") || !"textures".equals(prop.get("name").getAsString())
                        || !prop.has("value")) {
                    continue;
                }
                String decoded = new String(Base64.getDecoder().decode(prop.get("value").getAsString()),
                        StandardCharsets.UTF_8);
                JsonObject root = new JsonParser().parse(decoded).getAsJsonObject();
                if (!root.has("textures") || !root.get("textures").isJsonObject()) {
                    continue;
                }
                JsonObject textures = root.getAsJsonObject("textures");
                if (!textures.has("SKIN") || !textures.get("SKIN").isJsonObject()) {
                    continue;
                }
                JsonObject skin = textures.getAsJsonObject("SKIN");
                String normalizedUrl = skin.has("url") ? normalizeTextureUrl(skin.get("url").getAsString()) : null;
                if (normalizedUrl == null) {
                    continue;
                }
                java.util.regex.Matcher matcher = MINECRAFT_TEXTURE_URL.matcher(normalizedUrl);
                if (!matcher.matches()) {
                    continue;
                }
                String model = "default";
                if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                    JsonObject metadata = skin.getAsJsonObject("metadata");
                    if (metadata.has("model") && "slim".equalsIgnoreCase(metadata.get("model").getAsString())) {
                        model = "slim";
                    }
                }
                String uuid = profile.has("id") ? profile.get("id").getAsString() : fallbackUuid;
                return new SkinReport(normalizedUrl, matcher.group(1), model,
                        uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String normalizeTextureUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        java.util.regex.Matcher matcher = MINECRAFT_TEXTURE_URL.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        return "https://textures.minecraft.net/texture/" + matcher.group(1);
    }

    private static final class DenickReportContext {
        private final long detectedAtMs;
        private final String mapName;
        private final String gameId;
        private final String queueMode;
        private final String serverName;

        private DenickReportContext(long detectedAtMs, String mapName, String gameId, String queueMode,
                String serverName) {
            this.detectedAtMs = detectedAtMs;
            this.mapName = mapName == null ? "" : mapName;
            this.gameId = gameId == null ? "" : gameId;
            this.queueMode = queueMode == null ? "" : queueMode;
            this.serverName = serverName == null ? "" : serverName;
        }
    }

    private static class SkinReport {
        protected final String url;
        protected final String textureHash;
        protected final String model;
        protected final String profileUuid;

        private SkinReport(String url, String textureHash, String model, String profileUuid) {
            this.url = url;
            this.textureHash = textureHash;
            this.model = model;
            this.profileUuid = profileUuid;
        }
    }

    private static final class NickSkinReport extends SkinReport {
        private NickSkinReport(String url, String textureHash, String model, String profileUuid) {
            super(url, textureHash, model, profileUuid);
        }
    }

    private static void pullOnceQuietly(String base) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/gomod-denick-crowd.json");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                return;
            }
            byte[] buf = readAllLimited(stream, 512_000);
            stream.close();
            if (buf.length == 0) {
                return;
            }
            String text = new String(buf, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return;
            }
            JsonElement root = new JsonParser().parse(text);
            if (!root.isJsonObject()) {
                return;
            }
            if (code >= 200 && code < 300) pullRevocationsQuietly(base);
            mergeCrowdJson(root.getAsJsonObject());
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void pullRevocationsQuietly(String base) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/gomod-denick-revocations.json").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return;
            InputStream stream = conn.getInputStream();
            if (stream == null) return;
            byte[] bytes = readAllLimited(stream, 64_000);
            stream.close();
            JsonElement root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return;
            JsonArray pairs = root.getAsJsonArray();
            for (JsonElement element : pairs) {
                if (!element.isJsonObject()) continue;
                JsonObject pair = element.getAsJsonObject();
                if (!pair.has("nick") || !pair.has("real")) continue;
                TabDuplicateNickResolver.revokeFalsePositiveNickMapping(
                        pair.get("nick").getAsString(), pair.get("real").getAsString());
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void scheduleClientRescan(Minecraft mc) {
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> TabDuplicateNickResolver.rescanTabAfterCrowdPull());
    }

    private static void mergeCrowdJson(JsonObject o) {
        boolean anyNewLocal = false;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (crowdNickLowerToReal.size() >= MAX_MERGED_ENTRIES) {
                break;
            }
            if (e.getKey() == null || !e.getValue().isJsonPrimitive()) {
                continue;
            }
            String nickKey = sanitizeUsername(e.getKey());
            String realVal = sanitizeUsername(e.getValue().getAsString());
            if (nickKey == null || realVal == null || nickKey.equalsIgnoreCase(realVal)) {
                continue;
            }
            String nickLower = nickKey.toLowerCase(Locale.ROOT);
            if (TabDuplicateNickResolver.isFalsePositiveNickMappingRevoked(nickLower, realVal)) continue;
            crowdNickLowerToReal.putIfAbsent(nickLower, realVal);
            if (TabDuplicateNickResolver.rememberCrowdNickMapping(nickLower, realVal)) {
                anyNewLocal = true;
            }
        }
        if (anyNewLocal) {
            TabDuplicateNickResolver.schedulePersistNickMappings();
        }
    }

    private static byte[] readAllLimited(InputStream in, int maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) {
                break;
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String sanitizeUsername(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        String plain = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(t);
        if (plain == null) {
            plain = t;
        }
        plain = plain.trim();
        if (!MINECRAFT_USERNAME.matcher(plain).matches()) {
            return null;
        }
        return plain;
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String u = raw.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return "";
        }
        return u;
    }
}
