package me.napim.gomod.features;

import net.minecraft.scoreboard.Scoreboard;

/**
 * Compatibility shell retained for old callers.
 *
 * <p>gomod must never edit Hypixel's real player-team memberships. Lunar consumes those memberships for
 * nametag colours and TeamView, so removing even one locally causes incorrect results until a server packet
 * repairs the scoreboard.</p>
 */
public final class LunarTeamViewScoreboardFix {
    private LunarTeamViewScoreboardFix() {
    }

    public static void clientTick() {
        // Intentionally empty.
    }

    public static int removeProblematicTeamMemberships(Scoreboard scoreboard) {
        return 0;
    }

    public static boolean isProblematicTeamName(String teamName) {
        return false;
    }
}
