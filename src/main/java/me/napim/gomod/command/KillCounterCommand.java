package me.napim.gomod.command;

import me.napim.gomod.features.KillCounter;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.*;

/**
 * Command handler for /startkc command.
 */
public class KillCounterCommand extends Command {
    
    public KillCounterCommand() {
        super("startkc");
    }
    
    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "reset":
                    handleReset();
                    break;
                case "status":
                    handleStatus();
                    break;
                case "test":
                    handleTest();
                    break;
                case "scankits":
                    handleScanKits();
                    break;
                case "kitdebug":
                    handleKitDebug();
                    break;
                default:
                    handleStart();
                    break;
            }
        } else {
            handleStart();
        }
    }
    
    /**
     * Handles the /startkc command.
     */
    private void handleStart() {
        if (KillCounter.isEnabled()) {
            sendPrefixedMessage(EnumChatFormatting.YELLOW + "Kill counter is already running!");
            return;
        }
        
        KillCounter.start();
        sendPrefixedMessage(EnumChatFormatting.GREEN + "Kill counter started! Tracking kills in the top-left corner.");
    }
    
    /**
     * Handles the /stopkc command.
     */
    private void handleStop() {
        if (!KillCounter.isEnabled()) {
            sendPrefixedMessage(EnumChatFormatting.YELLOW + "Kill counter is not running!");
            return;
        }
        
        KillCounter.stop();
        sendPrefixedMessage(EnumChatFormatting.RED + "Kill counter stopped!");
    }
    
    /**
     * Handles the reset command.
     */
    private void handleReset() {
        KillCounter.reset();
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "Kill counter reset! All kill counts cleared.");
    }
    
    /**
     * Handles the status command.
     */
    private void handleStatus() {
        if (!KillCounter.isEnabled()) {
            sendPrefixedMessage(EnumChatFormatting.RED + "Kill counter is not running.");
            return;
        }
        
        Map<String, Integer> killCounts = KillCounter.getAllKillCounts();
        if (killCounts.isEmpty()) {
            sendPrefixedMessage(EnumChatFormatting.YELLOW + "Kill counter is running but no kills recorded yet.");
            return;
        }
        
        sendPrefixedMessage(EnumChatFormatting.GREEN + "Kill counter is running. Current kill counts:");
        
        // Sort by kill count (descending)
        List<Map.Entry<String, Integer>> sortedKills = new ArrayList<>(killCounts.entrySet());
        sortedKills.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        
        for (Map.Entry<String, Integer> entry : sortedKills) {
            String playerName = entry.getKey();
            int kills = entry.getValue();
            sendPrefixedMessage(EnumChatFormatting.WHITE + "  " + playerName + ": " + 
                    EnumChatFormatting.AQUA + kills + EnumChatFormatting.GRAY + " kills");
            
            // Show victims for this player
            List<String> victims = KillCounter.getPlayerVictims(playerName);
            if (!victims.isEmpty()) {
                String victimList = String.join(", ", victims);
                sendPrefixedMessage(EnumChatFormatting.GRAY + "    Victims: " + victimList);
            }
        }
    }
    
    /**
     * Handles the test command.
     */
    private void handleTest() {
        sendPrefixedMessage(EnumChatFormatting.GOLD + "Testing kit detection functionality...");
        KillCounter.testKitDetection();
        sendPrefixedMessage(EnumChatFormatting.GREEN + "Kit regex self-test finished (see console if you enabled JVM logging).");
        sendPrefixedMessage(EnumChatFormatting.GRAY + "In-game data: " + EnumChatFormatting.YELLOW + "/startkc kitdebug");
    }
    
    /** Prints {@link KillCounter#buildEnemyKitDebugLines()} to chat for copy/paste. */
    private void handleKitDebug() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        for (String line : KillCounter.buildEnemyKitDebugLines()) {
            mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }

    /**
     * Handles the scankits command.
     */
    private void handleScanKits() {
        boolean enemyKitsEnabled = KillCounter.isShowEnemyKits();
        if (!enemyKitsEnabled) {
            KillCounter.setShowEnemyKits(true);
            sendPrefixedMessage(EnumChatFormatting.YELLOW + "Enemy kit display was disabled, enabling it so scans can show results.");
        }
        sendPrefixedMessage(EnumChatFormatting.GOLD + "Manually scanning for enemy kits...");
        KillCounter.manualKitScan();
        sendPrefixedMessage(EnumChatFormatting.GREEN + "Kit scan completed.");
        sendPrefixedMessage(EnumChatFormatting.GRAY + "Pasteable report: " + EnumChatFormatting.YELLOW + "/startkc kitdebug");
    }
    
    /**
     * Sends the help message.
     */
    private void sendHelpMessage() {
        sendPrefixedMessage(EnumChatFormatting.GOLD + "=== TAB QoL Commands ===");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc - Start tracking kills");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/stopkc - Stop tracking kills");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc reset - Reset kill counts");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc status - Show current kill counts");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc test - Test kit detection functionality");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc scankits - Manually scan for enemy kits");
        sendPrefixedMessage(EnumChatFormatting.YELLOW + "/startkc kitdebug - Pasteable debug (settings + nearby item names)");
    }
    
    private void sendPrefixedMessage(String message) {
        Main.sendMessage(Main.CHAT_PREFIX + message);
    }
}
