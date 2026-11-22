package me.napim.gomod.features;

import java.util.Locale;

/** Bounded frame-gap histogram; used only during an explicit performance capture. */
final class FrameTimingStats {
    private static final long BIN_NS = 100_000L;
    private final long[] histogram = new long[1001];
    private long count, totalNs, maxNs;

    void add(long nanos) {
        if (nanos <= 0L) return;
        count++;
        totalNs += nanos;
        maxNs = Math.max(maxNs, nanos);
        histogram[(int) Math.min(histogram.length - 1, nanos / BIN_NS)]++;
    }

    long count() { return count; }
    double averageMs() { return count == 0L ? 0.0 : totalNs / (count * 1_000_000.0); }
    double fps() { return totalNs == 0L ? 0.0 : count * 1_000_000_000.0 / totalNs; }

    double percentileMs(double quantile) {
        if (count == 0L) return 0.0;
        long target = Math.max(1L, (long) Math.ceil(count * quantile));
        long seen = 0L;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target) return i * BIN_NS / 1_000_000.0;
        }
        return maxNs / 1_000_000.0;
    }

    String describe(String state) {
        return String.format(Locale.ROOT,
                "%s frames=%d avgMs=%.3f fps=%.1f p50Ms=%.1f p95Ms=%.1f p99Ms=%.1f maxMs=%.3f",
                state, count, averageMs(), fps(), percentileMs(0.50), percentileMs(0.95),
                percentileMs(0.99), maxNs / 1_000_000.0);
    }
}
