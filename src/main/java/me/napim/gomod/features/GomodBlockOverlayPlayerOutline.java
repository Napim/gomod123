package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;

/**
 * Block overlay — dashed outline around the player the crosshair can attack (vanilla
 * {@code objectMouseOver} entity pick: 3 blocks survival / 6 creative), depth-tested.
 * When the next melee hit would crit, a small "+" is drawn on each interior dash (same color/size).
 */
public final class GomodBlockOverlayPlayerOutline {

    private static final double SKIN_PAD = 0.05D;
    private static final double DASH_LEN = 0.12D;
    private static final double GAP_LEN = 0.08D;
    /** Half-size of each "+" arm on crit-ready interior dashes. */
    private static final double CROSS_HALF = 0.035D;

    private GomodBlockOverlayPlayerOutline() {}

    public static boolean isEnabled() {
        return ConfigManager.isGomodBlockOverlayPlayerOverlayEnabled();
    }

    public static void render(float partialTicks) {
        if (!isEnabled()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.gameSettings.hideGUI) {
                return;
            }
            if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
                return;
            }
            EntityPlayer target = resolveCrosshairPlayer(mc);
            if (target == null) {
                return;
            }
            Entity view = mc.getRenderViewEntity();
            if (view == null) {
                return;
            }

            double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
            double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
            double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

            double ex = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks;
            double ey = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks;
            double ez = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks;
            double ox = ex - target.posX;
            double oy = ey - target.posY;
            double oz = ez - target.posZ;

            AxisAlignedBB bb = target.getEntityBoundingBox();
            if (bb == null) {
                return;
            }
            double minX = bb.minX + ox - camX - SKIN_PAD;
            double minY = bb.minY + oy - camY - SKIN_PAD;
            double minZ = bb.minZ + oz - camZ - SKIN_PAD;
            double maxX = bb.maxX + ox - camX + SKIN_PAD;
            double maxY = bb.maxY + oy - camY + SKIN_PAD;
            double maxZ = bb.maxZ + oz - camZ + SKIN_PAD;

            boolean critReady = wouldNextMeleeBeCrit(mc.thePlayer);
            int argb = ConfigManager.getGomodBlockOverlayColorArgb();
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float b = (argb & 0xFF) / 255f;
            float lineWidth = Math.max(1.5f, ConfigManager.getGomodBlockOverlayLineWidth());

            boolean thirdPerson = mc.gameSettings.thirdPersonView != 0;
            HudGlState.restoreBeforeWorldGeometryOverlay();
            GlStateManager.pushMatrix();
            try {
                GlStateManager.disableLighting();
                GlStateManager.disableTexture2D();
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.enableDepth();
                GlStateManager.depthFunc(515);
                GlStateManager.depthMask(true);
                GlStateManager.disableCull();
                GlStateManager.color(r, g, b, 1.0f);

                GL11.glLineWidth(lineWidth);
                GL11.glEnable(GL11.GL_LINE_SMOOTH);
                drawDashedBox(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, critReady);
            } finally {
                GL11.glDisable(GL11.GL_LINE_SMOOTH);
                GL11.glLineWidth(1.0f);
                GlStateManager.popMatrix();
                HudGlState.restoreAfterBlockOverlay(thirdPerson);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Mirrors {@code EntityPlayer#attackTargetEntityWithCurrentItem} crit check (1.8.9).
     */
    static boolean wouldNextMeleeBeCrit(EntityPlayer attacker) {
        if (attacker == null) {
            return false;
        }
        return attacker.fallDistance > 0.0F
                && !attacker.onGround
                && !attacker.isOnLadder()
                && !attacker.isInWater()
                && !attacker.isPotionActive(Potion.blindness)
                && attacker.ridingEntity == null;
    }

    /**
     * Same target vanilla uses for left-click attacks ({@code EntityRenderer#getMouseOver}).
     */
    private static EntityPlayer resolveCrosshairPlayer(Minecraft mc) {
        EntityPlayer self = mc.thePlayer;
        if (self == null) {
            return null;
        }
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY) {
            return null;
        }
        if (!(mop.entityHit instanceof EntityPlayer)) {
            return null;
        }
        EntityPlayer target = (EntityPlayer) mop.entityHit;
        return target == self ? null : target;
    }

    private static void drawDashedBox(double x1, double y1, double z1, double x2, double y2, double z2,
            float r, float g, float b, boolean crossOnInteriorDashes) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        addDashedEdge(wr, x1, y1, z1, x2, y1, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y1, z1, x2, y1, z2, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y1, z2, x1, y1, z2, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x1, y1, z2, x1, y1, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x1, y2, z1, x2, y2, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y2, z1, x2, y2, z2, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y2, z2, x1, y2, z2, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x1, y2, z2, x1, y2, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x1, y1, z1, x1, y2, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y1, z1, x2, y2, z1, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x2, y1, z2, x2, y2, z2, r, g, b, crossOnInteriorDashes);
        addDashedEdge(wr, x1, y1, z2, x1, y2, z2, r, g, b, crossOnInteriorDashes);

        tess.draw();
    }

    private static void addDashedEdge(WorldRenderer wr,
            double x1, double y1, double z1, double x2, double y2, double z2,
            float r, float g, float b, boolean crossOnInteriorDashes) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;
        double pos = 0.0D;
        boolean draw = true;
        while (pos < len - 1.0E-6D) {
            double seg = draw ? DASH_LEN : GAP_LEN;
            double next = Math.min(pos + seg, len);
            if (draw) {
                double sx = x1 + ux * pos;
                double sy = y1 + uy * pos;
                double sz = z1 + uz * pos;
                double ex = x1 + ux * next;
                double ey = y1 + uy * next;
                double ez = z1 + uz * next;
                vtx(wr, sx, sy, sz, r, g, b);
                vtx(wr, ex, ey, ez, r, g, b);
                if (crossOnInteriorDashes && !isCornerDash(pos, next, len)) {
                    addDashCross(wr, (sx + ex) * 0.5D, (sy + ey) * 0.5D, (sz + ez) * 0.5D, dx, dy, dz, r, g, b);
                }
            }
            pos = next;
            draw = !draw;
        }
    }

    /** Corner dashes sit where edges meet — skip "+" marks there to avoid clutter. */
    private static boolean isCornerDash(double pos, double next, double edgeLen) {
        return pos < 1.0E-4D || next >= edgeLen - 1.0E-4D;
    }

    /** Small "+" on an interior dash, in the plane perpendicular to the box edge. */
    private static void addDashCross(WorldRenderer wr, double cx, double cy, double cz,
            double edx, double edy, double edz, float r, float g, float b) {
        double adx = Math.abs(edx);
        double ady = Math.abs(edy);
        double adz = Math.abs(edz);
        double h = CROSS_HALF;
        if (adx >= ady && adx >= adz) {
            vtx(wr, cx, cy - h, cz, r, g, b);
            vtx(wr, cx, cy + h, cz, r, g, b);
            vtx(wr, cx, cy, cz - h, r, g, b);
            vtx(wr, cx, cy, cz + h, r, g, b);
        } else if (ady >= adx && ady >= adz) {
            vtx(wr, cx - h, cy, cz, r, g, b);
            vtx(wr, cx + h, cy, cz, r, g, b);
            vtx(wr, cx, cy, cz - h, r, g, b);
            vtx(wr, cx, cy, cz + h, r, g, b);
        } else {
            vtx(wr, cx - h, cy, cz, r, g, b);
            vtx(wr, cx + h, cy, cz, r, g, b);
            vtx(wr, cx, cy - h, cz, r, g, b);
            vtx(wr, cx, cy + h, cz, r, g, b);
        }
    }

    private static void vtx(WorldRenderer wr, double x, double y, double z, float r, float g, float b) {
        wr.pos(x, y, z).color(r, g, b, 1.0f).endVertex();
    }
}
