package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.KillCounter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Clears TAB QoL runtime caches and vanilla TAB display overrides (WL, guild tags, kill-effect tints from /tabstats).
 * Does not change settings in the config GUI; run {@code /tabstats all} to fetch data again.
 */
public class TabstatsResetsCommand extends Command {

    public TabstatsResetsCommand() {
        super("tabstatsresets");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not connected.");
            return;
        }
        mc.addScheduledTask(() -> {
            KillCounter.resetTabStatsOverlayNow();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB QoL overlay reset"
                    + EnumChatFormatting.GRAY + " (TAB restored to vanilla for this session). "
                    + EnumChatFormatting.YELLOW + "/tabstats all"
                    + EnumChatFormatting.GRAY + " to refill WL/guild/kill-effect data.");
        });
    }
}
