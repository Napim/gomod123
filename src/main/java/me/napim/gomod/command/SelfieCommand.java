package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import me.napim.gomod.features.Selfie;

/**
 * Command to toggle the Selfie feature.
 * Usage: /selfie
 * 
 * When enabled, freezes the camera at the current position while
 * allowing the player to move away from it.
 */
public class SelfieCommand extends Command {
    public SelfieCommand() {
        super("selfie");
    }

    @Override
    public void handle(String[] args) {
        Selfie.setEnabled(!Selfie.isEnabled());
    }
}

