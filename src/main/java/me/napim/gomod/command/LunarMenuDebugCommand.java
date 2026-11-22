package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.LunarMenuDebugCapture;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import java.io.File;
import java.util.Locale;

/** Controls a bounded comparison of Lunar menu movement and typing. */
public final class LunarMenuDebugCommand extends Command {
    public LunarMenuDebugCommand() { super("lunarmenudebug"); }
    @Override public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            try {
                File file = LunarMenuDebugCapture.start();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Lunar menu debug active (up to 5 minutes): " + EnumChatFormatting.WHITE + file.getAbsolutePath());
                Main.sendMessage(EnumChatFormatting.YELLOW + "Reproduce the menu wall issue: keep movement enabled, open the affected menu, move into a wall, then try to move away. Run /lunarmenudebug stop afterward. Capture records key, movement, sprint and collision states, but no coordinates or typed text.");
            } catch (Throwable error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not start menu debug: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("stop".equals(action)) {
            File file = LunarMenuDebugCapture.stop();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Lunar menu debug saved: " + EnumChatFormatting.WHITE + (file == null ? "No capture started." : file.getAbsolutePath())); return;
        }
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Lunar menu debug: " + (LunarMenuDebugCapture.isActive() ? "active" : "inactive")); return;
        }
        if ("mark".equals(action) && args.length == 2) {
            String label = args[1].toLowerCase(Locale.ROOT);
            Main.sendMessage(Main.CHAT_PREFIX + (LunarMenuDebugCapture.mark(label)
                    ? EnumChatFormatting.GREEN + "Lunar menu debug marked: " + label
                    : EnumChatFormatting.YELLOW + "Start a capture first; marks: baseline, typing, blurred.")); return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /lunarmenudebug <start|mark baseline|mark typing|mark blurred|stop|status>");
    }
}
