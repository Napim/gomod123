package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SidebarBlitzHourReminder {
    private static final String SYNTHETIC_ENTRY = EnumChatFormatting.YELLOW + "" + EnumChatFormatting.BOLD + "BLITZ HOUR" + EnumChatFormatting.RESET;
    private static final int SIDEBAR_ROW_PADDING = 3;
    private static final int BLITZ_HOUR_BLOCK_SCORE_OFFSET = 2;
    private static final int BLITZ_HOUR_BLOCK_BUMP = 2;
    private static final String[] SPACER_ENTRY_CANDIDATES = {
            "\u00A7k", "\u00A7l", "\u00A7m", "\u00A7n", "\u00A7o", "\u00A7p", "\u00A7q", "\u00A7r\u00A7k",
            "\u00A7r\u00A7l", "\u00A7r\u00A7m", "\u00A7r\u00A7n", "\u00A7r\u00A7o", "\u00A7r\u00A7p"
    };

    private static boolean rememberedBlitzHourGame = false;
    private static boolean cageSeenThisRound = false;
    private static boolean replacedCageOpenSoundThisRound = false;
    private static long lastBlitzRoundContextMs = 0L;
    private static AppliedMutation applied;

    public SidebarBlitzHourReminder() {
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        clientTick();
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        refreshSidebarLineMutation(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        try {
            if (!ConfigManager.isSidebarBlitzHourLineEffective()) {
                return;
            }
            if (!rememberedBlitzHourGame || replacedCageOpenSoundThisRound) {
                return;
            }
            if (!(event.getPacket() instanceof S29PacketSoundEffect)) {
                return;
            }
            S29PacketSoundEffect pkt = (S29PacketSoundEffect) event.getPacket();
            String sound = pkt.getSoundName();
            if (sound == null || !"mob.enderdragon.growl".equalsIgnoreCase(sound)) {
                return;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            if (!cageSeenThisRound && !GameContext.isBlitzCagePhaseSidebar(mc) && !GameContext.isBlitzGameActive(mc)) {
                return;
            }

            // If cage opening sound is disabled, block it entirely
            if (!ConfigManager.isCageOpeningSoundEnabled()) {
                event.setCancelled(true);
                replacedCageOpenSoundThisRound = true;
                return;
            }

            // Cage opening sound is enabled: intercept and play alternative if BLITZ HOUR SFX enabled
            event.setCancelled(true);
            replacedCageOpenSoundThisRound = true;
            playBlitzHourCageOpenSound(mc, pkt);
        } catch (Throwable ignored) {
        }
    }

    public static boolean shouldRenderSyntheticSidebarLine(Minecraft mc) {
        try {
            return applied == null
                    && shouldShowRememberedBlitzHourLine(mc)
                    && !MapQueuer.isBlitzHourOnSidebar();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getFormattedSidebarLine() {
        return EnumChatFormatting.YELLOW + "" + EnumChatFormatting.BOLD + "BLITZ HOUR" + EnumChatFormatting.RESET;
    }

    public static String rewriteSidebarTextForDraw(String line) {
        return line;
    }

    public static boolean isBlitzHourSidebarText(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
        if (plain == null) {
            plain = line;
        }
        return plain.toLowerCase(Locale.ROOT).contains("blitz hour");
    }

    /** Adjust draw {@code x} while vanilla/custom sidebar text is painting (covers paths that skip GuiIngame redirects). */
    public static float adjustDrawXIfBlitzHour(FontRenderer fontRenderer, String text, float defaultX) {
        return defaultX;
    }

    public static boolean isSyntheticSidebarLine(String line) {
        if (line == null) {
            return false;
        }
        return isBlitzHourSidebarText(line) || line.equals(getFormattedSidebarLine());
    }

    /**
     * Horizontally center {@code line} inside the sidebar row background (same box as the title uses).
     */
    public static int centeredDrawX(FontRenderer fontRenderer, int scaledWidth, int sidebarContentWidth, String line) {
        if (fontRenderer == null || line == null || scaledWidth <= 0 || sidebarContentWidth <= 0) {
            return scaledWidth;
        }
        int left = scaledWidth - sidebarContentWidth - SIDEBAR_ROW_PADDING;
        int right = scaledWidth - SIDEBAR_ROW_PADDING + 2;
        int lineWidth = fontRenderer.getStringWidth(stripLeadingSpacesForCenterDraw(line));
        return left + (right - left - lineWidth) / 2;
    }

    /**
     * Vanilla {@code GuiIngame#renderScoreboard}: row label at {@code rowLeft}, red score at {@code rowLeft + nameWidth - scoreWidth}.
     * Center the visible label in that name column, not across the whole sidebar max-width box.
     */
    public static float centeredDrawXForVanillaScoreboardRow(FontRenderer fontRenderer, int rowLeft, String drawText,
            int nameWidth, int scoreWidth) {
        if (fontRenderer == null || drawText == null) {
            return rowLeft;
        }
        int labelWidth = fontRenderer.getStringWidth(stripLeadingSpacesForCenterDraw(drawText));
        int labelArea = Math.max(0, nameWidth - Math.max(0, scoreWidth));
        if (labelArea <= 0) {
            return rowLeft;
        }
        return rowLeft + (labelArea - labelWidth) / 2.0F;
    }

    /**
     * Custom sidebar passes (disguise / injected KE+BlitzHour): score is right-aligned in the max-width box.
     */
    public static int centeredDrawXInSidebarBox(FontRenderer fontRenderer, int boxLeft, int boxRight, int scoreDrawX,
            String line) {
        if (fontRenderer == null || line == null) {
            return boxLeft;
        }
        int labelWidth = fontRenderer.getStringWidth(stripLeadingSpacesForCenterDraw(line));
        int labelRight = scoreDrawX > boxLeft ? scoreDrawX : boxRight;
        return boxLeft + (labelRight - boxLeft - labelWidth) / 2;
    }

    /** Leading spaces from {@link #withLeadingSpacesForSidebarCenter} must not affect centering math. */
    public static String stripLeadingSpacesForCenterDraw(String line) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i == 0 ? line : line.substring(i);
    }

    private static int[] resolveBlitzHourRowNameAndScoreWidth(FontRenderer fontRenderer, String drawText) {
        if (fontRenderer == null || drawText == null || !isBlitzHourSidebarText(drawText)) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return null;
            }
            Scoreboard board = mc.theWorld.getScoreboard();
            if (board == null) {
                return null;
            }
            ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return null;
            }
            for (Score score : SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective)) {
                if (score == null || score.getPlayerName() == null) {
                    continue;
                }
                ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                String formatted = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (formatted == null) {
                    formatted = score.getPlayerName();
                }
                if (!isBlitzHourSidebarText(formatted) && !isBlitzHourSidebarText(score.getPlayerName())) {
                    continue;
                }
                String scoreText = EnumChatFormatting.RED + Integer.toString(score.getScorePoints());
                int scoreWidth = fontRenderer.getStringWidth(scoreText);
                int nameWidth = fontRenderer.getStringWidth(formatted);
                return new int[]{nameWidth, scoreWidth};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Prefix spaces so left-aligned scoreboard rendering centers {@code formattedLine} in the sidebar box.
     */
    public static String withLeadingSpacesForSidebarCenter(FontRenderer fontRenderer, int sidebarContentWidth,
            String formattedLine) {
        if (fontRenderer == null || formattedLine == null || sidebarContentWidth <= 0) {
            return formattedLine;
        }
        int lineWidth = fontRenderer.getStringWidth(formattedLine);
        if (lineWidth >= sidebarContentWidth) {
            return formattedLine;
        }
        int spaceWidth = Math.max(1, fontRenderer.getStringWidth(" "));
        int leadSpaces = (sidebarContentWidth - lineWidth) / spaceWidth / 2;
        if (leadSpaces <= 0) {
            return formattedLine;
        }
        StringBuilder padded = new StringBuilder(leadSpaces + formattedLine.length());
        for (int i = 0; i < leadSpaces; i++) {
            padded.append(' ');
        }
        padded.append(formattedLine);
        return padded.toString();
    }

    public static boolean isRememberedBlitzHourGame() {
        return rememberedBlitzHourGame;
    }

    private static void clientTick() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                if (!ForceBlitzHourDebug.isForced()) {
                    resetRound();
                }
                return;
            }

            if (ForceBlitzHourDebug.isForced()) {
                ForceBlitzHourDebug.clientTick(mc);
                rememberedBlitzHourGame = true;
                refreshSidebarLineMutation(mc);
                return;
            }

            if (!GameContext.isOnHypixel(mc)) {
                resetRound();
                return;
            }

            boolean pregame = GameContext.isBlitzPregameLobby(mc);
            boolean cage = GameContext.isBlitzCagePhaseSidebar(mc);
            boolean active = GameContext.isBlitzGameActive(mc);
            boolean gameOver = GameContext.isBlitzGameOverSidebar(mc);
            boolean blitzRoundContext = pregame || cage || active || gameOver;

            if (GameContext.isHypixelLobby(mc) && !pregame) {
                resetRound();
                return;
            }

            boolean liveBlitzHour = MapQueuer.isBlitzHourOnSidebar();
            if (liveBlitzHour) {
                rememberedBlitzHourGame = true;
            } else if (pregame && !cage && !active) {
                rememberedBlitzHourGame = false;
                cageSeenThisRound = false;
                replacedCageOpenSoundThisRound = false;
            }

            if (cage) {
                cageSeenThisRound = true;
            }

            if (blitzRoundContext) {
                lastBlitzRoundContextMs = System.currentTimeMillis();
            } else if (lastBlitzRoundContextMs > 0L && System.currentTimeMillis() - lastBlitzRoundContextMs > 10000L) {
                resetRound();
                return;
            }

            refreshSidebarLineMutation(mc);
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldShowRememberedBlitzHourLine(Minecraft mc) {
        try {
            return ConfigManager.isSidebarBlitzHourLineEffective()
                    && rememberedBlitzHourGame
                    && mc != null
                    && mc.theWorld != null
                    && (GameContext.isBlitzCagePhaseSidebar(mc) || GameContext.isBlitzGameActive(mc));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBlitzHourBlockApplied() {
        return applied != null;
    }

    /** Removes client scoreboard rows inserted by {@link #applyBlitzHourSidebarBlock}. */
    public static void clearBlitzHourSidebarBlock() {
        restoreApplied();
    }

    /**
     * Inserts BLITZ HOUR with blank spacer rows above and below it, anchored above Kit (or Taunt/KE when Kit is hidden).
     */
    public static void applyBlitzHourSidebarBlock(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) {
            return;
        }
        try {
            if (applied != null && applied.matchesCurrent(board, objective) && hasSyntheticScore(board, objective)) {
                return;
            }
            restoreApplied();
            applyBlitzHourBlock(board, objective);
            ScoreboardDateHider.forceScoreboardRefresh();
        } catch (Throwable ignored) {
            restoreApplied();
        }
    }

    private static void refreshSidebarLineMutation(Minecraft mc) {
        try {
            if (!shouldShowRememberedBlitzHourLine(mc)) {
                restoreApplied();
                return;
            }
            Scoreboard board = mc.theWorld.getScoreboard();
            if (board == null) {
                restoreApplied();
                return;
            }
            ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                restoreApplied();
                return;
            }
            applyBlitzHourSidebarBlock(board, objective);
        } catch (Throwable ignored) {
            restoreApplied();
        }
    }

    private static void applyBlitzHourBlock(Scoreboard board, ScoreObjective objective) {
        int anchorScore = findKitAnchorScore(board, objective);
        int spacerBelowScore = anchorScore + 1;
        int blitzScore = anchorScore + BLITZ_HOUR_BLOCK_SCORE_OFFSET;
        int spacerAboveScore = anchorScore + 3;
        int bumpThreshold = anchorScore + BLITZ_HOUR_BLOCK_SCORE_OFFSET;
        boolean reuseSpacerBelow = hasExistingSpacerAtScore(board, objective, spacerBelowScore);

        Set<String> skip = new HashSet<>();
        skip.add(getFormattedSidebarLine());
        bumpScoresAtOrAbove(board, objective, bumpThreshold, BLITZ_HOUR_BLOCK_BUMP, skip);

        Set<String> usedEntries = new HashSet<>();
        String blitzEntry = getFormattedSidebarLine();
        usedEntries.add(blitzEntry);

        String spacerBelowEntry = null;
        if (!reuseSpacerBelow) {
            spacerBelowEntry = chooseSpacerEntry(board, objective, usedEntries);
            if (spacerBelowEntry != null) {
                usedEntries.add(spacerBelowEntry);
                board.removePlayerFromTeams(spacerBelowEntry);
                board.removeObjectiveFromEntity(spacerBelowEntry, objective);
                board.getValueFromObjective(spacerBelowEntry, objective).setScorePoints(spacerBelowScore);
            }
        }

        String spacerAboveEntry = chooseSpacerEntry(board, objective, usedEntries);
        if (spacerAboveEntry != null) {
            board.removePlayerFromTeams(spacerAboveEntry);
            board.removeObjectiveFromEntity(spacerAboveEntry, objective);
            board.getValueFromObjective(spacerAboveEntry, objective).setScorePoints(spacerAboveScore);
        }

        board.removePlayerFromTeams(blitzEntry);
        board.removeObjectiveFromEntity(blitzEntry, objective);
        board.getValueFromObjective(blitzEntry, objective).setScorePoints(blitzScore);

        applied = new AppliedMutation(board, objective, blitzEntry, spacerBelowEntry, spacerAboveEntry,
                bumpThreshold, reuseSpacerBelow);
    }

    /**
     * Score points for the BLITZ HOUR row itself (anchor + 2), with spacer rows at anchor + 1 and anchor + 3.
     */
    public static int findBlitzHourInsertScorePoints(Scoreboard board, ScoreObjective objective) {
        return findKitAnchorScore(board, objective) + BLITZ_HOUR_BLOCK_SCORE_OFFSET;
    }

    private static int findKitAnchorScore(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) {
            return 0;
        }
        try {
            List<Score> sorted = new ArrayList<>(board.getSortedScores(objective));
            for (Score score : sorted) {
                if (!isSidebarScoreCandidate(score)) {
                    continue;
                }
                ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
                    return score.getScorePoints();
                }
                if (SidebarTeamsLayout.isTeamAnchorLine(line)) {
                    return score.getScorePoints();
                }
            }
            for (Score score : sorted) {
                if (!isSidebarScoreCandidate(score)) {
                    continue;
                }
                ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (isTauntSidebarText(line)) {
                    return score.getScorePoints();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean hasExistingSpacerAtScore(Scoreboard board, ScoreObjective objective, int scorePoints) {
        try {
            for (Score score : board.getSortedScores(objective)) {
                if (score == null || score.getScorePoints() != scorePoints || !isSidebarScoreCandidate(score)) {
                    continue;
                }
                ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                return isSidebarSpacerLine(line);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void bumpScoresAtOrAbove(Scoreboard board, ScoreObjective objective, int threshold, int delta,
            Set<String> skip) {
        for (Score score : new ArrayList<>(board.getSortedScores(objective))) {
            if (score == null || score.getPlayerName() == null) {
                continue;
            }
            if (skip != null && skip.contains(score.getPlayerName())) {
                continue;
            }
            if (isBlitzHourSidebarText(score.getPlayerName())) {
                continue;
            }
            if (score.getScorePoints() >= threshold) {
                score.setScorePoints(score.getScorePoints() + delta);
            }
        }
    }

    private static String chooseSpacerEntry(Scoreboard board, ScoreObjective objective, Set<String> used) {
        for (String candidate : SPACER_ENTRY_CANDIDATES) {
            if (candidate == null || candidate.isEmpty() || (used != null && used.contains(candidate))) {
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

    private static boolean isSidebarScoreCandidate(Score score) {
        if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
            return false;
        }
        return !isBlitzHourSidebarText(score.getPlayerName());
    }

    private static boolean isTauntSidebarText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
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

    private static boolean hasSyntheticScore(Scoreboard board, ScoreObjective objective) {
        if (board == null || objective == null) {
            return false;
        }
        try {
            for (Score score : new ArrayList<Score>(board.getSortedScores(objective))) {
                if (score != null && isBlitzHourSidebarText(score.getPlayerName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void restoreApplied() {
        AppliedMutation current = applied;
        applied = null;
        if (current == null) {
            return;
        }
        try {
            Scoreboard board = current.board;
            ScoreObjective objective = current.objective;
            if (board == null || objective == null) {
                return;
            }
            Set<String> skip = new HashSet<>();
            if (current.syntheticEntry != null) {
                skip.add(current.syntheticEntry);
            }
            if (current.spacerBelowEntry != null) {
                skip.add(current.spacerBelowEntry);
            }
            if (current.spacerAboveEntry != null) {
                skip.add(current.spacerAboveEntry);
            }
            bumpScoresAtOrAbove(board, objective, current.bumpThreshold, -BLITZ_HOUR_BLOCK_BUMP, skip);
            if (current.spacerBelowEntry != null) {
                board.removePlayerFromTeams(current.spacerBelowEntry);
                board.removeObjectiveFromEntity(current.spacerBelowEntry, objective);
            }
            if (current.spacerAboveEntry != null) {
                board.removePlayerFromTeams(current.spacerAboveEntry);
                board.removeObjectiveFromEntity(current.spacerAboveEntry, objective);
            }
            if (current.syntheticEntry != null) {
                board.removePlayerFromTeams(current.syntheticEntry);
                board.removeObjectiveFromEntity(current.syntheticEntry, objective);
            }
            ScoreboardDateHider.forceScoreboardRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static void playBlitzHourCageOpenSound(Minecraft mc, S29PacketSoundEffect pkt) {
        // Only play alternative sound if Blitz Hour cage SFX is enabled
        if (!ConfigManager.isBlitzHourCageSfxEnabled()) {
            return;
        }
        try {
            mc.thePlayer.playSound("mob.zombie.remedy", 1.0F, 1.0F);
        } catch (Throwable t) {
            try {
                double x = pkt.getX() / 8.0D;
                double y = pkt.getY() / 8.0D;
                double z = pkt.getZ() / 8.0D;
                mc.theWorld.playSound(x, y, z, "mob.zombie.remedy", 1.0F, 1.0F, false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void resetRound() {
        if (!ForceBlitzHourDebug.isForced()) {
            ForceBlitzHourDebug.removeInjection();
        }
        restoreApplied();
        rememberedBlitzHourGame = false;
        cageSeenThisRound = false;
        replacedCageOpenSoundThisRound = false;
        lastBlitzRoundContextMs = 0L;
    }

    private static final class AppliedMutation {
        private final Scoreboard board;
        private final ScoreObjective objective;
        private final String syntheticEntry;
        private final String spacerBelowEntry;
        private final String spacerAboveEntry;
        private final int bumpThreshold;

        private AppliedMutation(Scoreboard board, ScoreObjective objective, String syntheticEntry,
                String spacerBelowEntry, String spacerAboveEntry, int bumpThreshold, boolean reuseSpacerBelow) {
            this.board = board;
            this.objective = objective;
            this.syntheticEntry = syntheticEntry;
            this.spacerBelowEntry = reuseSpacerBelow ? null : spacerBelowEntry;
            this.spacerAboveEntry = spacerAboveEntry;
            this.bumpThreshold = bumpThreshold;
        }

        private boolean matchesCurrent(Scoreboard board, ScoreObjective objective) {
            return this.board == board && this.objective == objective;
        }
    }
}
