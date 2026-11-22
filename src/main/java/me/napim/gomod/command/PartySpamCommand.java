package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Command to repeatedly send party invites to a player and then disband.
 * This is useful for party spam scenarios.
 */
public class PartySpamCommand extends Command {
    private static ScheduledExecutorService newDaemonScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "GoMod-PartySpam");
            t.setDaemon(true);
            return t;
        });
    }
    private static ScheduledExecutorService scheduler = newDaemonScheduler();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final int DELAY = 210; // ms between party commands
    private static ScheduledFuture<?> currentTask = null;
    private static final Object LOCK = new Object();
    private static boolean invitePhase = true;
    private static int targetIndex = 0;

    // Lowercase name -> display name. Linked order keeps multi-player spam predictable.
    private static final Map<String, String> activeSpamTargets = new LinkedHashMap<>();
    
    public PartySpamCommand() {
        super("pspam");
    }
    
    @Override
    public void handle(String[] args) {
        if (args.length < 1) {
            sendMessage(EnumChatFormatting.RED + "Usage: /pspam <player> [player2 ...]");
            return;
        }

        List<String> started = new ArrayList<>();
        List<String> stopped = new ArrayList<>();

        synchronized (LOCK) {
            for (String arg : args) {
                String playerName = arg == null ? "" : arg.trim();
                if (playerName.isEmpty()) {
                    continue;
                }
                String key = playerName.toLowerCase();
                if (activeSpamTargets.containsKey(key)) {
                    stopped.add(activeSpamTargets.remove(key));
                    if (targetIndex >= activeSpamTargets.size()) {
                        targetIndex = 0;
                    }
                } else {
                    activeSpamTargets.put(key, playerName);
                    started.add(playerName);
                }
            }

            if (activeSpamTargets.isEmpty()) {
                stopPartySpamLocked();
            } else {
                startPartySpamLocked();
            }
        }

        if (!started.isEmpty()) {
            sendMessage(EnumChatFormatting.GREEN + "Started party spam for "
                    + EnumChatFormatting.GOLD + joinNames(started));
        }
        if (!stopped.isEmpty()) {
            sendMessage(EnumChatFormatting.RED + "Stopped party spam for "
                    + EnumChatFormatting.GOLD + joinNames(stopped));
        }
    }
    
    /**
     * Stops the party spam. Must be called with {@link #LOCK} held.
     */
    private void stopPartySpamLocked() {
        running.set(false);
        
        // Cancel the current task if it exists
        if (currentTask != null && !currentTask.isDone() && !currentTask.isCancelled()) {
            currentTask.cancel(false);
            currentTask = null;
        }
        
        // Reset the scheduler to ensure a clean state
        scheduler.shutdown();
        scheduler = newDaemonScheduler();
        invitePhase = true;
        targetIndex = 0;
    }
    
    /**
     * Starts party spam if it is not already running. Must be called with {@link #LOCK} held.
     */
    private void startPartySpamLocked() {
        if (running.get()) {
            return;
        }

        if (scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = newDaemonScheduler();
        }

        running.set(true);
        invitePhase = true;
        
        // Schedule the spam task
        currentTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!running.get()) {
                    return;
                }

                String target = null;
                boolean sendInvite;
                synchronized (LOCK) {
                    if (activeSpamTargets.isEmpty()) {
                        stopPartySpamLocked();
                        return;
                    }
                    if (targetIndex >= activeSpamTargets.size()) {
                        targetIndex = 0;
                    }
                    List<String> targets = new ArrayList<>(activeSpamTargets.values());
                    target = targets.get(targetIndex);
                    sendInvite = invitePhase;
                    if (invitePhase) {
                        invitePhase = false;
                    } else {
                        invitePhase = true;
                        targetIndex = (targetIndex + 1) % targets.size();
                    }
                }
                
                if (sendInvite) {
                    // Send party invite
                    Minecraft.getMinecraft().thePlayer.sendChatMessage("/p invite " + target);
                } else {
                    // Disband party
                    Minecraft.getMinecraft().thePlayer.sendChatMessage("/p disband");
                }
            } catch (Exception e) {
                System.err.println("Error in party spam task: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, DELAY, TimeUnit.MILLISECONDS);
    }

    private String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                joined.append(EnumChatFormatting.GRAY).append(", ").append(EnumChatFormatting.GOLD);
            }
            joined.append(names.get(i));
        }
        return joined.toString();
    }
    
    /**
     * Sends a message to the player
     */
    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
