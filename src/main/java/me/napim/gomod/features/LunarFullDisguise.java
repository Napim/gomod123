package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;

import java.lang.reflect.Method;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Copies a currently visible player's Lunar cosmetics profile into the local Lunar render caches.
 * This is a local visual disguise only: it never sends an identity or profile spoof to a server.
 */
public final class LunarFullDisguise {
    private static final long PROFILE_TTL_MS = TimeUnit.MINUTES.toMillis(5L);
    private static final long LATE_DISGUISE_ECHO_GRACE_MS = TimeUnit.SECONDS.toMillis(30L);
    private static final int MAX_CACHED_PROFILES = 256;
    private static final int PERSISTED_PROFILE_MAGIC = 0x474C4450; // GLDP
    private static final int PERSISTED_PROFILE_VERSION = 1;
    private static final int MAX_PERSISTED_PROFILE_BYTES = 8 * 1024 * 1024;
    /** Bound relay transfers and receiver memory for an opted-in shared Lunar outfit. */
    private static final int MAX_SHARED_PROFILE_BYTES = 512 * 1024;
    private static final String PERSISTED_PUSH_CLASS =
            "com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2";
    private static final Map<UUID, CachedPush> PLAYER_PUSHES =
            new ConcurrentHashMap<UUID, CachedPush>();
    private static final Map<UUID, PeerOverride> PEER_OVERRIDES =
            new ConcurrentHashMap<UUID, PeerOverride>();

    private static final Object LOCK = new Object();
    private static final Object PROFILE_WAIT_LOCK = new Object();
    /** Prevents our own local profile writes from being mistaken for a new native outfit snapshot. */
    private static final ThreadLocal<Boolean> APPLYING_LOCAL_APPEARANCE = new ThreadLocal<Boolean>();
    private static final AtomicInteger WAITING_TARGET_PROFILE_UPDATES = new AtomicInteger();
    private static final AtomicInteger WAITING_OTHER_PEER_PROFILE_UPDATES = new AtomicInteger();
    private static volatile UUID waitingSourcePlayerId;
    private static volatile Object outfitManager;
    private static volatile UUID localLoginSnapshotUuid;
    private static volatile CachedPush localLoginSnapshot;
    private static volatile UUID activeSourceUuid;
    private static volatile UUID activeLocalUuid;
    private static volatile Object savedLocalPush;
    /** Localized target push last written by /disguisefull; protects against Lunar echoing it later. */
    private static volatile Object lastAppliedLocalDisguisePush;
    private static volatile Object recentRestorePush;
    private static volatile UUID recentRestoreLocalUuid;
    private static volatile long recentRestoreUntilMs;
    private static volatile Object lastAppliedDisguiseSourcePush;
    private static volatile UUID lastAppliedDisguiseSourceUuid;
    private static volatile UUID lastAppliedDisguiseLocalUuid;
    private static volatile long activationGeneration;
    private static int worldIdentity;

    private LunarFullDisguise() {
    }

    /** Metadata for a saved Lunar profile found by the target's last saved Minecraft name. */
    public static final class CachedTarget {
        public final UUID playerId;
        public final String playerName;
        public final long savedAtMs;

        private CachedTarget(UUID playerId, String playerName, long savedAtMs) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.savedAtMs = savedAtMs;
        }
    }

    /** Finds an explicitly captured /disguisefull profile, even when the player is offline. */
    public static CachedTarget findCachedTarget(String playerName) throws IOException {
        if (playerName == null || playerName.trim().isEmpty()) return null;
        File directory = persistedProfileDirectory();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".gldp"));
        if (files == null || files.length == 0) return null;
        CachedTarget newest = null;
        for (File file : files) {
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)))) {
                if (input.readInt() != PERSISTED_PROFILE_MAGIC
                        || input.readInt() != PERSISTED_PROFILE_VERSION) continue;
                UUID id = new UUID(input.readLong(), input.readLong());
                long savedAt = input.readLong();
                String name = input.readUTF();
                int payloadLength = input.readInt();
                if (payloadLength < 1 || payloadLength > MAX_PERSISTED_PROFILE_BYTES
                        || !name.equalsIgnoreCase(playerName.trim())) continue;
                if (newest == null || savedAt > newest.savedAtMs) {
                    newest = new CachedTarget(id, name, savedAt);
                }
            } catch (IOException | RuntimeException ignored) {
                // Ignore incomplete or obsolete cache files; a live target can refresh them.
            }
        }
        return newest;
    }

    /** Serialize a cached native outfit push for an explicitly shared disguise. */
    public static String getSharedProfileBase64(UUID playerId) throws Exception {
        if (playerId == null || !GomodFixes.isWeaveRuntime()) {
            throw new IllegalStateException("A Lunar profile is available only in the Lunar build.");
        }
        CachedPush cached = freshPush(playerId);
        if (cached == null && outfitManager != null) {
            loadPersistedProfile(playerId);
            cached = freshPush(playerId);
        }
        if (cached == null || cached.push == null) {
            throw new IllegalStateException("Lunar has not loaded this player's appearance yet.");
        }
        UUID embeddedId = playerUuid(cached.push);
        if (embeddedId == null || !playerId.equals(embeddedId)) {
            throw new IllegalStateException("The cached Lunar appearance belongs to a different player.");
        }
        Object serialized = cached.push.getClass().getMethod("toByteArray").invoke(cached.push);
        if (!(serialized instanceof byte[])) throw new IllegalStateException("Lunar returned an invalid appearance profile.");
        byte[] bytes = (byte[]) serialized;
        if (bytes.length < 1 || bytes.length > MAX_SHARED_PROFILE_BYTES) {
            throw new IllegalStateException("The Lunar appearance profile is too large to share safely.");
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Cache a bounded, UUID-checked profile delivered by the authenticated same-server relay. */
    public static boolean cacheSharedProfileBase64(UUID expectedPlayerId, String encoded) {
        if (expectedPlayerId == null || encoded == null || encoded.isEmpty()
                || encoded.length() > ((MAX_SHARED_PROFILE_BYTES + 2) / 3) * 4
                || outfitManager == null || !GomodFixes.isWeaveRuntime()) {
            return false;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 1 || bytes.length > MAX_SHARED_PROFILE_BYTES) return false;
            ClassLoader lunarLoader = outfitManager.getClass().getClassLoader();
            Class<?> pushClass = Class.forName(PERSISTED_PUSH_CLASS, true, lunarLoader);
            Object push = pushClass.getMethod("parseFrom", byte[].class).invoke(null, new Object[]{bytes});
            UUID embeddedId = playerUuid(push);
            if (embeddedId == null || !expectedPlayerId.equals(embeddedId)) return false;
            long now = System.currentTimeMillis();
            PLAYER_PUSHES.put(expectedPlayerId, new CachedPush(push, now));
            pruneProfiles(now);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.theWorld != null) {
                mc.addScheduledTask(() -> {
                    GomodPeerDisguise.onLunarProfileAvailable(expectedPlayerId);
                    RemoteDisguiseManager.onLunarProfileAvailable(expectedPlayerId);
                });
            }
            LunarConnectionDiagnostics.record("disguise.full_lunar_shared_profile",
                    "result=cached payloadBytes=" + bytes.length);
            return true;
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("disguise.full_lunar_shared_profile",
                    "result=invalid errorType=" + error.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean hasCachedProfile(UUID playerId) {
        return freshPush(playerId) != null;
    }

    /** Public/dev receiving path: retain Lunar's native store for shared peer appearances. */
    public static void onOutfitManagerAvailable(Object manager) {
        if (manager == null || !GomodFixes.isWeaveRuntime()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        // Lunar 2.23.1's outfit manager is a process-wide store. Keep its reference even when
        // Lunar receives a cosmetics push before Minecraft has created the world; world-scoped
        // player snapshots are still cleared independently by ensureWorldScope.
        if (mc.theWorld != null) {
            ensureWorldScope(mc);
        }
        if (outfitManager != manager) {
            outfitManager = manager;
            LunarConnectionDiagnostics.record("disguise.full_lunar_outfit_manager", "result=available");
        }
    }

    /**
     * Public/dev receiving path after Lunar dispatches a native profile push. Payloads stay in
     * memory and are never written to diagnostics; peer overrides retain their real profile for restore.
     */
    public static void onCosmeticsPush(Object push) {
        if (push == null || !GomodFixes.isWeaveRuntime()) {
            return;
        }
        try {
            onCosmeticsPush(playerUuid(push), push);
        } catch (Throwable ignored) {
        }
    }

    /** Capture from Lunar's native profile handler, which supplies the authoritative player UUID separately. */
    public static void onCosmeticsPush(UUID playerId, Object push) {
        if (push == null || playerId == null || !GomodFixes.isWeaveRuntime()
                || GomodLogoColorSync.isApplyingNativeProfile()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        UUID localId = currentLocalUuid(mc);
        boolean isLocal = playerId.equals(localId);
        // applyPushToPlayer writes through Lunar's native player-logo profile handler, whose
        // mixin callback returns here. That synthetic push is the disguise we're applying,
        // not a newly received local outfit; caching it would replace the restore snapshot.
        if (isLocal && Boolean.TRUE.equals(APPLYING_LOCAL_APPEARANCE.get())) {
            return;
        }
        if (isLocal && handleFullDisguiseEcho(mc, localId, push)) {
            return;
        }
        if (mc.theWorld == null && !isLocal) {
            return;
        }
        if (mc.theWorld != null) {
            ensureWorldScope(mc);
        }

        UUID waitingFor = waitingSourcePlayerId;
        if (waitingFor != null) {
            if (waitingFor.equals(playerId)) {
                WAITING_TARGET_PROFILE_UPDATES.incrementAndGet();
            } else if (!playerId.equals(localId)) {
                WAITING_OTHER_PEER_PROFILE_UPDATES.incrementAndGet();
            }
        }

        CachedPush previous = PLAYER_PUSHES.get(playerId);
        if (previous != null && previous.push == push) {
            return;
        }

        long now = System.currentTimeMillis();
        CachedPush cached = new CachedPush(push, now);
        PLAYER_PUSHES.put(playerId, cached);
        if (isLocal) {
            localLoginSnapshotUuid = localId;
            localLoginSnapshot = cached;
            if (activeSourceUuid == null && localId.equals(recentRestoreLocalUuid)
                    && now <= recentRestoreUntilMs) {
                // A genuine account update after /disguisefull off supersedes the older restore
                // target in case a delayed copy of the disguise arrives afterward.
                recentRestorePush = push;
            }
        }
        pruneProfiles(now);

        if (mc.theWorld != null) {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    GomodPeerDisguise.onLunarProfileAvailable(playerId);
                    RemoteDisguiseManager.onLunarProfileAvailable(playerId);
                    refreshPeerOverridesForPush(playerId);
                }
            });
        }

        UUID source = activeSourceUuid;
        UUID local = activeLocalUuid;
        if (source == null || local == null) {
            return;
        }
        if (playerId.equals(local)) {
            // Preserve the latest real local profile so FullAccess updates that arrive while the
            // disguise is active are restored when it is turned off.
            savedLocalPush = push;
        }
        if (playerId.equals(source) || playerId.equals(local)) {
            scheduleApply(source, activationGeneration);
        }
    }

    /**
     * Lunar initializes the local outfit from its cosmetics LoginResponse; it does not
     * necessarily dispatch that same profile through PlayerCosmeticsPushV2. Convert that native
     * response into the push shape used by the local render cache so disguise/restore does not
     * depend on Lunar echoing the local player as a remote player.
     */
    public static void onLocalCosmeticsLoginResponse(Object response) {
        if (response == null || !GomodInternalFeatures.isEnabled() || !GomodFixes.isWeaveRuntime()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        UUID localId = currentLocalUuid(mc);
        if (mc == null || localId == null) {
            return;
        }
        try {
            Object push = buildLocalSnapshotFromLoginResponse(response, localId);
            if (push == null) {
                LunarConnectionDiagnostics.record("disguise.full_lunar_local_snapshot",
                        "result=unavailable reason=active_outfit_missing");
                return;
            }
            long now = System.currentTimeMillis();
            CachedPush cached = new CachedPush(push, now);
            localLoginSnapshotUuid = localId;
            localLoginSnapshot = cached;
            if (activeSourceUuid == null && localId.equals(recentRestoreLocalUuid)
                    && now <= recentRestoreUntilMs) {
                recentRestorePush = push;
            }
            if (mc.theWorld != null) {
                ensureWorldScope(mc);
                PLAYER_PUSHES.put(localId, cached);
                pruneProfiles(now);
            }

            UUID activeLocal = activeLocalUuid;
            UUID source = activeSourceUuid;
            if (localId.equals(activeLocal)) {
                savedLocalPush = push;
                if (source != null) {
                    scheduleApply(source, activationGeneration);
                }
            }
            LunarConnectionDiagnostics.record("disguise.full_lunar_local_snapshot",
                    "result=captured source=cosmetics_login_response");
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("disguise.full_lunar_local_snapshot",
                    "result=failed errorType=" + error.getClass().getSimpleName());
        }
    }

    /** Returns the real local badge ID when Lunar has not created its logo-profile cache yet. */
    public static int localSnapshotBadgeId(UUID playerId) {
        CachedPush snapshot = localLoginSnapshot;
        if (playerId == null || snapshot == null || !playerId.equals(localLoginSnapshotUuid)) {
            return 0;
        }
        try {
            return ((Number) invokeNoArgs(snapshot.push, "getBadgeId")).intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Waits off-thread for both players' live pushes and Lunar's local profile managers. */
    public static String awaitReady(UUID sourcePlayerId, long timeoutMillis) throws InterruptedException {
        return awaitReady(sourcePlayerId, timeoutMillis, () -> true);
    }

    public static String awaitReady(UUID sourcePlayerId, long timeoutMillis,
                                    BooleanSupplier stillCurrent) throws InterruptedException {
        if (sourcePlayerId == null) {
            return "The selected player has no profile UUID.";
        }
        synchronized (PROFILE_WAIT_LOCK) {
            waitingSourcePlayerId = sourcePlayerId;
            WAITING_TARGET_PROFILE_UPDATES.set(0);
            WAITING_OTHER_PEER_PROFILE_UPDATES.set(0);
        }
        try {
            String savedProfileLoadFailure = null;
            long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
            long nextProfileRequestMs = 0L;
            int profileRequests = 0;
            do {
                if (!stillCurrent.getAsBoolean()) throw new CancellationException("Full disguise request was cancelled.");
                Minecraft mc = Minecraft.getMinecraft();
                UUID localId = currentLocalUuid(mc);
                if (freshPush(sourcePlayerId) == null && outfitManager != null) {
                    try {
                        loadPersistedProfile(sourcePlayerId);
                    } catch (Throwable error) {
                        if (savedProfileLoadFailure == null) {
                            savedProfileLoadFailure = safeMessage(error);
                        }
                    }
                }
                CachedPush source = freshPush(sourcePlayerId);
                long now = System.currentTimeMillis();
                if ((source == null || outfitManager == null) && now < deadline
                        && now >= nextProfileRequestMs && profileRequests < 3) {
                    requestLiveTargetProfile(sourcePlayerId, deadline, stillCurrent);
                    nextProfileRequestMs = now + 5000L;
                    profileRequests++;
                }
                CachedPush local = localId == null ? null : freshLocalPush(localId);
                if (source != null && local != null && outfitManager != null
                        && GomodLogoColorSync.isNativePlayerProfileManagerReady()) {
                    return null;
                }
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                Thread.sleep(100L);
            } while (true);

            if (freshPush(sourcePlayerId) == null) {
                if (savedProfileLoadFailure != null) {
                    return "The saved Lunar appearance could not be loaded: "
                            + savedProfileLoadFailure;
                }
                int matchingUpdates = WAITING_TARGET_PROFILE_UPDATES.get();
                int otherPeerUpdates = WAITING_OTHER_PEER_PROFILE_UPDATES.get();
                LunarConnectionDiagnostics.record("disguise.full_lunar_wait",
                        "result=target_profile_missing matchingTargetUpdates=" + matchingUpdates
                                + " otherPeerUpdates=" + otherPeerUpdates);
                return "Lunar has not delivered that player's cosmetics profile to this client yet. "
                        + "During the wait, matching target profile updates: " + matchingUpdates
                        + "; other players' profile updates: " + otherPeerUpdates
                        + ". Keep them in-world and connected to Lunar, then retry.";
            }
            Minecraft mc = Minecraft.getMinecraft();
            UUID localId = currentLocalUuid(mc);
            if (localId == null || freshLocalPush(localId) == null) {
                return "Lunar has not supplied your own selected outfit yet. Wait for your cosmetics and outfit list to finish loading, then retry.";
            }
            if (outfitManager == null || !GomodLogoColorSync.isNativePlayerProfileManagerReady()) {
                return "Lunar's local cosmetics renderer is still initializing; wait briefly, then retry.";
            }
            return "Lunar profile data is still loading; retry in a moment.";
        } finally {
            synchronized (PROFILE_WAIT_LOCK) {
                if (sourcePlayerId.equals(waitingSourcePlayerId)) {
                    waitingSourcePlayerId = null;
                }
            }
        }
    }

    private static void requestLiveTargetProfile(UUID playerId, long deadline, BooleanSupplier stillCurrent) {
        try {
            Class<?> bridge = Class.forName("me.napim.gomod.features.LunarSpectatorCosmetics", false,
                    LunarFullDisguise.class.getClassLoader());
            bridge.getMethod("requestDisguiseProfile", UUID.class, Long.TYPE, BooleanSupplier.class)
                    .invoke(null, playerId, deadline, stillCurrent);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.full_lunar_profile_request_unavailable",
                    error.getClass().getName(), "errorType=" + error.getClass().getSimpleName());
        }
    }

    /** Activates the cached target profile on the Minecraft thread and waits for its result. */
    public static void activate(UUID sourcePlayerId) throws Exception {
        activate(sourcePlayerId, null);
    }

    /** Activates and persists the exact full Lunar push associated with this command target. */
    public static boolean activate(UUID sourcePlayerId, String sourcePlayerName) throws Exception {
        return activate(sourcePlayerId, sourcePlayerName, () -> true);
    }

    /** Activation guarded by the owning command so a delayed client-thread task cannot undo /off. */
    public static boolean activate(UUID sourcePlayerId, String sourcePlayerName,
                                   BooleanSupplier shouldContinue) throws Exception {
        if (!GomodInternalFeatures.isEnabled() || !GomodFixes.isWeaveRuntime()) {
            throw new IllegalStateException("Local full disguise is available only in internal Weave builds.");
        }
        BooleanSupplier guard = shouldContinue == null ? () -> true : shouldContinue;
        if (!guard.getAsBoolean()) throw new CancellationException("Full disguise request was cancelled.");
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            throw new IllegalStateException("Join a world before using /disguisefull.");
        }
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicReference<Boolean> cacheSaved = new AtomicReference<Boolean>(Boolean.FALSE);
        final CountDownLatch completed = new CountDownLatch(1);
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!guard.getAsBoolean()) {
                        throw new CancellationException("Full disguise request was cancelled.");
                    }
                    activateOnClientThread(mc, sourcePlayerId, sourcePlayerName, cacheSaved);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    completed.countDown();
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            apply.run();
        } else {
            mc.addScheduledTask(apply);
            if (!completed.await(8L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while applying the Lunar appearance.");
            }
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof CancellationException) throw (CancellationException) error;
            throw new IllegalStateException(safeMessage(error), error);
        }
        return Boolean.TRUE.equals(cacheSaved.get());
    }

    /** Restores the last real local push. Safe to call from any thread and when no full disguise is active. */
    public static void deactivate() {
        final UUID restoreId;
        final Object restorePush;
        final long generation;
        synchronized (LOCK) {
            if (activeSourceUuid == null) {
                return;
            }
            restoreId = activeLocalUuid;
            restorePush = savedLocalPush;
            if (restoreId != null && restorePush != null && lastAppliedLocalDisguisePush != null) {
                recentRestorePush = restorePush;
                recentRestoreLocalUuid = restoreId;
                recentRestoreUntilMs = System.currentTimeMillis() + LATE_DISGUISE_ECHO_GRACE_MS;
            } else {
                recentRestorePush = null;
                recentRestoreLocalUuid = null;
                recentRestoreUntilMs = 0L;
            }
            activeSourceUuid = null;
            activeLocalUuid = null;
            savedLocalPush = null;
            lastAppliedDisguiseSourcePush = null;
            lastAppliedDisguiseSourceUuid = null;
            lastAppliedDisguiseLocalUuid = null;
            generation = ++activationGeneration;
        }
        if (restoreId == null || restorePush == null) {
            LunarConnectionDiagnostics.record("disguise.full_lunar_restore",
                    "result=skipped reason=" + (restoreId == null ? "local_identity_missing" : "snapshot_missing"));
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Runnable restore = new Runnable() {
            @Override
            public void run() {
                try {
                    if (activationGeneration != generation) {
                        return;
                    }
                    applyPushToLocal(restorePush, restoreId);
                    GomodLogoColorSync.restoreLocalLogoPreferencesAfterFullDisguise();
                    LunarConnectionDiagnostics.record("disguise.full_lunar_restore",
                            "result=success snapshot=restored lateEchoGuard="
                                    + (recentRestoreLocalUuid != null));
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("disguise.full_lunar_restore",
                            "result=failed errorType=" + error.getClass().getSimpleName());
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            restore.run();
        } else {
            mc.addScheduledTask(restore);
        }
    }

    public static boolean isActive() {
        return activeSourceUuid != null;
    }

    /** Apply a cached target outfit to a remote Gomod peer in this viewer's Lunar caches only. */
    public static boolean applyPeerAppearance(UUID peerPlayerId, UUID targetPlayerId) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || peerPlayerId == null || targetPlayerId == null
                || peerPlayerId.equals(currentLocalUuid(mc))) {
            return false;
        }
        if (!mc.isCallingFromMinecraftThread()) {
            final PeerOverride pending;
            synchronized (LOCK) {
                PeerOverride previous = PEER_OVERRIDES.get(peerPlayerId);
                if (previous != null && previous.targetPlayerId.equals(targetPlayerId)) {
                    return true;
                }
                pending = new PeerOverride(targetPlayerId,
                        previous == null ? null : previous.originalPush, false);
                PEER_OVERRIDES.put(peerPlayerId, pending);
            }
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (PEER_OVERRIDES.get(peerPlayerId) != pending) {
                        return;
                    }
                    try {
                        applyPeerAppearanceOnClientThread(peerPlayerId, targetPlayerId, pending);
                    } catch (Throwable error) {
                        if (PEER_OVERRIDES.get(peerPlayerId) == pending) {
                            clearPeerAppearance(peerPlayerId);
                        }
                        LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                                "result=failed errorType=" + error.getClass().getSimpleName());
                    }
                }
            });
            return true;
        }
        return applyPeerAppearanceOnClientThread(peerPlayerId, targetPlayerId, null);
    }

    private static boolean applyPeerAppearanceOnClientThread(UUID peerPlayerId, UUID targetPlayerId,
                                                              PeerOverride expected) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || peerPlayerId == null || targetPlayerId == null
                || peerPlayerId.equals(currentLocalUuid(mc))
                || (expected != null && PEER_OVERRIDES.get(peerPlayerId) != expected)) {
            return false;
        }
        ensureWorldScope(mc);
        if (expected != null && PEER_OVERRIDES.get(peerPlayerId) != expected) {
            return false;
        }
        PeerOverride previous = PEER_OVERRIDES.get(peerPlayerId);
        CachedPush target = freshPush(targetPlayerId);
        CachedPush peer = freshPush(peerPlayerId);
        if (target == null || peer == null || outfitManager == null
                || !GomodLogoColorSync.isNativePlayerProfileManagerReady()) {
            if (expected != null) {
                clearPeerAppearance(peerPlayerId);
            } else if (previous != null && !previous.targetPlayerId.equals(targetPlayerId)) {
                clearPeerAppearance(peerPlayerId);
            }
            return false;
        }
        if (previous != null && previous.applied && previous.targetPlayerId.equals(targetPlayerId)) {
            return true;
        }
        Object original = previous == null ? peer.push : previous.originalPush;
        if (original == null) {
            original = peer.push;
        }
        PeerOverride next = new PeerOverride(targetPlayerId, original, true);
        try {
            applyPushToPlayer(target.push, peerPlayerId);
            PEER_OVERRIDES.put(peerPlayerId, next);
            LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile", "result=applied");
            return true;
        } catch (Throwable failure) {
            if (expected != null || (previous != null
                    && !previous.targetPlayerId.equals(targetPlayerId))) {
                clearPeerAppearance(peerPlayerId);
            }
            throw failure instanceof Exception ? (Exception) failure
                    : new IllegalStateException("Remote Lunar disguise profile could not be applied.", failure);
        }
    }

    /** Restore the remote player's last real push when their Gomod disguise is withdrawn. */
    public static void clearPeerAppearance(final UUID peerPlayerId) {
        if (peerPlayerId == null) {
            return;
        }
        final PeerOverride override = PEER_OVERRIDES.remove(peerPlayerId);
        if (override == null || override.originalPush == null) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || outfitManager == null) {
            return;
        }
        final int restoreWorld = worldIdentity;
        Runnable restore = new Runnable() {
            @Override
            public void run() {
                if (PEER_OVERRIDES.containsKey(peerPlayerId) || worldIdentity != restoreWorld) {
                    return;
                }
                try {
                    applyPushToPlayer(override.originalPush, peerPlayerId);
                    LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile", "result=restored");
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                            "result=restore_failed errorType=" + error.getClass().getSimpleName());
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            restore.run();
        } else {
            mc.addScheduledTask(restore);
        }
    }

    private static void refreshPeerOverridesForPush(final UUID updatedPlayerId) {
        if (updatedPlayerId == null || PEER_OVERRIDES.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, PeerOverride> entry : PEER_OVERRIDES.entrySet()) {
            final UUID peerPlayerId = entry.getKey();
            final PeerOverride expected = entry.getValue();
            if (peerPlayerId.equals(updatedPlayerId)) {
                CachedPush actualPeerPush = freshPush(peerPlayerId);
                if (actualPeerPush != null) {
                    expected.originalPush = actualPeerPush.push;
                }
            }
            if (!peerPlayerId.equals(updatedPlayerId)
                    && !expected.targetPlayerId.equals(updatedPlayerId)) {
                continue;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (PEER_OVERRIDES.get(peerPlayerId) != expected) {
                        return;
                    }
                    CachedPush target = freshPush(expected.targetPlayerId);
                    if (target == null) {
                        return;
                    }
                    try {
                        applyPushToPlayer(target.push, peerPlayerId);
                    } catch (Throwable error) {
                        LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                                "result=refresh_failed errorType=" + error.getClass().getSimpleName());
                    }
                }
            });
        }
    }

    private static void activateOnClientThread(Minecraft mc, UUID sourcePlayerId,
            String sourcePlayerName, AtomicReference<Boolean> cacheSaved) throws Exception {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || sourcePlayerId == null) {
            throw new IllegalStateException("Join a world and choose an in-world player.");
        }
        ensureWorldScope(mc);
        UUID localId = currentLocalUuid(mc);
        if (localId == null || localId.equals(sourcePlayerId)) {
            throw new IllegalStateException("Choose another player, not yourself.");
        }
        if (freshPush(sourcePlayerId) == null) {
            loadPersistedProfile(sourcePlayerId);
        }
        CachedPush source = freshPush(sourcePlayerId);
        CachedPush local = freshLocalPush(localId);
        Object manager = outfitManager;
        if (source == null || local == null || manager == null
                || !GomodLogoColorSync.isNativePlayerProfileManagerReady()) {
            throw new IllegalStateException("The live Lunar profile or renderer is not ready; retry in a moment.");
        }

        final long generation;
        synchronized (LOCK) {
            // Replacing one full disguise preserves the original local snapshot rather than
            // accidentally snapshotting the already-copied target appearance.
            if (activeSourceUuid == null) {
                savedLocalPush = local.push;
            }
            activeSourceUuid = sourcePlayerId;
            activeLocalUuid = localId;
            lastAppliedDisguiseSourcePush = null;
            lastAppliedDisguiseSourceUuid = null;
            lastAppliedDisguiseLocalUuid = null;
            generation = ++activationGeneration;
        }

        try {
            applyActiveDisguisePush(source.push, sourcePlayerId, localId);
            String cacheName = validPlayerName(sourcePlayerName)
                    ? sourcePlayerName : findOnlinePlayerName(mc, sourcePlayerId);
            if (validPlayerName(cacheName)) {
                try {
                    savePersistedProfile(sourcePlayerId, cacheName, source.push);
                    cacheSaved.set(Boolean.TRUE);
                } catch (Throwable cacheError) {
                    LunarConnectionDiagnostics.record("disguise.full_lunar_cache",
                            "result=save_failed errorType=" + cacheError.getClass().getSimpleName());
                }
            } else {
                LunarConnectionDiagnostics.record("disguise.full_lunar_cache",
                        "result=save_skipped reason=target_name_unavailable");
            }
            LunarConnectionDiagnostics.record("disguise.full_lunar_apply", "result=success");
        } catch (Throwable error) {
            synchronized (LOCK) {
                if (activationGeneration == generation) {
                    activeSourceUuid = null;
                    activeLocalUuid = null;
                    lastAppliedLocalDisguisePush = null;
                    lastAppliedDisguiseSourcePush = null;
                    lastAppliedDisguiseSourceUuid = null;
                    lastAppliedDisguiseLocalUuid = null;
                    Object original = savedLocalPush;
                    savedLocalPush = null;
                    if (original != null) {
                        try {
                            applyPushToLocal(original, localId);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            LunarConnectionDiagnostics.record("disguise.full_lunar_apply",
                    "result=failed errorType=" + error.getClass().getSimpleName());
            throw error instanceof Exception ? (Exception) error
                    : new IllegalStateException("Lunar appearance could not be applied.", error);
        }
    }

    private static void scheduleApply(final UUID sourceId, final long expectedGeneration) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (activationGeneration != expectedGeneration
                        || sourceId == null || !sourceId.equals(activeSourceUuid)) {
                    return;
                }
                CachedPush source = freshPush(sourceId);
                UUID localId = activeLocalUuid;
                if (source == null || localId == null) {
                    return;
                }
                try {
                    applyActiveDisguisePush(source.push, sourceId, localId);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("disguise.full_lunar_apply",
                            "result=refresh_failed errorType=" + error.getClass().getSimpleName());
                }
            }
        });
    }

    /** Avoid rewriting Lunar's local profile when it echoes the same disguised push. */
    private static void applyActiveDisguisePush(Object sourcePush, UUID sourceId, UUID localId)
            throws Exception {
        if (sourcePush == null || sourceId == null || localId == null) return;
        synchronized (LOCK) {
            if (!sourceId.equals(activeSourceUuid) || !localId.equals(activeLocalUuid)) return;
            if (sourceId.equals(lastAppliedDisguiseSourceUuid)
                    && localId.equals(lastAppliedDisguiseLocalUuid)
                    && sameProfile(lastAppliedDisguiseSourcePush, sourcePush)) {
                return;
            }
            // Mark before Lunar handles it: its synchronous profile callback can enqueue a refresh.
            lastAppliedDisguiseSourcePush = sourcePush;
            lastAppliedDisguiseSourceUuid = sourceId;
            lastAppliedDisguiseLocalUuid = localId;
        }
        try {
            applyPushToLocal(sourcePush, localId);
        } catch (Exception | Error failure) {
            synchronized (LOCK) {
                if (lastAppliedDisguiseSourcePush == sourcePush) {
                    lastAppliedDisguiseSourcePush = null;
                    lastAppliedDisguiseSourceUuid = null;
                    lastAppliedDisguiseLocalUuid = null;
                }
            }
            throw failure;
        }
    }

    private static boolean sameProfile(Object first, Object second) {
        if (first == null || second == null) return false;
        if (first == second) return true;
        try {
            return first.equals(second);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * A local outfit push matching our last copied target profile is an echo, not the user's
     * original appearance. Ignore it while disguised; if it arrives just after /disguisefull off,
     * put the saved local profile back after Lunar finishes accepting the stale push.
     */
    private static boolean handleFullDisguiseEcho(Minecraft mc, UUID localId, Object push) {
        Object applied = lastAppliedLocalDisguisePush;
        if (mc == null || localId == null || applied == null || !sameProfile(applied, push)) return false;

        UUID activeLocal = activeLocalUuid;
        if (activeSourceUuid != null && localId.equals(activeLocal)) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.full_lunar_echo",
                    "active-target-profile", "result=ignored_local_target_echo");
            return true;
        }

        Object restore = recentRestorePush;
        UUID restoreId = recentRestoreLocalUuid;
        long restoreUntil = recentRestoreUntilMs;
        if (restore == null || !localId.equals(restoreId) || System.currentTimeMillis() > restoreUntil) {
            return false;
        }
        Runnable reapplyRestore = new Runnable() {
            @Override
            public void run() {
                if (activeSourceUuid != null || recentRestorePush != restore
                        || !localId.equals(recentRestoreLocalUuid)
                        || System.currentTimeMillis() > recentRestoreUntilMs) {
                    return;
                }
                try {
                    applyPushToLocal(restore, localId);
                    GomodLogoColorSync.restoreLocalLogoPreferencesAfterFullDisguise();
                    LunarConnectionDiagnostics.recordOnceAfterStart("disguise.full_lunar_echo",
                            "late-target-profile", "result=restored_local_snapshot_after_late_echo");
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("disguise.full_lunar_echo",
                            "result=late_restore_failed errorType=" + error.getClass().getSimpleName());
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) reapplyRestore.run();
        else mc.addScheduledTask(reapplyRestore);
        return true;
    }

    private static void applyPushToLocal(Object sourcePush, UUID localId) throws Exception {
        applyPushToPlayer(sourcePush, localId);
    }

    private static void applyPushToPlayer(Object sourcePush, UUID playerId) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        boolean localPlayer = playerId.equals(currentLocalUuid(mc));
        if (localPlayer) {
            APPLYING_LOCAL_APPEARANCE.set(Boolean.TRUE);
        }
        try {
            Object playerPush = GomodLogoColorSync.copyCosmeticsPushForPlayer(sourcePush, playerId);
            if (localPlayer && playerId.equals(activeLocalUuid) && activeSourceUuid != null) {
                lastAppliedLocalDisguisePush = playerPush;
            }
            GomodLogoColorSync.applyFullDisguisePlayerLogoProfile(playerId, playerPush);

            Object manager = outfitManager;
            if (manager == null) {
                throw new IllegalStateException("Lunar's outfit manager is unavailable");
            }
            Class<?> pushClass = playerPush.getClass();
            Method update = null;
            for (Method candidate : manager.getClass().getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers()) || candidate.isBridge()
                        || candidate.getReturnType() != Void.TYPE || candidate.getParameterTypes().length != 1
                        || candidate.getParameterTypes()[0] != pushClass) continue;
                if (update != null) throw new NoSuchMethodException("Lunar's V2 outfit profile handler is ambiguous");
                update = candidate;
            }
            if (update == null) {
                throw new NoSuchMethodException("Lunar's V2 outfit profile handler was not found");
            }
            update.invoke(manager, playerPush);
        } finally {
            if (localPlayer) {
                APPLYING_LOCAL_APPEARANCE.remove();
            }
        }

        String playerName = findOnlinePlayerName(mc, playerId);
        if (playerName != null && !playerName.isEmpty()) {
            try {
                Class<?> support = Class.forName("me.napim.gomod.mixins.LunarCompanionMultiTypeSupport",
                        false, LunarFullDisguise.class.getClassLoader());
                support.getMethod("refreshPlayerByName", String.class)
                        .invoke(null, playerName);
            } catch (ClassNotFoundException ignored) {
                // Non-companion cosmetics use Lunar's normal lazy renderer refresh.
            } catch (Throwable ignored) {
                // The profile and outfit caches are already updated; refresh is best effort.
            }
        }
    }

    private static String findOnlinePlayerName(Minecraft mc, UUID playerId) {
        if (mc == null || playerId == null) {
            return null;
        }
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(playerId);
                if (info != null && info.getGameProfile() != null) {
                    String name = info.getGameProfile().getName();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.theWorld != null) {
                for (Object candidate : mc.theWorld.playerEntities) {
                    if (candidate instanceof EntityPlayer) {
                        EntityPlayer player = (EntityPlayer) candidate;
                        if (playerId.equals(player.getUniqueID())) {
                            return player.getName();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static UUID playerUuid(Object push) throws Exception {
        Method hasPlayerUuid = push.getClass().getMethod("hasPlayerUuid");
        if (!Boolean.TRUE.equals(hasPlayerUuid.invoke(push))) {
            return null;
        }
        Object protoUuid = push.getClass().getMethod("getPlayerUuid").invoke(push);
        long high = ((Number) protoUuid.getClass().getMethod("getHigh64").invoke(protoUuid)).longValue();
        long low = ((Number) protoUuid.getClass().getMethod("getLow64").invoke(protoUuid)).longValue();
        return new UUID(high, low);
    }

    private static Object buildLocalSnapshotFromLoginResponse(Object response, UUID localId) throws Exception {
        ClassLoader loader = response.getClass().getClassLoader();
        Object responseTree = invokeNoArgs(response, "getOutfitTree");
        Object defaultOutfitUuid = invokeNoArgs(responseTree, "getDefaultOutfitId");
        UUID defaultOutfitId = uuidValue(defaultOutfitUuid);
        Object selectedOutfit = null;
        Object outfitsValue = invokeNoArgs(response, "getOutfitsList");
        if (!(outfitsValue instanceof Iterable)) {
            return null;
        }
        for (Object outfit : (Iterable<?>) outfitsValue) {
            if (defaultOutfitId != null
                    && defaultOutfitId.equals(uuidValue(invokeNoArgs(outfit, "getId")))) {
                selectedOutfit = outfit;
                break;
            }
        }
        if (selectedOutfit == null) {
            return null;
        }

        Class<?> uuidClass = Class.forName("com.lunarclient.common.v1.Uuid", true, loader);
        Object playerUuid = invokeStaticNoArgs(uuidClass, "newBuilder");
        invokePrimitive(playerUuid, "setHigh64", Long.TYPE, localId.getMostSignificantBits());
        invokePrimitive(playerUuid, "setLow64", Long.TYPE, localId.getLeastSignificantBits());
        playerUuid = invokeNoArgs(playerUuid, "build");

        Class<?> pushClass = Class.forName(
                "com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2", true, loader);
        Object builder = invokeStaticNoArgs(pushClass, "newBuilder");
        invokeCompatible(builder, "setPlayerUuid", playerUuid);
        invokeCompatible(builder, "setLogoColor", invokeNoArgs(response, "getLogoColor"));
        invokePrimitive(builder, "setLogoAlwaysShow", Boolean.TYPE,
                invokeNoArgs(response, "getLogoAlwaysShow"));
        invokeCompatible(builder, "setPlusColor", invokeNoArgs(response, "getPlusColor"));
        invokeCompatible(builder, "addAllDefaultCosmetics",
                invokeNoArgs(selectedOutfit, "getCosmeticsList"));
        invokePrimitive(builder, "setBadgeId", Integer.TYPE,
                invokeNoArgs(selectedOutfit, "getBadgeId"));
        invokeCompatible(builder, "setOutfitUuid", defaultOutfitUuid);
        addConditionalOutfits(builder, responseTree, outfitsValue, loader);
        return invokeNoArgs(builder, "build");
    }

    private static void addConditionalOutfits(Object pushBuilder, Object outfitTree,
                                              Object outfits, ClassLoader loader) throws Exception {
        Object value = invokeNoArgs(outfitTree, "getConditionalOutfitsList");
        if (!(value instanceof Iterable)) {
            return;
        }
        Class<?> conditionalClass = Class.forName(
                "com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2$ConditionalCosmetics",
                true, loader);
        for (Object conditionalOutfit : (Iterable<?>) value) {
            UUID conditionalId = uuidValue(invokeNoArgs(conditionalOutfit, "getOutfitId"));
            if (conditionalId == null) {
                continue;
            }
            Object conditionalCosmetics = null;
            for (Object outfit : (Iterable<?>) outfits) {
                if (conditionalId.equals(uuidValue(invokeNoArgs(outfit, "getId")))) {
                    conditionalCosmetics = invokeNoArgs(outfit, "getCosmeticsList");
                    break;
                }
            }
            if (conditionalCosmetics == null) {
                continue;
            }
            Object conditionalBuilder = invokeStaticNoArgs(conditionalClass, "newBuilder");
            invokeCompatible(conditionalBuilder, "setEquipCondition",
                    invokeNoArgs(conditionalOutfit, "getEquipCondition"));
            invokeCompatible(conditionalBuilder, "addAllCosmetics", conditionalCosmetics);
            invokeCompatible(pushBuilder, "addConditionalCosmetics",
                    invokeNoArgs(conditionalBuilder, "build"));
        }
    }

    private static UUID uuidValue(Object lunarUuid) throws Exception {
        if (lunarUuid == null) {
            return null;
        }
        long high = ((Number) invokeNoArgs(lunarUuid, "getHigh64")).longValue();
        long low = ((Number) invokeNoArgs(lunarUuid, "getLow64")).longValue();
        return new UUID(high, low);
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Object invokeStaticNoArgs(Class<?> target, String name) throws Exception {
        return target.getMethod(name).invoke(null);
    }

    private static void invokeCompatible(Object target, String name, Object argument) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException("Lunar method not found: " + name);
    }

    private static void invokePrimitive(Object target, String name, Class<?> parameterType,
                                        Object argument) throws Exception {
        target.getClass().getMethod(name, parameterType).invoke(target, argument);
    }

    private static UUID currentLocalUuid(Minecraft mc) {
        if (mc == null || mc.getSession() == null || mc.getSession().getProfile() == null) {
            return null;
        }
        return mc.getSession().getProfile().getId();
    }

    private static boolean validPlayerName(String playerName) {
        if (playerName == null || playerName.length() < 3 || playerName.length() > 16) return false;
        for (int i = 0; i < playerName.length(); i++) {
            char c = playerName.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }

    private static File persistedProfileDirectory() {
        return new File(GomodPaths.getGomod123Dir(), "lunar-full-disguise-cache");
    }

    private static File persistedProfileFile(UUID playerId) {
        return new File(persistedProfileDirectory(), playerId.toString() + ".gldp");
    }

    /** Reads a cached protobuf after Lunar's current classloader-backed outfit manager is ready. */
    private static boolean loadPersistedProfile(UUID playerId) throws Exception {
        if (playerId == null || outfitManager == null || freshPush(playerId) != null) return false;
        File file = persistedProfileFile(playerId);
        if (!file.isFile()) return false;

        byte[] payload;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != PERSISTED_PROFILE_MAGIC
                    || input.readInt() != PERSISTED_PROFILE_VERSION) {
                throw new IOException("This saved Lunar profile uses an unsupported cache format");
            }
            UUID storedId = new UUID(input.readLong(), input.readLong());
            input.readLong(); // savedAtMs; persistent profiles do not expire.
            input.readUTF(); // last known Minecraft name.
            int length = input.readInt();
            if (!playerId.equals(storedId)) throw new IOException("Saved Lunar profile identity does not match");
            if (length < 1 || length > MAX_PERSISTED_PROFILE_BYTES) {
                throw new IOException("Saved Lunar profile payload has an invalid size");
            }
            payload = new byte[length];
            input.readFully(payload);
            if (input.read() != -1) throw new IOException("Saved Lunar profile has trailing data");
        }

        ClassLoader lunarLoader = outfitManager.getClass().getClassLoader();
        Class<?> pushClass = Class.forName(PERSISTED_PUSH_CLASS, true, lunarLoader);
        Object push = pushClass.getMethod("parseFrom", byte[].class)
                .invoke(null, new Object[]{payload});
        UUID embeddedId = playerUuid(push);
        if (embeddedId != null && !playerId.equals(embeddedId)) {
            throw new IOException("Saved Lunar profile UUID does not match its cache key");
        }
        long now = System.currentTimeMillis();
        PLAYER_PUSHES.put(playerId, new CachedPush(push, now));
        pruneProfiles(now);
        LunarConnectionDiagnostics.record("disguise.full_lunar_cache",
                "result=loaded payloadBytes=" + payload.length);
        return true;
    }

    /** Persists the complete protobuf push only after an explicit successful /disguisefull. */
    private static void savePersistedProfile(UUID playerId, String playerName, Object push)
            throws Exception {
        if (playerId == null || !validPlayerName(playerName) || push == null) {
            throw new IllegalArgumentException("The Lunar profile identity is incomplete");
        }
        Object serialized = push.getClass().getMethod("toByteArray").invoke(push);
        if (!(serialized instanceof byte[])) {
            throw new IllegalStateException("Lunar returned an invalid profile payload");
        }
        byte[] payload = (byte[]) serialized;
        if (payload.length == 0 || payload.length > MAX_PERSISTED_PROFILE_BYTES) {
            throw new IllegalStateException("Lunar profile payload is outside the supported size");
        }

        File directory = persistedProfileDirectory();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create the Lunar profile cache directory");
        }
        File destination = persistedProfileFile(playerId);
        File temporary = File.createTempFile(playerId.toString() + ".", ".tmp", directory);
        boolean moved = false;
        try {
            FileOutputStream fileStream = new FileOutputStream(temporary);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fileStream))) {
                output.writeInt(PERSISTED_PROFILE_MAGIC);
                output.writeInt(PERSISTED_PROFILE_VERSION);
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
                output.writeLong(System.currentTimeMillis());
                output.writeUTF(playerName);
                output.writeInt(payload.length);
                output.write(payload);
                output.flush();
                fileStream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            LunarConnectionDiagnostics.record("disguise.full_lunar_cache",
                    "result=saved payloadBytes=" + payload.length);
        } finally {
            if (!moved && temporary.exists()) temporary.delete();
        }
    }

    private static CachedPush freshPush(UUID playerId) {
        CachedPush value = playerId == null ? null : PLAYER_PUSHES.get(playerId);
        return value == null || System.currentTimeMillis() - value.receivedAtMs > PROFILE_TTL_MS
                ? null : value;
    }

    private static CachedPush freshLocalPush(UUID playerId) {
        CachedPush receivedPush = freshPush(playerId);
        CachedPush loginSnapshot = localLoginSnapshot;
        if (playerId != null && playerId.equals(localLoginSnapshotUuid)
                && loginSnapshot != null
                && (receivedPush == null || loginSnapshot.receivedAtMs > receivedPush.receivedAtMs)) {
            return loginSnapshot;
        }
        return receivedPush;
    }

    private static void ensureWorldScope(Minecraft mc) {
        int current = mc == null || mc.theWorld == null ? 0 : System.identityHashCode(mc.theWorld);
        Object restorePush = null;
        UUID restoreId = null;
        synchronized (LOCK) {
            if (worldIdentity == current) {
                return;
            }
            worldIdentity = current;
            PLAYER_PUSHES.clear();
            PEER_OVERRIDES.clear();
            // The current Lunar outfit store is process-wide, not a world-owned cache. Keep
            // the manager reference across server/world transitions; the next push hook will
            // replace it if Lunar constructs a new manager instance.
            UUID localId = currentLocalUuid(mc);
            CachedPush localSnapshot = localLoginSnapshot;
            if (localId != null && localId.equals(localLoginSnapshotUuid) && localSnapshot != null) {
                PLAYER_PUSHES.put(localId, localSnapshot);
            }
            if (activeSourceUuid != null) {
                restorePush = savedLocalPush;
                restoreId = activeLocalUuid;
                activeSourceUuid = null;
                activeLocalUuid = null;
                savedLocalPush = null;
                lastAppliedLocalDisguisePush = null;
                lastAppliedDisguiseSourcePush = null;
                lastAppliedDisguiseSourceUuid = null;
                lastAppliedDisguiseLocalUuid = null;
                activationGeneration++;
            }
            // Native caches belong to the previous world. Never let a delayed old-world echo
            // reapply its appearance into a newly joined server.
            lastAppliedLocalDisguisePush = null;
            recentRestorePush = null;
            recentRestoreLocalUuid = null;
            recentRestoreUntilMs = 0L;
        }
        // A world change creates new Lunar caches; do not write the old profile into them.
        if (restorePush != null && restoreId != null && current == 0) {
            LunarConnectionDiagnostics.record("disguise.full_lunar_restore", "result=deferred_world_change");
        }
    }

    private static void pruneProfiles(long now) {
        for (Map.Entry<UUID, CachedPush> entry : PLAYER_PUSHES.entrySet()) {
            CachedPush push = entry.getValue();
            if (push == null || now - push.receivedAtMs > PROFILE_TTL_MS) {
                PLAYER_PUSHES.remove(entry.getKey(), push);
            }
        }
        if (PLAYER_PUSHES.size() > MAX_CACHED_PROFILES) {
            UUID local = currentLocalUuid(Minecraft.getMinecraft());
            for (UUID playerId : PLAYER_PUSHES.keySet()) {
                if (!playerId.equals(local) && !playerId.equals(activeSourceUuid)) {
                    PLAYER_PUSHES.remove(playerId);
                    if (PLAYER_PUSHES.size() <= MAX_CACHED_PROFILES) {
                        break;
                    }
                }
            }
        }
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return current == null ? "Lunar appearance could not be applied" : current.getClass().getSimpleName();
        }
        return message.length() > 150 ? message.substring(0, 150) : message;
    }

    private static final class CachedPush {
        final Object push;
        final long receivedAtMs;

        CachedPush(Object push, long receivedAtMs) {
            this.push = push;
            this.receivedAtMs = receivedAtMs;
        }
    }

    private static final class PeerOverride {
        final UUID targetPlayerId;
        volatile Object originalPush;
        final boolean applied;

        PeerOverride(UUID targetPlayerId, Object originalPush, boolean applied) {
            this.targetPlayerId = targetPlayerId;
            this.originalPush = originalPush;
            this.applied = applied;
        }
    }
}
