package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;

/**
 * /playershow — same behaviour as /playersplay but without enabled/disabled chat lines.
 */
public class PlayersShowCommand extends Command {

    public PlayersShowCommand() {
        super("playershow");
    }

    @Override
    public void handle(String[] args) {
        PlayersPlayCommand.handleInternal(args, true);
    }
}
