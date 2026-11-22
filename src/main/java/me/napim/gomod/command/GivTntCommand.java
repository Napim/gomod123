package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command to give TNT and flint+steel client-side
 * Usage: /givtnt
 */
public class GivTntCommand extends Command {
    public GivTntCommand() {
        super("givtnt");
    }

    @Override
    public void handle(String[] args) {
        try {
            if (Minecraft.getMinecraft().thePlayer == null) return;
            // Give TNT x64 only
            ItemStack tnt = new ItemStack(Blocks.tnt, 64);
            Minecraft.getMinecraft().thePlayer.inventory.addItemStackToInventory(tnt);
            
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Gave TNT (client-side)");
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to give TNT: " + t.getMessage());
        }
    }
}


