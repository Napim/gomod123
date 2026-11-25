package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.ClientInputHelper;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-only hotbar items during Blitz pregames (customizable to slots 3..8; slots 1, 2, 9 are reserved for Hypixel):
 * <ul>
 *   <li>Requeue paper (default slot 4) — right-click to requeue Solo or Teams.</li>
 *   <li>Status changer disc (default slot 5) — displays and cycles Hypixel {@code /status} (Online, Away, Busy, Appear Offline).</li>
 *   <li>Queue Status skull (default slot 6) — displays Solo/Teams/Lobby counts in the action bar while held; right-click to refresh (2s cooldown).</li>
 *   <li>Player visibility dye (default slot 8) — right-click to hide/show other players.</li>
 * </ul>
 *
 * <p>The real server inventory is never clicked: Minecraft's right-click and container-click
 * entry points consume the marked stacks before any packet can be sent.</p>
 */
public final class BlitzPregamePlayerToggle {
    private static final String ITEM_MARKER = "gomod_blitz_pregame_players";
    private static final String QSTATUS_ITEM_MARKER = "gomod_blitz_pregame_qstatus";
    private static final String REQUEUE_ITEM_MARKER = "gomod_blitz_pregame_requeue";
    private static final String STATUS_ITEM_MARKER = "gomod_blitz_pregame_status";

    private static final Pattern STATUS_CHAT_SET_PATTERN = Pattern.compile(
            "(?i)(?:reminder:\\s*)?(?:your\\s+)?(?:online\\s+)?status\\s+(?:has\\s+been\\s+set\\s+to|is\\s+currently\\s+set\\s+to|is\\s+currently|is\\s+now\\s+set\\s+to|is\\s+now|was\\s+set\\s+to|set\\s+to)\\s+(appear\\s+offline|offline|online|away|busy)\\b");
    private static final Pattern STATUS_CHAT_ALREADY_PATTERN = Pattern.compile(
            "(?i)(?:(?:your\\s+)?(?:online\\s+)?status\\s+is\\s+already(?:\\s+set\\s+to)?|already\\s+(?:have\\s+)?(?:your\\s+)?(?:online\\s+)?status\\s+(?:set\\s+)?to)\\s+(appear\\s+offline|offline|online|away|busy)\\b");
    private static final Pattern OUTGOING_STATUS_CMD_PATTERN = Pattern.compile(
            "(?i)^/status\\s+(appear\\s+offline|appearoffline|offline|online|away|busy)$");

    public enum HypixelOnlineStatus {
        ONLINE("online", "Online", "\u00a7a", "/status online"),
        AWAY("away", "Away", "\u00a7e", "/status away"),
        BUSY("busy", "Busy", "\u00a75", "/status busy"),
        APPEAR_OFFLINE("offline", "Appear Offline", "\u00a74", "/status offline");

        public final String configKey;
        public final String displayName;
        public final String colorCode;
        public final String command;

        HypixelOnlineStatus(String configKey, String displayName, String colorCode, String command) {
            this.configKey = configKey;
            this.displayName = displayName;
            this.colorCode = colorCode;
            this.command = command;
        }

        public HypixelOnlineStatus next() {
            HypixelOnlineStatus[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public Item getDiscItem() {
            switch (this) {
                case ONLINE: return Items.record_cat;       // 2257
                case AWAY: return Items.record_13;          // 2256
                case BUSY: return Items.record_mall;        // 2261
                case APPEAR_OFFLINE: return Items.record_stal; // 2263
            }
            return Items.record_cat;
        }

        public static HypixelOnlineStatus fromToken(String raw) {
            if (raw == null) {
                return null;
            }
            String norm = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (norm.equals("online")) {
                return ONLINE;
            }
            if (norm.equals("away")) {
                return AWAY;
            }
            if (norm.equals("busy")) {
                return BUSY;
            }
            if (norm.equals("offline") || norm.equals("appear offline")
                    || norm.equals("appearoffline") || norm.equals("appearing offline")) {
                return APPEAR_OFFLINE;
            }
            return null;
        }
    }

    private static final String SOLO_NPC_TEXTURE_BASE64 =
            "eyJ0aW1lc3RhbXAiOjE1MDQyOTA0ODIzNzcsInByb2ZpbGVJZCI6ImRiYTJmMTE1NmNjMjQwMWJhOWU5YjRkMjdmN2M4OTdkIiwicHJvZmlsZU5hbWUiOiJjb2RlbmFtZV9CIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8zMDliZmU1NTk3MWZiMGRmNzI2YzJhOGY1MmE1N2E2YTY2NDU4NWYxNmM1Yzg5ZWE2ZTE5ZDdmNWI0NWUifX19";
    private static final String TEAMS_NPC_TEXTURE_BASE64 =
            "eyJ0aW1lc3RhbXAiOjE1MDQyOTA1MTMyODYsInByb2ZpbGVJZCI6ImRiYTJmMTE1NmNjMjQwMWJhOWU5YjRkMjdmN2M4OTdkIiwicHJvZmlsZU5hbWUiOiJjb2RlbmFtZV9CIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS83MDM3Y2I2YzkxNTJhZmJjYThhYTJjYzNmN2QzZWYyMDMzOTRhZTZhNDczYzMxZWVkNzUzYjY3N2I2M2Y5NSJ9fX0=";
    private static final String SOLO_NPC_PROFILE_ID =
            UUID.nameUUIDFromBytes("gomod123:blitz-pregame-qstatus-solo".getBytes(StandardCharsets.UTF_8)).toString();
    private static final String TEAMS_NPC_PROFILE_ID =
            UUID.nameUUIDFromBytes("gomod123:blitz-pregame-qstatus-teams".getBytes(StandardCharsets.UTF_8)).toString();

    private static final long USE_COOLDOWN_MS = 1000L;
    private static final long REQUEUE_COOLDOWN_MS = 1500L;
    private static final long LOBBY_ONLINE_INFERENCE_DELAY_MS = 4000L;
    private static final long PENDING_STATUS_ALREADY_WINDOW_MS = 2500L;
    private static final long QSTATUS_CACHE_TTL_MS = 10_000L;
    private static final long QSTATUS_ERROR_BACKOFF_MS = 10_000L;
    private static final long QSTATUS_MANUAL_REFRESH_COOLDOWN_MS = 2000L;
    private static final long QSTATUS_SHINE_DURATION_MS = 550L;

    private static EntityPlayerSP itemOwner;
    private static int ownedVisibilitySlot = -1;
    private static int ownedQStatusSlot = -1;
    private static int ownedRequeueSlot = -1;
    private static int ownedStatusSlot = -1;
    private static ItemStack displacedStack;
    private static ItemStack displacedQStatusStack;
    private static ItemStack displacedRequeueStack;
    private static ItemStack displacedStatusStack;
    private static boolean activePregame;
    private static boolean playersHidden;
    private static long lastSuccessfulUseMs;
    private static long lastRequeueUseMs;
    private static long lastQStatusManualRefreshMs;
    /** True after this feature has consumed the current physical use-item press. */
    private static boolean usePressConsumed;
    private static int lastPublishedHeldMeta = Integer.MIN_VALUE;

    private static volatile HypixelOnlineStatus trackedHypixelStatus;
    private static volatile long lastExplicitStatusChangeMs;
    private static volatile long lastNonOnlineActionBarMs;
    private static volatile long lastOtherLobbyActionBarMs;
    private static int lobbyInferenceWorldId;
    private static long lobbyEnteredAtMs;
    private static volatile HypixelOnlineStatus pendingButtonTargetStatus;
    private static volatile long pendingButtonClickMs;
    private static volatile boolean autoAdvanceUsedForClick;

    private static final AtomicBoolean qStatusFetchInFlight = new AtomicBoolean(false);
    private static volatile boolean qStatusManualRefreshPending;
    private static volatile long qStatusShineStartMs;
    private static volatile QueueStatusSnapshot cachedQStatus;
    private static volatile long lastQStatusErrorMs;
    private static volatile boolean qStatusMissingApiKey;
    private static volatile boolean qStatusLastFetchFailed;

    public static final class QueueStatusSnapshot {
        public final int totalPlayers;
        public final int soloPlayers;
        public final int teamsPlayers;
        public final int lobbyPlayers;
        public final long fetchedAtMs;

        public QueueStatusSnapshot(int totalPlayers, int soloPlayers, int teamsPlayers, int lobbyPlayers, long fetchedAtMs) {
            this.totalPlayers = totalPlayers;
            this.soloPlayers = soloPlayers;
            this.teamsPlayers = teamsPlayers;
            this.lobbyPlayers = lobbyPlayers;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    private BlitzPregamePlayerToggle() {
    }

    public static int getVisibilitySlotIndex() {
        return ConfigManager.getBlitzPregamePlayerToggleSlot() - 1;
    }

    public static int getQueueStatusSlotIndex() {
        return ConfigManager.getBlitzPregameQueueStatusSlot() - 1;
    }

    public static int getRequeueSlotIndex() {
        return ConfigManager.getBlitzPregameRequeueSlot() - 1;
    }

    public static int getStatusSlotIndex() {
        return ConfigManager.getBlitzPregameStatusSlot() - 1;
    }

    public static HypixelOnlineStatus getCurrentHypixelStatus() {
        HypixelOnlineStatus current = trackedHypixelStatus;
        if (current != null) {
            return current;
        }
        HypixelOnlineStatus saved = HypixelOnlineStatus.fromToken(ConfigManager.getBlitzPregameHypixelStatus());
        trackedHypixelStatus = saved != null ? saved : HypixelOnlineStatus.ONLINE;
        return trackedHypixelStatus;
    }

    private static void setTrackedHypixelStatus(HypixelOnlineStatus status, boolean fromServer) {
        if (status == null) {
            return;
        }
        trackedHypixelStatus = status;
        ConfigManager.setBlitzPregameHypixelStatus(status.configKey);
        if (!fromServer) {
            lastExplicitStatusChangeMs = System.currentTimeMillis();
        }
    }

    public static HypixelOnlineStatus parseStatusFromActionBar(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String plain = stripFormatting(text);
        if (plain.isEmpty()) {
            return null;
        }
        String upper = plain.toUpperCase(Locale.ROOT);
        if (upper.contains("YOU ARE CURRENTLY APPEARING OFFLINE")) {
            return HypixelOnlineStatus.APPEAR_OFFLINE;
        }
        if (upper.contains("YOU ARE CURRENTLY BUSY")) {
            return HypixelOnlineStatus.BUSY;
        }
        if (upper.contains("YOU ARE CURRENTLY AWAY")) {
            return HypixelOnlineStatus.AWAY;
        }
        return null;
    }

    public static boolean isOtherLobbyStatusActionBar(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String upper = stripFormatting(text).toUpperCase(Locale.ROOT);
        return upper.contains("YOU ARE CURRENTLY NICKED")
                || upper.contains("YOU ARE CURRENTLY VANISHED");
    }

    public static HypixelOnlineStatus parseStatusFromChatConfirmation(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String plain = stripFormatting(text);
        Matcher m = STATUS_CHAT_SET_PATTERN.matcher(plain);
        if (m.find()) {
            return HypixelOnlineStatus.fromToken(m.group(1));
        }
        return null;
    }

    public static HypixelOnlineStatus parseAlreadySetStatusFromChat(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String plain = stripFormatting(text);
        Matcher m = STATUS_CHAT_ALREADY_PATTERN.matcher(plain);
        if (m.find()) {
            return HypixelOnlineStatus.fromToken(m.group(1));
        }
        return null;
    }

    public static HypixelOnlineStatus parseStatusFromOutgoingCommand(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        Matcher m = OUTGOING_STATUS_CMD_PATTERN.matcher(command.trim());
        if (m.matches()) {
            return HypixelOnlineStatus.fromToken(m.group(1));
        }
        return null;
    }

    public static void onOutgoingChatCommand(String message) {
        HypixelOnlineStatus parsed = parseStatusFromOutgoingCommand(message);
        if (parsed != null) {
            setTrackedHypixelStatus(parsed, false);
        }
    }

    public static void onIncomingChatPacket(S02PacketChat packet) {
        if (packet == null) {
            return;
        }
        IChatComponent component = packet.getChatComponent();
        if (component == null) {
            return;
        }
        String raw = component.getUnformattedText();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (packet.getType() == 2) {
            HypixelOnlineStatus fromBar = parseStatusFromActionBar(raw);
            if (fromBar != null) {
                lastNonOnlineActionBarMs = now;
                setTrackedHypixelStatus(fromBar, true);
            } else if (isOtherLobbyStatusActionBar(raw)) {
                lastOtherLobbyActionBarMs = now;
            }
            return;
        }

        HypixelOnlineStatus confirmed = parseStatusFromChatConfirmation(raw);
        if (confirmed != null) {
            pendingButtonTargetStatus = null;
            setTrackedHypixelStatus(confirmed, true);
            return;
        }

        HypixelOnlineStatus already = parseAlreadySetStatusFromChat(raw);
        if (already != null) {
            setTrackedHypixelStatus(already, true);
            if (pendingButtonTargetStatus == already
                    && !autoAdvanceUsedForClick
                    && now - pendingButtonClickMs <= PENDING_STATUS_ALREADY_WINDOW_MS) {
                autoAdvanceUsedForClick = true;
                final HypixelOnlineStatus next = already.next();
                pendingButtonTargetStatus = next;
                setTrackedHypixelStatus(next, false);
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    mc.addScheduledTask(() -> {
                        if (mc.thePlayer != null) {
                            mc.thePlayer.sendChatMessage(next.command);
                        }
                    });
                }
            } else {
                pendingButtonTargetStatus = null;
            }
        }
    }

    private static void updateLobbyStatusInference(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !GameContext.isOnHypixel(mc) || !GameContext.isHypixelLobby(mc)) {
            lobbyInferenceWorldId = 0;
            lobbyEnteredAtMs = 0L;
            return;
        }
        int worldId = System.identityHashCode(mc.theWorld);
        long now = System.currentTimeMillis();
        if (lobbyInferenceWorldId != worldId || lobbyEnteredAtMs == 0L) {
            lobbyInferenceWorldId = worldId;
            lobbyEnteredAtMs = now;
            return;
        }
        if (now - lobbyEnteredAtMs >= LOBBY_ONLINE_INFERENCE_DELAY_MS
                && lastNonOnlineActionBarMs < lobbyEnteredAtMs
                && lastOtherLobbyActionBarMs < lobbyEnteredAtMs
                && now - lastExplicitStatusChangeMs >= LOBBY_ONLINE_INFERENCE_DELAY_MS
                && getCurrentHypixelStatus() != HypixelOnlineStatus.ONLINE) {
            setTrackedHypixelStatus(HypixelOnlineStatus.ONLINE, true);
        }
    }

    private static String stripFormatting(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replaceAll("\u00a7.", "").replaceAll("\\s+", " ").trim();
    }

    public static void onClientTick(Minecraft mc) {
        // Always observe lobby presence so Online status is inferred even outside Blitz pregames.
        updateLobbyStatusInference(mc);

        // rightClickMouse is pulsed repeatedly while use-item remains held. Rearm
        // only after the physical key/button is released so one click is one use.
        if (!ClientInputHelper.isUseItemHeld(mc)) {
            usePressConsumed = false;
        }

        if (mc == null || mc.thePlayer == null || mc.theWorld == null || !isAnyItemEnabled()) {
            deactivate();
            return;
        }

        if (!isActualBlitzPregame(mc)) {
            deactivate();
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (itemOwner != player) {
            restoreAllOwnedSlots();
            itemOwner = player;
        }

        int visSlot = getVisibilitySlotIndex();
        int qSlot = getQueueStatusSlotIndex();
        int reqSlot = getRequeueSlotIndex();
        int statusSlot = getStatusSlotIndex();

        // First restore any disabled or moved slots before writing new items (handles slot swaps cleanly).
        if (!ConfigManager.isBlitzPregamePlayerToggleEnabled() || (ownedVisibilitySlot >= 0 && ownedVisibilitySlot != visSlot)) {
            restoreVisibilitySlot();
            if (!ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
                playersHidden = false;
            }
        }
        if (!ConfigManager.isBlitzPregameQueueStatusItemEnabled() || (ownedQStatusSlot >= 0 && ownedQStatusSlot != qSlot)) {
            restoreQStatusSlot();
        }
        if (!ConfigManager.isBlitzPregameRequeueItemEnabled() || (ownedRequeueSlot >= 0 && ownedRequeueSlot != reqSlot)) {
            restoreRequeueSlot();
        }
        if (!ConfigManager.isBlitzPregameStatusItemEnabled() || (ownedStatusSlot >= 0 && ownedStatusSlot != statusSlot)) {
            restoreStatusSlot();
        }

        activePregame = true;

        if (ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            ItemStack current = player.inventory.getStackInSlot(visSlot);
            if (ownedVisibilitySlot != visSlot || !isControlItem(current)) {
                displacedStack = copyUnlessControl(current);
                ownedVisibilitySlot = visSlot;
            }
            player.inventory.setInventorySlotContents(visSlot, makeControlItem());
        }

        boolean teamsPregame = isTeamsPregame(mc);

        if (ConfigManager.isBlitzPregameQueueStatusItemEnabled()) {
            ItemStack current = player.inventory.getStackInSlot(qSlot);
            if (ownedQStatusSlot != qSlot || !isQStatusItem(current)) {
                displacedQStatusStack = copyUnlessControl(current);
                ownedQStatusSlot = qSlot;
            }
            ItemStack desired = makeQStatusItem(teamsPregame);
            if (!ItemStack.areItemStacksEqual(current, desired)) {
                player.inventory.setInventorySlotContents(qSlot, desired);
            }
        }

        if (ConfigManager.isBlitzPregameRequeueItemEnabled()) {
            ItemStack current = player.inventory.getStackInSlot(reqSlot);
            if (ownedRequeueSlot != reqSlot || !isRequeueItem(current)) {
                displacedRequeueStack = copyUnlessControl(current);
                ownedRequeueSlot = reqSlot;
            }
            ItemStack desired = makeRequeueItem(teamsPregame);
            if (!ItemStack.areItemStacksEqual(current, desired)) {
                player.inventory.setInventorySlotContents(reqSlot, desired);
            }
        }

        if (ConfigManager.isBlitzPregameStatusItemEnabled()) {
            ItemStack current = player.inventory.getStackInSlot(statusSlot);
            if (ownedStatusSlot != statusSlot || !isStatusItem(current)) {
                displacedStatusStack = copyUnlessControl(current);
                ownedStatusSlot = statusSlot;
            }
            ItemStack desired = makeStatusItem();
            if (!ItemStack.areItemStacksEqual(current, desired)) {
                player.inventory.setInventorySlotContents(statusSlot, desired);
            }
        }

        if (isHoldingQueueStatusItem(mc)) {
            maybeRefreshQueueStatus(false);
            pushActionBar(mc, getHeldQueueStatusHudText(mc));
        }

        publishHeldStateIfChanged();
    }

    /**
     * Handles the marked pregame items before vanilla can send a use-item packet.
     *
     * @return true when the click belongs to this feature and must be cancelled
     */
    public static boolean tryHandleRightClick(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !activePregame || itemOwner != mc.thePlayer || !isActualBlitzPregame(mc)) {
            return false;
        }

        int slot = mc.thePlayer.inventory.currentItem;
        ItemStack clickedStack = mc.thePlayer.getHeldItem();

        if (slot == ownedVisibilitySlot && ConfigManager.isBlitzPregamePlayerToggleEnabled() && isControlItem(clickedStack)) {
            return toggleVisibility(mc, clickedStack);
        }
        if (slot == ownedRequeueSlot && ConfigManager.isBlitzPregameRequeueItemEnabled() && isRequeueItem(clickedStack)) {
            return triggerRequeue(mc);
        }
        if (slot == ownedQStatusSlot && ConfigManager.isBlitzPregameQueueStatusItemEnabled() && isQStatusItem(clickedStack)) {
            return handleQueueStatusClick(mc);
        }
        if (slot == ownedStatusSlot && ConfigManager.isBlitzPregameStatusItemEnabled() && isStatusItem(clickedStack)) {
            return cycleHypixelStatus(mc);
        }
        return false;
    }

    /**
     * Handles clicking any client-only pregame item from the player-inventory row of
     * a server-backed GUI (for example, the Blitz kit or kill-effect selector).
     *
     * @return true when the click was handled and the window click must be cancelled
     */
    public static boolean tryHandleContainerClick(Minecraft mc, int slotId, int mouseButtonClicked, int mode) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !(mc.currentScreen instanceof GuiContainer)
                || !isAnyItemEnabled()
                || !isActualBlitzPregame(mc)
                || !activePregame || itemOwner != mc.thePlayer
                || mode != 0
                || (mouseButtonClicked != 0 && mouseButtonClicked != 1)) {
            return false;
        }

        if (mc.thePlayer.openContainer == null
                || slotId < 0 || slotId >= mc.thePlayer.openContainer.inventorySlots.size()) {
            return false;
        }

        Slot clickedSlot = mc.thePlayer.openContainer.getSlot(slotId);
        if (clickedSlot == null || clickedSlot.inventory != mc.thePlayer.inventory) {
            return false;
        }

        int slotIdx = clickedSlot.getSlotIndex();
        if (slotIdx == ownedVisibilitySlot && ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            ItemStack clickedStack = mc.thePlayer.inventory.getStackInSlot(ownedVisibilitySlot);
            if (isControlItem(clickedStack)) {
                return toggleVisibility(mc, clickedStack);
            }
        } else if (slotIdx == ownedRequeueSlot && ConfigManager.isBlitzPregameRequeueItemEnabled()) {
            ItemStack clickedStack = mc.thePlayer.inventory.getStackInSlot(ownedRequeueSlot);
            if (isRequeueItem(clickedStack)) {
                return triggerRequeue(mc);
            }
        } else if (slotIdx == ownedQStatusSlot && ConfigManager.isBlitzPregameQueueStatusItemEnabled()) {
            ItemStack clickedStack = mc.thePlayer.inventory.getStackInSlot(ownedQStatusSlot);
            if (isQStatusItem(clickedStack)) {
                return handleQueueStatusClick(mc);
            }
        } else if (slotIdx == ownedStatusSlot && ConfigManager.isBlitzPregameStatusItemEnabled()) {
            ItemStack clickedStack = mc.thePlayer.inventory.getStackInSlot(ownedStatusSlot);
            if (isStatusItem(clickedStack)) {
                return cycleHypixelStatus(mc);
            }
        }

        return false;
    }

    private static boolean cycleHypixelStatus(Minecraft mc) {
        if (usePressConsumed) {
            return true;
        }
        usePressConsumed = true;

        long now = System.currentTimeMillis();
        HypixelOnlineStatus next = getCurrentHypixelStatus().next();
        pendingButtonTargetStatus = next;
        pendingButtonClickMs = now;
        autoAdvanceUsedForClick = false;
        setTrackedHypixelStatus(next, false);

        if (ownedStatusSlot >= 0) {
            mc.thePlayer.inventory.setInventorySlotContents(ownedStatusSlot, makeStatusItem());
        }
        mc.thePlayer.playSound("note.pling", 0.5F, 1.8F);
        mc.thePlayer.sendChatMessage(next.command);
        return true;
    }

    private static boolean handleQueueStatusClick(Minecraft mc) {
        if (usePressConsumed) {
            return true;
        }
        usePressConsumed = true;

        long now = System.currentTimeMillis();
        long elapsed = now - lastQStatusManualRefreshMs;
        if (elapsed < QSTATUS_MANUAL_REFRESH_COOLDOWN_MS) {
            long remainingSec = Math.max(1L, (QSTATUS_MANUAL_REFRESH_COOLDOWN_MS - elapsed + 999L) / 1000L);
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.RED + "You must wait "
                            + EnumChatFormatting.YELLOW + remainingSec + "s"
                            + EnumChatFormatting.RED + " between uses!"));
            return true;
        }
        lastQStatusManualRefreshMs = now;
        qStatusManualRefreshPending = true;
        maybeRefreshQueueStatus(true);
        pushActionBar(mc, getHeldQueueStatusHudText(mc));
        return true;
    }

    private static void pushActionBar(Minecraft mc, String text) {
        if (mc == null || mc.ingameGUI == null || text == null || text.isEmpty()) {
            return;
        }
        mc.ingameGUI.setRecordPlaying(text, false);
    }

    private static boolean triggerRequeue(Minecraft mc) {
        if (usePressConsumed) {
            return true;
        }
        usePressConsumed = true;

        long now = System.currentTimeMillis();
        if (now - lastRequeueUseMs < REQUEUE_COOLDOWN_MS) {
            return true;
        }
        lastRequeueUseMs = now;

        boolean teams = isTeamsPregame(mc);
        mc.thePlayer.sendChatMessage(requeueCommandForMode(teams));
        return true;
    }

    private static boolean toggleVisibility(Minecraft mc, ItemStack clickedStack) {
        // Always consume duplicate vanilla/Lunar pulses for the same press, but
        // do not toggle again or repeat the cooldown chat message.
        if (usePressConsumed) {
            return true;
        }
        usePressConsumed = true;

        long now = System.currentTimeMillis();
        if (now - lastSuccessfulUseMs < USE_COOLDOWN_MS) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.RED + "You must wait "
                            + EnumChatFormatting.YELLOW + "1s"
                            + EnumChatFormatting.RED + " between uses!"));
            return true;
        }

        if (!isControlItem(clickedStack)) {
            displacedStack = copyUnlessControl(clickedStack);
        }
        lastSuccessfulUseMs = now;
        activePregame = true;
        playersHidden = !playersHidden;
        if (ownedVisibilitySlot >= 0) {
            mc.thePlayer.inventory.setInventorySlotContents(ownedVisibilitySlot, makeControlItem());
        }
        mc.thePlayer.addChatMessage(new ChatComponentText(
                (playersHidden ? EnumChatFormatting.RED : EnumChatFormatting.GREEN)
                        + (playersHidden ? "Player visibility disabled!" : "Player visibility enabled!")));
        publishHeldStateIfChanged();
        return true;
    }

    /**
     * Shared by the normal renderer and Replay Mod's direct RenderPlayer path.
     * The local player is deliberately never hidden.
     */
    public static boolean shouldHide(Entity entity) {
        if (!activePregame || !playersHidden || !(entity instanceof EntityPlayer)
                || !ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return isActualBlitzPregame(mc) && entity != mc.thePlayer;
    }

    /** Coordinate fallback for effects delivered independently of an entity tick. */
    public static boolean isHiddenPlayerNear(double x, double y, double z, double radius) {
        if (!activePregame || !playersHidden) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isActualBlitzPregame(mc)) {
            return false;
        }
        double radiusSq = radius * radius;
        for (Object candidate : mc.theWorld.playerEntities) {
            if (!(candidate instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) candidate;
            if (shouldHide(player)) {
                double dx = player.posX - x;
                double dy = player.posY - y;
                double dz = player.posZ - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Relay value: 8/10 only while the local player is actually holding the visibility dye. */
    public static int getLocalHeldDyeMeta() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!activePregame || !ConfigManager.isBlitzPregamePlayerToggleEnabled()
                || !isActualBlitzPregame(mc) || ownedVisibilitySlot < 0
                || itemOwner != mc.thePlayer || mc.thePlayer.inventory.currentItem != ownedVisibilitySlot) {
            return -1;
        }
        return playersHidden ? 8 : 10;
    }

    /** True while the local player is actively holding the Queue Status skull in Blitz pregame. */
    public static boolean isHoldingQueueStatusItem(Minecraft mc) {
        if (!activePregame || !ConfigManager.isBlitzPregameQueueStatusItemEnabled()
                || mc == null || mc.thePlayer == null || mc.theWorld == null
                || ownedQStatusSlot < 0
                || itemOwner != mc.thePlayer
                || mc.thePlayer.inventory.currentItem != ownedQStatusSlot
                || !isActualBlitzPregame(mc)) {
            return false;
        }
        return isQStatusItem(mc.thePlayer.getHeldItem());
    }

    /**
     * Returns the formatted action bar text to display while holding the Queue Status skull,
     * or {@code null} when the skull is not currently held.
     */
    public static String getHeldQueueStatusHudText(Minecraft mc) {
        if (!isHoldingQueueStatusItem(mc)) {
            return null;
        }
        if (qStatusMissingApiKey) {
            return EnumChatFormatting.RED + "Hypixel API key not set (/gomod api hypixel <key>)";
        }
        QueueStatusSnapshot snapshot = cachedQStatus;
        if (snapshot != null) {
            String base = formatQueueStatusHudLine(snapshot);
            if (qStatusManualRefreshPending && qStatusFetchInFlight.get()) {
                return base + EnumChatFormatting.DARK_GRAY + " ...";
            }
            long shineStart = qStatusShineStartMs;
            if (shineStart > 0L) {
                long elapsed = System.currentTimeMillis() - shineStart;
                if (elapsed >= 0L && elapsed < QSTATUS_SHINE_DURATION_MS) {
                    float progress = (float) elapsed / (float) QSTATUS_SHINE_DURATION_MS;
                    return applyLeftToRightShine(base, progress);
                }
            }
            return base;
        }
        if (qStatusLastFetchFailed && !qStatusFetchInFlight.get()) {
            return EnumChatFormatting.RED + "Queue status unavailable";
        }
        return EnumChatFormatting.GRAY + "Loading queue status...";
    }

    /** Parses Hypixel {@code /gameCounts} JSON identically to {@code /qstatus}. */
    public static QueueStatusSnapshot parseQueueStatusResponse(JsonObject response, long fetchedAtMs) {
        if (response == null || !response.has("success") || !response.get("success").getAsBoolean()) {
            return null;
        }
        if (!response.has("games") || !response.get("games").isJsonObject()) {
            return null;
        }
        JsonObject games = response.getAsJsonObject("games");
        if (!games.has("SURVIVAL_GAMES") || !games.get("SURVIVAL_GAMES").isJsonObject()) {
            return null;
        }
        JsonObject survival = games.getAsJsonObject("SURVIVAL_GAMES");
        int totalPlayers = survival.has("players") ? survival.get("players").getAsInt() : 0;
        int soloPlayers = 0;
        int teamsPlayers = 0;
        if (survival.has("modes") && survival.get("modes").isJsonObject()) {
            JsonObject modes = survival.getAsJsonObject("modes");
            if (modes.has("solo_normal")) {
                soloPlayers = modes.get("solo_normal").getAsInt();
            }
            if (modes.has("teams_normal")) {
                teamsPlayers = modes.get("teams_normal").getAsInt();
            }
        }
        int lobbyPlayers = Math.max(0, totalPlayers - (soloPlayers + teamsPlayers));
        return new QueueStatusSnapshot(totalPlayers, soloPlayers, teamsPlayers, lobbyPlayers, fetchedAtMs);
    }

    public static String formatQueueStatusHudLine(QueueStatusSnapshot snapshot) {
        if (snapshot == null) {
            return EnumChatFormatting.GRAY + "Loading queue status...";
        }
        return EnumChatFormatting.WHITE + "Solo: " + EnumChatFormatting.AQUA + snapshot.soloPlayers
                + EnumChatFormatting.DARK_GRAY + " | "
                + EnumChatFormatting.WHITE + "Teams: " + EnumChatFormatting.BLUE + snapshot.teamsPlayers
                + EnumChatFormatting.DARK_GRAY + " | "
                + EnumChatFormatting.WHITE + "Lobby: " + EnumChatFormatting.YELLOW + snapshot.lobbyPlayers;
    }

    /**
     * Sweeps a bright white/gold highlight left-to-right across a §-formatted string
     * without shifting character positions or losing original colors.
     */
    public static String applyLeftToRightShine(String formatted, float progress) {
        if (formatted == null || formatted.isEmpty() || progress < 0.0f || progress >= 1.0f) {
            return formatted;
        }
        StringBuilder plain = new StringBuilder(formatted.length());
        String[] colors = new String[formatted.length()];
        int visibleCount = 0;
        String currentColor = EnumChatFormatting.WHITE.toString();
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                char code = formatted.charAt(i + 1);
                currentColor = "\u00a7" + code;
                i++;
                continue;
            }
            plain.append(c);
            colors[visibleCount++] = currentColor;
        }
        if (visibleCount == 0) {
            return formatted;
        }

        float head = progress * (visibleCount + 4.0f) - 2.0f;
        StringBuilder out = new StringBuilder(formatted.length() + visibleCount * 4);
        String lastPrefix = null;
        for (int i = 0; i < visibleCount; i++) {
            char c = plain.charAt(i);
            float dist = Math.abs(i - head);
            String prefix;
            if (c == ' ') {
                prefix = colors[i];
            } else if (dist <= 1.0f) {
                prefix = EnumChatFormatting.WHITE.toString() + EnumChatFormatting.BOLD;
            } else if (dist <= 2.4f) {
                prefix = EnumChatFormatting.YELLOW.toString();
            } else {
                prefix = colors[i];
            }
            if (!prefix.equals(lastPrefix)) {
                if (lastPrefix != null && lastPrefix.contains("\u00a7l") && !prefix.contains("\u00a7l")) {
                    out.append(EnumChatFormatting.RESET);
                }
                out.append(prefix);
                lastPrefix = prefix;
            }
            out.append(c);
        }
        return out.toString();
    }

    public static String requeueCommandForMode(boolean teams) {
        return teams ? "/play blitz_teams_normal" : "/play blitz_solo_normal";
    }

    private static void maybeRefreshQueueStatus(boolean force) {
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            qStatusMissingApiKey = true;
            cachedQStatus = null;
            return;
        }
        qStatusMissingApiKey = false;

        long now = System.currentTimeMillis();
        QueueStatusSnapshot existing = cachedQStatus;
        if (!force) {
            if (existing != null && now - existing.fetchedAtMs < QSTATUS_CACHE_TTL_MS) {
                return;
            }
            if (qStatusLastFetchFailed && now - lastQStatusErrorMs < QSTATUS_ERROR_BACKOFF_MS) {
                return;
            }
        }

        if (!qStatusFetchInFlight.compareAndSet(false, true)) {
            return;
        }

        final String trimmedKey = apiKey.trim();
        final boolean wasManual = force;
        Thread t = new Thread(() -> {
            boolean refreshedOk = false;
            try {
                JsonObject response = HypixelApiHelper.fetchJson(
                        "https://api.hypixel.net/gameCounts?key=" + URLEncoder.encode(trimmedKey, "UTF-8"));
                QueueStatusSnapshot parsed = parseQueueStatusResponse(response, System.currentTimeMillis());
                if (parsed != null) {
                    cachedQStatus = parsed;
                    qStatusLastFetchFailed = false;
                    refreshedOk = true;
                } else {
                    qStatusLastFetchFailed = true;
                    lastQStatusErrorMs = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {
                qStatusLastFetchFailed = true;
                lastQStatusErrorMs = System.currentTimeMillis();
            } finally {
                qStatusFetchInFlight.set(false);
                if (wasManual) {
                    qStatusManualRefreshPending = false;
                    if (refreshedOk) {
                        qStatusShineStartMs = System.currentTimeMillis();
                    }
                }
                final boolean playChime = wasManual && refreshedOk;
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    mc.addScheduledTask(() -> {
                        String text = getHeldQueueStatusHudText(mc);
                        if (text != null) {
                            pushActionBar(mc, text);
                            if (playChime && mc.thePlayer != null) {
                                mc.thePlayer.playSound("note.pling", 0.25F, 1.8F);
                            }
                        }
                    });
                }
            }
        }, "gomod-pregame-qstatus");
        t.setDaemon(true);
        t.start();
    }

    /** Applies a Pre-game tools toggle or slot change without waiting for another tick. */
    public static void onModuleSettingChanged() {
        if (!isAnyItemEnabled()) {
            deactivate();
            return;
        }
        if (!ConfigManager.isBlitzPregamePlayerToggleEnabled()
                || (ownedVisibilitySlot >= 0 && ownedVisibilitySlot != getVisibilitySlotIndex())) {
            restoreVisibilitySlot();
            if (!ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
                playersHidden = false;
            }
            publishHeldStateIfChanged();
        }
        if (!ConfigManager.isBlitzPregameQueueStatusItemEnabled()
                || (ownedQStatusSlot >= 0 && ownedQStatusSlot != getQueueStatusSlotIndex())) {
            restoreQStatusSlot();
        }
        if (!ConfigManager.isBlitzPregameRequeueItemEnabled()
                || (ownedRequeueSlot >= 0 && ownedRequeueSlot != getRequeueSlotIndex())) {
            restoreRequeueSlot();
        }
        if (!ConfigManager.isBlitzPregameStatusItemEnabled()
                || (ownedStatusSlot >= 0 && ownedStatusSlot != getStatusSlotIndex())) {
            restoreStatusSlot();
        }
    }

    private static boolean isAnyItemEnabled() {
        return ConfigManager.isBlitzPregamePlayerToggleEnabled()
                || ConfigManager.isBlitzPregameQueueStatusItemEnabled()
                || ConfigManager.isBlitzPregameRequeueItemEnabled()
                || ConfigManager.isBlitzPregameStatusItemEnabled();
    }

    public static boolean isTeamsPregame(Minecraft mc) {
        return BlitzTeamsDetector.isBlitzTeamsGame(mc) || BlitzTeamsDetector.isBlitzTeamsTabContext(mc);
    }

    private static ItemStack makeControlItem() {
        ItemStack stack = new ItemStack(Items.dye, 1, playersHidden ? 8 : 10);
        stack.setStackDisplayName(
                EnumChatFormatting.WHITE + "Players: "
                        + (playersHidden
                        ? EnumChatFormatting.RED + "Hidden"
                        : EnumChatFormatting.GREEN + "Visible")
                        + EnumChatFormatting.GRAY + " (Right Click)");
        return markItem(stack, ITEM_MARKER);
    }

    /** Creates a textured Blitz Solo or Teams NPC head icon (used by both the hotbar item and the settings GUI). */
    public static ItemStack createQueueStatusIcon(boolean teams) {
        ItemStack stack = new ItemStack(Items.skull, 1, 3);
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound skullOwner = new NBTTagCompound();
        skullOwner.setString("Id", teams ? TEAMS_NPC_PROFILE_ID : SOLO_NPC_PROFILE_ID);
        skullOwner.setString("Name", teams ? "gomod-qstatus-teams" : "gomod-qstatus-solo");
        NBTTagCompound properties = new NBTTagCompound();
        NBTTagList textures = new NBTTagList();
        NBTTagCompound texture = new NBTTagCompound();
        texture.setString("Value", teams ? TEAMS_NPC_TEXTURE_BASE64 : SOLO_NPC_TEXTURE_BASE64);
        textures.appendTag(texture);
        properties.setTag("textures", textures);
        skullOwner.setTag("Properties", properties);
        tag.setTag("SkullOwner", skullOwner);
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack makeQStatusItem(boolean teams) {
        ItemStack stack = createQueueStatusIcon(teams);
        stack.setStackDisplayName(
                EnumChatFormatting.WHITE + "Queue Status"
                        + EnumChatFormatting.GRAY + " (Right Click)");
        return markItem(stack, QSTATUS_ITEM_MARKER);
    }

    private static ItemStack makeRequeueItem(boolean teams) {
        ItemStack stack = new ItemStack(Items.paper, 1, 0);
        stack.setStackDisplayName(
                EnumChatFormatting.WHITE + "Requeue: "
                        + EnumChatFormatting.AQUA + (teams ? "Teams" : "Solo")
                        + EnumChatFormatting.GRAY + " (Right Click)");
        return markItem(stack, REQUEUE_ITEM_MARKER);
    }

    public static String formatStatusItemDisplayName(HypixelOnlineStatus status) {
        HypixelOnlineStatus effective = status != null ? status : HypixelOnlineStatus.ONLINE;
        return "\u00a7fStatus: "
                + effective.colorCode + effective.displayName
                + "\u00a77 (Right Click)";
    }

    /** Creates the music disc icon for a Hypixel online status (used by both the hotbar item and the settings GUI). */
    public static ItemStack createStatusIcon(HypixelOnlineStatus status) {
        HypixelOnlineStatus effective = status != null ? status : HypixelOnlineStatus.ONLINE;
        ItemStack stack = new ItemStack(effective.getDiscItem(), 1, 0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("HideFlags", 32);
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack makeStatusItem() {
        HypixelOnlineStatus status = getCurrentHypixelStatus();
        ItemStack stack = createStatusIcon(status);
        stack.setStackDisplayName(formatStatusItemDisplayName(status));
        return markItem(stack, STATUS_ITEM_MARKER);
    }

    private static ItemStack markItem(ItemStack stack, String marker) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
        }
        tag.setBoolean(marker, true);
        stack.setTagCompound(tag);
        return stack;
    }

    private static boolean isControlItem(ItemStack stack) {
        return stack != null && stack.getItem() == Items.dye && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(ITEM_MARKER);
    }

    private static boolean isQStatusItem(ItemStack stack) {
        return stack != null && stack.getItem() == Items.skull && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(QSTATUS_ITEM_MARKER);
    }

    private static boolean isRequeueItem(ItemStack stack) {
        return stack != null && stack.getItem() == Items.paper && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(REQUEUE_ITEM_MARKER);
    }

    private static boolean isStatusItem(ItemStack stack) {
        return stack != null && stack.getItem() != null && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(STATUS_ITEM_MARKER);
    }

    private static boolean isAnyControlItem(ItemStack stack) {
        return isControlItem(stack) || isQStatusItem(stack) || isRequeueItem(stack) || isStatusItem(stack);
    }

    private static ItemStack copyUnlessControl(ItemStack stack) {
        return stack == null || isAnyControlItem(stack) ? null : stack.copy();
    }

    /**
     * The controls belong only to the actual Blitz map waiting room. The broad
     * pregame detector also covers the games-hub queue, and the cage detector
     * marks the transition into the match; neither may retain the items/effects.
     */
    private static boolean isActualBlitzPregame(Minecraft mc) {
        return mc != null && mc.thePlayer != null && mc.theWorld != null
                && GameContext.isBlitzPregameLobby(mc)
                && !GameContext.isBlitzHypixelHubQueueSidebar(mc)
                && !GameContext.isBlitzCagePhaseSidebar(mc)
                && !GameContext.isBlitzGameActive(mc);
    }

    private static void deactivate() {
        restoreAllOwnedSlots();
        activePregame = false;
        playersHidden = false;
        lastSuccessfulUseMs = 0L;
        lastRequeueUseMs = 0L;
        lastQStatusManualRefreshMs = 0L;
        usePressConsumed = false;
        publishHeldStateIfChanged();
    }

    private static void restoreAllOwnedSlots() {
        restoreVisibilitySlot();
        restoreQStatusSlot();
        restoreRequeueSlot();
        restoreStatusSlot();
        itemOwner = null;
    }

    private static void restoreVisibilitySlot() {
        if (itemOwner != null && ownedVisibilitySlot >= 0
                && isControlItem(itemOwner.inventory.getStackInSlot(ownedVisibilitySlot))) {
            itemOwner.inventory.setInventorySlotContents(
                    ownedVisibilitySlot, displacedStack == null ? null : displacedStack.copy());
        }
        ownedVisibilitySlot = -1;
        displacedStack = null;
    }

    private static void restoreQStatusSlot() {
        if (itemOwner != null && ownedQStatusSlot >= 0
                && isQStatusItem(itemOwner.inventory.getStackInSlot(ownedQStatusSlot))) {
            itemOwner.inventory.setInventorySlotContents(
                    ownedQStatusSlot, displacedQStatusStack == null ? null : displacedQStatusStack.copy());
        }
        ownedQStatusSlot = -1;
        displacedQStatusStack = null;
    }

    private static void restoreRequeueSlot() {
        if (itemOwner != null && ownedRequeueSlot >= 0
                && isRequeueItem(itemOwner.inventory.getStackInSlot(ownedRequeueSlot))) {
            itemOwner.inventory.setInventorySlotContents(
                    ownedRequeueSlot, displacedRequeueStack == null ? null : displacedRequeueStack.copy());
        }
        ownedRequeueSlot = -1;
        displacedRequeueStack = null;
    }

    private static void restoreStatusSlot() {
        if (itemOwner != null && ownedStatusSlot >= 0
                && isStatusItem(itemOwner.inventory.getStackInSlot(ownedStatusSlot))) {
            itemOwner.inventory.setInventorySlotContents(
                    ownedStatusSlot, displacedStatusStack == null ? null : displacedStatusStack.copy());
        }
        ownedStatusSlot = -1;
        displacedStatusStack = null;
    }

    private static void publishHeldStateIfChanged() {
        int meta = getLocalHeldDyeMeta();
        if (meta == lastPublishedHeldMeta) {
            return;
        }
        lastPublishedHeldMeta = meta;
        try {
            Main.getGlobalChatClient().requestImmediatePoll();
        } catch (Throwable ignored) {
        }
        try {
            ShareInvManager.resendHelloIfConnected();
        } catch (Throwable ignored) {
        }
    }
}
