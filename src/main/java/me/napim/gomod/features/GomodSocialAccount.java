package me.napim.gomod.features;

import me.napim.gomod.util.ClientPlayerIdentity;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Per-gomod-install Social storage paths ({@link GomodSocialIdentity}), with one-time migration
 * from older per-Minecraft-UUID file names.
 */
public final class GomodSocialAccount {

    private static volatile String cachedKey = "";
    /** Per-Minecraft-profile friends cache key (uuid:…), not the gomod Social install id. */
    private static volatile String cachedFriendsKey = "";

    private GomodSocialAccount() {
    }

    /** Legacy per-Minecraft-session key (used only for migration). */
    public static String legacyMcIdentityKey(Minecraft mc) {
        if (mc == null) {
            return "unknown";
        }
        try {
            String uuid = ClientPlayerIdentity.uuidUndashed(mc);
            if (uuid != null && !uuid.isEmpty()) {
                return uuid;
            }
            String name = ClientPlayerIdentity.displayName(mc);
            if (name != null && !name.isEmpty()) {
                return "name:" + name.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    /** {@code gomod123/gomodSocial_<socialId>.json} */
    public static File dataFile(String baseName) {
        String key = sanitizeFileToken(cachedKey);
        if (key.isEmpty()) {
            key = "unknown";
        }
        return new File(ConfigManager.getGomodDataDirectory(), baseName + "_" + key + ".json");
    }

    /** Friends list bucket: one entry per logged-in Minecraft profile (UUID preferred). */
    public static String friendsAccountKey(Minecraft mc) {
        String uuid = GomodSocialIdentity.profileUuidUndashed(mc);
        if (uuid != null && !uuid.isEmpty()) {
            return "uuid:" + uuid;
        }
        String socialKey = GomodSocialIdentity.socialId();
        if (socialKey != null && !socialKey.isEmpty()) {
            return "sid:" + socialKey;
        }
        return legacyMcIdentityKey(mc);
    }

    public static void refresh(Minecraft mc) {
        String socialKey = GomodSocialIdentity.socialId();
        String legacyMcKey = legacyMcIdentityKey(mc);
        String friendsKey = friendsAccountKey(mc);
        if (!friendsKey.equals(cachedFriendsKey)) {
            ConfigManager.syncGomodFriendsForAccountKey(friendsKey);
            ConfigManager.migrateGomodFriendsBucket(legacyMcKey, friendsKey);
            ConfigManager.migrateGomodFriendsBucket(socialKey, friendsKey);
            cachedFriendsKey = friendsKey;
        }
        if (!socialKey.equals(cachedKey)) {
            maybeMigrateDataFiles(socialKey, legacyMcKey);
            cachedKey = socialKey;
        }
    }

    public static String currentKey() {
        return cachedKey;
    }

    public static String currentFriendsKey() {
        return cachedFriendsKey;
    }

    private static void maybeMigrateDataFiles(String socialKey, String legacyMcKey) {
        if (legacyMcKey == null || legacyMcKey.isEmpty() || legacyMcKey.equals(socialKey)) {
            return;
        }
        String newTok = sanitizeFileToken(socialKey);
        String oldTok = sanitizeFileToken(legacyMcKey);
        if (newTok.isEmpty() || oldTok.isEmpty() || newTok.equals(oldTok)) {
            return;
        }
        String[] bases = { "gomodSocial", "gomodSocialGroups", "gomodSocialForums" };
        File dir = ConfigManager.getGomodDataDirectory();
        for (String base : bases) {
            File dest = new File(dir, base + "_" + newTok + ".json");
            if (dest.isFile()) {
                continue;
            }
            File src = new File(dir, base + "_" + oldTok + ".json");
            if (!src.isFile()) {
                continue;
            }
            try {
                Files.copy(src.toPath(), dest.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static String sanitizeFileToken(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ':') {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
