package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.DebugHiddenPlayers;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.List;

/**
 * Dev-only command for completely suppressing a player's entity render.
 *
 * /hideplayerdebug <player> - toggle
 * /hideplayerdebug list
 * /hideplayerdebug clear
 */
public final class HidePlayerDebugCommand extends Command {
    public HidePlayerDebugCommand() {
        super("hideplayerdebug", "hideplayer");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: /hideplayerdebug <player|list|clear>");
            return;
        }

        String sub = args[0].trim();
        if ("clear".equalsIgnoreCase(sub)) {
            DebugHiddenPlayers.clear();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "All debug-hidden players are visible again.");
            return;
        }
        if ("list".equalsIgnoreCase(sub)) {
            List<String> hidden = DebugHiddenPlayers.getHiddenNames();
            if (hidden.isEmpty()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "No debug-hidden players.");
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                        + "Debug-hidden players: " + EnumChatFormatting.WHITE
                        + String.join(", ", hidden));
            }
            return;
        }

        boolean hidden = DebugHiddenPlayers.toggle(sub);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + sub
                + (hidden
                ? EnumChatFormatting.RED + " is now completely hidden."
                : EnumChatFormatting.GREEN + " is visible again.")
                + EnumChatFormatting.GRAY + " (world + replay)");
    }
}
