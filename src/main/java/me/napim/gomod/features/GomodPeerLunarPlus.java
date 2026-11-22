package me.napim.gomod.features;

import me.napim.gomod.util.CompactUuid;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shares and consumes the local-only Lunar+ badge choice for Gomod peers on the relay. */
public final class GomodPeerLunarPlus {
    private static final long PEER_TTL_MS = GomodSocialPresence.PRESENCE_TTL_MS;
    private static final Map<String, PeerState> BY_UUID = new ConcurrentHashMap<String, PeerState>();
    private static final Map<String, String> NAME_TO_UUID = new ConcurrentHashMap<String, String>();

    private GomodPeerLunarPlus() {
    }

    public static void attachLocalState(JsonObject payload) {
        if (payload == null) {
            return;
        }
        boolean advertise = GomodFixes.isWeaveRuntime()
                && ConfigManager.isLunarTweaksEnabled()
                && ConfigManager.isLunarTweaksShowToOtherPlayers();
        payload.addProperty("lunarPlusBadgeStatePresent", advertise);
        int logoRgb = advertise ? GomodLogoColorSync.getLocalLogoRgbForRelay() : -1;
        int plusRgb = advertise ? GomodLogoColorSync.getLocalPlusRgbForRelay() : -1;
        payload.addProperty("lunarPlusBadgeHidden", advertise && plusRgb == 0);
        boolean logoColorPresent = advertise && logoRgb >= 0;
        boolean plusColorPresent = advertise && plusRgb >= 0;
        // Keep the legacy combined flag for older peer clients and publish independent
        // presence bits so a custom plus color can be shared while the logo stays native.
        payload.addProperty("lunarLogoColorStatePresent", logoColorPresent && plusColorPresent);
        payload.addProperty("lunarLogoColorOverridePresent", logoColorPresent);
        payload.addProperty("lunarPlusColorOverridePresent", plusColorPresent);
        payload.addProperty("lunarLogoRgb", logoColorPresent ? logoRgb : -1);
        payload.addProperty("lunarPlusRgb", plusColorPresent ? plusRgb : -1);
    }

    public static void onPollResponse(JsonObject response) {
        pruneStale();
        if (response == null) {
            return;
        }
        ingestArray(response, "gomodPeersOnServer");
        // FullAccess visibility snapshots include all verified same-server Gomod peers,
        // not just the mutual-friend subset used by the Social presence list.
        ingestArray(response, "lunarFullAccessPeersOnServer");
    }

    private static void ingestArray(JsonObject response, String key) {
        if (!response.has(key) || !response.get(key).isJsonArray()) {
            return;
        }
        for (com.google.gson.JsonElement element : response.getAsJsonArray(key)) {
            if (element != null && element.isJsonObject()) {
                ingest(element.getAsJsonObject());
            }
        }
    }

    public static void onRelayPeerMessage(JsonObject message) {
        if (message != null && booleanValue(message, "relayPresenceHeartbeat")) {
            if (message.has("lunarPlusBadgeStatePresent")
                    && !booleanValue(message, "lunarPlusBadgeStatePresent")) {
                ingest(message);
            } else {
                refreshPeerTimestamp(message);
            }
        } else {
            ingest(message);
        }
    }

    private static void refreshPeerTimestamp(JsonObject object) {
        String name = firstNonEmpty(object, "name", "player", "from", "sender");
        String uuid = normalizeUuid(firstNonEmpty(object, "playerUuid", "mcProfileUuid", "uuid"));
        if (isLocalPlayer(name, uuid) || (uuid.isEmpty() && name.isEmpty())) return;
        if (uuid.isEmpty()) uuid = "name:" + name.toLowerCase(Locale.ROOT);
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        PeerState previous = BY_UUID.get(uuid);
        if (previous != null && previous.lastSeenMs <= ts) {
            BY_UUID.put(uuid, new PeerState(previous.name, previous.uuid, previous.hidePlus,
                    previous.logoColorPresent, previous.logoRgb, previous.plusColorPresent,
                    previous.plusRgb, previous.server, ts));
        }
        if (!name.isEmpty()) NAME_TO_UUID.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    /** Returns null when the peer has not advertised a choice (or its presence has expired). */
    public static Boolean advertisedHidePlus(UUID playerId) {
        if (!GomodFixes.isWeaveRuntime() || !ConfigManager.isLunarTweaksEnabled()) {
            return null;
        }
        if (!ConfigManager.isLunarTweaksSeeGomodPlayers()) return Boolean.TRUE;
        if (GomodPeerLunarFullAccess.shouldHideLunarFeatures(playerId)) {
            return Boolean.TRUE;
        }
        PeerState state = playerId == null ? null : BY_UUID.get(CompactUuid.key(playerId));
        if (state != null && !state.isStale()) {
            return state.hidePlus;
        }
        return null;
    }

    public static boolean hasPeerState(UUID playerId) {
        PeerState state = playerId == null ? null : BY_UUID.get(CompactUuid.key(playerId));
        return state != null && !state.isStale();
    }

    /** Returns a verified same-server peer logo RGB, or -1 when no live display override exists. */
    public static int advertisedLogoRgb(UUID playerId) {
        PeerState state = displayColorState(playerId);
        return state == null || !state.logoColorPresent ? -1 : state.logoRgb;
    }

    /** Returns a verified same-server peer Lunar+ RGB (zero means No+), or -1 when absent. */
    public static int advertisedPlusRgb(UUID playerId) {
        PeerState state = displayColorState(playerId);
        return state == null || !state.plusColorPresent ? -1 : state.plusRgb;
    }

    private static PeerState displayColorState(UUID playerId) {
        if (!GomodFixes.isWeaveRuntime() || !ConfigManager.isLunarTweaksEnabled()
                || !ConfigManager.isLunarTweaksSeeGomodPlayers() || playerId == null) return null;
        PeerState state = BY_UUID.get(CompactUuid.key(playerId));
        if (state == null || state.isStale()
                || (!state.logoColorPresent && !state.plusColorPresent)) return null;
        String localServer = GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());
        if (localServer != null && !state.server.isEmpty()
                && !localServer.equalsIgnoreCase(state.server)) return null;
        return state;
    }

    private static void ingest(JsonObject object) {
        if (object == null || !object.has("lunarPlusBadgeStatePresent")) {
            return;
        }
        String name = firstNonEmpty(object, "name", "player", "from", "sender");
        String uuid = normalizeUuid(firstNonEmpty(object, "playerUuid", "mcProfileUuid", "uuid"));
        if (isLocalPlayer(name, uuid)) {
            return;
        }
        if (uuid.isEmpty() && name.isEmpty()) {
            return;
        }

        boolean present = booleanValue(object, "lunarPlusBadgeStatePresent");
        boolean hidden = booleanValue(object, "lunarPlusBadgeHidden");
        boolean legacyColorPresent = booleanValue(object, "lunarLogoColorStatePresent");
        boolean logoColorPresent = object.has("lunarLogoColorOverridePresent")
                ? booleanValue(object, "lunarLogoColorOverridePresent") : legacyColorPresent;
        boolean plusColorPresent = object.has("lunarPlusColorOverridePresent")
                ? booleanValue(object, "lunarPlusColorOverridePresent") : legacyColorPresent;
        int logoRgb = colorValue(object, "lunarLogoRgb");
        int plusRgb = colorValue(object, "lunarPlusRgb");
        logoColorPresent &= logoRgb >= 0;
        plusColorPresent &= plusRgb >= 0;
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        String server = firstNonEmpty(object, "server");
        if (uuid.isEmpty()) {
            uuid = "name:" + name.toLowerCase(Locale.ROOT);
        }
        if (!present) {
            BY_UUID.remove(uuid);
            if (!name.isEmpty()) {
                NAME_TO_UUID.remove(name.toLowerCase(Locale.ROOT), uuid);
            }
            if (!uuid.startsWith("name:")) {
                UUID peerId = parseUuid(uuid);
                GomodLogoColorSync.refreshRemoteLunarPlusVisibility(uuid,
                        GomodPeerLunarFullAccess.shouldHideLunarFeatures(peerId));
            }
            return;
        }
        BY_UUID.put(uuid, new PeerState(name, uuid, hidden, logoColorPresent, logoRgb,
                plusColorPresent, plusRgb, server, ts));
        if (!name.isEmpty()) {
            NAME_TO_UUID.put(name.toLowerCase(Locale.ROOT), uuid);
        }
        if (!uuid.startsWith("name:")) {
            Boolean effective = advertisedHidePlus(parseUuid(uuid));
            GomodLogoColorSync.refreshRemoteLunarPlusVisibility(
                    uuid, effective != null && effective.booleanValue());
        }
        pruneStale();
    }

    private static void pruneStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PeerState> entry : BY_UUID.entrySet()) {
            PeerState state = entry.getValue();
            if (state == null || now - state.lastSeenMs > PEER_TTL_MS) {
                BY_UUID.remove(entry.getKey());
                if (state != null && !state.uuid.startsWith("name:")) {
                    GomodLogoColorSync.refreshRemoteLunarPlusVisibility(state.uuid, false);
                }
                if (state != null && !state.name.isEmpty()) {
                    NAME_TO_UUID.remove(state.name.toLowerCase(Locale.ROOT), entry.getKey());
                }
            }
        }
    }

    private static boolean isLocalPlayer(String name, String uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (!uuid.isEmpty() && mc.thePlayer.getUniqueID() != null
                && uuid.equals(normalizeUuid(mc.thePlayer.getUniqueID().toString()))) {
            return true;
        }
        return name != null && !name.isEmpty() && mc.thePlayer.getName() != null
                && name.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static String firstNonEmpty(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                String value = object.get(key).getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static boolean booleanValue(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int colorValue(JsonObject object, String key) {
        try {
            if (!object.has(key) || object.get(key).isJsonNull()) return -1;
            int color = object.get(key).getAsInt();
            return color >= 0 && color <= 0xFFFFFF ? color : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String normalizeUuid(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return value.length() == 32 ? value : "";
    }

    private static UUID parseUuid(String normalizedUuid) {
        if (normalizedUuid == null || normalizedUuid.length() != 32) {
            return null;
        }
        String value = normalizedUuid.substring(0, 8) + "-" + normalizedUuid.substring(8, 12) + "-"
                + normalizedUuid.substring(12, 16) + "-" + normalizedUuid.substring(16, 20) + "-"
                + normalizedUuid.substring(20);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class PeerState {
        final String name;
        final String uuid;
        final boolean hidePlus;
        final boolean logoColorPresent;
        final int logoRgb;
        final boolean plusColorPresent;
        final int plusRgb;
        final String server;
        final long lastSeenMs;

        PeerState(String name, String uuid, boolean hidePlus, boolean logoColorPresent,
                  int logoRgb, boolean plusColorPresent, int plusRgb, String server, long lastSeenMs) {
            this.name = name == null ? "" : name;
            this.uuid = uuid == null ? "" : uuid;
            this.hidePlus = hidePlus;
            this.logoColorPresent = logoColorPresent;
            this.logoRgb = logoRgb;
            this.plusColorPresent = plusColorPresent;
            this.plusRgb = plusRgb;
            this.server = server == null ? "" : server;
            this.lastSeenMs = lastSeenMs;
        }

        boolean isStale() {
            return System.currentTimeMillis() - lastSeenMs > PEER_TTL_MS;
        }
    }
}
