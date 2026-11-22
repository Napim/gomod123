package me.napim.gomod.command;

import me.napim.gomod.features.IncomingCapeDesigns;
import net.weavemc.loader.api.command.Command;

/** Client command used by the cape-received [View] chat button. */
public class GomodViewCapesCommand extends Command {
    public GomodViewCapesCommand() {
        super("gomodviewcapes");
    }

    @Override
    public void handle(String[] args) {
        IncomingCapeDesigns.openCapeGui();
    }
}
