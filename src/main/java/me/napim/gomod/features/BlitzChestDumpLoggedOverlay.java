package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * While {@link ChestDumpLogger.Kind#BLITZ_LOOT} is enabled, chest blocks successfully written
 * to the session dump file get a green fill overlay (same style as {@link CageChestOverlay} red).
 */
public final class BlitzChestDumpLoggedOverlay {

    private static final Set<String> loggedChestKeys = new HashSet<String>();

    private BlitzChestDumpLoggedOverlay() {
    }

    public static synchronized void markLogged(BlockPos pos) {
        if (pos != null) {
            loggedChestKeys.add(key(pos));
        }
    }

    public static synchronized void clear() {
        loggedChestKeys.clear();
    }

    public static void render(float partialTicks) {
        if (!ChestDumpLogger.isEnabled(ChestDumpLogger.Kind.BLITZ_LOOT)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (loggedChestKeys.isEmpty()) {
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
                if (pos == null || !isLogged(pos)) {
                    continue;
                }
                AxisAlignedBB worldBox = getChestSelectionBox(mc, pos);
                if (worldBox == null) {
                    continue;
                }
                drawBox(wr, tess, worldBox, camX, camY, camZ, 0.15f, 1.0f, 0.15f, 1.0f);
            }
        } finally {
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

    private static synchronized boolean isLogged(BlockPos pos) {
        return loggedChestKeys.contains(key(pos));
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
        addCubeQuads(wr, x1, y1, z1, x2, y2, z2, r, g, b, 0.28f * fade);
        tess.draw();
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

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
