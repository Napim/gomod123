package me.napim.gomod.features;

import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.util.EnumChatFormatting;

/**
 * Whether the gomod relay / service is reachable (HTTP inbox poll and/or ShareInv websocket).
 */
public final class GomodServiceStatus {

    private static volatile long lastHttpRelayOkMs;
    private static volatile long lastHttpRelayFailMs;
    private static volatile String lastHttpRelayError = "";
    /** Set from relay poll when this session matches server admin list. */
    private static volatile boolean socialAdmin;

    private GomodServiceStatus() {
    }

    public static void markHttpRelaySuccess() {
        lastHttpRelayOkMs = System.currentTimeMillis();
        lastHttpRelayError = "";
    }

    public static void markHttpRelayFailure(String error) {
        lastHttpRelayFailMs = System.currentTimeMillis();
        lastHttpRelayError = error == null ? "" : error;
    }

    public static void setSocialAdmin(boolean admin) {
        socialAdmin = admin;
    }

    /** True when gomod Service marked this player as a Social moderator (thread delete, etc.). */
    public static boolean isSocialAdmin() {
        return socialAdmin;
    }

    /** Recent successful HTTP relay poll (friend DMs, presence, requests). */
    public static boolean isHttpRelayConnected() {
        if (lastHttpRelayOkMs <= 0L) {
            return false;
        }
        long windowMs = (ConfigManager.getGlobalChatPollSeconds() + 4L) * 1000L;
        return System.currentTimeMillis() - lastHttpRelayOkMs < windowMs;
    }

    /** ShareInv websocket or HTTP relay inbox. */
    public static boolean isServiceConnected() {
        return ShareInvManager.isRelayOpen() || isHttpRelayConnected();
    }

    public static String statusBannerText() {
        if (isServiceConnected()) {
            return "";
        }
        if (ShareInvManager.isRelayOpen()) {
            return "";
        }
        return statusBannerLine1() + statusBannerLine2();
    }

    public static String statusBannerLine1() {
        return EnumChatFormatting.RED + "Not connected to gomod Service";
    }

    public static String statusBannerLine2() {
        return EnumChatFormatting.GRAY + "Messages and presence won't sync until relay is back.";
    }

    public static String statusSubtitle() {
        if (isHttpRelayConnected()) {
            return EnumChatFormatting.GRAY + "gomod Service connected";
        }
        if (ShareInvManager.isRelayOpen()) {
            return EnumChatFormatting.GRAY + "gomod Service connected (ShareInv)";
        }
        return EnumChatFormatting.RED + "gomod Service offline";
    }
}
