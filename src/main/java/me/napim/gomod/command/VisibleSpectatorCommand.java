package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Internal-build master switch for automatic spectator rendering. */
public class VisibleSpectatorCommand extends Command {
    public VisibleSpectatorCommand() {
        super("visiblespecs");
    }

    @Override
    public void handle(String[] args) {
        boolean next;
        if (args.length == 0 || "toggle".equalsIgnoreCase(args[0])) {
            next = !VisibleSpecs.isEnabled();
        } else if ("on".equalsIgnoreCase(args[0])) {
            next = true;
        } else if ("off".equalsIgnoreCase(args[0])) {
            next = false;
        } else if ("status".equalsIgnoreCase(args[0])) {
            Main.sendMessage(Main.CHAT_PREFIX + "Visible Spectators: "
                    + (VisibleSpecs.isEnabled() ? EnumChatFormatting.GREEN + "enabled"
                    : EnumChatFormatting.RED + "disabled")
                    + EnumChatFormatting.GRAY + " (runs only while spectating)");
            return;
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Usage: /visiblespecs [on|off|toggle|status]");
            return;
        }
        VisibleSpecs.setEnabled(next);
    }
}
