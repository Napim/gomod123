package me.napim.gomod.command;

import me.napim.gomod.features.ServerCommandsRequest;
import net.weavemc.loader.api.command.Command;

public class ServerCommandsCommand extends Command {
    public ServerCommandsCommand() {
        super("servercommands", "servercmds");
    }

    @Override
    public void handle(String[] args) {
        ServerCommandsRequest.request();
    }
}
