package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

/**
 * Periodically posts lightweight client stats (FPS, entity counts, a few gomod toggles) while enabled.
 * For real sampling use Spark or similar; this is only a coarse live hint.
 */
public final class GomodPerformanceWatch {

    private static volatile boolean enabled;
    private int tickAccumulator;

    public GomodPerformanceWatch() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void sendSnapshotChat() {
        Main.sendMessage(Main.CHAT_PREFIX + formatLine());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent event) {
        if (!enabled) {
            return;
        }
        tickAccumulator++;
        if (tickAccumulator < 40) {
            return;
        }
        tickAccumulator = 0;
        Main.sendMessage(Main.CHAT_PREFIX + formatLine());
    }

    private static String formatLine() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return EnumChatFormatting.RED + "(no mc)";
        }
        int fps = mc.getDebugFPS();
        int loadedEnt = mc.theWorld != null ? mc.theWorld.loadedEntityList.size() : -1;
        int players = mc.theWorld != null ? mc.theWorld.playerEntities.size() : -1;
        int chunkX = mc.thePlayer != null && mc.theWorld != null
                ? mc.theWorld.getChunkProvider().getLoadedChunkCount()
                : -1;
        StringBuilder sb = new StringBuilder(128);
        sb.append(EnumChatFormatting.GRAY).append("FPS ").append(EnumChatFormatting.WHITE).append(fps);
        sb.append(EnumChatFormatting.GRAY).append(" | loadedEnt ").append(EnumChatFormatting.WHITE).append(loadedEnt);
        sb.append(EnumChatFormatting.GRAY).append(" | players ").append(EnumChatFormatting.WHITE).append(players);
        sb.append(EnumChatFormatting.GRAY).append(" | chunks ").append(EnumChatFormatting.WHITE).append(chunkX);
        sb.append(EnumChatFormatting.GRAY).append(" | entityTags ").append(EnumChatFormatting.WHITE)
                .append(EntityNametagLimiter.getCandidateCount());
        if (EntityNametagLimiter.getLimitedCount() > 0) {
            sb.append(EnumChatFormatting.GRAY).append(" (-")
                    .append(EntityNametagLimiter.getLimitedCount()).append(')');
        }
        sb.append(EnumChatFormatting.DARK_GRAY).append(" — ");
        sb.append(toggleChunk("KC", ConfigManager.isKillCounterEnabled()));
        sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
        sb.append(toggleChunk("InvHUD", ConfigManager.isInventoryHUDEnabled()));
        sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
        sb.append(toggleChunk("ItemESP", ItemESP.isEnabled()));
        sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
        sb.append(toggleChunk("tabKill",
                ConfigManager.isKillCounterTabKillersEnabled() && ConfigManager.isKillCounterEnabled()));
        return sb.toString();
    }

    private static String toggleChunk(String label, boolean on) {
        return EnumChatFormatting.GRAY + label + " "
                + (on ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off");
    }
}
