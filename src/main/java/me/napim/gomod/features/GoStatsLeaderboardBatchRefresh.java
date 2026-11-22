package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Refreshes a bounded leaderboard selection using the same bounded worker pattern as Teams Nick Finder. */
public final class GoStatsLeaderboardBatchRefresh {
    public static final int TOP_PLAYER_COUNT = 350;
    public static final int MAX_PLAYER_COUNT = 950;
    /** Preserve worker concurrency; actual API requests share Hypixel's 300-per-five-minute key quota. */
    private static final int WORKER_COUNT = 12;
    private static final AtomicLong NEXT_JOB_ID = new AtomicLong();
    private static volatile Job activeJob;

    private GoStatsLeaderboardBatchRefresh() {
    }

    public static int getWorkerCount() { return WORKER_COUNT; }

    public static boolean isRunning() {
        Job job = activeJob;
        return job != null && !job.isFinished();
    }

    /** Returns null when another leaderboard batch refresh is already active. */
    public static synchronized Job start(final GoStatsLeaderboardBatchRefreshListener listener,
                                         final String kit, final String metric) {
        return start(listener, "kit", kit, metric, TOP_PLAYER_COUNT, null, null, 0);
    }

    public static synchronized Job start(final GoStatsLeaderboardBatchRefreshListener listener,
                                         final String kit, final String metric, final int requestedCount,
                                         final GoStatsLeaderboardClient.KitFilters filters) {
        return start(listener, "kit", kit, metric, requestedCount, filters, null, 0);
    }

    public static synchronized Job start(final GoStatsLeaderboardBatchRefreshListener listener,
                                         final String kit, final String metric, final int requestedCount,
                                         final GoStatsLeaderboardClient.KitFilters filters,
                                         final List<GoStatsLeaderboardClient.Player> loadedTopPlayers,
                                         final int loadedTotal) {
        return start(listener, "kit", kit, metric, requestedCount, filters,
                loadedTopPlayers, loadedTotal);
    }

    public static synchronized Job start(final GoStatsLeaderboardBatchRefreshListener listener,
                                         final String scope, final String kit, final String metric,
                                         final int requestedCount,
                                         final GoStatsLeaderboardClient.KitFilters filters,
                                         final List<GoStatsLeaderboardClient.Player> loadedTopPlayers,
                                         final int loadedTotal) {
        if (listener == null || scope == null || metric == null || metric.trim().isEmpty()) return null;
        final String normalizedScope = scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"overall".equals(normalizedScope) && !"solo".equals(normalizedScope)
                && !"teams".equals(normalizedScope) && !"kit".equals(normalizedScope)) return null;
        final String selectedKit = kit == null ? "" : kit.trim();
        if ("kit".equals(normalizedScope) && selectedKit.isEmpty()) return null;
        final GoStatsLeaderboardClient.KitFilters selectedFilters =
                filters != null && filters.isActive() ? filters : null;
        if (activeJob != null && !activeJob.isFinished()) return null;
        final int targetCount = Math.max(1, Math.min(MAX_PLAYER_COUNT, requestedCount));

        final Job job = new Job(NEXT_JOB_ID.incrementAndGet());
        activeJob = job;
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        LeaderboardRefreshDebugCapture.start(job.id, normalizedScope, selectedKit, metric, targetCount,
                loadedTopPlayers != null, selectedFilters != null, apiKey != null && !apiKey.trim().isEmpty());
        LeaderboardRefreshDebugCapture.record(job.id, "job.start", "loadedRows="
                + (loadedTopPlayers == null ? 0 : loadedTopPlayers.size()) + " loadedTotal=" + loadedTotal
                + " filterActive=" + (selectedFilters != null));
        GoStatsContributions.recordLeaderboardUploadState(job.id);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            LeaderboardRefreshDebugCapture.record(job.id, "job.blocked", "reason=missing-hypixel-api-key");
            dispatch(() -> listener.onTop350LoadFailed(job.id,
                    "Set a Hypixel API key first."));
            finishEmptyJob(job, listener, false, 0, "missing-hypixel-api-key");
            return job;
        }
        if (loadedTopPlayers != null && loadedTotal > 0
                && loadedTopPlayers.size() >= Math.min(targetCount, loadedTotal)) {
            final List<GoStatsLeaderboardClient.Player> targets = uniquePlayers(loadedTopPlayers, targetCount);
            LeaderboardRefreshDebugCapture.record(job.id, "targets.loaded", "source=loaded-gui-rows rows="
                    + loadedTopPlayers.size() + " uniqueTargets=" + targets.size() + " leaderboardTotal=" + loadedTotal);
            LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, targets.size(), false);
            Thread launcher = new Thread(() -> {
                dispatch(() -> listener.onTop350PlayersLoaded(job.id, targets, loadedTotal));
                startWorkers(job, listener, targets, normalizedScope, selectedKit, metric);
            }, "gomod-leaderboard-refresh-start");
            launcher.setDaemon(true);
            launcher.start();
            return job;
        }
        LeaderboardRefreshDebugCapture.record(job.id, "targets.load.start", "service=gostats123 endpoint=/blitz/data"
                + " requestedCount=" + targetCount + " maxPageSize=500 scope=" + normalizedScope
                + " metric=" + metric);
        final List<GoStatsLeaderboardClient.Player> initialRows = loadedTopPlayers != null && loadedTotal > 0
                ? loadedTopPlayers : null;
        final int initialOffset = initialRows == null ? 0 : initialRows.size();
        GoStatsLeaderboardClient.fetchTopPlayersForBatchRefresh(normalizedScope, metric, selectedKit, targetCount,
                selectedFilters, job.id, initialRows, initialOffset, loadedTotal,
                (page, error) -> {
                    if (job.isCancelled()) {
                        finishEmptyJob(job, listener, true, 0, "cancelled-during-target-load");
                        return;
                    }
                    if (error != null || page == null) {
                        final String message = error == null ? "gostats123 returned no leaderboard data" : error;
                        LeaderboardRefreshDebugCapture.record(job.id, "targets.load.failed", "reason=" + message);
                        dispatch(() -> listener.onTop350LoadFailed(job.id, message));
                        finishEmptyJob(job, listener, false, 0, "target-load-failed");
                        return;
                    }

                    final List<GoStatsLeaderboardClient.Player> targets = uniquePlayers(page.players, targetCount);
                    LeaderboardRefreshDebugCapture.record(job.id, "targets.loaded", "source=gostats123-api rows="
                            + page.players.size() + " uniqueTargets=" + targets.size() + " leaderboardTotal=" + page.total);
                    LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, targets.size(), false);
                    dispatch(() -> listener.onTop350PlayersLoaded(job.id, targets, page.total));
                    if (targets.isEmpty()) {
                        finishEmptyJob(job, listener, job.isCancelled(), 0, "no-targets-returned");
                        return;
                    }
                    startWorkers(job, listener, targets, normalizedScope, selectedKit, metric);
                });
        return job;
    }

    private static List<GoStatsLeaderboardClient.Player> uniquePlayers(
            List<GoStatsLeaderboardClient.Player> source, int limit) {
        List<GoStatsLeaderboardClient.Player> result = new ArrayList<GoStatsLeaderboardClient.Player>();
        Set<String> seen = new HashSet<String>();
        if (source == null) return result;
        for (GoStatsLeaderboardClient.Player player : source) {
            if (player == null || player.uuid == null || player.uuid.trim().isEmpty()) continue;
            String uuid = player.uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
            if (uuid.matches("[a-f0-9]{32}") && seen.add(uuid)) result.add(player);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static void startWorkers(final Job job,
                                     final GoStatsLeaderboardBatchRefreshListener listener,
                                     final List<GoStatsLeaderboardClient.Player> targets,
                                     final String scope, final String kit, final String metric) {
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger updated = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger workersRemaining = new AtomicInteger(WORKER_COUNT);
        LeaderboardRefreshDebugCapture.record(job.id, "workers.start", "workerCount=" + WORKER_COUNT
                + " targets=" + targets.size());
        LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, targets.size(), false);

        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            final int assignedWorkerIndex = workerIndex;
            Thread worker = new Thread(() -> {
                try {
                    while (!job.isCancelled()) {
                        int index = next.getAndIncrement();
                        if (index >= targets.size()) break;
                        final GoStatsLeaderboardClient.Player previous = targets.get(index);
                        final int startedCount = started.incrementAndGet();
                        LeaderboardRefreshDebugCapture.progress(job.id, startedCount, completed.get(),
                                updated.get(), skipped.get(), targets.size(), job.isCancelled());
                        dispatch(() -> listener.onTop350PlayerStarted(job.id, previous,
                                startedCount, targets.size()));

                        GoStatsLeaderboardClient.Player refreshed = null;
                        String error = null;
                        LeaderboardRefreshDebugCapture.PlayerScope debugScope =
                                LeaderboardRefreshDebugCapture.enterPlayer(job.id, index + 1, targets.size(),
                                        assignedWorkerIndex, previous.uuid, previous.username);
                        try {
                            long profileStart = System.nanoTime();
                            JsonObject freshProfile = GoStatsBlitzClient.fetchLeaderboardRefreshProfile(previous.uuid);
                            LeaderboardRefreshDebugCapture.recordCurrent("profile.ready", "elapsedMs="
                                    + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - profileStart));
                            long transformStart = System.nanoTime();
                            GoStatsPlayerPresence.Presence presence =
                                    GoStatsPlayerPresence.fromPlayerProfile(freshProfile);
                            refreshed = GoStatsLeaderboardClient.fromFreshHypixelProfile(previous,
                                    freshProfile, scope, metric, kit);
                            refreshed = GoStatsLeaderboardClient.withRankInfoFallback(refreshed, previous);
                            refreshed = GoStatsLeaderboardClient.withPresence(refreshed, presence.status,
                                    presence.lastAction, presence.lastLoginMs, presence.lastLogoutMs,
                                    presence.lastActionSeconds, presence.lastGameType);
                            LeaderboardRefreshDebugCapture.recordCurrent("profile.transform.complete", "elapsedMs="
                                    + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transformStart)
                                    + " metric=" + metric + " previousValue=" + previous.metricValue
                                    + " newValue=" + refreshed.metricValue);
                            long queueStart = System.nanoTime();
                            GoStatsContributions.queueLeaderboardRefresh(freshProfile, job.id);
                            LeaderboardRefreshDebugCapture.recordCurrent("contribution.queued", "elapsedMs="
                                    + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queueStart));
                        } catch (Exception failure) {
                            error = failure.getMessage();
                            if (error == null || error.trim().isEmpty()) error = "request failed";
                            LeaderboardRefreshDebugCapture.recordCurrent("player.exception", "type="
                                    + failure.getClass().getName() + " reason=" + error);
                        } finally {
                            debugScope.finish(error == null ? "success" : "failed",
                                    error == null ? "" : "reason=" + error);
                            debugScope.close();
                        }

                        final GoStatsLeaderboardClient.Player finalRefreshed = refreshed;
                        final String finalError = error;
                        final int completedCount = completed.incrementAndGet();
                        if (finalError == null) updated.incrementAndGet();
                        else skipped.incrementAndGet();
                        LeaderboardRefreshDebugCapture.progress(job.id, started.get(), completedCount,
                                updated.get(), skipped.get(), targets.size(), job.isCancelled());
                        dispatch(() -> listener.onTop350PlayerCompleted(job.id, previous,
                                finalRefreshed, finalError, completedCount, targets.size()));
                    }
                } catch (Throwable unexpected) {
                    LeaderboardRefreshDebugCapture.record(job.id, "worker.exception", "worker="
                            + assignedWorkerIndex + " type=" + unexpected.getClass().getName()
                            + " reason=" + unexpected.getMessage());
                    if (unexpected instanceof Error) throw (Error) unexpected;
                } finally {
                    LeaderboardRefreshDebugCapture.record(job.id, "worker.finish", "worker=" + assignedWorkerIndex
                            + " cancelled=" + job.isCancelled() + " assigned=" + started.get()
                            + " completed=" + completed.get());
                    if (workersRemaining.decrementAndGet() == 0) {
                        String finishReason = job.isCancelled() ? "cancelled"
                                : completed.get() < targets.size() ? "worker-pool-ended-before-all-targets" : "workers-finished";
                        LeaderboardRefreshDebugCapture.finish(job.id, finishReason,
                                started.get(), completed.get(), updated.get(), skipped.get(), targets.size(), job.isCancelled());
                        synchronized (GoStatsLeaderboardBatchRefresh.class) {
                            job.finish();
                            if (activeJob == job) activeJob = null;
                        }
                        dispatch(() -> listener.onTop350Finished(job.id, updated.get(), skipped.get(),
                                completed.get(), targets.size(), job.isCancelled()));
                    }
                }
            }, "gomod-gostats-top350-" + workerIndex);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void finishEmptyJob(Job job, GoStatsLeaderboardBatchRefreshListener listener,
                                       boolean cancelled, int total, String reason) {
        LeaderboardRefreshDebugCapture.finish(job.id, reason, 0, 0, 0, 0, total, cancelled);
        synchronized (GoStatsLeaderboardBatchRefresh.class) {
            job.finish();
            if (activeJob == job) activeJob = null;
        }
        dispatch(() -> listener.onTop350Finished(job.id, 0, 0, 0, total, cancelled));
    }

    private static void dispatch(Runnable callback) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null) minecraft.addScheduledTask(callback);
    }

    public static final class Job {
        private final long id;
        private volatile boolean cancelled;
        private volatile boolean finished;

        private Job(long id) {
            this.id = id;
        }

        public long getId() {
            return id;
        }

        public boolean cancel() {
            if (finished) return false;
            cancelled = true;
            LeaderboardRefreshDebugCapture.record(id, "job.cancel", "reason=user-request");
            return true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isFinished() {
            return finished;
        }

        private void finish() {
            finished = true;
        }
    }
}
