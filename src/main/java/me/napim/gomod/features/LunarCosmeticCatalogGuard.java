package me.napim.gomod.features;

/** Internal QA guard for Lunar's duplicate full-catalog count flag. */
public final class LunarCosmeticCatalogGuard {
    private static final int DEV_CATALOG_COUNT_THRESHOLD = 5000;

    private LunarCosmeticCatalogGuard() {
    }

    /**
     * Suppresses Lunar's account-wide cosmetic flag only while FullAccess is connected and
     * the dev API has already returned a large explicit owned-cosmetic catalog. When FullAccess
     * is unavailable, keep Lunar's native account flag unchanged.
     */
    public static boolean applyDevCatalogCountGuard(Object loginResponse, boolean originalFlag) {
        if (!originalFlag || loginResponse == null) {
            return originalFlag;
        }

        try {
            if (!ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessConnected()) {
                return originalFlag;
            }

            Object count = loginResponse.getClass()
                    .getMethod("getOwnedCosmeticsCount")
                    .invoke(loginResponse);
            return !(count instanceof Number)
                    || ((Number) count).intValue() < DEV_CATALOG_COUNT_THRESHOLD;
        } catch (Throwable ignored) {
            // If this Lunar version changes its generated API, preserve Lunar's account flag.
            return originalFlag;
        }
    }
}
