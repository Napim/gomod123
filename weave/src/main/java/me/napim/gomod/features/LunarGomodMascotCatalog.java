package me.napim.gomod.features;

import me.napim.gomod.util.LunarGomodCompanionResourcePack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers the packaged companion as a normal cosmetic and retains its local ownership. */
public final class LunarGomodMascotCatalog {
    // Same reserved Gomod range as the custom sprays; independent of native catalog ordering.
    public static final int COSMETIC_ID = 2_000_418_004;
    public static final String COSMETIC_NAME = "goparky";
    public static final String COSMETIC_FILE =
            "lunar:cosmetics/models/gek/companions/gomod_mascot_v14/gomod_mascot.gek.json";
    private static volatile boolean catalogInstalled;
    private static volatile Map<Integer, String> cosmeticNamesById = Collections.emptyMap();

    private LunarGomodMascotCatalog() {
    }

    /** Build Lunar's native definition with its public constructor and retain its full catalog. */
    public static Object appendToCatalog(Object parsed) {
        catalogInstalled = false;
        if (!(parsed instanceof List<?>) || ((List<?>) parsed).isEmpty()) {
            report("catalog", "invalid-list", "type=" + (parsed == null ? "null" : parsed.getClass().getName()));
            return parsed;
        }
        try {
            List<?> rows = (List<?>) parsed;
            cacheCosmeticNames(rows);
            for (Object row : rows) {
                if (row == null) continue;
                int id = ((Number) LunarNativeCatalogRuntime.value(row, 0)).intValue();
                if (id != COSMETIC_ID) continue;
                if (!isOurDefinition(row)) {
                    report("catalog", "id-collision", "id=" + COSMETIC_ID);
                    return parsed;
                }
                LunarGomodCompanionResourcePack.mount();
                return parsed;
            }
            Object definition = newNormalCompanion(rows.get(0).getClass());
            List<Object> result = new ArrayList<Object>(rows.size() + 1);
            result.addAll(rows);
            result.add(definition);
            LunarGomodCompanionResourcePack.mount();
            report("catalog", "appended", "id=" + COSMETIC_ID + " entryCount=" + result.size()
                    + " category=companion devOnly=false");
            return result;
        } catch (Throwable error) {
            reportFailure("catalog", error);
            return parsed;
        }
    }

    /** Keep Lunar's own ID-to-display-name catalog available to the locker snapshot reporter. */
    private static void cacheCosmeticNames(List<?> rows) {
        Map<Integer, String> names = new HashMap<Integer, String>();
        for (Object row : rows) {
            if (row == null) continue;
            try {
                Object rawId = LunarNativeCatalogRuntime.value(row, 0);
                Object rawName = LunarNativeCatalogRuntime.value(row, 1);
                if (!(rawId instanceof Number) || rawName == null) continue;
                int id = ((Number) rawId).intValue();
                String name = String.valueOf(rawName).replaceAll("[\\p{Cntrl}]", "").trim();
                if (id > 0 && !name.isEmpty() && !name.equals(String.valueOf(id))) names.put(Integer.valueOf(id), name);
            } catch (Throwable ignored) { }
        }
        if (names.isEmpty()) return;
        cosmeticNamesById = Collections.unmodifiableMap(names);
        LunarPublicLoginGuard.cosmeticCatalogNamesAvailable();
    }

    /** Returns a name from Lunar's already-loaded native catalog, or an empty string if unknown. */
    public static String cosmeticName(String id) {
        if (id == null || id.isEmpty()) return "";
        try {
            String name = cosmeticNamesById.get(Integer.valueOf(Integer.parseInt(id)));
            return name == null ? "" : name;
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static Object newNormalCompanion(Class<?> nativeType) throws Exception {
        Constructor<?> constructor = LunarNativeCatalogRuntime.companionConstructor(nativeType);
        Class<?> enumType = constructor.getParameterTypes()[10];
        Object itemMaterial = Enum.valueOf((Class) enumType, "ANY");
        List<String> colors = Arrays.asList("GREEN", "WHITE", "BLACK");
        List<String> tags = new ArrayList<String>();
        return constructor.newInstance(COSMETIC_ID, COSMETIC_NAME, COSMETIC_FILE,
                "companion", "NONE", "", null, true, false, true, itemMaterial,
                false, colors, tags, new Date(), false);
    }

    /** Verify actual manager insertion, rather than treating a JSON append as registration. */
    public static void onCatalogLoaded(Object manager, String catalogField) {
        try {
            java.lang.reflect.Field field = manager.getClass().getDeclaredField(catalogField);
            field.setAccessible(true);
            Map<?, ?> definitions = (Map<?, ?>) field.get(manager);
            Object definition = definitions.get(Integer.valueOf(COSMETIC_ID));
            catalogInstalled = isOurDefinition(definition);
            report("registration", catalogInstalled ? "installed" : "missing",
                    "id=" + COSMETIC_ID + " category=companion devOnly=false");
        } catch (Throwable error) {
            catalogInstalled = false;
            reportFailure("registration", error);
        }
    }

    /** Login clears native owned objects: include our ID in the local list it rebuilds from. */
    public static List<?> appendOwnedCompanion(List<?> owned, ClassLoader lunarLoader) {
        if (owned == null || !catalogInstalled || !ConfigManager.isLunarTweaksEnabled()
                || !LunarOnlineStatus.isFullAccessConnected()) return owned;
        try {
            Class<?> ownedType = Class.forName(
                    "com.lunarclient.websocket.cosmetic.v2.OwnedCosmetic", false, lunarLoader);
            Method idGetter = ownedType.getMethod("getCosmeticId");
            for (Object row : owned) {
                if (row != null && ((Number) idGetter.invoke(row)).intValue() == COSMETIC_ID) return owned;
            }
            Object builder = ownedType.getMethod("newBuilder").invoke(null);
            builder.getClass().getMethod("setCosmeticId", int.class).invoke(builder, COSMETIC_ID);
            List<Object> result = new ArrayList<Object>(owned);
            result.add(builder.getClass().getMethod("build").invoke(builder));
            report("ownership", "appended", "id=" + COSMETIC_ID + " ownedRowsAdded=1");
            return result;
        } catch (Throwable error) {
            reportFailure("ownership", error);
            return owned;
        }
    }

    /** Inspect the same native objects that have just been serialized to Locker state. */
    public static void onLockerPublished(Object manager) {
        if (!catalogInstalled) return;
        try {
            Map<?, ?> ownedById = (Map<?, ?>) manager.getClass()
                    .getMethod("CRROOOOORRCIOOCRRHHOOHIICRRCOC").invoke(manager);
            Set<?> owned = (Set<?>) manager.getClass()
                    .getMethod("RRRRORCIRIOOCICIHIHCCCOOHCRHIR").invoke(manager);
            Object mascot = ownedById.get(Long.valueOf(COSMETIC_ID));
            boolean present = mascot != null && owned.contains(mascot);
            int companions = 0;
            for (Object row : owned) {
                Object category = row.getClass().getMethod("IOIRCCOIOORIOHHCHCHOICOCHOCOOH").invoke(row);
                if (category instanceof Enum<?> && "COMPANION".equals(((Enum<?>) category).name())) companions++;
            }
            String rowJson = present ? String.valueOf(mascot.getClass().getMethod("provide").invoke(mascot)) : "missing";
            report("locker", present ? "published" : "missing", "id=" + COSMETIC_ID
                    + " companionCount=" + companions + " row=" + rowJson);
        } catch (Throwable error) {
            reportFailure("locker", error);
        }
    }

    private static boolean isOurDefinition(Object row) throws Exception {
        if (row == null) return false;
        return COSMETIC_NAME.equals(LunarNativeCatalogRuntime.value(row, 1))
                && COSMETIC_FILE.equals(LunarNativeCatalogRuntime.value(row, 2))
                && !Boolean.TRUE.equals(LunarNativeCatalogRuntime.value(row, 15));
    }

    private static void report(String stage, String result, String details) {
        LunarConnectionDiagnostics.recordOnce("lunar.gomod_mascot_" + stage,
                result + ":" + details, "result=" + result + " " + details);
    }

    private static void reportFailure(String stage, Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        report(stage, "failed", "exception=" + cause.getClass().getSimpleName());
    }
}

