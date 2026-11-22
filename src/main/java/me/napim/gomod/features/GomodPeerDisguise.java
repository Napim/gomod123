package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Opt-in, same-server visual disguise state for other Gomod clients. */
public final class GomodPeerDisguise {
    private static final long PEER_TTL_MS = GomodSocialPresence.PRESENCE_TTL_MS;
    private static final Pattern MC_USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Map<String, PeerState> PEERS = new ConcurrentHashMap<String, PeerState>();
    /** Short-lived, server-scoped display aliases retained across menu/rejoin snapshot gaps. */
    private static final Map<String, CachedPeerName> RECENT_PEER_NAMES =
            new ConcurrentHashMap<String, CachedPeerName>();
    private static volatile LocalShare localShare;
    private static volatile boolean relaySharingSupported;
    private static String lastSnapshotServer = "";
    private static long lastSnapshotAtMs;
    private static volatile long lastLocalLunarProfilePublishAtMs;
    /** Avoid repeatedly downloading the same full outfit while Lunar is still initializing. */
    private static final Map<String, Long> FULL_PROFILE_LOOKUP_AT =
            new ConcurrentHashMap<String, Long>();
    private static final java.util.Set<String> CAPE_TARGETS = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private GomodPeerDisguise() {
    }

    /** The presence request is explicit opt-in, and an empty update clears a previous share. */
    public static void attachLocalState(JsonObject payload) {
        if (payload == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        LocalShare share = localShare;
        // A connection transition can leave the player object alive briefly after its world
        // has been detached. Publishing "disabled" in that window clears other clients'
        // alias cache, which is exactly when a rejoining player is first added to their TAB.
        // Treat loading/menu gaps as no update. An explicit /disguise off clears localShare,
        // and the next in-world poll still publishes the normal disabled state.
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            payload.addProperty("gomodDisguiseStatePresent", false);
            LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.publish_deferred",
                    "world-transition", "deferred=true reason=world-transition");
            return;
        }
        String activeTarget = LocalDisguise.getTargetName();
        boolean active = share != null && LocalDisguise.isEnabled() && activeTarget != null
                && activeTarget.equalsIgnoreCase(share.targetName);
        if (!active && share != null) {
            // Keep the last opt-in mapping through brief local-disguise activation gaps. The
            // explicit disable path clears localShare before polling, so it remains a clear.
            payload.addProperty("gomodDisguiseStatePresent", false);
            LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.publish_deferred",
                    "disguise-transition", "deferred=true reason=disguise-transition");
            return;
        }
        payload.addProperty("gomodDisguiseStatePresent", true);
        payload.addProperty("gomodDisguiseEnabled", active);
        LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.publish", "enabled=" + active,
                "enabled=" + active + " fullLunar=" + (active && share.fullLunar)
                        + " relaySupported=" + relaySharingSupported);
        if (active) {
            payload.addProperty("gomodDisguiseTargetName", share.targetName);
            payload.addProperty("gomodDisguiseTargetUuid", share.targetUuid);
            payload.addProperty("gomodDisguiseFullLunar", share.fullLunar);
            JsonObject snapshot = LocalDisguise.getSharedDisguiseProfileSnapshot();
            if (snapshot != null) payload.add("gomodDisguiseProfile", snapshot);
            if (share.fullLunar && System.currentTimeMillis() - lastLocalLunarProfilePublishAtMs
                    >= 60_000L) {
                try {
                    UUID targetId = parseUuid(share.targetUuid);
                    if (targetId == null) throw new IllegalStateException("Invalid Lunar target UUID.");
                    String encoded = LunarFullDisguise.getSharedProfileBase64(targetId);
                    payload.addProperty("gomodDisguiseLunarProfileBase64", encoded);
                    lastLocalLunarProfilePublishAtMs = System.currentTimeMillis();
                } catch (Throwable unavailable) {
                    LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.full_profile_publish",
                            "unavailable", "result=waiting_for_lunar_profile");
                }
            }
        }
    }

    /** Called only after the local disguise has succeeded. */
    public static boolean shareLocalDisguise(String targetName, String targetUuid, boolean fullLunar) {
        String name = targetName == null ? "" : targetName.trim();
        String uuid = normalizeUuid(targetUuid);
        if (!MC_USERNAME.matcher(name).matches() || (fullLunar && uuid.isEmpty())) {
            return false;
        }
        localShare = new LocalShare(name, uuid, fullLunar);
        LunarConnectionDiagnostics.record("gomod.peer_disguise.local_share",
                "enabled=true fullLunar=" + fullLunar + " relaySupported=" + relaySharingSupported);
        return true;
    }

    public static boolean isRelaySharingSupported() {
        return relaySharingSupported;
    }

    public static void attachTargetCapeTargets(JsonObject payload) {
        if (payload == null) return;
        JsonArray targets = new JsonArray();
        Set<String> unique = new HashSet<String>();
        // Local /disguise does not create a RemoteDisguiseManager entry. Refresh its published cape too,
        // giving this single active target a slot even when the manual/peer list reaches the poll bound.
        String localTarget = GomodPeerStandaloneCape.getLocalDisguiseCapeTargetUuid();
        if (!localTarget.isEmpty() && unique.add(localTarget)) targets.add(new com.google.gson.JsonPrimitive(localTarget));
        // RemoteDisguiseManager has already added local manual-disguise targets.
        // Keep them when appending targets requested by peer disguises.
        JsonElement existing = payload.get("gomodDisguiserCapeTargets");
        if (existing != null && existing.isJsonArray()) {
            for (JsonElement value : existing.getAsJsonArray()) {
                if (targets.size() >= 32) break;
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                String uuid = normalizeUuid(value.getAsString());
                if (!uuid.isEmpty() && unique.add(uuid)) targets.add(new com.google.gson.JsonPrimitive(uuid));
                if (targets.size() >= 32) break;
            }
        }
        for (String value : CAPE_TARGETS) {
            if (targets.size() >= 32) break;
            String uuid = normalizeUuid(value);
            if (!uuid.isEmpty() && unique.add(uuid)) targets.add(new com.google.gson.JsonPrimitive(uuid));
        }
        payload.add("gomodDisguiserCapeTargets", targets);
    }

    public static void onTargetCapesPollResponse(JsonObject response) {
        if (response == null || !response.has("gomodDisguiserTargetCapes")
                || !response.get("gomodDisguiserTargetCapes").isJsonArray()) return;
        JsonArray capes = response.getAsJsonArray("gomodDisguiserTargetCapes");
        for (JsonElement value : capes) {
            if (!value.isJsonObject()) continue;
            JsonObject cape = value.getAsJsonObject();
            String uuid = normalizeUuid(first(cape, "playerUuid", "uuid"));
            GomodPeerStandaloneCape.onRelayPeerMessage(cape);
        }
        CAPE_TARGETS.clear();
        for (PeerState state : PEERS.values()) if (state.manual && !state.isStale()) CAPE_TARGETS.add(state.targetUuid);
    }

    /** Both Gomod commands share the active disguise's appearance; ordinary disguises do not. */
    public static boolean isSharingLocalDisguise() {
        LocalShare share = localShare;
        if (share == null || !LocalDisguise.isEnabled()) {
            return false;
        }
        String target = LocalDisguise.getTargetName();
        return target != null && target.equalsIgnoreCase(share.targetName);
    }

    public static boolean clearLocalShare() {
        boolean hadShare = localShare != null;
        localShare = null;
        return hadShare;
    }

    public static boolean hasPeerDisguises() {
        return !PEERS.isEmpty();
    }

    /** Display identity from the current same-server snapshot, independent of asynchronous skin loading. */
    public static PeerName getPeerName(GameProfile profile) {
        if (profile == null || (PEERS.isEmpty() && RECENT_PEER_NAMES.isEmpty())) return null;
        if (profile.getId() != null) {
            String sourceUuid = normalizeUuid(profile.getId().toString());
            PeerState state = PEERS.get(sourceUuid);
            String currentServer = currentServerKey();
            if (state != null && !state.isStale() && sameServer(state.serverKey, currentServer)) {
                return state.displayName;
            }
            PeerName recent = recentPeerName(currentServer, sourceUuid, null);
            return recent != null ? recent : recentPeerName(currentServer, null, profile.getName());
        }
        return getPeerName(profile.getName());
    }

    /** Chat identifies its speaker by the real server name rather than a profile UUID. */
    public static PeerName getPeerName(String sourceName) {
        if (sourceName == null || sourceName.isEmpty()
                || (PEERS.isEmpty() && RECENT_PEER_NAMES.isEmpty())) return null;
        String currentServer = currentServerKey();
        for (PeerState state : PEERS.values()) {
            if (!state.isStale() && sameServer(state.serverKey, currentServer)
                    && sourceName.equalsIgnoreCase(state.sourceName)) return state.displayName;
        }
        return recentPeerName(currentServer, null, sourceName);
    }

    public static final class PeerName {
        public final String sourceName;
        public final String targetName;

        private PeerName(String sourceName, String targetName) {
            this.sourceName = sourceName;
            this.targetName = targetName;
        }
    }

    /** The relay includes this as a complete snapshot scoped to the viewer's current server. */
    public static void onPollResponse(JsonObject response) {
        if (response == null) return;
        String snapshotServer = first(response, "gomodPeerSnapshotServer");
        String currentServer = GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());
        if (!snapshotServer.isEmpty() && (currentServer == null || !snapshotServer.equalsIgnoreCase(currentServer))) return;
        relaySharingSupported = booleanValue(response, "gomodPeerDisguiseSupported");
        LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.relay_support",
                "supported=" + relaySharingSupported, "supported=" + relaySharingSupported);
        if (!response.has("gomodPeersOnServer")
                || !response.get("gomodPeersOnServer").isJsonArray()) {
            if (booleanValue(response, "gomodPeersOnServerUnchanged") && !snapshotServer.isEmpty()) {
                long snapshotAt = response.has("gomodPeerSnapshotAt")
                        && !response.get("gomodPeerSnapshotAt").isJsonNull()
                        ? response.get("gomodPeerSnapshotAt").getAsLong() : System.currentTimeMillis();
                if (snapshotServer.equalsIgnoreCase(lastSnapshotServer) && snapshotAt < lastSnapshotAtMs) return;
                lastSnapshotServer = snapshotServer;
                lastSnapshotAtMs = snapshotAt;
                for (Map.Entry<String, PeerState> entry : PEERS.entrySet()) {
                    PeerState state = entry.getValue();
                    if (state == null || !sameServer(state.serverKey, snapshotServer)
                            || (state.expiresAt > 0L && snapshotAt >= state.expiresAt)) continue;
                    PeerState refreshed = state.withTimestamp(snapshotAt);
                    PEERS.put(entry.getKey(), refreshed);
                    rememberPeerName(refreshed.sourceUuid, refreshed.sourceName, refreshed.targetName,
                            refreshed.serverKey, snapshotAt, refreshed.expiresAt);
                }
            }
            return;
        }
        if (response.has("gomodPeerSnapshotAt") && !response.get("gomodPeerSnapshotAt").isJsonNull()) {
            long snapshotAt = response.get("gomodPeerSnapshotAt").getAsLong();
            if (snapshotServer.equalsIgnoreCase(lastSnapshotServer) && snapshotAt < lastSnapshotAtMs) return;
            lastSnapshotServer = snapshotServer;
            lastSnapshotAtMs = snapshotAt;
        }
        JsonArray array = response.getAsJsonArray("gomodPeersOnServer");
        Set<String> current = new HashSet<String>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String sourceUuid = normalizeUuid(first(object, "playerUuid", "mcProfileUuid", "uuid"));
            String sourceName = first(object, "name", "player");
            if (sourceUuid.isEmpty() || sourceName.isEmpty() || isLocal(sourceUuid, sourceName)) {
                continue;
            }
            current.add(sourceUuid);
            long ts = object.has("ts") && !object.get("ts").isJsonNull()
                    ? object.get("ts").getAsLong() : System.currentTimeMillis();
            PeerState previous = PEERS.get(sourceUuid);
            if (previous != null && ts < previous.lastSeenMs) continue;
            // A presence-only update (for example an older sender's periodic cape hello) is
            // not an explicit disguise-off update. Keep the last known state until a complete
            // disguise update arrives; TTL expiry or explicit off still clears it.
            if (object.has("gomodDisguiseStatePresent")
                    && !booleanValue(object, "gomodDisguiseStatePresent")) {
                continue;
            }
            if (!booleanValue(object, "gomodDisguiseEnabled")) {
                clearPeer(sourceUuid);
                continue;
            }
            String targetName = first(object, "gomodDisguiseTargetName");
            String targetUuid = normalizeUuid(first(object, "gomodDisguiseTargetUuid"));
            if (!MC_USERNAME.matcher(targetName).matches()) {
                clearPeer(sourceUuid);
                continue;
            }
            boolean fullLunar = booleanValue(object, "gomodDisguiseFullLunar") && !targetUuid.isEmpty();
            String peerServer = first(object, "server");
            if (peerServer.isEmpty()) peerServer = !snapshotServer.isEmpty() ? snapshotServer : currentServer;
            peerServer = normalizeServer(peerServer);
            boolean manual = booleanValue(object, "gomodDisguiserApplied");
            DisguiserLunarIcon lunarIcon = manual
                    ? DisguiserLunarIcon.fromWire(first(object, "gomodDisguiseLunarIcon")) : DisguiserLunarIcon.KEEP;
            long expiresAt = object.has("gomodDisguiseExpiresAt") && !object.get("gomodDisguiseExpiresAt").isJsonNull()
                    ? object.get("gomodDisguiseExpiresAt").getAsLong() : 0L;
            if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) { clearPeer(sourceUuid); continue; }
            if (manual && !targetUuid.isEmpty()) CAPE_TARGETS.add(targetUuid);
            boolean changed = previous == null
                    || !previous.sourceName.equalsIgnoreCase(sourceName)
                    || !previous.targetName.equalsIgnoreCase(targetName)
                    || !previous.targetUuid.equals(targetUuid)
                    || previous.fullLunar != fullLunar || previous.manual != manual || previous.expiresAt != expiresAt
                    || previous.lunarIcon != lunarIcon || !previous.serverKey.equals(peerServer);
            PeerState next = changed
                    ? new PeerState(sourceName, sourceUuid, targetName, targetUuid, fullLunar, ts, manual,
                            expiresAt, lunarIcon, peerServer)
                    : previous.withTimestamp(ts);
            PEERS.put(sourceUuid, next);
            rememberPeerName(sourceUuid, sourceName, targetName, peerServer, ts, expiresAt);

            if (changed) {
                LunarConnectionDiagnostics.record("gomod.peer_disguise.received",
                        "enabled=true fullLunar=" + fullLunar + " changed=true");
            }
            // Idempotent: no extra lookup is launched while one is pending or installed, but
            // a failed profile/texture lookup is retried after its short backoff expires.
            JsonObject profileSnapshot = object.has("gomodDisguiseProfile")
                    && object.get("gomodDisguiseProfile").isJsonObject()
                    ? object.getAsJsonObject("gomodDisguiseProfile") : null;
            if (profileSnapshot != null) {
                RemoteDisguiseManager.applyGomodPeerDisguiseSnapshot(sourceName, parseUuid(sourceUuid),
                        targetName, targetUuid, manual, expiresAt, lunarIcon, fullLunar, profileSnapshot);
            } else {
                RemoteDisguiseManager.applyGomodPeerDisguise(sourceName, parseUuid(sourceUuid),
                        targetName, manual, expiresAt, lunarIcon, fullLunar);
            }
            reconcileLunarProfile(next);
        }

        for (String existing : PEERS.keySet()) {
            PeerState state = PEERS.get(existing);
            if (state == null || state.isStale()) {
                // Keep the resolved renderer entry through a short absence. Nametags/skins
                // use that entry, not the separate TAB name cache. Explicit off still clears.
                clearPeer(existing);
            }
        }
    }

    /** Scope retained renderer entries to the server where the peer opted in. */
    public static boolean allowsPeerAppearance(UUID sourceUuid, String sourceName) {
        PeerState state = sourceUuid == null ? null : PEERS.get(normalizeUuid(sourceUuid.toString()));
        if (state == null && sourceName != null) {
            for (PeerState candidate : PEERS.values()) {
                if (sourceName.equalsIgnoreCase(candidate.sourceName)) { state = candidate; break; }
            }
        }
        return state != null && !state.isStale() && sameServer(state.serverKey, currentServerKey());
    }

    public static void expirePeerStates() {
        for (Map.Entry<String, PeerState> entry : PEERS.entrySet()) {
            if (entry.getValue().isStale()) clearPeer(entry.getKey());
        }
    }

    /** Retry a full-profile overlay when Lunar delivers either the source or target profile. */
    public static void onLunarProfileAvailable(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (PeerState state : PEERS.values()) {
            if (!state.isStale() && state.fullLunar && (playerId.equals(parseUuid(state.sourceUuid))
                    || playerId.equals(parseUuid(state.targetUuid)))) {
                reconcileLunarProfile(state);
            }
        }
    }

    private static void reconcileLunarProfile(PeerState state) {
        if (state == null) {
            return;
        }
        UUID sourceUuid = parseUuid(state.sourceUuid);
        if (!sameServer(state.serverKey, currentServerKey())) {
            LunarFullDisguise.clearPeerAppearance(sourceUuid);
            return;
        }
        if (!state.fullLunar) {
            LunarFullDisguise.clearPeerAppearance(sourceUuid);
            return;
        }
        if (sourceUuid == null || GomodPeerLunarFullAccess.shouldHideDisguisedLunarFeatures(sourceUuid)) {
            LunarFullDisguise.clearPeerAppearance(sourceUuid);
            return;
        }
        UUID targetUuid = parseUuid(state.targetUuid);
        if (targetUuid == null) {
            return;
        }
        if (!LunarFullDisguise.hasCachedProfile(targetUuid)) {
            requestSharedLunarProfile(state, sourceUuid, targetUuid);
            return;
        }
        try {
            LunarFullDisguise.applyPeerAppearance(sourceUuid, targetUuid);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                    "result=failed errorType=" + error.getClass().getSimpleName());
        }
    }

    private static void requestSharedLunarProfile(final PeerState state,
                                                  final UUID sourceUuid,
                                                  final UUID targetUuid) {
        final String key = state.sourceUuid + "|" + state.targetUuid;
        long now = System.currentTimeMillis();
        Long previous = FULL_PROFILE_LOOKUP_AT.get(key);
        if (previous != null && now - previous < 15_000L) return;
        if (FULL_PROFILE_LOOKUP_AT.size() > 128) FULL_PROFILE_LOOKUP_AT.clear();
        FULL_PROFILE_LOOKUP_AT.put(key, now);
        Thread worker = new Thread(() -> {
            try {
                JsonObject request = DisguiserRelay.context("disguiser_profile_get");
                request.addProperty("sourceUuid", sourceUuid.toString());
                request.addProperty("targetUuid", targetUuid.toString());
                JsonObject response = DisguiserRelay.request(request);
                if (!response.has("profileBase64") || response.get("profileBase64").isJsonNull()) return;
                String encoded = response.get("profileBase64").getAsString();
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) return;
                mc.addScheduledTask(() -> {
                    PeerState current = PEERS.get(state.sourceUuid);
                    if (current == null || current.isStale() || !current.fullLunar
                            || !current.targetUuid.equalsIgnoreCase(state.targetUuid)
                            || !sameServer(current.serverKey, currentServerKey())
                            || GomodPeerLunarFullAccess.shouldHideDisguisedLunarFeatures(sourceUuid)) return;
                    if (LunarFullDisguise.cacheSharedProfileBase64(targetUuid, encoded)) {
                        FULL_PROFILE_LOOKUP_AT.remove(key);
                        reconcileLunarProfile(current);
                    }
                });
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("gomod.peer_disguise.full_profile_fetch",
                        "result=unavailable errorType=" + error.getClass().getSimpleName());
            }
        }, "gomod-peer-lunar-profile");
        worker.setDaemon(true);
        worker.start();
    }

    private static void clearPeer(String normalizedUuid) {
        clearPeer(normalizedUuid, false);
    }

    private static void clearPeer(String normalizedUuid, boolean preserveRecentDisplayName) {
        if (!preserveRecentDisplayName) forgetRecentPeerNames(normalizedUuid);
        String requestPrefix = normalizedUuid + "|";
        for (String key : FULL_PROFILE_LOOKUP_AT.keySet()) {
            if (key.startsWith(requestPrefix)) FULL_PROFILE_LOOKUP_AT.remove(key);
        }
        PeerState removed = PEERS.remove(normalizedUuid);
        if (removed == null) {
            return;
        }
        UUID sourceUuid = parseUuid(normalizedUuid);
        CAPE_TARGETS.remove(removed.targetUuid);
        RemoteDisguiseManager.clearGomodPeerDisguise(sourceUuid, removed.sourceName);
        LunarFullDisguise.clearPeerAppearance(sourceUuid);
        LunarConnectionDiagnostics.record("gomod.peer_disguise.received",
                "enabled=false fullLunar=" + removed.fullLunar);
    }

    private static void rememberPeerName(String sourceUuid, String sourceName, String targetName,
                                         String serverKey, long lastSeenMs, long expiresAt) {
        String key = peerNameCacheKey(serverKey, sourceUuid);
        if (key.isEmpty() || sourceName == null || sourceName.isEmpty()
                || targetName == null || targetName.isEmpty()) return;
        RECENT_PEER_NAMES.put(key, new CachedPeerName(sourceName, sourceUuid, targetName,
                normalizeServer(serverKey), lastSeenMs, expiresAt));
    }

    private static PeerName recentPeerName(String currentServer, String sourceUuid, String sourceName) {
        if (currentServer == null || currentServer.isEmpty()) return null;
        if (sourceUuid != null && !sourceUuid.isEmpty()) {
            String key = peerNameCacheKey(currentServer, sourceUuid);
            CachedPeerName cached = RECENT_PEER_NAMES.get(key);
            if (cached == null) return null;
            if (cached.isStale()) {
                RECENT_PEER_NAMES.remove(key, cached);
                return null;
            }
            reportRecentPeerNameUse();
            return cached.displayName;
        }
        for (Map.Entry<String, CachedPeerName> entry : RECENT_PEER_NAMES.entrySet()) {
            CachedPeerName cached = entry.getValue();
            if (cached == null) continue;
            if (cached.isStale()) {
                RECENT_PEER_NAMES.remove(entry.getKey(), cached);
                continue;
            }
            if (sameServer(cached.serverKey, currentServer)
                    && sourceName != null && sourceName.equalsIgnoreCase(cached.sourceName)) {
                reportRecentPeerNameUse();
                return cached.displayName;
            }
        }
        return null;
    }

    private static void forgetRecentPeerNames(String sourceUuid) {
        if (sourceUuid == null || sourceUuid.isEmpty()) return;
        for (Map.Entry<String, CachedPeerName> entry : RECENT_PEER_NAMES.entrySet()) {
            CachedPeerName cached = entry.getValue();
            if (cached != null && sourceUuid.equals(cached.sourceUuid)) {
                RECENT_PEER_NAMES.remove(entry.getKey(), cached);
            }
        }
    }

    private static String peerNameCacheKey(String serverKey, String sourceUuid) {
        String server = normalizeServer(serverKey);
        String uuid = normalizeUuid(sourceUuid);
        return server.isEmpty() || uuid.isEmpty() ? "" : server + "|" + uuid;
    }

    private static String currentServerKey() {
        return normalizeServer(GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft()));
    }

    private static String normalizeServer(String server) {
        return server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameServer(String left, String right) {
        return left != null && !left.isEmpty() && right != null && !right.isEmpty()
                && left.equalsIgnoreCase(right);
    }

    private static void reportRecentPeerNameUse() {
        LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.display_cache", "used",
                "used=true scope=same-server short-lived=true");
    }

    private static boolean isLocal(String uuid, String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        UUID localUuid = mc.thePlayer.getUniqueID();
        return (localUuid != null && normalizeUuid(localUuid.toString()).equals(uuid))
                || name.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static String first(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                String value = object.get(name).getAsString();
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
        if (value.length() != 32) {
            return "";
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return "";
            }
        }
        return value;
    }

    private static UUID parseUuid(String normalized) {
        if (normalized == null || normalized.length() != 32) {
            return null;
        }
        String dashed = normalized.substring(0, 8) + "-" + normalized.substring(8, 12) + "-"
                + normalized.substring(12, 16) + "-" + normalized.substring(16, 20) + "-"
                + normalized.substring(20);
        try {
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class LocalShare {
        final String targetName;
        final String targetUuid;
        final boolean fullLunar;

        LocalShare(String targetName, String targetUuid, boolean fullLunar) {
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.fullLunar = fullLunar;
        }
    }

    private static final class CachedPeerName {
        final String sourceName;
        final String sourceUuid;
        final String targetName;
        final String serverKey;
        final long lastSeenMs;
        final long expiresAt;
        final PeerName displayName;

        CachedPeerName(String sourceName, String sourceUuid, String targetName,
                       String serverKey, long lastSeenMs, long expiresAt) {
            this.sourceName = sourceName;
            this.sourceUuid = sourceUuid;
            this.targetName = targetName;
            this.serverKey = serverKey;
            this.lastSeenMs = lastSeenMs;
            this.expiresAt = expiresAt;
            this.displayName = new PeerName(sourceName, targetName);
        }

        boolean isStale() {
            return (expiresAt > 0L && System.currentTimeMillis() >= expiresAt)
                    || lastSeenMs <= 0L || System.currentTimeMillis() - lastSeenMs > PEER_TTL_MS;
        }
    }

    private static final class PeerState {
        final String sourceName;
        final String sourceUuid;
        final String targetName;
        final String targetUuid;
        final boolean fullLunar;
        final long lastSeenMs;
        final PeerName displayName;
        final boolean manual;
        final long expiresAt;
        final DisguiserLunarIcon lunarIcon;
        final String serverKey;

        PeerState(String sourceName, String sourceUuid, String targetName, String targetUuid,
                  boolean fullLunar, long lastSeenMs, boolean manual, long expiresAt, String serverKey) {
            this(sourceName, sourceUuid, targetName, targetUuid, fullLunar, lastSeenMs, manual,
                    expiresAt, DisguiserLunarIcon.KEEP, serverKey);
        }

        PeerState(String sourceName, String sourceUuid, String targetName, String targetUuid,
                  boolean fullLunar, long lastSeenMs, boolean manual, long expiresAt,
                  DisguiserLunarIcon lunarIcon, String serverKey) {
            this.sourceName = sourceName;
            this.sourceUuid = sourceUuid;
            this.targetName = targetName;
            this.targetUuid = targetUuid;
            this.fullLunar = fullLunar;
            this.lastSeenMs = lastSeenMs;
            this.displayName = new PeerName(sourceName, targetName);
            this.manual = manual;
            this.expiresAt = expiresAt;
            this.lunarIcon = lunarIcon;
            this.serverKey = serverKey == null ? "" : serverKey;
        }

        PeerState withTimestamp(long value) {
            return new PeerState(sourceName, sourceUuid, targetName, targetUuid, fullLunar,
                    value, manual, expiresAt, lunarIcon, serverKey);
        }

        boolean isStale() {
            return (expiresAt > 0L && System.currentTimeMillis() >= expiresAt)
                    || lastSeenMs <= 0L || System.currentTimeMillis() - lastSeenMs > PEER_TTL_MS;
        }
    }
}
