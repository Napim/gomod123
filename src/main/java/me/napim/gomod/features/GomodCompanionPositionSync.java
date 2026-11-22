package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.MinecraftFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Native companion render tracking and Goparky reaction timers on the authenticated Gomod socket. */
public final class GomodCompanionPositionSync {
    private static final long INTERVAL_MS = 100L;
    private static final long STALE_MS = 1500L;
    private static final long MAX_TAUNT_MS = 5000L;
    private static final long MAX_VICTIM_POSITION_AGE_MS = 2500L;
    private static final int MAX_COMPANIONS = 16;
    private static final long GOPARKY_ID = 2_000_418_004L;
    private static final Map<String, Actor> ACTORS = new HashMap<>();
    private static final Map<Object, Actor> ACTOR_LOOKUP = new java.util.WeakHashMap<>();
    private static final Map<String, Motion> PEERS = new HashMap<>();
    private static final Map<UUID, Stream> STREAMS = new HashMap<>();
    private static final Map<String, VictimPosition> RECENT_PLAYER_POSITIONS = new HashMap<>();
    private static Object lastWorld;
    private static Object positionWorld;
    private static UUID lastOwner;
    private static String lastServer = "";
    private static String session = "";
    private static long sequence;
    private static long nextSendAtMs;
    private static boolean sentNonEmpty;

    private GomodCompanionPositionSync() {}

    public static void rememberActor(Object actor, Object owner, Object definition) {
        if (actor == null || owner == null || definition == null) return;
        try {
            UUID ownerId = (UUID) owner.getClass().getMethod("bridge$getUniqueID").invoke(owner);
            long id = LunarCosmeticRuntime.id(definition);
            Minecraft mc = Minecraft.getMinecraft();
            if (ownerId == null || id <= 0L || mc == null) return;
            Actor entry = new Actor(actor, ownerId, id, mc.theWorld);
            Actor old = ACTORS.put(key(ownerId, id), entry);
            if (old != null) ACTOR_LOOKUP.remove(old.actor.get());
            ACTOR_LOOKUP.put(actor, entry);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnce("gomod.companion_positions.actor_registration_failed",
                    error.getClass().getSimpleName(), "errorType=" + error.getClass().getSimpleName());
        }
    }

    public static void tick(Minecraft mc) {
        if (!GomodFixes.isWeaveRuntime() || mc == null) return;
        rememberPlayerPositions(mc);
        UUID owner = mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
        String server = normalizeServer(GomodSocialPresence.resolveLocalServer(mc));
        if (mc.theWorld != lastWorld || !java.util.Objects.equals(lastOwner, owner) || !server.equals(lastServer)) {
            lastWorld = mc.theWorld;
            lastOwner = owner;
            lastServer = server;
            PEERS.clear();
            STREAMS.clear();
            session = UUID.randomUUID().toString();
            sequence = 0L;
            nextSendAtMs = 0L;
            sentNonEmpty = false;
        }
        long now = System.currentTimeMillis();
        ACTORS.values().removeIf(actor -> actor.actor.get() == null || actor.world != mc.theWorld);
        ACTOR_LOOKUP.values().removeIf(actor -> actor.world != mc.theWorld);
        PEERS.values().removeIf(motion -> now - motion.receivedAt > STALE_MS);
        STREAMS.values().removeIf(stream -> now - stream.receivedAt > STALE_MS);
        if (mc.thePlayer == null || mc.theWorld == null || now < nextSendAtMs
                || GomodSocialStore.isAppearOffline()) return;
        nextSendAtMs = now + INTERVAL_MS;
        if (server.isEmpty() || "mainmenu".equals(server) || "singleplayer".equals(server)) return;
        JsonArray states = new JsonArray();
        for (Actor entry : ACTORS.values()) {
            Object actor = entry.actor.get();
            if (entry.id != GOPARKY_ID || !owner.equals(entry.owner) || actor == null
                    || now - entry.renderedAt > 800L) continue;
            try {
                long hurt = GoparkyBlitzReactions.getLocalHurtRemaining(actor);
                long taunt = GoparkyBlitzReactions.getLocalTauntRemaining(actor);
                if (hurt <= 0L && taunt <= 0L) continue;
                double[] position = entry.renderedPosition;
                if (position == null) continue;
                // The deployed relay requires these legacy fields. Send only active
                // reaction events; updated viewers ignore their coordinates entirely.
                double dx = position[0] - mc.thePlayer.posX;
                double dy = position[1] - mc.thePlayer.posY;
                double dz = position[2] - mc.thePlayer.posZ;
                if (!bounded(dx, 32D) || !bounded(dy, 32D) || !bounded(dz, 32D)) continue;
                JsonObject state = new JsonObject();
                state.addProperty("id", entry.id);
                state.addProperty("dx", rounded(dx));
                state.addProperty("dy", rounded(dy));
                state.addProperty("dz", rounded(dz));
                state.addProperty("yaw", rounded(position[3] - 180.0D));
                state.addProperty("hurt", hurt);
                state.addProperty("taunt", taunt);
                states.add(state);
                if (states.size() >= MAX_COMPANIONS) break;
            } catch (Throwable ignored) {}
        }
        if (states.size() == 0 && !sentNonEmpty) return;
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "companion_positions");
        packet.addProperty("server", server);
        packet.addProperty("session", session);
        packet.addProperty("sequence", ++sequence);
        packet.add("companions", states);
        if (ShareInvManager.sendCompanionPositions(packet)) {
            sentNonEmpty = states.size() != 0;
            LunarConnectionDiagnostics.recordOnce("gomod.companion_positions.sent", "active",
                    "active=true intervalMs=100 reactionsOnly=true sharedMovement=false");
        }
    }

    /** Invoked on Minecraft's thread by ShareInvManager. */
    public static void receive(JsonObject packet) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;
        try {
            String server = normalizeServer(packet.get("server").getAsString());
            if (!server.equals(normalizeServer(GomodSocialPresence.resolveLocalServer(mc)))) return;
            UUID owner = parseUuid(packet.get("playerUuid").getAsString());
            if (owner.equals(mc.thePlayer.getUniqueID())) return;
            EntityPlayer entity = mc.theWorld.getPlayerEntityByUUID(owner);
            if (entity == null) return;
            String incomingSession = packet.get("session").getAsString();
            long seq = packet.get("sequence").getAsLong();
            JsonArray states = packet.getAsJsonArray("companions");
            if (states == null || states.size() > MAX_COMPANIONS || seq <= 0 || incomingSession.length() > 64) return;
            long now = System.currentTimeMillis();
            Stream stream = STREAMS.get(owner);
            if (stream != null && incomingSession.equals(stream.session) && seq <= stream.sequence) return;
            STREAMS.put(owner, new Stream(incomingSession, seq, now));
            java.util.Set<String> included = new java.util.HashSet<>();
            for (JsonElement element : states) {
                JsonObject state = element.getAsJsonObject();
                long id = state.get("id").getAsLong();
                double dx = state.get("dx").getAsDouble(), dy = state.get("dy").getAsDouble();
                double dz = state.get("dz").getAsDouble(), yaw = state.get("yaw").getAsDouble();
                if (id != GOPARKY_ID || !bounded(dx, 32D) || !bounded(dy, 32D) || !bounded(dz, 32D)
                        || !bounded(yaw, 3600D)) continue;
                String key = key(owner, id);
                included.add(key);
                Motion previous = PEERS.get(key);
                if (previous != null && incomingSession.equals(previous.session) && seq <= previous.sequence) continue;
                long hurt = state.has("hurt") ? Math.max(0L, Math.min(420L, state.get("hurt").getAsLong())) : 0L;
                long taunt = state.has("taunt")
                        ? Math.max(0L, Math.min(MAX_TAUNT_MS, state.get("taunt").getAsLong())) : 0L;
                PEERS.put(key, new Motion(owner, incomingSession, seq, now, now + hurt, now + taunt));
            }
            for (Iterator<Map.Entry<String, Motion>> it = PEERS.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Motion> entry = it.next();
                if (owner.equals(entry.getValue().owner) && !included.contains(entry.getKey())) it.remove();
            }
            LunarConnectionDiagnostics.recordOnce("gomod.companion_positions.received", "active",
                    "active=true scope=same-server reactionsOnly=true sharedMovement=false");
        } catch (Throwable ignored) {}
    }

    /** Legacy entry point: peer coordinates no longer replace Lunar's native following. */
    public static double[] renderPosition(Object actor, float partialTicks) {
        return null;
    }

    /** Adjust Lunar's per-frame record without Mixin's Args helper classes. */
    public static Object adjustRenderedFrame(Object frame, Object actor, float partialTicks) {
        if (frame == null || actor == null) return frame;
        try {
            double[] position = LunarCosmeticRuntime.framePosition(frame);
            double[] recoil = GoparkyBlitzReactions.getGoparkyRenderRecoilOffset(actor);
            if (recoil != null) {
                position[0] += recoil[0]; position[2] += recoil[1];
            }
            Actor entry = findActor(actor);
            if (entry != null) { entry.renderedAt = System.currentTimeMillis(); entry.renderedPosition = position.clone(); }
            return recoil == null ? frame
                    : LunarCosmeticRuntime.copyFrame(frame, position[0], position[1], position[2], (float) position[3]);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnce("gomod.companion_positions.frame_adjust_failed",
                    error.getClass().getSimpleName(), "result=failed errorType=" + error.getClass().getSimpleName());
        }
        return frame;
    }

    public static boolean hasRenderedGoparky(UUID owner) {
        Actor entry = owner == null ? null : ACTORS.get(key(owner, 2_000_418_004L));
        Minecraft mc = Minecraft.getMinecraft();
        return entry != null && entry.actor.get() != null && mc != null && entry.world == mc.theWorld
                && System.currentTimeMillis() - entry.renderedAt <= STALE_MS;
    }
    public static double[] renderedGoparkyPosition(UUID owner) {
        Actor entry = owner == null ? null : ACTORS.get(key(owner, 2_000_418_004L));
        return hasRenderedGoparky(owner) && entry.renderedPosition != null ? entry.renderedPosition.clone() : null;
    }

    /** Includes local and remote Goparkys actually rendered in this world. */
    public static java.util.Set<UUID> renderedGoparkyOwners() {
        java.util.Set<UUID> owners = new java.util.HashSet<>();
        for (Actor entry : ACTORS.values()) {
            if (entry.id == GOPARKY_ID && hasRenderedGoparky(entry.owner)) owners.add(entry.owner);
        }
        return owners;
    }

    public static long hurtRemaining(Object actor) {
        Actor entry = findActor(actor);
        if (entry == null || entry.id != 2_000_418_004L) return 0L;
        Motion motion = PEERS.get(key(entry.owner, entry.id));
        return motion == null ? 0L : Math.max(0L, motion.hurtUntil - System.currentTimeMillis());
    }

    public static UUID cosmeticAppearanceOwner(Object actor) {
        Actor entry = findActor(actor); return entry == null ? null : entry.owner;
    }
    public static long cosmeticAppearanceId(Object actor) {
        Actor entry = findActor(actor); return entry == null ? 0L : entry.id;
    }

    public static long tauntRemaining(Object actor) {
        Actor entry = findActor(actor);
        if (entry == null || entry.id != 2_000_418_004L) return 0L;
        Motion motion = PEERS.get(key(entry.owner, entry.id));
        return motion == null ? 0L : Math.max(0L, motion.tauntUntil - System.currentTimeMillis());
    }

    /** Recent pre-death position for the player named by the kill feed or debug command. */
    public static double[] findRecentPlayerPosition(Minecraft mc, String playerName) {
        if (mc == null || mc.theWorld == null || playerName == null || playerName.trim().isEmpty()) return null;
        rememberPlayerPositions(mc);
        String wanted = normalizePlayerName(playerName);
        long now = System.currentTimeMillis();
        VictimPosition cached = RECENT_PLAYER_POSITIONS.get(wanted);
        if (cached != null && cached.world == mc.theWorld && now - cached.observedAtMs <= MAX_VICTIM_POSITION_AGE_MS) {
            return cached.position.clone();
        }
        for (Object value : mc.theWorld.playerEntities) {
            if (!(value instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) value;
            if (!matchesPlayer(player, wanted)) continue;
            return new double[] {player.posX, player.posY, player.posZ};
        }
        return null;
    }

    private static void rememberPlayerPositions(Minecraft mc) {
        if (mc.theWorld == null) return;
        long now = System.currentTimeMillis();
        if (positionWorld != mc.theWorld) {
            RECENT_PLAYER_POSITIONS.clear();
            positionWorld = mc.theWorld;
        }
        for (Object value : mc.theWorld.playerEntities) {
            if (!(value instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) value;
            String name = player.getName();
            if (name == null || name.isEmpty()) continue;
            // Keep the last live tick when a death update has arrived; spectators may
            // already be moving away from the spot where the kill happened.
            if (!player.isEntityAlive() || player.isDead) continue;
            double x = player.posX, y = player.posY, z = player.posZ;
            if (!bounded(x, 30_000_000D) || !bounded(y, 4096D) || !bounded(z, 30_000_000D)) continue;
            VictimPosition position = new VictimPosition(mc.theWorld, now, new double[] {x, y, z});
            String nameKey = normalizePlayerName(name);
            RECENT_PLAYER_POSITIONS.put(nameKey, position);
            IChatComponent displayName = player.getDisplayName();
            String display = displayName == null ? null : displayName.getUnformattedText();
            if (display != null && !display.isEmpty() && !display.equals(name)) {
                String displayKey = normalizePlayerName(display);
                if (!displayKey.equals(nameKey)) RECENT_PLAYER_POSITIONS.put(displayKey, position);
            }
        }
        RECENT_PLAYER_POSITIONS.values().removeIf(position -> position.world != mc.theWorld
                || now - position.observedAtMs > MAX_VICTIM_POSITION_AGE_MS * 3L);
    }

    private static boolean matchesPlayer(EntityPlayer player, String normalized) {
        if (normalized.isEmpty()) return false;
        if (normalized.equals(normalizePlayerName(player.getName()))) return true;
        IChatComponent displayName = player.getDisplayName();
        return displayName != null && normalized.equals(normalizePlayerName(displayName.getUnformattedText()));
    }

    private static String normalizePlayerName(String raw) {
        String plain = MinecraftFormatting.stripSectionCodes(raw).trim();
        // Hypixel messages may prepend one or more rank/team labels to the player's IGN.
        while (plain.startsWith("[")) {
            int close = plain.indexOf(']');
            if (close < 1 || close > 40) break;
            plain = plain.substring(close + 1).trim();
        }
        StringBuilder normalized = null;
        for (int index = 0; index < plain.length(); index++) {
            char value = plain.charAt(index);
            if (value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_') {
                if (normalized != null) normalized.append(value);
            } else {
                if (normalized == null) normalized = new StringBuilder(plain.length()).append(plain, 0, index);
                if (value >= 'A' && value <= 'Z') normalized.append((char) (value + ('a' - 'A')));
            }
        }
        return normalized == null ? plain : normalized.toString();
    }

    public static boolean isGoparkyActor(Object actor) {
        Actor entry = findActor(actor);
        return entry != null && entry.id == 2_000_418_004L;
    }

    private static Actor findActor(Object actor) {
        return actor == null ? null : ACTOR_LOOKUP.get(actor);
    }

    private static boolean bounded(double v, double max) { return Double.isFinite(v) && Math.abs(v) <= max; }
    private static double rounded(double v) { return Math.rint(v * 1000D) / 1000D; }
    private static String normalizeServer(String raw) { return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT); }
    private static String key(UUID owner, long id) { return owner + ":" + id; }
    private static UUID parseUuid(String raw) {
        String s = raw.replace("-", "");
        if (s.length() != 32) throw new IllegalArgumentException("uuid");
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20));
    }

    private static final class Actor {
        final WeakReference<Object> actor;
        final UUID owner;
        final long id;
        final Object world;
        long renderedAt;
        double[] renderedPosition;
        Actor(Object actor, UUID owner, long id, Object world) {
            this.actor = new WeakReference<>(actor);
            this.owner = owner; this.id = id; this.world = world;
        }
    }

    private static final class Stream {
        final String session;
        final long sequence, receivedAt;
        Stream(String session, long sequence, long receivedAt) {
            this.session = session; this.sequence = sequence; this.receivedAt = receivedAt;
        }
    }

    private static final class VictimPosition {
        final Object world;
        final long observedAtMs;
        final double[] position;
        VictimPosition(Object world, long observedAtMs, double[] position) {
            this.world = world; this.observedAtMs = observedAtMs; this.position = position;
        }
    }

    private static final class Motion {
        final UUID owner;
        final String session;
        final long sequence, receivedAt, hurtUntil, tauntUntil;
        Motion(UUID owner, String session, long seq, long now,
               long hurtUntil, long tauntUntil) {
            this.owner = owner; this.session = session; this.sequence = seq; this.receivedAt = now;
            this.hurtUntil = hurtUntil; this.tauntUntil = tauntUntil;
        }
    }
}
