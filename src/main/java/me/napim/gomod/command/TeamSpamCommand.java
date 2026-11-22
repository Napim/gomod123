package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.TeamSpamManager;
import me.napim.gomod.features.TeamSpamManager.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.PacketEvent;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Command to manage team spam functionality.
 * This allows players to automatically spam team invites to a list of players.
 */
public class TeamSpamCommand extends Command {
    private static ScheduledExecutorService newDaemonScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "GoMod-TeamSpam");
            t.setDaemon(true);
            return t;
        });
    }
    private static void startDaemonThread(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
    private static ScheduledExecutorService scheduler = newDaemonScheduler();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger count = new AtomicInteger(0);
    private static final AtomicInteger delay = new AtomicInteger(900); // ms between invites
    private static final AtomicBoolean isProcessing = new AtomicBoolean(false); // Lock for preventing concurrent execution
    private static ScheduledFuture<?> currentTask = null; // Reference to the current scheduled task
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy");
    
    // Keep track of the last player that was invited
    public static volatile String lastInvitedPlayer = "";
    
    // Keep track of the last player successfully found
    public static volatile String lastFoundPlayer = "";
    
    // Track when the spammer last sent an invite (to distinguish from manual invites)
    private static volatile long lastSpammerInviteTime = 0;

    /**
     * Owns a single automatic invite and its exact packet identities. Manual invites invalidate the run,
     * including queued client-thread sends and discoveries waiting to be applied.
     */
    private static final AtomicInteger inviteGeneration = new AtomicInteger();
    private static final ThreadLocal<InviteAttempt> automatedInviteScope = new ThreadLocal<>();
    private static final java.util.Map<Object, InviteAttempt> automatedPackets = new java.util.WeakHashMap<>();
    private static volatile InviteAttempt pendingInvite;
    private static volatile long manualInviteCooldownUntil;

    public static final class InviteAttempt {
        public final String realName;
        private final int generation;
        private final long sentAt;
        private final Object world, player, connection;
        private InviteAttempt(String name, int generation, Minecraft mc) {
            realName=name;this.generation=generation;sentAt=System.currentTimeMillis();
            world=mc.theWorld;player=mc.thePlayer;connection=mc.getNetHandler();
        }
    }
    
    // Time window in milliseconds - only process responses within this window after spammer invite
    private static final long INVITE_RESPONSE_WINDOW_MS = 2000; // 2 seconds
    
    public TeamSpamCommand() {
        super("tspam");
    }
    
    /**
     * Get the current delay setting for team invites
     * @return delay in milliseconds
     */
    public static int getDelay() {
        return delay.get();
    }
    
    /**
     * Check if the last invite was from the spammer and the response is within the time window.
     * This helps distinguish spammer invites from manual invites.
     * @return true if the last invite was from spammer and within response window
     */
    public static boolean isLastInviteFromSpammer() {
        InviteAttempt attempt=pendingInvite;
        long age=attempt==null?-1:System.currentTimeMillis()-attempt.sentAt;
        return attempt!=null&&isInviteAttemptCurrent(attempt)&&age>=0&&age<=INVITE_RESPONSE_WINDOW_MS;
    }

    public static boolean isInviteAttemptCurrent(InviteAttempt attempt) {
        Minecraft mc=Minecraft.getMinecraft();
        return attempt!=null&&running.get()&&attempt.generation==inviteGeneration.get()
                &&mc!=null&&mc.theWorld==attempt.world&&mc.thePlayer==attempt.player&&mc.getNetHandler()==attempt.connection;
    }
    public static synchronized InviteAttempt claimInviteResponse(String expectedName) {
        InviteAttempt attempt=pendingInvite;
        if(!isLastInviteFromSpammer()||(expectedName!=null&&!attempt.realName.equalsIgnoreCase(expectedName)))return null;
        pendingInvite=null;lastInvitedPlayer="";lastSpammerInviteTime=0;
        return attempt;
    }
    private static synchronized InviteAttempt prepareAutomatedInvite(String target,int generation,Minecraft mc) {
        if(!running.get()||generation!=inviteGeneration.get()||mc==null||mc.thePlayer==null)return null;
        if(pendingInvite!=null)return null; // Backlogged client-thread tasks cannot replace an unanswered request.
        InviteAttempt attempt=new InviteAttempt(target,generation,mc);
        pendingInvite=attempt;lastInvitedPlayer=target;lastSpammerInviteTime=attempt.sentAt;
        return attempt;
    }
    private static String inviteTarget(String message) {
        if(message==null)return "";
        String[] args=message.trim().split("\\s+");
        if(args.length<2||!args[0].equalsIgnoreCase("/team"))return "";
        String target=args.length==3&&args[1].equalsIgnoreCase("invite")?args[2]:args.length==2?args[1]:"";
        if(!target.matches("[A-Za-z0-9_]{1,16}"))return "";
        if(args.length==2&&java.util.Arrays.asList("invite","accept","deny","decline","leave","disband","list","help","chat","kick","remove","join").contains(target.toLowerCase(java.util.Locale.ROOT)))return "";
        return target;
    }
    /** Packet identity survives delayed/cross-thread delivery; a matching command string alone is insufficient. */
    public static void captureInvitePacket(Object packet,String message) {
        InviteAttempt scope=automatedInviteScope.get();
        if(scope==null||!scope.realName.equalsIgnoreCase(inviteTarget(message)))return;
        synchronized(automatedPackets){if(automatedPackets.size()>=64)automatedPackets.clear();automatedPackets.put(packet,scope);}
    }
    public static boolean interceptInvitePacket(net.minecraft.network.Packet<?> packet) {
        if(!(packet instanceof net.minecraft.network.play.client.C01PacketChatMessage))return false;
        InviteAttempt origin;
        synchronized(automatedPackets){origin=automatedPackets.get(packet);}
        if(origin!=null)return !isInviteAttemptCurrent(origin);
        onOutgoingSendChatMessageHead(((net.minecraft.network.play.client.C01PacketChatMessage)packet).getMessage());
        return false;
    }

    /**
     * Called from {@link me.napim.gomod.mixins.EntityPlayerSPMixin} at the start of every outgoing chat line.
     * A manual invite stops this run and invalidates pending/queued work. Merely clearing the last name
     * is insufficient: the next scheduled probe could otherwise steal the manual command's response.
     */
    public static void onOutgoingSendChatMessageHead(String message) {
        String target=inviteTarget(message);
        if(!running.get()||target.isEmpty())return;
        InviteAttempt scope=automatedInviteScope.get();
        if(scope!=null&&scope.realName.equalsIgnoreCase(target))return;
        final Minecraft mc=Minecraft.getMinecraft();
        final Object player=mc==null?null:mc.thePlayer;
        stopSilent();
        manualInviteCooldownUntil=System.currentTimeMillis()+5000L;
        if(mc!=null)mc.addScheduledTask(()->{
            if(mc.thePlayer==player&&mc.thePlayer!=null)mc.thePlayer.addChatMessage(new ChatComponentText(
                    Main.CHAT_PREFIX+EnumChatFormatting.YELLOW+"Manual team invite: stopped Teams Nick Finder to avoid incorrect nicknames."));
        });
    }

    // Public accessor for GUI toggle state
    public static boolean isRunning() {
        return running.get();
    }

    /**
     * Stops the team spammer silently (no chat message). Used for auto-stop on game start.
     */
    public static synchronized void stopSilent() {
        if (!running.get()) return;
        running.set(false);
        inviteGeneration.incrementAndGet();
        pendingInvite=null;
        if (currentTask != null && !currentTask.isDone() && !currentTask.isCancelled()) {
            currentTask.cancel(false);
            currentTask = null;
        }
        scheduler.shutdown();
        scheduler = newDaemonScheduler();
        isProcessing.set(false);
        count.set(0);
        lastInvitedPlayer = "";
        lastFoundPlayer = "";
        lastSpammerInviteTime = 0;
        automatedInviteScope.remove();
    }

    /**
     * Stops team spam with a chat notice when returning to a Hypixel lobby from Blitz pregame (e.g. /hub).
     */
    public static void stopForLobbyReturn() {
        if (!running.get()) {
            return;
        }
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(
                    Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Returned to lobby - stopping team spam."));
        }
        stopSilent();
    }
    
    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            toggleTeamSpam();
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "add":
                if (args.length > 1) {
                    final String usernameToAdd = args[1];
                    sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Adding player " + usernameToAdd + "...");
                    startDaemonThread("GoMod-TeamSpam-Add", () -> {
                        String result = TeamSpamManager.addPlayer(usernameToAdd);
                        sendMessage(result);
                    });
                } else {
                    sendMessage(EnumChatFormatting.RED + "Usage: /tspam add <username>");
                }
                break;
                
            case "remove":
                if (args.length > 1) {
                    String result = TeamSpamManager.removePlayer(args[1]);
                    sendMessage(result);
                } else {
                    sendMessage(EnumChatFormatting.RED + "Usage: /tspam remove <username>");
                }
                break;
                
            case "players":
                showPlayers();
                break;
                
            case "confirm":
                if (TeamSpamManager.isUsingResourceFile()) {
                    sendMessage(EnumChatFormatting.GREEN + "Confirmed using the default player list.");
                    TeamSpamManager.savePlayers();
                } else {
                    sendMessage(EnumChatFormatting.RED + "You're already using your own player list. No confirmation needed.");
                }
                break;
                
            case "clear":
                TeamSpamManager.clearPlayers();
                sendMessage(EnumChatFormatting.GREEN + "Cleared all players from team spam list.");
                break;
                
            case "refresh":
                refreshPlayers();
                break;
            case "refreshrank":
                refreshPlayersRankOnly();
                break;
                
            default:
                sendMessage(EnumChatFormatting.RED + "Unknown subcommand: " + subCommand);
                sendMessage(EnumChatFormatting.YELLOW + "Available commands: add, remove, players, confirm, clear, refresh");
        }
    }
    
    private void toggleTeamSpam() {
        if (running.get()) {
            // Stop the spam
            stopTeamSpam();
        } else {
            // Start the spam
            startTeamSpam();
        }
    }
    
    private void stopTeamSpam() {
        stopSilent();
        
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Team spam stopped.");
    }
    
    private void startTeamSpam() {
        Minecraft mc = Minecraft.getMinecraft();
        if(System.currentTimeMillis()<manualInviteCooldownUntil){
            sendMessage(Main.CHAT_PREFIX+EnumChatFormatting.YELLOW+"Wait a few seconds for your manual team invite reply before restarting Teams Nick Finder.");
            return;
        }
        // Only on the Blitz Teams map pregame — not the Hypixel games hub Blitz queue lobby.
        if (!BlitzTeamsDetector.isBlitzTeamsMapPregame(mc)) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Team spam can only start in Blitz Teams map pregame.");
            return;
        }

        // First make sure any previous spam is properly stopped
        if (running.get()) {
            stopTeamSpam();
        }
        
        List<Player> allPlayers = TeamSpamManager.getPlayers();
        
        // Filter only MVP++ players
        List<Player> mvpPlusPlusPlayers = new ArrayList<>();
        for (Player player : allPlayers) {
            if (player.hasMVPPlusPlus()) {
                mvpPlusPlusPlayers.add(player);
            }
        }
        
        if (mvpPlusPlusPlayers.isEmpty()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No MVP++ players in team spam list. Add MVP++ players with /tspam add <username>");
            return;
        }
        
        // Sort players by priority: most recently found first, then most recently added, then MVP++ first, then alphabetically
        Collections.sort(mvpPlusPlusPlayers, (p1, p2) -> {
            // Primary: most recently found first
            long found1 = p1.getLastFoundTimestamp();
            long found2 = p2.getLastFoundTimestamp();
            boolean p1HasFound = found1 > 0;
            boolean p2HasFound = found2 > 0;

            // Players with a lastFoundTimestamp appear before those without
            if (p1HasFound && !p2HasFound) return -1;
            if (!p1HasFound && p2HasFound) return 1;

            // If both have found timestamps, newest first
            if (p1HasFound && p2HasFound && found1 != found2) {
                return Long.compare(found2, found1);
            }

            // Secondary: most recently added first among players with same found status
            long added1 = p1.getAddedTimestamp();
            long added2 = p2.getAddedTimestamp();
            int addedCompare = Long.compare(added2, added1);
            if (addedCompare != 0) return addedCompare;

            // Tertiary: MVP++ first (though all should be MVP++ already)
            if (p1.hasMVPPlusPlus() && !p2.hasMVPPlusPlus()) return -1;
            if (!p1.hasMVPPlusPlus() && p2.hasMVPPlusPlus()) return 1;

            // Quaternary: alphabetical by username
            return p1.getUsername().compareToIgnoreCase(p2.getUsername());
        });
        
        // Debug: Log the initial sorted order
        System.out.println("[TeamSpamCommand] Initial sorted player order:");
        for (int i = 0; i < Math.min(5, mvpPlusPlusPlayers.size()); i++) {
            Player p = mvpPlusPlusPlayers.get(i);
            System.out.println("  " + (i+1) + ". " + p.getUsername() + " (found: " + p.getLastFoundTimestamp() + ")");
        }
        
        // Removed verbose notice about prioritizing recently found player
        
        inviteGeneration.incrementAndGet();
        running.set(true);
        count.set(0);
        
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Starting team spam with " + mvpPlusPlusPlayers.size() + " MVP++ players...");
        
        // Schedule the spam task to invite all players once, then stop
        scheduleNextInvite();
    }
    
    private void scheduleNextInvite() {
        final int generation=inviteGeneration.get();
        if (!running.get()) {
            return;  // Stop if running was set to false
        }
        InviteAttempt outstanding=pendingInvite;
        if(outstanding!=null){
            if(!isLastInviteFromSpammer()){
                stopSilent();
                Minecraft client=Minecraft.getMinecraft();
                if(client!=null)client.addScheduledTask(()->sendMessage(Main.CHAT_PREFIX+EnumChatFormatting.YELLOW+"Teams Nick Finder stopped: no unambiguous reply to the last invite."));
                return;
            }
            currentTask=scheduler.schedule(()->{
                if(running.get()&&generation==inviteGeneration.get())scheduleNextInvite();
            },50,TimeUnit.MILLISECONDS);
            return;
        }
        
        // Check if we're already processing an invite
        if (isProcessing.get()) {
            return;  // Skip if we're still processing the previous invite
        }
        
        isProcessing.set(true);  // Set lock
        
        try {
            // Get a fresh list of MVP++ players in case it's been updated
            List<Player> currentPlayers = new ArrayList<>();
            for (Player player : TeamSpamManager.getPlayers()) {
                if (player.hasMVPPlusPlus()) {
                    currentPlayers.add(player);
                }
            }
            
            // Sort players by priority: most recently found first, then most recently added, then MVP++ first, then alphabetically
            Collections.sort(currentPlayers, (p1, p2) -> {
                // Primary: most recently found first
                long found1 = p1.getLastFoundTimestamp();
                long found2 = p2.getLastFoundTimestamp();
                boolean p1HasFound = found1 > 0;
                boolean p2HasFound = found2 > 0;

                // Players with a lastFoundTimestamp appear before those without
                if (p1HasFound && !p2HasFound) return -1;
                if (!p1HasFound && p2HasFound) return 1;

                // If both have found timestamps, newest first
                if (p1HasFound && p2HasFound && found1 != found2) {
                    return Long.compare(found2, found1);
                }

                // Secondary: most recently added first among players with same found status
                long added1 = p1.getAddedTimestamp();
                long added2 = p2.getAddedTimestamp();
                int addedCompare = Long.compare(added2, added1);
                if (addedCompare != 0) return addedCompare;

                // Tertiary: MVP++ first (though all should be MVP++ already)
                if (p1.hasMVPPlusPlus() && !p2.hasMVPPlusPlus()) return -1;
                if (!p1.hasMVPPlusPlus() && p2.hasMVPPlusPlus()) return 1;

                // Quaternary: alphabetical by username
                return p1.getUsername().compareToIgnoreCase(p2.getUsername());
            });
            
            // Debug: Log the sorted order
            System.out.println("[TeamSpamCommand] Sorted player order:");
            for (int i = 0; i < Math.min(5, currentPlayers.size()); i++) {
                Player p = currentPlayers.get(i);
                System.out.println("  " + (i+1) + ". " + p.getUsername() + " (found: " + p.getLastFoundTimestamp() + ")");
            }
            
            if (currentPlayers.isEmpty()) {
                stopTeamSpam();
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No MVP++ players left in team spam list. Stopping.");
                return;
            }
            
            int currentIndex = count.get();
            if (currentIndex >= currentPlayers.size()) {
                // We've invited all players once, stop the spam
                stopTeamSpam();
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Finished inviting all " + currentPlayers.size() + " MVP++ players. Stopping.");
                return;
            }
            
            Player player;
            synchronized(TeamSpamCommand.class){
                if(!running.get()||generation!=inviteGeneration.get())return;
                player=currentPlayers.get(currentIndex);
                count.incrementAndGet();
            }
            
            // Check if player is already in the lobby (unnicked)
            if (isPlayerInLobby(player.getUsername())) {
                System.out.println("[TeamSpam] Skipping " + player.getUsername() + " - already in lobby");
                // Skip this player and move to next immediately
                isProcessing.set(false);
                scheduleNextInvite();
                return;
            }
            
            final Minecraft mc = Minecraft.getMinecraft();
            final String inviteTarget = player.getUsername();
            if (mc == null || mc.thePlayer == null) {
                isProcessing.set(false);
                return;
            }
            // Publish ownership on the client thread; old run generations cannot send after a manual invite.
            mc.addScheduledTask(() -> {
                InviteAttempt attempt=prepareAutomatedInvite(inviteTarget,generation,mc);
                if(attempt==null)return;
                automatedInviteScope.set(attempt);
                try {
                    mc.thePlayer.sendChatMessage("/team invite " + inviteTarget);
                } finally {
                    automatedInviteScope.remove();
                }
            });
            
            // DO NOT update timestamp here - wait for confirmation from TeamInviteResponseHandler
            // that the player is actually online before marking them as "found"
            
            // Schedule the next invite after the delay
            currentTask = scheduler.schedule(() -> {
                if(!running.get()||generation!=inviteGeneration.get())return;
                isProcessing.set(false);  // Release lock
                scheduleNextInvite();  // Schedule next invite
            }, delay.get(), TimeUnit.MILLISECONDS);
            
        } catch (Exception e) {
            System.err.println("Error in team spam task: " + e.getMessage());
            e.printStackTrace();
            isProcessing.set(false);  // Release lock
        }
    }
    
    private void showPlayers() {
        List<Player> players = TeamSpamManager.getPlayers();
        
        if (players.isEmpty()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No players in team spam list. Add players with /tspam add <username>");
            return;
        }
        
        if (TeamSpamManager.isUsingResourceFile()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Using default player list. Type /tspam confirm to save this list as your own.");
        }
        
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Players in team spam list (" + players.size() + "):");
        
        // Sort players: MVP++ first, then alphabetically
        List<Player> sortedPlayers = new ArrayList<>(players);
        Collections.sort(sortedPlayers, (p1, p2) -> {
            // First sort by MVP++ status
            if (p1.hasMVPPlusPlus() && !p2.hasMVPPlusPlus()) {
                return -1;
            } else if (!p1.hasMVPPlusPlus() && p2.hasMVPPlusPlus()) {
                return 1;
            }
            // Then sort alphabetically
            return p1.getUsername().compareToIgnoreCase(p2.getUsername());
        });
        
        // Display players one per line with their added date
        for (int i = 0; i < sortedPlayers.size(); i++) {
            Player player = sortedPlayers.get(i);
            StringBuilder entry = new StringBuilder();
            entry.append((i + 1)).append(". ");
            
            if (player.hasMVPPlusPlus()) {
                entry.append(EnumChatFormatting.GOLD).append(player.getUsername())
                     .append(EnumChatFormatting.YELLOW).append(" [MVP++]");
            } else {
                entry.append(EnumChatFormatting.WHITE).append(player.getUsername());
            }
            
            // Add the date when the player was added
            String dateAdded = DATE_FORMAT.format(new Date(player.getAddedTimestamp()));
            entry.append(EnumChatFormatting.GRAY).append(" (Added: ").append(dateAdded).append(")");
            
            sendMessage(entry.toString());
        }
    }
    
    private void refreshPlayers() {
        // Enforce a 10-minute cooldown between refreshes
        if (!RefreshCooldown.allow()) {
            long waitMs = RefreshCooldown.timeRemainingMs();
            long mins = waitMs / 60000;
            long secs = (waitMs % 60000) / 1000;
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Refresh is on cooldown. Try again in " + mins + "m " + secs + "s.");
            return;
        }

        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Refreshing player list...");
        
        // Run the refresh in a separate thread to avoid blocking the game
        startDaemonThread("GoMod-TeamSpam-Refresh", () -> {
            try {
                String result = TeamSpamManager.checkAllPlayersMVPPlusPlus();
                sendMessage(Main.CHAT_PREFIX + result);
            } catch (Exception e) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Error refreshing players: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void refreshPlayersRankOnly() {
        // Enforce a 10-minute cooldown between refreshes
        if (!RefreshCooldown.allow()) {
            long waitMs = RefreshCooldown.timeRemainingMs();
            long mins = waitMs / 60000;
            long secs = (waitMs % 60000) / 1000;
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Refresh is on cooldown. Try again in " + mins + "m " + secs + "s.");
            return;
        }

        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Refreshing player ranks (Hypixel only)...");
        startDaemonThread("GoMod-TeamSpam-RefreshRank", () -> {
            try {
                String result = TeamSpamManager.refreshAllRanksOnly();
                sendMessage(Main.CHAT_PREFIX + result);
            } catch (Exception e) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Error refreshing players: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // Simple in-process cooldown helper
    public static class RefreshCooldownPublic {
        public static boolean allow() { return RefreshCooldown.allow(); }
        public static long timeRemainingMs() { return RefreshCooldown.timeRemainingMs(); }
    }

    private static class RefreshCooldown {
        private static final long COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes
        private static long lastRefreshAt = 0L;

        static synchronized boolean allow() {
            long now = System.currentTimeMillis();
            if (now - lastRefreshAt >= COOLDOWN_MS) {
                lastRefreshAt = now;
                return true;
            }
            return false;
        }

        static synchronized long timeRemainingMs() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefreshAt;
            return elapsed >= COOLDOWN_MS ? 0L : (COOLDOWN_MS - elapsed);
        }
    }
    
    /**
     * Check if a player is already in the current lobby/game (unnicked)
     * @param username The player's real username
     * @return true if the player is in the lobby, false otherwise
     */
    private static boolean isPlayerInLobby(String username) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null || mc.getNetHandler().getPlayerInfoMap() == null) {
                return false;
            }
            
            // Check if the player is in the tab list with their real username
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null && info.getGameProfile() != null) {
                    String tabName = info.getGameProfile().getName();
                    if (tabName != null && tabName.equalsIgnoreCase(username)) {
                        return true; // Player is in lobby with real name (unnicked)
                    }
                }
            }
        } catch (Exception e) {
            // Silently handle errors
            System.err.println("[TeamSpam] Error checking lobby: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Chat event handler to detect game start and stop team spam automatically
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
            
            // Check for game start message
            if (message != null && message.replaceAll("\\s+", " ").trim().equals("Survive while eliminating")) {
                // Game has started, stop team spam
                System.out.println("[TeamSpam] Game started, stopping team spam automatically.");
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Game started - stopping team spam.");
                stopTeamSpam();
            }
        } catch (Exception e) {
            // Silently handle errors
        }
    }
    
    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
