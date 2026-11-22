package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.world.World;

/**
 * Heuristic for Hypixel **Blitz Survival Games — Teams** (sidebar shows Blitz + team cues + Taunt when live).
 * Used by Ask for Loot and other team-only features; solo BSG does not satisfy {@link #isBlitzTeamsMatchLive}.
 * <p>
 * Sidebar parsing is cached per {@link World#getTotalWorldTime()} so render hot paths (e.g. many {@code EntityItem}s)
 * do not rescan the scoreboard every frame.
 */
public final class BlitzTeamsDetector {

    private static World cacheWorld;
    private static long cacheWorldTime = Long.MIN_VALUE;
    private static boolean cacheValid;
    private static boolean snapBlitz;
    private static boolean snapTeam;
    private static boolean snapTaunt;
    private static boolean snapCountdown;
    /** TAB stats sorting must remain disabled through same-world Teams scoreboard transitions. */
    private static boolean teamsTabContext;

    private BlitzTeamsDetector() {
    }

    private static void ensureSidebarSnapshot(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            cacheValid = false;
            cacheWorld = null;
            teamsTabContext = false;
            return;
        }
        World w = mc.theWorld;
        long t = w.getTotalWorldTime();
        if (cacheValid && cacheWorld == w && cacheWorldTime == t) {
            return;
        }
        if (cacheWorld != w) {
            teamsTabContext = false;
        }
        cacheWorld = w;
        cacheWorldTime = t;
        cacheValid = true;
        snapBlitz = false;
        snapTeam = false;
        snapTaunt = false;
        snapCountdown = false;
        try {
            Scoreboard scoreboard = w.getScoreboard();
            if (scoreboard == null) {
                return;
            }
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return;
            }
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String raw = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (raw == null) {
                    continue;
                }
                String clean = stripFormatting(raw).toLowerCase();
                if (clean.contains("blitz")) {
                    snapBlitz = true;
                }
                if (clean.contains("team")) {
                    snapTeam = true;
                }
                if (clean.contains("taunt")) {
                    snapTaunt = true;
                }
                if (clean.contains("cages open") || clean.contains("starting in") || clean.contains("game starts in")) {
                    snapCountdown = true;
                }
                if (clean.contains("waiting") && clean.contains("players")) {
                    snapCountdown = true;
                }
            }
            String objectiveName = objective.getDisplayName();
            if (objectiveName != null) {
                String clean = stripFormatting(objectiveName).toLowerCase();
                if (clean.contains("blitz")) {
                    snapBlitz = true;
                }
                if (clean.contains("team")) {
                    snapTeam = true;
                }
            }
            if (snapBlitz && snapTeam) {
                teamsTabContext = true;
            }
            // These are server team prefixes, not disguised display names. They identify a Teams pregame
            // even before its sidebar publishes a mode/Teams Left row. Scan only at snapshot/tick cadence.
            if (!teamsTabContext) {
                for (ScorePlayerTeam team : scoreboard.getTeams()) {
                    String prefix = team.getColorPrefix();
                    if (prefix != null && (prefix.contains("[DIST") || prefix.contains("[dist"))) {
                        teamsTabContext = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            snapBlitz = snapTeam = snapTaunt = snapCountdown = false;
        }
    }

    /** Sidebar shows a Taunt line (active match cue on Hypixel Blitz). */
    public static boolean sidebarHasTaunt(Minecraft mc) {
        ensureSidebarSnapshot(mc);
        return cacheValid && snapTaunt;
    }

    /** Sidebar shows both Team and Taunt (team mode in an active-looking match). */
    public static boolean sidebarHasTeamAndTaunt(Minecraft mc) {
        ensureSidebarSnapshot(mc);
        return cacheValid && snapTeam && snapTaunt;
    }

    public static boolean isBlitzTeamsGame(Minecraft mc) {
        ensureSidebarSnapshot(mc);
        return cacheValid && snapBlitz && snapTeam;
    }

    /** Dedicated TAB guard, including district prefixes and previously confirmed same-world Teams play. */
    public static boolean isBlitzTeamsTabContext(Minecraft mc) {
        ensureSidebarSnapshot(mc);
        return cacheValid && teamsTabContext;
    }

    /**
     * Blitz Teams pregame on the match map (cages / starting countdown). False in the Hypixel games hub
     * Blitz queue, where the sidebar still mentions lobby/limbo ({@link GameContext#isBlitzHypixelHubQueueSidebar}).
     */
    public static boolean isBlitzTeamsMapPregame(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        if (!GameContext.isBlitzPregameLobby(mc)) {
            return false;
        }
        if (GameContext.isBlitzHypixelHubQueueSidebar(mc)) {
            return false;
        }
        return isBlitzTeamsGame(mc);
    }

    public static boolean isActiveBlitzTeamsGame(Minecraft mc) {
        ensureSidebarSnapshot(mc);
        return cacheValid && snapBlitz && snapTeam && snapTaunt;
    }

    /**
     * True during an active Blitz Teams match (sidebar has taunt, etc.), but not in the
     * Hypixel main lobby, mode lobby, or Blitz pre-game / countdown lobby.
     */
    public static boolean isBlitzTeamsMatchLive(Minecraft mc) {
        if (!isActiveBlitzTeamsGame(mc)) {
            return false;
        }
        if (GameContext.isHypixelLobby(mc)) {
            return false;
        }
        if (GameContext.isBlitzPregameLobby(mc)) {
            return false;
        }
        ensureSidebarSnapshot(mc);
        if (cacheValid && snapCountdown) {
            return false;
        }
        return true;
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("\u00a7[0-9A-FK-ORa-fk-or]", "");
    }
}
