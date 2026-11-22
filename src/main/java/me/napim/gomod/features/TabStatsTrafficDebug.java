package me.napim.gomod.features;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.util.EnumChatFormatting;

/**
 * Debug logging for work triggered by TAB list / Tab Stats: inbound tab packets, TAB compose passes,
 * Hypixel/Mojang HTTP, and queued per-player scans. Lag with Tab Stats on is usually frequent
 * {@link KillCounter#updateAllTabNames()} (client CPU), not "packets sent" to Hypixel every tick —
 * HTTP is paced and only runs from scans / guild cache misses.
 *
 * <p>When debug is not OFF, all lines are appended under {@code &lt;gameDir&gt;/gomod123/tabstats-traffic-debug/}
 * in a timestamped {@code session-*.log} file.</p>
 */
public final class TabStatsTrafficDebug {

    public static final String DEBUG_FOLDER_NAME = "tabstats-traffic-debug";

    public enum Mode {
        OFF,
        /** Chat: 1s totals for inbound tab packets. Console + log file for compose / HTTP / queue. */
        SUMMARY,
        /** Chat for batched tab packets plus each compose / HTTP / queue / bulk-scan line (keys redacted). */
        VERBOSE
    }

    private static volatile Mode mode = Mode.OFF;

    private static final Object LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static int cS38;
    private static int cS3E;
    private static long windowStartMs = System.currentTimeMillis();
    private static final long BATCH_FLUSH_MS = 1000L;

    /** Active session log (absolute path); null when OFF or before first line opened. */
    private static volatile Path activeSessionLog;

    private TabStatsTrafficDebug() {
    }

    /** {@code <gameDir>/gomod123/tabstats-traffic-debug}. */
    public static File getDebugLogFolder() {
        return new File(GomodPaths.getGomod123Dir(), DEBUG_FOLDER_NAME).getAbsoluteFile();
    }

    /** Absolute path string of the current session log, or null if debug is off / no session yet. */
    public static String getActiveSessionLogPath() {
        Path p = activeSessionLog;
        return p == null ? null : p.toAbsolutePath().toString();
    }

    public static Mode getMode() {
        return mode;
    }

    public static Mode cycleMode() {
        Mode prev = mode;
        Mode next;
        if (prev == Mode.OFF) {
            next = Mode.SUMMARY;
        } else if (prev == Mode.SUMMARY) {
            next = Mode.VERBOSE;
        } else {
            next = Mode.OFF;
        }
        applyModeTransition(prev, next);
        return next;
    }

    public static void setMode(Mode m) {
        Mode next = m == null ? Mode.OFF : m;
        Mode prev = mode;
        applyModeTransition(prev, next);
    }

    private static void applyModeTransition(Mode prev, Mode next) {
        mode = next;
        synchronized (LOCK) {
            cS38 = 0;
            cS3E = 0;
            windowStartMs = System.currentTimeMillis();
        }
        if (prev != Mode.OFF && next == Mode.OFF) {
            endFileSession();
        } else if (next != Mode.OFF && prev == Mode.OFF) {
            startFileSession(next);
        } else if (next != Mode.OFF) {
            ensureFileSession(next);
            if (prev != Mode.OFF && prev != next) {
                appendFileLine("# mode change " + prev + " -> " + next);
            }
        }
    }

    private static void startFileSession(Mode activeMode) {
        synchronized (FILE_LOCK) {
            endFileSessionLocked();
            try {
                File folder = getDebugLogFolder();
                if (!folder.isDirectory() && !folder.mkdirs()) {
                    System.err.println("[TabStatsTraffic] Could not create folder: " + folder.getAbsolutePath());
                    return;
                }
                String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                activeSessionLog = folder.toPath().resolve("session-" + ts + ".log");
                String header = "# Tab Stats traffic debug — " + ts + "\r\n"
                        + "# Mode: " + activeMode + "\r\n"
                        + "# Game dir gomod123 folder: " + GomodPaths.getGomod123Dir().getAbsolutePath() + "\r\n"
                        + "# ---\r\n";
                Files.write(
                        activeSessionLog,
                        header.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Throwable t) {
                System.err.println("[TabStatsTraffic] Failed to start log file: " + t.getMessage());
                activeSessionLog = null;
            }
        }
    }

    private static void ensureFileSession(Mode activeMode) {
        synchronized (FILE_LOCK) {
            if (activeSessionLog != null) {
                return;
            }
            startFileSession(activeMode);
        }
    }

    private static void endFileSession() {
        synchronized (FILE_LOCK) {
            endFileSessionLocked();
        }
    }

    private static void endFileSessionLocked() {
        try {
            if (activeSessionLog != null) {
                appendFileLineLocked("# --- session end ---");
            }
        } catch (Throwable ignored) {
        } finally {
            activeSessionLog = null;
        }
    }

    private static void appendFileLine(String line) {
        if (mode == Mode.OFF) {
            return;
        }
        synchronized (FILE_LOCK) {
            if (activeSessionLog == null) {
                return;
            }
            appendFileLineLocked(line);
        }
    }

    private static void appendFileLineLocked(String line) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            byte[] bytes = (ts + " " + line.replace("\r\n", " ").replace("\n", " ") + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(activeSessionLog, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable t) {
            System.err.println("[TabStatsTraffic] Log append failed: " + sanitizeFsMsg(t.getMessage()));
        }
    }

    private static String sanitizeFsMsg(String m) {
        return m == null ? "" : m.replace("\r", " ").replace("\n", " ");
    }

    /** Call once per client tick (e.g. from {@code KillEffectCommand#onTick}) to flush batched tab packet counts. */
    public static void flushPacketBatchIfDue() {
        if (mode == Mode.OFF) {
            return;
        }
        long now = System.currentTimeMillis();
        int s38;
        int s3e;
        synchronized (LOCK) {
            if (now - windowStartMs < BATCH_FLUSH_MS) {
                return;
            }
            s38 = cS38;
            s3e = cS3E;
            cS38 = 0;
            cS3E = 0;
            windowStartMs = now;
        }
        if (s38 == 0 && s3e == 0) {
            return;
        }
        String line = "IN(batch 1s) S38PlayerListItem×" + s38 + " S3ETeams×" + s3e
                + " — marks vanilla TAB/team updates (gomod recomposes display on debounced tick)";
        logBatched(line);
    }

    public static void onInboundTabPacket(String kind) {
        if (TabPerformanceDebug.isActive()) TabPerformanceDebug.traffic("inbound." + kind);
        if (mode == Mode.OFF || kind == null) {
            return;
        }
        synchronized (LOCK) {
            if ("S38".equalsIgnoreCase(kind)) {
                cS38++;
            } else {
                cS3E++;
            }
        }
    }

    public static void onTabCompose(String reason) {
        if (TabPerformanceDebug.isActive()) TabPerformanceDebug.traffic("compose.trigger");
        if (mode == Mode.OFF || reason == null) {
            return;
        }
        logNow("COMPOSE updateAllTabNames (" + reason + ")");
    }

    public static void onHttpUrl(String url) {
        TabPerformanceDebug.http(url);
        if (mode == Mode.OFF || url == null || url.isEmpty()) {
            return;
        }
        String redacted = redactUrl(url);
        String u = url.toLowerCase(Locale.ROOT);
        String label;
        if (u.contains("api.hypixel.net/player")) {
            label = "HTTP GET Hypixel /player ";
        } else if (u.contains("api.hypixel.net/guild")) {
            label = "HTTP GET Hypixel /guild ";
        } else if (u.contains("api.mojang.com")) {
            label = "HTTP GET Mojang profile ";
        } else {
            label = "HTTP GET ";
        }
        logNow(label + redacted);
    }

    public static void onQueuedSinglePlayerScan(String playerName) {
        TabPerformanceDebug.traffic("scan.queue.single");
        if (mode == Mode.OFF || playerName == null || playerName.isEmpty()) {
            return;
        }
        logNow("QUEUE single-player tabstats scan for " + playerName + " (threads HTTP+Mojang+Hypixel)");
    }

    public static void onBulkTabstatsScanStart(int rowCount, String trigger) {
        TabPerformanceDebug.traffic("scan.queue.bulk");
        if (mode == Mode.OFF) {
            return;
        }
        String t = trigger == null ? "?" : trigger;
        logNow("BULK tabstats scan start: " + rowCount + " row(s), trigger=" + t);
    }

    private static String redactUrl(String url) {
        return url.replaceAll("(?i)key=[^&]+", "key=<redacted>");
    }

    private static void logBatched(String line) {
        System.out.println("[TabStatsTraffic] " + line);
        appendFileLine(line);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[TabStatsTraffic] "
                + EnumChatFormatting.GRAY + line);
    }

    private static void logNow(String line) {
        System.out.println("[TabStatsTraffic] " + line);
        appendFileLine(line);
        if (mode == Mode.VERBOSE) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[TabStatsTraffic] "
                    + EnumChatFormatting.WHITE + line);
        }
    }
}
