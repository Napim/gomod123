package me.napim.gomod.features;

import me.napim.gomod.util.CompactUuid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shares only the peer-visibility preference used to gate Lunar visuals on Gomod clients. */
public final class GomodPeerLunarFullAccess {
    private static final long PEER_TTL_MS = GomodSocialPresence.PRESENCE_TTL_MS;
    private static final Map<String, PeerState> BY_UUID = new ConcurrentHashMap<String, PeerState>();

    private GomodPeerLunarFullAccess() {
    }

    /** Include a visibility bit in the existing authenticated presence poll; never send account/catalog data. */
    public static void attachLocalState(JsonObject payload) {
        if (payload == null) {
            return;
        }
        boolean weave = GomodFixes.isWeaveRuntime();
        payload.addProperty("lunarFullAccessStatePresent", weave);
        payload.addProperty("lunarFullAccessShareEnabled", weave
                && ConfigManager.isLunarTweaksShowToOtherPlayers());
        // Private service-demand hint, separate from sharing / appear-offline.
        // The relay does not include this in peer presence snapshots.
        payload.addProperty("lunarFullAccessEnabled", weave && ConfigManager.isLunarTweaksEnabled());
        payload.addProperty("lunarApiReadyObserved", weave && ConfigManager.isLunarTweaksEnabled()
                && LunarApiAvailabilityProbe.hasFreshDirectReadiness());
        GomodCosmeticAppearance.attachLocalState(payload);
        GomodStudioSharing.attachLocalState(payload);
    }

    public static void onPollResponse(JsonObject response) {
        if (response == null) {
            return;
        }
        JsonArray peers = response.has("lunarFullAccessPeersOnServer")
                && response.get("lunarFullAccessPeersOnServer").isJsonArray()
                ? response.getAsJsonArray("lunarFullAccessPeersOnServer") : null;
        if (peers != null) {
            GomodCosmeticAppearance.acceptPeers(peers);
            GomodStudioSharing.acceptPeers(peers);
            // The relay returns a complete snapshot for the client's current server. Do not
            // carry an old server's peer policy into a new world after a server switch.
            Set<String> currentPeers = new HashSet<String>();
            for (JsonElement element : peers) {
                if (element != null && element.isJsonObject()) {
                    String uuid = ingest(element.getAsJsonObject());
                    if (!uuid.isEmpty()) {
                        currentPeers.add(uuid);
                    }
                }
            }
            for (String uuid : BY_UUID.keySet()) {
                if (!currentPeers.contains(uuid)) {
                    BY_UUID.remove(uuid);
                }
            }
            refreshPeerPlusBadges();
        } else if (booleanValue(response, "lunarFullAccessPeersOnServerUnchanged")) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, PeerState> entry : BY_UUID.entrySet()) {
                PeerState state = entry.getValue();
                if (state != null) {
                    BY_UUID.put(entry.getKey(), new PeerState(state.shareEnabled, now));
                }
            }
            GomodCosmeticAppearance.refreshPeerTimestamps(now);
            GomodStudioSharing.refreshPeerTimestamps(now);
        }
        pruneStale();
    }

    /** Stop any peer emote that was already in progress when a new privacy restriction arrived. */
    public static void stopHiddenPeerEmotes() {
        if (!GomodFixes.isWeaveRuntime() || !ConfigManager.isLunarTweaksEnabled()) {
            return;
        }
        for (Map.Entry<String, PeerState> entry : BY_UUID.entrySet()) {
            if (entry.getValue() != null && shouldHideLunarFeatures(parseUuid(entry.getKey()))) {
                stopRemoteEmote(parseUuid(entry.getKey()));
            }
        }
    }

    /** Keep the custom Lunar+ logo cache consistent with the same feature-visibility policy. */
    public static void refreshPeerPlusBadges() {
        if (!GomodFixes.isWeaveRuntime()) {
            return;
        }
        for (String uuid : BY_UUID.keySet()) {
            UUID playerId = parseUuid(uuid);
            if (playerId == null) {
                continue;
            }
            Boolean advertised = GomodPeerLunarPlus.advertisedHidePlus(playerId);
            boolean hide = shouldHideLunarFeatures(playerId)
                    || Boolean.TRUE.equals(advertised);
            GomodLogoColorSync.refreshRemoteLunarPlusVisibility(uuid, hide);
        }
    }

    /** A remote Gomod peer's disallow preference or our local receive toggle suppresses its Lunar visuals. */
    public static boolean shouldHideLunarFeatures(UUID playerId) {
        if (playerId == null || !GomodFixes.isWeaveRuntime()
                || !ConfigManager.isLunarTweaksEnabled()) {
            return false;
        }
        PeerState state = BY_UUID.get(CompactUuid.key(playerId));
        if (state == null || state.isStale()) {
            return false;
        }
        return !ConfigManager.isLunarTweaksSeeGomodPlayers() || !state.shareEnabled;
    }

    /** Strict opt-in policy for an explicit peer FullAccess disguise, independent of local FA enablement. */
    public static boolean shouldHideDisguisedLunarFeatures(UUID playerId) {
        if (playerId == null || !GomodFixes.isWeaveRuntime()
                || !ConfigManager.isLunarTweaksSeeGomodPlayers()) {
            return true;
        }
        PeerState state = BY_UUID.get(CompactUuid.key(playerId));
        return state == null || state.isStale() || !state.shareEnabled;
    }

    /**
     * Cosmetic-layer policy also honors this client's private username blocklist. Unlike the
     * shared peer-privacy policy, a local blocklist entry remains active with FullAccess off.
     */
    public static boolean shouldHideLunarCosmetics(UUID playerId) {
        if (playerId == null || !GomodFixes.isWeaveRuntime()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || (mc.thePlayer != null && playerId.equals(mc.thePlayer.getUniqueID()))) {
            return false;
        }
        String playerName = findOnlinePlayerName(mc, playerId);
        if (ConfigManager.isLunarTweaksHiddenCosmeticPlayer(playerName)) {
            return true;
        }
        return shouldHideLunarFeatures(playerId);
    }

    /** Refresh Lunar's native companion objects after an ignore-list edit, without Lunar types in shared code. */
    public static int refreshLocalLunarCosmeticsForPlayer(String playerName) {
        if (!GomodFixes.isWeaveRuntime() || playerName == null || playerName.trim().isEmpty()) {
            return 0;
        }
        try {
            Class<?> support = Class.forName("me.napim.gomod.mixins.LunarCompanionMultiTypeSupport",
                    false, GomodPeerLunarFullAccess.class.getClassLoader());
            Object result = support.getMethod("refreshPlayerByName", String.class).invoke(null, playerName);
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (ClassNotFoundException ignored) {
            return 0;
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("cosmetics.local_ignore", "refreshFailed="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static String findOnlinePlayerName(Minecraft mc, UUID playerId) {
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(playerId);
                if (info != null && info.getGameProfile() != null) {
                    String name = info.getGameProfile().getName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.theWorld != null) {
                for (Object candidate : mc.theWorld.playerEntities) {
                    if (candidate instanceof EntityPlayer) {
                        EntityPlayer player = (EntityPlayer) candidate;
                        if (playerId.equals(player.getUniqueID())) {
                            return player.getName();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Called by the guarded Lunar emote-push mixin; no Lunar protobuf types leak into shared code. */
    public static boolean shouldSuppressEmotePush(Object push) {
        if (push == null) {
            return false;
        }
        try {
            Object playerUuid = invoke(push, "getPlayerUuid");
            long high = ((Number) invoke(playerUuid, "getHigh64")).longValue();
            long low = ((Number) invoke(playerUuid, "getLow64")).longValue();
            UUID id = new UUID(high, low);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && id.equals(mc.thePlayer.getUniqueID())) {
                return false;
            }
            return shouldHideLunarFeatures(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("missing protobuf UUID");
        }
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static String ingest(JsonObject object) {
        if (!object.has("lunarFullAccessStatePresent")
                || !booleanValue(object, "lunarFullAccessStatePresent")) {
            return "";
        }
        String rawUuid = firstNonEmpty(object, "playerUuid", "mcProfileUuid", "uuid");
        String uuid = normalizeUuid(rawUuid);
        if (uuid.isEmpty() || isLocalPlayer(uuid)) {
            return "";
        }
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        PeerState next = new PeerState(booleanValue(object, "lunarFullAccessShareEnabled"), ts);
        PeerState previous = BY_UUID.put(uuid, next);
        if (!next.shareEnabled && (previous == null || previous.shareEnabled)) {
            stopRemoteEmote(parseUuid(uuid));
        }
        return uuid;
    }

    private static void stopRemoteEmote(UUID playerId) {
        LunarPeerEmoteControl.stop(playerId);
    }

    private static boolean isLocalPlayer(String uuid) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.thePlayer != null && mc.thePlayer.getUniqueID() != null
                    && uuid.equals(normalizeUuid(mc.thePlayer.getUniqueID().toString()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void pruneStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PeerState> entry : BY_UUID.entrySet()) {
            PeerState state = entry.getValue();
            if (state == null || now - state.lastSeenMs > PEER_TTL_MS) {
                BY_UUID.remove(entry.getKey(), state);
            }
        }
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
        final boolean shareEnabled;
        final long lastSeenMs;

        PeerState(boolean shareEnabled, long lastSeenMs) {
            this.shareEnabled = shareEnabled;
            this.lastSeenMs = lastSeenMs;
        }

        boolean isStale() {
            return System.currentTimeMillis() - lastSeenMs > PEER_TTL_MS;
        }
    }
}
