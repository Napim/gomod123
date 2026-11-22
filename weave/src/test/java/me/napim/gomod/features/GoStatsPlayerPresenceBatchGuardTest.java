package me.napim.gomod.features;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class GoStatsPlayerPresenceBatchGuardTest {
    private static final String TEST_UUID = "11111111222233334444555566667777";

    @After
    public void clearActiveJob() throws Exception {
        setActiveJob(null);
    }

    @Test
    public void suppressesSessionDetailRequestsWhileBatchRefreshIsRunning() throws Exception {
        assertFalse(GoStatsLeaderboardBatchRefresh.isRunning());

        GoStatsLeaderboardBatchRefresh.Job job = newJob(999L);
        setActiveJob(job);
        assertTrue(GoStatsLeaderboardBatchRefresh.isRunning());

        GoStatsPlayerPresence.requestSessionDetails(TEST_UUID);

        Field requestsField = GoStatsPlayerPresence.class.getDeclaredField("SESSION_REQUESTS");
        requestsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> inFlight = (Set<String>) requestsField.get(null);
        assertFalse("Hover session details must not start while a batch refresh is running",
                inFlight.contains(TEST_UUID));
        assertNull("Suppressed request must not populate session cache",
                GoStatsPlayerPresence.cachedSessionDetails(TEST_UUID));

        Method finish = GoStatsLeaderboardBatchRefresh.Job.class.getDeclaredMethod("finish");
        finish.setAccessible(true);
        finish.invoke(job);
        assertFalse(GoStatsLeaderboardBatchRefresh.isRunning());
    }

    private static GoStatsLeaderboardBatchRefresh.Job newJob(long id) throws Exception {
        Constructor<GoStatsLeaderboardBatchRefresh.Job> ctor =
                GoStatsLeaderboardBatchRefresh.Job.class.getDeclaredConstructor(long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id);
    }

    private static void setActiveJob(GoStatsLeaderboardBatchRefresh.Job job) throws Exception {
        Field activeJob = GoStatsLeaderboardBatchRefresh.class.getDeclaredField("activeJob");
        activeJob.setAccessible(true);
        activeJob.set(null, job);
    }
}
