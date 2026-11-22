package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.LunarNametagDebugCapture;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.util.Locale;

/** Starts/stops a privacy-safe comparison capture around Lunar FullAccess toggles. */
public final class LunarNametagDebugCommand extends Command {
    public LunarNametagDebugCommand() {
        super("lunarlogodebug");
    }

    @Override
    public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            try {
                boolean wasActive = LunarNametagDebugCapture.isActive();
                File output = LunarNametagDebugCapture.start();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + (wasActive ? "Lunar nametag capture is already active: " : "Lunar nametag capture started: ")
                        + EnumChatFormatting.WHITE + output.getAbsolutePath());
                Main.sendMessage(EnumChatFormatting.YELLOW
                        + "Trace: click a Lunar+ color, open TAB briefly, then run /lunarlogodebug mark plus_first. "
                        + "Open Lunar Logo Color without selecting, let it load, open TAB and mark logo_page. "
                        + "Choose a different + color, open TAB and mark plus_second; then watch F5 and stop. "
                        + "The capture records callback/API/profile values and nametag compile/list-draw stages; "
                        + "TAB draw status is included when its hook attaches.");
            } catch (Throwable error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not start Lunar nametag capture: "
                        + EnumChatFormatting.WHITE + error.getClass().getSimpleName());
            }
            return;
        }
        if ("mark".equals(action)) {
            String label = args.length > 1 ? args[1] : "";
            if (!LunarNametagDebugCapture.isActive()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Start /lunarlogodebug before adding a checkpoint.");
            } else if (LunarNametagDebugCapture.recordMarker(label)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Lunar nametag checkpoint recorded: " + EnumChatFormatting.WHITE + label);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Use /lunarlogodebug mark <short_label> (letters, numbers, _ or -).");
            }
            return;
        }
        if ("stop".equals(action)) {
            if (!LunarNametagDebugCapture.isActive()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "No Lunar nametag capture is active.");
                return;
            }
            File output = LunarNametagDebugCapture.stop();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Lunar nametag capture saved: "
                    + EnumChatFormatting.WHITE + (output == null ? "<unknown>" : output.getAbsolutePath()));
            return;
        }
        if ("status".equals(action)) {
            File output = LunarNametagDebugCapture.getOutput();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Lunar nametag capture: "
                    + (LunarNametagDebugCapture.isActive()
                        ? EnumChatFormatting.GREEN + "active" : EnumChatFormatting.GRAY + "inactive")
                    + (output == null ? "" : EnumChatFormatting.WHITE + " (" + output.getAbsolutePath() + ")"));
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /lunarlogodebug <start|mark <label>|stop|status>");
    }
}
