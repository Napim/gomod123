package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.IChatComponent;
import net.minecraft.network.play.server.S02PacketChat;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Dev-only Blitz opponent history with account-bound relay sync and a disk-backed offline queue. */
public final class RecentOpponents {
    private static final String FILE_NAME = "recent_opponents.json";
    private static final int MAX_RECORDS = 500;
    private static final long DISGUISED_OPPONENT_DELAY_MS = 20L * 60L * 1000L;
    private static final String DIRECT_KILL_RESULT_MODE = "direct-kills";
    private static final String[] SELF_KILL_PHRASES = {
            "you killed ", "you shot ", "you slew ", "you slain ", "you blew up ",
            "you burned to death ", "you frozen to death ", "you electrocuted ",
            "you eliminated ", "you defeated "
    };
    private static final String[] KILL_FEED_PHRASES = {
            " was killed by ", " were killed by ", " was slain by ", " were slain by ",
            " was shot by ", " were shot by ", " was blown up by ", " were blown up by ",
            " was burned to death by ", " were burned to death by ",
            " was frozen to death by ", " were frozen to death by ",
            " was electrocuted by ", " were electrocuted by ",
            " was eliminated by ", " were eliminated by ", " was defeated by ", " were defeated by "
    };
    private static final Map<String, Record> RECORDS = new LinkedHashMap<>();
    private static final Map<String, SeenThisMatch> MATCH_PLAYERS = new LinkedHashMap<>();
    /** Opponent record key -> true when the local player killed them, false when they killed us. */
    private static final Map<String, Boolean> MATCH_RESULTS = new LinkedHashMap<>();
    private static boolean loaded;
    private static volatile boolean matchOpen;
    private static boolean matchWasActive;
    private static long pendingFinishAt;
    private static String currentMatchId = "";

    public RecentOpponents() { }

    public static void clientTick() {
        ensureLoaded();
        RecentOpponentsRelay.tick();
        if (!GomodInternalFeatures.isEnabled()) {
            if (matchOpen) finishMatch();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
            if (matchOpen) finishMatch();
            return;
        }
        boolean gameOver = GameContext.isBlitzGameOverSidebar(mc);
        if (gameOver) {
            if (!matchOpen) return;
            if (!matchWasActive) {
                finishMatch();
                return;
            }
            if (pendingFinishAt == 0L) pendingFinishAt = System.currentTimeMillis() + 1500L;
            if (System.currentTimeMillis() >= pendingFinishAt) finishMatch();
            return;
        }
        boolean active = GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc);
        if (!active) {
            if (matchOpen) finishMatch();
            return;
        }

        if (!matchOpen) beginMatch();
        matchWasActive = true;
        pendingFinishAt = 0L;
        collectPlayers(mc);
    }

    private static void beginMatch() {
        ensureLoaded();
        MATCH_PLAYERS.clear();
        MATCH_RESULTS.clear();
        currentMatchId = UUID.randomUUID().toString();
        matchOpen = true;
        matchWasActive = false;
        pendingFinishAt = 0L;
    }

    private static void collectPlayers(Minecraft mc) {
        Collection<NetworkPlayerInfo> entries = mc.getNetHandler().getPlayerInfoMap();
        if (entries == null) return;
        boolean teamsMode = BlitzTeamsDetector.isBlitzTeamsGame(mc);
        NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        ScorePlayerTeam ownTeam = self == null ? null : self.getPlayerTeam();
        UUID localUuid = mc.thePlayer.getUniqueID();
        for (NetworkPlayerInfo info : entries) {
            if (info == null) continue;
            // A TAB render hook can expose a display-only disguised GameProfile. Recent Opponents
            // must key and store the server's source identity instead of that temporary alias.
            GameProfile sourceProfile = RemoteDisguiseManager.getSourceProfile(info);
            if (sourceProfile == null) continue;
            UUID uuid = sourceProfile.getId();
            String name = sourceProfile.getName();
            if (name == null || name.trim().isEmpty()
                    || (uuid != null && uuid.equals(localUuid))
                    || name.equalsIgnoreCase(mc.thePlayer.getName())) continue;
            ScorePlayerTeam theirTeam = info.getPlayerTeam();
            if (teamsMode && ownTeam != null && theirTeam != null && ownTeam.isSameTeam(theirTeam)) continue;
            String uuidString = uuid == null ? "" : uuid.toString();
            String key = recordKey(name, uuidString);
            if (!MATCH_PLAYERS.containsKey(key)) {
                boolean disguised = RemoteDisguiseManager.getDisguisedTargetProfile(sourceProfile) != null;
                long now = System.currentTimeMillis();
                long releaseAt = disguised ? now + DISGUISED_OPPONENT_DELAY_MS : 0L;
                SeenThisMatch seen = new SeenThisMatch(name, uuidString, releaseAt);
                MATCH_PLAYERS.put(key, seen);
                recordSightedPlayer(seen, now);
            }
        }
    }

    private static void recordSightedPlayer(SeenThisMatch seen, long occurredAt) {
        if (seen.releaseAt > occurredAt) {
            RecentOpponentsRelay.enqueueDelayed(currentMatchId, seen.name, seen.uuid,
                    "encounter", occurredAt, seen.releaseAt);
        } else {
            RecentOpponentsRelay.enqueue(currentMatchId, seen.name, seen.uuid, "encounter", occurredAt);
        }
    }

    private static void finishMatch() {
        if (!matchOpen) return;
        // Sighting events are durable in the offline outbox as soon as rostered.
        matchOpen = false;
        matchWasActive = false;
        pendingFinishAt = 0L;
        MATCH_PLAYERS.clear();
        MATCH_RESULTS.clear();
        currentMatchId = "";
    }

    @SubscribeEvent
    public void onChat(PacketEvent.Receive event) {
        if (!GomodInternalFeatures.isEnabled() || !(event.getPacket() instanceof S02PacketChat)) return;
        try {
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            IChatComponent component = packet.getChatComponent();
            String text = component == null ? null : component.getUnformattedText();
            if ((text == null || text.trim().isEmpty()) && component != null) {
                text = component.getFormattedText();
                if (text != null) text = text.replaceAll("\u00a7.", "");
            }
            if (matchOpen && matchWasActive && text != null) recordKillOutcomeFromChat(text);
        } catch (Throwable ignored) { }
    }

    private static void recordKillOutcomeFromChat(String message) {
        String clean = message.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return;
        String lower = clean.toLowerCase(Locale.ROOT);

        // Hypixel's first-person feed commonly says "You killed <player>".
        for (String phrase : SELF_KILL_PHRASES) {
            int phraseIndex = lower.indexOf(phrase);
            if (phraseIndex < 0) continue;
            String opponentKey = findOpponentKey(clean.substring(phraseIndex + phrase.length()));
            if (opponentKey != null) {
                recordDirectResult(opponentKey, true);
                return;
            }
        }

        // Also handle the broadcast feed: "Victim was killed by Killer".
        String localName = getLocalPlayerName();
        for (String phrase : KILL_FEED_PHRASES) {
            int phraseIndex = lower.indexOf(phrase);
            if (phraseIndex < 0) continue;
            String victimPart = clean.substring(0, phraseIndex);
            String killerPart = clean.substring(phraseIndex + phrase.length());
            boolean localWasVictim = isLocalPlayerMention(victimPart, localName);
            boolean localWasKiller = isLocalPlayerMention(killerPart, localName);
            if (localWasVictim == localWasKiller) continue;

            String opponentKey = localWasVictim
                    ? findOpponentKey(killerPart)
                    : findOpponentKey(victimPart);
            if (opponentKey != null) {
                recordDirectResult(opponentKey, localWasKiller);
                return;
            }
        }
    }

    private static void recordDirectResult(String opponentKey, boolean localPlayerWon) {
        if (!matchOpen || !matchWasActive || MATCH_RESULTS.containsKey(opponentKey)) return;
        SeenThisMatch seen = MATCH_PLAYERS.get(opponentKey);
        if (seen == null) return;
        MATCH_RESULTS.put(opponentKey, localPlayerWon);
        long now = System.currentTimeMillis();
        String kind = localPlayerWon ? "win" : "loss";
        if (seen.releaseAt > now) {
            RecentOpponentsRelay.enqueueDelayed(currentMatchId, seen.name, seen.uuid,
                    kind, now, seen.releaseAt);
        } else {
            RecentOpponentsRelay.enqueue(currentMatchId, seen.name, seen.uuid, kind, now);
        }
    }

    private static String findOpponentKey(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Map.Entry<String, SeenThisMatch> entry : MATCH_PLAYERS.entrySet()) {
            SeenThisMatch seen = entry.getValue();
            if (containsPlayerName(text, seen.name)) return entry.getKey();
            Record record = RECORDS.get(entry.getKey());
            if (record != null && !record.name.equalsIgnoreCase(seen.name)
                    && containsPlayerName(text, record.name)) return entry.getKey();
        }
        return null;
    }

    private static boolean isLocalPlayerMention(String text, String localName) {
        if (text == null) return false;
        if ("you".equalsIgnoreCase(text.trim())) return true;
        return containsPlayerName(text, localName);
    }

    private static boolean containsPlayerName(String text, String name) {
        if (text == null || name == null || name.trim().isEmpty()) return false;
        Pattern pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name.trim())
                + "(?![A-Za-z0-9_])");
        return pattern.matcher(text).find();
    }

    private static String getLocalPlayerName() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null) {
                String name = mc.thePlayer.getGameProfile().getName();
                if (name != null && !name.isEmpty()) return name;
            }
            return mc == null || mc.thePlayer == null ? "" : mc.thePlayer.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static synchronized List<Record> getRecords() {
        ensureLoaded();
        List<Record> result = RecentOpponentsRelay.mergeHistory(rawRecordsByLastSeen());
        Map<String, String> resolvedRealNamesByUuid = new HashMap<String, String>();
        for (Record record : result) {
            if (!hasGeneratedNickUuid(record)) continue;
            String realName = resolveDenickedRealName(record);
            if (realName != null) resolvedRealNamesByUuid.put(normalizeUuid(record.uuid), realName);
        }
        result = RecentOpponentRecordOps.mergeUnresolvedNickSessions(result, resolvedRealNamesByUuid);
        if (ConfigManager.isRecentOpponentsGroupDenickedPlayers()) {
            result = groupDenickedRecords(result);
        }
        RecentOpponentRecordOps.sortRecords(result, ConfigManager.getRecentOpponentsSortMode());
        return result;
    }

    public static String getRelaySyncStatus() {
        return RecentOpponentsRelay.getStatus();
    }

    public static String getRelaySyncStatusDetail() {
        return RecentOpponentsRelay.getStatusDetail();
    }

    private static List<Record> rawRecordsByLastSeen() {
        List<Record> result = new ArrayList<Record>(RECORDS.values());
        Collections.sort(result, new Comparator<Record>() {
            @Override public int compare(Record a, Record b) { return Long.compare(b.lastSeen, a.lastSeen); }
        });
        return result;
    }

    static synchronized List<Record> getLegacyRecordsForSync() {
        ensureLoaded();
        List<Record> result = rawRecordsByLastSeen();
        if (result.size() > MAX_RECORDS) return new ArrayList<Record>(result.subList(0, MAX_RECORDS));
        return result;
    }

    private static int compareKillDeathRatio(Record a, Record b) {
        double left = a.getKillDeathRatio();
        double right = b.getKillDeathRatio();
        if (Double.isNaN(left)) return Double.isNaN(right) ? 0 : 1;
        if (Double.isNaN(right)) return -1;
        return Double.compare(left, right);
    }

    private static List<Record> groupDenickedRecords(List<Record> records) {
        Map<String, Record> realPlayersByName = new HashMap<String, Record>();
        for (Record record : records) {
            if (resolveDenickedRealName(record) == null) {
                realPlayersByName.put(record.name.toLowerCase(Locale.ROOT), record);
            }
        }
        Map<String, Record> grouped = new LinkedHashMap<String, Record>();
        for (Record record : records) {
            String realName = resolveDenickedRealName(record);
            String groupKey = recordKey(record.name, record.uuid);
            String displayName = record.name;
            String uuid = record.uuid;
            if (realName != null) {
                displayName = realName;
                Record realRecord = realPlayersByName.get(realName.toLowerCase(Locale.ROOT));
                if (realRecord != null) {
                    groupKey = recordKey(realRecord.name, realRecord.uuid);
                    uuid = realRecord.uuid;
                } else {
                    groupKey = "denicked:" + realName.toLowerCase(Locale.ROOT);
                    uuid = "";
                }
            }
            Record previous = grouped.get(groupKey);
            if (previous == null) {
                grouped.put(groupKey, new Record(displayName, uuid, record.matches,
                        record.wins, record.losses, record.lastSeen, record.legacyUnverified));
            } else {
                grouped.put(groupKey, new Record(displayName, uuid,
                        previous.matches + record.matches, previous.wins + record.wins,
                        previous.losses + record.losses, Math.max(previous.lastSeen, record.lastSeen),
                        previous.legacyUnverified || record.legacyUnverified));
            }
        }
        return new ArrayList<Record>(grouped.values());
    }

    /** Returns the real IGN for a known nick, or null when this record is not denicked. */
    public static String resolveDenickedRealName(Record record) {
        if (record == null || record.name == null || record.name.trim().isEmpty()) return null;
        String realName = null;
        if (record.uuid != null && !record.uuid.isEmpty()) {
            try {
                realName = TabDuplicateNickResolver.getResolvedRealNameForSessionUuid(UUID.fromString(record.uuid));
            } catch (IllegalArgumentException ignored) { }
        }
        if (realName == null || realName.trim().isEmpty()) {
            realName = TabDuplicateNickResolver.getKnownRealName(record.name);
        }
        if (realName == null) return null;
        realName = realName.trim();
        return realName.isEmpty() || realName.equalsIgnoreCase(record.name) ? null : realName;
    }

    public static String getDisplayName(Record record) {
        String realName = resolveDenickedRealName(record);
        return realName == null ? record.name : record.name + " (" + realName + ")";
    }

    /** Known mappings identify the real IGN; Hypixel-generated nick profiles also use non-Mojang UUID versions. */
    public static boolean isNick(Record record) {
        if (record == null) return false;
        if (resolveDenickedRealName(record) != null) return true;
        return hasGeneratedNickUuid(record);
    }

    /** Structural Hypixel nick signal useful when the real-IGN lookup has not resolved yet. */
    public static boolean hasGeneratedNickUuid(Record record) {
        return record != null && RecentOpponentRecordOps.isGeneratedNickUuid(record.uuid);
    }

    public static synchronized void clear() {
        ensureLoaded();
        RECORDS.clear();
        save();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = getFile();
        if (!file.isFile()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonArray()) return;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                String name = string(obj, "name", "");
                if (name.isEmpty()) continue;
                boolean hasDirectKillResults = DIRECT_KILL_RESULT_MODE.equals(string(obj, "resultMode", ""));
                Record record = new Record(name, string(obj, "uuid", ""), integer(obj, "matches"),
                        hasDirectKillResults ? integer(obj, "wins") : 0,
                        hasDirectKillResults ? integer(obj, "losses") : 0, number(obj, "lastSeen"),
                        booleanValue(obj, "legacyUnverified", false));
                String key = recordKey(record.name, record.uuid);
                Record existing = RECORDS.get(key);
                RECORDS.put(key, existing == null ? record : mergeRecords(existing, record));
            }
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not read recent opponents: " + ex.getMessage());
        }
    }

    private static synchronized void save() {
        File file = getFile();
        File dir = file.getParentFile();
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
            JsonArray array = new JsonArray();
            for (Record record : rawRecordsByLastSeen()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", record.name);
                obj.addProperty("uuid", record.uuid);
                obj.addProperty("matches", record.matches);
                obj.addProperty("wins", record.wins);
                obj.addProperty("losses", record.losses);
                obj.addProperty("resultMode", DIRECT_KILL_RESULT_MODE);
                obj.addProperty("lastSeen", record.lastSeen);
                if (record.legacyUnverified) obj.addProperty("legacyUnverified", true);
                array.add(obj);
            }
            try (FileWriter writer = new FileWriter(file)) { new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(array, writer); }
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not save recent opponents: " + ex.getMessage());
        }
    }

    private static File getFile() { return new File(GomodPaths.getGomod123Dir(), FILE_NAME); }
    private static String string(JsonObject o, String k, String fallback) { try { return o.has(k) ? o.get(k).getAsString() : fallback; } catch (Throwable ignored) { return fallback; } }
    private static boolean booleanValue(JsonObject o, String k, boolean fallback) { try { return o.has(k) ? o.get(k).getAsBoolean() : fallback; } catch (Throwable ignored) { return fallback; } }
    private static int integer(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsInt() : 0; } catch (Throwable ignored) { return 0; } }
    private static long number(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsLong() : 0L; } catch (Throwable ignored) { return 0L; } }

    private static String recordKey(String name, String uuid) {
        String normalizedUuid = normalizeUuid(uuid);
        if (!normalizedUuid.isEmpty()) return "uuid:" + normalizedUuid;
        return "name:" + normalizedName(name);
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUuid(String uuid) {
        if (uuid == null) return "";
        String compact = uuid.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (compact.length() != 32) return "";
        boolean allZeroes = true;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return "";
            if (c != '0') allZeroes = false;
        }
        return allZeroes ? "" : compact;
    }

    private static Record mergeRecords(Record first, Record second) {
        Record newest = second.lastSeen >= first.lastSeen ? second : first;
        String uuid = !newest.uuid.isEmpty() ? newest.uuid : (!first.uuid.isEmpty() ? first.uuid : second.uuid);
        return new Record(newest.name, uuid, first.matches + second.matches,
                first.wins + second.wins, first.losses + second.losses,
                Math.max(first.lastSeen, second.lastSeen),
                first.legacyUnverified || second.legacyUnverified);
    }

    private static final class SeenThisMatch {
        final String name;
        final String uuid;
        final long releaseAt;
        SeenThisMatch(String name, String uuid, long releaseAt) {
            this.name = name;
            this.uuid = uuid;
            this.releaseAt = releaseAt;
        }
    }

    public static final class Record {
        public final String name;
        public final String uuid;
        public final int matches;
        public final int wins;
        public final int losses;
        public final long lastSeen;
        public final boolean legacyUnverified;
        Record(String name, String uuid) { this(name, uuid, 0, 0, 0, 0L); }
        Record(String name, String uuid, int matches, int wins, int losses, long lastSeen) {
            this(name, uuid, matches, wins, losses, lastSeen, false);
        }
        Record(String name, String uuid, int matches, int wins, int losses, long lastSeen,
               boolean legacyUnverified) {
            this.name = name; this.uuid = uuid; this.matches = matches; this.wins = wins;
            this.losses = losses; this.lastSeen = lastSeen; this.legacyUnverified = legacyUnverified;
        }
        public int getWinRatePercent() { int decided = wins + losses; return decided == 0 ? -1 : Math.round(wins * 100.0f / decided); }
        public double getKillDeathRatio() {
            if (wins == 0 && losses == 0) return Double.NaN;
            // Keep flawless records readable and sortable as a finite K/D value.
            return wins / (double) Math.max(1, losses);
        }
        public String getKillDeathRatioText() {
            double ratio = getKillDeathRatio();
            if (Double.isNaN(ratio)) return "—";
            return String.format(Locale.ROOT, "%.2f", ratio);
        }
    }
}
