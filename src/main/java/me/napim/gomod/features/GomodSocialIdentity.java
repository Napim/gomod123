package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.util.ClientPlayerIdentity;
import net.minecraft.client.Minecraft;

/**
 * Stable gomod Social identity for this installation. Different people sharing one Minecraft
 * login still get separate Social data and relay inboxes when they use different gomod installs
 * (each has its own {@link ConfigManager#getOrCreateGomodSocialId()}).
 */
public final class GomodSocialIdentity {

    private GomodSocialIdentity() {}

    public static String socialId() {
        return ConfigManager.getOrCreateGomodSocialId();
    }

    /** Stable relay/dashboard label for the loader-specific distribution in use. */
    public static String clientBuild() {
        return GomodFixes.isWeaveRuntime() ? "lunar" : "forge";
    }

    /** Distribution flavor, embedded at build time and sent only for relay diagnostics. */
    public static String releaseFlavor() {
        return GomodReleaseBuild.PUBLIC_RELEASE ? "public" : "dev";
    }

    public static String profileUuidUndashed(Minecraft mc) {
        try {
            if (mc != null && mc.getSession() != null) {
                String sessionId = normalizeUuid(mc.getSession().getPlayerID());
                if (!sessionId.isEmpty()) {
                    return sessionId;
                }
            }
        } catch (Throwable ignored) {
        }
        return ClientPlayerIdentity.uuidUndashed(mc);
    }

    private static String normalizeUuid(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        if (value.length() != 32) return "";
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return "";
        }
        return value;
    }

    public static void attachSender(JsonObject payload) {
        if (payload == null) {
            return;
        }
        payload.addProperty("gomodSocialId", socialId());
        payload.addProperty("senderSocialId", socialId());
        payload.addProperty("clientBuild", clientBuild());
        payload.addProperty("releaseFlavor", releaseFlavor());
        attachRelayPlayer(payload, Minecraft.getMinecraft());
    }

    public static void attachPoll(JsonObject payload) {
        if (payload == null) {
            return;
        }
        payload.addProperty("gomodSocialId", socialId());
        payload.addProperty("modVersion", GomodVersion.get());
        payload.addProperty("clientBuild", clientBuild());
        payload.addProperty("releaseFlavor", releaseFlavor());
        String jarSha256 = GomodJarSha256.current();
        if (!jarSha256.isEmpty()) payload.addProperty("modJarSha256", jarSha256);
    }

    /** In-game profile UUID + IGN for relay presence (not a login token). */
    public static void attachRelayPlayer(JsonObject payload, Minecraft mc) {
        if (payload == null || mc == null) {
            return;
        }
        String name = GomodSocialPresence.sessionPlayerName(mc);
        if (!name.isEmpty()) {
            payload.addProperty("player", name);
        }
        String uuid = profileUuidUndashed(mc);
        if (!uuid.isEmpty()) {
            payload.addProperty("mcProfileUuid", uuid);
            payload.addProperty("playerUuid", uuid);
        }
    }
}
