package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Bounded opt-in trace for Hypixel leaderboard text through ArmorStand and Lunar font-render paths. */
public final class LeaderboardHologramDebugCapture {
    private static final long MAX_CAPTURE_MS = 180_000L;
    private static final int MAX_ROWS = 800;
    private static final int MAX_CHARS = 180_000;
    private static final int MAX_ARMOR_STAND_SAMPLES = 80;
    private static final Pattern LEADERBOARD_ROW = Pattern.compile(
            "^\\s*\\d[\\d,]*\\.\\s+.+?\\s+-\\s+[\\d,]+\\s*$");
    private static final Map<String, String> LAST_DETAILS = new HashMap<String, String>();
    private static final List<String> ROWS = new ArrayList<String>();
    private static final Set<String> ARMOR_STAND_LABEL_SAMPLES = new HashSet<String>();
    private static long startedAtMs;
    private static final AtomicLong RENDER_LABEL_HOOK_CALLS = new AtomicLong();
    private static final AtomicLong ARMOR_STAND_RENDER_LABEL_CALLS = new AtomicLong();
    private static final AtomicLong ARMOR_STAND_HOOK_CALLS = new AtomicLong();
    private static final AtomicLong ARMOR_STAND_CANDIDATE_CALLS = new AtomicLong();
    private static final AtomicLong LUNAR_TEXT_HOLOGRAM_CALLS = new AtomicLong();
    private static final AtomicLong LUNAR_TEXT_HOLOGRAM_CANDIDATE_CALLS = new AtomicLong();
    private static final AtomicLong FONT_RENDERER_CALLS = new AtomicLong();
    private static final AtomicLong FONT_RENDERER_CANDIDATE_CALLS = new AtomicLong();
    private static int charCount;
    private static boolean truncated;
    private static volatile boolean active;

    private LeaderboardHologramDebugCapture() { }

    public static synchronized boolean start() {
        if (active) return false;
        ROWS.clear();
        LAST_DETAILS.clear();
        ARMOR_STAND_LABEL_SAMPLES.clear();
        charCount = 0;
        RENDER_LABEL_HOOK_CALLS.set(0L);
        ARMOR_STAND_RENDER_LABEL_CALLS.set(0L);
        ARMOR_STAND_HOOK_CALLS.set(0L);
        ARMOR_STAND_CANDIDATE_CALLS.set(0L);
        LUNAR_TEXT_HOLOGRAM_CALLS.set(0L);
        LUNAR_TEXT_HOLOGRAM_CANDIDATE_CALLS.set(0L);
        FONT_RENDERER_CALLS.set(0L);
        FONT_RENDERER_CANDIDATE_CALLS.set(0L);
        truncated = false;
        startedAtMs = System.currentTimeMillis();
        active = true;
        appendLocked("capture_started durationLimitMs=" + MAX_CAPTURE_MS);
        Minecraft mc = Minecraft.getMinecraft();
        appendLocked("capture_context disguiseEnabled=" + LocalDisguise.isEnabled()
                + " source=" + clean(LocalDisguise.fastRealNameText, 32)
                + " target=" + clean(LocalDisguise.fastTargetNameText, 32)
                + " remoteDisguises=" + RemoteDisguiseManager.getActiveCount()
                + " game=" + clean(mc == null ? "<null>" : GameContext.explainDetection(mc), 700));
        return true;
    }

    public static boolean isActive() { return active; }

    public static long getArmorStandHookCalls() { return ARMOR_STAND_HOOK_CALLS.get(); }

    public static long getLunarTextHologramCalls() { return LUNAR_TEXT_HOLOGRAM_CALLS.get(); }

    public static long getRenderLabelHookCalls() { return RENDER_LABEL_HOOK_CALLS.get(); }

    /** Tracks whether the normal Render label path is reached and samples bounded raw ArmorStand labels. */
    public static void recordRenderLabelEntry(Entity entity, String label) {
        if (!active) return;
        RENDER_LABEL_HOOK_CALLS.incrementAndGet();
        if (!(entity instanceof EntityArmorStand)) return;
        ARMOR_STAND_RENDER_LABEL_CALLS.incrementAndGet();
        int entityId = entity.getEntityId();
        String sample = entityId + "|" + safe(label);
        synchronized (LeaderboardHologramDebugCapture.class) {
            if (!active || ARMOR_STAND_LABEL_SAMPLES.size() >= MAX_ARMOR_STAND_SAMPLES
                    || !ARMOR_STAND_LABEL_SAMPLES.add(sample)) return;
            Minecraft mc = Minecraft.getMinecraft();
            recordLocked("render-label-input", "entity=" + entityId,
                    "candidate=" + isCandidate(label, null) + " leaderboardPattern=" + isLeaderboardRow(label)
                            + " input=" + clean(label, 500)
                            + " game=" + clean(mc == null ? "<null>" : GameContext.explainDetection(mc), 700));
        }
    }

    public static void recordArmorStandLabel(Entity entity, String input, String output,
            boolean disguiseRewriteEnabled) {
        if (!active) return;
        ARMOR_STAND_HOOK_CALLS.incrementAndGet();
        if (!isCandidate(input, output)) return;
        ARMOR_STAND_CANDIDATE_CALLS.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        synchronized (LeaderboardHologramDebugCapture.class) {
            recordLocked("armor-stand-label", entity == null ? "entity=unknown" : "entity=" + entity.getEntityId(),
                "rewriteEnabled=" + disguiseRewriteEnabled + " changed=" + !safe(input).equals(safe(output))
                        + " input=" + clean(input, 500) + " output=" + clean(output, 500)
                        + " game=" + clean(mc == null ? "<null>" : GameContext.explainDetection(mc), 700));
        }
    }

    /** Called by the native renderer tap even when the renderer was loaded before mixins. */
    public static String rewriteLunarTextHologram(String input) {
        try {
            String output = LocalDisguise.rewriteLunarLeaderboardTextHologram(input);
            recordLunarTextHologram(input, output, LocalDisguise.fastEnabledFlag);
            return output;
        } catch (Throwable ignored) {
            return input;
        }
    }

    /** Records Lunar's separate world-text hologram renderer, which bypasses entity labels. */
    public static void recordLunarTextHologram(String input, String output, boolean disguiseRewriteEnabled) {
        if (!active) return;
        LUNAR_TEXT_HOLOGRAM_CALLS.incrementAndGet();
        if (!isCandidate(input, output)) return;
        LUNAR_TEXT_HOLOGRAM_CANDIDATE_CALLS.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        synchronized (LeaderboardHologramDebugCapture.class) {
            recordLocked("lunar-text-hologram", "world-renderer",
                    "rewriteEnabled=" + disguiseRewriteEnabled + " changed=" + !safe(input).equals(safe(output))
                            + " input=" + clean(input, 500) + " output=" + clean(output, 500)
                            + " game=" + clean(mc == null ? "<null>" : GameContext.explainDetection(mc), 700));
        }
    }

    public static void recordFontRendererText(String input, String output) {
        if (!active) return;
        FONT_RENDERER_CALLS.incrementAndGet();
        if (!isCandidate(input, output)) return;
        FONT_RENDERER_CANDIDATE_CALLS.incrementAndGet();
        Minecraft mc = Minecraft.getMinecraft();
        synchronized (LeaderboardHologramDebugCapture.class) {
            recordLocked("font-render-entry", "candidate",
                "changed=" + !safe(input).equals(safe(output)) + " input=" + clean(input, 500)
                        + " output=" + clean(output, 500)
                        + " game=" + clean(mc == null ? "<null>" : GameContext.explainDetection(mc), 700));
        }
    }

    public static synchronized File stop() throws Exception {
        if (ROWS.isEmpty()) return null;
        active = false;
        appendLocked("capture_summary renderLabelHookCalls=" + RENDER_LABEL_HOOK_CALLS.get()
                + " armorStandRenderLabelCalls=" + ARMOR_STAND_RENDER_LABEL_CALLS.get()
                + " armorStandHookCalls=" + ARMOR_STAND_HOOK_CALLS.get()
                + " armorStandCandidateCalls=" + ARMOR_STAND_CANDIDATE_CALLS.get()
                + " lunarTextHologramCalls=" + LUNAR_TEXT_HOLOGRAM_CALLS.get()
                + " lunarTextHologramCandidateCalls=" + LUNAR_TEXT_HOLOGRAM_CANDIDATE_CALLS.get()
                + " fontRendererCalls=" + FONT_RENDERER_CALLS.get()
                + " fontRendererCandidateCalls=" + FONT_RENDERER_CANDIDATE_CALLS.get());
        StringBuilder text = new StringBuilder(charCount + 64);
        for (String row : ROWS) text.append(row).append('\n');
        if (truncated) text.append("capture_truncated=true\n");
        File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
        Files.createDirectories(directory.toPath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File file = File.createTempFile("leaderboard_hologram_" + stamp + "_", ".log", directory);
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        ROWS.clear();
        LAST_DETAILS.clear();
        ARMOR_STAND_LABEL_SAMPLES.clear();
        charCount = 0;
        truncated = false;
        return file;
    }

    private static boolean isCandidate(String input, String output) {
        String source = LocalDisguise.fastRealNameText;
        String target = LocalDisguise.fastTargetNameText;
        return isInteresting(input, source, target) || isInteresting(output, source, target);
    }

    private static boolean isInteresting(String text, String source, String target) {
        if (text == null || text.isEmpty()) return false;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) plain = text;
        String trimmed = plain.trim();
        if (isLeaderboardRow(trimmed)
                || "You have no data yet.".equalsIgnoreCase(trimmed)) return true;
        return containsName(trimmed, source) || containsName(trimmed, target);
    }

    private static boolean isLeaderboardRow(String text) {
        if (text == null) return false;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return LEADERBOARD_ROW.matcher((plain == null ? text : plain).trim()).matches();
    }

    private static boolean containsName(String text, String name) {
        if (text == null || name == null || name.isEmpty()) return false;
        for (int at = text.indexOf(name.charAt(0)); at >= 0; at = text.indexOf(name.charAt(0), at + 1)) {
            int end = at + name.length();
            if (end <= text.length() && text.regionMatches(true, at, name, 0, name.length())
                    && (at == 0 || !isUsernameChar(text.charAt(at - 1)))
                    && (end == text.length() || !isUsernameChar(text.charAt(end)))) return true;
        }
        return false;
    }

    private static boolean isUsernameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9' || c == '_';
    }

    private static void recordLocked(String event, String key, String details) {
        if (!active) return;
        long now = System.currentTimeMillis();
        if (now - startedAtMs > MAX_CAPTURE_MS) {
            appendLocked("capture_auto_stopped durationLimitMs=" + MAX_CAPTURE_MS);
            active = false;
            return;
        }
        String signature = event + "|" + key + "|" + details.substring(0, Math.min(details.length(), 520));
        if (LAST_DETAILS.containsKey(signature)) return;
        LAST_DETAILS.put(signature, details);
        appendLocked("t=" + Math.max(0L, now - startedAtMs) + "ms event=" + event + " key=" + key + " " + details);
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

    private static String safe(String value) { return value == null ? "<null>" : value; }

    private static String clean(String value, int limit) {
        if (value == null) return "<null>";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return cleaned.length() > limit ? cleaned.substring(0, limit) + "..." : cleaned;
    }
}
