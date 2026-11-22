package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import me.napim.gomod.util.GameContext;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * Chest overlay helper:
 * - after cages open, chests opened by anyone in that 15s window: red overlay
 * - at deathmatch start, the same open-state window runs for middle chests
 * - after the Blitz Star hidden message, the same open-state window runs again
 * - if our opened chest contains the Blitz Star item, that chest is green
 */
public final class CageChestOverlay {
    private static final String BLITZ_STAR_HIDDEN_MESSAGE = "The Blitz Star has been hidden in a random chest!";
    private static boolean lastCageSidebar = false;
    private static boolean lastBlitzActive = false;
    private static boolean lastDeathmatchPhase = false;
    private static boolean cagesSeenThisRound = false;
    private static long lastCageSeenMs = 0L;
    private static long activeUntilMs = 0L;
    private static long blitzStarActiveUntilMs = 0L;
    private static long lastStateResetWorldTime = 0L;
    private static BlockPos lastInteractedChestPos = null;
    private static long lastInteractedChestPosMs = 0L;
    private static final long WINDOW_MS = 15_000L;
    private static final long CAGE_RECENT_FALLBACK_MS = 8_000L;
    private static final long CAGE_SEEN_ARMED_MS = 60_000L;
    private static final long CHEST_INTERACTION_TTL_MS = 4_000L;
    private static final Set<String> openedChestKeys = new HashSet<String>();
    private static final Set<String> blitzStarChestKeys = new HashSet<String>();

    private CageChestOverlay() {}

    public static void onRightClickBlock(net.minecraft.world.World world, BlockPos pos) {
        if (world == null || pos == null) {
            return;
        }
        try {
            Block b = world.getBlockState(pos).getBlock();
            if (b instanceof BlockChest) {
                lastInteractedChestPos = new BlockPos(pos);
                lastInteractedChestPosMs = System.currentTimeMillis();
                if (isOverlayActive()) {
                    openedChestKeys.add(key(pos));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onChatMessage(String raw) {
        if (!ConfigManager.isCageChestOverlayEnabled() || raw == null) {
            return;
        }
        String message = raw.replaceAll("\\s+", " ").trim();
        if (!BLITZ_STAR_HIDDEN_MESSAGE.equals(message)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc) || !GameContext.isBlitzGameActive(mc)) {
            return;
        }
        blitzStarActiveUntilMs = System.currentTimeMillis() + WINDOW_MS;
        clearMarkedChests();
    }

    public static void render(float partialTicks) {
        if (!ConfigManager.isCageChestOverlayEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        tickWindowState(mc);
        markBlitzStarChestFromOpenContainer(mc);
        if (!isOverlayActive()) {
            return;
        }
        net.minecraft.entity.Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        GlStateManager.pushMatrix();
        boolean attribPushed = false;
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attribPushed = true;
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);

            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            for (Object obj : mc.theWorld.loadedTileEntityList) {
                if (!(obj instanceof TileEntityChest)) {
                    continue;
                }
                TileEntity te = (TileEntity) obj;
                BlockPos pos = te.getPos();
                if (pos == null) {
                    continue;
                }
                AxisAlignedBB worldBox = getChestSelectionBox(mc, pos);
                if (worldBox == null) {
                    continue;
                }
                // Detect chests opened by anyone nearby (not only our own right-click hook).
                String chestKey = key(pos);
                if (isChestCurrentlyOpened((TileEntityChest) te)) {
                    openedChestKeys.add(chestKey);
                }
                if (!openedChestKeys.contains(chestKey)) {
                    continue;
                }
                if (blitzStarChestKeys.contains(chestKey)) {
                    drawBox(wr, tess, worldBox, camX, camY, camZ, 0.15f, 1.0f, 0.15f, 1.0f);
                } else {
                    drawBox(wr, tess, worldBox, camX, camY, camZ, 1.0f, 0.15f, 0.15f, 1.0f);
                }
            }
        } finally {
            // Hard reset (in addition to attrib pop) to avoid black-screen GL state leaks
            // when other overlay mods or perspective mods touch the same state.
            GlStateManager.enableTexture2D();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GL11.glLineWidth(1.0f);
            if (attribPushed) {
                GL11.glPopAttrib();
            }
            GlStateManager.popMatrix();
        }
    }

    private static void tickWindowState(Minecraft mc) {
        if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) {
            lastCageSidebar = false;
            lastBlitzActive = false;
            lastDeathmatchPhase = false;
            cagesSeenThisRound = false;
            lastCageSeenMs = 0L;
            activeUntilMs = 0L;
            blitzStarActiveUntilMs = 0L;
            lastInteractedChestPos = null;
            lastInteractedChestPosMs = 0L;
            clearMarkedChests();
            return;
        }
        long worldTime = mc.theWorld.getTotalWorldTime();
        if (worldTime < lastStateResetWorldTime) {
            clearMarkedChests();
            lastInteractedChestPos = null;
            lastInteractedChestPosMs = 0L;
        }
        lastStateResetWorldTime = worldTime;

        boolean cageNow = GameContext.isBlitzCagePhaseSidebar(mc);
        boolean blitzActiveNow = GameContext.isBlitzGameActive(mc);
        boolean deathmatchNow = GameContext.isBlitzDeathmatchPhase(mc);
        long now = System.currentTimeMillis();
        if (cageNow) {
            lastCageSeenMs = now;
            cagesSeenThisRound = true;
        }
        // Main transition: cages row disappears -> start 15s tracking window.
        if (lastCageSidebar && !cageNow) {
            activeUntilMs = System.currentTimeMillis() + WINDOW_MS;
            clearMarkedChests();
        } else if (!cageNow && activeUntilMs <= now && (now - lastCageSeenMs) <= CAGE_RECENT_FALLBACK_MS) {
            // Fallback: if the client missed the exact transition tick (scoreboard flicker),
            // still start the window shortly after the last confirmed cages frame.
            activeUntilMs = now + WINDOW_MS;
            clearMarkedChests();
        } else if (!lastBlitzActive && blitzActiveNow && cagesSeenThisRound
                && (now - lastCageSeenMs) <= CAGE_SEEN_ARMED_MS) {
            // Extra fallback: if scoreboard layout change skips direct cages-row disappearance,
            // start the 15s window when match active starts after a recent cages phase.
            activeUntilMs = now + WINDOW_MS;
            clearMarkedChests();
            cagesSeenThisRound = false;
        } else if (!lastDeathmatchPhase && deathmatchNow) {
            // Deathmatch start: run the same chest overlay window for middle chests.
            activeUntilMs = now + WINDOW_MS;
            clearMarkedChests();
        }
        lastCageSidebar = cageNow;
        lastBlitzActive = blitzActiveNow;
        lastDeathmatchPhase = deathmatchNow;
    }

    private static boolean isOverlayActive() {
        long now = System.currentTimeMillis();
        return isCageChestOverlayActive(now) || isBlitzStarOverlayActive(now);
    }

    private static boolean isCageChestOverlayActive(long now) {
        return ConfigManager.isCageChestOverlayEnabled() && now <= activeUntilMs;
    }

    private static boolean isBlitzStarOverlayActive(long now) {
        return ConfigManager.isCageChestOverlayEnabled() && now <= blitzStarActiveUntilMs;
    }

    private static void clearMarkedChests() {
        openedChestKeys.clear();
        blitzStarChestKeys.clear();
    }

    private static void markBlitzStarChestFromOpenContainer(Minecraft mc) {
        if (mc == null || !isBlitzStarOverlayActive(System.currentTimeMillis())
                || !(mc.currentScreen instanceof GuiChest)
                || mc.thePlayer == null
                || !(mc.thePlayer.openContainer instanceof ContainerChest)) {
            return;
        }
        IInventory lower = ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory();
        if (lower == null || !containsBlitzStar(lower)) {
            return;
        }
        BlockPos pos = recentInteractedChestPos();
        if (pos == null) {
            return;
        }
        markBlitzStarChestAt(mc, pos, lower.getSizeInventory() > 27);
    }

    private static boolean containsBlitzStar(IInventory inventory) {
        try {
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() == Items.nether_star) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static BlockPos recentInteractedChestPos() {
        if (lastInteractedChestPos == null
                || System.currentTimeMillis() - lastInteractedChestPosMs > CHEST_INTERACTION_TTL_MS) {
            return null;
        }
        return new BlockPos(lastInteractedChestPos);
    }

    private static void markBlitzStarChestAt(Minecraft mc, BlockPos pos, boolean includeOpenAdjacentChest) {
        openedChestKeys.add(key(pos));
        blitzStarChestKeys.add(key(pos));
        if (!includeOpenAdjacentChest || mc == null || mc.theWorld == null) {
            return;
        }
        markAdjacentOpenChest(mc, pos.add(1, 0, 0));
        markAdjacentOpenChest(mc, pos.add(-1, 0, 0));
        markAdjacentOpenChest(mc, pos.add(0, 0, 1));
        markAdjacentOpenChest(mc, pos.add(0, 0, -1));
    }

    private static void markAdjacentOpenChest(Minecraft mc, BlockPos pos) {
        try {
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            TileEntity te = mc.theWorld.getTileEntity(pos);
            if (block instanceof BlockChest && te instanceof TileEntityChest
                    && isChestCurrentlyOpened((TileEntityChest) te)) {
                openedChestKeys.add(key(pos));
                blitzStarChestKeys.add(key(pos));
            }
        } catch (Throwable ignored) {
        }
    }

    private static AxisAlignedBB getChestSelectionBox(Minecraft mc, BlockPos pos) {
        try {
            Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (!(block instanceof BlockChest)) {
                return null;
            }
            return block.getSelectedBoundingBox(mc.theWorld, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawBox(WorldRenderer wr, Tessellator tess, AxisAlignedBB worldBox,
                                double camX, double camY, double camZ, float r, float g, float b, float fade) {
        final double eps = 0.002;
        double x1 = worldBox.minX - camX - eps;
        double y1 = worldBox.minY - camY - eps;
        double z1 = worldBox.minZ - camZ - eps;
        double x2 = worldBox.maxX - camX + eps;
        double y2 = worldBox.maxY - camY + eps;
        double z2 = worldBox.maxZ - camZ + eps;

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // Fill-only red overlay (no dark/black-looking outline pass).
        addCubeQuads(wr, x1, y1, z1, x2, y2, z2, r, g, b, 0.28f * fade);
        tess.draw();
    }

    private static boolean isChestCurrentlyOpened(TileEntityChest chest) {
        if (chest == null) {
            return false;
        }
        try {
            return chest.numPlayersUsing > 0 || chest.lidAngle > 0.02f || chest.prevLidAngle > 0.02f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addCubeQuads(WorldRenderer wr,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
