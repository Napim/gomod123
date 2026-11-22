package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds relay inbox keys so DMs/screenshots reach the recipient's gomod Social id (preferred)
 * and legacy Minecraft name aliases.
 */
public final class GomodSocialRelayKeys {

    private GomodSocialRelayKeys() {}

    public static void attachDmTarget(JsonObject payload, String friendName) {
        if (payload == null) {
            return;
        }
        GomodSocialIdentity.attachSender(payload);
        String targetSocialId = ConfigManager.getFriendSocialId(friendName);
        String senderSocialId = GomodSocialIdentity.socialId();
        if (targetSocialId != null && !targetSocialId.isEmpty()
                && (senderSocialId == null || !targetSocialId.equalsIgnoreCase(senderSocialId))) {
            payload.addProperty("targetSocialId", targetSocialId);
        }
        MojangUsernameResolver.MojangProfile profile = MojangUsernameResolver.resolveProfile(friendName);
        if (profile != null && profile.id != null && !profile.id.isEmpty()) {
            payload.addProperty("targetMcProfileUuid", profile.id.replace("-", ""));
        }
        payload.add("targetInboxKeys", inboxKeysForFriend(friendName));
        payload.add("targetSocialInboxIds", socialInboxIdsForTarget(friendName));
    }

    public static void attachFriendRequest(JsonObject payload, String targetName) {
        if (payload == null) {
            return;
        }
        GomodSocialIdentity.attachSender(payload);
        String target = targetName == null ? "" : targetName.trim();
        MojangUsernameResolver.MojangProfile profile = MojangUsernameResolver.resolveProfile(target);
        if (profile != null) {
            if (profile.name != null && !profile.name.isEmpty()) {
                target = profile.name;
                payload.addProperty("to", target);
            }
            if (profile.id != null && !profile.id.isEmpty()) {
                payload.addProperty("targetMcProfileUuid", profile.id.replace("-", ""));
            }
        }
        String targetSocialId = ConfigManager.getFriendSocialId(target);
        if (targetSocialId != null && !targetSocialId.isEmpty()) {
            payload.addProperty("toSocialId", targetSocialId);
        }
        payload.add("targetSocialInboxIds", socialInboxIdsForTarget(target));
        JsonArray keys = inboxKeysForFriend(target);
        addKey(keys, target);
        if (profile != null && profile.name != null) {
            addKey(keys, profile.name);
        }
        payload.add("targetInboxKeys", keys);
    }

    public static JsonArray inboxKeysForFriend(String friendName) {
        JsonArray keys = new JsonArray();
        if (friendName == null || friendName.trim().isEmpty()) {
            return keys;
        }
        String stored = ConfigManager.getGomodFriendDisplayName(friendName.trim());
        addKey(keys, stored);
        String onlineIgn = FriendListScanner.findOnlineIgnForFriend(stored);
        if (onlineIgn != null) {
            addKey(keys, onlineIgn);
        }
        return keys;
    }

    /** Social inbox ids for a target player only (never includes the local sender). */
    public static JsonArray socialInboxIdsForTarget(String targetName) {
        JsonArray ids = new JsonArray();
        String targetSocialId = ConfigManager.getFriendSocialId(targetName);
        String senderSocialId = GomodSocialIdentity.socialId();
        if (targetSocialId != null && !targetSocialId.isEmpty()
                && (senderSocialId == null || !targetSocialId.equalsIgnoreCase(senderSocialId))) {
            addSocialId(ids, targetSocialId);
        }
        return ids;
    }

    /** @deprecated use {@link #socialInboxIdsForTarget(String)} */
    public static JsonArray socialInboxIdsForFriend(String friendName) {
        return socialInboxIdsForTarget(friendName);
    }

    private static void addSocialId(JsonArray ids, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (id.equalsIgnoreCase(ids.get(i).getAsString())) {
                return;
            }
        }
        ids.add(new com.google.gson.JsonPrimitive(id));
    }

    private static void addKey(JsonArray keys, String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String n = name.trim();
        for (int i = 0; i < keys.size(); i++) {
            if (n.equalsIgnoreCase(keys.get(i).getAsString())) {
                return;
            }
        }
        keys.add(new com.google.gson.JsonPrimitive(n));
    }
}
