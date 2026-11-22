package me.napim.gomod.features;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command duration parser. Absolute deadlines are never renewed by a relay poll. */
public final class DisguiseDuration {
    public static final long MAX_MS = 365L * 24 * 60 * 60 * 1000;
    private static final Pattern PART = Pattern.compile("([0-9]+)([smhdw])");
    private DisguiseDuration() { }

    public static long parse(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        Matcher parts = PART.matcher(value);
        long total = 0;
        int end = 0;
        int seenUnits = 0;
        try {
            while (parts.find()) {
                if (parts.start() != end) throw new IllegalArgumentException();
                int unitIndex = "smhdw".indexOf(parts.group(2));
                if ((seenUnits & (1 << unitIndex)) != 0) throw new IllegalArgumentException();
                seenUnits |= 1 << unitIndex;
                long unit = parts.group(2).equals("s") ? 1000L : parts.group(2).equals("m") ? 60000L
                        : parts.group(2).equals("h") ? 3600000L : parts.group(2).equals("d") ? 86400000L : 604800000L;
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(parts.group(1)), unit));
                end = parts.end();
            }
            if (end != value.length() || total < 1000L || total > MAX_MS) throw new IllegalArgumentException();
            return total;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Use a duration such as 30m, 1h, 3d or 1h30m (1 second to 365 days).");
        }
    }
}
