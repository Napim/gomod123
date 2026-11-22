package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Removes only relay-marked developer catalog rows from the public build's local Locker view. */
public final class LunarPublicCatalogOwnership {
    private static final int DEV_CATALOG_MARKER_FIELD = 536870000;
    private static final ResponseCache MARKED_COSMETIC_IDS = new ResponseCache();
    private static final ResponseCache MARKED_EMOTE_IDS = new ResponseCache();
    private static final AtomicBoolean LOGGED_COSMETICS = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_EMOTES = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_PLUS_COLORS = new AtomicBoolean();

    private LunarPublicCatalogOwnership() {
    }

    /** Protobuf hashCode/equals call the getters we filter, so cache keys must use identity only. */
    private static final class ResponseCache {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<Object>();
        private final Map<ResponseKey, Set<Integer>> entries = new HashMap<ResponseKey, Set<Integer>>();

        Set<Integer> get(Object response) {
            removeCollected();
            return entries.get(new ResponseKey(response, null));
        }

        void put(Object response, Set<Integer> ids) {
            removeCollected();
            entries.put(new ResponseKey(response, collected), ids);
        }

        private void removeCollected() {
            ResponseKey key;
            while ((key = (ResponseKey) collected.poll()) != null) entries.remove(key);
        }
    }

    private static final class ResponseKey extends WeakReference<Object> {
        private final int identityHash;

        ResponseKey(Object response, ReferenceQueue<Object> queue) {
            super(response, queue);
            identityHash = System.identityHashCode(response);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ResponseKey)) return false;
            Object response = get();
            return response != null && response == ((ResponseKey) other).get();
        }
    }

    public static List<?> filterOwnedCosmetics(List<?> rows, Object response) {
        return filterOwnedRows(rows, response, "getCosmeticId", MARKED_COSMETIC_IDS,
                LOGGED_COSMETICS, "cosmetic");
    }

    public static List<?> filterOwnedEmotes(List<?> rows, Object response) {
        return filterOwnedRows(rows, response, "getEmoteId", MARKED_EMOTE_IDS,
                LOGGED_EMOTES, "emote");
    }

    public static List<?> filterOwnedCosmeticIds(List<?> ids, Object response) {
        return filterOwnedIds(ids, response, "getOwnedCosmeticsList", "getCosmeticId",
                MARKED_COSMETIC_IDS);
    }

    public static List<?> filterOwnedOutfits(List<?> outfits, Object response) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || outfits == null || response == null) return outfits;
        Set<Integer> marked = getMarkedIds(response, "getOwnedCosmeticsList", "getCosmeticId",
                MARKED_COSMETIC_IDS);
        if (marked == null || marked.isEmpty()) return outfits;
        List<Object> filtered = new ArrayList<Object>(outfits.size());
        boolean changed = false;
        for (Object outfit : outfits) {
            Object cosmetics = invokeNoArgs(outfit, "getCosmeticsList");
            if (!(cosmetics instanceof List<?>)) {
                filtered.add(outfit);
                continue;
            }
            List<?> equipped = (List<?>) cosmetics;
            List<Object> kept = new ArrayList<Object>(equipped.size());
            for (Object item : equipped) {
                Integer id = readInteger(item, "getCosmeticId");
                if (id != null && marked.contains(id)) changed = true;
                else kept.add(item);
            }
            if (kept.size() == equipped.size()) {
                filtered.add(outfit);
                continue;
            }
            try {
                Object builder = outfit.getClass().getMethod("toBuilder").invoke(outfit);
                builder.getClass().getMethod("clearCosmetics").invoke(builder);
                builder.getClass().getMethod("addAllCosmetics", Iterable.class).invoke(builder, kept);
                filtered.add(builder.getClass().getMethod("build").invoke(builder));
            } catch (Throwable ignored) {
                filtered.add(outfit);
                changed = false;
            }
        }
        return changed ? Collections.unmodifiableList(filtered) : outfits;
    }

    public static Object filterPublicCosmeticSettings(Object settings, Object response) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || settings == null || response == null) return settings;
        Set<Integer> marked = getMarkedIds(response, "getOwnedCosmeticsList", "getCosmeticId",
                MARKED_COSMETIC_IDS);
        boolean changed = false;
        Object builder;
        try {
            builder = settings.getClass().getMethod("toBuilder").invoke(settings);
            Object plusColor = invokeNoArgs(settings, "getPlusColor");
            Object hasPlus = invokeNoArgs(settings, "hasPlusColor");
            if (Boolean.TRUE.equals(hasPlus) && plusColor != null && hasDevCatalogMarker(plusColor)) {
                builder.getClass().getMethod("clearPlusColor").invoke(builder);
                changed = true;
            }
            if (marked != null && !marked.isEmpty()) {
                Object equippedValue = invokeNoArgs(settings, "getEquippedCosmeticsList");
                if (equippedValue instanceof List<?>) {
                    List<?> equipped = (List<?>) equippedValue;
                    List<Object> kept = new ArrayList<Object>(equipped.size());
                    for (Object item : equipped) {
                        Integer id = readInteger(item, "getCosmeticId");
                        if (id == null || !marked.contains(id)) kept.add(item);
                    }
                    if (kept.size() != equipped.size()) {
                        builder.getClass().getMethod("clearEquippedCosmetics").invoke(builder);
                        builder.getClass().getMethod("addAllEquippedCosmetics", Iterable.class)
                                .invoke(builder, kept);
                        changed = true;
                    }
                }
                Object activeValue = invokeNoArgs(settings, "getActiveCosmeticIdsList");
                if (activeValue instanceof List<?>) {
                    List<?> active = (List<?>) activeValue;
                    List<Object> kept = new ArrayList<Object>(active.size());
                    for (Object id : active) {
                        if (!(id instanceof Number) || !marked.contains(Integer.valueOf(((Number) id).intValue()))) {
                            kept.add(id);
                        }
                    }
                    if (kept.size() != active.size()) {
                        builder.getClass().getMethod("clearActiveCosmeticIds").invoke(builder);
                        builder.getClass().getMethod("addAllActiveCosmeticIds", Iterable.class)
                                .invoke(builder, kept);
                        changed = true;
                    }
                }
            }
            return changed ? builder.getClass().getMethod("build").invoke(builder) : settings;
        } catch (Throwable ignored) {
            return settings;
        }
    }

    public static List<?> filterOwnedEmoteIds(List<?> ids, Object response) {
        return filterOwnedIds(ids, response, "getOwnedEmotesList", "getEmoteId",
                MARKED_EMOTE_IDS);
    }

    public static List<?> filterAvailableLunarPlusColors(List<?> colors, Object response) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || colors == null || response == null) return colors;
        List<Object> filtered = new ArrayList<Object>(colors.size());
        int removed = 0;
        for (Object color : colors) {
            if (color != null && hasDevCatalogMarker(color)) removed++;
            else filtered.add(color);
        }
        if (removed == 0) return colors;
        if (LOGGED_PLUS_COLORS.compareAndSet(false, true)) {
            System.out.println("[gomod] Public Locker filtered relay developer Lunar+ colors=" + removed);
        }
        return Collections.unmodifiableList(filtered);
    }

    public static Object filterMarkedColor(Object color) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || color == null || !hasDevCatalogMarker(color)) return color;
        try {
            return color.getClass().getMethod("getDefaultInstance").invoke(null);
        } catch (Throwable ignored) {
            return color;
        }
    }

    public static boolean filterAllOwnedEmotesFlag(Object response, boolean original) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || !original || response == null) return original;
        Set<Integer> ids = getMarkedIds(response, "getOwnedEmotesList", "getEmoteId", MARKED_EMOTE_IDS);
        return ids == null || ids.isEmpty() ? original : false;
    }

    private static List<?> filterOwnedRows(List<?> rows, Object response, String idGetter,
                                           ResponseCache markedByResponse,
                                           AtomicBoolean logged, String kind) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || rows == null || response == null) return rows;

        List<Object> filtered = new ArrayList<Object>(rows.size());
        LinkedHashSet<Integer> markedIds = new LinkedHashSet<Integer>();
        int removed = 0;
        for (Object row : rows) {
            if (row != null && hasDevCatalogMarker(row)) {
                Integer id = readInteger(row, idGetter);
                if (id != null) markedIds.add(id);
                removed++;
            } else {
                filtered.add(row);
            }
        }
        synchronized (markedByResponse) {
            markedByResponse.put(response, Collections.unmodifiableSet(markedIds));
        }
        if (removed == 0) return rows;
        if (logged.compareAndSet(false, true)) {
            System.out.println("[gomod] Public Locker filtered relay developer " + kind
                    + " catalog rows=" + removed);
        }
        return Collections.unmodifiableList(filtered);
    }

    private static List<?> filterOwnedIds(List<?> ids, Object response, String rowsGetter,
                                          String idGetter, ResponseCache markedByResponse) {
        if (!LunarPublicLoginGuard.isOfficialOwnershipOnly() || ids == null || response == null) return ids;
        Set<Integer> marked = getMarkedIds(response, rowsGetter, idGetter, markedByResponse);
        if (marked == null || marked.isEmpty()) return ids;
        List<Object> filtered = new ArrayList<Object>(ids.size());
        for (Object id : ids) {
            if (!(id instanceof Number) || !marked.contains(Integer.valueOf(((Number) id).intValue()))) {
                filtered.add(id);
            }
        }
        return Collections.unmodifiableList(filtered);
    }

    private static Set<Integer> getMarkedIds(Object response, String rowsGetter, String idGetter,
                                              ResponseCache markedByResponse) {
        synchronized (markedByResponse) {
            Set<Integer> cached = markedByResponse.get(response);
            if (cached != null) return cached;
        }
        try {
            Object value = response.getClass().getMethod(rowsGetter).invoke(response);
            // A transformed getter already cached the original marked rows before returning its filtered list.
            // Filtering that returned list again would replace the cache with an empty set.
            synchronized (markedByResponse) {
                Set<Integer> cached = markedByResponse.get(response);
                if (cached != null) return cached;
            }
            if (value instanceof List<?>) {
                filterOwnedRows((List<?>) value, response, idGetter, markedByResponse,
                        "getOwnedEmotesList".equals(rowsGetter) ? LOGGED_EMOTES : LOGGED_COSMETICS,
                        "catalog");
            }
        } catch (Throwable ignored) {
            // A Lunar schema change leaves the original ownership response intact.
        }
        synchronized (markedByResponse) {
            return markedByResponse.get(response);
        }
    }

    private static Integer readInteger(Object row, String getter) {
        try {
            Object value = row.getClass().getMethod(getter).invoke(row);
            return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArgs(Object value, String method) {
        if (value == null) return null;
        try {
            return value.getClass().getMethod(method).invoke(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasDevCatalogMarker(Object row) {
        try {
            Object unknownFields = row.getClass().getMethod("getUnknownFields").invoke(row);
            Method hasField = unknownFields.getClass().getMethod("hasField", Integer.TYPE);
            return Boolean.TRUE.equals(hasField.invoke(unknownFields, DEV_CATALOG_MARKER_FIELD));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
