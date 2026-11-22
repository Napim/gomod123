package me.napim.gomod.features;

/** Smooth local/peer-visible travel from the companion's current spot to a recent death location. */
public final class GoparkyTauntTravel {
    private final double[] from;
    private final double[] to;
    private final long startedAtMs;
    private final long durationMs;
    private final double initialFrameYaw;
    private final double headingFrameYaw;

    public GoparkyTauntTravel(double[] from, double[] to, long startedAtMs, long durationMs) {
        this(from, to, startedAtMs, durationMs, Double.NaN);
    }

    /** Initial and target yaw use Lunar's rendered-frame convention (entity yaw + 180 degrees). */
    public GoparkyTauntTravel(double[] from, double[] to, long startedAtMs, long durationMs,
                              double initialFrameYaw) {
        if (!valid(from) || !valid(to) || durationMs < 1L) {
            throw new IllegalArgumentException("Taunt travel needs finite XYZ points and a positive duration.");
        }
        if (!Double.isNaN(initialFrameYaw) && !Double.isFinite(initialFrameYaw)) {
            throw new IllegalArgumentException("Invalid initial frame yaw.");
        }
        this.from = from.clone();
        this.to = to.clone();
        this.startedAtMs = startedAtMs;
        this.durationMs = durationMs;
        double dx = to[0] - from[0], dz = to[2] - from[2];
        double entityYaw = Math.hypot(dx, dz) < 1.0E-6D
                ? (Double.isNaN(initialFrameYaw) ? 0D : initialFrameYaw - 180D)
                : Math.toDegrees(Math.atan2(-dx, dz));
        this.initialFrameYaw = normalizeDegrees(Double.isNaN(initialFrameYaw)
                ? entityYaw + 180D : initialFrameYaw);
        this.headingFrameYaw = normalizeDegrees(entityYaw + 180D);
    }

    public static long durationFor(double distance) {
        return durationFor(distance, GoparkyTauntSettings.DEFAULT_TRAVEL_BLOCKS_PER_SECOND);
    }

    /** Estimate native path time with a short setup allowance and a bounded one-time route timeout. */
    public static long durationFor(double distance, double travelBlocksPerSecond) {
        if (!Double.isFinite(distance) || distance < 0D) throw new IllegalArgumentException("Invalid travel distance.");
        if (!Double.isFinite(travelBlocksPerSecond)
                || travelBlocksPerSecond < 0.5D || travelBlocksPerSecond > 12.0D) {
            throw new IllegalArgumentException("Invalid travel speed.");
        }
        return Math.max(700L, Math.min(30_000L,
                700L + Math.round(distance * 1000D / travelBlocksPerSecond)));
    }

    /** Returns the animated position, held at the destination until the five-second taunt ends. */
    public double[] sample(long nowMs) {
        double t = Math.max(0D, Math.min(1D, (nowMs - startedAtMs) / (double) durationMs));
        double eased = t * t * (3D - 2D * t);
        return new double[] {from[0] + (to[0] - from[0]) * eased,
                from[1] + (to[1] - from[1]) * eased,
                from[2] + (to[2] - from[2]) * eased};
    }

    /** Position plus a smooth turn toward the route, in Lunar's native frame-yaw convention. */
    public double[] sampleFrame(long nowMs) {
        double[] point = sample(nowMs);
        return new double[] {point[0], point[1], point[2], frameYawAt(nowMs)};
    }

    /** Turns into the run over 160 ms so the mascot does not snap to face the destination. */
    public double frameYawAt(long nowMs) {
        double turn = smoothStep(Math.max(0D, Math.min(1D, (nowMs - startedAtMs) / 160D)));
        return interpolateDegrees(initialFrameYaw, headingFrameYaw, turn);
    }

    /** Fades the running gait in and out with the eased route. */
    public double runningAmount(long nowMs) {
        if (distance() < 0.05D) return 0D;
        double progress = Math.max(0D, Math.min(1D, (nowMs - startedAtMs) / (double) durationMs));
        return Math.min(smoothStep(progress / 0.12D), smoothStep((1D - progress) / 0.12D));
    }

    /** Smoothly rejoin Lunar's live follow path after the taunt animation ends. */
    public double[] returnTo(double[] followPosition, long elapsedReturnMs, long returnDurationMs) {
        if (!valid(followPosition) || returnDurationMs < 1L) return followPosition;
        double t = Math.max(0D, Math.min(1D, elapsedReturnMs / (double) returnDurationMs));
        double eased = t * t * (3D - 2D * t);
        return new double[] {to[0] + (followPosition[0] - to[0]) * eased,
                to[1] + (followPosition[1] - to[1]) * eased,
                to[2] + (followPosition[2] - to[2]) * eased};
    }

    /** Blends both position and facing back into Lunar's native follow frame. */
    public double[] returnToFrame(double[] followFrame, long elapsedReturnMs, long returnDurationMs) {
        double[] point = returnTo(followFrame, elapsedReturnMs, returnDurationMs);
        if (point == followFrame || point == null || followFrame.length < 4) return point;
        double t = Math.max(0D, Math.min(1D, elapsedReturnMs / (double) returnDurationMs));
        double eased = smoothStep(t);
        return new double[] {point[0], point[1], point[2],
                interpolateDegrees(headingFrameYaw, followFrame[3], eased)};
    }

    public double headingFrameYaw() { return headingFrameYaw; }

    public double distance() {
        double x = to[0] - from[0], y = to[1] - from[1], z = to[2] - from[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    public long durationMs() { return durationMs; }
    public double[] destination() { return to.clone(); }

    private static boolean valid(double[] p) {
        return p != null && p.length >= 3 && Double.isFinite(p[0]) && Double.isFinite(p[1]) && Double.isFinite(p[2]);
    }

    private static double smoothStep(double value) {
        double t = Math.max(0D, Math.min(1D, value));
        return t * t * (3D - 2D * t);
    }

    private static double interpolateDegrees(double from, double to, double amount) {
        double difference = normalizeDegrees(to - from);
        if (difference > 180D) difference -= 360D;
        return normalizeDegrees(from + difference * amount);
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360D;
        return normalized < 0D ? normalized + 360D : normalized;
    }
}
