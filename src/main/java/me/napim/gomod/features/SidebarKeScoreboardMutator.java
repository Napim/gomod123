package me.napim.gomod.features;

import me.napim.gomod.features.BlitzTeamsDetector;
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

/**
 * Rewrites the real sidebar spacer row under {@code Kit:} into {@code KE: ...} by mutating the client scoreboard
 * data itself. This survives custom sidebar renderers / movers that bypass {@code GuiIngame.renderScoreboard}.
 */
public final class SidebarKeScoreboardMutator {

    private static final String[] INVISIBLE_ENTRY_CANDIDATES = {
            "\u00A7r", "\u00A70", "\u00A71", "\u00A72", "\u00A73", "\u00A74", "\u00A75", "\u00A76", "\u00A77",
            "\u00A78", "\u00A79", "\u00A7a", "\u00A7b", "\u00A7c", "\u00A7d", "\u00A7e", "\u00A7f",
            "\u00A7r\u00A70", "\u00A7r\u00A71", "\u00A7r\u00A72", "\u00A7r\u00A73", "\u00A7r\u00A74",
            "\u00A7r\u00A75", "\u00A7r\u00A76", "\u00A7r\u00A77", "\u00A7r\u00A78", "\u00A7r\u00A79"
    };

    private static AppliedMutation applied;

    public SidebarKeScoreboardMutator() {
    }

    public static boolean isSidebarRowMutationActive() {
        return applied != null;
    }

    /**
     * Immediately undo client scoreboard mutation for the KE row. Weave tick/overlay events may not run on Forge;
     * call this when the user disables sidebar KE in settings so the line disappears at once.
     */
    public static void forceRestoreSidebarMutation() {
        restoreApplied();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("SidebarKeScoreboardMutator")) return;
        long _profT = FeatureProfiler.begin("SidebarKeScoreboardMutator");
        try { refresh(false); } finally { FeatureProfiler.end("SidebarKeScoreboardMutator", _profT); }
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        refresh(true);
    }

    private static void refresh(boolean forceBeforeRender) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
            restoreApplied();
            return;
        }

        Scoreboard board = mc.theWorld.getScoreboard();
        if (board == null) {
            restoreApplied();
            return;
        }

        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        String keLine = BlitzFlameKillEffectTracker.getFormattedSidebarKillEffectLine();
        if (!shouldMutateSidebarRow(mc, objective, keLine)) {
            restoreApplied();
            return;
        }
        // Blitz Teams active match: Taunt is the top row — scoreboard mutator cannot insert KE above it.
        // Fall back to {@code GuiIngameMixin#gomod$renderSidebarWithInjectedKeRow} (draws KE above Taunt).
        if (BlitzTeamsDetector.isActiveBlitzTeamsGame(mc)) {
            restoreApplied();
            BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(false);
            return;
        }

        if (applied != null) {
            if (applied.matchesCurrent(board, objective, keLine) && applied.isStillApplied()) {
                if (forceBeforeRender) {
                    BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(true);
                }
                return;
            }
            restoreApplied();
        }

        SpacerTarget target = findSpacerTarget(board, objective);
        if (target == null || target.team == null || target.originalEntry == null || target.originalEntry.isEmpty()
                || ScoreboardTeamSafety.isOnlinePlayerName(target.originalEntry)
                || ScoreboardTeamSafety.containsOnlinePlayer(target.team)) {
            restoreApplied();
            return;
        }

        restoreApplied();
        applyMutation(board, objective, target, keLine);
    }

    private static boolean shouldMutateSidebarRow(Minecraft mc, ScoreObjective objective, String keLine) {
        if (objective == null || keLine == null || keLine.isEmpty()) {
            return false;
        }
        if (!ConfigManager.isHideScoreboardValuesEnabled()) {
            return false;
        }
        if (!ConfigManager.isSidebarKillEffectLineEnabled()) {
            return false;
        }
        return LocalDisguise.isBlitzSgSidebarActive()
                || GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzPregameLobby(mc)
                || GameContext.isBlitzCagePhaseSidebar(mc);
    }

    private static SpacerTarget findSpacerTarget(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) {
            return null;
        }
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective);
        Score previousScore = null;
        ScorePlayerTeam previousTeam = null;
        String previousLine = null;
        boolean sawKitRow = false;
        boolean kitAnchorWithoutSpacer = false;
        for (int i = 0; i < visibleScores.size(); i++) {
            Score score = visibleScores.get(i);
            if (score == null || score.getPlayerName() == null) {
                continue;
            }
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
                sawKitRow = true;
                if (previousScore != null && isSidebarSpacerLine(previousLine)) {
                    return new SpacerTarget(previousScore, previousTeam);
                }
                // A genuine Kit row may not have a spacer above it; remember that for the Taunt fallback.
                kitAnchorWithoutSpacer = true;
            } else if ((!sawKitRow || kitAnchorWithoutSpacer) && isTauntSidebarText(line)) {
                // If the Kit row is omitted (e.g. Hide Kit), fall back to using the spacer directly above Taunt.
                // This keeps the intended Kit/KE/Taunt ordering (with Kit hidden) by rewriting the Taunt spacer into KE.
                // Sidebar render order is bottom-up; prefer mutating the spacer that renders above the Taunt row.
                if (i + 1 < visibleScores.size()) {
                    Score nextScore = visibleScores.get(i + 1);
                    if (nextScore != null && nextScore.getPlayerName() != null) {
                        ScorePlayerTeam nextTeam = board.getPlayersTeam(nextScore.getPlayerName());
                        String nextLine = ScorePlayerTeam.formatPlayerName(nextTeam, nextScore.getPlayerName());
                        if (isSidebarSpacerLine(nextLine)) {
                            return new SpacerTarget(nextScore, nextTeam);
                        }
                    }
                }
                if (previousScore != null && isSidebarSpacerLine(previousLine)) {
                    return new SpacerTarget(previousScore, previousTeam);
                }
                continue;
            } else if (BlitzTeamsDetector.isBlitzTeamsGame(Minecraft.getMinecraft())
                    && SidebarTeamsLayout.isTeamAnchorLine(line)) {
                if (previousScore != null && isSidebarSpacerLine(previousLine)) {
                    return new SpacerTarget(previousScore, previousTeam);
                }
            } else {
                previousScore = score;
                previousTeam = team;
                previousLine = line;
            }
        }
        return null;
    }

    private static boolean isTauntSidebarText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim().toLowerCase(java.util.Locale.ROOT);
        return plain.contains("taunt:");
    }

    private static boolean isSidebarSpacerLine(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim();
        if (plain.isEmpty()) {
            return true;
        }
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static void applyMutation(Scoreboard board, ScoreObjective objective, SpacerTarget target, String keLine) {
        try {
            if (ScoreboardTeamSafety.isOnlinePlayerName(target.originalEntry)
                    || ScoreboardTeamSafety.containsOnlinePlayer(target.team)) {
                return;
            }
            String invisibleEntry = chooseInvisibleEntry(board, objective, target.originalEntry);
            if (invisibleEntry == null || invisibleEntry.isEmpty()) {
                BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(false);
                return;
            }

            int scorePoints = target.score.getScorePoints();
            String originalPrefix = target.team.getColorPrefix();
            String originalSuffix = target.team.getColorSuffix();
            String teamName = target.team.getRegisteredName();

            if (board.getPlayersTeam(target.originalEntry) == target.team) {
                board.removePlayerFromTeam(target.originalEntry, target.team);
            }
            board.removePlayerFromTeams(invisibleEntry);
            board.removeObjectiveFromEntity(invisibleEntry, objective);
            board.removeObjectiveFromEntity(target.originalEntry, objective);

            board.addPlayerToTeam(invisibleEntry, teamName);
            board.getValueFromObjective(invisibleEntry, objective).setScorePoints(scorePoints);
            target.team.setNamePrefix(keLine);
            target.team.setNameSuffix("");

            applied = new AppliedMutation(board, objective, teamName, target.originalEntry, invisibleEntry, scorePoints,
                    originalPrefix, originalSuffix, keLine);
            BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(true);
            ScoreboardDateHider.forceScoreboardRefresh();
        } catch (Throwable ignored) {
            restoreApplied();
        }
    }

    private static void restoreApplied() {
        AppliedMutation current = applied;
        applied = null;
        BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(false);
        if (current == null) {
            return;
        }
        try {
            Scoreboard board = current.board;
            ScoreObjective objective = current.objective;
            if (board == null || objective == null) {
                return;
            }
            ScorePlayerTeam team = board.getTeam(current.teamName);
            if (team != null && !ScoreboardTeamSafety.containsOnlinePlayer(team)
                    && !ScoreboardTeamSafety.isOnlinePlayerName(current.originalEntry)) {
                team.setNamePrefix(current.originalPrefix);
                team.setNameSuffix(current.originalSuffix);
                board.removePlayerFromTeams(current.mutatedEntry);
                board.removePlayerFromTeams(current.originalEntry);
                board.addPlayerToTeam(current.originalEntry, current.teamName);
            }
            board.removeObjectiveFromEntity(current.mutatedEntry, objective);
            board.removeObjectiveFromEntity(current.originalEntry, objective);
            board.getValueFromObjective(current.originalEntry, objective).setScorePoints(current.scorePoints);
            ScoreboardDateHider.forceScoreboardRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static String chooseInvisibleEntry(Scoreboard board, ScoreObjective objective, String originalEntry) {
        for (String candidate : INVISIBLE_ENTRY_CANDIDATES) {
            if (candidate == null || candidate.isEmpty() || candidate.equals(originalEntry)) {
                continue;
            }
            if (board.getPlayersTeam(candidate) != null) {
                continue;
            }
            if (!board.getObjectivesForEntity(candidate).isEmpty()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static final class SpacerTarget {
        private final Score score;
        private final ScorePlayerTeam team;
        private final String originalEntry;

        private SpacerTarget(Score score, ScorePlayerTeam team) {
            this.score = score;
            this.team = team;
            this.originalEntry = score.getPlayerName();
        }
    }

    private static final class AppliedMutation {
        private final Scoreboard board;
        private final ScoreObjective objective;
        private final String teamName;
        private final String originalEntry;
        private final String mutatedEntry;
        private final int scorePoints;
        private final String originalPrefix;
        private final String originalSuffix;
        private final String keLine;

        private AppliedMutation(Scoreboard board, ScoreObjective objective, String teamName, String originalEntry,
                String mutatedEntry, int scorePoints, String originalPrefix, String originalSuffix, String keLine) {
            this.board = board;
            this.objective = objective;
            this.teamName = teamName;
            this.originalEntry = originalEntry;
            this.mutatedEntry = mutatedEntry;
            this.scorePoints = scorePoints;
            this.originalPrefix = originalPrefix;
            this.originalSuffix = originalSuffix;
            this.keLine = keLine;
        }

        private boolean matchesCurrent(Scoreboard board, ScoreObjective objective, String keLine) {
            return this.board == board
                    && this.objective == objective
                    && this.keLine.equals(keLine);
        }

        private boolean isStillApplied() {
            try {
                if (board == null || objective == null) {
                    return false;
                }
                ScorePlayerTeam team = board.getTeam(teamName);
                if (team == null || !keLine.equals(team.getColorPrefix())) {
                    return false;
                }
                java.util.Map<ScoreObjective, Score> scores = board.getObjectivesForEntity(mutatedEntry);
                Score score = scores == null ? null : scores.get(objective);
                return score != null && score.getScorePoints() == scorePoints
                        && board.getPlayersTeam(mutatedEntry) == team;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
