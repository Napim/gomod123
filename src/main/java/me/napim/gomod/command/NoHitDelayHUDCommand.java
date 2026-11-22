/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.command.NoHitDelayState;
import me.napim.gomod.features.NoHitDelay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class NoHitDelayHUDCommand
extends Command {
    public NoHitDelayHUDCommand() {
        super("nohitui", new String[]{"nhdui"});
    }

    public void handle(String[] args) {
        if (args.length == 0 || "toggle".equalsIgnoreCase(args[0])) {
            NoHitDelay.toggleHud();
            this.sendStatus();
            return;
        }
        String sub = args[0].toLowerCase();
        if ("on".equals(sub)) {
            NoHitDelay.setHudEnabled(true);
            this.sendStatus();
            return;
        }
        if ("off".equals(sub)) {
            NoHitDelay.setHudEnabled(false);
            this.sendStatus();
            return;
        }
        if ("status".equals(sub)) {
            this.sendStatus();
            return;
        }
        if ("reset".equals(sub) || "clear".equals(sub)) {
            NoHitDelayState.resetDebugCounters();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "NoHitDelay HUD counters reset.");
            this.sendStatus();
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /nohitui [on|off|status|reset]");
    }

    private void sendStatus() {
        boolean enabled = NoHitDelay.isHudEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "NoHitDelay HUD " + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled") + EnumChatFormatting.RESET);
    }
}

