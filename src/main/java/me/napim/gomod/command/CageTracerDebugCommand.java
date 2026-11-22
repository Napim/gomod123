package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.CageTracerDebug;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Bounded performance capture; available on both client builds. */
public final class CageTracerDebugCommand extends Command {
    public CageTracerDebugCommand() { super("cagetracerdebug"); }

    @Override
    public void handle(String[] args) {
        run(args.length == 0 ? "start" : args[0]);
    }

    public static void run(String action) {
        String message;
        if ("start".equalsIgnoreCase(action)) message = CageTracerDebug.start();
        else if ("stop".equalsIgnoreCase(action)) message = CageTracerDebug.stop();
        else if ("status".equalsIgnoreCase(action)) message = CageTracerDebug.status();
        else message = "Usage: /cagetracerdebug [start|stop|status]";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + message);
    }
}
