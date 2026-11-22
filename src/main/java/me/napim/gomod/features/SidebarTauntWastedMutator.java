package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Hypixel Blitz sidebar taunt row tweaks: {@code USED → WASTED} after a failed taunt kill window, or
 * {@code §cUSED → §aUSED} (light green) after a successful Taunt Bonus, without flashing when the row
 * already reads {@code WASTED} (finder must match both USED and WASTED).
 */
public final class SidebarTauntWastedMutator {

    /** Plain sidebar text after stripping {@code §} — matches taunt status line we may rewrite. */
    private static final Pattern TAUNT_USED_OR_WASTED_PLAIN =
            Pattern.compile("(?i).*taunt\\s*:\\s*(used|wasted).*");

    private static Applied applied;

    private static final class Applied {
        final Scoreboard board;
        final String teamName;
        final String originalPrefix;
        final String originalSuffix;

        Applied(Scoreboard board, String teamName, String originalPrefix, String originalSuffix) {
            this.board = board;
            this.teamName = teamName;
            this.originalPrefix = originalPrefix == null ? "" : originalPrefix;
            this.originalSuffix = originalSuffix == null ? "" : originalSuffix;
        }
    }

    /** Debug: is a sidebar taunt rewrite currently applied? */
    public static boolean isApplied() {
        return applied != null;
    }

    public static String debugAppliedTeamName() {
        Applied cur = applied;
        return cur == null ? null : cur.teamName;
    }

    public static void forceRestore() {
        Applied cur = applied;
        applied = null;
        if (cur == null) {
            return;
        }
        try {
            Scoreboard board = cur.board;
            if (board == null) {
                return;
            }
            ScorePlayerTeam team = board.getTeam(cur.teamName);
            if (team != null && !ScoreboardTeamSafety.containsOnlinePlayer(team)) {
                team.setNamePrefix(cur.originalPrefix);
                team.setNameSuffix(cur.originalSuffix);
            }
            ScoreboardDateHider.forceScoreboardRefresh();
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("SidebarTauntWastedMutator")) {
            forceRestore();
            return;
        }
        long t0 = FeatureProfiler.begin("SidebarTauntWastedMutator");
        try {
            refresh();
        } finally {
            FeatureProfiler.end("SidebarTauntWastedMutator", t0);
        }
    }

    /**
     * Weave {@link TickEvent} is rare on some Forge + Weave setups; also run right before overlay paint so the
     * sidebar matches without waiting for a client tick.
     */
    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!ClientTickHub.isFeatureEnabled("SidebarTauntWastedMutator")) {
            return;
        }
        try {
            refresh();
        } catch (Throwable ignored) {
        }
    }

    private static void refresh() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ConfigManager.isHideScoreboardValuesEnabled()
                || !ConfigManager.isSidebarTauntWastedEnabled()
                || !ScoreboardDateHider.isHypixelSidebarTweaksContext(mc)
                || mc.theWorld == null
                || mc.thePlayer == null) {
            forceRestore();
            return;
        }
        if (!GameContext.isOnHypixel(mc) || !BlitzTeamsDetector.sidebarHasTaunt(mc)) {
            forceRestore();
            return;
        }

        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            forceRestore();
            return;
        }

        TauntHit hit = findTauntStatusRow(board, objective);
        if (hit == null || ScoreboardTeamSafety.isOnlinePlayerName(hit.entry)
                || ScoreboardTeamSafety.containsOnlinePlayer(hit.team)) {
            forceRestore();
            return;
        }

        if (applied != null) {
            if (applied.board != board || !applied.teamName.equals(hit.team.getRegisteredName())) {
                forceRestore();
                hit = findTauntStatusRow(board, objective);
                if (hit == null) {
                    forceRestore();
                    return;
                }
            }
        }

        boolean wantWasted = PlayerTarget.isLocalTauntWastedState();
        boolean wantSuccessGreen = PlayerTarget.isLocalTauntSuccessSidebarState();

        if (wantWasted) {
            applyWasted(board, hit);
            return;
        }
        if (wantSuccessGreen) {
            applySuccessGreenUsed(board, hit);
            return;
        }

        forceRestore();
    }

    private static void applyWasted(Scoreboard board, TauntHit hit) {
        String plain = plainFormattedLine(hit);
        if (plain != null && plain.toUpperCase(Locale.ROOT).contains("WASTED")) {
            return;
        }

        String prefix = hit.team.getColorPrefix();
        String suffix = hit.team.getColorSuffix();
        String newPrefix = rewriteUsedToWasted(prefix);
        String newSuffix = rewriteUsedToWasted(suffix);
        if (newPrefix.equals(prefix) && newSuffix.equals(suffix)) {
            return;
        }
        if (applied == null) {
            applied = new Applied(board, hit.team.getRegisteredName(), prefix, suffix);
        }
        hit.team.setNamePrefix(newPrefix);
        hit.team.setNameSuffix(newSuffix);
        ScoreboardDateHider.forceScoreboardRefresh();
    }

    private static void applySuccessGreenUsed(Scoreboard board, TauntHit hit) {
        String plain = plainFormattedLine(hit);
        if (plain != null && plain.toUpperCase(Locale.ROOT).contains("WASTED")) {
            return;
        }

        String prefix = hit.team.getColorPrefix();
        String suffix = hit.team.getColorSuffix();
        String newPrefix = rewriteRedUsedToGreenUsed(prefix);
        String newSuffix = rewriteRedUsedToGreenUsed(suffix);
        if (newPrefix.equals(prefix) && newSuffix.equals(suffix)) {
            return;
        }
        if (applied == null) {
            applied = new Applied(board, hit.team.getRegisteredName(), prefix, suffix);
        }
        hit.team.setNamePrefix(newPrefix);
        hit.team.setNameSuffix(newSuffix);
        ScoreboardDateHider.forceScoreboardRefresh();
    }

    private static String plainFormattedLine(TauntHit hit) {
        String line = ScorePlayerTeam.formatPlayerName(hit.team, hit.entry);
        if (line == null) {
            return null;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
        if (plain == null) {
            plain = line;
        }
        return plain.replace('\u00A0', ' ').trim();
    }

    private static String rewriteUsedToWasted(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        if (!s.toUpperCase(Locale.ROOT).contains("USED")) {
            return s;
        }
        return s.replaceFirst("(?i)USED", "WASTED");
    }

    /**
     * Hypixel uses red ({@code §c}) before {@code USED}; swap to green ({@code §a}) for a successful taunt kill.
     */
    private static String rewriteRedUsedToGreenUsed(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        if (!s.toUpperCase(Locale.ROOT).contains("USED")) {
            return s;
        }
        String red = EnumChatFormatting.RED.toString();
        String darkRed = EnumChatFormatting.DARK_RED.toString();
        String green = EnumChatFormatting.GREEN.toString();
        String t = s;
        t = t.replace(red + "USED", green + "USED");
        t = t.replace(darkRed + "USED", green + "USED");
        return t;
    }

    private static final class TauntHit {
        final ScorePlayerTeam team;
        final String entry;

        TauntHit(ScorePlayerTeam team, String entry) {
            this.team = team;
            this.entry = entry;
        }
    }

    private static TauntHit findTauntStatusRow(Scoreboard board, ScoreObjective objective) {
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective);
        for (Score score : visibleScores) {
            if (score == null || score.getPlayerName() == null) {
                continue;
            }
            String entry = score.getPlayerName();
            ScorePlayerTeam team = board.getPlayersTeam(entry);
            String line = ScorePlayerTeam.formatPlayerName(team, entry);
            if (line == null) {
                continue;
            }
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
            if (plain == null) {
                plain = line;
            }
            plain = plain.replace('\u00A0', ' ').trim();
            if (TAUNT_USED_OR_WASTED_PLAIN.matcher(plain).matches() && team != null
                    && !ScoreboardTeamSafety.isOnlinePlayerName(entry)
                    && !ScoreboardTeamSafety.containsOnlinePlayer(team)) {
                return new TauntHit(team, entry);
            }
        }
        return null;
    }
}
