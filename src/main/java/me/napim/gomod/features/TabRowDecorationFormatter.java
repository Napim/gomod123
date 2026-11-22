package me.napim.gomod.features;

/** Small pure formatting helpers shared by TAB row composition and offline checks. */
public final class TabRowDecorationFormatter {
    private TabRowDecorationFormatter() {
    }

    /** Returns the yellow enemy-kit label; callers append it after the row's guild tag. */
    public static String enemyKitSuffix(String kit) {
        if (kit == null || kit.trim().isEmpty()) {
            return "";
        }
        return "\u00A7r\u00A7e(" + kit.trim() + ")";
    }
}
