package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScorePlayerTeam;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-queue feature that automatically joins blitz teams when a win bonus is detected
 * Also supports death requeue - automatically requeue when you die
 */
public class AutoQueue {
    
    // Track death status for team mode
    private static volatile boolean localPlayerDead = false;
    private static volatile boolean teammateDead = false;
    private static volatile String currentTeammate = null;
    private static volatile boolean trackedPlayerRequeueTriggered;
    private static final Pattern WINNER_LINE = Pattern.compile("^Winners?\\s*-\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINNER_ENTRY = Pattern.compile(
            "^(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)?([A-Za-z0-9_]{1,16})(?:\\s*\\([^()\\r\\n]{1,64}\\))?$"
    );
    private static final AtomicInteger delayedRequeueGeneration = new AtomicInteger(0);
    private static final ThreadLocal<Boolean> AUTOMATED_COMMAND_SEND = new ThreadLocal<>();

    private static final String[] TRANSFER_COMMANDS = {
            "/l", "/lobby", "/hub", "/requeue", "/play", "/limbo"
    };

    /** Cancel delayed win/death requeues when the user or server starts another transfer. */
    public static void onOutgoingChatCommand(String message) {
        if (Boolean.TRUE.equals(AUTOMATED_COMMAND_SEND.get()) || !isTransferCommand(message)) {
            return;
        }
        cancelPendingRequeues();
    }

    /** Invalidates delayed commands from the previous server/world. */
    public static void onConnectionTransition() {
        cancelPendingRequeues();
    }

    private static boolean isTransferCommand(String message) {
        if (message == null) {
            return false;
        }
        String command = message.trim().toLowerCase(Locale.ROOT);
        for (String prefix : TRANSFER_COMMANDS) {
            if (command.equals(prefix) || command.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    private static void cancelPendingRequeues() {
        delayedRequeueGeneration.incrementAndGet();
        localPlayerDead = false;
        teammateDead = false;
        currentTeammate = null;
        trackedPlayerRequeueTriggered = false;
    }

    private static void runOnClientThread(Runnable task) {
        if (task == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (mc.isCallingFromMinecraftThread()) {
                task.run();
            } else {
                mc.addScheduledTask(task);
            }
        } catch (Throwable t) {
            System.err.println("[AutoQueue] Failed to schedule client-thread task: " + t.getMessage());
        }
    }

    private static void sendAutomatedChat(Minecraft mc, String command) {
        if (mc == null || mc.thePlayer == null || command == null || command.isEmpty()) {
            return;
        }
        AUTOMATED_COMMAND_SEND.set(Boolean.TRUE);
        try {
            mc.thePlayer.sendChatMessage(command);
        } finally {
            AUTOMATED_COMMAND_SEND.remove();
        }
    }
    
    /**
     * Sets whether auto-queue is enabled
     * @param value true to enable, false to disable
     */
    public static void setEnabled(boolean value) {
        ConfigManager.setAutoQueueEnabled(value);
    }
    
    /**
     * True if either solo or teams win requeue is on.
     */
    public static boolean isEnabled() {
        return ConfigManager.isAutoQueueEnabled();
    }
    
    public static void loadSetting() {
        // Settings read live from {@link ConfigManager}
    }
    
    public static void setDeathRequeueEnabled(boolean value) {
        ConfigManager.setDeathRequeueEnabled(value);
    }
    
    public static boolean isDeathRequeueEnabled() {
        return ConfigManager.isDeathRequeueEnabled();
    }
    
    /**
     * Handles chat events to detect server-sent win bonus messages and player deaths
     */
    @SubscribeEvent
    public void onChat(Receive event) {
        try {
            if (!(event.getPacket() instanceof S02PacketChat)) {
                return;
            }
            
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            // Keep the original server identities even if another listener rewrites the component.
            final String message = packet.getChatComponent().getFormattedText();
            final String unformattedMessage = packet.getChatComponent().getUnformattedText();
            if (!mc.isCallingFromMinecraftThread()) {
                final Object world = mc.theWorld;
                final Object connection = mc.getNetHandler();
                mc.addScheduledTask(() -> {
                    if (mc.theWorld == world && mc.getNetHandler() == connection) {
                        handleChat(message, unformattedMessage);
                    }
                });
                return;
            }
            handleChat(message, unformattedMessage);
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error reading chat: " + e.getMessage());
        }
    }

    private void handleChat(String message, String unformattedMessage) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) return;
            
            // Check for game start to reset death tracking and stop team spammer / nick finder
            if (unformattedMessage != null && unformattedMessage.replaceAll("\\s+", " ").trim().equals("Survive while eliminating")) {
                localPlayerDead = false;
                teammateDead = false;
                currentTeammate = null;
                trackedPlayerRequeueTriggered = false;
                // Stop team spammer and unknown nick finder if they're running
                me.napim.gomod.command.TeamSpamCommand.stopSilent();
                UnknownNickFinder.stopSilent();
            }
            if (trackedPlayerRequeueTriggered) return;
            
            // Handle death requeue logic (independent of win requeue)
            // Also run when PlayerQueuer has death-tracked players, regardless of solo/teams death toggles
            boolean hasDeathTrackedPlayers = PlayerQueuer.isEnabled() && !PlayerQueuer.getDeathTrackPlayers().isEmpty();
            boolean anyDeathMode = ConfigManager.isDeathRequeueSoloEnabled() || ConfigManager.isDeathRequeueTeamsEnabled();
            if (anyDeathMode || hasDeathTrackedPlayers) {
                handleDeathRequeue(unformattedMessage);
            }
            if (trackedPlayerRequeueTriggered || handleTrackedPlayerWin(unformattedMessage)) return;
            
            if (!ConfigManager.isAutoQueueSoloEnabled() && !ConfigManager.isAutoQueueTeamsEnabled()) {
                return;
            }
            // Check if message contains "(Win Bonus)" and is from server (not from a player)
            // Server messages typically don't start with player names or chat prefixes
            if (message.contains("(Win Bonus)") && !isPlayerMessage(unformattedMessage)) {
                // Check if we're in a Blitz SG game before requeuing
                if (!isBlitzGame()) {
                    return;
                }
                // Determine game mode based on scoreboard
                boolean isTeamMode = isTeamGameMode();
                if (isTeamMode && !ConfigManager.isAutoQueueTeamsEnabled()) {
                    return;
                }
                if (!isTeamMode && !ConfigManager.isAutoQueueSoloEnabled()) {
                    return;
                }
                String gameMode = isTeamMode ? "teams" : "solo";
                String command = isTeamMode ? "/play blitz_teams_normal" : "/play blitz_solo_normal";
                // Get the appropriate delay from config
                float delaySeconds = isTeamMode ? ConfigManager.getAutoQueueTeamsDelay() : ConfigManager.getAutoQueueSoloDelay();
                int delay = (int)(delaySeconds * 1000); // Convert to milliseconds for Thread.sleep
                
                // Notify the player immediately with proper [gomod] formatting
                sendMessage(EnumChatFormatting.GRAY + "[" + EnumChatFormatting.DARK_GREEN + EnumChatFormatting.BOLD + "gomod" + 
                           EnumChatFormatting.GRAY + "] " + EnumChatFormatting.RESET + 
                           EnumChatFormatting.YELLOW + "Auto-queuing for blitz " + gameMode);
                
                // Execute the command after the configured delay
                final int generation = delayedRequeueGeneration.incrementAndGet();
                new Thread(() -> {
                    try {
                        Thread.sleep(delay);
                        runOnClientThread(() -> {
                            Minecraft current = Minecraft.getMinecraft();
                            if (generation != delayedRequeueGeneration.get()
                                    || current == null || current.thePlayer == null
                                    || !GameContext.isOnHypixel(current)) {
                                return;
                            }
                            sendAutomatedChat(current, command);
                        });
                    } catch (InterruptedException e) {
                        // Thread was interrupted, don't send the command
                    }
                }).start();
            }
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Exact server result rows only; player chat and kit text cannot supply a target name. */
    static List<String> parseWinnerNames(String message) {
        if (message == null || message.length() > 512) return Collections.emptyList();
        String clean = message.replaceAll("(?i)§[0-9a-fk-or]", "").trim();
        Matcher row = WINNER_LINE.matcher(clean);
        if (!row.matches()) return Collections.emptyList();
        List<String> winners = new ArrayList<>();
        for (String entry : row.group(1).split("(?i)\\s*[,&]\\s*|\\s+and\\s+", -1)) {
            Matcher winner = WINNER_ENTRY.matcher(entry.trim());
            if (!winner.matches()) return Collections.emptyList();
            winners.add(winner.group(1));
        }
        return winners;
    }

    private static boolean handleTrackedPlayerWin(String message) {
        List<String> winners = parseWinnerNames(message);
        if (winners.isEmpty()) return false;
        if (!PlayerQueuer.isEnabled() || ReplaySettings.isReplayActive() || !isBlitzGame()) return false;
        for (String winner : winners) {
            if (PlayerQueuer.isDeathTracked(winner)) {
                triggerTrackedPlayerRequeue(isTeamGameMode(), winner, true);
                return trackedPlayerRequeueTriggered;
            }
        }
        return false;
    }
    
    /**
     * Helper method to send a chat message to the player
     */
    private static void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
    
    /**
     * Checks if a message is from a player (not from the server)
     * @param message The unformatted message to check
     * @return true if the message appears to be from a player, false if from server
     */
    private static boolean isPlayerMessage(String message) {
        // Server messages typically start with "+" for coin rewards (e.g., "+49 coins! ... (Win Bonus)")
        // Player messages don't start with "+"
        return !message.startsWith("+");
    }
    
    /**
     * Checks if the current game mode is a team mode by looking for "Team" in the scoreboard
     * @return true if "Team" is found in the scoreboard, false otherwise
     */
    private static boolean isTeamGameMode() {
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
            
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            // Check all scoreboard entries for "Team" mention
            for (Score score : scores) {
                // Use the same method as MapQueuer to properly read scoreboard entries
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String scoreText = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (scoreText != null) {
                    // Strip formatting codes before checking for team keywords
                    String cleanText = stripFormatting(scoreText);
                    // Check for various team-related keywords (case insensitive)
                    String lowerText = cleanText.toLowerCase();
                    if (lowerText.contains("team") || lowerText.contains("teams") || 
                        lowerText.contains("team mode") || lowerText.contains("team game")) {
                        return true;
                    }
                }
            }
            
            // Also check the objective name itself
            String objectiveName = objective.getDisplayName();
            if (objectiveName != null) {
                // Strip formatting codes before checking for team keywords
                String cleanObjectiveName = stripFormatting(objectiveName);
                String lowerObjective = cleanObjectiveName.toLowerCase();
                if (lowerObjective.contains("team") || lowerObjective.contains("teams")) {
                    return true;
                }
            }
            return false;
            
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error checking scoreboard: " + e.getMessage());
            e.printStackTrace();
            // Default to solo mode on error
            return false;
        }
    }
    
    /**
     * Strips Minecraft formatting codes from text
     * @param text The text to clean
     * @return Clean text without formatting codes
     */
    private static String stripFormatting(String text) {
        if (text == null) {
            return "";
        }
        // Remove § formatting codes and & color codes
        return text.replaceAll("(?i)§[0-9a-fk-or]", "").replaceAll("(?i)&[0-9a-fk-or]", "");
    }
    
    /**
     * Checks if the current game is a Blitz SG game by looking for "Blitz" in the scoreboard
     * @return true if "Blitz" is found in the scoreboard, false otherwise
     */
    static boolean isBlitzGame() {
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
            
            // Check the objective name first (usually contains the game name)
            String objectiveName = objective.getDisplayName();
            if (objectiveName != null) {
                String cleanObjectiveName = stripFormatting(objectiveName);
                String lowerObjective = cleanObjectiveName.toLowerCase();
                if (lowerObjective.contains("blitz")) {
                    return true;
                }
            }
            
            // Check all scoreboard entries for "Blitz" mention
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            for (Score score : scores) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String scoreText = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (scoreText != null) {
                    String cleanText = stripFormatting(scoreText);
                    String lowerText = cleanText.toLowerCase();
                    
                    if (lowerText.contains("blitz")) {
                        return true;
                    }
                }
            }
            return false;
            
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error checking if Blitz game: " + e.getMessage());
            e.printStackTrace();
            // Default to false on error (don't requeue if unsure)
            return false;
        }
    }
    
    /**
     * Handles death requeue logic - detects player death and triggers requeue
     * @param message The unformatted chat message
     */
    private static void handleDeathRequeue(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            
            // Do not process death requeue while watching a replay
            if (ReplaySettings.isReplayActive()) {
                // Reset any pending death flags to avoid queued requeues
                localPlayerDead = false;
                teammateDead = false;
                return;
            }
            
            String localPlayerName = mc.thePlayer.getName();
            boolean isTeamMode = isTeamGameMode();
            
            // Detect teammate from scoreboard in team mode (refresh on each death check)
            if (isTeamMode) {
                // Always try to refresh teammate detection, as team status may change after death
                String detectedTeammate = detectTeammate();
                if (detectedTeammate != null) {
                    currentTeammate = detectedTeammate;
                }
            }
            
            // Check for death messages - patterns like "PlayerName was killed by OtherPlayer"
            String[] deathPatterns = {
                " was killed by ",
                " was slain by ",
                " was shot by ",
                " was burned to death by ",
                " was frozen to death by ",
                " was eliminated by "
            };
            
            for (String pattern : deathPatterns) {
                if (message.contains(pattern)) {
                    String[] parts = message.split(pattern);
                    if (parts.length >= 1) {
                        String victim = stripFormatting(parts[0].trim()).trim();
                        if (victim.isEmpty()) {
                            continue;
                        }
                        
                        // Check if local player died
                        if (victim.equalsIgnoreCase(localPlayerName)) {
                            localPlayerDead = true;
                        }
                        
                        // Check if teammate died (in team mode)
                        if (isTeamMode && currentTeammate != null && victim.equalsIgnoreCase(currentTeammate)) {
                            teammateDead = true;
                        }
                        
                        // Check if a death-tracked player died (from PlayerQueuer) — exact ign match after § strip
                        if (PlayerQueuer.isDeathTracked(victim)) {
                            triggerTrackedPlayerRequeue(isTeamMode, victim, false);
                            return; // Exit early to avoid duplicate processing
                        }
                    }
                }
            }
            
            if (localPlayerDead) {
                if (isTeamMode) {
                    if (!ConfigManager.isDeathRequeueTeamsEnabled()) {
                        return;
                    }
                    // In team mode, both players must be dead OR we can't detect a teammate
                    if (teammateDead) {
                        triggerDeathRequeue(true);
                    } else if (currentTeammate == null) {
                        triggerDeathRequeue(true);
                    }
                } else {
                    if (!ConfigManager.isDeathRequeueSoloEnabled()) {
                        return;
                    }
                    triggerDeathRequeue(false);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error in handleDeathRequeue: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Detects the teammate from scoreboard in team mode
     * Uses the same logic as PlayerTarget to find teammates on the same team
     * @return teammate name, or null if not found
     */
    private static String detectTeammate() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null || mc.thePlayer == null) {
                return null;
            }
            
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) return null;
            
            // Get local player's team
            String localPlayerName = mc.thePlayer.getName();
            ScorePlayerTeam myTeam = scoreboard.getPlayersTeam(localPlayerName);
            
            // If we're not on a team (dead/spectating), use last known teammate or search for potential teammate
            if (myTeam == null) {
                // First, check if we have a cached teammate name
                if (currentTeammate != null && !currentTeammate.isEmpty()) {
                    // Verify this teammate still exists in the world
                    for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                        if (player == mc.thePlayer) continue;
                        String name = player.getName();
                        if (name != null && name.equals(currentTeammate)) {
                            return currentTeammate;
                        }
                    }
                }
                
                // Look through all players to find someone who might be our teammate
                // This handles the case where we die but our teammate is still alive
                for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                    if (player == mc.thePlayer) continue;
                    
                    String name = player.getName();
                    if (name == null || name.isEmpty()) continue;
                    
                    // Check if this looks like a player name (not NPC)
                    if (name.length() < 3 || name.length() > 16 || !name.matches("^[a-zA-Z0-9_]+$")) {
                        continue;
                    }
                    
                    ScorePlayerTeam playerTeam = scoreboard.getPlayersTeam(name);
                    // If player has a team, they might be our teammate (we can't verify same team without our team)
                    if (playerTeam != null) {
                        // Return the first valid player with a team as potential teammate
                        return name;
                    }
                }
                return null;
            }
            
            // We're on a team - find a teammate on the same team
            for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer) continue;
                
                String name = player.getName();
                if (name == null || name.isEmpty()) continue;
                
                // Check if this looks like a player name (not NPC)
                if (name.length() < 3 || name.length() > 16 || !name.matches("^[a-zA-Z0-9_]+$")) {
                    continue;
                }
                
                // Check if player is on the same team
                ScorePlayerTeam playerTeam = scoreboard.getPlayersTeam(name);
                if (playerTeam != null && myTeam.isSameTeam(playerTeam)) {
                    return name;
                }
            }
            
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error detecting teammate: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    /**
     * Triggers the death requeue with appropriate delay
     * @param isTeamMode whether we're in team mode
     */
    private static void triggerDeathRequeue(boolean isTeamMode) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            // Prevent multiple requeue triggers
            if (localPlayerDead && isTeamMode && !teammateDead) {
                return; // Already handled or waiting for teammate
            }
            
            // Block requeue if currently in replay
            if (ReplaySettings.isReplayActive()) {
                localPlayerDead = false;
                teammateDead = false;
                return;
            }
            
            // Check if we're in a Blitz SG game before requeuing
            if (!isBlitzGame()) {
                // Mark as handled by resetting the death flags
                localPlayerDead = false;
                teammateDead = false;
                return;
            }
            
            // Mark as handled by resetting the death flags
            localPlayerDead = false;
            teammateDead = false;
            
            String gameMode = isTeamMode ? "teams" : "solo";
            String command = isTeamMode ? "/play blitz_teams_normal" : "/play blitz_solo_normal";
            
            // Get the appropriate delay from config
            float delaySeconds = isTeamMode ? ConfigManager.getDeathRequeueTeamsDelay() : ConfigManager.getDeathRequeueSoloDelay();
            int delay = (int)(delaySeconds * 1000); // Convert to milliseconds
            
            // Notify the player
            String message = EnumChatFormatting.GRAY + "[" + EnumChatFormatting.DARK_GREEN + EnumChatFormatting.BOLD + "gomod" + 
                       EnumChatFormatting.GRAY + "] " + EnumChatFormatting.RESET + 
                       EnumChatFormatting.RED + "You died! " + EnumChatFormatting.YELLOW + 
                       "Requeuing for blitz " + gameMode;
            
            // Add delay info only if delay is greater than 0
            if (delaySeconds > 0.0f) {
                message += " in " + String.format("%.2f", delaySeconds) + " seconds...";
            } else {
                message += "...";
            }
            
            sendMessage(message);
            
            // Execute the command after the configured delay
            final int generation = delayedRequeueGeneration.incrementAndGet();
            new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    runOnClientThread(() -> {
                        Minecraft current = Minecraft.getMinecraft();
                        if (generation != delayedRequeueGeneration.get()
                                || current == null || current.thePlayer == null
                                || !GameContext.isOnHypixel(current)) {
                            return;
                        }
                        sendAutomatedChat(current, command);
                    });
                } catch (InterruptedException e) {
                }
            }, "AutoQueue-DeathRequeue").start();
            
        } catch (Exception e) {
            System.err.println("[AutoQueue] Error triggering death requeue: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Triggers an immediate Player Requeuer queue when a tracked player dies or wins.
     * This path deliberately ignores the normal solo/teams death-requeue delay sliders.
     * @param isTeamMode whether we're in team mode
     * @param playerName the name of the tracked player who finished
     */
    private static void triggerTrackedPlayerRequeue(boolean isTeamMode, String playerName, boolean won) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (trackedPlayerRequeueTriggered || mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            // Block requeue if currently in replay
            if (ReplaySettings.isReplayActive()) {
                return;
            }
            
            // Check if we're in a Blitz SG game before requeuing
            if (!isBlitzGame()) {
                return;
            }
            
            String gameMode = isTeamMode ? "teams" : "solo";
            
            // The immediate tracked-player queue supersedes any delayed personal win/death queue.
            if (!PlayerQueuer.sendDeathTrackedPlayerRequeueNow(isTeamMode)) return;
            trackedPlayerRequeueTriggered = true;
            delayedRequeueGeneration.incrementAndGet();

            // Notify the player
            String message = EnumChatFormatting.GRAY + "[" + EnumChatFormatting.DARK_GREEN + EnumChatFormatting.BOLD + "gomod" + 
                       EnumChatFormatting.GRAY + "] " + EnumChatFormatting.RESET + 
                       EnumChatFormatting.GOLD + playerName + (won ? " won! " : " died! ") + EnumChatFormatting.YELLOW + 
                       "Requeuing for blitz " + gameMode + " now...";
            
            sendMessage(message);

        } catch (Exception e) {
            System.err.println("[AutoQueue] Error triggering tracked player death requeue: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
