package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.client.multiplayer.WorldClient;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Logs opened chest snapshots to {@code tooltip_logs/} for offline merge scripts.
 * <ul>
 *   <li>{@link Kind#VAULT} — Blitz vault chests → {@code chest_dump_*.txt} (merge with vault items tool)</li>
 *   <li>{@link Kind#BLITZ_LOOT} — regular Blitz loot chests → {@code blitz_chest_dump_*.txt}</li>
 * </ul>
 * While logging, entering a new {@link WorldClient} clears block-coordinate dedupe and writes a
 * {@code world reset} banner so merge scripts treat each game as its own dedupe session.
 * <p>
 * Fast looting: caches each chest view while {@link GuiChest} is open, commits on close (or when
 * the GUI closes without a screen transition), locks the clicked block at open, and re-arms capture
 * when the same {@link GuiChest} instance shows a new block or loot layout (common when chaining chests).
 */
public final class ChestDumpLogger {

    public enum Kind {
        VAULT("chest_dump_", "Vault chest dump"),
        BLITZ_LOOT("blitz_chest_dump_", "Blitz chest dump");

        final String filePrefix;
        final String label;

        Kind(String filePrefix, String label) {
            this.filePrefix = filePrefix;
            this.label = label;
        }
    }

    /** How long after a right-click we still trust {@link #lastInteractedChestPos} for a new open. */
    private static final long CHEST_INTERACTION_TTL_MS = 2_500L;
    /** Ticks to keep trying capture while the chest GUI stays open (fast open/close). */
    private static final int OPEN_CAPTURE_RETRY_TICKS = 24;

    private static final class CachedSlot {
        int slot;
        String item = "none";
        int meta = -1;
        int amount;
        String display = "";
        String nbt = "";
        final List<String> tooltip = new ArrayList<String>();
    }

    private static final class PendingChestCapture {
        String containerName = "";
        int slotCount;
        final List<CachedSlot> slots = new ArrayList<CachedSlot>();
        /** Block clicked to open this chest; frozen at open so fast looting cannot steal a later click's coords. */
        BlockPos lockedChestPos;
        String fingerprint = "";
        /** Loot fingerprint last successfully written for this pending capture. */
        String committedFingerprint = "";
        boolean committed;
        int captureSeq;
    }

    private static final class Session {
        boolean enabled;
        String lastSnapshotFingerprint = "";
        File activeLogFile;
        int sessionChestSnapshotsLogged;
        final Set<String> sessionUniqueChestFingerprints = new HashSet<String>();
        /**
         * {@code blockX,blockY,blockZ|fingerprint} keys already logged this session.
         * Block-only dedupe caused false skips when fast looting assigned the wrong coords.
         */
        final Set<String> sessionSeenCaptureKeys = new HashSet<String>();
        PendingChestCapture pendingOpen;
        int openCaptureRetriesLeft;
        int nextCaptureSeq;
    }

    private static volatile BlockPos lastInteractedChestPos;
    private static volatile long lastInteractedChestPosMs;

    private static final Session VAULT_SESSION = new Session();
    private static final Session BLITZ_LOOT_SESSION = new Session();

    /** {@link System#identityHashCode} of {@link WorldClient} while logging; 0 = unknown / left world. */
    private static int lastVaultWorldIdentity;
    private static int lastBlitzLootWorldIdentity;

    public ChestDumpLogger() {
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (VAULT_SESSION.enabled) {
            detectWorldChangeWhileLogging(Kind.VAULT, VAULT_SESSION, mc);
            tickLootChestCapture(Kind.VAULT, VAULT_SESSION, mc);
        }
        if (BLITZ_LOOT_SESSION.enabled) {
            detectWorldChangeWhileLogging(Kind.BLITZ_LOOT, BLITZ_LOOT_SESSION, mc);
            tickLootChestCapture(Kind.BLITZ_LOOT, BLITZ_LOOT_SESSION, mc);
        }
    }

    /**
     * Called from {@code displayGuiScreen} HEAD while {@code currentScreen} is still the previous GUI.
     * Ensures we snapshot on chest close even when the chest was only open for a fraction of a tick.
     */
    public static void onDisplayGuiScreen(GuiScreen newScreen) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen oldScreen = mc.currentScreen;
        if (VAULT_SESSION.enabled) {
            handleChestScreenTransition(Kind.VAULT, VAULT_SESSION, mc, oldScreen, newScreen);
        }
        if (BLITZ_LOOT_SESSION.enabled) {
            handleChestScreenTransition(Kind.BLITZ_LOOT, BLITZ_LOOT_SESSION, mc, oldScreen, newScreen);
        }
    }

    public static synchronized boolean toggleFromCommand(Kind kind) {
        Session session = session(kind);
        setEnabled(kind, !session.enabled);
        return session.enabled;
    }

    public static synchronized void setEnabled(Kind kind, boolean value) {
        Session session = session(kind);
        if (session.enabled == value) {
            return;
        }
        session.enabled = value;
        session.lastSnapshotFingerprint = "";
        session.pendingOpen = null;
        session.openCaptureRetriesLeft = 0;
        if (kind == Kind.BLITZ_LOOT) {
            BlitzChestDumpLoggedOverlay.clear();
        }
        if (value) {
            session.sessionChestSnapshotsLogged = 0;
            session.sessionUniqueChestFingerprints.clear();
            session.sessionSeenCaptureKeys.clear();
            session.nextCaptureSeq = 0;
            session.activeLogFile = createSessionLogFile(kind);
            resetTrackedWorldIdentity(kind, mcWorldIdentity(Minecraft.getMinecraft()));
            writeSessionBanner(kind, session, "enabled");
            String chatHint = kind == Kind.BLITZ_LOOT
                    ? " — chat confirm + green overlay on logged chests"
                    : "";
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + kind.label + " "
                    + EnumChatFormatting.GREEN + "enabled"
                    + EnumChatFormatting.GRAY + " (coords reset each new world)" + chatHint
                    + EnumChatFormatting.YELLOW + " -> "
                    + EnumChatFormatting.WHITE + getActiveLogPath(session));
            return;
        }
        resetTrackedWorldIdentity(kind, 0);
        writeSessionBanner(kind, session, "disabled");
        writeSessionSummaryFooter(session);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + kind.label + " "
                + EnumChatFormatting.RED + "disabled"
                + EnumChatFormatting.YELLOW + " -> "
                + EnumChatFormatting.WHITE + getActiveLogPath(session));
    }

    public static synchronized boolean isEnabled(Kind kind) {
        return session(kind).enabled;
    }

    /** @deprecated use {@link #isEnabled(Kind)} */
    public static synchronized boolean isEnabled() {
        return isEnabled(Kind.VAULT);
    }

    /** @deprecated use {@link #toggleFromCommand(Kind)} */
    public static synchronized boolean toggleFromCommand() {
        return toggleFromCommand(Kind.VAULT);
    }

    /** @deprecated use {@link #setEnabled(Kind, boolean)} */
    public static synchronized void setEnabled(boolean value) {
        setEnabled(Kind.VAULT, value);
    }

    private static Session session(Kind kind) {
        return kind == Kind.BLITZ_LOOT ? BLITZ_LOOT_SESSION : VAULT_SESSION;
    }

    private static int trackedWorldIdentity(Kind kind) {
        return kind == Kind.BLITZ_LOOT ? lastBlitzLootWorldIdentity : lastVaultWorldIdentity;
    }

    private static void resetTrackedWorldIdentity(Kind kind, int identity) {
        if (kind == Kind.BLITZ_LOOT) {
            lastBlitzLootWorldIdentity = identity;
        } else {
            lastVaultWorldIdentity = identity;
        }
    }

    private static int mcWorldIdentity(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return 0;
        }
        return System.identityHashCode(mc.theWorld);
    }

    /**
     * Clears per-world block coord dedupe when the client loads a different {@link WorldClient}
     * (new Blitz map, lobby, reconnect) so logging can stay enabled across games.
     */
    private static void detectWorldChangeWhileLogging(Kind kind, Session session, Minecraft mc) {
        int identity = mcWorldIdentity(mc);
        int previous = trackedWorldIdentity(kind);
        if (identity == previous) {
            return;
        }
        resetTrackedWorldIdentity(kind, identity);
        if (previous == 0) {
            return;
        }
        if (identity == 0) {
            clearSpatialDedupeState(session);
            if (kind == Kind.BLITZ_LOOT) {
                BlitzChestDumpLoggedOverlay.clear();
            }
            return;
        }
        onNewWorldWhileLogging(kind, session);
    }

    private static void clearSpatialDedupeState(Session session) {
        session.lastSnapshotFingerprint = "";
        session.sessionSeenCaptureKeys.clear();
        session.pendingOpen = null;
        session.openCaptureRetriesLeft = 0;
    }

    private static void onNewWorldWhileLogging(Kind kind, Session session) {
        clearSpatialDedupeState(session);
        if (kind == Kind.BLITZ_LOOT) {
            BlitzChestDumpLoggedOverlay.clear();
        }
        writeWorldResetBanner(kind, session);
        if (kind == Kind.BLITZ_LOOT) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz chest dump: "
                    + EnumChatFormatting.GRAY + "new world — block coords reset "
                    + EnumChatFormatting.DARK_GRAY + "(still logging)");
        }
    }

    private static synchronized void writeWorldResetBanner(Kind kind, Session session) {
        ensureLogFile(kind, session);
        if (session.activeLogFile == null) {
            return;
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (Writer writer = openLogWriter(session.activeLogFile, true)) {
            writer.write("=== world reset @ " + timestamp + " (" + kind.name() + ") ==="
                    + System.lineSeparator());
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    /** Called from right-click mixin when the player clicks a chest block. */
    public static void noteChestBlockInteraction(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return;
        }
        try {
            Block block = world.getBlockState(pos).getBlock();
            if (block instanceof BlockChest) {
                lastInteractedChestPos = new BlockPos(pos);
                lastInteractedChestPosMs = System.currentTimeMillis();
                if (BLITZ_LOOT_SESSION.enabled) {
                    maybeRearmOnChestClick(BLITZ_LOOT_SESSION, new BlockPos(pos));
                }
                if (VAULT_SESSION.enabled) {
                    maybeRearmOnChestClick(VAULT_SESSION, new BlockPos(pos));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleChestScreenTransition(Kind kind, Session session, Minecraft mc,
                                                    GuiScreen oldScreen, GuiScreen newScreen) {
        boolean wasLootChest = isLootChestScreen(mc, oldScreen);
        boolean isLootChest = isLootChestScreen(mc, newScreen);
        if (wasLootChest && isLootChest) {
            if (oldScreen != newScreen) {
                onLootChestGuiClosed(kind, session, mc, oldScreen);
                onLootChestGuiOpened(kind, session, mc);
            } else {
                IInventory lower = getLowerChestInventoryFromScreen(newScreen);
                if (lower == null) {
                    lower = getLowerChestInventory(mc);
                }
                if (lower != null && acceptsContainerForKind(kind, lower)) {
                    refreshPendingFromInventory(kind, session, mc, lower);
                }
            }
            return;
        }
        if (wasLootChest && !isLootChest) {
            onLootChestGuiClosed(kind, session, mc, oldScreen);
        }
        if (!wasLootChest && isLootChest) {
            onLootChestGuiOpened(kind, session, mc);
        }
    }

    private static void tickLootChestCapture(Kind kind, Session session, Minecraft mc) {
        if (!isLootChestScreen(mc, mc.currentScreen)) {
            if (session.pendingOpen != null && !session.pendingOpen.committed) {
                tryCommitPendingCapture(kind, session, mc, true);
            }
            session.pendingOpen = null;
            session.openCaptureRetriesLeft = 0;
            session.lastSnapshotFingerprint = "";
            return;
        }
        IInventory lower = getLowerChestInventory(mc);
        if (lower == null || !acceptsContainerForKind(kind, lower)) {
            return;
        }
        if (session.pendingOpen == null) {
            onLootChestGuiOpened(kind, session, mc);
        }
        refreshPendingFromInventory(kind, session, mc, lower);
    }

    private static void startNewPendingCapture(Session session, BlockPos lockPos) {
        PendingChestCapture pending = new PendingChestCapture();
        pending.captureSeq = ++session.nextCaptureSeq;
        pending.lockedChestPos = lockPos != null ? new BlockPos(lockPos) : recentInteractedChestPos();
        session.pendingOpen = pending;
        session.openCaptureRetriesLeft = OPEN_CAPTURE_RETRY_TICKS;
        session.lastSnapshotFingerprint = "";
    }

    private static void onLootChestGuiOpened(Kind kind, Session session, Minecraft mc) {
        if (session.pendingOpen != null && !session.pendingOpen.committed) {
            tryCommitPendingCapture(kind, session, mc, true);
        }
        startNewPendingCapture(session, recentInteractedChestPos());
        IInventory lower = getLowerChestInventory(mc);
        if (lower != null && acceptsContainerForKind(kind, lower)) {
            refreshPendingFromInventory(kind, session, mc, lower);
        }
    }

    /**
     * Right-click on a different chest block while a prior capture is already committed
     * (same {@link GuiChest} instance or fast chaining).
     */
    private static void maybeRearmOnChestClick(Session session, BlockPos clicked) {
        PendingChestCapture pending = session.pendingOpen;
        if (pending == null || !pending.committed || clicked == null) {
            return;
        }
        if (pending.lockedChestPos != null && pending.lockedChestPos.equals(clicked)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        startNewPendingCapture(session, clicked);
        IInventory lower = getLowerChestInventory(mc);
        if (lower != null) {
            refreshPendingFromInventory(
                    session == BLITZ_LOOT_SESSION ? Kind.BLITZ_LOOT : Kind.VAULT, session, mc, lower);
        }
    }

    /** Committed capture but container loot or clicked block changed — start a fresh pending snapshot. */
    private static void maybeRearmAfterCommittedChestChange(Kind kind, Session session, Minecraft mc,
                                                            PendingChestCapture pending) {
        if (pending == null || !pending.committed || pending.fingerprint.isEmpty()) {
            return;
        }
        BlockPos recent = recentInteractedChestPos();
        boolean posChanged = recent != null && pending.lockedChestPos != null
                && !pending.lockedChestPos.equals(recent);
        boolean lootChanged = pending.committedFingerprint != null
                && !pending.committedFingerprint.equals(pending.fingerprint);
        if (!posChanged && !lootChanged) {
            return;
        }
        startNewPendingCapture(session, recent != null ? recent : pending.lockedChestPos);
        IInventory lower = getLowerChestInventory(mc);
        if (lower != null && acceptsContainerForKind(kind, lower)) {
            refreshPendingFromInventory(kind, session, mc, lower);
        }
    }

    /**
     * Chained chest opens without closing {@link GuiChest}: a new block was clicked while the
     * previous chest snapshot is still pending.
     */
    private static void maybeCommitAndRearmOnNewChestBlock(Kind kind, Session session, Minecraft mc,
                                                           IInventory lower, String previousFp) {
        PendingChestCapture pending = session.pendingOpen;
        if (pending == null || pending.committed || previousFp.isEmpty() || pending.fingerprint.isEmpty()) {
            return;
        }
        BlockPos recent = recentInteractedChestPos();
        if (recent == null || pending.lockedChestPos == null || pending.lockedChestPos.equals(recent)) {
            return;
        }
        tryCommitPendingCapture(kind, session, mc, true);
        startNewPendingCapture(session, recent);
        refreshPendingFromInventory(kind, session, mc, lower);
    }

    private static void onLootChestGuiClosed(Kind kind, Session session, Minecraft mc, GuiScreen closingScreen) {
        IInventory lower = getLowerChestInventoryFromScreen(closingScreen);
        if (lower == null) {
            lower = getLowerChestInventory(mc);
        }
        if (lower != null && acceptsContainerForKind(kind, lower)) {
            refreshPendingFromInventory(kind, session, mc, lower);
        }
        tryCommitPendingCapture(kind, session, mc, true);
        session.pendingOpen = null;
        session.openCaptureRetriesLeft = 0;
        session.lastSnapshotFingerprint = "";
    }

    private static boolean isLootChestScreen(Minecraft mc, GuiScreen screen) {
        return mc != null && mc.thePlayer != null && screen instanceof GuiChest;
    }

    private static IInventory getLowerChestInventory(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || !(mc.thePlayer.openContainer instanceof ContainerChest)) {
            return null;
        }
        return ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory();
    }

    private static IInventory getLowerChestInventoryFromScreen(GuiScreen screen) {
        if (!(screen instanceof GuiChest)) {
            return null;
        }
        try {
            if (((GuiChest) screen).inventorySlots instanceof ContainerChest) {
                return ((ContainerChest) ((GuiChest) screen).inventorySlots).getLowerChestInventory();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Blitz loot dumps only want survival chests, not other {@link GuiChest} UIs. */
    private static boolean acceptsContainerForKind(Kind kind, IInventory lower) {
        if (lower == null) {
            return false;
        }
        if (kind != Kind.BLITZ_LOOT) {
            return true;
        }
        return "Chest".equals(getContainerName(lower));
    }

    private static void refreshPendingFromInventory(Kind kind, Session session, Minecraft mc, IInventory lower) {
        if (session.pendingOpen == null || lower == null || !acceptsContainerForKind(kind, lower)) {
            return;
        }
        PendingChestCapture pending = session.pendingOpen;
        pending.containerName = getContainerName(lower);
        pending.slotCount = lower.getSizeInventory();
        if (pending.lockedChestPos == null) {
            pending.lockedChestPos = recentInteractedChestPos();
        }
        if (pending.lockedChestPos == null) {
            pending.lockedChestPos = resolveNearestOpenChestBlockPos(mc);
        }
        pending.slots.clear();
        int validSlots = 0;
        for (int i = 0; i < lower.getSizeInventory(); i++) {
            CachedSlot cached = cacheSlot(mc, i, lower.getStackInSlot(i));
            pending.slots.add(cached);
            if (cached.amount > 0 && cached.meta >= 0 && cached.item != null
                    && !"none".equals(cached.item) && !"unknown".equals(cached.item)) {
                validSlots++;
            }
        }
        if (validSlots > 0) {
            String previousFp = pending.fingerprint;
            String newFp = buildSnapshotFingerprint(mc, lower);
            pending.fingerprint = newFp;
            session.lastSnapshotFingerprint = newFp;
            maybeCommitAndRearmOnNewChestBlock(kind, session, mc, lower, previousFp);
            pending = session.pendingOpen;
            if (pending != null) {
                maybeRearmAfterCommittedChestChange(kind, session, mc, pending);
            }
        }
    }

    private static CachedSlot cacheSlot(Minecraft mc, int slotIndex, ItemStack stack) {
        CachedSlot cached = new CachedSlot();
        cached.slot = slotIndex;
        NBTTagCompound serialized = new NBTTagCompound();
        if (stack == null || stack.getItem() == null) {
            return cached;
        }
        Item item = stack.getItem();
        cached.item = item == null || Item.itemRegistry == null
                ? "unknown"
                : String.valueOf(Item.itemRegistry.getNameForObject(item));
        cached.meta = stack.getMetadata();
        cached.amount = stack.stackSize;
        cached.display = safe(stack.getDisplayName());
        try {
            stack.writeToNBT(serialized);
        } catch (Throwable ignored) {
        }
        cached.nbt = serialized.toString();
        List<String> tooltip = new ArrayList<String>();
        try {
            tooltip = stack.getTooltip(mc.thePlayer,
                    mc.gameSettings != null && mc.gameSettings.advancedItemTooltips);
        } catch (Throwable ignored) {
        }
        for (String line : tooltip) {
            String stripped = stripFormatting(line);
            if (!stripped.isEmpty()) {
                cached.tooltip.add(stripped);
            }
        }
        return cached;
    }

    private enum CommitOutcome {
        WRITTEN,
        SKIPPED_DUPLICATE,
        SKIPPED_NO_LOOT,
        SKIPPED_NO_FILE,
        WRITE_FAILED
    }

    private static CommitOutcome tryCommitPendingCapture(Kind kind, Session session, Minecraft mc, boolean fromClose) {
        PendingChestCapture pending = session.pendingOpen;
        if (pending == null || pending.committed) {
            return null;
        }
        if (!hasValidLootSlots(pending)) {
            if (fromClose && kind == Kind.BLITZ_LOOT) {
                notifyBlitzChestCaptureChat(session, pending, CommitOutcome.SKIPPED_NO_LOOT, null);
            }
            pending.committed = true;
            return CommitOutcome.SKIPPED_NO_LOOT;
        }
        String captureKey = captureDedupeKey(pending);
        if (captureKey != null && session.sessionSeenCaptureKeys.contains(captureKey)) {
            pending.committed = true;
            if (kind == Kind.BLITZ_LOOT) {
                notifyBlitzChestCaptureChat(session, pending, CommitOutcome.SKIPPED_DUPLICATE, captureKey);
            }
            return CommitOutcome.SKIPPED_DUPLICATE;
        }
        if (!fromClose) {
            return null;
        }
        ensureLogFile(kind, session);
        if (session.activeLogFile == null) {
            if (kind == Kind.BLITZ_LOOT) {
                notifyBlitzChestCaptureChat(session, pending, CommitOutcome.SKIPPED_NO_FILE, null);
            }
            pending.committed = true;
            return CommitOutcome.SKIPPED_NO_FILE;
        }
        boolean written = writeSnapshotFromPending(mc, pending, session);
        pending.committed = true;
        pending.committedFingerprint = pending.fingerprint;
        if (!written) {
            if (kind == Kind.BLITZ_LOOT) {
                notifyBlitzChestCaptureChat(session, pending, CommitOutcome.WRITE_FAILED, null);
            }
            return CommitOutcome.WRITE_FAILED;
        }
        if (captureKey != null) {
            session.sessionSeenCaptureKeys.add(captureKey);
        }
        if (!pending.fingerprint.isEmpty()) {
            session.sessionUniqueChestFingerprints.add(pending.fingerprint);
        }
        if (kind == Kind.BLITZ_LOOT) {
            notifyBlitzChestCaptureChat(session, pending, CommitOutcome.WRITTEN, captureKey);
            if (pending.lockedChestPos != null) {
                BlitzChestDumpLoggedOverlay.markLogged(pending.lockedChestPos);
            }
        }
        return CommitOutcome.WRITTEN;
    }

    private static String captureDedupeKey(PendingChestCapture pending) {
        if (pending == null) {
            return null;
        }
        String block = chestBlockKey(pending.lockedChestPos);
        if (block != null && !pending.fingerprint.isEmpty()) {
            return block + "|" + pending.fingerprint;
        }
        if (!pending.fingerprint.isEmpty()) {
            return "fp:" + pending.fingerprint;
        }
        if (block != null) {
            return "pos:" + block + "|seq:" + pending.captureSeq;
        }
        return "seq:" + pending.captureSeq;
    }

    private static boolean hasValidLootSlots(PendingChestCapture pending) {
        for (CachedSlot slot : pending.slots) {
            if (slot.amount > 0 && slot.meta >= 0 && slot.item != null
                    && !"none".equals(slot.item) && !"unknown".equals(slot.item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean writeSnapshotFromPending(Minecraft mc, PendingChestCapture pending, Session session) {
        session.sessionChestSnapshotsLogged++;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        int nonEmpty = 0;
        for (CachedSlot slot : pending.slots) {
            if (slot.amount > 0) {
                nonEmpty++;
            }
        }
        try (Writer writer = openLogWriter(session.activeLogFile, true)) {
            writer.write("=== " + timestamp + " ===" + System.lineSeparator());
            writer.write("screen=GuiChest" + System.lineSeparator());
            writer.write("container=" + pending.containerName + System.lineSeparator());
            writer.write("slot_count=" + pending.slotCount + System.lineSeparator());
            writer.write("non_empty_slots=" + nonEmpty + System.lineSeparator());
            writer.write("capture_seq=" + pending.captureSeq + System.lineSeparator());
            if (pending.lockedChestPos != null) {
                writer.write("chest_block_x=" + pending.lockedChestPos.getX() + System.lineSeparator());
                writer.write("chest_block_y=" + pending.lockedChestPos.getY() + System.lineSeparator());
                writer.write("chest_block_z=" + pending.lockedChestPos.getZ() + System.lineSeparator());
            }
            writer.write(System.lineSeparator());
            for (CachedSlot slot : pending.slots) {
                writeCachedSlotBlock(writer, slot);
            }
            writer.write(System.lineSeparator());
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Per-chest chat confirmation while {@link Kind#BLITZ_LOOT} logging is on. */
    private static void notifyBlitzChestCaptureChat(Session session, PendingChestCapture pending,
                                                    CommitOutcome outcome, String captureKey) {
        String coords = formatChestCoords(pending);
        int lootCount = countNonEmptyLootSlots(pending);
        String logName = session.activeLogFile == null ? "?" : session.activeLogFile.getName();

        switch (outcome) {
            case WRITTEN:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Chest #" + pending.captureSeq
                        + " logged" + EnumChatFormatting.GRAY + " @ " + EnumChatFormatting.WHITE + coords
                        + EnumChatFormatting.GRAY + " (" + lootCount + " item stack"
                        + (lootCount == 1 ? "" : "s") + ")"
                        + EnumChatFormatting.DARK_GRAY + " -> " + EnumChatFormatting.YELLOW + logName
                        + EnumChatFormatting.GREEN + " (green overlay)");
                sendBlitzChestItemLines(pending);
                break;
            case SKIPPED_DUPLICATE:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Chest #" + pending.captureSeq
                        + " skipped" + EnumChatFormatting.GRAY + " @ " + EnumChatFormatting.WHITE + coords
                        + EnumChatFormatting.GRAY + " — duplicate pos+loot this session"
                        + EnumChatFormatting.DARK_GRAY + " (not written again)");
                sendBlitzChestItemLines(pending);
                if (captureKey != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "dedupe: " + captureKey);
                }
                break;
            case SKIPPED_NO_LOOT:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Chest #" + pending.captureSeq
                        + " not logged" + EnumChatFormatting.GRAY + " @ " + EnumChatFormatting.WHITE + coords
                        + EnumChatFormatting.GRAY + " — empty or unreadable loot");
                break;
            case SKIPPED_NO_FILE:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Chest #" + pending.captureSeq
                        + " not logged" + EnumChatFormatting.GRAY + " @ " + EnumChatFormatting.WHITE + coords
                        + EnumChatFormatting.RED + " — could not open log file");
                break;
            case WRITE_FAILED:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Chest #" + pending.captureSeq
                        + " write failed" + EnumChatFormatting.GRAY + " @ " + EnumChatFormatting.WHITE + coords
                        + EnumChatFormatting.GRAY + " — check disk / " + EnumChatFormatting.YELLOW + logName);
                sendBlitzChestItemLines(pending);
                break;
            default:
                break;
        }
    }

    private static void sendBlitzChestItemLines(PendingChestCapture pending) {
        for (CachedSlot slot : pending.slots) {
            if (slot.amount <= 0 || slot.meta < 0 || slot.item == null
                    || "none".equals(slot.item) || "unknown".equals(slot.item)) {
                continue;
            }
            String label = slot.display == null || slot.display.isEmpty()
                    ? slot.item
                    : stripFormatting(slot.display);
            if (label.isEmpty()) {
                label = slot.item;
            }
            String extra = "";
            if (!slot.tooltip.isEmpty()) {
                String tip = slot.tooltip.get(0);
                if (tip != null && !tip.isEmpty() && !tip.equals(label)) {
                    extra = EnumChatFormatting.DARK_GRAY + " [" + tip + "]";
                }
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "  slot " + slot.slot + ": "
                    + EnumChatFormatting.WHITE + label
                    + EnumChatFormatting.GRAY + " x" + slot.amount
                    + EnumChatFormatting.DARK_GRAY + " (" + slot.item + ":" + slot.meta + ")"
                    + extra);
        }
    }

    private static String formatChestCoords(PendingChestCapture pending) {
        if (pending == null || pending.lockedChestPos == null) {
            return "unknown";
        }
        BlockPos p = pending.lockedChestPos;
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static int countNonEmptyLootSlots(PendingChestCapture pending) {
        int count = 0;
        for (CachedSlot slot : pending.slots) {
            if (slot.amount > 0 && slot.meta >= 0 && slot.item != null
                    && !"none".equals(slot.item) && !"unknown".equals(slot.item)) {
                count++;
            }
        }
        return count;
    }

    private static void writeCachedSlotBlock(Writer writer, CachedSlot slot) throws IOException {
        writer.write("slot=" + slot.slot + System.lineSeparator());
        boolean present = slot.amount > 0 && slot.meta >= 0 && slot.item != null
                && !"none".equals(slot.item) && !"unknown".equals(slot.item);
        writer.write("stack_present=" + present + System.lineSeparator());
        writer.write("item=" + slot.item + System.lineSeparator());
        writer.write("meta=" + slot.meta + System.lineSeparator());
        writer.write("amount=" + slot.amount + System.lineSeparator());
        writer.write("display=" + slot.display + System.lineSeparator());
        writer.write("formatted_tooltip:" + System.lineSeparator());
        for (int i = 0; i < slot.tooltip.size(); i++) {
            writer.write((i + 1) + ": " + slot.tooltip.get(i) + System.lineSeparator());
        }
        writer.write("stripped_tooltip:" + System.lineSeparator());
        for (int i = 0; i < slot.tooltip.size(); i++) {
            writer.write((i + 1) + ": " + slot.tooltip.get(i) + System.lineSeparator());
        }
        writer.write("nbt=" + slot.nbt + System.lineSeparator());
        writer.write(System.lineSeparator());
    }

    private static String buildSnapshotFingerprint(Minecraft mc, IInventory lower) {
        StringBuilder builder = new StringBuilder();
        builder.append(mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName()).append('|');
        builder.append(getContainerName(lower)).append('|');
        builder.append(lower.getSizeInventory()).append('|');
        for (int i = 0; i < lower.getSizeInventory(); i++) {
            ItemStack stack = lower.getStackInSlot(i);
            if (stack == null) {
                builder.append(i).append(":empty|");
                continue;
            }
            NBTTagCompound serialized = new NBTTagCompound();
            try {
                stack.writeToNBT(serialized);
            } catch (Throwable ignored) {
            }
            builder.append(i).append(':')
                    .append(stack.getItem()).append(':')
                    .append(stack.getMetadata()).append(':')
                    .append(stack.stackSize).append(':')
                    .append(serialized).append('|');
        }
        return builder.toString();
    }

    private static synchronized void writeSessionSummaryFooter(Session session) {
        if (session.activeLogFile == null) {
            return;
        }
        try (Writer writer = openLogWriter(session.activeLogFile, true)) {
            writer.write("=== chest dump session summary ===" + System.lineSeparator());
            writer.write("chest_snapshots_logged=" + session.sessionChestSnapshotsLogged + System.lineSeparator());
            writer.write("unique_chest_layouts=" + session.sessionUniqueChestFingerprints.size() + System.lineSeparator());
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    private static synchronized void writeSessionBanner(Kind kind, Session session, String state) {
        ensureLogFile(kind, session);
        if (session.activeLogFile == null) {
            return;
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (Writer writer = openLogWriter(session.activeLogFile, true)) {
            writer.write("=== logger " + state + " @ " + timestamp + " (" + kind.name() + ") ==="
                    + System.lineSeparator());
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    private static void ensureLogFile(Kind kind, Session session) {
        if (session.activeLogFile == null) {
            session.activeLogFile = createSessionLogFile(kind);
        }
    }

    private static File createSessionLogFile(Kind kind) {
        File dir = chestLogsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, kind.filePrefix + timestamp + ".txt");
        try {
            if (!file.exists() && !file.createNewFile()) {
                return null;
            }
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Writer openLogWriter(File file, boolean append) throws IOException {
        return new OutputStreamWriter(new FileOutputStream(file, append), StandardCharsets.UTF_8);
    }

    private static File chestLogsDir() {
        return new File(GomodPaths.getGomod123Dir(), "tooltip_logs");
    }

    private static String getContainerName(IInventory lower) {
        try {
            if (lower == null || lower.getDisplayName() == null) {
                return "none";
            }
            return safe(lower.getDisplayName().getUnformattedText());
        } catch (Throwable ignored) {
            return "none";
        }
    }

    private static String getActiveLogPath(Session session) {
        return session.activeLogFile == null ? chestLogsDir().getAbsolutePath() : session.activeLogFile.getAbsolutePath();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String stripFormatting(String value) {
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        return stripped == null ? "" : stripped;
    }

    private static BlockPos recentInteractedChestPos() {
        long now = System.currentTimeMillis();
        BlockPos recent = lastInteractedChestPos;
        if (recent != null && now - lastInteractedChestPosMs <= CHEST_INTERACTION_TTL_MS) {
            return new BlockPos(recent);
        }
        return null;
    }

    /** Fallback when the right-click mixin did not record a block (e.g. automated open). */
    private static BlockPos resolveNearestOpenChestBlockPos(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        BlockPos recent = recentInteractedChestPos();
        if (recent != null) {
            return recent;
        }
        try {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            double px = mc.thePlayer.posX;
            double py = mc.thePlayer.posY;
            double pz = mc.thePlayer.posZ;
            for (Object obj : mc.theWorld.loadedTileEntityList) {
                if (!(obj instanceof TileEntityChest)) {
                    continue;
                }
                TileEntityChest chest = (TileEntityChest) obj;
                if (!isChestVisuallyOpen(chest)) {
                    continue;
                }
                BlockPos pos = chest.getPos();
                if (pos == null) {
                    continue;
                }
                double dx = (pos.getX() + 0.5D) - px;
                double dy = (pos.getY() + 0.5D) - py;
                double dz = (pos.getZ() + 0.5D) - pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = pos;
                }
            }
            return best == null ? null : new BlockPos(best);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isChestVisuallyOpen(TileEntityChest chest) {
        if (chest == null) {
            return false;
        }
        try {
            return chest.numPlayersUsing > 0 || chest.lidAngle > 0.02f || chest.prevLidAngle > 0.02f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String chestBlockKey(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
