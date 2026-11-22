package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.GomodReleaseBuild;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

public final class HypixelApiRequestDebugTest {
    private static final String TARGET = "a0b1c2d3-e4f5-4a12-b345-123456789012";
    private static final String SECRET = "fixture-credential%2Fprivate";
    private static final String PLAYER_URL = "https://api.hypixel.net/player?key=" + SECRET
            + "&uuid=" + TARGET + "&unexpected=untrusted-query";

    @After
    public void stopCapture() throws Exception {
        snapshot(true);
    }

    @Test
    public void tracesHypixelAttemptsWithAllowlistedFieldsAndTracksRepeatedTargetsInDev() throws Exception {
        assertNull(HypixelApiRequestDebug.begin("GET", PLAYER_URL));
        HypixelApiRequestDebug.start();
        assertNull(HypixelApiRequestDebug.begin("GET", "https://api.hypixel.net.example.com/player"));

        HypixelApiRequestDebug.Request first = HypixelApiRequestDebug.begin("GET", PLAYER_URL);
        assertNotNull(first);
        HttpURLConnection connection = new HttpURLConnection(new URL(PLAYER_URL)) {
            @Override public void connect() {}
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public String getHeaderField(String name) {
                if ("RateLimit-Limit".equals(name)) return "300";
                if ("RateLimit-Remaining".equals(name)) return "0";
                if ("RateLimit-Reset".equals(name)) return "28";
                return "untrusted-header";
            }
        };
        HypixelApiRequestDebug.finish(first, 429, "28", connection, new Exception("private HTTP response body"));

        HypixelApiRequestDebug.Request second = HypixelApiRequestDebug.begin("GET",
                "https://api.hypixel.net/v2/player?uuid=" + TARGET.replace("-", "") + "&key=" + SECRET);
        HypixelApiRequestDebug.finish(second, 200, "untrusted-retry-after", null, null);
        HypixelApiRequestDebug.record("tab.cages_open", "autoScanMode=CAGES_OPEN");
        String trace = snapshot(false);
        assertTrue(trace.contains("HTTP attempts captured: 2"));
        assertTrue(trace.contains("START #1 GET api.hypixel.net/player target=" + TARGET.replace("-", "")));
        assertTrue(trace.contains("END   #1 status=429"));
        assertTrue(trace.contains("retry-after=28"));
        assertTrue(trace.contains("END   #2 status=200"));
        assertFalse(trace.contains("fixture-credential"));
        assertFalse(trace.contains("untrusted-"));
        assertFalse(trace.contains("private HTTP response body"));
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            assertFalse(trace.contains("ratelimit-"));
            assertFalse(trace.contains("previousAttempt="));
            assertFalse(trace.contains("tab.cages_open"));
        } else {
            assertTrue(trace.contains("ratelimit-limit=300 ratelimit-remaining=0 ratelimit-reset=28"));
            assertTrue(trace.contains("previousAttempt=#1 sincePreviousMs="));
            assertTrue(trace.contains("tab.cages_open autoScanMode=CAGES_OPEN"));
        }
    }

    @Test
    public void lateResponseCannotAppearInTheNextCapture() throws Exception {
        HypixelApiRequestDebug.start();
        HypixelApiRequestDebug.Request old = HypixelApiRequestDebug.begin("GET", PLAYER_URL);
        assertTrue(snapshot(true).contains("START #1"));
        HypixelApiRequestDebug.start();
        HypixelApiRequestDebug.Request current = HypixelApiRequestDebug.begin("GET", PLAYER_URL);
        HypixelApiRequestDebug.finish(old, 429, null, null, null);
        HypixelApiRequestDebug.finish(current, 200, null, null, null);

        String trace = snapshot(false);
        assertFalse(trace.contains("status=429"));
        assertFalse(trace.contains("previousAttempt="));
        assertTrue(trace.contains("END   #1 status=200"));
    }

    @Test
    public void recordsCacheHitsAndPlayerDeadlineWithoutHttpAttempts() throws Exception {
        assumeFalse(GomodReleaseBuild.PUBLIC_RELEASE);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String url = "https://api.hypixel.net/player?key=" + SECRET + "&uuid=" + uuid;
        JsonObject profile = new JsonObject();
        profile.addProperty("uuid", uuid);
        HypixelPlayerDataCache.ingest("TraceCacheTester", profile);
        HypixelApiRequestDebug.start();

        HypixelPlayerRequests.Loader noNetwork = ignored -> { throw new AssertionError("Unexpected HTTP"); };
        HypixelPlayerRequests.fetch(url, false, false, noNetwork);
        HypixelPlayerRequests.noteNetworkSuccess(uuid, System.currentTimeMillis() + 180_000L);
        try {
            HypixelPlayerRequests.fetch(url, true, false, noNetwork);
            fail("Expected a player cooldown deadline");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().startsWith("Fresh player data is still cooling down"));
        }

        String trace = snapshot(false);
        assertTrue(trace.contains("hypixel.cache_hit uuid=" + uuid));
        assertTrue(trace.contains("hypixel.cooldown_wait.skipped uuid=" + uuid));
        assertTrue(trace.contains("reason=player-cooldown-would-exceed-deadline"));
        assertTrue(trace.contains("HTTP attempts captured: 0"));
    }

    @Test
    public void distinguishesKeyBackoffFromPerPlayerRejectionAndLocalQuota() throws Exception {
        assumeFalse(GomodReleaseBuild.PUBLIC_RELEASE);
        String sameKey = "https://api.hypixel.net/v2/status?key=" + UUID.randomUUID() + "&uuid=" + TARGET;
        String quotaKey = "https://api.hypixel.net/v2/counts?key=" + UUID.randomUUID();
        HypixelApiRequestDebug.start();
        HypixelPlayerRequests.noteApiKeyRateLimit(sameKey, 180_000L, "already looked up this player too recently");
        HypixelPlayerRequests.awaitNetworkRequestSlot(sameKey);
        HypixelPlayerRequests.noteApiKeyRateLimit(sameKey, 180_000L, "HTTP 429 (untrusted body)");
        assertKeySlotDeferred(sameKey);

        for (int slot = 0; slot < 300; slot++) HypixelPlayerRequests.awaitNetworkRequestSlot(quotaKey);
        assertKeySlotDeferred(quotaKey);

        String trace = snapshot(false);
        assertTrue(trace.contains("reason=recent-player-lookup backoffScope=player"));
        assertTrue(trace.contains("reason=unclassified backoffScope=api-key"));
        assertTrue(trace.matches("(?s).*endpoint=/v2/status[^\\n]* quotaWaitMs=0 keyBackoffMs=[1-9][0-9]*.*"));
        assertTrue(trace.matches("(?s).*endpoint=/v2/counts.*?quotaWaitMs=[1-9][0-9]* keyBackoffMs=0.*"));
        assertFalse(trace.contains("untrusted body"));
        assertTrue(trace.contains("HTTP attempts captured: 0"));
    }

    private static void assertKeySlotDeferred(String url) throws Exception {
        try {
            HypixelPlayerRequests.awaitNetworkRequestSlot(url);
            fail("Expected a key wait beyond the request deadline");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().startsWith("Hypixel API key is rate limited"));
        }
    }

    private static String snapshot(boolean stop) throws Exception {
        Method snapshot = HypixelApiRequestDebug.class.getDeclaredMethod("snapshot", boolean.class);
        snapshot.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) snapshot.invoke(null, stop);
        return String.join("\n", lines);
    }
}
