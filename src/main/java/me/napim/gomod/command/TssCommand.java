package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.TabStatsRuntimeMute;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /tss} — toggle temporary pause for TAB QoL Settings behavior (state persists across restart).
 */
public class TssCommand extends Command {

    public TssCommand() {
        super("tss");
    }

    @Override
    public void handle(String[] args) {
        boolean nowMuted = TabStatsRuntimeMute.toggle();
        try {
            KillCounter.forceTabRefresh();
        } catch (Throwable ignored) {
        }
        if (nowMuted) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "TAB QoL Settings "
                    + EnumChatFormatting.RED + "paused"
                    + EnumChatFormatting.GRAY + " — TAB overlays, sorting, auto scans, and related features are off until "
                    + EnumChatFormatting.WHITE + "/tss"
                    + EnumChatFormatting.GRAY + ". Saved TAB settings are unchanged.");
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB QoL Settings "
                    + EnumChatFormatting.WHITE + "active again"
                    + EnumChatFormatting.GRAY + " (your saved toggles apply).");
        }
    }
}
