package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BorderIndication;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command to toggle the BorderIndication feature and configure settings.
 */
public class BorderIndicationCommand extends Command {
    public BorderIndicationCommand() {
        super("borderindicator", "borderind", "bi");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            BorderIndication.toggle();
            boolean on = BorderIndication.isEnabled();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Border Indicator " + 
                (on ? (EnumChatFormatting.GREEN + "enabled") : (EnumChatFormatting.RED + "disabled")) + 
                EnumChatFormatting.RESET);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "toggle":
                BorderIndication.toggle();
                boolean on2 = BorderIndication.isEnabled();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Border Indicator " + 
                    (on2 ? (EnumChatFormatting.GREEN + "enabled") : (EnumChatFormatting.RED + "disabled")) + 
                    EnumChatFormatting.RESET);
                break;
            
            case "opacity":
                if (args.length < 2) {
                    float currentOpacity = BorderIndication.getOpacity();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Current opacity: " + 
                        EnumChatFormatting.WHITE + String.format("%.2f", currentOpacity) + 
                        EnumChatFormatting.GRAY + " (0.0-1.0)");
                    return;
                }
                try {
                    float opacity = Float.parseFloat(args[1]);
                    BorderIndication.setOpacity(opacity);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Border indicator opacity set to: " + 
                        EnumChatFormatting.WHITE + String.format("%.2f", opacity));
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid opacity value. Use a number between 0.0 and 1.0");
                }
                break;
            
            case "distance":
            case "range":
                if (args.length < 2) {
                    int currentDistance = BorderIndication.getRenderDistance();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Current render distance: " + 
                        EnumChatFormatting.WHITE + currentDistance + 
                        EnumChatFormatting.GRAY + " blocks (16-128)");
                    return;
                }
                try {
                    int distance = Integer.parseInt(args[1]);
                    BorderIndication.setRenderDistance(distance);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Border indicator render distance set to: " + 
                        EnumChatFormatting.WHITE + distance + EnumChatFormatting.GRAY + " blocks");
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid distance value. Use a number between 16 and 128");
                }
                break;
            
            case "help":
            default:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Border Indicator Commands:");
                Main.sendMessage(EnumChatFormatting.YELLOW + "  /borderindicator" + EnumChatFormatting.WHITE + " - Toggle border indicator");
                Main.sendMessage(EnumChatFormatting.YELLOW + "  /borderindicator toggle" + EnumChatFormatting.WHITE + " - Toggle border indicator");
                Main.sendMessage(EnumChatFormatting.YELLOW + "  /borderindicator opacity <0.0-1.0>" + EnumChatFormatting.WHITE + " - Set overlay opacity");
                Main.sendMessage(EnumChatFormatting.YELLOW + "  /borderindicator distance <16-128>" + EnumChatFormatting.WHITE + " - Set render distance");
                Main.sendMessage(EnumChatFormatting.YELLOW + "  /borderindicator help" + EnumChatFormatting.WHITE + " - Show this help");
                Main.sendMessage(EnumChatFormatting.GRAY + "Aliases: /borderind, /bi");
        }
    }
}

