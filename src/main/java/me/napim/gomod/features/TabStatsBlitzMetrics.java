package me.napim.gomod.features;

import com.google.gson.JsonObject;
import net.minecraft.util.EnumChatFormatting;

/**
 * Parsed Hypixel Blitz {@code HungerGames} stats for TAB suffix formatting (KD / WL / W).
 */
public final class TabStatsBlitzMetrics {

    public final String colorPrefix;
    public final double wlRatio;
    public final int totalWins;
    public final int kills;
    public final int deaths;

    public TabStatsBlitzMetrics(String colorPrefix, double wlRatio, int totalWins, int kills, int deaths) {
        this.colorPrefix = colorPrefix == null ? "" : colorPrefix;
        this.wlRatio = wlRatio;
        this.totalWins = Math.max(0, totalWins);
        this.kills = Math.max(0, kills);
        this.deaths = Math.max(0, deaths);
    }

    public static TabStatsBlitzMetrics fromHypixel(JsonObject blitzStats) {
        JsonObject stats = blitzStats != null ? blitzStats : new JsonObject();
        int soloWins = stats.has("wins") ? stats.get("wins").getAsInt() : 0;
        int teamWins = stats.has("wins_teams_normal") ? stats.get("wins_teams_normal").getAsInt() : 0;
        int totalWins = soloWins + teamWins;
        int deaths = stats.has("deaths") ? stats.get("deaths").getAsInt() : 0;
        int kills = stats.has("kills") ? stats.get("kills").getAsInt() : 0;
        double wl = deaths <= 0 ? (double) totalWins : (double) totalWins / (double) deaths;
        return new TabStatsBlitzMetrics(colorPrefixForWl(wl), wl, totalWins, kills, deaths);
    }

    /** Settings preview / color picker sample row (KD example {@code 4.60}, WL {@code 0.52}, wins {@code 700w}). */
    public static TabStatsBlitzMetrics previewSample() {
        return new TabStatsBlitzMetrics(colorPrefixForWl(0.52), 0.52, 700, 2302, 500);
    }

    public static String colorPrefixForWl(double wl) {
        if (wl >= 0.70) {
            return EnumChatFormatting.DARK_RED.toString() + EnumChatFormatting.BOLD + EnumChatFormatting.UNDERLINE;
        }
        if (wl >= 0.35) {
            return EnumChatFormatting.DARK_RED.toString();
        }
        if (wl >= 0.20) {
            return EnumChatFormatting.DARK_PURPLE.toString();
        }
        if (wl >= 0.10) {
            return EnumChatFormatting.WHITE.toString();
        }
        return EnumChatFormatting.GRAY.toString();
    }

    /** Kill/death ratio (Blitz {@code kills} / {@code deaths}). */
    public double killDeathRatio() {
        if (deaths <= 0) {
            return kills <= 0 ? 0.0 : (double) kills;
        }
        return (double) kills / (double) deaths;
    }

    public String formatSuffix(boolean showKd, boolean showWl, boolean showWins) {
        if (!showKd && !showWl && !showWins) {
            return "";
        }
        StringBuilder inner = new StringBuilder();
        if (showKd) {
            inner.append(formatRatio(killDeathRatio())).append("kd");
        }
        if (showWl) {
            if (inner.length() > 0) {
                inner.append('/');
            }
            inner.append(formatRatio(wlRatio)).append("wl");
        }
        if (showWins) {
            if (inner.length() > 0) {
                inner.append('/');
            }
            inner.append(totalWins).append('w');
        }
        if (inner.length() == 0) {
            return "";
        }
        // WL tier colors (incl. BOLD) apply only when WL is shown; KD-only / W-only use plain dark red.
        String bracketColor = showWl ? colorPrefix : EnumChatFormatting.DARK_RED.toString();
        return bracketColor + "[" + inner + "]" + EnumChatFormatting.RESET;
    }

    /** Full bracket with KD, WL, and wins (e.g. chat / tabstats detail). */
    public String formatFull() {
        return formatSuffix(true, true, true);
    }

    private static String formatRatio(double value) {
        if (value == 0.0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        double rounded1 = Math.floor(value * 10.0 + 0.5) / 10.0;
        if (Math.abs(value - rounded1) < 0.0001) {
            return String.format(java.util.Locale.US, "%.1f", rounded1);
        }
        double rounded2 = Math.floor(value * 100.0 + 0.5) / 100.0;
        return String.format(java.util.Locale.US, "%.2f", rounded2);
    }
}
