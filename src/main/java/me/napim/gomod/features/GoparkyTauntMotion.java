package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A sampled taunt timeline: randomness is chosen once, never once per render frame. */
public final class GoparkyTauntMotion {
    public static final long DURATION_MS = 5000L;
    private final double speed;
    private final double headPitch;
    private final double[] starts;

    public GoparkyTauntMotion(double speed, int minimumGapMs, int maximumGapMs, long seed) {
        if (!Double.isFinite(speed) || speed < 0.25D || speed > 4.0D
                || minimumGapMs < 0 || maximumGapMs > 2000 || maximumGapMs < minimumGapMs) {
            throw new IllegalArgumentException("Speed must be 0.25-4; gaps must be 0-2000ms, minimum <= maximum.");
        }
        this.speed = speed;
        Random random = new Random(seed);
        headPitch = -65.0D + random.nextDouble() * 115.0D;
        List<Double> cycles = new ArrayList<Double>();
        double start = 0.0D;
        while (start < DURATION_MS) {
            cycles.add(start);
            int gap = minimumGapMs + random.nextInt(maximumGapMs - minimumGapMs + 1);
            start += 300.0D / speed + gap;
        }
        starts = new double[cycles.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = cycles.get(i);
    }

    /** Smooth crouch and compass press amount [0,1], plus head pitch in degrees. */
    public double[] sample(long elapsedMs) {
        if (elapsedMs < 0L || elapsedMs >= DURATION_MS) return new double[3];
        double crouch = 0.0D;
        for (int i = starts.length - 1; i >= 0; i--) {
            if (elapsedMs < starts[i]) continue;
            double phase = (elapsedMs - starts[i]) * speed;
            if (phase < 60.0D) crouch = ease(phase / 60.0D);
            else if (phase < 200.0D) crouch = 1.0D;
            else if (phase < 300.0D) crouch = 1.0D - ease((phase - 200.0D) / 100.0D);
            break;
        }
        double envelope = Math.min(ease(elapsedMs / 120.0D), ease((DURATION_MS - elapsedMs) / 160.0D));
        double swing = compassClick(elapsedMs) * ease((DURATION_MS - elapsedMs) / 160.0D);
        return new double[] {crouch * envelope, headPitch * envelope, swing};
    }

    /** One deliberate down press and upward release for each configured taunt beat. */
    private double compassClick(long elapsedMs) {
        double amount = 0.0D;
        for (int i = starts.length - 1; i >= 0; i--) {
            double sinceStart = elapsedMs - starts[i];
            if (sinceStart < 0.0D) continue;
            if (sinceStart >= 170.0D) break;

            double pulse;
            if (sinceStart < 55.0D) {
                pulse = ease(sinceStart / 55.0D);
            } else if (sinceStart < 80.0D) {
                pulse = 1.0D;
            } else {
                pulse = 1.0D - ease((sinceStart - 80.0D) / 90.0D);
            }
            amount = Math.max(amount, pulse);
        }
        return amount;
    }

    private static double ease(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return t * t * (3.0D - 2.0D * t);
    }
}
