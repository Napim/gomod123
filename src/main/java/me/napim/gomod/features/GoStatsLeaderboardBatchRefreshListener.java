package me.napim.gomod.features;

import java.util.List;

/** UI callbacks for a paced refresh of a kit leaderboard's top players. */
public interface GoStatsLeaderboardBatchRefreshListener {
    void onTop350PlayersLoaded(long jobId, List<GoStatsLeaderboardClient.Player> players, int totalPlayers);

    /** Called when additional target pages finish loading while earlier players are already updating. */
    default void onTop350PlayersAppended(long jobId, List<GoStatsLeaderboardClient.Player> appendedPlayers,
                                         int totalTargets, int totalPlayers) {
    }

    void onTop350PlayerStarted(long jobId, GoStatsLeaderboardClient.Player player,
                               int started, int total);

    void onTop350PlayerCompleted(long jobId, GoStatsLeaderboardClient.Player previous,
                                 GoStatsLeaderboardClient.Player updated, String error,
                                 int completed, int total);

    void onTop350LoadFailed(long jobId, String error);

    void onTop350Finished(long jobId, int updated, int skipped, int completed,
                          int total, boolean cancelled);
}
