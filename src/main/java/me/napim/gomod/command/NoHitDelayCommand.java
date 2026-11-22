/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.NoHitDelay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class NoHitDelayCommand
extends Command {
    public NoHitDelayCommand() {
        super("nohitdelay", new String[]{"nhd"});
    }

    public void handle(String[] args) {
        if (args.length == 0 || "toggle".equalsIgnoreCase(args[0])) {
            NoHitDelay.toggle();
            this.sendStatus();
            return;
        }
        String sub = args[0].toLowerCase();
        if ("on".equals(sub)) {
            NoHitDelay.setEnabled(true);
            this.sendStatus();
            return;
        }
        if ("off".equals(sub)) {
            NoHitDelay.setEnabled(false);
            this.sendStatus();
            return;
        }
        if ("status".equals(sub)) {
            this.sendStatus();
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /nohitdelay [on|off|status]");
    }

    private void sendStatus() {
        boolean enabled = NoHitDelay.isEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "NoHitDelay " + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled") + EnumChatFormatting.RESET);
    }
}

