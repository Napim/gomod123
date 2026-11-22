package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.network.play.server.S3EPacketTeams;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Opt-in, bounded trace of server scoreboard-team changes and their local disguise/color effects. */
public final class ScoreboardTeamDisguiseDebugCapture {
    private static final long MAX_CAPTURE_MS = 900_000L;
    private static final int MAX_ROWS = 12_000;
    private static final int MAX_CHARS = 1_800_000;
    private static final int MAX_MEMBERS_IN_LINE = 64;
    private static final Map<String, String> LAST_DETAILS = new HashMap<String, String>();
    private static final List<String> ROWS = new ArrayList<String>();
    private static final Map<String, String> PREVIOUS_TEAMS = new HashMap<String, String>();
    private static long startedAtMs;
    private static int charCount;
    private static volatile boolean active;
    private static boolean truncated;
    private static Object lastWorld;

    private ScoreboardTeamDisguiseDebugCapture() {
    }

    public static synchronized boolean start(Minecraft mc) {
        if (active) return false;
        ROWS.clear();
        LAST_DETAILS.clear();
        PREVIOUS_TEAMS.clear();
        charCount = 0;
        truncated = false;
        lastWorld = null;
        startedAtMs = System.currentTimeMillis();
        active = true;
        appendLocked("capture_started version=2 durationLimitMs=" + MAX_CAPTURE_MS
                + " rowLimit=" + MAX_ROWS + " captures=team-packets,applied-team-state,phase,nametag,color");
        recordTick(mc);
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized int getRowCount() {
        return ROWS.size();
    }

    /** Runs on client tick after inbound packets have had a chance to update the scoreboard. */
    public static void recordTick(Minecraft mc) {
        recordTick(mc, "command-snapshot");
    }

    public static void recordTick(Minecraft mc, String hook) {
        if (!active) return;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (!active || autoStopIfExpiredLocked()) return;
            recordChangedLocked("capture-hook", safe(hook, 64), "reached=true");
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                recordChangedLocked("client-state", "world", "playerOrWorldUnavailable");
                return;
            }

            if (lastWorld != mc.theWorld) {
                lastWorld = mc.theWorld;
                PREVIOUS_TEAMS.clear();
                appendLocked("t=" + elapsedMs() + "ms event=world-change local="
                        + safe(mc.thePlayer.getName(), 64));
            }

            String phase = GameContext.explainDetection(mc)
                    + ", blitzGameOver=" + GameContext.isBlitzGameOverSidebar(mc);
            String sidebar = describeSidebar(mc.theWorld.getScoreboard());
            recordChangedLocked("game-context", "phase",
                    "state={" + phase + "} sidebar={" + sidebar + "}");

            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) {
                recordChangedLocked("team-state", "scoreboard", "null");
                return;
            }

            Map<String, String> currentTeams = new TreeMap<String, String>();
            try {
                Collection<ScorePlayerTeam> teams = scoreboard.getTeams();
                if (teams != null) {
                    for (ScorePlayerTeam team : teams) {
                        if (team == null) continue;
                        String registeredName = team.getRegisteredName();
                        if (registeredName == null) continue;
                        currentTeams.put(registeredName, describeTeam(team));
                    }
                }
            } catch (Throwable failure) {
                recordChangedLocked("team-state", "snapshot-error",
                        failure.getClass().getSimpleName());
            }

            Set<String> teamNames = new HashSet<String>();
            teamNames.addAll(PREVIOUS_TEAMS.keySet());
            teamNames.addAll(currentTeams.keySet());
            List<String> orderedNames = new ArrayList<String>(teamNames);
            Collections.sort(orderedNames);
            for (String name : orderedNames) {
                String before = PREVIOUS_TEAMS.get(name);
                String after = currentTeams.get(name);
                if (before == null && after != null) {
                    appendLocked("t=" + elapsedMs() + "ms event=team-state op=added team="
                            + safe(name, 160) + " " + after);
                } else if (before != null && after == null) {
                    appendLocked("t=" + elapsedMs() + "ms event=team-state op=removed team="
                            + safe(name, 160) + " previous=" + before);
                } else if (before != null && !before.equals(after)) {
                    appendLocked("t=" + elapsedMs() + "ms event=team-state op=changed team="
                            + safe(name, 160) + " previous=" + before + " current=" + after);
                }
            }
            PREVIOUS_TEAMS.clear();
            PREVIOUS_TEAMS.putAll(currentTeams);

            String localName = mc.thePlayer.getName();
            ScorePlayerTeam localTeam = scoreboard.getPlayersTeam(localName);
            String formatted = ScorePlayerTeam.formatPlayerName(localTeam, localName);
            recordChangedLocked("local-team", "self",
                    "realName=" + safe(localName, 64)
                            + " assigned=" + (localTeam == null ? "<none>" : safe(localTeam.getRegisteredName(), 160))
                            + " formatted=" + safe(formatted, 320)
                            + " team=" + (localTeam == null ? "<none>" : describeTeam(localTeam))
                            + " disguiseEnabled=" + LocalDisguise.isEnabled()
                            + " target=" + safe(LocalDisguise.getTargetName(), 64));
        }
    }

    /** Called at the inbound packet boundary, before Minecraft applies the team operation. */
    public static void recordInboundTeamsPacket(Object packet) {
        if (!active || !(packet instanceof S3EPacketTeams)) return;
        S3EPacketTeams teamsPacket = (S3EPacketTeams) packet;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (!active || autoStopIfExpiredLocked()) return;
            int action = teamsPacket.getAction();
            StringBuilder details = new StringBuilder(512);
            details.append("t=").append(elapsedMs()).append("ms event=team-packet direction=server-to-client")
                    .append(" action=").append(actionName(action)).append('(').append(action).append(')')
                    .append(" team=").append(safe(teamsPacket.getName(), 160))
                    .append(" prefix=").append(safe(teamsPacket.getPrefix(), 320))
                    .append(" suffix=").append(safe(teamsPacket.getSuffix(), 320))
                    .append(" color=").append(teamsPacket.getColor());
            Collection<String> players = teamsPacket.getPlayers();
            details.append(" members=").append(describeMembers(players))
                    .append(" thread=").append(Thread.currentThread().getName());
            appendLocked(details.toString());
        }
    }

    /** Logs name rows only; Lunar's separate level/health rows must not consume the capture budget. */
    public static void recordNametag(Minecraft mc, String input, String output, String sourceName) {
        if (!active || mc == null || mc.thePlayer == null || sourceName == null
                || !sourceName.equalsIgnoreCase(mc.thePlayer.getName())
                || !LocalDisguise.isNameLabelForDebug(input, sourceName)) return;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (!active || autoStopIfExpiredLocked()) return;
            String localTeam = "<unavailable>";
            try {
                if (mc != null && mc.theWorld != null && sourceName != null) {
                    ScorePlayerTeam team = mc.theWorld.getScoreboard().getPlayersTeam(sourceName);
                    localTeam = team == null ? "<none>" : team.getRegisteredName()
                            + " formatted=" + ScorePlayerTeam.formatPlayerName(team, sourceName);
                }
            } catch (Throwable ignored) {
            }
            recordChangedLocked("nametag-display", "self",
                    "source=" + safe(sourceName, 64)
                            + " input=" + safe(input, 360)
                            + " output=" + safe(output, 360)
                            + " assignedTeam=" + safe(localTeam, 420)
                            + " disguiseEnabled=" + LocalDisguise.isEnabled()
                            + " target=" + safe(LocalDisguise.getTargetName(), 64)
                            + " phase={" + (mc == null ? "<no-minecraft>" : GameContext.explainDetection(mc)) + "}");
        }
    }

    /** Called where GameContext chooses the server/team/fallback color for the local disguise nametag. */
    public static void recordNametagColor(String sourceFormattedName, String teamFormatted,
                                          boolean blitzActive, boolean gameOver, boolean mapPregame,
                                          String decision, String result, String context) {
        if (!active) return;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (!active || autoStopIfExpiredLocked()) return;
            recordChangedLocked("nametag-color", "self",
                    "sourceFormatted=" + safe(sourceFormattedName, 360)
                            + " teamFormatted=" + safe(teamFormatted, 360)
                            + " blitzActive=" + blitzActive + " gameOver=" + gameOver
                            + " mapPregame=" + mapPregame + " decision=" + safe(decision, 96)
                            + " result=" + safe(result, 32) + " phase={" + safe(context, 700) + "}");
        }
    }

    public static File stop() throws Exception {
        final String text;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (ROWS.isEmpty()) return null;
            appendLocked("t=" + elapsedMs() + "ms event=capture-stop requested=true");
            active = false;
            text = joinRowsLocked() + (truncated ? "capture_truncated=true\n" : "");
            ROWS.clear();
            LAST_DETAILS.clear();
            PREVIOUS_TEAMS.clear();
            charCount = 0;
            truncated = false;
            lastWorld = null;
        }
        File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
        Files.createDirectories(directory.toPath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File file = File.createTempFile("scoreboard_team_disguise_" + stamp + "_", ".log", directory);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static boolean autoStopIfExpiredLocked() {
        if (System.currentTimeMillis() - startedAtMs <= MAX_CAPTURE_MS) return false;
        appendLocked("t=" + elapsedMs() + "ms event=capture-auto-stopped durationLimitMs=" + MAX_CAPTURE_MS);
        active = false;
        return true;
    }

    private static void recordChangedLocked(String event, String key, String details) {
        String signature = event + "|" + key;
        String previous = LAST_DETAILS.get(signature);
        if (details.equals(previous)) return;
        LAST_DETAILS.put(signature, details);
        appendLocked("t=" + elapsedMs() + "ms event=" + event + " key=" + key + " " + details);
    }

    public static void recordHookFailure(String hook, Throwable failure) {
        if (!active) return;
        synchronized (ScoreboardTeamDisguiseDebugCapture.class) {
            if (!active || autoStopIfExpiredLocked()) return;
            recordChangedLocked("capture-hook-error", safe(hook, 64),
                    failure == null ? "<unknown>" : failure.getClass().getSimpleName());
        }
    }

    private static String describeSidebar(Scoreboard scoreboard) {
        if (scoreboard == null) return "<no-scoreboard>";
        try {
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) return "<no-sidebar-objective>";
            StringBuilder out = new StringBuilder("title=").append(safe(objective.getDisplayName(), 120));
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            int count = 0;
            for (Score score : scores) {
                if (count >= 15) break;
                if (score == null) continue;
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                out.append(" | ").append(safe(line, 100)).append('=').append(score.getScorePoints());
                count++;
            }
            return out.toString();
        } catch (Throwable failure) {
            return "<sidebar-error:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String describeTeam(ScorePlayerTeam team) {
        if (team == null) return "<none>";
        Collection<String> membership = team.getMembershipCollection();
        List<String> members = new ArrayList<String>();
        if (membership != null) members.addAll(membership);
        Collections.sort(members);
        StringBuilder sample = new StringBuilder("[");
        int shown = Math.min(MAX_MEMBERS_IN_LINE, members.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) sample.append(',');
            sample.append(safe(members.get(i), 48));
        }
        if (members.size() > shown) sample.append(",...+").append(members.size() - shown);
        sample.append(']');
        return "prefix=" + safe(team.getColorPrefix(), 160)
                + " suffix=" + safe(team.getColorSuffix(), 160)
                + " chatColor=" + safe(String.valueOf(team.getChatFormat()), 32)
                + " members=" + members.size() + sample;
    }

    private static String describeMembers(Collection<String> members) {
        if (members == null) return "<null>";
        List<String> sorted = new ArrayList<String>(members);
        Collections.sort(sorted);
        StringBuilder out = new StringBuilder("[");
        int shown = Math.min(MAX_MEMBERS_IN_LINE, sorted.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(',');
            out.append(safe(sorted.get(i), 48));
        }
        if (sorted.size() > shown) out.append(",...+").append(sorted.size() - shown);
        return out.append("] count=").append(sorted.size()).toString();
    }

    private static String actionName(int action) {
        switch (action) {
            case 0: return "CREATE";
            case 1: return "REMOVE";
            case 2: return "UPDATE";
            case 3: return "ADD_PLAYERS";
            case 4: return "REMOVE_PLAYERS";
            default: return "UNKNOWN";
        }
    }

    private static long elapsedMs() {
        return Math.max(0L, System.currentTimeMillis() - startedAtMs);
    }

    private static void appendLocked(String row) {
        if (ROWS.size() >= MAX_ROWS || charCount + row.length() > MAX_CHARS) {
            truncated = true;
            active = false;
            return;
        }
        ROWS.add(row);
        charCount += row.length() + 1;
    }

    private static String joinRowsLocked() {
        StringBuilder out = new StringBuilder(charCount + 32);
        for (String row : ROWS) out.append(row).append('\n');
        return out.toString();
    }

    private static String safe(String value, int limit) {
        if (value == null) return "<null>";
        String cleaned = value.replace('\u00a7', '&').replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (cleaned.length() > limit) return cleaned.substring(0, limit) + "...";
        return cleaned;
    }
}
