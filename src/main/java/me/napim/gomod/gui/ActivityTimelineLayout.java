package me.napim.gomod.gui;

import me.napim.gomod.features.GoStatsBlitzClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure geometry and lane-packing layout for the graphical Activity timeline strip.
 * Maps each kit's observed check interval [older, latest] onto a horizontal time track
 * and staggers overlapping kit icon chips across lanes so icons remain clickable.
 */
final class ActivityTimelineLayout {
    static final int CHIP_SIZE = 14;
    static final int LANE_COUNT = 2;
    private static final int MIN_SPAN_PX = 4;
    private static final int MIN_CHIP_GAP_PX = 2;

    static final class Entry {
        final GoStatsBlitzClient.Kit kit;
        final long olderMs;
        final long latestMs;
        final int spanLeft;
        final int spanRight;
        final int chipX;
        final int lane;
        final boolean clippedLeft;

        Entry(GoStatsBlitzClient.Kit kit, long olderMs, long latestMs,
              int spanLeft, int spanRight, int chipX, int lane, boolean clippedLeft) {
            this.kit = kit;
            this.olderMs = olderMs;
            this.latestMs = latestMs;
            this.spanLeft = spanLeft;
            this.spanRight = spanRight;
            this.chipX = chipX;
            this.lane = lane;
            this.clippedLeft = clippedLeft;
        }
    }

    static final class Result {
        final List<Entry> entries;
        final int olderOutsideCount;
        final long windowStartMs;
        final long nowMs;

        Result(List<Entry> entries, int olderOutsideCount, long windowStartMs, long nowMs) {
            this.entries = Collections.unmodifiableList(entries);
            this.olderOutsideCount = olderOutsideCount;
            this.windowStartMs = windowStartMs;
            this.nowMs = nowMs;
        }
    }

    private ActivityTimelineLayout() {
    }

    static Result build(List<GoStatsBlitzClient.Kit> kits, long nowMs, int windowDays,
                        int trackLeft, int trackWidth) {
        long durationMs = Math.max(1L, (long) windowDays) * 24L * 60L * 60L * 1000L;
        long windowStartMs = nowMs - durationMs;
        if (kits == null || kits.isEmpty() || trackWidth <= CHIP_SIZE) {
            return new Result(Collections.<Entry>emptyList(), 0, windowStartMs, nowMs);
        }

        List<Candidate> visible = new ArrayList<Candidate>();
        int olderOutside = 0;
        for (GoStatsBlitzClient.Kit kit : kits) {
            if (kit == null || kit.lastActivityAt <= 0L) continue;
            long latestMs = parseMillis(kit.activityBefore, kit.lastActivityAt);
            long olderMs = parseMillis(kit.activityAfter, latestMs);
            if (olderMs > latestMs) olderMs = latestMs;
            if (latestMs < windowStartMs) {
                olderOutside++;
                continue;
            }
            visible.add(new Candidate(kit, olderMs, latestMs));
        }

        // Lay out left-to-right by latest timestamp so lane packing stays deterministic.
        Collections.sort(visible, Comparator.comparingLong((Candidate c) -> c.latestMs)
                .thenComparingLong(c -> c.olderMs));

        int trackRight = trackLeft + trackWidth;
        int[] laneLastRight = new int[LANE_COUNT];
        for (int i = 0; i < LANE_COUNT; i++) laneLastRight[i] = Integer.MIN_VALUE;

        List<Entry> entries = new ArrayList<Entry>(visible.size());
        for (Candidate c : visible) {
            boolean clippedLeft = c.olderMs < windowStartMs;
            long clampedOlder = Math.max(windowStartMs, Math.min(nowMs, c.olderMs));
            long clampedLatest = Math.max(windowStartMs, Math.min(nowMs, c.latestMs));

            int leftPx = mapToX(clampedOlder, windowStartMs, durationMs, trackLeft, trackWidth);
            int rightPx = mapToX(clampedLatest, windowStartMs, durationMs, trackLeft, trackWidth);
            if (rightPx - leftPx < MIN_SPAN_PX) {
                int center = (leftPx + rightPx) / 2;
                leftPx = Math.max(trackLeft, center - MIN_SPAN_PX / 2);
                rightPx = Math.min(trackRight, leftPx + MIN_SPAN_PX);
                leftPx = Math.max(trackLeft, rightPx - MIN_SPAN_PX);
            }

            int idealChipX = ((leftPx + rightPx) / 2) - CHIP_SIZE / 2;
            idealChipX = Math.max(trackLeft + 1, Math.min(trackRight - CHIP_SIZE - 1, idealChipX));

            int chosenLane = 0;
            int bestOverlap = Integer.MAX_VALUE;
            for (int lane = 0; lane < LANE_COUNT; lane++) {
                int neededStart = laneLastRight[lane] == Integer.MIN_VALUE
                        ? Integer.MIN_VALUE : laneLastRight[lane] + MIN_CHIP_GAP_PX;
                if (idealChipX >= neededStart) {
                    chosenLane = lane;
                    bestOverlap = 0;
                    break;
                }
                int overlap = neededStart - idealChipX;
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    chosenLane = lane;
                }
            }

            int chipX = idealChipX;
            if (bestOverlap > 0 && laneLastRight[chosenLane] != Integer.MIN_VALUE) {
                int nudged = laneLastRight[chosenLane] + MIN_CHIP_GAP_PX;
                if (nudged + CHIP_SIZE <= trackRight - 1) {
                    chipX = nudged;
                }
            }
            laneLastRight[chosenLane] = Math.max(laneLastRight[chosenLane], chipX + CHIP_SIZE);

            entries.add(new Entry(c.kit, c.olderMs, c.latestMs, leftPx, rightPx, chipX, chosenLane, clippedLeft));
        }

        return new Result(entries, olderOutside, windowStartMs, nowMs);
    }

    static Entry hitTest(Result layout, int mouseX, int mouseY, int laneBaseY, int lanePitch) {
        if (layout == null || layout.entries.isEmpty()) return null;
        // Prefer direct hit on a kit icon chip first (checking top-drawn / newest entries first).
        for (int i = layout.entries.size() - 1; i >= 0; i--) {
            Entry e = layout.entries.get(i);
            int chipY = laneBaseY + e.lane * lanePitch;
            if (mouseX >= e.chipX - 1 && mouseX <= e.chipX + CHIP_SIZE + 1
                    && mouseY >= chipY - 1 && mouseY <= chipY + CHIP_SIZE + 1) {
                return e;
            }
        }
        // Fall back to hitting the kit's bracket bar in its lane.
        for (int i = layout.entries.size() - 1; i >= 0; i--) {
            Entry e = layout.entries.get(i);
            int chipY = laneBaseY + e.lane * lanePitch;
            if (mouseX >= e.spanLeft - 1 && mouseX <= e.spanRight + 1
                    && mouseY >= chipY && mouseY <= chipY + CHIP_SIZE) {
                return e;
            }
        }
        return null;
    }

    private static int mapToX(long timestampMs, long windowStartMs, long durationMs,
                              int trackLeft, int trackWidth) {
        double ratio = (double) (timestampMs - windowStartMs) / (double) Math.max(1L, durationMs);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return trackLeft + (int) Math.round(ratio * trackWidth);
    }

    private static long parseMillis(String iso, long fallbackMs) {
        if (iso == null || iso.trim().isEmpty()) return fallbackMs;
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try {
                return Instant.parse(iso.trim()).toEpochMilli();
            } catch (Exception ignoredAgain) {
                return fallbackMs;
            }
        }
    }

    private static final class Candidate {
        final GoStatsBlitzClient.Kit kit;
        final long olderMs;
        final long latestMs;

        Candidate(GoStatsBlitzClient.Kit kit, long olderMs, long latestMs) {
            this.kit = kit;
            this.olderMs = olderMs;
            this.latestMs = latestMs;
        }
    }
}
