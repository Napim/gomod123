package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.LunarArmDebugCapture;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import java.io.File;
import java.util.Locale;

/** Controls a bounded equip/remove comparison of the local first-person arm. */
public final class LunarArmDebugCommand extends Command {
    public LunarArmDebugCommand() { super("lunararmdebug"); }

    @Override public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if ("start".equals(action)) {
            try {
                File out = LunarArmDebugCapture.start();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Arm debug active (up to 5 minutes): " + EnumChatFormatting.WHITE + out.getAbsolutePath());
                Main.sendMessage(EnumChatFormatting.YELLOW + "Equip Witch, return to first person, then /lunararmdebug mark equipped. Remove it and mark removed. Finish with /lunararmdebug stop.");
            } catch (Throwable error) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not start arm debug: " + error.getClass().getSimpleName());
            }
            return;
        }
        if ("stop".equals(action)) {
            File out = LunarArmDebugCapture.stop();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Arm debug saved: " + EnumChatFormatting.WHITE + (out == null ? "No capture started." : out.getAbsolutePath()));
            return;
        }
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Arm debug: " + (LunarArmDebugCapture.isActive() ? "active" : "inactive"));
            return;
        }
        if ("mark".equals(action) && args.length == 2) {
            String label = args[1].toLowerCase(Locale.ROOT);
            Main.sendMessage(Main.CHAT_PREFIX + (LunarArmDebugCapture.mark(label)
                    ? EnumChatFormatting.GREEN + "Arm debug marked: " + label
                    : EnumChatFormatting.YELLOW + "Start a capture first; marks: equipped, removed, visible, hidden, shown."));
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /lunararmdebug <start|mark equipped|mark removed|mark visible|mark hidden|mark shown|stop|status>");
    }
}
