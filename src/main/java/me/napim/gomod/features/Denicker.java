package me.napim.gomod.features;

import java.util.UUID;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

/**
 * TAB duplicate-nick resolver, real-IGN nametag overlay, and denicking tools (Miscellaneous → Denicker Tools).
 */
public final class Denicker {

    public static final UUID PREVIEW_SKIN_UUID = UUID.fromString("aac216da-db31-4495-8a1a-b3b97d1922b2");
    public static final String PREVIEW_NICK = "TheCrazyNick";
    public static final String PREVIEW_REAL = "FireRapidGamer25";

    private Denicker() {
    }

    public static boolean isEnabled() {
        return ConfigManager.isDenickerEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setDenickerEnabled(enabled);
        applyRuntime();
    }

    /** Sync {@link ClientTickHub} after config load or toggle. */
    public static void applyRuntime() {
        if (isEnabled()) {
            ClientTickHub.enableFeature("TabDuplicateNickResolver");
        } else {
            ClientTickHub.disableFeature("TabDuplicateNickResolver");
        }
    }

    public static String formatResolvedTag(String tabNick, String realIgnPlain) {
        if (realIgnPlain == null || realIgnPlain.trim().isEmpty()) {
            return "";
        }
        String trimmedReal = realIgnPlain.trim();
        if (ConfigManager.isDenickerRankTagMode() && shouldUseRankNametagInWorld()) {
            String rankLine = HypixelTabStatsRankCache.formatNametagRankLinePreferKeys(tabNick, trimmedReal);
            if (rankLine != null && !rankLine.isEmpty()) {
                return rankLine;
            }
        }
        return EnumChatFormatting.GOLD + "(" + trimmedReal + ")" + EnumChatFormatting.RESET;
    }

    /**
     * When TAB QoL → Players stats scan → When cages open is on, rank-style denicked nametags wait until cages open;
     * pregame and cage countdown always use gold {@code (realIgn)} even if rank mode is selected.
     */
    private static boolean shouldUseRankNametagInWorld() {
        if (KillCounter.getAutoScanMode() != KillCounter.AutoScanMode.CAGES_OPEN) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc)) {
            return true;
        }
        return !GameContext.isBlitzPregameLobby(mc) && !GameContext.isBlitzCagePhaseSidebar(mc);
    }

    /** Sample tag for the settings GUI preview (no API). */
    public static String formatPreviewResolvedTag() {
        if (ConfigManager.isDenickerRankTagMode()) {
            return formatPreviewRankTag();
        }
        return EnumChatFormatting.GOLD + "(" + PREVIEW_REAL + ")" + EnumChatFormatting.RESET;
    }

    public static String formatPreviewRankTag() {
        return EnumChatFormatting.AQUA + "[MVP"
                + EnumChatFormatting.RED + "++"
                + EnumChatFormatting.AQUA + "] "
                + EnumChatFormatting.AQUA + PREVIEW_REAL
                + EnumChatFormatting.RESET;
    }

    public static String getTagModeButtonLabel() {
        return "Nick nametag: " + formatPreviewResolvedTag();
    }

    public static boolean isRevealRealSkinEnabled() {
        return ConfigManager.isDenickerRevealRealSkinEnabled();
    }

    public static void setRevealRealSkinEnabled(boolean enabled) {
        ConfigManager.setDenickerRevealRealSkinEnabled(enabled);
    }

    public static String getRevealRealSkinButtonLabel() {
        return "Reveal real skin: "
                + (isRevealRealSkinEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    public static boolean isShowRealIgnInTabEnabled() {
        return ConfigManager.isDenickerShowRealIgnInTabEnabled();
    }

    public static void setShowRealIgnInTabEnabled(boolean enabled) {
        ConfigManager.setDenickerShowRealIgnInTabEnabled(enabled);
    }

    public static String getShowRealIgnInTabButtonLabel() {
        return "Reveal nick's IGN in TAB: "
                + (isShowRealIgnInTabEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    public static boolean isNickRenamedItemsEnabled() {
        return ConfigManager.isNickRenamedItemScannerEnabled();
    }

    public static void setNickRenamedItemsEnabled(boolean enabled) {
        ConfigManager.setNickRenamedItemScannerEnabled(enabled);
    }

    public static String getNickRenamedItemsButtonLabel() {
        return "Nick Renamed Items: "
                + (isNickRenamedItemsEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    public static boolean isDeathSearchNickEnabled() {
        return ConfigManager.isNickDeathSearchLinkEnabled();
    }

    public static void setDeathSearchNickEnabled(boolean enabled) {
        ConfigManager.setNickDeathSearchLinkEnabled(enabled);
    }

    public static String getDeathSearchNickButtonLabel() {
        return "Death [SEARCH NICK]: "
                + (isDeathSearchNickEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    /** Gold {@code (realIgn)} suffix for TAB — always parentheses, independent of nametag rank mode. */
    public static String formatTabRealIgnSuffix(String realIgnPlain) {
        if (realIgnPlain == null || realIgnPlain.trim().isEmpty()) {
            return "";
        }
        return EnumChatFormatting.RESET + EnumChatFormatting.GOLD.toString() + "(" + realIgnPlain.trim() + ")"
                + EnumChatFormatting.RESET;
    }
}
