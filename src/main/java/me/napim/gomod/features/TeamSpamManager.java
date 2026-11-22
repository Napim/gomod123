package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import me.napim.gomod.util.GomodPaths;
import net.minecraft.util.EnumChatFormatting;

/**
 * Manages players for team spam functionality.
 */
public class TeamSpamManager {
    private static final int MOJANG_CONNECT_TIMEOUT_MS = 5000;
    private static final int MOJANG_READ_TIMEOUT_MS = 10000;
    private static File teamSpamDataDir() {
        return GomodPaths.getGomod123Dir();
    }

    private static File teamSpamPlayersFile() {
        return new File(teamSpamDataDir(), "tspam_list.json");
    }
    private static final String RESOURCE_PLAYERS_FILE = "/players.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PLAYER_LIST_TYPE = new TypeToken<List<Player>>() {}.getType();
    private static final long REFRESH_MIN_PACING_NS = 20_000_000L; // 20ms between bulk refresh iterations
    /** Mojang session + Hypixel player lookups during GUI refresh: attempt count before reporting failure. */
    private static final int REFRESH_API_ATTEMPTS = 3;
    /** Backoff between refresh retries (ms), scaled by attempt index. */
    private static final long REFRESH_RETRY_BASE_DELAY_MS = 100L;
    /** Avoid rechecking unchanged profile names every time the Nick Finder refresh button is pressed. */
    private static final long MOJANG_PROFILE_CACHE_MS = 12L * 60L * 60L * 1000L;
    /** Minimum spacing between Mojang session-server profile requests. */
    private static final long MOJANG_MIN_REQUEST_INTERVAL_NS = 300_000_000L;
    private static final long MOJANG_DEFAULT_RETRY_AFTER_MS = 5_000L;
    private static final long MOJANG_MAX_RETRY_AFTER_MS = 300_000L;
    private static final Object MOJANG_REQUEST_LOCK = new Object();
    private static long nextMojangRequestAtNs;
    private static long mojangBackoffUntilNs;
    
    private static List<Player> players = new ArrayList<>();
    private static boolean loaded = false;
    private static boolean usingResourceFile = false;
    
    /**
     * Represents a player for team spam.
     */
    public static class Player {
        public String username; // Made public to allow direct update
        private String uuid;
        private long addedTimestamp;
        private long lastFoundTimestamp; // Separate timestamp for when player was last found
        private boolean hasMVPPlusPlus = true; // Default to true until proven otherwise
        private String rank = ""; // Store the actual rank
        private String rankPlusColor = ""; // Store rank plus color for MVP+
        private String monthlyRankColor = ""; // Store monthly rank color for MVP++
        private long lastMojangProfileCheckTimestamp;
        
        public Player(String username, String uuid) {
            this.username = username;
            this.uuid = uuid;
            this.addedTimestamp = System.currentTimeMillis();
            this.lastFoundTimestamp = 0; // Not found yet
        }
        
        public String getUsername() {
            return username;
        }
        
        public String getUuid() {
            return uuid;
        }
        
        public long getAddedTimestamp() {
            return addedTimestamp;
        }
        
        public void setAddedTimestamp(long timestamp) {
            this.addedTimestamp = timestamp;
        }
        
        public long getLastFoundTimestamp() {
            return lastFoundTimestamp;
        }
        
        public void setLastFoundTimestamp(long timestamp) {
            this.lastFoundTimestamp = timestamp;
        }
        
        public boolean hasMVPPlusPlus() {
            return hasMVPPlusPlus;
        }
        
        public void setHasMVPPlusPlus(boolean hasMVPPlusPlus) {
            this.hasMVPPlusPlus = hasMVPPlusPlus;
        }
        
        public String getRank() {
            return rank;
        }
        
        public void setRank(String rank) {
            this.rank = rank;
        }
        
        public String getRankPlusColor() {
            return rankPlusColor;
        }
        
        public void setRankPlusColor(String rankPlusColor) {
            this.rankPlusColor = rankPlusColor;
        }
        
        public String getMonthlyRankColor() {
            return monthlyRankColor;
        }
        
        public void setMonthlyRankColor(String monthlyRankColor) {
            this.monthlyRankColor = monthlyRankColor;
        }

        public long getLastMojangProfileCheckTimestamp() {
            return lastMojangProfileCheckTimestamp;
        }

        public void setLastMojangProfileCheckTimestamp(long timestamp) {
            this.lastMojangProfileCheckTimestamp = timestamp;
        }
    }
    
    /**
     * Loads players from the JSON file.
     */
    public static void loadPlayers() {
        boolean loadedAnyFile = false;
        usingResourceFile = false;
        
        System.out.println("[TeamSpamManager] Starting to load players...");
        
        // Ensure directory exists
        File configDirFile = teamSpamDataDir();
        if (!configDirFile.exists()) {
            boolean dirCreated = configDirFile.mkdirs();
            if (dirCreated) {
                System.out.println("[TeamSpamManager] Created config directory: " + configDirFile.getAbsolutePath());
            } else {
                System.out.println("[TeamSpamManager] Failed to create config directory: " + configDirFile.getAbsolutePath());
            }
        }
        
        // Try to load from the player file
        File file = teamSpamPlayersFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                players = GSON.fromJson(reader, PLAYER_LIST_TYPE);
                if (players == null) {
                    players = new ArrayList<>();
                } else {
                    loadedAnyFile = true;
                    System.out.println("[TeamSpamManager] Loaded " + players.size() + " players from " + file.getAbsolutePath());
                    
                    // If we successfully loaded from the main file, don't try to load from other sources
                    // This prevents duplicate player entries
                    loaded = true;
                    publishAllRanksToTabStatsCache();
                    return;
                }
            } catch (IOException e) {
                System.err.println("[TeamSpamManager] Error loading players from " + file.getAbsolutePath() + ": " + e.getMessage());
                players = new ArrayList<>();
            }
        } else {
            System.out.println("[TeamSpamManager] File " + file.getAbsolutePath() + " does not exist");
        }
        
        // If we couldn't load from the file, load the default players from resource
        if (!loadedAnyFile) {
            players.clear(); // Start with a clean list
            
            try {
                System.out.println("[TeamSpamManager] Attempting to load default players from resource");
                InputStream is = TeamSpamManager.class.getResourceAsStream(RESOURCE_PLAYERS_FILE);
                
                if (is != null) {
                    try (InputStreamReader reader = new InputStreamReader(is)) {
                        // Resource file has a different format
                        JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
                        JsonArray playersArray = json.getAsJsonArray("players");
                        
                        for (int i = 0; i < playersArray.size(); i++) {
                            JsonObject playerObj = playersArray.get(i).getAsJsonObject();
                            String name = playerObj.get("name").getAsString();
                            String uuid = playerObj.get("uuid").getAsString();
                            players.add(new Player(name, uuid));
                        }
                        
                        loadedAnyFile = true;
                        usingResourceFile = true;
                        System.out.println("[TeamSpamManager] Successfully loaded " + players.size() + " default players from resource");
                    }
                } else {
                    System.out.println("[TeamSpamManager] No resource file found at " + RESOURCE_PLAYERS_FILE);
                    
                    // Add a default list of players as fallback
                    System.out.println("[TeamSpamManager] Using hardcoded default player list");
                    players.add(new Player("_checkered", "12a6476f-059c-4924-a56f-c4807433df62"));
                    players.add(new Player("cjk500", "17f23144-5440-4d37-9598-ea466c2c150f"));
                    players.add(new Player("Jurce", "be5bc621-1107-478f-8c93-e4d2975e4afa"));
                    // Just a few defaults as example
                    
                    loadedAnyFile = true;
                    usingResourceFile = true;
                }
                
                // Print players for verification
                int countToShow = Math.min(5, players.size());
                System.out.println("[TeamSpamManager] First " + countToShow + " default players:");
                for (int i = 0; i < countToShow; i++) {
                    System.out.println("  - " + players.get(i).getUsername() + " (" + players.get(i).getUuid() + ")");
                }
                
            } catch (Exception e) {
                System.err.println("[TeamSpamManager] Error loading default players: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // If all methods failed, create a new empty file
        if (!loadedAnyFile) {
            System.out.println("[TeamSpamManager] No player files found. Creating new empty file.");
            players = new ArrayList<>();
            savePlayers();
        }
        
        System.out.println("[TeamSpamManager] Final player count: " + players.size());
        System.out.println("[TeamSpamManager] Using resource file: " + usingResourceFile);

        publishAllRanksToTabStatsCache();
        loaded = true;
    }

    private static void applyHypixelRankData(Player player, String[] rankData) {
        if (player == null || rankData == null || rankData.length == 0) {
            return;
        }
        boolean hasMVPPlusPlus = "MVP++".equals(rankData[0]);
        player.setHasMVPPlusPlus(hasMVPPlusPlus);
        player.setRank(rankData[0]);
        player.setRankPlusColor(rankData.length > 1 ? rankData[1] : "");
        player.setMonthlyRankColor(rankData.length > 2 ? rankData[2] : "");
        publishRankToTabStatsCache(player);
    }

    private static void applyHypixelRankFields(Player player, String rank, String rankPlusColor,
            String monthlyRankColor, boolean hasMVPPlusPlus) {
        if (player == null) {
            return;
        }
        player.setHasMVPPlusPlus(hasMVPPlusPlus);
        player.setRank(rank);
        player.setRankPlusColor(rankPlusColor == null ? "" : rankPlusColor);
        player.setMonthlyRankColor(monthlyRankColor == null ? "" : monthlyRankColor);
        publishRankToTabStatsCache(player);
    }

    private static void publishRankToTabStatsCache(Player player) {
        if (player == null || player.getUsername() == null || player.getUsername().trim().isEmpty()) {
            return;
        }
        HypixelTabStatsRankCache.ingestSnapshot(
                player.getUsername().trim(),
                player.getRank(),
                player.getRankPlusColor(),
                player.getMonthlyRankColor());
    }

    private static void publishAllRanksToTabStatsCache() {
        final List<Player> snapshot = new ArrayList<>(players);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                for (Player player : snapshot) {
                    publishRankToTabStatsCache(player);
                }
            }
        }, "gomod-tspam-rank-cache");
        worker.setDaemon(true);
        worker.start();
    }
    
    /**
     * Saves players to the JSON file.
     */
    public static void savePlayers() {
        try {
            // Ensure the directory exists
            File configDirFile = teamSpamDataDir();
            if (!configDirFile.exists()) {
                boolean dirCreated = configDirFile.mkdirs();
                if (!dirCreated) {
                    System.out.println("[TeamSpamManager] Failed to create config directory: " + configDirFile.getAbsolutePath());
                }
            }
            
            File file = teamSpamPlayersFile();
            if (!file.exists()) {
                file.createNewFile();
            }
            
            FileWriter writer = new FileWriter(file);
            GSON.toJson(players, writer);
            writer.close();
            
            // When we save players, we're no longer using the resource file
            usingResourceFile = false;
            
            System.out.println("Saved " + players.size() + " players to " + file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error saving players: " + e.getMessage());
        }
    }
    
    /**
     * Adds a player to the team spam list.
     * 
     * @param username The player's username
     * @return A message indicating success or failure
     */
    public static String addPlayer(String username) {
        if (!loaded) {
            loadPlayers();
        }
        
        // Check if player already exists
        for (Player player : players) {
            if (player.getUsername().equalsIgnoreCase(username)) {
                return me.napim.gomod.Main.CHAT_PREFIX + "Player " + player.getUsername() + " is already in the team spam list.";
            }
        }
        
        try {
            // Get correct username and UUID from Mojang API
            String[] playerData = getPlayerData(username);
            if (playerData == null) {
                return "Failed to get data for player " + username + ". Player might not exist.";
            }
            
            String correctUsername = playerData[0];
            String uuid = playerData[1];
            
            // Build player and enrich with rank info if possible
            Player newPlayer = new Player(correctUsername, uuid);
            // Set a fresh "found" timestamp so the player appears at the top of the GUI list
            newPlayer.setLastFoundTimestamp(System.currentTimeMillis());

            // Try to fetch rank details via Hypixel API if a key is set
            String apiKey = ApiKeyManager.getApiKey("hypixel");
            String rankTagForMessage = "";
            System.out.println("[TeamSpamManager] API key is " + (apiKey != null && !apiKey.isEmpty() ? "set" : "NOT SET"));
            if (apiKey != null && !apiKey.isEmpty()) {
                try {
                    String[] rankData = checkPlayerRank(uuid, apiKey);
                    System.out.println("[TeamSpamManager] Rank data received: " + (rankData != null ? rankData[0] : "null"));
                    applyHypixelRankData(newPlayer, rankData);
                    rankTagForMessage = buildRankTag(newPlayer);
                    System.out.println("[TeamSpamManager] Player " + correctUsername + " has MVP++: " + newPlayer.hasMVPPlusPlus());
                } catch (Exception rankEx) {
                    // Keep defaults if rank lookup fails; include a brief note in the message
                    System.out.println("[TeamSpamManager] Rank check failed: " + rankEx.getMessage());
                    rankTagForMessage = "";
                }
            } else {
                System.out.println("[TeamSpamManager] No API key set, cannot check rank for " + correctUsername);
            }
            
            // Add player to list
            // If player is MVP++, add them at the top of the MVP++ section (first position)
            // Otherwise, add them at the end
            System.out.println("[TeamSpamManager] Player " + correctUsername + " hasMVPPlusPlus: " + newPlayer.hasMVPPlusPlus());
            System.out.println("[TeamSpamManager] Current list size: " + players.size());
            if (newPlayer.hasMVPPlusPlus()) {
                // Find the first MVP++ player's position and insert before them
                // This makes new MVP++ players appear at the TOP of the MVP++ list
                int insertPosition = 0; // Default to beginning if no MVP++ found
                for (int i = 0; i < players.size(); i++) {
                    if (players.get(i).hasMVPPlusPlus()) {
                        insertPosition = i;
                        System.out.println("[TeamSpamManager] Found existing MVP++ at position " + i);
                        break;
                    }
                }
                // If no MVP++ players exist yet, insert at position 0 (top of list)
                System.out.println("[TeamSpamManager] Inserting MVP++ player at position " + insertPosition);
                players.add(insertPosition, newPlayer);
            } else {
                // Non-MVP++ players go at the end
                System.out.println("[TeamSpamManager] Adding non-MVP++ player at end (position " + players.size() + ")");
                players.add(newPlayer);
            }
            savePlayers();
            
            String usernameColor = getUsernameColor(newPlayer);
            String addedPlayerSegment = rankTagForMessage.isEmpty() ? " " : rankTagForMessage + " ";
            if (!correctUsername.equals(username)) {
                return me.napim.gomod.Main.CHAT_PREFIX + "Added player" + addedPlayerSegment + usernameColor + correctUsername + EnumChatFormatting.RESET + " to team spam list.";
            } else {
                return me.napim.gomod.Main.CHAT_PREFIX + "Added player" + addedPlayerSegment + usernameColor + correctUsername + EnumChatFormatting.RESET + " to team spam list.";
            }
        } catch (Exception e) {
            if (e.getMessage().contains("403")) {
                return "API Error: No Hypixel API key set. Please set one with /gmapi hypixel <your-api-key>";
            } else if (e.getMessage().contains("401")) {
                return "API Error: Invalid Hypixel API key. Please renew it with /gmapi hypixel <your-api-key>";
            } else {
                return "Error adding player: " + e.getMessage();
            }
        }
    }

    private static String buildRankTag(Player player) {
        String rank = player.getRank();
        if (rank == null || rank.isEmpty() || "Default".equals(rank)) {
            return "";
        }
        // MVP++ special coloring: monthlyRankColor for base, rankPlusColor for ++
        if ("MVP++".equals(rank)) {
            String base = getMVPPlusPlusColor(player.getMonthlyRankColor());
            String plus = getMVPPlusPlusSignsColor(player.getRankPlusColor());
            return base + " [MVP" + plus + "++" + base + "]" + EnumChatFormatting.RESET;
        }
        // MVP+ special coloring: aqua base, rankPlusColor for +
        if ("MVP+".equals(rank)) {
            String base = EnumChatFormatting.AQUA + "";
            String plus = getMVPPlusColor(player.getRankPlusColor());
            return base + " [MVP" + plus + "+" + base + "]" + EnumChatFormatting.RESET;
        }
        // Hypixel's STAFF API rank is rendered in-game as [ዞ], with a gold glyph
        // and red brackets (and the player name is red as well).
        if ("STAFF".equals(rank)) {
            return EnumChatFormatting.RED + " [" + EnumChatFormatting.GOLD + "\u12de"
                    + EnumChatFormatting.RED + "]" + EnumChatFormatting.RESET;
        }
        // Common ranks
        String color = getRankColor(rank);
        return color + " [" + rank + "]" + EnumChatFormatting.RESET;
    }
    
    private static String getUsernameColor(Player player) {
        String rank = player.getRank();
        if (rank == null || rank.isEmpty()) {
            return EnumChatFormatting.WHITE + "";
        }
        // MVP++: use monthlyRankColor for username
        if ("MVP++".equals(rank)) {
            return getMVPPlusPlusColor(player.getMonthlyRankColor());
        }
        // MVP+: use aqua for username
        if ("MVP+".equals(rank)) {
            return EnumChatFormatting.AQUA + "";
        }
        // Other ranks: use rank color for username
        return getRankColor(rank);
    }

    private static String getRankColor(String rank) {
        if (rank == null) return EnumChatFormatting.YELLOW + "";
        switch (rank) {
            case "STAFF": return EnumChatFormatting.RED + "";
            case "OWNER": return EnumChatFormatting.DARK_RED + "";
            case "ADMIN": return EnumChatFormatting.RED + "";
            case "GM": return EnumChatFormatting.DARK_GREEN + "";
            case "YOUTUBER": return EnumChatFormatting.GOLD + "";
            case "MOJANG": return EnumChatFormatting.GOLD + "";
            case "EVENTS": return EnumChatFormatting.LIGHT_PURPLE + "";
            case "INNIT": return EnumChatFormatting.LIGHT_PURPLE + "";
            case "MVP": return EnumChatFormatting.AQUA + "";
            case "VIP+": return EnumChatFormatting.GOLD + "";
            case "VIP": return EnumChatFormatting.GREEN + "";
            case "Default": return EnumChatFormatting.GRAY + "";
            default: return EnumChatFormatting.YELLOW + "";
        }
    }

    private static String getMVPPlusColor(String rankPlusColor) {
        if (rankPlusColor == null || rankPlusColor.isEmpty()) {
            return EnumChatFormatting.GOLD + "";
        }
        switch (rankPlusColor) {
            case "RED": return EnumChatFormatting.RED + "";
            case "BLUE": return EnumChatFormatting.BLUE + "";
            case "GREEN": return EnumChatFormatting.GREEN + "";
            case "YELLOW": return EnumChatFormatting.YELLOW + "";
            case "GOLD": return EnumChatFormatting.GOLD + "";
            case "AQUA": return EnumChatFormatting.AQUA + "";
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + "";
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + "";
            case "DARK_AQUA": return EnumChatFormatting.DARK_AQUA + "";
            case "DARK_GREEN": return EnumChatFormatting.DARK_GREEN + "";
            case "DARK_RED": return EnumChatFormatting.DARK_RED + "";
            case "DARK_BLUE": return EnumChatFormatting.DARK_BLUE + "";
            case "WHITE": return EnumChatFormatting.WHITE + "";
            case "GRAY": return EnumChatFormatting.GRAY + "";
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + "";
            case "BLACK": return EnumChatFormatting.BLACK + "";
            default: return EnumChatFormatting.GOLD + "";
        }
    }

    private static String getMVPPlusPlusColor(String monthlyRankColor) {
        if (monthlyRankColor == null || monthlyRankColor.isEmpty()) {
            return EnumChatFormatting.GOLD + "";
        }
        switch (monthlyRankColor) {
            case "DEFAULT": return EnumChatFormatting.GOLD + "";
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + "";
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + "";
            case "DARK_AQUA": return EnumChatFormatting.DARK_AQUA + "";
            case "AQUA": return EnumChatFormatting.AQUA + "";
            case "RED": return EnumChatFormatting.RED + "";
            case "GOLD": return EnumChatFormatting.GOLD + "";
            case "GREEN": return EnumChatFormatting.GREEN + "";
            case "YELLOW": return EnumChatFormatting.YELLOW + "";
            case "WHITE": return EnumChatFormatting.WHITE + "";
            case "BLUE": return EnumChatFormatting.BLUE + "";
            case "DARK_GREEN": return EnumChatFormatting.DARK_GREEN + "";
            case "DARK_RED": return EnumChatFormatting.DARK_RED + "";
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + "";
            case "BLACK": return EnumChatFormatting.BLACK + "";
            case "DARK_BLUE": return EnumChatFormatting.DARK_BLUE + "";
            default: return EnumChatFormatting.GOLD + "";
        }
    }

    private static String getMVPPlusPlusSignsColor(String rankPlusColor) {
        if (rankPlusColor == null || rankPlusColor.isEmpty()) {
            return EnumChatFormatting.GOLD + "";
        }
        switch (rankPlusColor) {
            case "DEFAULT": return EnumChatFormatting.GOLD + "";
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + "";
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + "";
            case "DARK_AQUA": return EnumChatFormatting.DARK_AQUA + "";
            case "AQUA": return EnumChatFormatting.AQUA + "";
            case "RED": return EnumChatFormatting.RED + "";
            case "GOLD": return EnumChatFormatting.GOLD + "";
            case "GREEN": return EnumChatFormatting.GREEN + "";
            case "YELLOW": return EnumChatFormatting.YELLOW + "";
            case "WHITE": return EnumChatFormatting.WHITE + "";
            case "BLUE": return EnumChatFormatting.BLUE + "";
            case "DARK_GREEN": return EnumChatFormatting.DARK_GREEN + "";
            case "DARK_RED": return EnumChatFormatting.DARK_RED + "";
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + "";
            case "BLACK": return EnumChatFormatting.BLACK + "";
            case "DARK_BLUE": return EnumChatFormatting.DARK_BLUE + "";
            default: return EnumChatFormatting.GOLD + "";
        }
    }

    /**
     * Updates a single player's information (name and rank) using Mojang and Hypixel APIs.
     * @param username The player's current username (case-insensitive)
     * @return A result message for chat
     */
    public static String updatePlayerInfo(String username) {
        if (!loaded) {
            loadPlayers();
        }

        Player player = findPlayerByName(username);
        if (player == null) {
            return me.napim.gomod.Main.CHAT_PREFIX + "Player " + username + " is not in the team spam list.";
        }

        String oldUsername = player.getUsername();
        String uuid = player.getUuid();
        int updates = 0;
        int errors = 0;
        List<String> errorDetails = new ArrayList<String>();

        // Update current username via Mojang API
        try {
            String currentUsername = getCurrentUsername(uuid);
            if (currentUsername != null && !currentUsername.equals(oldUsername)) {
                player.username = currentUsername;
                updates++;
            }
        } catch (Exception e) {
            errors++;
            errorDetails.add("Mojang name lookup: " + shortError(e));
        }

        // Update rank info via Hypixel API
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        boolean rankUpdated = false;
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                String[] rankData = checkPlayerRank(uuid, apiKey, true);
                applyHypixelRankData(player, rankData);
                rankUpdated = true;
                updates++;
            } catch (Exception e) {
                errors++;
                errorDetails.add("Hypixel player refresh: " + shortError(e));
            }
        }

        savePlayers();

        StringBuilder msg = new StringBuilder();
        msg.append(me.napim.gomod.Main.CHAT_PREFIX)
           .append("Updated");
        String tag = buildRankTag(player);
        // tag already includes a leading space if not empty
        if (!tag.isEmpty()) {
            msg.append(tag).append(" ");
        } else {
            msg.append(" ");
        }
        String usernameColor = getUsernameColor(player);
        msg.append(usernameColor).append(player.getUsername()).append(EnumChatFormatting.RESET);
        if (updates == 0) {
            msg.append(". No changes.");
        }
        if (rankUpdated) {
            msg.append(" ").append(EnumChatFormatting.GRAY).append("(Rank and gostats123 stats refreshed)");
        }
        if (errors > 0) {
            msg.append(" ").append(EnumChatFormatting.GRAY).append("(")
                    .append(errors).append(" issue").append(errors == 1 ? "" : "s")
                    .append(" during update: ").append(String.join("; ", errorDetails)).append(")");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            msg.append(" ").append(EnumChatFormatting.YELLOW).append("(Set Hypixel API key to update rank)");
        }
        return msg.toString();
    }

    private static String shortError(Exception error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "request failed" : error.getClass().getSimpleName();
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 140 ? compact.substring(0, 137) + "..." : compact;
    }

    /**
     * Refreshes one list entry (Mojang current name by UUID, then Hypixel rank). Retries each network step up to
     * {@link #REFRESH_API_ATTEMPTS} times. On persistent failure, appends a chat-ready line to {@code errorsOut} if
     * non-null, then continues (e.g. Hypixel still runs after Mojang failure).
     */
    public static void updatePlayerInfoInPlace(Player player, List<String> errorsOut) {
        if (!loaded) {
            loadPlayers();
        }
        if (player == null) {
            return;
        }

        String uuid = player.getUuid();
        String oldUsername = player.getUsername();

        String currentUsername = null;
        Exception mojangLast = null;
        long lastProfileCheck = player.getLastMojangProfileCheckTimestamp();
        long now = System.currentTimeMillis();
        boolean shouldCheckMojang = lastProfileCheck <= 0L || now - lastProfileCheck >= MOJANG_PROFILE_CACHE_MS;
        if (shouldCheckMojang) {
            for (int i = 0; i < REFRESH_API_ATTEMPTS; i++) {
                try {
                    currentUsername = getCurrentUsername(uuid);
                    mojangLast = null;
                    player.setLastMojangProfileCheckTimestamp(System.currentTimeMillis());
                    break;
                } catch (Exception e) {
                    mojangLast = e;
                    // A 429 is a shared service throttle, not a transient per-player error. The
                    // request gate delays later players; retrying this same UUID would add load.
                    if (e instanceof MojangRateLimitException) {
                        break;
                    }
                    if (i < REFRESH_API_ATTEMPTS - 1) {
                        sleepRefreshRetryBackoff(i);
                    }
                }
            }
        }
        if (mojangLast != null) {
            appendRefreshFailure(errorsOut, "Mojang profile", player.getUsername(), mojangLast);
        }
        if (currentUsername != null && !currentUsername.equals(oldUsername)) {
            player.username = currentUsername;
        } else if (currentUsername != null && !currentUsername.equals(player.username)) {
            player.username = currentUsername;
        }

        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey != null && !apiKey.isEmpty()) {
            String[] rankData = null;
            Exception hypixelLast = null;
            for (int i = 0; i < REFRESH_API_ATTEMPTS; i++) {
                try {
                    rankData = checkPlayerRank(uuid, apiKey);
                    hypixelLast = null;
                    break;
                } catch (Exception e) {
                    hypixelLast = e;
                    if (i < REFRESH_API_ATTEMPTS - 1) {
                        sleepRefreshRetryBackoff(i);
                    }
                }
            }
            if (rankData != null) {
                applyHypixelRankData(player, rankData);
            } else if (hypixelLast != null) {
                appendRefreshFailure(errorsOut, "Hypixel rank", player.getUsername(), hypixelLast);
            }
        }
    }

    private static void sleepRefreshRetryBackoff(int attemptIndex) {
        long ms = REFRESH_RETRY_BASE_DELAY_MS * (attemptIndex + 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void appendRefreshFailure(List<String> errorsOut, String what, String username, Exception e) {
        if (errorsOut == null) {
            return;
        }
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        errorsOut.add(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RED + what + " failed for "
                + EnumChatFormatting.WHITE + username + EnumChatFormatting.RED + ": " + EnumChatFormatting.GRAY + msg);
    }

    public static long getRefreshMinPacingNs() {
        return REFRESH_MIN_PACING_NS;
    }

    private static void enforceRefreshPacing(long iterationStartNs) {
        long elapsedNs = System.nanoTime() - iterationStartNs;
        long remainingNs = REFRESH_MIN_PACING_NS - elapsedNs;
        if (remainingNs > 0) {
            try {
                Thread.sleep(remainingNs / 1_000_000L, (int) (remainingNs % 1_000_000L));
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Updates only a player's rank information using the Hypixel API (no Mojang calls).
     * Faster than updatePlayerInfo since it skips username lookups.
     * @param username The player's current username (case-insensitive)
     * @return A result message for chat
     */
    public static String updatePlayerRankOnly(String username) {
        if (!loaded) {
            loadPlayers();
        }

        Player player = findPlayerByName(username);
        if (player == null) {
            return me.napim.gomod.Main.CHAT_PREFIX + "Player " + username + " is not in the team spam list.";
        }

        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.isEmpty()) {
            return me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RED + "No Hypixel API key set. Use /gmapi hypixel <key>";
        }

        int updates = 0;
        int errors = 0;

        try {
            String[] rankData = checkPlayerRank(player.getUuid(), apiKey);
            applyHypixelRankData(player, rankData);
            updates++;
        } catch (Exception e) {
            errors++;
        }

        savePlayers();

        StringBuilder msg = new StringBuilder();
        msg.append(me.napim.gomod.Main.CHAT_PREFIX)
           .append("Updated");
        String tag = buildRankTag(player);
        // tag already includes a leading space if not empty
        if (!tag.isEmpty()) {
            msg.append(tag).append(" ");
        } else {
            msg.append(" ");
        }
        String usernameColor = getUsernameColor(player);
        msg.append(usernameColor).append(player.getUsername()).append(EnumChatFormatting.RESET);
        if (updates == 0) {
            msg.append(". No changes.");
        }
        msg.append(" ").append(EnumChatFormatting.GRAY).append("(Rank refreshed)");
        if (errors > 0) {
            msg.append(" ").append(EnumChatFormatting.GRAY).append("(1 issue during update)");
        }
        return msg.toString();
    }

    /**
     * Refreshes only Hypixel rank information for all players (skips Mojang username lookups).
     * Includes rate limiting and 20ms minimum pacing per player.
     * @return Summary message
     */
    public static String refreshAllRanksOnly() {
        if (!loaded) {
            loadPlayers();
        }

        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: No Hypixel API key set. Please set one with /gmapi hypixel <your-api-key>";
        }

        int checkedCount = 0;
        int mvpPlusPlusCount = 0;
        int errorCount = 0;

        int processed = 0;
        long windowStart = System.currentTimeMillis();
        for (Player player : players) {
            long iterationStartNs = System.nanoTime();
            try {
                String[] rankData = checkPlayerRank(player.getUuid(), apiKey);
                boolean hasMVPPlusPlus = "MVP++".equals(rankData[0]);
                applyHypixelRankFields(player, rankData[0],
                        rankData.length > 1 ? rankData[1] : "",
                        rankData.length > 2 ? rankData[2] : "",
                        hasMVPPlusPlus);
                checkedCount++;
                if (hasMVPPlusPlus) {
                    mvpPlusPlusCount++;
                }

                // Rate limit blocks of 300 per 5 minutes
                processed++;
                if (processed % 300 == 0) {
                    long elapsed = System.currentTimeMillis() - windowStart;
                    long minWindow = 5 * 60 * 1000L; // 5 minutes
                    if (elapsed < minWindow) {
                        long sleepMs = minWindow - elapsed;
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                    }
                    windowStart = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("[TeamSpamManager] Error checking rank for " + player.getUsername() + ": " + e.getMessage());
                errorCount++;
            }
            enforceRefreshPacing(iterationStartNs);
        }

        savePlayers();
        notifyGUIRefresh();

        StringBuilder result = new StringBuilder();
        result.append("Checked ").append(checkedCount).append(" players: ");
        result.append(mvpPlusPlusCount).append(" have MVP++, ");
        result.append(checkedCount - mvpPlusPlusCount).append(" do not. ");
        if (errorCount > 0) {
            result.append(errorCount).append(" errors occurred.");
        }
        return result.toString();
    }
    
    /**
     * Removes a player from the team spam list.
     * 
     * @param username The player's username
     * @return A message indicating success or failure
     */
    public static String removePlayer(String username) {
        if (!loaded) {
            loadPlayers();
        }
        
        // Sanitize incoming name (strip color codes and rank tags)
        String sanitized = username
            .replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "")
            .replaceAll("^\\[[^]]+\\]\\s+", "")
            .trim();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUsername().equalsIgnoreCase(sanitized)) {
                Player removed = players.get(i);
                String correctUsername = removed.getUsername();
                String tag = buildRankTag(removed);
                String usernameColor = getUsernameColor(removed);
                players.remove(i);
                savePlayers();
                // tag already includes a leading space if not empty
                return me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RESET + "Removed player" +
                    (tag.isEmpty() ? " " : tag + " ") + usernameColor + correctUsername + EnumChatFormatting.RESET + " from team spam list.";
            }
        }
        
        return me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RESET + "Player " + EnumChatFormatting.WHITE + sanitized + EnumChatFormatting.RESET + " is not in the team spam list.";
    }
    
    /**
     * Gets a list of all players in the team spam list, sorted by most recently added.
     * 
     * @return A list of player usernames
     */
    public static List<Player> getPlayers() {
        if (!loaded) {
            loadPlayers();
        }
        
        // Sort by most recently added
        List<Player> sortedPlayers = new ArrayList<>(players);
        Collections.sort(sortedPlayers, new Comparator<Player>() {
            @Override
            public int compare(Player p1, Player p2) {
                return Long.compare(p2.getAddedTimestamp(), p1.getAddedTimestamp());
            }
        });
        
        return sortedPlayers;
    }
    
    /**
     * Checks if the current player list is from the resource file.
     * 
     * @return true if using the resource file, false otherwise
     */
    public static boolean isUsingResourceFile() {
        if (!loaded) {
            loadPlayers();
        }
        return usingResourceFile;
    }
    
    /**
     * Clears all players from the team spam list.
     */
    public static void clearPlayers() {
        players.clear();
        savePlayers();
        System.out.println("Cleared all players from team spam list.");
    }
    
    /**
     * Gets player data (username and UUID) from the Mojang API.
     * 
     * @param username The player's username
     * @return An array containing [correctUsername, uuid], or null if not found
     */
    private static String[] getPlayerData(String username) throws Exception {
        URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        
        if (conn.getResponseCode() == 403) {
            throw new Exception("403 - Forbidden. API key may be missing or invalid.");
        } else if (conn.getResponseCode() == 401) {
            throw new Exception("401 - Unauthorized. API key is invalid.");
        } else if (conn.getResponseCode() != 200) {
            return null;
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        JsonObject jsonResponse = new JsonParser().parse(response.toString()).getAsJsonObject();
        if (jsonResponse.has("id") && jsonResponse.has("name")) {
            String uuid = jsonResponse.get("id").getAsString();
            String correctUsername = jsonResponse.get("name").getAsString();
            return new String[] { correctUsername, uuid };
        }
        
        return null;
    }
    
    /**
     * Updates the last found timestamp for a player to mark them as recently found.
     * 
     * @param username The player's username
     * @return True if the player was found and updated, false otherwise
     */
    public static boolean updatePlayerTimestamp(String username) {
        if (!loaded) {
            loadPlayers();
        }
        
        // Find the player in the list (case insensitive)
        for (Player player : players) {
            if (player.getUsername().equalsIgnoreCase(username)) {
                // Update the last found timestamp to current time (not the added timestamp)
                player.setLastFoundTimestamp(System.currentTimeMillis());
                
                // Save the changes
                savePlayers();
                
                System.out.println("[TeamSpamManager] Updated last found timestamp for player " + player.getUsername() + " to " + new java.text.SimpleDateFormat("MM/dd/yy HH:mm").format(new java.util.Date()));
                
                // Notify GUI to refresh if it's open
                notifyGUIRefresh();
                
                return true;
            }
        }
        
        // Player not found
        return false;
    }
    
    /**
     * Notifies the GUI to refresh the player list when a player is found.
     */
    private static void notifyGUIRefresh() {
        try {
            // Get the current GUI screen
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null) {
                // Schedule a GUI refresh on the main thread
                mc.addScheduledTask(() -> {
                    try {
                        // Use the static method to refresh the GUI
                        me.napim.gomod.gui.TeamSpamGUI.refreshCurrentGUI();
                    } catch (Exception e) {
                        System.out.println("[TeamSpamManager] Could not refresh GUI: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
        } catch (Exception e) {
            // Silently handle any errors to avoid disrupting the main functionality
            System.out.println("[TeamSpamManager] Error notifying GUI refresh: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Finds a player in the team spam list by their name.
     * 
     * @param username The player's username
     * @return The Player object if found, or null if not found
     */
    public static Player findPlayerByName(String username) {
        if (!loaded) {
            loadPlayers();
        }
        
        for (Player player : players) {
            if (player.getUsername().equalsIgnoreCase(username)) {
                return player;
            }
        }
        
        return null;
    }
    
    /**
     * Checks all players' MVP++ status using the Hypixel API and updates player names based on their UUIDs.
     * Players without MVP++ will be marked accordingly, and names will be updated if they've changed.
     * 
     * @return A message with the results of the check
     */
    public static String checkAllPlayersMVPPlusPlus() {
        if (!loaded) {
            loadPlayers();
        }
        
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.isEmpty()) {
            return "Error: No Hypixel API key set. Please set one with /gmapi hypixel <your-api-key>";
        }
        
        // First, run the name update process
        String nameUpdateResult = updateAllPlayerNames();
        System.out.println("[TeamSpamManager] Name update result: " + nameUpdateResult);
        
        int checkedCount = 0;
        int mvpPlusPlusCount = 0;
        int nameUpdatedCount = 0;
        int errorCount = 0;
        
        int processed = 0;
        long windowStart = System.currentTimeMillis();
        for (Player player : players) {
            long iterationStartNs = System.nanoTime();
            try {
                // Name update is handled in updateAllPlayerNames() above
                // Now check MVP++ status and rank
                String[] rankData = checkPlayerRank(player.getUuid(), apiKey);
                boolean hasMVPPlusPlus = "MVP++".equals(rankData[0]);
                applyHypixelRankFields(player, rankData[0],
                        rankData.length > 1 ? rankData[1] : "",
                        rankData.length > 2 ? rankData[2] : "",
                        hasMVPPlusPlus);
                checkedCount++;
                
                if (hasMVPPlusPlus) {
                    mvpPlusPlusCount++;
                }
                
                // Rate limiting: Hypixel allows 300 requests / 5 minutes
                processed++;
                if (processed % 300 == 0) {
                    long elapsed = System.currentTimeMillis() - windowStart;
                    long minWindow = 5 * 60 * 1000L; // 5 minutes
                    if (elapsed < minWindow) {
                        long sleepMs = minWindow - elapsed;
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                    }
                    windowStart = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("[TeamSpamManager] Error checking player " + player.getUsername() + ": " + e.getMessage());
                errorCount++;
            }
            enforceRefreshPacing(iterationStartNs);
        }
        
        // Save the updated player data
        savePlayers();

        // Notify GUI to refresh and re-sort so MVP++ appear above others after refresh
        notifyGUIRefresh();
        
        StringBuilder result = new StringBuilder();
        result.append("Checked ").append(checkedCount).append(" players: ");
        result.append(mvpPlusPlusCount).append(" have MVP++, ");
        result.append(checkedCount - mvpPlusPlusCount).append(" do not. ");
        
        // Always report name check results, even if no names were updated
        result.append("Checked ").append(checkedCount).append(" player names");
        if (nameUpdatedCount > 0) {
            result.append(": ").append(nameUpdatedCount).append(" were updated");
        } else {
            result.append(": all names are current");
        }
        result.append(". ");
        
        if (errorCount > 0) {
            result.append(errorCount).append(" errors occurred.");
        }
        
        return result.toString();
    }
    
    /**
     * Checks a player's rank using the Hypixel API.
     * 
     * @param uuid The player's UUID
     * @param apiKey The Hypixel API key
     * @return Array with [rank, rankPlusColor, monthlyRankColor] where rank is the actual rank string
     */
    private static String[] checkPlayerRank(String uuid, String apiKey) throws Exception {
        return checkPlayerRank(uuid, apiKey, false);
    }

    private static String[] checkPlayerRank(String uuid, String apiKey, boolean requireGoStatsReceipt) throws Exception {
        JsonObject cached = HypixelPlayerDataCache.getFreshByUuid(uuid);
        JsonObject jsonResponse;
        if (cached != null && "Hypixel".equalsIgnoreCase(HypixelPlayerRequests.source(cached))
                && !HypixelPlayerRequests.blitzOnly(cached)) {
            // A profile fetched moments ago is already the freshest available; contribute it without
            // a duplicate request (or Hypixel's per-player "looked up too recently" rejection).
            GoStatsContributions.observeExplicitPlayerInfo(cached);
            jsonResponse = new JsonObject();
            jsonResponse.addProperty("success", true);
            jsonResponse.add("player", cached);
        } else {
            // Explicit Nick Finder refreshes bypass stale profile caches. The shared response observer
            // uploads Blitz stats to gostats123 and this same response updates the saved Hypixel rank.
            jsonResponse = HypixelApiHelper.fetchJsonFresh(
                    "https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid);
        }
        me.napim.gomod.features.GoStatsBlitzClient.cacheSuccessfulHypixelPlayerResponse(uuid, jsonResponse);
        if (requireGoStatsReceipt) {
            GoStatsContributions.SubmissionResult contribution =
                    GoStatsContributions.submitFreshHypixelResponse(jsonResponse);
            if (!contribution.isUsableByLeaderboard() && !"stale".equalsIgnoreCase(contribution.status)) {
                if ("quarantined".equalsIgnoreCase(contribution.status)) {
                    throw new Exception("gostats123 received the stats but held them for review.");
                }
                throw new Exception(contribution.failureDescription());
            }
        }
        
        // Check if the request was successful
        if (!jsonResponse.get("success").getAsBoolean()) {
            throw new Exception("API request was not successful: " + 
                               (jsonResponse.has("cause") ? jsonResponse.get("cause").getAsString() : "Unknown reason"));
        }
        
        // Check if player exists in response
        if (!jsonResponse.has("player") || jsonResponse.get("player").isJsonNull()) {
            return new String[]{"Default", "", ""}; // Player not found, assume default rank
        }
        
        JsonObject playerData = jsonResponse.getAsJsonObject("player");
        
        // Extract colors first
        String rankPlusColor = playerData.has("rankPlusColor") ? playerData.get("rankPlusColor").getAsString() : "";
        String monthlyRankColor = playerData.has("monthlyRankColor") ? playerData.get("monthlyRankColor").getAsString() : "";

        // STAFF is authoritative even when an older purchase-rank prefix/field is also present.
        if (playerData.has("rank") && "STAFF".equals(playerData.get("rank").getAsString())) {
            return new String[]{"STAFF", "", ""};
        }
        
        // Check for special prefixes first
        if (playerData.has("prefix")) {
            String prefix = playerData.get("prefix").getAsString();
            if (prefix.contains("MOJANG")) {
                return new String[]{"MOJANG", "", ""};
            } else if (prefix.contains("EVENTS")) {
                return new String[]{"EVENTS", "", ""};
            } else if (prefix.contains("INNIT")) {
                return new String[]{"INNIT", "", ""};
            } else if (prefix.contains("OWNER")) {
                return new String[]{"OWNER", "", ""};
            }
        }
        
        // Check for special ranks
        if (playerData.has("rank")) {
            String rank = playerData.get("rank").getAsString();
            if (rank.equals("YOUTUBER")) {
                return new String[]{"YOUTUBER", "", ""};
            } else if (rank.equals("ADMIN")) {
                // Check if it's actually OWNER based on prefix
                if (playerData.has("prefix") && playerData.get("prefix").getAsString().contains("OWNER")) {
                    return new String[]{"OWNER", "", ""};
                }
                return new String[]{"ADMIN", "", ""};
            } else if (rank.equals("GAME_MASTER")) {
                return new String[]{"GM", "", ""};
            } else if (rank.equals("MVP_PLUS_PLUS")) {
                return new String[]{"MVP++", rankPlusColor, monthlyRankColor};
            }
        }
        
        // Check for MVP++ (monthly rank)
        if (playerData.has("monthlyPackageRank")) {
            String monthlyRank = playerData.get("monthlyPackageRank").getAsString();
            if (monthlyRank.equals("SUPERSTAR")) {
                return new String[]{"MVP++", rankPlusColor, monthlyRankColor};
            }
        }
        
        // Check new package rank system
        if (playerData.has("newPackageRank")) {
            String packageRank = playerData.get("newPackageRank").getAsString();
            if (packageRank.equals("MVP_PLUS")) {
                return new String[]{"MVP+", rankPlusColor, ""};
            } else if (packageRank.equals("MVP")) {
                return new String[]{"MVP", "", ""};
            } else if (packageRank.equals("VIP_PLUS")) {
                return new String[]{"VIP+", "", ""};
            } else if (packageRank.equals("VIP")) {
                return new String[]{"VIP", "", ""};
            }
        }
        
        // Check old package rank system (fallback)
        if (playerData.has("packageRank")) {
            String packageRank = playerData.get("packageRank").getAsString();
            if (packageRank.equals("MVP_PLUS")) {
                return new String[]{"MVP+", rankPlusColor, ""};
            } else if (packageRank.equals("MVP")) {
                return new String[]{"MVP", "", ""};
            } else if (packageRank.equals("VIP_PLUS")) {
                return new String[]{"VIP+", "", ""};
            } else if (packageRank.equals("VIP")) {
                return new String[]{"VIP", "", ""};
            }
        }
        
        return new String[]{"Default", "", ""};
    }
    
    
    /**
     * Gets a player's current username from their UUID using the Mojang API.
     * 
     * @param uuid The player's UUID (without dashes)
     * @return The player's current username, or null if not found
     */
    private static String getCurrentUsername(String uuid) throws Exception {
        if (uuid == null || uuid.isEmpty()) return null;
        
        // Strip dashes from UUID if present (Mojang API requires undashed UUIDs)
        String undashedUuid = uuid.replace("-", "");
        
        URL url = new URL(me.napim.gomod.util.MojangProfileApi.profileJsonUrl(undashedUuid));
        awaitMojangRequestSlot();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(MOJANG_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MOJANG_READ_TIMEOUT_MS);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                // Don't throw exception for 204 (No Content), just return null as user might not exist anymore
                if (code == 204) return null;
                if (code == 429) {
                    long retryAfterMs = parseMojangRetryAfterMs(conn.getHeaderField("Retry-After"));
                    extendMojangBackoff(retryAfterMs);
                    throw new MojangRateLimitException(retryAfterMs);
                }
                throw new Exception("Failed to get player data: " + code + " " + conn.getResponseMessage());
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JsonObject jsonResponse = new JsonParser().parse(response.toString()).getAsJsonObject();
            if (jsonResponse.has("name")) {
                return jsonResponse.get("name").getAsString();
            }
        } finally {
            conn.disconnect();
        }

        return null;
    }

    private static void awaitMojangRequestSlot() throws InterruptedException {
        while (true) {
            long remaining;
            synchronized (MOJANG_REQUEST_LOCK) {
                long now = System.nanoTime();
                long availableAt = Math.max(nextMojangRequestAtNs, mojangBackoffUntilNs);
                remaining = availableAt - now;
                if (remaining <= 0L) {
                    nextMojangRequestAtNs = now + MOJANG_MIN_REQUEST_INTERVAL_NS;
                    return;
                }
            }
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
        }
    }

    private static void extendMojangBackoff(long retryAfterMs) {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(retryAfterMs);
        synchronized (MOJANG_REQUEST_LOCK) {
            if (until > mojangBackoffUntilNs) {
                mojangBackoffUntilNs = until;
            }
        }
    }

    private static long parseMojangRetryAfterMs(String retryAfter) {
        if (retryAfter == null || retryAfter.trim().isEmpty()) {
            return MOJANG_DEFAULT_RETRY_AFTER_MS;
        }
        String value = retryAfter.trim();
        long delayMs;
        try {
            delayMs = Long.parseLong(value) * 1000L;
        } catch (NumberFormatException notSeconds) {
            try {
                delayMs = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)).toEpochMilli()
                        - System.currentTimeMillis();
            } catch (RuntimeException notHttpDate) {
                return MOJANG_DEFAULT_RETRY_AFTER_MS;
            }
        }
        return Math.max(1_000L, Math.min(MOJANG_MAX_RETRY_AFTER_MS, delayMs));
    }

    private static final class MojangRateLimitException extends Exception {
        private static final long serialVersionUID = 1L;
        private final long retryAfterMs;

        private MojangRateLimitException(long retryAfterMs) {
            super("Failed to get player data: 429 Too Many Requests (retry after " + retryAfterMs + " ms)");
            this.retryAfterMs = retryAfterMs;
        }

        @SuppressWarnings("unused")
        private long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
    
    /**
     * Updates all player names in the team spam list to their current names,
     * based on their UUIDs. This ensures the system works when players change their names.
     * 
     * @return A result message indicating how many names were updated
     */
    public static String updateAllPlayerNames() {
        if (!loaded) {
            loadPlayers();
        }
        
        int checkedCount = 0;
        int updatedCount = 0;
        int errorCount = 0;
        
        int processed = 0;
        long windowStart = System.currentTimeMillis();
        for (Player player : players) {
            long iterationStartNs = System.nanoTime();
            try {
                String uuid = player.getUuid();
                String oldUsername = player.getUsername();
                String currentUsername = getCurrentUsername(uuid);
                
                // Debug logging to trace what's happening
                System.out.println("[TeamSpamManager] Checking " + oldUsername + " (" + uuid + ") -> " + currentUsername);
                
                if (currentUsername != null && !currentUsername.equals(oldUsername)) {
                    // Player has changed their name
                    System.out.println("[TeamSpamManager] Player name changed: " + oldUsername + " -> " + currentUsername);
                    player.username = currentUsername; // Update the name
                    updatedCount++;
                } else if (currentUsername != null) {
                    // Even if the name matches strictly, ensure capitalization is correct
                    if (!currentUsername.equals(player.username)) {
                        System.out.println("[TeamSpamManager] Fixing capitalization: " + player.username + " -> " + currentUsername);
                        player.username = currentUsername;
                        updatedCount++;
                    }
                }
                
                checkedCount++;
                processed++;
                // Batch limit 300 requests per 5 minutes
                if (processed % 300 == 0) {
                    long elapsed = System.currentTimeMillis() - windowStart;
                    long minWindow = 5 * 60 * 1000L; // 5 minutes
                    if (elapsed < minWindow) {
                        long sleepMs = minWindow - elapsed;
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                    }
                    windowStart = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("[TeamSpamManager] Error updating name for " + player.getUsername() + ": " + e.getMessage());
                errorCount++;
            }
            enforceRefreshPacing(iterationStartNs);
        }
        
        // Save the updated player data if any names changed
        if (updatedCount > 0) {
            savePlayers();
        }
        
        return "Checked " + checkedCount + " players: " + updatedCount + " had name changes and were updated." +
               (errorCount > 0 ? " " + errorCount + " errors occurred." : "");
    }
}
