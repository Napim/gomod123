package me.napim.gomod.features;

import me.napim.gomod.util.CompactUuid;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relay-backed held-item cosmetic for the client-only Blitz pregame dye.
 * Vanilla clients continue to see the server's real held item.
 */
public final class GomodPeerPregameDye {
    public static final String RELAY_FIELD = "blitzPregamePlayerDyeMeta";
    private static final long TTL_MS = GomodSocialPresence.PRESENCE_TTL_MS;
    private static final Map<String, PeerState> BY_UUID = new ConcurrentHashMap<String, PeerState>();
    private static final Map<String, String> NAME_TO_UUID = new ConcurrentHashMap<String, String>();

    private GomodPeerPregameDye() {
    }

    public static void attachLocalStateTo(JsonObject payload) {
        if (payload != null) {
            payload.addProperty(RELAY_FIELD, BlitzPregamePlayerToggle.getLocalHeldDyeMeta());
        }
    }

    public static void onPollResponse(JsonObject response) {
        if (response != null && response.has("gomodPeersOnServer")
                && response.get("gomodPeersOnServer").isJsonArray()) {
            ingestArray(response.getAsJsonArray("gomodPeersOnServer"));
        }
    }

    public static void onRelayPeerMessage(JsonObject message) {
        if (message != null && booleanValue(message, "relayPresenceHeartbeat")) {
            if (message.has(RELAY_FIELD) && !message.get(RELAY_FIELD).isJsonNull()) ingest(message);
            else refreshPeerTimestamp(message);
        } else {
            ingest(message);
        }
    }

    public static ItemStack heldDyeFor(EntityLivingBase entity) {
        if (!(entity instanceof EntityPlayer) || isLocal((EntityPlayer) entity)) {
            return null;
        }
        PeerState state = find((EntityPlayer) entity);
        if (state == null || state.isStale() || (state.meta != 8 && state.meta != 10)) {
            return null;
        }
        return new ItemStack(Items.dye, 1, state.meta);
    }

    private static void ingestArray(JsonArray array) {
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) {
                ingest(element.getAsJsonObject());
            }
        }
    }

    private static void ingest(JsonObject object) {
        if (object == null || !object.has(RELAY_FIELD) || object.get(RELAY_FIELD).isJsonNull()) {
            return;
        }
        String name = first(object, "name", "player", "from", "sender");
        String uuid = normUuid(first(object, "playerUuid", "mcProfileUuid", "uuid"));
        if (name.isEmpty() && uuid.isEmpty()) {
            return;
        }
        if (uuid.isEmpty()) {
            uuid = "name:" + name.toLowerCase(Locale.ROOT);
        }
        if (isLocal(name, uuid)) {
            return;
        }
        int meta;
        try {
            meta = object.get(RELAY_FIELD).getAsInt();
        } catch (Throwable ignored) {
            meta = -1;
        }
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        BY_UUID.put(uuid, new PeerState(meta, ts));
        if (!name.isEmpty()) {
            NAME_TO_UUID.put(name.toLowerCase(Locale.ROOT), uuid);
        }
    }

    private static void refreshPeerTimestamp(JsonObject object) {
        String name = first(object, "name", "player", "from", "sender");
        String uuid = normUuid(first(object, "playerUuid", "mcProfileUuid", "uuid"));
        if (name.isEmpty() && uuid.isEmpty() || isLocal(name, uuid)) return;
        if (uuid.isEmpty()) uuid = "name:" + name.toLowerCase(Locale.ROOT);
        long ts = object.has("ts") && !object.get("ts").isJsonNull()
                ? object.get("ts").getAsLong() : System.currentTimeMillis();
        PeerState previous = BY_UUID.get(uuid);
        if (previous != null && previous.ts <= ts) BY_UUID.put(uuid, new PeerState(previous.meta, ts));
        if (!name.isEmpty()) NAME_TO_UUID.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    private static PeerState find(EntityPlayer player) {
        if (BY_UUID.isEmpty()) return null;
        if (player.getUniqueID() != null) {
            PeerState state = BY_UUID.get(CompactUuid.key(player.getUniqueID()));
            if (state != null) {
                return state;
            }
        }
        String name = player.getName();
        String key = name == null ? null : NAME_TO_UUID.get(name.toLowerCase(Locale.ROOT));
        return key == null ? null : BY_UUID.get(key);
    }

    private static boolean isLocal(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && player == mc.thePlayer;
    }

    private static boolean isLocal(String name, String uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (!uuid.isEmpty() && mc.thePlayer.getUniqueID() != null
                && uuid.equals(normUuid(mc.thePlayer.getUniqueID().toString()))) {
            return true;
        }
        return !name.isEmpty() && name.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static String first(JsonObject object, String... keys) {
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
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    && object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String normUuid(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return value.length() == 32 ? value : "";
    }

    private static final class PeerState {
        final int meta;
        final long ts;

        PeerState(int meta, long ts) {
            this.meta = meta;
            this.ts = ts;
        }

        boolean isStale() {
            return ts <= 0L || System.currentTimeMillis() - ts > TTL_MS;
        }
    }
}
