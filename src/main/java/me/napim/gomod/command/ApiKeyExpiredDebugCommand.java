package me.napim.gomod.command;

import me.napim.gomod.features.GameModeStatusFetcher;
import net.weavemc.loader.api.command.Command;

/**
 * Shows the clickable expired Hypixel API key chat message for preview/testing.
 */
public final class ApiKeyExpiredDebugCommand extends Command {

    public ApiKeyExpiredDebugCommand() {
        super("apikeyexpireddebug");
    }

    @Override
    public void handle(String[] args) {
        GameModeStatusFetcher.sendHypixelKeyExpiredMessage();
    }
}
