package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blocks Finder — ESP overlay for user-defined block types.
 */
public final class BlocksFinder {

    public static final String MODE_OUTLINE        = "OUTLINE";
    public static final String MODE_FILLED         = "FILLED";
    public static final String MODE_OUTLINE_FILLED = "OUTLINE_FILLED";
    /** Legacy config value — migrated to {@link #MODE_OUTLINE_FILLED}. */
    private static final String MODE_CHAMS = "CHAMS";

    public static final String[] MODES = {
        MODE_OUTLINE, MODE_FILLED, MODE_OUTLINE_FILLED
    };
    public static final String[] MODE_LABELS = {
        "Outline", "Filled", "Outline + Fill"
    };

    public static final int FILL_ALPHA_MIN = 5;
    public static final int FILL_ALPHA_MAX = 255;
    public static final int WORLD_OPACITY_MIN = 0;
    public static final int WORLD_OPACITY_MAX = 100;

    /** Often-used blocks shown first in the picker. */
    public static final String[] COMMON_BLOCK_IDS = {
        "minecraft:chest",
        "minecraft:trapped_chest",
        "minecraft:crafting_table",
        "minecraft:enchanting_table"
    };

    private static final int MAX_FOUND          = 256;
    /** Only draw targets within this distance; scan radius matches this to avoid full-world spikes. */
    private static final int MAX_RENDER_DIST    = 96;
    private static final int MAX_RENDER_DIST_SQ = MAX_RENDER_DIST * MAX_RENDER_DIST;
    private static final int SCAN_CHUNK_RADIUS_MAX = (MAX_RENDER_DIST >> 4) + 1;
    /** Chunks scanned per tick — spreads work so the main thread never stalls. */
    private static final int CHUNKS_PER_TICK = 6;
    private static final int MAX_FOUND_PER_CHUNK = 48;
    /** Ticks to wait after a full scan pass before starting the next (keeps ESP stable). */
    private static final int TICKS_BETWEEN_SCAN_PASSES = 20;
    private static final int ALLOWED_CACHE_TICKS = 10;

    private static final class FoundEntry {
        final BlockPos pos;
        final String blockId;

        FoundEntry(BlockPos pos, String blockId) {
            this.pos = pos;
            this.blockId = blockId;
        }
    }

    private static Set<String> trackedIds = new HashSet<String>();
    private static Set<Block> trackedBlocks = new HashSet<Block>();
    private static Map<Block, String> trackedBlockIdByBlock = Collections.emptyMap();
    private static volatile List<FoundEntry> foundEntries = Collections.emptyList();
    private static boolean keyWasDown = false;
    private static boolean lastBlocksFinderAllowed = true;
    private static int allowedCacheTicks = 0;
    private static boolean cachedAllowed = false;
    private static int scanOriginChunkX = Integer.MIN_VALUE;
    private static int scanOriginChunkZ = Integer.MIN_VALUE;
    private static int scanChunkRadius = -1;
    private static int scanChunkIndex = 0;
    private static int ticksUntilNextScanPass = 0;
    private static final ArrayList<Long> scanChunkCoords = new ArrayList<Long>();
    private static final Map<Long, List<FoundEntry>> scanResultsByChunk = new LinkedHashMap<Long, List<FoundEntry>>();
    private static List<BlockTypeHudRow> hudRowTemplate = Collections.emptyList();
    /** True while drawing Block Finder overlay (chams/ESP) — skips world-opacity mixin. */
    private static boolean overlayRenderPass = false;

    private static final ThreadLocal<Deque<Boolean>> worldOpacityPatchStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    /** Nested {@code renderBlock} calls must not clobber the outer vertex range. */
    private static final ThreadLocal<Deque<Integer>> worldOpacityVertexStartStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static Field worldOpacityByteBufferField;

    static {
        for (String n : new String[]{"byteBuffer", "field_178999_b", "field_179010_h"}) {
            try {
                Field f = WorldRenderer.class.getDeclaredField(n);
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    worldOpacityByteBufferField = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private BlocksFinder() {}

    public static boolean isEnabled() {
        return ConfigManager.isBlocksFinderEnabled();
    }

    /**
     * Block Finder runs only in singleplayer and Hypixel replay spectator mode.
     * Unlike advanced perspective, it does not work in Hypixel lobbies or Blitz pregame.
     */
    public static boolean isBlocksFinderAllowed(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        if (GameContext.isOnHypixel(mc)) {
            if (GameContext.isHypixelLobby(mc) || GameContext.isBlitzPregameLobby(mc)) {
                return false;
            }
            return GameContext.isHypixelReplayWatching(mc) || ReplaySettings.isReplayActive();
        }
        return mc.isSingleplayer();
    }

    /** Config enabled and current world context allows scanning/rendering. */
    public static boolean isActive() {
        return isEnabled() && cachedAllowed;
    }

    public static void setEnabled(boolean v) {
        ConfigManager.setBlocksFinderEnabled(v);
        refreshAllowedCacheNow(Minecraft.getMinecraft());
        if (!v) {
            resetScanState();
        }
        if (!v) {
            BlocksFinderWorldOpacityAtlasHook.restoreAll();
        } else if (ConfigManager.getBlocksFinderWorldOpacity() < WORLD_OPACITY_MAX) {
            BlocksFinderWorldOpacityAtlasHook.applyPercent(ConfigManager.getBlocksFinderWorldOpacity());
        }
    }

    public static void loadSettings() {
        trackedIds = new HashSet<String>(ConfigManager.getBlocksFinderBlocks());
        migrateRemovedOptions();
        rebuildTrackedBlockSet();
        refreshAllowedCacheNow(Minecraft.getMinecraft());
    }

    /** Drop removed Chams settings from older configs. */
    private static void migrateRemovedOptions() {
        String mode = ConfigManager.getBlocksFinderEspMode();
        if (MODE_CHAMS.equals(mode)) {
            ConfigManager.setBlocksFinderEspMode(MODE_OUTLINE_FILLED);
        }
    }

    private static void rebuildTrackedBlockSet() {
        Set<Block> blocks = new HashSet<Block>();
        Map<Block, String> idByBlock = new HashMap<Block, String>();
        for (String id : trackedIds) {
            try {
                Block block = Block.getBlockFromName(id);
                if (block != null && block != Blocks.air) {
                    blocks.add(block);
                    idByBlock.put(block, id);
                }
            } catch (Throwable ignored) {
            }
        }
        trackedBlocks = blocks;
        trackedBlockIdByBlock = idByBlock;
        hudRowTemplate = Collections.emptyList();
        resetScanState();
    }

    private static void refreshAllowedCacheNow(Minecraft mc) {
        allowedCacheTicks = ALLOWED_CACHE_TICKS;
        cachedAllowed = isBlocksFinderAllowed(mc);
    }

    private static void tickAllowedCache(Minecraft mc) {
        if (allowedCacheTicks++ >= ALLOWED_CACHE_TICKS) {
            allowedCacheTicks = 0;
            cachedAllowed = isBlocksFinderAllowed(mc);
        }
    }

    private static void resetScanState() {
        scanOriginChunkX = Integer.MIN_VALUE;
        scanOriginChunkZ = Integer.MIN_VALUE;
        scanChunkRadius = -1;
        scanChunkIndex = 0;
        ticksUntilNextScanPass = 0;
        scanChunkCoords.clear();
        scanResultsByChunk.clear();
        foundEntries = Collections.emptyList();
    }

    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackChunkZ(long packed) {
        return (int) packed;
    }

    private static int getScanChunkRadius(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return SCAN_CHUNK_RADIUS_MAX;
        }
        int renderChunks = mc.gameSettings.renderDistanceChunks;
        if (renderChunks <= 0) {
            renderChunks = 8;
        }
        return Math.min(renderChunks, SCAN_CHUNK_RADIUS_MAX);
    }

    /**
     * Updates which chunks are in range without wiping cached scan results (avoids ESP flicker).
     */
    private static void syncScanChunkRegion(int pcx, int pcz, int radius) {
        HashSet<Long> inRange = new HashSet<Long>();
        scanChunkCoords.clear();
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                long packed = packChunk(cx, cz);
                inRange.add(packed);
                scanChunkCoords.add(packed);
            }
        }
        for (Long key : new ArrayList<Long>(scanResultsByChunk.keySet())) {
            if (!inRange.contains(key)) {
                scanResultsByChunk.remove(key);
            }
        }
        if (scanChunkIndex >= scanChunkCoords.size()) {
            scanChunkIndex = 0;
        }
        scanOriginChunkX = pcx;
        scanOriginChunkZ = pcz;
        scanChunkRadius = radius;
    }

    private static void publishFoundEntriesFromScan(double ppx, double ppy, double ppz) {
        if (scanResultsByChunk.isEmpty()) {
            return;
        }
        double rangeSq = MAX_RENDER_DIST_SQ;
        List<FoundEntry> found = new ArrayList<FoundEntry>(Math.min(MAX_FOUND, 64));
        outer:
        for (List<FoundEntry> chunkList : scanResultsByChunk.values()) {
            if (chunkList == null || chunkList.isEmpty()) {
                continue;
            }
            for (FoundEntry entry : chunkList) {
                double dx = entry.pos.getX() + 0.5 - ppx;
                double dy = entry.pos.getY() + 0.5 - ppy;
                double dz = entry.pos.getZ() + 0.5 - ppz;
                if (dx * dx + dy * dy + dz * dz > rangeSq) {
                    continue;
                }
                found.add(entry);
                if (found.size() >= MAX_FOUND) {
                    break outer;
                }
            }
        }
        if (!found.isEmpty()) {
            foundEntries = found;
        }
    }

    private static void tickIncrementalScan(Minecraft mc) {
        if (mc.theWorld == null || mc.thePlayer == null || trackedBlocks.isEmpty()) {
            foundEntries = Collections.emptyList();
            return;
        }
        int pcx = ((int) mc.thePlayer.posX) >> 4;
        int pcz = ((int) mc.thePlayer.posZ) >> 4;
        int radius = getScanChunkRadius(mc);
        double ppx = mc.thePlayer.posX;
        double ppy = mc.thePlayer.posY;
        double ppz = mc.thePlayer.posZ;
        if (pcx != scanOriginChunkX || pcz != scanOriginChunkZ || radius != scanChunkRadius
                || scanChunkCoords.isEmpty()) {
            syncScanChunkRegion(pcx, pcz, radius);
            publishFoundEntriesFromScan(ppx, ppy, ppz);
        }

        if (ticksUntilNextScanPass > 0) {
            ticksUntilNextScanPass--;
            return;
        }
        if (scanChunkCoords.isEmpty()) {
            return;
        }

        double rangeSq = MAX_RENDER_DIST_SQ;
        int budget = CHUNKS_PER_TICK;
        while (budget-- > 0 && scanChunkIndex < scanChunkCoords.size()) {
            long packed = scanChunkCoords.get(scanChunkIndex++);
            int cx = unpackChunkX(packed);
            int cz = unpackChunkZ(packed);
            List<FoundEntry> chunkHits = scanChunkForTracked(mc, cx, cz, ppx, ppy, ppz, rangeSq);
            if (chunkHits != null) {
                scanResultsByChunk.put(packed, chunkHits);
            }
        }
        if (scanChunkIndex >= scanChunkCoords.size()) {
            scanChunkIndex = 0;
            ticksUntilNextScanPass = TICKS_BETWEEN_SCAN_PASSES;
            publishFoundEntriesFromScan(ppx, ppy, ppz);
        }
    }

    /**
     * @return chunk hits, or {@code null} if the chunk is not loaded (caller keeps prior cache).
     */
    private static List<FoundEntry> scanChunkForTracked(Minecraft mc, int cx, int cz,
                                                        double ppx, double ppy, double ppz,
                                                        double rangeSq) {
        if (!mc.theWorld.getChunkProvider().chunkExists(cx, cz)) {
            return null;
        }
        Chunk chunk;
        try {
            chunk = mc.theWorld.getChunkFromChunkCoords(cx, cz);
        } catch (Throwable ignored) {
            return null;
        }
        if (chunk == null || chunk.isEmpty()) {
            return Collections.emptyList();
        }
        int baseX = cx << 4;
        int baseZ = cz << 4;
        List<FoundEntry> found = new ArrayList<FoundEntry>(8);
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        for (ExtendedBlockStorage storage : storages) {
            if (storage == null || storage.isEmpty()) {
                continue;
            }
            int baseY = storage.getYLocation();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        Block block;
                        try {
                            block = storage.getBlockByExtId(lx, ly, lz);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (block == null || block == Blocks.air) {
                            continue;
                        }
                        String blockId = trackedBlockIdByBlock.get(block);
                        if (blockId == null) {
                            continue;
                        }
                        int wx = baseX + lx;
                        int wy = baseY + ly;
                        int wz = baseZ + lz;
                        double dx = wx + 0.5 - ppx;
                        double dy = wy + 0.5 - ppy;
                        double dz = wz + 0.5 - ppz;
                        if (dx * dx + dy * dy + dz * dz > rangeSq) {
                            continue;
                        }
                        found.add(new FoundEntry(new BlockPos(wx, wy, wz), blockId));
                        if (found.size() >= MAX_FOUND_PER_CHUNK) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }

    public static Set<String> getTrackedIds() {
        return trackedIds;
    }

    public static void addBlock(String blockId) {
        if (trackedIds.add(blockId)) {
            rebuildTrackedBlockSet();
            ConfigManager.setBlocksFinderBlocks(new ArrayList<String>(trackedIds));
            foundEntries = Collections.emptyList();
            if (isWorldOpacityActive()) {
                reloadChunkMeshesForWorldOpacity();
            }
        }
    }

    public static void removeBlock(String blockId) {
        if (trackedIds.remove(blockId)) {
            rebuildTrackedBlockSet();
            ConfigManager.setBlocksFinderBlocks(new ArrayList<String>(trackedIds));
            foundEntries = Collections.emptyList();
            if (isWorldOpacityActive()) {
                reloadChunkMeshesForWorldOpacity();
            }
        }
    }

    public static void clearAllTracked() {
        trackedIds.clear();
        trackedBlocks.clear();
        trackedBlockIdByBlock = Collections.emptyMap();
        hudRowTemplate = Collections.emptyList();
        ConfigManager.setBlocksFinderBlocks(Collections.<String>emptyList());
        ConfigManager.clearAllBlocksFinderBlockColors();
        resetScanState();
    }

    /** Horizontal scan radius in blocks (capped to ESP draw distance). */
    public static int getEffectiveScanRange(Minecraft mc) {
        return getScanChunkRadius(mc) * 16;
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        handleToggleKey();
        syncWorldOpacityAtlas();
        tickAllowedCache(mc);
        boolean allowed = cachedAllowed;
        if (!allowed && lastBlocksFinderAllowed && isEnabled()
                && ConfigManager.getBlocksFinderWorldOpacity() < WORLD_OPACITY_MAX
                && !trackedIds.isEmpty()) {
            reloadChunkMeshesForWorldOpacity();
        }
        lastBlocksFinderAllowed = allowed;
        if (!isActive()) {
            return;
        }
        tickIncrementalScan(mc);
    }

    private static void syncWorldOpacityAtlas() {
        if (!isEnabled()) {
            return;
        }
        int want = ConfigManager.getBlocksFinderWorldOpacity();
        if (want == BlocksFinderWorldOpacityAtlasHook.getAppliedPercent()) {
            return;
        }
        if (want >= WORLD_OPACITY_MAX) {
            BlocksFinderWorldOpacityAtlasHook.restoreAll();
        } else {
            BlocksFinderWorldOpacityAtlasHook.applyPercent(want);
        }
    }

    private static void handleToggleKey() {
        int keyCode = ConfigManager.getBlocksFinderKeyCode();
        if (keyCode <= 0) {
            keyWasDown = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) {
            keyWasDown = false;
            return;
        }
        boolean down = Keyboard.isKeyDown(keyCode);
        boolean pressed = down && !keyWasDown;
        keyWasDown = down;
        if (pressed) {
            if (!isEnabled() && !isBlocksFinderAllowed(mc)) {
                me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                        + "\u00a7cBlock Finder only works in singleplayer and replays.");
                return;
            }
            boolean next = !isEnabled();
            setEnabled(next);
            me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                    + "Block Finder: " + (next ? "\u00a7aON" : "\u00a7cOFF"));
        }
    }

    private static boolean withinRenderDistance(FoundEntry entry, double camX, double camY, double camZ) {
        double dx = entry.pos.getX() + 0.5 - camX;
        double dy = entry.pos.getY() + 0.5 - camY;
        double dz = entry.pos.getZ() + 0.5 - camZ;
        return dx * dx + dy * dy + dz * dz <= MAX_RENDER_DIST_SQ;
    }

    private static final class BlockTypeHudRow {
        final String blockId;
        final String label;
        final ItemStack icon;
        final int labelColor;
        int found;
        int visible;

        BlockTypeHudRow(String blockId, String label, ItemStack icon) {
            this.blockId = blockId;
            this.label = label;
            this.icon = icon;
            this.labelColor = 0xFF000000 | ConfigManager.getBlocksFinderBlockColorRgb(blockId);
        }
    }

    private static String formatBlockHudLabel(String blockId) {
        if (blockId == null) {
            return "?";
        }
        int colon = blockId.indexOf(':');
        String path = colon >= 0 ? blockId.substring(colon + 1) : blockId;
        String[] words = path.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1));
            }
        }
        return sb.length() > 0 ? sb.toString() : path;
    }

    private static ItemStack iconStackForBlockId(String blockId) {
        try {
            Block block = Block.getBlockFromName(blockId);
            if (block == null || block == Blocks.air) {
                return null;
            }
            Item item = Item.getItemFromBlock(block);
            if (item == null) {
                return null;
            }
            return new ItemStack(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<BlockTypeHudRow> buildHudRowTemplate() {
        if (!hudRowTemplate.isEmpty()) {
            return hudRowTemplate;
        }
        List<String> orderedIds = new ArrayList<String>(trackedIds);
        Collections.sort(orderedIds);
        List<BlockTypeHudRow> template = new ArrayList<BlockTypeHudRow>(orderedIds.size());
        for (String blockId : orderedIds) {
            template.add(new BlockTypeHudRow(blockId, formatBlockHudLabel(blockId), iconStackForBlockId(blockId)));
        }
        hudRowTemplate = template;
        return template;
    }

    private static List<BlockTypeHudRow> buildHudRows(Minecraft mc, List<FoundEntry> entries,
                                                      double camX, double camY, double camZ,
                                                      float partialTicks) {
        List<BlockTypeHudRow> rows = new ArrayList<BlockTypeHudRow>();
        for (BlockTypeHudRow template : buildHudRowTemplate()) {
            rows.add(new BlockTypeHudRow(template.blockId, template.label, template.icon));
        }
        Map<String, BlockTypeHudRow> rowById = new LinkedHashMap<String, BlockTypeHudRow>();
        for (BlockTypeHudRow row : rows) {
            rowById.put(row.blockId, row);
        }
        for (FoundEntry entry : entries) {
            BlockTypeHudRow row = rowById.get(entry.blockId);
            if (row == null) {
                continue;
            }
            row.found++;
            if (withinRenderDistance(entry, camX, camY, camZ)
                    && !shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                row.visible++;
            }
        }
        return rows;
    }

    /** On-screen per-block counter with icons while Block Finder is active. */
    public static void renderHud(float partialTicks) {
        if (!isActive() || trackedIds.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.fontRendererObj == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }

        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        List<FoundEntry> entries = foundEntries;
        List<BlockTypeHudRow> rows = buildHudRows(mc, entries, camX, camY, camZ, partialTicks);

        int totalFound = 0;
        int totalVisible = 0;
        for (BlockTypeHudRow row : rows) {
            totalFound += row.found;
            totalVisible += row.visible;
        }

        FontRenderer fr = mc.fontRendererObj;
        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Block Finder";
        String totalLine = EnumChatFormatting.GRAY + "Total "
                + EnumChatFormatting.WHITE + totalVisible
                + EnumChatFormatting.GRAY + " / "
                + EnumChatFormatting.AQUA + totalFound;

        final int padX = 6;
        final int padY = 4;
        final int lineGap = 2;
        final int iconSize = 16;
        final int iconGap = 4;
        final int rowH = 18;

        int boxW = fr.getStringWidth(title) + padX * 2;
        for (BlockTypeHudRow row : rows) {
            String countText = EnumChatFormatting.WHITE + String.valueOf(row.visible)
                    + EnumChatFormatting.GRAY + " / "
                    + EnumChatFormatting.AQUA + row.found;
            int rowTextW = fr.getStringWidth(row.label) + fr.getStringWidth(countText) + 6;
            int rowW = iconSize + iconGap + rowTextW + padX * 2;
            if (rowW > boxW) {
                boxW = rowW;
            }
        }
        int totalW = fr.getStringWidth(totalLine) + padX * 2;
        if (totalW > boxW) {
            boxW = totalW;
        }

        int boxH = padY * 2 + fr.FONT_HEIGHT + lineGap
                + rows.size() * rowH + lineGap + fr.FONT_HEIGHT;

        ScaledResolution sr = new ScaledResolution(mc);
        int x = 4;
        int y = sr.getScaledHeight() - boxH - 4;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableDepth();
        Gui.drawRect(x, y, x + boxW, y + boxH, 0x90000000);
        Gui.drawRect(x, y, x + boxW, y + 1, 0xFFAA8800);

        int textY = y + padY;
        fr.drawStringWithShadow(title, x + padX, textY, 0xFFFFFF);
        textY += fr.FONT_HEIGHT + lineGap;

        for (BlockTypeHudRow row : rows) {
            int rowY = textY;
            if (row.icon != null) {
                HudGuiItemIcon.drawInventoryHudSlot(mc, row.icon, x + padX, rowY + 1);
            }
            String countText = EnumChatFormatting.WHITE + String.valueOf(row.visible)
                    + EnumChatFormatting.GRAY + " / "
                    + EnumChatFormatting.AQUA + String.valueOf(row.found);
            int labelX = x + padX + iconSize + iconGap;
            int labelY = rowY + (rowH - 8) / 2;
            fr.drawStringWithShadow(row.label, labelX, labelY, row.labelColor);
            int countX = labelX + fr.getStringWidth(row.label) + 4;
            fr.drawStringWithShadow(countText, countX, labelY, 0xFFFFFF);
            textY += rowH;
        }

        fr.drawStringWithShadow(totalLine, x + padX, textY, 0xAAAAAA);
        GlStateManager.enableDepth();
        HudGlState.restoreForOverlayText();
    }

    public static boolean isWorldOpacityActive() {
        return isEnabled()
                && cachedAllowed
                && ConfigManager.getBlocksFinderWorldOpacity() < WORLD_OPACITY_MAX
                && BlocksFinderWorldOpacityAtlasHook.isAtlasFaded();
    }

    /** Terrain pass: translucent layer while atlas is faded (barrier-style). */
    public static boolean shouldUseTranslucentTerrainLayer() {
        return isEnabled()
                && cachedAllowed
                && ConfigManager.getBlocksFinderWorldOpacity() < WORLD_OPACITY_MAX;
    }

    /**
     * {@link me.napim.gomod.mixins.BlockWorldOpacityLayerMixin} — used during async chunk rebuild;
     * must not rely on {@link #cachedAllowed} (updated on the client thread only).
     */
    public static boolean shouldForceWorldOpacityTranslucentLayer(Block block) {
        if (overlayRenderPass || !BlocksFinderWorldOpacityAtlasHook.isAtlasFaded()) {
            return false;
        }
        if (!isEnabled() || ConfigManager.getBlocksFinderWorldOpacity() >= WORLD_OPACITY_MAX) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !isBlocksFinderAllowed(mc)) {
            return false;
        }
        return BlocksFinderWorldOpacityRules.shouldForceTranslucentLayer(block);
    }

    public static float getWorldOpacityAlpha() {
        return ConfigManager.getBlocksFinderWorldOpacity() / 100f;
    }

    /** 0–100%; fades the block atlas like barrier visibility (all blocks except ESP targets). */
    public static void setWorldOpacity(int percent) {
        int clamped = Math.max(WORLD_OPACITY_MIN, Math.min(WORLD_OPACITY_MAX, percent));
        if (ConfigManager.getBlocksFinderWorldOpacity() == clamped) {
            return;
        }
        ConfigManager.setBlocksFinderWorldOpacity(clamped);
        if (!isEnabled()) {
            return;
        }
        if (clamped >= WORLD_OPACITY_MAX) {
            BlocksFinderWorldOpacityAtlasHook.restoreAll();
        } else {
            BlocksFinderWorldOpacityAtlasHook.applyPercent(clamped);
        }
    }

    /**
     * Hold {@link ConfigManager#getBlocksFinderWorldOpacityKeyCode()} and scroll the mouse wheel.
     *
     * @return {@code true} if the wheel event was consumed
     */
    public static boolean handleWorldOpacityScroll(int dWheel) {
        int keyCode = ConfigManager.getBlocksFinderWorldOpacityKeyCode();
        if (keyCode <= 0 || dWheel == 0) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) {
            return false;
        }
        if (!Keyboard.isKeyDown(keyCode)) {
            return false;
        }
        if (!isBlocksFinderAllowed(mc)) {
            return false;
        }
        int notches = (int) Math.signum(dWheel) * Math.max(1, Math.abs(dWheel) / 120);
        setWorldOpacity(ConfigManager.getBlocksFinderWorldOpacity() + notches * 5);
        return true;
    }

    public static boolean isTrackedBlockState(IBlockState state) {
        if (state == null || trackedBlocks.isEmpty()) {
            return false;
        }
        Block block = state.getBlock();
        return block != null && block != Blocks.air && trackedBlocks.contains(block);
    }

    /** Used by {@link me.napim.gomod.mixins.BlocksFinderWorldOpacityMixin}. */
    public static boolean shouldApplyWorldOpacity(IBlockState state) {
        if (overlayRenderPass || !isWorldOpacityActive() || state == null) {
            return false;
        }
        if (TntPlacementPreviewAtlasHook.isPreviewRenderActive()) {
            return false;
        }
        Block block = state.getBlock();
        if (block == null || block == Blocks.air) {
            return false;
        }
        if (block == Blocks.water || block == Blocks.flowing_water) {
            return false;
        }
        try {
            if (block.getMaterial().isLiquid()) {
                return false;
            }
            // Only full opaque cubes — leaves/glass/cutout break when vertex alpha is patched.
            if (!block.isOpaqueCube()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return !isTrackedBlockState(state);
    }

    public static boolean isWorldOpacityChunkPatchActive() {
        Deque<Boolean> stack = worldOpacityPatchStack.get();
        return !stack.isEmpty() && Boolean.TRUE.equals(stack.peek());
    }

    /** {@link me.napim.gomod.mixins.BlocksFinderWorldOpacityMixin} HEAD */
    public static void beginWorldOpacityChunkBlock(IBlockState state, WorldRenderer wr) {
        if (!shouldApplyWorldOpacity(state) || wr == null) {
            return;
        }
        worldOpacityPatchStack.get().push(Boolean.TRUE);
        try {
            worldOpacityVertexStartStack.get().push(wr.getVertexCount());
        } catch (Throwable ignored) {
            worldOpacityVertexStartStack.get().push(0);
        }
    }

    /** {@link me.napim.gomod.mixins.BlocksFinderWorldOpacityMixin} RETURN */
    public static void endWorldOpacityChunkBlock(IBlockState state, WorldRenderer wr) {
        if (!shouldApplyWorldOpacity(state) || wr == null) {
            return;
        }
        Deque<Boolean> stack = worldOpacityPatchStack.get();
        if (stack.isEmpty() || !Boolean.TRUE.equals(stack.pop())) {
            return;
        }
        Integer startBoxed = worldOpacityVertexStartStack.get().poll();
        if (startBoxed == null) {
            return;
        }
        float alpha = getWorldOpacityAlpha();
        if (alpha >= 0.999f) {
            return;
        }
        try {
            int start = startBoxed;
            int end = wr.getVertexCount();
            if (end <= start) {
                return;
            }
            VertexFormat vf = wr.getVertexFormat();
            if (vf == null) {
                return;
            }
            int stride = vf.getNextOffset();
            int colorOff = colorElementByteOffset(vf);
            if (stride <= 0 || colorOff < 0) {
                return;
            }
            ByteBuffer buf = resolveWorldRendererByteBuffer(wr);
            if (buf == null) {
                return;
            }
            int cap = buf.capacity();
            for (int vi = start; vi < end; vi++) {
                int aByte = vi * stride + colorOff + 3;
                if (aByte >= 0 && aByte < cap) {
                    int existing = buf.get(aByte) & 0xFF;
                    int na = Math.max(8, Math.min(255, Math.round(existing * alpha)));
                    buf.put(aByte, (byte) na);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** {@link me.napim.gomod.mixins.BlocksFinderWorldOpacityWorldRendererMixin} */
    public static void patchWorldOpacityVertexCorner(WorldRenderer wr, int corner) {
        if (!isWorldOpacityChunkPatchActive() || wr == null
                || TntPlacementPreviewAtlasHook.isPreviewRenderActive()) {
            return;
        }
        float alpha = getWorldOpacityAlpha();
        if (alpha >= 0.999f) {
            return;
        }
        try {
            VertexFormat vf = wr.getVertexFormat();
            if (vf == null) {
                return;
            }
            int stride = vf.getNextOffset();
            int colorOff = colorElementByteOffset(vf);
            if (stride <= 0 || colorOff < 0) {
                return;
            }
            int baseByte = wr.getColorIndex(corner) * stride + colorOff;
            ByteBuffer buf = resolveWorldRendererByteBuffer(wr);
            if (buf == null || baseByte < 0 || baseByte + 4 > buf.capacity()) {
                return;
            }
            int existing = buf.get(baseByte + 3) & 0xFF;
            int na = Math.max(8, Math.min(255, Math.round(existing * alpha)));
            int saved = buf.position();
            try {
                buf.position(baseByte + 3);
                buf.put((byte) na);
            } finally {
                buf.position(saved);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * After the faded SOLID layer draw, redraw tracked blocks at full opacity with normal depth
     * so targets stay visible while stone/dirt fade via {@code GlStateManager.color} alpha.
     */
    public static void renderTrackedWorldRestore(float partialTicks) {
        if (!isWorldOpacityActive() || BlocksFinderWorldOpacityAtlasHook.isAtlasFaded()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        List<FoundEntry> entries = foundEntries;
        if (entries.isEmpty()) {
            return;
        }
        BlockRendererDispatcher dispatcher = mc.getBlockRendererDispatcher();
        if (dispatcher == null) {
            return;
        }
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        overlayRenderPass = true;
        try {
            GlStateManager.pushMatrix();
            boolean attribPushed = false;
            try {
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                attribPushed = true;
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
                GlStateManager.enableTexture2D();
                GlStateManager.enableLighting();
                RenderHelper.enableStandardItemLighting();
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

                Tessellator tess = Tessellator.getInstance();
                WorldRenderer wr = tess.getWorldRenderer();
                List<FoundEntry> bakedRestore = new ArrayList<FoundEntry>();
                int drawn = 0;
                for (FoundEntry entry : entries) {
                    if (!withinRenderDistance(entry, camX, camY, camZ)) {
                        continue;
                    }
                    if (++drawn > MAX_FOUND) {
                        break;
                    }
                    IBlockState state;
                    try {
                        state = mc.theWorld.getBlockState(entry.pos);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!isTrackedBlockState(state)) {
                        continue;
                    }
                    Block block = state.getBlock();
                    if (block == null || block == Blocks.air) {
                        continue;
                    }
                    if (isTileEntityChamsBlock(block)) {
                        renderTrackedChestRestore(mc, wr, tess, entry, camX, camY, camZ);
                        continue;
                    }
                    bakedRestore.add(entry);
                }
                if (!bakedRestore.isEmpty()) {
                    GlStateManager.pushMatrix();
                    try {
                        GlStateManager.translate(-camX, -camY, -camZ);
                        wr.setTranslation(0.0, 0.0, 0.0);
                        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                        try {
                            for (FoundEntry entry : bakedRestore) {
                                IBlockState state = mc.theWorld.getBlockState(entry.pos);
                                if (isTrackedBlockState(state)) {
                                    dispatcher.renderBlock(state, entry.pos, mc.theWorld, wr);
                                }
                            }
                        } finally {
                            finishWorldRendererBuild(wr, tess);
                        }
                    } finally {
                        GlStateManager.popMatrix();
                    }
                }
            } finally {
                if (attribPushed) {
                    GL11.glPopAttrib();
                }
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                RenderHelper.disableStandardItemLighting();
                GlStateManager.popMatrix();
            }
        } finally {
            overlayRenderPass = false;
        }
    }

    private static void renderTrackedChestRestore(Minecraft mc, WorldRenderer wr, Tessellator tess,
                                                  FoundEntry entry, double camX, double camY, double camZ) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(-camX, -camY, -camZ);
            GlStateManager.disableTexture2D();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            AxisAlignedBB bb = getBlockBB(mc, entry.pos);
            final double eps = 0.002;
            addCubeQuads(wr,
                    bb.minX - eps, bb.minY - eps, bb.minZ - eps,
                    bb.maxX + eps, bb.maxY + eps, bb.maxZ + eps,
                    0.92f, 0.92f, 0.92f, 1.0f);
            finishWorldRendererBuild(wr, tess);
            GlStateManager.enableTexture2D();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Rebuild all chunk meshes so blocks pick up the faded atlas + translucent layer bucket. */
    public static void reloadChunkMeshesForWorldOpacity() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.renderGlobal == null) {
                return;
            }
            mc.renderGlobal.loadRenderers();
        } catch (Throwable ignored) {
        }
    }

    private static int colorElementByteOffset(VertexFormat vf) {
        int offset = 0;
        for (int i = 0; i < vf.getElementCount(); i++) {
            VertexFormatElement el = vf.getElement(i);
            if (el.getUsage() == VertexFormatElement.EnumUsage.COLOR) {
                return offset;
            }
            offset += el.getType().getSize();
        }
        return -1;
    }

    private static ByteBuffer resolveWorldRendererByteBuffer(WorldRenderer wr) {
        try {
            ByteBuffer direct = wr.getByteBuffer();
            if (direct != null) {
                return direct;
            }
        } catch (Throwable ignored) {
        }
        if (worldOpacityByteBufferField == null) {
            return null;
        }
        try {
            return (ByteBuffer) worldOpacityByteBufferField.get(wr);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Chest-like blocks have no baked model in 1.8 — chams use a colored box instead of TE render. */
    private static boolean isTileEntityChamsBlock(Block block) {
        return block == Blocks.chest || block == Blocks.trapped_chest || block == Blocks.ender_chest;
    }

    /**
     * Only draw hidden (occluded) surfaces — depth buffer already has the normal world pass.
     * Avoids painting targets on top of air / the player in third person.
     */
    private static void beginThroughWallDepth() {
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_GREATER);
    }

    private static void endThroughWallDepth() {
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
    }

    /** Skip targets between the camera and the player body in F5 third person. */
    private static boolean shouldSkipOverlayForThirdPerson(Minecraft mc, BlockPos pos,
                                                           double camX, double camY, double camZ,
                                                           float partialTicks) {
        if (mc.gameSettings == null || mc.gameSettings.thirdPersonView == 0 || mc.thePlayer == null) {
            return false;
        }
        Entity player = mc.thePlayer;
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks
                + player.getEyeHeight();
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;

        double bx = pos.getX() + 0.5 - camX;
        double by = pos.getY() + 0.5 - camY;
        double bz = pos.getZ() + 0.5 - camZ;
        double blockDistSq = bx * bx + by * by + bz * bz;

        double pdx = px - camX;
        double pdy = py - camY;
        double pdz = pz - camZ;
        double playerDistSq = pdx * pdx + pdy * pdy + pdz * pdz;

        return blockDistSq < playerDistSq - 0.5;
    }

    public static void render(float partialTicks) {
        if (!isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (trackedIds.isEmpty()) {
            return;
        }
        List<FoundEntry> entries = foundEntries;
        if (entries.isEmpty()) {
            return;
        }

        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        String mode = normalizeEspMode(ConfigManager.getBlocksFinderEspMode());
        float fillAlpha = ConfigManager.getBlocksFinderFillAlpha() / 255f;
        float animAlpha = fillAlpha;

        overlayRenderPass = true;
        try {
            GlStateManager.pushMatrix();
            boolean attribPushed = false;
            try {
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                attribPushed = true;
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                beginThroughWallDepth();
                GlStateManager.disableCull();

                Tessellator tess = Tessellator.getInstance();
                WorldRenderer wr = tess.getWorldRenderer();

                if (MODE_OUTLINE.equals(mode) || MODE_FILLED.equals(mode) || MODE_OUTLINE_FILLED.equals(mode)) {
                    renderEspBatched(mc, wr, tess, entries, mode, camX, camY, camZ, partialTicks, animAlpha);
                }
            } finally {
                endThroughWallDepth();
                restoreGlAfterOverlay(attribPushed);
                GlStateManager.popMatrix();
            }
        } finally {
            overlayRenderPass = false;
        }
    }

    private static void restoreGlAfterOverlay(boolean attribPopped) {
        if (attribPopped) {
            GL11.glPopAttrib();
        }
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        RenderHelper.disableStandardItemLighting();
        HudGlState.restoreForOverlayText();
    }

    /**
     * Chams: baked models for normal blocks; chests as closed boxes; enchanting table book via TE.
     */
    private static void renderChams(Minecraft mc, List<FoundEntry> entries,
                                    double camX, double camY, double camZ, float partialTicks) {
        BlockRendererDispatcher blockDispatcher = mc.getBlockRendererDispatcher();
        float alpha = Math.max(0.45f, ConfigManager.getBlocksFinderFillAlpha() / 255f);

        List<FoundEntry> visible = new ArrayList<FoundEntry>();
        List<FoundEntry> bakedEntries = new ArrayList<FoundEntry>();
        List<FoundEntry> chestEntries = new ArrayList<FoundEntry>();
        List<FoundEntry> enchantBookEntries = new ArrayList<FoundEntry>();
        for (FoundEntry e : entries) {
            if (!withinRenderDistance(e, camX, camY, camZ)) {
                continue;
            }
            visible.add(e);
            try {
                IBlockState state = mc.theWorld.getBlockState(e.pos);
                if (state == null || state.getBlock() == null) {
                    bakedEntries.add(e);
                    continue;
                }
                Block block = state.getBlock();
                if (block == Blocks.enchanting_table) {
                    bakedEntries.add(e);
                    enchantBookEntries.add(e);
                } else if (isTileEntityChamsBlock(block)) {
                    chestEntries.add(e);
                } else {
                    bakedEntries.add(e);
                }
            } catch (Throwable ignored) {
                bakedEntries.add(e);
            }
        }

        overlayRenderPass = true;
        try {
            GlStateManager.pushMatrix();
            boolean attribPushed = false;
            try {
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                attribPushed = true;
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.enableCull();
                beginThroughWallDepth();

                Tessellator tess = Tessellator.getInstance();
                WorldRenderer wr = tess.getWorldRenderer();

                if (blockDispatcher != null && !bakedEntries.isEmpty()) {
                    renderBakedBlockChams(blockDispatcher, mc, wr, tess, bakedEntries, camX, camY, camZ,
                            partialTicks, alpha);
                }

                if (!chestEntries.isEmpty()) {
                    renderChestBoxChams(mc, wr, tess, chestEntries, camX, camY, camZ, partialTicks, alpha);
                }

                if (!enchantBookEntries.isEmpty()) {
                    renderEnchantBookChams(mc, enchantBookEntries, camX, camY, camZ, partialTicks, alpha);
                }

                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GL11.glLineWidth(1.25f);
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                for (FoundEntry entry : visible) {
                    if (shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                        continue;
                    }
                    drawChamsOutline(wr, mc, entry, camX, camY, camZ, 0.9f);
                }
                finishWorldRendererBuild(wr, tess);

            } finally {
                endThroughWallDepth();
                restoreGlAfterOverlay(attribPushed);
                GlStateManager.popMatrix();
            }
        } finally {
            overlayRenderPass = false;
        }
    }

    /** One draw per block so directional models (crafting table) keep correct face culling. */
    private static void renderBakedBlockChams(BlockRendererDispatcher dispatcher, Minecraft mc,
                                              WorldRenderer wr, Tessellator tess, List<FoundEntry> entries,
                                              double camX, double camY, double camZ, float partialTicks,
                                              float alpha) {
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);

        for (FoundEntry entry : entries) {
            if (shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                continue;
            }
            IBlockState state;
            try {
                state = mc.theWorld.getBlockState(entry.pos);
            } catch (Throwable ignored) {
                continue;
            }
            if (state == null || state.getBlock() == Blocks.air
                    || isTileEntityChamsBlock(state.getBlock())) {
                continue;
            }
            int rgb = ConfigManager.getBlocksFinderBlockColorRgb(entry.blockId);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;
            GlStateManager.color(r, g, b, alpha);

            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(-camX, -camY, -camZ);
                wr.setTranslation(0.0, 0.0, 0.0);
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                try {
                    dispatcher.renderBlock(state, entry.pos, mc.theWorld, wr);
                } finally {
                    finishWorldRendererBuild(wr, tess);
                }
            } finally {
                GlStateManager.popMatrix();
            }
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** Closed chest shape (TE renderer shows open lid + glitched latch in overlay pass). */
    private static void renderChestBoxChams(Minecraft mc, WorldRenderer wr, Tessellator tess,
                                            List<FoundEntry> entries, double camX, double camY, double camZ,
                                            float partialTicks, float alpha) {
        GlStateManager.disableTexture2D();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (FoundEntry entry : entries) {
            if (shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                continue;
            }
            int rgb = ConfigManager.getBlocksFinderBlockColorRgb(entry.blockId);
            float r = ((rgb >> 16) & 0xFF) / 255f;
            float g = ((rgb >> 8) & 0xFF) / 255f;
            float b = (rgb & 0xFF) / 255f;
            AxisAlignedBB bb = getBlockBB(mc, entry.pos);
            final double eps = 0.003;
            addCubeQuads(wr,
                    bb.minX - camX - eps, bb.minY - camY - eps, bb.minZ - camZ - eps,
                    bb.maxX - camX + eps, bb.maxY - camY + eps, bb.maxZ - camZ + eps,
                    r, g, b, alpha);
        }
        finishWorldRendererBuild(wr, tess);
        GlStateManager.enableTexture2D();
    }

    /** Enchanting table book only (table body comes from baked block model). */
    private static void renderEnchantBookChams(Minecraft mc, List<FoundEntry> entries,
                                               double camX, double camY, double camZ,
                                               float partialTicks, float alpha) {
        TileEntityRendererDispatcher teDispatcher = TileEntityRendererDispatcher.instance;
        if (teDispatcher == null) {
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(-camX, -camY, -camZ);
            for (FoundEntry entry : entries) {
                if (shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                    continue;
                }
                TileEntity te;
                try {
                    te = mc.theWorld.getTileEntity(entry.pos);
                } catch (Throwable ignored) {
                    continue;
                }
                if (te == null) {
                    continue;
                }
                try {
                    teDispatcher.renderTileEntityAt(te,
                            entry.pos.getX(), entry.pos.getY(), entry.pos.getZ(), partialTicks);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            GlStateManager.popMatrix();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static void drawChamsOutline(WorldRenderer wr, Minecraft mc, FoundEntry entry,
                                         double camX, double camY, double camZ, float a) {
        int rgb = ConfigManager.getBlocksFinderBlockColorRgb(entry.blockId);
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        AxisAlignedBB bb = getBlockBB(mc, entry.pos);
        final double eps = 0.003;
        addCubeLines(wr, bb.minX - camX - eps, bb.minY - camY - eps, bb.minZ - camZ - eps,
                bb.maxX - camX + eps, bb.maxY - camY + eps, bb.maxZ - camZ + eps, r, g, b, a);
    }

    /** Ends an open {@link WorldRenderer#begin} — empty draws reset state safely in 1.8.9. */
    private static void finishWorldRendererBuild(WorldRenderer wr, Tessellator tess) {
        try {
            tess.draw();
        } catch (Throwable ignored) {
            try {
                wr.reset();
            } catch (Throwable ignored2) {
            }
        }
    }

    private static void renderEspBatched(Minecraft mc, WorldRenderer wr, Tessellator tess,
                                         List<FoundEntry> entries, String mode,
                                         double camX, double camY, double camZ, float partialTicks,
                                         float fillAlpha) {
        boolean wantFill = MODE_FILLED.equals(mode) || MODE_OUTLINE_FILLED.equals(mode);
        boolean wantOutline = MODE_OUTLINE.equals(mode) || MODE_OUTLINE_FILLED.equals(mode);

        if (wantFill) {
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (FoundEntry entry : entries) {
                if (!withinRenderDistance(entry, camX, camY, camZ)
                        || shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                    continue;
                }
                int rgb = ConfigManager.getBlocksFinderBlockColorRgb(entry.blockId);
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                AxisAlignedBB bb = getBlockBB(mc, entry.pos);
                final double eps = 0.003;
                addCubeQuads(wr,
                        bb.minX - camX - eps, bb.minY - camY - eps, bb.minZ - camZ - eps,
                        bb.maxX - camX + eps, bb.maxY - camY + eps, bb.maxZ - camZ + eps,
                        r, g, b, fillAlpha);
            }
            finishWorldRendererBuild(wr, tess);
        }

        if (wantOutline) {
            GL11.glLineWidth(1.5f);
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (FoundEntry entry : entries) {
                if (!withinRenderDistance(entry, camX, camY, camZ)
                        || shouldSkipOverlayForThirdPerson(mc, entry.pos, camX, camY, camZ, partialTicks)) {
                    continue;
                }
                int rgb = ConfigManager.getBlocksFinderBlockColorRgb(entry.blockId);
                float r = ((rgb >> 16) & 0xFF) / 255f;
                float g = ((rgb >> 8) & 0xFF) / 255f;
                float b = (rgb & 0xFF) / 255f;
                AxisAlignedBB bb = getBlockBB(mc, entry.pos);
                final double eps = 0.003;
                addCubeLines(wr,
                        bb.minX - camX - eps, bb.minY - camY - eps, bb.minZ - camZ - eps,
                        bb.maxX - camX + eps, bb.maxY - camY + eps, bb.maxZ - camZ + eps,
                        r, g, b, 1.0f);
            }
            finishWorldRendererBuild(wr, tess);
        }
    }

    public static String normalizeEspMode(String mode) {
        if (mode == null) {
            return MODE_OUTLINE_FILLED;
        }
        if (MODE_CHAMS.equals(mode) || "BEACON".equals(mode) || "PULSE".equals(mode) || "CORNER".equals(mode)) {
            return MODE_OUTLINE_FILLED;
        }
        for (String m : MODES) {
            if (m.equals(mode)) {
                return mode;
            }
        }
        return MODE_OUTLINE_FILLED;
    }

    private static void addCubeLines(WorldRenderer wr,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
    }

    private static void addCubeQuads(WorldRenderer wr,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
    }

    private static AxisAlignedBB getBlockBB(Minecraft mc, BlockPos pos) {
        try {
            AxisAlignedBB bb = mc.theWorld.getBlockState(pos).getBlock()
                    .getSelectedBoundingBox(mc.theWorld, pos);
            if (bb != null) {
                return bb;
            }
        } catch (Throwable ignored) {
        }
        return new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    public static String nextMode(String current) {
        String norm = normalizeEspMode(current);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(norm)) {
                return MODES[(i + 1) % MODES.length];
            }
        }
        return MODES[0];
    }

    public static String modeLabel(String mode) {
        String norm = normalizeEspMode(mode);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(norm)) {
                return MODE_LABELS[i];
            }
        }
        return norm;
    }
}
