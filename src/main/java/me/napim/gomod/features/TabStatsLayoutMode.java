package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;

/** Build-aware TAB stats layout policy. */
public final class TabStatsLayoutMode {
    private TabStatsLayoutMode() {
    }

    /** Keep the stats column separate when the TAB formatter adds a long rank/lifetime-kill prefix. */
    public static boolean shouldSeparateStats() {
        if (!GomodFixes.isWeaveRuntime()) {
            return true;
        }
        return shouldSeparateStats(true, GomodReleaseBuild.PUBLIC_RELEASE,
                ConfigManager.isWeaveDevTabStatsSeparated(), ConfigManager.isKillCounterChatStyleTab(),
                LunarTabLogoCompatibility.isNativeRowDrawObserved());
    }

    static boolean shouldSeparateStats(boolean weaveRuntime, boolean publicRelease,
            boolean devSeparated, boolean betterPlayerFormatTab, boolean nativeRowDrawObserved) {
        if (!weaveRuntime) {
            return true;
        }
        // Better player format prepends a rank and lifetime-kill count. Separate W/L, K/D, and wins only
        // when Lunar's native TAB callback is present to draw that column. On Lunar updates that remove
        // the callback, inline stats remain visible instead of leaving only an invisible reserved gap.
        return !publicRelease && devSeparated || betterPlayerFormatTab && nativeRowDrawObserved;
    }

    /**
     * Lunar's merged TAB draw helper can bypass FontRenderer string rewriting. When the active row has a
     * composed stats suffix, draw that full cached row unless the incoming native text already contains it.
     */
    static String chooseNativeRowText(String originalText, String composedText, boolean rowMatches,
            boolean hasStats, boolean originalAlreadyHasStats) {
        if (!rowMatches || !hasStats || originalAlreadyHasStats || composedText == null || composedText.isEmpty()) {
            return originalText;
        }
        return composedText;
    }

    public static boolean isWeaveDevToggleAvailable() {
        return GomodFixes.isWeaveRuntime() && !GomodReleaseBuild.PUBLIC_RELEASE;
    }

    public static boolean setWeaveDevSeparated(boolean separated) {
        if (!isWeaveDevToggleAvailable()) {
            return false;
        }
        ConfigManager.setWeaveDevTabStatsSeparated(separated);
        KillCounter.markTabCompositionDirty();
        return true;
    }

    public static boolean toggleWeaveDevSeparated() {
        boolean separated = !ConfigManager.isWeaveDevTabStatsSeparated();
        setWeaveDevSeparated(separated);
        return separated;
    }

    public static String describe() {
        if (!GomodFixes.isWeaveRuntime()) {
            return "separated (Forge)";
        }
        if (ConfigManager.isKillCounterChatStyleTab()) {
            return shouldSeparateStats()
                    ? "separated (Weave, Better player format)"
                    : "inline (Weave, native stats draw unavailable)";
        }
        if (GomodReleaseBuild.PUBLIC_RELEASE) {
            return "inline (Weave public)";
        }
        return ConfigManager.isWeaveDevTabStatsSeparated() ? "separated (Weave dev)" : "inline (Weave dev)";
    }
}
