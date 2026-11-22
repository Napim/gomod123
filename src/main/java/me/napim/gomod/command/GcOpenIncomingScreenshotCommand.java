package me.napim.gomod.command;

import me.napim.gomod.features.IncomingScreenshotManager;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;

/** Client command used by chat click on incoming [IMG] lines. */
public class GcOpenIncomingScreenshotCommand extends Command {
    public GcOpenIncomingScreenshotCommand() {
        super("gcopenimg");
    }

    @Override
    public void handle(String[] args) {
        IncomingScreenshotManager.openLatestIncomingFromChat(Minecraft.getMinecraft());
    }
}
