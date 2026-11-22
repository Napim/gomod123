package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzRouletteDebug;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public final class RouletteDebugCommand extends Command {
    public RouletteDebugCommand() { super("roulettedebug"); }

    @Override public void handle(String[] args) {
        String action = args.length == 0 ? "start" : args[0];
        String message;
        if (args.length > 1) message = "Usage: /roulettedebug [start|stop|status]";
        else if ("start".equalsIgnoreCase(action)) message = BlitzRouletteDebug.start();
        else if ("stop".equalsIgnoreCase(action)) message = BlitzRouletteDebug.stop();
        else if ("status".equalsIgnoreCase(action)) message = BlitzRouletteDebug.status();
        else message = "Usage: /roulettedebug [start|stop|status]";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + message);
    }
}
