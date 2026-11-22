package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ChatComponentText;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;

public class MapQueuer {
    private static boolean isEnabled = false;
    private static String[] targetMaps = {}; // Changed to support multiple maps
    private static String gameMode = "solo"; // "solo" or "teams"
    private static ScheduledExecutorService scheduler;
    private static boolean isRunning = false;
    private static volatile long pauseUntilMs = 0L;
    private static String[] lastTargetMaps = {}; // Store last used maps for resume
    private static String lastGameMode = "solo"; // Store last used mode for resume
    /** When true, a matching map alone is not enough — sidebar must show Blitz Hour (kits maxed window). */
    private static boolean requireBlitzHour = false;
    private static boolean lastRequireBlitzHour = false;
    
    // Flag to prevent double map-found notifications between tick handler and scheduler
    private static volatile boolean mapFoundNotified = false;
    
    // Cache for game ID to avoid multiple scoreboard reads
    private static String cachedGameId = null;
    private static long gameIdCacheTime = 0;
    private static final long GAME_ID_CACHE_DURATION = 1000; // 1 second cache
    
    // Maps that are only available in Teams mode
    private static final String[] TEAMS_ONLY_MAPS = {"Stoneguard", "Mirador Basin", "Darkstone"};
    
    // Maps that are only available in Solo mode
    private static final String[] SOLO_ONLY_MAPS = {
        "Alexandria", "Riverside", "Citadel", "Bastion", "Aelin's Tower", "Seafloor", "Valley", 
        "Thorin", "Shroom Valley", "Peaks", "Mithril Revived", "Hamani", "Greece", 
        "Enthorran", "Caelum", "Caelum v2"
    };
    
    /** Max players per Blitz map lobby (Hypixel). */
    private static final Map<String, Integer> MAP_MAX_PLAYERS = buildMapMaxPlayers();

    // All available maps — solo-only, then both modes, then teams-only; within each group by max players
    private static final String[] allMaps = buildSortedAllMaps();

    private static Map<String, Integer> buildMapMaxPlayers() {
        Map<String, Integer> players = new HashMap<>();
        players.put("Alexandria", 24);
        players.put("Aelin's Tower", 16);
        players.put("Alice", 24);
        players.put("Bastion", 24);
        players.put("Caelum", 12);
        players.put("Caelum v2", 16);
        players.put("Cattle Drive", 16);
        players.put("Citadel", 18);
        players.put("City", 24);
        players.put("Darkstone", 24);
        players.put("Despair v1", 24);
        players.put("Despair v2", 24);
        players.put("Docks v1", 24);
        players.put("Docks v2", 24);
        players.put("Egypt", 24);
        players.put("Enthorran", 16);
        players.put("Greece", 24);
        players.put("Gulch", 16);
        players.put("Hamani", 24);
        players.put("Impact", 16);
        players.put("KTulu Island", 24);
        players.put("Mirador Basin", 24);
        players.put("Mithril Revived", 24);
        players.put("Moonbase", 24);
        players.put("Pandora", 24);
        players.put("Peaks", 16);
        players.put("Persia", 24);
        players.put("Pixelville", 32);
        players.put("Proxima", 24);
        players.put("Riverside", 24);
        players.put("Ruins", 16);
        players.put("Seafloor", 24);
        players.put("Shogun", 16);
        players.put("Shroom Valley", 24);
        players.put("Stoneguard", 24);
        players.put("Thorin", 24);
        players.put("Valley", 16);
        players.put("Winter", 24);
        players.put("Woodlands", 12);
        return Collections.unmodifiableMap(players);
    }

    private static String[] buildSortedAllMaps() {
        List<String> maps = new ArrayList<>(MAP_MAX_PLAYERS.keySet());
        maps.sort((a, b) -> {
            int category = Integer.compare(getMapCategoryOrder(a), getMapCategoryOrder(b));
            if (category != 0) {
                return category;
            }
            int weight = Integer.compare(getMapSortWeight(a), getMapSortWeight(b));
            if (weight != 0) {
                return weight;
            }
            return a.compareToIgnoreCase(b);
        });
        return maps.toArray(new String[0]);
    }

    /** Sort weight: category group, then max players; Caelum v2 sits right after Caelum. */
    private static int getMapSortWeight(String map) {
        if ("Caelum v2".equals(map)) {
            return getMapMaxPlayers("Caelum") * 1000 + 500;
        }
        return getMapMaxPlayers(map) * 1000;
    }

    /** 0 = solo-only, 1 = both modes, 2 = teams-only */
    private static int getMapCategoryOrder(String map) {
        if (isSoloOnlyMap(map)) {
            return 0;
        }
        if (isTeamsOnlyMap(map)) {
            return 2;
        }
        return 1;
    }
    
    /**
     * Get the total number of maps available for a specific mode
     */
    public static int getTotalMapsForMode(String mode) {
        if (mode.equals("solo")) {
            // Solo mode: all maps except teams-only maps
            return allMaps.length - TEAMS_ONLY_MAPS.length;
        } else if (mode.equals("teams")) {
            // Teams mode: all maps except solo-only maps
            return allMaps.length - SOLO_ONLY_MAPS.length;
        }
        return allMaps.length; // Default to all maps
    }
    
    /**
     * Calculate the percentage chance of getting a selected map
     */
    public static double getMapSelectionPercentage(boolean[] selectedMaps, String mode) {
        int selectedCount = 0;
        for (boolean selected : selectedMaps) {
            if (selected) selectedCount++;
        }
        
        if (selectedCount == 0) return 0.0;
        
        int totalMaps = getTotalMapsForMode(mode);
        return (double) selectedCount / totalMaps * 100.0;
    }
    
    public static void startMapQueuer(String[] maps, String mode) {
        startMapQueuer(maps, mode, false);
    }

    /**
     * @param requireBlitzHourOnly if true, stop only when a selected map appears and the sidebar shows Blitz Hour
     *                             (Hypixel line {@code BLITZ HOUR}); otherwise requeue like a filtered lobby.
     */
    public static void startMapQueuer(String[] maps, String mode, boolean requireBlitzHourOnly) {
        if (isRunning) {
            Main.sendMessage(EnumChatFormatting.RED + "Map Queuer is already running!");
            return;
        }
        
        // Clear game ID cache when starting
        clearGameIdCache();
        mapFoundNotified = false;
        
        if (maps.length == 0 && !requireBlitzHourOnly) {
            Main.sendMessage(EnumChatFormatting.RED + "No maps selected!");
            return;
        }
        
        // Validate maps based on mode
        for (String map : maps) {
            if (mode.equals("solo")) {
                for (String teamsMap : TEAMS_ONLY_MAPS) {
                    if (map.equals(teamsMap)) {
                        Main.sendMessage(EnumChatFormatting.RED + map + " is only available in Teams mode!");
                        return;
                    }
                }
            } else if (mode.equals("teams")) {
                for (String soloMap : SOLO_ONLY_MAPS) {
                    if (map.equals(soloMap)) {
                        Main.sendMessage(EnumChatFormatting.RED + map + " is only available in Solo mode!");
                        return;
                    }
                }
            }
        }
        
        targetMaps = maps.clone();
        gameMode = mode;
        lastTargetMaps = maps.clone(); // Store for resume
        lastGameMode = mode; // Store for resume
        requireBlitzHour = requireBlitzHourOnly;
        lastRequireBlitzHour = requireBlitzHourOnly;
        isEnabled = true;
        isRunning = true;
        
        String mapsList = String.join(", ", maps);
        
        // Calculate percentage chance
        int totalMaps = getTotalMapsForMode(mode);
        if (maps.length == 0 && requireBlitzHourOnly) {
            Main.sendMessage(EnumChatFormatting.GREEN + "Map Queuer started! Looking for any Blitz Hour lobby in " + mode + " mode...");
        } else {
            double percentage = (double) maps.length / totalMaps * 100.0;
            Main.sendMessage(EnumChatFormatting.GREEN + "Map Queuer started! Looking for: " + mapsList + " in " + mode + " mode..."
                    + (requireBlitzHourOnly ? EnumChatFormatting.LIGHT_PURPLE + " (Blitz Hour lobbies only)" : ""));
            Main.sendMessage(EnumChatFormatting.AQUA + "Chance to get selected map: " + String.format("%.1f", percentage) + "% (" + maps.length + "/" + totalMaps + " maps)");
        }
        
        // Start the scheduler immediately
        scheduler = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "GoMod-MapQueuer"); t.setDaemon(true); return t; });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndQueue();
            } catch (Exception e) {
                System.err.println("Error in Map Queuer: " + e.getMessage());
            }
        }, 0, 5100, TimeUnit.MILLISECONDS);
    }
    
    // Legacy method for single map (for backward compatibility)
    public static void startMapQueuer(String map, String mode) {
        startMapQueuer(new String[]{map}, mode, false);
    }

    public static boolean isRequireBlitzHour() {
        return requireBlitzHour;
    }

    /**
     * While the queuer is running, turn Blitz Hour requirement on or off (e.g. from the Map Queuer GUI).
     */
    public static void setRequireBlitzHour(boolean v) {
        if (!isRunning) {
            return;
        }
        requireBlitzHour = v;
        lastRequireBlitzHour = v;
        if (v) {
            Main.sendMessage(EnumChatFormatting.AQUA + "Map Queuer will only stop when a selected map is in a Blitz Hour lobby (sidebar shows BLITZ HOUR).");
        } else {
            Main.sendMessage(EnumChatFormatting.YELLOW + "Blitz Hour requirement turned off for this session.");
        }
    }

    /**
     * True if the Blitz sidebar currently contains a Blitz Hour line, or {@link ForceBlitzHourDebug} is active.
     */
    public static boolean isBlitzHourOnSidebar() {
        if (ForceBlitzHourDebug.isForced()) {
            return true;
        }
        return readLiveBlitzHourFromSidebar();
    }

    /**
     * Live read only (ignores {@link ForceBlitzHourDebug}).
     */
    public static boolean readLiveBlitzHourFromSidebar() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return false;
            }
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return false;
            }
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (line == null) {
                    continue;
                }
                String clean = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                if (clean != null && clean.toLowerCase(Locale.ROOT).contains("blitz hour")) {
                    return true;
                }
            }
            String objectiveName = objective.getDisplayName();
            if (objectiveName != null) {
                String c = EnumChatFormatting.getTextWithoutFormattingCodes(objectiveName);
                if (c != null && c.toLowerCase(Locale.ROOT).contains("blitz hour")) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[MapQueuer] isBlitzHourOnSidebar: " + e.getMessage());
        }
        return false;
    }

    private static boolean isBlitzHourOnlyAnyMapQueue() {
        return requireBlitzHour && targetMaps.length == 0;
    }

    /**
     * Handles the special GUI case where BLITZ HOUR is selected by itself.
     * With no target maps, any unfiltered Blitz Hour lobby is a match.
     */
    private static boolean handleBlitzHourOnlyAnyMapMatch(Minecraft mc, boolean requeueFilteredGame) {
        if (!isBlitzHourOnlyAnyMapQueue() || !isBlitzHourOnSidebar()) {
            return false;
        }

        String currentMap = getCurrentMap();
        if (currentMap == null) {
            return false;
        }

        if (me.napim.gomod.gui.MapQueuerGUI.hasActiveFilters()) {
            String currentGameId = getCurrentGameId();
            if (currentGameId != null) {
                boolean isFiltered = me.napim.gomod.gui.MapQueuerGUI.isGameFiltered(currentGameId);
                if (isFiltered) {
                    if (requeueFilteredGame) {
                        Main.sendMessage(EnumChatFormatting.YELLOW + "Found Blitz Hour lobby but game " + currentGameId + " is filtered! Requeuing...");
                        clearGameIdCache();
                        mc.thePlayer.sendChatMessage("/play blitz_" + gameMode + "_normal");
                    }
                    return true;
                }
            } else {
                return true;
            }
        }

        if (mapFoundNotified) {
            return true;
        }
        mapFoundNotified = true;

        try {
            mc.thePlayer.playSound("random.levelup", 1.0f, 0.7f);
        } catch (Exception ignored) {}

        Main.sendMessage(EnumChatFormatting.GREEN + "Found Blitz Hour map: " + currentMap + "! Stopping queuer.");
        stopMapQueuer();
        return true;
    }
    
    /**
     * Update the running Map Queuer with a new selection and/or mode.
     * Applies immediately without stopping the scheduler.
     */
    public static void updateSelection(String[] maps, String mode) {
        if (!isRunning) {
            return;
        }
        if (maps == null || (maps.length == 0 && !requireBlitzHour)) {
            Main.sendMessage(EnumChatFormatting.RED + "No maps selected! Keeping previous selection.");
            return;
        }
        // Validate against mode constraints
        for (String map : maps) {
            if ("solo".equals(mode)) {
                for (String teamsMap : TEAMS_ONLY_MAPS) {
                    if (map.equals(teamsMap)) {
                        Main.sendMessage(EnumChatFormatting.RED + map + " is only available in Teams mode!");
                        return;
                    }
                }
            } else if ("teams".equals(mode)) {
                for (String soloMap : SOLO_ONLY_MAPS) {
                    if (map.equals(soloMap)) {
                        Main.sendMessage(EnumChatFormatting.RED + map + " is only available in Solo mode!");
                        return;
                    }
                }
            }
        }
        targetMaps = maps.clone();
        gameMode = mode;
        lastTargetMaps = maps.clone();
        lastGameMode = mode;
        clearGameIdCache();
        String mapsList = String.join(", ", maps);
        sendUpdatedSelectionMessage(mapsList, mode);
        // Do not send a command or change timing; let the next scheduled tick handle it
    }

    /**
     * Sends a multi-line safe yellow status message for updated selection, preserving color on wrap.
     */
    private static void sendUpdatedSelectionMessage(String mapsList, String mode) {
        String targetText = (mapsList == null || mapsList.isEmpty()) && requireBlitzHour
                ? "any Blitz Hour lobby"
                : mapsList;
        try {
            ChatComponentText prefixComponent = new ChatComponentText(Main.CHAT_PREFIX);
            ChatComponentText statusComponent = new ChatComponentText("Updated map queuer selection: " + targetText + " (" + mode + ")");
            statusComponent.getChatStyle().setColor(EnumChatFormatting.YELLOW);
            prefixComponent.appendSibling(statusComponent);
            Minecraft.getMinecraft().thePlayer.addChatMessage(prefixComponent);
        } catch (Exception e) {
            Main.sendMessage(EnumChatFormatting.YELLOW + "Updated map queuer selection: " + targetText + " (" + mode + ")");
        }
    }

    public static void stopMapQueuer() {
        if (!isRunning) {
            Main.sendMessage(EnumChatFormatting.RED + "Map Queuer is not running!");
            return;
        }
        
        isEnabled = false;
        isRunning = false;
        requireBlitzHour = false;

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        
        sendClickableStopMessage();
        
        // Play stop sound effect
        try {
            Minecraft.getMinecraft().thePlayer.playSound("random.click", 1.0f, 0.8f);
            System.out.println("[MapQueuer] Stop sound played");
        } catch (Exception e) {
            System.err.println("[MapQueuer] Error playing stop sound: " + e.getMessage());
        }
    }
    
    public static void resumeMapQueuer() {
        if (isRunning) {
            Main.sendMessage(EnumChatFormatting.RED + "Map Queuer is already running!");
            return;
        }
        
        lastRequireBlitzHour = loadBlitzHourOnlyPreference();
        if (lastTargetMaps.length == 0 && !lastRequireBlitzHour) {
            Main.sendMessage(EnumChatFormatting.RED + "No previous settings found to resume!");
            return;
        }
        
        // Resume with the last used settings
        startMapQueuer(lastTargetMaps, lastGameMode, lastRequireBlitzHour);
    }
    
    private static void sendClickableStatusMessage(String mapsList, String mode) {
        try {
            // Create the main status text
            String statusText;
            if ((mapsList == null || mapsList.isEmpty()) && requireBlitzHour) {
                statusText = "Queuing for " + mode + " mode... (Looking for: any Blitz Hour lobby)";
            } else {
                statusText = "Queuing for " + mode + " mode... (Looking for: " + mapsList + ")";
            }
            
            // Build message as [gomod] prefix + green status text, only the hint is clickable
            ChatComponentText prefixComponent = new ChatComponentText(Main.CHAT_PREFIX);
            ChatComponentText statusComponent = new ChatComponentText(statusText);
            statusComponent.getChatStyle().setColor(EnumChatFormatting.GREEN); // Ensure wrapped lines stay green
            
            // Give the hint an explicit red component style instead of embedding § codes. On Lunar, chat
            // wrapping can split after the embedded reset and inherit the green status style on the next line.
            ChatComponentText hintComponent = new ChatComponentText(" [");
            hintComponent.getChatStyle().setColor(EnumChatFormatting.DARK_RED);
            hintComponent.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mapqueuer stop"));
            hintComponent.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(EnumChatFormatting.RED + "Stop queuing")));
            ChatComponentText hintLabel = new ChatComponentText("Click to stop");
            hintLabel.getChatStyle().setBold(true);
            hintComponent.appendSibling(hintLabel);
            hintComponent.appendSibling(new ChatComponentText("]"));
            statusComponent.appendSibling(hintComponent);
            
            // Send the clickable message
            prefixComponent.appendSibling(statusComponent);
            Minecraft.getMinecraft().thePlayer.addChatMessage(prefixComponent);
        } catch (Exception e) {
            System.err.println("Error sending clickable status message: " + e.getMessage());
            // Fallback to regular message
            Main.sendMessage(EnumChatFormatting.YELLOW + "Use /mapqueuer stop to stop the queuer.");
        }
    }
    
    private static void sendClickableStopMessage() {
        try {
            // Create the main stop text
            String stopText = "Map Queuer stopped.";
            
            // Build message as [gomod] prefix + yellow stop text
            ChatComponentText prefixComponent = new ChatComponentText(Main.CHAT_PREFIX);
            ChatComponentText stopComponent = new ChatComponentText(stopText);
            stopComponent.getChatStyle().setColor(EnumChatFormatting.YELLOW);
            
            // Add clickable resume button with light green formatting
            ChatComponentText resumeComponent = new ChatComponentText(
                " " + EnumChatFormatting.DARK_GREEN + "[" +
                EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "Click to resume" +
                EnumChatFormatting.RESET + EnumChatFormatting.DARK_GREEN + "]"
            );
            resumeComponent.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mapqueuer resume"));
            resumeComponent.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(EnumChatFormatting.GREEN + "Resume queuing")));
            stopComponent.appendSibling(resumeComponent);
            
            // Send the clickable message
            prefixComponent.appendSibling(stopComponent);
            Minecraft.getMinecraft().thePlayer.addChatMessage(prefixComponent);
        } catch (Exception e) {
            System.err.println("Error sending clickable stop message: " + e.getMessage());
            // Fallback to regular message
            Main.sendMessage(EnumChatFormatting.YELLOW + "Map Queuer stopped. Use /mapqueuer resume to resume.");
        }
    }
    
    private static void checkAndQueue() {
        if (!isEnabled || !isRunning) {
            return;
        }
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (System.currentTimeMillis() < pauseUntilMs) {
            return;
        }
        
        // Check if current game is filtered BEFORE handling success/started
        String currentGameIdEarly = getCurrentGameId();
        if (currentGameIdEarly != null) {
            System.out.println("[MapQueuer] Early check - Current game ID: " + currentGameIdEarly);
            boolean isFilteredEarly = me.napim.gomod.gui.MapQueuerGUI.isGameFiltered(currentGameIdEarly);
            System.out.println("[MapQueuer] Early check - Is filtered: " + isFilteredEarly);
            if (isFilteredEarly) {
                Main.sendMessage(EnumChatFormatting.YELLOW + "Game " + currentGameIdEarly + " is filtered! Requeuing...");
                clearGameIdCache(); // Clear cache before warping to new game
                String command = "/play blitz_" + gameMode + "_normal";
                Minecraft.getMinecraft().thePlayer.sendChatMessage(command);
                return;
            }
        } else {
            System.out.println("[MapQueuer] Early check - No game ID detected");
        }

        if (handleBlitzHourOnlyAnyMapMatch(mc, true)) {
            return;
        }

        // Check if we're already in one of the target maps
        String currentMap = getCurrentMap();
        if (currentMap != null) {
            for (String targetMap : targetMaps) {
                if (currentMap.equals(targetMap)) {
                    // CRITICAL: Check if game is filtered BEFORE declaring success
                    // Only need to wait for game ID if there are active filters
                    if (me.napim.gomod.gui.MapQueuerGUI.hasActiveFilters()) {
                        String currentGameId = getCurrentGameId();
                        System.out.println("[MapQueuer] Success check - Current game ID: " + currentGameId);
                        if (currentGameId != null) {
                            boolean isFiltered = me.napim.gomod.gui.MapQueuerGUI.isGameFiltered(currentGameId);
                            System.out.println("[MapQueuer] Success check - Is filtered: " + isFiltered);
                            if (isFiltered) {
                                Main.sendMessage(EnumChatFormatting.YELLOW + "Found target map " + targetMap + " but game " + currentGameId + " is filtered! Requeuing...");
                                String command = "/play blitz_" + gameMode + "_normal";
                                Minecraft.getMinecraft().thePlayer.sendChatMessage(command);
                                return;
                            }
                        } else {
                            System.out.println("[MapQueuer] Success check - No game ID detected, waiting for game ID to check filter...");
                            // Don't declare success yet - wait until we can verify filter status
                            return;
                        }
                    }

                    if (requireBlitzHour && !isBlitzHourOnSidebar()) {
                        Main.sendMessage(EnumChatFormatting.YELLOW + "Found map " + targetMap + " but not Blitz Hour! Requeuing...");
                        clearGameIdCache();
                        Minecraft.getMinecraft().thePlayer.sendChatMessage("/play blitz_" + gameMode + "_normal");
                        return;
                    }
                    
                    // Only declare success if game is NOT filtered
                    // Check if tick handler already notified (prevent double message)
                    if (mapFoundNotified) {
                        return;
                    }
                    mapFoundNotified = true;
                    
                    // Play success sound effect
                    try {
                        Minecraft.getMinecraft().thePlayer.playSound("random.levelup", 1.0f, 0.7f);
                    } catch (Exception ignored) {}
                    
                    Main.sendMessage(EnumChatFormatting.GREEN + "Found target map: " + targetMap + "! Stopping queuer.");
                    stopMapQueuer();
                    return;
                }
            }
        }
        
        // Check if the game has already started (look for "Taunt:" in scoreboard)
        if (isGameStarted()) {
            Main.sendMessage(EnumChatFormatting.RED + "Game has already started! Stopping MapQueuer.");
            
            // Play sound effect for game started detection
            try {
                Minecraft.getMinecraft().thePlayer.playSound("random.levelup", 1.0f, 0.4f);
                System.out.println("[MapQueuer] Game started sound played");
            } catch (Exception e) {
                System.err.println("[MapQueuer] Error playing game started sound: " + e.getMessage());
            }
            
            stopMapQueuer();
            return;
        }
        
        // Check if current game is filtered
        String currentGameId = getCurrentGameId();
        if (currentGameId != null) {
            System.out.println("[MapQueuer] Checking if game " + currentGameId + " is filtered...");
            if (me.napim.gomod.gui.MapQueuerGUI.isGameFiltered(currentGameId)) {
                Main.sendMessage(EnumChatFormatting.YELLOW + "Game " + currentGameId + " is filtered! Requeuing...");
                clearGameIdCache(); // Clear cache before warping to new game
                String command = "/play blitz_" + gameMode + "_normal";
                mc.thePlayer.sendChatMessage(command);
                return;
            }
        }
        
        // Check for countdown timer and go to limbo if less than 10 seconds
        int countdownSeconds = getCountdownSeconds();
        System.out.println("[MapQueuer] Countdown seconds detected: " + countdownSeconds);
        
        if (countdownSeconds == 0) {
            // Game is starting right now (00:00) - too late to leave
            Main.sendMessage(EnumChatFormatting.RED + "Game is starting now! Too late to leave.");
            try {
                mc.thePlayer.playSound("random.levelup", 1.0f, 0.4f);
            } catch (Exception ignored) {}
            return;
        } else if (countdownSeconds > 0 && countdownSeconds < 10) {
            System.out.println("[MapQueuer] Countdown < 10 seconds, going to limbo!");
            Main.sendMessage(EnumChatFormatting.YELLOW + "Game starting in " + countdownSeconds + " seconds! Going to limbo...");
            mc.thePlayer.sendChatMessage("/limbo");
            
            // Wait for the countdown + 3 seconds before continuing
            int waitTime = countdownSeconds + 3;
            Main.sendMessage(EnumChatFormatting.AQUA + "Waiting " + waitTime + " seconds before continuing to queue...");
            pauseUntilMs = System.currentTimeMillis() + (waitTime * 1000L);
            
            // Schedule a delayed queue command
            scheduler.schedule(() -> {
                if (isEnabled && isRunning) {
                    String command = "/play blitz_" + gameMode + "_normal";
                    mc.thePlayer.sendChatMessage(command);
                    String mapsList = String.join(", ", targetMaps);
                    sendClickableStatusMessage(mapsList, gameMode);
                    pauseUntilMs = 0L;
                }
            }, waitTime, TimeUnit.SECONDS);
            
            return;
        }
        
        // Send the queue command
        clearGameIdCache(); // Clear cache before warping to new game
        String command = "/play blitz_" + gameMode + "_normal";
        mc.thePlayer.sendChatMessage(command);
        
        // Check countdown after warping to the new game
        checkCountdownAfterWarp();
        
        // Send clickable status message
        String mapsList = String.join(", ", targetMaps);
        sendClickableStatusMessage(mapsList, gameMode);
    }
    
    // New method to check countdown immediately after warping to a game
    public static void checkCountdownAfterWarp() {
        if (!isEnabled || !isRunning) {
            return;
        }
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Wait a moment for the scoreboard to update after warping
        scheduler.schedule(() -> {
            try {
                // Check if the game has already started
                if (isGameStarted()) {
                    Main.sendMessage(EnumChatFormatting.RED + "Game has already started! Stopping MapQueuer.");
                    
                    // Play sound effect for game started detection
                    try {
                        Minecraft.getMinecraft().thePlayer.playSound("random.levelup", 1.0f, 0.4f);
                        System.out.println("[MapQueuer] Game started sound played");
                    } catch (Exception e) {
                        System.err.println("[MapQueuer] Error playing game started sound: " + e.getMessage());
                    }
                    
                    stopMapQueuer();
                    return;
                }
                
                // Check countdown timer
                int countdownSeconds = getCountdownSeconds();
                System.out.println("[MapQueuer] Post-warp countdown check: " + countdownSeconds + " seconds");
                
                if (countdownSeconds > 0 && countdownSeconds < 10) {
                    System.out.println("[MapQueuer] Post-warp: Countdown < 10 seconds, going to limbo!");
                    Main.sendMessage(EnumChatFormatting.YELLOW + "Game starting in " + countdownSeconds + " seconds! Going to limbo...");
                    mc.thePlayer.sendChatMessage("/limbo");
                    
                    // Wait for the countdown + 3 seconds before continuing
                    int waitTime = countdownSeconds + 3;
                    Main.sendMessage(EnumChatFormatting.AQUA + "Waiting " + waitTime + " seconds before continuing to queue...");
                    pauseUntilMs = System.currentTimeMillis() + (waitTime * 1000L);
                    
                    // Schedule a delayed queue command
                    scheduler.schedule(() -> {
                        if (isEnabled && isRunning) {
                            String command = "/play blitz_" + gameMode + "_normal";
                            mc.thePlayer.sendChatMessage(command);
                            String mapsList = String.join(", ", targetMaps);
                            sendClickableStatusMessage(mapsList, gameMode);
                            pauseUntilMs = 0L;
                        }
                    }, waitTime, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                System.err.println("[MapQueuer] Error in post-warp countdown check: " + e.getMessage());
            }
        }, 2, TimeUnit.SECONDS); // Wait 2 seconds for scoreboard to update
    }
    
    /**
     * Instant map check called every few ticks by MapQueuerTickHandler.
     * ONLY checks if we're on a target map and notifies the player.
     * NEVER sends /play commands or triggers requeue.
     */
    public static void instantMapCheck() {
        if (!isEnabled || !isRunning || mapFoundNotified) {
            return;
        }
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Don't check if we're paused (waiting for countdown etc.)
        if (System.currentTimeMillis() < pauseUntilMs) {
            return;
        }

        if (handleBlitzHourOnlyAnyMapMatch(mc, false)) {
            return;
        }
        
        String currentMap = getCurrentMap();
        if (currentMap == null) {
            return;
        }
        
        for (String targetMap : targetMaps) {
            if (currentMap.equals(targetMap)) {
                // Only need to check game ID filter if there are active filters
                if (me.napim.gomod.gui.MapQueuerGUI.hasActiveFilters()) {
                    String currentGameId = getCurrentGameId();
                    if (currentGameId != null) {
                        boolean isFiltered = me.napim.gomod.gui.MapQueuerGUI.isGameFiltered(currentGameId);
                        if (isFiltered) {
                            // Game is filtered - don't notify, let the scheduler handle requeue
                            return;
                        }
                    } else {
                        // Game ID not available yet but filters exist - wait for it
                        return;
                    }
                }

                if (requireBlitzHour && !isBlitzHourOnSidebar()) {
                    return;
                }
                
                // Prevent double notification
                mapFoundNotified = true;
                
                // Play success sound effect
                try {
                    mc.thePlayer.playSound("random.levelup", 1.0f, 0.7f);
                } catch (Exception ignored) {}
                
                Main.sendMessage(EnumChatFormatting.GREEN + "Found target map: " + targetMap + "! Stopping queuer.");
                stopMapQueuer();
                return;
            }
        }
    }
    
    public static String getCurrentMap() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return null;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            if (objective == null) {
                return null;
            }
            
            // Look for the "Map:" line in the scoreboard
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                
                if (line.contains("Map:")) {
                    // Extract map name from "Map: MapName" format
                    String mapName = line.substring(line.indexOf("Map:") + 5).trim();
                    // Remove any color codes
                    mapName = EnumChatFormatting.getTextWithoutFormattingCodes(mapName);
                    // Remove any non-printable characters and weird symbols
                    mapName = mapName.replaceAll("[^\\p{Print}]", "").trim();
                    
                    // Handle special cases where scoreboard names differ from button names
                    if (mapName.equals("Island of Despair V1")) {
                        return "Despair v1";
                    } else if (mapName.equals("Island of Despair V2")) {
                        return "Despair v2";
                    }
                    
                    return mapName;
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting current map: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    private static boolean isGameStarted() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return false;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            if (objective == null) {
                return false;
            }
            
            // Look for "Taunt:" in the scoreboard - this indicates the game has started
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                
                if (line.contains("Taunt:")) {
                    System.out.println("[MapQueuer] Game started detected - found 'Taunt:' in scoreboard: '" + line + "'");
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("[MapQueuer] Exception in isGameStarted: " + e.getMessage());
        }
        return false;
    }
    
    public static String getCurrentGameId() {
        // Check cache first
        long currentTime = System.currentTimeMillis();
        if (cachedGameId != null && (currentTime - gameIdCacheTime) < GAME_ID_CACHE_DURATION) {
            System.out.println("[MapQueuer] Using cached game ID: " + cachedGameId);
            return cachedGameId;
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                cachedGameId = null;
                gameIdCacheTime = currentTime;
                return null;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            if (objective == null) {
                cachedGameId = null;
                gameIdCacheTime = currentTime;
                return null;
            }
            
            // Look for game ID pattern (e.g., "m184AQ", "m123AB", etc.)
            // Try multiple patterns to catch different formats - ORDER MATTERS!
            // Try more specific patterns first, then fallback to simpler ones
            // IMPORTANT: Handle emojis and special characters that might be in game IDs
            java.util.regex.Pattern[] gameIdPatterns = {
                // Pattern for game IDs with emojis/special chars: m9🔮2CV, m123🔮ABC, etc.
                java.util.regex.Pattern.compile("m\\d+[^\\s]+"), // m + digits + any non-space characters (handles emojis)
                java.util.regex.Pattern.compile("m\\d+[A-Za-z]{2,4}"), // Longer format: m184AQ (most specific)
                java.util.regex.Pattern.compile("m\\d+[A-Za-z]{1,3}"), // Shorter format: m2AE
                java.util.regex.Pattern.compile("m\\d+[A-Za-z]+"),  // Standard format: m123AB (at least 1 letter)
                java.util.regex.Pattern.compile("m\\d+[A-Za-z]*"), // Any letters after m + digits (including zero)
                java.util.regex.Pattern.compile("m\\d{2,}") // Just m + 2+ digits (fallback, but require at least 2 digits)
            };
            
            System.out.println("[MapQueuer] ===== FULL SCOREBOARD DEBUG =====");
            System.out.println("[MapQueuer] Total scoreboard entries: " + scoreboard.getSortedScores(objective).size());
            
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                
                // Debug: print all scoreboard lines with more detail
                System.out.println("[MapQueuer] Scoreboard line: '" + line + "' (raw: '" + score.getPlayerName() + "')");
                
                // Remove color codes using Minecraft's built-in method for consistency
                String cleanLine = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                // Also remove any leftover non-printable/special characters
                if (cleanLine != null) {
                    cleanLine = cleanLine.replaceAll("[^\\p{Print}]", "").trim();
                } else {
                    continue;
                }
                
                // Debug: print cleaned line
                System.out.println("[MapQueuer] Cleaned line: '" + cleanLine + "'");
                
                // Try each pattern - look for the LONGEST match
                String bestMatch = null;
                int bestMatchLength = 0;
                
                for (java.util.regex.Pattern pattern : gameIdPatterns) {
                    java.util.regex.Matcher matcher = pattern.matcher(cleanLine);
                    while (matcher.find()) {
                        String gameId = matcher.group();
                        System.out.println("[MapQueuer] Pattern match: " + gameId + " from line: " + cleanLine);
                        
                        // Keep the longest match to avoid partial matches like "m8" from "m82cn"
                        if (gameId.length() > bestMatchLength) {
                            bestMatch = gameId;
                            bestMatchLength = gameId.length();
                            System.out.println("[MapQueuer] New best match: " + gameId + " (length: " + gameId.length() + ")");
                        }
                    }
                }
                
                if (bestMatch != null) {
                    System.out.println("[MapQueuer] Final game ID: " + bestMatch + " from line: " + cleanLine);
                    
                    // Normalize the game ID by removing emojis and special characters
                    String normalizedGameId = normalizeGameId(bestMatch);
                    System.out.println("[MapQueuer] Normalized game ID: " + normalizedGameId);
                    
                    // Cache the normalized result
                    cachedGameId = normalizedGameId;
                    gameIdCacheTime = currentTime;
                    return normalizedGameId;
                }
            }
            
            System.out.println("[MapQueuer] ===== NO GAME ID FOUND IN SCOREBOARD =====");
            System.out.println("[MapQueuer] This might mean:");
            System.out.println("[MapQueuer] 1. Game ID is not visible in scoreboard yet");
            System.out.println("[MapQueuer] 2. Game ID is in a different scoreboard slot");
            System.out.println("[MapQueuer] 3. We're not in a game/lobby where game ID is shown");
            System.out.println("[MapQueuer] 4. Game ID format is different than expected");
            System.out.println("[MapQueuer] 5. Game ID contains characters we're not detecting");
            
            // Check if we have a cached game ID that might be stale
            if (cachedGameId != null) {
                System.out.println("[MapQueuer] WARNING: Using cached game ID: " + cachedGameId + " (might be stale!)");
                System.out.println("[MapQueuer] Cache age: " + (currentTime - gameIdCacheTime) + "ms");
            }
        } catch (Exception e) {
            System.out.println("[MapQueuer] Exception in getCurrentGameId: " + e.getMessage());
        }
        
        // Cache null result
        cachedGameId = null;
        gameIdCacheTime = currentTime;
        return null;
    }
    
    // Clear game ID cache (call when changing maps or when cache might be stale)
    public static void clearGameIdCache() {
        cachedGameId = null;
        gameIdCacheTime = 0;
    }
    
    /**
     * Normalizes a game ID by removing emojis and special characters
     * Example: "m3👽6BK" -> "m36BK"
     */
    private static String normalizeGameId(String gameId) {
        if (gameId == null || gameId.isEmpty()) {
            return gameId;
        }
        
        // Remove emojis and special Unicode characters, keep only alphanumeric characters
        // This handles cases like "m3👽6BK" -> "m36BK"
        String normalized = gameId.replaceAll("[^\\p{ASCII}]", "");
        
        // Also remove any remaining non-alphanumeric characters except 'm' at the start
        normalized = normalized.replaceAll("[^m\\dA-Za-z]", "");
        
        System.out.println("[MapQueuer] Normalized '" + gameId + "' -> '" + normalized + "'");
        return normalized;
    }
    
    private static int getCountdownSeconds() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                System.out.println("[MapQueuer] No scoreboard available");
                return -1;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1); // Sidebar
            
            if (objective == null) {
                System.out.println("[MapQueuer] No sidebar objective found");
                return -1;
            }
            
            System.out.println("[MapQueuer] Checking scoreboard for countdown...");
            System.out.println("[MapQueuer] Scoreboard objective: " + objective.getDisplayName());
            
            // Look for the "Starting in XX:XX" line in the scoreboard
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                
                System.out.println("[MapQueuer] Scoreboard line: '" + line + "'");
                
                // Also check for other countdown patterns
                if (line.contains("starts in") || line.contains("starts") || line.contains("seconds") || line.contains(":")) {
                    System.out.println("[MapQueuer] Potential countdown line found: '" + line + "'");
                }
                
                // Check for various countdown patterns
                String timeStr = null;
                if (line.contains("Starting in")) {
                    System.out.println("[MapQueuer] Found 'Starting in' line: '" + line + "'");
                    timeStr = line.substring(line.indexOf("Starting in") + 12).trim();
                } else if (line.contains("starts in")) {
                    System.out.println("[MapQueuer] Found 'starts in' line: '" + line + "'");
                    timeStr = line.substring(line.indexOf("starts in") + 9).trim();
                } else if (line.contains("The game starts in")) {
                    System.out.println("[MapQueuer] Found 'The game starts in' line: '" + line + "'");
                    timeStr = line.substring(line.indexOf("The game starts in") + 18).trim();
                } else if (line.contains("Game starts in")) {
                    System.out.println("[MapQueuer] Found 'Game starts in' line: '" + line + "'");
                    timeStr = line.substring(line.indexOf("Game starts in") + 14).trim();
                } else if (line.contains(" to") && line.contains(":")) {
                    // Handle "XX:XX to" format (like "00:13 to")
                    System.out.println("[MapQueuer] Found 'XX:XX to' format line: '" + line + "'");
                    timeStr = line.substring(0, line.indexOf(" to")).trim();
                }
                
                if (timeStr != null) {
                    System.out.println("[MapQueuer] Raw time string: '" + timeStr + "'");
                    
                    timeStr = EnumChatFormatting.getTextWithoutFormattingCodes(timeStr);
                    System.out.println("[MapQueuer] Cleaned time string: '" + timeStr + "'");
                    
                    timeStr = timeStr.replaceAll("[^\\p{Print}]", "").trim();
                    System.out.println("[MapQueuer] Final time string: '" + timeStr + "'");
                    
                    // Extract only the time part (MM:SS or SS) - stop at first non-digit/non-colon character
                    String timeOnly = "";
                    for (int i = 0; i < timeStr.length(); i++) {
                        char c = timeStr.charAt(i);
                        if (Character.isDigit(c) || c == ':') {
                            timeOnly += c;
                        } else {
                            break; // Stop at first non-digit/non-colon character
                        }
                    }
                    System.out.println("[MapQueuer] Extracted time only: '" + timeOnly + "'");
                    
                    // Parse MM:SS or SS format
                    if (timeOnly.contains(":")) {
                        String[] parts = timeOnly.split(":");
                        if (parts.length == 2) {
                            try {
                                int minutes = Integer.parseInt(parts[0]);
                                int seconds = Integer.parseInt(parts[1]);
                                int totalSeconds = minutes * 60 + seconds;
                                System.out.println("[MapQueuer] Parsed countdown: " + minutes + ":" + seconds + " = " + totalSeconds + " seconds");
                                return totalSeconds;
                            } catch (NumberFormatException e) {
                                System.out.println("[MapQueuer] NumberFormatException parsing time: " + e.getMessage());
                            }
                        }
                    } else if (!timeOnly.isEmpty()) {
                        try {
                            int seconds = Integer.parseInt(timeOnly);
                            System.out.println("[MapQueuer] Parsed countdown: " + seconds + " seconds");
                            return seconds;
                        } catch (NumberFormatException e) {
                            System.out.println("[MapQueuer] NumberFormatException parsing time: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[MapQueuer] Exception in getCountdownSeconds: " + e.getMessage());
            e.printStackTrace();
        }
        return -1;
    }
    
    public static boolean isEnabled() {
        return isEnabled;
    }
    
    public static boolean isRunning() {
        return isRunning;
    }
    
    public static String[] getTargetMaps() {
        return targetMaps.clone();
    }
    
    public static String getTargetMap() {
        return targetMaps.length > 0 ? targetMaps[0] : "";
    }
    
    public static String getGameMode() {
        return gameMode;
    }
    
    public static String[] getTeamsOnlyMaps() {
        return TEAMS_ONLY_MAPS.clone();
    }
    
    public static String[] getSoloOnlyMaps() {
        return SOLO_ONLY_MAPS.clone();
    }

    public static String[] getAllMaps() {
        return allMaps.clone();
    }

    public static int getMapMaxPlayers(String map) {
        Integer max = MAP_MAX_PLAYERS.get(map);
        return max != null ? max : 24;
    }

    /** Minimum players before a Blitz lobby starts (ceil(max * 3/8): 12→5, 16→6, 18→7, 24→9, 32→12). */
    public static int getMapStartPlayers(String map) {
        int max = getMapMaxPlayers(map);
        return (max * 3 + 7) / 8;
    }
    
    public static boolean isTeamsOnlyMap(String map) {
        for (String teamsMap : TEAMS_ONLY_MAPS) {
            if (map.equals(teamsMap)) {
                return true;
            }
        }
        return false;
    }
    
    public static boolean isSoloOnlyMap(String map) {
        for (String soloMap : SOLO_ONLY_MAPS) {
            if (map.equals(soloMap)) {
                return true;
            }
        }
        return false;
    }
    
    // Configuration persistence — <gameDir>/gomod123/mapqueuer.json (Forge)
    private static File mapQueuerConfigFile() {
        return new File(GomodPaths.getGomod123Dir(), "mapqueuer.json");
    }

    /** Factory default map selection when no {@code mapqueuer.json} exists yet. */
    private static void applyFactoryDefaultMapSelection(boolean[] selectedMaps) {
        for (int i = 0; i < allMaps.length; i++) {
            if ("Docks v2".equals(allMaps[i])) {
                selectedMaps[i] = true;
                break;
            }
        }
    }

    public static void saveSelectedMaps(boolean[] selectedMaps, String mode, boolean blitzHourOnly) {
        try {
            File configDir = GomodPaths.getGomod123Dir();
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            File configFile = mapQueuerConfigFile();
            JsonObject config = new JsonObject();
            
            JsonArray selectedArray = new JsonArray();
            for (int i = 0; i < selectedMaps.length && i < allMaps.length; i++) {
                if (selectedMaps[i]) {
                    selectedArray.add(new com.google.gson.JsonPrimitive(allMaps[i]));
                }
            }
            
            config.add("selectedMaps", selectedArray);
            config.addProperty("mode", mode);
            config.addProperty("blitzHourOnly", blitzHourOnly);
            
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(config.toString());
            }
        } catch (IOException e) {
            System.err.println("Failed to save MapQueuer config: " + e.getMessage());
        }
    }
    
    public static boolean[] loadSelectedMaps() {
        boolean[] selectedMaps = new boolean[allMaps.length];
        
        try {
            File configFile = mapQueuerConfigFile();
            if (!configFile.exists()) {
                applyFactoryDefaultMapSelection(selectedMaps);
                gameMode = "solo";
                return selectedMaps;
            }
            
            JsonObject config;
            try (FileReader reader = new FileReader(configFile)) {
                config = new JsonParser().parse(reader).getAsJsonObject();
            }
            
            if (config.has("selectedMaps")) {
                JsonArray selectedArray = config.getAsJsonArray("selectedMaps");
                for (int i = 0; i < selectedArray.size(); i++) {
                    String mapName = selectedArray.get(i).getAsString();
                    for (int j = 0; j < allMaps.length; j++) {
                        if (allMaps[j].equals(mapName)) {
                            selectedMaps[j] = true;
                            break;
                        }
                    }
                }
            }
            
            if (config.has("mode")) {
                gameMode = config.get("mode").getAsString();
            }
        } catch (IOException e) {
            System.err.println("Failed to load MapQueuer config: " + e.getMessage());
        }
        
        return selectedMaps;
    }

    /**
     * Whether the Map Queuer GUI "BLITZ HOUR" map-slot toggle is enabled (persisted in {@code mapqueuer.json}).
     */
    public static boolean loadBlitzHourOnlyPreference() {
        try {
            File configFile = mapQueuerConfigFile();
            if (!configFile.exists()) {
                return false;
            }
            JsonObject config;
            try (FileReader reader = new FileReader(configFile)) {
                config = new JsonParser().parse(reader).getAsJsonObject();
            }
            if (config.has("blitzHourOnly") && config.get("blitzHourOnly").isJsonPrimitive()) {
                return config.get("blitzHourOnly").getAsBoolean();
            }
        } catch (Exception e) {
            System.err.println("Failed to load Blitz Hour preference: " + e.getMessage());
        }
        return false;
    }

    public static String getSavedMode() {
        return gameMode;
    }
}
