package me.napim.gomod.features;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatting helpers for disguised Hypixel leaderboard hologram rows. */
public final class LeaderboardHologramFormatting {
    private static final Pattern ENTRY_PATTERN = Pattern.compile(
            "^\\s*(\\d[\\d,]*)\\.\\s+(.+?)\\s+-\\s+([\\d,]+)\\s*$");

    private LeaderboardHologramFormatting() {
    }

    /**
     * Restores the source row's formatting at the leaderboard separator after the name/guild replacement.
     * The replacement can consume the original color code before " - ", which otherwise lets the target
     * guild-tag color bleed onto the separator.
     */
    public static String restoreSeparatorFormatting(String source, String rewritten) {
        if (source == null || rewritten == null || source.equals(rewritten)) {
            return rewritten;
        }

        VisibleText sourceVisible = toVisibleText(source);
        VisibleText rewrittenVisible = toVisibleText(rewritten);
        Matcher sourceMatch = ENTRY_PATTERN.matcher(sourceVisible.text);
        Matcher rewrittenMatch = ENTRY_PATTERN.matcher(rewrittenVisible.text);
        if (!sourceMatch.matches() || !rewrittenMatch.matches()) {
            return rewritten;
        }

        int sourceSeparator = sourceMatch.end(2);
        int rewrittenSeparator = rewrittenMatch.end(2);
        if (!isVisibleOffset(sourceVisible, sourceSeparator)
                || !isVisibleOffset(rewrittenVisible, rewrittenSeparator)) {
            return rewritten;
        }

        int sourceFormattedOffset = sourceVisible.formattedStarts[sourceSeparator];
        int rewrittenFormattedOffset = rewrittenVisible.formattedStarts[rewrittenSeparator];
        String sourceFormatting = activeFormattingBefore(source, sourceFormattedOffset);
        String rewrittenFormatting = activeFormattingBefore(rewritten, rewrittenFormattedOffset);
        if (sourceFormatting.equalsIgnoreCase(rewrittenFormatting)) {
            return rewritten;
        }

        String restoreFormatting = beginsWithColorCode(sourceFormatting)
                ? sourceFormatting : "\u00A7r" + sourceFormatting;
        return rewritten.substring(0, rewrittenFormattedOffset)
                + restoreFormatting + rewritten.substring(rewrittenFormattedOffset);
    }

    private static boolean beginsWithColorCode(String formatting) {
        return formatting != null && formatting.length() >= 2 && formatting.charAt(0) == '\u00A7'
                && isColorCode(Character.toLowerCase(formatting.charAt(1)));
    }

    /** Replaces only the visible player segment of a ranked leaderboard row. */
    public static String replacePlayerSegment(String formatted, String replacement) {
        if (formatted == null || formatted.isEmpty() || replacement == null || replacement.isEmpty()) {
            return formatted;
        }
        VisibleText visible = toVisibleText(formatted);
        Matcher matcher = ENTRY_PATTERN.matcher(visible.text);
        if (!matcher.matches()) {
            return formatted;
        }
        int visibleStart = matcher.start(2);
        int visibleEnd = matcher.end(2);
        if (visibleStart < 0 || visibleEnd <= visibleStart
                || visibleEnd > visible.formattedStarts.length) {
            return formatted;
        }
        int formattedStart = visible.formattedStarts[visibleStart];
        int formattedEnd = visible.formattedStarts[visibleEnd - 1] + 1;
        String rewritten = formatted.substring(0, formattedStart) + replacement + formatted.substring(formattedEnd);
        return restoreSeparatorFormatting(formatted, rewritten);
    }

    private static boolean isVisibleOffset(VisibleText visible, int offset) {
        return visible != null && offset >= 0 && offset < visible.formattedStarts.length;
    }

    private static VisibleText toVisibleText(String formatted) {
        StringBuilder visible = new StringBuilder(formatted.length());
        int[] starts = new int[formatted.length()];
        int count = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00A7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            starts[count++] = i;
            visible.append(c);
        }
        int[] trimmedStarts = new int[count];
        System.arraycopy(starts, 0, trimmedStarts, 0, count);
        return new VisibleText(visible.toString(), trimmedStarts);
    }

    private static String activeFormattingBefore(String formatted, int end) {
        String color = "";
        StringBuilder styles = new StringBuilder();
        for (int i = 0; i < end && i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) != '\u00A7') {
                continue;
            }
            char code = Character.toLowerCase(formatted.charAt(i + 1));
            if (code == 'r') {
                color = "";
                styles.setLength(0);
            } else if (isColorCode(code)) {
                color = "\u00A7" + formatted.charAt(i + 1);
                styles.setLength(0);
            } else if (isStyleCode(code)) {
                String token = "\u00A7" + formatted.charAt(i + 1);
                if (styles.indexOf(token) < 0) {
                    styles.append(token);
                }
            }
            i++;
        }
        return color + styles;
    }

    private static boolean isColorCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
    }

    private static boolean isStyleCode(char code) {
        return code >= 'k' && code <= 'o';
    }

    private static final class VisibleText {
        private final String text;
        private final int[] formattedStarts;

        private VisibleText(String text, int[] formattedStarts) {
            this.text = text;
            this.formattedStarts = formattedStarts;
        }
    }
}
