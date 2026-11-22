package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native display-profile subscriptions for same-match, render-only spectator bodies. */
public final class LunarSpectatorCosmetics {
    private static final String SUB = "com.lunarclient.websocket.subscription.v1.";
    private static final String PUSH = "com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2";
    private static final URI OFFICIAL_PROFILE_URI = URI.create("wss://websocket.lunarclientprod.com/game");
    private static final Map<UUID, Long> DUE = new LinkedHashMap<UUID, Long>();
    private static final Set<UUID> SUBSCRIBED = new HashSet<UUID>();
    private static final ClassValue<Optional<Bridge>> BRIDGES = new ClassValue<Optional<Bridge>>() {
        @Override protected Optional<Bridge> computeValue(Class<?> type) {
            try { return Optional.of(new Bridge(type)); }
            catch (Throwable error) {
                LunarConnectionDiagnostics.recordOnceAfterStart("spectator.cosmetics_unsupported", type.getName(),
                        "errorType=" + error.getClass().getSimpleName());
                return Optional.empty();
            }
        }
    };
    private static Object socket, world, local, connection;
    private static String account;
    private static Bridge bridge;
    private static long generation, request, pending, pendingUntil, nextSendAt;
    static final int PEER_LOGO_BATCH_SIZE = 50;
    static final long PEER_LOGO_BATCH_INTERVAL_MS = 300L;
    static final long PEER_LOGO_RETRY_MS = 300000L;
    static final long PEER_LOGO_FAILURE_RETRY_MS = 5000L;
    private static final long PEER_LOGO_UNSUBSCRIBE_INTERVAL_MS = 1000L;
    private static final long PEER_LOGO_REQUEST_TIMEOUT_MS = 12000L;
    private static final Map<UUID, Long> PEER_LOGO_RETRY_AT = new LinkedHashMap<UUID, Long>();
    private static final Set<UUID> PEER_LOGO_SUBSCRIBED = new LinkedHashSet<UUID>();
    private static final Set<UUID> PEER_LOGO_PENDING_UNSUB = new LinkedHashSet<UUID>();
    private static Object peerLogoSocket, peerLogoWorld, peerLogoLocal, peerLogoConnection;
    private static String peerLogoAccount;
    private static long peerLogoNextRequestAt, peerLogoNextUnsubscribeAt;
    private static volatile Object officialProfileSocket;
    private static Object heartbeatSocket;
    private static long nextHeartbeat;
    private static long nextOfficialSelfRequestAt;

    private LunarSpectatorCosmetics() {}

    /** Called only from Lunar's Assets reconnect routine. Keep the already authenticated official socket. */
    public static void closeForRouteChange(Object socket) throws ReflectiveOperationException, InterruptedException {
        if (socket == null) return;
        boolean fullAccessRoute = LunarOnlineStatus.isFullAccessRouteActive();
        boolean activeCosmeticSocket = socket == LunarColorRuntime.cosmeticSocket();
        boolean socketOpen = open(socket);
        URI endpoint = null;
        boolean nativeBridgeAvailable = false;
        if (fullAccessRoute && activeCosmeticSocket && socketOpen) {
            Object value = socket.getClass().getMethod("getURI").invoke(socket);
            if (value instanceof URI) endpoint = (URI) value;
            nativeBridgeAvailable = BRIDGES.get(socket.getClass()).isPresent();
        }
        if (shouldRetainOfficialProfileSocket(fullAccessRoute, activeCosmeticSocket, socketOpen,
                endpoint, nativeBridgeAvailable)) {
            officialProfileSocket = socket;
            LunarConnectionDiagnostics.record("lunar_peer_logo_profile.handoff", "officialSocketRetained=true");
            return;
        }
        try {
            socket.getClass().getMethod("closeBlocking").invoke(socket);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof InterruptedException) throw (InterruptedException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    /** Keep the native Lunar profile feed only for the active, authenticated Lunar route and a usable bridge. */
    static boolean shouldRetainOfficialProfileSocket(boolean fullAccessRoute, boolean activeCosmeticSocket,
            boolean socketOpen, URI endpoint, boolean nativeBridgeAvailable) {
        return fullAccessRoute && activeCosmeticSocket && socketOpen
                && OFFICIAL_PROFILE_URI.equals(endpoint) && nativeBridgeAvailable;
    }

    /** Bounded explicit lookup for an in-world /disguisefull target, using the native display RPC. */
    public static void requestDisguiseProfile(UUID target, long deadline, BooleanSupplier stillCurrent) {
        requestProfile(target, deadline, stillCurrent, LunarColorRuntime.cosmeticSocket(), false);
    }

    private static void requestProfile(UUID target, long deadline, BooleanSupplier stillCurrent,
            Object profileSource, boolean peerLogo) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || target == null || System.currentTimeMillis() >= deadline || !stillCurrent.getAsBoolean()) return;
        final Object expectedWorld = mc.theWorld, expectedLocal = mc.thePlayer, expectedConnection = mc.getNetHandler();
        final Object expectedSocket = LunarColorRuntime.cosmeticSocket();
        final String expectedAccount = mc.getSession() == null ? null : mc.getSession().getPlayerID();
        if (expectedWorld == null || expectedLocal == null || expectedConnection == null
                || expectedAccount == null || expectedSocket == null || !open(expectedSocket)) return;
        if (profileSource == null || !open(profileSource)) return;
        Optional<Bridge> supported = BRIDGES.get(profileSource.getClass());
        if (!supported.isPresent()) return;
        final Bridge nativeBridge = supported.get();
        BooleanSupplier currentRequest = () -> System.currentTimeMillis() < deadline && stillCurrent.getAsBoolean()
                && mc.theWorld == expectedWorld && mc.thePlayer == expectedLocal && mc.getNetHandler() == expectedConnection
                && mc.getSession() != null && expectedAccount.equals(mc.getSession().getPlayerID())
                && LunarColorRuntime.cosmeticSocket() == expectedSocket && open(expectedSocket);
        mc.addScheduledTask(() -> {
            if (!currentRequest.getAsBoolean() || !isOnline(mc, target)) return;
            try {
                Object service = nativeBridge.service.get(profileSource);
                if (service == null) return;
                Object payload = nativeBridge.payload(nativeBridge.subscribeRequest, Collections.singleton(target));
                Object callback = Proxy.newProxyInstance(nativeBridge.loader, new Class<?>[]{nativeBridge.callback}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (!"run".equals(method.getName())) return null;
                    final Object response = args == null || args.length != 1 ? null : args[0];
                    mc.addScheduledTask(() -> {
                        if (!currentRequest.getAsBoolean() || !isOnline(mc, target)) return;
                        try {
                            if (response == null || !nativeBridge.response.isInstance(response)) return;
                            List<?> profiles = (List<?>) nativeBridge.pushes.invoke(response);
                            if (profiles.size() > 1) throw new IllegalStateException("Unexpected requested profile count");
                            for (Object profile : profiles) {
                                if (!target.equals(nativeBridge.playerId(profile)))
                                    throw new IllegalStateException("Requested profile UUID mismatch");
                                if (peerLogo && GomodLogoColorSync.getNativeTabLogoWidth(target) != 0) continue;
                                nativeBridge.dispatch.invoke(expectedSocket, profile);
                            }
                            LunarConnectionDiagnostics.record(peerLogo ? "lunar_peer_logo_profile.response" : "disguise.full_lunar_profile_request",
                                    "result=" + (profiles.isEmpty() ? "empty" : "received") + " nativeDispatcher=true payload=omitted");
                        } catch (Throwable error) { event("disguise_profile_failed", error); }
                    });
                    return null;
                });
                nativeBridge.subscribe.invoke(service, null, payload, callback);
                if (!peerLogo) LunarConnectionDiagnostics.record("disguise.full_lunar_profile_request", "result=requested payload=omitted");
                // This target has a real native body, whose normal world lifecycle owns its subscription.
            } catch (Throwable error) { event("disguise_profile_failed", error); }
        });
    }

    /**
     * Select up to {@code maxBatchSize} missing peer UUIDs without starvation:
     * never-queried players always go first (prioritizing standard v4 account UUIDs ahead of
     * non-v4 NPC entries when a lobby spans multiple batches), followed by the oldest expired
     * retries if the batch still has room.
     */
    static Set<UUID> selectPeerLogoBatch(Collection<UUID> missingCandidates, Map<UUID, Long> retryAtMap,
            long now, int maxBatchSize) {
        Set<UUID> batch = new LinkedHashSet<UUID>();
        if (missingCandidates == null || missingCandidates.isEmpty() || maxBatchSize <= 0) return batch;
        List<UUID> unqueriedV4 = new ArrayList<UUID>();
        List<UUID> unqueriedOther = new ArrayList<UUID>();
        List<Map.Entry<UUID, Long>> expiredRetries = null;
        for (UUID id : missingCandidates) {
            if (id == null) continue;
            Long retryAt = retryAtMap.get(id);
            if (retryAt == null) {
                if (id.version() == 4) unqueriedV4.add(id);
                else unqueriedOther.add(id);
            } else if (retryAt.longValue() <= now) {
                if (expiredRetries == null) expiredRetries = new ArrayList<Map.Entry<UUID, Long>>();
                expiredRetries.add(new AbstractMap.SimpleImmutableEntry<UUID, Long>(id, retryAt));
            }
        }
        for (UUID id : unqueriedV4) {
            if (batch.size() >= maxBatchSize) break;
            batch.add(id);
        }
        for (UUID id : unqueriedOther) {
            if (batch.size() >= maxBatchSize) break;
            batch.add(id);
        }
        if (batch.size() < maxBatchSize && expiredRetries != null) {
            expiredRetries.sort(Comparator.comparingLong(entry -> entry.getValue().longValue()));
            for (Map.Entry<UUID, Long> entry : expiredRetries) {
                if (batch.size() >= maxBatchSize) break;
                batch.add(entry.getKey());
            }
        }
        return batch;
    }

    private static void requestPeerLogoBatch(Set<UUID> targets, long deadline, BooleanSupplier stillCurrent,
            Object profileSource) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || targets == null || targets.isEmpty()
                || System.currentTimeMillis() >= deadline || !stillCurrent.getAsBoolean()) return;
        final Object expectedWorld = mc.theWorld, expectedLocal = mc.thePlayer, expectedConnection = mc.getNetHandler();
        final Object expectedSocket = LunarColorRuntime.cosmeticSocket();
        final String expectedAccount = mc.getSession() == null ? null : mc.getSession().getPlayerID();
        if (expectedWorld == null || expectedLocal == null || expectedConnection == null
                || expectedAccount == null || expectedSocket == null || !open(expectedSocket)) return;
        if (profileSource == null || !open(profileSource)) return;
        Optional<Bridge> supported = BRIDGES.get(profileSource.getClass());
        if (!supported.isPresent()) return;
        final Bridge nativeBridge = supported.get();
        final Set<UUID> batch = new LinkedHashSet<UUID>(targets);
        BooleanSupplier currentRequest = () -> System.currentTimeMillis() < deadline && stillCurrent.getAsBoolean()
                && mc.theWorld == expectedWorld && mc.thePlayer == expectedLocal && mc.getNetHandler() == expectedConnection
                && mc.getSession() != null && expectedAccount.equals(mc.getSession().getPlayerID())
                && LunarColorRuntime.cosmeticSocket() == expectedSocket && open(expectedSocket);
        mc.addScheduledTask(() -> {
            if (!currentRequest.getAsBoolean()) return;
            final Set<UUID> onlineBatch = new LinkedHashSet<UUID>();
            for (UUID id : batch) {
                if (isOnline(mc, id) && GomodLogoColorSync.getNativeTabLogoWidth(id) == 0) onlineBatch.add(id);
            }
            if (onlineBatch.isEmpty()) return;
            try {
                Object service = nativeBridge.service.get(profileSource);
                if (service == null) return;
                Object payload = nativeBridge.payload(nativeBridge.subscribeRequest, onlineBatch);
                Object callback = Proxy.newProxyInstance(nativeBridge.loader, new Class<?>[]{nativeBridge.callback}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (!"run".equals(method.getName())) return null;
                    final Object response = args == null || args.length != 1 ? null : args[0];
                    mc.addScheduledTask(() -> {
                        if (!currentRequest.getAsBoolean()) return;
                        try {
                            if (response == null || !nativeBridge.response.isInstance(response)) return;
                            List<?> profiles = (List<?>) nativeBridge.pushes.invoke(response);
                            if (profiles.size() > onlineBatch.size())
                                throw new IllegalStateException("Unexpected requested profile count");
                            Set<UUID> seen = new HashSet<UUID>();
                            int dispatched = 0;
                            for (Object profile : profiles) {
                                UUID id = nativeBridge.playerId(profile);
                                if (!onlineBatch.contains(id) || !seen.add(id))
                                    throw new IllegalStateException("Requested profile UUID mismatch");
                                if (!isOnline(mc, id) || GomodLogoColorSync.getNativeTabLogoWidth(id) != 0) continue;
                                nativeBridge.dispatch.invoke(expectedSocket, profile);
                                dispatched++;
                            }
                            LunarConnectionDiagnostics.record("lunar_peer_logo_profile.response",
                                    "requested=" + onlineBatch.size() + " received=" + dispatched
                                            + " result=" + (dispatched == 0 ? "empty" : "received")
                                            + " nativeDispatcher=true payload=omitted");
                        } catch (Throwable error) {
                            schedulePeerLogoFailureRetry(onlineBatch);
                            event("peer_logo_profile_failed", error);
                        }
                    });
                    return null;
                });
                nativeBridge.subscribe.invoke(service, null, payload, callback);
                synchronized (LunarSpectatorCosmetics.class) {
                    if (officialProfileSocket == profileSource && peerLogoWorld == expectedWorld) {
                        PEER_LOGO_SUBSCRIBED.addAll(onlineBatch);
                        PEER_LOGO_PENDING_UNSUB.removeAll(onlineBatch);
                    }
                }
            } catch (Throwable error) {
                schedulePeerLogoFailureRetry(onlineBatch);
                event("peer_logo_profile_failed", error);
            }
        });
    }

    private static synchronized void schedulePeerLogoFailureRetry(Set<UUID> failedBatch) {
        long retryAt = System.currentTimeMillis() + PEER_LOGO_FAILURE_RETRY_MS;
        for (UUID id : failedBatch) {
            if (PEER_LOGO_RETRY_AT.containsKey(id)) {
                PEER_LOGO_RETRY_AT.put(id, Long.valueOf(retryAt));
            }
        }
    }

    private static void unsubscribeOfficial(Object official, Set<UUID> players) {
        if (official == null || players == null || players.isEmpty() || !open(official)) return;
        Optional<Bridge> supported = BRIDGES.get(official.getClass());
        if (!supported.isPresent()) return;
        Bridge nativeBridge = supported.get();
        try {
            Object service = nativeBridge.service.get(official);
            if (service == null) return;
            Object callback = Proxy.newProxyInstance(nativeBridge.loader, new Class<?>[]{nativeBridge.callback},
                    (proxy, method, args) -> method.getDeclaringClass() == Object.class
                            ? objectMethod(proxy, method, args) : null);
            nativeBridge.unsubscribe.invoke(service, null, nativeBridge.payload(nativeBridge.unsubscribeRequest, players), callback);
        } catch (Throwable error) { event("official_unsubscribe_failed", error); }
    }

    /**
     * Maintain the native official profile socket alongside FullAccess. Apply missing profiles
     * using Lunar's normal dispatcher, after verifying the account, world and requested player.
     */
    public static synchronized void updatePeerLogoProfiles(Minecraft mc) {
        if (mc == null || !LunarOnlineStatus.isFullAccessRouteActive()) {
            Object previous = officialProfileSocket;
            officialProfileSocket = null;
            heartbeatSocket = null;
            if (previous != null && previous != LunarColorRuntime.cosmeticSocket()) {
                try { previous.getClass().getMethod("close").invoke(previous); }
                catch (ReflectiveOperationException error) { event("official_close_failed", error); }
            }
            clearPeerLogoRequests(null);
            return;
        }
        Object official = officialProfileSocket;
        if (official == null || !open(official)) return;
        long now = System.currentTimeMillis();
        if (heartbeatSocket != official || now >= nextHeartbeat) {
            heartbeatSocket = official;
            nextHeartbeat = now + 15000L;
            sendOfficialHeartbeat(official);
        }
        if (!LunarOnlineStatus.isFullAccessConnected() || mc.theWorld == null
                || mc.thePlayer == null || mc.getNetHandler() == null) {
            clearPeerLogoRequests(official);
            return;
        }
        Object activeSocket = LunarColorRuntime.cosmeticSocket();
        String owner = mc.getSession() == null ? null : mc.getSession().getPlayerID();
        if (activeSocket == null || owner == null || !open(activeSocket)) {
            clearPeerLogoRequests(official);
            return;
        }
        if (peerLogoSocket != activeSocket || peerLogoWorld != mc.theWorld
                || peerLogoLocal != mc.thePlayer || peerLogoConnection != mc.getNetHandler()
                || !Objects.equals(peerLogoAccount, owner)) {
            clearPeerLogoRequests(official);
            peerLogoSocket = activeSocket;
            peerLogoWorld = mc.theWorld;
            peerLogoLocal = mc.thePlayer;
            peerLogoConnection = mc.getNetHandler();
            peerLogoAccount = owner;
        }
        UUID localId = mc.thePlayer.getUniqueID();
        Set<UUID> currentOnline = new LinkedHashSet<UUID>();
        List<UUID> missingCandidates = new ArrayList<UUID>();
        for (net.minecraft.client.network.NetworkPlayerInfo player : mc.getNetHandler().getPlayerInfoMap()) {
            if (player.getGameProfile() == null) continue;
            UUID id = player.getGameProfile().getId();
            if (id == null || id.equals(localId)) continue;
            if (!currentOnline.add(id)) continue;
            if (GomodLogoColorSync.getNativeTabLogoWidth(id) == 0) {
                missingCandidates.add(id);
            }
        }
        PEER_LOGO_RETRY_AT.keySet().retainAll(currentOnline);
        if (!PEER_LOGO_SUBSCRIBED.isEmpty()) {
            Iterator<UUID> subscribedIt = PEER_LOGO_SUBSCRIBED.iterator();
            while (subscribedIt.hasNext()) {
                UUID id = subscribedIt.next();
                if (!currentOnline.contains(id)) {
                    subscribedIt.remove();
                    PEER_LOGO_PENDING_UNSUB.add(id);
                }
            }
        }
        if (!PEER_LOGO_PENDING_UNSUB.isEmpty() && now >= peerLogoNextUnsubscribeAt) {
            Set<UUID> toUnsub = new LinkedHashSet<UUID>(PEER_LOGO_PENDING_UNSUB);
            PEER_LOGO_PENDING_UNSUB.clear();
            peerLogoNextUnsubscribeAt = now + PEER_LOGO_UNSUBSCRIBE_INTERVAL_MS;
            unsubscribeOfficial(official, toUnsub);
        }
        if (now < peerLogoNextRequestAt || missingCandidates.isEmpty()) return;
        Set<UUID> batch = selectPeerLogoBatch(missingCandidates, PEER_LOGO_RETRY_AT, now, PEER_LOGO_BATCH_SIZE);
        if (batch.isEmpty()) return;
        long nextRetryAt = now + PEER_LOGO_RETRY_MS;
        for (UUID id : batch) {
            PEER_LOGO_RETRY_AT.put(id, Long.valueOf(nextRetryAt));
        }
        peerLogoNextRequestAt = now + PEER_LOGO_BATCH_INTERVAL_MS;
        final Object expectedWorld = mc.theWorld;
        final Object expectedLocal = mc.thePlayer;
        final Object expectedConnection = mc.getNetHandler();
        final String expectedAccount = owner;
        requestPeerLogoBatch(batch, now + PEER_LOGO_REQUEST_TIMEOUT_MS, () ->
                peerLogoSocket == activeSocket && peerLogoWorld == expectedWorld
                        && peerLogoLocal == expectedLocal && peerLogoConnection == expectedConnection
                        && Objects.equals(peerLogoAccount, expectedAccount)
                        && officialProfileSocket == official
                        && LunarOnlineStatus.isFullAccessConnected()
                        && LunarOnlineStatus.isFullAccessRouteActive(), official);
    }

    private static void sendOfficialHeartbeat(Object official) {
        try {
            Bridge nativeBridge = BRIDGES.get(official.getClass()).get();
            Object callback = Proxy.newProxyInstance(nativeBridge.loader, new Class<?>[]{nativeBridge.callback},
                    (proxy, method, args) -> method.getDeclaringClass() == Object.class
                            ? objectMethod(proxy, method, args) : null);
            nativeBridge.gameHeartbeat.invoke(nativeBridge.heartbeat.get(official), null,
                    nativeBridge.heartbeatRequest, callback);
        } catch (ReflectiveOperationException | RuntimeException error) { event("official_heartbeat_failed", error); }
    }

    /** True while the retained official Lunar websocket is open alongside FullAccess. */
    public static boolean hasOfficialProfileSocket() {
        Object official = officialProfileSocket;
        return official != null && LunarOnlineStatus.isFullAccessRouteActive() && open(official);
    }

    /**
     * Query Lunar's official server for our own current display profile without dispatching
     * into Lunar's live FullAccess cosmetic manager.
     */
    public static synchronized boolean requestOfficialSelfProfile() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || !LunarOnlineStatus.isFullAccessRouteActive()) return false;
        final Object official = officialProfileSocket;
        if (official == null || !open(official)) return false;
        final String expectedAccount = mc.getSession().getPlayerID();
        final UUID localId = parseSessionUuid(expectedAccount);
        if (localId == null) return false;
        long now = System.currentTimeMillis();
        if (now < nextOfficialSelfRequestAt) return false;
        Optional<Bridge> supported = BRIDGES.get(official.getClass());
        if (!supported.isPresent()) return false;
        final Bridge nativeBridge = supported.get();
        nextOfficialSelfRequestAt = now + 4000L;
        final long deadline = now + 12000L;
        mc.addScheduledTask(() -> {
            if (System.currentTimeMillis() >= deadline
                    || officialProfileSocket != official || !open(official)
                    || !LunarOnlineStatus.isFullAccessRouteActive()
                    || mc.getSession() == null || !Objects.equals(expectedAccount, mc.getSession().getPlayerID())) {
                return;
            }
            try {
                Object service = nativeBridge.service.get(official);
                if (service == null) return;
                Object payload = nativeBridge.payload(nativeBridge.subscribeRequest, Collections.singleton(localId));
                Object callback = Proxy.newProxyInstance(nativeBridge.loader, new Class<?>[]{nativeBridge.callback}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (!"run".equals(method.getName())) return null;
                    final Object response = args == null || args.length != 1 ? null : args[0];
                    mc.addScheduledTask(() -> {
                        if (officialProfileSocket != official
                                || mc.getSession() == null || !Objects.equals(expectedAccount, mc.getSession().getPlayerID())) {
                            return;
                        }
                        try {
                            if (response == null || !nativeBridge.response.isInstance(response)) return;
                            List<?> profiles = (List<?>) nativeBridge.pushes.invoke(response);
                            LunarConnectionDiagnostics.record("official_loadout.self_profile",
                                    "profiles=" + profiles.size());
                            if (profiles.size() > 1) return;
                            for (Object profile : profiles) {
                                if (!localId.equals(nativeBridge.playerId(profile))) continue;
                                LunarPublicLoginGuard.recordOfficialSelfProfilePush(profile);
                            }
                        } catch (Throwable error) { event("official_self_profile_failed", error); }
                    });
                    return null;
                });
                nativeBridge.subscribe.invoke(service, null, payload, callback);
            } catch (Throwable error) { event("official_self_profile_failed", error); }
        });
        return true;
    }

    private static UUID parseSessionUuid(String raw) {
        if (raw == null) return null;
        String hex = raw.replace("-", "").trim();
        if (!hex.matches("(?i)[0-9a-f]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    private static boolean isOnline(Minecraft mc, UUID id) {
        if (mc == null || id == null || mc.theWorld == null) return false;
        if (mc.theWorld.getPlayerEntityByUUID(id) != null) return true;
        if (mc.getNetHandler() != null) return mc.getNetHandler().getPlayerInfo(id) != null;
        return false;
    }

    private static void clearPeerLogoRequests(Object official) {
        if (official != null && (!PEER_LOGO_SUBSCRIBED.isEmpty() || !PEER_LOGO_PENDING_UNSUB.isEmpty())) {
            Set<UUID> toUnsub = new LinkedHashSet<UUID>(PEER_LOGO_SUBSCRIBED);
            toUnsub.addAll(PEER_LOGO_PENDING_UNSUB);
            unsubscribeOfficial(official, toUnsub);
        }
        PEER_LOGO_RETRY_AT.clear();
        PEER_LOGO_SUBSCRIBED.clear();
        PEER_LOGO_PENDING_UNSUB.clear();
        peerLogoNextRequestAt = 0L;
        peerLogoNextUnsubscribeAt = 0L;
        peerLogoSocket = peerLogoWorld = peerLogoLocal = peerLogoConnection = null;
        peerLogoAccount = null;
    }

    /** Called only by SpectatorRelay on the Minecraft thread, never from the render loop. */
    public static void update(Minecraft mc, Collection<UUID> players, long now) {
        Object activeSocket = LunarColorRuntime.cosmeticSocket();
        String owner = mc.getSession() == null ? null : mc.getSession().getPlayerID();
        if (world != mc.theWorld || local != mc.thePlayer || connection != mc.getNetHandler()
                || !Objects.equals(account, owner) || socket != activeSocket) {
            clear();
            world = mc.theWorld; local = mc.thePlayer; connection = mc.getNetHandler();
            account = owner; socket = activeSocket;
        }
        if (socket == null || owner == null || connection == null || !open(socket)) return;
        if (bridge == null) {
            Optional<Bridge> supported = BRIDGES.get(socket.getClass());
            if (!supported.isPresent()) return;
            bridge = supported.get();
        }
        Set<UUID> wanted = new LinkedHashSet<UUID>();
        for (UUID id : players) {
            if (id != null && !id.equals(mc.thePlayer.getUniqueID())) wanted.add(id);
            if (wanted.size() >= SpectatorFeedState.MAX_PLAYERS) break;
        }
        Set<UUID> removed = new HashSet<UUID>(SUBSCRIBED);
        removed.removeAll(wanted);
        unsubscribe(mc, removed);
        SUBSCRIBED.removeAll(removed);
        DUE.keySet().retainAll(wanted);
        // Let Lunar's ordinary despawn/unsubscribe queue retire its real body first.
        for (UUID id : wanted) if (!DUE.containsKey(id)) DUE.put(id, now + 1200L);
        if ((pending != 0L && now < pendingUntil) || now < nextSendAt) return;
        pending = 0L;
        final Set<UUID> batch = new LinkedHashSet<UUID>();
        for (Map.Entry<UUID, Long> entry : DUE.entrySet()) if (entry.getValue() <= now) batch.add(entry.getKey());
        if (batch.isEmpty()) return;
        final Object expectedSocket = socket;
        final Bridge expectedBridge = bridge;
        final long epoch = generation, token = ++request;
        pending = token; pendingUntil = now + 25000L; nextSendAt = now + 1000L;
        for (UUID id : batch) DUE.put(id, now + 30000L);
        try {
            Object service = bridge.service.get(socket);
            if (service == null) throw new IllegalStateException("Lunar subscription service unavailable");
            Object payload = bridge.payload(bridge.subscribeRequest, batch);
            Object callback = Proxy.newProxyInstance(bridge.loader, new Class<?>[]{bridge.callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (!"run".equals(method.getName())) return null;
                final Object response = args == null || args.length != 1 ? null : args[0];
                mc.addScheduledTask(() -> {
                    if (!current(mc, expectedSocket, epoch) || pending != token) return;
                    pending = 0L;
                    try {
                        if (response == null || !expectedBridge.response.isInstance(response))
                            throw new IllegalStateException("Missing Lunar spectator profiles");
                        List<?> pushes = (List<?>) expectedBridge.pushes.invoke(response);
                        if (pushes.size() > batch.size()) throw new IllegalStateException("Unexpected spectator profile count");
                        Set<UUID> seen = new HashSet<UUID>();
                        for (Object push : pushes) {
                            UUID id = expectedBridge.playerId(push);
                            if (!batch.contains(id) || !seen.add(id) || !DUE.containsKey(id)) continue;
                            // Keep Lunar's own dispatch, sharing preferences and disguise hooks in the path.
                            expectedBridge.dispatch.invoke(expectedSocket, push);
                        }
                    } catch (Throwable error) {
                        retry(batch);
                        event("response_failed", error);
                    }
                });
                return null;
            });
            bridge.subscribe.invoke(service, null, payload, callback);
            SUBSCRIBED.addAll(batch);
        } catch (Throwable error) {
            pending = 0L; retry(batch); event("subscribe_failed", error);
        }
    }

    private static boolean current(Minecraft mc, Object expectedSocket, long epoch) {
        return epoch == generation && socket == expectedSocket && socket == LunarColorRuntime.cosmeticSocket()
                && world == mc.theWorld && local == mc.thePlayer && connection == mc.getNetHandler()
                && mc.getSession() != null && Objects.equals(account, mc.getSession().getPlayerID())
                && SpectatorRelay.isAliveViewer() && open(socket);
    }

    /** Retire only our extra watches. Native world entities still own their ordinary subscriptions. */
    private static void unsubscribe(Minecraft mc, Set<UUID> players) {
        if (bridge == null || socket == null || !open(socket) || players.isEmpty()) return;
        Set<UUID> extra = new LinkedHashSet<UUID>();
        for (UUID id : players) {
            // Native body subscriptions follow entity spawn/despawn, independently of TAB-logo loading.
            if (mc != null && mc.theWorld != null && mc.theWorld.getPlayerEntityByUUID(id) != null) continue;
            extra.add(id);
        }
        if (extra.isEmpty()) return;
        try {
            Object service = bridge.service.get(socket);
            if (service == null) return;
            Object callback = Proxy.newProxyInstance(bridge.loader, new Class<?>[]{bridge.callback}, (proxy, method, args) ->
                    method.getDeclaringClass() == Object.class ? objectMethod(proxy, method, args) : null);
            bridge.unsubscribe.invoke(service, null, bridge.payload(bridge.unsubscribeRequest, extra), callback);
        } catch (Throwable error) { event("unsubscribe_failed", error); }
    }

    public static void clear() {
        if (socket == null && DUE.isEmpty()) return;
        unsubscribe(Minecraft.getMinecraft(), new HashSet<UUID>(SUBSCRIBED));
        generation++; pending = pendingUntil = nextSendAt = 0L;
        DUE.clear(); SUBSCRIBED.clear(); socket = world = local = connection = null; account = null; bridge = null;
    }

    private static void retry(Set<UUID> players) {
        long due = System.currentTimeMillis() + 5000L;
        for (UUID id : players) if (DUE.containsKey(id)) DUE.put(id, due);
    }
    private static boolean open(Object value) {
        try { return value != null && Boolean.TRUE.equals(value.getClass().getMethod("isOpen").invoke(value)); }
        catch (Exception error) { return false; }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
        return "Gomod spectator cosmetic callback";
    }
    private static void event(String name, Throwable error) {
        LunarConnectionDiagnostics.recordOnceAfterStart("spectator.cosmetics_" + name, error.getClass().getName(),
                "errorType=" + error.getClass().getSimpleName());
    }

    /** All obfuscated members are resolved by unique protobuf types/signatures from the active socket. */
    private static final class Bridge {
        final ClassLoader loader;
        final Class<?> callback, subscribeRequest, unsubscribeRequest, response, uuid;
        final Field service, heartbeat;
        final Method subscribe, unsubscribe, dispatch, high, low, playerUuid, size, pushes, gameHeartbeat;
        final Object heartbeatRequest;
        Bridge(Class<?> type) throws Exception {
            loader = type.getClassLoader();
            Class<?> api = Class.forName(SUB + "SubscriptionService$Interface", false, loader);
            Field match = null;
            for (Field field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType() == api) {
                if (match != null) throw new NoSuchFieldException("Ambiguous subscription service");
                match = field;
            }
            if (match == null) throw new NoSuchFieldException("Missing subscription service");
            service = match; service.setAccessible(true);
            callback = Class.forName("com.google.protobuf.RpcCallback", false, loader);
            Class<?> controller = Class.forName("com.google.protobuf.RpcController", false, loader);
            String heartbeatPackage = "com.lunarclient.websocket.heartbeat.v1.";
            Class<?> heartbeatService = Class.forName(heartbeatPackage + "HeartbeatService$Interface", false, loader);
            Field heartbeatField = null;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != heartbeatService) continue;
                if (heartbeatField != null) throw new NoSuchFieldException("Ambiguous Lunar heartbeat service");
                heartbeatField = field;
            }
            if (heartbeatField == null) throw new NoSuchFieldException("Missing Lunar heartbeat service");
            heartbeat = heartbeatField;
            heartbeat.setAccessible(true);
            Class<?> heartbeatType = Class.forName(heartbeatPackage + "GameHeartbeatRequest", false, loader);
            heartbeatRequest = heartbeatType.getMethod("getDefaultInstance").invoke(null);
            gameHeartbeat = heartbeatService.getMethod("gameHeartbeat", controller, heartbeatType, callback);
            subscribeRequest = Class.forName(SUB + "SubscribeV2Request", false, loader);
            unsubscribeRequest = Class.forName(SUB + "UnsubscribeRequest", false, loader);
            response = Class.forName(SUB + "SubscribeV2Response", false, loader);
            uuid = Class.forName("com.lunarclient.common.v1.Uuid", false, loader);
            Class<?> push = Class.forName(PUSH, false, loader);
            subscribe = api.getMethod("subscribeV2", controller, subscribeRequest, callback);
            unsubscribe = api.getMethod("unsubscribe", controller, unsubscribeRequest, callback);
            dispatch = LunarColorRuntime.uniqueMethod(type, Void.TYPE, push);
            playerUuid = push.getMethod("getPlayerUuid"); size = push.getMethod("getSerializedSize");
            high = uuid.getMethod("getHigh64"); low = uuid.getMethod("getLow64");
            pushes = response.getMethod("getCosmeticPushesList");
        }
        Object payload(Class<?> type, Set<UUID> ids) throws Exception {
            Object builder = type.getMethod("newBuilder").invoke(null);
            Method add = builder.getClass().getMethod("addTargetUuids", uuid);
            for (UUID id : ids) {
                Object value = uuid.getMethod("newBuilder").invoke(null);
                value.getClass().getMethod("setHigh64", Long.TYPE).invoke(value, id.getMostSignificantBits());
                value.getClass().getMethod("setLow64", Long.TYPE).invoke(value, id.getLeastSignificantBits());
                add.invoke(builder, value.getClass().getMethod("build").invoke(value));
            }
            return builder.getClass().getMethod("build").invoke(builder);
        }
        UUID playerId(Object push) throws Exception {
            if (((Number) size.invoke(push)).intValue() > 512 * 1024) throw new IllegalArgumentException("Oversized spectator profile");
            Object id = playerUuid.invoke(push);
            return new UUID(((Number) high.invoke(id)).longValue(), ((Number) low.invoke(id)).longValue());
        }
    }
}
