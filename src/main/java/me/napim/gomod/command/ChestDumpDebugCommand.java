package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestDumpLogger;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class ChestDumpDebugCommand extends Command {
    public ChestDumpDebugCommand() {
        super("chestdumpdebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            ChestDumpLogger.Kind kind = ChestDumpLogger.Kind.VAULT;
            if (args.length == 0) {
                boolean enabled = ChestDumpLogger.toggleFromCommand(kind);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Vault chest dump "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            String action = args[0] == null ? "" : args[0].trim().toLowerCase();
            if ("on".equals(action)) {
                ChestDumpLogger.setEnabled(kind, true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Vault chest dump "
                        + EnumChatFormatting.GREEN + "enabled");
                return;
            }
            if ("off".equals(action)) {
                ChestDumpLogger.setEnabled(kind, false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Vault chest dump "
                        + EnumChatFormatting.RED + "disabled");
                return;
            }
            if ("status".equals(action)) {
                boolean enabled = ChestDumpLogger.isEnabled(kind);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Vault chest dump is "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /chestdumpdebug [on|off|status]");
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Chest dump debug command failed.");
        }
    }
}
