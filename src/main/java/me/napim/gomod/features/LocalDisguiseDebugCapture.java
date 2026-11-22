package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded opt-in trace for the local /disguise TAB and nametag render paths. */
public final class LocalDisguiseDebugCapture {
    private static final long MAX_CAPTURE_MS = 180_000L;
    private static final int MAX_ROWS = 1200;
    private static final int MAX_CHARS = 300_000;
    private static final Map<String, String> LAST_DETAILS = new HashMap<String, String>();
    private static final Map<String, Long> LAST_TIMES = new HashMap<String, Long>();
    private static final List<String> ROWS = new ArrayList<String>();
    private static long startedAtMs;
    private static int charCount;
    private static volatile boolean active;
    private static boolean truncated;

    private LocalDisguiseDebugCapture() {
    }

    public static synchronized boolean start() {
        if (active) return false;
        ROWS.clear();
        LAST_DETAILS.clear();
        LAST_TIMES.clear();
        charCount = 0;
        truncated = false;
        startedAtMs = System.currentTimeMillis();
        active = true;
        appendLocked("capture_started durationLimitMs=" + MAX_CAPTURE_MS);
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    /** Record only changed details, with a one-second heartbeat for unchanged render paths. */
    public static synchronized void record(String event, String key, String details) {
        recordWithHeartbeat(event, key, details, 1000L);
    }

    /** Capture already-disguised labels too: a later draw can recolor them without restoring the real IGN. */
    public static void recordFinalPlayerLabel(String stage, String input, String output) {
        if (!active || (!LocalDisguise.isLocalDisguisePlayerLabelText(input)
                && !LocalDisguise.isLocalDisguisePlayerLabelText(output))) return;
        boolean overlay = KillCounter.isTabOverlayRenderActive();
        String source = LocalDisguise.fastRealNameText;
        String target = LocalDisguise.fastTargetNameText;
        String plain = input == null ? "" : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(input);
        boolean sourceInput = plain != null && source != null
                && plain.toLowerCase(Locale.ROOT).contains(source.toLowerCase(Locale.ROOT));
        boolean bare = plain != null && (plain.trim().equalsIgnoreCase(source)
                || plain.trim().equalsIgnoreCase(target));
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        recordWithHeartbeat("final-player-label", stage + (overlay ? "-tab" : "-world")
                        + (sourceInput ? "-source" : "-target") + (bare ? "-bare" : "-row"),
                "stage=" + stage + " overlay=" + overlay
                        + " gameOver=" + me.napim.gomod.util.GameContext.isBlitzGameOverSidebar(mc)
                        + " source=" + source + " target=" + target
                        + " input=" + clean(input, 220) + " output=" + clean(output, 220), 3000L);
    }

    private static synchronized void recordWithHeartbeat(String event, String key, String details, long heartbeatMs) {
        if (!active) return;
        long now = System.currentTimeMillis();
        if (now - startedAtMs > MAX_CAPTURE_MS) {
            appendLocked("capture_auto_stopped durationLimitMs=" + MAX_CAPTURE_MS);
            active = false;
            return;
        }
        String safeEvent = clean(event, 48);
        String safeKey = clean(key, 96);
        String safeDetails = clean(details, 1400);
        String signature = safeEvent + "|" + safeKey;
        String previous = LAST_DETAILS.get(signature);
        Long previousAt = LAST_TIMES.get(signature);
        if (safeDetails.equals(previous) && previousAt != null && now - previousAt < heartbeatMs) return;
        LAST_DETAILS.put(signature, safeDetails);
        LAST_TIMES.put(signature, now);
        appendLocked("t=" + Math.max(0L, now - startedAtMs) + "ms event=" + safeEvent
                + " key=" + safeKey + " " + safeDetails);
    }

    public static File stop() throws Exception {
        final String text;
        synchronized (LocalDisguiseDebugCapture.class) {
            if (ROWS.isEmpty()) return null;
            active = false;
            text = joinRowsLocked() + (truncated ? "capture_truncated=true\n" : "");
            ROWS.clear();
            LAST_DETAILS.clear();
            LAST_TIMES.clear();
            charCount = 0;
            truncated = false;
        }
        File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
        Files.createDirectories(directory.toPath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File file = File.createTempFile("local_disguise_trace_" + stamp + "_", ".log", directory);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
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
        StringBuilder out = new StringBuilder(charCount + 16);
        for (String row : ROWS) out.append(row).append('\n');
        return out.toString();
    }

    private static String clean(String value, int limit) {
        if (value == null) return "<null>";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (cleaned.length() > limit) return cleaned.substring(0, limit) + "...";
        return cleaned;
    }
}
