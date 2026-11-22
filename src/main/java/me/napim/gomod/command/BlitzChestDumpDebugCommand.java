package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestDumpLogger;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Enables logging of regular Blitz survival-games loot chests (wheat, apples, ingots, etc.)
 * to {@code blitz_chest_dump_*.txt} for {@code tools/merge_blitz_chest_dumps.py}.
 * Block-coordinate dedupe resets automatically when the client loads a new world (new game).
 */
public class BlitzChestDumpDebugCommand extends Command {
    public BlitzChestDumpDebugCommand() {
        super("blitzchestdumpdebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            ChestDumpLogger.Kind kind = ChestDumpLogger.Kind.BLITZ_LOOT;
            if (args.length == 0) {
                boolean enabled = ChestDumpLogger.toggleFromCommand(kind);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz chest dump "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            String action = args[0] == null ? "" : args[0].trim().toLowerCase();
            if ("on".equals(action)) {
                ChestDumpLogger.setEnabled(kind, true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz chest dump "
                        + EnumChatFormatting.GREEN + "enabled");
                return;
            }
            if ("off".equals(action)) {
                ChestDumpLogger.setEnabled(kind, false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz chest dump "
                        + EnumChatFormatting.RED + "disabled");
                return;
            }
            if ("status".equals(action)) {
                boolean enabled = ChestDumpLogger.isEnabled(kind);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz chest dump is "
                        + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
                return;
            }

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: /blitzchestdumpdebug [on|off|status]");
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Blitz chest dump command failed.");
        }
    }
}
