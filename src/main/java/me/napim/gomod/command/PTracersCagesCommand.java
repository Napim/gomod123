package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.PlayerTracers;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /ptracerscages -- toggles automatic player tracers during Blitz cages plus 4 seconds after cages open.
 * This mode works even if /ptracers is currently disabled.
 */
public class PTracersCagesCommand extends Command {

    public PTracersCagesCommand() {
        super("ptracerscages");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            if ("debug".equalsIgnoreCase(args[0])) {
                CageTracerDebugCommand.run(args.length > 1 ? args[1] : "status");
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Usage: /ptracerscages [debug start|stop|status]");
            }
            return;
        }
        PlayerTracers.toggleCagesAuto();
        boolean on = PlayerTracers.isCagesAutoEnabled();
        ConfigManager.setPtracersCagesEnabled(on);
        Main.sendMessage(Main.CHAT_PREFIX + (on
                ? EnumChatFormatting.GREEN + "Player tracers cages-auto " + EnumChatFormatting.BOLD + "ENABLED"
                : EnumChatFormatting.RED + "Player tracers cages-auto " + EnumChatFormatting.BOLD + "DISABLED")
                + EnumChatFormatting.GRAY + " (during cages + 4s after open).");
    }
}
