package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.Main;
import me.napim.gomod.command.TeamSpamCommand;
import me.napim.gomod.util.GameContext;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.network.NetworkPlayerInfo;

/**
 * Unknown Nick Finder - Brute-force searches for player nicknames by trying character combinations.
 * Optimized with character frequency analysis based on real Minecraft username data.
 */
public class UnknownNickFinder {
    
    // Optimized character order based on Minecraft username statistics
    // Letters: Most common first (e, a, r, i, o, t, n, s, l, c...)
    // Numbers: Most common in usernames (0, 1, 3, 4, 2, 7, 5, 8, 6, 9)
    // Special: underscore (allowed in Minecraft usernames)
    private static final char[] SEARCH_ORDER = {
        // Most common letters in Minecraft usernames
        'e', 'a', 'r', 'i', 'o', 't', 'n', 's', 'l', 'c', 
        'u', 'd', 'p', 'm', 'h', 'g', 'b', 'f', 'y', 'w', 
        'k', 'v', 'x', 'z', 'j', 'q',
        // Most common numbers in Minecraft usernames (0, 1, 3, 4, 2 are most frequent)
        '0', '1', '3', '4', '2', '7', '5', '8', '6', '9',
        // Underscore (less common but valid)
        '_'
    };
    
    private static final int MAX_USERNAME_LENGTH = 16; // Minecraft username limit
    private static final int MIN_DELAY_MS = 500; // Minimum safe delay
    private static final int MAX_DEPTH_DEFAULT = 16; // Default to max Minecraft username length
    
    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> { Thread t = new Thread(r, "GoMod-NickFinder"); t.setDaemon(true); return t; });
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static ScheduledFuture<?> currentTask = null;
    private static ScheduledFuture<?> nextAttemptTask = null; // Track the timer for next attempt
    
    // Search state
    private static String currentPrefix = "";
    private static int currentCharIndex = 0;
    private static int delayMs = 900; // Will be set to TeamSpam delay when started
    private static int baseDelayMs = 900; // Store the original delay
    private static int maxDepth = MAX_DEPTH_DEFAULT;
    private static long searchStartTime = 0;
    private static int totalAttempts = 0;
    private static final int SLOWDOWN_THRESHOLD = 100; // Slow down after this many attempts
    private static final int SLOWDOWN_DELAY_INCREASE = 200; // Add 200ms after threshold
    private static char[] prioritizedSearchOrder = null; // Dynamically generated based on tab list
    
    // Result tracking
    private static String foundNickname = null;
    private static volatile boolean waitingForResponse = false;

    /** Confirms Hypixel lobby sidebar briefly before stopping (avoids scoreboard flicker). */
    private static int consecutiveHypixelLobbyTicks = 0;
    private static final int LOBBY_CONFIRM_TICKS = 3;

    /** Count of other tab players when the search started; used to stop if everyone else leaves mid-search. */
    private static int otherTabPlayersAtSearchStart = 0;
    
    /**
     * Gets the current search order array to use (prioritized or default)
     */
    private static char[] getSearchOrder() {
        return prioritizedSearchOrder != null ? prioritizedSearchOrder : SEARCH_ORDER;
    }
    
    /**
     * Generates a prioritized search order based on the current tab list and prefix.
     * Characters that don't match tab list players at the current position are tried first.
     * 
     * @param prefix The current prefix being searched (e.g., "", "D", "Dr", "Dra")
     * @return Prioritized character array for the next position
     */
    private static int countOtherPlayersInTab() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null || mc.getNetHandler().getPlayerInfoMap() == null) {
                return 0;
            }
            String self = mc.thePlayer.getName();
            int n = 0;
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                String name = info.getGameProfile().getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (!name.equalsIgnoreCase(self)) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Stops the finder if the search began with at least one other player on tab and tab no longer has anyone else.
     * Avoids burning invites after everyone left the lobby / queue.
     */
    private static boolean stopIfEveryoneElseLeft() {
        if (!running.get()) {
            return false;
        }
        if (otherTabPlayersAtSearchStart <= 0) {
            return false;
        }
        if (countOtherPlayersInTab() > 0) {
            return false;
        }
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No other players in tab (they may have left) - stopping Unknown Nick Finder.");
        stopInternal(false);
        return true;
    }

    /** Same gate as {@link TeamSpamCommand} start: Blitz Teams map pregame, not games hub queue. */
    private static boolean isBlitzTeamsPregameInviteContext(Minecraft mc) {
        return BlitzTeamsDetector.isBlitzTeamsMapPregame(mc);
    }

    private static char[] generatePrioritizedSearchOrder(String prefix) {
        Set<Character> tabLettersAtPosition = new HashSet<>();
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfoMap() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile() != null && info.getGameProfile().getName() != null) {
                        String name = info.getGameProfile().getName().toLowerCase();
                        
                        // Check if this player's name matches our current prefix
                        if (name.length() > prefix.length() && name.startsWith(prefix.toLowerCase())) {
                            // Get the character at the position we're about to search
                            char nextChar = name.charAt(prefix.length());
                            tabLettersAtPosition.add(nextChar);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UnknownNickFinder] Error reading tab list: " + e.getMessage());
        }
        
        // Build prioritized list: characters NOT matching tab positions first, then matching ones
        List<Character> notInTab = new ArrayList<>();
        List<Character> inTab = new ArrayList<>();
        
        for (char c : SEARCH_ORDER) {
            if (tabLettersAtPosition.contains(c)) {
                inTab.add(c);
            } else {
                notInTab.add(c);
            }
        }
        
        // Combine: not in tab first, then in tab
        List<Character> combined = new ArrayList<>();
        combined.addAll(notInTab);
        combined.addAll(inTab);
        
        // Convert to char array
        char[] result = new char[combined.size()];
        for (int i = 0; i < combined.size(); i++) {
            result[i] = combined.get(i);
        }
        
        // Debug output
        if (!tabLettersAtPosition.isEmpty() && prefix.length() < 3) {
            System.out.println("[UnknownNickFinder] At prefix '" + prefix + "', tab has letters: " + tabLettersAtPosition);
            System.out.println("[UnknownNickFinder] Prioritizing " + notInTab.size() + " letters not matching tab");
        }
        
        return result;
    }
    
    /**
     * Starts the Unknown Nick Finder search
     * Uses the same delay as TeamSpam for consistency
     */
    public static void start() {
        // Use the same delay as TeamSpam feature
        int teamSpamDelay = TeamSpamCommand.getDelay();
        start(teamSpamDelay, MAX_DEPTH_DEFAULT);
    }
    
    /**
     * Starts the Unknown Nick Finder search with custom settings
     * @param delayMs Delay between attempts in milliseconds
     * @param maxDepth Maximum search depth (number of characters to try)
     */
    public static void start(int delayMs, int maxDepth) {
        if (running.get()) {
            sendMessage(EnumChatFormatting.RED + "Unknown Nick Finder is already running! Use /unickfind stop to cancel.");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Can't start the Unknown Nick Finder right now.");
            return;
        }
        // Match TeamSpam: map pregame only, not Hypixel games hub Blitz queue.
        if (!isBlitzTeamsPregameInviteContext(mc)) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Unknown nick finder can only start in Blitz Teams map pregame.");
            return;
        }
        
        // Validate settings
        UnknownNickFinder.delayMs = Math.max(MIN_DELAY_MS, delayMs);
        UnknownNickFinder.baseDelayMs = UnknownNickFinder.delayMs; // Store base delay
        UnknownNickFinder.maxDepth = Math.min(Math.max(1, maxDepth), MAX_USERNAME_LENGTH);
        
        // Reset state
        currentPrefix = "";
        currentCharIndex = 0;
        foundNickname = null;
        waitingForResponse = false;
        searchStartTime = System.currentTimeMillis();
        totalAttempts = 0;
        
        // Generate prioritized search order based on tab list (starting with empty prefix)
        prioritizedSearchOrder = generatePrioritizedSearchOrder("");
        
        running.set(true);
        consecutiveHypixelLobbyTicks = 0;
        otherTabPlayersAtSearchStart = countOtherPlayersInTab();

        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Starting Unknown Nick Finder...");
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Smart search: avoiding tab list players");
        
        // Perform first attempt immediately, then future attempts use delay
        performNextAttempt();
    }
    
    /**
     * Stops the Unknown Nick Finder search
     */
    public static void stop() {
        stopInternal(true);
    }

    /**
     * Stops the Unknown Nick Finder silently (no chat message). Used for auto-stop on game start.
     */
    public static void stopSilent() {
        if (!running.get()) return;
        stopInternal(false);
    }

    /**
     * Stops the finder with a chat notice when the player returns to a Hypixel lobby (e.g. left Blitz pregame via /hub).
     */
    public static void stopForLobbyReturn() {
        if (!running.get()) {
            return;
        }
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Returned to lobby - stopping Unknown Nick Finder.");
        stopInternal(false);
    }

    private static void stopInternal(boolean sendMessage) {
        if (!running.get()) {
            if (sendMessage) sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown Nick Finder is not running!");
            return;
        }
        
        running.set(false);
        consecutiveHypixelLobbyTicks = 0;
        otherTabPlayersAtSearchStart = 0;

        // Cancel the current task
        if (currentTask != null && !currentTask.isDone() && !currentTask.isCancelled()) {
            currentTask.cancel(false);
            currentTask = null;
        }
        
        // Cancel any pending next attempt task
        if (nextAttemptTask != null && !nextAttemptTask.isDone() && !nextAttemptTask.isCancelled()) {
            nextAttemptTask.cancel(false);
            nextAttemptTask = null;
        }
        
        if (sendMessage) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Stopped" + 
                       (foundNickname != null ? 
                           (EnumChatFormatting.GRAY + " - Last found: " + EnumChatFormatting.WHITE + foundNickname) : 
                           ""));
        }
    }
    
    /**
     * Check if the finder is currently running
     */
    public static boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get the current search progress
     */
    public static String getProgress() {
        if (!running.get()) {
            return "Not running";
        }
        return "Trying: /team " + (currentPrefix.isEmpty() ? getSearchOrder()[currentCharIndex] : currentPrefix);
    }
    
    /**
     * Called when a chat message is received to detect successful invites
     * Should be called from a chat listener mixin
     */
    public static void onChatMessage(String message) {
        if (!running.get() || !waitingForResponse) {
            return;
        }
        
        // Check for success patterns
        // Common success messages:
        // - "You invited [PLAYER] to your team!"
        // - "[PLAYER] joined the team."
        // - "You sent a team invite to [PLAYER]"
        // - "Team request sent to [PLAYER]!"
        // - "You've already sent that player a team request!"
        
        String lowerMsg = message.toLowerCase();
        
        // Success indicators - more flexible patterns
        if ((lowerMsg.contains("invited") && lowerMsg.contains("to your team")) ||
            lowerMsg.contains("joined the team") ||
            (lowerMsg.contains("team request sent to")) ||
            (lowerMsg.contains("sent a team invite")) ||
            (lowerMsg.contains("has been invited")) ||
            (lowerMsg.contains("already sent") && lowerMsg.contains("team request"))) {
            
            // Cancel any pending timer immediately - we're handling this response
            if (nextAttemptTask != null && !nextAttemptTask.isDone() && !nextAttemptTask.isCancelled()) {
                nextAttemptTask.cancel(false);
            }
            
            // Found a player!
            String attemptedName = currentPrefix + getSearchOrder()[currentCharIndex];
            
            // Check if this is a repeat find (already sent message)
            boolean isAlreadySent = lowerMsg.contains("already sent");
            
            // If we got "already sent" and the attempted name is EXACTLY the same as before,
            // it means we're trying the same thing twice (shouldn't happen, but safety check)
            if (isAlreadySent && foundNickname != null && attemptedName.equals(foundNickname)) {
                completeSearch();
                return;
            }
            
            // This is a new/deeper find, update foundNickname
            foundNickname = attemptedName;
            
            // Show success with green colored name and checkmark
            sendMessage(EnumChatFormatting.GRAY + "Attempt #" + totalAttempts + ": " + 
                       EnumChatFormatting.GREEN + foundNickname + 
                       EnumChatFormatting.GRAY + ".. " + 
                       EnumChatFormatting.GREEN + "\u2713");
            
            // Check if we should continue deeper or stop here
            if (currentPrefix.length() + 1 >= maxDepth) {
                // Reached max depth, complete the search
                completeSearch();
            } else {
                // Continue searching deeper to find the full nickname
                continueSearchDeeper();
            }
            return;
        }
        
        // Error indicators (player not found, move to next)
        if (lowerMsg.contains("couldn't find a player") ||
            lowerMsg.contains("couldn't find a player by that name") ||
            lowerMsg.contains("that player is not online") ||
            lowerMsg.contains("no player was found") ||
            lowerMsg.contains("player not found")) {
            
            // Cancel the pending next attempt timer since we got an error response
            if (nextAttemptTask != null && !nextAttemptTask.isDone() && !nextAttemptTask.isCancelled()) {
                nextAttemptTask.cancel(false);
            }
            
            // Clear waiting flag and move to next character
            waitingForResponse = false;
            currentCharIndex++;
            
            if (currentCharIndex >= getSearchOrder().length) {
                // Tried all characters at this depth
                if (currentPrefix.length() < maxDepth - 1) {
                    completeCurrentLevel();
                } else {
                    completeSearch();
                }
            } else {
                // Schedule next attempt immediately since we got a response
                scheduleNextAttempt(delayMs);
            }
        }
    }
    
    /**
     * Stop team spam / nick finder when leaving Blitz pregame for a Hypixel lobby (sidebar no longer shows queue/pregame rows).
     * Blitz Teams pregame is excluded from the lobby check so brief {@link GameContext#isHypixelLobby} false positives there do not stop either feature.
     */
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("UnknownNickFinder")) return;
        long _profT = FeatureProfiler.begin("UnknownNickFinder");
        try {
        if (!UnknownNickFinder.isRunning() && !TeamSpamCommand.isRunning()) {
            consecutiveHypixelLobbyTicks = 0;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            consecutiveHypixelLobbyTicks = 0;
            UnknownNickFinder.stopSilent();
            TeamSpamCommand.stopSilent();
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            consecutiveHypixelLobbyTicks = 0;
            UnknownNickFinder.stopSilent();
            TeamSpamCommand.stopSilent();
            return;
        }
        if (UnknownNickFinder.isRunning()) {
            UnknownNickFinder.stopIfEveryoneElseLeft();
        }
        // isHypixelLobby can be true in Blitz Teams pregame (e.g. "blitz" mode marker without rows matched by
        // containsGameOrPregameMarkers). Do not treat that as "returned to lobby" — same scope as team spam.
        boolean hypixelMainLobbyForAutoStop = GameContext.isHypixelLobby(mc) && !isBlitzTeamsPregameInviteContext(mc);
        if (hypixelMainLobbyForAutoStop) {
            consecutiveHypixelLobbyTicks++;
            if (consecutiveHypixelLobbyTicks >= LOBBY_CONFIRM_TICKS) {
                consecutiveHypixelLobbyTicks = 0;
                UnknownNickFinder.stopForLobbyReturn();
                TeamSpamCommand.stopForLobbyReturn();
            }
        } else {
            consecutiveHypixelLobbyTicks = 0;
        }
        } finally { FeatureProfiler.end("UnknownNickFinder", _profT); }
    }

    /**
     * Chat event handler to detect and filter messages
     */
    @SubscribeEvent
    public void onChatPacket(PacketEvent.Receive event) {
        if (!running.get()) {
            return;
        }
        
        try {
            if (!(event.getPacket() instanceof S02PacketChat)) {
                return;
            }
            
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            String message = packet.getChatComponent().getUnformattedText();
            String lowerMsg = message.toLowerCase();
            
            // Check for game start message first
            if (message != null && message.replaceAll("\\s+", " ").trim().equals("Survive while eliminating")) {
                // Game has started, stop the finder
                System.out.println("[UnknownNickFinder] Game started, stopping finder automatically.");
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Game started - stopping Unknown Nick Finder.");
                stop();
                return;
            }
            
            // FIRST: Process the message for detection (before cancelling)
            onChatMessage(message);
            
            // THEN: Hide error and spam messages while finder is running
            if (lowerMsg.contains("couldn't find a player") ||
                lowerMsg.contains("couldn't find a player by that name") ||
                lowerMsg.contains("that player is not online") ||
                lowerMsg.contains("no player was found") ||
                lowerMsg.contains("player not found") ||
                (lowerMsg.contains("already sent") && lowerMsg.contains("team request"))) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            // Silently handle errors
        }
    }
    
    /**
     * Schedules the next search attempt
     */
    private static void scheduleNextAttempt() {
        scheduleNextAttempt(delayMs);
    }
    
    /**
     * Schedules the next search attempt with a specific delay
     */
    private static void scheduleNextAttempt(long delay) {
        if (!running.get()) {
            return;
        }
        
        currentTask = scheduler.schedule(() -> {
            try {
                performNextAttempt();
            } catch (Exception e) {
                System.err.println("[UnknownNickFinder] Error during attempt: " + e.getMessage());
                e.printStackTrace();
                scheduleNextAttempt(); // Continue despite error
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Performs the next search attempt
     */
    private static void performNextAttempt() {
        if (!running.get()) {
            return;
        }
        if (stopIfEveryoneElseLeft()) {
            return;
        }
        
        // Check if we've exceeded max depth
        if (currentPrefix.length() >= maxDepth) {
            // Completed search at this depth
            completeSearch();
            return;
        }
        
        // Build the attempt string
        String attempt = currentPrefix + getSearchOrder()[currentCharIndex];

        // Hypixel can resolve a partial name to its owner. Do not let the finder
        // branch into or invite our own account while expanding successful prefixes.
        if (UnknownNickFinderPolicy.isOwnIgnPrefix(attempt, getLocalIgn())) {
            advancePastSkippedAttempt();
            return;
        }

        totalAttempts++;
        
        // Slowdown safety: After 100 attempts, increase delay to prevent kicks
        if (totalAttempts == SLOWDOWN_THRESHOLD) {
            delayMs = baseDelayMs + SLOWDOWN_DELAY_INCREASE;
        }
        
        // Show what we're trying (format: Attempt #8: ta)
        sendMessage(EnumChatFormatting.GRAY + "Attempt #" + totalAttempts + ": " + 
                   EnumChatFormatting.YELLOW + attempt);
        
        // Send the team invite command
        waitingForResponse = true;
        Minecraft.getMinecraft().thePlayer.sendChatMessage("/team invite " + attempt);
        
        // Schedule the next attempt after delay (chat handler may trigger earlier or cancel this)
        nextAttemptTask = scheduler.schedule(() -> {
            if (stopIfEveryoneElseLeft()) {
                return;
            }
            // Clear waiting flag and move to next
            waitingForResponse = false;
            currentCharIndex++;
            
            if (currentCharIndex >= getSearchOrder().length) {
                // Tried all characters at this depth
                if (currentPrefix.length() < maxDepth - 1) {
                    completeCurrentLevel();
                } else {
                    completeSearch();
                }
            } else {
                // Perform next attempt immediately (we already waited)
                performNextAttempt();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static String getLocalIgn() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        try {
            if (mc.getSession() != null) {
                String accountName = mc.getSession().getUsername();
                if (accountName != null && !accountName.trim().isEmpty()) {
                    return accountName.trim();
                }
            }
        } catch (Exception ignored) {
            // Fall back to the local player name if the session is unavailable.
        }
        try {
            return mc.thePlayer == null ? null : mc.thePlayer.getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Advance without sending an invite or counting a skipped local-name prefix as an attempt. */
    private static void advancePastSkippedAttempt() {
        currentCharIndex++;
        if (currentCharIndex >= getSearchOrder().length) {
            if (currentPrefix.length() < maxDepth - 1) {
                completeCurrentLevel();
            } else {
                completeSearch();
            }
        } else {
            scheduleNextAttempt(0L);
        }
    }
    
    /**
     * Continues search deeper after finding a match
     */
    private static void continueSearchDeeper() {
        // Cancel the pending next attempt timer (we're changing direction)
        if (nextAttemptTask != null && !nextAttemptTask.isDone() && !nextAttemptTask.isCancelled()) {
            nextAttemptTask.cancel(false);
        }
        
        // Update prefix to include the found character
        currentPrefix = foundNickname;
        currentCharIndex = 0;
        waitingForResponse = false;
        
        // Regenerate prioritized search order for the new depth with current prefix
        prioritizedSearchOrder = generatePrioritizedSearchOrder(currentPrefix);
        
        // Continue with next attempt after delay (no extra message needed)
        nextAttemptTask = scheduler.schedule(() -> {
            if (stopIfEveryoneElseLeft()) {
                return;
            }
            performNextAttempt();
        }, delayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Completes the current search level (exhausted all characters)
     */
    private static void completeCurrentLevel() {
        // If we have a previous match and we exhausted all characters at the next depth,
        // it means the previous match IS the complete username
        if (foundNickname != null && !foundNickname.isEmpty()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Complete username: " + 
                       EnumChatFormatting.WHITE + foundNickname);
        } else {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No matches found.");
        }
        completeSearch();
    }
    
    /**
     * Completes the entire search
     */
    private static void completeSearch() {
        long duration = System.currentTimeMillis() - searchStartTime;
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        
        if (foundNickname != null && !foundNickname.isEmpty()) {
            // Only show if not already shown
            if (running.get()) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Found: " + 
                           EnumChatFormatting.WHITE + foundNickname + 
                           EnumChatFormatting.GRAY + " (" + totalAttempts + " attempts, " + 
                           minutes + "m " + seconds + "s)");
            }
        } else {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No players found " + 
                       EnumChatFormatting.GRAY + "(" + totalAttempts + " attempts)");
        }
        
        running.set(false);
        consecutiveHypixelLobbyTicks = 0;
        otherTabPlayersAtSearchStart = 0;
    }
    
    /**
     * Sends a message to the player
     */
    private static void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
    
    /**
     * Gets the last found nickname (null if none found)
     */
    public static String getFoundNickname() {
        return foundNickname;
    }
    
    /**
     * Gets the total number of attempts made
     */
    public static int getTotalAttempts() {
        return totalAttempts;
    }
}

