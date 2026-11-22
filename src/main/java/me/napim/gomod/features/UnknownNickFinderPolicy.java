package me.napim.gomod.features;

/** Pure search guards for Unknown Nick Finder. */
final class UnknownNickFinderPolicy {
    private UnknownNickFinderPolicy() {
    }

    /**
     * True when a guess could resolve to the local account by exact name or prefix.
     * Minecraft usernames are case-insensitive for command matching.
     */
    static boolean isOwnIgnPrefix(String guess, String ownIgn) {
        if (guess == null || guess.isEmpty() || ownIgn == null || ownIgn.isEmpty()) {
            return false;
        }
        return guess.length() <= ownIgn.length()
                && ownIgn.regionMatches(true, 0, guess, 0, guess.length());
    }
}
