/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.TabDuplicateNickResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class TabReloadCommand
extends Command {
    public TabReloadCommand() {
        super("tabreload", new String[0]);
    }

    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not connected.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Rescanning current TAB entries...");
        mc.addScheduledTask(() -> {
            int duplicateEntries = TabDuplicateNickResolver.forceRescanAndAnnounce();
            if (duplicateEntries > 0) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Found " + EnumChatFormatting.YELLOW + duplicateEntries + EnumChatFormatting.GREEN + " duplicate TAB entr" + (duplicateEntries == 1 ? "y" : "ies") + EnumChatFormatting.GREEN + " in the current snapshot.");
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No duplicate TAB names found in the current snapshot.");
            }
        });
    }
}

