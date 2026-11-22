package me.napim.gomod.command;

import me.napim.gomod.features.KillCounter;
import net.weavemc.loader.api.command.Command;

/**
 * TAB overlay pipeline dump: raw {@code getPlayerInfoMap} vs what gomod passes to the TAB GUI.
 */
public class TabListDebugCommand extends Command {
    public TabListDebugCommand() {
        super("tablistdebug");
    }

    @Override
    public void handle(String[] args) {
        KillCounter.dumpTabOverlayPipelineDebugToChat();
    }
}
