package me.napim.gomod.features;

import me.napim.gomod.util.MinecraftFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;

/**
 * Lobby ping on TAB: draws {@code NNNms} in place of the vanilla ping bars (Patcher-style), slightly smaller than
 * default tab text. Works on any multiplayer server.
 */
public final class LobbyPingDisplay {

    /** Slightly smaller than vanilla tab text ({@code 0.5f} in Patcher is much smaller). */
    private static final float TAB_PING_ICON_TEXT_SCALE = 0.72f;
    private static final int MAX_PING = 9999;
    private static final String[] TAB_PING_LABELS = new String[MAX_PING + 1];
    private static final int[] TAB_PING_WIDTHS = new int[MAX_PING + 1];
    private static FontRenderer measuredFont;
    private static boolean measuredUnicode;
    private static boolean measuredBidi;

    private LobbyPingDisplay() {
    }

    public static boolean isEnabled() {
        return ConfigManager.isLobbyPingDisplayEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setLobbyPingDisplayEnabled(enabled);
    }

    public static String getToggleLabel() {
        return isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF";
    }

    /** When true, ping is drawn at the TAB ping-icon column instead of appended after the player line. */
    public static boolean usesTabPingIconReplacement() {
        return shouldRender(Minecraft.getMinecraft());
    }

    /**
     * Ping is rendered in the icon column. Keep the original player text for existing callers.
     */
    public static String appendTabPingIfEnabled(NetworkPlayerInfo info, String tabLine) {
        return tabLine;
    }

    /**
     * Replaces vanilla TAB ping bars with colored {@code NNNms} text (includes {@code ms}).
     *
     * @return {@code true} if drawn and vanilla {@code drawPing} should be skipped
     */
    public static boolean drawTabPingAtIcon(Minecraft mc, int offset, int xPosition, int yPosition,
            NetworkPlayerInfo info) {
        if (mc == null || mc.fontRendererObj == null || info == null || !shouldRender(mc)) {
            return false;
        }
        int ping = sanitizePing(info.getResponseTime());
        if (ping < 0) {
            return true;
        }
        String label = TAB_PING_LABELS[ping];
        if (label == null) {
            label = ping + "ms";
            TAB_PING_LABELS[ping] = label;
        }
        FontRenderer fr = mc.fontRendererObj;
        int color = colorRgbForPing(ping);
        float scale = TAB_PING_ICON_TEXT_SCALE;
        int textW = scaledLabelWidth(fr, ping, label);
        int x = xPosition + offset - textW / 2;
        int y = yPosition + 2;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.translate(x, y, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            fr.drawStringWithShadow(label, 0, 0, color);
        } finally {
            GlStateManager.popMatrix();
        }
        return true;
    }

    public static void onFontReload() {
        measuredFont = null;
    }

    private static int scaledLabelWidth(FontRenderer font, int ping, String label) {
        boolean unicode = font.getUnicodeFlag();
        boolean bidi = font.getBidiFlag();
        if (measuredFont != font || measuredUnicode != unicode || measuredBidi != bidi) {
            Arrays.fill(TAB_PING_WIDTHS, 0);
            measuredFont = font;
            measuredUnicode = unicode;
            measuredBidi = bidi;
        }
        // A width of zero is valid for custom fonts, so zero marks a miss and width plus one is stored.
        int width = TAB_PING_WIDTHS[ping];
        if (width == 0) {
            width = (int) (font.getStringWidth(label) * TAB_PING_ICON_TEXT_SCALE) + 1;
            TAB_PING_WIDTHS[ping] = width;
        }
        return width - 1;
    }

    /**
     * True if the formatted line already ends with a ping-style {@code …NNNms} chunk (e.g. from {@link #appendTabPingIfEnabled}
     * or a previous compose pass).
     */
    public static boolean lineAlreadyShowsPingMs(String tabLine) {
        if (tabLine == null || tabLine.isEmpty()) {
            return false;
        }
        String stripped = MinecraftFormatting.stripSectionCodes(tabLine).trim();
        int suffix = stripped.length() - 2;
        if (suffix < 1 || (stripped.charAt(suffix) | 32) != 'm'
                || (stripped.charAt(suffix + 1) | 32) != 's') return false;
        char lastDigit = stripped.charAt(suffix - 1);
        return lastDigit >= '0' && lastDigit <= '9';
    }

    /** @deprecated Ping is appended to the name line via {@link #appendTabPingIfEnabled}. */
    @Deprecated
    public static String decorateTabName(NetworkPlayerInfo info, String originalName) {
        return originalName;
    }

    public static String decorateNametag(EntityPlayer entity, String originalName) {
        return originalName;
    }

    private static int colorRgbForPing(int ping) {
        if (ping <= 64) return 0x55FF55;
        if (ping <= 120) return 0xFFFF55;
        if (ping <= 250) return 0xFF5555;
        return 0xAA0000;
    }

    private static boolean shouldRender(Minecraft mc) {
        if (!isEnabled()) {
            return false;
        }

        if (mc == null || mc.isSingleplayer() || mc.theWorld == null || mc.getNetHandler() == null) {
            return false;
        }

        return !ReplaySettings.isReplayActive();
    }

    private static int sanitizePing(int ping) {
        // Hide bogus / placeholder values (vanilla often reports 0–1ms in lobby when not meaningful).
        if (ping <= 1) {
            return -1;
        }
        return Math.min(ping, MAX_PING);
    }
}
