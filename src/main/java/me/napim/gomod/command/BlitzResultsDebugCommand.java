package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzSoloExtendedResults;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Arms a one-match capture of message-count decisions and Hypixel's raw Blitz result components. */
public final class BlitzResultsDebugCommand extends Command {
    public BlitzResultsDebugCommand() {
        super("bsgresultsdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0 && "off".equalsIgnoreCase(args[0])) {
            BlitzSoloExtendedResults.setResultsDebugArmed(false);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz results debug capture: " + EnumChatFormatting.RED + "OFF");
            return;
        }
        BlitzSoloExtendedResults.setResultsDebugArmed(true);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Blitz message-count/results debug armed. Run this before the countdown, finish the game, then send the saved .txt file.");
    }
}
