package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzFlameKillEffectTracker;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public final class KeSidebarDebugCommand extends Command {

    public KeSidebarDebugCommand() {
        super("kesidebardebug");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[KE sidebar debug]");
        send(EnumChatFormatting.GRAY + "Hypixel: " + (GameContext.isOnHypixel(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                + EnumChatFormatting.GRAY + "  Blitz active: " + (GameContext.isBlitzGameActive(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        send(EnumChatFormatting.GRAY + "Hide kit row toggle: " + (ConfigManager.isHideKitScoreboardEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
        send(EnumChatFormatting.GRAY + "Real row rewrite: "
                + (BlitzFlameKillEffectTracker.isSidebarRealRowRewriteActive() ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));

        String ke = BlitzFlameKillEffectTracker.getFormattedSidebarKillEffectLine();
        send(EnumChatFormatting.GRAY + "Resolved KE line: "
                + (ke != null ? EnumChatFormatting.GREEN + "yes " + EnumChatFormatting.DARK_GRAY + "(" + EnumChatFormatting.RESET + ke + EnumChatFormatting.DARK_GRAY + ")"
                : EnumChatFormatting.RED + "no"));

        String lastKit = BlitzFlameKillEffectTracker.getSidebarLastKitAnchorRaw();
        if (lastKit != null && !lastKit.isEmpty()) {
            send(EnumChatFormatting.GRAY + "Last kit-anchor draw: " + EnumChatFormatting.WHITE + sanitize(lastKit)
                    + EnumChatFormatting.DARK_GRAY + " (hidden=" + (BlitzFlameKillEffectTracker.wasSidebarLastKitAnchorHidden() ? "yes" : "no") + ")");
        } else {
            send(EnumChatFormatting.RED + "Last kit-anchor draw: (none seen)");
        }

        send(EnumChatFormatting.DARK_GRAY + "Recent sidebar draws (newest last):");
        String[] snap = BlitzFlameKillEffectTracker.getSidebarRecentDrawsSnapshot();
        for (String s : snap) {
            if (s == null || s.trim().isEmpty()) {
                continue;
            }
            send(EnumChatFormatting.DARK_GRAY + "- " + EnumChatFormatting.GRAY + sanitize(s));
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        // Keep it readable in chat; scoreboard strings can be very long with emoji.
        String clean = raw.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "");
        if (clean.length() > 140) {
            clean = clean.substring(0, 140) + "...";
        }
        return clean;
    }

    private static void send(String line) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }
}

