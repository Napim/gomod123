package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.DisguiseKiller;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Session toggle for copying the latest player killed in a live Blitz match. */
public final class DisguiseKillerCommand extends Command {
    public DisguiseKillerCommand() {
        super("disguisekiller", new String[0]);
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.requireInternalCommand("disguisekiller")) return;
        if (args.length == 0) {
            DisguiseKiller.setEnabled(!DisguiseKiller.isEnabled());
        } else if (args.length == 1 && ("on".equalsIgnoreCase(args[0]) || "enable".equalsIgnoreCase(args[0]))) {
            DisguiseKiller.setEnabled(true);
        } else if (args.length == 1 && ("off".equalsIgnoreCase(args[0]) || "disable".equalsIgnoreCase(args[0]))) {
            DisguiseKiller.setEnabled(false);
        } else if (args.length != 1 || !"status".equalsIgnoreCase(args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: /disguisekiller [on|off|status]");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + (DisguiseKiller.isEnabled()
                ? EnumChatFormatting.GREEN + "Disguise killer: ON. Each Blitz kill copies that player's disguise."
                : EnumChatFormatting.YELLOW + "Disguise killer: OFF. Use /disguise off to restore your own appearance."));
    }
}
