package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import me.napim.gomod.Main;
import me.napim.gomod.features.UnknownNickFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Command to control the Unknown Nick Finder feature.
 * Searches for unknown player nicknames by trying character combinations.
 */
public class UnknownNickFinderCommand extends Command {
    
    public UnknownNickFinderCommand() {
        super("unickfind");
    }
    
    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            showHelp();
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "start":
                // Always use default settings (TeamSpam delay, 16 char max)
                UnknownNickFinder.start();
                break;
                
            case "stop":
                UnknownNickFinder.stop();
                break;
                
            case "status":
                showStatus();
                break;
                
            case "help":
                showHelp();
                break;
                
            default:
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown subcommand: " + subCommand);
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Use " + EnumChatFormatting.WHITE + "/unickfind help" + EnumChatFormatting.YELLOW + " for usage.");
        }
    }
    
    private void showHelp() {
        sendMessage(EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "=== Unknown Nick Finder ===");
        sendMessage(EnumChatFormatting.YELLOW + "Brute-force searches for player nicknames by trying combinations.");
        sendMessage("");
        sendMessage(EnumChatFormatting.GREEN + "/unickfind start" + EnumChatFormatting.GRAY + " - Start searching (TeamSpam delay, up to 16 chars)");
        sendMessage(EnumChatFormatting.GREEN + "/unickfind stop" + EnumChatFormatting.GRAY + " - Stop the current search");
        sendMessage(EnumChatFormatting.GREEN + "/unickfind status" + EnumChatFormatting.GRAY + " - Show current search progress");
        sendMessage("");
        sendMessage(EnumChatFormatting.YELLOW + "How it works:");
        sendMessage(EnumChatFormatting.GRAY + "• Tries character combinations in smart order (e, a, r, i, o, t...)");
        sendMessage(EnumChatFormatting.GRAY + "• Uses same timing as TeamSpam invites (safe!)");
        sendMessage(EnumChatFormatting.GRAY + "• Automatically goes deeper when it finds matches");
        sendMessage(EnumChatFormatting.GRAY + "• Stops when complete username is found");
    }
    
    private void showStatus() {
        if (!UnknownNickFinder.isRunning()) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown Nick Finder is not running.");
            String lastFound = UnknownNickFinder.getFoundNickname();
            if (lastFound != null) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Last found: " + 
                           EnumChatFormatting.GOLD + lastFound);
            }
            return;
        }
        
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Unknown Nick Finder is running!");
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Current: " + 
                   EnumChatFormatting.WHITE + UnknownNickFinder.getProgress());
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Total attempts: " + 
                   EnumChatFormatting.WHITE + UnknownNickFinder.getTotalAttempts());
        
        String found = UnknownNickFinder.getFoundNickname();
        if (found != null) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Found so far: " + 
                       EnumChatFormatting.GOLD + found);
        }
    }
    
    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}

