package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Keeps sidebar-only rewrites away from scoreboard teams that contain real players. */
final class ScoreboardTeamSafety {
    private ScoreboardTeamSafety() {
    }

    static boolean containsOnlinePlayer(ScorePlayerTeam team) {
        if (team == null) {
            return false;
        }
        Set<String> onlineNames = collectOnlineNames(Minecraft.getMinecraft());
        if (onlineNames.isEmpty()) {
            // During world/tab initialization we cannot prove that a team is sidebar-only.
            return true;
        }
        try {
            Collection<String> members = team.getMembershipCollection();
            if (members == null) {
                return true;
            }
            for (String member : members) {
                String clean = cleanName(member);
                if (!clean.isEmpty() && onlineNames.contains(clean.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    static boolean isOnlinePlayerName(String value) {
        String clean = cleanName(value);
        return !clean.isEmpty()
                && collectOnlineNames(Minecraft.getMinecraft()).contains(clean.toLowerCase(Locale.ROOT));
    }

    private static Set<String> collectOnlineNames(Minecraft mc) {
        HashSet<String> names = new HashSet<>();
        if (mc == null) {
            return names;
        }
        try {
            if (mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info != null && info.getGameProfile() != null) {
                        addName(names, info.getGameProfile().getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.thePlayer != null) {
                addName(names, mc.thePlayer.getName());
            }
            if (mc.theWorld != null) {
                for (EntityPlayer player : mc.theWorld.playerEntities) {
                    if (player != null) {
                        addName(names, player.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private static void addName(Set<String> names, String value) {
        String clean = cleanName(value);
        if (!clean.isEmpty()) {
            names.add(clean.toLowerCase(Locale.ROOT));
        }
    }

    private static String cleanName(String value) {
        if (value == null) {
            return "";
        }
        String clean = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        return clean == null ? "" : clean.trim();
    }
}
