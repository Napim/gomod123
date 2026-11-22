package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RemoteDisguiseManager {
    private static final Map<String, RemoteDisguiseEntry> BY_SOURCE_NAME = new HashMap<>();
    private static final Map<UUID, RemoteDisguiseEntry> BY_SOURCE_UUID = new HashMap<>();
    private static final Map<String, RemoteDisguiseEntry> BY_GOMOD_PEER_NAME = new HashMap<>();
    private static final Map<UUID, RemoteDisguiseEntry> BY_GOMOD_PEER_UUID = new HashMap<>();
    private static final Map<String, AtomicLong> MANUAL_GENERATIONS = new HashMap<String, AtomicLong>();
    private static final ConcurrentHashMap<UUID, AtomicLong> GOMOD_PEER_GENERATIONS =
            new ConcurrentHashMap<UUID, AtomicLong>();
    private static final ConcurrentHashMap<UUID, String> GOMOD_PEER_PENDING_TARGETS =
            new ConcurrentHashMap<UUID, String>();
    private static final ConcurrentHashMap<UUID, String> GOMOD_PEER_FAILED_TARGETS =
            new ConcurrentHashMap<UUID, String>();
    private static final ConcurrentHashMap<UUID, Long> GOMOD_PEER_RETRY_AFTER_MS =
            new ConcurrentHashMap<UUID, Long>();
    private static final AtomicLong MANUAL_TAB_FINAL_RENDER_REWRITE_COUNT = new AtomicLong();
    private static volatile String lastManualTabFinalRenderRewrite = "not_seen";
    private static final String[] MANUAL_KILL_FEED_MARKERS = {
            " was killed by ", " were killed by ", " was slain by ", " were slain by ",
            " was shot by ", " were shot by ", " was blown up by ", " were blown up by ",
            " was burned to death by ", " were burned to death by ", " was frozen to death by ",
            " were frozen to death by ", " was electrocuted by ", " were electrocuted by ",
            " was eliminated by "
    };
    private static final Pattern LEADERBOARD_HOLOGRAM_ROW = Pattern.compile(
            "^\\s*(\\d[\\d,]*)\\.\\s+(.+?)\\s+-\\s+([\\d,]+)\\s*$");
    private static final Pattern REKT_FINISHER_HOLOGRAM = Pattern.compile(
            "^\\s*([A-Za-z0-9_]{1,16})\\s+has\\s+#rekt\\s+([A-Za-z0-9_]{1,16})\\s+here\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final long GOMOD_PEER_PROFILE_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(15L);
    private static ReflectionHandles reflectionHandles = null;
    private static Class<?> reflectionClass = null;

    private RemoteDisguiseManager() {
    }

    /** Match identity must bypass Lunar's display-only profile alias while TAB is being rendered. */
    public static GameProfile getSourceProfile(NetworkPlayerInfo info) {
        if (info == null) return null;
        if (info instanceof NetworkPlayerInfoAccessor) {
            GameProfile source = ((NetworkPlayerInfoAccessor) info).gomod$getGameProfile();
            if (source != null) return source;
        }
        return info.getGameProfile();
    }

    public static synchronized boolean hasEntries() {
        return !BY_SOURCE_NAME.isEmpty() || !BY_GOMOD_PEER_NAME.isEmpty();
    }

    public static synchronized int getActiveCount() {
        return BY_SOURCE_NAME.size() + BY_GOMOD_PEER_NAME.size();
    }

    public static void disguisePlayer(String sourceName, String targetName) throws Exception {
        disguisePlayer(sourceName, targetName, 0L);
    }

    public static void disguisePlayer(String sourceName, String targetName, long expiresAt) throws Exception {
        disguisePlayer(sourceName, targetName, expiresAt, DisguiserLunarIcon.KEEP);
    }

    public static void disguisePlayer(String sourceName, String targetName, long expiresAt,
                                      DisguiserLunarIcon lunarIcon) throws Exception {
        disguisePlayer(sourceName, targetName, expiresAt, lunarIcon, false);
    }

    public static void disguisePlayer(String sourceName, String targetName, long expiresAt,
                                      DisguiserLunarIcon lunarIcon, boolean fullLunar) throws Exception {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            throw new Exception("Source player is required.");
        }
        if (targetName == null || targetName.trim().isEmpty()) {
            throw new Exception("Target player is required.");
        }

        // Never hold our renderer/tick monitor while profile loading waits for Minecraft's
        // scheduled texture task. Otherwise hasEntries()/nametag rendering blocks that very
        // thread, and the texture task cannot run until the command's timeout releases us.
        LocalDisguise.RemoteDisguiseProfile profile = LocalDisguise.resolveRemoteProfile(targetName.trim());
        disguiseResolvedProfile(sourceName, profile, expiresAt, lunarIcon, fullLunar);
    }

    public static void disguiseResolvedProfile(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt) throws Exception {
        disguiseResolvedProfile(sourceName, profile, expiresAt, DisguiserLunarIcon.KEEP);
    }

    public static void disguiseResolvedProfile(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt, DisguiserLunarIcon lunarIcon) throws Exception {
        disguiseResolvedProfile(sourceName, profile, expiresAt, lunarIcon, false);
    }

    public static void disguiseResolvedProfile(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt,
            DisguiserLunarIcon lunarIcon, boolean fullLunar) throws Exception {
        if (sourceName == null || sourceName.trim().isEmpty() || profile == null)
            throw new Exception("Source player and resolved target profile are required.");
        final String sourceKey = sourceName.trim().toLowerCase(Locale.ROOT);
        final AtomicLong generation;
        final long request;
        synchronized (RemoteDisguiseManager.class) {
            AtomicLong current = MANUAL_GENERATIONS.get(sourceKey);
            if (current == null) { current = new AtomicLong(); MANUAL_GENERATIONS.put(sourceKey, current); }
            generation = current;
            request = generation.incrementAndGet();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) throw new Exception("Minecraft is not ready.");
        if (mc.isCallingFromMinecraftThread()) {
            installResolvedProfileIfCurrent(sourceName.trim(), profile, expiresAt, lunarIcon,
                    fullLunar, generation, request);
        } else {
            Future<?> install = mc.addScheduledTask(() -> installResolvedProfileIfCurrent(sourceName.trim(),
                    profile, expiresAt, lunarIcon, fullLunar, generation, request));
            try {
                install.get(20L, TimeUnit.SECONDS);
            } catch (Exception failure) {
                install.cancel(false);
                throw failure;
            }
        }
        DisguiserRelay.refreshTargetCape(profile.profile);
    }

    /** Resolve an opted-in Gomod peer's disguise without blocking poll processing or game ticks. */
    public static void applyGomodPeerDisguiseSnapshot(String sourceName, UUID sourceUuid,
            String targetName, String targetUuid, boolean manual, long expiresAt,
            DisguiserLunarIcon lunarIcon, boolean fullLunar, com.google.gson.JsonObject snapshot) {
        if (sourceUuid == null || sourceName == null || sourceName.isEmpty()) return;
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        if (!mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(() -> applyGomodPeerDisguiseSnapshot(sourceName, sourceUuid, targetName,
                    targetUuid, manual, expiresAt, lunarIcon, fullLunar, snapshot));
            return;
        }
        String key = snapshot.toString() + "|" + manual + "|" + fullLunar + "|" + expiresAt + "|" + lunarIcon.wire;
        synchronized (RemoteDisguiseManager.class) {
            RemoteDisguiseEntry installed = BY_GOMOD_PEER_UUID.get(sourceUuid);
            if (installed != null && sourceName.equalsIgnoreCase(installed.sourceName)
                    && key.equals(installed.peerSnapshotKey)) return;
        }
        LocalDisguise.RemoteDisguiseProfile profile = LocalDisguise.RemoteDisguiseProfile.fromRelaySnapshot(
                snapshot, targetName, targetUuid);
        if (profile == null) {
            applyGomodPeerDisguise(sourceName, sourceUuid, targetName, manual, expiresAt, lunarIcon, fullLunar);
            return;
        }
        synchronized (RemoteDisguiseManager.class) {
            peerGeneration(sourceUuid).incrementAndGet();
            GOMOD_PEER_PENDING_TARGETS.remove(sourceUuid);
            GOMOD_PEER_FAILED_TARGETS.remove(sourceUuid);
            GOMOD_PEER_RETRY_AFTER_MS.remove(sourceUuid);
            installResolvedGomodPeerProfile(sourceName, sourceUuid, profile, manual, expiresAt, lunarIcon, fullLunar);
            BY_GOMOD_PEER_UUID.get(sourceUuid).peerSnapshotKey = key;
        }
        LunarConnectionDiagnostics.record("gomod.peer_disguise.profile_snapshot",
                "result=applied lookupRequired=false killsKnown=" + (profile.getBlitzKillCount() != null)
                        + " chatCounter=" + profile.isBlitzChatKillCounterEnabled()
                        + " tournamentCounter=" + profile.isBlitzTournamentKillCounterEnabled());
    }

    public static void applyGomodPeerDisguise(final String sourceName, final UUID sourceUuid,
                                               final String targetName) {
        applyGomodPeerDisguise(sourceName, sourceUuid, targetName, false, 0L);
    }

    public static void applyGomodPeerDisguise(final String sourceName, final UUID sourceUuid,
                                               final String targetName, final boolean manual, final long expiresAt) {
        applyGomodPeerDisguise(sourceName, sourceUuid, targetName, manual, expiresAt, DisguiserLunarIcon.KEEP);
    }

    public static void applyGomodPeerDisguise(final String sourceName, final UUID sourceUuid,
            final String targetName, final boolean manual, final long expiresAt, final DisguiserLunarIcon lunarIcon) {
        applyGomodPeerDisguise(sourceName, sourceUuid, targetName, manual, expiresAt, lunarIcon, false);
    }

    public static void applyGomodPeerDisguise(final String sourceName, final UUID sourceUuid,
            final String targetName, final boolean manual, final long expiresAt,
            final DisguiserLunarIcon lunarIcon, final boolean fullLunar) {
        if (sourceName == null || sourceName.trim().isEmpty() || sourceUuid == null
                || targetName == null || targetName.trim().isEmpty()) {
            return;
        }
        final String cleanSourceName = sourceName.trim();
        final String cleanTargetName = targetName.trim();
        final String pendingKey = cleanTargetName + "|" + manual + "|" + fullLunar
                + "|" + expiresAt + "|" + lunarIcon.wire;
        final AtomicLong generation;
        final long request;
        synchronized (RemoteDisguiseManager.class) {
            RemoteDisguiseEntry installed = BY_GOMOD_PEER_UUID.get(sourceUuid);
            if (installed != null && installed.targetProfile != null
                    && cleanTargetName.equalsIgnoreCase(installed.targetProfile.name)) {
                boolean wasFullLunar = installed.fullLunar;
                installed.manualCosmetics = manual;
                installed.fullLunar = fullLunar;
                installed.expiresAt = expiresAt;
                installed.lunarIcon = lunarIcon;
                if (wasFullLunar && !fullLunar) LunarFullDisguise.clearPeerAppearance(sourceUuid);
                else if (fullLunar && installed.targetProfile.profile != null
                        && installed.targetProfile.profile.getId() != null) {
                    try {
                        LunarFullDisguise.applyPeerAppearance(sourceUuid,
                                installed.targetProfile.profile.getId());
                    } catch (Throwable error) {
                        LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                                "result=failed errorType=" + error.getClass().getSimpleName());
                    }
                }
                return;
            }
            String pendingTarget = GOMOD_PEER_PENDING_TARGETS.get(sourceUuid);
            if (pendingKey.equalsIgnoreCase(pendingTarget)) {
                return;
            }
            String failedTarget = GOMOD_PEER_FAILED_TARGETS.get(sourceUuid);
            Long retryAfter = GOMOD_PEER_RETRY_AFTER_MS.get(sourceUuid);
            if (pendingKey.equalsIgnoreCase(failedTarget) && retryAfter != null
                    && System.currentTimeMillis() < retryAfter.longValue()) {
                return;
            }
            generation = peerGeneration(sourceUuid);
            request = generation.incrementAndGet();
            GOMOD_PEER_PENDING_TARGETS.put(sourceUuid, pendingKey);
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final LocalDisguise.RemoteDisguiseProfile profile =
                            LocalDisguise.resolveRemoteProfile(cleanTargetName);
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc == null) {
                        failPeerProfileRequest(sourceUuid, pendingKey, generation, request);
                        return;
                    }
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                AtomicLong current = GOMOD_PEER_GENERATIONS.get(sourceUuid);
                                if (current == generation && current.get() == request) {
                                    try {
                                        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) return;
                                        installResolvedGomodPeerProfile(cleanSourceName, sourceUuid, profile,
                                                manual, expiresAt, lunarIcon, fullLunar);
                                        if (manual) DisguiserRelay.refreshTargetCape(profile.profile);
                                        GOMOD_PEER_FAILED_TARGETS.remove(sourceUuid);
                                        GOMOD_PEER_RETRY_AFTER_MS.remove(sourceUuid);
                                    } catch (Throwable error) {
                                        failPeerProfileRequest(sourceUuid, pendingKey, generation, request);
                                        LunarConnectionDiagnostics.record("gomod.peer_disguise.skin",
                                                "result=failed errorType=" + error.getClass().getSimpleName());
                                    }
                                }
                            } finally {
                                GOMOD_PEER_PENDING_TARGETS.remove(sourceUuid, pendingKey);
                            }
                        }
                    });
                } catch (Throwable error) {
                    failPeerProfileRequest(sourceUuid, pendingKey, generation, request);
                    LunarConnectionDiagnostics.record("gomod.peer_disguise.skin",
                            "result=failed errorType=" + error.getClass().getSimpleName());
                }
            }
        }, "gomod-peer-disguise-" + sourceUuid.toString().substring(0, 8));
        worker.setDaemon(true);
        worker.start();
    }

    private static AtomicLong peerGeneration(UUID sourceUuid) {
        AtomicLong generation = GOMOD_PEER_GENERATIONS.get(sourceUuid);
        if (generation != null) {
            return generation;
        }
        AtomicLong created = new AtomicLong();
        AtomicLong existing = GOMOD_PEER_GENERATIONS.putIfAbsent(sourceUuid, created);
        return existing == null ? created : existing;
    }

    private static void failPeerProfileRequest(UUID sourceUuid, String targetName,
                                              AtomicLong generation, long request) {
        AtomicLong current = GOMOD_PEER_GENERATIONS.get(sourceUuid);
        if (current != generation || current.get() != request) {
            return;
        }
        GOMOD_PEER_PENDING_TARGETS.remove(sourceUuid, targetName);
        GOMOD_PEER_FAILED_TARGETS.put(sourceUuid, targetName);
        GOMOD_PEER_RETRY_AFTER_MS.put(sourceUuid,
                System.currentTimeMillis() + GOMOD_PEER_PROFILE_RETRY_DELAY_MS);
    }

    /** Revoke only the peer-synchronized entry; manual /disguiser entries remain independent. */
    public static synchronized void clearGomodPeerDisguise(UUID sourceUuid, String sourceName) {
        if (sourceUuid != null) {
            AtomicLong generation = GOMOD_PEER_GENERATIONS.get(sourceUuid);
            if (generation != null) {
                generation.incrementAndGet();
            }
            GOMOD_PEER_PENDING_TARGETS.remove(sourceUuid);
            GOMOD_PEER_FAILED_TARGETS.remove(sourceUuid);
            GOMOD_PEER_RETRY_AFTER_MS.remove(sourceUuid);
        }
        RemoteDisguiseEntry entry = sourceUuid == null ? null : BY_GOMOD_PEER_UUID.remove(sourceUuid);
        if (entry == null && sourceName != null) {
            entry = BY_GOMOD_PEER_NAME.get(sourceName.toLowerCase(Locale.ROOT));
        }
        if (entry == null) {
            return;
        }
        restoreEntry(entry, findInfoForEntry(entry, Minecraft.getMinecraft()));
        BY_GOMOD_PEER_NAME.remove(entry.sourceNameLower);
        if (entry.sourceUuid != null) {
            BY_GOMOD_PEER_UUID.remove(entry.sourceUuid);
        }
        KillCounter.markTabCompositionDirty();
        applyNow();
    }

    private static synchronized void installResolvedGomodPeerProfile(
            String sourceName, UUID sourceUuid, LocalDisguise.RemoteDisguiseProfile profile, boolean manual, long expiresAt) {
        installResolvedGomodPeerProfile(sourceName, sourceUuid, profile, manual, expiresAt, DisguiserLunarIcon.KEEP);
    }

    private static synchronized void installResolvedGomodPeerProfile(
            String sourceName, UUID sourceUuid, LocalDisguise.RemoteDisguiseProfile profile,
            boolean manual, long expiresAt, DisguiserLunarIcon lunarIcon) {
        installResolvedGomodPeerProfile(sourceName, sourceUuid, profile, manual, expiresAt, lunarIcon, false);
    }

    private static synchronized void installResolvedGomodPeerProfile(
            String sourceName, UUID sourceUuid, LocalDisguise.RemoteDisguiseProfile profile,
            boolean manual, long expiresAt, DisguiserLunarIcon lunarIcon, boolean fullLunar) {
        RemoteDisguiseEntry existing = BY_GOMOD_PEER_UUID.get(sourceUuid);
        if (existing != null) {
            restoreEntry(existing, findInfoForEntry(existing, Minecraft.getMinecraft()));
            BY_GOMOD_PEER_NAME.remove(existing.sourceNameLower);
            BY_GOMOD_PEER_UUID.remove(sourceUuid);
        }
        RemoteDisguiseEntry entry = new RemoteDisguiseEntry(sourceName, profile);
        entry.sourceUuid = sourceUuid;
        entry.manualCosmetics = manual;
        entry.fullLunar = fullLunar;
        entry.expiresAt = expiresAt;
        entry.lunarIcon = lunarIcon;
        BY_GOMOD_PEER_NAME.put(entry.sourceNameLower, entry);
        BY_GOMOD_PEER_UUID.put(sourceUuid, entry);
        applyNow();
        invalidateInstalledEntryTabRow(entry);
        LunarConnectionDiagnostics.record("gomod.peer_disguise.skin", "result=applied");
    }

    private static void invalidateInstalledEntryTabRow(RemoteDisguiseEntry entry) {
        NetworkPlayerInfo info = entry == null ? null : entry.lastInfo;
        if (info != null) {
            KillCounter.invalidateTabRowAfterDisguise(info);
        } else {
            KillCounter.markTabCompositionDirty();
        }
    }

    /** Only the short, client-thread mutation is synchronized; failed loads retain the old entry. */
    private static synchronized void installResolvedProfileIfCurrent(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt, DisguiserLunarIcon lunarIcon,
            boolean fullLunar, AtomicLong generation, long request) {
        if (generation.get() != request) return;
        installResolvedProfile(sourceName, profile, expiresAt, lunarIcon, fullLunar);
    }

    private static synchronized void installResolvedProfile(String sourceName,
                                                              LocalDisguise.RemoteDisguiseProfile profile, long expiresAt) {
        installResolvedProfile(sourceName, profile, expiresAt, DisguiserLunarIcon.KEEP);
    }

    private static synchronized void installResolvedProfile(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt, DisguiserLunarIcon lunarIcon) {
        installResolvedProfile(sourceName, profile, expiresAt, lunarIcon, false);
    }

    private static synchronized void installResolvedProfile(String sourceName,
            LocalDisguise.RemoteDisguiseProfile profile, long expiresAt,
            DisguiserLunarIcon lunarIcon, boolean fullLunar) {
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) return;
        RemoteDisguiseEntry existing = BY_SOURCE_NAME.get(sourceName.toLowerCase(Locale.ROOT));
        if (existing != null) {
            restoreEntry(existing, findInfoForEntry(existing, Minecraft.getMinecraft()));
            removeEntry(existing);
        }

        RemoteDisguiseEntry entry = new RemoteDisguiseEntry(sourceName.trim(), profile);
        entry.manualCosmetics = true;
        entry.fullLunar = fullLunar;
        entry.expiresAt = expiresAt;
        entry.lunarIcon = lunarIcon;

        BY_SOURCE_NAME.put(entry.sourceNameLower, entry);
        resolveSourceUuidFromTab(entry);
        if (entry.sourceUuid != null) {
            BY_SOURCE_UUID.put(entry.sourceUuid, entry);
        }

        applyNow();
        invalidateInstalledEntryTabRow(entry);
        if (expiresAt > 0L) TimedDisguiserStore.remember(sourceName, profile.name, expiresAt, lunarIcon, fullLunar);
    }

    public static synchronized boolean clearPlayer(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return false;
        }

        AtomicLong generation = MANUAL_GENERATIONS.get(sourceName.toLowerCase(Locale.ROOT));
        if (generation != null) generation.incrementAndGet();
        TimedDisguiserStore.remove(sourceName);
        RemoteDisguiseEntry entry = BY_SOURCE_NAME.get(sourceName.toLowerCase(Locale.ROOT));
        if (entry == null) {
            return false;
        }

        restoreEntry(entry, findInfoForEntry(entry, Minecraft.getMinecraft()));
        removeEntry(entry);
        KillCounter.markTabCompositionDirty();
        applyNow();
        return true;
    }

    public static synchronized int clearAll() {
        TimedDisguiserStore.clear();
        return clearForAccountSwitch();
    }

    /** Clears only local full-Lunar manual entries, leaving ordinary disguiser entries untouched. */
    public static synchronized int clearAllFullLunarEntries() {
        int cleared = 0;
        Minecraft mc = Minecraft.getMinecraft();
        for (RemoteDisguiseEntry entry : new java.util.ArrayList<RemoteDisguiseEntry>(BY_SOURCE_NAME.values())) {
            if (!entry.manualCosmetics || !entry.fullLunar) continue;
            AtomicLong generation = MANUAL_GENERATIONS.get(entry.sourceNameLower);
            if (generation != null) generation.incrementAndGet();
            TimedDisguiserStore.remove(entry.sourceName);
            restoreEntry(entry, findInfoForEntry(entry, mc));
            removeEntry(entry);
            cleared++;
        }
        if (cleared > 0) KillCounter.markTabCompositionDirty();
        applyNow();
        return cleared;
    }

    public static synchronized int clearForAccountSwitch() {
        for (AtomicLong generation : MANUAL_GENERATIONS.values()) generation.incrementAndGet();
        int cleared = 0;
        Minecraft mc = Minecraft.getMinecraft();
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) {
            restoreEntry(entry, findInfoForEntry(entry, mc));
            cleared++;
        }
        BY_SOURCE_NAME.clear();
        BY_SOURCE_UUID.clear();
        if (cleared > 0) KillCounter.markTabCompositionDirty();
        applyNow();
        return cleared;
    }

    public static synchronized String getDisguisedNameFor(EntityPlayer player) {
        if (player == null || player.getGameProfile() == null) {
            return null;
        }
        RemoteDisguiseEntry entry = findEntry(player.getGameProfile());
        return entry == null ? null : entry.targetProfile.name;
    }

    /** Explicit /disguiser entries only; peer-shared full disguises have their own cosmetic policy. */
    public static synchronized LocalDisguise.RemoteDisguiseProfile getManualDisguiseTarget(GameProfile profile) {
        RemoteDisguiseEntry entry = findManualEntry(profile);
        return entry == null ? null : entry.targetProfile;
    }

    static synchronized boolean hasManualDisguiseTarget(UUID playerId) {
        RemoteDisguiseEntry entry = findManualEntry(playerId, null);
        return entry != null && entry.targetProfile != null;
    }

    static synchronized boolean hasManualDisguiseTargetName(String playerName) {
        RemoteDisguiseEntry entry = findManualEntry(null, playerName);
        return entry != null && entry.targetProfile != null;
    }

    /** Still tracked until expiry cleanup removes it; used to keep the denicker out of timer-boundary races. */
    public static synchronized boolean hasTrackedManualDisguise(GameProfile profile) {
        if (profile == null) return false;
        RemoteDisguiseEntry entry = profile.getId() == null ? null : BY_SOURCE_UUID.get(profile.getId());
        String normalizedName = LocalDisguise.normalizeReplayProfileName(profile.getName());
        if (entry == null && !normalizedName.isEmpty()) {
            entry = BY_SOURCE_NAME.get(normalizedName.toLowerCase(Locale.ROOT));
        }
        return entry != null && entry.manualCosmetics;
    }

    /** Target profile for any active local or Gomod-peer disguise, including ordinary and full-Lunar entries. */
    public static synchronized LocalDisguise.RemoteDisguiseProfile getDisguisedTargetProfile(GameProfile profile) {
        RemoteDisguiseEntry entry = findEntry(profile);
        return entry == null || entry.isExpired() ? null : entry.targetProfile;
    }

    /** Target profile for TAB QoL, where the source TAB row is identified by its unchanged real profile name. */
    public static synchronized LocalDisguise.RemoteDisguiseProfile getDisguisedTargetProfileForSourceName(
            String sourceName) {
        RemoteDisguiseEntry entry = findSourceEntryByName(sourceName);
        return entry == null || entry.isExpired() ? null : entry.targetProfile;
    }

    /** Full target row for the replay Players chest, preserving the target's Hypixel rank and guild formatting. */
    public static synchronized String getReplaySelectorTargetDisplayNameForSourceName(String sourceName) {
        RemoteDisguiseEntry entry = findSourceEntryByName(sourceName);
        if (entry == null || entry.isExpired() || entry.targetProfile == null
                || entry.targetProfile.name == null || entry.targetProfile.name.isEmpty()) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc)) {
            return entry.targetProfile.name;
        }
        // Replay TAB rows are deliberately white, so do not read their render-only value as the target's
        // rank styling. The retained target snapshot is the authoritative source for this selector UI.
        if (GameContext.isHypixelReplayWatching(mc)
                && entry.targetProfile.hypixelDisplayName != null
                && !entry.targetProfile.hypixelDisplayName.isEmpty()) {
            return ensureTargetLobbyRank(
                    appendTargetGuildTagIfMissing(entry.targetProfile.hypixelDisplayName, entry.targetProfile),
                    entry.targetProfile, mc);
        }
        String targetFormatted = findTargetFormattedDisplay(entry, mc);
        if (targetFormatted != null && !targetFormatted.isEmpty()) {
            return ensureTargetLobbyRank(
                    appendTargetGuildTagIfMissing(targetFormatted, entry.targetProfile), entry.targetProfile, mc);
        }
        String fallback = entry.targetProfile.hypixelDisplayName;
        if (fallback == null || fallback.isEmpty()) fallback = entry.targetProfile.hypixelChatName;
        if (fallback == null || fallback.isEmpty()) fallback = entry.targetProfile.name;
        if (GameContext.isHypixelLobby(mc)
                && !GameContext.shouldSuppressDisguiseNametagDecorations(mc)) {
            fallback = appendTargetGuildTagIfMissing(fallback, entry.targetProfile);
        }
        return ensureTargetLobbyRank(fallback, entry.targetProfile, mc);
    }

    /** Target network-level snapshot for a disguise applied to the local player by any disguiser command. */
    public static synchronized LocalDisguise.RemoteDisguiseProfile getLocalPlayerNetworkXpTarget() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) {
            return null;
        }
        RemoteDisguiseEntry entry = findEntry(mc.thePlayer.getGameProfile());
        if (entry == null || entry.targetProfile == null || !entry.targetProfile.networkLevelKnown) {
            return null;
        }
        return entry.targetProfile;
    }

    /** The vanilla XP bar shows Hypixel network level in lobbies, so mirror the disguised target there. */
    public static boolean shouldOverrideLocalPlayerHypixelLobbyXpBar() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)
                || !GameContext.isHypixelLobby(mc)
                || GameContext.isBlitzGameActive(mc)
                || GameContext.isHypixelReplayWatching(mc)) {
            return false;
        }
        LocalDisguise.RemoteDisguiseProfile target = getLocalPlayerNetworkXpTarget();
        return target != null && target.networkLevelKnown;
    }

    public static synchronized String getManualDisguiseSourceName(GameProfile profile) {
        RemoteDisguiseEntry entry = findManualEntry(profile);
        return entry == null ? null : entry.sourceName;
    }

    /**
     * Lunar's enhanced TAB can pass the source profile name straight to FontRenderer, bypassing the composed
     * NetworkPlayerInfo row. Replace only that exact source-name draw while TAB is active with the line Gomod has
     * composed for the manual disguise (target name, pregame rank/guild policy and TAB-QoL stats).
     */
    public static String rewriteManualDisguiseTabRenderedText(String renderedText) {
        if (!GomodFixes.isWeaveRuntime() || renderedText == null || renderedText.isEmpty()) {
            return renderedText;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.getNetHandler() == null) {
            return renderedText;
        }
        boolean tabKeyDown = mc.gameSettings.keyBindPlayerList != null
                && mc.gameSettings.keyBindPlayerList.isKeyDown();
        if (!KillCounter.isTabOverlayRenderActive()
                && (mc.currentScreen != null || !tabKeyDown)) {
            return renderedText;
        }

        if (!hasTabDisguiseEntries()) {
            return renderedText;
        }
        // Lunar and Forge strip different style codes. Use the current runtime's rules for formatted text.
        String plain = renderedText.indexOf('\u00a7') < 0 ? renderedText
                : EnumChatFormatting.getTextWithoutFormattingCodes(renderedText);
        if (plain == null) return renderedText;
        String candidate = plain.trim();
        if (candidate.isEmpty()) return renderedText;
        NetworkPlayerInfo currentRow = KillCounter.currentTabOverlayRow();
        if (currentRow != null) {
            GameProfile sourceProfile = getSourceProfile(currentRow);
            LocalDisguise.RemoteDisguiseProfile target = getManualDisguiseTarget(sourceProfile);
            if (target != null && sourceProfile != null
                    && (candidate.equalsIgnoreCase(LocalDisguise.normalizeReplayProfileName(sourceProfile.getName()))
                    || candidate.equalsIgnoreCase(target.name))) {
                return manualTabRenderRow(currentRow, target);
            }
            // A supplied row is authoritative; never resolve its display alias to a different player's row.
            return renderedText;
        }
        if (!hasActiveManualDisguiseForSourceName(candidate)) {
            return renderedText;
        }
        try {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || getSourceProfile(info) == null) {
                    continue;
                }
                GameProfile profile = getSourceProfile(info);
                String sourceName = profile.getName();
                if (sourceName == null || !candidate.equalsIgnoreCase(
                        LocalDisguise.normalizeReplayProfileName(sourceName))) {
                    continue;
                }
                LocalDisguise.RemoteDisguiseProfile target = getManualDisguiseTarget(profile);
                if (target == null || target.name == null || target.name.isEmpty()) {
                    continue;
                }

                return manualTabRenderRow(info, target);
            }
        } catch (Throwable ignored) {
        }
        return renderedText;
    }

    private static synchronized boolean hasTabDisguiseEntries() {
        return !BY_SOURCE_NAME.isEmpty() || !BY_SOURCE_UUID.isEmpty()
                || !BY_GOMOD_PEER_NAME.isEmpty() || !BY_GOMOD_PEER_UUID.isEmpty();
    }

    private static String manualTabRenderRow(NetworkPlayerInfo info, LocalDisguise.RemoteDisguiseProfile target) {
        String replacement = KillCounter.getRenderedTabLineForInfo(info);
        if (replacement == null || replacement.isEmpty()) {
            IChatComponent display = info.getDisplayName();
            replacement = display == null ? target.name : display.getFormattedText();
        }
        if (replacement == null || replacement.isEmpty()) replacement = target.name;
        GameProfile source = getSourceProfile(info);
        String sourceName = source == null ? "<unknown>" : source.getName();
        long count = MANUAL_TAB_FINAL_RENDER_REWRITE_COUNT.incrementAndGet();
        String visibleReplacement = EnumChatFormatting.getTextWithoutFormattingCodes(replacement);
        lastManualTabFinalRenderRewrite = "count=" + count + " source=" + sourceName
                + " target=" + target.name + " output="
                + (visibleReplacement == null ? "<unavailable>" : visibleReplacement.trim());
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("remote-tab-font", sourceName,
                    "target=" + target.name + " output=" + replacement);
        }
        return replacement;
    }

    private static synchronized boolean hasActiveManualDisguiseForSourceName(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return false;
        }
        String normalized = LocalDisguise.normalizeReplayProfileName(sourceName);
        RemoteDisguiseEntry entry = normalized.isEmpty() ? null
                : BY_SOURCE_NAME.get(normalized.toLowerCase(Locale.ROOT));
        return entry != null && !entry.isExpired();
    }

    /** Last source-name fallback draw, for /disguiserdebug; no UUIDs or auth data are recorded. */
    public static String describeLastManualDisguiseTabRenderedText() {
        return lastManualTabFinalRenderRewrite;
    }

    public static synchronized boolean isManualFullLunarDisguise(GameProfile profile) {
        RemoteDisguiseEntry entry = findManualEntry(profile);
        return entry != null && entry.fullLunar;
    }

    static synchronized boolean isManualFullLunarDisguiseForPlayer(UUID playerId) {
        RemoteDisguiseEntry entry = findManualEntry(playerId, null);
        return entry != null && entry.fullLunar;
    }

    public static synchronized DisguiserLunarIcon getManualLunarIcon(GameProfile profile) {
        RemoteDisguiseEntry entry = findManualEntry(profile);
        return entry == null ? DisguiserLunarIcon.KEEP : entry.lunarIcon;
    }

    static synchronized DisguiserLunarIcon getManualLunarIconForPlayer(UUID playerId) {
        RemoteDisguiseEntry entry = findManualEntry(playerId, null);
        return entry == null ? DisguiserLunarIcon.KEEP : entry.lunarIcon;
    }

    public static synchronized String describeManualDisguise(GameProfile profile) {
        RemoteDisguiseEntry entry = findManualEntry(profile);
        return entry == null ? "active=false" : "active=true source=" + entry.sourceName
                + " icon=" + entry.lunarIcon.wire + " shared=" + BY_GOMOD_PEER_NAME.containsValue(entry)
                + " remainingMs=" + (entry.expiresAt == 0 ? -1 : Math.max(0, entry.expiresAt - System.currentTimeMillis()))
                + "\n" + GomodPeerStandaloneCape.describeManualCape(entry.targetProfile);
    }

    public static synchronized void attachCapeTargets(com.google.gson.JsonObject payload) {
        com.google.gson.JsonArray targets = new com.google.gson.JsonArray();
        java.util.Set<UUID> unique = new java.util.HashSet<UUID>();
        java.util.List<RemoteDisguiseEntry> entries = new java.util.ArrayList<RemoteDisguiseEntry>(BY_SOURCE_NAME.values());
        entries.addAll(BY_GOMOD_PEER_NAME.values());
        for (RemoteDisguiseEntry entry : entries) {
            if (!entry.manualCosmetics || entry.isExpired() || entry.targetProfile == null || entry.targetProfile.profile == null) continue;
            UUID uuid = entry.targetProfile.profile.getId();
            if (uuid != null && unique.add(uuid)) targets.add(new com.google.gson.JsonPrimitive(uuid.toString()));
            if (unique.size() >= 32) break;
        }
        payload.add("gomodDisguiserCapeTargets", targets);
    }

    private static RemoteDisguiseEntry findManualEntry(GameProfile profile) {
        return profile == null ? null : findManualEntry(profile.getId(), profile.getName());
    }

    private static RemoteDisguiseEntry findManualEntry(UUID playerId, String playerName) {
        if (BY_SOURCE_UUID.isEmpty() && BY_SOURCE_NAME.isEmpty()
                && BY_GOMOD_PEER_UUID.isEmpty() && BY_GOMOD_PEER_NAME.isEmpty()) return null;
        RemoteDisguiseEntry entry = playerId == null ? null : BY_SOURCE_UUID.get(playerId);
        String normalizedName = entry == null && playerName != null
                ? LocalDisguise.normalizeReplayProfileName(playerName).toLowerCase(Locale.ROOT) : "";
        if (entry == null && !normalizedName.isEmpty()) entry = BY_SOURCE_NAME.get(normalizedName);
        if (entry == null && playerId != null) entry = scopedPeerEntry(BY_GOMOD_PEER_UUID.get(playerId));
        if (entry == null && !normalizedName.isEmpty()) entry = scopedPeerEntry(BY_GOMOD_PEER_NAME.get(normalizedName));
        return entry != null && entry.manualCosmetics && !entry.isExpired() ? entry : null;
    }

    /** Timers continue while disconnected. Call on the game thread. */
    public static synchronized void expireTimedEntries() {
        boolean expiredManual = false;
        for (RemoteDisguiseEntry entry : new java.util.ArrayList<RemoteDisguiseEntry>(BY_SOURCE_NAME.values())) {
            if (entry.isExpired()) {
                restoreEntry(entry, findInfoForEntry(entry, Minecraft.getMinecraft()));
                removeEntry(entry);
                TimedDisguiserStore.remove(entry.sourceName);
                expiredManual = true;
            }
        }
        if (expiredManual) KillCounter.markTabCompositionDirty();
        for (RemoteDisguiseEntry entry : new java.util.ArrayList<RemoteDisguiseEntry>(BY_GOMOD_PEER_NAME.values())) {
            if (entry.isExpired()) clearGomodPeerDisguise(entry.sourceUuid, entry.sourceName);
        }
    }

    /** Retry local full-Lunar overlays as soon as either player's native profile arrives. */
    public static synchronized void onLunarProfileAvailable(UUID playerId) {
        if (playerId == null) return;
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) applyFullLunarEntryIfRelevant(entry, playerId);
        for (RemoteDisguiseEntry entry : BY_GOMOD_PEER_NAME.values()) applyFullLunarEntryIfRelevant(scopedPeerEntry(entry), playerId);
    }

    private static void applyFullLunarEntryIfRelevant(RemoteDisguiseEntry entry, UUID playerId) {
        if (entry == null || !entry.fullLunar || entry.isExpired() || entry.sourceUuid == null
                || entry.targetProfile == null || entry.targetProfile.profile == null
                || entry.targetProfile.profile.getId() == null) return;
        if (playerId.equals(entry.sourceUuid) || playerId.equals(entry.targetProfile.profile.getId())) {
            try {
                LunarFullDisguise.applyPeerAppearance(entry.sourceUuid, entry.targetProfile.profile.getId());
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("gomod.peer_disguise.lunar_profile",
                        "result=failed errorType=" + error.getClass().getSimpleName());
            }
        }
    }

    public static synchronized String getDisguisedDisplayNameFor(EntityPlayer player) {
        return getDisguisedDisplayNameFor(player, null);
    }

    /** Resolve the target's full Hypixel lobby row; pregame and live Blitz nametags stay name-only. */
    public static synchronized String getDisguisedDisplayNameFor(EntityPlayer player, String sourceFormatted) {
        if (player == null || player.getGameProfile() == null) {
            return null;
        }
        RemoteDisguiseEntry entry = findEntry(player.getGameProfile());
        if (entry == null) {
            return null;
        }
        // Outside Hypixel, keep the server-provided team/chat formatting and only substitute the name.
        if (!GameContext.isOnHypixel(Minecraft.getMinecraft())) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (GameContext.isHypixelReplayWatching(mc)) {
            // Replay actor labels are plain white IGNs, even when the disguise target has a Hypixel rank
            // or guild tag in lobby/TAB data.
            return EnumChatFormatting.WHITE + entry.targetProfile.name + EnumChatFormatting.RESET;
        }
        String sourceName = LocalDisguise.normalizeReplayProfileName(player.getGameProfile().getName());
        boolean hypixelBlitzMaskedName = LunarDisguiseDisplayCompatibility.containsObfuscatedHypixelName(
                sourceFormatted, sourceName);
        // The exact §kXXXX<IGN> mask is a Blitz match label. Treat it as a match nametag even during the
        // short scoreboard transition into cages, before GameContext has caught up from the previous lobby.
        if (GameContext.shouldSuppressDisguiseNametagDecorations(mc) || hypixelBlitzMaskedName) {
            String blitzNameColor = GameContext.getBlitzDisguiseNametagNameColor(
                    mc, player, sourceFormatted);
            if (blitzNameColor != null) {
                return EnumChatFormatting.RESET + blitzNameColor + entry.targetProfile.name
                        + EnumChatFormatting.RESET;
            }
            if (sourceFormatted == null || sourceFormatted.isEmpty()) {
                return EnumChatFormatting.RESET + entry.targetProfile.name + EnumChatFormatting.RESET;
            }
            String sourceWithoutGuild = KillCounter.stripGuildTagFromDisguiseDisplay(sourceFormatted);
            String replaced = LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(sourceWithoutGuild,
                    sourceName, entry.targetProfile.name);
            if (sourceWithoutGuild.equals(replaced)) {
                return EnumChatFormatting.RESET + entry.targetProfile.name + EnumChatFormatting.RESET;
            }
            return KillCounter.stripGuildTagFromDisguiseDisplay(replaced);
        }

        String targetFormatted = findTargetFormattedDisplay(entry, mc);
        if (targetFormatted != null && !targetFormatted.isEmpty()) {
            return appendTargetGuildTagIfMissing(targetFormatted, entry.targetProfile);
        }
        if (sourceFormatted == null || sourceFormatted.isEmpty()) {
            return null;
        }
        String sourceWithoutGuild = GameContext.isOnHypixel(mc)
                ? KillCounter.stripGuildTagFromDisguiseDisplay(sourceFormatted) : sourceFormatted;
        String replaced = replaceNameInFormatted(sourceWithoutGuild,
                LocalDisguise.normalizeReplayProfileName(player.getGameProfile().getName()),
                entry.targetProfile.name);
        return appendTargetGuildTagIfMissing(replaced, entry.targetProfile);
    }

    /** Active local or Gomod-shared disguise target used by incoming chat and kill-feed display rewrites. */
    public static synchronized String getDisguiseTargetNameForSourceName(String sourceName) {
        LocalDisguise.RemoteDisguiseProfile target = getDisguiseTargetProfileForSourceName(sourceName);
        return target == null ? null : target.name;
    }

    public static synchronized LocalDisguise.RemoteDisguiseProfile getDisguiseTargetProfileForSourceName(
            String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) return null;
        RemoteDisguiseEntry entry = findSourceEntryByName(sourceName);
        return entry == null || entry.isExpired() || entry.targetProfile == null ? null : entry.targetProfile;
    }

    /** Rewrites a Hypixel leaderboard hologram row for an active /disguiser or shared peer disguise. */
    public static synchronized String rewriteLeaderboardHologramText(String formatted) {
        if (formatted == null || formatted.isEmpty() || !hasEntries()) {
            return formatted;
        }
        String rektHologram = rewriteRektFinisherHologram(formatted);
        if (!formatted.equals(rektHologram)) return rektHologram;
        if (formatted.indexOf('.') < 0 || formatted.indexOf('-') < 0) return formatted;

        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) visible = formatted;
        Matcher row = LEADERBOARD_HOLOGRAM_ROW.matcher(visible);
        if (!row.matches()) return formatted;

        String sourceName = findDisguiseSourceNameInText(row.group(2), 0);
        if (sourceName == null || sourceName.isEmpty()) return formatted;
        LocalDisguise.RemoteDisguiseProfile target = getDisguiseTargetProfileForSourceName(sourceName);
        if (target == null || target.name == null || target.name.isEmpty()) return formatted;

        // Leaderboard holograms show the disguised name without a Hypixel rank prefix,
        // matching LocalDisguise's existing leaderboard-row behavior. Keep the target's
        // rank-derived name color, and add only its guild suffix where lobby decorations apply.
        String targetDisplay = target.hypixelChatName;
        if (targetDisplay == null || targetDisplay.isEmpty()) targetDisplay = target.name;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && GameContext.isHypixelLobby(mc)
                && !GameContext.shouldSuppressDisguiseNametagDecorations(mc)) {
            targetDisplay = appendTargetGuildTagIfMissing(targetDisplay, target);
        }
        return LeaderboardHologramFormatting.replacePlayerSegment(formatted, targetDisplay);
    }

    /** Recolors a disguised player's name in the #rekt world-space finisher label without adding a rank prefix. */
    private static String rewriteRektFinisherHologram(String formatted) {
        if (formatted.indexOf('#') < 0 || !hasEntries()) return formatted;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) visible = formatted;
        Matcher hologram = REKT_FINISHER_HOLOGRAM.matcher(visible);
        if (!hologram.matches()) return formatted;

        String rewritten = formatted;
        // The finisher text contains two player identities: the player who got the kill is group 1,
        // and the player marked by #rekt is group 2. Resolve both independently, replacing the later
        // span first so a longer target name cannot shift the earlier span's formatted offsets.
        for (int group = 2; group >= 1; group--) {
            String displayedName = hologram.group(group);
            RemoteDisguiseEntry entry = findSourceEntryByName(displayedName);
            if (entry == null) entry = findEntryForRektDisplayName(displayedName);
            LocalDisguise.RemoteDisguiseProfile target = entry == null ? null : entry.targetProfile;
            if (target == null || target.name == null || target.name.isEmpty()) continue;

            String targetDisplay = target.hypixelChatName;
            if (targetDisplay == null || targetDisplay.isEmpty()) targetDisplay = target.name;
            int visibleStart = hologram.start(group);
            int visibleEnd = hologram.end(group);
            int formattedStart = mapVisibleIndexToFormatted(formatted, visibleStart);
            // Keep formatting codes immediately after the IGN: they style the following hologram text
            // (for example, Hypixel's yellow " here" suffix), rather than belonging to the name.
            int formattedEnd = mapVisibleEndIndexToFormatted(formatted, visibleEnd);
            if (formattedStart < 0 || formattedEnd < formattedStart || formattedEnd > rewritten.length()) continue;
            rewritten = rewritten.substring(0, formattedStart) + targetDisplay + rewritten.substring(formattedEnd);
        }
        return rewritten;
    }

    /** Reapply the target's rank color if a cached hologram already contains its disguised name. */
    private static RemoteDisguiseEntry findEntryForRektDisplayName(String displayedName) {
        if (displayedName == null || displayedName.isEmpty()) return null;
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) {
            if (isActiveRektTargetName(entry, displayedName)) return entry;
        }
        for (Map.Entry<String, RemoteDisguiseEntry> peer : BY_GOMOD_PEER_NAME.entrySet()) {
            RemoteDisguiseEntry entry = scopedPeerEntry(peer.getValue());
            if (entry == null || (BY_SOURCE_NAME.get(entry.sourceNameLower) != null
                    && !BY_SOURCE_NAME.get(entry.sourceNameLower).isExpired())) continue;
            if (isActiveRektTargetName(entry, displayedName)) return entry;
        }
        return null;
    }

    private static boolean isActiveRektTargetName(RemoteDisguiseEntry entry, String displayedName) {
        return entry != null && !entry.isExpired() && entry.targetProfile != null
                && entry.targetProfile.name != null
                && entry.targetProfile.name.equalsIgnoreCase(displayedName);
    }

    /** Finds a currently disguised source inside a visible kill-feed line after its "by" marker. */
    public static synchronized String findDisguiseSourceNameInText(String text, int fromIndex) {
        if (text == null || text.isEmpty() || fromIndex < 0) return null;
        int bestIndex = Integer.MAX_VALUE;
        String bestName = null;
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) {
            if (entry == null || entry.isExpired() || entry.targetProfile == null
                    || entry.sourceName.isEmpty()) continue;
            int index = indexOfStandaloneName(text, entry.sourceName, fromIndex);
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                bestName = entry.sourceName;
            }
        }
        for (Map.Entry<String, RemoteDisguiseEntry> peer : BY_GOMOD_PEER_NAME.entrySet()) {
            RemoteDisguiseEntry local = BY_SOURCE_NAME.get(peer.getKey());
            if (local != null && !local.isExpired()) continue;
            RemoteDisguiseEntry entry = scopedPeerEntry(peer.getValue());
            if (entry == null || entry.isExpired() || entry.targetProfile == null
                    || entry.sourceName.isEmpty()) continue;
            int index = indexOfStandaloneName(text, entry.sourceName, fromIndex);
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                bestName = entry.sourceName;
            }
        }
        return bestName;
    }

    public static synchronized boolean isDisguiseTournamentKillCounterEnabled(String sourceName) {
        LocalDisguise.RemoteDisguiseProfile target = getDisguiseTargetProfileForSourceName(sourceName);
        return target != null && target.isBlitzTournamentKillCounterEnabled();
    }

    public static synchronized Integer getDisguiseTournamentKillCount(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) return null;
        RemoteDisguiseEntry entry = findSourceEntryByName(sourceName);
        if (entry == null || entry.isExpired() || entry.targetProfile == null) return null;
        Integer targetKills = entry.targetProfile.getBlitzKillCount();
        return targetKills == null ? null : Math.max(0, targetKills + entry.matchKillOffset);
    }

    /** Observes Hypixel result lines so local and peer-shared disguises advance the target's tournament kill number. */
    public static synchronized void handleIncomingChat(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String normalized = message.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean reset = lower.contains("cage open") || lower.contains("cages open")
                || lower.contains("game starts in 1 second")
                || lower.contains("survive while eliminating")
                || lower.contains("blitz star will be released in 3 minutes")
                || lower.contains("you died!") || lower.contains("want to play again?");
        long now = System.currentTimeMillis();
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) {
            observeTournamentKillCounter(entry, normalized, lower, now, reset);
        }
        for (Map.Entry<String, RemoteDisguiseEntry> peer : BY_GOMOD_PEER_NAME.entrySet()) {
            RemoteDisguiseEntry local = BY_SOURCE_NAME.get(peer.getKey());
            if (local == null || local.isExpired()) {
                observeTournamentKillCounter(scopedPeerEntry(peer.getValue()), normalized, lower, now, reset);
            }
        }
    }

    private static boolean messageShowsDisguisedSourceAsKiller(String message, String lowerMessage, String sourceName) {
        if (message == null || lowerMessage == null || sourceName == null || sourceName.isEmpty()) return false;
        int markerEnd = -1;
        int earliest = Integer.MAX_VALUE;
        for (String marker : MANUAL_KILL_FEED_MARKERS) {
            int at = lowerMessage.indexOf(marker);
            if (at >= 0 && at < earliest) {
                earliest = at;
                markerEnd = at + marker.length();
            }
        }
        return markerEnd >= 0 && indexOfStandaloneName(message, sourceName, markerEnd) >= 0;
    }

    private static RemoteDisguiseEntry findSourceEntryByName(String sourceName) {
        String normalizedName = LocalDisguise.normalizeReplayProfileName(sourceName);
        if (normalizedName.isEmpty()) return null;
        String key = normalizedName.toLowerCase(Locale.ROOT);
        RemoteDisguiseEntry entry = BY_SOURCE_NAME.get(key);
        if (entry != null && !entry.isExpired()) return entry;
        return scopedPeerEntry(BY_GOMOD_PEER_NAME.get(key));
    }

    private static void observeTournamentKillCounter(RemoteDisguiseEntry entry, String normalized,
            String lower, long now, boolean reset) {
        if (entry == null || entry.isExpired() || entry.targetProfile == null) return;
        if (reset) {
            entry.matchKillOffset = 0;
            entry.lastCountedKillMessage = null;
            entry.lastCountedKillMessageMs = 0L;
            return;
        }
        if (entry.targetProfile.getBlitzKillCount() == null
                || !messageShowsDisguisedSourceAsKiller(normalized, lower, entry.sourceName)) return;
        if (normalized.equals(entry.lastCountedKillMessage) && now - entry.lastCountedKillMessageMs <= 1500L) return;
        entry.matchKillOffset++;
        entry.lastCountedKillMessage = normalized;
        entry.lastCountedKillMessageMs = now;
    }

    private static int indexOfStandaloneName(String text, String name, int fromIndex) {
        if (text == null || name == null || name.isEmpty()) return -1;
        int from = Math.max(0, fromIndex);
        for (int i = from; i <= text.length() - name.length(); i++) {
            if (!text.regionMatches(true, i, name, 0, name.length())) continue;
            if ((i == 0 || !isMinecraftNameCharacter(text.charAt(i - 1)))
                    && (i + name.length() == text.length()
                    || !isMinecraftNameCharacter(text.charAt(i + name.length())))) return i;
        }
        return -1;
    }

    private static boolean isMinecraftNameCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    public static synchronized boolean hasActiveManualDisguises() {
        for (RemoteDisguiseEntry entry : BY_SOURCE_NAME.values()) {
            if (entry.manualCosmetics && !entry.isExpired() && entry.targetProfile != null) return true;
        }
        return false;
    }

    private static String appendTargetGuildTagIfMissing(String formatted,
            LocalDisguise.RemoteDisguiseProfile target) {
        if (formatted == null || formatted.isEmpty() || target == null) {
            return formatted;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc)) {
            return formatted;
        }
        if (target.guildTag == null || target.guildTag.isEmpty()) {
            return formatted;
        }
        // A composed line can contain the source's trailing guild and another stale guild from a previous refresh.
        // Strip all trailing guild-shaped suffixes, then append exactly the target's current guild once.
        String normalized = KillCounter.stripGuildTagFromDisguiseDisplay(formatted);
        String color = target.guildTagColor == null || target.guildTagColor.isEmpty()
                ? EnumChatFormatting.WHITE.toString() : target.guildTagColor;
        return normalized + " " + EnumChatFormatting.RESET + color + "[" + target.guildTag + "]"
                + EnumChatFormatting.RESET;
    }

    public static synchronized void applyNow() {
        expireTimedEntries();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }

        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        if (infos == null || infos.isEmpty()) {
            return;
        }

        for (NetworkPlayerInfo info : infos) {
            if (info == null || getSourceProfile(info) == null || getSourceProfile(info).getName() == null) {
                continue;
            }
            RemoteDisguiseEntry entry = findEntry(getSourceProfile(info));
            if (entry == null) {
                continue;
            }
            applyToInfo(info, entry);
        }
    }

    public static String replaceNameInFormatted(String formatted, String originalName, String targetName) {
        return GomodPeerDisguiseDisplay.replaceFormattedName(formatted, originalName, targetName);
    }

    /** Range boundary used when rebuilding complete rank/hologram segments rather than replacing an IGN. */
    private static int mapVisibleIndexToFormatted(String formatted, int visibleIndex) {
        if (visibleIndex <= 0) return 0;
        int visibleCount = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            if (visibleCount == visibleIndex) return i;
            visibleCount++;
        }
        return formatted.length();
    }

    /** Maps an exclusive visible-text end before formatting codes that belong to the following text. */
    private static int mapVisibleEndIndexToFormatted(String formatted, int visibleEndIndex) {
        if (formatted == null || visibleEndIndex <= 0) return 0;
        int visibleCount = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (visibleCount == visibleEndIndex) return i;
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            visibleCount++;
        }
        return formatted.length();
    }

    private static void resolveSourceUuidFromTab(RemoteDisguiseEntry entry) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return;
        }
        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        if (infos == null) {
            return;
        }
        for (NetworkPlayerInfo info : infos) {
            if (info == null || getSourceProfile(info) == null || getSourceProfile(info).getName() == null) {
                continue;
            }
            if (entry.sourceNameLower.equals(getSourceProfile(info).getName().toLowerCase(Locale.ROOT))) {
                UUID uuid = getSourceProfile(info).getId();
                if (uuid != null) {
                    entry.sourceUuid = uuid;
                }
                return;
            }
        }
    }

    private static RemoteDisguiseEntry scopedPeerEntry(RemoteDisguiseEntry entry) {
        return entry != null && GomodPeerDisguise.allowsPeerAppearance(entry.sourceUuid, entry.sourceName)
                ? entry : null;
    }

    /** New TAB records must receive a retained disguise before their first render. */
    public static synchronized void applyToJoinedInfo(NetworkPlayerInfo info) {
        if (info == null) return;
        RemoteDisguiseEntry entry = findEntry(getSourceProfile(info));
        if (entry != null) applyToInfo(info, entry);
    }

    private static RemoteDisguiseEntry findEntry(GameProfile profile) {
        if (profile == null) {
            return null;
        }

        UUID uuid = profile.getId();
        if (uuid != null) {
            RemoteDisguiseEntry entry = BY_SOURCE_UUID.get(uuid);
            if (entry != null) {
                return entry;
            }
            entry = scopedPeerEntry(BY_GOMOD_PEER_UUID.get(uuid));
            if (entry != null) {
                return entry;
            }
        }

        String name = LocalDisguise.normalizeReplayProfileName(profile.getName());
        if (name.isEmpty()) {
            return null;
        }

        RemoteDisguiseEntry entry = BY_SOURCE_NAME.get(name.toLowerCase(Locale.ROOT));
        if (entry != null && entry.sourceUuid == null && uuid != null) {
            entry.sourceUuid = uuid;
            BY_SOURCE_UUID.put(uuid, entry);
        }
        if (entry == null) {
            entry = scopedPeerEntry(BY_GOMOD_PEER_NAME.get(name.toLowerCase(Locale.ROOT)));
            if (entry != null && entry.sourceUuid == null && uuid != null) {
                entry.sourceUuid = uuid;
                BY_GOMOD_PEER_UUID.put(uuid, entry);
            }
        }
        return entry;
    }

    private static void applyToInfo(NetworkPlayerInfo info, RemoteDisguiseEntry entry) {
        if (info == null || entry == null || entry.targetProfile == null) {
            return;
        }

        if (entry.lastInfo != info || entry.original == null) {
            entry.original = captureOriginal(info, entry);
            entry.lastInfo = info;
        }

        Minecraft mc = Minecraft.getMinecraft();
        String replaced;
        boolean suppressHypixelTabDecorations = GameContext.isOnHypixel(mc)
                && (GameContext.isHypixelPregameLobby(mc)
                || GameContext.isBlitzTabDisguiseRankSuppressed(mc));
        boolean preserveLiveBlitzSourceTabColor = GameContext.isOnHypixel(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))
                && !GameContext.isBlitzGameOverSidebar(mc);
        if (suppressHypixelTabDecorations || preserveLiveBlitzSourceTabColor) {
            // Both pregame and live Blitz suppress lobby rank decorations. Resolve the current source row first:
            // the pregame branch used to bypass the live-color rule and retain an old white snapshot.
            String originalName = sourceProfileName(info, entry);
            String baseFormatted = currentSourceTabDisplayName(info, entry, originalName);
            if (preserveLiveBlitzSourceTabColor) {
                baseFormatted = liveBlitzSourceTabName(info, originalName, baseFormatted,
                        KillCounter.isPlayerDead(originalName) || SpectatorRelay.isTabSpectator(info));
            }
            baseFormatted = KillCounter.stripGuildTagFromDisguiseDisplay(baseFormatted);
            replaced = LocalDisguise.rewriteSuppressedHypixelPregamePeerTabLine(baseFormatted, originalName,
                    entry.targetProfile.name);
            // Blitz pregame should keep the target's guild in TAB QoL while still suppressing
            // the target's ranked TAB prefix. Other Hypixel pregame rows keep guilds suppressed.
            if (GameContext.isBlitzPregameLobby(mc)) {
                replaced = appendTargetGuildTagIfMissing(replaced, entry.targetProfile);
            }
        } else {
            String targetFormatted = GameContext.isOnHypixel(mc)
                    ? findTargetFormattedDisplay(entry, mc)
                    : null;
            if (targetFormatted != null && !targetFormatted.isEmpty()) {
                replaced = targetFormatted;
            } else {
                String baseFormatted = buildBaseDisplayName(info);
                if (GameContext.isOnHypixel(mc)) {
                    baseFormatted = KillCounter.stripGuildTagFromDisguiseDisplay(baseFormatted);
                }
                String originalName = getSourceProfile(info) == null ? entry.sourceName : getSourceProfile(info).getName();
                replaced = replaceNameInFormatted(baseFormatted, originalName, entry.targetProfile.name);
            }
            replaced = appendTargetGuildTagIfMissing(replaced, entry.targetProfile);
        }
        info.setDisplayName(new ChatComponentText(replaced));
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("remote-tab-row", entry.sourceName,
                    "target=" + entry.targetProfile.name + " preserveSourceColor="
                            + (suppressHypixelTabDecorations || preserveLiveBlitzSourceTabColor)
                            + " team=" + ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), sourceProfileName(info, entry))
                            + " output=" + replaced);
        }
        if (entry.sourceUuid == null && getSourceProfile(info) != null) {
            entry.sourceUuid = getSourceProfile(info).getId();
            if (entry.sourceUuid != null) {
                if (BY_SOURCE_NAME.get(entry.sourceNameLower) == entry) BY_SOURCE_UUID.put(entry.sourceUuid, entry);
                if (BY_GOMOD_PEER_NAME.get(entry.sourceNameLower) == entry) BY_GOMOD_PEER_UUID.put(entry.sourceUuid, entry);
            }
        }

        ResourceLocation targetCape = entry.manualCosmetics
                ? GomodPeerStandaloneCape.capeForManualTarget(entry.targetProfile)
                : entry.targetProfile.capeLocation;

        if (info instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setPlayerTexturesLoaded(true);
            accessor.gomod$setLocationSkin(entry.targetProfile.skinLocation);
            accessor.gomod$setLocationCape(targetCape);
            accessor.gomod$setSkinType(entry.targetProfile.skinType);
            applyFullLunarEntryIfRelevant(entry, entry.targetProfile.profile.getId());
            return;
        }

        ReflectionHandles handles = ensureReflectionHandles(info);
        if (handles == null) {
            return;
        }

        try {
            if (handles.playerTexturesLoaded != null) {
                handles.playerTexturesLoaded.setBoolean(info, true);
            }
            if (handles.locationSkin != null) {
                handles.locationSkin.set(info, entry.targetProfile.skinLocation);
            }
            if (handles.locationCape != null) {
                handles.locationCape.set(info, targetCape);
            }
            if (handles.skinType != null) {
                handles.skinType.set(info, entry.targetProfile.skinType);
            }
        } catch (Throwable ignored) {
        }
        if (entry.targetProfile.profile != null) {
            applyFullLunarEntryIfRelevant(entry, entry.targetProfile.profile.getId());
        }
    }

    /** Keeps the freshest server-provided source row while it still contains that source IGN. */
    private static String currentSourceTabDisplayName(NetworkPlayerInfo info, RemoteDisguiseEntry entry,
            String sourceName) {
        ScorePlayerTeam team = info == null ? null : info.getPlayerTeam();
        String teamFormatted = ScorePlayerTeam.formatPlayerName(team, sourceName == null ? "" : sourceName);
        Minecraft mc = Minecraft.getMinecraft();
        boolean preserveLiveBlitzTint = GameContext.isOnHypixel(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc))
                && !GameContext.isBlitzGameOverSidebar(mc);
        if (preserveLiveBlitzTint && hasLegacyColorCode(teamFormatted)
                && sourceName != null && containsExactName(
                        EnumChatFormatting.getTextWithoutFormattingCodes(teamFormatted), sourceName)) {
            if (entry != null && entry.original != null) {
                entry.original.displayName = new ChatComponentText(teamFormatted);
                entry.original.teamFormattedName = teamFormatted;
            }
            return liveBlitzSourceTabName(info, sourceName, teamFormatted,
                    KillCounter.isPlayerDead(sourceName) || SpectatorRelay.isTabSpectator(info));
        }
        if (info != null && sourceName != null && !sourceName.isEmpty()) {
            IChatComponent liveDisplay = info.getDisplayName();
            if (liveDisplay != null) {
                String liveFormatted = liveDisplay.getFormattedText();
                String visible = EnumChatFormatting.getTextWithoutFormattingCodes(liveFormatted);
                if (visible != null && containsExactName(visible, sourceName)) {
                    if (entry != null && entry.original != null) {
                        entry.original.displayName = new ChatComponentText(liveFormatted);
                        entry.original.teamFormattedName = teamFormatted;
                    }
                    return liveFormatted;
                }
            }
        }
        if (entry != null && entry.original != null && entry.original.displayName != null) {
            // Scoreboard team updates can change white pregame names to red/blue without a new displayName packet.
            if (entry.original.teamFormattedName != null
                    && !teamFormatted.equals(entry.original.teamFormattedName)) {
                entry.original.displayName = new ChatComponentText(teamFormatted);
                entry.original.teamFormattedName = teamFormatted;
                return teamFormatted;
            }
            String originalFormatted = entry.original.displayName.getFormattedText();
            String visible = EnumChatFormatting.getTextWithoutFormattingCodes(originalFormatted);
            if (visible != null && (sourceName == null || containsExactName(visible, sourceName))) {
                return originalFormatted;
            }
        }
        return teamFormatted;
    }

    private static boolean hasLegacyColorCode(String formatted) {
        if (formatted == null) return false;
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00a7') {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') return true;
                i++;
            }
        }
        return false;
    }

    /** A neutral pregame team/display snapshot is not an alive match color. */
    public static String liveBlitzSourceTabName(NetworkPlayerInfo info, String sourceName,
                                               String formatted, boolean dead) {
        if (info == null || sourceName == null || formatted == null) return formatted;
        String teamText = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), sourceName);
        String color = nameRelationColor(teamText, sourceName);
        if (dead) color = EnumChatFormatting.BLUE.toString();
        if (color == null) color = nameRelationColor(formatted, sourceName);
        if (color == null) {
            Minecraft mc = Minecraft.getMinecraft();
            boolean sameTeam = mc != null && mc.thePlayer != null && BlitzTeamsDetector.isBlitzTeamsGame(mc)
                    && info.getPlayerTeam() != null
                    && mc.thePlayer.getTeam() != null && mc.thePlayer.getTeam().isSameTeam(info.getPlayerTeam());
            color = (sameTeam ? EnumChatFormatting.GREEN : EnumChatFormatting.RED).toString();
        }
        return replaceNameInFormatted(formatted, sourceName, color + sourceName + EnumChatFormatting.RESET);
    }

    private static String nameRelationColor(String formatted, String name) {
        if (formatted == null || name == null) return null;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        int at = findExactNameIndex(visible, name);
        if (at < 0) return null;
        int end = mapVisibleIndexToFormatted(formatted, at);
        // A name at visible offset zero can still have leading legacy color codes.
        while (end + 1 < formatted.length() && formatted.charAt(end) == '\u00a7') end += 2;
        char color = 'f';
        for (int i = 0; i + 1 < end; i++) {
            if (formatted.charAt(i) != '\u00a7') continue;
            char next = Character.toLowerCase(formatted.charAt(++i));
            if (next == 'r') color = 'f';
            else if (next >= '0' && next <= '9' || next >= 'a' && next <= 'f') color = next;
        }
        return color == 'a' || color == 'c' || color == '9' ? "\u00a7" + color : null;
    }

    private static String buildBaseDisplayName(NetworkPlayerInfo info) {
        if (info == null) {
            return "";
        }
        IChatComponent display = info.getDisplayName();
        if (display != null) {
            return display.getFormattedText();
        }

        GameProfile profile = getSourceProfile(info);
        String name = profile == null ? "" : profile.getName();
        ScorePlayerTeam team = info.getPlayerTeam();
        return ScorePlayerTeam.formatPlayerName(team, name);
    }

    private static String findTargetFormattedDisplay(RemoteDisguiseEntry entry, Minecraft mc) {
        if (entry == null || entry.targetProfile == null || entry.targetProfile.name == null
                || mc == null || !GameContext.isOnHypixel(mc)) {
            return null;
        }

        Collection<NetworkPlayerInfo> infos = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfoMap();
        if (infos == null) {
            return entry.targetProfile.hypixelDisplayName;
        }

        for (NetworkPlayerInfo info : infos) {
            if (info == null || getSourceProfile(info) == null || getSourceProfile(info).getName() == null) {
                continue;
            }
            // A disguised source has the target's display name but retains its own UUID and rank.
            // Only the actual target's row is authoritative for target rank formatting.
            if (entry.targetProfile.uuid != null
                    ? !entry.targetProfile.uuid.equals(getSourceProfile(info).getId())
                    : !entry.targetProfile.name.equalsIgnoreCase(getSourceProfile(info).getName())) {
                continue;
            }
            String rendered = KillCounter.getRenderedTabLineForInfo(info);
            if (rendered != null && !rendered.isEmpty()) {
                return ensureTargetLobbyRank(appendTargetGuildTagIfMissing(rendered, entry.targetProfile),
                        entry.targetProfile, mc);
            }
            IChatComponent display = info.getDisplayName();
            if (display != null) {
                return ensureTargetLobbyRank(appendTargetGuildTagIfMissing(display.getFormattedText(), entry.targetProfile),
                        entry.targetProfile, mc);
            }
            ScorePlayerTeam team = info.getPlayerTeam();
            return ensureTargetLobbyRank(appendTargetGuildTagIfMissing(
                    ScorePlayerTeam.formatPlayerName(team, getSourceProfile(info).getName()), entry.targetProfile),
                    entry.targetProfile, mc);
        }
        return ensureTargetLobbyRank(appendTargetGuildTagIfMissing(entry.targetProfile.hypixelDisplayName,
                entry.targetProfile), entry.targetProfile, mc);
    }

    /** In Hypixel lobbies, the native target row can omit [MVP+]; restore it from the target's API profile. */
    private static String ensureTargetLobbyRank(String formatted, LocalDisguise.RemoteDisguiseProfile target,
            Minecraft mc) {
        if (formatted == null || target == null || mc == null || !GameContext.isHypixelLobby(mc)
                || target.name == null || target.hypixelDisplayName == null) {
            return formatted;
        }
        String targetRank = rankPrefixBeforeName(target.hypixelDisplayName, target.name);
        if (targetRank == null || targetRank.equalsIgnoreCase(rankPrefixBeforeName(formatted, target.name))) {
            return formatted;
        }
        return appendTargetGuildTagIfMissing(target.hypixelDisplayName, target);
    }

    private static String rankPrefixBeforeName(String formatted, String name) {
        if (formatted == null || name == null || name.isEmpty()) return null;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) return null;
        int index = visible.toLowerCase(Locale.ROOT).indexOf(name.toLowerCase(Locale.ROOT));
        if (index <= 0) return null;
        String prefix = visible.substring(0, index).trim();
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]\\s*$").matcher(prefix);
        return match.find() ? match.group(1) : null;
    }

    private static void restoreEntry(RemoteDisguiseEntry entry, NetworkPlayerInfo info) {
        if (entry == null) return;
        if (entry.fullLunar && entry.sourceUuid != null) {
            LunarFullDisguise.clearPeerAppearance(entry.sourceUuid);
        }
        if (entry.original == null || info == null) return;
        OriginalSnapshot original = entry.original;
        String sourceName = sourceProfileName(info, entry);
        IChatComponent restoredDisplayName = restoreDisplayName(original.displayName, entry, sourceName);
        restoredDisplayName = ensureRestoredSourceName(restoredDisplayName, original, info, entry, sourceName);
        info.setDisplayName(restoredDisplayName);

        if (info instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setPlayerTexturesLoaded(original.texturesLoaded);
            accessor.gomod$setLocationSkin(original.skin);
            accessor.gomod$setLocationCape(original.cape);
            accessor.gomod$setSkinType(original.skinType);
            KillCounter.invalidateTabRowAfterDisguise(info);
            return;
        }

        ReflectionHandles handles = ensureReflectionHandles(info);
        if (handles == null) {
            KillCounter.invalidateTabRowAfterDisguise(info);
            return;
        }

        try {
            if (handles.playerTexturesLoaded != null) {
                handles.playerTexturesLoaded.setBoolean(info, original.texturesLoaded);
            }
            if (handles.locationSkin != null) {
                handles.locationSkin.set(info, original.skin);
            }
            if (handles.locationCape != null) {
                handles.locationCape.set(info, original.cape);
            }
            if (handles.skinType != null) {
                handles.skinType.set(info, original.skinType);
            }
        } catch (Throwable ignored) {
        }
        KillCounter.invalidateTabRowAfterDisguise(info);
    }

    /**
     * A replaced NetworkPlayerInfo can make the saved component already contain the disguise alias. In that case,
     * restore the server's source-team row instead of leaving the target alias in TAB after the disguise is cleared.
     */
    private static IChatComponent ensureRestoredSourceName(IChatComponent restoredDisplayName,
            OriginalSnapshot original, NetworkPlayerInfo info, RemoteDisguiseEntry entry, String sourceName) {
        if (entry == null || entry.targetProfile == null || sourceName == null || sourceName.isEmpty()) {
            return restoredDisplayName;
        }
        String restoredFormatted = restoredDisplayName == null ? null : restoredDisplayName.getFormattedText();
        String restoredVisible = restoredFormatted == null
                ? null : EnumChatFormatting.getTextWithoutFormattingCodes(restoredFormatted);
        if (restoredVisible == null || containsExactName(restoredVisible, sourceName)
                || !containsExactName(restoredVisible, entry.targetProfile.name)) {
            return restoredDisplayName;
        }

        String teamName = original.teamFormattedName;
        String teamVisible = teamName == null ? null : EnumChatFormatting.getTextWithoutFormattingCodes(teamName);
        if (teamVisible == null || !containsExactName(teamVisible, sourceName)) {
            teamName = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), sourceName);
            teamVisible = EnumChatFormatting.getTextWithoutFormattingCodes(teamName);
        }
        return teamVisible != null && containsExactName(teamVisible, sourceName)
                ? new ChatComponentText(teamName) : null;
    }

    private static NetworkPlayerInfo findInfoForEntry(RemoteDisguiseEntry entry, Minecraft mc) {
        if (entry == null || mc == null || mc.getNetHandler() == null) {
            return null;
        }

        Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
        if (infos == null) {
            return null;
        }

        for (NetworkPlayerInfo info : infos) {
            if (info == null || getSourceProfile(info) == null || getSourceProfile(info).getName() == null) {
                continue;
            }
            UUID uuid = getSourceProfile(info).getId();
            if (uuid != null && entry.sourceUuid != null && uuid.equals(entry.sourceUuid)) {
                return info;
            }
            if (entry.sourceNameLower.equals(getSourceProfile(info).getName().toLowerCase(Locale.ROOT))) {
                return info;
            }
        }
        return null;
    }

    private static OriginalSnapshot captureOriginal(NetworkPlayerInfo info, RemoteDisguiseEntry entry) {
        OriginalSnapshot snapshot = new OriginalSnapshot();
        if (info == null) {
            return snapshot;
        }

        snapshot.displayName = restoreDisplayName(info.getDisplayName(), entry, sourceProfileName(info, entry));
        snapshot.teamFormattedName = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), sourceProfileName(info, entry));

        if (info instanceof NetworkPlayerInfoAccessor) {
            // Read the backing fields directly. The public skin getters can trigger an asynchronous
            // texture load and return Steve while the real profile skin is still pending. Marking
            // that fallback as loaded would make expiry restore Steve permanently.
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            snapshot.texturesLoaded = accessor.gomod$getPlayerTexturesLoaded();
            snapshot.skin = accessor.gomod$getLocationSkin();
            snapshot.cape = accessor.gomod$getLocationCape();
            snapshot.skinType = accessor.gomod$getSkinType();
            return snapshot;
        }

        ReflectionHandles handles = ensureReflectionHandles(info);
        if (handles == null) {
            return snapshot;
        }

        try {
            if (handles.playerTexturesLoaded != null) {
                snapshot.texturesLoaded = handles.playerTexturesLoaded.getBoolean(info);
            }
            if (handles.locationSkin != null) {
                Object skin = handles.locationSkin.get(info);
                snapshot.skin = skin instanceof ResourceLocation ? (ResourceLocation) skin : null;
            }
            if (handles.locationCape != null) {
                Object cape = handles.locationCape.get(info);
                snapshot.cape = cape instanceof ResourceLocation ? (ResourceLocation) cape : null;
            }
            if (handles.skinType != null) {
                Object type = handles.skinType.get(info);
                snapshot.skinType = type instanceof String ? (String) type : null;
            }
        } catch (Throwable ignored) {
        }

        return snapshot;
    }

    /**
     * A server can replace its NetworkPlayerInfo row while a disguise is active. If we have to
     * snapshot that replacement, it may already contain the target IGN with the source rank.
     * Normalize just that exact username token so expiry cannot preserve the alias.
     */
    private static String sourceProfileName(NetworkPlayerInfo info, RemoteDisguiseEntry entry) {
        if (info != null && getSourceProfile(info) != null) {
            String profileName = getSourceProfile(info).getName();
            if (profileName != null && !profileName.trim().isEmpty()) {
                return LocalDisguise.normalizeReplayProfileName(profileName);
            }
        }
        return entry == null ? null : entry.sourceName;
    }

    private static IChatComponent restoreDisplayName(IChatComponent displayName, RemoteDisguiseEntry entry,
            String sourceProfileName) {
        if (displayName == null || entry == null || entry.sourceName == null || entry.targetProfile == null
                || entry.targetProfile.name == null) return displayName;
        String canonicalSourceName = sourceProfileName == null || sourceProfileName.trim().isEmpty()
                ? entry.sourceName : sourceProfileName;
        String formatted = displayName.getFormattedText();
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null || containsExactName(visible, canonicalSourceName)) return displayName;
        int targetIndex = findExactNameIndex(visible, entry.targetProfile.name);
        if (targetIndex < 0) return displayName;
        int start = mapVisibleIndexToFormatted(formatted, targetIndex);
        int end = mapVisibleIndexToFormatted(formatted, targetIndex + entry.targetProfile.name.length());
        if (start < 0 || end < start) return displayName;
        return new ChatComponentText(formatted.substring(0, start) + canonicalSourceName + formatted.substring(end));
    }

    private static boolean containsExactName(String value, String name) {
        return findExactNameIndex(value, name) >= 0;
    }

    private static int findExactNameIndex(String value, String name) {
        if (value == null || name == null || name.isEmpty()) return -1;
        String haystack = value.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return -1;
            int end = index + needle.length();
            boolean leftBoundary = index == 0 || !isMinecraftNameChar(haystack.charAt(index - 1));
            boolean rightBoundary = end == haystack.length() || !isMinecraftNameChar(haystack.charAt(end));
            if (leftBoundary && rightBoundary) return index;
            from = index + 1;
        }
        return -1;
    }

    private static boolean isMinecraftNameChar(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9') || value == '_';
    }

    private static void removeEntry(RemoteDisguiseEntry entry) {
        if (entry == null) {
            return;
        }
        BY_SOURCE_NAME.remove(entry.sourceNameLower);
        if (entry.sourceUuid != null) {
            BY_SOURCE_UUID.remove(entry.sourceUuid);
        }
    }

    private static ReflectionHandles ensureReflectionHandles(NetworkPlayerInfo info) {
        if (info == null) {
            return null;
        }
        Class<?> cls = info.getClass();
        if (reflectionHandles != null && reflectionClass == cls) {
            return reflectionHandles;
        }

        reflectionClass = cls;
        reflectionHandles = new ReflectionHandles();

        reflectionHandles.playerTexturesLoaded = findField(cls, "playerTexturesLoaded", "field_178868_i");
        reflectionHandles.locationSkin = findField(cls, "locationSkin", "field_178865_e");
        reflectionHandles.locationCape = findField(cls, "locationCape", "field_178862_f");
        reflectionHandles.skinType = findField(cls, "skinType", "field_178863_g");

        if (reflectionHandles.locationSkin == null || reflectionHandles.locationCape == null) {
            for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType() == ResourceLocation.class) {
                        f.setAccessible(true);
                        if (reflectionHandles.locationSkin == null) {
                            reflectionHandles.locationSkin = f;
                        } else if (reflectionHandles.locationCape == null) {
                            reflectionHandles.locationCape = f;
                        }
                    }
                }
            }
        }

        if (reflectionHandles.skinType == null) {
            for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType() == String.class && f.getName().toLowerCase(Locale.ROOT).contains("skin")) {
                        f.setAccessible(true);
                        reflectionHandles.skinType = f;
                        break;
                    }
                }
                if (reflectionHandles.skinType != null) {
                    break;
                }
            }
        }

        if (reflectionHandles.locationSkin == null || reflectionHandles.skinType == null) {
            return null;
        }
        return reflectionHandles;
    }

    private static Field findField(Class<?> cls, String... names) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static final class RemoteDisguiseEntry {
        String peerSnapshotKey;
        private final String sourceName;
        private final String sourceNameLower;
        private UUID sourceUuid;
        private final LocalDisguise.RemoteDisguiseProfile targetProfile;
        private NetworkPlayerInfo lastInfo;
        private OriginalSnapshot original;
        private boolean manualCosmetics;
        private boolean fullLunar;
        private DisguiserLunarIcon lunarIcon = DisguiserLunarIcon.KEEP;
        private long expiresAt;
        private int matchKillOffset;
        private String lastCountedKillMessage;
        private long lastCountedKillMessageMs;

        private boolean isExpired() { return expiresAt > 0L && System.currentTimeMillis() >= expiresAt; }

        private RemoteDisguiseEntry(String sourceName, LocalDisguise.RemoteDisguiseProfile targetProfile) {
            this.sourceName = sourceName;
            this.sourceNameLower = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
            this.targetProfile = targetProfile;
        }
    }

    private static final class OriginalSnapshot {
        private boolean texturesLoaded = false;
        private ResourceLocation skin = null;
        private ResourceLocation cape = null;
        private String skinType = null;
        private IChatComponent displayName = null;
        private String teamFormattedName = null;
    }

    private static final class ReflectionHandles {
        private Field playerTexturesLoaded;
        private Field locationSkin;
        private Field locationCape;
        private Field skinType;
    }
}
