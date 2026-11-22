package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Toggles verbose per-player chat (and console lines) during {@code /tabstats all} bulk scans.
 */
public class TabstatsFullDebugCommand extends Command {
    public TabstatsFullDebugCommand() {
        super("tabstatsfulldebug");
    }

    @Override
    public void handle(String[] args) {
        boolean on = KillEffectCommand.toggleTabstatsFullDebugChat();
        sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tabstats per-player verbose "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " (bulk scan detail in chat + console)");
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
