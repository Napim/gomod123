package me.napim.gomod.features;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats Teams Nick Finder timestamps for list rows (last found / added) from {@link ConfigManager} prefs.
 */
public final class TeamNickFinderDateFormat {

    private static final DateTimeFormatter SAMPLE_TIME_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SAMPLE_TIME_12 = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    private TeamNickFinderDateFormat() {}

    /**
     * Resolves the configured zone for absolute timestamps.
     * <p>{@code SYSTEM} uses {@link ZoneId#systemDefault()} — the timezone the JVM got from the operating
     * system (same as other desktop apps). It does not scan files or read private data beyond that.
     */
    public static ZoneId resolveZone() {
        return zoneIdFromStoredString(ConfigManager.getTeamNickFinderZoneId());
    }

    /**
     * Parses stored {@link ConfigManager#getTeamNickFinderZoneId()} values: {@code SYSTEM}, {@code UTC},
     * {@code UTC+1}…{@code UTC+12}, or any valid IANA {@link ZoneId} id.
     */
    public static ZoneId zoneIdFromStoredString(String id) {
        if (id == null) {
            return ZoneOffset.UTC;
        }
        if ("SYSTEM".equalsIgnoreCase(id)) {
            return ZoneId.systemDefault();
        }
        if ("UTC".equalsIgnoreCase(id)) {
            return ZoneOffset.UTC;
        }
        if (id.length() >= 5 && id.regionMatches(true, 0, "UTC+", 0, 4)) {
            try {
                int h = Integer.parseInt(id.substring(4).trim());
                if (h >= 1 && h <= 12) {
                    return ZoneOffset.ofHours(h);
                }
            } catch (NumberFormatException ignored) {
            }
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * Short label for the date-settings button, including a sample current time in that zone, e.g.
     * {@code UTC (08:57)} or {@code UTC +3 (11:57)} in 24-hour mode, or {@code 08:57 AM}-style in 12-hour mode.
     */
    public static String formatTimezoneChoiceLabel(String storedId) {
        if (storedId == null) {
            return "UTC (" + sampleTime(ZoneOffset.UTC) + ")";
        }
        if ("SYSTEM".equalsIgnoreCase(storedId)) {
            ZoneId z = ZoneId.systemDefault();
            return "PC local (" + sampleTime(z) + ")";
        }
        if ("UTC".equalsIgnoreCase(storedId)) {
            return "UTC (" + sampleTime(ZoneOffset.UTC) + ")";
        }
        if (storedId.length() >= 5 && storedId.regionMatches(true, 0, "UTC+", 0, 4)) {
            try {
                int h = Integer.parseInt(storedId.substring(4).trim());
                if (h >= 1 && h <= 12) {
                    ZoneOffset off = ZoneOffset.ofHours(h);
                    return "UTC +" + h + " (" + sampleTime(off) + ")";
                }
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            ZoneId z = ZoneId.of(storedId);
            String id = z.getId();
            return id + " (" + sampleTime(z) + ")";
        } catch (DateTimeException e) {
            return "UTC (" + sampleTime(ZoneOffset.UTC) + ")";
        }
    }

    private static String sampleTime(ZoneId zone) {
        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.now(), zone);
        return ConfigManager.isTeamNickFinder24HourClock()
                ? SAMPLE_TIME_24.format(zdt)
                : SAMPLE_TIME_12.format(zdt);
    }

    /** Formats epoch millis for UI (relative or absolute per config). */
    public static String formatListTimestamp(long epochMs) {
        if (ConfigManager.isTeamNickFinderLastFoundRelative()) {
            return formatRelative(epochMs);
        }
        ZoneId z = resolveZone();
        ZonedDateTime dt = Instant.ofEpochMilli(epochMs).atZone(z);
        String pattern = ConfigManager.isTeamNickFinder24HourClock()
                ? "MM/dd/yyyy HH:mm"
                : "MM/dd/yyyy hh:mm a";
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(dt);
    }

    private static String formatRelative(long epochMs) {
        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - epochMs);
        long sec = diff / 1000L;
        if (sec < 45L) {
            return "just now";
        }
        long min = sec / 60L;
        if (min < 60L) {
            return min + "m ago";
        }
        long hr = min / 60L;
        long minRem = min % 60L;
        if (hr < 24L) {
            if (minRem == 0L) {
                return hr + "h ago";
            }
            return hr + "h, " + minRem + "m ago";
        }
        long d = hr / 24L;
        long hrRem = hr % 24L;
        if (d < 365L) {
            if (hrRem == 0L) {
                return d + "d ago";
            }
            return d + "d, " + hrRem + "h ago";
        }
        long y = d / 365L;
        long dRem = d % 365L;
        if (dRem == 0L) {
            return y + "y ago";
        }
        return y + "y, " + dRem + "d ago";
    }
}
