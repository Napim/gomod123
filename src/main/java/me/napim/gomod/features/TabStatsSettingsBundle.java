package me.napim.gomod.features;

/**
 * One-shot disable/restore for everything exposed on {@link me.napim.gomod.gui.KillCounterSettingsGUI}
 * ("TAB QoL Settings"). First {@code off} captures config; {@code on} restores that snapshot.
 */
public final class TabStatsSettingsBundle {

    private static boolean suspended;
    private static Snapshot snap;

    private TabStatsSettingsBundle() {
    }

    public static boolean isSuspended() {
        return suspended;
    }

    /**
     * Disables kill counter, stats/guild/kits/tabkillers overlays, TAB sort, kill-effect TAB styling, blitz star icon,
     * auto tabstats scan, and /tabstats-all chat — same as turning every toggle on that screen off.
     */
    public static void applyOff() {
        if (!suspended) {
            snap = Snapshot.capture();
            suspended = true;
        }
        applyAllOff();
        ConfigManager.saveConfigNow();
    }

    /**
     * Restores settings from the snapshot taken before the first {@link #applyOff()} in this session.
     *
     * @return false if nothing was suspended
     */
    public static boolean restoreOn() {
        if (!suspended || snap == null) {
            return false;
        }
        snap.apply();
        suspended = false;
        snap = null;
        ConfigManager.saveConfigNow();
        return true;
    }

    private static void applyAllOff() {
        KillCounter.setKillEffectTabDisplayMode(KillCounter.KillEffectTabDisplayMode.OFF);
        KillCounter.setStatsDisplay(false);
        KillCounter.setShowGuildTag(false);
        KillCounter.setShowTeammateKits(false);
        KillCounter.setShowEnemyKits(false);
        KillCounter.setShowKeAllChat(false);
        KillCounter.setAutoScanMode(KillCounter.AutoScanMode.OFF);
        ConfigManager.setKillCounterBlitzStarTabIconEnabled(false);
        KillCounter.setTabListSortMode(KillCounter.TabListSortMode.OFF);
        ConfigManager.setKillCounterTabListSortDistrictEnabled(false);
        KillCounter.setTabKillersEnabled(false, false);
        KillCounter.setEnabled(false);
        KillCounter.initializeSettings();
        KillCounter.forceTabRefresh();
    }

    private static final class Snapshot {
        final boolean killCounterEnabled;
        final String tabKillDisplayMode;
        final String tabListSortMode;
        final boolean tabListDistrict;
        final String tabListGameScope;
        final boolean statsDisplay;
        final boolean statsShowKd;
        final boolean statsShowWl;
        final boolean statsShowW;
        final String killEffectTabMode;
        final boolean blitzStarTabIcon;
        final String autoScanMode;
        final boolean guildTag;
        final boolean teammateKits;
        final boolean enemyKits;
        final boolean tabKillers;
        final boolean keAllChat;

        private Snapshot(boolean killCounterEnabled, String tabKillDisplayMode, String tabListSortMode,
                boolean tabListDistrict, String tabListGameScope, boolean statsDisplay,
                boolean statsShowKd, boolean statsShowWl, boolean statsShowW,
                String killEffectTabMode, boolean blitzStarTabIcon, String autoScanMode, boolean guildTag,
                boolean teammateKits, boolean enemyKits, boolean tabKillers, boolean keAllChat) {
            this.killCounterEnabled = killCounterEnabled;
            this.tabKillDisplayMode = tabKillDisplayMode;
            this.tabListSortMode = tabListSortMode;
            this.tabListDistrict = tabListDistrict;
            this.tabListGameScope = tabListGameScope;
            this.statsDisplay = statsDisplay;
            this.statsShowKd = statsShowKd;
            this.statsShowWl = statsShowWl;
            this.statsShowW = statsShowW;
            this.killEffectTabMode = killEffectTabMode;
            this.blitzStarTabIcon = blitzStarTabIcon;
            this.autoScanMode = autoScanMode;
            this.guildTag = guildTag;
            this.teammateKits = teammateKits;
            this.enemyKits = enemyKits;
            this.tabKillers = tabKillers;
            this.keAllChat = keAllChat;
        }

        static Snapshot capture() {
            return new Snapshot(
                    ConfigManager.isKillCounterEnabled(),
                    ConfigManager.getKillCounterTabDisplayMode(),
                    ConfigManager.getKillCounterTabListSortMode(),
                    ConfigManager.isKillCounterTabListSortDistrictEnabled(),
                    ConfigManager.getKillCounterTabListSortGameScope(),
                    ConfigManager.isKillCounterStatsDisplay(),
                    ConfigManager.isKillCounterStatsShowKd(),
                    ConfigManager.isKillCounterStatsShowWl(),
                    ConfigManager.isKillCounterStatsShowW(),
                    ConfigManager.getKillCounterKillEffectTabMode(),
                    ConfigManager.isKillCounterBlitzStarTabIconEnabled(),
                    ConfigManager.getKillCounterAutoScanMode(),
                    ConfigManager.isKillCounterShowGuildTag(),
                    ConfigManager.isKillCounterShowTeammateKits(),
                    ConfigManager.isKillCounterShowEnemyKits(),
                    ConfigManager.isKillCounterTabKillersEnabled(),
                    ConfigManager.isKillCounterShowKeAllChat());
        }

        void apply() {
            KillCounter.setTabListSortGameScope(KillCounter.TabListSortGameScope.parseConfig(tabListGameScope));
            ConfigManager.setKillCounterTabListSortDistrictEnabled(tabListDistrict);
            KillCounter.setTabListSortMode(KillCounter.TabListSortMode.parseConfig(tabListSortMode));
            KillCounter.setKillEffectTabDisplayMode(KillCounter.KillEffectTabDisplayMode.fromConfig(killEffectTabMode));
            ConfigManager.setKillCounterBlitzStarTabIconEnabled(blitzStarTabIcon);
            KillCounter.setAutoScanMode(KillCounter.AutoScanMode.fromConfig(autoScanMode));
            KillCounter.setShowGuildTag(guildTag);
            KillCounter.setShowTeammateKits(teammateKits);
            KillCounter.setShowEnemyKits(enemyKits);
            KillCounter.setShowKeAllChat(keAllChat);
            KillCounter.setTabKillersEnabled(tabKillers, false);
            KillCounter.setStatsShowKd(statsShowKd);
            KillCounter.setStatsShowWl(statsShowWl);
            KillCounter.setStatsShowW(statsShowW);
            KillCounter.setStatsDisplay(statsDisplay);
            KillCounter.setTabKillCounterDisplayMode(KillCounter.TabKillCounterDisplayMode.fromConfig(tabKillDisplayMode));
            KillCounter.setEnabled(killCounterEnabled);
            KillCounter.initializeSettings();
            KillCounter.forceTabRefresh();
        }
    }
}
