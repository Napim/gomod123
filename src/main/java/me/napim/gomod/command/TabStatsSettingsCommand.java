package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.TabStatsRuntimeMute;
import me.napim.gomod.features.TabStatsSettingsBundle;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /tabstatssettings off} — snapshot then disable everything on the TAB QoL Settings screen (killcountersettings).
 * {@code /tabstatssettings on} — restore that snapshot.
 */
public class TabStatsSettingsCommand extends Command {

    public TabStatsSettingsCommand() {
        super("tabstatssettings");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            sendHelp();
            return;
        }
        String a0 = args[0].toLowerCase();
        if ("off".equals(a0) || "disable".equals(a0)) {
            TabStatsSettingsBundle.applyOff();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB QoL Settings bundle: "
                    + EnumChatFormatting.WHITE + "OFF"
                    + EnumChatFormatting.GRAY + " — kill counter, WL/stats, guild, kits, tabkillers, TAB sort, KE display, "
                    + "blitz star tab icon, auto tabstats scan, tabstats chat. "
                    + EnumChatFormatting.YELLOW + "/tabstatssettings on"
                    + EnumChatFormatting.GRAY + " restores what you had before the first off this session.");
            return;
        }
        if ("on".equals(a0) || "enable".equals(a0)) {
            if (TabStatsSettingsBundle.restoreOn()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB QoL Settings bundle restored.");
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Nothing to restore — run "
                        + EnumChatFormatting.WHITE + "/tabstatssettings off"
                        + EnumChatFormatting.YELLOW + " first (snapshot is only kept until you restore or restart the game).");
            }
            return;
        }
        if ("status".equals(a0)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB QoL bundle: "
                    + (TabStatsSettingsBundle.isSuspended()
                    ? EnumChatFormatting.YELLOW + "suspended (use /tabstatssettings on)"
                    : EnumChatFormatting.GREEN + "normal"));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB QoL runtime pause (/tss): "
                    + (TabStatsRuntimeMute.isMuted()
                    ? EnumChatFormatting.YELLOW + "ON — saved settings frozen until /tss"
                    : EnumChatFormatting.GREEN + "off"));
            return;
        }
        sendHelp();
    }

    private void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/tabstatssettings off"
                + EnumChatFormatting.WHITE + " — snapshot + disable all TAB QoL Settings features");
        Main.sendMessage(EnumChatFormatting.GRAY + "/tabstatssettings on"
                + EnumChatFormatting.WHITE + " — restore snapshot");
        Main.sendMessage(EnumChatFormatting.GRAY + "/tabstatssettings status"
                + EnumChatFormatting.WHITE + " — bundle + /tss pause state");
        Main.sendMessage(EnumChatFormatting.GRAY + "/tss"
                + EnumChatFormatting.WHITE + " — toggle temporary pause (persists across restart)");
    }
}
