package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Diagnostic dump for visible spectators / spectator opacity ({@link VisibleSpecs}).
 * {@code /visiblespecsdebug} — core dump. {@code /visiblespecsdebug full} — also runs KillCounter TAB dump ({@code /gomodlag tab}).
 */
public class VisibleSpecsDebugCommand extends Command {
    public VisibleSpecsDebugCommand() {
        super("visiblespecsdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0 && !"full".equalsIgnoreCase(args[0].trim())) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Usage: /visiblespecsdebug  or  /visiblespecsdebug full");
            return;
        }
        boolean full = args.length > 0;
        VisibleSpecs.dumpDebugSnapshotToChat(full);
    }
}
