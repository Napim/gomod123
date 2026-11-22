package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.TabStatsLayoutMode;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Dev-Weave-only switch between stable inline and experimental separated TAB stats. */
public final class TabStatsLayoutCommand extends Command {
    public TabStatsLayoutCommand() {
        super("tabstatslayout");
    }

    @Override
    public void handle(String[] args) {
        if (!TabStatsLayoutMode.isWeaveDevToggleAvailable()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "This command is only available in the Weave dev build.");
            return;
        }

        String requested = args != null && args.length > 0 ? args[0].trim().toLowerCase() : "toggle";
        if ("inline".equals(requested)) {
            TabStatsLayoutMode.setWeaveDevSeparated(false);
        } else if ("separated".equals(requested) || "separate".equals(requested)) {
            TabStatsLayoutMode.setWeaveDevSeparated(true);
        } else if ("toggle".equals(requested)) {
            TabStatsLayoutMode.toggleWeaveDevSeparated();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Usage: /tabstatslayout [inline|separated|toggle]");
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB stats layout: "
                + EnumChatFormatting.YELLOW + TabStatsLayoutMode.describe());
    }
}
