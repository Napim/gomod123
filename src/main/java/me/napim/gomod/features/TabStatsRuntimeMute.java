package me.napim.gomod.features;

/**
 * Temporary mute for everything on {@link me.napim.gomod.gui.KillCounterSettingsGUI}: TAB overlays, sorting,
 * auto /tabstats scans, etc. Unlike {@link TabStatsSettingsBundle} / {@code /tabstatssettings off}, this does not
 * change the underlying TAB settings bundle (it only gates behavior) — toggle with {@code /tss}.
 */
public final class TabStatsRuntimeMute {

    private static volatile boolean muted;

    private TabStatsRuntimeMute() {
    }

    public static boolean isMuted() {
        return muted;
    }

    public static void setMuted(boolean value) {
        if (muted == value) {
            return;
        }
        muted = value;
        ConfigManager.setTabStatsRuntimeMuteEnabled(value);
    }

    /** @return the new muted state */
    public static boolean toggle() {
        setMuted(!muted);
        return isMuted();
    }
}
