/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.network.NetworkPlayerInfo
 *  net.minecraft.network.play.server.S01PacketJoinGame
 *  net.minecraft.network.play.server.S38PacketPlayerListItem
 *  net.minecraft.network.play.server.S3EPacketTeams
 *  net.minecraft.scoreboard.ScorePlayerTeam
 *  net.minecraft.scoreboard.Team
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.event.PacketEvent$Receive
 *  net.weavemc.loader.api.event.SubscribeEvent
 *  net.weavemc.loader.api.event.TickEvent
 */
package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.napim.gomod.Main;
import me.napim.gomod.command.KillEffectCommand;
import me.napim.gomod.util.CompactUuid;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import me.napim.gomod.util.MinecraftFormatting;
import me.napim.gomod.features.DeathmatchTabCleaner;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.TeamInviteResponseHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

public class TabDuplicateNickResolver {
    private static final long PERIODIC_SCAN_MS = 1250L;
    private static final long PACKET_TRIGGERED_SCAN_DEBOUNCE_MS = 150L;
    private static final long PACKET_TRIGGERED_SCAN_DELAY_MS = 125L;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("(?i)\\b[A-Za-z0-9_]{3,16}\\b");
    private static final int MAX_VISIBLE_NAME_CACHE = 1024;
    private static final int MAX_CACHED_LABEL_LENGTH = 256;
    private static final ConcurrentHashMap<String, String> VISIBLE_NAMES = new ConcurrentHashMap<>();
    private static final Set<String> IGNORED_FALSE_POSITIVE_UUIDS = new HashSet<String>();
    private static final Set<String> IGNORED_FALSE_POSITIVE_NAMES = new HashSet<String>();
    private static final ConcurrentHashMap<String, String> UUID_TO_REAL_NAME;
    private static final ConcurrentHashMap<String, String> UUID_TO_DUPLICATE_NICK;
    private static final ConcurrentHashMap<String, String> SESSION_DUPLICATE_NICK_TO_REAL_NAME;
    private static final Set<String> REVOKED_FALSE_POSITIVE_PAIRS;
    private static final Set<String> LOOKUPS_IN_FLIGHT;
    private static final Set<String> ANNOUNCED_MAPPINGS;
    private static final ExecutorService LOOKUP_EXECUTOR;
    /** After Mojang 429 / errors, stop hammering the same profile UUID every 500ms tab scan. */
    private static final ConcurrentHashMap<String, Long> MOJANG_RESOLVE_BACKOFF_UNTIL_MS = new ConcurrentHashMap<>();
    /** Max rows in {@code tab-nick-mappings-history.json} (oldest dropped). */
    private static final int MAX_NICK_MAPPING_HISTORY_ROWS = 1500;
    private static final ConcurrentLinkedQueue<JsonObject> PENDING_NICK_MAPPING_HISTORY_ROWS = new ConcurrentLinkedQueue<>();
    private static final DateTimeFormatter HISTORY_ENDED_AT_ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HISTORY_ENDED_AT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private volatile long lastScanMs = 0L;
    private int lastWorldIdentity = 0;
    private volatile long lastPacketTriggeredScanMs = 0L;
    private final AtomicReference<PendingPacketScan> pendingPacketScan = new AtomicReference<>();

    private static final class PendingPacketScan {
        final NetHandlerPlayClient connection;
        final long dueAtMs;

        PendingPacketScan(NetHandlerPlayClient connection, long dueAtMs) {
            this.connection = connection;
            this.dueAtMs = dueAtMs;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ConfigManager.isDenickerEnabled() || !ClientTickHub.isFeatureEnabled("TabDuplicateNickResolver")) {
            // Packet-triggered detection also feeds other TAB features when Denicker is disabled.
            if (pendingPacketScan.get() != null) {
                handlePendingPacketScan(Minecraft.getMinecraft(), System.currentTimeMillis());
            }
            return;
        }
        long _profT = FeatureProfiler.begin("TabDuplicateNickResolver");
        try {
        long now;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            this.resetWorldState();
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            UUID_TO_DUPLICATE_NICK.clear();
            return;
        }
        DenickCrowdSync.maybePeriodicPull(mc);
        int currentWorldIdentity = System.identityHashCode(mc.theWorld);
        if (currentWorldIdentity != this.lastWorldIdentity) {
            this.lastWorldIdentity = currentWorldIdentity;
            this.resetTransientState();
            if (GameContext.isOnHypixel(mc)) {
                DenickCrowdSync.onHypixelWorldEntered(mc);
            }
        }
        now = System.currentTimeMillis();
        if (handlePendingPacketScan(mc, now) || now - this.lastScanMs < PERIODIC_SCAN_MS) {
            return;
        }
        this.lastScanMs = now;
        TabDuplicateNickResolver.scanCurrentTabForDuplicateNames();
        } finally { FeatureProfiler.end("TabDuplicateNickResolver", _profT); }
    }

    private boolean handlePendingPacketScan(Minecraft mc, long now) {
        PendingPacketScan pending = pendingPacketScan.get();
        if (pending == null) return false;
        if (mc == null || mc.theWorld == null || mc.getNetHandler() != pending.connection) {
            pendingPacketScan.compareAndSet(pending, null);
            return false;
        }
        if (now < pending.dueAtMs) return true;
        if (!pendingPacketScan.compareAndSet(pending, null)) return false;
        lastScanMs = now;
        scanCurrentTabForDuplicateNames();
        return true;
    }

    @SubscribeEvent
    public void onPlayerListPacket(PacketEvent.Receive event) {
        if (event.getPacket() instanceof S01PacketJoinGame) {
            this.resetWorldState();
            return;
        }
        if (!(event.getPacket() instanceof S38PacketPlayerListItem) && !(event.getPacket() instanceof S3EPacketTeams)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingPacketScan.get() != null || now - this.lastPacketTriggeredScanMs < PACKET_TRIGGERED_SCAN_DEBOUNCE_MS) {
            return;
        }
        Minecraft currentMc = Minecraft.getMinecraft();
        if (currentMc == null || currentMc.theWorld == null || !GameContext.isOnHypixel(currentMc)) {
            UUID_TO_DUPLICATE_NICK.clear();
            return;
        }
        NetHandlerPlayClient connection = currentMc.getNetHandler();
        if (connection == null) return;
        PendingPacketScan pending = new PendingPacketScan(connection, now + PACKET_TRIGGERED_SCAN_DELAY_MS);
        // Packet hooks may run off-thread. Wait for the client to apply the roster update and consume
        // one scan on its regular tick. Only retain the connection: a lobby swap can replace the world
        // before this runs, and the scan should inspect that connection's latest roster.
        if (pendingPacketScan.compareAndSet(null, pending)) {
            this.lastPacketTriggeredScanMs = now;
        }
    }

    /** Mojang username from session cache for this profile UUID, if already resolved (no network). */
    public static String getCachedMojangUsernameForUuid(UUID profileUuid) {
        if (profileUuid == null) {
            return null;
        }
        return UUID_TO_REAL_NAME.get(TabDuplicateNickResolver.normalizeUuid(profileUuid));
    }

    /**
     * Real Mojang name for a Hypixel nick session UUID when duplicate-nick scan has resolved it, or {@code null}.
     */
    public static String getResolvedRealNameForSessionUuid(UUID profileUuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (profileUuid == null || mc == null || !GameContext.isOnHypixel(mc)) {
            return null;
        }
        String uuid = TabDuplicateNickResolver.normalizeUuid(profileUuid);
        String duplicateNick = UUID_TO_DUPLICATE_NICK.get(uuid);
        String actualName = UUID_TO_REAL_NAME.get(uuid);
        if (duplicateNick == null || duplicateNick.trim().isEmpty()
                || actualName == null || actualName.trim().isEmpty()) {
            return null;
        }
        if (isFalsePositiveNickMappingRevoked(duplicateNick, actualName)) return null;
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick)
                || TabDuplicateNickResolver.isIgnoredFalsePositiveName(actualName)) {
            return null;
        }
        if (actualName.equalsIgnoreCase(duplicateNick.trim())) {
            return null;
        }
        return actualName.trim();
    }

    /**
     * Keep the server-visible nick for spectator rendering when this exact Hypixel profile UUID has been
     * resolved to a Mojang account. The resolved account name is only used to validate the current mapping;
     * it is never returned as a display name.
     */
    public static String getVisibleNameForSessionUuid(UUID profileUuid, String profileName) {
        if (profileName == null) {
            return null;
        }
        String visibleName = profileName.trim();
        Minecraft mc = Minecraft.getMinecraft();
        if (visibleName.isEmpty() || profileUuid == null || mc == null || !GameContext.isOnHypixel(mc)
                || GameContext.isHypixelReplayWatching(mc)) {
            return visibleName;
        }
        String uuid = normalizeUuid(profileUuid);
        String duplicateNick = UUID_TO_DUPLICATE_NICK.get(uuid);
        String actualName = UUID_TO_REAL_NAME.get(uuid);
        if (duplicateNick == null || duplicateNick.trim().isEmpty()
                || actualName == null || actualName.trim().isEmpty()
                || isFalsePositiveNickMappingRevoked(duplicateNick, actualName)
                || isIgnoredFalsePositiveName(duplicateNick) || isIgnoredFalsePositiveName(actualName)
                || actualName.equalsIgnoreCase(duplicateNick.trim())
                || !actualName.equalsIgnoreCase(visibleName)) {
            return visibleName;
        }
        return duplicateNick.trim();
    }

    /**
     * Real Mojang name for denicker skin reveal: session UUID map first, then nick→real session/persisted maps
     * (same sources as {@link #getKnownRealName} used by nametag overlay).
     */
    public static String resolveRealNameForDenicker(UUID sessionUuid, String profileName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc)) {
            return null;
        }
        String fromUuid = getResolvedRealNameForSessionUuid(sessionUuid);
        if (fromUuid != null && !fromUuid.isEmpty()) {
            return fromUuid;
        }
        if (profileName == null || profileName.trim().isEmpty()) {
            return null;
        }
        String nick = profileName.trim();
        String known = getKnownRealName(nick);
        if (known == null || known.trim().isEmpty() || known.equalsIgnoreCase(nick)) {
            return null;
        }
        return known.trim();
    }

    /**
     * True when some <i>other</i> TAB row's cached Mojang name equals {@code nick} — e.g. real {@code FoxyLuigi}
     * is on the list while another player is nicked as {@code FoxyLuigi}. Then we must not hide rows just because
     * they share that visible nick.
     */
    public static boolean tabHasOtherPlayerWithResolvedName(Collection<NetworkPlayerInfo> tabEntries, String nick,
            UUID selfUuid) {
        if (tabEntries == null || nick == null || nick.trim().isEmpty() || selfUuid == null) {
            return false;
        }
        String want = nick.trim();
        for (NetworkPlayerInfo o : tabEntries) {
            if (o == null || o.getGameProfile() == null) {
                continue;
            }
            UUID id = o.getGameProfile().getId();
            if (id == null || selfUuid.equals(id)) {
                continue;
            }
            String resolved = TabDuplicateNickResolver.getCachedMojangUsernameForUuid(id);
            if (resolved != null && resolved.equalsIgnoreCase(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hide a TAB row when duplicate-nick scan tagged this UUID: same visible nick as another player, resolved
     * real name differs from that nick, and the duplicate nick still appears as some row's profile name (the
     * real player's Mojang name on TAB). Previously we called {@link #getKnownRealName} with the row's
     * profile name, which fails for Hypixel nicks (profile ≠ duplicate username).
     *
     * @param tabEntries full player info map for collision checks (nick vs real same IGN).
     */
    public static boolean shouldHideDuplicateTabListEntry(NetworkPlayerInfo info, Set<String> liveProfileNamesLower,
            Collection<NetworkPlayerInfo> tabEntries) {
        Minecraft mc = Minecraft.getMinecraft();
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null
                || liveProfileNamesLower == null || mc == null || !GameContext.isOnHypixel(mc)
                || isDisguiseDisplayProfile(info)) {
            return false;
        }
        UUID selfId = info.getGameProfile().getId();
        String uuid = TabDuplicateNickResolver.normalizeUuid(selfId);
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(uuid)) {
            return false;
        }
        String duplicateNick = UUID_TO_DUPLICATE_NICK.get(uuid);
        String actualName = UUID_TO_REAL_NAME.get(uuid);
        if (duplicateNick == null || duplicateNick.trim().isEmpty()
                || actualName == null || actualName.trim().isEmpty()) {
            return false;
        }
        if (isFalsePositiveNickMappingRevoked(duplicateNick, actualName)) return false;
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick)
                || TabDuplicateNickResolver.isIgnoredFalsePositiveName(actualName)) {
            return false;
        }
        if (actualName.equalsIgnoreCase(duplicateNick.trim())) {
            return false;
        }
        if (TabDuplicateNickResolver.tabHasOtherPlayerWithResolvedName(tabEntries, duplicateNick.trim(), selfId)) {
            return false;
        }
        String profileName = info.getGameProfile().getName();
        if (profileName != null && profileName.equalsIgnoreCase(actualName.trim())) {
            return false;
        }
        return liveProfileNamesLower.contains(duplicateNick.trim().toLowerCase(Locale.ROOT));
    }

    /** Debug line: duplicate TAB nick vs Mojang-resolved name for this list entry (or null). */
    public static String getDuplicateTabHideExplain(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null) {
            return null;
        }
        String uuid = TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId());
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(uuid)) {
            return null;
        }
        String duplicateNick = UUID_TO_DUPLICATE_NICK.get(uuid);
        String actualName = UUID_TO_REAL_NAME.get(uuid);
        if (duplicateNick == null || duplicateNick.trim().isEmpty()
                || actualName == null || actualName.trim().isEmpty()
                || isFalsePositiveNickMappingRevoked(duplicateNick, actualName)
                || actualName.equalsIgnoreCase(duplicateNick.trim())) {
            return null;
        }
        return "shared TAB nick " + duplicateNick + ", profile Mojang name " + actualName;
    }

    public static String getResolvedTabDisplayName(NetworkPlayerInfo info, String originalName, String baseUsername) {
        int idx;
        Minecraft mc = Minecraft.getMinecraft();
        if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null
                || mc == null || !GameContext.isOnHypixel(mc) || isDisguiseDisplayProfile(info)) {
            return null;
        }
        String uuid = TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId());
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(uuid)) {
            return null;
        }
        String actualName = UUID_TO_REAL_NAME.get(uuid);
        String duplicateNick = UUID_TO_DUPLICATE_NICK.get(uuid);
        if (isFalsePositiveNickMappingRevoked(duplicateNick, actualName)) return null;
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(actualName) || TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick) || TabDuplicateNickResolver.isIgnoredFalsePositiveName(baseUsername)) {
            return null;
        }
        if (actualName == null || actualName.isEmpty() || duplicateNick == null || duplicateNick.isEmpty()) {
            return null;
        }
        String sourceName = TabDuplicateNickResolver.findDisplayedSourceName(originalName, duplicateNick, baseUsername);
        if (sourceName == null || sourceName.isEmpty()) {
            sourceName = duplicateNick;
        }
        if (sourceName == null || sourceName.isEmpty() || actualName.equalsIgnoreCase(sourceName)) {
            return null;
        }
        if (originalName != null && sourceName != null && (idx = originalName.indexOf(sourceName)) >= 0) {
            return originalName.substring(0, idx) + actualName + originalName.substring(idx + sourceName.length());
        }
        return actualName;
    }

    public static String resolveCurrentUsernameBlocking(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return TabDuplicateNickResolver.resolveCurrentUsernameBlocking(TabDuplicateNickResolver.normalizeUuid(uuid));
    }

    /**
     * A disguise name is a render-only alias, not evidence that the underlying Hypixel profile is nicked.
     * Keep those rows out of duplicate-name detection and duplicate-row cleanup while the disguise is active.
     */
    public static boolean isDisguiseDisplayProfile(NetworkPlayerInfo info) {
        if (info == null || info.getGameProfile() == null) {
            return false;
        }
        com.mojang.authlib.GameProfile profile = info.getGameProfile();
        if (LocalDisguise.isEnabled() && LocalDisguise.matchesLocalProfileFast(profile)) {
            return true;
        }
        if (RemoteDisguiseManager.hasTrackedManualDisguise(profile)) {
            return true;
        }
        return GomodPeerDisguise.getPeerName(profile) != null;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static String resolveCurrentUsernameBlocking(String undashedUuid) {
        if (undashedUuid == null) return null;
        if (undashedUuid.isEmpty()) {
            return null;
        }
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(undashedUuid)) {
            return null;
        }
        long nowWall = System.currentTimeMillis();
        Long backoffUntil = MOJANG_RESOLVE_BACKOFF_UNTIL_MS.get(undashedUuid);
        if (backoffUntil != null && backoffUntil > nowWall) {
            return null;
        }
        String cached = UUID_TO_REAL_NAME.get(undashedUuid);
        if (cached != null && !cached.isEmpty()) {
            if (!TabDuplicateNickResolver.isIgnoredFalsePositiveName(cached)) return cached;
            UUID_TO_REAL_NAME.remove(undashedUuid);
            return null;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection)new URL(me.napim.gomod.util.MojangProfileApi.profileJsonUrl(undashedUuid)).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "gomod123-tabuuid");
            int responseCode = connection.getResponseCode();
            if (responseCode == 204) return null;
            if (responseCode == 404) {
                return null;
            }
            if (responseCode == 429) {
                MOJANG_RESOLVE_BACKOFF_UNTIL_MS.put(undashedUuid, System.currentTimeMillis() + 120_000L);
                return null;
            }
            if (responseCode != 200) {
                MOJANG_RESOLVE_BACKOFF_UNTIL_MS.put(undashedUuid, System.currentTimeMillis() + 45_000L);
                return null;
            }
            InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
            try {
                JsonObject response = new JsonParser().parse((Reader)reader).getAsJsonObject();
                if (!response.has("name")) return null;
                String name = response.get("name").getAsString();
                if (name == null) return null;
                if (name.isEmpty()) return null;
                if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(name)) {
                    String string = null;
                    return string;
                }
                UUID_TO_REAL_NAME.put(undashedUuid, name);
                String string = name;
                return string;
            }
            finally {
                reader.close();
                connection.disconnect();
            }
        }
        catch (Exception exception) {
            MOJANG_RESOLVE_BACKOFF_UNTIL_MS.put(undashedUuid, System.currentTimeMillis() + 30_000L);
        }
        return null;
    }

    public static String getKnownDuplicateNick(String actualName) {
        if (actualName == null || actualName.trim().isEmpty()) {
            return null;
        }
        String normalizedActualName = actualName.trim().toLowerCase(Locale.ROOT);
        String fromRealToNick = TeamInviteResponseHandler.REAL_TO_NICK.get(normalizedActualName);
        if (fromRealToNick != null && !fromRealToNick.isEmpty()
                && !TabDuplicateNickResolver.isIgnoredFalsePositiveName(fromRealToNick)
                && !isFalsePositiveNickMappingRevoked(fromRealToNick, actualName)) {
            return fromRealToNick;
        }
        for (Map.Entry<String, String> entry : UUID_TO_REAL_NAME.entrySet()) {
            String duplicateNick;
            String resolvedName = entry.getValue();
            if (resolvedName == null || !normalizedActualName.equals(resolvedName.toLowerCase(Locale.ROOT)) || (duplicateNick = UUID_TO_DUPLICATE_NICK.get(entry.getKey())) == null || duplicateNick.isEmpty() || TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick) || isFalsePositiveNickMappingRevoked(duplicateNick, resolvedName)) continue;
            return duplicateNick;
        }
        for (Map.Entry<String, String> entry : SESSION_DUPLICATE_NICK_TO_REAL_NAME.entrySet()) {
            String real = entry.getValue();
            String nickKey = entry.getKey();
            if (real == null || nickKey == null || nickKey.isEmpty()) {
                continue;
            }
            if (!normalizedActualName.equals(real.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(nickKey)) {
                continue;
            }
            if (isFalsePositiveNickMappingRevoked(nickKey, real)) continue;
            return nickKey;
        }
        return null;
    }

    /**
     * Manual {@code /nametagnick &lt;nick&gt; &lt;IGN&gt;} guess: same persistence and TAB/tabstats behavior as
     * {@link #registerNickMappingFromTeamsFinder}. Duplicate TAB + Mojang resolution overwrites the session entry
     * with the actual profile name when it differs ({@link #getKnownRealName} checks UUID mappings first).
     */
    public static void registerManualNickToRealGuess(String visibleNick, String realIgnGuess) {
        registerNickMappingFromTeamsFinder(realIgnGuess, visibleNick);
    }

    public static boolean removeManualNickToRealGuess(String visibleNick) {
        if (visibleNick == null || visibleNick.trim().isEmpty()) {
            return false;
        }
        String nickLower = visibleNick.trim().toLowerCase(Locale.ROOT);
        String removedReal = SESSION_DUPLICATE_NICK_TO_REAL_NAME.remove(nickLower);
        if (removedReal == null || removedReal.trim().isEmpty()) {
            return false;
        }
        removeRealToNickMappingIfMatches(removedReal, visibleNick);
        TabDuplicateNickResolver.schedulePersistNickMappings();
        return true;
    }

    /**
     * Teams Nick Finder: Hypixel confirms {@code realName} is nicked as {@code nickName}. Registers the same
     * nick→real mapping as tab duplicate resolution so TAB can show {@code nick (real)} and duplicate rows hide.
     */
    public static void registerNickMappingFromTeamsFinder(String realName, String nickName) {
        if (realName == null || nickName == null) {
            return;
        }
        String r = realName.trim();
        String n = nickName.trim();
        if (r.isEmpty() || n.isEmpty() || r.equalsIgnoreCase(n)) {
            return;
        }
        if (isFalsePositiveNickMappingRevoked(n, r)) return;
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(n) || TabDuplicateNickResolver.isIgnoredFalsePositiveName(r)) {
            return;
        }
        supersedeStaleSessionNicksForSameReal(r, n);
        SESSION_DUPLICATE_NICK_TO_REAL_NAME.put(n.toLowerCase(Locale.ROOT), r);
        TeamInviteResponseHandler.REAL_TO_NICK.put(r.toLowerCase(Locale.ROOT), n);
        TabDuplicateNickResolver.schedulePersistNickMappings();
        KillCounter.syncKnownAliases(r, n);
        if (ConfigManager.isDenickerEnabled() && ConfigManager.isDenickerRevealRealSkinEnabled()) {
            DenickerRealSkinCache.ensurePrefetchRealName(r);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.addScheduledTask(DeathmatchTabCleaner::reconcileNicknameDuplicateHiding);
        }
    }

    private static void removeRealToNickMappingIfMatches(String realName, String nickName) {
        if (realName == null || nickName == null) {
            return;
        }
        String realLower = realName.trim().toLowerCase(Locale.ROOT);
        String nickTrimmed = nickName.trim();
        String mappedNick = TeamInviteResponseHandler.REAL_TO_NICK.get(realLower);
        if (mappedNick != null && mappedNick.equalsIgnoreCase(nickTrimmed)) {
            TeamInviteResponseHandler.REAL_TO_NICK.remove(realLower);
        }
        String coloredNick = TeamInviteResponseHandler.REAL_TO_NICK_COLORED.get(realLower);
        if (coloredNick != null) {
            String coloredPlain = EnumChatFormatting.getTextWithoutFormattingCodes(coloredNick);
            if (coloredPlain != null && coloredPlain.equalsIgnoreCase(nickTrimmed)) {
                TeamInviteResponseHandler.REAL_TO_NICK_COLORED.remove(realLower);
            }
        }
    }

    /**
     * When {@code actualName} gets a new disguise nick, remove any other cached nick keys pointing at the same
     * real so Hypixel can reuse an old disguise name for someone else without stale denick.
     */
    private static void supersedeStaleSessionNicksForSameReal(String actualName, String newDuplicateNick) {
        if (actualName == null || actualName.trim().isEmpty() || newDuplicateNick == null || newDuplicateNick.trim().isEmpty()) {
            return;
        }
        String realTrimmed = actualName.trim();
        String newLower = newDuplicateNick.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : new ArrayList<>(SESSION_DUPLICATE_NICK_TO_REAL_NAME.entrySet())) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (!realTrimmed.equalsIgnoreCase(e.getValue().trim())) {
                continue;
            }
            if (e.getKey().equalsIgnoreCase(newLower)) {
                continue;
            }
            String oldNickKey = e.getKey();
            SESSION_DUPLICATE_NICK_TO_REAL_NAME.remove(oldNickKey, e.getValue());
            removeRealToNickMappingIfMatches(realTrimmed, oldNickKey);
            recordNickMappingSuperseded(oldNickKey, realTrimmed);
        }
    }

    private static void recordNickMappingSuperseded(String nickKeyLower, String realIgn) {
        if (nickKeyLower == null || nickKeyLower.isEmpty() || realIgn == null || realIgn.trim().isEmpty()) {
            return;
        }
        long endedAtMs = System.currentTimeMillis();
        JsonObject row = new JsonObject();
        row.addProperty("nick", nickKeyLower);
        row.addProperty("real", realIgn.trim());
        row.addProperty("endedAtMs", endedAtMs);
        row.addProperty("endedAtIso", HISTORY_ENDED_AT_ISO.format(Instant.ofEpochMilli(endedAtMs)));
        row.addProperty("endedAtLocal", HISTORY_ENDED_AT_LOCAL.format(Instant.ofEpochMilli(endedAtMs)));
        PENDING_NICK_MAPPING_HISTORY_ROWS.offer(row);
    }

    public static String getKnownRealName(String duplicateNick) {
        if (duplicateNick == null || duplicateNick.trim().isEmpty()) {
            return null;
        }
        String normalizedDuplicateNick = duplicateNick.trim().toLowerCase(Locale.ROOT);
        if (!UUID_TO_DUPLICATE_NICK.isEmpty() && !UUID_TO_REAL_NAME.isEmpty()) {
            for (Map.Entry<String, String> entry : UUID_TO_DUPLICATE_NICK.entrySet()) {
                String currentNick = entry.getValue();
                if (!normalizedDuplicateNick.equals(currentNick.toLowerCase(Locale.ROOT))) continue;
                String actualName = UUID_TO_REAL_NAME.get(entry.getKey());
                if (actualName == null || actualName.isEmpty() || isIgnoredFalsePositiveName(actualName)
                        || isFalsePositiveNickMappingRevoked(currentNick, actualName)) continue;
                return actualName;
            }
        }
        String cached = SESSION_DUPLICATE_NICK_TO_REAL_NAME.get(normalizedDuplicateNick);
        if (cached == null || cached.isEmpty() || TabDuplicateNickResolver.isIgnoredFalsePositiveName(cached)) {
            cached = null;
        }
        if (cached != null && isFalsePositiveNickMappingRevoked(normalizedDuplicateNick, cached)) cached = null;
        if (cached != null) {
            return cached;
        }
        String crowd = DenickCrowdSync.getCrowdResolvedReal(normalizedDuplicateNick);
        if (crowd == null || crowd.isEmpty() || TabDuplicateNickResolver.isIgnoredFalsePositiveName(crowd)) {
            return null;
        }
        if (isFalsePositiveNickMappingRevoked(normalizedDuplicateNick, crowd)) return null;
        return crowd;
    }

    /**
     * Apply an authoritative crowd-map revocation to every client-side denick source, including saved mappings.
     * The exact pair is kept locally so an already-cached false report cannot return after a restart.
     */
    public static void revokeFalsePositiveNickMapping(String duplicateNick, String actualName) {
        if (!isMinecraftUsername(duplicateNick) || !isMinecraftUsername(actualName)
                || duplicateNick.equalsIgnoreCase(actualName)) return;
        String nick = duplicateNick.trim();
        String real = actualName.trim();
        String nickLower = nick.toLowerCase(Locale.ROOT);
        String realLower = real.toLowerCase(Locale.ROOT);
        REVOKED_FALSE_POSITIVE_PAIRS.add(nickLower + "|" + realLower);

        String mappedReal = SESSION_DUPLICATE_NICK_TO_REAL_NAME.get(nickLower);
        if (mappedReal != null && mappedReal.equalsIgnoreCase(real)) {
            SESSION_DUPLICATE_NICK_TO_REAL_NAME.remove(nickLower, mappedReal);
        }
        removeRealToNickMappingIfMatches(real, nick);
        for (Map.Entry<String, String> entry : UUID_TO_DUPLICATE_NICK.entrySet()) {
            String uuid = entry.getKey();
            String mappedNick = entry.getValue();
            String mappedName = UUID_TO_REAL_NAME.get(uuid);
            if (mappedNick != null && mappedNick.equalsIgnoreCase(nick)
                    && mappedName != null && mappedName.equalsIgnoreCase(real)) {
                UUID_TO_DUPLICATE_NICK.remove(uuid, mappedNick);
            }
        }
        ANNOUNCED_MAPPINGS.remove(realLower + "|" + nickLower);
        DenickCrowdSync.forgetRevokedMapping(nick, real);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.isCallingFromMinecraftThread()) KillCounter.markTabCompositionDirty();
        else mc.addScheduledTask(KillCounter::markTabCompositionDirty);
        persistRevokedFalsePositivePairs();
        schedulePersistNickMappings();
    }

    public static boolean isFalsePositiveNickMappingRevoked(String duplicateNick, String actualName) {
        if (duplicateNick == null || actualName == null) return false;
        return REVOKED_FALSE_POSITIVE_PAIRS.contains(duplicateNick.trim().toLowerCase(Locale.ROOT)
                + "|" + actualName.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isMinecraftUsername(String name) {
        return name != null && USERNAME_PATTERN.matcher(name.trim()).matches();
    }

    private static void loadPersistedRevokedFalsePositivePairs() {
        // Local corrections survive an older running client's canonical-cache writer during a repair.
        for(String fileName:new String[]{"tab-nick-mapping-revocations.json","tab-nick-mapping-revocations-local.json"}) {
        try {
            Path path = GomodPaths.getGomod123Dir().toPath().resolve(fileName);
            if (!Files.exists(path)) continue;
            byte[] raw = Files.readAllBytes(path);
            if (raw.length == 0) continue;
            JsonElement root = new JsonParser().parse(new String(raw, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) continue;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject pair = element.getAsJsonObject();
                if (!pair.has("nick") || !pair.has("real")) continue;
                String nick = pair.get("nick").getAsString();
                String real = pair.get("real").getAsString();
                if (isMinecraftUsername(nick) && isMinecraftUsername(real) && !nick.equalsIgnoreCase(real)) {
                    REVOKED_FALSE_POSITIVE_PAIRS.add(nick.trim().toLowerCase(Locale.ROOT)
                            + "|" + real.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable ignored) {
        }
        }
    }

    private static void persistRevokedFalsePositivePairs() {
        Thread thread = new Thread(() -> {
            try {
                java.io.File directory = GomodPaths.getGomod123Dir();
                directory.mkdirs();
                JsonArray pairs = new JsonArray();
                for (String key : REVOKED_FALSE_POSITIVE_PAIRS) {
                    String[] names = key.split("\\|", 2);
                    if (names.length != 2) continue;
                    JsonObject pair = new JsonObject();
                    pair.addProperty("nick", names[0]);
                    pair.addProperty("real", names[1]);
                    pairs.add(pair);
                }
                Path path = directory.toPath().resolve("tab-nick-mapping-revocations.json");
                Files.write(path, pairs.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        }, "gomod-tab-nick-revocations-save");
        thread.setDaemon(true);
        thread.start();
    }

    /** Refresh TAB duplicate scan after crowd denick JSON was pulled (nametags / TAB can pick up new mappings). */
    public static void rescanTabAfterCrowdPull() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) {
            return;
        }
        if (mc.isCallingFromMinecraftThread()) {
            scanCurrentTabForDuplicateNames();
            return;
        }
        mc.addScheduledTask(TabDuplicateNickResolver::scanCurrentTabForDuplicateNames);
    }

    public static int forceRescanAndAnnounce() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return 0;
        }
        if (mc.isCallingFromMinecraftThread()) {
            TabDuplicateNickResolver.clearDetectionState();
            return TabDuplicateNickResolver.scanCurrentTabForDuplicateNames();
        }
        int[] result = new int[1];
        mc.addScheduledTask(() -> {
            TabDuplicateNickResolver.clearDetectionState();
            result[0] = TabDuplicateNickResolver.scanCurrentTabForDuplicateNames();
        });
        return result[0];
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static int scanCurrentTabForDuplicateNames() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null || !GameContext.isOnHypixel(mc)) {
            UUID_TO_DUPLICATE_NICK.clear();
            return 0;
        }
        if (GameContext.isHypixelReplayWatching(mc)) {
            UUID_TO_DUPLICATE_NICK.clear();
            return 0;
        }
        Collection playerInfoMap = mc.getNetHandler().getPlayerInfoMap();
        if (playerInfoMap == null || playerInfoMap.isEmpty()) {
            ConcurrentHashMap<String, String> concurrentHashMap = UUID_TO_DUPLICATE_NICK;
            synchronized (concurrentHashMap) {
                UUID_TO_DUPLICATE_NICK.clear();
            }
            return 0;
        }
        LinkedHashMap<String, String> newDuplicateNickByUuid = new LinkedHashMap<String, String>();
        HashSet<String> activeAnnouncementKeys = new HashSet<String>();
        int duplicateEntries = 0;
        duplicateEntries += TabDuplicateNickResolver.collectDuplicatesByKey(TabDuplicateNickResolver.groupByRawProfileName(playerInfoMap), newDuplicateNickByUuid, activeAnnouncementKeys);
        duplicateEntries += TabDuplicateNickResolver.collectDuplicatesByKey(TabDuplicateNickResolver.groupByVisibleTabName(playerInfoMap), newDuplicateNickByUuid, activeAnnouncementKeys);
        if (duplicateEntries > 0) {
            DenickCrowdSync.onDuplicateTabNicksDetected(mc);
        }
        ConcurrentHashMap<String, String> concurrentHashMap = UUID_TO_DUPLICATE_NICK;
        synchronized (concurrentHashMap) {
            UUID_TO_DUPLICATE_NICK.clear();
            UUID_TO_DUPLICATE_NICK.putAll(newDuplicateNickByUuid);
        }
        if (mc.theWorld != null && GameContext.isOnHypixel(mc)) {
            mc.addScheduledTask(DeathmatchTabCleaner::reconcileNicknameDuplicateHiding);
        }
        return duplicateEntries;
    }

    private static void requestLookup(String undashedUuid, String duplicateNick) {
        Minecraft currentMc = Minecraft.getMinecraft();
        if (currentMc == null || !GameContext.isOnHypixel(currentMc)
                || undashedUuid == null || undashedUuid.isEmpty() || !LOOKUPS_IN_FLIGHT.add(undashedUuid)) {
            return;
        }
        LOOKUP_EXECUTOR.execute(() -> {
            try {
                Minecraft mc;
                String actualName = TabDuplicateNickResolver.resolveCurrentUsernameBlocking(undashedUuid);
                String latestDuplicateNick = UUID_TO_DUPLICATE_NICK.get(undashedUuid);
                if (latestDuplicateNick == null || latestDuplicateNick.isEmpty()) {
                    latestDuplicateNick = duplicateNick;
                }
                if (latestDuplicateNick != null && actualName != null && (mc = Minecraft.getMinecraft()) != null) {
                    mc.addScheduledTask(TabDuplicateNickResolver::scanCurrentTabForDuplicateNames);
                }
            }
            finally {
                LOOKUPS_IN_FLIGHT.remove(undashedUuid);
            }
        });
    }

    private static void announceIfResolved(String duplicateNick, String actualName) {
        Minecraft currentMc = Minecraft.getMinecraft();
        if (currentMc == null || !GameContext.isOnHypixel(currentMc)
                || duplicateNick == null || duplicateNick.isEmpty() || actualName == null || actualName.isEmpty()) {
            return;
        }
        if (isFalsePositiveNickMappingRevoked(duplicateNick, actualName)) return;
        // Guard: local disguise can intentionally make the local player's visible TAB name match a real player's IGN.
        // In that case, duplicate-nick resolution must NEVER register "targetNick -> localRealIgn" mappings, or we'd
        // leak the local player's real IGN into TAB/nametags (e.g. "BuyBrett (nAku)").
        if (isLocalDisguiseDuplicateCollision(duplicateNick, actualName)
                || isPeerDisguiseDuplicateCollision(duplicateNick, actualName)) {
            return;
        }
        supersedeStaleSessionNicksForSameReal(actualName, duplicateNick);
        TeamInviteResponseHandler.REAL_TO_NICK.put(actualName.toLowerCase(Locale.ROOT), duplicateNick);
        SESSION_DUPLICATE_NICK_TO_REAL_NAME.put(duplicateNick.toLowerCase(Locale.ROOT), actualName);
        DenickCrowdSync.reportAutoDuplicateResolved(duplicateNick, actualName);
        TabDuplicateNickResolver.schedulePersistNickMappings();
        KillCounter.syncKnownAliases(actualName, duplicateNick);
        // The Weave TAB renderer consumes KillCounter's render-only row cache. Recompose it as soon as a denick is
        // learned instead of waiting for the periodic refresh (or for vanilla GuiPlayerTabOverlay to be entered).
        KillCounter.markTabCompositionDirty();
        if (ConfigManager.isDenickerEnabled() && ConfigManager.isDenickerRevealRealSkinEnabled()) {
            DenickerRealSkinCache.ensurePrefetchRealName(actualName);
        }
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick) || TabDuplicateNickResolver.isIgnoredFalsePositiveName(actualName)) {
            return;
        }
        if (actualName.equalsIgnoreCase(duplicateNick)) {
            return;
        }
        String key = actualName.toLowerCase() + "|" + duplicateNick.toLowerCase();
        if (!ANNOUNCED_MAPPINGS.add(key)) {
            return;
        }
        Minecraft mc = currentMc;
        if (mc != null) {
            final String message = Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "- " + actualName + EnumChatFormatting.YELLOW + " is nicked as " + duplicateNick;
            mc.addScheduledTask(() -> {
                if (ConfigManager.isDebugMessagesToChatEnabled()) {
                    Main.sendMessage(message);
                }
                DeathmatchTabCleaner.reconcileNicknameDuplicateHiding();
            });
        }
    }

    /**
     * True when the mapping {@code duplicateNick -> actualName} would associate the local player's real IGN with the
     * active local disguise target. This situation happens when you locally disguise as an existing Mojang account
     * name and that real player is present in TAB; the duplicate scan sees two "same visible nick" rows and resolves
     * the local UUID to your real IGN, which must not be persisted or shown.
     */
    private static boolean isLocalDisguiseDuplicateCollision(String duplicateNick, String actualName) {
        try {
            if (duplicateNick == null || actualName == null) {
                return false;
            }
            if (!LocalDisguise.isEnabled()) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null) {
                return false;
            }
            String target = LocalDisguise.getTargetName();
            if (target == null || target.trim().isEmpty()) {
                return false;
            }
            // Only block the specific leak: "disguise target name" -> "local player's actual IGN".
            if (!duplicateNick.trim().equalsIgnoreCase(target.trim())) {
                return false;
            }
            String selfReal = mc.thePlayer.getGameProfile().getName();
            if (selfReal == null || selfReal.trim().isEmpty()) {
                return false;
            }
            return actualName.trim().equalsIgnoreCase(selfReal.trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** A same-server alias must never be reported as the remote player's Hypixel nick. */
    private static boolean isPeerDisguiseDuplicateCollision(String duplicateNick, String actualName) {
        GomodPeerDisguise.PeerName peer = GomodPeerDisguise.getPeerName(actualName);
        return peer != null && peer.targetName != null
                && peer.targetName.equalsIgnoreCase(duplicateNick.trim());
    }

    private void resetWorldState() {
        this.pendingPacketScan.set(null);
        this.lastScanMs = 0L;
        this.lastPacketTriggeredScanMs = 0L;
        TabDuplicateNickResolver.clearDetectionState();
    }

    private void resetTransientState() {
        this.lastScanMs = 0L;
        this.lastPacketTriggeredScanMs = 0L;
        UUID_TO_DUPLICATE_NICK.clear();
        LOOKUPS_IN_FLIGHT.clear();
        MOJANG_RESOLVE_BACKOFF_UNTIL_MS.clear();
    }

    private static void clearDetectionState() {
        ANNOUNCED_MAPPINGS.clear();
        UUID_TO_DUPLICATE_NICK.clear();
        LOOKUPS_IN_FLIGHT.clear();
        MOJANG_RESOLVE_BACKOFF_UNTIL_MS.clear();
    }

    /** Public for TAB duplicate hiding: extracted IGN from TAB display (not internal profile name). */
    public static String getVisibleTabUsernamePublic(NetworkPlayerInfo info) {
        return TabDuplicateNickResolver.getVisibleTabUsername(info);
    }

    private static String getVisibleTabUsername(NetworkPlayerInfo info) {
        GameProfile profile = info == null ? null : info.getGameProfile();
        if (profile == null) {
            return null;
        }
        String visible = null;
        try {
            IChatComponent display = info.getDisplayName();
            if (display != null) {
                visible = display.getFormattedText();
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        if (visible == null || visible.isEmpty()) {
            try {
                visible = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), profile.getName());
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        String extracted = extractVisibleUsername(visible);
        if (extracted != null) {
            if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(extracted)) {
                return null;
            }
            return extracted;
        }
        String profileName = profile.getName();
        return TabDuplicateNickResolver.isIgnoredFalsePositiveName(profileName) ? null : profileName;
    }

    private static String extractVisibleUsername(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_CACHED_LABEL_LENGTH) return parseVisibleUsername(text);
        String cached = VISIBLE_NAMES.get(text);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String parsed = parseVisibleUsername(text);
        synchronized (VISIBLE_NAMES) {
            if (VISIBLE_NAMES.size() >= MAX_VISIBLE_NAME_CACHE) VISIBLE_NAMES.clear();
            VISIBLE_NAMES.put(text, parsed == null ? "" : parsed);
        }
        return parsed;
    }

    private static String parseVisibleUsername(String text) {
        String stripped = MinecraftFormatting.stripSectionCodes(text).trim();
        if (stripped.isEmpty()) {
            return null;
        }
        String candidate = stripped;
        int closeBracket;
        while (candidate.startsWith("[") && (closeBracket = candidate.indexOf(93)) > 0) {
            candidate = candidate.substring(closeBracket + 1).trim();
        }
        String exact = findFirstUsernameToken(candidate);
        return exact != null || candidate == stripped ? exact : findFirstUsernameToken(stripped);
    }

    /** World-entity/Lunar compatibility: extracts the visible IGN from a formatted nametag string. */
    public static String extractVisibleUsernamePublic(String text) {
        String extracted = extractVisibleUsername(text);
        return isIgnoredFalsePositiveName(extracted) ? null : extracted;
    }

    private static String findFirstUsernameToken(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = USERNAME_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static Map<String, List<NetworkPlayerInfo>> groupByRawProfileName(Collection<NetworkPlayerInfo> playerInfoMap) {
        HashMap<String, List<NetworkPlayerInfo>> playersByName = new HashMap<String, List<NetworkPlayerInfo>>();
        for (NetworkPlayerInfo info : playerInfoMap) {
            String rawName;
            if (info == null || info.getGameProfile() == null || isDisguiseDisplayProfile(info)
                    || info.getGameProfile().getName() == null
                    || info.getGameProfile().getId() != null && TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId()))
                    || (rawName = info.getGameProfile().getName()).isEmpty()
                    || TabDuplicateNickResolver.isIgnoredFalsePositiveName(rawName)) continue;
            playersByName.computeIfAbsent(rawName.toLowerCase(), k -> new ArrayList()).add(info);
        }
        return playersByName;
    }

    private static Map<String, List<NetworkPlayerInfo>> groupByVisibleTabName(Collection<NetworkPlayerInfo> playerInfoMap) {
        HashMap<String, List<NetworkPlayerInfo>> playersByName = new HashMap<String, List<NetworkPlayerInfo>>();
        for (NetworkPlayerInfo info : playerInfoMap) {
            String visibleName;
            if (info == null || info.getGameProfile() == null
                    || isDisguiseDisplayProfile(info)
                    || info.getGameProfile().getId() != null && TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId()))
                    || (visibleName = TabDuplicateNickResolver.getVisibleTabUsername(info)) == null || visibleName.isEmpty()
                    || TabDuplicateNickResolver.isIgnoredFalsePositiveName(visibleName)) continue;
            playersByName.computeIfAbsent(visibleName.toLowerCase(), k -> new ArrayList()).add(info);
        }
        return playersByName;
    }

    private static int collectDuplicatesByKey(Map<String, List<NetworkPlayerInfo>> groupedInfos, Map<String, String> duplicateNickByUuid, Set<String> activeAnnouncementKeys) {
        int duplicateEntries = 0;
        for (List<NetworkPlayerInfo> infos : groupedInfos.values()) {
            String duplicateNick;
            if (infos == null || infos.size() < 2 || (duplicateNick = TabDuplicateNickResolver.pickDuplicateNick(infos)) == null || duplicateNick.isEmpty() || TabDuplicateNickResolver.isIgnoredFalsePositiveName(duplicateNick)) continue;
            HashSet<String> distinctUuids = new HashSet<String>();
            for (NetworkPlayerInfo info : infos) {
                String uuid;
                if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null || TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(uuid = TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId()))) continue;
                distinctUuids.add(uuid);
            }
            if (distinctUuids.size() < 2) continue;
            duplicateEntries += infos.size();
            HashMap<String, String> resolvedNamesByUuid = new HashMap<String, String>();
            for (NetworkPlayerInfo info : infos) {
                String actualName;
                String uuid;
                if (info == null || info.getGameProfile() == null || info.getGameProfile().getId() == null || TabDuplicateNickResolver.isIgnoredFalsePositiveUuid(uuid = TabDuplicateNickResolver.normalizeUuid(info.getGameProfile().getId()))) continue;
                String existing = duplicateNickByUuid.get(uuid);
                if (existing == null || existing.isEmpty()) {
                    duplicateNickByUuid.put(uuid, duplicateNick);
                }
                if ((actualName = UUID_TO_REAL_NAME.get(uuid)) != null && !actualName.isEmpty()) {
                    if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(actualName)) {
                        UUID_TO_REAL_NAME.remove(uuid);
                        continue;
                    }
                    resolvedNamesByUuid.put(uuid, actualName);
                    continue;
                }
                TabDuplicateNickResolver.requestLookup(uuid, duplicateNick);
            }
            for (String actualName : resolvedNamesByUuid.values()) {
                if (actualName == null || actualName.isEmpty() || actualName.equalsIgnoreCase(duplicateNick)) continue;
                activeAnnouncementKeys.add(actualName.toLowerCase() + "|" + duplicateNick.toLowerCase());
                TabDuplicateNickResolver.announceIfResolved(duplicateNick, actualName);
            }
        }
        return duplicateEntries;
    }

    private static String pickDuplicateNick(List<NetworkPlayerInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return null;
        }
        for (NetworkPlayerInfo info : infos) {
            String visible = TabDuplicateNickResolver.getVisibleTabUsername(info);
            if (visible == null || visible.isEmpty()) continue;
            return visible;
        }
        for (NetworkPlayerInfo info : infos) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) continue;
            return info.getGameProfile().getName();
        }
        return null;
    }

    private static String findDisplayedSourceName(String originalName, String duplicateNick, String baseUsername) {
        if (originalName != null && duplicateNick != null && !duplicateNick.isEmpty() && originalName.contains(duplicateNick)) {
            return duplicateNick;
        }
        if (originalName != null && baseUsername != null && !baseUsername.isEmpty() && originalName.contains(baseUsername)) {
            return baseUsername;
        }
        if (duplicateNick != null && !duplicateNick.isEmpty()) {
            return duplicateNick;
        }
        return baseUsername;
    }

    private static String normalizeUuid(UUID uuid) {
        return uuid == null ? null : CompactUuid.key(uuid);
    }

    /**
     * Merge a crowd-synced nick→real pair into the session map and {@code gomod123/tab-nick-mappings.json}.
     * Does not chat-announce; used when pulling {@code /gomod-denick-crowd.json} so relay restarts do not
     * erase mappings other players reported.
     *
     * @return true if this pair was newly added to the local session map
     */
    public static boolean rememberCrowdNickMapping(String nick, String real) {
        if (nick == null || real == null) {
            return false;
        }
        String nickKey = nick.trim().toLowerCase(Locale.ROOT);
        String realTrimmed = real.trim();
        if (nickKey.length() < 3 || realTrimmed.length() < 3 || nickKey.equalsIgnoreCase(realTrimmed)) {
            return false;
        }
        if (isFalsePositiveNickMappingRevoked(nickKey, realTrimmed)) return false;
        if (TabDuplicateNickResolver.isIgnoredFalsePositiveName(nickKey)
                || TabDuplicateNickResolver.isIgnoredFalsePositiveName(realTrimmed)) {
            return false;
        }
        String prev = SESSION_DUPLICATE_NICK_TO_REAL_NAME.putIfAbsent(nickKey, realTrimmed);
        if (prev != null && !prev.isEmpty()) {
            return false;
        }
        TeamInviteResponseHandler.REAL_TO_NICK.put(realTrimmed.toLowerCase(Locale.ROOT), nickKey);
        return true;
    }

    /**
     * Load {@code gomod123/tab-nick-mappings.json} (nick ign lower → real ign) so TAB can show {@code nick (real)}
     * after restarts before duplicate detection runs again.
     */
    public static void loadPersistedNickMappings() {
        try {
            loadPersistedRevokedFalsePositivePairs();
            Path p = GomodPaths.getGomod123Dir().toPath().resolve("tab-nick-mappings.json");
            if (!Files.exists(p)) {
                return;
            }
            byte[] raw = Files.readAllBytes(p);
            if (raw.length == 0) {
                return;
            }
            String json = new String(raw, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getKey() == null || !e.getValue().isJsonPrimitive()) {
                    continue;
                }
                String nick = e.getKey().trim().toLowerCase(Locale.ROOT);
                String real = e.getValue().getAsString();
                if (nick.length() >= 3 && real != null && real.trim().length() >= 3) {
                    String realTrimmed = real.trim();
                    if (isFalsePositiveNickMappingRevoked(nick, realTrimmed)) continue;
                    SESSION_DUPLICATE_NICK_TO_REAL_NAME.putIfAbsent(nick, realTrimmed);
                    TeamInviteResponseHandler.REAL_TO_NICK.put(realTrimmed.toLowerCase(Locale.ROOT), nick);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void schedulePersistNickMappings() {
        Thread t = new Thread(TabDuplicateNickResolver::savePersistedNickMappingsQuietly, "gomod-tab-nick-save");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void savePersistedNickMappingsQuietly() {
        try {
            java.io.File dir = GomodPaths.getGomod123Dir();
            dir.mkdirs();
            Path p = dir.toPath().resolve("tab-nick-mappings.json");
            JsonObject o = new JsonObject();
            for (Map.Entry<String, String> e : SESSION_DUPLICATE_NICK_TO_REAL_NAME.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    o.addProperty(e.getKey(), e.getValue());
                }
            }
            Files.write(p, o.toString().getBytes(StandardCharsets.UTF_8));
            appendPendingNickMappingHistoryQuietly(dir);
        } catch (Throwable ignored) {
        }
    }

    private static void appendPendingNickMappingHistoryQuietly(java.io.File dir) {
        try {
            if (PENDING_NICK_MAPPING_HISTORY_ROWS.isEmpty()) {
                return;
            }
            Path historyPath = dir.toPath().resolve("tab-nick-mappings-history.json");
            JsonArray history;
            if (Files.exists(historyPath)) {
                byte[] raw = Files.readAllBytes(historyPath);
                if (raw.length == 0) {
                    history = new JsonArray();
                } else {
                    String text = new String(raw, StandardCharsets.UTF_8).trim();
                    if (text.isEmpty()) {
                        history = new JsonArray();
                    } else {
                        JsonElement root = new JsonParser().parse(text);
                        history = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
                    }
                }
            } else {
                history = new JsonArray();
            }
            JsonObject row;
            while ((row = PENDING_NICK_MAPPING_HISTORY_ROWS.poll()) != null) {
                history.add(row);
            }
            int excess = history.size() - MAX_NICK_MAPPING_HISTORY_ROWS;
            if (excess > 0) {
                JsonArray trimmed = new JsonArray();
                for (int i = excess; i < history.size(); i++) {
                    trimmed.add(history.get(i));
                }
                history = trimmed;
            }
            Files.write(historyPath, history.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static boolean isIgnoredFalsePositiveUuid(String undashedUuid) {
        return undashedUuid != null && IGNORED_FALSE_POSITIVE_UUIDS.contains(undashedUuid.toLowerCase());
    }

    static boolean isIgnoredFalsePositiveName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (IGNORED_FALSE_POSITIVE_NAMES.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("wl") && normalized.matches(".*\\d.*");
    }

    static {
        IGNORED_FALSE_POSITIVE_UUIDS.add("0cdea3d36e2e4669913873bd0ddf16c3");
        IGNORED_FALSE_POSITIVE_UUIDS.add(Denicker.PREVIEW_SKIN_UUID.toString().replace("-", "").toLowerCase(Locale.ROOT));
        IGNORED_FALSE_POSITIVE_UUIDS.add(VisibleSpecs.PREVIEW_SPECTATOR_UUID.toString().replace("-", "").toLowerCase(Locale.ROOT));
        IGNORED_FALSE_POSITIVE_NAMES.add("0wl");
        // Common non-Hypixel tab rank labels observed as duplicate profile names on other networks.
        IGNORED_FALSE_POSITIVE_NAMES.add("mythic");
        IGNORED_FALSE_POSITIVE_NAMES.add("heroic");
        IGNORED_FALSE_POSITIVE_NAMES.add("epic");
        IGNORED_FALSE_POSITIVE_NAMES.add("champion");
        IGNORED_FALSE_POSITIVE_NAMES.add(Denicker.PREVIEW_NICK.toLowerCase(Locale.ROOT));
        IGNORED_FALSE_POSITIVE_NAMES.add(Denicker.PREVIEW_REAL.toLowerCase(Locale.ROOT));
        IGNORED_FALSE_POSITIVE_NAMES.add(VisibleSpecs.PREVIEW_SPECTATOR_NAME.toLowerCase(Locale.ROOT));
        UUID_TO_REAL_NAME = new ConcurrentHashMap();
        UUID_TO_DUPLICATE_NICK = new ConcurrentHashMap();
        SESSION_DUPLICATE_NICK_TO_REAL_NAME = new ConcurrentHashMap();
        REVOKED_FALSE_POSITIVE_PAIRS = ConcurrentHashMap.newKeySet();
        LOOKUPS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
        ANNOUNCED_MAPPINGS = ConcurrentHashMap.newKeySet();
        LOOKUP_EXECUTOR = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "gomod-tab-nick-resolver");
            t.setDaemon(true);
            return t;
        });
    }
}
