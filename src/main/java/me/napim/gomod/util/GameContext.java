package me.napim.gomod.util;

import me.napim.gomod.features.LocalDisguiseDebugCapture;
import me.napim.gomod.features.ScoreboardDateHider;
import me.napim.gomod.features.ScoreboardTeamDisguiseDebugCapture;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized detection helpers for server/lobby/game context.
 * Use these instead of hardcoding markers in multiple places.
 * <p>
 * <b>How detection works:</b> {@link #isOnHypixel} accepts official/wildcard Hypixel hostnames and
 * recognizes Hypixel's exact scoreboard footer for players connecting through an alias or proxy. That
 * identity is retained for the network connection, including when gomod hides the footer. Everything else reads
 * the in-world <b>scoreboard</b>
 * sidebar (display slot 1): objective title plus each line’s plain text (formatting stripped). There is
 * no separate “lobby id” or sub-server IP parsing here — Hypixel does not expose a reliable per-lobby
 * address on the client beyond what you see in the scoreboard and chat.
 */
public final class GameContext {
    private GameContext() {}

    /** Word "taunt" on a row (Hypixel Blitz in-match); avoids missing {@code line.contains("taunt")} on odd Unicode. */
    private static final Pattern SIDEBAR_TAUNT_WORD = Pattern.compile("(?i)\\btaunt\\b");

    /**
     * After cages open, Hypixel shows kit delivery time as {@code Get kit: MM:SS} or {@code Get kits: MM:SS}
     * (lines are lowercased before matching).
     */
    private static final Pattern SIDEBAR_GET_KIT_COUNTDOWN = Pattern.compile("(?i)get kits?:\\s*(\\d+):(\\d{2})\\b");

    /**
     * During Blitz deathmatch, Hypixel shows the draw timer as {@code Game End: MM:SS} (e.g. {@code 01:00}).
     */
    private static final Pattern SIDEBAR_GAME_END_COUNTDOWN = Pattern.compile("(?i)game\\s*end\\s*:\\s*(\\d+):(\\d{2})\\b");

    /** Fallback when a deathmatch end timer is formatted in seconds only (e.g. {@code Game End: 30s}). */
    private static final Pattern SIDEBAR_GAME_END_SECONDS = Pattern.compile("(?i)game\\s*end\\s*:\\s*(\\d+)\\s*s\\b");

    /** Hypixel pregame waiting-room player counts, e.g. "Players: 8/12" or "8/12 Players". */
    private static final Pattern SIDEBAR_PREGAME_PLAYER_COUNT = Pattern.compile(
            "(?i)(?:\\bplayers?\\s*:?\\s*\\d+\\s*/\\s*\\d+\\b|\\b\\d+\\s*/\\s*\\d+\\s+players?\\b)");

    /**
     * Last time the sidebar reliably indicated Blitz pregame, live match, cages, or post-game. Used when slot-1
     * objective flickers empty during teleports so TAB disguise logic does not treat the player as generic Hypixel.
     */
    private static volatile long lastBlitzSidebarReliableContextMs = 0L;
    private static final long BLITZ_TAB_CONTEXT_HOLD_MS = 5000L;

    /**
     * One scoreboard parse per world tick for disguise FontRenderer / Blitz detection hot paths.
     * {@link #shouldSuppressInventoryQolOverlays} uses a separate world-tick cache.
     */
    private static int sidebarCtxWorldId = Integer.MIN_VALUE;
    private static long sidebarCtxWorldTime = Long.MIN_VALUE;
    private static long sidebarCtxRevision;
    private static boolean sidebarCtxValid;
    private static boolean sidebarCtxOnHypixel;
    private static boolean sidebarCtxHasObjective;
    private static String sidebarCtxTitleLower = "";
    private static String sidebarCtxInternalLower = "";
    private static List<String> sidebarCtxLines = java.util.Collections.emptyList();
    private static boolean sidebarCtxHypixelLobby;
    private static boolean sidebarCtxHypixelPregame;
    private static boolean sidebarCtxBlitzPregame;
    private static boolean sidebarCtxBlitzHubQueue;
    private static boolean sidebarCtxBlitzCage;
    private static boolean sidebarCtxBlitzActive;
    private static boolean sidebarCtxBlitzGameOver;
    private static boolean sidebarCtxReplay;
    private static boolean sidebarCtxBlitzTabSuppress;
    /** Last tick we reliably saw a main Hypixel hub/limbo sidebar (used when slot-1 objective flickers empty). */
    private static boolean sidebarCtxLastKnownHypixelLobby;

    /** Alias/proxy fallback state: the exact Hypixel footer is latched for one network connection. */
    private static Object aliasHypixelHandler;
    private static volatile HypixelHostname lastHypixelHostname;
    private static Object aliasHypixelServerData;
    private static Object aliasHypixelScannedWorld;
    private static long aliasHypixelScannedWorldTime = Long.MIN_VALUE;
    private static boolean aliasHypixelScanValid;
    private static boolean aliasHypixelScanFound;
    private static boolean aliasHypixelConfirmedForConnection;

    /**
     * {@link #shouldSuppressInventoryQolOverlays} is queried from hotbar/container paths at render rate (60+ Hz).
     * On Hypixel each uncached call parsed the sidebar twice ({@link #isHypixelLobby} + {@link #isBlitzPregameLobby}).
     */
    private static int suppressInvQolCacheWorldId = Integer.MIN_VALUE;
    private static long suppressInvQolCacheWorldTime = Long.MIN_VALUE;
    private static boolean suppressInvQolCacheValue;
    private static boolean suppressInvQolCacheValid;

    private static void ensureSidebarContextCached(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null) {
                sidebarCtxValid = false;
                lastBlitzSidebarReliableContextMs = 0L;
                return;
            }
            int worldId = System.identityHashCode(mc.theWorld);
            long worldTime = mc.theWorld.getTotalWorldTime();
            Scoreboard scoreboard = getScoreboard(mc);
            long revision = SidebarSnapshot.revision(scoreboard);
            if (sidebarCtxValid && sidebarCtxWorldId == worldId && sidebarCtxWorldTime == worldTime
                    && sidebarCtxRevision == revision) {
                return;
            }
            sidebarCtxWorldId = worldId;
            sidebarCtxWorldTime = worldTime;
            sidebarCtxRevision = revision;
            sidebarCtxValid = true;
            sidebarCtxOnHypixel = isOnHypixel(mc);
            sidebarCtxHasObjective = false;
            sidebarCtxTitleLower = "";
            sidebarCtxInternalLower = "";
            sidebarCtxLines = java.util.Collections.emptyList();
            sidebarCtxHypixelLobby = false;
            sidebarCtxHypixelPregame = false;
            sidebarCtxBlitzPregame = false;
            sidebarCtxBlitzHubQueue = false;
            sidebarCtxBlitzCage = false;
            sidebarCtxBlitzActive = false;
            sidebarCtxBlitzGameOver = false;
            sidebarCtxReplay = false;
            sidebarCtxBlitzTabSuppress = false;

            if (!sidebarCtxOnHypixel) {
                lastBlitzSidebarReliableContextMs = 0L;
                sidebarCtxLastKnownHypixelLobby = false;
                return;
            }

            ScoreObjective objective = scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                if (sidebarCtxLastKnownHypixelLobby) {
                    sidebarCtxBlitzTabSuppress = false;
                } else {
                    long last = lastBlitzSidebarReliableContextMs;
                    sidebarCtxBlitzTabSuppress = last > 0L
                            && System.currentTimeMillis() - last < BLITZ_TAB_CONTEXT_HOLD_MS;
                }
                return;
            }

            sidebarCtxHasObjective = true;
            sidebarCtxTitleLower = stripFormatting(objective.getDisplayName()).toLowerCase(Locale.ROOT);
            sidebarCtxInternalLower = stripFormatting(objective.getName()).toLowerCase(Locale.ROOT);
            sidebarCtxLines = getScoreboardLines(scoreboard, objective);
            sidebarCtxReplay = sidebarCtxTitleLower.contains("replay");
            if (!sidebarCtxReplay) {
                for (String line : sidebarCtxLines) {
                    if (line != null && (line.contains("replay from") || line.equals("replay"))) {
                        sidebarCtxReplay = true;
                        break;
                    }
                }
            }

            sidebarCtxHypixelLobby = isHypixelLobbyFromSidebar(sidebarCtxTitleLower, sidebarCtxLines);
            sidebarCtxLastKnownHypixelLobby = sidebarCtxHypixelLobby;
            sidebarCtxBlitzPregame = isBlitzPregameLobbyFromSidebar(sidebarCtxTitleLower, sidebarCtxLines);
            sidebarCtxHypixelPregame = sidebarCtxBlitzPregame
                    || isHypixelPregameLobbyFromSidebar(sidebarCtxTitleLower, sidebarCtxLines);
            sidebarCtxBlitzHubQueue = sidebarCtxBlitzPregame
                    && (containsLobbyMarker(sidebarCtxTitleLower) || containsLobbyMarker(sidebarCtxLines));

            String combined = sidebarCtxTitleLower + " " + sidebarCtxInternalLower;
            boolean blitzSg = combined.contains("blitz sg")
                    || combined.replace(" ", "").contains("blitzsg")
                    || (sidebarCtxTitleLower.contains("blitz") && sidebarCtxTitleLower.contains("sg"));
            sidebarCtxBlitzCage = blitzSg && containsAny(sidebarCtxLines, "cages open");

            sidebarCtxBlitzActive = sidebarLinesIndicateBlitzLiveTaunt(sidebarCtxLines)
                    || hasHypixelBlitzInMatchSidebarFingerprint(sidebarCtxLines)
                    || hasBlitzInMatchLooseFingerprint(sidebarCtxLines);

            if (sidebarCtxTitleLower.contains("game over") || sidebarCtxTitleLower.contains("gameover")) {
                sidebarCtxBlitzGameOver = true;
            } else {
                for (String line : sidebarCtxLines) {
                    if (line != null && (line.contains("game over") || line.contains("gameover"))) {
                        sidebarCtxBlitzGameOver = true;
                        break;
                    }
                }
            }

            if (sidebarCtxHypixelLobby) {
                lastBlitzSidebarReliableContextMs = 0L;
            } else if (sidebarCtxBlitzPregame || sidebarCtxBlitzCage || sidebarCtxBlitzActive || sidebarCtxBlitzGameOver) {
                lastBlitzSidebarReliableContextMs = System.currentTimeMillis();
            }

            sidebarCtxBlitzTabSuppress = sidebarCtxBlitzPregame || sidebarCtxBlitzCage || sidebarCtxBlitzGameOver;
            if (!sidebarCtxBlitzTabSuppress && mc.theWorld != null && !sidebarCtxHypixelLobby) {
                long last = lastBlitzSidebarReliableContextMs;
                sidebarCtxBlitzTabSuppress = last > 0L
                        && System.currentTimeMillis() - last < BLITZ_TAB_CONTEXT_HOLD_MS;
            }
            // Main hub/limbo (browsing games): keep disguise rank on TAB — not flat Blitz white.
            if (sidebarCtxHypixelLobby && !sidebarCtxBlitzHubQueue) {
                sidebarCtxBlitzTabSuppress = false;
            }
        } catch (Throwable ignored) {
            sidebarCtxValid = false;
        }
    }

    private static boolean isScoreboardSidebarSlotPresent(Minecraft mc) {
        try {
            Scoreboard sb = getScoreboard(mc);
            if (sb == null) {
                return false;
            }
            return sb.getObjectiveInDisplaySlot(1) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Blitz TAB during pregame, cages, or post-game should mirror Hypixel (no injected disguise rank brackets).
     * When the sidebar objective disappears for a frame while teleporting, keep that behavior until the board returns
     * or we return to a main lobby / timeout — avoids a flash of real rank + name.
     */
    public static boolean isBlitzTabDisguiseRankSuppressed(Minecraft mc) {
        ensureSidebarContextCached(mc);
        if (sidebarCtxHypixelLobby) {
            return false;
        }
        return sidebarCtxBlitzTabSuppress;
    }

    public static boolean isOnHypixel(Minecraft mc) {
        try {
            if (mc == null) return false;
            Object serverData = mc.getCurrentServerData();
            Object handler = mc.getNetHandler();
            if (serverData == null) {
                resetAliasHypixelIdentity(handler, null);
                return false;
            }
            String address = mc.getCurrentServerData().serverIP;
            if (isHypixelHostname(address)) return true;
            return isHypixelFromAliasScoreboard(mc, serverData, handler);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isHypixelHostname(String address) {
        if (address == null) return false;
        HypixelHostname cached = lastHypixelHostname;
        if (cached != null && cached.address.equals(address)) return cached.hypixel;
        String host = address.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) host = host.substring(1, close);
        } else {
            int firstColon = host.indexOf(':');
            if (firstColon >= 0 && firstColon == host.lastIndexOf(':')) host = host.substring(0, firstColon);
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        boolean hypixel = host.equals("hypixel.net") || host.endsWith(".hypixel.net");
        lastHypixelHostname = new HypixelHostname(address, hypixel);
        return hypixel;
    }

    private static final class HypixelHostname {
        final String address;
        final boolean hypixel;

        HypixelHostname(String address, boolean hypixel) {
            this.address = address;
            this.hypixel = hypixel;
        }
    }

    /**
     * Recognize third-party connection names using Hypixel's exact {@code www.hypixel.net} sidebar footer. The
     * marker is remembered until the connection changes, so hiding the footer cannot disable Hypixel features.
     */
    private static synchronized boolean isHypixelFromAliasScoreboard(Minecraft mc, Object serverData, Object handler) {
        if (handler == null) {
            resetAliasHypixelIdentity(null, serverData);
            return false;
        }
        if (aliasHypixelHandler != handler || aliasHypixelServerData != serverData) {
            aliasHypixelHandler = handler;
            aliasHypixelServerData = serverData;
            aliasHypixelScannedWorld = null;
            aliasHypixelScannedWorldTime = Long.MIN_VALUE;
            aliasHypixelScanValid = false;
            aliasHypixelScanFound = false;
            aliasHypixelConfirmedForConnection = false;
        }
        if (aliasHypixelConfirmedForConnection) return true;
        // Several callers run on Netty/worker threads. Only inspect scoreboard state on Minecraft's client thread;
        // regular render/tick calls will populate this one-world-tick cache for those callers.
        if (!mc.isCallingFromMinecraftThread()) return false;
        if (mc.theWorld == null) return false;

        Object world = mc.theWorld;
        long worldTime = mc.theWorld.getTotalWorldTime();
        if (aliasHypixelScanValid && aliasHypixelScannedWorld == world
                && aliasHypixelScannedWorldTime == worldTime) {
            return aliasHypixelScanFound;
        }
        aliasHypixelScannedWorld = world;
        aliasHypixelScannedWorldTime = worldTime;
        aliasHypixelScanValid = true;
        aliasHypixelScanFound = hasHypixelSidebarFooter(mc);
        if (aliasHypixelScanFound) aliasHypixelConfirmedForConnection = true;
        return aliasHypixelScanFound;
    }

    private static synchronized void resetAliasHypixelIdentity(Object handler, Object serverData) {
        if (aliasHypixelHandler == handler && aliasHypixelServerData == serverData) return;
        aliasHypixelHandler = handler;
        aliasHypixelServerData = serverData;
        aliasHypixelScannedWorld = null;
        aliasHypixelScannedWorldTime = Long.MIN_VALUE;
        aliasHypixelScanValid = false;
        aliasHypixelScanFound = false;
        aliasHypixelConfirmedForConnection = false;
    }

    private static boolean hasHypixelSidebarFooter(Minecraft mc) {
        Scoreboard scoreboard = getScoreboard(mc);
        ScoreObjective objective = scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) return false;
        if (ScoreboardDateHider.isHypixelFooterText(objective.getDisplayName())) return true;
        SidebarSnapshot sidebar = SidebarSnapshot.get(scoreboard, objective);
        for (Score score : sidebar.scores()) {
            if (ScoreboardDateHider.isHypixelFooterText(sidebar.formatted(score))) return true;
        }
        return false;
    }

    public static boolean isHypixelLobby(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxHypixelLobby;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True in Hypixel game waiting rooms before a match starts, including non-Blitz modes.
     * Uses explicit waiting/countdown cues, or a game-mode + map + player-capacity sidebar.
     */
    public static boolean isHypixelPregameLobby(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null) return false;
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxHypixelPregame;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isHypixelPregameLobbyFromSidebar(String title, List<String> lines) {
        boolean hasModeMarker = containsLobbyModeMarker(title) || containsLobbyModeMarker(lines);
        if (!hasModeMarker) return false;

        if (containsPregameWaitingMarker(title) || containsPregameWaitingMarker(lines)) {
            return true;
        }

        boolean hasMap = containsAny(lines, "map:");
        if (!hasMap || lines == null) return false;
        for (String line : lines) {
            if (line != null && SIDEBAR_PREGAME_PLAYER_COUNT.matcher(line).find()) return true;
        }
        return false;
    }

    private static boolean containsPregameWaitingMarker(String text) {
        return text != null && (text.contains("starting in")
                || text.contains("game starts in")
                || text.contains("waiting for players")
                || text.contains("waiting for more players")
                || text.contains("waiting..."));
    }

    private static boolean containsPregameWaitingMarker(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) {
            if (containsPregameWaitingMarker(line)) return true;
        }
        return false;
    }

    /**
     * Best-armor and useless-item overlays use Blitz/inventory heuristics meant for matches — not Hypixel hub lobbies
     * or Blitz pregame waiting rooms (see {@link #isHypixelLobby} / {@link #isBlitzPregameLobby}).
     */
    public static boolean shouldSuppressInventoryQolOverlays(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null) {
                suppressInvQolCacheValid = false;
                return false;
            }
            int worldId = System.identityHashCode(mc.theWorld);
            long worldTime = mc.theWorld.getTotalWorldTime();
            if (suppressInvQolCacheValid
                    && suppressInvQolCacheWorldId == worldId
                    && suppressInvQolCacheWorldTime == worldTime) {
                return suppressInvQolCacheValue;
            }
            boolean suppress = computeShouldSuppressInventoryQolOverlays(mc);
            suppressInvQolCacheValid = true;
            suppressInvQolCacheWorldId = worldId;
            suppressInvQolCacheWorldTime = worldTime;
            suppressInvQolCacheValue = suppress;
            return suppress;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** One sidebar parse for lobby + pregame suppress (hot path when BestArmor overlay is enabled on Hypixel). */
    private static boolean computeShouldSuppressInventoryQolOverlays(Minecraft mc) {
        ensureSidebarContextCached(mc);
        if (!sidebarCtxOnHypixel) {
            return false;
        }
        return sidebarCtxHypixelLobby || sidebarCtxBlitzPregame;
    }

    private static boolean isHypixelLobbyFromSidebar(String title, List<String> lines) {
        boolean hasLobbyMarker = containsLobbyMarker(title) || containsLobbyMarker(lines);
        boolean hasModeMarker = containsLobbyModeMarker(title) || containsLobbyModeMarker(lines);
        boolean hasGameOrPregame = containsGameOrPregameMarkers(title) || containsGameOrPregameMarkers(lines);
        return (hasLobbyMarker || hasModeMarker) && !hasGameOrPregame;
    }

    private static boolean isBlitzPregameLobbyFromSidebar(String title, List<String> lines) {
        boolean isBlitz = title.contains("blitz") || containsAny(lines, "blitz") || title.contains("bsg");
        boolean hasMapMarker = containsAny(lines, "map:") || title.contains("map");
        if (!isBlitz && !hasMapMarker) {
            return false;
        }
        if (sidebarLinesIndicateBlitzLiveTaunt(lines)) {
            return false;
        }
        boolean mainHypixelLobby = isHypixelLobbyFromSidebar(title, lines);
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            // Promotional rows on the main hub (Blitz Hour / Blitz Star) are not a Blitz waiting room.
            if (line.contains("blitz hour") || line.contains("blitz star")) {
                if (!mainHypixelLobby) {
                    return true;
                }
                continue;
            }
            if (line.contains("cages open") || line.contains("starting in") || line.contains("game starts in")) {
                return true;
            }
            if (line.contains("players") && line.contains("/")) {
                return true;
            }
            if (line.contains("map:") && (line.contains("starting in") || line.contains("players"))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlitzPregameLobby(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxBlitzPregame;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Blitz queue/waiting on the main Hypixel games hub: sidebar still looks like a lobby (mentions lobby/limbo).
     * On Hypixel, Blitz queue/pregame TAB typically uses plain white (§f) for names — not full rank colors like the main lobby.
     */
    public static boolean isBlitzHypixelHubQueueSidebar(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxBlitzHubQueue;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True when the sidebar title looks like Blitz SG and a row mentions cages opening — reliable cage-phase signal
     * (easier than waiting for Taunt, which appears after release).
     */
    public static boolean isBlitzCagePhaseSidebar(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxBlitzCage;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * On the Blitz map in cage phase ({@link #isBlitzCagePhaseSidebar}), or other Blitz pregame on the map
     * (not the hub queue). Used to avoid reusing hub §f TAB cache on the match server.
     */
    public static boolean isBlitzMatchMapPregameSidebar(Minecraft mc) {
        ensureSidebarContextCached(mc);
        return sidebarCtxBlitzCage || (sidebarCtxBlitzPregame && !sidebarCtxBlitzHubQueue);
    }

    public static boolean isBlitzGameActive(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxBlitzActive;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Strict live/post-game Hypixel Blitz match check that rejects lobbies, pregames, replays,
     * and non-Blitz modes (such as SkyWars) whose sidebars share generic {@code Kills}/{@code Players left} rows.
     */
    public static boolean isStrictBlitzMatchContext(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            if (!sidebarCtxValid || !sidebarCtxHasObjective) {
                return false;
            }
            return isStrictBlitzMatchFromSidebar(
                    sidebarCtxTitleLower, sidebarCtxInternalLower, sidebarCtxLines,
                    sidebarCtxOnHypixel, sidebarCtxHypixelLobby, sidebarCtxBlitzPregame,
                    sidebarCtxBlitzActive, sidebarCtxBlitzGameOver, sidebarCtxReplay);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Pure sidebar evaluation for {@link #isStrictBlitzMatchContext(Minecraft)} (exposed for unit tests). */
    public static boolean isStrictBlitzMatchFromSidebar(
            String titleLower, String internalLower, List<String> lines,
            boolean onHypixel, boolean hypixelLobby, boolean blitzPregame,
            boolean blitzGameActive, boolean blitzGameOver, boolean replay) {
        if (!onHypixel || hypixelLobby || replay) {
            return false;
        }
        if (!blitzGameActive && !blitzGameOver) {
            return false;
        }
        if (blitzPregame && !blitzGameActive && !blitzGameOver) {
            return false;
        }
        String safeTitle = titleLower == null ? "" : titleLower;
        String safeInternal = internalLower == null ? "" : internalLower;
        boolean blitzMarker = safeTitle.contains("blitz")
                || safeTitle.contains("bsg")
                || safeInternal.contains("blitz")
                || safeInternal.contains("bsg");
        if (blitzMarker) {
            return true;
        }
        if (containsLobbyModeMarker(safeTitle) || containsLobbyModeMarker(safeInternal)) {
            return false;
        }
        return sidebarLinesIndicateBlitzLiveTaunt(lines)
                || containsAny(lines, "kit:")
                || containsAny(lines, "blitz star");
    }

    /** Show disguise rank/guild decorations only in a confirmed Hypixel lobby, never in pregame or a live match. */
    public static boolean shouldSuppressDisguiseNametagDecorations(Minecraft mc) {
        return !isHypixelLobby(mc) || isHypixelPregameLobby(mc) || isBlitzMatchMapPregameSidebar(mc)
                || isBlitzGameActive(mc)
                || isBlitzGameOverSidebar(mc);
    }

    /**
     * Hypixel Blitz colors in-world disguise nametags by match relation only during a live match:
     * teammates and the local player are green, opponents are red. At GAME OVER, keep the local
     * disguised name light red instead of inheriting Hypixel's post-match spectator tint.
     * Map pregame/cages retain normal server formatting.
     */
    public static String getBlitzDisguiseNametagNameColor(Minecraft mc, EntityPlayer sourcePlayer) {
        return getBlitzDisguiseNametagNameColor(mc, sourcePlayer, null);
    }

    /**
     * Follow the source player's current Hypixel scoreboard-team color for disguised labels. Team packets can update
     * before Lunar refreshes a rendered name string, so the applied team is the freshest source during transitions.
     * During map pregame/cages, only mirror a color the server sent; do not force match relation colors before the game.
     */
    public static String getBlitzDisguiseNametagNameColor(Minecraft mc, EntityPlayer sourcePlayer,
                                                           String sourceFormattedName) {
        if (mc == null || mc.thePlayer == null || sourcePlayer == null || !isOnHypixel(mc)) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    null, false, false, false, null, "outside-hypixel-or-player-unavailable");
        }
        boolean gameActive = isBlitzGameActive(mc);
        boolean gameOver = isBlitzGameOverSidebar(mc);
        boolean mapPregame = isBlitzMatchMapPregameSidebar(mc);
        if (!gameActive && !gameOver && !mapPregame) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    null, gameActive, gameOver, mapPregame, null, "no-blitz-phase");
        }

        // Preserve Hypixel's green local-player identity through GAME OVER while disguised.
        if (gameOver && mc.thePlayer == sourcePlayer) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    null, gameActive, gameOver, mapPregame, EnumChatFormatting.GREEN.toString(), "game-over-local-green");
        }

        String sourceName = sourcePlayer.getName();
        String teamFormatted = null;
        String serverColor = null;
        try {
            Scoreboard scoreboard = getScoreboard(mc);
            ScorePlayerTeam sourceTeam = scoreboard == null ? null : scoreboard.getPlayersTeam(sourceName);
            if (sourceTeam != null) {
                teamFormatted = ScorePlayerTeam.formatPlayerName(sourceTeam, sourceName);
                serverColor = findColorBeforePlayerName(teamFormatted, sourceName);
            }
        } catch (Throwable ignored) {
        }
        String colorDecision = "scoreboard-team-color";
        if (serverColor == null) {
            serverColor = findColorBeforePlayerName(sourceFormattedName, sourceName);
            colorDecision = "server-formatted-color";
        }
        if (serverColor != null) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    teamFormatted, gameActive, gameOver, mapPregame, serverColor, colorDecision);
        }

        // Pregame/cages should retain the server's native name color; the scoreboard supplied no color to copy.
        if (mapPregame || (!gameActive && !gameOver)) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    teamFormatted, gameActive, gameOver, mapPregame, null, "pregame-no-native-color");
        }

        if (mc.thePlayer == sourcePlayer) {
            return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                    teamFormatted, gameActive, gameOver, mapPregame, EnumChatFormatting.GREEN.toString(), "active-local-fallback-green");
        }
        try {
            Scoreboard scoreboard = getScoreboard(mc);
            if (scoreboard != null) {
                ScorePlayerTeam localTeam = scoreboard.getPlayersTeam(mc.thePlayer.getName());
                ScorePlayerTeam sourceTeam = scoreboard.getPlayersTeam(sourcePlayer.getName());
                if (localTeam != null && sourceTeam != null && localTeam.isSameTeam(sourceTeam)) {
                    return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                            teamFormatted, gameActive, gameOver, mapPregame, EnumChatFormatting.GREEN.toString(), "active-teammate-fallback-green");
                }
            }
        } catch (Throwable ignored) {
        }
        return traceBlitzDisguiseNameColor(mc, sourcePlayer, sourceFormattedName,
                teamFormatted, gameActive, gameOver, mapPregame, EnumChatFormatting.RED.toString(), "active-opponent-fallback-red");
    }

    private static String traceBlitzDisguiseNameColor(Minecraft mc, EntityPlayer sourcePlayer,
            String sourceFormattedName, String teamFormatted, boolean gameActive, boolean gameOver,
            boolean mapPregame, String result, String decision) {
        if (ScoreboardTeamDisguiseDebugCapture.isActive()
                && mc != null && sourcePlayer != null && mc.thePlayer == sourcePlayer
                && LocalDisguise.isNameLabelForDebug(sourceFormattedName, sourcePlayer.getName())) {
            ScoreboardTeamDisguiseDebugCapture.recordNametagColor(sourceFormattedName, teamFormatted,
                    gameActive, gameOver, mapPregame, decision, result, explainDetection(mc));
        }
        if (LocalDisguiseDebugCapture.isActive() && mc != null && mc.thePlayer == sourcePlayer
                && LocalDisguise.isNameLabelForDebug(sourceFormattedName, sourcePlayer.getName())) {
            LocalDisguiseDebugCapture.record("nametag-color", "local-player",
                    "source=" + (sourcePlayer == null ? "<none>" : sourcePlayer.getName())
                            + " input=" + sourceFormattedName + " teamFormatted=" + teamFormatted
                            + " blitzActive=" + gameActive + " gameOver=" + gameOver
                            + " mapPregame=" + mapPregame + " decision=" + decision + " result=" + result
                            + " context=" + explainDetection(mc));
        }
        return result;
    }

    /** Returns the active Minecraft color code immediately before the player's visible IGN, if present. */
    private static String findColorBeforePlayerName(String formatted, String playerName) {
        if (formatted == null || formatted.isEmpty() || playerName == null || playerName.isEmpty()) {
            return null;
        }
        String plain = stripFormatting(formatted);
        int nameAt = plain.toLowerCase(Locale.ROOT).indexOf(playerName.toLowerCase(Locale.ROOT));
        if (nameAt < 0) {
            return null;
        }

        String activeColor = null;
        int visibleIndex = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(++i));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    activeColor = "\u00a7" + code;
                } else if (code == 'r') {
                    activeColor = EnumChatFormatting.RESET.toString();
                }
                continue;
            }
            if (visibleIndex == nameAt) {
                return activeColor;
            }
            visibleIndex++;
        }
        return null;
    }

    public static boolean isBlitzSpectator(Minecraft mc) {
        try {
            if (!isBlitzGameActive(mc)) return false;
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            return containsAny(lines, "spectator") || containsAny(lines, "spectators");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Hypixel Blitz deathmatch phase: sidebar shows a {@code Game End:} style countdown (no crafting table).
     */
    public static boolean isBlitzDeathmatchPhase(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            return containsAny(lines, "game end");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Blitz map Gulch — no crafting table; drives material/food rules for useless-item hints. */
    public static boolean isBlitzGulchMap(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            for (String line : lines) {
                if (line != null && line.contains("gulch")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Blitz map Citadel — no enchanting table; experience bottles cannot be used for enchanting here.
     */
    public static boolean isBlitzCitadelMap(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            for (String line : lines) {
                if (line != null && line.contains("citadel")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Blitz SG after release while kits are not yet granted: sidebar shows {@code Get kits:} (or similar). Troll kit
     * receives a witch egg at kit time, so invisibility splash is not "useless" until that row disappears.
     */
    public static boolean isBlitzSidebarWaitingForKitsDelivery(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            return containsAny(lines, "get kits") || sidebarAnyLineMatchesGetKitCountdown(lines);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True when the scoreboard sidebar shows the post-cage {@code Get kit(s): MM:SS} countdown row.
     */
    public static boolean blitzSidebarShowsGetKitCountdown(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            return sidebarAnyLineMatchesGetKitCountdown(lines);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Parses remaining time from the Blitz {@code Get kit: MM:SS} / {@code Get kits: MM:SS} sidebar row.
     *
     * @return milliseconds until kit delivery, or {@code -1} if absent or not parseable
     */
    public static long parseBlitzSidebarGetKitRemainingMs(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return -1L;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            if (lines == null) {
                return -1L;
            }
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                Matcher m = SIDEBAR_GET_KIT_COUNTDOWN.matcher(line);
                if (!m.find()) {
                    continue;
                }
                int minutes = Integer.parseInt(m.group(1));
                int seconds = Integer.parseInt(m.group(2));
                if (seconds < 0 || seconds >= 60 || minutes < 0 || minutes > 120) {
                    continue;
                }
                return minutes * 60_000L + seconds * 1000L;
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /**
     * Parses remaining time from the Blitz deathmatch {@code Game End: MM:SS} sidebar row.
     *
     * @return milliseconds until game end, or {@code -1} if not in Blitz deathmatch or unparseable
     */
    public static long parseBlitzSidebarGameEndRemainingMs(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null || !isOnHypixel(mc)) {
                return -1L;
            }
            ensureSidebarContextCached(mc);
            if (!sidebarCtxValid || !sidebarCtxOnHypixel || !sidebarCtxHasObjective
                    || sidebarCtxHypixelLobby || sidebarCtxBlitzGameOver) {
                return -1L;
            }
            return parseBlitzGameEndRemainingMsFromSidebar(
                    sidebarCtxTitleLower, sidebarCtxInternalLower, sidebarCtxLines, sidebarCtxReplay);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * Pure sidebar parser for Blitz deathmatch {@code Game End:} countdowns (exposed for unit tests).
     */
    public static long parseBlitzGameEndRemainingMsFromSidebar(
            String titleLower, String internalLower, List<String> lines, boolean replay) {
        if (lines == null || lines.isEmpty()) {
            return -1L;
        }
        if (isHypixelLobbyFromSidebar(titleLower == null ? "" : titleLower, lines)) {
            return -1L;
        }
        String safeTitle = titleLower == null ? "" : titleLower;
        String safeInternal = internalLower == null ? "" : internalLower;
        if (containsLobbyMarker(safeTitle) || containsLobbyMarker(lines)) {
            return -1L;
        }
        if (safeTitle.contains("game over") || safeTitle.contains("gameover")) {
            return -1L;
        }
        for (String line : lines) {
            if (line != null && (line.contains("game over") || line.contains("gameover"))) {
                return -1L;
            }
        }
        boolean blitzTitle = safeTitle.contains("blitz")
                || safeTitle.contains("bsg")
                || safeInternal.contains("blitz");
        boolean nonBlitzModeTitle = !blitzTitle && containsLobbyModeMarker(safeTitle);
        if (nonBlitzModeTitle) {
            return -1L;
        }
        boolean blitzRows = containsAny(lines, "kit:") || sidebarLinesIndicateBlitzLiveTaunt(lines);
        if (!blitzTitle && !(replay && blitzRows) && !blitzRows) {
            return -1L;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            Matcher mmSs = SIDEBAR_GAME_END_COUNTDOWN.matcher(line);
            if (mmSs.find()) {
                int minutes = Integer.parseInt(mmSs.group(1));
                int seconds = Integer.parseInt(mmSs.group(2));
                if (seconds >= 0 && seconds < 60 && minutes >= 0 && minutes <= 120) {
                    return minutes * 60_000L + seconds * 1000L;
                }
            }
            Matcher secOnly = SIDEBAR_GAME_END_SECONDS.matcher(line);
            if (secOnly.find()) {
                int seconds = Integer.parseInt(secOnly.group(1));
                if (seconds >= 0 && seconds <= 7200) {
                    return seconds * 1000L;
                }
            }
        }
        return -1L;
    }

    private static boolean sidebarAnyLineMatchesGetKitCountdown(List<String> lines) {
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            if (line != null && SIDEBAR_GET_KIT_COUNTDOWN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** Sidebar {@code Kit:} line is Troll (Blitz); lines are already lowercased in {@link #getScoreboardLines}. */
    public static boolean isBlitzSidebarKitTroll(Minecraft mc) {
        try {
            if (!isOnHypixel(mc)) {
                return false;
            }
            List<String> lines = getScoreboardLines(getScoreboard(mc), getObjective(mc));
            if (lines == null) {
                return false;
            }
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                if (line.contains("kit:") && line.contains("troll")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isBlitzTrollPreKitPendingWitchEgg(Minecraft mc) {
        return isBlitzSidebarWaitingForKitsDelivery(mc) && isBlitzSidebarKitTroll(mc);
    }

    /**
     * Custom TAB ordering (kills / W/L / …) and alive-before-dead grouping only apply in Blitz queue, pregame, cages,
     * live match, or post-game — not in generic Hypixel lobbies or other modes.
     */
    public static boolean isBlitzTabListCustomSortContext(Minecraft mc) {
        try {
            if (!isOnHypixel(mc) || mc == null || mc.theWorld == null) {
                return false;
            }
            return isBlitzHypixelHubQueueSidebar(mc)
                    || isBlitzPregameLobby(mc)
                    || isBlitzCagePhaseSidebar(mc)
                    || isBlitzGameActive(mc)
                    || isBlitzGameOverSidebar(mc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Blitz post-game sidebar (e.g. "GAME OVER"). Used so TAB/game-state logic does not treat this like a normal lobby.
     */
    public static boolean isBlitzGameOverSidebar(Minecraft mc) {
        try {
            ensureSidebarContextCached(mc);
            return sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxBlitzGameOver;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Hypixel replay spectator UI: sidebar title/lines mention {@code REPLAY} and {@code Replay from &lt;id&gt;}.
     */
    public static boolean isHypixelReplayWatching(Minecraft mc) {
        try {
            // Native Lunar labels query this once per player row, often several
            // times per frame. Reuse the sidebar snapshot already read each tick.
            ensureSidebarContextCached(mc);
            return sidebarCtxValid && sidebarCtxOnHypixel && sidebarCtxHasObjective && sidebarCtxReplay;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String explainDetection(Minecraft mc) {
        return "hypixel=" + isOnHypixel(mc)
                + ", hypixelLobby=" + isHypixelLobby(mc)
                + ", hypixelPregame=" + isHypixelPregameLobby(mc)
                + ", blitzPregame=" + isBlitzPregameLobby(mc)
                + ", blitzHubQueue=" + isBlitzHypixelHubQueueSidebar(mc)
                + ", blitzCage=" + isBlitzCagePhaseSidebar(mc)
                + ", blitzMatchPregame=" + isBlitzMatchMapPregameSidebar(mc)
                + ", blitzActive=" + isBlitzGameActive(mc)
                + ", blitzGameOver=" + isBlitzGameOverSidebar(mc)
                + ", blitzSpectator=" + isBlitzSpectator(mc)
                + ", replay=" + isHypixelReplayWatching(mc);
    }

    private static Scoreboard getScoreboard(Minecraft mc) {
        return (mc == null || mc.theWorld == null) ? null : mc.theWorld.getScoreboard();
    }

    private static ScoreObjective getObjective(Minecraft mc) {
        Scoreboard scoreboard = getScoreboard(mc);
        return scoreboard == null ? null : scoreboard.getObjectiveInDisplaySlot(1);
    }

    private static List<String> getScoreboardLines(Scoreboard scoreboard, ScoreObjective objective) {
        return SidebarSnapshot.get(scoreboard, objective).lowercase();
    }

    private static boolean containsLobbyMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("lobby") || text.contains("limbo");
    }

    private static boolean containsLobbyMarker(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) {
            if (containsLobbyMarker(line)) return true;
        }
        return false;
    }

    private static boolean containsLobbyModeMarker(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("blitz")
                || text.contains("skywars")
                || text.contains("sky wars")
                || text.contains("bedwars")
                || text.contains("bed wars")
                || text.contains("duels")
                || text.contains("arcade")
                || text.contains("murder mystery")
                || text.contains("build battle")
                || text.contains("wool wars")
                || text.contains("tnt games")
                || text.contains("mega walls")
                || text.contains("smash heroes")
                || text.contains("cops and crims")
                || text.contains("quakecraft")
                || text.contains("vampirez")
                || text.equals("walls")
                || text.startsWith("warlords");
    }

    private static boolean containsLobbyModeMarker(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) {
            if (containsLobbyModeMarker(line)) return true;
        }
        return false;
    }

    private static boolean containsGameOrPregameMarkers(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("starting in")
                || text.contains("game starts in")
                || text.contains("cages open")
                || text.contains("players remaining")
                || text.contains("players left")
                || text.contains("next event")
                || text.contains("refill")
                || text.contains("deathmatch")
                || text.contains("game end")
                || text.contains("time left")
                || text.contains("final kill")
                || text.contains("bed destroyed")
                || text.contains("bed broken")
                || text.contains("teams left")
                || text.contains("players alive")
                // Live-match cues are detected by the dedicated mode fingerprints below; promotions and
                // persistent stats such as Blitz Star, lobby kills, and SkyWars souls are not match markers.
                || text.contains("diamond upgrade")
                || text.contains("emerald upgrade");
    }

    private static boolean containsGameOrPregameMarkers(List<String> lines) {
        if (lines == null) return false;
        for (String line : lines) {
            if (containsGameOrPregameMarkers(line)) return true;
        }
        return false;
    }

    private static boolean containsAny(List<String> lines, String needle) {
        if (lines == null || needle == null) return false;
        for (String line : lines) {
            if (line != null && line.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Keep a-z, 0-9, colon, space — drops emoji/symbols so "taunt" survives next to odd Unicode (Hypixel sidebar rows).
     */
    private static String asciiKeyForSidebarMatch(String lineLower) {
        if (lineLower == null || lineLower.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(lineLower.length());
        for (int i = 0; i < lineLower.length(); i++) {
            char c = lineLower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == ' ') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static boolean sidebarLinesIndicateBlitzLiveTaunt(List<String> lines) {
        if (lines == null) {
            return false;
        }
        if (containsAny(lines, "taunt")) {
            return true;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            if (SIDEBAR_TAUNT_WORD.matcher(line).find()) {
                return true;
            }
            if (asciiKeyForSidebarMatch(line).contains("taunt")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strong in-match layout (e.g. sdebug 20260401_070156): {@code Kills} + {@code Players} + {@code Kit} plus a
     * match-phase row, excluding cage/starting countdown. Covers cases where Taunt text is not matched raw.
     */
    private static boolean hasHypixelBlitzInMatchSidebarFingerprint(List<String> lines) {
        if (lines == null) {
            return false;
        }
        if (containsAny(lines, "cages open") || containsAny(lines, "starting in") || containsAny(lines, "game starts in")) {
            return false;
        }
        if (!containsAny(lines, "kills:")) {
            return false;
        }
        if (!containsAny(lines, "players:")) {
            return false;
        }
        if (!containsAny(lines, "kit:")) {
            return false;
        }
        return containsAny(lines, "next event")
                || containsAny(lines, "get kits")
                || sidebarAnyLineMatchesGetKitCountdown(lines)
                || containsAny(lines, "refill")
                || containsAny(lines, "deathmatch")
                || containsAny(lines, "time left")
                || containsAny(lines, "players remaining")
                || containsAny(lines, "players left")
                || containsAny(lines, "blitz star")
                || containsAny(lines, "game end");
    }

    /**
     * Extra in-match detection when strict fingerprints miss (e.g. sidebar layout changes). Used by
     * {@link #isBlitzGameActive} and TAB killers grouping so dead rows still reorder while Hypixel lists them.
     */
    private static boolean hasBlitzInMatchLooseFingerprint(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        if (containsAny(lines, "cages open") || containsAny(lines, "starting in") || containsAny(lines, "game starts in")) {
            return false;
        }
        boolean hasKillsLine = false;
        for (String line : lines) {
            if (line != null && line.contains("kills")) {
                hasKillsLine = true;
                break;
            }
        }
        if (!hasKillsLine) {
            return false;
        }
        return containsAny(lines, "players remaining")
                || containsAny(lines, "players left")
                || containsAny(lines, "remaining")
                || containsAny(lines, "alive")
                || containsAny(lines, "spectator")
                || containsAny(lines, "spectators")
                || containsAny(lines, "next event")
                || containsAny(lines, "refill")
                || containsAny(lines, "deathmatch")
                || containsAny(lines, "time left")
                || containsAny(lines, "game end")
                || containsAny(lines, "kit:");
    }

    private static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return MinecraftFormatting.stripSectionCodes(text);
    }
}
