package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides duplicate TAB rows (nicked / extra profile vs real IGN). Deathmatch-specific dead-player tab
 * removal was removed; other features handle tab state.
 */
public final class DeathmatchTabCleaner {
    /** Hidden TAB rows by profile UUID (undashed, lower) — never by GameProfile name, or both rows vanish when names match. */
    private static final Set<String> HIDDEN_DUPED_TAB_UUIDS = ConcurrentHashMap.newKeySet();
    /** Hidden TAB rows by profile/visible name (lower). Used for Blitz quit/death cleanup. */
    private static final Set<String> HIDDEN_TAB_NAMES = ConcurrentHashMap.newKeySet();
    /**
     * Quit-hide names that have disappeared from TAB at least once since being hidden. Used so we do not treat
     * Hypixel's stale TAB row (still listed briefly after "has quit!") as a "rejoin" and drop the hide too early.
     */
    private static final Set<String> QUIT_TAB_HIDE_ROW_WAS_ABSENT = ConcurrentHashMap.newKeySet();
    private static final long PERIODIC_SCAN_MS = 750L;
    private static final long IDLE_SCAN_MS = 2000L;

    private long lastPeriodicScanAt = 0L;
    private int lastWorldIdentity = 0;

    /**
     * Hides the extra TAB row for a nicked profile when the real IGN is also listed (same logic as
     * periodic cleanup, but runs from tab-duplicate / teams-nick resolution on Hypixel).
     */
    public static void clearDuplicateHides() {
        HIDDEN_DUPED_TAB_UUIDS.clear();
    }

    public static void clearQuitGhostHides() {
        HIDDEN_TAB_NAMES.clear();
        QUIT_TAB_HIDE_ROW_WAS_ABSENT.clear();
    }

    public static void reconcileNicknameDuplicateHiding() {
        if (!ConfigManager.isHypixelTabDupeHideEnabled()) {
            clearDuplicateHides();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return;
        }
        if (GameContext.isHypixelReplayWatching(mc)) {
            clearDuplicateHides();
            return;
        }
        Collection<NetworkPlayerInfo> tabEntries = mc.getNetHandler().getPlayerInfoMap();
        if (tabEntries == null || tabEntries.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> liveTabNames = new LinkedHashMap<String, String>();
        for (NetworkPlayerInfo info : tabEntries) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
                continue;
            }
            if (TabDuplicateNickResolver.isDisguiseDisplayProfile(info)) {
                continue;
            }
            String tabName = info.getGameProfile().getName().trim();
            if (tabName.isEmpty()) {
                continue;
            }
            liveTabNames.put(tabName.toLowerCase(Locale.ROOT), tabName);
        }
        Set<String> liveLower = liveTabNames.keySet();
        LinkedHashSet<String> desiredHiddenUuids = new LinkedHashSet<String>();
        for (NetworkPlayerInfo info : tabEntries) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
                continue;
            }
            if (TabDuplicateNickResolver.isDisguiseDisplayProfile(info)) {
                continue;
            }
            String tabName = info.getGameProfile().getName().trim();
            if (tabName.isEmpty()) {
                continue;
            }
            if (TabDuplicateNickResolver.shouldHideDuplicateTabListEntry(info, liveLower, tabEntries)) {
                String u = normalizedProfileUuid(info);
                if (u != null) {
                    desiredHiddenUuids.add(u);
                }
            }
        }
        DeathmatchTabCleaner.addHidesForSameVisibleIgn(tabEntries, desiredHiddenUuids);

        LinkedHashSet<String> hiddenDupedSnapshot = new LinkedHashSet<String>(HIDDEN_DUPED_TAB_UUIDS);
        for (String uuid : desiredHiddenUuids) {
            if (!hiddenDupedSnapshot.contains(uuid)) {
                NetworkPlayerInfo explainInfo = findInfoByNormalizedUuid(tabEntries, uuid);
                String tabLineName = explainInfo != null && explainInfo.getGameProfile() != null
                        && explainInfo.getGameProfile().getName() != null
                        ? explainInfo.getGameProfile().getName()
                        : uuid;
                String explain = DeathmatchTabCleaner.explainDupHide(explainInfo);
                String extra = (explain != null && !explain.isEmpty())
                        ? " — " + EnumChatFormatting.GRAY + explain
                        : "";
                if (ConfigManager.isDebugMessagesToChatEnabled()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[debug] removed duplicate TAB row (nicked/extra profile): "
                            + EnumChatFormatting.YELLOW + tabLineName + EnumChatFormatting.GRAY + extra);
                }
            }
        }
        LinkedHashSet<String> finalHiddenUuids = new LinkedHashSet<String>(desiredHiddenUuids);
        for (String uuid : hiddenDupedSnapshot) {
            if (!desiredHiddenUuids.contains(uuid)) {
                NetworkPlayerInfo inf = findInfoByNormalizedUuid(tabEntries, uuid);
                if (shouldKeepDuplicateHiddenBecauseDead(inf)) {
                    finalHiddenUuids.add(uuid);
                    continue;
                }
                if (inf != null && inf.getGameProfile() != null && inf.getGameProfile().getName() != null
                        && ConfigManager.isDebugMessagesToChatEnabled()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[debug] restored duplicate TAB row: "
                            + EnumChatFormatting.YELLOW + inf.getGameProfile().getName());
                }
            }
        }

        HIDDEN_DUPED_TAB_UUIDS.clear();
        HIDDEN_DUPED_TAB_UUIDS.addAll(finalHiddenUuids);
    }

    private static String normalizedProfileUuid(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null) {
            return null;
        }
        return info.getGameProfile().getId().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static NetworkPlayerInfo findInfoByNormalizedUuid(Collection<NetworkPlayerInfo> tabEntries, String normUuid) {
        if (normUuid == null || tabEntries == null) {
            return null;
        }
        for (NetworkPlayerInfo inf : tabEntries) {
            String u = normalizedProfileUuid(inf);
            if (normUuid.equals(u)) {
                return inf;
            }
        }
        return null;
    }

    private static boolean shouldKeepDuplicateHiddenBecauseDead(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null
                || TabDuplicateNickResolver.isDisguiseDisplayProfile(info)) {
            return false;
        }
        String profile = info.getGameProfile().getName();
        if (KillCounter.isPlayerDead(profile)) {
            return true;
        }
        String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
        if (KillCounter.isPlayerDead(visible)) {
            return true;
        }
        String lookupProfile = KillCounter.resolveTabLookupName(profile);
        if (KillCounter.isPlayerDead(lookupProfile)) {
            return true;
        }
        String lookupVisible = KillCounter.resolveTabLookupName(visible);
        return KillCounter.isPlayerDead(lookupVisible);
    }

    /**
     * Same extracted TAB IGN on multiple rows with different UUIDs: keep the row whose internal profile name
     * matches that IGN (real player); hide other internal profiles. Works without Mojang lookups (unlike
     * {@link TabDuplicateNickResolver#shouldHideDuplicateTabListEntry}).
     */
    private static void addHidesForSameVisibleIgn(Collection<NetworkPlayerInfo> tabEntries,
            LinkedHashSet<String> desiredHiddenUuids) {
        if (tabEntries == null || tabEntries.isEmpty()) {
            return;
        }
        Map<String, List<NetworkPlayerInfo>> byLowerVisible = new HashMap<String, List<NetworkPlayerInfo>>();
        for (NetworkPlayerInfo info : tabEntries) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            if (TabDuplicateNickResolver.isDisguiseDisplayProfile(info)) {
                continue;
            }
            String profile = info.getGameProfile().getName();
            if (profile == null || profile.trim().isEmpty()) {
                continue;
            }
            String vis = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
            if (vis == null || vis.trim().isEmpty()) {
                vis = profile;
            }
            String key = vis.trim().toLowerCase(Locale.ROOT);
            byLowerVisible.computeIfAbsent(key, k -> new ArrayList<NetworkPlayerInfo>()).add(info);
        }
        for (List<NetworkPlayerInfo> group : byLowerVisible.values()) {
            if (group == null || group.size() < 2) {
                continue;
            }
            HashSet<UUID> uuids = new HashSet<UUID>();
            for (NetworkPlayerInfo i : group) {
                if (i.getGameProfile() != null && i.getGameProfile().getId() != null) {
                    uuids.add(i.getGameProfile().getId());
                }
            }
            if (uuids.size() < 2) {
                continue;
            }
            String visibleCanon = TabDuplicateNickResolver.getVisibleTabUsernamePublic(group.get(0));
            if (visibleCanon == null || visibleCanon.trim().isEmpty()) {
                visibleCanon = group.get(0).getGameProfile().getName();
            }
            if (visibleCanon == null) {
                continue;
            }
            String visTrim = visibleCanon.trim();
            // Hide only "spoof" rows: GameProfile name equals the visible nick but Mojang says that UUID is someone
            // else (nicked player). Never hide every profile==nick row — that removes the real account when they share
            // the same IGN as a nicker (e.g. Palime nicked as FoxyLuigi + real FoxyLuigi).
            for (NetworkPlayerInfo i : group) {
                String p = i.getGameProfile().getName();
                if (p == null || p.trim().isEmpty()) {
                    continue;
                }
                if (!p.equalsIgnoreCase(visTrim)) {
                    continue;
                }
                String resolved = TabDuplicateNickResolver.getCachedMojangUsernameForUuid(i.getGameProfile().getId());
                if (resolved != null && !resolved.equalsIgnoreCase(visTrim)) {
                    String u = normalizedProfileUuid(i);
                    if (u != null) {
                        desiredHiddenUuids.add(u);
                    }
                }
            }
        }
    }

    private static String explainDupHide(NetworkPlayerInfo row) {
        if (row == null) {
            return null;
        }
        String fromUuid = TabDuplicateNickResolver.getDuplicateTabHideExplain(row);
        if (fromUuid != null && !fromUuid.isEmpty()) {
            return fromUuid;
        }
        String vis = TabDuplicateNickResolver.getVisibleTabUsernamePublic(row);
        String prof = row.getGameProfile() != null ? row.getGameProfile().getName() : null;
        if (vis != null && prof != null && !prof.equalsIgnoreCase(vis.trim())) {
            return "same visible TAB name \"" + vis + "\" — hiding internal profile row";
        }
        return null;
    }

    /**
     * If non-null, {@link #filterPlayerInfoCollection} would drop this TAB row; used by {@code /tablistdebug}.
     */
    public static String tabRowFilterReason(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
            return null;
        }
        if (!HIDDEN_TAB_NAMES.isEmpty()) {
            String profile = info.getGameProfile().getName();
            if (profile != null && HIDDEN_TAB_NAMES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                return "quit-hide name list";
            }
            String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
            if (visible != null && HIDDEN_TAB_NAMES.contains(visible.trim().toLowerCase(Locale.ROOT))) {
                return "quit-hide (visible name)";
            }
        }
        String uuid = normalizedProfileUuid(info);
        if (uuid != null && HIDDEN_DUPED_TAB_UUIDS.contains(uuid)) {
            return "duplicate-profile hide";
        }
        return null;
    }

    public static int debugHiddenQuitNameCount() {
        return HIDDEN_TAB_NAMES.size();
    }

    public static int debugHiddenDupUuidCount() {
        return HIDDEN_DUPED_TAB_UUIDS.size();
    }

    /** Sample of internal quit-hide keys for debug paste (lowercase names). */
    public static String formatHiddenTabNamesSample(int max) {
        if (HIDDEN_TAB_NAMES.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, Math.min(max, 24));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : HIDDEN_TAB_NAMES) {
            if (n++ >= limit) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        if (HIDDEN_TAB_NAMES.size() > limit) {
            sb.append(" …+").append(HIDDEN_TAB_NAMES.size() - limit);
        }
        return sb.toString();
    }

    public static Collection<NetworkPlayerInfo> filterPlayerInfoCollection(Collection<NetworkPlayerInfo> original) {
        boolean hideDupes = ConfigManager.isHypixelTabDupeHideEnabled();
        boolean hideQuitGhosts = ConfigManager.isHypixelTabQuitGhostHideEnabled();
        if (!hideDupes && !hideQuitGhosts) {
            return original;
        }
        if (original == null || original.isEmpty()) {
            return original;
        }
        boolean hasDupesToHide = hideDupes && !HIDDEN_DUPED_TAB_UUIDS.isEmpty();
        boolean hasQuitToHide = hideQuitGhosts && !HIDDEN_TAB_NAMES.isEmpty();
        if (!hasDupesToHide && !hasQuitToHide) {
            return original;
        }
        ArrayList<NetworkPlayerInfo> filtered = new ArrayList<NetworkPlayerInfo>(original.size());
        for (NetworkPlayerInfo info : original) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) {
                filtered.add(info);
                continue;
            }
            if (hideQuitGhosts && !HIDDEN_TAB_NAMES.isEmpty()) {
                String profile = info.getGameProfile().getName();
                if (profile != null && HIDDEN_TAB_NAMES.contains(profile.trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                if (visible != null && HIDDEN_TAB_NAMES.contains(visible.trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            if (hideDupes) {
                String uuid = normalizedProfileUuid(info);
                if (uuid != null && HIDDEN_DUPED_TAB_UUIDS.contains(uuid)) {
                    continue;
                }
            }
            filtered.add(info);
        }
        return filtered;
    }

    public static void hideTabName(String name) {
        if (!ConfigManager.isHypixelTabQuitGhostHideEnabled()) {
            return;
        }
        if (name == null) {
            return;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (!n.isEmpty()) {
            HIDDEN_TAB_NAMES.add(n);
            QUIT_TAB_HIDE_ROW_WAS_ABSENT.remove(n);
        }
    }

    @SubscribeEvent
    public void onPacket(PacketEvent.Receive event) {
        if (event.getPacket() instanceof S01PacketJoinGame) {
            resetState();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("DeathmatchTabCleaner")) return;
        long _profT = FeatureProfiler.begin("DeathmatchTabCleaner");
        try {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            resetState();
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            resetState();
            return;
        }

        int currentWorldIdentity = System.identityHashCode(mc.theWorld);
        if (currentWorldIdentity != lastWorldIdentity) {
            lastWorldIdentity = currentWorldIdentity;
            resetState();
        }

        long now = System.currentTimeMillis();
        long scanIntervalMs = (!HIDDEN_TAB_NAMES.isEmpty()
                || !HIDDEN_DUPED_TAB_UUIDS.isEmpty()
                || !QUIT_TAB_HIDE_ROW_WAS_ABSENT.isEmpty())
                ? PERIODIC_SCAN_MS
                : IDLE_SCAN_MS;
        if (now - lastPeriodicScanAt < scanIntervalMs) {
            return;
        }
        lastPeriodicScanAt = now;
        // Quit-hiding is sticky until the next full server join; if a player rejoins within the same world,
        // we must unhide their TAB row when they are visible again.
        if (ConfigManager.isHypixelTabQuitGhostHideEnabled()) {
            pruneHiddenQuitNamesThatAreNowPresentInTab(mc);
        }
        if (ConfigManager.isHypixelTabDupeHideEnabled()) {
            reconcileNicknameDuplicateHiding();
        } else {
            clearDuplicateHides();
        }
        } finally { FeatureProfiler.end("DeathmatchTabCleaner", _profT); }
    }

    private static void pruneHiddenQuitNamesThatAreNowPresentInTab(Minecraft mc) {
        if (HIDDEN_TAB_NAMES.isEmpty() || mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            return;
        }
        try {
            Collection<NetworkPlayerInfo> tabEntries = mc.getNetHandler().getPlayerInfoMap();
            if (tabEntries == null || tabEntries.isEmpty()) {
                return;
            }
            // Build a set of currently visible TAB names (profile name + visible-name, whichever exists).
            HashSet<String> liveNamesLower = new HashSet<String>();
            for (NetworkPlayerInfo info : tabEntries) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                if (info.getGameProfile().getName() != null) {
                    String n = info.getGameProfile().getName().trim();
                    if (!n.isEmpty()) {
                        liveNamesLower.add(n.toLowerCase(Locale.ROOT));
                    }
                }
                String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                if (visible != null) {
                    String v = visible.trim();
                    if (!v.isEmpty()) {
                        liveNamesLower.add(v.toLowerCase(Locale.ROOT));
                    }
                }
            }

            if (liveNamesLower.isEmpty()) {
                return;
            }

            ArrayList<String> toRemove = new ArrayList<String>();
            for (String hidden : HIDDEN_TAB_NAMES) {
                if (hidden == null) {
                    continue;
                }
                if (liveNamesLower.contains(hidden)) {
                    // Stale quit row can linger for a few ticks with the same name — require absence first,
                    // then presence again, before we unhide (true rejoin).
                    if (QUIT_TAB_HIDE_ROW_WAS_ABSENT.contains(hidden)) {
                        toRemove.add(hidden);
                    }
                } else {
                    QUIT_TAB_HIDE_ROW_WAS_ABSENT.add(hidden);
                }
            }
            for (String r : toRemove) {
                HIDDEN_TAB_NAMES.remove(r);
                QUIT_TAB_HIDE_ROW_WAS_ABSENT.remove(r);
            }
        } catch (Throwable ignored) {
        }
    }

    private void resetState() {
        HIDDEN_DUPED_TAB_UUIDS.clear();
        HIDDEN_TAB_NAMES.clear();
        QUIT_TAB_HIDE_ROW_WAS_ABSENT.clear();
        lastPeriodicScanAt = 0L;
    }
}
