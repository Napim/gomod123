package me.napim.gomod.features;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches Hypixel network rank (from the same {@code /player} JSON as tabstats) for TAB / nametag styling.
 */
public final class HypixelTabStatsRankCache {

    public static final class Snapshot {
        public final String rank;
        public final String rankPlusColor;
        public final String monthlyRankColor;

        Snapshot(String rank, String rankPlusColor, String monthlyRankColor) {
            this.rank = rank == null ? "" : rank;
            this.rankPlusColor = rankPlusColor == null ? "" : rankPlusColor;
            this.monthlyRankColor = monthlyRankColor == null ? "" : monthlyRankColor;
        }
    }

    private static final Map<String, Snapshot> BY_PROFILE_LOWER = new ConcurrentHashMap<>();
    /** Profiles we already tried to load from Hypixel (including Default / failed lookups). */
    private static final Set<String> RANK_LOOKUP_ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<Runnable>> RANK_LOOKUP_WAITERS = new ConcurrentHashMap<>();

    private HypixelTabStatsRankCache() {
    }

    public static void clear() {
        BY_PROFILE_LOWER.clear();
        RANK_LOOKUP_ATTEMPTED.clear();
    }

    public static void remove(String profileKeyLower) {
        if (profileKeyLower != null) {
            BY_PROFILE_LOWER.remove(profileKeyLower.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Ingest rank fields from a Hypixel {@code player} JSON object (tab profile name lower → snapshot).
     */
    public static void ingest(String tabProfileKeyLower, JsonObject playerData) {
        if (tabProfileKeyLower == null || tabProfileKeyLower.isEmpty() || playerData == null) {
            return;
        }
        String key = tabProfileKeyLower.toLowerCase(Locale.ROOT);
        Snapshot snap = parseSnapshot(playerData);
        if (snap == null || "Default".equals(snap.rank) || snap.rank.isEmpty()) {
            BY_PROFILE_LOWER.remove(key);
        } else {
            BY_PROFILE_LOWER.put(key, snap);
        }
    }

    /** Same storage as {@link #ingest(String, JsonObject)} using parsed rank fields (e.g. team spam list). */
    public static void ingestSnapshot(String profileKey, String rank, String rankPlusColor, String monthlyRankColor) {
        if (profileKey == null || profileKey.trim().isEmpty()) {
            return;
        }
        String key = profileKey.trim().toLowerCase(Locale.ROOT);
        Snapshot snap = new Snapshot(rank, rankPlusColor, monthlyRankColor);
        if ("Default".equals(snap.rank) || snap.rank.isEmpty()) {
            BY_PROFILE_LOWER.remove(key);
        } else {
            BY_PROFILE_LOWER.put(key, snap);
        }
    }

    public static Snapshot getSnapshotForProfile(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        Snapshot s = BY_PROFILE_LOWER.get(playerName.toLowerCase(Locale.ROOT));
        if (s != null) {
            return s;
        }
        for (Map.Entry<String, Snapshot> e : BY_PROFILE_LOWER.entrySet()) {
            if (e.getKey().equalsIgnoreCase(playerName)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** §-prefixed username color (official Hypixel palette), or empty if unknown. */
    public static String getUsernameColorFormatting(String playerName) {
        Snapshot s = getSnapshotForProfile(playerName);
        if (s == null) {
            return "";
        }
        return buildOfficialUsernameColor(s.rank, s.monthlyRankColor);
    }

    /**
     * Tabstats stores rank under the tab {@link net.minecraft.client.network.NetworkPlayerInfo} profile name (often the nick).
     * Denick TAB composition resolves stats under the real IGN — try both keys.
     */
    public static String getUsernameColorFormattingPreferKeys(String lookupIgn, String tabProfileName) {
        String a = getUsernameColorFormatting(lookupIgn);
        if (a != null && !a.isEmpty()) {
            return a;
        }
        if (tabProfileName != null && lookupIgn != null
                && !tabProfileName.trim().equalsIgnoreCase(lookupIgn.trim())) {
            return getUsernameColorFormatting(tabProfileName);
        }
        return "";
    }

    /** Fingerprint segment so TAB recomposes when tabstats rank cache fills in after the first pass. */
    public static String tabStatsRankFingerprintForRow(String lookupIgn, String tabProfileName) {
        Snapshot s = getSnapshotForProfile(lookupIgn);
        if (s == null && tabProfileName != null && lookupIgn != null
                && !tabProfileName.trim().equalsIgnoreCase(lookupIgn.trim())) {
            s = getSnapshotForProfile(tabProfileName);
        }
        if (s == null) {
            return "";
        }
        return s.rank + "|" + s.rankPlusColor + "|" + s.monthlyRankColor;
    }

    /** Colored {@code [MVP+]} tag without trailing space, or empty. */
    public static String getFormattedRankTag(String playerName) {
        Snapshot s = getSnapshotForProfile(playerName);
        if (s == null) {
            return "";
        }
        return buildOfficialRankTag(s.rank, s.rankPlusColor, s.monthlyRankColor);
    }

    /** Formats an API rank value using the same official prefix palette used by /infop and TAB. */
    public static String formatOfficialRankTag(String rank, String rankPlusColor, String monthlyRankColor) {
        String normalized = normalizeDisplayRank(rank);
        return buildOfficialRankTag(normalized, rankPlusColor, monthlyRankColor);
    }

    /** Formats an API rank value's username color using the same palette used by /infop and TAB. */
    public static String formatOfficialUsernameColor(String rank, String monthlyRankColor) {
        return buildOfficialUsernameColor(normalizeDisplayRank(rank), monthlyRankColor);
    }

    private static String normalizeDisplayRank(String rank) {
        if (rank == null || rank.trim().isEmpty()) return "Default";
        String normalized = rank.trim().toUpperCase(Locale.ROOT);
        if ("YOUTUBE".equals(normalized)) return "YOUTUBER";
        if ("NORMAL".equals(normalized) || "NONE".equals(normalized) || "UNRANKED".equals(normalized)) {
            return "Default";
        }
        return normalized;
    }

    /** Full overlay line: {@code [RANK] coloredName§r} or empty if no snapshot / no real name context. */
    public static String formatNametagRankLine(String lookupNameForApi, String realIgnPlain) {
        if (realIgnPlain == null || realIgnPlain.trim().isEmpty()) {
            return "";
        }
        Snapshot s = getSnapshotForProfile(lookupNameForApi);
        if (s == null || "Default".equals(s.rank) || s.rank.isEmpty()) {
            return "";
        }
        String tag = buildOfficialRankTag(s.rank, s.rankPlusColor, s.monthlyRankColor);
        String nameCol = buildOfficialUsernameColor(s.rank, s.monthlyRankColor);
        String ign = realIgnPlain.trim();
        if (tag.isEmpty()) {
            return nameCol + ign + EnumChatFormatting.RESET;
        }
        return tag + " " + nameCol + ign + EnumChatFormatting.RESET;
    }

    /** Tries tab profile nick first, then {@code realIgnPlain} as cache key (tabstats may store either). */
    public static String formatNametagRankLinePreferKeys(String tabNick, String realIgnPlain) {
        if (realIgnPlain == null || realIgnPlain.trim().isEmpty()) {
            return "";
        }
        String trimmedReal = realIgnPlain.trim();
        String a = formatNametagRankLine(tabNick, trimmedReal);
        if (!a.isEmpty()) {
            return a;
        }
        if (tabNick != null && !tabNick.trim().isEmpty() && !tabNick.trim().equalsIgnoreCase(trimmedReal)) {
            return formatNametagRankLine(trimmedReal, trimmedReal);
        }
        return "";
    }

    /**
     * {@code true} when we should call Hypixel for rank/color before client-only chat lines (e.g. {@code /pee}).
     */
    public static boolean needsHypixelRankLookup(String lookupIgn, String tabProfileName) {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        return !hasCachedOrAttemptedRankLookup(lookupIgn, tabProfileName);
    }

    /**
     * Loads rank data from Hypixel once per profile key, then runs {@code onComplete} on the client thread.
     */
    public static void fetchHypixelRankIfNeeded(String uuidUndashed, String tabProfileName, String lookupIgn,
                                               Runnable onComplete) {
        if (!needsHypixelRankLookup(lookupIgn, tabProfileName)) {
            runOnClientThread(onComplete);
            return;
        }
        if (uuidUndashed == null || uuidUndashed.trim().isEmpty()) {
            markRankLookupAttempted(lookupIgn, tabProfileName);
            runOnClientThread(onComplete);
            return;
        }
        final String uuid = uuidUndashed.replace("-", "");
        final String tabNick = tabProfileName == null ? "" : tabProfileName.trim();
        final String realIgn = lookupIgn == null ? "" : lookupIgn.trim();
        synchronized (RANK_LOOKUP_WAITERS) {
            List<Runnable> existing = RANK_LOOKUP_WAITERS.get(uuid);
            if (existing != null) {
                if (onComplete != null) {
                    existing.add(onComplete);
                }
                return;
            }
            List<Runnable> callbacks = new ArrayList<>();
            if (onComplete != null) {
                callbacks.add(onComplete);
            }
            RANK_LOOKUP_WAITERS.put(uuid, callbacks);
        }
        Thread worker = new Thread(() -> {
            try {
                String apiKey = ApiKeyManager.getApiKey("hypixel");
                if (apiKey == null || apiKey.trim().isEmpty()) {
                    return;
                }
                JsonObject json = HypixelApiHelper.fetchJson(
                        "https://api.hypixel.net/player?key=" + apiKey.trim() + "&uuid=" + uuid);
                if (json.has("success") && json.get("success").getAsBoolean()
                        && json.has("player") && !json.get("player").isJsonNull()) {
                    JsonObject playerData = json.getAsJsonObject("player");
                    if (!realIgn.isEmpty()) {
                        ingest(realIgn.toLowerCase(Locale.ROOT), playerData);
                    }
                    if (!tabNick.isEmpty() && !tabNick.equalsIgnoreCase(realIgn)) {
                        ingest(tabNick.toLowerCase(Locale.ROOT), playerData);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                markRankLookupAttempted(lookupIgn, tabProfileName);
                List<Runnable> callbacks;
                synchronized (RANK_LOOKUP_WAITERS) {
                    callbacks = RANK_LOOKUP_WAITERS.remove(uuid);
                }
                if (callbacks != null) {
                    for (Runnable callback : callbacks) {
                        runOnClientThread(callback);
                    }
                }
            }
        }, "gomod-rank-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean hasCachedOrAttemptedRankLookup(String lookupIgn, String tabProfileName) {
        if (lookupIgn != null && !lookupIgn.trim().isEmpty()) {
            String key = lookupIgn.trim().toLowerCase(Locale.ROOT);
            if (BY_PROFILE_LOWER.containsKey(key) || RANK_LOOKUP_ATTEMPTED.contains(key)) {
                return true;
            }
        }
        if (tabProfileName != null && !tabProfileName.trim().isEmpty()) {
            String key = tabProfileName.trim().toLowerCase(Locale.ROOT);
            if (lookupIgn != null && key.equals(lookupIgn.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (BY_PROFILE_LOWER.containsKey(key) || RANK_LOOKUP_ATTEMPTED.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static void markRankLookupAttempted(String lookupIgn, String tabProfileName) {
        if (lookupIgn != null && !lookupIgn.trim().isEmpty()) {
            RANK_LOOKUP_ATTEMPTED.add(lookupIgn.trim().toLowerCase(Locale.ROOT));
        }
        if (tabProfileName != null && !tabProfileName.trim().isEmpty()) {
            RANK_LOOKUP_ATTEMPTED.add(tabProfileName.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void runOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            task.run();
            return;
        }
        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    private static Snapshot parseSnapshot(JsonObject playerData) {
        try {
            String fetchedRankPlusColor = playerData.has("rankPlusColor") ? playerData.get("rankPlusColor").getAsString() : "";
            String fetchedMonthlyRankColor = playerData.has("monthlyRankColor") ? playerData.get("monthlyRankColor").getAsString() : "";

            // A STAFF role overrides any legacy prefix or MVP++ purchase data.
            if (playerData.has("rank") && "STAFF".equals(playerData.get("rank").getAsString())) {
                return new Snapshot("STAFF", "", "");
            }

            if (playerData.has("prefix")) {
                String prefix = playerData.get("prefix").getAsString();
                if (prefix.contains("MOJANG")) {
                    return new Snapshot("MOJANG", "", "");
                }
                if (prefix.contains("EVENTS")) {
                    return new Snapshot("EVENTS", "", "");
                }
                if (prefix.contains("INNIT")) {
                    return new Snapshot("INNIT", "", "");
                }
                if (prefix.contains("OWNER")) {
                    return new Snapshot("OWNER", "", "");
                }
            }

            if (playerData.has("rank")) {
                String exactRank = playerData.get("rank").getAsString();
                if ("YOUTUBER".equals(exactRank)) {
                    return new Snapshot("YOUTUBER", "", "");
                }
                if ("ADMIN".equals(exactRank)) {
                    if (playerData.has("prefix") && playerData.get("prefix").getAsString().contains("OWNER")) {
                        return new Snapshot("OWNER", "", "");
                    }
                    return new Snapshot("ADMIN", "", "");
                }
                if ("GAME_MASTER".equals(exactRank)) {
                    return new Snapshot("GM", "", "");
                }
                if ("MVP_PLUS_PLUS".equals(exactRank)) {
                    return new Snapshot("MVP++", fetchedRankPlusColor, fetchedMonthlyRankColor);
                }
            }

            if (playerData.has("monthlyPackageRank")) {
                String monthlyRank = playerData.get("monthlyPackageRank").getAsString();
                if ("SUPERSTAR".equals(monthlyRank)) {
                    return new Snapshot("MVP++", fetchedRankPlusColor, fetchedMonthlyRankColor);
                }
            }

            String packageRank = "";
            if (playerData.has("newPackageRank")) {
                packageRank = playerData.get("newPackageRank").getAsString();
            } else if (playerData.has("packageRank")) {
                packageRank = playerData.get("packageRank").getAsString();
            }

            switch (packageRank) {
                case "MVP_PLUS":
                    return new Snapshot("MVP+", fetchedRankPlusColor, "");
                case "MVP":
                    return new Snapshot("MVP", "", "");
                case "VIP_PLUS":
                    return new Snapshot("VIP+", "", "");
                case "VIP":
                    return new Snapshot("VIP", "", "");
                default:
                    return new Snapshot("Default", "", "");
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildOfficialRankTag(String rank, String rankPlusColor, String monthlyRankColor) {
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return "";
        }
        if ("MVP".equals(rank)) {
            return EnumChatFormatting.AQUA.toString() + "[MVP]";
        }
        if ("MVP+".equals(rank)) {
            String baseColor = EnumChatFormatting.AQUA.toString();
            String plusColor = mvpPlusSign(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "+" + baseColor + "]";
        }
        if ("MVP++".equals(rank)) {
            String baseColor = mvpPlusPlusBase(monthlyRankColor);
            String plusColor = mvpPlusPlusSigns(rankPlusColor);
            return baseColor + "[MVP" + plusColor + "++" + baseColor + "]";
        }
        if ("VIP+".equals(rank)) {
            String baseColor = EnumChatFormatting.GREEN.toString();
            return baseColor + "[VIP" + EnumChatFormatting.GOLD + "+" + baseColor + "]";
        }
        if ("YOUTUBER".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.WHITE + "YOUTUBE" + EnumChatFormatting.RED + "]";
        }
        if ("PIG+++".equals(rank)) {
            return EnumChatFormatting.LIGHT_PURPLE + "[PIG" + EnumChatFormatting.AQUA + "+++"
                    + EnumChatFormatting.LIGHT_PURPLE + "]";
        }
        if ("STAFF".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.GOLD + "\u12de"
                    + EnumChatFormatting.RED + "]";
        }
        return rankColor(rank) + "[" + renderedRankLabel(rank) + "]";
    }

    private static String buildOfficialUsernameColor(String rank, String monthlyRankColor) {
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return EnumChatFormatting.GRAY.toString();
        }
        switch (rank) {
            case "MVP++":
                return mvpPlusPlusBase(monthlyRankColor);
            case "MVP+":
            case "MVP":
                return EnumChatFormatting.AQUA.toString();
            case "VIP+":
            case "VIP":
                return EnumChatFormatting.GREEN.toString();
            case "YOUTUBER":
            case "OWNER":
            case "ADMIN":
            case "STAFF":
                return EnumChatFormatting.RED.toString();
            case "GM":
                return EnumChatFormatting.DARK_GREEN.toString();
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD.toString();
            case "INNIT":
            case "PIG+++":
                return EnumChatFormatting.LIGHT_PURPLE.toString();
            default:
                return rankColor(rank);
        }
    }

    private static String renderedRankLabel(String currentRank) {
        if ("YOUTUBER".equals(currentRank)) {
            return "YOUTUBE";
        }
        return currentRank;
    }

    private static String rankColor(String currentRank) {
        if (currentRank == null) {
            return EnumChatFormatting.YELLOW.toString();
        }
        switch (currentRank) {
            case "OWNER":
            case "ADMIN":
            case "YOUTUBER":
            case "STAFF":
                return EnumChatFormatting.RED.toString();
            case "GM":
                return EnumChatFormatting.DARK_GREEN.toString();
            case "MOJANG":
            case "EVENTS":
                return EnumChatFormatting.GOLD.toString();
            case "PIG+++":
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE.toString();
            case "MVP":
            case "MVP+":
                return EnumChatFormatting.AQUA.toString();
            case "VIP+":
            case "VIP":
                return EnumChatFormatting.GREEN.toString();
            case "Default":
                return EnumChatFormatting.GRAY.toString();
            default:
                return EnumChatFormatting.YELLOW.toString();
        }
    }

    private static String mvpPlusSign(String plusColor) {
        if (plusColor == null || plusColor.isEmpty()) {
            return EnumChatFormatting.GOLD.toString();
        }
        switch (plusColor) {
            case "RED":
                return EnumChatFormatting.RED.toString();
            case "BLUE":
                return EnumChatFormatting.BLUE.toString();
            case "GREEN":
                return EnumChatFormatting.GREEN.toString();
            case "YELLOW":
                return EnumChatFormatting.YELLOW.toString();
            case "GOLD":
                return EnumChatFormatting.GOLD.toString();
            case "AQUA":
                return EnumChatFormatting.AQUA.toString();
            case "LIGHT_PURPLE":
                return EnumChatFormatting.LIGHT_PURPLE.toString();
            case "DARK_PURPLE":
                return EnumChatFormatting.DARK_PURPLE.toString();
            case "DARK_AQUA":
                return EnumChatFormatting.DARK_AQUA.toString();
            case "DARK_GREEN":
                return EnumChatFormatting.DARK_GREEN.toString();
            case "DARK_RED":
                return EnumChatFormatting.DARK_RED.toString();
            case "DARK_BLUE":
                return EnumChatFormatting.DARK_BLUE.toString();
            case "WHITE":
                return EnumChatFormatting.WHITE.toString();
            case "GRAY":
                return EnumChatFormatting.GRAY.toString();
            case "DARK_GRAY":
                return EnumChatFormatting.DARK_GRAY.toString();
            case "BLACK":
                return EnumChatFormatting.BLACK.toString();
            default:
                return EnumChatFormatting.GOLD.toString();
        }
    }

    private static String mvpPlusPlusBase(String color) {
        if (color == null || color.isEmpty()) {
            return EnumChatFormatting.GOLD.toString();
        }
        switch (color) {
            case "DEFAULT":
                return EnumChatFormatting.GOLD.toString();
            case "DARK_PURPLE":
                return EnumChatFormatting.DARK_PURPLE.toString();
            case "LIGHT_PURPLE":
                return EnumChatFormatting.LIGHT_PURPLE.toString();
            case "DARK_AQUA":
                return EnumChatFormatting.DARK_AQUA.toString();
            case "AQUA":
                return EnumChatFormatting.AQUA.toString();
            case "RED":
                return EnumChatFormatting.RED.toString();
            case "GOLD":
                return EnumChatFormatting.GOLD.toString();
            case "GREEN":
                return EnumChatFormatting.GREEN.toString();
            case "YELLOW":
                return EnumChatFormatting.YELLOW.toString();
            case "WHITE":
                return EnumChatFormatting.WHITE.toString();
            case "BLUE":
                return EnumChatFormatting.BLUE.toString();
            case "DARK_GREEN":
                return EnumChatFormatting.DARK_GREEN.toString();
            case "DARK_RED":
                return EnumChatFormatting.DARK_RED.toString();
            case "DARK_GRAY":
                return EnumChatFormatting.DARK_GRAY.toString();
            case "BLACK":
                return EnumChatFormatting.BLACK.toString();
            case "DARK_BLUE":
                return EnumChatFormatting.DARK_BLUE.toString();
            default:
                return EnumChatFormatting.GOLD.toString();
        }
    }

    private static String mvpPlusPlusSigns(String color) {
        return mvpPlusPlusBase(color);
    }
}
