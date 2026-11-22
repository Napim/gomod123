package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BarrierVisibilityDebugCapture;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.util.Locale;

/** Controls the bounded, internal-only barrier/first-person-render comparison capture. */
public final class BarrierVisibilityDebugCommand extends Command {
    public BarrierVisibilityDebugCommand() {
        super("barrierdebug");
    }

    @Override
    public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            try {
                File out = BarrierVisibilityDebugCapture.start();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Barrier debug capture active (3 minutes max): " + EnumChatFormatting.WHITE + out.getAbsolutePath());
                Main.sendMessage(EnumChatFormatting.YELLOW
                        + "Mark the bad arm state with /barrierdebug mark issue, the normal state with /barrierdebug mark normal, then /barrierdebug stop and send me the log.");
            } catch (Throwable error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Could not start barrier debug: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("stop".equals(action)) {
            File out = BarrierVisibilityDebugCapture.stop();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Barrier debug saved: "
                    + EnumChatFormatting.WHITE + (out == null ? "No capture started." : out.getAbsolutePath()));
            return;
        }
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Barrier debug: " + BarrierVisibilityDebugCapture.status());
            return;
        }
        if ("mark".equals(action) && args.length == 2) {
            String label = args[1].toLowerCase(Locale.ROOT);
            Main.sendMessage(Main.CHAT_PREFIX + (BarrierVisibilityDebugCapture.mark(label)
                    ? EnumChatFormatting.GREEN + "Barrier debug marked: " + label
                    : EnumChatFormatting.YELLOW + "Start a capture first; marks are issue and normal."));
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /barrierdebug <start|mark issue|mark normal|stop|status>");
    }
}
