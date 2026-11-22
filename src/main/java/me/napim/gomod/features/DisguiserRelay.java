package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Uses the existing account-authenticated relay; never sends Minecraft/Lunar credentials. */
public final class DisguiserRelay {
    private static final ConcurrentHashMap<UUID, String> CAPE_LOOKUPS = new ConcurrentHashMap<>();
    private DisguiserRelay() { }

    public static JsonObject context(String type) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null)
            throw new Exception("Join a server first.");
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("server", GomodSocialPresence.resolveLocalServer(mc));
        return body;
    }

    public static GameProfile sourceProfile(String name) throws Exception {
        String requestedName = name == null ? "" : name.trim();
        if (requestedName.isEmpty()) throw new Exception("Enter a player name.");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getNetHandler() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = info.getGameProfile();
                if (profile != null && profile.getId() != null && requestedName.equalsIgnoreCase(profile.getName())) return profile;
            }
        }
        // The source may be loaded nearby even if the server has already removed their TAB row.
        // A loaded entity still gives us the UUID the shared relay needs; do not fall back to a
        // name-only profile because that cannot identify nicked/aliased players across viewers.
        if (mc != null && mc.theWorld != null) {
            for (Object entity : mc.theWorld.playerEntities) {
                if (!(entity instanceof EntityPlayer)) continue;
                GameProfile profile = ((EntityPlayer) entity).getGameProfile();
                if (profile != null && profile.getId() != null && profile.getName() != null
                        && requestedName.equalsIgnoreCase(profile.getName())) {
                    return profile;
                }
            }
        }

        // A shared disguise is keyed by the source's visible name on each receiving client. The
        // source does not need to be loaded on the sender: give the relay a stable, valid UUID
        // scoped to this server and name. This is deliberately not represented as a Mojang UUID.
        String server = mc == null ? null : GomodSocialPresence.resolveLocalServer(mc);
        String normalizedServer = server == null ? "unknown-server" : server.trim().toLowerCase(Locale.ROOT);
        String normalizedName = requestedName.toLowerCase(Locale.ROOT);
        String identity = "gomod123:shared-disguiser-source:v1:" + normalizedServer + ":" + normalizedName;
        UUID stableRelayId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new GameProfile(stableRelayId, requestedName);
    }

    public static JsonObject request(JsonObject body) throws Exception {
        String endpoint = ConfigManager.getGlobalChatEndpoint();
        HttpURLConnection conn = (HttpURLConnection)new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setConnectTimeout(6000); conn.setReadTimeout(12000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            GomodRelayAuth.authorize(conn, endpoint);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = conn.getOutputStream()) { output.write(bytes); }
            int code = conn.getResponseCode();
            GomodRelayAuth.noteResponseCode(endpoint, code);
            if (code < 200 || code >= 300) throw new Exception("Relay HTTP " + code + " (shared disguiser may need a relay update).");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (result.size() + read > 1024 * 1024) throw new Exception("Relay response too large.");
                    result.write(buffer, 0, read);
                }
            }
            JsonObject response = new JsonParser().parse(new String(result.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!response.has("ok") || !response.get("ok").getAsBoolean()) throw new Exception("Relay rejected the disguise.");
            return response;
        } finally { conn.disconnect(); }
    }

    /** Upload the active, opted-in local full Lunar disguise profile once for on-demand peer fetches. */
    public static void publishFullLunarProfile(UUID targetUuid, String profileBase64) throws Exception {
        if (targetUuid == null || profileBase64 == null || profileBase64.isEmpty()) {
            throw new IllegalArgumentException("The shared Lunar appearance profile is unavailable.");
        }
        JsonObject body = context("disguiser_profile_set");
        body.addProperty("targetUuid", targetUuid.toString());
        body.addProperty("profileBase64", profileBase64);
        request(body);
    }

    public static void refreshTargetCape(GameProfile target) {
        if (target == null || target.getId() == null) return;
        if (CAPE_LOOKUPS.size() >= 128) CAPE_LOOKUPS.clear();
        recordCapeLookup(target.getId(), "pending");
        Thread worker = new Thread(() -> {
            try {
                JsonObject body = new JsonObject(); body.addProperty("type", "disguiser_cape");
                body.addProperty("targetUuid", target.getId().toString());
                JsonObject response = request(body);
                boolean capePresent = response.has("cape") && response.get("cape").isJsonObject();
                JsonObject cape = response.has("cape") && response.get("cape").isJsonObject()
                        ? response.getAsJsonObject("cape") : new JsonObject();
                cape.addProperty("name", target.getName()); cape.addProperty("playerUuid", target.getId().toString());
                cape.addProperty("standaloneCapeStatePresent", true);
                if (!cape.has("standaloneCapeOptionKey")) {
                    cape.addProperty("standaloneCapeOptionKey", "gomod:off");
                    cape.addProperty("standaloneCapeLunarOnWeave", true);
                }
                Minecraft mc = Minecraft.getMinecraft();
                String result = !response.has("cape") ? "unsupported-response" : !capePresent ? "no-saved-selection"
                        : "received keyPresent=" + (cape.has("standaloneCapeOptionKey")
                        && !cape.get("standaloneCapeOptionKey").getAsString().isEmpty())
                        + " usesDefaultCape=" + (cape.has("standaloneCapeLunarOnWeave")
                        && cape.get("standaloneCapeLunarOnWeave").getAsBoolean());
                recordCapeLookup(target.getId(), result);
                if (mc != null && response.has("cape")) mc.addScheduledTask(() -> {
                    GomodPeerStandaloneCape.onRelayPeerMessage(cape);
                    recordCapeLookup(target.getId(), result + " applied=true");
                });
            } catch (Exception unavailable) {
                recordCapeLookup(target.getId(), "unavailable errorType=" + unavailable.getClass().getSimpleName());
            }
        }, "gomod-disguiser-cape");
        worker.setDaemon(true); worker.start();
    }

    private static void recordCapeLookup(UUID target, String result) {
        CAPE_LOOKUPS.put(target, result);
        LunarConnectionDiagnostics.record("disguiser.target_cape", "result=" + result);
    }

    public static String describeTargetCapeLookup(GameProfile target) {
        String result = target == null || target.getId() == null ? null : CAPE_LOOKUPS.get(target.getId());
        return result == null ? "not-requested" : result;
    }
}
