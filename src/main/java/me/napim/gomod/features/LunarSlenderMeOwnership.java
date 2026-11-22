package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.GomodReleaseBuild;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds installed catalog IDs to this client's in-memory ownership response for local QA only. */
public final class LunarSlenderMeOwnership {
    private static final int SLENDER_ME_ID = 9753;
    // Lunar 2.23.1-2641 native manifest IDs, used only if its catalog manifest is unavailable.
    private static final int[] LIMITED_COSMETIC_FALLBACK_IDS = {4255, 4256, 4257};
    private static final long MAX_MANIFEST_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_MANIFEST_ROWS = 20000;
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_EXPIRED_OVERRIDE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_BADGE_PROVIDER_READY = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_BADGE_PROVIDER_UNREADY = new AtomicBoolean();
    private static volatile ManifestSnapshot cachedManifest;
    private static volatile Set<Integer> localQaCosmeticIds = Collections.emptySet();
    private static volatile OfficialCosmeticOwnershipSnapshot officialOwnedCosmeticSnapshot =
            new OfficialCosmeticOwnershipSnapshot("", Collections.<Integer>emptySet(),
                    Collections.<Integer>emptySet(), false);
    private static volatile CosmeticOwnershipBadgeSnapshot cosmeticOwnershipBadgeSnapshot =
            new CosmeticOwnershipBadgeSnapshot("", Collections.<Integer>emptySet(),
                    Collections.<Integer>emptySet(), false);
    private static volatile OfficialEmoteOwnershipSnapshot officialOwnedEmoteSnapshot =
            new OfficialEmoteOwnershipSnapshot("", Collections.<Integer>emptySet(), false);
    private static volatile long lastBadgeOwnershipRefreshAttemptMs;

    private LunarSlenderMeOwnership() {
    }

    /**
     * The API dev catalog can lag Lunar's installed cosmetic manifest. While the internal
     * FullAccess relay is connected, append the shipped non-special catalog. The internal
     * local cosmetics QA toggle can append catalog rows marked Limited and strip expiry metadata
     * from already-owned cosmetics whose expiration timestamp has passed.
     * Lunar still supplies names, previews, and rendering assets from its native catalog.
     * These are local response edits only; they do not grant or renew account ownership, save an
     * equipped outfit at Lunar's service, or publish the cosmetic to other clients.
     */
    public static List<?> appendIfFullAccess(List<?> owned, ClassLoader lunarLoader, Object responseOwner) {
        if (owned == null) {
            return owned;
        }

        try {
            captureOfficialCosmeticOwnershipForBadges(owned, responseOwner);
            captureOfficialCosmeticOwnership(owned, responseOwner);
        } catch (Throwable ignored) {
            // Badge bookkeeping must never affect Lunar's native ownership response.
        }
        if (lunarLoader == null || !GomodFixes.isWeaveRuntime()) return owned;

        boolean fullAccessEnabled = ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected();
        boolean limitedUnlockerEnabled = fullAccessEnabled
                && ConfigManager.isLunarLimitedCosmeticsUnlockerEnabled();
        if (!fullAccessEnabled && !limitedUnlockerEnabled) {
            return owned;
        }

        ManifestSnapshot manifest = loadManifestSnapshot();
        LinkedHashSet<Integer> existingIds = new LinkedHashSet<Integer>();
        LinkedHashSet<Integer> localQaIds = new LinkedHashSet<Integer>();
        if (limitedUnlockerEnabled) {
            localQaIds.addAll(localQaCosmeticIds);
            localQaIds.addAll(manifest.limitedIds);
        }
        Method idGetter = null;
        try {
            Class<?> ownedType = Class.forName(
                    "com.lunarclient.websocket.cosmetic.v2.OwnedCosmetic", false, lunarLoader);
            idGetter = ownedType.getMethod("getCosmeticId");
            List<Object> normalizedOwned = new ArrayList<Object>(owned.size());
            long nowMillis = System.currentTimeMillis();
            long nowSeconds = nowMillis / 1000L;
            int nowNanos = (int) ((nowMillis % 1000L) * 1000000L);
            for (Object cosmetic : owned) {
                if (cosmetic != null) {
                    Object value = idGetter.invoke(cosmetic);
                    if (value instanceof Number) existingIds.add(Integer.valueOf(((Number) value).intValue()));
                }
                normalizedOwned.add(cosmetic);
            }

            int expiredRowsSanitized = 0;
            if (limitedUnlockerEnabled) {
                for (int i = 0; i < normalizedOwned.size(); i++) {
                    Object cosmetic = normalizedOwned.get(i);
                    if (cosmetic == null || !isExpired(cosmetic, nowSeconds, nowNanos)) continue;
                    Object value = idGetter.invoke(cosmetic);
                    if (value instanceof Number) {
                        localQaIds.add(Integer.valueOf(((Number) value).intValue()));
                    }
                    normalizedOwned.set(i, withoutExpiration(cosmetic));
                    expiredRowsSanitized++;
                }
            }
            if (limitedUnlockerEnabled) {
                localQaCosmeticIds = Collections.unmodifiableSet(localQaIds);
            }

            LinkedHashSet<Integer> missingIds = new LinkedHashSet<Integer>();
            LinkedHashSet<Integer> offeredIds = new LinkedHashSet<Integer>();
            if (fullAccessEnabled) offeredIds.addAll(manifest.ids);
            if (limitedUnlockerEnabled) offeredIds.addAll(manifest.limitedIds);
            for (Integer id : offeredIds) {
                if (id != null && id.intValue() > 0 && !existingIds.contains(id)) missingIds.add(id);
            }
            if (missingIds.isEmpty() && expiredRowsSanitized == 0) {
                logOnce("manifestRows=" + manifest.ids.size() + " limitedRows=" + manifest.limitedIds.size()
                        + " ownedRowsAdded=0 manifestRead=" + manifest.read
                        + " fullAccess=" + fullAccessEnabled + " limitedUnlocker=" + limitedUnlockerEnabled);
                return owned;
            }

            Method newBuilder = ownedType.getMethod("newBuilder");
            List<Object> result = new ArrayList<Object>(owned.size() + missingIds.size());
            result.addAll(normalizedOwned);
            for (Integer id : missingIds) {
                Object builder = newBuilder.invoke(null);
                builder.getClass().getMethod("setCosmeticId", Integer.TYPE)
                        .invoke(builder, id.intValue());
                result.add(builder.getClass().getMethod("build").invoke(builder));
            }
            logOnce("manifestRows=" + manifest.ids.size() + " ownedRowsAdded=" + missingIds.size()
                    + " manifestRead=" + manifest.read + " fullAccess=" + fullAccessEnabled
                    + " limitedUnlocker=" + limitedUnlockerEnabled
                    + " expiredRowsSanitized=" + expiredRowsSanitized);
            if (expiredRowsSanitized > 0 && LOGGED_EXPIRED_OVERRIDE.compareAndSet(false, true)) {
                LunarConnectionDiagnostics.record("cosmetic_catalog.expired_local_override",
                        "expiredRowsSanitized=" + expiredRowsSanitized + " scope=local-internal-qa");
            }
            return result;
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            logOnce("ownedRowsAdded=0 manifestRead=" + manifest.read
                    + " error=" + cause.getClass().getSimpleName());
            return owned;
        }
    }

    /** Returns local, source-separated Locker badge data without exposing account identifiers. */
    public static String cosmeticOwnershipBadgeDataJson() {
        JsonObject result = new JsonObject();
        boolean enabled = GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected()
                && ConfigManager.isLunarCosmeticSourceBadgesEnabled();
        result.addProperty("enabled", enabled);
        JsonArray edited = new JsonArray();
        if (enabled) {
            try {
                for (Long id : GomodCosmeticAppearance.editedCosmeticIds()) {
                    if (id != null && id.longValue() > 0) edited.add(new JsonPrimitive(String.valueOf(id)));
                }
            } catch (Throwable ignored) {
                // Saved appearance badges are optional; keep source/ownership badges available.
            }
        }
        result.add("edited", edited);
        JsonArray editedEmotes=new JsonArray();
        if(enabled)try{for(Integer id:GomodStudioSettings.editedEmoteIds())editedEmotes.add(new JsonPrimitive(String.valueOf(id)));}catch(Throwable ignored){}
        result.add("editedEmotes",editedEmotes);
        JsonArray editedBadges = new JsonArray();
        if (enabled) {
            try {
                for (Integer id : GomodStudioSettings.editedBadgeIds()) {
                    if (id != null && id.intValue() > 0) {
                        editedBadges.add(new JsonPrimitive(String.valueOf(id)));
                    }
                }
            } catch (Throwable ignored) {
                // Badge settings are optional; keep the other Locker source markers available.
            }
        }
        result.add("editedBadges", editedBadges);
        if (!enabled) {
            result.addProperty("ready", false);
            result.add("owned", new JsonArray());
            result.add("limited", new JsonArray());
            result.addProperty("emoteReady", false);
            result.add("ownedEmotes", new JsonArray());
            return result.toString();
        }

        String currentAccount = LunarPublicLoginGuard.currentAccountKey();
        OfficialCosmeticOwnershipSnapshot snapshot = officialOwnedCosmeticSnapshot;
        boolean ready = snapshot.ready && !currentAccount.isEmpty()
                && currentAccount.equals(snapshot.account);
        OfficialEmoteOwnershipSnapshot emoteSnapshot = officialOwnedEmoteSnapshot;
        boolean emoteReady = emoteSnapshot.ready && !currentAccount.isEmpty()
                && currentAccount.equals(emoteSnapshot.account);
        if (!ready || !emoteReady) {
            refreshOfficialOwnershipForBadge();
            currentAccount = LunarPublicLoginGuard.currentAccountKey();
            snapshot = officialOwnedCosmeticSnapshot;
            ready = snapshot.ready && !currentAccount.isEmpty()
                    && currentAccount.equals(snapshot.account);
            emoteSnapshot = officialOwnedEmoteSnapshot;
            emoteReady = emoteSnapshot.ready && !currentAccount.isEmpty()
                    && currentAccount.equals(emoteSnapshot.account);
        }
        result.addProperty("emoteReady", emoteReady);
        JsonArray ownedEmotes = new JsonArray();
        if (emoteReady) {
            for (Integer id : emoteSnapshot.ownedIds) {
                if (id != null && id.intValue() > 0) {
                    ownedEmotes.add(new JsonPrimitive(String.valueOf(id)));
                }
            }
        }
        result.add("ownedEmotes", ownedEmotes);

        CosmeticOwnershipBadgeSnapshot responseSnapshot = cosmeticOwnershipBadgeSnapshot;
        boolean responseReady = responseSnapshot.ready && !currentAccount.isEmpty()
                && currentAccount.equals(responseSnapshot.account);
        boolean providerReady = ready || responseReady;
        result.addProperty("ready", providerReady);
        if (!providerReady) {
            result.add("owned", new JsonArray());
            result.add("limited", new JsonArray());
            logBadgeProviderState(false, ready, false, 0, 0);
            return result.toString();
        }

        Set<Integer> ownedIds = responseReady ? responseSnapshot.ownedIds : snapshot.ownedIds;
        Set<Integer> expiredIds = responseReady ? responseSnapshot.expiredIds : snapshot.expiredIds;

        JsonArray owned = new JsonArray();
        for (Integer id : ownedIds) {
            if (id != null && id.intValue() > 0) owned.add(new JsonPrimitive(String.valueOf(id)));
        }
        result.add("owned", owned);

        JsonArray limited = new JsonArray();
        if (ConfigManager.isLunarLimitedCosmeticsUnlockerEnabled()) {
            LinkedHashSet<Integer> limitedIds = new LinkedHashSet<Integer>(loadManifestSnapshot().limitedIds);
            limitedIds.addAll(localQaCosmeticIds);
            limitedIds.addAll(expiredIds);
            limitedIds.removeAll(ownedIds);
            for (Integer id : limitedIds) {
                if (id != null && id.intValue() > 0) limited.add(new JsonPrimitive(String.valueOf(id)));
            }
        }
        result.add("limited", limited);
        boolean fullAccess = ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected();
        result.addProperty("fullAccess", fullAccess);
        logBadgeProviderState(true, ready, responseReady, owned.size(), limited.size());
        return result.toString();
    }

    /** Captures the pre-injection Locker list only when it belongs to the active official LoginResponse. */
    private static void captureOfficialCosmeticOwnershipForBadges(List<?> owned, Object responseOwner) {
        if (owned == null || responseOwner == null) return;
        try {
            String account = LunarPublicLoginGuard.officialCosmeticLoginAccountKey(responseOwner);
            if (account.isEmpty()) return;

            LinkedHashSet<Integer> ownedIds = new LinkedHashSet<Integer>();
            LinkedHashSet<Integer> expiredIds = new LinkedHashSet<Integer>();
            long nowMillis = System.currentTimeMillis();
            long nowSeconds = nowMillis / 1000L;
            int nowNanos = (int) ((nowMillis % 1000L) * 1000000L);
            for (Object row : owned) {
                if (row == null) continue;
                try {
                    Object value = row.getClass().getMethod("getCosmeticId").invoke(row);
                    if (!(value instanceof Number)) continue;
                    Integer id = Integer.valueOf(((Number) value).intValue());
                    if (id.intValue() <= 0) continue;
                    ownedIds.add(id);
                    try {
                        if (isExpired(row, nowSeconds, nowNanos)) {
                            ownedIds.remove(id);
                            expiredIds.add(id);
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // Keep the official row classified as owned when expiry metadata is unavailable.
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Skip rows that do not expose Lunar's native cosmetic ID getter.
                }
            }
            if (!account.equals(LunarPublicLoginGuard.currentAccountKey())) return;
            cosmeticOwnershipBadgeSnapshot = new CosmeticOwnershipBadgeSnapshot(account,
                    Collections.unmodifiableSet(ownedIds),
                    Collections.unmodifiableSet(expiredIds), true);
        } catch (Throwable ignored) {
            // Source badges are optional UI state; never affect the native Locker response.
        }
    }

    private static void logBadgeProviderState(boolean providerReady, boolean officialReady,
                                               boolean responseReady, int ownedRows, int limitedRows) {
        if (GomodReleaseBuild.PUBLIC_RELEASE) return;
        AtomicBoolean logged = providerReady ? LOGGED_BADGE_PROVIDER_READY : LOGGED_BADGE_PROVIDER_UNREADY;
        if (logged.compareAndSet(false, true)) {
            boolean fullAccess = ConfigManager.isLunarTweaksEnabled()
                    && LunarOnlineStatus.isFullAccessConnected();
            System.out.println("[gomod] Cosmetic source badge data: ready=" + providerReady
                    + " officialSnapshot=" + officialReady + " lockerResponseSnapshot=" + responseReady
                    + " fullAccess=" + fullAccess + " ownedRows=" + ownedRows
                    + " limitedRows=" + limitedRows);
        }
    }

    /**
     * The Locker can mount before the official response's account key is ready. Retry from the
     * currently retained official response so its one-shot network callback cannot leave badges
     * hidden for the rest of the session. The account and response identity are revalidated by
     * the normal capture path; this never reads a FullAccess response as owned state.
     */
    private static void refreshOfficialOwnershipForBadge() {
        long now = System.currentTimeMillis();
        synchronized (LunarSlenderMeOwnership.class) {
            if (now - lastBadgeOwnershipRefreshAttemptMs < 1000L) return;
            lastBadgeOwnershipRefreshAttemptMs = now;
        }
        try {
            Class<?> guard = Class.forName("me.napim.gomod.features.LunarPublicLoginGuard", false,
                    LunarSlenderMeOwnership.class.getClassLoader());
            Object response = guard.getMethod("currentOfficialCosmeticLoginResponse").invoke(null);
            if (response != null) captureOfficialCosmeticOwnership(response);
            Object emoteResponse = guard.getMethod("currentOfficialEmoteLoginResponse").invoke(null);
            if (emoteResponse != null) captureOfficialEmoteOwnership(emoteResponse);
        } catch (Throwable ignored) {
            // A missing/unsupported official response keeps the badges fail-closed.
        }
    }

    /** Captures the official LoginResponse before FullAccess can produce a local Locker list. */
    public static void captureOfficialCosmeticOwnership(Object responseOwner) {
        try {
            String account = LunarPublicLoginGuard.officialCosmeticLoginAccountKey(responseOwner);
            if (account.isEmpty()) return;
            Object raw = responseOwner.getClass().getMethod("getOwnedCosmeticsList").invoke(responseOwner);
            if (raw instanceof List) captureOfficialCosmeticOwnership((List<?>) raw, responseOwner, account);
        } catch (Throwable ignored) { }
    }

    /** Captures explicit ownership from the active official Emote LoginResponse, never the Locker catalogue. */
    public static void captureOfficialEmoteOwnership(Object responseOwner) {
        try {
            String account = LunarPublicLoginGuard.officialEmoteLoginAccountKey(responseOwner);
            if (account.isEmpty()) return;
            LinkedHashSet<Integer> ownedIds = new LinkedHashSet<Integer>();
            boolean ownedRowsAvailable = false;
            try {
                Object rows = responseOwner.getClass().getMethod("getOwnedEmotesList").invoke(responseOwner);
                if (rows instanceof Iterable) {
                    ownedRowsAvailable = true;
                    for (Object row : (Iterable<?>) rows) {
                        Integer id = readEmoteId(row);
                        if (id != null && id.intValue() > 0) ownedIds.add(id);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Older Lunar schemas may only provide the compact owned-ID list.
            }
            // Rich ownership rows are the strongest signal. Only fall back to explicit IDs
            // when the schema lacks rows or the native server sent no rows at all. Never use
            // hasAllEmotesFlag: the dev FullAccess route sets that catalog-access flag locally.
            if (!ownedRowsAvailable || ownedIds.isEmpty()) {
                Object rawIds = responseOwner.getClass().getMethod("getOwnedEmoteIdsList").invoke(responseOwner);
                if (rawIds instanceof Iterable) {
                    for (Object value : (Iterable<?>) rawIds) {
                        Integer id = value instanceof Number
                                ? Integer.valueOf(((Number) value).intValue()) : readEmoteId(value);
                        if (id != null && id.intValue() > 0) ownedIds.add(id);
                    }
                }
            }
            if (!account.equals(LunarPublicLoginGuard.officialEmoteLoginAccountKey(responseOwner))
                    || !account.equals(LunarPublicLoginGuard.currentAccountKey())) return;
            officialOwnedEmoteSnapshot = new OfficialEmoteOwnershipSnapshot(account,
                    Collections.unmodifiableSet(ownedIds), true);
            System.out.println("[gomod] Official Emote ownership captured: protocol="
                    + responseOwner.getClass().getName().substring("com.lunarclient.websocket.".length())
                    + " ownedRows=" + ownedIds.size() + " source=account-response");
        } catch (Throwable ignored) {
            // Unsupported schemas remain fail-closed; never infer Emote ownership from visible tiles.
        }
    }

    private static Integer readEmoteId(Object value) {
        if (value == null) return null;
        try {
            Object raw = value.getClass().getMethod("getEmoteId").invoke(value);
            return raw instanceof Number ? Integer.valueOf(((Number) raw).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void captureOfficialCosmeticOwnership(List<?> owned, Object responseOwner) {
        try {
            String account = LunarPublicLoginGuard.officialCosmeticLoginAccountKey(responseOwner);
            if (!account.isEmpty()) captureOfficialCosmeticOwnership(owned, responseOwner, account);
        } catch (Throwable ignored) { }
    }

    private static void captureOfficialCosmeticOwnership(List<?> owned, Object responseOwner, String account) {
        if (owned == null || account == null || account.isEmpty()) return;
        LinkedHashSet<Integer> ownedIds = new LinkedHashSet<Integer>();
        LinkedHashSet<Integer> expiredIds = new LinkedHashSet<Integer>();
        long nowMillis = System.currentTimeMillis();
        long nowSeconds = nowMillis / 1000L;
        int nowNanos = (int) ((nowMillis % 1000L) * 1000000L);
        for (Object row : owned) {
            if (row == null) continue;
            try {
                Object rawId = row.getClass().getMethod("getCosmeticId").invoke(row);
                if (rawId instanceof Number && ((Number) rawId).intValue() > 0) {
                    Integer id = Integer.valueOf(((Number) rawId).intValue());
                    ownedIds.add(id);
                    if (isExpired(row, nowSeconds, nowNanos)) {
                        ownedIds.remove(id);
                        expiredIds.add(id);
                    }
                }
            } catch (Throwable ignored) { }
        }
        try {
            if (!account.equals(LunarPublicLoginGuard.officialCosmeticLoginAccountKey(responseOwner))) return;
        } catch (Throwable ignored) {
            return;
        }
        officialOwnedCosmeticSnapshot = new OfficialCosmeticOwnershipSnapshot(account,
                Collections.unmodifiableSet(ownedIds), Collections.unmodifiableSet(expiredIds), true);
    }

    /** True only for cosmetics exposed by this client's internal Limited/expired QA toggle. */
    public static boolean isLocalQaCosmetic(int cosmeticId) {
        return cosmeticId > 0
                && GomodFixes.isWeaveRuntime()
                && ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessConnected()
                && ConfigManager.isLunarLimitedCosmeticsUnlockerEnabled()
                && localQaCosmeticIds.contains(Integer.valueOf(cosmeticId));
    }

    private static boolean isExpired(Object cosmetic, long nowSeconds, int nowNanos)
            throws ReflectiveOperationException {
        Object hasExpiresAt = cosmetic.getClass().getMethod("hasExpiresAt").invoke(cosmetic);
        if (!Boolean.TRUE.equals(hasExpiresAt)) return false;
        Object expiresAt = cosmetic.getClass().getMethod("getExpiresAt").invoke(cosmetic);
        long seconds = ((Number) expiresAt.getClass().getMethod("getSeconds").invoke(expiresAt)).longValue();
        int nanos = ((Number) expiresAt.getClass().getMethod("getNanos").invoke(expiresAt)).intValue();
        return seconds < nowSeconds || (seconds == nowSeconds && nanos <= nowNanos);
    }

    private static Object withoutExpiration(Object cosmetic) throws ReflectiveOperationException {
        Object builder = cosmetic.getClass().getMethod("toBuilder").invoke(cosmetic);
        builder.getClass().getMethod("clearExpiresAt").invoke(builder);
        builder.getClass().getMethod("clearExpirationReason").invoke(builder);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static ManifestSnapshot loadManifestSnapshot() {
        Path path = nativeManifestPath();
        long modified = -1L;
        long size = -1L;
        try {
            if (Files.isRegularFile(path)) {
                FileTime time = Files.getLastModifiedTime(path);
                modified = time.toMillis();
                size = Files.size(path);
            }
        } catch (IOException ignored) {
            // Fall back to the known Slender Me row below.
        }

        ManifestSnapshot snapshot = cachedManifest;
        if (snapshot != null && snapshot.modified == modified && snapshot.size == size) return snapshot;
        synchronized (LunarSlenderMeOwnership.class) {
            snapshot = cachedManifest;
            if (snapshot != null && snapshot.modified == modified && snapshot.size == size) return snapshot;

            LinkedHashSet<Integer> ids = new LinkedHashSet<Integer>();
            LinkedHashSet<Integer> limitedIds = new LinkedHashSet<Integer>();
            boolean read = false;
            if (size > 0L && size <= MAX_MANIFEST_BYTES) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement root = new JsonParser().parse(reader);
                    if (root.isJsonArray() && root.getAsJsonArray().size() <= MAX_MANIFEST_ROWS) {
                        JsonArray rows = root.getAsJsonArray();
                        for (JsonElement element : rows) {
                            if (!element.isJsonObject()) continue;
                            JsonObject row = element.getAsJsonObject();
                            if (row.has("special") && row.get("special").isJsonPrimitive()
                                    && row.get("special").getAsBoolean()) continue;
                            JsonElement id = row.get("id");
                            if (id == null || !id.isJsonPrimitive()) continue;
                            try {
                                int value = id.getAsInt();
                                if (value > 0) {
                                    ids.add(Integer.valueOf(value));
                                    JsonElement name = row.get("name");
                                    boolean hasLimitedName = name != null && name.isJsonPrimitive()
                                            && name.getAsString().trim().toLowerCase(Locale.ROOT)
                                            .endsWith("(limited)");
                                    boolean limitedFlag = row.has("limited") && row.get("limited").isJsonPrimitive()
                                            && row.get("limited").getAsBoolean();
                                    if (hasLimitedName || limitedFlag) limitedIds.add(Integer.valueOf(value));
                                }
                            } catch (RuntimeException ignored) {
                                // Ignore malformed entries while retaining valid native catalog rows.
                            }
                        }
                        read = !ids.isEmpty();
                    }
                } catch (Throwable ignored) {
                    ids.clear();
                }
            }
            for (int id : LIMITED_COSMETIC_FALLBACK_IDS) limitedIds.add(Integer.valueOf(id));
            // Preserve the previously fixed item if Lunar moves or temporarily omits its manifest.
            ids.add(Integer.valueOf(SLENDER_ME_ID));
            snapshot = new ManifestSnapshot(modified, size,
                    Collections.unmodifiableSet(ids), Collections.unmodifiableSet(limitedIds), read);
            cachedManifest = snapshot;
            return snapshot;
        }
    }

    private static Path nativeManifestPath() {
        String home = System.getProperty("user.home", "");
        return Paths.get(home, ".lunarclient", "textures", "assets", "lunar", "cosmetics.json");
    }

    private static void logOnce(String details) {
        if (LOGGED.compareAndSet(false, true)) {
            LunarConnectionDiagnostics.record("cosmetic_catalog.native_manifest",
                    details + " scope=internal-dev-catalog");
        }
    }

    private static final class ManifestSnapshot {
        final long modified;
        final long size;
        final Set<Integer> ids;
        final Set<Integer> limitedIds;
        final boolean read;

        ManifestSnapshot(long modified, long size, Set<Integer> ids, Set<Integer> limitedIds, boolean read) {
            this.modified = modified;
            this.size = size;
            this.ids = ids;
            this.limitedIds = limitedIds;
            this.read = read;
        }
    }

    private static final class OfficialCosmeticOwnershipSnapshot {
        private final String account;
        private final Set<Integer> ownedIds;
        private final Set<Integer> expiredIds;
        private final boolean ready;

        private OfficialCosmeticOwnershipSnapshot(String account, Set<Integer> ownedIds, Set<Integer> expiredIds,
                                                  boolean ready) {
            this.account = account;
            this.ownedIds = ownedIds;
            this.expiredIds = expiredIds;
            this.ready = ready;
        }
    }

    private static final class CosmeticOwnershipBadgeSnapshot {
        private final String account;
        private final Set<Integer> ownedIds;
        private final Set<Integer> expiredIds;
        private final boolean ready;

        private CosmeticOwnershipBadgeSnapshot(String account, Set<Integer> ownedIds,
                                               Set<Integer> expiredIds, boolean ready) {
            this.account = account;
            this.ownedIds = ownedIds;
            this.expiredIds = expiredIds;
            this.ready = ready;
        }
    }

    private static final class OfficialEmoteOwnershipSnapshot {
        private final String account;
        private final Set<Integer> ownedIds;
        private final boolean ready;

        private OfficialEmoteOwnershipSnapshot(String account, Set<Integer> ownedIds, boolean ready) {
            this.account = account;
            this.ownedIds = ownedIds;
            this.ready = ready;
        }
    }
}
