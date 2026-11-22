/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.ChatComponentText
 *  net.minecraft.util.EnumChatFormatting
 *  net.minecraft.util.IChatComponent
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import me.napim.gomod.features.AIChatHandler;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.command.Command;

public class GoStatsToggleCommand
extends Command {
    public GoStatsToggleCommand() {
        super("gostats123", new String[0]);
    }

    public void handle(String[] args) {
        boolean newState = !ConfigManager.isGoStatsEnabled();
        ConfigManager.setGoStatsEnabled(newState);
        AIChatHandler.setEnabled(newState);
        String statusMessage = String.format("%s@gostats123 functionality has been %s%s%s.", EnumChatFormatting.BLUE, newState ? EnumChatFormatting.GREEN : EnumChatFormatting.RED, newState ? "enabled" : "disabled", EnumChatFormatting.RESET);
        this.sendMessage(statusMessage);
        if (newState) {
            this.sendMessage(EnumChatFormatting.YELLOW + "To use: Include " + EnumChatFormatting.AQUA + "@gostats123" + EnumChatFormatting.YELLOW + " in your messages to get AI responses.");
            this.sendMessage(EnumChatFormatting.YELLOW + "Example: Hey " + EnumChatFormatting.AQUA + "@gostats123" + EnumChatFormatting.YELLOW + " what's the best strategy for bedwars?");
            this.sendMessage(EnumChatFormatting.GRAY + "No personal OpenAI key is needed; daily usage is shown in gostats123 settings.");
        }
        System.out.println("gostats123 toggle executed: " + statusMessage);
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage((IChatComponent)new ChatComponentText(message));
        }
    }
}

