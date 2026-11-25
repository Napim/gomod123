package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refreshes a bounded leaderboard selection using bounded workers fed by a
 * streaming target queue so updates begin as soon as the first rows are ready
 * while remaining leaderboard pages continue loading in the background.
 */
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

        final int initialExpectedTotal = loadedTotal > 0 ? Math.min(targetCount, loadedTotal) : targetCount;
        final TargetQueue targetQueue = new TargetQueue(targetCount, initialExpectedTotal);
        job.attachCancelHook(targetQueue::wakeAll);

        final List<GoStatsLeaderboardClient.Player> initialRows = loadedTopPlayers != null && loadedTotal > 0
                ? loadedTopPlayers : null;
        final List<GoStatsLeaderboardClient.Player> preloadedTargets = initialRows == null
                ? Collections.<GoStatsLeaderboardClient.Player>emptyList()
                : targetQueue.enqueueBatch(initialRows, loadedTotal);

        if (initialRows != null && loadedTopPlayers.size() >= Math.min(targetCount, loadedTotal)) {
            targetQueue.markLoadingDone();
            LeaderboardRefreshDebugCapture.record(job.id, "targets.loaded", "source=loaded-gui-rows rows="
                    + loadedTopPlayers.size() + " uniqueTargets=" + preloadedTargets.size() + " leaderboardTotal=" + loadedTotal);
            LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, preloadedTargets.size(), false);
            if (preloadedTargets.isEmpty()) {
                dispatch(() -> listener.onTop350PlayersLoaded(job.id, preloadedTargets, loadedTotal));
                finishEmptyJob(job, listener, false, 0, "no-targets-returned");
                return job;
            }
            Thread launcher = new Thread(() -> {
                dispatch(() -> listener.onTop350PlayersLoaded(job.id, preloadedTargets, loadedTotal));
                startWorkers(job, listener, targetQueue, normalizedScope, selectedKit, metric);
            }, "gomod-leaderboard-refresh-start");
            launcher.setDaemon(true);
            launcher.start();
            return job;
        }

        final AtomicBoolean workersStarted = new AtomicBoolean(false);
        if (!preloadedTargets.isEmpty()) {
            LeaderboardRefreshDebugCapture.record(job.id, "targets.stream.initial", "source=loaded-gui-rows rows="
                    + loadedTopPlayers.size() + " uniqueTargets=" + preloadedTargets.size()
                    + " expectedTotal=" + targetQueue.getDisplayTotal() + " leaderboardTotal=" + loadedTotal);
            LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, targetQueue.getDisplayTotal(), false);
            workersStarted.set(true);
            Thread launcher = new Thread(() -> {
                dispatch(() -> listener.onTop350PlayersLoaded(job.id, preloadedTargets, loadedTotal));
                startWorkers(job, listener, targetQueue, normalizedScope, selectedKit, metric);
            }, "gomod-leaderboard-refresh-start");
            launcher.setDaemon(true);
            launcher.start();
        }

        LeaderboardRefreshDebugCapture.record(job.id, "targets.load.start", "service=gostats123 endpoint=/blitz/data"
                + " requestedCount=" + targetCount + " preloaded=" + preloadedTargets.size()
                + " scope=" + normalizedScope + " metric=" + metric + " mode=streaming");
        final int initialOffset = initialRows == null ? 0 : initialRows.size();
        GoStatsLeaderboardClient.streamTopPlayersForBatchRefresh(normalizedScope, metric, selectedKit, targetCount,
                selectedFilters, job.id, initialRows, initialOffset, loadedTotal,
                new GoStatsLeaderboardClient.StreamCallback() {
                    @Override
                    public void onChunk(List<GoStatsLeaderboardClient.Player> chunk, int totalPlayers) {
                        if (job.isCancelled()) return;
                        final List<GoStatsLeaderboardClient.Player> accepted = targetQueue.enqueueBatch(chunk, totalPlayers);
                        if (accepted.isEmpty()) return;
                        final int displayTotal = targetQueue.getDisplayTotal();
                        LeaderboardRefreshDebugCapture.record(job.id, "targets.stream.chunk",
                                "chunkSize=" + accepted.size() + " enqueued=" + targetQueue.getEnqueuedCount()
                                        + " expectedTotal=" + displayTotal + " leaderboardTotal=" + totalPlayers);
                        if (workersStarted.compareAndSet(false, true)) {
                            LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, displayTotal, false);
                            dispatch(() -> listener.onTop350PlayersLoaded(job.id, accepted, totalPlayers));
                            startWorkers(job, listener, targetQueue, normalizedScope, selectedKit, metric);
                        } else {
                            dispatch(() -> listener.onTop350PlayersAppended(job.id, accepted, displayTotal, totalPlayers));
                        }
                    }

                    @Override
                    public void onDone(int totalPlayers, String error) {
                        targetQueue.markLoadingDone();
                        if (!workersStarted.get()) {
                            if (job.isCancelled()) {
                                finishEmptyJob(job, listener, true, 0, "cancelled-during-target-load");
                                return;
                            }
                            if (error != null) {
                                LeaderboardRefreshDebugCapture.record(job.id, "targets.load.failed", "reason=" + error);
                                dispatch(() -> listener.onTop350LoadFailed(job.id, error));
                                finishEmptyJob(job, listener, false, 0, "target-load-failed");
                                return;
                            }
                            LeaderboardRefreshDebugCapture.record(job.id, "targets.loaded",
                                    "source=gostats123-api uniqueTargets=0 leaderboardTotal=" + totalPlayers);
                            dispatch(() -> listener.onTop350PlayersLoaded(job.id,
                                    Collections.<GoStatsLeaderboardClient.Player>emptyList(), totalPlayers));
                            finishEmptyJob(job, listener, false, 0, "no-targets-returned");
                            return;
                        }
                        if (error != null) {
                            LeaderboardRefreshDebugCapture.record(job.id, "targets.load.partial_failed",
                                    "enqueued=" + targetQueue.getEnqueuedCount() + " reason=" + error);
                        } else {
                            LeaderboardRefreshDebugCapture.record(job.id, "targets.loaded",
                                    "source=gostats123-stream uniqueTargets=" + targetQueue.getEnqueuedCount()
                                            + " leaderboardTotal=" + totalPlayers);
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return job.isCancelled();
                    }
                });
        return job;
    }

    private static void startWorkers(final Job job,
                                     final GoStatsLeaderboardBatchRefreshListener listener,
                                     final TargetQueue targetQueue,
                                     final String scope, final String kit, final String metric) {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger updated = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger workersRemaining = new AtomicInteger(WORKER_COUNT);
        LeaderboardRefreshDebugCapture.record(job.id, "workers.start", "workerCount=" + WORKER_COUNT
                + " targets=" + targetQueue.getDisplayTotal() + " ready=" + targetQueue.getEnqueuedCount());
        LeaderboardRefreshDebugCapture.progress(job.id, 0, 0, 0, 0, targetQueue.getDisplayTotal(), false);

        for (int workerIndex = 0; workerIndex < WORKER_COUNT; workerIndex++) {
            final int assignedWorkerIndex = workerIndex;
            Thread worker = new Thread(() -> {
                try {
                    while (!job.isCancelled()) {
                        final GoStatsLeaderboardClient.Player previous = targetQueue.pollNext(job);
                        if (previous == null) break;
                        final int startedCount = started.incrementAndGet();
                        final int currentTotal = targetQueue.getDisplayTotal();
                        LeaderboardRefreshDebugCapture.progress(job.id, startedCount, completed.get(),
                                updated.get(), skipped.get(), currentTotal, job.isCancelled());
                        dispatch(() -> listener.onTop350PlayerStarted(job.id, previous,
                                startedCount, currentTotal));

                        GoStatsLeaderboardClient.Player refreshed = null;
                        String error = null;
                        LeaderboardRefreshDebugCapture.PlayerScope debugScope =
                                LeaderboardRefreshDebugCapture.enterPlayer(job.id, startedCount, currentTotal,
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
                        final int completionTotal = targetQueue.getDisplayTotal();
                        if (finalError == null) updated.incrementAndGet();
                        else skipped.incrementAndGet();
                        LeaderboardRefreshDebugCapture.progress(job.id, started.get(), completedCount,
                                updated.get(), skipped.get(), completionTotal, job.isCancelled());
                        dispatch(() -> listener.onTop350PlayerCompleted(job.id, previous,
                                finalRefreshed, finalError, completedCount, completionTotal));
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
                        final int finalTotal = targetQueue.getDisplayTotal();
                        String finishReason = job.isCancelled() ? "cancelled"
                                : completed.get() < finalTotal ? "worker-pool-ended-before-all-targets" : "workers-finished";
                        LeaderboardRefreshDebugCapture.finish(job.id, finishReason,
                                started.get(), completed.get(), updated.get(), skipped.get(), finalTotal, job.isCancelled());
                        synchronized (GoStatsLeaderboardBatchRefresh.class) {
                            job.finish();
                            if (activeJob == job) activeJob = null;
                        }
                        dispatch(() -> listener.onTop350Finished(job.id, updated.get(), skipped.get(),
                                completed.get(), finalTotal, job.isCancelled()));
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

    /**
     * Thread-safe streaming queue bridging background leaderboard page loads and refresh workers.
     */
    static final class TargetQueue {
        private final Object lock = new Object();
        private final ArrayDeque<GoStatsLeaderboardClient.Player> queue = new ArrayDeque<GoStatsLeaderboardClient.Player>();
        private final Set<String> seenUuids = new HashSet<String>();
        private final int maxTargets;
        private int enqueuedCount;
        private int expectedTotal;
        private boolean loadingDone;

        TargetQueue(int maxTargets, int initialExpectedTotal) {
            this.maxTargets = Math.max(1, maxTargets);
            this.expectedTotal = Math.max(0, Math.min(this.maxTargets, initialExpectedTotal));
        }

        List<GoStatsLeaderboardClient.Player> enqueueBatch(List<GoStatsLeaderboardClient.Player> batch,
                                                           int knownLeaderboardTotal) {
            List<GoStatsLeaderboardClient.Player> accepted = new ArrayList<GoStatsLeaderboardClient.Player>();
            synchronized (lock) {
                if (batch != null) {
                    for (GoStatsLeaderboardClient.Player player : batch) {
                        if (enqueuedCount >= maxTargets) break;
                        if (player == null || player.uuid == null || player.uuid.trim().isEmpty()) continue;
                        String uuid = player.uuid.replace("-", "").trim().toLowerCase(java.util.Locale.ROOT);
                        if (uuid.matches("[a-f0-9]{32}") && seenUuids.add(uuid)) {
                            queue.addLast(player);
                            accepted.add(player);
                            enqueuedCount++;
                        }
                    }
                }
                if (knownLeaderboardTotal > 0) {
                    expectedTotal = Math.max(enqueuedCount, Math.min(maxTargets, knownLeaderboardTotal));
                } else {
                    expectedTotal = Math.max(expectedTotal, enqueuedCount);
                }
                if (!accepted.isEmpty()) {
                    lock.notifyAll();
                }
            }
            return accepted;
        }

        void markLoadingDone() {
            synchronized (lock) {
                loadingDone = true;
                expectedTotal = enqueuedCount;
                lock.notifyAll();
            }
        }

        void wakeAll() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        GoStatsLeaderboardClient.Player pollNext(Job job) {
            synchronized (lock) {
                while (queue.isEmpty() && !loadingDone && !job.isCancelled()) {
                    try {
                        lock.wait(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (job.isCancelled() || queue.isEmpty()) {
                    return null;
                }
                return queue.pollFirst();
            }
        }

        int getEnqueuedCount() {
            synchronized (lock) {
                return enqueuedCount;
            }
        }

        int getDisplayTotal() {
            synchronized (lock) {
                return loadingDone ? enqueuedCount : Math.max(enqueuedCount, expectedTotal);
            }
        }
    }

    public static final class Job {
        private final long id;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile Runnable cancelHook;

        private Job(long id) {
            this.id = id;
        }

        private void attachCancelHook(Runnable cancelHook) {
            this.cancelHook = cancelHook;
            if (cancelled && cancelHook != null) {
                cancelHook.run();
            }
        }

        public long getId() {
            return id;
        }

        public boolean cancel() {
            if (finished) return false;
            cancelled = true;
            Runnable hook = cancelHook;
            if (hook != null) hook.run();
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
