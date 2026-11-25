package me.napim.gomod.features;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoStatsLeaderboardBatchRefreshStreamingTest {

    @Test
    public void workersConsumeInitialBatchWhileNextPageStreamsIn() throws Exception {
        GoStatsLeaderboardBatchRefresh.Job job = newJob(101L);
        GoStatsLeaderboardBatchRefresh.TargetQueue queue =
                new GoStatsLeaderboardBatchRefresh.TargetQueue(4, 4);

        List<GoStatsLeaderboardClient.Player> firstChunk = Arrays.asList(
                fakePlayer(1, "00000000000000000000000000000001", "Alice"),
                fakePlayer(2, "00000000000000000000000000000002", "Bob"));
        List<GoStatsLeaderboardClient.Player> acceptedFirst = queue.enqueueBatch(firstChunk, 500);
        assertEquals(2, acceptedFirst.size());
        assertEquals(4, queue.getDisplayTotal());

        final List<String> consumed = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch firstTwoConsumed = new CountDownLatch(2);
        final CountDownLatch allDone = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            while (true) {
                GoStatsLeaderboardClient.Player p = queue.pollNext(job);
                if (p == null) break;
                consumed.add(p.username);
                firstTwoConsumed.countDown();
            }
            allDone.countDown();
        }, "test-streaming-worker");
        worker.setDaemon(true);
        worker.start();

        // Worker immediately starts consuming the first chunk before the second chunk even arrives.
        assertTrue("Worker should consume initial chunk immediately",
                firstTwoConsumed.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("Alice", "Bob"), consumed);

        // Stream in second chunk (with a duplicate Bob that must be ignored).
        List<GoStatsLeaderboardClient.Player> secondChunk = Arrays.asList(
                fakePlayer(2, "00000000000000000000000000000002", "Bob"),
                fakePlayer(3, "00000000000000000000000000000003", "Charlie"),
                fakePlayer(4, "00000000000000000000000000000004", "Dana"));
        List<GoStatsLeaderboardClient.Player> acceptedSecond = queue.enqueueBatch(secondChunk, 500);
        assertEquals(2, acceptedSecond.size());
        queue.markLoadingDone();

        assertTrue("Worker should finish after markLoadingDone",
                allDone.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("Alice", "Bob", "Charlie", "Dana"), consumed);
        assertEquals(4, queue.getDisplayTotal());
    }

    @Test
    public void cancellingJobWakesWaitingWorkersImmediately() throws Exception {
        GoStatsLeaderboardBatchRefresh.Job job = newJob(102L);
        GoStatsLeaderboardBatchRefresh.TargetQueue queue =
                new GoStatsLeaderboardBatchRefresh.TargetQueue(10, 10);
        java.lang.reflect.Method attach = GoStatsLeaderboardBatchRefresh.Job.class
                .getDeclaredMethod("attachCancelHook", Runnable.class);
        attach.setAccessible(true);
        attach.invoke(job, (Runnable) queue::wakeAll);

        final CountDownLatch workerExited = new CountDownLatch(1);
        final AtomicInteger polled = new AtomicInteger();
        Thread worker = new Thread(() -> {
            GoStatsLeaderboardClient.Player p = queue.pollNext(job);
            if (p != null) polled.incrementAndGet();
            workerExited.countDown();
        });
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(50L);
        assertTrue(job.cancel());
        assertTrue("Waiting worker must wake and exit on cancel",
                workerExited.await(1, TimeUnit.SECONDS));
        assertEquals(0, polled.get());
        assertNull(queue.pollNext(job));
    }

    @Test
    public void displayTotalClampsToActualEnqueuedCountWhenLoadingEndsEarly() throws Exception {
        GoStatsLeaderboardBatchRefresh.TargetQueue queue =
                new GoStatsLeaderboardBatchRefresh.TargetQueue(295, 295);
        queue.enqueueBatch(Collections.singletonList(
                fakePlayer(1, "00000000000000000000000000000001", "SoloOnly")), 295);
        assertEquals(295, queue.getDisplayTotal());
        queue.markLoadingDone();
        assertEquals(1, queue.getDisplayTotal());
    }

    private static GoStatsLeaderboardBatchRefresh.Job newJob(long id) throws Exception {
        Constructor<GoStatsLeaderboardBatchRefresh.Job> ctor =
                GoStatsLeaderboardBatchRefresh.Job.class.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id);
    }

    private static GoStatsLeaderboardClient.Player fakePlayer(int rank, String uuid, String username) throws Exception {
        Constructor<GoStatsLeaderboardClient.Player> ctor =
                GoStatsLeaderboardClient.Player.class.getDeclaredConstructor(
                        int.class, String.class, String.class, String.class, String.class, int.class,
                        String.class, String.class, String.class, String.class, String.class, String.class,
                        boolean.class, long.class, long.class, long.class, long.class, long.class,
                        long.class, long.class, long.class, long.class, long.class, double.class);
        ctor.setAccessible(true);
        return ctor.newInstance(rank, uuid, username, "", "", 0xFFFFFF,
                "", "", "", "", "", "",
                false, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, (double) (1000 - rank));
    }
}
