package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/** Opt-in, bounded TAB CPU/traffic trace. No render-time file I/O or request payloads. */
public final class TabPerformanceDebug {
    private static final int DEFAULT_SECONDS = 120;
    private static final long SETTLE_NS = 250_000_000L;
    private static volatile Session active;
    private static final ThreadLocal<PendingMetrics> pendingMetrics = new ThreadLocal<PendingMetrics>();

    public enum Metric {
        FRAME_GAP, TICK_GAP, DISPLAY_UPDATE, PLAYER_RENDER, PLAYER_NAMETAG, PLAYER_MODEL, PLAYER_LAYERS,
        PLAYER_BADGE_LOOKUP, VANILLA_OVERLAY, COMPOSE, FINALIZE_ROWS, FONT_REWRITE,
        FONT_WIDTH_REWRITE, FONT_DRAW_REWRITE, FONT_HUD_NAMES, FONT_SELECTOR,
        FONT_SIDEBAR, FONT_TAB_DECORATE, FONT_REPLAY, FONT_LOCAL_DISGUISE, FONT_REMOTE_DISGUISE,
        FONT_TAB_STATS_ALIGN, TAB_RENDERED_NAME, TAB_VICTIM_TEXT, TAB_VICTIM_OFFSET, TAB_VICTIM_WIDTH
    }

    private static final class Totals {
        long calls, total, max;
        void add(long nanos) { calls++; total += nanos; max = Math.max(max, nanos); }
        void add(Totals other) {
            if (other == null || other.calls == 0L) return;
            calls += other.calls;
            total += other.total;
            max = Math.max(max, other.max);
            other.clear();
        }
        void clear() { calls = 0L; total = 0L; max = 0L; }
    }
    private static final class Bucket {
        final Totals[] timings = new Totals[Metric.values().length];
        final Totals openGap = new Totals(), closedGap = new Totals();
        final Map<String, Long> traffic = new LinkedHashMap<>();
        int openFrames, closedFrames, hudRefreshes;
        int minecraftFps = -1;
        int loadedPlayers = -1;
        int rawTabRows = -1;
        boolean tabVictimsEnabled;
        Bucket() { for (int i = 0; i < timings.length; i++) timings[i] = new Totals(); }
    }
    private static final class Session {
        final long start = System.nanoTime();
        final Thread client = Thread.currentThread();
        final int limitSeconds;
        final boolean players;
        final Bucket[] buckets;
        final FrameTimingStats openFrames = new FrameTimingStats(), closedFrames = new FrameTimingStats();
        final Totals[] openMetrics = new Totals[Metric.values().length];
        final Totals[] closedMetrics = new Totals[Metric.values().length];
        long observedOpenFrames, observedClosedFrames, hudRefreshes;
        final List<String> events = new ArrayList<>();
        final List<String> stalls = new ArrayList<>();
        final Map<String, Long> stacks = new HashMap<>();
        final long[] gc = gcTotals();
        final String context = "disguise=" + LocalDisguise.fastEnabledFlag
                + " localTrace=" + LocalDisguiseDebugCapture.isActive()
                + " hologramTrace=" + LeaderboardHologramDebugCapture.isActive()
                + " trafficDebug=" + TabStatsTrafficDebug.getMode()
                + " renderProfiler=" + RenderProfiler.isRecording()
                + " gpuSync=" + RenderProfiler.shouldGpuSynchronize()
                + " deepRender=" + RenderProfiler.isDeepPlayerHookMode()
                + " playerHooksBypassed=" + RenderProfiler.shouldBypassPlayerHooks()
                + " tickProfiler=" + FeatureProfiler.isRecording();
        volatile boolean tabOpen;
        volatile long recentOpen;
        volatile long lastOverlay;
        volatile long lastFrame;
        volatile long ended;
        long stateChanged;
        boolean previousFrameOpen;
        long transitionFrames;
        long lastTick, samples, samplingNanos, maxSampleNanos, droppedStacks;
        String lastStallStack;
        Session(int seconds, boolean players) {
            limitSeconds = seconds;
            this.players = players;
            buckets = new Bucket[seconds + 1];
            for (int i = 0; i < buckets.length; i++) buckets[i] = new Bucket();
            for (int i = 0; i < openMetrics.length; i++) { openMetrics[i] = new Totals(); closedMetrics[i] = new Totals(); }
            stateChanged = start;
        }
        int second() { return (int) Math.min(limitSeconds,
                Math.max(0, ((ended == 0L ? System.nanoTime() : ended) - start) / 1_000_000_000L)); }
        void event(String value) {
            if (events.size() < 256) events.add(String.format(Locale.ROOT, "%.3fs %s",
                    (System.nanoTime() - start) / 1_000_000_000.0, value));
        }
    }

    /** Font hooks can record millions of timings in one capture; merge their local batch once per frame. */
    private static final class PendingMetrics {
        final Session session;
        final Totals[] open = new Totals[Metric.values().length];
        final Totals[] closed = new Totals[Metric.values().length];
        final Totals[] bucket = new Totals[Metric.values().length];
        int bucketSecond = -1;

        PendingMetrics(Session session) {
            this.session = session;
            for (int i = 0; i < open.length; i++) {
                open[i] = new Totals();
                closed[i] = new Totals();
                bucket[i] = new Totals();
            }
        }
    }

    private TabPerformanceDebug() { }
    public static boolean isActive() { return active != null; }
    public static long begin() { return active == null ? 0L : System.nanoTime(); }
    public static long beginTab() { Session s = active; return s == null || !s.tabOpen ? 0L : System.nanoTime(); }

    public static void end(Metric metric, long start) {
        Session s = active;
        if (s == null || start == 0L || start < s.start) return;
        long elapsed = System.nanoTime() - start;
        if (Thread.currentThread() == s.client) {
            int second = s.second();
            PendingMetrics pending = pendingMetrics.get();
            if (pending == null || pending.session != s) {
                pending = new PendingMetrics(s);
                pendingMetrics.set(pending);
            }
            if (pending.bucketSecond != second) {
                synchronized (s) { mergePendingMetricsLocked(s, pending); }
                pending.bucketSecond = second;
            }
            int index = metric.ordinal();
            (s.tabOpen ? pending.open[index] : pending.closed[index]).add(elapsed);
            pending.bucket[index].add(elapsed);
            if (elapsed > 50_000_000L) {
                synchronized (s) { s.event("slow " + metric + " ms=" + ms(elapsed) + " tab=" + s.tabOpen); }
            }
            return;
        }
        synchronized (s) {
            s.buckets[s.second()].timings[metric.ordinal()].add(elapsed);
            (s.tabOpen ? s.openMetrics : s.closedMetrics)[metric.ordinal()].add(elapsed);
            if (elapsed > 50_000_000L) s.event("slow " + metric + " ms=" + ms(elapsed) + " tab=" + s.tabOpen);
        }
    }

    /** Caller holds the session monitor. */
    private static void mergePendingMetricsLocked(Session s, PendingMetrics pending) {
        if (pending == null || pending.session != s) return;
        if (pending.bucketSecond >= 0 && pending.bucketSecond < s.buckets.length) {
            Totals[] bucket = s.buckets[pending.bucketSecond].timings;
            for (int i = 0; i < bucket.length; i++) bucket[i].add(pending.bucket[i]);
        }
        for (int i = 0; i < s.openMetrics.length; i++) {
            s.openMetrics[i].add(pending.open[i]);
            s.closedMetrics[i].add(pending.closed[i]);
        }
        pending.bucketSecond = -1;
    }

    public static synchronized boolean start() {
        return start(DEFAULT_SECONDS);
    }

    public static synchronized boolean start(int seconds) {
        return start(seconds, false);
    }

    public static synchronized boolean startPlayers(int seconds) {
        return start(seconds, true);
    }

    private static synchronized boolean start(int seconds, boolean players) {
        if (active != null) return false;
        final Session s = new Session(Math.max(10, Math.min(900, seconds)), players); active = s;
        Thread sampler = new Thread(() -> sample(s), "gomod-tab-lag-sampler");
        sampler.setDaemon(true); sampler.start();
        return true;
    }

    public static String status() {
        Session s = active;
        return s == null ? "TAB lag capture is off." : "TAB lag capture: " + s.second() + "s / " + s.limitSeconds + "s.";
    }

    /** Called once from Minecraft.runGameLoop, independent of Lunar's HUD refresh cadence. */
    public static void onFrame(Minecraft mc) {
        Session s = active;
        if (s == null) return;
        long now = System.nanoTime();
        boolean open = now - s.lastOverlay < 150_000_000L
                || (mc != null && mc.gameSettings != null && mc.gameSettings.keyBindPlayerList.isKeyDown());
        synchronized (s) {
            mergePendingMetricsLocked(s, pendingMetrics.get());
            if (open != s.tabOpen) {
                int rows = mc == null || mc.getNetHandler() == null ? 0 : mc.getNetHandler().getPlayerInfoMap().size();
                s.event("tab=" + open + " rows=" + rows); s.tabOpen = open;
                s.stateChanged = now;
            }
            if (open) s.recentOpen = now;
            Bucket b = s.buckets[s.second()];
            if (b.minecraftFps < 0) {
                b.minecraftFps = Minecraft.getDebugFPS();
                b.loadedPlayers = mc == null || mc.theWorld == null ? 0 : mc.theWorld.playerEntities.size();
                b.rawTabRows = mc == null || mc.getNetHandler() == null ? 0 : mc.getNetHandler().getPlayerInfoMap().size();
                b.tabVictimsEnabled = ConfigManager.isKillCounterTabKillersEnabled();
            }
            if (open) { b.openFrames++; s.observedOpenFrames++; }
            else { b.closedFrames++; s.observedClosedFrames++; }
            if (s.lastFrame != 0L) {
                long gap = now - s.lastFrame;
                b.timings[Metric.FRAME_GAP.ordinal()].add(gap);
                // Discard opening/closing and the short renderer-detection grace period from comparisons.
                if (open == s.previousFrameOpen && now - s.stateChanged >= SETTLE_NS) {
                    if (open) { s.openFrames.add(gap); b.openGap.add(gap); }
                    else { s.closedFrames.add(gap); b.closedGap.add(gap); }
                } else s.transitionFrames++;
                if (gap > 50_000_000L) s.event("frame_gap ms=" + ms(gap) + " tab=" + open);
            }
            s.lastFrame = now;
            s.previousFrameOpen = open;
        }
    }

    /** A cached HUD refresh is not a rendered game frame. Keep its rate visible as a separate sanity check. */
    public static void onHudRefresh() {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            s.hudRefreshes++;
            s.buckets[s.second()].hudRefreshes++;
        }
    }

    public static void onTick() {
        Session s = active;
        if (s == null) return;
        long now = System.nanoTime();
        synchronized (s) {
            if (s.lastTick != 0L) {
                long gap = now - s.lastTick;
                s.buckets[s.second()].timings[Metric.TICK_GAP.ordinal()].add(gap);
                if (gap > 100_000_000L) s.event("tick_gap ms=" + ms(gap) + " tab=" + s.tabOpen);
            }
            s.lastTick = now;
        }
    }

    /** Lunar can keep TAB open without holding the key. Sample the observed renderer too. */
    public static void onNativeTabRow() {
        Session s = active;
        if (s != null && System.nanoTime() - s.lastOverlay > 50_000_000L) onTabOverlay();
    }

    /** Observed overlay entry; also used by the throttled native Lunar row observer. */
    public static void onTabOverlay() {
        Session s = active;
        if (s == null) return;
        synchronized (s) {
            long now = System.nanoTime();
            if (!s.tabOpen) {
                s.event("tab=true source=renderer");
                s.stateChanged = now;
            }
            s.lastOverlay = now;
            s.recentOpen = now;
            s.tabOpen = true;
        }
    }

    public static void traffic(String kind) {
        Session s = active;
        if (s == null) return;
        String key = kind + (Thread.currentThread() == s.client ? " client" : " background");
        synchronized (s) {
            Map<String, Long> counts = s.buckets[s.second()].traffic;
            if (counts.size() < 48 || counts.containsKey(key)) counts.put(key, counts.getOrDefault(key, 0L) + 1L);
        }
    }

    public static void http(String url) {
        if (active == null) return;
        traffic(httpCategory(url));
    }

    // Categories only: never retain a URL, query, API key, UUID, or request body.
    static String httpCategory(String url) {
        if (url == null) return "http.other";
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("api.hypixel.net/player")) return "http.hypixel.player";
        if (u.contains("api.hypixel.net/guild")) return "http.hypixel.guild";
        if (u.contains("mojang.com")) return "http.mojang";
        if (u.contains("gostats")) return "http.gostats";
        return "http.other";
    }

    private static void sample(Session s) {
        int cycle = 0;
        while (active == s && s.second() < s.limitSeconds) {
            try { Thread.sleep(20L); } catch (InterruptedException ignored) { return; }
            if (active != s) return;
            boolean open = s.tabOpen || System.nanoTime() - s.recentOpen < 300_000_000L;
            if (!s.players && !open && ++cycle % 5 != 0) continue; // Player capture keeps 50 Hz in both views.
            long t0 = System.nanoTime();
            StackTraceElement[] trace = s.client.getStackTrace();
            StringBuilder key = new StringBuilder(open ? "TAB " : "CLOSED ");
            key.append(s.client.getState());
            for (int i = 0; i < Math.min(32, trace.length); i++) key.append("\n  ").append(trace[i]);
            long cost = System.nanoTime() - t0;
            synchronized (s) {
                s.samples++; s.samplingNanos += cost; s.maxSampleNanos = Math.max(s.maxSampleNanos, cost);
                String k = key.toString();
                if (s.stacks.size() < 768 || s.stacks.containsKey(k))
                    s.stacks.put(k, s.stacks.getOrDefault(k, 0L) + 1L);
                else s.droppedStacks++;
                // Keep rare stall stacks even after the common-stack table reaches its bound.
                if (open && s.lastFrame != 0L && System.nanoTime() - s.lastFrame > 50_000_000L
                        && !k.equals(s.lastStallStack)) {
                    if (s.stalls.size() >= 64) s.stalls.remove(0);
                    s.stalls.add("second=" + s.second() + " " + k); s.lastStallStack = k;
                }
            }
        }
        if (active == s) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX + stopSession(s)));
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + stopSession(s));
            }
        }
    }

    public static String stop() {
        Session s = active;
        return s == null ? "TAB lag capture is off." : stopSession(s);
    }

    private static String stopSession(Session s) {
        if (Thread.currentThread() == s.client) {
            synchronized (s) { mergePendingMetricsLocked(s, pendingMetrics.get()); }
            pendingMetrics.remove();
        }
        synchronized (TabPerformanceDebug.class) {
            if (active != s) return "TAB lag capture already stopped.";
            s.ended = System.nanoTime();
            active = null;
        }
        StringBuilder out = new StringBuilder("# TAB/player performance capture v4\n");
        synchronized (s) {
            out.append("# ").append(s.context).append('\n');
            out.append("captureMode=").append(s.players ? "PLAYERS" : "TAB").append('\n');
            out.append("# Frame source: Minecraft.runGameLoop HEAD; HUD refreshes are counted separately.\n");
            out.append("# Generic font sections are measured in both TAB states with the same timing hooks.\n");
            out.append("# Timings overlap; FRAME/TICK_GAP include all client work and normal frame pacing.\n");
            out.append("# HTTP counters cover gomod's instrumented request paths, not every Lunar request.\n");
            out.append(s.players ? "# Sampler: 20ms in both views; no per-call disk I/O.\n"
                    : "# Sampler: 20ms TAB / 100ms closed; no per-call disk I/O.\n");
            out.append("# PLAYER_* sections measure completed render callbacks, including native/Lunar/gomod work; calls are passes, not unique players.\n");
            out.append("# CPU submission times do not measure full asynchronous GPU cost; nested sections overlap.\n");
            out.append("# Frame stats discard 250ms after transitions; percentiles use 0.1ms bins (100ms = overflow).\n");
            out.append("durationMs=").append(ms(s.ended - s.start)).append("\n");
            out.append("renderLoopFrames=").append(s.observedOpenFrames + s.observedClosedFrames)
                    .append(" hudRefreshes=").append(s.hudRefreshes)
                    .append(" frameHookMissing=").append(s.observedOpenFrames + s.observedClosedFrames == 0L).append('\n');
            out.append(s.closedFrames.describe("TAB_CLOSED")).append('\n');
            out.append(s.openFrames.describe("TAB_OPEN")).append('\n');
            out.append("transitionFramesExcluded=").append(s.transitionFrames).append('\n');
            out.append("# Inclusive section costs by observed frame; nested parent/child sections must not be added.\n");
            appendMetricTotals(out, "TAB_OPEN", s.openMetrics, s.observedOpenFrames);
            appendMetricTotals(out, "TAB_CLOSED", s.closedMetrics, s.observedClosedFrames);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null) out.append("fpsLimit=")
                    .append(mc.gameSettings.limitFramerate).append(" vsync=")
                    .append(mc.gameSettings.enableVsync).append('\n');
            out.append("samples=").append(s.samples).append(" sampleTotalMs=").append(ms(s.samplingNanos))
                    .append(" sampleMaxMs=").append(ms(s.maxSampleNanos)).append(" droppedStacks=").append(s.droppedStacks).append('\n');
            long[] gc = gcTotals();
            out.append("gcCountDelta=").append(gc[0] - s.gc[0]).append(" gcTimeMsDelta=").append(gc[1] - s.gc[1]).append('\n');
            for (int i = 0; i <= s.second(); i++) {
                Bucket b = s.buckets[i];
                out.append("second=").append(i).append(" openFrames=").append(b.openFrames).append(" closedFrames=").append(b.closedFrames)
                        .append(" hudRefreshes=").append(b.hudRefreshes).append(" minecraftReportedFps=").append(b.minecraftFps)
                        .append(" loadedPlayers=").append(b.loadedPlayers)
                        .append(" rawTabRows=").append(b.rawTabRows)
                        .append(" tabVictims=").append(b.tabVictimsEnabled).append('\n');
                if (b.openGap.calls > 0) out.append("  TAB_OPEN_FRAME avgMs=")
                        .append(ms(b.openGap.total / b.openGap.calls)).append(" maxMs=").append(ms(b.openGap.max)).append('\n');
                if (b.closedGap.calls > 0) out.append("  TAB_CLOSED_FRAME avgMs=")
                        .append(ms(b.closedGap.total / b.closedGap.calls)).append(" maxMs=").append(ms(b.closedGap.max)).append('\n');
                for (Metric m : Metric.values()) {
                    Totals t = b.timings[m.ordinal()];
                    if (t.calls > 0) out.append("  ").append(m).append(" count=").append(t.calls)
                            .append(" totalMs=").append(ms(t.total)).append(" maxMs=").append(ms(t.max)).append('\n');
                }
                for (Map.Entry<String, Long> e : b.traffic.entrySet()) out.append("  ").append(e.getKey()).append(" count=").append(e.getValue()).append('\n');
            }
            out.append("# Transitions and slow calls (bounded to 256)\n");
            for (String e : s.events) out.append(e).append('\n');
            out.append("# Client stacks sampled during TAB frame stalls (>50ms; last 64 distinct)\n");
            for (String e : s.stalls) out.append(e).append('\n');
            out.append("# Sampled client stacks (top 100 per TAB state, up to 32 frames; counts are samples, not calls)\n");
            List<Map.Entry<String, Long>> stacks = new ArrayList<>(s.stacks.entrySet());
            stacks.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (String state : new String[] { "TAB ", "CLOSED " }) {
                int emitted = 0;
                for (Map.Entry<String, Long> e : stacks) {
                    if (!e.getKey().startsWith(state)) continue;
                    out.append("samples=").append(e.getValue()).append(' ').append(e.getKey()).append('\n');
                    if (++emitted >= 100) break;
                }
            }
        }
        try {
            File folder = new File(GomodPaths.getGomod123Dir(), "debugall"); Files.createDirectories(folder.toPath());
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
            File file = File.createTempFile("tab_lag_" + stamp + "_", ".log", folder);
            Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            return "TAB lag debug saved: " + file.getAbsolutePath();
        } catch (Exception e) { return "Could not save TAB lag debug: " + e.getClass().getSimpleName(); }
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0); }

    private static void appendMetricTotals(StringBuilder out, String state, Totals[] metrics, long frames) {
        for (Metric metric : Metric.values()) {
            Totals value = metrics[metric.ordinal()];
            if (value.calls == 0L) continue;
            out.append(state).append(' ').append(metric).append(" calls=").append(value.calls)
                    .append(" totalMs=").append(ms(value.total)).append(" maxMs=").append(ms(value.max))
                    .append(" msPerObservedFrame=").append(frames == 0L ? "n/a" : ms(value.total / frames)).append('\n');
        }
    }
    private static long[] gcTotals() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount()); time += Math.max(0L, bean.getCollectionTime());
        }
        return new long[] { count, time };
    }
}
