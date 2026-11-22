package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

/**
 * Client-side Blitz Hour simulation for debugging sidebar text, centering, Map Queuer, and
 * {@link SidebarBlitzHourReminder} without a real Hypixel Blitz Hour lobby.
 */
public final class ForceBlitzHourDebug {
    private static boolean forced;
    private static String injectedEntry;

    private ForceBlitzHourDebug() {
    }

    public static boolean isForced() {
        return forced;
    }

    public static void setForced(boolean on) {
        if (forced == on) {
            return;
        }
        forced = on;
        if (!on) {
            removeInjection();
        }
    }

    public static void toggle() {
        setForced(!forced);
    }

    /** Called from {@link SidebarBlitzHourReminder} client tick while force is on. */
    public static void clientTick(Minecraft mc) {
        if (!forced || mc == null || mc.theWorld == null) {
            return;
        }
        ensureSidebarLineInjected(mc);
    }

    public static boolean hasInjectedSidebarLine() {
        return injectedEntry != null;
    }

    /**
     * Inserts a {@code BLITZ HOUR} scoreboard row (Hypixel-style yellow bold) when the live sidebar does not
     * already contain one.
     */
    private static void ensureSidebarLineInjected(Minecraft mc) {
        try {
            if (MapQueuer.readLiveBlitzHourFromSidebar()) {
                removeInjection();
                return;
            }
            Scoreboard board = mc.theWorld.getScoreboard();
            if (board == null) {
                return;
            }
            ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return;
            }
            if (SidebarBlitzHourReminder.isBlitzHourBlockApplied()) {
                injectedEntry = SidebarBlitzHourReminder.getFormattedSidebarLine();
                return;
            }
            SidebarBlitzHourReminder.applyBlitzHourSidebarBlock(board, objective);
            injectedEntry = SidebarBlitzHourReminder.isBlitzHourBlockApplied()
                    ? SidebarBlitzHourReminder.getFormattedSidebarLine() : null;
        } catch (Throwable ignored) {
        }
    }

    public static void removeInjection() {
        injectedEntry = null;
        SidebarBlitzHourReminder.clearBlitzHourSidebarBlock();
    }

    public static String statusLine() {
        boolean live = MapQueuer.readLiveBlitzHourFromSidebar();
        return (forced ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + "  live sidebar="
                + (live ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                + EnumChatFormatting.GRAY + "  injected="
                + (injectedEntry != null ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                + EnumChatFormatting.GRAY + "  effective isBlitzHour="
                + (MapQueuer.isBlitzHourOnSidebar() ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no");
    }
}
