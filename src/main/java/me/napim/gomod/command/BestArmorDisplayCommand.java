package me.napim.gomod.command;

import me.napim.gomod.features.BestArmorDisplay;
import me.napim.gomod.Main;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;

/**
 * Command class for managing the Best Armor Display feature
 * Shows recommended armor pieces in the inventory GUI
 */
public class BestArmorDisplayCommand extends Command {
    
    public BestArmorDisplayCommand() {
        super("bestarmor");
    }
    
    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            // Toggle the feature
            boolean newState = !BestArmorDisplay.isEnabled();
            BestArmorDisplay.toggle(newState);
        } else if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) {
            // Enable the feature
            BestArmorDisplay.toggle(true);
        } else if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
            // Disable the feature
            BestArmorDisplay.toggle(false);
        } else if (args[0].equalsIgnoreCase("help")) {
            // Show help
            showHelp();
        } else {
            // Invalid argument
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid argument. Use /bestarmor help for usage.");
        }
    }
    
    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Best Armor Display Command:");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /bestarmor" + EnumChatFormatting.WHITE + " - Toggle the best armor display");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /bestarmor on" + EnumChatFormatting.WHITE + " - Enable the display");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /bestarmor off" + EnumChatFormatting.WHITE + " - Disable the display");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /bestarmor help" + EnumChatFormatting.WHITE + " - Show this help message");
        Main.sendMessage(EnumChatFormatting.GRAY + "Container hook / z-order (Lunar etc.): " + EnumChatFormatting.WHITE + "/fixbestarmor help");
        Main.sendMessage(EnumChatFormatting.GRAY + "When enabled, recommended armor pieces appear in your inventory GUI.");
    }
}

