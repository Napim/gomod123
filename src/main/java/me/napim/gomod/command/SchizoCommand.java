package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.SchizoMode;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command to toggle Schizo mode.
 * Usage: /schizo [on|off|status|help]
 */
public class SchizoCommand extends Command {
    public SchizoCommand() {
        super("schizo");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            SchizoMode.setEnabled(!SchizoMode.isEnabled());
            return;
        }

        String arg = args[0];
        if (arg.equalsIgnoreCase("on") || arg.equalsIgnoreCase("enable")) {
            SchizoMode.setEnabled(true);
        } else if (arg.equalsIgnoreCase("off") || arg.equalsIgnoreCase("disable")) {
            SchizoMode.setEnabled(false);
        } else if (arg.equalsIgnoreCase("status")) {
            String status = SchizoMode.isEnabled()
                    ? EnumChatFormatting.GREEN + "enabled"
                    : EnumChatFormatting.RED + "disabled";
            Main.sendMessage(Main.CHAT_PREFIX + "Schizo is currently " + status);
        } else if (arg.equalsIgnoreCase("help")) {
            showHelp();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid argument. Use /schizo help for usage.");
        }
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Schizo Command:");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /schizo" + EnumChatFormatting.WHITE + " - Toggle schizo mode");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /schizo on" + EnumChatFormatting.WHITE + " - Enable schizo mode");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /schizo off" + EnumChatFormatting.WHITE + " - Disable schizo mode");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /schizo status" + EnumChatFormatting.WHITE + " - Show current status");
        Main.sendMessage(EnumChatFormatting.YELLOW + "  /schizo help" + EnumChatFormatting.WHITE + " - Show this help message");
        Main.sendMessage(EnumChatFormatting.GRAY + "When enabled, rendered players face you.");
    }
}
