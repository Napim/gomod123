package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * DeathmatchOppZone feature.
 *
 * Renders a light pulsing red tint on top of every surface block OUTSIDE the
 * DM zone within RENDER_RANGE of the player.  Each 1×1 block column gets its
 * own quad placed on the actual top face of the highest non-air block, so the
 * overlay hugs the terrain perfectly rather than floating in the air.
 *
 * Usage: /glowoppdm <map> | off | list
 */
public final class DeathmatchOppZone {

    // ── Singleton instance (for EventBus subscription) ────────────────────────
    public static final DeathmatchOppZone INSTANCE = new DeathmatchOppZone();

    // ── State ─────────────────────────────────────────────────────────────────
    private static boolean                  enabled   = false;
    private static DeathmatchGlow.MapBounds activeMap = null;

    private DeathmatchOppZone() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean activate(String mapKey) {
        DeathmatchGlow.MapBounds m = DeathmatchGlow.getMap(mapKey);
        if (m == null) return false;
        activeMap = m;
        enabled   = true;
        System.out.println("[DeathmatchOppZone] Activated for map: " + m.displayName
                + " (center " + (int) m.centerX + ", " + (int) m.centerZ
                + ", radius " + m.radius + ")");
        return true;
    }

    public static void deactivate() {
        enabled   = false;
        activeMap = null;
        System.out.println("[DeathmatchOppZone] Deactivated.");
    }

    public static boolean isEnabled() { return enabled && activeMap != null; }

    public static DeathmatchGlow.MapBounds getActiveMap() { return activeMap; }

    // ── Render constants ──────────────────────────────────────────────────────

    /** How far around the player we scan for surface blocks (in each direction). */
    private static final int RENDER_RANGE = 48;

    /** How far up/down from player Y we search for the surface block. */
    private static final int Y_SCAN_UP   = 12;
    private static final int Y_SCAN_DOWN = 20;

    /** Tiny offset above the block top face to prevent z-fighting. */
    private static final double ABOVE = 0.003;

    /**
     * Pulse: alpha oscillates between these limits (~1.6 s period).
     * Kept very low so the tint is light/subtle.
     */
    private static final float ALPHA_MIN = 0.07f;
    private static final float ALPHA_MAX = 0.17f;

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (!isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;

        // Camera (render-manager viewer) position
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        // ── Pulsing alpha ─────────────────────────────────────────────────────
        double pulse = Math.sin(System.currentTimeMillis() / 800.0); // -1 → 1
        float  alpha = ALPHA_MIN + (float) ((pulse + 1.0) * 0.5) * (ALPHA_MAX - ALPHA_MIN);

        // ── DM zone inclusive block bounds (same formula as DeathmatchGlow) ───
        DeathmatchGlow.MapBounds m = activeMap;
        int cxInt = (int) Math.floor(m.centerX);
        int czInt = (int) Math.floor(m.centerZ);
        int r     = m.radius;
        int bMinX = cxInt - (r / 2);
        int bMaxX = cxInt + ((r - 1) / 2);
        int bMinZ = czInt - (r / 2);
        int bMaxZ = czInt + ((r - 1) / 2);

        // Player block position (foot level)
        int pBX = (int) Math.floor(viewerX);
        int pBY = (int) Math.floor(viewerY - 1.62);
        int pBZ = (int) Math.floor(viewerZ);

        try {
            GlStateManager.pushMatrix();
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GlStateManager.disableLighting();
            GlStateManager.enableDepth();

            // GL_LEQUAL so the overlay only appears on visible surfaces —
            // blocks hidden behind walls won't bleed through.  Each quad is
            // placed at sy+1+ABOVE, which is just above the block top face
            // and will pass the depth test against that surface.
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(false); // don't corrupt depth buffer

            // Light pinkish-red (slight green+blue lift = desaturated / soft red)
            GlStateManager.color(1.0f, 0.35f, 0.35f, alpha);

            GL11.glDisable(GL11.GL_CULL_FACE);

            Tessellator   tess = Tessellator.getInstance();
            WorldRenderer wr   = tess.getWorldRenderer();
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);

            // ── Per-column surface scan ───────────────────────────────────────
            // For each block column within RENDER_RANGE of the player that lies
            // OUTSIDE the safe DM zone, find the top non-air block and emit a
            // 1×1 quad right on top of it.
            for (int bx = pBX - RENDER_RANGE; bx <= pBX + RENDER_RANGE; bx++) {
                for (int bz = pBZ - RENDER_RANGE; bz <= pBZ + RENDER_RANGE; bz++) {
                    // Skip safe zone columns
                    if (bx >= bMinX && bx <= bMaxX && bz >= bMinZ && bz <= bMaxZ) continue;

                    int sy = findSurface(mc, bx, bz, pBY);
                    if (sy == -1) continue;

                    double rx = bx - viewerX;
                    double ry = (sy + 1 + ABOVE) - viewerY;
                    double rz = bz - viewerZ;

                    wr.pos(rx,       ry, rz      ).endVertex();
                    wr.pos(rx + 1.0, ry, rz      ).endVertex();
                    wr.pos(rx + 1.0, ry, rz + 1.0).endVertex();
                    wr.pos(rx,       ry, rz + 1.0).endVertex();
                }
            }

            tess.draw();

            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.depthMask(true);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GlStateManager.disableBlend();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            GlStateManager.popMatrix();

        } catch (Throwable e) {
            System.err.println("[DeathmatchOppZone] Render error: " + e.getMessage());
            try {
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GlStateManager.depthMask(true);
                GL11.glEnable(GL11.GL_CULL_FACE);
                GlStateManager.disableBlend();
                GlStateManager.enableLighting();
                GlStateManager.enableTexture2D();
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                GlStateManager.popMatrix();
            } catch (Throwable ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the Y of the highest non-air block near {@code pBY}, or -1. */
    private static int findSurface(Minecraft mc, int bx, int bz, int pBY) {
        for (int by = pBY + Y_SCAN_UP; by >= pBY - Y_SCAN_DOWN; by--) {
            if (by < 0 || by > 255) continue;
            if (!mc.theWorld.isAirBlock(new BlockPos(bx, by, bz))) return by;
        }
        return -1;
    }
}
