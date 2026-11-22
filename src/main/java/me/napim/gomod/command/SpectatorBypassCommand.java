package me.napim.gomod.command;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.SpectatorTracker;

import static me.napim.gomod.Main.sendMessage;
import static me.napim.gomod.Main.CHAT_PREFIX;

/**
 * Toggle broad spectator visibility during Blitz: shows invisible remote players the client still has
 * as entities (same data a client-side bot can read). Use {@code /visiblespecs} for KillCounter-only mode.
 */
public class SpectatorBypassCommand extends Command {
    public SpectatorBypassCommand() {
        super("spectatorsbypass");
    }

    @Override
    public void handle(String[] args) {
        VisibleSpecs.setSpectatorBypassAllInvisible(!VisibleSpecs.isSpectatorBypassAllInvisible());

        // Run full diagnostic so the user can see exactly what's happening
        if (VisibleSpecs.isSpectatorBypassAllInvisible()) {
            SpectatorTracker.runDiagnostic();
        }
    }
}
