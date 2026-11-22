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
import me.napim.gomod.util.GuiOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class InventoryHUDCommand
extends Command {
    public InventoryHUDCommand() {
        super("invhud", new String[0]);
    }

    public void handle(String[] args) {
        if (args.length == 0) {
            new Thread(new Runnable(){

                @Override
                public void run() {
                    try {
                        Thread.sleep(100L);
                    }
                    catch (InterruptedException interruptedException) {
                        // empty catch block
                    }
                    Minecraft.getMinecraft().addScheduledTask(new Runnable(){

                        @Override
                        public void run() {
                            GuiOpener.open("me.napim.gomod.gui.InventoryHUDGUI");
                        }
                    });
                }
            }, "OpenInvHudGui").start();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Inventory HUD Command:");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /invhud" + EnumChatFormatting.WHITE + " - Open Inventory HUD Configuration GUI");
            Main.sendMessage(EnumChatFormatting.YELLOW + "  /invhud help" + EnumChatFormatting.WHITE + " - Show this help message");
            Main.sendMessage(EnumChatFormatting.GRAY + "All settings can be configured through the GUI interface.");
        }
    }
}

