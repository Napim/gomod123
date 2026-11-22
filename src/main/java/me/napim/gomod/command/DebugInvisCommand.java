package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Collection;

/**
 * Debug command to check and remove invisibility from a player.
 */
public class DebugInvisCommand extends Command {
    public DebugInvisCommand() {
        super("debuginvis");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /debuginvis <player>");
            return;
        }

        String targetName = args[0];
        Minecraft mc = Minecraft.getMinecraft();
        
        if (mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in a world!");
            return;
        }

        EntityPlayer targetPlayer = null;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player.getName().equalsIgnoreCase(targetName)) {
                targetPlayer = player;
                break;
            }
        }

        if (targetPlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player not found: " + targetName);
            return;
        }

        // Debug info
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Debug info for " + targetPlayer.getName() + ":");
        Main.sendMessage(EnumChatFormatting.GRAY + "- isInvisible(): " + targetPlayer.isInvisible());
        Main.sendMessage(EnumChatFormatting.GRAY + "- isInvisibleToPlayer(): " + targetPlayer.isInvisibleToPlayer(mc.thePlayer));
        
        // Check for invisibility potion effect
        boolean hasInvisEffect = targetPlayer.isPotionActive(Potion.invisibility);
        Main.sendMessage(EnumChatFormatting.GRAY + "- Has Invisibility Potion: " + hasInvisEffect);
        
        if (hasInvisEffect) {
            PotionEffect effect = targetPlayer.getActivePotionEffect(Potion.invisibility);
            if (effect != null) {
                Main.sendMessage(EnumChatFormatting.GRAY + "  - Duration: " + effect.getDuration());
                Main.sendMessage(EnumChatFormatting.GRAY + "  - Amplifier: " + effect.getAmplifier());
            }
            
            // Try to remove it
            targetPlayer.removePotionEffect(Potion.invisibility.id);
            Main.sendMessage(EnumChatFormatting.GREEN + "Removed invisibility effect!");
            Main.sendMessage(EnumChatFormatting.GRAY + "- After removal - isInvisible(): " + targetPlayer.isInvisible());
        } else {
            Main.sendMessage(EnumChatFormatting.YELLOW + "No invisibility effect found.");
        }
        
        // List all active potion effects
        Collection<PotionEffect> effects = targetPlayer.getActivePotionEffects();
        if (!effects.isEmpty()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "Active effects:");
            for (PotionEffect eff : effects) {
                String effectName = Potion.potionTypes[eff.getPotionID()].getName();
                Main.sendMessage(EnumChatFormatting.GRAY + "  - " + effectName + " (ID: " + eff.getPotionID() + ")");
            }
        }
    }
}

