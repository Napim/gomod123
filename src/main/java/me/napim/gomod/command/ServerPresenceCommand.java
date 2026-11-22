package me.napim.gomod.command;

import me.napim.gomod.features.LunarServerPresenceOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.weavemc.loader.api.command.Command;

/** Internal dev commands for inspecting and sending Lunar friend-presence updates. */
public final class ServerPresenceCommand extends Command {
    public ServerPresenceCommand() {
        super("server");
    }

    @Override
    public void handle(String[] args) {
        String message;
        if (args == null || args.length != 1) {
            message = "Usage: /server hypixel | /server auto | /server debug";
        } else if ("hypixel".equalsIgnoreCase(args[0])) {
            message = LunarServerPresenceOverride.publishHypixelBlitz();
        } else if ("auto".equalsIgnoreCase(args[0]) || "reset".equalsIgnoreCase(args[0])) {
            message = LunarServerPresenceOverride.restoreDetectedServer();
        } else if ("debug".equalsIgnoreCase(args[0])) {
            message = LunarServerPresenceOverride.debugPresence();
        } else {
            message = "Usage: /server hypixel | /server auto | /server debug";
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("[gomod] " + message));
        }
    }
}
