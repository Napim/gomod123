package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.*;
import com.google.gson.*;
import me.napim.gomod.util.GameContext;
import java.util.*;
import java.util.regex.*;

/** Quiet sidebar identity shared by match-scoped relay features and input latches. */
public final class BlitzMatchIdentity {
    private static final Pattern ID = Pattern.compile("(?i)(?:^|\\s)(m\\d+[a-z0-9]+)(?=\\s|$)");
    private static final Pattern NON_PRINTABLE_ASCII = Pattern.compile("[^\\x20-\\x7e]");
    private static final Pattern LOCATION_SERVER = Pattern.compile("(?i)mini\\d+[a-z0-9]+");
    private static final Map<String, RawTeam> RAW_TEAMS = new LinkedHashMap<String, RawTeam>();
    private static Object handler;
    private static String lastSource = "missing";
    private static Object locationWorld, pendingWorld;
    private static String locationGame = "";
    private static long locationExpires, pendingUntil, nextProbeAt;
    private static long locationRequests, locationAnswers, locationTimeouts;
    private BlitzMatchIdentity() {}
    /** Capture before local scoreboard/privacy handlers mutate the packet or live team fields. */
    public static boolean onIncomingPacket(Packet<?> packet) {
        if (!(packet instanceof S3EPacketTeams || packet instanceof S02PacketChat
                || packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn)) {
            return false;
        }
        return captureIdentityPacket(packet);
    }

    private static synchronized boolean captureIdentityPacket(Packet<?> packet) {
        Minecraft mc = Minecraft.getMinecraft();
        sync(mc);
        if (packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn) {
            RAW_TEAMS.clear(); clearLocation(); return false;
        }
        if (packet instanceof S02PacketChat && consumeLocation((S02PacketChat) packet, mc)) return true;
        if (!(packet instanceof S3EPacketTeams) || handler == null) return false;
        S3EPacketTeams team = (S3EPacketTeams) packet;
        String name = team.getName();
        if (name == null || name.length() > 128) return false;
        int action = team.getAction();
        if (action == 1) { RAW_TEAMS.remove(name); return false; }
        RawTeam previous = RAW_TEAMS.get(name);
        if (action == 0 || action == 2) {
            RAW_TEAMS.put(name, new RawTeam(team.getPrefix(), team.getSuffix(), action == 0
                    ? boundedMembers(team.getPlayers()) : previous == null ? Collections.<String>emptySet() : previous.members));
        } else if ((action == 3 || action == 4) && previous != null) {
            Set<String> members = new LinkedHashSet<String>(previous.members);
            if (action == 3) members.addAll(boundedMembers(team.getPlayers()));
            else members.removeAll(boundedMembers(team.getPlayers()));
            RAW_TEAMS.put(name, new RawTeam(previous.prefix, previous.suffix, boundedMembers(members)));
        }
        while (RAW_TEAMS.size() > 256) RAW_TEAMS.remove(RAW_TEAMS.keySet().iterator().next());
        return false;
    }
    private static Set<String> boundedMembers(Collection<String> values) {
        Set<String> result = new LinkedHashSet<String>();
        if (values != null) for (String value : values) {
            if (value != null && value.length() <= 128) result.add(value);
            if (result.size() >= 64) break;
        }
        return result;
    }
    public static synchronized void clientTick(Minecraft mc) {
        sync(mc);
        if (!locationEligible(mc)) { clearLocation(); return; }
        long now = System.currentTimeMillis();
        if (pendingUntil > 0L && now >= pendingUntil) {
            pendingUntil = 0L; pendingWorld = null; locationTimeouts++;
        }
        if (pendingUntil > now || now < nextProbeAt || !resolve(mc).isEmpty()) return;
        // Hypixel omits its mini-server ID entirely from some live Blitz sidebars.
        // Ask once, then cache for this world; retries are capped at one per 15 seconds.
        pendingWorld = mc.theWorld; pendingUntil = now + 5000L; nextProbeAt = now + 15000L;
        locationRequests++;
        try { mc.thePlayer.sendChatMessage("/locraw"); }
        catch (RuntimeException failed) { pendingUntil = 0L; pendingWorld = null; }
    }
    private static boolean locationEligible(Minecraft mc) {
        return mc != null && mc.theWorld != null && mc.thePlayer != null && handler != null
                && (ConfigManager.isForceGameChatsEnabled() || ConfigManager.isSeeSpectatorsWhileAliveEnabled()
                    || ArmorDisguise.needsMatchLocation())
                && !GomodSocialStore.isAppearOffline() && !VisibleSpecs.isReplayContext()
                && GameContext.isOnHypixel(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc));
    }
    private static void clearLocation() {
        locationGame = ""; locationExpires = pendingUntil = 0L; pendingWorld = null;
    }
    private static void sync(Minecraft mc) {
        Object current = mc == null ? null : mc.getNetHandler();
        Object currentWorld = mc == null ? null : mc.theWorld;
        if (handler != current) { RAW_TEAMS.clear(); handler = current; lastSource = "missing"; clearLocation(); }
        if (locationWorld != currentWorld) {
            clearLocation(); locationWorld = currentWorld;
            // Armor leases must confirm the destination before expiring during a deathmatch teleport.
            // Keep the usual 15s retry cadence within a world; a new world may probe after at most 1s.
            if (ArmorDisguise.needsMatchLocation()) nextProbeAt = Math.min(nextProbeAt, System.currentTimeMillis() + 1000L);
        }
    }
    /** Consume only a bounded system JSON reply to our own outstanding query in this world. */
    private static boolean consumeLocation(S02PacketChat packet, Minecraft mc) {
        long now = System.currentTimeMillis();
        if (mc == null || handler == null || pendingWorld != mc.theWorld || pendingUntil <= now
                || (packet.getType() != 0 && packet.getType() != 1) || packet.getChatComponent() == null) return false;
        String text = packet.getChatComponent().getUnformattedText().trim();
        if (text.length() > 2048 || !text.startsWith("{") || !text.endsWith("}")) return false;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) return false;
            JsonObject json = parsed.getAsJsonObject();
            String server = string(json, "server");
            if (server.isEmpty() || (!json.has("gametype") && !json.has("lobbyname"))) return false;
            pendingUntil = 0L; pendingWorld = null; locationAnswers++;
            locationGame = ""; locationExpires = 0L;
            if ("SURVIVAL_GAMES".equals(string(json, "gametype")) && !json.has("lobbyname")
                    && LOCATION_SERVER.matcher(server).matches()) {
                locationGame = "hypixel:blitz:m" + server.substring(4).toLowerCase(Locale.ROOT);
                locationExpires = now + 60000L;
            }
            return true;
        } catch (RuntimeException malformed) { return false; }
    }
    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }
    public static synchronized String resolve(Minecraft mc) {
        sync(mc);
        lastSource = "missing";
        for (String line : HypixelScoreboardMapResolver.collectSidebarTextLines(mc)) {
            String id = extract(line);
            if (!id.isEmpty()) { lastSource = "visible_sidebar"; return id; }
        }
        if (mc == null || mc.theWorld == null || handler == null) return "";
        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) return "";
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) return "";
        // Only raw teams that still own a row in the CURRENT sidebar are eligible. Never use
        // a last-known server ID: removed rows, team replacement and reconnect must fail closed.
        for (Score score : board.getSortedScores(objective)) {
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            RawTeam raw = team == null ? null : RAW_TEAMS.get(team.getRegisteredName());
            if (raw == null || !raw.members.contains(score.getPlayerName())) continue;
            String id = extract(raw.prefix + score.getPlayerName() + raw.suffix);
            if (!id.isEmpty()) { lastSource = "raw_current_sidebar_team"; return id; }
        }
        if (!locationGame.isEmpty() && locationWorld == mc.theWorld && System.currentTimeMillis() < locationExpires) {
            lastSource = "current_world_locraw"; return locationGame;
        }
        return "";
    }
    public static synchronized String debugStatus(Minecraft mc) {
        String id = resolve(mc);
        return "game=" + id + " identitySource=" + lastSource + " rawTeams=" + RAW_TEAMS.size()
                + " locationRequests=" + locationRequests + " locationAnswers=" + locationAnswers
                + " locationTimeouts=" + locationTimeouts + " locationPending=" + (pendingUntil > System.currentTimeMillis());
    }
    private static String extract(String text) {
        if (text == null) return "";
        String plain = HypixelScoreboardMapResolver.stripFormatting(text);
        Matcher match = ID.matcher(NON_PRINTABLE_ASCII.matcher(plain).replaceAll(""));
        return match.find() ? "hypixel:blitz:" + match.group(1).toLowerCase(Locale.ROOT) : "";
    }
    private static final class RawTeam {
        final String prefix, suffix;
        final Set<String> members;
        RawTeam(String prefix, String suffix, Set<String> members) {
            this.prefix = prefix == null ? "" : prefix.substring(0, Math.min(256, prefix.length()));
            this.suffix = suffix == null ? "" : suffix.substring(0, Math.min(256, suffix.length()));
            this.members = members;
        }
    }
}
