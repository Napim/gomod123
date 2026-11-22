package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-render-thread sampler (named sections). Unlike {@link FeatureProfiler}, this is meant for
 * {@code renderEntities}, world-last, and HUD paths — issues {@code /gomodlag tick off} does not silence.
 */
public final class RenderProfiler {

    private static volatile boolean recording;
    private static volatile long profilerStartMs;
    private static volatile long profilerStopMs;
    private static volatile long renderEntitiesFrames;
    private static volatile boolean gpuSynchronizedMode;
    private static volatile boolean playerHooksBypassedMode;
    private static volatile boolean playerHooksAlternatingMode;
    private static volatile boolean deepPlayerHookMode;

    private static final ConcurrentHashMap<String, long[]> totalNs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, long[]> sampleCount = new ConcurrentHashMap<>();
    private static final List<String> registrationOrder = new ArrayList<>();
    private static final ConcurrentHashMap<String, Long> lastSampleNs = new ConcurrentHashMap<>();

    private RenderProfiler() {}

    public static boolean isRecording() {
        return recording;
    }

    public static void startRecording() {
        startRecording(false, false, false);
    }

    public static void startGpuSynchronizedRecording() {
        startRecording(true, false, false);
    }

    /** GPU-timed control run with gomod's player-specific render behavior temporarily bypassed. */
    public static void startPlayerBaselineRecording() {
        startRecording(true, true, false);
    }

    /** Same-scene A/B run: alternates normal and bypassed player hooks every 30 entity frames. */
    public static void startPlayerHooksAlternatingRecording() {
        startRecording(true, false, true);
    }

    /** Same-scene A/B run with extra timings around the player-render hook boundaries. */
    public static void startPlayerHooksDeepRecording() {
        startRecording(true, false, true);
        deepPlayerHookMode = true;
    }

    private static void startRecording(boolean gpuSynchronized, boolean bypassPlayerHooks,
            boolean alternatePlayerHooks) {
        totalNs.clear();
        sampleCount.clear();
        lastSampleNs.clear();
        registrationOrder.clear();
        renderEntitiesFrames = 0;
        profilerStartMs = System.currentTimeMillis();
        profilerStopMs = 0;
        gpuSynchronizedMode = gpuSynchronized;
        playerHooksBypassedMode = bypassPlayerHooks;
        playerHooksAlternatingMode = alternatePlayerHooks;
        deepPlayerHookMode = false;
        recording = true;
    }

    public static void stopRecording() {
        if (recording) profilerStopMs = System.currentTimeMillis();
        recording = false;
        deepPlayerHookMode = false;
    }

    public static void resetAll() {
        totalNs.clear();
        sampleCount.clear();
        lastSampleNs.clear();
        registrationOrder.clear();
        renderEntitiesFrames = 0;
        profilerStartMs = recording ? System.currentTimeMillis() : 0;
        profilerStopMs = 0;
        gpuSynchronizedMode = false;
        playerHooksBypassedMode = false;
        playerHooksAlternatingMode = false;
        deepPlayerHookMode = false;
    }

    public static boolean isGpuSynchronizedMode() {
        return gpuSynchronizedMode;
    }

    public static boolean shouldGpuSynchronize() {
        return recording && gpuSynchronizedMode;
    }

    public static boolean isPlayerHooksBypassedMode() {
        return playerHooksBypassedMode;
    }

    /** Active only during the control recording; stopping immediately restores normal rendering. */
    public static boolean shouldBypassPlayerHooks() {
        return recording && (playerHooksBypassedMode
                || (playerHooksAlternatingMode && ((renderEntitiesFrames / 30L) & 1L) != 0L));
    }

    public static boolean isPlayerHooksAlternatingMode() {
        return playerHooksAlternatingMode;
    }

    public static boolean isDeepPlayerHookMode() {
        return deepPlayerHookMode;
    }

    /** Begins an extra diagnostic section only during {@code /gomodlag render deep}. */
    public static long beginDeep(String section) {
        return deepPlayerHookMode && recording ? System.nanoTime() : 0L;
    }

    /** Ends an extra diagnostic section only during {@code /gomodlag render deep}. */
    public static void endDeep(String section, long startNs) {
        if (startNs != 0L) {
            end(section, startNs);
        }
    }

    /** Once per {@link net.minecraft.client.renderer.RenderGlobal#renderEntities} entry (best-effort frame boundary). */
    public static void markRenderEntitiesFrame() {
        if (!recording) {
            return;
        }
        renderEntitiesFrames++;
    }

    public static long getRenderEntitiesFrames() {
        return renderEntitiesFrames;
    }

    public static long getElapsedSeconds() {
        if (profilerStartMs == 0L) {
            return 0L;
        }
        long end = recording ? System.currentTimeMillis() : profilerStopMs;
        return Math.max(0L, end - profilerStartMs) / 1000L;
    }

    public static long begin(String section) {
        if (!recording) {
            return 0L;
        }
        return System.nanoTime();
    }

    public static void end(String section, long startNs) {
        if (!recording || startNs == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startNs;
        long[] acc = totalNs.computeIfAbsent(section, k -> {
            registrationOrder.add(k);
            return new long[1];
        });
        long[] cnt = sampleCount.computeIfAbsent(section, k -> new long[1]);
        synchronized (acc) {
            acc[0] += elapsed;
        }
        synchronized (cnt) {
            cnt[0]++;
        }
        lastSampleNs.put(section, elapsed);
    }

    public static List<ProfileEntry> buildReport() {
        List<ProfileEntry> report = new ArrayList<>();
        List<String> names = new ArrayList<>(registrationOrder);
        for (String name : names) {
            long[] ns = totalNs.get(name);
            long[] cnt = sampleCount.get(name);
            Long last = lastSampleNs.get(name);
            if (ns == null || cnt == null || cnt[0] == 0L) {
                continue;
            }
            long samples = cnt[0];
            long totalNsVal;
            synchronized (ns) {
                totalNsVal = ns[0];
            }
            double avgUs = (totalNsVal / (double) samples) / 1000.0;
            double lastUs = last != null ? last / 1000.0 : 0.0;
            double totalMs = totalNsVal / 1_000_000.0;
            report.add(new ProfileEntry(name, avgUs, lastUs, totalMs, samples));
        }
        report.sort((a, b) -> Double.compare(b.avgUs, a.avgUs));
        return report;
    }

    public static final class ProfileEntry {
        public final String name;
        public final double avgUs;
        public final double lastUs;
        public final double totalMs;
        public final long samples;

        ProfileEntry(String name, double avgUs, double lastUs, double totalMs, long samples) {
            this.name = name;
            this.avgUs = avgUs;
            this.lastUs = lastUs;
            this.totalMs = totalMs;
            this.samples = samples;
        }
    }
}
