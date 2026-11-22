package me.napim.gomod.features;

import me.napim.gomod.util.CompactUuid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows other gomod users' standalone (2nd cape button) wear via relay presence / peer snapshots.
 */
public final class GomodPeerStandaloneCape {

    private static final long PEER_TTL_MS = GomodSocialPresence.PRESENCE_TTL_MS;
    private static final ResourceLocation NO_ORIGINAL_CAPE =
            new ResourceLocation("gomod123", "internal/no_original_cape");

    private static final Map<String, PeerWear> BY_UUID = new ConcurrentHashMap<String, PeerWear>();
    private static final Map<String, String> NAME_TO_UUID = new ConcurrentHashMap<String, String>();
    private static final Map<String, ResourceLocation> ORIGINAL_CAPES = new ConcurrentHashMap<String, ResourceLocation>();
    /** One active local disguise owns its confirmed cape independently of server presence/teleports. */
    private static volatile String localDisguiseCapeTargetUuid = "";

    private GomodPeerStandaloneCape() {
    }

    public static void setLocalDisguiseCapeTarget(GameProfile profile) {
        // Release the previous lifetime first, including when the same target is enabled again after reset.
        localDisguiseCapeTargetUuid = "";
        pruneStale();
        localDisguiseCapeTargetUuid = profile == null || profile.getId() == null
                ? "" : CompactUuid.key(profile.getId());
    }

    public static String getLocalDisguiseCapeTargetUuid() {
        return localDisguiseCapeTargetUuid;
    }

    public static void attachLocalWearTo(JsonObject payload) {
        PlayerStandaloneCape.attachRelayWearFields(payload);
        GomodPeerLunarPlus.attachLocalState(payload);
        GomodPeerLunarFullAccess.attachLocalState(payload);
    }

    public static void onPollResponse(JsonObject response) {
        if (response == null) {
            return;
        }
        if (response.has("gomodDisguiserTargetCapes") && response.get("gomodDisguiserTargetCapes").isJsonArray())
            ingestPeerArray(response.getAsJsonArray("gomodDisguiserTargetCapes"));
        if (response.has("gomodPeersOnServer") && response.get("gomodPeersOnServer").isJsonArray()) {
            ingestPeerArray(response.getAsJsonArray("gomodPeersOnServer"));
        }
        GomodPeerLunarFullAccess.onPollResponse(response);
        GomodPeerLunarPlus.onPollResponse(response);
        GomodPeerDisguise.onPollResponse(response);
    }

    public static void onRelayPeerMessage(JsonObject message) {
        if (message == null) {
            return;
        }
        if (booleanValue(message, "relayPresenceHeartbeat")) {
            refreshPeerTimestamp(message);
        } else {
            ingestPeerObject(message);
        }
        GomodPeerLunarPlus.onRelayPeerMessage(message);
    }

    private static void refreshPeerTimestamp(JsonObject object) {
        if (!booleanValue(object, "standaloneCapeStatePresent")) return;
        String name = firstNonEmpty(object, "name", "player", "from", "sender");
        String uuid = normUuid(firstNonEmpty(object, "playerUuid", "mcProfileUuid", "uuid"));
        if ((name.isEmpty() && uuid.isEmpty()) || isLocalPlayer(name, uuid)) return;
        String key = uuid.isEmpty() ? "name:" + name.toLowerCase(Locale.ROOT) : uuid;
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        BY_UUID.compute(key, (ignored, previous) -> previous == null || previous.lastSeenMs > ts
                ? previous : new PeerWear(previous.name, previous.uuid, previous.optionKey,
                        previous.location, previous.useDefaultCape, ts));
        pruneStale();
    }

    private static void ingestPeerArray(JsonArray arr) {
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (el != null && el.isJsonObject()) {
                ingestPeerObject(el.getAsJsonObject());
            }
        }
    }

    private static void ingestPeerObject(JsonObject o) {
        if (o == null) {
            return;
        }
        // Modern presence rows include default cape fields even without a wear update.
        // An explicit false marker means leave the saved selection alone; only legacy
        // rows without the marker infer state from the fields themselves.
        boolean hasCapeState = o.has("standaloneCapeStatePresent")
                ? booleanValue(o, "standaloneCapeStatePresent")
                : o.has("standaloneCapeOptionKey")
                    || o.has("standaloneCapeImageBase64")
                    || o.has("standaloneCapeLunarOnWeave");
        if (!hasCapeState) {
            return;
        }
        String name = firstNonEmpty(o, "name", "player", "from", "sender");
        String uuid = normUuid(firstNonEmpty(o, "playerUuid", "mcProfileUuid", "uuid"));
        if (name.isEmpty() && uuid.isEmpty()) {
            return;
        }
        long ts = o.has("ts") && !o.get("ts").isJsonNull() ? o.get("ts").getAsLong() : System.currentTimeMillis();
        String optionKey = firstNonEmpty(o, "standaloneCapeOptionKey");
        String imageUrl = firstNonEmpty(o, "standaloneCapeImageUrl");
        String imageBase64 = firstNonEmpty(o, "standaloneCapeImageBase64");
        // The legacy wire field now means that the sender wants the receiver's native/default cape path.
        boolean useDefaultCape = booleanValue(o, "standaloneCapeLunarOnWeave");
        String manualTargetUuid = normUuid(firstNonEmpty(o, "gomodDisguiseTargetUuid"));
        if (booleanValue(o, "gomodDisguiserApplied") && !manualTargetUuid.isEmpty()) {
            // Timed relay rows carry the target's wear, not the source's selection. Cache it
            // under the target immediately, so the first resolved skin uses the right cape.
            // Keeping it out of the source cache also prevents target wear leaking after expiry.
            rememberPeer(firstNonEmpty(o, "gomodDisguiseTargetName"), manualTargetUuid,
                    optionKey, imageUrl, imageBase64, useDefaultCape, ts);
            return;
        }
        rememberPeer(name, uuid, optionKey, imageUrl, imageBase64, useDefaultCape, ts);
    }

    private static void rememberPeer(String name, String uuid, String optionKey, String imageUrl,
            String imageBase64, boolean useDefaultCape, long ts) {
        if (isLocalPlayer(name, uuid)) {
            return;
        }
        if (uuid.isEmpty() && !name.isEmpty()) {
            uuid = "name:" + name.toLowerCase(Locale.ROOT);
        }
        final boolean[] accepted = {false};
        BY_UUID.compute(uuid, (key, previous) -> {
            // Polls replay older presence messages after fresh target-cape responses.
            // Reject them before registering textures, which can reuse the same location.
            if (previous != null && previous.lastSeenMs > ts) return previous;
            ResourceLocation location = null;
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                location = PlayerCustomCapes.registerEphemeralPeerWear(key, imageBase64);
            }
            if (location == null && optionKey != null && !optionKey.isEmpty()) {
                location = PlayerStandaloneCape.resolveLocationForOptionKey(optionKey);
            }
            if (location == null && imageUrl != null && !imageUrl.isEmpty()) {
                location = CapeTextureLoader.loadExternalCapeTexture(imageUrl);
            }
            if (name != null && !name.isEmpty()) {
                NAME_TO_UUID.put(name.toLowerCase(Locale.ROOT), key);
            }
            accepted[0] = true;
            return new PeerWear(name, key, optionKey, location, useDefaultCape, ts);
        });
        if (!accepted[0]) return;
        LocalDisguise.onDisguiseTargetCapeWearChanged(name, uuid);
        pruneStale();
    }

    public static ResourceLocation capeForProfile(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getManualDisguiseTarget(profile);
        if (target != null) return capeForManualTarget(target);
        PeerWear wear = findWear(profile);
        return wear == null ? null : wear.locationForThisClient();
    }

    /** Exactly one target substitution; never borrows the source's OptiFine/Lunar/account cape. */
    public static ResourceLocation capeForManualTarget(LocalDisguise.RemoteDisguiseProfile target) {
        if (target == null) return null;
        PeerWear wear = findPublishedWear(target.profile, true);
        if (wear != null && !wear.useDefaultCape && !wear.optionKey.isEmpty()
                && !"gomod:off".equals(wear.optionKey)) {
            // No Cape and a selected texture that is still loading both intentionally return null.
            return wear.locationForThisClient();
        }
        return target.capeLocation;
    }

    /** Local /disguise prefers the target's published Gomod cape until the user picks a cape manually. */
    public static ResourceLocation capeForLocalDisguiseTarget(GameProfile profile, ResourceLocation fallbackCape,
                                                               boolean userSelectedCape) {
        PeerWear wear = localDisguiseTargetWear(profile, userSelectedCape);
        if (wear != null) {
            return wear.locationForThisClient();
        }
        return fallbackCape;
    }

    public static boolean isNoCapeForLocalDisguiseTarget(GameProfile profile, boolean userSelectedCape) {
        PeerWear wear = localDisguiseTargetWear(profile, userSelectedCape);
        return wear != null && wear.isNoCapeSelected();
    }

    public static LocalDisguise.CapeOptionInfo localDisguiseTargetCapeForPeerSharing(
            GameProfile profile, boolean userSelectedCape) {
        PeerWear wear = localDisguiseTargetWear(profile, userSelectedCape);
        if (wear == null) return null;
        String label = wear.isNoCapeSelected() ? "No Cape" : wear.optionKey;
        return LocalDisguise.CapeOptionInfo.createEntry(label, wear.locationForThisClient(), wear.optionKey,
                false, false, true, true);
    }

    private static PeerWear localDisguiseTargetWear(GameProfile profile, boolean userSelectedCape) {
        if (userSelectedCape || profile == null) return null;
        PeerWear wear = findLocalDisguiseTargetPublishedWear(profile);
        if (wear == null || wear.useDefaultCape || wear.optionKey.isEmpty()
                || "gomod:off".equals(wear.optionKey)) {
            return null;
        }
        return wear;
    }

    private static PeerWear findLocalDisguiseTargetPublishedWear(GameProfile profile) {
        PeerWear fresh = findPublishedWear(profile, true);
        if (fresh != null || profile == null || profile.getId() == null) return fresh;
        String uuid = CompactUuid.key(profile.getId());
        // Retention is limited to this active disguise. Ordinary player presence still expires normally.
        return !uuid.isEmpty() && uuid.equals(localDisguiseCapeTargetUuid) ? BY_UUID.get(uuid) : null;
    }

    /** Bounded state summary; contains no relay URL, image bytes, or credentials. */
    public static String describeLocalDisguiseTargetCape(GameProfile profile, ResourceLocation fallbackCape,
                                                          boolean userSelectedCape) {
        if (profile == null) return "target=<none>";
        boolean local = isLocalPlayer(profile.getName(), profile.getId() == null
                ? "" : CompactUuid.key(profile.getId()));
        PeerWear wear = findLocalDisguiseTargetPublishedWear(profile);
        boolean usable = !userSelectedCape && wear != null
                && !wear.useDefaultCape && !wear.optionKey.isEmpty() && !"gomod:off".equals(wear.optionKey);
        ResourceLocation resolved = capeForLocalDisguiseTarget(profile, fallbackCape, userSelectedCape);
        return "target=" + profile.getName() + " targetUuid=" + profile.getId()
                + " source=" + (userSelectedCape ? "manual-picker" : local ? "local-live"
                : wear == null ? "missing-or-stale" : wear.isStale() ? "active-disguise-cache" : "relay-cache")
                + " manualCapeSelected=" + userSelectedCape
                + " selectedKey=" + (wear == null ? "<none>" : wear.optionKey)
                + " peerUsesDefaultCape=" + (wear != null && wear.useDefaultCape)
                + " selectedTexture=" + (wear == null ? null : wear.location)
                + " wearAgeMs=" + (wear == null ? -1 : Math.max(0, System.currentTimeMillis() - wear.lastSeenMs))
                + " fallbackCape=" + fallbackCape + " resolvedCape=" + resolved;
    }

    public static boolean isManualDisguiseProfile(GameProfile profile) {
        return RemoteDisguiseManager.getManualDisguiseTarget(profile) != null;
    }

    /**
     * Remote Lunar cloak mode is only a preference flag from the sender; it does not include Lunar's cosmetic texture.
     * Manual disguises own the complete cape pass, including an empty target cape.
     */
    public static boolean shouldSuppressVanillaCapeForProfile(GameProfile profile) {
        if (isManualDisguiseProfile(profile)) return true;
        PeerWear wear = findWear(profile);
        return wear != null && wear.isNoCapeSelected();
    }

    /** True when this remote gomod peer currently advertises a resolved gomod cape for this client to render. */
    public static boolean shouldSuppressLunarCloakForUuid(java.util.UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (RemoteDisguiseManager.hasManualDisguiseTarget(uuid)) return true;
        PeerWear wear = BY_UUID.isEmpty() ? null : BY_UUID.get(CompactUuid.key(uuid));
        return wear != null && !wear.isStale() && wear.hasCapeOverride();
    }

    /** Name fallback for Lunar bridge builds that do not expose a stable UUID method. */
    public static boolean shouldSuppressLunarCloakForName(String name) {
        if (name == null) {
            return false;
        }
        String playerName = name.trim();
        if (playerName.isEmpty()) return false;
        if (RemoteDisguiseManager.hasManualDisguiseTargetName(playerName)) return true;
        PeerWear wear = findPublishedWearByName(playerName);
        return wear != null && !wear.isStale() && wear.hasCapeOverride();
    }

    public static void applyNetworkOverrides(Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        pruneStale();
        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        if (infos == null || infos.isEmpty()) {
            return;
        }
        for (NetworkPlayerInfo info : infos) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            if (isManualDisguiseProfile(info.getGameProfile())) {
                // RemoteDisguiseManager owns this field and its original snapshot. Writing here
                // before that snapshot is captured would save the disguise cape as the original.
                continue;
            }
            if (LocalDisguise.matchesLocalProfile(info.getGameProfile())) {
                continue;
            }
            String key = infoKey(info.getGameProfile());
            if (key.isEmpty()) {
                continue;
            }
            PeerWear wear = findWear(info.getGameProfile());
            ResourceLocation desired = wear == null ? null : wear.locationForThisClient();
            if (wear == null || !wear.hasCapeOverride()) {
                restoreOriginalCape(info, key);
                continue;
            }
            if (!(info instanceof NetworkPlayerInfoAccessor)) {
                continue;
            }
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            ResourceLocation current = accessor.gomod$getLocationCape();
            if (desired == null ? current == null : desired.equals(current)) {
                continue;
            }
            if (!ORIGINAL_CAPES.containsKey(key)) {
                ORIGINAL_CAPES.put(key, current == null ? NO_ORIGINAL_CAPE : current);
            }
            accessor.gomod$setLocationCape(desired);
        }
    }

    private static void restoreAllOriginalCapes(Minecraft mc) {
        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        if (infos == null || infos.isEmpty()) {
            return;
        }
        for (NetworkPlayerInfo info : infos) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String key = infoKey(info.getGameProfile());
            if (!key.isEmpty()) {
                restoreOriginalCape(info, key);
            }
        }
    }

    private static void restoreOriginalCape(NetworkPlayerInfo info, String key) {
        if (!(info instanceof NetworkPlayerInfoAccessor)) {
            return;
        }
        if (!ORIGINAL_CAPES.containsKey(key)) {
            return;
        }
        ResourceLocation original = ORIGINAL_CAPES.remove(key);
        if (NO_ORIGINAL_CAPE.equals(original)) {
            original = null;
        }
        ResourceLocation current = ((NetworkPlayerInfoAccessor) info).gomod$getLocationCape();
        if (original == null ? current == null : original.equals(current)) {
            return;
        }
        ((NetworkPlayerInfoAccessor) info).gomod$setLocationCape(original);
    }

    private static String infoKey(GameProfile profile) {
        if (profile == null) {
            return "";
        }
        if (profile.getId() != null) {
            return CompactUuid.key(profile.getId());
        }
        String name = profile.getName();
        return name == null || name.trim().isEmpty() ? "" : "name:" + name.trim().toLowerCase(Locale.ROOT);
    }

    private static PeerWear findWear(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getManualDisguiseTarget(profile);
        if (target != null) {
            // One substitution: the target's published preference owns this cape, never the source's wear.
            return findPublishedWear(target.profile, true);
        }
        return findPublishedWear(profile, false);
    }

    private static PeerWear findPublishedWear(GameProfile profile, boolean allowLocalSelection) {
        if (profile == null) return null;
        if (allowLocalSelection && isLocalPlayer(profile.getName(),
                profile.getId() == null ? "" : CompactUuid.key(profile.getId()))) {
            // Peer messages omit the local player. Read the live selection instead of looking
            // for an entry that the receiver deliberately never caches.
            boolean gomod = PlayerStandaloneCape.shouldUseGomodCapeForPeerSharing();
            return new PeerWear(profile.getName(), infoKey(profile),
                    gomod ? PlayerStandaloneCape.getActiveOptionKeyForRelay() : "",
                    gomod ? PlayerStandaloneCape.getCapeLocationForPeerSharing() : null,
                    !gomod, System.currentTimeMillis());
        }
        if (BY_UUID.isEmpty()) return null;
        if (profile.getId() != null) {
            PeerWear byUuid = BY_UUID.get(CompactUuid.key(profile.getId()));
            if (byUuid != null && !byUuid.isStale()) {
                return byUuid;
            }
        }
        return findPublishedWearByName(profile.getName());
    }

    private static PeerWear findPublishedWearByName(String name) {
        if (BY_UUID.isEmpty() || name == null || name.isEmpty()) return null;
        String mapped = NAME_TO_UUID.get(name.toLowerCase(Locale.ROOT));
        PeerWear wear = mapped == null ? null : BY_UUID.get(mapped);
        return wear != null && !wear.isStale() ? wear : null;
    }

    private static boolean isLocalPlayer(String name, String uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (!uuid.isEmpty() && mc.thePlayer.getUniqueID() != null) {
            return uuid.equals(CompactUuid.key(mc.thePlayer.getUniqueID()));
        }
        if (name != null && !name.isEmpty() && mc.thePlayer.getName() != null) {
            return name.equalsIgnoreCase(mc.thePlayer.getName());
        }
        return false;
    }

    /** Bounded, payload-free snapshot for /disguiserdebug; no HTTP, GL or native queries. */
    public static String describeManualCape(LocalDisguise.RemoteDisguiseProfile target) {
        if (target == null || target.profile == null) return "target=<none>";
        GameProfile profile = target.profile;
        boolean local = isLocalPlayer(profile.getName(), profile.getId() == null ? "" : CompactUuid.key(profile.getId()));
        PeerWear wear = findPublishedWear(profile, true);
        return "target=" + profile.getName() + " targetUuid=" + profile.getId()
                + " localTarget=" + local + " localDisguiseActive=" + LocalDisguise.isEnabled()
                + " wearSource=" + (local ? "local-live" : wear == null ? "missing-or-stale" : "relay-cache")
                + " selectedKey=" + (wear == null ? "<none>" : wear.optionKey)
                + " peerUsesDefaultCape=" + (wear != null && wear.useDefaultCape)
                + " selectedTexture=" + (wear == null ? null : wear.location)
                + " wearAgeMs=" + (wear == null ? -1 : Math.max(0, System.currentTimeMillis() - wear.lastSeenMs))
                + " publicCape=" + target.capeLocation + " resolvedCape=" + capeForManualTarget(target)
                + " capeLookup={" + DisguiserRelay.describeTargetCapeLookup(profile) + "}";
    }

    private static void pruneStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PeerWear> e : BY_UUID.entrySet()) {
            PeerWear wear = e.getValue();
            if (wear != null && now - wear.lastSeenMs > PEER_TTL_MS
                    && !e.getKey().equals(localDisguiseCapeTargetUuid)) {
                BY_UUID.remove(e.getKey(), wear);
            }
        }
    }

    private static String firstNonEmpty(JsonObject o, String... keys) {
        if (o == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || !o.has(key) || o.get(key).isJsonNull()) {
                continue;
            }
            String v = o.get(key).getAsString();
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    private static boolean booleanValue(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normUuid(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return s.length() == 32 ? s : "";
    }

    private static final class PeerWear {
        final String name;
        final String uuid;
        final String optionKey;
        final ResourceLocation location;
        final boolean useDefaultCape;
        final long lastSeenMs;

        PeerWear(String name, String uuid, String optionKey, ResourceLocation location,
                boolean useDefaultCape, long lastSeenMs) {
            this.name = name == null ? "" : name;
            this.uuid = uuid == null ? "" : uuid;
            this.optionKey = optionKey == null ? "" : optionKey;
            this.location = location;
            this.useDefaultCape = useDefaultCape;
            this.lastSeenMs = lastSeenMs;
        }

        ResourceLocation locationForThisClient() {
            return useDefaultCape ? null : location;
        }

        boolean isNoCapeSelected() {
            return !useDefaultCape && "gomod:none".equals(optionKey);
        }

        boolean hasCapeOverride() {
            return locationForThisClient() != null || isNoCapeSelected();
        }

        boolean shouldUseRemoteLunarCloakOnThisClient() {
            return false;
        }

        boolean isStale() {
            return System.currentTimeMillis() - lastSeenMs > PEER_TTL_MS;
        }
    }
}
