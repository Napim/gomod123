package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles verbose gomod debug output to chat (TAB duplicate hide/restore, stale row hide, nick resolution announce).
 * Default is off.
 */
public class DebugMessagesCommand extends Command {

    public DebugMessagesCommand() {
        super("debugmessages");
    }

    @Override
    public void handle(String[] args) {
        if (args.length >= 1) {
            String a = args[0].toLowerCase();
            if ("on".equals(a) || "enable".equals(a) || "true".equals(a)) {
                ConfigManager.setDebugMessagesToChatEnabled(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Debug messages to chat: "
                        + EnumChatFormatting.WHITE + "ON");
                return;
            }
            if ("off".equals(a) || "disable".equals(a) || "false".equals(a)) {
                ConfigManager.setDebugMessagesToChatEnabled(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Debug messages to chat: "
                        + EnumChatFormatting.WHITE + "OFF");
                return;
            }
            if ("status".equals(a)) {
                sendStatus();
                return;
            }
        } else {
            boolean next = !ConfigManager.isDebugMessagesToChatEnabled();
            ConfigManager.setDebugMessagesToChatEnabled(next);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Debug messages to chat: "
                    + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/debugmessages"
                + EnumChatFormatting.WHITE + " — toggle");
        Main.sendMessage(EnumChatFormatting.GRAY + "/debugmessages on | off | status");
    }

    private void sendStatus() {
        boolean on = ConfigManager.isDebugMessagesToChatEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Debug messages to chat: "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " (TAB dupe hide/restore, stale Blitz row, nicked-as announce)");
    }
}
