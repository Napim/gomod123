package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.List;
import me.napim.gomod.util.MinecraftFormatting;
import me.napim.gomod.util.SidebarSnapshot;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Shared sidebar row filtering so {@link SidebarKeOverlay} layout matches vanilla after
 * {@code GuiIngameMixin#gomod$omitHiddenScoreboardRows} removes hidden date/kit lines.
 */
public final class SidebarScoreboardLayout {

    private SidebarScoreboardLayout() {
    }

    /**
     * Same omission rule as {@code GuiIngameMixin#gomod$shouldOmitScoreRow} (hide date/game ID / kit rows).
     */
    public static boolean shouldOmitSidebarRow(Scoreboard board, Score score) {
        if (!ConfigManager.isHideScoreboardValuesEnabled() || !ScoreboardDateHider.isHypixelSidebarTweaksContext()) {
            return false;
        }
        try {
            String line = formattedRow(board, score);
            if (ConfigManager.isHideDateGameIdEnabled() && ScoreboardDateHider.isDateOrGameIdText(line)) {
                return true;
            }
            if (ConfigManager.isHideKitScoreboardEnabled() && ScoreboardDateHider.isKitSidebarText(line)) {
                // Keep the kit anchor row when KE line injection is active; KE mutators/overlays locate
                // the insertion point via the original Kit row + spacer.
                return !BlitzFlameKillEffectTracker.shouldInjectSidebarKillEffectLine();
            }
            if (ConfigManager.isHideHypixelFooterEnabled() && ScoreboardDateHider.isHypixelFooterText(line)) {
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * After {@link #shouldOmitSidebarRow} removes date/game-id rows, the first remaining rows can still be empty
     * (blanked team text) or decorative emoji-only separators. Drops those from the start of {@code kept} in place.
     */
    public static void trimTopBlankSeparators(Scoreboard board, List<Score> kept) {
        if (board == null || kept == null || kept.isEmpty() || !ScoreboardDateHider.isHypixelSidebarTweaksContext()) {
            return;
        }
        while (!kept.isEmpty() && isVisuallyBlankSidebarRow(board, kept.get(0))) {
            kept.remove(0);
        }
    }

    private static boolean isVisuallyBlankSidebarRow(Scoreboard board, Score score) {
        if (score == null) {
            return false;
        }
        try {
            String playerName = score.getPlayerName();
            if (playerName == null) {
                return false;
            }
            return isVisuallyBlankSidebarText(formattedRow(board, score));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String formattedRow(Scoreboard board, Score score) {
        String line = SidebarSnapshot.get(board, score.getObjective()).formatted(score);
        if (line != null) {
            return line;
        }
        String name = score.getPlayerName();
        return ScorePlayerTeam.formatPlayerName(board.getPlayersTeam(name), name);
    }

    /**
     * True when the line shows no letters or digits after stripping {@code §} codes — empty, whitespace-only,
     * punctuation, or emoji-only separator rows.
     */
    private static boolean isVisuallyBlankSidebarText(String raw) {
        if (raw == null) {
            return true;
        }
        String s = MinecraftFormatting.stripSectionCodes(raw);
        s = s.replace('\u00A0', ' ').trim();
        if (s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Rows vanilla would paint for the right-hand scoreboard: same order as {@link Scoreboard#getSortedScores},
     * minus {@code #} entries, minus omitted rows when hide-scoreboard-values is on, then at most the last 15.
     */
    public static List<Score> visibleScoresForSidebarLayout(Scoreboard board, ScoreObjective objective) {
        ArrayList<Score> out = new ArrayList<>();
        if (board == null || objective == null) {
            return out;
        }
        List<Score> sorted = SidebarSnapshot.get(board, objective).scores();
        if (sorted == null || sorted.isEmpty()) {
            return out;
        }
        for (Score sc : sorted) {
            if (sc == null || sc.getPlayerName() == null || sc.getPlayerName().startsWith("#")) {
                continue;
            }
            if (shouldOmitSidebarRow(board, sc)) {
                continue;
            }
            out.add(sc);
        }
        if (out.size() > 15) {
            return new ArrayList<>(out.subList(out.size() - 15, out.size()));
        }
        return out;
    }
}
