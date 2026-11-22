package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.ForcePigManager;
import me.napim.gomod.features.SpawnedMobTracker;
import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class GomodDebugCommand extends Command {
    public GomodDebugCommand() {
        super("gdebug");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "=== gomod debug ===");

        long ago = System.currentTimeMillis() - ClientTickHub.getLastRunWallMs();
        Main.sendMessage(EnumChatFormatting.YELLOW + "[TickHub] " + EnumChatFormatting.WHITE + "runs=" + ClientTickHub.getTotalRuns()
                + " lastMsAgo=" + (ClientTickHub.getLastRunWallMs() <= 0 ? "never" : String.valueOf(ago))
                + " alive120ms=" + ClientTickHub.isHubRecentlyAlive(120L));

        Main.sendMessage(EnumChatFormatting.YELLOW + "[ForcePig] " + EnumChatFormatting.WHITE + ForcePigManager.debugState());
        Main.sendMessage(EnumChatFormatting.YELLOW + "[WitchSound] " + EnumChatFormatting.WHITE + WitchAutoSound.debugState());
        Main.sendMessage(EnumChatFormatting.YELLOW + "[CE Edit] " + EnumChatFormatting.WHITE + ChestRouteManager.debugEditState());
        Main.sendMessage(EnumChatFormatting.YELLOW + "[SpawnTrack] " + EnumChatFormatting.WHITE + SpawnedMobTracker.debugStatusSummary()
                + EnumChatFormatting.GRAY + " — /spawnedmobdebug on");

        if (mc != null && mc.theWorld != null) {
            int total = mc.theWorld.loadedEntityList.size();
            int witchExact = 0;
            for (Entity e : mc.theWorld.loadedEntityList) {
                if (e instanceof EntityWitch) witchExact++;
            }
            Main.sendMessage(EnumChatFormatting.YELLOW + "[World] " + EnumChatFormatting.WHITE
                    + "entities=" + total + " witches(exact)=" + witchExact
                    + " screen=" + (mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "none"));
        }
    }
}
