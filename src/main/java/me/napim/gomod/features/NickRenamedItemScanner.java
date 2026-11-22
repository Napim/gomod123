package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.authlib.GameProfile;
import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S04PacketEntityEquipment;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects custom renamed held items and worn armor on nicked Blitz players, notifies in chat with a
 * clickable GoStats DB lookup action, and queries {@code /blitz/data/renamed-items} asynchronously
 * only when clicked.
 *
 * <p><b>Strict detection rules (prevents false alarms on ordinary chest/in-game items):</b>
 * <ol>
 *   <li><b>Kits delivered first:</b> stays suppressed during cages and the pre-kit phase (~60–75s
 *       while {@code Get kit:} counts down) until kit delivery is confirmed via chat or scoreboard.</li>
 *   <li><b>Tooltip {@code Owner:} lore required:</b> only Blitz kit items carrying an
 *       {@code Owner: &lt;name&gt;} lore line (matching {@link DisguiseRenamedItemMasker}) qualify.</li>
 *   <li><b>Non-default name:</b> ignores vanilla item names and unrenamed kit titles
 *       ({@code Kit's Item (Level/Stars)}).</li>
 * </ol>
 */
public final class NickRenamedItemScanner {

    private static final String DEFAULT_BASE_URL = "https://gostats-discord-bot.onrender.com";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int COARSE_TICK_INTERVAL = 10; // 500 ms catch-up sweep
    private static final int MAX_STORED_LOOKUPS = 512;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_RAW_QUERY_LENGTH = 512;
    private static final int RESULT_LIMIT = 12;
    private static final long QUERY_CACHE_TTL_MS = 60_000L;
    private static final int QUERY_CACHE_MAX_ENTRIES = 64;
    private static final char[] FORMAT_STYLE_ORDER = {'k', 'l', 'm', 'n', 'o'};
    /** Conservative fallback wait (~1m20s) when cages were seen without chat/sidebar delivery confirmation. */
    private static final long CAGES_KIT_DELIVERY_FALLBACK_MS = 80_000L;
    /** Short settle delay when joining an already in-progress game mid-match. */
    private static final long MID_MATCH_JOIN_SETTLE_MS = 3_000L;

    private static final Pattern MINECRAFT_FORMATTING_RE = Pattern.compile(
            "(?:\u00a7|&)(?:[0-9a-fk-orx]|#[0-9a-f]{6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_RE = Pattern.compile("\\s+");
    private static final Pattern KIT_POSSESSIVE_PATTERN = Pattern.compile(
            "^([A-Za-z]+(?:\\s+[A-Za-z]+)*)'s\\s+.+?\\s+\\(([^)]+)\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_KIT_RECEIVE_SOON_PATTERN = Pattern.compile(
            "^You will receive your\\s+.+?\\s+kit in\\s+\\d+\\s+second(?:s)?!$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_KIT_GRANTED_PATTERN = Pattern.compile(
            "^You(?:\\s+have)?\\s+received your\\s+.+?\\s+kit!?$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> KNOWN_BLITZ_KIT_COMPACT_NAMES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "archer", "meatmaster", "speleologist", "baker", "knight", "guardian",
                    "scout", "hunter", "hypetrain", "fisherman", "armorer", "horsetamer",
                    "astronaut", "troll", "reaper", "shark", "reddragon", "toxicologist",
                    "rogue", "warlock", "slimeyslime", "jockey", "golem", "viking",
                    "shadowknight", "pigman", "paladin", "necromancer", "florist", "diver",
                    "arachnologist", "blaze", "wolftamer", "tim", "farmer", "creepertamer",
                    "snowman", "rambo", "ranger", "donkeytamer", "phoenix", "warrior",
                    "milkman", "random"
            )));

    private static final Set<String> BUILTIN_SPECIAL_PLAIN_NAMES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "blitz star",
                    "player tracker",
                    "tracking compass",
                    "compass",
                    "kit selector",
                    "game menu",
                    "play again",
                    "spectator settings",
                    "teleporter",
                    "return to lobby",
                    "leave party",
                    "taunt",
                    "breadro",
                    "big boy pants",
                    "cinderella's slippers",
                    "one pound fish",
                    "sexy pants",
                    "bedkeeper's head",
                    "herobrine's head",
                    "moleman's head",
                    "steve's head",
                    "tear trader's head",
                    "the carpenter's head",
                    "the delivery man's head",
                    "the referee's head"
            )));

    private static final Object LOOKUP_LOCK = new Object();
    private static final AtomicInteger NEXT_ENTRY_ID = new AtomicInteger(0);
    private static final Map<Integer, LookupEntry> STORED_LOOKUPS =
            new LinkedHashMap<Integer, LookupEntry>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, LookupEntry> eldest) {
                    return size() > MAX_STORED_LOOKUPS;
                }
            };
    private static final Map<String, CachedLookup> QUERY_CACHE =
            new LinkedHashMap<String, CachedLookup>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedLookup> eldest) {
                    return size() > QUERY_CACHE_MAX_ENTRIES;
                }
            };
    private static final Set<String> IN_FLIGHT_QUERIES = ConcurrentHashMap.newKeySet();

    private static final Map<Long, String> lastSlotRawByEntityAndSlot = new HashMap<Long, String>();
    private static final Set<String> announcedItemKeys = new HashSet<String>();
    private static final AtomicBoolean chestDefaultsLoadStarted = new AtomicBoolean(false);
    private static volatile Set<String> loadedChestDefaultNames = null;

    private static World lastWorld = null;
    private static boolean wasActiveMatch = false;
    private static int tickCounter = 0;

    // Kit-delivery latch for the current match/world.
    private static volatile boolean sawPreKitOrCagesThisMatch = false;
    private static volatile boolean sawGetKitCountdownThisMatch = false;
    private static volatile boolean kitsDeliveredThisMatch = false;
    private static volatile long preKitAnchorMs = 0L;
    private static volatile long liveMatchFirstSeenMs = 0L;
    private static volatile boolean pendingImmediateSweep = false;

    private NickRenamedItemScanner() {
    }

    public static final class LookupEntry {
        public final int id;
        public final String nickName;
        public final String realIgn;
        public final String itemOwner;
        public final String slotLabel;
        public final String rawDisplayName;
        public final String strippedDisplayName;
        public final String normalizedQuery;

        LookupEntry(int id, String nickName, String realIgn, String itemOwner, String slotLabel,
                    String rawDisplayName, String strippedDisplayName, String normalizedQuery) {
            this.id = id;
            this.nickName = nickName;
            this.realIgn = realIgn;
            this.itemOwner = itemOwner;
            this.slotLabel = slotLabel;
            this.rawDisplayName = rawDisplayName;
            this.strippedDisplayName = strippedDisplayName;
            this.normalizedQuery = normalizedQuery;
        }
    }

    public static final class DetectedItem {
        public final String rawDisplayName;
        public final String strippedDisplayName;
        public final String normalizedQuery;
        public final String itemOwner;

        DetectedItem(String rawDisplayName, String strippedDisplayName, String normalizedQuery, String itemOwner) {
            this.rawDisplayName = rawDisplayName;
            this.strippedDisplayName = strippedDisplayName;
            this.normalizedQuery = normalizedQuery;
            this.itemOwner = itemOwner;
        }
    }

    public static final class NickIdentity {
        public final String displayNick;
        public final String realIgn;
        public final String dedupeKey;

        public NickIdentity(String displayNick, String realIgn, String dedupeKey) {
            this.displayNick = displayNick;
            this.realIgn = realIgn;
            this.dedupeKey = dedupeKey;
        }
    }

    public static final class MatchRecord {
        public final String uuid;
        public final String username;
        public final String rankDisplay;
        public final String rankPlusColor;
        public final String monthlyRankColor;
        public final String itemLabel;
        public final String displayName;
        public final String rawDisplayName;
        public final boolean exact;
        public final boolean formatExact;
        public final long kills;
        public final long wins;

        public MatchRecord(String uuid, String username, String rankDisplay, String rankPlusColor,
                           String monthlyRankColor, String itemLabel, String displayName,
                           boolean exact, long kills, long wins) {
            this(uuid, username, rankDisplay, rankPlusColor, monthlyRankColor, itemLabel,
                    displayName, displayName, exact, false, kills, wins);
        }

        public MatchRecord(String uuid, String username, String rankDisplay, String rankPlusColor,
                           String monthlyRankColor, String itemLabel, String displayName,
                           String rawDisplayName, boolean exact, boolean formatExact,
                           long kills, long wins) {
            this.uuid = uuid;
            this.username = username;
            this.rankDisplay = rankDisplay;
            this.rankPlusColor = rankPlusColor;
            this.monthlyRankColor = monthlyRankColor;
            this.itemLabel = itemLabel;
            this.displayName = displayName;
            this.rawDisplayName = (rawDisplayName != null && !rawDisplayName.isEmpty())
                    ? rawDisplayName : displayName;
            this.exact = exact;
            this.formatExact = formatExact;
            this.kills = kills;
            this.wins = wins;
        }
    }

    public static final class SearchResponse {
        public final String query;
        public final String rawQuery;
        public final String normalizedQuery;
        public final boolean exactOnly;
        public final boolean exactFormatOnly;
        public final int formatExactTotal;
        public final int exactTotal;
        public final int substringTotal;
        public final int total;
        public final int scannedPlayers;
        public final List<MatchRecord> matches;

        public SearchResponse(String query, String normalizedQuery, int exactTotal,
                              int substringTotal, int total, int scannedPlayers,
                              List<MatchRecord> matches) {
            this(query, query, normalizedQuery, false, false, exactTotal, exactTotal,
                    substringTotal, total, scannedPlayers, matches);
        }

        public SearchResponse(String query, String rawQuery, String normalizedQuery,
                              boolean exactOnly, boolean exactFormatOnly, int formatExactTotal,
                              int exactTotal, int substringTotal, int total, int scannedPlayers,
                              List<MatchRecord> matches) {
            this.query = query;
            this.rawQuery = (rawQuery != null && !rawQuery.isEmpty()) ? rawQuery : query;
            this.normalizedQuery = normalizedQuery;
            this.exactOnly = exactOnly;
            this.exactFormatOnly = exactFormatOnly;
            this.formatExactTotal = formatExactTotal;
            this.exactTotal = exactTotal;
            this.substringTotal = substringTotal;
            this.total = total;
            this.scannedPlayers = scannedPlayers;
            this.matches = matches;
        }
    }

    public static final class ChatActionSpec {
        public final String prefixText;
        public final String linkText;
        public final String clickCommand;
        public final String hoverText;
        public final String suffixText;

        public ChatActionSpec(String prefixText, String linkText, String clickCommand,
                              String hoverText, String suffixText) {
            this.prefixText = prefixText;
            this.linkText = linkText;
            this.clickCommand = clickCommand;
            this.hoverText = hoverText;
            this.suffixText = suffixText;
        }
    }

    private static final class CachedLookup {
        final long fetchedAtMs;
        final SearchResponse response;

        CachedLookup(long fetchedAtMs, SearchResponse response) {
            this.fetchedAtMs = fetchedAtMs;
            this.response = response;
        }
    }

    /** Packet hook called from ForgeEventBridge and WeaveEventBridge. Fast O(1) exit on Netty thread. */
    public static void onPacketReceived(Object packetObj) {
        if (!(packetObj instanceof S04PacketEntityEquipment)) {
            return;
        }
        if (!ConfigManager.isNickRenamedItemScannerEnabled()
                || !ClientTickHub.isFeatureEnabled("NickRenamedItemScanner")) {
            return;
        }
        S04PacketEntityEquipment eq = (S04PacketEntityEquipment) packetObj;
        ItemStack stack = eq.getItemStack();
        // Skip everything lacking both a custom display name and tooltip Lore (where Owner: lives).
        if (stack == null || !stack.hasDisplayName() || !hasDisplayLoreQuick(stack)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        final int entityId = eq.getEntityID();
        final int slot = eq.getEquipmentSlot();
        mc.addScheduledTask(() -> handleEquipmentOnClientThread(entityId, slot, stack));
    }

    /** Observes Blitz kit-countdown and kit-delivery chat lines cleanly on client thread. */
    public static void onChatMessage(String unformattedMessage) {
        onChatMessageAt(unformattedMessage, System.currentTimeMillis());
    }

    static synchronized void onChatMessageAt(String unformattedMessage, long nowMs) {
        if (unformattedMessage == null || unformattedMessage.isEmpty()) {
            return;
        }
        String normalized = WHITESPACE_RE.matcher(stripFormattingExact(unformattedMessage)).replaceAll(" ").trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (LOCAL_KIT_GRANTED_PATTERN.matcher(normalized).matches()
                || isBlitzStarTwoMinuteRelease(normalized)
                || normalized.toLowerCase(Locale.ROOT).contains("your kit has been delivered")) {
            if (!kitsDeliveredThisMatch) {
                pendingImmediateSweep = true;
            }
            kitsDeliveredThisMatch = true;
            return;
        }
        if (normalized.contains("The game starts in 1 second")
                || normalized.equals("Survive while eliminating")
                || LOCAL_KIT_RECEIVE_SOON_PATTERN.matcher(normalized).matches()
                || isBlitzStarThreeMinuteRelease(normalized)) {
            sawPreKitOrCagesThisMatch = true;
            if (preKitAnchorMs <= 0L) {
                preKitAnchorMs = nowMs;
            }
        }
    }

    /** Client-thread coarse catch-up scan and world-change state reset. */
    public static void clientTick(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            if (lastWorld != null || wasActiveMatch || sawPreKitOrCagesThisMatch || kitsDeliveredThisMatch) {
                resetMatchCaches();
            }
            lastWorld = null;
            wasActiveMatch = false;
            return;
        }
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            resetMatchCaches();
        }
        if (!ConfigManager.isNickRenamedItemScannerEnabled()) {
            wasActiveMatch = false;
            return;
        }
        if (GameContext.isHypixelReplayWatching(mc) || ReplaySettings.isReplayActive()
                || !GameContext.isOnHypixel(mc) || GameContext.isHypixelLobby(mc)
                || GameContext.isBlitzGameOverSidebar(mc)) {
            if (wasActiveMatch || kitsDeliveredThisMatch) {
                resetMatchCaches();
                wasActiveMatch = false;
            }
            return;
        }

        long now = System.currentTimeMillis();
        boolean inCages = GameContext.isBlitzCagePhaseSidebar(mc);
        boolean waitingForKitSidebar = GameContext.isBlitzSidebarWaitingForKitsDelivery(mc);
        boolean blitzActive = GameContext.isBlitzGameActive(mc);
        boolean deathmatch = GameContext.isBlitzDeathmatchPhase(mc);

        updateKitDeliveryState(inCages, GameContext.isBlitzPregameLobby(mc),
                waitingForKitSidebar, blitzActive, deathmatch, now);

        if (!isPostKitScanContextFlags(inCages, GameContext.isBlitzPregameLobby(mc),
                waitingForKitSidebar, blitzActive, deathmatch, kitsDeliveredThisMatch)) {
            return;
        }

        if (!wasActiveMatch) {
            wasActiveMatch = true;
            pendingImmediateSweep = true;
            scheduleChestDefaultsWarmup();
        }
        boolean runNow = pendingImmediateSweep || (++tickCounter % COARSE_TICK_INTERVAL == 0);
        if (!runNow) {
            return;
        }
        pendingImmediateSweep = false;
        List<EntityPlayer> players = mc.theWorld.playerEntities;
        if (players == null || players.isEmpty()) {
            return;
        }
        for (int i = 0; i < players.size(); i++) {
            inspectAllPlayerSlots(mc, players.get(i));
        }
    }

    static synchronized void updateKitDeliveryState(boolean inCages, boolean inPregame,
                                                    boolean waitingForKitSidebar,
                                                    boolean blitzActive, boolean deathmatch,
                                                    long nowMs) {
        if (inPregame || inCages) {
            sawPreKitOrCagesThisMatch = true;
            kitsDeliveredThisMatch = false;
            liveMatchFirstSeenMs = 0L;
            if (inCages && preKitAnchorMs <= 0L) {
                preKitAnchorMs = nowMs;
            }
            return;
        }
        if (!blitzActive && !deathmatch) {
            return;
        }
        if (deathmatch) {
            if (!kitsDeliveredThisMatch) {
                pendingImmediateSweep = true;
            }
            kitsDeliveredThisMatch = true;
            return;
        }
        if (liveMatchFirstSeenMs <= 0L) {
            liveMatchFirstSeenMs = nowMs;
        }
        if (waitingForKitSidebar) {
            sawGetKitCountdownThisMatch = true;
            sawPreKitOrCagesThisMatch = true;
            kitsDeliveredThisMatch = false;
            if (preKitAnchorMs <= 0L) {
                preKitAnchorMs = nowMs;
            }
            return;
        }
        if (kitsDeliveredThisMatch) {
            return;
        }
        // Authoritative signal #1: "Get kit: MM:SS" countdown disappeared while live match stayed active.
        if (sawGetKitCountdownThisMatch) {
            kitsDeliveredThisMatch = true;
            pendingImmediateSweep = true;
            return;
        }
        // Fallback when cages/pre-kit chat was observed but sidebar never exposed a countdown row.
        if (sawPreKitOrCagesThisMatch) {
            long anchor = preKitAnchorMs > 0L ? preKitAnchorMs : liveMatchFirstSeenMs;
            if (nowMs - anchor >= CAGES_KIT_DELIVERY_FALLBACK_MS) {
                kitsDeliveredThisMatch = true;
                pendingImmediateSweep = true;
            }
            return;
        }
        // Mid-game spectator/rejoin after kits already arrived: wait briefly for sidebar/chat to settle.
        if (nowMs - liveMatchFirstSeenMs >= MID_MATCH_JOIN_SETTLE_MS) {
            kitsDeliveredThisMatch = true;
            pendingImmediateSweep = true;
        }
    }

    static synchronized void resetMatchCaches() {
        lastSlotRawByEntityAndSlot.clear();
        announcedItemKeys.clear();
        tickCounter = 0;
        sawPreKitOrCagesThisMatch = false;
        sawGetKitCountdownThisMatch = false;
        kitsDeliveredThisMatch = false;
        preKitAnchorMs = 0L;
        liveMatchFirstSeenMs = 0L;
        pendingImmediateSweep = false;
    }

    static boolean isKitsDeliveredThisMatch() {
        return kitsDeliveredThisMatch;
    }

    private static boolean isBlitzStarThreeMinuteRelease(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("blitz star") && lower.contains("3 minute")
                && (lower.contains("release") || lower.contains("released"));
    }

    private static boolean isBlitzStarTwoMinuteRelease(String normalized) {
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("blitz star") && lower.contains("2 minute")
                && (lower.contains("release") || lower.contains("released") || lower.contains("releas"));
    }

    private static void handleEquipmentOnClientThread(int entityId, int slot, ItemStack stack) {
        if (!ConfigManager.isNickRenamedItemScannerEnabled() || stack == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isActiveScanContext(mc)) {
            return;
        }
        scheduleChestDefaultsWarmup();
        Entity entity = mc.theWorld.getEntityByID(entityId);
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) entity;
        NickIdentity nick = resolveNickIdentity(mc, player);
        if (nick == null) {
            return;
        }
        inspectPlayerSlot(player.getEntityId(), slot, stack, nick);
    }

    private static void inspectAllPlayerSlots(Minecraft mc, EntityPlayer player) {
        if (player == null || player == mc.thePlayer) {
            return;
        }
        // Quick display-name + Lore NBT check across all 5 slots before TAB/nick resolution.
        ItemStack held = player.getCurrentEquippedItem();
        ItemStack[] armor = player.inventory != null ? player.inventory.armorInventory : null;
        boolean anyCandidate = (held != null && held.hasDisplayName() && hasDisplayLoreQuick(held));
        if (!anyCandidate && armor != null) {
            for (int i = 0; i < armor.length && i < 4; i++) {
                if (armor[i] != null && armor[i].hasDisplayName() && hasDisplayLoreQuick(armor[i])) {
                    anyCandidate = true;
                    break;
                }
            }
        }
        if (!anyCandidate) {
            return;
        }
        NickIdentity nick = resolveNickIdentity(mc, player);
        if (nick == null) {
            return;
        }
        int entityId = player.getEntityId();
        inspectPlayerSlot(entityId, 0, held, nick);
        if (armor != null) {
            for (int i = 0; i < 4 && i < armor.length; i++) {
                // MC 1.8 equipment slot mapping: 1=boots, 2=leggings, 3=chestplate, 4=helmet
                inspectPlayerSlot(entityId, i + 1, armor[i], nick);
            }
        }
    }

    private static void inspectPlayerSlot(int entityId, int slot, ItemStack stack, NickIdentity nick) {
        if (stack == null || !stack.hasDisplayName() || !hasDisplayLoreQuick(stack) || nick == null) {
            return;
        }
        String rawName = extractRawCustomDisplayName(stack);
        if (rawName == null || rawName.isEmpty()) {
            return;
        }
        long slotKey = (((long) entityId) << 4) | (slot & 0xFL);
        String previousRaw = lastSlotRawByEntityAndSlot.get(slotKey);
        if (rawName.equals(previousRaw)) {
            return;
        }
        lastSlotRawByEntityAndSlot.put(slotKey, rawName);

        Set<String> chestDefaults = getChestDefaultNamesNonBlocking();
        DetectedItem detected = inspectCandidateItem(stack, rawName, chestDefaults);
        if (detected == null) {
            return;
        }

        String dedupeKey = nick.dedupeKey + "|" + detected.normalizedQuery;
        if (!announcedItemKeys.add(dedupeKey)) {
            return;
        }

        String slotLabel = slotLabelFor(slot);
        int entryId = storeLookupEntry(nick.displayNick, nick.realIgn, detected.itemOwner, slotLabel, detected);
        sendDetectedChatMessage(nick, slotLabel, detected, entryId);
    }

    static boolean isActiveScanContext(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        if (GameContext.isHypixelReplayWatching(mc) || ReplaySettings.isReplayActive()) {
            return false;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return false;
        }
        if (GameContext.isHypixelLobby(mc)
                || GameContext.isBlitzGameOverSidebar(mc)) {
            return false;
        }
        return isPostKitScanContextFlags(
                GameContext.isBlitzCagePhaseSidebar(mc),
                GameContext.isBlitzPregameLobby(mc),
                GameContext.isBlitzSidebarWaitingForKitsDelivery(mc),
                GameContext.isBlitzGameActive(mc),
                GameContext.isBlitzDeathmatchPhase(mc),
                kitsDeliveredThisMatch);
    }

    static boolean isPostKitScanContextFlags(boolean inCages, boolean inPregame,
                                             boolean waitingForKitSidebar,
                                             boolean blitzActive, boolean deathmatch,
                                             boolean kitsDelivered) {
        if (inCages || inPregame || waitingForKitSidebar || !kitsDelivered) {
            return false;
        }
        return blitzActive || deathmatch;
    }

    static NickIdentity resolveNickIdentity(Minecraft mc, EntityPlayer player) {
        if (mc == null || player == null || player == mc.thePlayer) {
            return null;
        }
        if (SpectatorRelay.isConfirmedSpectator(player)) {
            return null;
        }
        GameProfile entityProfile = player.getGameProfile();
        if (entityProfile == null || entityProfile.getName() == null) {
            return null;
        }
        String entityName = entityProfile.getName().trim();
        if (entityName.isEmpty() || KillCounter.isLocalClientAnyKnownIgn(entityName)) {
            return null;
        }
        NetHandlerPlayClient net = mc.getNetHandler();
        if (net == null) {
            return null;
        }
        NetworkPlayerInfo info = findTabEntry(net, player.getUniqueID(), entityName);
        if (info == null) {
            // Exclude Watchdog bots, holograms, and NPC entities not present in TAB.
            return null;
        }
        if (TabDuplicateNickResolver.isDisguiseDisplayProfile(info)) {
            return null;
        }
        GameProfile sourceProfile = RemoteDisguiseManager.getSourceProfile(info);
        GameProfile effectiveProfile = sourceProfile != null ? sourceProfile : info.getGameProfile();
        UUID sourceUuid = effectiveProfile != null && effectiveProfile.getId() != null
                ? effectiveProfile.getId() : player.getUniqueID();
        String profileName = effectiveProfile != null && effectiveProfile.getName() != null
                ? effectiveProfile.getName().trim() : entityName;
        if (profileName.isEmpty() || KillCounter.isLocalClientAnyKnownIgn(profileName)) {
            return null;
        }

        String visibleNick = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
        String displayNick = visibleNick != null && !visibleNick.trim().isEmpty()
                ? visibleNick.trim() : profileName;
        String resolvedReal = resolveKnownRealIgn(sourceUuid, profileName, displayNick);
        boolean nonV4Uuid = isNickedProfileUuid(sourceUuid);
        if (!nonV4Uuid && resolvedReal == null) {
            return null;
        }
        String dedupeKey = sourceUuid != null
                ? sourceUuid.toString().toLowerCase(Locale.ROOT)
                : displayNick.toLowerCase(Locale.ROOT);
        return new NickIdentity(displayNick, resolvedReal, dedupeKey);
    }

    static boolean isNickedProfileUuid(UUID uuid) {
        return uuid != null && RecentOpponentRecordOps.isGeneratedNickUuid(uuid.toString());
    }

    private static String resolveKnownRealIgn(UUID sourceUuid, String profileName, String displayNick) {
        String real = TabDuplicateNickResolver.resolveRealNameForDenicker(sourceUuid, profileName);
        if (real == null && displayNick != null && !displayNick.isEmpty()) {
            real = TabDuplicateNickResolver.getKnownRealName(displayNick);
        }
        if (real == null && profileName != null && !profileName.equals(displayNick)) {
            real = TabDuplicateNickResolver.getKnownRealName(profileName);
        }
        if (real == null) {
            return null;
        }
        String trimmed = real.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(displayNick) || trimmed.equalsIgnoreCase(profileName)) {
            return null;
        }
        return trimmed;
    }

    private static NetworkPlayerInfo findTabEntry(NetHandlerPlayClient net, UUID uuid, String entityName) {
        if (uuid != null) {
            NetworkPlayerInfo byUuid = net.getPlayerInfo(uuid);
            if (byUuid != null) {
                return byUuid;
            }
        }
        Collection<NetworkPlayerInfo> entries = net.getPlayerInfoMap();
        if (entries == null || entries.isEmpty() || entries.size() > 64) {
            return null;
        }
        for (NetworkPlayerInfo info : entries) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String name = info.getGameProfile().getName();
            if (name != null && name.equalsIgnoreCase(entityName)) {
                return info;
            }
        }
        return null;
    }

    private static boolean hasDisplayLoreQuick(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null || !tag.hasKey("display", 10)) {
                return false;
            }
            NBTTagCompound display = tag.getCompoundTag("display");
            return display != null && display.hasKey("Lore", 9);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String extractRawCustomDisplayName(ItemStack stack) {
        if (stack == null || !stack.hasDisplayName()) {
            return null;
        }
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null && tag.hasKey("display", 10)) {
                NBTTagCompound display = tag.getCompoundTag("display");
                if (display != null && display.hasKey("Name", 8)) {
                    String nbtName = display.getString("Name");
                    if (nbtName != null && !nbtName.isEmpty()) {
                        return nbtName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return stack.getDisplayName();
    }

    static DetectedItem inspectCandidateItem(ItemStack stack, String rawName, Set<String> chestDefaults) {
        if (stack == null || rawName == null || rawName.isEmpty()) {
            return null;
        }
        // Requirement #2: real Blitz kit items carry an "Owner: <Name>" lore line.
        String itemOwner = DisguiseRenamedItemMasker.extractItemOwnerFromLore(stack);
        if (itemOwner == null || itemOwner.trim().isEmpty()) {
            return null;
        }
        String stripped = stripFormattingExact(rawName);
        if (stripped.isEmpty()) {
            return null;
        }
        try {
            if (stack.getItem() != null) {
                String vanilla = stripFormattingExact(stack.getItem().getItemStackDisplayName(stack));
                if (!vanilla.isEmpty() && stripped.equalsIgnoreCase(vanilla)) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        boolean isSkull = false;
        try {
            isSkull = stack.getItem() instanceof ItemSkull;
        } catch (Throwable ignored) {
        }
        return filterNonCustomName(rawName, stripped, itemOwner, isSkull, chestDefaults);
    }

    /** Pure string/item classifier exposed for unit testing without requiring full Minecraft item init. */
    static DetectedItem filterNonCustomName(String rawName, String strippedDisplay, String itemOwner,
                                            boolean isSkullItem, Set<String> chestDefaults) {
        if (rawName == null || strippedDisplay == null || itemOwner == null || itemOwner.trim().isEmpty()) {
            return null;
        }
        String cleanStripped = stripFormattingExact(strippedDisplay);
        if (cleanStripped.isEmpty()) {
            return null;
        }
        String normalized = cleanStripped.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("(right click)") || normalized.endsWith("(left click)")) {
            return null;
        }
        if (BUILTIN_SPECIAL_PLAIN_NAMES.contains(normalized)) {
            return null;
        }
        if (isSkullItem && normalized.endsWith("'s head")) {
            return null;
        }
        if (chestDefaults != null && chestDefaults.contains(normalized)) {
            return null;
        }
        String apostropheNormalized = cleanStripped.replace('\u2019', '\'').replace('\u2018', '\'');
        if (isDefaultKitTitle(apostropheNormalized)) {
            return null;
        }
        return new DetectedItem(rawName, cleanStripped, normalized, itemOwner.trim());
    }

    private static boolean isDefaultKitTitle(String cleanText) {
        if (KillCounter.isDefaultKitItemDisplayName(cleanText)) {
            return true;
        }
        Matcher m = KIT_POSSESSIVE_PATTERN.matcher(cleanText);
        if (!m.matches()) {
            return false;
        }
        String compactKit = WHITESPACE_RE.matcher(m.group(1)).replaceAll("").toLowerCase(Locale.ROOT);
        if (!KNOWN_BLITZ_KIT_COMPACT_NAMES.contains(compactKit)) {
            return false;
        }
        return isKnownKitLevelOrPrestigeToken(m.group(2).trim());
    }

    private static boolean isKnownKitLevelOrPrestigeToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if ("?".equals(token) || "??".equals(token)) {
            return true;
        }
        boolean allStarsOrQuestion = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '\u272B' && c != '\u2B50' && c != '\u2605'
                    && c != '\u2726' && c != '\u2606' && c != '\u2B51' && c != '?') {
                allStarsOrQuestion = false;
                break;
            }
        }
        if (allStarsOrQuestion) {
            return true;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        return "I".equals(upper) || "II".equals(upper) || "III".equals(upper)
                || "IV".equals(upper) || "V".equals(upper) || "VI".equals(upper)
                || "VII".equals(upper) || "VIII".equals(upper) || "IX".equals(upper)
                || "X".equals(upper);
    }

    public static boolean hasFormattingCodes(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String text = value.replace("\\u00a7", "\u00a7");
        return MINECRAFT_FORMATTING_RE.matcher(text).find();
    }

    private static final class FormatParseState {
        boolean started;
        boolean hasPendingSpace;
        String pendingSpaceColor = "";
        String pendingSpaceStyles = "";
    }

    /** Canonicalizes Minecraft color/style runs consistently with GoStats {@code canonicalize_minecraft_formatting}. */
    public static String canonicalizeFormattingExact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String raw = value.replace("\\u00a7", "\u00a7");
        List<String> runColors = new ArrayList<String>();
        List<String> runStyles = new ArrayList<String>();
        List<StringBuilder> runTexts = new ArrayList<StringBuilder>();

        String curColor = "";
        boolean[] curStyleFlags = new boolean[5]; // k, l, m, n, o
        FormatParseState parseState = new FormatParseState();

        Matcher matcher = MINECRAFT_FORMATTING_RE.matcher(raw);
        int pos = 0;
        while (matcher.find()) {
            String segment = raw.substring(pos, matcher.start());
            if (!segment.isEmpty()) {
                consumeFormattedSegment(segment, curColor, buildStyleKey(curStyleFlags),
                        parseState, runColors, runStyles, runTexts);
            }
            String codeToken = matcher.group(0).substring(1).toLowerCase(Locale.ROOT);
            if ("f".equals(codeToken) || "r".equals(codeToken)) {
                curColor = "";
                Arrays.fill(curStyleFlags, false);
            } else if ((codeToken.length() == 1 && "0123456789abcde".indexOf(codeToken.charAt(0)) >= 0)
                    || codeToken.startsWith("#")) {
                curColor = codeToken;
                Arrays.fill(curStyleFlags, false);
            } else if (codeToken.length() == 1) {
                for (int i = 0; i < FORMAT_STYLE_ORDER.length; i++) {
                    if (FORMAT_STYLE_ORDER[i] == codeToken.charAt(0)) {
                        curStyleFlags[i] = true;
                        break;
                    }
                }
            }
            pos = matcher.end();
        }
        if (pos < raw.length()) {
            consumeFormattedSegment(raw.substring(pos), curColor, buildStyleKey(curStyleFlags),
                    parseState, runColors, runStyles, runTexts);
        }

        if (runTexts.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        String prevColor = "";
        String prevStyles = "";
        for (int i = 0; i < runTexts.size(); i++) {
            String color = runColors.get(i);
            String styles = runStyles.get(i);
            if (!color.equals(prevColor) || !styles.equals(prevStyles)) {
                if (prevColor.isEmpty() && prevStyles.isEmpty()) {
                    if (!color.isEmpty()) {
                        out.append('\u00a7').append(color);
                    }
                    appendStyleCodes(out, styles, "");
                } else if (color.equals(prevColor) && containsAllChars(styles, prevStyles)) {
                    appendStyleCodes(out, styles, prevStyles);
                } else if (!color.equals(prevColor) && !color.isEmpty()) {
                    out.append('\u00a7').append(color);
                    appendStyleCodes(out, styles, "");
                } else {
                    out.append("\u00a7r");
                    if (!color.isEmpty()) {
                        out.append('\u00a7').append(color);
                    }
                    appendStyleCodes(out, styles, "");
                }
                prevColor = color;
                prevStyles = styles;
            }
            out.append(runTexts.get(i));
        }
        return out.toString();
    }

    private static void consumeFormattedSegment(String segment, String curColor, String curStyles,
                                                FormatParseState state,
                                                List<String> runColors, List<String> runStyles,
                                                List<StringBuilder> runTexts) {
        String normalized = Normalizer.normalize(segment, Normalizer.Form.NFKC);
        int i = 0;
        while (i < normalized.length()) {
            int cp = normalized.codePointAt(i);
            int charCount = Character.charCount(cp);
            i += charCount;
            if (Character.getType(cp) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(cp)) {
                if (state.started && !state.hasPendingSpace) {
                    state.hasPendingSpace = true;
                    state.pendingSpaceColor = curColor;
                    state.pendingSpaceStyles = curStyles;
                }
                continue;
            }
            if (state.hasPendingSpace) {
                appendRunText(runColors, runStyles, runTexts,
                        state.pendingSpaceColor, state.pendingSpaceStyles, " ");
                state.hasPendingSpace = false;
            }
            state.started = true;
            String folded = new String(Character.toChars(cp)).toLowerCase(Locale.ROOT);
            appendRunText(runColors, runStyles, runTexts, curColor, curStyles, folded);
        }
    }

    private static void appendRunText(List<String> runColors, List<String> runStyles,
                                      List<StringBuilder> runTexts, String color, String styles,
                                      String text) {
        int lastIdx = runTexts.size() - 1;
        if (lastIdx >= 0 && runColors.get(lastIdx).equals(color) && runStyles.get(lastIdx).equals(styles)) {
            runTexts.get(lastIdx).append(text);
        } else {
            runColors.add(color);
            runStyles.add(styles);
            runTexts.add(new StringBuilder(text));
        }
    }

    private static String buildStyleKey(boolean[] flags) {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < FORMAT_STYLE_ORDER.length; i++) {
            if (flags[i]) {
                sb.append(FORMAT_STYLE_ORDER[i]);
            }
        }
        return sb.toString();
    }

    private static boolean containsAllChars(String superset, String subset) {
        for (int i = 0; i < subset.length(); i++) {
            if (superset.indexOf(subset.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void appendStyleCodes(StringBuilder out, String styles, String exclude) {
        for (int i = 0; i < styles.length(); i++) {
            char c = styles.charAt(i);
            if (exclude.indexOf(c) < 0) {
                out.append('\u00a7').append(c);
            }
        }
    }

    /** Mirrors GoStats {@code strip_minecraft_formatting} so search tokens match byte-for-byte. */
    public static String stripFormattingExact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\\u00a7", "\u00a7");
        text = MINECRAFT_FORMATTING_RE.matcher(text).replaceAll("");
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.getType(c) != Character.FORMAT) {
                sb.append(c);
            }
        }
        return WHITESPACE_RE.matcher(sb.toString()).replaceAll(" ").trim();
    }

    public static String normalizeSearchQuery(String value) {
        return stripFormattingExact(value).toLowerCase(Locale.ROOT);
    }

    static String slotLabelFor(int slot) {
        switch (slot) {
            case 0:
                return "hand";
            case 1:
                return "boots";
            case 2:
                return "leggings";
            case 3:
                return "chestplate";
            case 4:
                return "helmet";
            default:
                return "item";
        }
    }

    private static int storeLookupEntry(String nickName, String realIgn, String itemOwner,
                                        String slotLabel, DetectedItem item) {
        synchronized (LOOKUP_LOCK) {
            int id = NEXT_ENTRY_ID.incrementAndGet();
            STORED_LOOKUPS.put(id, new LookupEntry(
                    id, nickName, realIgn, itemOwner, slotLabel,
                    item.rawDisplayName, item.strippedDisplayName, item.normalizedQuery));
            return id;
        }
    }

    public static LookupEntry getStoredLookup(int id) {
        synchronized (LOOKUP_LOCK) {
            return STORED_LOOKUPS.get(id);
        }
    }

    private static void sendDetectedChatMessage(NickIdentity nick, String slotLabel,
                                                DetectedItem detected, int entryId) {
        IChatComponent root = buildDetectedChatComponent(nick, slotLabel, detected, entryId);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Runnable send = () -> {
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(root);
            } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(root);
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            send.run();
        } else {
            mc.addScheduledTask(send);
        }
    }

    static IChatComponent buildDetectedChatComponent(NickIdentity nick, String slotLabel,
                                                     DetectedItem detected, int entryId) {
        return toChatComponent(buildDetectedChatSpec(nick, slotLabel, detected, entryId));
    }

    static ChatActionSpec buildDetectedChatSpec(NickIdentity nick, String slotLabel,
                                                DetectedItem detected, int entryId) {
        String actionVerb = "hand".equals(slotLabel) ? "holding renamed hand" : "wearing renamed " + slotLabel;
        StringBuilder prefix = new StringBuilder();
        prefix.append(Main.CHAT_PREFIX)
                .append("\u00a7eNick ")
                .append("\u00a7f").append(nick.displayNick);
        if (nick.realIgn != null && !nick.realIgn.isEmpty()) {
            prefix.append("\u00a77 (")
                    .append("\u00a76").append(nick.realIgn)
                    .append("\u00a77)");
        }
        prefix.append("\u00a7e ").append(actionVerb).append(": ")
                .append("\u00a7f").append(detected.rawDisplayName)
                .append("\u00a7r ");
        String plainNick = stripFormattingExact(nick.displayNick);
        if (detected.itemOwner != null && !detected.itemOwner.isEmpty()
                && !detected.itemOwner.equalsIgnoreCase(plainNick)
                && (nick.realIgn == null || !detected.itemOwner.equalsIgnoreCase(nick.realIgn))) {
            prefix.append("\u00a77[Owner: \u00a7e").append(detected.itemOwner).append("\u00a77] ");
        }

        String linkText = "\u00a7a\u00a7l[Search item owner]";
        String hoverText = "\u00a7aClick to search the owner of this item"
                + "\n\u00a78Note: nick may hold/wear another player's looted item";
        return new ChatActionSpec(prefix.toString(), linkText, "/nickitemdb " + entryId, hoverText, "");
    }

    private static IChatComponent toChatComponent(ChatActionSpec spec) {
        IChatComponent root = new ChatComponentText(spec.prefixText);
        if (spec.linkText != null && !spec.linkText.isEmpty()) {
            IChatComponent button = new ChatComponentText(spec.linkText);
            ChatStyle style = new ChatStyle();
            if (spec.clickCommand != null && !spec.clickCommand.isEmpty()) {
                style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, spec.clickCommand));
            }
            if (spec.hoverText != null && !spec.hoverText.isEmpty()) {
                style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(spec.hoverText)));
            }
            button.setChatStyle(style);
            root.appendSibling(button);
        }
        if (spec.suffixText != null && !spec.suffixText.isEmpty()) {
            root.appendSibling(new ChatComponentText(spec.suffixText));
        }
        return root;
    }

    public static void lookupByEntryId(int id) {
        LookupEntry entry = getStoredLookup(id);
        if (entry == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "That renamed-item lookup entry has expired. Use /nickitemdb <item name> instead.");
            return;
        }
        String querySource = (entry.rawDisplayName != null && !entry.rawDisplayName.isEmpty())
                ? entry.rawDisplayName : entry.strippedDisplayName;
        lookupRenamedItemInDatabase(querySource, entry.nickName, true, true);
    }

    public static void lookupRenamedItemInDatabase(String rawQuery, String nickContext) {
        boolean hasFormatting = hasFormattingCodes(rawQuery);
        lookupRenamedItemInDatabase(rawQuery, nickContext, hasFormatting, hasFormatting);
    }

    public static void lookupRenamedItemInDatabase(String rawQuery, String nickContext,
                                                   boolean exactOnly, boolean exactFormatOnly) {
        final String queryDisplay = stripFormattingExact(rawQuery);
        final String normalizedQuery = queryDisplay.toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /nickitemdb <renamed item name>");
            return;
        }
        if (queryDisplay.length() > MAX_QUERY_LENGTH) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Search query must be " + MAX_QUERY_LENGTH + " characters or fewer.");
            return;
        }

        final String trimmedRaw = rawQuery == null ? queryDisplay
                : rawQuery.replace("\\u00a7", "\u00a7").trim();
        final String rawForRequest = trimmedRaw.length() > MAX_RAW_QUERY_LENGTH ? queryDisplay : trimmedRaw;
        final String canonicalQuery = exactFormatOnly
                ? canonicalizeFormattingExact(rawForRequest.isEmpty() ? queryDisplay : rawForRequest)
                : normalizedQuery;
        final String cacheKey = (exactFormatOnly ? "EF:" : (exactOnly ? "E:" : "A:"))
                + (exactFormatOnly ? canonicalQuery : normalizedQuery);
        final String displayLabel = (exactFormatOnly && !rawForRequest.isEmpty())
                ? rawForRequest.replace('&', '\u00a7')
                : queryDisplay;

        CachedLookup cached = getFreshCachedLookup(cacheKey);
        if (cached != null) {
            sendSearchResultsChat(cached.response, nickContext);
            return;
        }

        if (!IN_FLIGHT_QUERIES.add(cacheKey)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Already searching GoStats DB for \"" + displayLabel + "\u00a77\"...");
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Searching GoStats DB for " + EnumChatFormatting.WHITE + "\"" + displayLabel
                + EnumChatFormatting.WHITE + "\"" + EnumChatFormatting.GRAY
                + (exactFormatOnly ? " (exact formatting)" : (exactOnly ? " (exact name)" : "")) + "...");

        Thread worker = new Thread(() -> {
            try {
                SearchResponse response = fetchRenamedItemMatches(
                        queryDisplay, rawForRequest, RESULT_LIMIT, exactOnly, exactFormatOnly);
                putCachedLookup(cacheKey, response);
                sendSearchResultsChat(response, nickContext);
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Renamed-item DB lookup failed: " + e.getMessage());
            } finally {
                IN_FLIGHT_QUERIES.remove(cacheKey);
            }
        }, "gomod-nick-renamed-db");
        worker.setDaemon(true);
        worker.start();
    }

    public static SearchResponse fetchRenamedItemMatches(String query, int limit) throws Exception {
        return fetchRenamedItemMatches(query, query, limit, false, false);
    }

    public static SearchResponse fetchRenamedItemMatches(String queryDisplay, String rawQuery,
                                                       int limit, boolean exactOnly,
                                                       boolean exactFormatOnly) throws Exception {
        String base = System.getProperty("gomod123.gostatsUrl", DEFAULT_BASE_URL);
        if (base == null || base.trim().isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String plain = stripFormattingExact(queryDisplay);
        if (plain.isEmpty()) {
            plain = stripFormattingExact(rawQuery);
        }
        String encodedQuery = URLEncoder.encode(plain, "UTF-8");
        int clampedLimit = Math.max(1, Math.min(50, limit));
        StringBuilder urlBuilder = new StringBuilder(base)
                .append("/blitz/data/renamed-items?query=").append(encodedQuery)
                .append("&limit=").append(clampedLimit);
        if (exactOnly || exactFormatOnly) {
            urlBuilder.append("&exact=1");
        }
        if (exactFormatOnly) {
            urlBuilder.append("&exact_format=1");
        }
        String normalizedRaw = rawQuery == null ? "" : rawQuery.replace("\\u00a7", "\u00a7").trim();
        if (!normalizedRaw.isEmpty() && normalizedRaw.length() <= MAX_RAW_QUERY_LENGTH
                && (exactFormatOnly || hasFormattingCodes(normalizedRaw))) {
            urlBuilder.append("&raw=").append(URLEncoder.encode(normalizedRaw, "UTF-8"));
        }
        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "gomod123-nick-renamed-items");
        try {
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAVAILABLE) {
                throw new Exception("GoStats database is starting up — try clicking again in a moment.");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                String errBody = readStream(conn.getErrorStream());
                throw new Exception("HTTP " + status + (errBody.isEmpty() ? "" : " (" + errBody + ")"));
            }
            String jsonText = readStream(conn.getInputStream());
            return parseSearchResponse(jsonText);
        } finally {
            conn.disconnect();
        }
    }

    static SearchResponse parseSearchResponse(String jsonText) throws Exception {
        JsonElement parsed = new JsonParser().parse(jsonText);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new Exception("gostats123 returned invalid JSON");
        }
        JsonObject root = parsed.getAsJsonObject();
        String query = jsonString(root, "query", "");
        String rawQuery = jsonString(root, "raw_query", query);
        String normalizedQuery = jsonString(root, "normalized_query", "");
        boolean exactOnly = root.has("exact_only") && !root.get("exact_only").isJsonNull()
                && root.get("exact_only").getAsBoolean();
        boolean exactFormatOnly = root.has("exact_format_only") && !root.get("exact_format_only").isJsonNull()
                && root.get("exact_format_only").getAsBoolean();
        int exactTotal = (int) jsonLong(root, "exact_total", 0L);
        int formatExactTotal = (int) jsonLong(root, "format_exact_total", exactTotal);
        int substringTotal = (int) jsonLong(root, "substring_total", 0L);
        int total = (int) jsonLong(root, "total", exactTotal + substringTotal);
        int scannedPlayers = (int) jsonLong(root, "scanned_players", 0L);

        List<MatchRecord> matches = new ArrayList<MatchRecord>();
        if (root.has("matches") && root.get("matches").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("matches");
            for (JsonElement element : arr) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                JsonObject rankObj = obj.has("rank_info") && obj.get("rank_info").isJsonObject()
                        ? obj.getAsJsonObject("rank_info") : null;
                String displayName = jsonString(obj, "display_name", "");
                String rawDisplayName = jsonString(obj, "raw_display_name", displayName);
                boolean exact = obj.has("exact") && !obj.get("exact").isJsonNull() && obj.get("exact").getAsBoolean();
                boolean formatExact = obj.has("format_exact") && !obj.get("format_exact").isJsonNull()
                        && obj.get("format_exact").getAsBoolean();
                matches.add(new MatchRecord(
                        jsonString(obj, "uuid", ""),
                        jsonString(obj, "username", "Unknown"),
                        rankObj == null ? "" : jsonString(rankObj, "display", ""),
                        rankObj == null ? "" : jsonString(rankObj, "rank_plus_color", ""),
                        rankObj == null ? "" : jsonString(rankObj, "monthly_rank_color", ""),
                        jsonString(obj, "item_label", ""),
                        displayName,
                        rawDisplayName,
                        exact,
                        formatExact,
                        jsonLong(obj, "kills", 0L),
                        jsonLong(obj, "wins", 0L)
                ));
            }
        }
        return new SearchResponse(query, rawQuery, normalizedQuery, exactOnly, exactFormatOnly,
                formatExactTotal, exactTotal, substringTotal, total, scannedPlayers, matches);
    }

    private static void sendSearchResultsChat(SearchResponse res, String nickContext) {
        Minecraft mc = Minecraft.getMinecraft();
        List<IChatComponent> lines = buildSearchResultLines(res, nickContext);
        if (mc == null) {
            return;
        }
        Runnable post = () -> {
            for (IChatComponent line : lines) {
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(line);
                } else if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(line);
                }
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            post.run();
        } else {
            mc.addScheduledTask(post);
        }
    }

    static List<IChatComponent> buildSearchResultLines(SearchResponse res, String nickContext) {
        List<ChatActionSpec> specs = buildSearchResultSpecs(res, nickContext);
        List<IChatComponent> lines = new ArrayList<IChatComponent>(specs.size());
        for (ChatActionSpec spec : specs) {
            lines.add(toChatComponent(spec));
        }
        return lines;
    }

    static List<ChatActionSpec> buildSearchResultSpecs(SearchResponse res, String nickContext) {
        List<ChatActionSpec> specs = new ArrayList<ChatActionSpec>();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        String ctx = (nickContext != null && !nickContext.isEmpty())
                ? "\u00a77 [\u00a7f" + nickContext + "\u00a77]"
                : "";
        String shownQuery = (res.rawQuery != null && !res.rawQuery.isEmpty()) ? res.rawQuery : res.query;
        if (res.matches.isEmpty()) {
            if (res.exactFormatOnly && res.exactTotal > res.formatExactTotal && res.query != null && !res.query.isEmpty()) {
                String prefix = Main.CHAT_PREFIX + "\u00a7eNo exact-formatting DB owners matched \u00a7f\""
                        + shownQuery + "\u00a7r\u00a7f\"" + ctx
                        + "\u00a77 (" + nf.format(res.scannedPlayers) + " players; \u00a7f"
                        + res.exactTotal + "\u00a77 same text, other style/color) ";
                String hover = "\u00a7bClick to view players who named an item \"" + res.query
                        + "\" with different bold/italic/color codes";
                specs.add(new ChatActionSpec(prefix, "\u00a7b[Show Same Name]",
                        "/nickitemdb --plain " + res.query, hover, ""));
            } else {
                specs.add(new ChatActionSpec(
                        Main.CHAT_PREFIX + "\u00a7eNo "
                                + (res.exactFormatOnly ? "exact-formatting " : (res.exactOnly ? "exact " : ""))
                                + "DB owners matched \u00a7f\"" + shownQuery + "\u00a7r\u00a7f\"" + ctx
                                + "\u00a77 (" + nf.format(res.scannedPlayers) + " saved players).",
                        "", "", "", ""));
            }
            return specs;
        }

        String summaryStats = res.exactFormatOnly
                ? "\u00a77 (exact: \u00a7f" + res.formatExactTotal
                        + (res.exactTotal > res.formatExactTotal
                        ? "\u00a77, other-style: \u00a7f" + (res.exactTotal - res.formatExactTotal) : "")
                        + "\u00a77, players: \u00a7f" + nf.format(res.scannedPlayers) + "\u00a77):"
                : (res.exactOnly
                ? "\u00a77 (exact name: \u00a7f" + res.exactTotal
                        + "\u00a77, players: \u00a7f" + nf.format(res.scannedPlayers) + "\u00a77):"
                : "\u00a77 (exact: \u00a7f" + res.exactTotal
                        + "\u00a77, partial: \u00a7f" + res.substringTotal
                        + "\u00a77, players: \u00a7f" + nf.format(res.scannedPlayers) + "\u00a77):");
        specs.add(new ChatActionSpec(
                Main.CHAT_PREFIX + "\u00a7aRenamed item DB matches for \u00a7f\"" + shownQuery + "\u00a7r\u00a7f\""
                        + ctx + summaryStats,
                "", "", "", ""));

        for (MatchRecord match : res.matches) {
            String badge = match.formatExact
                    ? "\u00a7a[Exact] "
                    : (match.exact ? (res.exactOnly ? "\u00a7a[Exact] " : "\u00a72[Name] ") : "\u00a7e[Part] ");
            // Backward-compatible exact label when server omits format_exact on older responses.
            if (match.exact && !res.exactFormatOnly && !hasFormattingCodes(shownQuery)) {
                badge = "\u00a7a[Exact] ";
            }
            String rowPrefix = "  \u00a78\u2022 " + badge;
            String clickCmd = (match.username != null && !match.username.isEmpty() && !"Unknown".equals(match.username))
                    ? "/infop " + match.username
                    : "";
            String shownItemName = (match.rawDisplayName != null && !match.rawDisplayName.isEmpty())
                    ? match.rawDisplayName : match.displayName;
            String hover = "\u00a7b" + match.username
                    + "\n\u00a77Kit Item: \u00a7f" + match.itemLabel
                    + "\n\u00a77Stored Name: \u00a7f" + shownItemName + "\u00a7r"
                    + "\n\u00a77Kills: \u00a7f" + nf.format(match.kills)
                    + "\u00a77 | Wins: \u00a7f" + nf.format(match.wins)
                    + (clickCmd.isEmpty() ? "" : "\n\u00a7aClick to run " + clickCmd);
            String suffix = "\u00a77 - \u00a7b" + match.itemLabel
                    + "\u00a78 (\u00a7f" + shownItemName + "\u00a7r\u00a78)";
            specs.add(new ChatActionSpec(rowPrefix, formatRankedPlayer(match), clickCmd, hover, suffix));
        }
        return specs;
    }

    private static String formatRankedPlayer(MatchRecord match) {
        if (match.rankDisplay == null || match.rankDisplay.isEmpty()
                || "Default".equalsIgnoreCase(match.rankDisplay)) {
            return "\u00a77" + match.username;
        }
        String tag = HypixelTabStatsRankCache.formatOfficialRankTag(
                match.rankDisplay, match.rankPlusColor, match.monthlyRankColor);
        String color = HypixelTabStatsRankCache.formatOfficialUsernameColor(
                match.rankDisplay, match.monthlyRankColor);
        if (color == null || color.isEmpty()) {
            color = "\u00a77";
        }
        return (tag != null && !tag.isEmpty() ? tag + " " : "") + color + match.username;
    }

    private static CachedLookup getFreshCachedLookup(String normalizedQuery) {
        long now = System.currentTimeMillis();
        synchronized (QUERY_CACHE) {
            CachedLookup entry = QUERY_CACHE.get(normalizedQuery);
            if (entry != null && now - entry.fetchedAtMs <= QUERY_CACHE_TTL_MS) {
                return entry;
            }
            if (entry != null) {
                QUERY_CACHE.remove(normalizedQuery);
            }
            return null;
        }
    }

    private static void putCachedLookup(String normalizedQuery, SearchResponse response) {
        synchronized (QUERY_CACHE) {
            QUERY_CACHE.put(normalizedQuery, new CachedLookup(System.currentTimeMillis(), response));
        }
    }

    private static void scheduleChestDefaultsWarmup() {
        if (loadedChestDefaultNames != null || !chestDefaultsLoadStarted.compareAndSet(false, true)) {
            return;
        }
        Thread loader = new Thread(() -> loadedChestDefaultNames = loadChestDefaultNames(),
                "gomod-chest-rename-defaults");
        loader.setDaemon(true);
        loader.start();
    }

    private static Set<String> getChestDefaultNamesNonBlocking() {
        Set<String> set = loadedChestDefaultNames;
        if (set == null) {
            scheduleChestDefaultsWarmup();
            return Collections.emptySet();
        }
        return set;
    }

    /** Streams only {@code items[].name} from {@code /blitz_chest_items.json} (skipping heavy NBT/tooltips). */
    static Set<String> loadChestDefaultNames() {
        Set<String> result = new HashSet<String>(1024);
        try (InputStream in = NickRenamedItemScanner.class.getResourceAsStream("/blitz_chest_items.json")) {
            if (in == null) {
                return Collections.emptySet();
            }
            try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String topKey = reader.nextName();
                    if ("items".equals(topKey)) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String itemKey = reader.nextName();
                                if ("name".equals(itemKey)) {
                                    String name = reader.nextString();
                                    if (name != null && name.indexOf('\u00a7') >= 0) {
                                        String normalized = normalizeSearchQuery(name);
                                        if (!normalized.isEmpty()) {
                                            result.add(normalized);
                                        }
                                    }
                                } else {
                                    reader.skipValue();
                                }
                            }
                            reader.endObject();
                        }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
        } catch (Throwable ignored) {
        }
        return Collections.unmodifiableSet(result);
    }

    private static String readStream(InputStream in) {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String jsonString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long jsonLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
