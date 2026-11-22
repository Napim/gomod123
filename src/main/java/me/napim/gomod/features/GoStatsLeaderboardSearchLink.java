package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.text.NumberFormat;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds a client-side leaderboard search link to Hypixel's self-death kill-count detail. */
public final class GoStatsLeaderboardSearchLink {
    private static final String LINK_TEXT = "[SEARCH NICK]";
    private static final Pattern SELF_DEATH_KILL_COUNT = Pattern.compile(
            "(?i)^\\s*You were killed by\\s+\\[([0-9][0-9,]*)\\]\\s+([A-Za-z0-9_]{3,16})"
                    + "\\b.*\\bwith their\\s+.+?\\s+kit[.!]?\\s*$");

    private GoStatsLeaderboardSearchLink() {
    }

    public static void appendSearchLink(IChatComponent component, String plainMessage) {
        if (!ConfigManager.isNickDeathSearchLinkEnabled()) return;
        if (component == null || plainMessage == null || hasSearchLink(component)) return;
        Matcher matcher = SELF_DEATH_KILL_COUNT.matcher(plainMessage);
        if (!matcher.matches()) return;
        if (!isNickedKiller(matcher.group(2))) return;

        String digits = matcher.group(1).replace(",", "");
        long killCount;
        try {
            killCount = Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return;
        }

        String formattedCount = NumberFormat.getIntegerInstance(Locale.US).format(killCount);
        component.appendSibling(new ChatComponentText(" "));
        ChatComponentText link = new ChatComponentText(EnumChatFormatting.AQUA + LINK_TEXT);
        ChatStyle style = new ChatStyle();
        style.setUnderlined(true);
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/gostatslbsearch " + digits));
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Search the gostats123 leaderboard near "
                        + EnumChatFormatting.WHITE + formattedCount + EnumChatFormatting.YELLOW + " kills")));
        link.setChatStyle(style);
        component.appendSibling(link);
    }

    /** Only expose kill-count search on a real Hypixel nick profile, not ordinary player names. */
    private static boolean isNickedKiller(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null || !GameContext.isOnHypixel(mc)) {
            return false;
        }

        boolean foundMatchingProfile = false;
        boolean foundGeneratedNickProfile = false;
        boolean foundOrdinaryProfile = false;
        try {
            Collection<NetworkPlayerInfo> entries = mc.getNetHandler().getPlayerInfoMap();
            if (entries != null) {
                for (NetworkPlayerInfo info : entries) {
                    GameProfile profile = RemoteDisguiseManager.getSourceProfile(info);
                    if (profile == null || profile.getName() == null
                            || !profileName.equalsIgnoreCase(profile.getName())) continue;
                    foundMatchingProfile = true;
                    UUID profileUuid = profile.getId();
                    if (profileUuid != null
                            && RecentOpponentRecordOps.isGeneratedNickUuid(profileUuid.toString())) {
                        foundGeneratedNickProfile = true;
                    } else {
                        foundOrdinaryProfile = true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // If TAB is changing while the chat packet is handled, fail closed for this message.
            return false;
        }

        // A same-name real account makes the kill message ambiguous; only show the action when
        // every matching live profile is a generated nick UUID.
        if (foundMatchingProfile) return foundGeneratedNickProfile && !foundOrdinaryProfile;

        String resolvedRealName = TabDuplicateNickResolver.getKnownRealName(profileName);
        return resolvedRealName != null && !resolvedRealName.trim().isEmpty()
                && !resolvedRealName.equalsIgnoreCase(profileName.trim());
    }

    private static boolean hasSearchLink(IChatComponent component) {
        try {
            for (IChatComponent sibling : component.getSiblings()) {
                if (sibling != null && LINK_TEXT.equals(sibling.getUnformattedText())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
