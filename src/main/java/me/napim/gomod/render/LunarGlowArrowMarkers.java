package me.napim.gomod.render;

import me.napim.gomod.features.GlowPlayer;
import me.napim.gomod.features.PlayerQueuer;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.features.SpectatorKillerNametagOverlay;
import me.napim.gomod.util.GlowTrailRenderer;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** Lightweight native-Weave fallback for player glow: animated depth-tested arrows above marked players. */
public final class LunarGlowArrowMarkers {
    private static final double VANILLA_LABEL_OFFSET_ABOVE_HEAD_Y = 0.5D;
    private static final double FALLBACK_TAG_ROW_STEP_Y = 0.276D;
    private static final double TAG_STACK_CLEARANCE_Y = 0.22D;
    private static final double ARROW_BOB_AMPLITUDE_Y = 0.07D;
    private static final double ARROW_HEAD_HEIGHT_Y = 0.28D;
    private static final double ARROW_SHAFT_HEIGHT_Y = 0.42D;
    private static final double ARROW_HEAD_RADIUS = 0.18D;
    private static final double ARROW_SHAFT_RADIUS = 0.06D;
    private static final int RESERVED_CLIENT_TAG_ROWS = 1;
    private static final double MAX_DISTANCE_SQ = 128.0D * 128.0D;
    private static final int MAX_MARKERS = 32;

    private static long lastAttemptMs;
    private static long lastRenderMs;
    private static int lastMarkerCount;
    private static String lastStatus = "not run";

    private LunarGlowArrowMarkers() {
    }

    public static void render(float partialTicks) {
        lastAttemptMs = System.currentTimeMillis();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null) {
            lastStatus = "missing mc/world/player/renderManager";
            return;
        }
        if (!shouldRun()) {
            lastMarkerCount = 0;
            lastStatus = "idle";
            return;
        }

        List<Marker> markers = collectMarkers(mc, partialTicks);
        lastMarkerCount = markers.size();
        if (markers.isEmpty()) {
            lastStatus = "no loaded marker targets";
            return;
        }

        Entity camera = mc.getRenderViewEntity();
        if (camera == null) {
            lastStatus = "missing renderViewEntity";
            return;
        }
        double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;

        GlStateManager.pushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_CULL_FACE);

            long now = System.currentTimeMillis();
            for (Marker marker : markers) {
                drawMarker(marker, camX, camY, camZ, now);
            }
            lastRenderMs = now;
            lastStatus = "rendered markers=" + markers.size();
        } catch (Throwable t) {
            lastStatus = "error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            System.err.println("[gomod] Lunar glow arrow marker: " + t.getMessage());
        } finally {
            GL11.glPopAttrib();
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    public static String debugDumpCompact() {
        long now = System.currentTimeMillis();
        return "LunarGlowArrowMarkers{status=" + lastStatus
                + ",markers=" + lastMarkerCount
                + ",lastAttemptMsAgo=" + (lastAttemptMs > 0L ? now - lastAttemptMs : -1L)
                + ",lastRenderMsAgo=" + (lastRenderMs > 0L ? now - lastRenderMs : -1L)
                + "}";
    }

    private static boolean shouldRun() {
        if (!GlowPlayer.getPlayers().isEmpty() || !GlowPlayer.getPlayers2().isEmpty()) {
            return true;
        }
        if (PlayerTarget.isGlowTeammateEnabled() || PlayerTarget.isTeammateTrailEnabled()) {
            return true;
        }
        return PlayerQueuer.isEnabled()
                && (!PlayerQueuer.getTargets().isEmpty() || !PlayerQueuer.getExcludeTargets().isEmpty());
    }

    private static List<Marker> collectMarkers(Minecraft mc, float partialTicks) {
        List<Marker> out = new ArrayList<Marker>();
        List<EntityPlayer> players = new ArrayList<EntityPlayer>();
        try {
            players.addAll(mc.theWorld.playerEntities);
        } catch (Throwable ignored) {
        }
        for (EntityPlayer player : players) {
            if (out.size() >= MAX_MARKERS) {
                break;
            }
            if (player == null || player == mc.thePlayer || player.isDead || player.getName() == null) {
                continue;
            }
            if (mc.thePlayer.getDistanceSqToEntity(player) > MAX_DISTANCE_SQ) {
                continue;
            }

            boolean manualV1 = GlowPlayer.isGlowingPlayer(player);
            boolean manualV2 = GlowPlayer.isGlowingPlayer2(player);
            boolean teammateGlow = PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(player);
            boolean teammateTrailOnly = !manualV1 && !manualV2 && !teammateGlow
                    && PlayerTarget.isTeammateTrailEnabled() && PlayerTarget.isEntityCurrentTeammate(player);
            boolean queuerInclude = PlayerQueuer.isQueuedTargetPlayer(player);
            boolean queuerExclude = PlayerQueuer.isQueuedExcludePlayer(player);

            if (!manualV1 && !manualV2 && !teammateGlow && !teammateTrailOnly && !queuerInclude && !queuerExclude) {
                continue;
            }

            int rgb = resolveMarkerColor(manualV1, manualV2, teammateGlow, teammateTrailOnly,
                    queuerInclude, queuerExclude);
            if (player.hurtTime > 0 && (manualV1 || teammateGlow || teammateTrailOnly)) {
                rgb = 0xFF3B30;
            }

            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks
                    + player.height + markerTipOffsetAboveEntityHeight(mc, player);
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            out.add(new Marker(player, x, y, z, rgb));

            if (manualV1 || manualV2 || (teammateGlow && PlayerTarget.isTeammateTrailEnabled()) || teammateTrailOnly) {
                recordTrail(player, partialTicks, rgb);
            }
        }
        return out;
    }

    private static double markerTipOffsetAboveEntityHeight(Minecraft mc, EntityPlayer player) {
        int rowsAboveVanillaName = RESERVED_CLIENT_TAG_ROWS + countKnownRowsAboveMainName(player);
        if (shouldReserveVanillaHealthRow(mc, player)) {
            rowsAboveVanillaName++;
        }
        return VANILLA_LABEL_OFFSET_ABOVE_HEAD_Y
                + rowStepWorldY(mc) * rowsAboveVanillaName
                + ARROW_BOB_AMPLITUDE_Y
                + TAG_STACK_CLEARANCE_Y;
    }

    private static int countKnownRowsAboveMainName(EntityPlayer player) {
        int rows = 0;
        try {
            rows += PlayerNametagOverlayStack.countOverlayRowsAboveMainIgn(player);
        } catch (Throwable ignored) {
        }
        try {
            if (SpectatorKillerNametagOverlay.shouldShowFor(player)
                    && !SpectatorKillerNametagOverlay.shouldUseMainIgnSlotForKiller(player)) {
                rows++;
            }
        } catch (Throwable ignored) {
        }
        return Math.max(0, rows);
    }

    private static boolean shouldReserveVanillaHealthRow(Minecraft mc, EntityPlayer player) {
        if (!(player instanceof AbstractClientPlayer)) {
            return false;
        }
        try {
            Entity camera = mc != null ? mc.getRenderViewEntity() : null;
            double distanceSq = camera != null
                    ? camera.getDistanceSqToEntity(player)
                    : Minecraft.getMinecraft().thePlayer.getDistanceSqToEntity(player);
            return VanillaPlayerNametagLayout.wouldRenderTabHealthNametagRow(
                    (AbstractClientPlayer) player, distanceSq);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double rowStepWorldY(Minecraft mc) {
        try {
            if (mc != null && mc.fontRendererObj != null) {
                return (double) mc.fontRendererObj.FONT_HEIGHT
                        * (double) VanillaPlayerNametagLayout.VANILLA_NAMETAG_LINE_GAP_FACTOR
                        * (double) VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE;
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_TAG_ROW_STEP_Y;
    }

    private static int resolveMarkerColor(boolean manualV1, boolean manualV2, boolean teammateGlow,
                                          boolean teammateTrailOnly, boolean queuerInclude, boolean queuerExclude) {
        if (queuerExclude) {
            return PlayerQueuer.QUEUER_EXCLUDE_OUTLINE_RGB;
        }
        if (queuerInclude) {
            return PlayerQueuer.QUEUER_INCLUDE_OUTLINE_RGB;
        }
        if (manualV1) {
            return GlowPlayer.getColorRgb();
        }
        if (teammateGlow || teammateTrailOnly) {
            return PlayerTarget.getGlowTeammateColorRgb();
        }
        if (manualV2) {
            return 0xFFFFFF;
        }
        return PlayerQueuer.QUEUER_INCLUDE_OUTLINE_RGB;
    }

    private static void recordTrail(EntityPlayer player, float partialTicks, int rgb) {
        try {
            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            String key = GlowPlayer.resolveMatchedGlowName(player);
            if (key == null) {
                key = GlowPlayer.resolveMatchedGlowName2(player);
            }
            GlowTrailRenderer.recordPosition(key != null ? key : player.getName(), x, y, z, rgb);
        } catch (Throwable ignored) {
        }
    }

    private static void drawMarker(Marker marker, double camX, double camY, double camZ, long nowMs) {
        float r = ((marker.rgb >> 16) & 0xFF) / 255.0F;
        float g = ((marker.rgb >> 8) & 0xFF) / 255.0F;
        float b = (marker.rgb & 0xFF) / 255.0F;
        float pulse = 0.5F + 0.5F * (float) Math.sin((nowMs % 900L) / 900.0D * Math.PI * 2.0D);
        float alpha = 0.76F + pulse * 0.20F;
        double bob = Math.sin((nowMs % 1200L) / 1200.0D * Math.PI * 2.0D) * ARROW_BOB_AMPLITUDE_Y;
        float scale = 0.94F + pulse * 0.10F;
        float spin = ((nowMs % 3600L) / 3600.0F) * 360.0F + (marker.player.getEntityId() * 29 % 360);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(marker.x - camX, marker.y + bob - camY, marker.z - camZ);
            GlStateManager.rotate(spin, 0.0F, 1.0F, 0.0F);
            GlStateManager.scale(scale, scale, scale);

            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();

            GlStateManager.pushMatrix();
            try {
                GlStateManager.scale(1.08F, 1.04F, 1.08F);
                drawArrow3D(wr, tess, 0.0F, 0.0F, 0.0F, 0.30F);
            } finally {
                GlStateManager.popMatrix();
            }
            drawArrow3D(wr, tess, r, g, b, alpha);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private static void drawArrow3D(WorldRenderer wr, Tessellator tess, float r, float g, float b, float a) {
        double headY = ARROW_HEAD_HEIGHT_Y;
        double shaftTopY = ARROW_HEAD_HEIGHT_Y + ARROW_SHAFT_HEIGHT_Y;
        double h = ARROW_HEAD_RADIUS;
        double s = ARROW_SHAFT_RADIUS;

        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, 0.0D, 0.0D, 0.0D, r, g, b, a, 0.98F);
        vertex(wr, -h, headY, -h, r, g, b, a, 0.78F);
        vertex(wr, h, headY, -h, r, g, b, a, 0.90F);

        vertex(wr, 0.0D, 0.0D, 0.0D, r, g, b, a, 1.08F);
        vertex(wr, h, headY, -h, r, g, b, a, 0.90F);
        vertex(wr, h, headY, h, r, g, b, a, 1.10F);

        vertex(wr, 0.0D, 0.0D, 0.0D, r, g, b, a, 1.06F);
        vertex(wr, h, headY, h, r, g, b, a, 1.10F);
        vertex(wr, -h, headY, h, r, g, b, a, 0.94F);

        vertex(wr, 0.0D, 0.0D, 0.0D, r, g, b, a, 0.92F);
        vertex(wr, -h, headY, h, r, g, b, a, 0.94F);
        vertex(wr, -h, headY, -h, r, g, b, a, 0.78F);
        tess.draw();

        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        quad(wr, -s, headY, -s, s, headY, -s, s, shaftTopY, -s, -s, shaftTopY, -s,
                r, g, b, a, 0.82F);
        quad(wr, s, headY, -s, s, headY, s, s, shaftTopY, s, s, shaftTopY, -s,
                r, g, b, a, 1.03F);
        quad(wr, s, headY, s, -s, headY, s, -s, shaftTopY, s, s, shaftTopY, s,
                r, g, b, a, 1.14F);
        quad(wr, -s, headY, s, -s, headY, -s, -s, shaftTopY, -s, -s, shaftTopY, s,
                r, g, b, a, 0.92F);
        quad(wr, -s, shaftTopY, -s, -s, shaftTopY, s, s, shaftTopY, s, s, shaftTopY, -s,
                r, g, b, a, 1.18F);
        tess.draw();
    }

    private static void quad(WorldRenderer wr,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                             float r, float g, float b, float a, float shade) {
        vertex(wr, x1, y1, z1, r, g, b, a, shade);
        vertex(wr, x2, y2, z2, r, g, b, a, shade);
        vertex(wr, x3, y3, z3, r, g, b, a, shade);
        vertex(wr, x4, y4, z4, r, g, b, a, shade);
    }

    private static void vertex(WorldRenderer wr, double x, double y, double z,
                               float r, float g, float b, float a, float shade) {
        wr.pos(x, y, z).color(clampColor(r * shade), clampColor(g * shade), clampColor(b * shade), a).endVertex();
    }

    private static float clampColor(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    private static final class Marker {
        final EntityPlayer player;
        final double x;
        final double y;
        final double z;
        final int rgb;

        Marker(EntityPlayer player, double x, double y, double z, int rgb) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rgb = rgb & 0xFFFFFF;
        }
    }
}
