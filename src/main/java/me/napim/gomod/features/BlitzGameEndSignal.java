package me.napim.gomod.features;

/** Terminal server signals only; a deathmatch "Game End: MM:SS" countdown remains live. */
public final class BlitzGameEndSignal {
    private BlitzGameEndSignal() {}
    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("(?i)\u00a7[0-9a-fk-or]", "").trim().replaceAll("\\s+", " ");
    }
    public static boolean chat(String text) {
        if (BlitzGameChatLine.parse(text) != null) return false;
        String clean = plain(text);
        return clean.matches("(?i)^winners?\\s*-\\s*\\S.*$")
                || clean.matches("(?i)^there (?:is|are) [01] players? remaining!$")
                || clean.matches("(?i)^(?:the )?game (?:is|ended in|has ended in) a draw[!.]?$")
                || title(clean);
    }
    public static boolean title(String text) {
        // Bare Draw/Victory/GameOver can be Roulette player names; require a banner, not an IGN.
        return plain(text).matches("(?i)^(?:VICTORY!|YOU WIN!|DRAW!|GAME OVER!?)$");
    }
    public static boolean sidebar(String text) {
        String clean = plain(text).replaceAll("[^\\x20-\\x7e]", "").trim();
        return clean.matches("(?i)^GAME\\s*OVER[!.]?$") || clean.matches("(?i)^players(?: left)?\\s*:\\s*[01]$");
    }
}
