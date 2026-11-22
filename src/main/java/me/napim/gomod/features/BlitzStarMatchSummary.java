package me.napim.gomod.features;

/**
 * Match-scoped Blitz Star finder/user summary for the extended end-game results card.
 */
public final class BlitzStarMatchSummary {
    public static final class Snapshot {
        public final String finder;
        public final String user;
        public final String star;

        private Snapshot(String finder, String user, String star) {
            this.finder = finder == null ? "" : finder;
            this.user = user == null ? "" : user;
            this.star = star == null ? "" : star;
        }
    }

    private static String finderIgn = "";
    private static String userIgn = "";
    private static String starDisplayName = "";

    private BlitzStarMatchSummary() {
    }

    public static synchronized void clear() {
        finderIgn = "";
        userIgn = "";
        starDisplayName = "";
    }

    public static synchronized void registerFinder(String ign) {
        if (ign == null || ign.trim().isEmpty()) {
            return;
        }
        finderIgn = ign.trim();
    }

    public static synchronized void registerUse(String ign, String star) {
        if (ign != null && !ign.trim().isEmpty()) {
            userIgn = ign.trim();
        }
        if (star != null && !star.trim().isEmpty()) {
            starDisplayName = star.trim();
        }
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(finderIgn, userIgn, starDisplayName);
    }
}
