package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.features.LunarWorldLabelProbe;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Opt-in live capture of actual player/mob nametag draw calls and OpenGL state. */
public final class NametagRenderDebugCommand extends Command {
    public NametagRenderDebugCommand() {
        super("nametagrenderdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 2 && "probe".equalsIgnoreCase(args[0])) {
            try {
                String result = LunarWorldLabelProbe.selectMode(args[1]);
                if (!"off".equalsIgnoreCase(args[1]) && !NametagRenderDebugCapture.isActive()) {
                    result += " " + NametagRenderDebugCapture.start();
                }
                NametagRenderDebugCapture.recordNativeWorldLabelState("probe_mode", result);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + result);
            } catch (IllegalArgumentException invalid) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Usage: /nametagrenderdebug probe off|all|lighting|textures|shader|blend");
            }
            return;
        }
        if (args.length != 1) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Usage: /nametagrenderdebug start|stop|status, or probe off|all|lighting|textures|shader|blend");
            return;
        }
        String action = args[0].trim().toLowerCase(java.util.Locale.ROOT);
        if ("start".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + NametagRenderDebugCapture.start());
        } else if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + NametagRenderDebugCapture.status() + " world-label probe=" + LunarWorldLabelProbe.modeName());
        } else if ("stop".equals(action)) {
            LunarWorldLabelProbe.selectMode("off");
            try {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + NametagRenderDebugCapture.stopAndSave());
            } catch (Throwable failure) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Nametag capture failed to save: "
                        + EnumChatFormatting.WHITE + failure.getClass().getSimpleName());
            }
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Usage: /nametagrenderdebug start|stop|status, or probe off|all|lighting|textures|shader|blend");
        }
    }
}
