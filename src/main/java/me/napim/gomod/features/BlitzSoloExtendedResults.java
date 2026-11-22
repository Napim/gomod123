package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the killers outside Hypixel's top-three Solo Blitz result rows.  Hypixel sends only the top three;
 * the remaining rows are reconstructed from the per-match kill feed kept by {@link KillCounter}.
 */
public final class BlitzSoloExtendedResults {
    private static final long RESULT_BLOCK_TIMEOUT_MS = 10000L;
    /** The 64-glyph aqua bold divider used by Hypixel's Blitz result card. */
    private static final String RESULT_DIVIDER = "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";
    private static final Pattern PLACEMENT_LINE = Pattern.compile("^(?:1st|2nd|3rd)\\s*-");
    private static final Pattern OFFICIAL_RANKED_RESULT = Pattern.compile(
            "^(?:Winner|1st|2nd|3rd)\\s*-\\s*(\\[[^\\]]+\\])\\s*([A-Za-z0-9_]{3,16})(?=\\s|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINNER_KIT_LINE = Pattern.compile(
            "^Winner\\s*-\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{3,16})\\s*\\(([^()]+)\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_AFTER_DASH = Pattern.compile(
            "^\\s*(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{3,16})(?=\\s|$)");
    /** Numeric brackets can be appended player counters, never a Hypixel rank prefix. */
    private static final Pattern NUMERIC_BRACKET_PREFIX = Pattern.compile("^\\[[0-9,]+\\]\\s+");
    private static final Pattern DEATHMATCH_KILL_LINE = Pattern.compile(
            "^([A-Za-z0-9_]{3,16})\\s+(?:was|were)\\s+.+?\\s+by\\s+([A-Za-z0-9_]{3,16})!?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAT_SPEAKER_BEFORE_COLON = Pattern.compile(
            "([A-Za-z0-9_]{3,16})(?:\\s+\\[[^]]+\\])?$");
    /**
     * Chat/font hooks may encounter our already-formatted result rows again after the lobby transition. Retain a
     * bounded set of their exact and formatting-stripped text so those later redraws do not reformat the disguise.
     */
    private static final int PRESERVED_RESULT_TEXT_LIMIT = 1024;
    private static final Map<String, Boolean> PRESERVED_RESULT_TEXT = new ConcurrentHashMap<String, Boolean>();
    private static final ConcurrentLinkedQueue<String> PRESERVED_RESULT_TEXT_ORDER = new ConcurrentLinkedQueue<String>();
    private static final long DEATHMATCH_TELEPORT_TRACK_MS = 20000L;
    private static final long CAGE_COUNTDOWN_DURATION_MS = 15000L;

    private long resultHeaderAtMs;
    private Map<String, Integer> killSnapshot = Collections.emptyMap();
    private Map<String, String> formattedNameSnapshot = Collections.emptyMap();
    private Map<String, String> kitSnapshot = Collections.emptyMap();
    /** Authoritative kit announced by Hypixel's Winner line, keyed by every known player alias. */
    private final Map<String, String> resultAnnouncedKits = new HashMap<String, String>();
    private Map<String, Integer> messageCountSnapshot = Collections.emptyMap();
    private Map<String, List<String>> messageTextSnapshot = Collections.emptyMap();
    private List<String> messageTimelineSnapshot = Collections.emptyList();
    private final List<String> liveMessageTimeline = new ArrayList<String>();
    private Map<String, String> messageDisguiseNameSnapshot = Collections.emptyMap();
    /** Match-end snapshot of remote /disguiser display names for every player printed in the detailed card. */
    private Map<String, String> remoteDisguiseNameSnapshot = Collections.emptyMap();
    private final Map<String, Integer> liveMessageCounts = new HashMap<String, Integer>();
    private final Map<String, List<String>> liveMessageTexts = new HashMap<String, List<String>>();
    private final Map<String, String> liveMessageDisguiseNames = new HashMap<String, String>();
    /** Remote disguise names observed while the match is running, retained if the command expires before results. */
    private final Map<String, String> liveRemoteDisguiseNameSnapshot = new HashMap<String, String>();
    /** Match-scoped rank/name formatting learned directly from Hypixel chat and kill messages. */
    private final Map<String, String> observedFormattedNames = new HashMap<String, String>();
    /** Exact rank/name pairs announced by Hypixel's winner and top-three result rows. */
    private final Map<String, String> officialResultRankNames = new HashMap<String, String>();
    private final Set<String> knownMessagePlayers = new HashSet<String>();
    private List<String> deathmatchPlayersSnapshot = Collections.emptyList();
    private List<String> liveDeathmatchPlayers = Collections.emptyList();
    private BlitzStarMatchSummary.Snapshot starSnapshot = BlitzStarMatchSummary.snapshot();
    private final Set<String> topThreeNamesLower = new HashSet<String>();
    private boolean emitted;
    private List<ResultPlayer> pendingRows;
    private long emitAfterMs;
    private long debugWriteAfterMs;
    private long deathmatchTrackUntilMs;
    private boolean deathmatchReachedThisMatch;
    private boolean deathmatchReachedSnapshot;
    private static volatile boolean resultsDebugArmed;
    private StringBuilder debugCapture;
    private boolean lastDeathmatchPhase;
    private boolean messageTrackingActive;
    private boolean teamsMessageMode;
    private long matchTrackingStartedAtMs;
    private long matchStartedAtMs;
    private long matchEndedAtMs;
    private long localMessageVisibilityEndedAtMs;
    private boolean localEliminationSeen;
    private boolean matchDurationAddsCageCountdown;
    private String matchMapName = "";
    private int matchStartingPlayerCount;
    private int matchMaxPlayerCount;
    private boolean mapPlayerLineEmitted;
    private boolean lastCagePhase;
    private int outsideLiveMatchTicks;

    /** True when text is one of our saved result rows or hover lines and must keep its captured styling. */
    public static boolean isPreservedDisguiseResultText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (PRESERVED_RESULT_TEXT.isEmpty()) {
            return false;
        }
        if (isPreservedDisguiseResultTextExact(text)) {
            return true;
        }
        // The plain form of an unformatted string is the string itself, which the exact lookup above checked.
        // Avoid allocating a stripped copy for the many ordinary labels passed through FontRenderer.
        if (text.indexOf('\u00a7') < 0) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return plain != null && PRESERVED_RESULT_TEXT.containsKey(plain.trim());
    }

    /** Allocation-free check used before the per-frame FontRenderer rewrite prefilter. */
    public static boolean isPreservedDisguiseResultTextExact(String text) {
        return text != null && !text.isEmpty() && PRESERVED_RESULT_TEXT.containsKey(text);
    }

    private static void preserveDisguiseResultText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        preserveDisguiseResultTextKey(text);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain != null) {
            preserveDisguiseResultTextKey(plain.trim());
        }
    }

    private static void preserveDisguiseResultTextKey(String text) {
        if (text == null || text.isEmpty() || PRESERVED_RESULT_TEXT.putIfAbsent(text, Boolean.TRUE) != null) {
            return;
        }
        PRESERVED_RESULT_TEXT_ORDER.add(text);
        while (PRESERVED_RESULT_TEXT.size() > PRESERVED_RESULT_TEXT_LIMIT) {
            String oldest = PRESERVED_RESULT_TEXT_ORDER.poll();
            if (oldest == null) {
                break;
            }
            PRESERVED_RESULT_TEXT.remove(oldest);
        }
    }

    public static void setResultsDebugArmed(boolean armed) {
        resultsDebugArmed = armed;
    }

    @SubscribeEvent
    public void onChat(Receive event) {
        if (!(event.getPacket() instanceof S02PacketChat)) {
            return;
        }
        IChatComponent component = ((S02PacketChat) event.getPacket()).getChatComponent();
        if (component == null) {
            return;
        }
        String message = component.getUnformattedText();
        if (message == null) {
            return;
        }
        // Some Hypixel chat components put legacy section-sign color codes in
        // their literal text nodes. getUnformattedText() only removes component
        // styles, so names otherwise arrive as e.g. "Josh\u00a7f" and fail the
        // strict Minecraft-username parser.
        String withoutLegacyCodes = EnumChatFormatting.getTextWithoutFormattingCodes(message);
        if (withoutLegacyCodes != null) {
            message = withoutLegacyCodes;
        }
        message = message.replaceAll("\\s+", " ").trim();
        long now = System.currentTimeMillis();

        String messageLower = message.toLowerCase(Locale.ROOT);
        if (messageLower.startsWith("the game starts in ") && !messageTrackingActive) {
            resetLiveDeathmatchTrackingForNewMatch();
        }
        if (messageLower.startsWith("deathmatch started")) {
            deathmatchReachedThisMatch = true;
            deathmatchTrackUntilMs = now + DEATHMATCH_TELEPORT_TRACK_MS;
            mergeCurrentWorldPlayers(Minecraft.getMinecraft());
        }
        if (deathmatchTrackUntilMs > 0L && now < deathmatchTrackUntilMs) {
            addDeathmatchPlayersFromKillLine(message);
        }
        if (messageTrackingActive) {
            observeFormattedPlayerNames(component, message);
            // Store the same visible sender text the chat renderer will show. The count map continues to use the
            // server's source IGN, while this display snapshot freezes local /disguise and peer /disguiser names
            // at message time (so later disguise expiry cannot restore the source IGN in the hover).
            IChatComponent displayComponent = LocalDisguise.rewriteIncomingChatComponentForDisplay(component);
            displayComponent = GomodPeerDisguiseDisplay.rewriteIncomingChat(displayComponent);
            String displayMessage = displayComponent == null ? component.getFormattedText()
                    : displayComponent.getFormattedText();
            trackPlayerMessage(message, displayMessage);
        }

        if ("Blitz Survival Games".equalsIgnoreCase(message)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)
                    || !GameContext.isBlitzGameOverSidebar(mc)) {
                return;
            }
            // Snapshot here, before the GAME OVER cleanup in KillCounter can discard match kills.
            resultHeaderAtMs = now;
            messageTrackingActive = false;
            killSnapshot = KillCounter.getAllKillCounts();
            messageCountSnapshot = new HashMap<String, Integer>(liveMessageCounts);
            messageTextSnapshot = copyMessageTexts(liveMessageTexts);
            messageTimelineSnapshot = new ArrayList<String>(liveMessageTimeline);
            messageDisguiseNameSnapshot = snapshotMessageDisguiseNames(messageCountSnapshot);
            if (matchEndedAtMs == 0L) {
                matchEndedAtMs = now;
            }
            ensureMatchDurationAnchorForResults(now);
            Map<String, Integer> namesToFormat = new HashMap<String, Integer>(killSnapshot);
            for (String speaker : messageCountSnapshot.keySet()) {
                if (speaker != null && !namesToFormat.containsKey(speaker)) {
                    namesToFormat.put(speaker, 0);
                }
            }
            formattedNameSnapshot = snapshotFormattedNames(namesToFormat);
            formattedNameSnapshot.putAll(observedFormattedNames);
            kitSnapshot = snapshotKits(killSnapshot);
            resultAnnouncedKits.clear();
            deathmatchReachedSnapshot = deathmatchReachedThisMatch;
            if (deathmatchReachedSnapshot) {
                mergeCurrentWorldPlayers(mc);
                deathmatchPlayersSnapshot = new ArrayList<String>(liveDeathmatchPlayers);
            } else {
                deathmatchPlayersSnapshot = Collections.emptyList();
            }
            starSnapshot = BlitzStarMatchSummary.snapshot();
            Set<String> resultNames = new HashSet<String>(namesToFormat.keySet());
            resultNames.addAll(deathmatchPlayersSnapshot);
            if (starSnapshot.finder != null && !starSnapshot.finder.trim().isEmpty()) {
                resultNames.add(starSnapshot.finder.trim());
            }
            if (starSnapshot.user != null && !starSnapshot.user.trim().isEmpty()) {
                resultNames.add(starSnapshot.user.trim());
            }
            Map<String, String> resultDisguises =
                    new HashMap<String, String>(liveRemoteDisguiseNameSnapshot);
            resultDisguises.putAll(snapshotRemoteDisguiseNames(resultNames));
            remoteDisguiseNameSnapshot = resultDisguises;
            topThreeNamesLower.clear();
            emitted = false;
            pendingRows = null;
            mapPlayerLineEmitted = true;
            debugWriteAfterMs = 0L;
            if (resultsDebugArmed && debugCapture == null) {
                debugCapture = new StringBuilder("gomod Blitz results chat capture\n\n");
            }
            appendMessageCountDebug("SNAPSHOT counts=" + liveMessageCounts + " teamsMode=" + teamsMessageMode);
            appendDebugLine(component);
            event.setCancelled(true);
            final IChatComponent resultHeaderComponent = component.createCopy();
            final Minecraft resultMinecraft = mc;
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    resultMinecraft.thePlayer.addChatMessage(resultHeaderComponent);
                    printResultHeaderDetails(resultMinecraft);
                }
            });
            return;
        }

        if (resultHeaderAtMs == 0L) {
            return;
        }
        if (now - resultHeaderAtMs > RESULT_BLOCK_TIMEOUT_MS) {
            clearResultBlock();
            return;
        }

        appendDebugLine(component);

        recordWinnerKit(message, component.getFormattedText());

        if (ConfigManager.isBlitzExtendedResultsEnabled() && pendingRows != null && message.isEmpty()) {
            // Hypixel sends one blank chat packet between the 3rd-place row and the closing divider.  Use that
            // exact slot for our extra rows, and cancel the blank so 4th follows directly after 3rd.
            event.setCancelled(true);
            final List<ResultPlayer> rows = pendingRows;
            pendingRows = null;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        printExtraRows(rows);
                    }
                });
            }
            return;
        }

        Matcher placement = PLACEMENT_LINE.matcher(message);
        if (!placement.find()) {
            return;
        }

        String name = extractPlacementName(message.substring(placement.end()));
        if (name != null) {
            addTopThreeNameAliases(name);
            rememberRemoteDisguiseName(name);
            // Hypixel's own top-three rows contain authoritative rank/name formatting.
            // They arrive after our initial result snapshot but before the extra rows
            // and deathmatch list are printed, so fold them into the live snapshot.
            String placementFormattedName = extractFormattedChatName(component.getFormattedText(), name);
            if (placementFormattedName != null) {
                String key = name.toLowerCase(Locale.ROOT);
                observedFormattedNames.put(key, placementFormattedName);
                formattedNameSnapshot.put(key, placementFormattedName);
            }
            captureOfficialResultRankName(name, message, component.getFormattedText());
        }

        if (message.startsWith("3rd") && !emitted) {
            emitted = true;
            if (ConfigManager.isBlitzExtendedResultsEnabled()) {
                pendingRows = buildExtraRows();
                if (!ConfigManager.isBlitzExtendedResultsMorePositionsEnabled()) {
                    pendingRows = Collections.emptyList();
                }
                if (pendingRows.isEmpty() && !shouldPrintStarSummary() && !shouldPrintDeathmatchPlayers()
                        && !shouldPrintMessageCounts()) {
                    pendingRows = null;
                }
            }
            // The bottom separator is sent after the third row; capture a little longer before writing the file.
            if (debugCapture != null) {
                debugWriteAfterMs = now + 500L;
            }
        }
    }

    @SubscribeEvent
    public void onTick(net.weavemc.loader.api.event.TickEvent event) {
        long now = System.currentTimeMillis();
        Minecraft mc = Minecraft.getMinecraft();
        boolean cageNow = mc != null && GameContext.isBlitzCagePhaseSidebar(mc);
        boolean activeNow = mc != null && GameContext.isBlitzGameActive(mc);
        boolean deathmatchNow = mc != null && GameContext.isBlitzDeathmatchPhase(mc);
        boolean gameOverNow = mc != null && GameContext.isBlitzGameOverSidebar(mc);
        boolean matchPregameNow = mc != null && GameContext.isBlitzMatchMapPregameSidebar(mc);
        boolean cagesOpenedNow = lastCagePhase && !cageNow && (activeNow || !matchPregameNow);
        if (cageNow && !lastCagePhase) {
            resetLiveDeathmatchTrackingForNewMatch();
        }
        if (cagesOpenedNow) {
            if (!messageTrackingActive) {
                resetLiveDeathmatchTrackingForNewMatch();
            }
            anchorMatchDurationAtCagesOpened(now);
        }
        lastCagePhase = cageNow;
        boolean liveMatchContext = cageNow || activeNow || deathmatchNow || gameOverNow;
        if (messageTrackingActive && resultHeaderAtMs == 0L && !liveMatchContext) {
            outsideLiveMatchTicks++;
            if (outsideLiveMatchTicks >= 40) {
                clearResultBlock();
            }
        } else {
            outsideLiveMatchTicks = 0;
        }
        if (resultHeaderAtMs == 0L) {
            String detectedMap = MapQueuer.getCurrentMap();
            if (detectedMap != null && !detectedMap.trim().isEmpty()) {
                matchMapName = detectedMap.trim();
            }
        }
        // Packet listeners can attach after the countdown (or another mod can consume its
        // chat packet). The live Blitz sidebar is a reliable fallback for starting the
        // per-match message tracker.
        if (!messageTrackingActive && resultHeaderAtMs == 0L
                && mc != null && (cageNow || activeNow) && !gameOverNow) {
            resetLiveDeathmatchTrackingForNewMatch();
            if (activeNow && !cageNow) {
                anchorMatchDurationAtCagesOpened(now);
            }
        }
        if (messageTrackingActive && matchStartedAtMs == 0L && activeNow && !cageNow && !gameOverNow) {
            anchorMatchDurationAtCagesOpened(now);
        }
        if (messageTrackingActive) {
            if (BlitzTeamsDetector.isBlitzTeamsGame(mc)) {
                teamsMessageMode = true;
            }
            rememberCurrentMessagePlayers(mc);
            captureLocalMessageVisibilityEndIfNeeded(mc, now);
            if (matchEndedAtMs == 0L && GameContext.isBlitzGameOverSidebar(mc)) {
                matchEndedAtMs = now;
            }
        }
        snapshotDeathmatchPlayersIfNeeded(now);
        if (debugCapture != null && debugWriteAfterMs > 0L && now >= debugWriteAfterMs) {
            writeDebugCapture();
        }
    }

    private void snapshotDeathmatchPlayersIfNeeded(long now) {
        Minecraft mc = Minecraft.getMinecraft();
        // The sidebar's "Game End" countdown also appears before the final
        // Deathmatch teleport. Only Hypixel's explicit start message proves we
        // actually entered Deathmatch; the sidebar is useful only after that.
        boolean deathmatchSidebarNow = mc != null && GameContext.isOnHypixel(mc)
                && GameContext.isBlitzDeathmatchPhase(mc);
        boolean deathmatchNow = deathmatchReachedThisMatch && deathmatchSidebarNow;
        if (deathmatchNow || now < deathmatchTrackUntilMs) {
            mergeCurrentWorldPlayers(mc);
        }
        if (!deathmatchNow && lastDeathmatchPhase && resultHeaderAtMs == 0L && now >= deathmatchTrackUntilMs) {
            liveDeathmatchPlayers = Collections.emptyList();
        }
        lastDeathmatchPhase = deathmatchNow;
    }

    private void captureLocalMessageVisibilityEndIfNeeded(Minecraft mc, long now) {
        if (localEliminationSeen || mc == null || !KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc)) {
            return;
        }
        localEliminationSeen = true;
        // After our death, more than one surviving player means we finished 3rd
        // or earlier. Spectator chat no longer exposes normal game messages then.
        if (countLikelyAlivePlayers(mc) > 1) {
            localMessageVisibilityEndedAtMs = now;
        }
    }

    private static int countLikelyAlivePlayers(Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) return 0;
        int alive = 0;
        Set<String> seen = new HashSet<String>();
        for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) continue;
            String name = info.getGameProfile().getName();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            if (info.getGameType() == net.minecraft.world.WorldSettings.GameType.SPECTATOR) continue;
            if (!KillCounter.isPlayerDead(name)) alive++;
        }
        return alive;
    }

    private static int countCurrentPlayers(Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) return 0;
        Set<String> names = new HashSet<String>();
        for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null || info.getGameProfile().getName() == null) continue;
            String name = info.getGameProfile().getName().trim();
            if (name.matches("[A-Za-z0-9_]{3,16}")) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names.size();
    }

    private static Map<String, List<String>> copyMessageTexts(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new HashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        return copy;
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    /** Match-relative timestamp shown before a saved deathmatch chat line. */
    private static String formatMessageElapsed(long elapsedMs) {
        return formatElapsed(elapsedMs);
    }

    private List<ResultPlayer> buildExtraRows() {
        List<ResultPlayer> rows = new ArrayList<ResultPlayer>();
        Set<String> emittedAliasesLower = new HashSet<String>();
        for (Map.Entry<String, Integer> entry : killSnapshot.entrySet()) {
            String name = entry.getKey();
            int kills = entry.getValue() == null ? 0 : entry.getValue();
            if (name == null || name.trim().isEmpty() || kills <= 0) {
                continue;
            }
            List<String> aliases = KillCounter.getLookupNamesForResults(name);
            if (containsAnyLower(topThreeNamesLower, aliases) || containsAnyLower(emittedAliasesLower, aliases)) {
                continue;
            }
            addAllLower(emittedAliasesLower, aliases);
            rows.add(new ResultPlayer(name, kills, formattedNameSnapshot.get(name.toLowerCase(Locale.ROOT)),
                    findResultKit(name)));
        }
        Collections.sort(rows, new Comparator<ResultPlayer>() {
            @Override
            public int compare(ResultPlayer a, ResultPlayer b) {
                int killCompare = Integer.compare(b.kills, a.kills);
                return killCompare != 0 ? killCompare : a.name.compareToIgnoreCase(b.name);
            }
        });
        return rows;
    }

    private void addTopThreeNameAliases(String name) {
        List<String> aliases = KillCounter.getLookupNamesForResults(name);
        if (aliases.isEmpty()) {
            topThreeNamesLower.add(name.toLowerCase(Locale.ROOT));
            return;
        }
        addAllLower(topThreeNamesLower, aliases);
    }

    private void recordWinnerKit(String message, String formattedMessage) {
        if (message == null) {
            return;
        }
        Matcher winner = WINNER_KIT_LINE.matcher(message);
        if (!winner.matches()) {
            return;
        }
        String name = winner.group(1) == null ? "" : winner.group(1).trim();
        String kit = winner.group(2) == null ? "" : winner.group(2).trim();
        if (name.isEmpty() || kit.isEmpty()) {
            return;
        }
        String formattedName = extractFormattedChatName(formattedMessage, name);
        if (isUsefulRankFormattedName(name, formattedName)) {
            String key = name.toLowerCase(Locale.ROOT);
            observedFormattedNames.put(key, formattedName);
            if (formattedNameSnapshot != null) {
                formattedNameSnapshot.put(key, formattedName);
            }
        }
        captureOfficialResultRankName(name, message, formattedMessage);
        List<String> aliases = KillCounter.getLookupNamesForResults(name);
        resultAnnouncedKits.put(name.toLowerCase(Locale.ROOT), kit);
        for (String alias : aliases) {
            if (alias != null && !alias.trim().isEmpty()) {
                resultAnnouncedKits.put(alias.trim().toLowerCase(Locale.ROOT), kit);
            }
        }
    }

    private String findResultKit(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        List<String> aliases = KillCounter.getLookupNamesForResults(playerName);
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String kit = resultAnnouncedKits.get(alias.trim().toLowerCase(Locale.ROOT));
            if (kit != null && !kit.trim().isEmpty()) {
                return kit;
            }
        }
        String direct = resultAnnouncedKits.get(playerName.trim().toLowerCase(Locale.ROOT));
        if (direct != null && !direct.trim().isEmpty()) {
            return direct;
        }
        for (String alias : aliases) {
            if (alias == null) {
                continue;
            }
            String kit = kitSnapshot.get(alias.trim().toLowerCase(Locale.ROOT));
            if (kit != null && !kit.trim().isEmpty()) {
                return kit;
            }
        }
        return kitSnapshot.get(playerName.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsAnyLower(Set<String> haystackLower, List<String> aliases) {
        if (haystackLower == null || haystackLower.isEmpty() || aliases == null || aliases.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (alias != null && haystackLower.contains(alias.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void addAllLower(Set<String> targetLower, List<String> aliases) {
        if (targetLower == null || aliases == null) {
            return;
        }
        for (String alias : aliases) {
            if (alias != null && !alias.trim().isEmpty()) {
                targetLower.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    private void printExtraRows(List<ResultPlayer> rows) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        int placement = 4;
        if (ConfigManager.isBlitzExtendedResultsMorePositionsEnabled()) {
            for (ResultPlayer row : rows) {
                String contents = EnumChatFormatting.YELLOW.toString() + EnumChatFormatting.BOLD + ordinal(placement)
                        + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + " - "
                        + formatDeathmatchPlayerName(row.name)
                        + formatKitSuffix(row.kitName)
                        + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + " - "
                        + row.kills + (row.kills == 1 ? " Kill" : " Kills");
                // Hypixel's result card begins each centered row with a white spacing component, then applies the
                // yellow/bold placement style.  Center in pixels, not character count, so coloured ranks still line up.
                String line = EnumChatFormatting.WHITE + centeredLeadingSpaces(contents) + contents;
                preserveDisguiseResultText(line);
                mc.thePlayer.addChatMessage(new ChatComponentText(line));
                placement++;
            }
        }
        boolean hasSupplementalSection = shouldPrintStarSummary() || shouldPrintDeathmatchPlayers()
                || shouldPrintMessageCounts();
        // We cancel Hypixel's original blank line after 3rd place to insert our
        // rows. Always restore exactly one separator before the first summary
        // section, even when there were no reconstructed 4th+ positions.
        if (hasSupplementalSection) {
            mc.thePlayer.addChatMessage(new ChatComponentText(""));
        }
        boolean printedSupplementalSection = false;
        if (shouldPrintStarSummary()) {
            printBlitzStarSummaryRows(mc);
            printedSupplementalSection = true;
        }
        if (shouldPrintDeathmatchPlayers()) {
            if (printedSupplementalSection) {
                mc.thePlayer.addChatMessage(new ChatComponentText(""));
            }
            printDeathmatchPlayersRow(mc);
            printedSupplementalSection = true;
        }
        if (shouldPrintMessageCounts()) {
            if (printedSupplementalSection) {
                mc.thePlayer.addChatMessage(new ChatComponentText(""));
            }
            printMessageCountLeaderboard(mc);
        }
        mc.thePlayer.addChatMessage(new ChatComponentText(""));
    }

    private boolean shouldPrintStarSummary() {
        return ConfigManager.isBlitzExtendedResultsStarInfoEnabled() && hasUsefulStarSummary();
    }

    private boolean shouldPrintDeathmatchPlayers() {
        return ConfigManager.isBlitzExtendedResultsDeathmatchPlayersEnabled()
                && deathmatchReachedSnapshot
                && deathmatchPlayersSnapshot != null && !deathmatchPlayersSnapshot.isEmpty();
    }

    private boolean shouldPrintMessageCounts() {
        return ConfigManager.isBlitzExtendedResultsMessageCountEnabled()
                && messageCountSnapshot != null && !messageCountSnapshot.isEmpty();
    }

    private boolean shouldPrintGameDuration() {
        return ConfigManager.isBlitzExtendedResultsGameDurationEnabled()
                && matchStartedAtMs > 0L && matchEndedAtMs >= matchStartedAtMs;
    }

    private long displayedMatchDurationMs() {
        long elapsed = Math.max(0L, matchEndedAtMs - matchStartedAtMs);
        return matchDurationAddsCageCountdown ? elapsed + CAGE_COUNTDOWN_DURATION_MS : elapsed;
    }

    private void printBlitzStarSummaryRows(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || !hasUsefulStarSummary()) {
            return;
        }
        String finder = starSnapshot.finder == null ? "" : starSnapshot.finder.trim();
        String user = starSnapshot.user == null ? "" : starSnapshot.user.trim();
        String star = starSnapshot.star == null ? "" : starSnapshot.star.trim();
        if (!finder.isEmpty()) {
            printCenteredLine(mc, formatStarSummaryLine("finder", finder,
                    !user.isEmpty() && samePlayerByAlias(finder, user) ? star : ""));
        }
        if (!user.isEmpty() && (finder.isEmpty() || !samePlayerByAlias(finder, user))) {
            printCenteredLine(mc, formatStarSummaryLine("user", user, star));
        }
    }

    private static void printCenteredLine(Minecraft mc, String contents) {
        String line = EnumChatFormatting.WHITE + centeredLeadingSpaces(contents) + contents;
        preserveDisguiseResultText(line);
        mc.thePlayer.addChatMessage(new ChatComponentText(line));
    }

    private void printResultHeaderDetails(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || !ConfigManager.isBlitzExtendedResultsEnabled()) return;
        boolean showMap = ConfigManager.isBlitzExtendedResultsMapPlayerCountEnabled()
                && matchMapName != null && !matchMapName.isEmpty();
        boolean showDuration = shouldPrintGameDuration();
        if (!showMap && !showDuration) return;

        // Keep Hypixel's visual gap below the title, then place both details as
        // one compact block. Its incoming blank packet supplies the gap below.
        mc.thePlayer.addChatMessage(new ChatComponentText(""));
        if (showMap) {
            String count = matchStartingPlayerCount > 0
                    ? " (" + matchStartingPlayerCount + "/" + matchMaxPlayerCount + ")" : "";
            String mapLine = EnumChatFormatting.GREEN + matchMapName + count;
            if (showDuration) {
                mapLine += EnumChatFormatting.GRAY + " - "
                        + EnumChatFormatting.WHITE.toString() + EnumChatFormatting.BOLD
                        + formatElapsed(displayedMatchDurationMs())
                        + EnumChatFormatting.RESET;
            }
            printCenteredLine(mc, mapLine);
        } else if (showDuration) {
            printCenteredLine(mc, EnumChatFormatting.DARK_GREEN + "Game duration: "
                    + EnumChatFormatting.WHITE + formatElapsed(displayedMatchDurationMs()));
        }
    }

    private void printDeathmatchPlayersRow(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || deathmatchPlayersSnapshot == null || deathmatchPlayersSnapshot.isEmpty()) {
            return;
        }
        printCenteredLine(mc, EnumChatFormatting.DARK_GREEN + "Deathmatch players:");
        int maxLineWidth = mc.fontRendererObj == null
                ? Integer.MAX_VALUE
                : Math.max(40, mc.fontRendererObj.getStringWidth(RESULT_DIVIDER) - 12);
        String separator = EnumChatFormatting.GRAY + ", ";
        StringBuilder contents = new StringBuilder();
        for (String playerName : deathmatchPlayersSnapshot) {
            String formattedName = formatDeathmatchPlayerName(playerName);
            if (formattedName == null || formattedName.isEmpty()) {
                continue;
            }
            String candidate = contents.length() == 0
                    ? formattedName
                    : contents.toString() + separator + formattedName;
            if (contents.length() > 0 && mc.fontRendererObj != null
                    && mc.fontRendererObj.getStringWidth(candidate) > maxLineWidth) {
                printCenteredLine(mc, contents.toString());
                contents.setLength(0);
                contents.append(formattedName);
            } else {
                if (contents.length() > 0) {
                    contents.append(separator);
                }
                contents.append(formattedName);
            }
        }
        if (contents.length() > 0) {
            printCenteredLine(mc, contents.toString());
        }
    }

    private void printMessageCountLeaderboard(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        List<MessageCountRow> rows = buildMessageCountRows();
        if (rows.isEmpty()) {
            return;
        }
        String interval = "";
        if (localMessageVisibilityEndedAtMs > matchStartedAtMs) {
            interval = " (00:00-" + formatElapsed(localMessageVisibilityEndedAtMs - matchStartedAtMs) + ")";
        }
        String heading = EnumChatFormatting.DARK_GREEN + "Messages count" + interval + ":";
        ChatComponentText title = new ChatComponentText(EnumChatFormatting.WHITE + centeredLeadingSpaces(heading) + heading);
        String allMessages = joinMessageHover(messageTimelineSnapshot);
        if (!allMessages.isEmpty()) {
            title.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText(allMessages)));
        }
        preserveDisguiseResultText(title.getFormattedText());
        mc.thePlayer.addChatMessage(title);
        for (MessageCountRow row : rows) {
            String formattedName = formatMessageCountPlayerName(row.name);
            String contents = formattedName
                    + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + " - "
                    + row.count + (row.count == 1 ? " Message" : " Messages");
            ChatComponentText leaderboardLine = new ChatComponentText(
                    EnumChatFormatting.WHITE + centeredLeadingSpaces(contents) + contents);
            preserveDisguiseResultText(leaderboardLine.getFormattedText());
            String hover = buildMessageHover(row.name);
            if (!hover.isEmpty()) {
                preserveDisguiseResultText(hover);
                leaderboardLine.getChatStyle().setChatHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hover)));
            }
            mc.thePlayer.addChatMessage(leaderboardLine);
        }
    }

    private String buildMessageHover(String playerName) {
        List<String> messages = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : messageTextSnapshot.entrySet()) {
            if (samePlayerByAlias(entry.getKey(), playerName)) {
                String frozenDisguiseDisplay = messageDisguiseDisplayForSource(entry.getKey());
                for (String message : entry.getValue()) {
                    messages.add(frozenDisguiseDisplay == null ? message
                            : GomodPeerDisguiseDisplay.rewriteHistoricalChatSpeaker(
                                    message, entry.getKey(), frozenDisguiseDisplay));
                }
            }
        }
        return joinMessageHover(messages);
    }

    private static String joinMessageHover(List<String> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder hover = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) hover.append("\n\n");
            // Reset around every captured chat line so one player's rank/style codes cannot tint later timestamps
            // or turn the complete tooltip into the default white style.
            String message = messages.get(i);
            preserveDisguiseResultText(message);
            hover.append(message).append(EnumChatFormatting.RESET);
        }
        String value = hover.toString();
        preserveDisguiseResultText(value);
        return value;
    }

    /** Prefer the exact speaker key, then its nick/denick aliases in the frozen match snapshot. */
    private String messageDisguiseDisplayForSource(String sourceName) {
        if (sourceName == null || sourceName.isEmpty() || messageDisguiseNameSnapshot == null) return null;
        String exact = messageDisguiseNameSnapshot.get(sourceName);
        if (exact != null && !exact.isEmpty()) return exact;
        for (Map.Entry<String, String> entry : messageDisguiseNameSnapshot.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()
                    && samePlayerByAlias(entry.getKey(), sourceName)) return entry.getValue();
        }
        return null;
    }

    private String formatMessageCountPlayerName(String playerName) {
        if (playerName != null) {
            for (Map.Entry<String, String> entry : messageDisguiseNameSnapshot.entrySet()) {
                if (samePlayerByAlias(entry.getKey(), playerName)
                        && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    // Freeze the disguise name at message time, but let an authoritative Hypixel result row
                    // restore its rank when the frozen value only carried a gray username.
                    return preferResultRankForSameName(playerName, entry.getValue());
                }
            }
        }
        LocalDisguise.RemoteDisguiseProfile remote =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(playerName);
        if (remote != null) {
            String displayed = LocalDisguise.formatRemoteDisguiseChatName(remote);
            if (displayed != null && !displayed.isEmpty()) {
                return preferResultRankForSameName(playerName, displayed);
            }
        }
        return formatDeathmatchPlayerName(playerName);
    }

    private String preferResultRankForSameName(String playerName, String formattedName) {
        if (playerName == null || formattedName == null || formattedName.isEmpty()) {
            return formattedName;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formattedName);
        if (plain == null || !plain.trim().equalsIgnoreCase(playerName.trim())) {
            return formattedName;
        }
        String ranked = formatDeathmatchPlayerName(playerName);
        return isUsefulRankFormattedName(playerName, ranked) ? ranked : formattedName;
    }

    private List<MessageCountRow> buildMessageCountRows() {
        List<MessageCountRow> rows = new ArrayList<MessageCountRow>();
        List<Map.Entry<String, Integer>> entries = new ArrayList<Map.Entry<String, Integer>>(
                messageCountSnapshot.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                String an = a.getKey() == null ? "" : a.getKey();
                String bn = b.getKey() == null ? "" : b.getKey();
                return an.compareToIgnoreCase(bn);
            }
        });
        for (Map.Entry<String, Integer> entry : entries) {
            String name = entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (name == null || name.trim().isEmpty() || count <= 0) {
                continue;
            }
            MessageCountRow aliasRow = null;
            for (MessageCountRow row : rows) {
                if (samePlayerByAlias(row.name, name)) {
                    aliasRow = row;
                    break;
                }
            }
            if (aliasRow == null) {
                rows.add(new MessageCountRow(name.trim(), count));
            } else {
                aliasRow.count += count;
            }
        }
        Collections.sort(rows, new Comparator<MessageCountRow>() {
            @Override
            public int compare(MessageCountRow a, MessageCountRow b) {
                int countCompare = Integer.compare(b.count, a.count);
                return countCompare != 0 ? countCompare : a.name.compareToIgnoreCase(b.name);
            }
        });
        return rows;
    }

    private String formatDeathmatchPlayerName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return "";
        }
        String trimmed = playerName.trim();
        // The local player's live rank caches may be white/rank-colored even while disguised as an unranked target.
        // Resolve either the real IGN or already-rewritten alias from the disguise's own rank snapshot first so
        // cache/packet ordering cannot intermittently color the same local name differently between matches.
        String disguiseFormatted = LocalDisguise.formatDisguiseNameForMatchResults(trimmed);
        boolean isLocalSourceName = LocalDisguise.fastEnabledFlag
                && LocalDisguise.fastRealNameText != null
                && LocalDisguise.fastRealNameText.equalsIgnoreCase(trimmed);
        if (disguiseFormatted != null && isLocalSourceName) {
            return disguiseFormatted;
        }
        String remoteDisguiseName = findRemoteDisguiseNameSnapshot(trimmed);
        if (remoteDisguiseName != null) {
            return remoteDisguiseName;
        }
        // Also handle a player first encountered after the GAME OVER snapshot (for example, a late result
        // placement line). The retained match snapshot above takes precedence when it exists, so expiry or a
        // lobby transition cannot make already-captured result rows revert to the source IGN.
        LocalDisguise.RemoteDisguiseProfile remoteTarget =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(trimmed);
        if (remoteTarget != null) {
            String remoteFormatted = LocalDisguise.formatRemoteDisguiseChatName(remoteTarget);
            if (remoteFormatted != null && !remoteFormatted.isEmpty()) {
                return remoteFormatted;
            }
        }
        // Use the exact result-card rank for nicknames even when TAB/API lookup cannot resolve them.
        String officialResultName = officialResultRankNames.get(trimmed.toLowerCase(Locale.ROOT));
        if (officialResultName != null) {
            String normalizedOfficialName = normalizeRankFormattedName(trimmed, officialResultName);
            if (normalizedOfficialName != null) {
                return rewriteLocalDisguiseNameForResults(normalizedOfficialName, trimmed);
            }
        }
        // Result placement and winner lines arrive after the GAME OVER snapshot and are the strongest evidence for
        // a nick's displayed rank. Prefer them over API/TAB caches that may hold only spectator-gray name text.
        String formatted = normalizeRankFormattedName(trimmed,
                observedFormattedNames.get(trimmed.toLowerCase(Locale.ROOT)));
        if (formatted != null) {
            return rewriteLocalDisguiseNameForResults(formatted, trimmed);
        }
        formatted = normalizeRankFormattedName(trimmed,
                formattedNameSnapshot.get(trimmed.toLowerCase(Locale.ROOT)));
        if (formatted != null) {
            return rewriteLocalDisguiseNameForResults(formatted, trimmed);
        }
        formatted = findRankFormattedNameByAlias(trimmed, observedFormattedNames);
        if (formatted == null) {
            formatted = findRankFormattedNameByAlias(trimmed, formattedNameSnapshot);
        }
        if (formatted != null) {
            return rewriteLocalDisguiseNameForResults(formatted, trimmed);
        }
        if (disguiseFormatted != null) {
            return disguiseFormatted;
        }
        // TAB changes eliminated players' name color to spectator blue. Prefer the rank cache, which retains their
        // real rank color, then rebuild any server rank prefix so the blue team color cannot leak into this row.
        formatted = normalizeRankFormattedName(trimmed, getApiCachedFormattedName(trimmed));
        if (formatted != null) {
            return rewriteLocalDisguiseNameForResults(formatted, trimmed);
        }
        formatted = normalizeRankFormattedName(trimmed, KillCounter.getServerFormattedNameForResults(trimmed));
        if (formatted == null) {
            // TAB colors can be spectator/death colors, so don't use them here. The match-scoped chat snapshot
            // is different: Hypixel's kill messages give each name its own server-formatted username color even
            // when they omit the rank tag. Keep that color for the result row when we captured an exact bare name.
            formatted = findObservedBareNameColorByAlias(trimmed);
        }
        return rewriteLocalDisguiseNameForResults(
                formatted == null ? EnumChatFormatting.GRAY + trimmed + EnumChatFormatting.RESET : formatted,
                trimmed);
    }

    private String findRemoteDisguiseNameSnapshot(String playerName) {
        if (playerName == null || remoteDisguiseNameSnapshot == null || remoteDisguiseNameSnapshot.isEmpty()) {
            return null;
        }
        String direct = remoteDisguiseNameSnapshot.get(playerName.trim().toLowerCase(Locale.ROOT));
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        for (Map.Entry<String, String> entry : remoteDisguiseNameSnapshot.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()
                    && samePlayerByAlias(entry.getKey(), playerName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> snapshotRemoteDisguiseNames(Set<String> sourceNames) {
        Map<String, String> snapshot = new HashMap<String, String>();
        if (sourceNames == null || sourceNames.isEmpty()) {
            return snapshot;
        }
        for (String sourceName : sourceNames) {
            if (sourceName == null || sourceName.trim().isEmpty()) {
                continue;
            }
            LocalDisguise.RemoteDisguiseProfile target =
                    RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
            if (target == null) {
                continue;
            }
            String displayName = LocalDisguise.formatRemoteDisguiseChatName(target);
            if (displayName != null && !displayName.isEmpty()) {
                snapshot.put(sourceName.trim().toLowerCase(Locale.ROOT), displayName);
            }
        }
        return snapshot;
    }

    private void rememberRemoteDisguiseName(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return;
        }
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        if (target == null) {
            return;
        }
        String displayName = LocalDisguise.formatRemoteDisguiseChatName(target);
        if (displayName != null && !displayName.isEmpty()) {
            liveRemoteDisguiseNameSnapshot.put(sourceName.trim().toLowerCase(Locale.ROOT), displayName);
        }
    }

    private String findObservedBareNameColorByAlias(String playerName) {
        if (playerName == null || playerName.trim().isEmpty() || observedFormattedNames == null
                || observedFormattedNames.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : observedFormattedNames.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !samePlayerByAlias(entry.getKey(), playerName)) {
                continue;
            }
            String visible = EnumChatFormatting.getTextWithoutFormattingCodes(entry.getValue());
            if (visible == null) {
                continue;
            }
            String observedName = visible.trim();
            // Only accept a captured color attached to the bare username. Prefix-bearing or larger chat fragments
            // are handled by the rank formatter above and must not leak unrelated formatting into the row.
            if (!observedName.equalsIgnoreCase(entry.getKey().trim())
                    && !observedName.equalsIgnoreCase(playerName.trim())) {
                continue;
            }
            int nameStart = formattedIndexForVisibleIndex(entry.getValue(), 0);
            if (nameStart < 0 || nameStart > entry.getValue().length()) {
                continue;
            }
            String nameColor = effectiveColor(entry.getValue().substring(0, nameStart));
            return nameColor + playerName.trim() + EnumChatFormatting.RESET;
        }
        return null;
    }

    private String findRankFormattedNameByAlias(String playerName, Map<String, String> formattedNames) {
        if (playerName == null || playerName.trim().isEmpty() || formattedNames == null || formattedNames.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : formattedNames.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !samePlayerByAlias(entry.getKey(), playerName)) {
                continue;
            }
            String normalized = normalizeRankFormattedName(playerName, entry.getValue());
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * The Deathmatch snapshot is built from the server/world profile, so the local player enters it under the real
     * IGN. Rewrite only that display token here; the snapshot and all match bookkeeping must keep the real name.
     */
    private static String rewriteLocalDisguiseNameForResults(String formatted, String sourceName) {
        if (formatted == null || formatted.isEmpty() || sourceName == null || sourceName.trim().isEmpty()
                || !LocalDisguise.fastEnabledFlag) {
            return formatted;
        }

        String realName = LocalDisguise.fastRealNameText;
        String targetName = LocalDisguise.fastTargetNameText;
        String trimmedSourceName = sourceName.trim();
        if (realName == null || realName.trim().isEmpty() || targetName == null || targetName.trim().isEmpty()
                || !realName.equalsIgnoreCase(trimmedSourceName)
                || realName.equalsIgnoreCase(targetName)) {
            return formatted;
        }

        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null) {
            plain = formatted;
        }
        Pattern token = Pattern.compile("(?i)(^|[^A-Za-z0-9_])" + Pattern.quote(realName.trim())
                + "($|[^A-Za-z0-9_])");
        Matcher matcher = token.matcher(plain);
        if (!matcher.find()) {
            return formatted;
        }

        int visibleStart = matcher.start() + matcher.group(1).length();
        int visibleEnd = matcher.end() - matcher.group(2).length();
        int formattedStart = visibleStart == 0 ? 0 : formattedIndexAfterVisibleChars(formatted, visibleStart);
        int formattedEnd = formattedIndexAfterVisibleChars(formatted, visibleEnd);
        if (formattedStart < 0 || formattedEnd < formattedStart || formattedEnd > formatted.length()) {
            return formatted;
        }
        return formatted.substring(0, formattedStart) + targetName.trim() + formatted.substring(formattedEnd);
    }

    private static String normalizeRankFormattedName(String playerName, String formatted) {
        if (!isUsefulRankFormattedName(playerName, formatted)) {
            return null;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null) {
            return null;
        }
        int closeBracket = plain.indexOf(']');
        if (closeBracket < 0) {
            return null;
        }
        int rankEnd = formattedIndexAfterVisibleChars(formatted, closeBracket + 1);
        if (rankEnd <= 0 || rankEnd > formatted.length()) {
            return null;
        }
        String rankTag = formatted.substring(0, rankEnd);
        return rankTag + " " + effectiveColor(rankTag) + playerName + EnumChatFormatting.RESET;
    }

    private static int formattedIndexAfterVisibleChars(String formatted, int visibleChars) {
        int visible = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            if (++visible >= visibleChars) {
                return i + 1;
            }
        }
        return formatted.length();
    }

    private static String effectiveColor(String formatted) {
        char color = 'f';
        for (int i = 0; i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) != '\u00a7') {
                continue;
            }
            char code = Character.toLowerCase(formatted.charAt(++i));
            if (code == 'r') {
                color = 'f';
            } else if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                color = code;
            }
        }
        return "\u00a7" + color;
    }

    private String formatStarSummaryLine(String role, String playerName, String starName) {
        String formattedName = formatDeathmatchPlayerName(playerName);
        if (formattedName == null || formattedName.trim().isEmpty()
                || !formattedVisibleTextContainsExactPlayerName(playerName, formattedName)) {
            formattedName = EnumChatFormatting.GRAY + playerName;
        }
        return EnumChatFormatting.GOLD + "Blitz Star " + role + ": "
                + formattedName
                + formatStarNameSuffix(starName);
    }

    private static String formatStarNameSuffix(String starName) {
        if (starName == null || starName.trim().isEmpty()) {
            return "";
        }
        return EnumChatFormatting.GRAY + " (" + EnumChatFormatting.AQUA + starName.trim() + EnumChatFormatting.GRAY + ")";
    }

    private boolean hasUsefulStarSummary() {
        if (starSnapshot == null) {
            return false;
        }
        return starSnapshot.finder != null && !starSnapshot.finder.trim().isEmpty()
                || starSnapshot.user != null && !starSnapshot.user.trim().isEmpty();
    }

    private static boolean samePlayerByAlias(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.trim().equalsIgnoreCase(b.trim())) {
            return true;
        }
        Set<String> aliases = new HashSet<String>();
        addAllLower(aliases, KillCounter.getLookupNamesForResults(a));
        return containsAnyLower(aliases, KillCounter.getLookupNamesForResults(b));
    }

    private static String centeredLeadingSpaces(String formattedContents) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return "";
        }
        // The font hooks can rewrite a name while measuring it, then rewrite it again while drawing. Freeze this
        // already-formatted result row before either pass so a disguise cannot change its measured pixel width.
        preserveDisguiseResultText(formattedContents);
        int remainingPixels = mc.fontRendererObj.getStringWidth(RESULT_DIVIDER)
                - mc.fontRendererObj.getStringWidth(formattedContents);
        if (remainingPixels <= 0) {
            return "";
        }
        int spaces = remainingPixels / (2 * Math.max(1, mc.fontRendererObj.getStringWidth(" ")));
        StringBuilder out = new StringBuilder(spaces);
        for (int i = 0; i < spaces; i++) {
            out.append(' ');
        }
        return out.toString();
    }

    private static Map<String, String> snapshotFormattedNames(Map<String, Integer> kills) {
        Map<String, String> result = new HashMap<String, String>();
        for (String name : kills.keySet()) {
            String formatted = KillCounter.getServerFormattedNameForResults(name);
            if (isUsefulRankFormattedName(name, formatted)) {
                result.put(name.toLowerCase(Locale.ROOT), formatted);
                continue;
            }
            formatted = getApiCachedFormattedName(name);
            if (isUsefulRankFormattedName(name, formatted)) {
                result.put(name.toLowerCase(Locale.ROOT), formatted);
            }
        }
        return result;
    }

    private static String getApiCachedFormattedName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String trimmed = name.trim();
        for (String lookupName : KillCounter.getLookupNamesForResults(trimmed)) {
            if (lookupName == null || lookupName.trim().isEmpty()) {
                continue;
            }
            String rankTag = HypixelTabStatsRankCache.getFormattedRankTag(lookupName);
            if (rankTag == null || rankTag.isEmpty()) {
                continue;
            }
            String nameColor = HypixelTabStatsRankCache.getUsernameColorFormattingPreferKeys(lookupName, trimmed);
            if (nameColor == null || nameColor.isEmpty()) {
                nameColor = EnumChatFormatting.WHITE.toString();
            }
            return rankTag + " " + nameColor + trimmed + EnumChatFormatting.RESET;
        }
        return null;
    }

    private static Map<String, String> snapshotKits(Map<String, Integer> kills) {
        Map<String, String> result = new HashMap<String, String>();
        for (String name : kills.keySet()) {
            String kit = KillCounter.getTrackedKitForResults(name);
            if (kit != null && !kit.trim().isEmpty()) {
                result.put(name.toLowerCase(Locale.ROOT), kit.trim());
            }
        }
        return result;
    }

    private static String formatKitSuffix(String kitName) {
        if (kitName == null || kitName.trim().isEmpty()) {
            return "";
        }
        String kit = kitName.trim();
        // Hypixel omits the Roman numeral for level-I kits in its own top-three
        // result rows, e.g. "(Troll)" rather than "(Troll I)".
        if (kit.endsWith(" I") && !kit.endsWith(" II") && !kit.endsWith(" III")) {
            kit = kit.substring(0, kit.length() - 2).trim();
        }
        EnumChatFormatting kitColor = isPrestigeKit(kit) ? EnumChatFormatting.GOLD : EnumChatFormatting.GREEN;
        return EnumChatFormatting.GRAY + " (" + kitColor + kit + EnumChatFormatting.GRAY + ")";
    }

    private static boolean isPrestigeKit(String kitName) {
        if (kitName == null || kitName.isEmpty()) {
            return false;
        }
        for (char starChar : new char[] { '\u2B50', '\u2605', '\u272B', '\u2726', '\u2606', '\u2B51' }) {
            if (kitName.indexOf(starChar) >= 0) {
                return true;
            }
        }
        return kitName.contains("??") || kitName.contains("?");
    }

    private static boolean isUsefulRankFormattedName(String name, String formatted) {
        if (name == null || formatted == null || formatted.trim().isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null) {
            plain = formatted;
        }
        plain = plain.trim();
        // A TAB kill-count prefix such as "[5]" is not a rank. Treating it as one
        // copied the counter's red style onto an unranked player's gray IGN in
        // the Messages count result row (and in other rank-formatted result rows).
        if (NUMERIC_BRACKET_PREFIX.matcher(plain).find()) {
            return false;
        }
        // Result/TAB caches for nicked players can occasionally contain a larger
        // fragment such as "Winner - [VIP+] Name (Kit X)". Never slice a rank
        // out of that by character count: it produces fragments like
        // "IP+] Name (". Supplemental rows only accept one canonical rank/name
        // segment and rebuild it below.
        if (!plain.matches("^\\[[^]]+\\]\\s+" + Pattern.quote(name.trim()) + "$")) {
            return false;
        }
        // A bare TAB-coloured IGN at game end is often Hypixel's dead/team colour (blue/gray/etc.), not rank data.
        // Only reuse TAB formatting when it visibly contains a rank/prefix; otherwise the results row uses white IGN.
        return !plain.equalsIgnoreCase(name.trim())
                && plain.contains("[") && plain.contains("]")
                && visibleTextContainsExactPlayerName(name, plain);
    }

    private static boolean formattedVisibleTextContainsExactPlayerName(String playerName, String formatted) {
        if (playerName == null || formatted == null) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (plain == null) {
            plain = formatted;
        }
        return visibleTextContainsExactPlayerName(playerName, plain);
    }

    private static boolean visibleTextContainsExactPlayerName(String playerName, String plainText) {
        if (playerName == null || plainText == null) {
            return false;
        }
        String trimmedName = playerName.trim();
        if (!trimmedName.matches("[A-Za-z0-9_]{3,16}")) {
            return false;
        }
        Pattern token = Pattern.compile("(?i)(^|[^A-Za-z0-9_])"
                + Pattern.quote(trimmedName) + "($|[^A-Za-z0-9_])");
        return token.matcher(plainText).find();
    }

    private static String extractPlacementName(String tail) {
        Matcher matcher = PLAYER_AFTER_DASH.matcher(tail);
        return matcher.lookingAt() ? matcher.group(1) : null;
    }

    private void captureOfficialResultRankName(String name, String plainLine, String formattedLine) {
        Matcher row = OFFICIAL_RANKED_RESULT.matcher(plainLine.trim());
        if (!row.find() || !row.group(2).equalsIgnoreCase(name)) return;
        String rank = row.group(1);
        String rankKey = rank.toUpperCase(Locale.ROOT);
        String normalized = normalizeRankFormattedName(name, extractFormattedChatName(formattedLine, name));
        String visible = normalized == null ? null : EnumChatFormatting.getTextWithoutFormattingCodes(normalized);
        if (visible == null || !visible.trim().equalsIgnoreCase(rank + " " + name)) {
            // A nick may be absent/rewritten in the formatted component. The official plain row
            // still supplies its exact rank; a failed formatting slice must never discard that rank.
            String color = rankKey.startsWith("[VIP") ? EnumChatFormatting.GREEN.toString()
                    : rankKey.startsWith("[MVP++") ? EnumChatFormatting.GOLD.toString()
                    : rankKey.startsWith("[MVP") ? EnumChatFormatting.AQUA.toString()
                    : EnumChatFormatting.WHITE.toString();
            normalized = color + rank + " " + color + name + EnumChatFormatting.RESET;
        }
        officialResultRankNames.put(name.trim().toLowerCase(Locale.ROOT), normalized);
    }

    private static boolean isResultDivider(String message) {
        if (message == null || message.length() < 48) {
            return false;
        }
        for (int i = 0; i < message.length(); i++) {
            if (message.charAt(i) != '\u25AC') {
                return false;
            }
        }
        return true;
    }

    private static String ordinal(int number) {
        int mod100 = number % 100;
        if (mod100 < 11 || mod100 > 13) {
            switch (number % 10) {
                case 1: return number + "st";
                case 2: return number + "nd";
                case 3: return number + "rd";
                default: break;
            }
        }
        return number + "th";
    }

    private void clearResultBlock() {
        resultHeaderAtMs = 0L;
        killSnapshot = Collections.emptyMap();
        formattedNameSnapshot = Collections.emptyMap();
        kitSnapshot = Collections.emptyMap();
        resultAnnouncedKits.clear();
        messageCountSnapshot = Collections.emptyMap();
        messageTextSnapshot = Collections.emptyMap();
        messageDisguiseNameSnapshot = Collections.emptyMap();
        remoteDisguiseNameSnapshot = Collections.emptyMap();
        liveMessageCounts.clear();
        liveMessageTexts.clear();
        liveMessageTimeline.clear();
        messageTimelineSnapshot = Collections.emptyList();
        liveMessageDisguiseNames.clear();
        liveRemoteDisguiseNameSnapshot.clear();
        observedFormattedNames.clear();
        officialResultRankNames.clear();
        knownMessagePlayers.clear();
        messageTrackingActive = false;
        teamsMessageMode = false;
        deathmatchPlayersSnapshot = Collections.emptyList();
        liveDeathmatchPlayers = Collections.emptyList();
        starSnapshot = BlitzStarMatchSummary.snapshot();
        topThreeNamesLower.clear();
        emitted = false;
        pendingRows = null;
        debugWriteAfterMs = 0L;
        deathmatchTrackUntilMs = 0L;
        deathmatchReachedThisMatch = false;
        deathmatchReachedSnapshot = false;
        matchStartedAtMs = 0L;
        matchEndedAtMs = 0L;
        matchTrackingStartedAtMs = 0L;
        localMessageVisibilityEndedAtMs = 0L;
        localEliminationSeen = false;
        matchDurationAddsCageCountdown = false;
        matchMapName = "";
        matchStartingPlayerCount = 0;
        matchMaxPlayerCount = 0;
        mapPlayerLineEmitted = false;
    }

    private void anchorMatchDurationAtCagesOpened(long now) {
        if (matchStartedAtMs > 0L) {
            return;
        }
        matchStartedAtMs = now;
        matchDurationAddsCageCountdown = true;
    }

    private void ensureMatchDurationAnchorForResults(long now) {
        if (matchStartedAtMs > 0L) {
            return;
        }
        matchStartedAtMs = matchTrackingStartedAtMs > 0L ? matchTrackingStartedAtMs : now;
        matchDurationAddsCageCountdown = false;
    }

    private void resetLiveDeathmatchTrackingForNewMatch() {
        // Fully retire the previous result card. Otherwise its ten-second timeout can
        // fire during this match and clear messageTrackingActive plus the new counts.
        resultHeaderAtMs = 0L;
        killSnapshot = Collections.emptyMap();
        formattedNameSnapshot = Collections.emptyMap();
        kitSnapshot = Collections.emptyMap();
        resultAnnouncedKits.clear();
        topThreeNamesLower.clear();
        emitted = false;
        pendingRows = null;
        debugWriteAfterMs = 0L;
        liveDeathmatchPlayers = Collections.emptyList();
        deathmatchTrackUntilMs = 0L;
        deathmatchReachedThisMatch = false;
        lastDeathmatchPhase = false;
        liveMessageCounts.clear();
        liveMessageTexts.clear();
        liveMessageTimeline.clear();
        messageTimelineSnapshot = Collections.emptyList();
        liveMessageDisguiseNames.clear();
        liveRemoteDisguiseNameSnapshot.clear();
        observedFormattedNames.clear();
        officialResultRankNames.clear();
        knownMessagePlayers.clear();
        messageCountSnapshot = Collections.emptyMap();
        messageTextSnapshot = Collections.emptyMap();
        messageDisguiseNameSnapshot = Collections.emptyMap();
        remoteDisguiseNameSnapshot = Collections.emptyMap();
        messageTrackingActive = true;
        matchTrackingStartedAtMs = System.currentTimeMillis();
        matchStartedAtMs = 0L;
        matchEndedAtMs = 0L;
        localMessageVisibilityEndedAtMs = 0L;
        localEliminationSeen = false;
        matchDurationAddsCageCountdown = false;
        Minecraft mc = Minecraft.getMinecraft();
        if (matchMapName == null || matchMapName.isEmpty()) {
            String detectedMap = MapQueuer.getCurrentMap();
            if (detectedMap != null) matchMapName = detectedMap.trim();
        }
        teamsMessageMode = BlitzTeamsDetector.isBlitzTeamsGame(mc);
        rememberCurrentMessagePlayers(mc);
        matchStartingPlayerCount = countCurrentPlayers(mc);
        matchMaxPlayerCount = matchMapName == null || matchMapName.isEmpty()
                ? 24 : MapQueuer.getMapMaxPlayers(matchMapName);
        if (resultsDebugArmed) {
            debugCapture = new StringBuilder("gomod Blitz message-count + results capture\n\n");
            appendMessageCountDebug("TRACKING START teamsMode=" + teamsMessageMode
                    + " knownPlayers=" + knownMessagePlayers.size());
        }
    }

    private void trackPlayerMessage(String message, String formattedMessage) {
        Minecraft mc = Minecraft.getMinecraft();
        if (BlitzTeamsDetector.isBlitzTeamsGame(mc)) {
            teamsMessageMode = true;
        }
        if (hasSpeakerPrefix(message, "[SPECTATOR]")) {
            appendMessageCountDebug("SKIP spectator message: " + message);
            return;
        }
        if (teamsMessageMode && !hasShoutSpeakerPrefix(message)) {
            appendMessageCountDebug("SKIP teams non-SHOUT: " + message);
            return;
        }
        String speaker = extractGameChatSpeaker(message);
        if (speaker == null) {
            if (message.indexOf(':') >= 0) {
                appendMessageCountDebug("SKIP no speaker parse: " + message);
            }
            return;
        }
        rememberCurrentMessagePlayers(mc);
        boolean knownPlayer = isKnownMessagePlayer(speaker);
        boolean rankedHypixelChat = hasVisibleRankPrefix(message, speaker);
        if (!knownPlayer && !rankedHypixelChat) {
            appendMessageCountDebug("SKIP unknown unranked speaker=" + speaker + ": " + message);
            return;
        }
        String key = speaker;
        for (String existing : liveMessageCounts.keySet()) {
            if (samePlayerByAlias(existing, speaker)) {
                key = existing;
                break;
            }
        }
        String disguiseName = messageCountDisguiseName(speaker);
        if (disguiseName != null && !disguiseName.isEmpty()) {
            liveMessageDisguiseNames.put(key, disguiseName);
        }
        Integer old = liveMessageCounts.get(key);
        liveMessageCounts.put(key, old == null ? 1 : old + 1);
        List<String> texts = liveMessageTexts.get(key);
        if (texts == null) {
            texts = new ArrayList<String>();
            liveMessageTexts.put(key, texts);
        }
        String originalLine = formattedMessage == null || formattedMessage.isEmpty() ? message : formattedMessage;
        long elapsed = matchStartedAtMs > 0L ? System.currentTimeMillis() - matchStartedAtMs : 0L;
        // Add the timestamp only after local /disguise and peer /disguiser rewrites. This keeps
        // their rank/name formatting from dropping it and preserves the established [mm:ss] style.
        String captured = EnumChatFormatting.DARK_GRAY + "["
                + EnumChatFormatting.WHITE + EnumChatFormatting.BOLD + formatMessageElapsed(elapsed)
                + EnumChatFormatting.DARK_GRAY + "]" + EnumChatFormatting.RESET
                + " " + originalLine + EnumChatFormatting.RESET;
        texts.add(captured);
        liveMessageTimeline.add(captured);
        knownMessagePlayers.add(speaker);
        appendMessageCountDebug("COUNT speaker=" + speaker + " total=" + liveMessageCounts.get(key)
                + " known=" + knownPlayer + " ranked=" + rankedHypixelChat);
    }

    private static String messageCountDisguiseName(String sourceName) {
        if (sourceName == null || sourceName.isEmpty()) return null;
        // Match-result formatting must not depend on the current server/phase. The result rows can be emitted
        // after Hypixel has already moved the client to a lobby, where the normal chat formatter may drop the
        // target's rank prefix and color. Use the disguise's saved target rank/name directly.
        String localDisplay = LocalDisguise.formatDisguiseNameForMatchResults(sourceName);
        if (localDisplay != null && !localDisplay.isEmpty()) return localDisplay;
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        if (target == null) return null;
        String remoteDisplay = LocalDisguise.formatRemoteDisguiseChatName(target);
        return remoteDisplay == null || remoteDisplay.isEmpty() ? target.name : remoteDisplay;
    }

    /**
     * Freeze each known speaker's disguise presentation at the result header, before the server-side lobby
     * transition can change the context used by ordinary chat formatting.
     */
    private Map<String, String> snapshotMessageDisguiseNames(Map<String, Integer> messageCounts) {
        Map<String, String> snapshot = new HashMap<String, String>(liveMessageDisguiseNames);
        if (messageCounts == null || messageCounts.isEmpty()) return snapshot;
        for (String sourceName : messageCounts.keySet()) {
            boolean alreadyCapturedAtMessageTime = false;
            for (String capturedSource : snapshot.keySet()) {
                if (samePlayerByAlias(capturedSource, sourceName)) {
                    alreadyCapturedAtMessageTime = true;
                    break;
                }
            }
            if (alreadyCapturedAtMessageTime) {
                continue;
            }
            String displayName = messageCountDisguiseName(sourceName);
            if (displayName != null && !displayName.isEmpty()) {
                snapshot.put(sourceName, displayName);
            }
        }
        return snapshot;
    }

    private static boolean hasVisibleRankPrefix(String message, String speaker) {
        int colon = message.indexOf(':');
        int nameAt = indexOfIgnoreCase(message.substring(0, colon < 0 ? message.length() : colon), speaker);
        if (nameAt < 0) {
            return false;
        }
        String prefix = message.substring(0, nameAt);
        Matcher brackets = Pattern.compile("\\[([^]]+)\\]").matcher(prefix);
        while (brackets.find()) {
            String tag = brackets.group(1).trim().toUpperCase(Locale.ROOT);
            if (!"SHOUT".equals(tag) && !"TEAM".equals(tag) && !"SPECTATOR".equals(tag)
                    && !tag.matches("[0-9,]+")) {
                return true;
            }
        }
        return false;
    }

    private void appendMessageCountDebug(String line) {
        if (debugCapture != null && line != null) {
            debugCapture.append("MSGCOUNT: ").append(line).append('\n');
        }
    }

    /**
     * Learns the exact rank tag from player chat and, for kill/death messages that
     * omit ranks, at least the server-supplied username color.
     */
    private void observeFormattedPlayerNames(IChatComponent component, String plainMessage) {
        if (component == null || plainMessage == null || plainMessage.isEmpty()) {
            return;
        }
        String formatted = component.getFormattedText();
        if (formatted == null || formatted.isEmpty()) {
            return;
        }

        String speaker = extractGameChatSpeaker(plainMessage);
        if (speaker != null) {
            rememberRemoteDisguiseName(speaker);
            String chatName = extractFormattedChatName(formatted, speaker);
            if (chatName != null) {
                observedFormattedNames.put(speaker.toLowerCase(Locale.ROOT), chatName);
            }
        }

        Matcher death = DEATHMATCH_KILL_LINE.matcher(plainMessage);
        if (ArmorDisguiseDeathSignal.victim(plainMessage) != null && death.matches()) {
            rememberRemoteDisguiseName(death.group(1));
            rememberRemoteDisguiseName(death.group(2));
            observeFormattedNameColor(formatted, death.group(1));
            observeFormattedNameColor(formatted, death.group(2));
        }
    }

    private void observeFormattedNameColor(String formattedLine, String playerName) {
        if (playerName == null || observedFormattedNames.containsKey(playerName.toLowerCase(Locale.ROOT))) {
            return;
        }
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formattedLine);
        if (visible == null) {
            return;
        }
        int visibleIndex = indexOfIgnoreCase(visible, playerName);
        if (visibleIndex < 0) {
            return;
        }
        int formattedIndex = formattedIndexForVisibleIndex(formattedLine, visibleIndex);
        String color = effectiveColor(formattedLine.substring(0, Math.max(0, formattedIndex)));
        observedFormattedNames.put(playerName.toLowerCase(Locale.ROOT),
                color + playerName + EnumChatFormatting.RESET);
    }

    private static String extractFormattedChatName(String formattedLine, String playerName) {
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formattedLine);
        if (visible == null) {
            return null;
        }
        int nameStart = indexOfIgnoreCase(visible, playerName);
        if (nameStart < 0) {
            return null;
        }
        int segmentStart = nameStart;
        int cursor = nameStart - 1;
        while (cursor >= 0 && Character.isWhitespace(visible.charAt(cursor))) cursor--;
        if (cursor >= 0 && visible.charAt(cursor) == ']') {
            int open = visible.lastIndexOf('[', cursor);
            if (open >= 0) {
                String tag = visible.substring(open, cursor + 1).toUpperCase(Locale.ROOT);
                if (!"[SHOUT]".equals(tag) && !"[TEAM]".equals(tag) && !"[SPECTATOR]".equals(tag)) {
                    segmentStart = open;
                }
            }
        }
        int formattedStart = formattedIndexForVisibleIndex(formattedLine, segmentStart);
        int formattedEnd = formattedIndexForVisibleIndex(formattedLine, nameStart + playerName.length());
        if (formattedStart < 0 || formattedEnd <= formattedStart || formattedEnd > formattedLine.length()) {
            return null;
        }
        // formattedStart points at the first visible character, after any color code that applies to it.
        // Carry that inherited color into the extracted segment or tags become "[MVP§x+§x]" with an
        // uncoloured opening bracket.
        String inheritedColor = effectiveColor(formattedLine.substring(0, formattedStart));
        String result = inheritedColor + formattedLine.substring(formattedStart, formattedEnd)
                + EnumChatFormatting.RESET;
        return result.indexOf('\u00a7') >= 0 ? result : null;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        return lowerText.indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static int formattedIndexForVisibleIndex(String formatted, int targetVisibleIndex) {
        int visible = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            if (visible++ == targetVisibleIndex) {
                return i;
            }
        }
        return formatted.length();
    }

    private static boolean hasShoutSpeakerPrefix(String message) {
        return hasSpeakerPrefix(message, "[SHOUT]");
    }

    private static boolean hasSpeakerPrefix(String message, String prefix) {
        if (message == null) {
            return false;
        }
        int colon = message.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        return message.substring(0, colon).toUpperCase(Locale.ROOT)
                .contains(prefix.toUpperCase(Locale.ROOT));
    }

    private static String extractGameChatSpeaker(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.startsWith("party >") || lower.startsWith("guild >")
                || lower.startsWith("officer >") || lower.startsWith("from ")
                || lower.startsWith("to ")) {
            return null;
        }
        int colon = message.indexOf(':');
        if (colon <= 0 || colon + 1 >= message.length()) {
            return null;
        }
        String speakerRegion = message.substring(0, colon).trim();
        Matcher matcher = CHAT_SPEAKER_BEFORE_COLON.matcher(speakerRegion);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void rememberCurrentMessagePlayers(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (mc.theWorld != null) {
            for (Object object : mc.theWorld.playerEntities) {
                if (object instanceof EntityPlayer) {
                    String name = ((EntityPlayer) object).getName();
                    if (name != null && !name.trim().isEmpty()) {
                        knownMessagePlayers.add(name.trim());
                        rememberRemoteDisguiseName(name);
                    }
                }
            }
        }
        if (mc.getNetHandler() != null) {
            for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                String profile = info.getGameProfile().getName();
                if (profile != null && !profile.trim().isEmpty()) {
                    knownMessagePlayers.add(profile.trim());
                    rememberRemoteDisguiseName(profile);
                }
                String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                if (visible != null && !visible.trim().isEmpty()) {
                    knownMessagePlayers.add(visible.trim());
                    rememberRemoteDisguiseName(visible);
                }
            }
        }
    }

    private boolean isKnownMessagePlayer(String speaker) {
        for (String known : knownMessagePlayers) {
            if (samePlayerByAlias(known, speaker)) {
                return true;
            }
        }
        return false;
    }

    private void appendDebugLine(IChatComponent component) {
        if (debugCapture == null || component == null) {
            return;
        }
        debugCapture.append("PLAIN: ").append(component.getUnformattedText()).append('\n');
        debugCapture.append("FORMATTED: ").append(component.getFormattedText()).append('\n');
        try {
            debugCapture.append("JSON: ").append(escape(IChatComponent.Serializer.componentToJson(component))).append('\n');
        } catch (Exception e) {
            debugCapture.append("JSON: <error ").append(escape(e.getMessage())).append(">\n");
        }
        appendComponentTree(debugCapture, component, 0);
        debugCapture.append('\n');
    }

    private static void appendComponentTree(StringBuilder out, IChatComponent component, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        String formatting = component.getChatStyle() == null ? "" : component.getChatStyle().getFormattingCode();
        out.append("component text=").append(escape(component.getUnformattedTextForChat()))
                .append(" style=").append(escape(formatting)).append('\n');
        for (IChatComponent sibling : component.getSiblings()) {
            appendComponentTree(out, sibling, depth + 1);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.replace("§", "<SECTION>").replace("\n", "\\n");
    }

    private static List<String> snapshotCurrentWorldPlayers(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<String>();
        Set<String> seenLower = new HashSet<String>();
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            String name = player.getName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String trimmed = name.trim();
            if (seenLower.add(trimmed.toLowerCase(Locale.ROOT))) {
                names.add(trimmed);
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void mergeCurrentWorldPlayers(Minecraft mc) {
        List<String> current = snapshotCurrentWorldPlayers(mc);
        if (current.isEmpty()) {
            return;
        }
        Set<String> seenLower = new HashSet<String>();
        List<String> merged = new ArrayList<String>();
        for (String name : liveDeathmatchPlayers) {
            if (name != null && seenLower.add(name.toLowerCase(Locale.ROOT))) {
                merged.add(name);
            }
        }
        for (String name : current) {
            if (name != null && seenLower.add(name.toLowerCase(Locale.ROOT))) {
                merged.add(name);
            }
        }
        Collections.sort(merged, String.CASE_INSENSITIVE_ORDER);
        liveDeathmatchPlayers = merged;
    }

    private void addDeathmatchPlayersFromKillLine(String message) {
        if (message == null || message.trim().isEmpty()
                || ArmorDisguiseDeathSignal.victim(message) == null) {
            return;
        }
        Matcher matcher = DEATHMATCH_KILL_LINE.matcher(message.trim());
        if (!matcher.matches()) {
            return;
        }
        addLiveDeathmatchPlayer(matcher.group(1));
        addLiveDeathmatchPlayer(matcher.group(2));
    }

    private void addLiveDeathmatchPlayer(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String trimmed = name.trim();
        Set<String> seenLower = new HashSet<String>();
        List<String> merged = new ArrayList<String>();
        for (String existing : liveDeathmatchPlayers) {
            if (existing != null && seenLower.add(existing.toLowerCase(Locale.ROOT))) {
                merged.add(existing);
            }
        }
        if (seenLower.add(trimmed.toLowerCase(Locale.ROOT))) {
            merged.add(trimmed);
        }
        Collections.sort(merged, String.CASE_INSENSITIVE_ORDER);
        liveDeathmatchPlayers = merged;
    }

    private void writeDebugCapture() {
        if (debugCapture == null) {
            return;
        }
        try {
            File dir = new File(ConfigManager.getGomodDataDirectory(), "debug");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File file = new File(dir, "blitz-results-" + System.currentTimeMillis() + ".txt");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(debugCapture.toString());
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Blitz results debug saved: "
                    + EnumChatFormatting.WHITE + file.getAbsolutePath());
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not save Blitz results debug: " + e.getMessage());
        } finally {
            debugCapture = null;
            resultsDebugArmed = false;
            debugWriteAfterMs = 0L;
        }
    }

    private static final class ResultPlayer {
        private final String name;
        private final int kills;
        private final String formattedName;
        private final String kitName;

        private ResultPlayer(String name, int kills, String formattedName, String kitName) {
            this.name = name;
            this.kills = kills;
            this.formattedName = formattedName;
            this.kitName = kitName;
        }
    }

    private static final class MessageCountRow {
        private final String name;
        private int count;

        private MessageCountRow(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }
}
