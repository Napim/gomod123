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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites Blitz disguise stat rows by mutating the client scoreboard data itself.
 * This follows the same approach as {@link SidebarKeScoreboardMutator} so it survives custom sidebar renderers.
 */
public final class DisguiseSidebarScoreboardMutator {

    private static final String[] INVISIBLE_ENTRY_CANDIDATES = {
            "\u00A7r", "\u00A70", "\u00A71", "\u00A72", "\u00A73", "\u00A74", "\u00A75", "\u00A76", "\u00A77",
            "\u00A78", "\u00A79", "\u00A7a", "\u00A7b", "\u00A7c", "\u00A7d", "\u00A7e", "\u00A7f",
            "\u00A7r\u00A70", "\u00A7r\u00A71", "\u00A7r\u00A72", "\u00A7r\u00A73", "\u00A7r\u00A74",
            "\u00A7r\u00A75", "\u00A7r\u00A76", "\u00A7r\u00A77", "\u00A7r\u00A78", "\u00A7r\u00A79",
            "\u00A7r\u00A7a", "\u00A7r\u00A7b", "\u00A7r\u00A7c", "\u00A7r\u00A7d", "\u00A7r\u00A7e", "\u00A7r\u00A7f"
    };

    private static List<AppliedMutation> applied = new ArrayList<>();

    public static String debugSummary() {
        StringBuilder out = new StringBuilder("appliedRows=" + applied.size());
        for (AppliedMutation mutation : applied) {
            out.append(" | entry=").append(mutation.originalEntry).append(" originalScore=")
                    .append(mutation.originalScore).append(" appliedScore=").append(mutation.appliedScore)
                    .append(" lineMutation=").append(mutation.lineMutation).append(" stillApplied=").append(mutation.isStillApplied());
            if (out.length() > 1600) break;
        }
        return out.toString();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("DisguiseSidebarScoreboardMutator")) return;
        long _profT = FeatureProfiler.begin("DisguiseSidebarScoreboardMutator");
        try { refresh(); } finally { FeatureProfiler.end("DisguiseSidebarScoreboardMutator", _profT); }
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        // Hypixel sends sidebar/team updates independently of client ticks. On Lunar/Weave that can leave the
        // original stats visible until the next tick (or longer when the loader's tick event is delayed). Run the
        // cheap validity check immediately before the HUD is painted so a server refresh cannot flash real stats.
        if (!ClientTickHub.isFeatureEnabled("DisguiseSidebarScoreboardMutator")) {
            return;
        }
        long _profT = FeatureProfiler.begin("DisguiseSidebarScoreboardMutator.render");
        try {
            refresh();
        } finally {
            FeatureProfiler.end("DisguiseSidebarScoreboardMutator.render", _profT);
        }
    }

    private static void refresh() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
            restoreApplied();
            return;
        }
        Scoreboard board = mc.theWorld.getScoreboard();
        ScoreObjective objective = board == null ? null : board.getObjectiveInDisplaySlot(1);
        if (board == null || objective == null || !LocalDisguise.shouldApplyAuxiliaryOverrides()
                || !LocalDisguise.shouldOverrideBlitzLobbySidebar(objective)) {
            restoreApplied();
            return;
        }

        String signature = LocalDisguise.getSidebarMutationSignature();
        if (matchesAppliedContext(board, objective, signature) && areAppliedMutationsStillValid()) {
            return;
        }
        if (applied != null && !applied.isEmpty()) {
            restoreApplied();
        }

        List<RowPlan> plans = buildRowPlans(board, objective);
        if (plans.isEmpty()) {
            return;
        }
        applyPlans(board, objective, plans, signature);
    }

    private static List<RowPlan> buildRowPlans(Scoreboard board, ScoreObjective objective) {
        ArrayList<RowPlan> plans = new ArrayList<>();
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective);
        for (Score score : visibleScores) {
            if (score == null || score.getPlayerName() == null) {
                continue;
            }
            String entry = score.getPlayerName();
            ScorePlayerTeam team = board.getPlayersTeam(entry);
            if (ScoreboardTeamSafety.isOnlinePlayerName(entry)
                    || ScoreboardTeamSafety.containsOnlinePlayer(team)) {
                continue;
            }
            String originalLine = ScorePlayerTeam.formatPlayerName(team, entry);
            if (originalLine == null) {
                originalLine = entry;
            }
            String rewrittenLine = LocalDisguise.rewriteSidebarLine(objective, originalLine, entry);
            int originalScore = score.getScorePoints();
            int rewrittenScore = LocalDisguise.rewriteSidebarScorePoints(objective, score, originalScore);
            boolean lineChanged = !rewrittenLine.equals(originalLine);
            boolean scoreChanged = rewrittenScore != originalScore;
            if (!lineChanged && !scoreChanged) {
                continue;
            }
            boolean hideScore = false;
            if (lineChanged) {
                String scoreText = EnumChatFormatting.RED + Integer.toString(rewrittenScore);
                hideScore = LocalDisguise.shouldHideSidebarScoreColumnText(objective, scoreText);
            }
            if (lineChanged && team == null) {
                continue;
            }
            plans.add(new RowPlan(score, team, entry, originalLine, rewrittenLine, originalScore, rewrittenScore, hideScore));
        }
        return plans;
    }

    private static boolean matchesAppliedContext(Scoreboard board, ScoreObjective objective, String signature) {
        if (applied == null || applied.isEmpty()) {
            return false;
        }
        for (AppliedMutation existing : applied) {
            if (!existing.matchesContext(board, objective, signature)) {
                return false;
            }
        }
        return true;
    }

    /** Hypixel scoreboard packets (e.g. Blitz Hour countdown) can revert team prefixes between ticks. */
    private static boolean areAppliedMutationsStillValid() {
        if (applied == null || applied.isEmpty()) {
            return false;
        }
        for (AppliedMutation existing : applied) {
            if (!existing.isStillApplied()) {
                return false;
            }
        }
        return true;
    }

    private static void applyPlans(Scoreboard board, ScoreObjective objective, List<RowPlan> plans, String signature) {
        ArrayList<AppliedMutation> next = new ArrayList<>();
        HashSet<String> usedInvisibleEntries = new HashSet<>();
        try {
            for (RowPlan plan : plans) {
                if (ScoreboardTeamSafety.isOnlinePlayerName(plan.originalEntry)
                        || ScoreboardTeamSafety.containsOnlinePlayer(plan.team)) {
                    continue;
                }
                if (!plan.lineChanged) {
                    plan.score.setScorePoints(plan.rewrittenScore);
                    next.add(AppliedMutation.scoreOnly(board, objective, plan, signature));
                    continue;
                }

                String invisibleEntry = chooseInvisibleEntry(board, objective, plan.originalEntry, usedInvisibleEntries);
                if (invisibleEntry == null) {
                    continue;
                }

                int scorePointsToKeep = plan.hideScore ? plan.originalScore : plan.rewrittenScore;
                String teamName = plan.team.getRegisteredName();
                String originalPrefix = plan.team.getColorPrefix();
                String originalSuffix = plan.team.getColorSuffix();

                if (board.getPlayersTeam(plan.originalEntry) == plan.team) {
                    board.removePlayerFromTeam(plan.originalEntry, plan.team);
                }
                board.removePlayerFromTeams(invisibleEntry);
                board.removeObjectiveFromEntity(invisibleEntry, objective);
                board.removeObjectiveFromEntity(plan.originalEntry, objective);

                board.addPlayerToTeam(invisibleEntry, teamName);
                board.getValueFromObjective(invisibleEntry, objective).setScorePoints(scorePointsToKeep);
                plan.team.setNamePrefix(plan.rewrittenLine);
                plan.team.setNameSuffix("");

                next.add(AppliedMutation.lineMutation(board, objective, plan, invisibleEntry, originalPrefix, originalSuffix, scorePointsToKeep, signature));
            }
            applied = next;
            if (!next.isEmpty()) {
                ScoreboardDateHider.forceScoreboardRefresh();
            }
        } catch (Throwable ignored) {
            restoreApplied();
        }
    }

    private static void restoreApplied() {
        List<AppliedMutation> current = applied;
        applied = new ArrayList<>();
        if (current == null || current.isEmpty()) {
            return;
        }
        for (int i = current.size() - 1; i >= 0; i--) {
            try {
                current.get(i).restore();
            } catch (Throwable ignored) {
            }
        }
        ScoreboardDateHider.forceScoreboardRefresh();
    }

    private static String chooseInvisibleEntry(Scoreboard board, ScoreObjective objective, String originalEntry, Set<String> used) {
        for (String candidate : INVISIBLE_ENTRY_CANDIDATES) {
            if (candidate == null || candidate.isEmpty() || candidate.equals(originalEntry) || used.contains(candidate)) {
                continue;
            }
            if (board.getPlayersTeam(candidate) != null) {
                continue;
            }
            if (!board.getObjectivesForEntity(candidate).isEmpty()) {
                continue;
            }
            used.add(candidate);
            return candidate;
        }
        return null;
    }

    private static final class RowPlan {
        private final Score score;
        private final ScorePlayerTeam team;
        private final String originalEntry;
        private final String originalLine;
        private final String rewrittenLine;
        private final int originalScore;
        private final int rewrittenScore;
        private final boolean hideScore;
        private final boolean lineChanged;

        private RowPlan(Score score, ScorePlayerTeam team, String originalEntry, String originalLine, String rewrittenLine,
                        int originalScore, int rewrittenScore, boolean hideScore) {
            this.score = score;
            this.team = team;
            this.originalEntry = originalEntry;
            this.originalLine = originalLine;
            this.rewrittenLine = rewrittenLine;
            this.originalScore = originalScore;
            this.rewrittenScore = rewrittenScore;
            this.hideScore = hideScore;
            this.lineChanged = !rewrittenLine.equals(originalLine);
        }
    }

    private static final class AppliedMutation {
        private final Scoreboard board;
        private final ScoreObjective objective;
        private final String originalEntry;
        private final String teamName;
        private final String mutatedEntry;
        private final String originalPrefix;
        private final String originalSuffix;
        private final int originalScore;
        private final int appliedScore;
        private final String rewrittenLine;
        private final boolean lineMutation;
        private final String signature;

        private AppliedMutation(Scoreboard board, ScoreObjective objective, String originalEntry, String teamName,
                                String mutatedEntry, String originalPrefix, String originalSuffix,
                                int originalScore, int appliedScore, String rewrittenLine, boolean lineMutation,
                                String signature) {
            this.board = board;
            this.objective = objective;
            this.originalEntry = originalEntry;
            this.teamName = teamName;
            this.mutatedEntry = mutatedEntry;
            this.originalPrefix = originalPrefix;
            this.originalSuffix = originalSuffix;
            this.originalScore = originalScore;
            this.appliedScore = appliedScore;
            this.rewrittenLine = rewrittenLine;
            this.lineMutation = lineMutation;
            this.signature = signature;
        }

        private static AppliedMutation scoreOnly(Scoreboard board, ScoreObjective objective, RowPlan plan, String signature) {
            return new AppliedMutation(board, objective, plan.originalEntry, null, null, null, null,
                    plan.originalScore, plan.rewrittenScore, plan.rewrittenLine, false, signature);
        }

        private static AppliedMutation lineMutation(Scoreboard board, ScoreObjective objective, RowPlan plan,
                                                    String mutatedEntry, String originalPrefix, String originalSuffix,
                                                    int appliedScore, String signature) {
            return new AppliedMutation(board, objective, plan.originalEntry,
                    plan.team == null ? null : plan.team.getRegisteredName(),
                    mutatedEntry, originalPrefix, originalSuffix,
                    plan.originalScore, appliedScore, plan.rewrittenLine, true, signature);
        }

        private boolean matchesContext(Scoreboard board, ScoreObjective objective, String signature) {
            return this.board == board
                    && this.objective == objective
                    && safeEquals(this.signature, signature);
        }

        private boolean isStillApplied() {
            if (board == null || objective == null) {
                return false;
            }
            if (!lineMutation) {
                try {
                    Score score = board.getObjectivesForEntity(originalEntry).get(objective);
                    return score != null && score.getScorePoints() == appliedScore;
                } catch (Throwable ignored) {
                    return false;
                }
            }
            ScorePlayerTeam team = teamName == null ? null : board.getTeam(teamName);
            if (team == null) {
                return false;
            }
            String prefix = team.getColorPrefix();
            return prefix != null && prefix.equals(rewrittenLine)
                    && board.getPlayersTeam(mutatedEntry) == team
                    && !board.getObjectivesForEntity(originalEntry).containsKey(objective);
        }

        private void restore() {
            if (board == null || objective == null || board.getObjective(objective.getName()) != objective) {
                return;
            }
            if (!lineMutation) {
                Score score = board.getObjectivesForEntity(originalEntry).get(objective);
                // A packet may already have replaced or removed the value. Undo only our own write.
                if (score != null && score.getScorePoints() == appliedScore) score.setScorePoints(originalScore);
                return;
            }
            ScorePlayerTeam team = board.getTeam(teamName);
            boolean ownsEntry = team != null && board.getPlayersTeam(mutatedEntry) == team;
            boolean originalPresent = board.getObjectivesForEntity(originalEntry).containsKey(objective);
            if (team != null && !ScoreboardTeamSafety.containsOnlinePlayer(team)
                    && !ScoreboardTeamSafety.isOnlinePlayerName(originalEntry)) {
                // Keep newer server prefixes/suffixes, especially the first in-game Kills: 1 update.
                if (rewrittenLine.equals(team.getColorPrefix())) {
                    team.setNamePrefix(originalPrefix);
                    if ("".equals(team.getColorSuffix())) team.setNameSuffix(originalSuffix);
                }
                if (ownsEntry) {
                    board.removePlayerFromTeam(mutatedEntry, team);
                    if (board.getPlayersTeam(originalEntry) == null) board.addPlayerToTeam(originalEntry, teamName);
                }
            }
            if (ownsEntry) {
                board.removeObjectiveFromEntity(mutatedEntry, objective);
                if (!originalPresent) board.getValueFromObjective(originalEntry, objective).setScorePoints(originalScore);
            }
        }

        private static boolean safeEquals(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }
}
