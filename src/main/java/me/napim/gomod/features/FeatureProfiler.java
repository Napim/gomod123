package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight per-feature tick profiler.
 *
 * Usage in ClientTickHub:
 *   long t = FeatureProfiler.begin("ChestRouteManager");
 *   doWork();
 *   FeatureProfiler.end("ChestRouteManager", t);
 *
 * Query via /goperf command.
 */
public final class FeatureProfiler {

    // ---- state ----
    private static volatile boolean recording = false;

    /** Total accumulated nanoseconds per feature name, across all recorded ticks. */
    private static final ConcurrentHashMap<String, long[]> totalNs = new ConcurrentHashMap<>();
    /** Number of tick samples per feature. */
    private static final ConcurrentHashMap<String, long[]> sampleCount = new ConcurrentHashMap<>();

    /** Insertion-order list of feature names, for stable report ordering. */
    private static final List<String> registrationOrder = new ArrayList<>();

    /** Nanoseconds spent in the last completed tick, per feature. */
    private static final ConcurrentHashMap<String, Long> lastTickNs = new ConcurrentHashMap<>();

    private static volatile long totalTicksRecorded = 0;
    private static volatile long profilerStartMs = 0;
    private static volatile long profilerStopMs = 0;

    private FeatureProfiler() {}

    // ---- public API ----

    public static boolean isRecording() {
        return recording;
    }

    public static void startRecording() {
        totalNs.clear();
        sampleCount.clear();
        lastTickNs.clear();
        registrationOrder.clear();
        totalTicksRecorded = 0;
        profilerStartMs = System.currentTimeMillis();
        profilerStopMs = 0;
        recording = true;
    }

    public static void stopRecording() {
        if (recording) profilerStopMs = System.currentTimeMillis();
        recording = false;
    }

    public static void resetAll() {
        totalNs.clear();
        sampleCount.clear();
        lastTickNs.clear();
        registrationOrder.clear();
        totalTicksRecorded = 0;
        profilerStartMs = recording ? System.currentTimeMillis() : 0;
        profilerStopMs = 0;
    }

    /**
     * Call right before the feature work. Returns System.nanoTime() if recording,
     * or 0 if not (zero-cost when disabled).
     */
    public static long begin(String feature) {
        if (!recording) return 0L;
        return System.nanoTime();
    }

    /**
     * Call right after the feature work, passing the value from {@link #begin}.
     */
    public static void end(String feature, long startNs) {
        if (!recording || startNs == 0L) return;
        long elapsed = System.nanoTime() - startNs;
        long[] acc = totalNs.computeIfAbsent(feature, k -> {
            registrationOrder.add(k);
            return new long[1];
        });
        long[] cnt = sampleCount.computeIfAbsent(feature, k -> new long[1]);
        synchronized (acc) { acc[0] += elapsed; }
        synchronized (cnt) { cnt[0]++; }
        lastTickNs.put(feature, elapsed);
    }

    /** Call once per full ClientTickHub run (after all features have been timed). */
    public static void recordTickDone() {
        if (!recording) return;
        totalTicksRecorded++;
    }

    public static long getTotalTicksRecorded() {
        return totalTicksRecorded;
    }

    public static long getElapsedSeconds() {
        if (profilerStartMs == 0) return 0;
        long end = recording ? System.currentTimeMillis() : profilerStopMs;
        return Math.max(0, end - profilerStartMs) / 1000;
    }

    /**
     * Returns a sorted list of entries: [featureName, avgMicros, lastMicros, totalMs].
     * Sorted by average microseconds descending (worst first).
     */
    public static List<ProfileEntry> buildReport() {
        List<ProfileEntry> report = new ArrayList<>();
        for (String name : registrationOrder) {
            long[] ns = totalNs.get(name);
            long[] cnt = sampleCount.get(name);
            Long last = lastTickNs.get(name);
            if (ns == null || cnt == null || cnt[0] == 0) continue;
            long samples = cnt[0];
            long totalNsVal;
            synchronized (ns) { totalNsVal = ns[0]; }
            double avgUs = (totalNsVal / (double) samples) / 1000.0;
            double lastUs = (last != null ? last / 1000.0 : 0.0);
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
