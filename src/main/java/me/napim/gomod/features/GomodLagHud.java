package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live, opt-in view of the existing gomod render and tick profilers. */
public final class GomodLagHud {
    private static final int HISTORY = 720;
    private static final long WINDOW_NS = 750_000_000L;
    private static final long SPIKE_NS = 100_000_000L;
    private static final long AFTER_SPIKE_NS = 2_000_000_000L;
    private static final int WHITE = 0xFFE7EDF6;
    private static final int MUTED = 0xFF94A4B8;
    private static final int GREEN = 0xFF50D890;
    private static final int AMBER = 0xFFF5B84B;
    private static final int RED = 0xFFFF6574;

    private static final long[] frameAt = new long[HISTORY];
    private static final float[] frameMs = new float[HISTORY];
    private static int frameWrite;
    private static int frameCount;
    private static int windowFrameCount;
    private static long lastFrameNs;
    private static long windowAtNs;
    private static Map<String, Counters> renderBaseline = new HashMap<String, Counters>();
    private static Map<String, Counters> tickBaseline = new HashMap<String, Counters>();
    private static List<Row> renderRows = Collections.emptyList();
    private static List<Row> tickRows = Collections.emptyList();
    private static List<Row> frozenRenderRows = Collections.emptyList();
    private static List<Row> frozenTickRows = Collections.emptyList();
    private static List<Row> spikeRenderRows = Collections.emptyList();
    private static List<Row> spikeTickRows = Collections.emptyList();
    private static long[] frozenFrameAt;
    private static float[] frozenFrameMs;
    private static long frozenGraphEndNs;
    private static boolean enabled;
    private static boolean ownRenderProfiler;
    private static boolean ownTickProfiler;
    private static boolean captureArmed;
    private static long spikeAtNs;
    private static boolean spikeSampled;
    private static boolean frozen;
    private static int entities;
    private static int players;
    private static int chunks;
    private static int heapMb;
    private static int frozenEntities;
    private static int frozenPlayers;
    private static int frozenChunks;
    private static int frozenHeapMb;

    public GomodLagHud() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static void setEnabled(boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (on) {
            ownRenderProfiler = !RenderProfiler.isRecording();
            ownTickProfiler = !FeatureProfiler.isRecording();
            if (ownRenderProfiler) RenderProfiler.startRecording();
            if (ownTickProfiler) FeatureProfiler.startRecording();
            resetView();
        } else {
            if (ownRenderProfiler && RenderProfiler.isRecording()
                    && !RenderProfiler.isGpuSynchronizedMode()
                    && !RenderProfiler.isPlayerHooksAlternatingMode()) {
                RenderProfiler.stopRecording();
            }
            if (ownTickProfiler && FeatureProfiler.isRecording()) FeatureProfiler.stopRecording();
            ownRenderProfiler = false;
            ownTickProfiler = false;
            captureArmed = false;
            spikeAtNs = 0L;
            frozen = false;
        }
    }

    public static void resetView() {
        frameWrite = 0;
        frameCount = 0;
        windowFrameCount = 0;
        lastFrameNs = 0L;
        windowAtNs = 0L;
        renderBaseline = new HashMap<String, Counters>();
        tickBaseline = new HashMap<String, Counters>();
        renderRows = Collections.emptyList();
        tickRows = Collections.emptyList();
        spikeRenderRows = Collections.emptyList();
        spikeTickRows = Collections.emptyList();
        frozen = false;
        frozenFrameAt = null;
        frozenFrameMs = null;
        spikeAtNs = 0L;
        spikeSampled = false;
        captureArmed = false;
    }

    /** Arms a capture; the first frame gap above 100 ms freezes after another two seconds. */
    public static void armCapture() {
        if (!enabled) setEnabled(true);
        resetView();
        captureArmed = true;
    }

    public static void toggleFreeze() {
        if (!enabled) setEnabled(true);
        if (frozen) {
            frozen = false;
            frozenFrameAt = null;
            frozenFrameMs = null;
            captureArmed = false;
            spikeAtNs = 0L;
        } else {
            freeze(System.nanoTime());
        }
    }

    /** Called once at the start of each vanilla HUD frame; measures wall-clock frame intervals. */
    public static void onFrameStart() {
        if (!enabled) return;
        windowFrameCount++;
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            long gap = now - lastFrameNs;
            if (gap > 0L) {
                frameAt[frameWrite] = now;
                frameMs[frameWrite] = (float) Math.min(30000.0, gap / 1_000_000.0);
                frameWrite = (frameWrite + 1) % HISTORY;
                if (frameCount < HISTORY) frameCount++;
                if (captureArmed && spikeAtNs == 0L && gap >= SPIKE_NS) {
                    spikeAtNs = now;
                    spikeSampled = false;
                }
            }
        }
        lastFrameNs = now;
        if (captureArmed && spikeAtNs != 0L && now - spikeAtNs >= AFTER_SPIKE_NS) freeze(now);
    }

    private static void freeze(long now) {
        frozen = true;
        captureArmed = false;
        frozenGraphEndNs = now;
        frozenFrameAt = new long[frameCount];
        frozenFrameMs = new float[frameCount];
        for (int i = 0; i < frameCount; i++) {
            int index = (frameWrite - frameCount + i + HISTORY) % HISTORY;
            frozenFrameAt[i] = frameAt[index];
            frozenFrameMs[i] = frameMs[index];
        }
        frozenRenderRows = new ArrayList<Row>(spikeSampled ? spikeRenderRows : renderRows);
        frozenTickRows = new ArrayList<Row>(spikeSampled ? spikeTickRows : tickRows);
        frozenEntities = entities;
        frozenPlayers = players;
        frozenChunks = chunks;
        frozenHeapMb = heapMb;
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) return;
        long now = System.nanoTime();
        if (!frozen && (windowAtNs == 0L || now - windowAtNs >= WINDOW_NS
                || spikeAtNs != 0L && !spikeSampled)) {
            updateWindow(mc, now);
            if (spikeAtNs != 0L && !spikeSampled) {
                spikeRenderRows = new ArrayList<Row>(renderRows);
                spikeTickRows = new ArrayList<Row>(tickRows);
                spikeSampled = true;
            }
        }
        draw(mc, now);
    }

    private static void updateWindow(Minecraft mc, long now) {
        long elapsed = windowAtNs == 0L ? 0L : now - windowAtNs;
        int sampledFrames = windowFrameCount;
        Map<String, Counters> renderCurrent = renderCounters();
        Map<String, Counters> tickCurrent = tickCounters();
        if (elapsed > 0L) {
            renderRows = rank(renderCurrent, renderBaseline, elapsed, sampledFrames);
            tickRows = rank(tickCurrent, tickBaseline, elapsed, sampledFrames);
        }
        renderBaseline = renderCurrent;
        tickBaseline = tickCurrent;
        windowFrameCount = 0;
        windowAtNs = now;
        if (mc.theWorld != null) {
            entities = mc.theWorld.loadedEntityList.size();
            players = mc.theWorld.playerEntities.size();
            chunks = mc.theWorld.getChunkProvider().getLoadedChunkCount();
        } else {
            entities = players = chunks = 0;
        }
        Runtime runtime = Runtime.getRuntime();
        heapMb = (int) ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
    }

    private static Map<String, Counters> renderCounters() {
        Map<String, Counters> result = new HashMap<String, Counters>();
        for (RenderProfiler.ProfileEntry entry : RenderProfiler.buildReport()) {
            result.put(entry.name, new Counters(entry.totalMs, entry.samples));
        }
        return result;
    }

    private static Map<String, Counters> tickCounters() {
        Map<String, Counters> result = new HashMap<String, Counters>();
        for (FeatureProfiler.ProfileEntry entry : FeatureProfiler.buildReport()) {
            result.put(entry.name, new Counters(entry.totalMs, entry.samples));
        }
        return result;
    }

    private static List<Row> rank(Map<String, Counters> current, Map<String, Counters> before,
            long windowNs, int sampledFrames) {
        List<Row> rows = new ArrayList<Row>();
        double seconds = windowNs / 1_000_000_000.0;
        double frames = Math.max(1, sampledFrames);
        for (Map.Entry<String, Counters> item : current.entrySet()) {
            Counters prior = before.get(item.getKey());
            if (prior == null) continue;
            Counters value = item.getValue();
            double deltaMs = value.totalMs - prior.totalMs;
            long calls = value.calls - prior.calls;
            if (deltaMs <= 0.0 || calls <= 0L) continue;
            rows.add(new Row(item.getKey(), deltaMs / seconds, deltaMs / frames, deltaMs / calls, calls));
        }
        rows.sort(Comparator.comparingDouble((Row row) -> row.msPerFrame).reversed());
        return rows;
    }

    private static void draw(Minecraft mc, long now) {
        ScaledResolution resolution = new ScaledResolution(mc);
        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();
        int w = Math.min(410, screenW - 12);
        if (w < 220) return;
        int h = Math.min(340, screenH - 12);
        if (h < 185) return;
        int x = screenW - w - 6;
        int y = 6;
        FontRenderer font = mc.fontRendererObj;
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Gui.drawRect(x, y, x + w, y + h, 0xE7101722);
        Gui.drawRect(x, y, x + w, y + 2, frozen ? RED : captureArmed ? AMBER : GREEN);
        String state = frozen ? "FROZEN" : spikeAtNs != 0L ? "CAPTURING" : captureArmed ? "ARMED >100ms" : "LIVE";
        font.drawStringWithShadow("gomod123  /  LAG MONITOR", x + 10, y + 9, WHITE);
        drawRight(font, state, x + w - 10, y + 9, frozen ? RED : captureArmed ? AMBER : GREEN);
        float latest = latestFrameMs();
        float peak = frozen ? peakFrozenFrameMs() : latest;
        String stats = frozen
                ? String.format(Locale.ROOT, "Peak %.0f ms frame gap   %d MB heap", peak, frozenHeapMb)
                : String.format(Locale.ROOT, "%d FPS   %.1f ms frame gap   %d MB heap", Minecraft.getDebugFPS(), latest, heapMb);
        font.drawStringWithShadow(stats, x + 10, y + 27, peak >= 100 ? RED : peak >= 33 ? AMBER : WHITE);
        drawGraph(x + 10, y + 46, w - 20, Math.min(75, Math.max(45, h / 4)), now);
        int graphBottom = y + 46 + Math.min(75, Math.max(45, h / 4));
        font.drawStringWithShadow("entities " + (frozen ? frozenEntities : entities)
                        + "   players " + (frozen ? frozenPlayers : players)
                        + "   chunks " + (frozen ? frozenChunks : chunks),
                x + 10, graphBottom + 8, MUTED);
        int sectionY = graphBottom + 26;
        int columnsGap = 12;
        int columnW = (w - 20 - columnsGap) / 2;
        drawSection(font, "RENDER  ms/frame", frozen ? frozenRenderRows : renderRows,
                RenderProfiler.isRecording(), x + 10, sectionY, columnW, h);
        drawSection(font, "TICK  ms/frame", frozen ? frozenTickRows : tickRows,
                FeatureProfiler.isRecording(), x + 10 + columnW + columnsGap, sectionY, columnW, h);
        String hint = frozen ? "capture frozen  /gomodlag hud freeze to resume"
                : captureArmed ? "waiting for a >100 ms frame gap"
                : "largest measured sections; nested timings may overlap";
        font.drawStringWithShadow(hint, x + 10, y + h - 24, MUTED);
        font.drawStringWithShadow("/gomodlag hud capture  |  freeze  |  off", x + 10, y + h - 13, MUTED);
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
        HudGlState.restoreForOverlayText();
    }

    private static void drawGraph(int x, int y, int w, int h, long now) {
        Gui.drawRect(x, y, x + w, y + h, 0xB0182533);
        int thresholdY = y + h - Math.min(h - 2, Math.round(33.3F * (h - 4) / 100.0F));
        Gui.drawRect(x, thresholdY, x + w, thresholdY + 1, 0x56728498);
        long end = frozen ? frozenGraphEndNs : now;
        long span = frozen && spikeAtNs != 0L ? 12_000_000_000L : 5_000_000_000L;
        int buckets = Math.max(1, w / 4);
        float[] peaks = new float[buckets];
        if (frozen && frozenFrameAt != null) {
            for (int i = 0; i < frozenFrameAt.length; i++) addPeak(peaks, frozenFrameAt[i], frozenFrameMs[i], end, span);
        } else {
            for (int i = 0; i < frameCount; i++) {
                int index = (frameWrite - frameCount + i + HISTORY) % HISTORY;
                addPeak(peaks, frameAt[index], frameMs[index], end, span);
            }
        }
        for (int i = 0; i < buckets; i++) {
            float ms = peaks[i];
            if (ms <= 0) continue;
            int bar = Math.max(1, Math.min(h - 4, Math.round(ms * (h - 4) / 100.0F)));
            int left = x + i * w / buckets;
            int right = x + (i + 1) * w / buckets;
            Gui.drawRect(left, y + h - bar, Math.max(left + 1, right), y + h, ms >= 100 ? RED : ms >= 33 ? AMBER : GREEN);
        }
        if (spikeAtNs != 0L) {
            int marker = x + (int) ((spikeAtNs - (end - span)) * w / span);
            if (marker >= x && marker < x + w) Gui.drawRect(marker, y, marker + 1, y + h, 0xCFFFFFFF);
        }
    }

    private static void addPeak(float[] peaks, long at, float ms, long end, long span) {
        long from = end - span;
        if (at < from || at > end) return;
        int index = (int) ((at - from) * peaks.length / span);
        index = Math.min(peaks.length - 1, Math.max(0, index));
        peaks[index] = Math.max(peaks[index], ms);
    }

    private static float latestFrameMs() {
        return frameCount == 0 ? 0.0F : frameMs[(frameWrite - 1 + HISTORY) % HISTORY];
    }

    private static float peakFrozenFrameMs() {
        float peak = 0.0F;
        if (frozenFrameMs != null) {
            for (float value : frozenFrameMs) peak = Math.max(peak, value);
        }
        return peak;
    }

    private static void drawSection(FontRenderer font, String title, List<Row> rows, boolean recording,
            int x, int y, int width, int panelHeight) {
        font.drawStringWithShadow(title, x, y, WHITE);
        Gui.drawRect(x, y + 12, x + width, y + 13, 0xFF354658);
        if (!recording) {
            font.drawStringWithShadow("profiler stopped", x, y + 22, AMBER);
            return;
        }
        if (rows.isEmpty()) {
            font.drawStringWithShadow("Collecting samples", x, y + 22, MUTED);
            return;
        }
        int rowHeight = 37;
        int maxRows = Math.max(1, Math.min(4, (panelHeight - 185) / rowHeight));
        double top = Math.max(0.01, rows.get(0).msPerFrame);
        for (int i = 0; i < Math.min(maxRows, rows.size()); i++) {
            Row row = rows.get(i);
            int ry = y + 21 + i * rowHeight;
            String label = friendlyName(row.name);
            List<String> labelLines = wrap(font, label, Math.max(20, width - 47));
            for (int line = 0; line < labelLines.size(); line++) {
                font.drawStringWithShadow(labelLines.get(line), x, ry + line * font.FONT_HEIGHT, WHITE);
            }
            drawRight(font, String.format(Locale.ROOT, "%.2f", row.msPerFrame), x + width, ry,
                    row.msPerFrame > 30 ? RED : row.msPerFrame > 8 ? AMBER : GREEN);
            Gui.drawRect(x, ry + 20, x + width, ry + 24, 0xFF273849);
            Gui.drawRect(x, ry + 20, x + Math.max(1, (int) (width * row.msPerFrame / top)), ry + 24,
                    row.msPerFrame > 30 ? RED : row.msPerFrame > 8 ? AMBER : GREEN);
            font.drawStringWithShadow(String.format(Locale.ROOT, "%.2f ms/call  %d calls", row.msPerCall, row.calls),
                    x, ry + 25, MUTED);
        }
    }

    private static List<String> wrap(FontRenderer font, String text, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        String remaining = text == null ? "" : text.trim();
        if (remaining.isEmpty()) {
            lines.add("");
            return lines;
        }
        while (!remaining.isEmpty()) {
            if (font.getStringWidth(remaining) <= maxWidth) {
                lines.add(remaining);
                break;
            }
            int split = remaining.length();
            while (split > 0 && font.getStringWidth(remaining.substring(0, split)) > maxWidth) {
                split--;
            }
            if (split <= 0) {
                split = 1;
            }
            int whitespace = remaining.lastIndexOf(' ', split - 1);
            if (whitespace > 0) {
                split = whitespace;
            }
            lines.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }
        return lines;
    }

    private static String friendlyName(String name) {
        if (name.startsWith("GuiIngame.overlay.")) return name.substring("GuiIngame.overlay.".length());
        if (name.startsWith("Forge.RenderWorldLast.")) return name.substring("Forge.RenderWorldLast.".length());
        if (name.startsWith("RenderGlobal.tail.")) return name.substring("RenderGlobal.tail.".length());
        if (name.startsWith("RenderGlobal.")) return name.substring("RenderGlobal.".length());
        if (name.startsWith("GomodPlayerHooks.")) return name.substring("GomodPlayerHooks.".length());
        return name;
    }

    private static void drawRight(FontRenderer font, String label, int right, int top, int color) {
        font.drawStringWithShadow(label, right - font.getStringWidth(label), top, color);
    }

    private static final class Counters {
        private final double totalMs;
        private final long calls;
        private Counters(double totalMs, long calls) {
            this.totalMs = totalMs;
            this.calls = calls;
        }
    }

    private static final class Row {
        private final String name;
        private final double msPerSecond;
        private final double msPerFrame;
        private final double msPerCall;
        private final long calls;
        private Row(String name, double msPerSecond, double msPerFrame, double msPerCall, long calls) {
            this.name = name;
            this.msPerSecond = msPerSecond;
            this.msPerFrame = msPerFrame;
            this.msPerCall = msPerCall;
            this.calls = calls;
        }
    }
}
