package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.DebugHits;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles chat output for melee hit damage and crit status.
 */
public class DebugHitsCommand extends Command {

    public DebugHitsCommand() {
        super("debughits");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0 || "toggle".equalsIgnoreCase(args[0])) {
            DebugHits.toggle();
            sendStatus();
            return;
        }
        String sub = args[0].toLowerCase();
        if ("on".equals(sub) || "enable".equals(sub) || "true".equals(sub)) {
            DebugHits.setEnabled(true);
            sendStatus();
            return;
        }
        if ("off".equals(sub) || "disable".equals(sub) || "false".equals(sub)) {
            DebugHits.setEnabled(false);
            sendStatus();
            return;
        }
        if ("status".equals(sub)) {
            sendStatus();
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /debughits [on|off|status]");
    }

    private void sendStatus() {
        boolean on = DebugHits.isEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Debug hits "
                + (on ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled")
                + EnumChatFormatting.RESET);
    }
}
