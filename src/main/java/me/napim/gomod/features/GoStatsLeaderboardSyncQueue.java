package me.napim.gomod.features;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded database uploads which never run on the profile refresh workers. */
public final class GoStatsLeaderboardSyncQueue {
    public interface Upload { void run() throws Exception; }

    private final ThreadPoolExecutor workers;
    private final Set<String> inFlight = new HashSet<String>();
    private final Map<String, Integer> pendingByUuid = new HashMap<String, Integer>();
    private final Map<String, String> errors = new LinkedHashMap<String, String>();
    private int pending, saved;

    public GoStatsLeaderboardSyncQueue(int concurrency, int capacity) {
        workers = new ThreadPoolExecutor(concurrency, concurrency, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, "gomod-leaderboard-database-sync");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized boolean submit(String uuid, String observationKey, Upload upload) {
        return submit(uuid, observationKey, upload, null);
    }

    /** Optional completion hook is used by the leaderboard refresh diagnostic session. */
    public synchronized boolean submit(String uuid, String observationKey, Upload upload,
                                       Runnable completionHook) {
        final String player = normalize(uuid);
        if (inFlight.contains(observationKey)) {
            runCompletionHook(completionHook);
            return true;
        }
        inFlight.add(observationKey);
        pending++;
        pendingByUuid.put(player, pendingByUuid.getOrDefault(player, 0) + 1);
        errors.remove(player);
        try {
            workers.execute(() -> {
                String failure = null;
                boolean succeeded = false;
                try {
                    upload.run();
                    succeeded = true;
                } catch (Exception error) {
                    failure = error.getMessage();
                    if (failure == null || failure.trim().isEmpty()) failure = error.getClass().getSimpleName();
                } finally {
                    if (!succeeded && failure == null) failure = "Database upload did not complete.";
                    complete(player, observationKey, failure);
                    runCompletionHook(completionHook);
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            complete(player, observationKey, "Database sync queue is full. Refresh this player again shortly.");
            runCompletionHook(completionHook);
            return false;
        }
    }

    private static void runCompletionHook(Runnable hook) {
        if (hook == null) return;
        try { hook.run(); } catch (Throwable ignored) { }
    }

    private synchronized void complete(String uuid, String key, String failure) {
        inFlight.remove(key);
        pending--;
        int remaining = pendingByUuid.getOrDefault(uuid, 1) - 1;
        if (remaining > 0) pendingByUuid.put(uuid, remaining);
        else pendingByUuid.remove(uuid);
        if (failure == null) {
            saved++;
            errors.remove(uuid);
        } else {
            errors.put(uuid, failure);
            while (errors.size() > 256) errors.remove(errors.keySet().iterator().next());
        }
    }

    public synchronized Status status() { return new Status(pending, saved, errors.size()); }
    public synchronized boolean isPending(String uuid) { return pendingByUuid.containsKey(normalize(uuid)); }
    public synchronized String error(String uuid) { return errors.getOrDefault(normalize(uuid), ""); }
    public void shutdown() { workers.shutdown(); }

    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    public static final class Status {
        public final int pending, saved, failed;
        private Status(int pending, int saved, int failed) {
            this.pending = pending;
            this.saved = saved;
            this.failed = failed;
        }
        public boolean hasActivity() { return pending + saved + failed > 0; }
    }
}
