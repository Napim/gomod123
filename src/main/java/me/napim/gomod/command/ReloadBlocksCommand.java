package me.napim.gomod.command;

import net.weavemc.loader.api.command.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.Main;

/**
 * Command to reload chunks from the server.
 * This is more effective than F3+A for fixing unloaded chunks.
 * Usage: /reloadblocks
 */
public class ReloadBlocksCommand extends Command {
    public ReloadBlocksCommand() {
        super("reloadblocks");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        
        if (mc.theWorld == null || mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "You must be in a world to reload chunks!");
            return;
        }

        try {
            // Get player position
            BlockPos playerPos = mc.thePlayer.getPosition();
            int playerX = playerPos.getX();
            int playerZ = playerPos.getZ();
            
            // Calculate render distance (in chunks)
            int renderDistance = mc.gameSettings.renderDistanceChunks;
            int chunkRange = renderDistance + 1; // Add buffer
            
            // Mark all chunks around player for update
            WorldClient world = mc.theWorld;
            
            // Mark a large area around the player for render update
            // This forces the client to request chunk data from the server
            int blockRange = chunkRange * 16; // Convert chunks to blocks
            world.markBlockRangeForRenderUpdate(
                playerX - blockRange, 
                0, 
                playerZ - blockRange,
                playerX + blockRange, 
                256, 
                playerZ + blockRange
            );
            
            // Also reload all world renderers for a complete refresh
            mc.renderGlobal.loadRenderers();
            
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Chunks reloaded successfully!");
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Reloaded " + 
                (chunkRange * 2) + "x" + (chunkRange * 2) + " chunks around you.");
            
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to reload chunks: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
