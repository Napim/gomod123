package me.napim.gomod.features;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.Main;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/** Tracks the Lunar presence path independently from Gomod relay connectivity. */
public final class LunarOnlineStatus {
    private static final String FULL_ACCESS_RELAY_HOST = "gomod-service.onrender.com";
    private static final String FULL_ACCESS_API_HOST = "gomod-service.onrender.com";
    private static final Set<Object> OPEN_LUNAR_GAME_SOCKETS =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Long> LAST_LUNAR_GAME_RESPONSE_MS =
            new WeakHashMap<Object, Long>();
    private static final Set<Object> OPEN_FULL_ACCESS_SOCKETS =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Set<Object> RELAY_AUTH_SUCCESS_SOCKETS =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Long> FULL_ACCESS_CONNECT_ATTEMPTS_MS =
            new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> FULL_ACCESS_SOCKET_OPENED_MS =
            new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> LAST_FULL_ACCESS_RESPONSE_MS =
            new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> SOCKET_ROUTE_GENERATIONS =
            new WeakHashMap<Object, Long>();
    private static final ScheduledExecutorService ROUTE_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "gomod-lunar-route-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile long fullAccessAuthSuccessMs;
    private static volatile long fullAccessAuthFirstAttemptMs;
    private static volatile boolean fullAccessPreferenceEnabled = true;
    private static volatile boolean fullAccessRuntimeRouteEnabled;
    private static volatile long fullAccessRouteEnabledSinceMs;
    private static volatile long lunarOnlineConnectingSinceMs;
    private static volatile String lunarOnlineConnectingMessage = "Waiting for a Lunar connection.";
    private static volatile long runtimeRouteGeneration;
    private static volatile boolean routeWatchdogStarted;
    private static volatile long nextFullAccessRecoveryProbeMs;
    private static volatile int fullAccessRouteFailureCount;
    private static volatile ConnectionState lastAnnouncedLunarOnlineState;
    private static volatile ConnectionState lastAnnouncedFullAccessState;
    private static int lastFullAccessAppearanceAvailability = -1;
    private static final long RESPONSE_FRESHNESS_MS = 90_000L;
    private static volatile long fullAccessRenderResponseMs;
    // API cold starts run in the background while official Lunar stays usable.
    private static final long OFFICIAL_CONNECTING_TIMEOUT_MS = 3 * 60_000L;
    private static final long CONNECTING_TIMEOUT_MS = 3 * 60_000L;
    // A readiness-approved handoff must not strand official presence if Auth stops responding.
    private static final long AUTH_RESPONSE_TIMEOUT_MS = 60_000L;
    // Auth has succeeded before Assets begins; an Assets socket should not wait indefinitely
    // for its first application response or remain stale while Lunar Online is marked offline.
    private static final long ASSETS_RESPONSE_TIMEOUT_MS = 3 * 60_000L;
    private static final long FULL_ACCESS_RECOVERY_INTERVAL_MS = 5 * 60_000L;
    private static final long FULL_ACCESS_RECOVERY_MAX_INTERVAL_MS = 30 * 60_000L;

    public enum ConnectionState {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    private LunarOnlineStatus() {
    }

    /** Initializes the internal Weave route state before Lunar begins connecting. */
    public static void initializeRuntimeRoutes(boolean enabled) {
        initializeRuntimeRoutes(enabled, enabled);
    }

    /** The user's preference and an actually activated relay route are separate state. */
    public static void initializeRuntimeRoutes(boolean requested, boolean relayRouteEnabled) {
        fullAccessPreferenceEnabled = requested;
        fullAccessRuntimeRouteEnabled = relayRouteEnabled;
        fullAccessAuthSuccessMs = 0L;
        fullAccessAuthFirstAttemptMs = 0L;
        nextFullAccessRecoveryProbeMs = requested && !relayRouteEnabled
                ? System.currentTimeMillis() + 5_000L : 0L;
        fullAccessRouteFailureCount = 0;
        runtimeRouteGeneration++;
        clearTrackedRouteSockets();
        fullAccessRouteEnabledSinceMs = relayRouteEnabled ? System.currentTimeMillis() : 0L;
        // Establish a quiet startup baseline; the first real connection transition is announced.
        lastAnnouncedLunarOnlineState = ConnectionState.CONNECTING;
        lastAnnouncedFullAccessState = requested
                ? ConnectionState.CONNECTING : ConnectionState.DISCONNECTED;
        markLunarOnlineConnecting(relayRouteEnabled
                ? "Connecting through Lunar FullAccess."
                : "Connecting to Lunar's official services.");
        startRouteWatchdog();
    }

    /** Applies an in-game FullAccess preference change to Lunar's live service routes. */
    public static void onFullAccessPreferenceChanged(boolean enabled) {
        fullAccessPreferenceEnabled = enabled;
        fullAccessRouteFailureCount = 0;
        if (enabled && !fullAccessRuntimeRouteEnabled) {
            // Do not close a working official socket just because the toggle is enabled.
            // The watchdog probes off-thread and the bootstrap rechecks this generation.
            runtimeRouteGeneration++;
            nextFullAccessRecoveryProbeMs = System.currentTimeMillis();
            LunarConnectionDiagnostics.record("lunar.fullaccess_enable_deferred",
                    "official-route-retained=true waiting-for-api-readiness=true");
            refreshLockerAvailability();
            return;
        }
        nextFullAccessRecoveryProbeMs = 0L;
        switchRuntimeRoute(enabled, "user-toggle");
        refreshLockerAvailability();
    }

    /** Called by the Weave bootstrap after endpoint mutation succeeds. */
    public static void onRuntimeRouteChanged(boolean enabled, String reason) {
        fullAccessRuntimeRouteEnabled = enabled;
        runtimeRouteGeneration++;
        if (!enabled && fullAccessPreferenceEnabled && isRecoverableFullAccessFailure(reason)) {
            fullAccessRouteFailureCount = Math.min(16, fullAccessRouteFailureCount + 1);
        }
        nextFullAccessRecoveryProbeMs = !enabled && fullAccessPreferenceEnabled
                ? System.currentTimeMillis() + fullAccessRecoveryDelayMs(fullAccessRouteFailureCount) : 0L;
        clearTrackedRouteSockets();
        fullAccessRouteEnabledSinceMs = enabled ? System.currentTimeMillis() : 0L;
        markLunarOnlineConnecting(routeTransitionMessage(enabled, reason));
        LunarConnectionDiagnostics.record("lunar.runtime_route",
                "fullAccessRoute=" + (enabled ? "relay" : "official")
                        + " reason=" + safeRouteReason(reason));
        reportStatus();
        reportFullAccessStatus();
    }

    /** Called when Lunar's client begins opening its dedicated Gomod FullAccess socket. */
    public static void onWebSocketConnectAttempt(Object client) {
        if (isAuthenticatorSocket(client) || isLunarOnlineSocket(client)) {
            markLunarOnlineConnecting(connectionAttemptMessage());
        }
        if (isFullAccessSocket(client) || isRelayAuthenticatorSocket(client)) {
            synchronized (OPEN_FULL_ACCESS_SOCKETS) {
                long now = System.currentTimeMillis();
                FULL_ACCESS_CONNECT_ATTEMPTS_MS.put(client, now);
                SOCKET_ROUTE_GENERATIONS.put(client, runtimeRouteGeneration);
                if (isRelayAuthenticatorSocket(client)
                        && fullAccessAuthSuccessMs == 0L
                        && fullAccessAuthFirstAttemptMs == 0L) {
                    fullAccessAuthFirstAttemptMs = now;
                }
            }
            reportFullAccessStatus();
        }
        if (isAuthenticatorSocket(client)) {
            reportStatus();
        }
    }

    /** Called by the Weave bootstrap's guarded WebSocketClient instrumentation. */
    public static void onWebSocketOpened(Object client) {
        LunarConnectionDiagnostics.onSocketOpened(client);
        if (isAuthenticatorSocket(client) || isLunarOnlineSocket(client)) {
            markLunarOnlineConnecting(connectionAttemptMessage());
        }
        if (isLunarOnlineSocket(client)) {
            synchronized (OPEN_LUNAR_GAME_SOCKETS) {
                OPEN_LUNAR_GAME_SOCKETS.add(client);
                LAST_LUNAR_GAME_RESPONSE_MS.remove(client);
            }
            reportStatus();
        }
        if (isAuthenticatorSocket(client)) {
            reportStatus();
        }
        if (isFullAccessSocket(client)) {
            synchronized (OPEN_FULL_ACCESS_SOCKETS) {
                OPEN_FULL_ACCESS_SOCKETS.add(client);
                FULL_ACCESS_SOCKET_OPENED_MS.put(client, System.currentTimeMillis());
                forgetFullAccessResponseLocked(client);
                FULL_ACCESS_CONNECT_ATTEMPTS_MS.remove(client);
            }
            reportFullAccessStatus();
        }
    }

    /** Called when Lunar receives a server frame on its game/presence socket. */
    public static void onWebSocketMessage(Object client) {
        onWebSocketMessage(client, null);
    }

    /** Called with the frame payload so authentication is not inferred from a mere socket open. */
    public static void onWebSocketMessage(Object client, Object payload) {
        LunarConnectionDiagnostics.onSocketFrame(client);
        if (isAuthenticatorSocket(client) && hasAuthSuccessFrame(client, payload)) {
            if (isRelayAuthenticatorSocket(client)) {
                fullAccessAuthSuccessMs = System.currentTimeMillis();
                fullAccessAuthFirstAttemptMs = 0L;
                synchronized (RELAY_AUTH_SUCCESS_SOCKETS) {
                    RELAY_AUTH_SUCCESS_SOCKETS.add(client);
                }
                synchronized (OPEN_FULL_ACCESS_SOCKETS) {
                    FULL_ACCESS_CONNECT_ATTEMPTS_MS.remove(client);
                }
                reportFullAccessStatus();
            }
            LunarConnectionDiagnostics.record("lunar.auth_success",
                    "source=upstream-authenticator-response " + endpointLabel(client));
            markLunarOnlineConnecting("Lunar authentication succeeded; waiting for game presence.");
            reportStatus();
        }
        if (isLunarOnlineSocket(client)) {
            synchronized (OPEN_LUNAR_GAME_SOCKETS) {
                // A route toggle clears the old tracked set, but Lunar's official presence
                // socket may remain open and keep delivering frames. A real frame is enough
                // evidence to re-track that socket without waiting for it to reopen.
                OPEN_LUNAR_GAME_SOCKETS.add(client);
                LAST_LUNAR_GAME_RESPONSE_MS.put(client, System.currentTimeMillis());
            }
            lunarOnlineConnectingSinceMs = 0L;
            lunarOnlineConnectingMessage = "";
            reportStatus();
        }
        if (isFullAccessSocket(client)) {
            synchronized (OPEN_FULL_ACCESS_SOCKETS) {
                if (OPEN_FULL_ACCESS_SOCKETS.contains(client)) {
                    LAST_FULL_ACCESS_RESPONSE_MS.put(client, System.currentTimeMillis());
                }
            }
            reportFullAccessStatus();
        }
    }

    /** Called when the instrumented WebSocket closes or fails. */
    public static void onWebSocketClosed(Object client) {
        onWebSocketClosed(client, -1, "callback-without-close-details", false);
    }

    /** Called with the close metadata from Java-WebSocket; reason text is sanitized before writing. */
    public static void onWebSocketClosed(Object client, int code, String reason, boolean remote) {
        LunarConnectionDiagnostics.onSocketClosed(client, code, reason, remote);
        maybeFallbackToOfficial(client, code != 1000);
        forgetSocket(client);
    }

    /** Called separately from normal close so connection failures retain their exception details. */
    public static void onWebSocketError(Object client, Throwable error) {
        LunarConnectionDiagnostics.onSocketError(client, error);
        maybeFallbackToOfficial(client, true);
        forgetSocket(client);
    }

    private static void maybeFallbackToOfficial(Object client, boolean failed) {
        if (!failed || !fullAccessPreferenceEnabled || !fullAccessRuntimeRouteEnabled) {
            return;
        }
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            Long socketGeneration = SOCKET_ROUTE_GENERATIONS.get(client);
            if (socketGeneration == null || socketGeneration.longValue() != runtimeRouteGeneration) {
                return;
            }
        }

        boolean failedAuthenticator = isRelayAuthenticatorSocket(client);
        if (failedAuthenticator) {
            synchronized (RELAY_AUTH_SUCCESS_SOCKETS) {
                // Authenticator sockets normally close after successful authentication.
                // Only an unsuccessful relay auth close should activate the official fallback.
                if (RELAY_AUTH_SUCCESS_SOCKETS.remove(client)) {
                    failedAuthenticator = false;
                }
            }
        }
        boolean failedAssets = isFullAccessSocket(client);
        if (!failedAuthenticator && !failedAssets) {
            return;
        }

        String supersededReason = supersededRouteFailureReason(
                client, failedAuthenticator, failedAssets);
        if (supersededReason != null) {
            LunarConnectionDiagnostics.record("lunar.fullaccess_failure_ignored",
                    "role=" + (failedAuthenticator ? "authenticator" : "assets")
                            + " reason=" + supersededReason);
            return;
        }

        LunarConnectionDiagnostics.record("lunar.fullaccess_fallback",
                "trigger=" + (failedAuthenticator ? "authenticator-failure" : "assets-failure")
                        + " target=official-lunar");
        switchRuntimeRoute(false, failedAuthenticator ? "authenticator-failure" : "assets-failure");
    }

    /**
     * A retry may fail after a newer socket has already authenticated or received Assets data.
     * Such a stale failure must not tear down the route that is currently working.
     */
    private static String supersededRouteFailureReason(
            Object failedClient, boolean failedAuthenticator, boolean failedAssets) {
        if (failedAuthenticator) {
            // This timestamp is scoped to the current route generation and is deliberately not
            // cleared by later token-refresh/retry attempts.
            if (fullAccessAuthSuccessMs > 0L) {
                return "auth-already-succeeded-this-route";
            }
            if (hasOtherPendingRelayAuthenticator(failedClient)) {
                return "another-auth-attempt-pending";
            }
        }
        if ((failedAuthenticator || failedAssets)
                && hasFreshFullAccessResponseFromOtherSocket(failedClient)) {
            return "another-assets-socket-has-fresh-response";
        }
        return null;
    }

    private static boolean hasOtherPendingRelayAuthenticator(Object failedClient) {
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            for (Map.Entry<Object, Long> attempt : FULL_ACCESS_CONNECT_ATTEMPTS_MS.entrySet()) {
                Object candidate = attempt.getKey();
                Long generation = SOCKET_ROUTE_GENERATIONS.get(candidate);
                if (candidate != failedClient
                        && generation != null
                        && generation.longValue() == runtimeRouteGeneration
                        && isRelayAuthenticatorSocket(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasFreshFullAccessResponseFromOtherSocket(Object failedClient) {
        long now = System.currentTimeMillis();
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            for (Object candidate : OPEN_FULL_ACCESS_SOCKETS) {
                if (candidate == failedClient) {
                    continue;
                }
                Long generation = SOCKET_ROUTE_GENERATIONS.get(candidate);
                Long lastResponse = LAST_FULL_ACCESS_RESPONSE_MS.get(candidate);
                if (generation != null
                        && generation.longValue() == runtimeRouteGeneration
                        && lastResponse != null
                        && Math.max(0L, now - lastResponse.longValue()) <= RESPONSE_FRESHNESS_MS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void switchRuntimeRoute(boolean enabled, String reason) {
        try {
            Class<?> bootstrap = Class.forName(
                    "me.napim.gomod.weave.LunarRelayWeaveBootstrap",
                    true, LunarOnlineStatus.class.getClassLoader());
            Method switchMethod = bootstrap.getMethod("setRuntimeFullAccessRoute",
                    boolean.class, String.class);
            switchMethod.invoke(null, enabled, reason);
        } catch (ClassNotFoundException unavailableOutsideWeave) {
            // The route bootstrap exists only in the native Weave build; Forge keeps its current behavior.
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar.runtime_route_request_failed",
                    "reason=" + safeRouteReason(reason)
                            + " exception=" + error.getClass().getSimpleName());
        }
    }

    private static String safeRouteReason(String reason) {
        return reason == null ? "unspecified" : reason.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void forgetSocket(Object client) {
        if (client == null) {
            return;
        }
        synchronized (OPEN_LUNAR_GAME_SOCKETS) {
            OPEN_LUNAR_GAME_SOCKETS.remove(client);
            LAST_LUNAR_GAME_RESPONSE_MS.remove(client);
        }
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            OPEN_FULL_ACCESS_SOCKETS.remove(client);
            FULL_ACCESS_CONNECT_ATTEMPTS_MS.remove(client);
            FULL_ACCESS_SOCKET_OPENED_MS.remove(client);
            forgetFullAccessResponseLocked(client);
            SOCKET_ROUTE_GENERATIONS.remove(client);
        }
        synchronized (RELAY_AUTH_SUCCESS_SOCKETS) {
            RELAY_AUTH_SUCCESS_SOCKETS.remove(client);
        }
        reportStatus();
        reportFullAccessStatus();
    }

    private static void forgetFullAccessResponseLocked(Object client) {
        if (LAST_FULL_ACCESS_RESPONSE_MS.remove(client) == null) return;
        long latest = 0L;
        for (Long response : LAST_FULL_ACCESS_RESPONSE_MS.values()) {
            latest = Math.max(latest, response);
        }
        fullAccessRenderResponseMs = latest;
    }

    /** Treat a received WebSocket PONG as transport liveness when Lunar is otherwise idle. */
    public static void onWebSocketPong(Object client) {
        if (isLunarOnlineSocket(client)) {
            synchronized (OPEN_LUNAR_GAME_SOCKETS) {
                if (OPEN_LUNAR_GAME_SOCKETS.contains(client)) {
                    LAST_LUNAR_GAME_RESPONSE_MS.put(client, System.currentTimeMillis());
                    lunarOnlineConnectingSinceMs = 0L;
                    lunarOnlineConnectingMessage = "";
                }
            }
            reportStatus();
        }
        if (isFullAccessSocket(client)) {
            synchronized (OPEN_FULL_ACCESS_SOCKETS) {
                if (OPEN_FULL_ACCESS_SOCKETS.contains(client)) {
                    LAST_FULL_ACCESS_RESPONSE_MS.put(client, System.currentTimeMillis());
                }
            }
            reportFullAccessStatus();
        }
    }

    /**
     * Records transport liveness from Java-WebSocket without enabling QA diagnostics.
     * Public builds use this minimal hook because FullAccess may be quiet between PONGs.
     */
    public static void onWebSocketImplPong(Object socketImpl) {
        if (socketImpl == null) return;
        try {
            Object client = socketImpl.getClass().getMethod("getWebSocketListener").invoke(socketImpl);
            onWebSocketPong(client);
        } catch (Throwable ignored) {
            // Status telemetry is best-effort and must not affect Lunar's WebSocket thread.
        }
    }

    private static void startRouteWatchdog() {
        if (routeWatchdogStarted) {
            return;
        }
        synchronized (ROUTE_WATCHDOG) {
            if (routeWatchdogStarted) {
                return;
            }
            ROUTE_WATCHDOG.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkRuntimeRouteTimeouts();
                    } catch (Throwable failure) {
                        // Scheduled executors permanently suppress later runs after an uncaught
                        // failure. A transient diagnostic/route failure must not disable recovery.
                        LunarConnectionDiagnostics.record("lunar.route_watchdog_failed",
                                "exception=" + failure.getClass().getSimpleName());
                    }
                }
            }, 5L, 5L, TimeUnit.SECONDS);
            routeWatchdogStarted = true;
        }
    }

    private static void checkRuntimeRouteTimeouts() {
        // The FullAccess socket can stop responding without a close/error callback. Refresh
        // the state here so dependent Locker/color features are disabled after their normal
        // response-freshness window even when no UI is polling the status.
        reportFullAccessStatus();
        if (fullAccessRuntimeRouteEnabled
                && publicOwnershipRefreshRequired()) {
            switchRuntimeRoute(false, "public-account-refresh");
            return;
        }
        if (fullAccessPreferenceEnabled && !fullAccessRuntimeRouteEnabled) {
            checkFullAccessRecovery();
            return;
        }
        if (!fullAccessPreferenceEnabled || !fullAccessRuntimeRouteEnabled) {
            return;
        }

        long now = System.currentTimeMillis();
        String trigger = null;
        long elapsedMs = 0L;
        long firstAuthAttempt = fullAccessAuthFirstAttemptMs;
        if (authResponseTimeoutReached(now, firstAuthAttempt, fullAccessAuthSuccessMs)) {
            trigger = "authenticator-response-timeout";
            elapsedMs = now - firstAuthAttempt;
        }
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            boolean hasFreshAssetsResponse = false;
            String assetsTimeoutTrigger = null;
            long assetsTimeoutElapsedMs = 0L;
            for (Object socket : OPEN_FULL_ACCESS_SOCKETS) {
                Long openedAt = FULL_ACCESS_SOCKET_OPENED_MS.get(socket);
                Long lastResponse = LAST_FULL_ACCESS_RESPONSE_MS.get(socket);
                long reference = lastResponse != null ? lastResponse.longValue()
                        : openedAt != null ? openedAt.longValue() : now;
                long age = Math.max(0L, now - reference);
                if (lastResponse != null && age <= RESPONSE_FRESHNESS_MS) {
                    hasFreshAssetsResponse = true;
                }
                if (age >= ASSETS_RESPONSE_TIMEOUT_MS) {
                    assetsTimeoutTrigger = lastResponse == null ? "assets-first-response-timeout"
                            : "assets-response-stale";
                    assetsTimeoutElapsedMs = age;
                }
            }

            if (!hasFreshAssetsResponse && assetsTimeoutTrigger != null && trigger == null) {
                trigger = assetsTimeoutTrigger;
                elapsedMs = assetsTimeoutElapsedMs;
            } else if (hasFreshAssetsResponse && "authenticator-response-timeout".equals(trigger)) {
                // A slow token refresh must not evict a still-healthy Assets session.
                trigger = null;
                elapsedMs = 0L;
            }

            if (trigger == null && fullAccessAuthSuccessMs > 0L && !hasFreshAssetsResponse) {
                boolean assetsPending = false;
                for (Map.Entry<Object, Long> attempt : FULL_ACCESS_CONNECT_ATTEMPTS_MS.entrySet()) {
                    Object socket = attempt.getKey();
                    Long generation = SOCKET_ROUTE_GENERATIONS.get(socket);
                    if (generation != null && generation.longValue() == runtimeRouteGeneration
                            && isFullAccessSocket(socket)) {
                        assetsPending = true;
                        break;
                    }
                }
                if (missingAssetsTimeoutReached(now, fullAccessAuthSuccessMs,
                        !OPEN_FULL_ACCESS_SOCKETS.isEmpty(), assetsPending)) {
                    trigger = "assets-not-started-after-auth";
                    elapsedMs = Math.max(0L, now - fullAccessAuthSuccessMs);
                }
            }

            if (trigger == null && !hasFreshAssetsResponse) {
                for (Map.Entry<Object, Long> attempt : FULL_ACCESS_CONNECT_ATTEMPTS_MS.entrySet()) {
                    Object socket = attempt.getKey();
                    Long generation = SOCKET_ROUTE_GENERATIONS.get(socket);
                    if (generation == null || generation.longValue() != runtimeRouteGeneration) {
                        continue;
                    }
                    long age = Math.max(0L, now - attempt.getValue().longValue());
                    if (isRelayAuthenticatorSocket(socket)) {
                        continue;
                    }
                    if (age >= ASSETS_RESPONSE_TIMEOUT_MS) {
                        trigger = "assets-connect-timeout";
                        elapsedMs = age;
                        break;
                    }
                }
            }

            // A changed Lunar layout can prevent the reconnect callback from starting either socket.
            // That leaves no Auth/Assets timestamp for the existing timeouts and otherwise strands
            // the route forever after readiness succeeded. Give the handoff its own bounded deadline.
            long routeStarted = fullAccessRouteEnabledSinceMs;
            if (trigger == null && !hasFreshAssetsResponse && fullAccessAuthFirstAttemptMs == 0L
                    && fullAccessAuthSuccessMs == 0L && OPEN_FULL_ACCESS_SOCKETS.isEmpty()
                    && FULL_ACCESS_CONNECT_ATTEMPTS_MS.isEmpty() && routeStarted > 0L
                    && now - routeStarted >= CONNECTING_TIMEOUT_MS) {
                trigger = "route-connect-not-started";
                elapsedMs = now - routeStarted;
            }
        }

        if (trigger == null || !fullAccessPreferenceEnabled || !fullAccessRuntimeRouteEnabled) {
            return;
        }
        LunarConnectionDiagnostics.record("lunar.fullaccess_fallback",
                "trigger=" + trigger + " elapsedMs=" + elapsedMs
                        + " target=official-lunar source=watchdog");
        switchRuntimeRoute(false, "timeout-" + trigger);
    }

    /** Both client flavors need fresh official ownership after switching accounts on the relay. */
    private static boolean publicOwnershipRefreshRequired() {
        try {
            Class<?> guard = Class.forName("me.napim.gomod.features.LunarPublicLoginGuard", false,
                    LunarOnlineStatus.class.getClassLoader());
            return !Boolean.TRUE.equals(guard.getMethod("isOfficialOwnershipReady").invoke(null));
        } catch (ClassNotFoundException outsideWeave) {
            return false;
        } catch (ReflectiveOperationException error) {
            LunarConnectionDiagnostics.recordOnce("lunar.public_ownership_refresh_failed",
                    error.getClass().getSimpleName(), "errorType=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean missingAssetsTimeoutReached(long now, long authSuccessMs,
                                                       boolean assetsOpen, boolean assetsPending) {
        // An open socket with a temporarily quiet stream has already started. Its
        // own last-response deadline above applies, not an old Auth timestamp.
        return authSuccessMs > 0L && !assetsOpen && !assetsPending
                && now - authSuccessMs >= ASSETS_RESPONSE_TIMEOUT_MS;
    }

    private static boolean authResponseTimeoutReached(long now, long firstAttemptMs,
                                                      long authSuccessMs) {
        return firstAttemptMs > 0L && authSuccessMs == 0L
                && now - firstAttemptMs >= AUTH_RESPONSE_TIMEOUT_MS;
    }

    private static boolean recoveryProbeDue(boolean preferenceEnabled, boolean relayRouteEnabled,
                                             long now, long nextProbeMs) {
        return preferenceEnabled && !relayRouteEnabled && nextProbeMs > 0L && now >= nextProbeMs;
    }

    private static boolean isRecoverableFullAccessFailure(String reason) {
        return "authenticator-failure".equals(reason) || "assets-failure".equals(reason)
                || (reason != null && reason.startsWith("timeout-"));
    }

    private static long fullAccessRecoveryDelayMs(int failures) {
        long delay = FULL_ACCESS_RECOVERY_INTERVAL_MS;
        for (int i = 1; i < failures && delay < FULL_ACCESS_RECOVERY_MAX_INTERVAL_MS; i++) {
            delay = Math.min(FULL_ACCESS_RECOVERY_MAX_INTERVAL_MS, delay * 2L);
        }
        return delay;
    }

    /** Rechecked under the bootstrap's existing route lock after a background probe. */
    public static boolean isFullAccessRecoveryDesired(long expectedGeneration) {
        return fullAccessPreferenceEnabled && !fullAccessRuntimeRouteEnabled
                && runtimeRouteGeneration == expectedGeneration;
    }

    private static void checkFullAccessRecovery() {
        long now = System.currentTimeMillis();
        if (!fullAccessPreferenceEnabled || fullAccessRuntimeRouteEnabled) return;
        boolean relayProbeDue = recoveryProbeDue(fullAccessPreferenceEnabled, fullAccessRuntimeRouteEnabled,
                now, nextFullAccessRecoveryProbeMs);
        boolean directProbeDue = LunarApiAvailabilityProbe.directWakeProbeDue(now);
        if (!relayProbeDue && !directProbeDue) return;
        long generation = runtimeRouteGeneration;
        try {
            if (directProbeDue) {
                boolean directReady = LunarApiAvailabilityProbe.probeDirectWake(new URL("https://"
                        + FULL_ACCESS_API_HOST + "/api/lunar/game/metadata"), 5_000);
                if (!isFullAccessRecoveryDesired(generation)) return;
                LunarConnectionDiagnostics.record("lunar.fullaccess_direct_wake",
                        "ready=" + directReady + " " + LunarApiAvailabilityProbe.lastDirectWakeDiagnostic()
                                + " credentials=none payload=omitted");
                if (directReady) {
                    // A private hint requests a bounded relay verification; it is never proof of connection.
                    if (Main.getGlobalChatClient() != null) Main.getGlobalChatClient().requestImmediatePoll();
                    relayProbeDue = true;
                }
            }
            if (!relayProbeDue) return;
            nextFullAccessRecoveryProbeMs = now + FULL_ACCESS_RECOVERY_INTERVAL_MS;
            boolean ready = LunarApiAvailabilityProbe.isReady(new URL("https://" + FULL_ACCESS_RELAY_HOST
                    + "/api/lunar/game/metadata"), 5_000);
            if (!isFullAccessRecoveryDesired(generation)) return;
            nextFullAccessRecoveryProbeMs = System.currentTimeMillis()
                    + LunarApiAvailabilityProbe.nextRetryDelayMs();
            LunarConnectionDiagnostics.record("lunar.fullaccess_recovery_probe",
                    "ready=" + ready + " official-route-retained=" + !ready
                            + " " + LunarApiAvailabilityProbe.lastDiagnostic()
                            + " retryDelayMs=" + LunarApiAvailabilityProbe.nextRetryDelayMs() + " payload=omitted");
            if (!ready) return;
            Class<?> bootstrap = Class.forName("me.napim.gomod.weave.LunarRelayWeaveBootstrap",
                    true, LunarOnlineStatus.class.getClassLoader());
            bootstrap.getMethod("retryRuntimeFullAccessRoute", long.class).invoke(null, generation);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar.fullaccess_recovery_failed",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    private static void clearTrackedRouteSockets() {
        fullAccessAuthSuccessMs = 0L;
        fullAccessAuthFirstAttemptMs = 0L;
        synchronized (OPEN_LUNAR_GAME_SOCKETS) {
            OPEN_LUNAR_GAME_SOCKETS.clear();
            LAST_LUNAR_GAME_RESPONSE_MS.clear();
        }
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            OPEN_FULL_ACCESS_SOCKETS.clear();
            fullAccessRenderResponseMs = 0L;
            FULL_ACCESS_CONNECT_ATTEMPTS_MS.clear();
            FULL_ACCESS_SOCKET_OPENED_MS.clear();
            LAST_FULL_ACCESS_RESPONSE_MS.clear();
            SOCKET_ROUTE_GENERATIONS.clear();
        }
        synchronized (RELAY_AUTH_SUCCESS_SOCKETS) {
            RELAY_AUTH_SUCCESS_SOCKETS.clear();
        }
    }

    public static boolean isConnected() {
        return getLunarOnlineState() == ConnectionState.CONNECTED;
    }

    /** Tracks fresh Lunar socket activity separately from route handoff progress. */
    public static ConnectionState getLunarOnlineState() {
        return reportOnlineStatus();
    }

    /** Adds the current Lunar service states to the authenticated relay usage poll. */
    public static void attachUsageStatus(JsonObject payload) {
        if (payload == null) return;
        if (!GomodFixes.isWeaveRuntime()) {
            payload.addProperty("lunarOnlineState", "unavailable");
            payload.addProperty("lunarFullAccessState", "unavailable");
            return;
        }
        payload.addProperty("lunarOnlineState", getLunarOnlineState().name().toLowerCase(java.util.Locale.ROOT));
        payload.addProperty("lunarFullAccessState", isFullAccessPreferenceEnabled()
                ? getFullAccessState().name().toLowerCase(java.util.Locale.ROOT)
                : "disabled");
        payload.addProperty("lunarFullAccessMessage", getFullAccessStatusMessage());
        payload.add("lunarConnectionDiagnostics", getFullAccessConnectionDiagnostics());
        try {
            Class<?> guard = Class.forName("me.napim.gomod.features.LunarPublicLoginGuard", false,
                    LunarOnlineStatus.class.getClassLoader());
            guard.getMethod("attachUsageLockerSnapshot", JsonObject.class).invoke(null, payload);
        } catch (ClassNotFoundException unavailableOutsideWeave) {
            // The Locker response observation is Lunar Weave-only.
        } catch (Throwable ignored) {
            // Cosmetic inventory telemetry must never interrupt the normal relay poll.
        }
    }

    /** Safe automatic diagnostics: no tokens, URLs, file paths, inventories or network/file work. */
    public static JsonObject getFullAccessConnectionDiagnostics() {
        JsonObject result = new JsonObject();
        result.addProperty("schema", 1);
        result.addProperty("connectionRevision", 1);
        result.addProperty("fullAccessEnabled", fullAccessPreferenceEnabled);
        result.addProperty("routeEnabled", fullAccessRuntimeRouteEnabled);
        long now = System.currentTimeMillis();
        result.addProperty("authAttemptAgeMs", fullAccessAuthFirstAttemptMs > 0L
                ? Math.max(0L, now - fullAccessAuthFirstAttemptMs) : -1L);
        result.addProperty("authSuccessAgeMs", fullAccessAuthSuccessMs > 0L
                ? Math.max(0L, now - fullAccessAuthSuccessMs) : -1L);
        long newestResponse = 0L;
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            result.addProperty("openGameSockets", OPEN_FULL_ACCESS_SOCKETS.size());
            result.addProperty("pendingConnectAttempts", FULL_ACCESS_CONNECT_ATTEMPTS_MS.size());
            for (Object socket : OPEN_FULL_ACCESS_SOCKETS) {
                Long response = LAST_FULL_ACCESS_RESPONSE_MS.get(socket);
                if (response != null) newestResponse = Math.max(newestResponse, response.longValue());
            }
        }
        result.addProperty("lastResponseAgeMs", newestResponse > 0L ? Math.max(0L, now - newestResponse) : -1L);
        try {
            Class<?> bootstrap = Class.forName("me.napim.gomod.weave.LunarRelayWeaveBootstrap", false,
                    LunarOnlineStatus.class.getClassLoader());
            Object nativeSnapshot = bootstrap.getMethod("getConnectionUsageSnapshot").invoke(null);
            if (nativeSnapshot instanceof JsonObject) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry : ((JsonObject) nativeSnapshot).entrySet())
                    result.add(entry.getKey(), entry.getValue());
            }
        } catch (Throwable unavailableOutsideWeave) {
            // Common Forge code and older bootstrap classes remain usable.
        }
        return result;
    }

    /** Human-readable explanation shown while Lunar Online is in its route/connection handoff. */
    public static String getLunarOnlineStatusMessage() {
        String message = lunarOnlineConnectingMessage;
        return message == null || message.trim().isEmpty()
                ? "Waiting for Lunar to confirm the game connection."
                : message;
    }

    /** FullAccess is online only while its own relay socket has recent server activity. */
    public static boolean isFullAccessConnected() {
        return getFullAccessState() == ConnectionState.CONNECTED;
    }

    /** Side-effect-free connection read for rendering. Freshness still expires without a socket callback. */
    public static boolean isFullAccessConnectedForRender() {
        long response = fullAccessRenderResponseMs;
        return response != 0L && System.currentTimeMillis() - response <= RESPONSE_FRESHNESS_MS;
    }

    /** Tracks relay connection progress separately from Lunar's official online socket. */
    public static ConnectionState getFullAccessState() {
        return reportFullAccessStatus();
    }

    /**
     * A delayed bootstrap reconnect must not interrupt a healthy or genuinely pending
     * FullAccess connection for the current route. A socket that has stayed open beyond the
     * existing Auth/Assets response windows is stale, though, and must not block a new mapping
     * or reconnect forever.
     */
    public static boolean hasCurrentFullAccessConnectionInProgressOrOpen() {
        if (!fullAccessPreferenceEnabled || !fullAccessRuntimeRouteEnabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        long generation = runtimeRouteGeneration;
        // Auth success means Lunar may still be starting its Assets socket. Bound that grace
        // period by the same timeout used by the route watchdog.
        if (isWithinConnectionTimeout(now, fullAccessAuthSuccessMs, ASSETS_RESPONSE_TIMEOUT_MS)) {
            return true;
        }
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            for (Object socket : OPEN_FULL_ACCESS_SOCKETS) {
                Long socketGeneration = SOCKET_ROUTE_GENERATIONS.get(socket);
                if (socketGeneration == null || socketGeneration.longValue() != generation
                        || !isFullAccessSocket(socket)) {
                    continue;
                }
                Long lastResponse = LAST_FULL_ACCESS_RESPONSE_MS.get(socket);
                Long openedAt = FULL_ACCESS_SOCKET_OPENED_MS.get(socket);
                long lastActivity = lastResponse != null ? lastResponse.longValue()
                        : openedAt != null ? openedAt.longValue() : 0L;
                if (isWithinConnectionTimeout(now, lastActivity, ASSETS_RESPONSE_TIMEOUT_MS)) return true;
            }
            for (Map.Entry<Object, Long> attempt : FULL_ACCESS_CONNECT_ATTEMPTS_MS.entrySet()) {
                Object socket = attempt.getKey();
                Long socketGeneration = SOCKET_ROUTE_GENERATIONS.get(socket);
                if (socketGeneration == null || socketGeneration.longValue() != generation) continue;
                long timeoutMs = isRelayAuthenticatorSocket(socket)
                        ? AUTH_RESPONSE_TIMEOUT_MS : ASSETS_RESPONSE_TIMEOUT_MS;
                if ((isRelayAuthenticatorSocket(socket) || isFullAccessSocket(socket))
                        && isWithinConnectionTimeout(now, attempt.getValue().longValue(), timeoutMs)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWithinConnectionTimeout(long now, long timestamp, long timeoutMs) {
        return timestamp > 0L && Math.max(0L, now - timestamp) <= timeoutMs;
    }

    /** Route identity, not proof of a healthy socket. Used to isolate private wheel storage. */
    public static boolean isFullAccessRouteActive() {
        return fullAccessPreferenceEnabled && fullAccessRuntimeRouteEnabled;
    }

    /** User preference gate for local FullAccess state restoration during relay route handoffs. */
    public static boolean isFullAccessPreferenceEnabled() {
        return fullAccessPreferenceEnabled;
    }

    public static String getFullAccessStatusMessage() {
        if (!fullAccessPreferenceEnabled) return "Lunar FullAccess is disabled.";
        if (fullAccessPreferenceEnabled && !fullAccessRuntimeRouteEnabled) {
            String ownershipWait = fullAccessWaitMessage(
                    "me.napim.gomod.features.LunarPublicLoginGuard", "getOfficialOwnershipWaitMessage");
            if (!ownershipWait.isEmpty()) return ownershipWait;
            long retrySeconds = Math.max(0L, (nextFullAccessRecoveryProbeMs - System.currentTimeMillis() + 999L) / 1_000L);
            return (LunarApiAvailabilityProbe.isHostRateLimited()
                    ? "The API host is limiting wake requests."
                    : "Waiting for the FullAccess API to become ready.")
                    + " Automatic retry in " + retrySeconds + "s; official Lunar stays available.";
        }
        long now = System.currentTimeMillis();
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            for (Object socket : OPEN_FULL_ACCESS_SOCKETS) {
                Long response = LAST_FULL_ACCESS_RESPONSE_MS.get(socket);
                if (response != null && now - response.longValue() <= RESPONSE_FRESHNESS_MS) {
                    return "Connected to Lunar FullAccess; the game service is responding.";
                }
            }
            if (!OPEN_FULL_ACCESS_SOCKETS.isEmpty()) {
                return "FullAccess game connection is open; waiting for server data.";
            }
        }
        if (fullAccessAuthSuccessMs > 0L) {
            return "FullAccess authentication succeeded; waiting for the game connection.";
        }
        if (fullAccessAuthFirstAttemptMs > 0L) {
            return "Connecting to the FullAccess authentication service; waiting for its response.";
        }
        String nativeWait = fullAccessWaitMessage(
                "me.napim.gomod.weave.LunarRelayWeaveBootstrap", "getFullAccessReconnectWaitMessage");
        String nativeWaitCode = fullAccessWaitMessage(
                "me.napim.gomod.weave.LunarRelayWeaveBootstrap", "getFullAccessReconnectWaitCode");
        if ("verified-asset-manager-not-loaded".equals(nativeWaitCode)
                || "waiting-for-compatibility-mapping".equals(nativeWaitCode)) {
            String compatibilityWait = fullAccessWaitMessage(
                    "me.napim.gomod.weave.LunarRemoteCompatibility", "getCompatibilityWaitMessage");
            if (!compatibilityWait.isEmpty()) return compatibilityWait;
        }
        return nativeWait.isEmpty() ? "Starting the Lunar FullAccess connection." : nativeWait;
    }

    private static String fullAccessWaitMessage(String owner, String method) {
        try {
            Object result = Class.forName(owner, false, LunarOnlineStatus.class.getClassLoader())
                    .getMethod(method).invoke(null);
            return result instanceof String ? (String) result : "";
        } catch (ReflectiveOperationException unavailable) {
            return "";
        }
    }

    /** Display the current wait stage without implying a FullAccess socket is already connecting. */
    public static String getFullAccessProgressLabel() {
        return fullAccessPreferenceEnabled && !fullAccessRuntimeRouteEnabled
                ? (LunarApiAvailabilityProbe.isHostRateLimited() ? "Waiting (API busy)..." : "Waiting for API...")
                : "Connecting...";
    }

    private static boolean reportStatus() {
        return reportOnlineStatus() == ConnectionState.CONNECTED;
    }

    private static ConnectionState reportOnlineStatus() {
        boolean freshGameResponse = false;
        int openSockets = 0;
        long newestResponseAge = Long.MAX_VALUE;
        synchronized (OPEN_LUNAR_GAME_SOCKETS) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<Object, Long>> responses = LAST_LUNAR_GAME_RESPONSE_MS.entrySet().iterator();
            while (responses.hasNext()) {
                Map.Entry<Object, Long> response = responses.next();
                if (!OPEN_LUNAR_GAME_SOCKETS.contains(response.getKey())) {
                    responses.remove();
                    continue;
                }
                openSockets++;
                long age = Math.max(0L, now - response.getValue());
                newestResponseAge = Math.min(newestResponseAge, age);
                if (age <= RESPONSE_FRESHNESS_MS) {
                    freshGameResponse = true;
                }
            }
            // A connected socket with no server frame is still not authenticated/online.
            for (Object socket : OPEN_LUNAR_GAME_SOCKETS) {
                if (!LAST_LUNAR_GAME_RESPONSE_MS.containsKey(socket)) {
                    openSockets++;
                }
            }
        }
        long now = System.currentTimeMillis();
        long connectingSince = lunarOnlineConnectingSinceMs;
        long connectingTimeout = fullAccessRuntimeRouteEnabled
                ? CONNECTING_TIMEOUT_MS : OFFICIAL_CONNECTING_TIMEOUT_MS;
        boolean connecting = connectingSince > 0L && now - connectingSince <= connectingTimeout;
        ConnectionState state = freshGameResponse ? ConnectionState.CONNECTED
                : connecting ? ConnectionState.CONNECTING : ConnectionState.DISCONNECTED;
        announceConnectionTransition(state, false);
        String reason = freshGameResponse ? "responsive-official-or-relayed-game-socket"
                : connecting ? (fullAccessRuntimeRouteEnabled
                        ? "fullaccess-route-connection-pending" : "official-route-connection-pending")
                : openSockets == 0 ? "no-open-official-presence-socket"
                : newestResponseAge == Long.MAX_VALUE ? "awaiting-first-official-game-frame"
                    : "official-game-socket-inactive";
        LunarConnectionDiagnostics.recordLunarStatus(state.name().toLowerCase(java.util.Locale.ROOT),
                reason, openSockets,
                newestResponseAge);
        return state;
    }

    private static void markLunarOnlineConnecting(String message) {
        lunarOnlineConnectingSinceMs = System.currentTimeMillis();
        lunarOnlineConnectingMessage = message;
    }

    private static String connectionAttemptMessage() {
        return fullAccessRuntimeRouteEnabled
                ? "Connecting through Lunar FullAccess; waiting for Lunar game presence."
                : "Connecting to Lunar's official services.";
    }

    private static String routeTransitionMessage(boolean enabled, String reason) {
        if (enabled) {
            return "Connecting to Lunar's services through the gomod FullAccess relay.";
        }
        if ("user-toggle".equals(reason)) {
            return "FullAccess disabled; reconnecting to Lunar's official services.";
        }
        return "FullAccess unavailable; reconnecting to Lunar's official services.";
    }

    private static void refreshLockerAvailability() {
        try {
            Class<?> locker = Class.forName("me.napim.gomod.features.GomodLunarPlusLocker",
                    true, LunarOnlineStatus.class.getClassLoader());
            locker.getMethod("onFullAccessPreferenceChanged").invoke(null);
        } catch (ClassNotFoundException unavailableOutsideWeave) {
            // This Locker extension is available only in the native Weave build.
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar.fullaccess_locker_refresh_failed",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isLunarOnlineSocket(Object client) {
        URI uri = getLunarSocketUri(client);
        if (uri == null) {
            return false;
        }
        boolean directOfficialSocket = "/game".equals(uri.getPath())
                && !"authenticator.lunarclientprod.com".equalsIgnoreCase(uri.getHost());
        // In the internal QA build, Lunar's authenticated Assets socket is routed through
        // Gomod's relay. The Lunar API opens the official RPC upstream before accepting it,
        // so a fresh server frame on this exact socket is the proxied presence signal.
        return directOfficialSocket || isFullAccessSocket(client);
    }

    private static boolean isAuthenticatorSocket(Object client) {
        URI uri = getLunarSocketUri(client);
        if (uri == null) return false;
        return (FULL_ACCESS_RELAY_HOST.equalsIgnoreCase(uri.getHost())
                && "/ws".equals(uri.getPath()))
                || ("authenticator.lunarclientprod.com".equalsIgnoreCase(uri.getHost())
                && "/game".equals(uri.getPath()));
    }

    private static boolean isRelayAuthenticatorSocket(Object client) {
        URI uri = getLunarSocketUri(client);
        return uri != null && FULL_ACCESS_RELAY_HOST.equalsIgnoreCase(uri.getHost())
                && "/ws".equals(uri.getPath());
    }

    private static boolean isFullAccessSocket(Object client) {
        URI uri = getLunarSocketUri(client);
        return uri != null && "/ws/game".equals(uri.getPath())
                && FULL_ACCESS_RELAY_HOST.equalsIgnoreCase(uri.getHost());
    }

    private static boolean hasAuthSuccessFrame(Object client, Object payload) {
        byte[] bytes;
        if (payload instanceof byte[]) {
            bytes = (byte[]) payload;
        } else if (payload instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) payload).duplicate();
            bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
        } else if (payload instanceof String) {
            bytes = ((String) payload).getBytes(StandardCharsets.UTF_8);
        } else {
            return false;
        }
        if (bytes.length == 0) return false;
        try {
            ClassLoader loader = client.getClass().getClassLoader();
            Class<?> responseClass = Class.forName(
                    "com.lunarclient.authenticator.v1.ClientboundWebSocketMessage", false, loader);
            Method parseFrom = responseClass.getMethod("parseFrom", byte[].class);
            Object response = parseFrom.invoke(null, new Object[]{bytes});
            Method hasAuthSuccess = responseClass.getMethod("hasAuthSuccess");
            return Boolean.TRUE.equals(hasAuthSuccess.invoke(response));
        } catch (Throwable ignored) {
            // Do not treat an encryption challenge or an unparseable frame as authenticated.
            return false;
        }
    }

    private static String endpointLabel(Object client) {
        URI uri = getLunarSocketUri(client);
        if (uri == null) return "endpoint=unknown";
        return "host=" + (uri.getHost() == null ? "unknown" : uri.getHost())
                + " path=" + (uri.getPath() == null ? "/" : uri.getPath());
    }

    private static URI getLunarSocketUri(Object client) {
        if (client == null || !client.getClass().getName().startsWith("com.moonsworth.lunar.")) {
            return null;
        }
        try {
            Object value = client.getClass().getMethod("getURI").invoke(client);
            return value instanceof URI ? (URI) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ConnectionState reportFullAccessStatus() {
        int activeSockets;
        long newestResponseAge;
        boolean connected;
        boolean waitingForApi;
        boolean connecting;
        ConnectionState state;
        synchronized (OPEN_FULL_ACCESS_SOCKETS) {
            long now = System.currentTimeMillis();
            activeSockets = 0;
            newestResponseAge = Long.MAX_VALUE;
            connected = false;
            waitingForApi = fullAccessPreferenceEnabled && !fullAccessRuntimeRouteEnabled
                    && nextFullAccessRecoveryProbeMs > 0L;
            connecting = waitingForApi || (fullAccessAuthSuccessMs > 0
                    && now - fullAccessAuthSuccessMs <= CONNECTING_TIMEOUT_MS)
                    || (fullAccessPreferenceEnabled && fullAccessRuntimeRouteEnabled
                    && fullAccessRouteEnabledSinceMs > 0L
                    && now - fullAccessRouteEnabledSinceMs <= CONNECTING_TIMEOUT_MS);

            Iterator<Map.Entry<Object, Long>> responses = LAST_FULL_ACCESS_RESPONSE_MS.entrySet().iterator();
            while (responses.hasNext()) {
                Map.Entry<Object, Long> response = responses.next();
                if (!OPEN_FULL_ACCESS_SOCKETS.contains(response.getKey())) {
                    responses.remove();
                    continue;
                }
                activeSockets++;
                long age = Math.max(0L, now - response.getValue());
                newestResponseAge = Math.min(newestResponseAge, age);
                if (age <= RESPONSE_FRESHNESS_MS) {
                    connected = true;
                }
            }

            for (Object socket : OPEN_FULL_ACCESS_SOCKETS) {
                if (!LAST_FULL_ACCESS_RESPONSE_MS.containsKey(socket)) {
                    activeSockets++;
                    Long openedAt = FULL_ACCESS_SOCKET_OPENED_MS.get(socket);
                    if (openedAt != null && now - openedAt <= CONNECTING_TIMEOUT_MS) {
                        connecting = true;
                    }
                }
            }

            Iterator<Map.Entry<Object, Long>> attempts = FULL_ACCESS_CONNECT_ATTEMPTS_MS.entrySet().iterator();
            while (attempts.hasNext()) {
                Map.Entry<Object, Long> attempt = attempts.next();
                if (OPEN_FULL_ACCESS_SOCKETS.contains(attempt.getKey())) {
                    continue;
                }
                if (now - attempt.getValue() <= CONNECTING_TIMEOUT_MS) {
                    connecting = true;
                } else {
                    attempts.remove();
                }
            }

            state = connected ? ConnectionState.CONNECTED
                    : connecting ? ConnectionState.CONNECTING : ConnectionState.DISCONNECTED;
            fullAccessRenderResponseMs = connected ? now - newestResponseAge : 0L;
        }

        // Never hold the socket-set monitor while announcing: the notification can enqueue work
        // on Minecraft's client thread, whose queue may be waiting for this same status check.
        announceConnectionTransition(state, true);
        String reason = connected ? "responsive-relay-game-socket"
                : waitingForApi ? "api-readiness-pending-official-route-retained"
                : connecting ? "relay-connection-pending"
                : "no-active-relay-fullaccess-socket";
        LunarConnectionDiagnostics.recordFullAccessStatus(state.name().toLowerCase(), reason,
                activeSockets, newestResponseAge);
        return state;
    }

    private static void announceConnectionTransition(ConnectionState state, boolean fullAccess) {
        if (fullAccess) updateFullAccessAppearanceAvailability(state == ConnectionState.CONNECTED);
        String service;
        boolean connected;
        synchronized (LunarOnlineStatus.class) {
            ConnectionState previous = fullAccess
                    ? lastAnnouncedFullAccessState : lastAnnouncedLunarOnlineState;
            if (previous == state) {
                return;
            }
            if (fullAccess) {
                lastAnnouncedFullAccessState = state;
            } else {
                lastAnnouncedLunarOnlineState = state;
            }
            if (state == ConnectionState.CONNECTING || previous == null) {
                return;
            }
            service = fullAccess ? "Lunar FullAccess" : "Lunar Online";
            connected = state == ConnectionState.CONNECTED;
        }

        String compatibilityWait = fullAccess && !connected
                ? fullAccessWaitMessage("me.napim.gomod.weave.LunarRemoteCompatibility", "getCompatibilityWaitMessage")
                : "";
        String status = !compatibilityWait.isEmpty()
                ? "Lunar FullAccess: waiting for a signed compatibility update."
                : service + (connected ? ": connected." : ": disconnected.");

        // Main.sendChatMessage may wait for Minecraft's task queue; never call it while holding
        // the class monitor, since the client thread can query status while holding that queue.
        Main.sendChatMessage(new ChatComponentText(Main.CHAT_PREFIX
                + (connected ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + status));
    }

    /** Keeps cached FullAccess-only colors aligned with actual socket availability, not the saved toggle. */
    private static void updateFullAccessAppearanceAvailability(boolean connected) {
        int next = connected ? 1 : 0;
        synchronized (LunarOnlineStatus.class) {
            if (lastFullAccessAppearanceAvailability == next) return;
            lastFullAccessAppearanceAvailability = next;
        }
        try {
            Class<?> locker = Class.forName("me.napim.gomod.features.GomodLunarPlusLocker",
                    true, LunarOnlineStatus.class.getClassLoader());
            locker.getMethod("onFullAccessConnectionChanged", Boolean.TYPE).invoke(null, connected);
        } catch (ClassNotFoundException unavailableOutsideWeave) {
            // The native Locker/color integration exists only in the Weave build.
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("lunar.fullaccess_appearance_refresh_failed",
                    "connected=" + connected + " exception=" + error.getClass().getSimpleName());
        }
    }
}
