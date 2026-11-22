package me.napim.gomod.features;

/** Allocation-free rejection before the spectator HUD fallback enters regex and scoreboard checks. */
final class SpectatorHudTextFilter {
    private SpectatorHudTextFilter() { }

    static boolean mayBeTargetText(String text) {
        return text != null && !text.isEmpty()
                && (hasVisiblePrefix(text, "Spectating") || hasVisiblePrefix(text, "Target:")
                || isBoldStandalonePlayerName(text));
    }

    static boolean hasVisiblePrefix(String text, String prefix) {
        int matched = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) { i++; continue; }
            if (matched == 0 && Character.isWhitespace(c)) continue;
            if (Character.toLowerCase(c) != Character.toLowerCase(prefix.charAt(matched))) return false;
            if (++matched == prefix.length()) return true;
        }
        return false;
    }

    static boolean isBoldStandalonePlayerName(String text) {
        if (!startsWithBoldText(text)) return false;
        int letters = 0;
        boolean trailingWhitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) { i++; continue; }
            if (c <= ' ') {
                if (letters != 0) trailingWhitespace = true;
                continue;
            }
            if (trailingWhitespace || ++letters > 16 || !(c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return letters != 0;
    }

    private static boolean startsWithBoldText(String text) {
        if (text == null) return false;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') bold = false;
                else if (code == 'l') bold = true;
            } else if (!Character.isWhitespace(c)) return bold;
        }
        return false;
    }
}
