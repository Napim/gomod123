package me.napim.gomod.features;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text recognition for the player names shown by Blitz's Roulette star. */
public final class BlitzRouletteText {
    private static final Pattern[] PLAYER_NAME_PATTERNS = {
            Pattern.compile("\\bROLLING\\s+FOR\\s+([A-Za-z0-9_]{3,16})\\s*['’]S\\s+ROULETTE\\s+BLITZ\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDIED\\s+FROM\\s+([A-Za-z0-9_]{3,16})\\s*['’]S\\s+ROULETTE\\s+BLITZ\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*PICKING\\s+A\\s+RANDOM\\s+FUTURE\\s+DEAD-PLAYER\\s+([A-Za-z0-9_]{3,16})\\s+BLITZ!\\s+ROULETTE\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*([A-Za-z0-9_]{3,16})\\s+BLITZ!\\s+ROULETTE\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*([A-Za-z0-9_]{3,16})\\s+is\\s+the\\s+(?:\\(un\\))?lucky\\s+winner!\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bThe\\s+fool\\s+([A-Za-z0-9_]{3,16})\\s+killed\\s+himself!(?:\\s|$)", Pattern.CASE_INSENSITIVE)
    };

    private BlitzRouletteText() { }

    /** True only for Roulette use, roll, winner, and self-kill lines. */
    public static boolean isRouletteNameMessage(String formattedText) {
        if (!couldBeRouletteText(formattedText)) return false;
        String text = normalizedVisibleText(formattedText);
        if (text.isEmpty()) return false;

        boolean roulette = text.contains("roulette");
        boolean blitz = text.contains("blitz");
        if (roulette && blitz && (text.contains("rolling for") || text.contains("died from")
                || text.contains("blitz! roulette")
                || text.contains("picking a random future dead-player"))) {
            return true;
        }
        if (text.contains(" is the (un)lucky winner") || text.contains(" is the unlucky winner")
                || text.contains(" is the lucky winner")) {
            return true;
        }
        return text.contains("the fool ") && text.contains(" killed himself");
    }

    /** Roulette's subtitle for the first and final title phases. */
    public static boolean isRouletteRollOrDeathSubtitle(String formattedText) {
        if (!couldBeRouletteText(formattedText)) return false;
        String text = normalizedVisibleText(formattedText);
        return text.contains("roulette") && text.contains("blitz")
                && (text.contains("rolling for") || text.contains("died from"));
    }

    /** The middle Roulette subtitle has no star name, so the HUD caller pairs it with the previous phase. */
    public static boolean isSelectedPlayerWillDieSubtitle(String formattedText) {
        if (formattedText == null || formattedText.isEmpty()) return false;
        return normalizedVisibleText(formattedText).contains("selected player will die");
    }

    /** True when a formatted title is exactly this IGN, with optional Minecraft color/style codes. */
    public static boolean isStandaloneUsernameLabel(String formattedText, String username) {
        if (formattedText == null || username == null || username.isEmpty()) return false;
        return normalizedVisibleText(formattedText).trim().equals(username.toLowerCase(Locale.ROOT));
    }

    /** Find an IGN at username boundaries, including a possessive suffix such as {@code nAku's}. */
    public static boolean containsUsername(String formattedText, String username) {
        if (formattedText == null || username == null || username.isEmpty()) return false;
        String text = normalizedVisibleText(formattedText);
        String needle = username.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= text.length() - needle.length()) {
            int at = text.indexOf(needle, from);
            if (at < 0) return false;
            int end = at + needle.length();
            if ((at == 0 || !isUsernameCharacter(text.charAt(at - 1)))
                    && (end == text.length() || !isUsernameCharacter(text.charAt(end)))) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    /** Return the visible-text ranges occupied by the Roulette player's IGN, never other words in the sentence. */
    public static List<int[]> findPlayerNameRanges(String visibleText) {
        List<int[]> ranges = new ArrayList<>();
        if (visibleText == null || visibleText.isEmpty() || visibleText.length() > 512
                || !isRouletteNameMessage(visibleText)) return ranges;
        for (Pattern pattern : PLAYER_NAME_PATTERNS) {
            Matcher matcher = pattern.matcher(visibleText);
            while (matcher.find()) {
                ranges.add(new int[]{matcher.start(1), matcher.end(1)});
            }
        }
        return ranges;
    }

    private static boolean couldBeRouletteText(String text) {
        if (text == null || text.isEmpty() || text.length() > 512) return false;
        // Avoid building normalized strings for the many unrelated FontRenderer calls during a frame.
        return containsIgnoreCase(text, "roulette") || containsIgnoreCase(text, "lucky winner")
                || containsIgnoreCase(text, "killed himself") || containsIgnoreCase(text, "selected player will die");
    }

    private static String normalizedVisibleText(String formattedText) {
        if (formattedText == null || formattedText.isEmpty()) return "";
        StringBuilder plain = new StringBuilder(formattedText.length());
        for (int i = 0; i < formattedText.length(); i++) {
            char c = formattedText.charAt(i);
            if (c == '\u00a7' && i + 1 < formattedText.length()) {
                i++;
                continue;
            }
            plain.append(c == '\u2019' || c == '\u2018' ? '\'' : c);
        }
        return plain.toString().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty() || needle.length() > text.length()) return false;
        for (int at = 0; at <= text.length() - needle.length(); at++) {
            if (text.regionMatches(true, at, needle, 0, needle.length())) return true;
        }
        return false;
    }

    private static boolean isUsernameCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9' || c == '_';
    }
}
