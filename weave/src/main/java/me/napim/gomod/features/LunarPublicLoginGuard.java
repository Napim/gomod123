package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.lang.reflect.Method;
import java.util.*;

/** Public Locker entitlement source is the real official Assets login, never relay catalog additions. */
public final class LunarPublicLoginGuard {
    /** Returned when an official cosmetic login confirms that no Lunar+ color is selected. */
    public static final int OFFICIAL_PLUS_UNSELECTED = -2;

    private static final ThreadLocal<Integer> SOURCE = new ThreadLocal<Integer>();
    private static final Map<Class<?>, Object> OFFICIAL = new HashMap<Class<?>, Object>();
    private static final Map<Class<?>, Object> FULL_ACCESS = new HashMap<Class<?>, Object>();
    private static final LinkedHashMap<String, JsonObject> OFFICIAL_PUSH_COSMETICS = new LinkedHashMap<String, JsonObject>();
    private static final LinkedHashMap<String, JsonObject> FULL_ACCESS_PUSH_COSMETICS = new LinkedHashMap<String, JsonObject>();
    private static final Map<Object, String> SOCKET_ACCOUNTS = new WeakHashMap<Object, String>();
    private static String account;
    private static volatile PlusRenderState plusRenderState = new PlusRenderState(null, -1, false);
    private static boolean officialPushCosmeticsObserved;
    private static boolean fullAccessPushCosmeticsObserved;
    private static String officialPushOutfitId = "";
    private static String fullAccessPushOutfitId = "";
    private static Object localLogo, localPlus;
    private static Boolean localAlwaysShow;
    private static Integer localBadge;
    private static Object officialSelfPush;
    private static long officialLoadoutUpdatedAt;
    private static String lastOfficialLoadoutDiag = "";
    private static JsonObject lastLockerSnapshot;
    private static long lastLockerSnapshotReportMs;
    private static String lastLockerSnapshotAccount;
    private static final long LOCKER_SNAPSHOT_REPORT_INTERVAL_MS = 60_000L;
    private static final int LOCKER_SNAPSHOT_ITEMS_PER_CATEGORY = 120;
    private static final Set<String> KINDS = new HashSet<String>(Arrays.asList(
            "cosmetic", "emote", "spray", "badge", "jam"));
    private LunarPublicLoginGuard() { }

    /** FullAccess unlocks are available in either Lunar flavor; OFF retains real ownership only. */
    public static boolean isOfficialOwnershipOnly() {
        return GomodReleaseBuild.PUBLIC_RELEASE && !ConfigManager.isLunarTweaksEnabled();
    }

    public static synchronized void rememberSocketAccount(Object socket) {
        if (socket == null) return;
        refreshAccount();
        if (account != null) SOCKET_ACCOUNTS.put(socket, account);
    }

    public static void beginFrame(Object socket) {
        refreshAccount();
        int source = 0;
        try {
            URI uri = (URI) socket.getClass().getMethod("getURI").invoke(socket);
            String host = uri.getHost();
            if ("gomod-service.onrender.com".equalsIgnoreCase(host) && "/ws/game".equals(uri.getPath())) source = 2;
            else if (host != null && host.toLowerCase(Locale.ROOT).endsWith(".lunarclientprod.com")
                    && "/game".equals(uri.getPath())) source = 1;
        } catch (ReflectiveOperationException ignored) { }
        catch (RuntimeException ignored) { }
        synchronized (LunarPublicLoginGuard.class) {
            String socketAccount = SOCKET_ACCOUNTS.get(socket);
            if (socketAccount == null && account != null) SOCKET_ACCOUNTS.put(socket, account);
            else if (!Objects.equals(socketAccount, account)) source = 0;
        }
        SOURCE.set(Integer.valueOf(source));
    }
    public static void endFrame() { SOURCE.remove(); }

    /** Adds bounded, source-separated Lunar and FullAccess Locker responses to admin usage check-ins. */
    public static synchronized void attachUsageLockerSnapshot(JsonObject payload) {
        if (payload == null || !ConfigManager.isLunarTweaksEnabled()) return;
        refreshAccount();
        if (account == null) return;
        long now = System.currentTimeMillis();
        if (lastLockerSnapshot != null && account.equals(lastLockerSnapshotAccount)
                && now - lastLockerSnapshotReportMs < LOCKER_SNAPSHOT_REPORT_INTERVAL_MS) return;
        JsonObject official = captureOfficialLockerSnapshot(now);
        JsonObject fullAccess = captureFullAccessLockerSnapshot(now);
        if (official == null && fullAccess == null) return;
        JsonObject snapshot = official == null ? new JsonObject() : official;
        snapshot.addProperty("version", 1);
        snapshot.addProperty("capturedAt", now);
        if (fullAccess != null) snapshot.add("fullAccess", fullAccess);
        lastLockerSnapshot = snapshot;
        lastLockerSnapshotAccount = account;
        lastLockerSnapshotReportMs = now;
        payload.add("lunarLockerSnapshot", snapshot);
    }

    /** Refresh the next reported Locker snapshot after Lunar's native cosmetic names load. */
    static synchronized void cosmeticCatalogNamesAvailable() {
        lastLockerSnapshotReportMs = 0L;
    }

    private static JsonObject captureOfficialLockerSnapshot(long now) {
        return captureLockerSnapshot(OFFICIAL, now, "official-lunar");
    }

    private static JsonObject captureFullAccessLockerSnapshot(long now) {
        return captureLockerSnapshot(FULL_ACCESS, now, "fullaccess-relay");
    }

    private static JsonObject captureLockerSnapshot(Map<Class<?>, Object> responses, long now, String origin) {
        JsonObject categories = new JsonObject();
        JsonObject categoryCounts = new JsonObject();
        JsonObject equipped = new JsonObject();
        JsonObject equippedCounts = new JsonObject();
        Map<String, LinkedHashMap<String, JsonObject>> ownedByCategory = new HashMap<String, LinkedHashMap<String, JsonObject>>();
        Map<String, LinkedHashMap<String, JsonObject>> equippedByCategory = new HashMap<String, LinkedHashMap<String, JsonObject>>();
        Map<String, Integer> ownedCountsByCategory = new HashMap<String, Integer>();
        boolean truncated = false;
        String outfitName = "";
        String[] services = {"cosmetic", "emote", "spray", "badge", "jam"};
        String[] labels = {"cosmetics", "emotes", "sprays", "badges", "jams"};
        String prefix = "com.lunarclient.websocket.";
        for (Object response : responses.values()) {
            if (response == null) continue;
            String className = response.getClass().getName();
            if (!className.startsWith(prefix) || !className.endsWith(".LoginResponse")) continue;
            String service = className.substring(prefix.length(), className.length() - ".LoginResponse".length())
                    .split("\\.")[0].toLowerCase(Locale.ROOT);
            int categoryIndex = -1;
            for (int i = 0; i < services.length; i++) if (services[i].equals(service)) categoryIndex = i;
            if (categoryIndex < 0) continue;
            String category = labels[categoryIndex];
            LinkedHashMap<String, JsonObject> ownedItems = ownedByCategory.computeIfAbsent(category,
                    ignored -> new LinkedHashMap<String, JsonObject>());
            LinkedHashMap<String, JsonObject> equippedItems = equippedByCategory.computeIfAbsent(category,
                    ignored -> new LinkedHashMap<String, JsonObject>());
            int ownedCount = -1;
            for (Method method : response.getClass().getMethods()) {
                String methodName = method.getName();
                String normalized = methodName.toLowerCase(Locale.ROOT);
                if (method.getParameterTypes().length != 0) continue;
                try {
                    if (methodName.startsWith("getOwned") && normalized.contains(services[categoryIndex])
                            && !normalized.contains("orbuilder") && !normalized.contains("idslist")
                            && methodName.endsWith("List")) {
                        Object raw = method.invoke(response);
                        if (!(raw instanceof Iterable)) continue;
                        int count = 0;
                        for (Object item : (Iterable<?>) raw) {
                            count++;
                            addLockerSnapshotItem(ownedItems, item, false, categoryIndex == 0);
                        }
                        ownedCount = Math.max(ownedCount, count);
                        if (count > LOCKER_SNAPSHOT_ITEMS_PER_CATEGORY) truncated = true;
                    } else if (methodName.startsWith("getOwned") && normalized.contains(services[categoryIndex])
                            && methodName.endsWith("Count")) {
                        Object value = method.invoke(response);
                        if (value instanceof Number) ownedCount = Math.max(ownedCount, ((Number) value).intValue());
                    } else if (methodName.startsWith("getEquipped") && normalized.contains(services[categoryIndex])
                            && !normalized.contains("orbuilder") && !normalized.contains("idslist")
                            && methodName.endsWith("List") && (categoryIndex == 1 || categoryIndex == 2)) {
                        Object raw = method.invoke(response);
                        if (raw instanceof Iterable) for (Object item : (Iterable<?>) raw) {
                            addLockerSnapshotItem(equippedItems, item, true);
                        }
                    }
                } catch (ReflectiveOperationException ignored) { }
            }

            if (categoryIndex == 0 && className.contains("cosmetic.v2.")) {
                outfitName = appendDefaultOutfitCosmetics(response, equippedItems);
            } else if (categoryIndex == 3) {
                try {
                    Object rawBadge = response.getClass().getMethod("getEquippedBadgeId").invoke(response);
                    if (rawBadge instanceof Number && ((Number) rawBadge).intValue() > 0) {
                        addLockerSnapshotItem(equippedItems, rawBadge, true);
                    }
                } catch (ReflectiveOperationException ignored) { }
            }

            if (ownedCount >= 0) ownedCountsByCategory.put(category,
                    Math.max(ownedCountsByCategory.getOrDefault(category, 0), ownedCount));

            if (categoryIndex == 1) {
                int idsCount = addLockerSnapshotIdList(response, "getOwnedEmoteIdsList", ownedItems, false);
                ownedCount = Math.max(ownedCount, idsCount);
                addLockerSnapshotIdList(response, "getEquippedEmoteIdsList", equippedItems, false);
                if (ownedCount >= 0) ownedCountsByCategory.put(category,
                        Math.max(ownedCountsByCategory.getOrDefault(category, 0), ownedCount));
            }
        }

        boolean pushedCosmeticsObserved = "official-lunar".equals(origin)
                ? officialPushCosmeticsObserved : fullAccessPushCosmeticsObserved;
        if (pushedCosmeticsObserved) {
            LinkedHashMap<String, JsonObject> pushed = "official-lunar".equals(origin)
                    ? OFFICIAL_PUSH_COSMETICS : FULL_ACCESS_PUSH_COSMETICS;
            equippedByCategory.put("cosmetics", new LinkedHashMap<String, JsonObject>(pushed));
            String pushedOutfitId = "official-lunar".equals(origin)
                    ? officialPushOutfitId : fullAccessPushOutfitId;
            outfitName = findOutfitName(responses, pushedOutfitId);
        }

        for (String category : labels) {
            LinkedHashMap<String, JsonObject> ownedItems = ownedByCategory.get(category);
            int ownedCount = Math.max(ownedCountsByCategory.getOrDefault(category, 0),
                    ownedItems == null ? 0 : ownedItems.size());
            if (ownedCount > 0 || ownedCountsByCategory.containsKey(category)) {
                JsonArray items = lockerItemsJson(ownedItems == null
                        ? new LinkedHashMap<String, JsonObject>() : ownedItems);
                categories.add(category, items);
                categoryCounts.addProperty(category, ownedCount);
                if (ownedCount > items.size()) truncated = true;
            }
            LinkedHashMap<String, JsonObject> equippedItems = equippedByCategory.get(category);
            if (equippedItems != null && !equippedItems.isEmpty()) {
                JsonArray items = lockerItemsJson(equippedItems);
                equipped.add(category, items);
                equippedCounts.addProperty(category, items.size());
                if (equippedItems.size() > items.size()) truncated = true;
            }
        }
        if (categoryCounts.size() == 0 && equippedCounts.size() == 0) return null;
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("version", 1);
        snapshot.addProperty("capturedAt", now);
        snapshot.addProperty("origin", origin);
        snapshot.add("categories", categories);
        snapshot.add("categoryCounts", categoryCounts);
        snapshot.add("equipped", equipped);
        snapshot.add("equippedCounts", equippedCounts);
        if (!outfitName.isEmpty()) snapshot.addProperty("activeOutfit", outfitName);
        snapshot.addProperty("truncated", truncated);
        return snapshot;
    }

    private static int addLockerSnapshotIdList(Object response, String getter,
            LinkedHashMap<String, JsonObject> target, boolean useCosmeticCatalog) {
        try {
            Object raw = response.getClass().getMethod(getter).invoke(response);
            if (!(raw instanceof Iterable)) return -1;
            int count = 0;
            for (Object value : (Iterable<?>) raw) {
                JsonObject normalized = lockerSnapshotItem(value, useCosmeticCatalog);
                if (normalized == null) continue;
                count++;
                String id = normalized.get("id").getAsString();
                if (!target.containsKey(id)) target.put(id, normalized);
            }
            return count;
        } catch (ReflectiveOperationException ignored) {
            return -1;
        }
    }

    private static String findOutfitName(Map<Class<?>, Object> responses, String outfitId) {
        if (outfitId == null || outfitId.isEmpty()) return "";
        for (Object response : responses.values()) {
            if (response == null || !response.getClass().getName().equals(
                    "com.lunarclient.websocket.cosmetic.v2.LoginResponse")) continue;
            try {
                Object rawOutfits = invoke(response, "getOutfitsList");
                if (!(rawOutfits instanceof Iterable)) continue;
                for (Object outfit : (Iterable<?>) rawOutfits) {
                    if (!outfitId.equals(lockerScalar(invoke(outfit, "getId")))) continue;
                    String name = String.valueOf(invoke(outfit, "getName"))
                            .replaceAll("[\\p{Cntrl}]", "").trim();
                    return name.length() > 160 ? name.substring(0, 160) : name;
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return "";
    }

    private static JsonArray lockerItemsJson(LinkedHashMap<String, JsonObject> source) {
        JsonArray result = new JsonArray();
        int count = 0;
        for (JsonObject item : source.values()) {
            if (count++ >= LOCKER_SNAPSHOT_ITEMS_PER_CATEGORY) break;
            result.add(item);
        }
        return result;
    }

    private static void addLockerSnapshotItem(LinkedHashMap<String, JsonObject> target, Object item,
                                                boolean equipped) {
        addLockerSnapshotItem(target, item, equipped, false);
    }

    private static void addLockerSnapshotItem(LinkedHashMap<String, JsonObject> target, Object item,
                                                boolean equipped, boolean useCosmeticCatalog) {
        JsonObject normalized = lockerSnapshotItem(item, useCosmeticCatalog);
        if (normalized == null) return;
        String id = normalized.get("id").getAsString();
        if (equipped) {
            addNumericDetail(normalized, item, "getSlotNumber", "slot");
            addNumericDetail(normalized, item, "getAttachedJamId", "attachedJamId");
        }
        target.put(id, normalized);
    }

    private static void addNumericDetail(JsonObject target, Object item, String getter, String key) {
        try {
            Object value = item.getClass().getMethod(getter).invoke(item);
            if (value instanceof Number && (((Number) value).intValue() > 0
                    || "getSlotNumber".equals(getter) && ((Number) value).intValue() == 0)) {
                target.addProperty(key, ((Number) value).intValue());
            }
        } catch (ReflectiveOperationException ignored) { }
    }

    private static String appendDefaultOutfitCosmetics(Object response,
            LinkedHashMap<String, JsonObject> equippedItems) {
        try {
            Object tree = response.getClass().getMethod("getOutfitTree").invoke(response);
            Object defaultId = tree.getClass().getMethod("getDefaultOutfitId").invoke(tree);
            String defaultKey = lockerScalar(defaultId);
            Object rawOutfits = response.getClass().getMethod("getOutfitsList").invoke(response);
            if (!(rawOutfits instanceof Iterable)) return "";
            for (Object outfit : (Iterable<?>) rawOutfits) {
                Object outfitId = outfit.getClass().getMethod("getId").invoke(outfit);
                if (defaultKey.isEmpty() || !defaultKey.equals(lockerScalar(outfitId))) continue;
                Object rawName = outfit.getClass().getMethod("getName").invoke(outfit);
                String name = rawName == null ? "" : String.valueOf(rawName).replaceAll("[\\p{Cntrl}]", "").trim();
                Object rawCosmetics = outfit.getClass().getMethod("getCosmeticsList").invoke(outfit);
                if (rawCosmetics instanceof Iterable) for (Object cosmetic : (Iterable<?>) rawCosmetics) {
                    addLockerSnapshotItem(equippedItems, cosmetic, true, true);
                }
                return name.length() > 160 ? name.substring(0, 160) : name;
            }
        } catch (ReflectiveOperationException ignored) { }
        return "";
    }

    private static JsonObject lockerSnapshotItem(Object item, boolean useCosmeticCatalog) {
        if (item == null) return null;
        String id = firstLockerItemValue(item, "getId", "getUuid", "getCosmeticId", "getEmoteId",
                "getSprayId", "getBadgeId", "getJamId", "getKey");
        String name = firstLockerItemValue(item, "getName", "getDisplayName", "getTitle", "getLabel");
        if (item instanceof CharSequence || item instanceof Number || item instanceof Enum<?>) {
            id = lockerScalar(item);
            if (name.isEmpty()) name = id;
        }
        if (id.isEmpty()) id = name;
        if (name.isEmpty()) name = id;
        if (id.isEmpty()) return null;
        if (useCosmeticCatalog && name.equals(id)) {
            String catalogName = LunarGomodMascotCatalog.cosmeticName(id);
            if (!catalogName.isEmpty()) name = catalogName;
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", id.length() > 160 ? id.substring(0, 160) : id);
        result.addProperty("name", name.length() > 160 ? name.substring(0, 160) : name);
        return result;
    }

    private static String firstLockerItemValue(Object item, String... getters) {
        for (String getter : getters) {
            try {
                String value = lockerScalar(item.getClass().getMethod(getter).invoke(item));
                if (!value.isEmpty()) return value;
            } catch (ReflectiveOperationException ignored) { }
        }
        return "";
    }

    private static String lockerScalar(Object value) {
        if (value == null) return "";
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value).replaceAll("[\\p{Cntrl}]", "").trim();
        }
        try {
            long high = ((Number) value.getClass().getMethod("getHigh64").invoke(value)).longValue();
            long low = ((Number) value.getClass().getMethod("getLow64").invoke(value)).longValue();
            return new UUID(high, low).toString();
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
    }

    /** Invoked immediately after the native RPC builder builds, before its scheduled client callback. */
    public static synchronized Object filterLogin(Object response) {
        if (response == null) return null;
        String name = response.getClass().getName();
        if (name.equals("com.lunarclient.websocket.cosmetic.v2.LoadTabLogosResponse")) {
            if (isOfficialOwnershipOnly()) return filterTabLogos(response);
            return filterInternalTabPlus(response);
        }
        String prefix = "com.lunarclient.websocket.";
        if (!name.startsWith(prefix) || !name.endsWith(".LoginResponse")) return response;
        String kind = name.substring(prefix.length(), name.length() - ".LoginResponse".length());
        if (!KINDS.contains(kind.split("\\.")[0])) return response;
        Integer source = SOURCE.get();
        if (Integer.valueOf(1).equals(source)) {
            OFFICIAL.put(response.getClass(), response);
            lastLockerSnapshotReportMs = 0L;
            if (kind.equals("cosmetic.v2")) {
                rememberColors(response);
                LunarSlenderMeOwnership.captureOfficialCosmeticOwnership(response);
            } else if (kind.equals("emote.v1") || kind.equals("emote.v2")) {
                LunarSlenderMeOwnership.captureOfficialEmoteOwnership(response);
            }
            if (!isOfficialOwnershipOnly()) {
                if (kind.equals("cosmetic.v2")) return filterInternalPlus(response);
                if (kind.equals("badge.v1")) return LunarCustomBadgeCatalog.appendOwnedBadges(response);
                return response;
            }
            System.out.println("[gomod] Public Locker official login retained: service=" + kind);
            return kind.equals("badge.v1") ? LunarCustomBadgeCatalog.appendOwnedBadges(response) : response;
        }
        if (Integer.valueOf(2).equals(source)) {
            FULL_ACCESS.put(response.getClass(), response);
            lastLockerSnapshotReportMs = 0L;
            if (!isOfficialOwnershipOnly()) {
                if (kind.equals("cosmetic.v2")) {
                    return filterInternalPlus(LunarOutfitPersistence.restoreLogin(response));
                }
                if (kind.equals("badge.v1")) return LunarCustomBadgeCatalog.appendOwnedBadges(response);
                return response;
            }
        } else if (!isOfficialOwnershipOnly()) {
            if (kind.equals("cosmetic.v2")) return filterInternalPlus(response);
            if (kind.equals("badge.v1")) return LunarCustomBadgeCatalog.appendOwnedBadges(response);
            return response;
        }
        // The same response is recorded for the dashboard before the public build keeps
        // relay-added ownership out of Lunar's live native Locker state.
        Object official = OFFICIAL.get(response.getClass());
        if (official != null) {
            System.out.println("[gomod] Public Locker non-official login uses official entitlements: service=" + kind);
            return kind.equals("badge.v1") ? LunarCustomBadgeCatalog.appendOwnedBadges(official) : official;
        }
        try {
            System.out.println("[gomod] Public Locker relay login withheld: no official service=" + kind);
            if (kind.equals("cosmetic.v2")) return emptyCosmeticLogin(response.getClass());
            Object empty = response.getClass().getMethod("getDefaultInstance").invoke(null);
            return kind.equals("badge.v1") ? LunarCustomBadgeCatalog.appendOwnedBadges(empty) : empty;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot preserve public Lunar ownership for " + kind, error);
        }
    }
    /** No ownership while refreshing an account, but keep Lunar's required default outfit/tree valid. */
    private static Object emptyCosmeticLogin(Class<?> responseType) throws ReflectiveOperationException {
        ClassLoader loader = responseType.getClassLoader();
        Class<?> uuidType = Class.forName("com.lunarclient.common.v1.Uuid", false, loader);
        UUID id = UUID.nameUUIDFromBytes(("gomod-public-default-outfit:" + account).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object uuidBuilder = uuidType.getMethod("newBuilder").invoke(null);
        uuidBuilder.getClass().getMethod("setHigh64", long.class).invoke(uuidBuilder, id.getMostSignificantBits());
        uuidBuilder.getClass().getMethod("setLow64", long.class).invoke(uuidBuilder, id.getLeastSignificantBits());
        Object uuid = invoke(uuidBuilder, "build");
        Object outfitBuilder = Class.forName("com.lunarclient.websocket.cosmetic.v2.Outfit", false, loader).getMethod("newBuilder").invoke(null);
        set(outfitBuilder, "setId", uuid); set(outfitBuilder, "setName", "Default Outfit");
        Object treeBuilder = Class.forName("com.lunarclient.websocket.cosmetic.v2.OutfitTree", false, loader).getMethod("newBuilder").invoke(null);
        set(treeBuilder, "setDefaultOutfitId", uuid);
        Object builder = responseType.getMethod("newBuilder").invoke(null);
        set(builder, "addOutfits", invoke(outfitBuilder, "build")); set(builder, "setOutfitTree", invoke(treeBuilder, "build"));
        return invoke(builder, "build");
    }
    /** A relay profile push can add its default + after the genuine login has finished. */
    public static synchronized Object filterPush(Object push) {
        if (push == null || !push.getClass().getName().equals(
                "com.lunarclient.websocket.cosmetic.v2.PlayerCosmeticsPushV2")) return push;
        refreshAccount();
        try {
            if (!isLocal(invoke(push, "getPlayerUuid"))) return push;
            Integer source = SOURCE.get();
            if (Integer.valueOf(1).equals(source)) {
                recordOfficialSelfProfilePush(push);
                return isOfficialOwnershipOnly() ? push : filterInternalPlus(push);
            }
            if (Integer.valueOf(2).equals(source)) capturePushCosmetics(push, 2);
            if (!isOfficialOwnershipOnly()) return filterInternalPlus(push);
            Object builder = invoke(push, "toBuilder"); applyColors(builder);
            if (localAlwaysShow != null) builder.getClass().getMethod("setLogoAlwaysShow", boolean.class).invoke(builder, localAlwaysShow);
            if (localBadge != null) builder.getClass().getMethod("setBadgeId", int.class).invoke(builder, localBadge);
            System.out.println("[gomod] Public local profile keeps only verified official logo/+; peer profiles unchanged");
            return invoke(builder, "build");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot preserve public local appearance", error); }
    }

    private static void capturePushCosmetics(Object push, int source) throws ReflectiveOperationException {
        Object raw = invoke(push, "getDefaultCosmeticsList");
        LinkedHashMap<String, JsonObject> cosmetics = new LinkedHashMap<String, JsonObject>();
        if (raw instanceof Iterable) for (Object cosmetic : (Iterable<?>) raw) {
            addLockerSnapshotItem(cosmetics, cosmetic, true, true);
        }
        String outfitId = "";
        try {
            if (Boolean.TRUE.equals(invoke(push, "hasOutfitUuid"))) {
                outfitId = lockerScalar(invoke(push, "getOutfitUuid"));
            }
        } catch (NoSuchMethodException olderSchema) {
            // Older Lunar profile pushes did not include the active outfit UUID.
        }
        if (source == 1) {
            OFFICIAL_PUSH_COSMETICS.clear();
            OFFICIAL_PUSH_COSMETICS.putAll(cosmetics);
            officialPushCosmeticsObserved = true;
            officialPushOutfitId = outfitId;
        } else if (source == 2) {
            FULL_ACCESS_PUSH_COSMETICS.clear();
            FULL_ACCESS_PUSH_COSMETICS.putAll(cosmetics);
            fullAccessPushCosmeticsObserved = true;
            fullAccessPushOutfitId = outfitId;
        }
        lastLockerSnapshotReportMs = 0L;
    }

    /** Record the local player's authoritative official Lunar display push without altering live FullAccess state. */
    public static synchronized void recordOfficialSelfProfilePush(Object push) {
        if (push == null) return;
        refreshAccount();
        try {
            if (!isLocal(invoke(push, "getPlayerUuid"))) return;
            officialSelfPush = push;
            officialLoadoutUpdatedAt = System.currentTimeMillis();
            capturePushCosmetics(push, 1);
            rememberColors(push);
            Object badge = invoke(push, "getBadgeId");
            if (badge instanceof Number) localBadge = Integer.valueOf(((Number) badge).intValue());
        } catch (ReflectiveOperationException ignored) { }
    }

    /** Snapshot of how official Lunar Client users (without Gomod) currently see the local player. */
    public static synchronized String officialLoadoutJson() {
        refreshAccount();
        JsonObject result = new JsonObject();
        boolean enabled = GomodLogoColorSync.isFullAccessColorAvailable();
        result.addProperty("enabled", enabled);
        result.addProperty("liveSocket", LunarSpectatorCosmetics.hasOfficialProfileSocket());
        JsonArray cosmetics = new JsonArray();
        String outfitName = "";
        String source = "unavailable";
        int badgeId = localBadge == null ? 0 : Math.max(0, localBadge.intValue());
        String logoColor = formatProtoColor(localLogo);
        String plusColor = formatProtoColor(localPlus);
        boolean logoAlwaysShow = Boolean.TRUE.equals(localAlwaysShow);

        if (officialSelfPush != null) {
            source = "live-push";
            try {
                Object chosenList = resolveConditionalPushCosmetics(officialSelfPush);
                if (chosenList != null) {
                    appendEquippedCosmeticsForUi(chosenList, cosmetics);
                } else {
                    appendEquippedCosmeticsForUi(invoke(officialSelfPush, "getDefaultCosmeticsList"), cosmetics);
                }
                Object badge = invoke(officialSelfPush, "getBadgeId");
                if (badge instanceof Number) badgeId = Math.max(0, ((Number) badge).intValue());
                String pushLogo = formatProtoColor(optionalMessage(officialSelfPush, "hasLogoColor", "getLogoColor"));
                if (!pushLogo.isEmpty()) logoColor = pushLogo;
                String pushPlus = formatProtoColor(optionalMessage(officialSelfPush, "hasPlusColor", "getPlusColor"));
                plusColor = pushPlus;
                Object alwaysShow = invoke(officialSelfPush, "getLogoAlwaysShow");
                if (alwaysShow instanceof Boolean) logoAlwaysShow = ((Boolean) alwaysShow).booleanValue();
                outfitName = findOutfitName(OFFICIAL, officialPushOutfitId);
            } catch (ReflectiveOperationException ignored) { }
        } else {
            Object officialCosmeticLogin = findOfficialResponse("com.lunarclient.websocket.cosmetic.v2.LoginResponse");
            if (officialCosmeticLogin != null) {
                source = "official-login";
                try {
                    Object tree = invoke(officialCosmeticLogin, "getOutfitTree");
                    String defaultKey = lockerScalar(invoke(tree, "getDefaultOutfitId"));
                    String conditionalKey = resolveMatchingConditionalOutfitId(tree);
                    String targetKey = !conditionalKey.isEmpty() ? conditionalKey : defaultKey;
                    Object rawOutfits = invoke(officialCosmeticLogin, "getOutfitsList");
                    Object matchedOutfit = null;
                    Object defaultOutfit = null;
                    int outfitCount = 0;
                    StringBuilder allSummary = new StringBuilder();
                    if (rawOutfits instanceof Iterable) {
                        for (Object outfit : (Iterable<?>) rawOutfits) {
                            outfitCount++;
                            String id = lockerScalar(invoke(outfit, "getId"));
                            String oName = String.valueOf(invoke(outfit, "getName")).replaceAll("[\\p{Cntrl}]", "").trim();
                            Object rawList = invoke(outfit, "getCosmeticsList");
                            int cCount = rawList instanceof Collection ? ((Collection<?>) rawList).size() : 0;
                            if (allSummary.length() > 0) allSummary.append(',');
                            allSummary.append(oName).append(':').append(cCount);
                            if (!targetKey.isEmpty() && targetKey.equals(id)) matchedOutfit = outfit;
                            if (!defaultKey.isEmpty() && defaultKey.equals(id)) defaultOutfit = outfit;
                        }
                    }
                    Object selectedOutfit = matchedOutfit != null ? matchedOutfit : defaultOutfit;
                    if (selectedOutfit != null) {
                        Object rawName = invoke(selectedOutfit, "getName");
                        if (rawName != null) {
                            outfitName = String.valueOf(rawName).replaceAll("[\\p{Cntrl}]", "").trim();
                            if (outfitName.length() > 160) outfitName = outfitName.substring(0, 160);
                        }
                        appendEquippedCosmeticsForUi(invoke(selectedOutfit, "getCosmeticsList"), cosmetics);
                        try {
                            if (Boolean.TRUE.equals(invoke(selectedOutfit, "hasBadgeId"))) {
                                Object rawOutfitBadge = invoke(selectedOutfit, "getBadgeId");
                                if (rawOutfitBadge instanceof Number && ((Number) rawOutfitBadge).intValue() > 0) {
                                    badgeId = ((Number) rawOutfitBadge).intValue();
                                }
                            }
                        } catch (ReflectiveOperationException ignored) { }
                    }
                    int condCount = 0;
                    try {
                        Object conds = invoke(tree, "getConditionalOutfitsList");
                        if (conds instanceof Collection) condCount = ((Collection<?>) conds).size();
                    } catch (ReflectiveOperationException ignored) { }
                    String diag = "source=" + source + " outfits=" + outfitCount + " selected=" + outfitName
                            + " cosmetics=" + cosmetics.size() + " conditionalCount=" + condCount
                            + " conditionalMatched=" + (!conditionalKey.isEmpty())
                            + " all=[" + allSummary + "]";
                    if (!diag.equals(lastOfficialLoadoutDiag)) {
                        lastOfficialLoadoutDiag = diag;
                        LunarConnectionDiagnostics.record("official_loadout.snapshot", diag);
                    }
                } catch (ReflectiveOperationException error) {
                    String diag = "source=" + source + " error=" + error.getClass().getSimpleName();
                    if (!diag.equals(lastOfficialLoadoutDiag)) {
                        lastOfficialLoadoutDiag = diag;
                        LunarConnectionDiagnostics.record("official_loadout.snapshot", diag);
                    }
                }
            }
            if (badgeId <= 0) {
                Object officialBadgeLogin = findOfficialResponse("com.lunarclient.websocket.badge.v1.LoginResponse");
                if (officialBadgeLogin != null) {
                    try {
                        Object rawBadge = invoke(officialBadgeLogin, "getEquippedBadgeId");
                        if (rawBadge instanceof Number && ((Number) rawBadge).intValue() > 0) {
                            badgeId = ((Number) rawBadge).intValue();
                        }
                    } catch (ReflectiveOperationException ignored) { }
                }
            }
        }

        result.addProperty("available", !"unavailable".equals(source));
        result.addProperty("source", source);
        result.addProperty("outfitName", outfitName);
        result.add("cosmetics", cosmetics);
        result.addProperty("badgeId", badgeId);
        result.addProperty("logoColor", logoColor);
        result.addProperty("plusColor", plusColor);
        result.addProperty("logoAlwaysShow", logoAlwaysShow);
        result.addProperty("updatedAt", officialLoadoutUpdatedAt);
        return result.toString();
    }

    private static Object findOfficialResponse(String className) {
        for (Object response : OFFICIAL.values()) {
            if (response != null && className.equals(response.getClass().getName())) return response;
        }
        return null;
    }

    private static Object resolveConditionalPushCosmetics(Object push) {
        if (push == null) return null;
        try {
            Object raw = invoke(push, "getConditionalCosmeticsList");
            if (!(raw instanceof Iterable)) return null;
            for (Object item : (Iterable<?>) raw) {
                if (item == null || !Boolean.TRUE.equals(invoke(item, "hasEquipCondition"))) continue;
                if (matchesEquipCondition(invoke(item, "getEquipCondition"))) {
                    return invoke(item, "getCosmeticsList");
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        return null;
    }

    private static String resolveMatchingConditionalOutfitId(Object tree) {
        if (tree == null) return "";
        try {
            Object raw = invoke(tree, "getConditionalOutfitsList");
            if (!(raw instanceof Iterable)) return "";
            for (Object item : (Iterable<?>) raw) {
                if (item == null || !Boolean.TRUE.equals(invoke(item, "hasEquipCondition"))) continue;
                if (matchesEquipCondition(invoke(item, "getEquipCondition"))) {
                    return lockerScalar(invoke(item, "getOutfitId"));
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        return "";
    }

    private static boolean matchesEquipCondition(Object condition) {
        if (condition == null) return false;
        try {
            if (Boolean.TRUE.equals(invoke(condition, "hasServer"))) {
                Object serverCond = invoke(condition, "getServer");
                Object rawIp = serverCond == null ? null : invoke(serverCond, "getServerIp");
                String expected = normalizeServerHost(rawIp == null ? "" : String.valueOf(rawIp));
                String current = currentServerHost();
                if (!expected.isEmpty() && !current.isEmpty()) {
                    return current.equals(expected) || current.endsWith("." + expected) || expected.endsWith("." + current);
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private static String currentServerHost() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.getCurrentServerData() == null || mc.getCurrentServerData().serverIP == null) return "";
            return normalizeServerHost(mc.getCurrentServerData().serverIP);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeServerHost(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static void appendEquippedCosmeticsForUi(Object rawList, JsonArray target) {
        if (!(rawList instanceof Iterable)) return;
        for (Object item : (Iterable<?>) rawList) {
            if (item == null) continue;
            try {
                Object rawId = invoke(item, "getCosmeticId");
                if (!(rawId instanceof Number)) continue;
                int id = ((Number) rawId).intValue();
                if (id <= 0) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                String name = LunarGomodMascotCatalog.cosmeticName(String.valueOf(id));
                if (!name.isEmpty()) entry.addProperty("name", name);
                try {
                    if (Boolean.TRUE.equals(invoke(item, "hasGeckolibMetadata"))) {
                        JsonObject meta = protoStructToJson(invoke(item, "getGeckolibMetadata"));
                        if (meta != null && meta.size() > 0) entry.add("metadata", meta);
                    }
                } catch (ReflectiveOperationException ignored) { }
                target.add(entry);
            } catch (ReflectiveOperationException ignored) { }
        }
    }

    private static JsonObject protoStructToJson(Object struct) {
        if (struct == null) return null;
        try {
            Object rawMap = invoke(struct, "getFieldsMap");
            if (!(rawMap instanceof Map)) return null;
            JsonObject out = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawMap).entrySet()) {
                if (entry.getKey() == null) continue;
                out.add(String.valueOf(entry.getKey()), protoValueToJson(entry.getValue()));
            }
            return out;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static JsonElement protoValueToJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        try {
            Object kind = invoke(value, "getKindCase");
            String kindName = kind == null ? "" : String.valueOf(kind);
            if ("STRING_VALUE".equals(kindName)) {
                Object str = invoke(value, "getStringValue");
                return str == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(str));
            }
            if ("NUMBER_VALUE".equals(kindName)) {
                Object num = invoke(value, "getNumberValue");
                return num instanceof Number ? new JsonPrimitive((Number) num) : JsonNull.INSTANCE;
            }
            if ("BOOL_VALUE".equals(kindName)) {
                Object bool = invoke(value, "getBoolValue");
                return bool instanceof Boolean ? new JsonPrimitive((Boolean) bool) : JsonNull.INSTANCE;
            }
            if ("STRUCT_VALUE".equals(kindName)) {
                JsonObject nested = protoStructToJson(invoke(value, "getStructValue"));
                return nested == null ? new JsonObject() : nested;
            }
            if ("LIST_VALUE".equals(kindName)) {
                JsonArray arr = new JsonArray();
                Object listValue = invoke(value, "getListValue");
                Object values = listValue == null ? null : invoke(listValue, "getValuesList");
                if (values instanceof Iterable) {
                    for (Object elem : (Iterable<?>) values) arr.add(protoValueToJson(elem));
                }
                return arr;
            }
        } catch (ReflectiveOperationException ignored) { }
        return JsonNull.INSTANCE;
    }

    private static String formatProtoColor(Object colorProto) {
        if (colorProto == null) return "";
        try {
            Object raw = invoke(colorProto, "getColor");
            if (raw instanceof Number) {
                int rgb = ((Number) raw).intValue() & 0xFFFFFF;
                return String.format(Locale.ROOT, "#%06X", rgb);
            }
        } catch (ReflectiveOperationException ignored) { }
        return "";
    }
    private static Object filterTabLogos(Object response) {
        try {
            boolean official = Integer.valueOf(1).equals(SOURCE.get());
            Object builder = invoke(response, "toBuilder"); invoke(builder, "clearTabLogos"); boolean changed = false;
            for (Object row : (List<?>) invoke(response, "getTabLogosList")) {
                if (!official && isLocal(invoke(row, "getPlayerUuid"))) {
                    Object copy = invoke(row, "toBuilder"); applyColors(copy);
                    if (localBadge != null) copy.getClass().getMethod("setBadgeId", int.class).invoke(copy, localBadge);
                    row = invoke(copy, "build"); changed = true;
                }
                set(builder, "addTabLogos", row);
            }
            return changed ? invoke(builder, "build") : response;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot preserve public TAB appearance", error); }
    }

    /** Preserve the account's Plus choice before native reconnect callbacks populate any render/UI cache. */
    private static Object filterInternalPlus(Object value) {
        if (isOfficialOwnershipOnly() || !ConfigManager.isLunarTweaksEnabled()) return value;
        try {
            int selected = GomodLogoColorSync.getLocalPlusRgbForRelay();
            // Reconnect payloads can arrive before the FullAccess socket is ready. Their
            // cached Plus must not become a visible selection during that interval.
            if (!LunarOnlineStatus.isFullAccessConnected()) {
                int official = officialPlusRgbForRestore();
                selected = official > 0 ? official : 0;
            }
            // No local override means the native path retains its existing saved choice.
            // A reconnect's default color must never supersede an explicit color or No+.
            if (selected < 0) return value;
            Object desired = selected > 0 ? newPlusColor(value.getClass().getClassLoader(), selected) : null;
            Object current = optionalMessage(value, "hasPlusColor", "getPlusColor");
            if (Objects.equals(current, desired)) return value;
            Object builder = invoke(value, "toBuilder");
            if (desired == null) invoke(builder, "clearPlusColor"); else set(builder, "setPlusColor", desired);
            return invoke(builder, "build");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot preserve the local Lunar+ choice on reconnect", error);
        }
    }

    private static Object newPlusColor(ClassLoader loader, int rgb) throws ReflectiveOperationException {
        Object builder = Class.forName("com.lunarclient.common.v1.Color", false, loader).getMethod("newBuilder").invoke(null);
        builder.getClass().getMethod("setColor", int.class).invoke(builder, rgb & 0xFFFFFF);
        return invoke(builder, "build");
    }

    private static Object filterInternalTabPlus(Object response) {
        if (!ConfigManager.isLunarTweaksEnabled()) return response;
        refreshAccount();
        try {
            Object builder = invoke(response, "toBuilder");
            invoke(builder, "clearTabLogos");
            boolean changed = false;
            for (Object row : (List<?>) invoke(response, "getTabLogosList")) {
                Object filtered = isLocal(invoke(row, "getPlayerUuid")) ? filterInternalPlus(row) : row;
                changed |= filtered != row;
                set(builder, "addTabLogos", filtered);
            }
            return changed ? invoke(builder, "build") : response;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot preserve the local Lunar+ TAB choice on reconnect", error);
        }
    }
    private static void rememberColors(Object value) {
        try {
            // Protobuf message getters return their default nested message even when the
            // field was never sent. Treating that default as an explicit selection turns
            // an unselected public Lunar+ into the native Original Lunar Plus fallback.
            localLogo = optionalMessage(value, "hasLogoColor", "getLogoColor");
            localPlus = optionalMessage(value, "hasPlusColor", "getPlusColor");
            if (GomodReleaseBuild.PUBLIC_RELEASE) localPlus = verifiedOfficialPlus(value, localPlus);
            localAlwaysShow = (Boolean) invoke(value, "getLogoAlwaysShow");
            publishPlusRenderState();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot retain official appearance", error); }
    }

    /**
     * A present protobuf color is not proof that the account owns/selects Lunar+.
     * Some public-client login/profile messages carry Lunar's zero/default color, which
     * the native renderer interprets as the Original Lunar Plus fallback. Only preserve
     * an actual nonzero color in the official account's available palette.
     */
    private static Object verifiedOfficialPlus(Object value, Object candidate) {
        if (candidate == null) return null;
        try {
            int color = ((Number) invoke(candidate, "getColor")).intValue() & 0xFFFFFF;
            if (color == 0) return null; // zero is Lunar's No+ sentinel in Gomod's native picker
            Object login = value != null && value.getClass().getName().equals(
                    "com.lunarclient.websocket.cosmetic.v2.LoginResponse") ? value : null;
            if (login == null) for (Object official : OFFICIAL.values()) {
                if (official != null && official.getClass().getName().equals(
                        "com.lunarclient.websocket.cosmetic.v2.LoginResponse")) {
                    login = official;
                    break;
                }
            }
            if (login == null) return null;
            Object available = invoke(login, "getAvailableLunarPlusColorsList");
            if (!(available instanceof Iterable)) return null;
            for (Object owned : (Iterable<?>) available) {
                if (owned != null && (((Number) invoke(owned, "getColor")).intValue() & 0xFFFFFF) == color)
                    return candidate;
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // Fail closed for an unknown public schema; the display-only filter below
            // hides Lunar+'s native fallback until an official palette confirms a choice.
        }
        return null;
    }

    /** Display-only read for an ID already matched to the authenticated session by the caller. */
    public static boolean shouldHideLocalPlus(UUID playerId) {
        if (playerId == null) return false;
        boolean fullAccess = ConfigManager.isLunarTweaksEnabled() && LunarOnlineStatus.isFullAccessConnectedForRender();
        if (!GomodReleaseBuild.PUBLIC_RELEASE && fullAccess) return false;
        PlusRenderState state = plusRenderState;
        if (!playerId.equals(state.account)) return true;
        if (!fullAccess) return !state.officialLogin || state.rgb <= 0;
        return GomodLogoColorSync.shouldHideLocalPlusForRender(playerId, state.rgb);
    }

    private static void publishPlusRenderState() {
        int rgb = -1;
        if (localPlus != null) {
            try {
                rgb = ((Number) invoke(localPlus, "getColor")).intValue() & 0xFFFFFF;
            } catch (ReflectiveOperationException ignored) { }
        }
        plusRenderState = new PlusRenderState(account == null ? null : UUID.fromString(account), rgb,
                hasRememberedOfficialCosmeticLogin());
    }

    private static final class PlusRenderState {
        private final UUID account;
        private final int rgb;
        private final boolean officialLogin;

        private PlusRenderState(UUID account, int rgb, boolean officialLogin) {
            this.account = account;
            this.rgb = rgb;
            this.officialLogin = officialLogin;
        }
    }
    private static Object optionalMessage(Object value, String hasMethod, String getter) throws ReflectiveOperationException {
        try {
            if (!Boolean.TRUE.equals(invoke(value, hasMethod))) return null;
        } catch (NoSuchMethodException olderSchema) {
            // Older Lunar message versions used non-optional fields. Preserve their
            // getter behavior when no protobuf presence accessor exists.
        }
        return invoke(value, getter);
    }
    private static void applyColors(Object builder) throws ReflectiveOperationException {
        if (localLogo != null) set(builder, "setLogoColor", localLogo); else invoke(builder, "clearLogoColor");
        if (localPlus != null) set(builder, "setPlusColor", localPlus); else invoke(builder, "clearPlusColor");
    }
    public static synchronized int officialPlusRgb() {
        if (!GomodReleaseBuild.PUBLIC_RELEASE || localPlus == null) return -1;
        try {
            int color = ((Number) invoke(localPlus, "getColor")).intValue() & 0xFFFFFF;
            return color == 0 ? -1 : color;
        }
        catch (ReflectiveOperationException error) { return -1; }
    }

    /** Official account baseline for restoring local cache edits after FullAccess disconnects. */
    public static synchronized int officialLogoRgbForRestore() {
        if (!hasOfficialCosmeticLogin()) return -1;
        if (localLogo == null) return 0xFFFFFF;
        try { return ((Number) invoke(localLogo, "getColor")).intValue() & 0xFFFFFF; }
        catch (ReflectiveOperationException error) { return -1; }
    }

    /** Distinguishes an official unselected Plus from a missing official login snapshot. */
    public static synchronized int officialPlusRgbForRestore() {
        if (!hasOfficialCosmeticLogin()) return -1;
        if (localPlus == null) return OFFICIAL_PLUS_UNSELECTED;
        try {
            int rgb = ((Number) invoke(localPlus, "getColor")).intValue() & 0xFFFFFF;
            return rgb == 0 ? OFFICIAL_PLUS_UNSELECTED : rgb;
        }
        catch (ReflectiveOperationException error) { return -1; }
    }

    private static boolean hasOfficialCosmeticLogin() {
        refreshAccount();
        return hasRememberedOfficialCosmeticLogin();
    }

    private static boolean hasRememberedOfficialCosmeticLogin() {
        for (Class<?> type : OFFICIAL.keySet())
            if ("com.lunarclient.websocket.cosmetic.v2.LoginResponse".equals(type.getName())) return true;
        return false;
    }

    /** True only for the account-owned cosmetic LoginResponse captured from Lunar's official socket. */
    public static synchronized boolean isOfficialCosmeticLoginResponse(Object response) {
        if (response == null) return false;
        refreshAccount();
        return OFFICIAL.get(response.getClass()) == response
                && "com.lunarclient.websocket.cosmetic.v2.LoginResponse".equals(response.getClass().getName());
    }

    /** Returns the account only while this exact response remains the active official login. */
    public static synchronized String officialCosmeticLoginAccountKey(Object response) {
        if (response == null) return "";
        refreshAccount();
        if (OFFICIAL.get(response.getClass()) != response
                || !"com.lunarclient.websocket.cosmetic.v2.LoginResponse".equals(response.getClass().getName())) {
            return "";
        }
        return account == null ? "" : account;
    }

    /** Returns the account only for the exact Emote LoginResponse captured from Lunar's official socket. */
    public static synchronized String officialEmoteLoginAccountKey(Object response) {
        if (response == null) return "";
        refreshAccount();
        String name = response.getClass().getName();
        if (OFFICIAL.get(response.getClass()) != response
                || !("com.lunarclient.websocket.emote.v1.LoginResponse".equals(name)
                || "com.lunarclient.websocket.emote.v2.LoginResponse".equals(name))) {
            return "";
        }
        return account == null ? "" : account;
    }

    /** Stable local account key for clearing source snapshots when the client account changes. */
    public static synchronized String currentAccountKey() {
        refreshAccount();
        return account == null ? "" : account;
    }

    /** Current account's official cosmetics login, for a delayed local Locker snapshot retry. */
    public static synchronized Object currentOfficialCosmeticLoginResponse() {
        refreshAccount();
        for (Map.Entry<Class<?>, Object> entry : OFFICIAL.entrySet()) {
            if ("com.lunarclient.websocket.cosmetic.v2.LoginResponse".equals(entry.getKey().getName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Current account's official Emote login, used to retry ownership capture when Locker mounts early. */
    public static synchronized Object currentOfficialEmoteLoginResponse() {
        refreshAccount();
        for (Map.Entry<Class<?>, Object> entry : OFFICIAL.entrySet()) {
            String name = entry.getKey().getName();
            if ("com.lunarclient.websocket.emote.v1.LoginResponse".equals(name)
                    || "com.lunarclient.websocket.emote.v2.LoginResponse".equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Public users can still select one of their genuinely available native Lunar+ colors. */
    public static synchronized void nativePlusSelected(Integer color) {
        if (!isOfficialOwnershipOnly() || color == null) return;
        refreshAccount();
        if (color.intValue() == 0) {
            localPlus = null;
            publishPlusRenderState();
            return;
        }
        try {
            for (Object login : OFFICIAL.values()) if (login.getClass().getName().equals("com.lunarclient.websocket.cosmetic.v2.LoginResponse"))
                for (Object available : (List<?>) invoke(login, "getAvailableLunarPlusColorsList"))
                    if (((Number) invoke(available, "getColor")).intValue() == color.intValue()
                            && verifiedOfficialPlus(login, available) != null) {
                        localPlus = available;
                        publishPlusRenderState();
                        return;
                    }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot retain native plus selection", error); }
    }
    private static boolean isLocal(Object uuid) throws ReflectiveOperationException {
        if (account == null || uuid == null) return false;
        return new UUID(((Number) invoke(uuid, "getHigh64")).longValue(), ((Number) invoke(uuid, "getLow64")).longValue()).toString().equals(account);
    }
    private static Object invoke(Object value, String name) throws ReflectiveOperationException { return value.getClass().getMethod(name).invoke(value); }
    private static void set(Object builder, String name, Object value) throws ReflectiveOperationException { builder.getClass().getMethod(name, value.getClass()).invoke(builder, value); }
    public static synchronized boolean isOfficialOwnershipReady() {
        refreshAccount();
        Set<String> services = new HashSet<String>();
        String p = "com.lunarclient.websocket.";
        for (Class<?> type : OFFICIAL.keySet()) services.add(type.getName().substring(p.length()).split("\\.")[0]);
        return services.containsAll(Arrays.asList("cosmetic", "emote", "spray", "badge"));
    }

    /** Bounded account-free explanation for the existing admin connection status. */
    public static synchronized String getOfficialOwnershipWaitMessage() {
        refreshAccount();
        if (account == null) return "Waiting for Lunar to load the signed-in Minecraft account.";
        Set<String> received = new HashSet<String>();
        String prefix = "com.lunarclient.websocket.";
        for (Class<?> type : OFFICIAL.keySet()) {
            received.add(type.getName().substring(prefix.length()).split("\\.")[0]);
        }
        List<String> missing = new ArrayList<String>();
        for (String service : Arrays.asList("cosmetic", "emote", "spray", "badge")) {
            if (!received.contains(service)) missing.add(service);
        }
        return missing.isEmpty() ? "" : "Waiting for official Lunar ownership: "
                + String.join(", ", missing) + ".";
    }
    /** A changed authenticated Minecraft account must never inherit the previous account's ownership. */
    private static synchronized void refreshAccount() {
        try {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, LunarPublicLoginGuard.class.getClassLoader());
            Object client = minecraft.getMethod("getMinecraft").invoke(null);
            Object session = minecraft.getMethod("getSession").invoke(client);
            Object id;
            try {
                String raw = (String) session.getClass().getMethod("getPlayerID").invoke(session);
                if (raw != null && raw.matches("(?i)[0-9a-f]{32}")) raw = raw.substring(0, 8) + "-" + raw.substring(8, 12)
                        + "-" + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
                id = UUID.fromString(raw);
            } catch (NoSuchMethodException legacySession) {
                Object profile = session.getClass().getMethod("getProfile").invoke(session);
                id = profile.getClass().getMethod("getId").invoke(profile);
            }
            if (id != null && !id.toString().equals(account)) {
                if (account != null) System.out.println("[gomod] Public Lunar account changed; requesting fresh official entitlements");
                OFFICIAL.clear(); FULL_ACCESS.clear(); lastLockerSnapshot = null; lastLockerSnapshotAccount = null;
                OFFICIAL_PUSH_COSMETICS.clear(); FULL_ACCESS_PUSH_COSMETICS.clear();
                officialPushCosmeticsObserved = false; fullAccessPushCosmeticsObserved = false;
                officialPushOutfitId = ""; fullAccessPushOutfitId = "";
                lastLockerSnapshotReportMs = 0L; localLogo = null; localPlus = null; localAlwaysShow = null; localBadge = null;
                officialSelfPush = null; officialLoadoutUpdatedAt = 0L; account = id.toString();
                publishPlusRenderState();
            }
        } catch (ReflectiveOperationException ignored) { }
        catch (RuntimeException ignored) { }
    }
}
