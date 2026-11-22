package me.napim.gomod.features;

import me.napim.gomod.render.LabelBillboardAngles;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Player aim tracers with tick-cached rays and the original animated face marker. */
public final class PlayerTracers {
    private static boolean enabled, showSelf, cagesAutoEnabled;
    private static double maxRange = 64.0;
    private static boolean cagesLineVisibleLastTick, deathmatchPhaseLastTick;
    private static long cagesGraceUntilMs;
    private static final long CAGES_POST_OPEN_GRACE_MS = 4000L;
    private static volatile World chestStopWorld;
    private static volatile boolean firstChestOpenedInRound;
    private static World phaseWorld;
    private static long phaseTick = Long.MIN_VALUE;
    private static boolean phaseCached;
    private static OverlayStyle overlayStyle = OverlayStyle.BOTH;
    private static OverlayDepthMode overlayDepthMode = OverlayDepthMode.DEPTH;
    private static OverlayStrength overlayStrength = OverlayStrength.STRONG;
    private static final PlayerTracerTickCache<RaySample> RAYS = new PlayerTracerTickCache<RaySample>();
    private static final List<RaySample> TARGETS = new ArrayList<RaySample>(32);
    private static final Set<BlockPos> OVERLAY_BLOCKS = new HashSet<BlockPos>();

    private static final Set<Block> PASS_THROUGH = new HashSet<Block>(Arrays.asList(
            Blocks.glass, Blocks.glass_pane, Blocks.stained_glass, Blocks.stained_glass_pane, Blocks.barrier));

    // Minecraft's legacy text palette, indexed by §0-§f in the resolved nametag.
    private static final int[] NAMETAG_COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    // n, u, v axes; indexed by EnumFacing.getIndex(): down, up, north, south, west, east.
    private static final double[][] FACE_AXES = {
            {0,-1,0, 1,0,0, 0,0,-1}, {0,1,0, 1,0,0, 0,0,1},
            {0,0,-1, -1,0,0, 0,1,0}, {0,0,1, 1,0,0, 0,1,0},
            {-1,0,0, 0,0,1, 0,1,0}, {1,0,0, 0,0,-1, 0,1,0}
    };
    private PlayerTracers() {}
    public static void toggle() { enabled = !enabled; }
    public static boolean isEnabled() { return enabled; }
    public static void setShowSelf(boolean value) { showSelf = value; }
    public static boolean isShowSelf() { return showSelf; }
    public static void setMaxRange(double range) {
        if (!Double.isNaN(range) && !Double.isInfinite(range)) maxRange = Math.max(8.0, Math.min(256.0, range));
    }
    public static double getMaxRange() { return maxRange; }
    public static void toggleCagesAuto() { setCagesAutoEnabled(!cagesAutoEnabled); }
    public static boolean isCagesAutoEnabled() { return cagesAutoEnabled; }
    public static void setCagesAutoEnabled(boolean value) {
        if (cagesAutoEnabled != value) resetPhase();
        cagesAutoEnabled = value;
    }
    public static OverlayStyle getOverlayStyle() { return overlayStyle; }
    public static void setOverlayStyle(OverlayStyle value) {
        OverlayStyle next = value == null ? OverlayStyle.BOTH : value;
        if (overlayStyle != next) RAYS.clear();
        overlayStyle = next;
    }
    public static OverlayDepthMode getOverlayDepthMode() { return overlayDepthMode; }
    public static void setOverlayDepthMode(OverlayDepthMode value) { overlayDepthMode = value == null ? OverlayDepthMode.DEPTH : value; }
    public static OverlayStrength getOverlayStrength() { return overlayStrength; }
    public static void setOverlayStrength(OverlayStrength value) { overlayStrength = value == null ? OverlayStrength.NORMAL : value; }

    public enum OverlayStyle {
        OFF, FILL, OUTLINE, BOTH;
        public static OverlayStyle parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("off".equals(value) || "none".equals(value)) return OFF;
            if ("fill".equals(value)) return FILL;
            if ("outline".equals(value) || "line".equals(value) || "wire".equals(value)) return OUTLINE;
            if ("both".equals(value) || "all".equals(value)) return BOTH;
            return null;
        }
    }
    public enum OverlayDepthMode {
        DEPTH, XRAY;
        public static OverlayDepthMode parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("depth".equals(value) || "normal".equals(value)) return DEPTH;
            if ("xray".equals(value) || "through".equals(value)) return XRAY;
            return null;
        }
    }
    public enum OverlayStrength {
        SOFT, NORMAL, STRONG;
        public static OverlayStrength parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if ("soft".equals(value) || "low".equals(value)) return SOFT;
            if ("normal".equals(value) || "default".equals(value) || "med".equals(value) || "medium".equals(value)) return NORMAL;
            if ("strong".equals(value) || "high".equals(value)) return STRONG;
            return null;
        }
    }

    private static void resetPhase() {
        phaseWorld = null;
        phaseTick = Long.MIN_VALUE;
        phaseCached = cagesLineVisibleLastTick = deathmatchPhaseLastTick = false;
        cagesGraceUntilMs = 0;
    }

    private static void syncChestStopWorld(World world) {
        if (chestStopWorld != world) {
            chestStopWorld = world;
            firstChestOpenedInRound = false;
        }
    }

    private static void updateChestStopRound(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            syncChestStopWorld(null);
            return;
        }
        syncChestStopWorld(mc.theWorld);
        if (!firstChestOpenedInRound) return;
        // Hypixel can keep the same WorldClient alive across Blitz rounds. Clear the
        // one-chest latch after the round ends, even if Cage Tracers are toggled off.
        boolean matchPregame = me.napim.gomod.util.GameContext.isBlitzMatchMapPregameSidebar(mc);
        if (me.napim.gomod.util.GameContext.isBlitzGameOverSidebar(mc)
                || me.napim.gomod.util.GameContext.isBlitzHypixelHubQueueSidebar(mc)
                || (me.napim.gomod.util.GameContext.isBlitzPregameLobby(mc) && !matchPregame)) {
            firstChestOpenedInRound = false;
            resetPhase();
        }
    }

    /** Called on Minecraft's client thread after the server broadcasts a chest-open block event. */
    public static void onFirstChestOpened(World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (world == null || mc == null || mc.theWorld != world
                || !me.napim.gomod.util.GameContext.isOnHypixel(mc)) {
            return;
        }
        boolean blitzMatch = me.napim.gomod.util.GameContext.isBlitzCagePhaseSidebar(mc)
                || me.napim.gomod.util.GameContext.isBlitzMatchMapPregameSidebar(mc)
                || me.napim.gomod.util.GameContext.isBlitzGameActive(mc);
        if (!blitzMatch) return;
        syncChestStopWorld(world);
        firstChestOpenedInRound = true;
    }

    private static boolean isCagesAutoWindowActive(Minecraft mc, long now, CageTracerDebug.Frame debug) {
        updateChestStopRound(mc);
        if (!cagesAutoEnabled) return false;
        // Round-end latch cleanup must not let stale cage/deathmatch lines reopen the grace window.
        if (me.napim.gomod.util.GameContext.isBlitzGameOverSidebar(mc)
                || me.napim.gomod.util.GameContext.isBlitzHypixelHubQueueSidebar(mc)) {
            resetPhase();
            return false;
        }
        if (firstChestOpenedInRound) return false;
        if (phaseWorld != mc.theWorld) {
            resetPhase();
            phaseWorld = mc.theWorld;
        }
        long tick = mc.theWorld.getTotalWorldTime();
        if (phaseTick == tick) {
            if (debug != null) debug.gateCacheHits++;
            return phaseCached;
        }
        phaseTick = tick;
        if (!me.napim.gomod.util.GameContext.isOnHypixel(mc)) {
            cagesLineVisibleLastTick = deathmatchPhaseLastTick = phaseCached = false;
            cagesGraceUntilMs = 0;
            return false;
        }
        boolean cages = HypixelScoreboardMapResolver.hasCagesOpenLine(mc);
        boolean deathmatch = me.napim.gomod.util.GameContext.isBlitzDeathmatchPhase(mc);
        if (cages) {
            cagesLineVisibleLastTick = true;
            deathmatchPhaseLastTick = deathmatch;
            return phaseCached = true;
        }
        if (cagesLineVisibleLastTick || (!deathmatchPhaseLastTick && deathmatch)) {
            cagesGraceUntilMs = now + CAGES_POST_OPEN_GRACE_MS;
        }
        cagesLineVisibleLastTick = false;
        deathmatchPhaseLastTick = deathmatch;
        return phaseCached = now <= cagesGraceUntilMs;
    }

    private static final class RayResult {
        final Vec3 hitPos;
        final boolean hitSolid;
        final EnumFacing face;
        final BlockPos blockPos;
        RayResult(Vec3 hitPos, boolean hitSolid, EnumFacing face, BlockPos blockPos) {
            this.hitPos = hitPos; this.hitSolid = hitSolid; this.face = face; this.blockPos = blockPos;
        }
    }

    private static RayResult castRay(World world, Vec3 start, Vec3 direction, double range, CageTracerDebug.Frame debug) {
        Vec3 end = start.addVector(direction.xCoord * range, direction.yCoord * range, direction.zCoord * range);
        Vec3 from = start;
        double remaining = range;
        int attempts = (int) Math.ceil(range * 1.75) + 4;
        for (int attempt = 0; attempt < attempts && remaining > 0.0001; attempt++) {
            if (debug != null) debug.worldRays++;
            MovingObjectPosition hit = world.rayTraceBlocks(from, end);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                return new RayResult(end, false, null, null);
            }
            BlockPos position = hit.getBlockPos();
            Block block;
            try {
                block = world.getBlockState(position).getBlock();
            } catch (Throwable ignored) {
                return new RayResult(hit.hitVec, false, null, null);
            }
            if (!PASS_THROUGH.contains(block)) return new RayResult(hit.hitVec, true, hit.sideHit, position);
            if (debug != null) debug.transparentSkips++;
            double step = PlayerTracerRayMath.distancePastVoxel(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord,
                    direction.xCoord, direction.yCoord, direction.zCoord,
                    position.getX(), position.getY(), position.getZ());
            from = hit.hitVec.addVector(direction.xCoord * step, direction.yCoord * step, direction.zCoord * step);
            remaining = range - ((from.xCoord - start.xCoord) * direction.xCoord
                    + (from.yCoord - start.yCoord) * direction.yCoord + (from.zCoord - start.zCoord) * direction.zCoord);
        }
        if (remaining > 0.0001 && debug != null) debug.rayLimits++;
        return new RayResult(end, false, null, null);
    }

    private static final class RaySample {
        final EntityPlayer player;
        final Vec3 look;
        final RayResult ray;
        final AxisAlignedBB box;
        final String name;
        int color;
        int nameWidth = -1;
        boolean overlay;
        RaySample(Minecraft mc, EntityPlayer player, CageTracerDebug.Frame debug, int color) {
            this.player = player;
            look = player.getLook(1.0F);
            Vec3 start = new Vec3(player.posX + look.xCoord * 0.20,
                    player.posY + player.getEyeHeight() + look.yCoord * 0.20, player.posZ + look.zCoord * 0.20);
            ray = castRay(mc.theWorld, start, look, maxRange, debug);
            box = ray.hitSolid && overlayStyle != OverlayStyle.OFF ? selectionBox(mc.theWorld, ray.blockPos) : null;
            name = player.getName();
            this.color = color;
        }
    }

    private static AxisAlignedBB selectionBox(World world, BlockPos position) {
        try {
            Block block = world.getBlockState(position).getBlock();
            block.setBlockBoundsBasedOnState(world, position);
            return block.getSelectedBoundingBox(world, position);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clientTick(Minecraft mc) {
        CageTracerDebug.clientTick();
        if (mc == null || mc.theWorld == null) {
            RAYS.clear(); TARGETS.clear(); OVERLAY_BLOCKS.clear(); resetPhase();
            syncChestStopWorld(null);
            return;
        }
        updateChestStopRound(mc);
        if (mc.thePlayer == null) {
            RAYS.clear(); TARGETS.clear(); OVERLAY_BLOCKS.clear(); resetPhase();
        }
    }

    /** Called by both world-render bridges. Expensive target work is shared by all frames in a tick. */
    public static void render(float partialTicks) {
        CageTracerDebug.Frame debug = CageTracerDebug.beginFrame();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                RAYS.clear(); TARGETS.clear(); OVERLAY_BLOCKS.clear(); resetPhase();
                return;
            }
            long gateStart = debug == null ? 0 : System.nanoTime();
            boolean cages = isCagesAutoWindowActive(mc, System.currentTimeMillis(), debug);
            if (debug != null) {
                debug.gateNs = System.nanoTime() - gateStart;
                debug.cages = cages;
                debug.active = enabled || cages;
                debug.loadedPlayers = mc.theWorld.playerEntities.size();
            }
            if (!enabled && !cages) {
                RAYS.clear(); TARGETS.clear(); OVERLAY_BLOCKS.clear();
                return;
            }
            net.minecraft.entity.Entity camera = mc.getRenderViewEntity();
            if (camera == null) return;
            long raysStart = debug == null ? 0 : System.nanoTime();
            RAYS.begin(mc.theWorld, mc.theWorld.getTotalWorldTime(), maxRange);
            TARGETS.clear();
            OVERLAY_BLOCKS.clear();
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == null || (!showSelf && player == mc.thePlayer)) continue;
                if (player.isDead) continue;
                RaySample sample = RAYS.get(player);
                if (sample == null) {
                    if (debug != null) debug.rayCasts++;
                    sample = new RaySample(mc, player, debug, resolveNametagColor(mc, player));
                    RAYS.put(player, sample);
                } else {
                    if (debug != null) debug.cacheHits++;
                }
                sample.overlay = sample.box != null && OVERLAY_BLOCKS.add(sample.ray.blockPos);
                TARGETS.add(sample);
            }
            if (debug != null) {
                debug.rayNs = System.nanoTime() - raysStart;
                debug.targets = TARGETS.size();
            }
            if (TARGETS.isEmpty()) return;
            double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
            double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
            double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
            long geometryStart = debug == null ? 0 : System.nanoTime();
            GlStateManager.pushMatrix();
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.depthMask(false);
                Tessellator tess = Tessellator.getInstance();
                WorldRenderer wr = tess.getWorldRenderer();
                GlStateManager.disableDepth();
                long nowMs = System.currentTimeMillis();
                drawTracers(wr, tess, partialTicks, camX, camY, camZ, debug);
                drawBlockOverlays(wr, tess, camX, camY, camZ, debug);
                GlStateManager.disableDepth();
                drawFaceMarkerPass(wr, tess, camX, camY, camZ, nowMs, true, false, debug);
                drawFaceMarkerPass(wr, tess, camX, camY, camZ, nowMs, true, true, debug);
                drawFaceMarkerPass(wr, tess, camX, camY, camZ, nowMs, false, false, debug);
                drawFaceMarkerPass(wr, tess, camX, camY, camZ, nowMs, false, true, debug);
                if (debug != null) debug.geometryNs += System.nanoTime() - geometryStart;
                long labelsStart = debug == null ? 0 : System.nanoTime();
                if (ConfigManager.isPtracersTargetNametagEnabled()) {
                    for (RaySample sample : TARGETS) {
                        if (sample.ray.hitSolid) renderTracerTargetNametag(mc, sample, camX, camY, camZ, debug);
                    }
                }
                if (debug != null) debug.labelNs = System.nanoTime() - labelsStart;
            } finally {
                long restoreStart = debug == null ? 0 : System.nanoTime();
                GL11.glLineWidth(1.0F);
                GL11.glPopAttrib();
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.disableBlend();
                GlStateManager.enableLighting();
                GlStateManager.enableTexture2D();
                GlStateManager.color(1, 1, 1, 1);
                GlStateManager.popMatrix();
                if (debug != null) debug.geometryNs += System.nanoTime() - restoreStart;
            }
        } finally {
            CageTracerDebug.endFrame(debug);
        }
    }

    private static void drawTracers(WorldRenderer wr, Tessellator tess, float partialTicks,
                                    double camX, double camY, double camZ, CageTracerDebug.Frame debug) {
        GL11.glLineWidth(1.8F);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int vertices = 0;
        for (RaySample sample : TARGETS) {
            EntityPlayer player = sample.player;
            Vec3 look = sample.look;
            double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks + look.xCoord * 0.20 - camX;
            double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.getEyeHeight() + look.yCoord * 0.20 - camY;
            double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks + look.zCoord * 0.20 - camZ;
            float r = ((sample.color >> 16) & 255) / 255F;
            float g = ((sample.color >> 8) & 255) / 255F;
            float b = (sample.color & 255) / 255F;
            Vec3 hit = sample.ray.hitPos;
            wr.pos(x, y, z).color(r, g, b, 0.9F).endVertex();
            wr.pos(hit.xCoord - camX, hit.yCoord - camY, hit.zCoord - camZ)
                    .color(r, g, b, 0.35F).endVertex();
            vertices += 2;
        }
        tess.draw();
        if (debug != null) { debug.draws++; debug.vertices += vertices; }
    }

    /** Batches each original X/circle glow and core pass across all targets. */
    private static void drawFaceMarkerPass(WorldRenderer wr, Tessellator tess,
                                           double camX, double camY, double camZ, long nowMs,
                                           boolean glow, boolean circle, CageTracerDebug.Frame debug) {
        GL11.glLineWidth(circle ? (glow ? 4.5F : 1.4F) : (glow ? 5.0F : 1.8F));
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int vertices = 0;
        for (RaySample sample : TARGETS) {
            if (!sample.ray.hitSolid) continue;
            Vec3 hit = sample.ray.hitPos;
            double[] axes = FACE_AXES[(sample.ray.face == null ? EnumFacing.UP : sample.ray.face).getIndex()];
            double cx = hit.xCoord - camX + axes[0] * 0.028;
            double cy = hit.yCoord - camY + axes[1] * 0.028;
            double cz = hit.zCoord - camZ + axes[2] * 0.028;
            float r = ((sample.color >> 16) & 255) / 255F;
            float g = ((sample.color >> 8) & 255) / 255F;
            float b = (sample.color & 255) / 255F;
            float alpha = circle ? (glow ? 0.16F : 0.85F) : (glow ? 0.20F : 1.0F);
            if (!circle) {
                double angle = (nowMs % 2000L) / 2000.0 * Math.PI * 2.0 + Math.PI * 0.25;
                double cosA = Math.cos(angle), sinA = Math.sin(angle);
                double a1x = cosA * axes[3] + sinA * axes[6];
                double a1y = cosA * axes[4] + sinA * axes[7];
                double a1z = cosA * axes[5] + sinA * axes[8];
                double a2x = -sinA * axes[3] + cosA * axes[6];
                double a2y = -sinA * axes[4] + cosA * axes[7];
                double a2z = -sinA * axes[5] + cosA * axes[8];
                double arm = 0.21;
                wr.pos(cx + a1x * arm, cy + a1y * arm, cz + a1z * arm).color(r, g, b, alpha).endVertex();
                wr.pos(cx - a1x * arm, cy - a1y * arm, cz - a1z * arm).color(r, g, b, alpha).endVertex();
                wr.pos(cx + a2x * arm, cy + a2y * arm, cz + a2z * arm).color(r, g, b, alpha).endVertex();
                wr.pos(cx - a2x * arm, cy - a2y * arm, cz - a2z * arm).color(r, g, b, alpha).endVertex();
                vertices += 4;
            } else {
                double pulse = 0.5 + 0.5 * Math.sin((nowMs % 1400L) / 1400.0 * Math.PI * 2.0);
                double radius = 0.27 + pulse * 0.055;
                final int segments = 40;
                for (int i = 0; i < segments; i++) {
                    double a = i / (double) segments * Math.PI * 2.0;
                    double next = (i + 1) / (double) segments * Math.PI * 2.0;
                    double x1 = (Math.cos(a) * axes[3] + Math.sin(a) * axes[6]) * radius;
                    double y1 = (Math.cos(a) * axes[4] + Math.sin(a) * axes[7]) * radius;
                    double z1 = (Math.cos(a) * axes[5] + Math.sin(a) * axes[8]) * radius;
                    double x2 = (Math.cos(next) * axes[3] + Math.sin(next) * axes[6]) * radius;
                    double y2 = (Math.cos(next) * axes[4] + Math.sin(next) * axes[7]) * radius;
                    double z2 = (Math.cos(next) * axes[5] + Math.sin(next) * axes[8]) * radius;
                    wr.pos(cx + x1, cy + y1, cz + z1).color(r, g, b, alpha).endVertex();
                    wr.pos(cx + x2, cy + y2, cz + z2).color(r, g, b, alpha).endVertex();
                }
                vertices += segments * 2;
            }
        }
        tess.draw();
        if (debug != null) { debug.draws++; debug.vertices += vertices; }
    }

    private static void drawBlockOverlays(WorldRenderer wr, Tessellator tess,
                                          double camX, double camY, double camZ, CageTracerDebug.Frame debug) {
        if (overlayStyle == OverlayStyle.OFF || OVERLAY_BLOCKS.isEmpty()) return;
        if (overlayDepthMode == OverlayDepthMode.DEPTH) GlStateManager.enableDepth();
        else GlStateManager.disableDepth();
        float fill = overlayStrength == OverlayStrength.SOFT ? 0.06F : overlayStrength == OverlayStrength.STRONG ? 0.16F : 0.10F;
        float outline = overlayStrength == OverlayStrength.SOFT ? 0.34F : overlayStrength == OverlayStrength.STRONG ? 0.64F : 0.48F;
        float lineWidth = overlayStrength == OverlayStrength.SOFT ? 1.15F : overlayStrength == OverlayStrength.STRONG ? 1.8F : 1.35F;
        for (int pass = 0; pass < 2; pass++) {
            boolean filling = pass == 0;
            if (filling && overlayStyle == OverlayStyle.OUTLINE || !filling && overlayStyle == OverlayStyle.FILL) continue;
            if (!filling) GL11.glLineWidth(lineWidth);
            wr.begin(filling ? GL11.GL_QUADS : GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            int vertices = 0;
            for (RaySample sample : TARGETS) {
                if (!sample.overlay) continue;
                AxisAlignedBB box = sample.box;
                float r = ((sample.color >> 16) & 255) / 255F;
                float g = ((sample.color >> 8) & 255) / 255F;
                float b = (sample.color & 255) / 255F;
                if (filling) addCubeQuads(wr, box.minX - camX - 0.002, box.minY - camY - 0.002, box.minZ - camZ - 0.002,
                        box.maxX - camX + 0.002, box.maxY - camY + 0.002, box.maxZ - camZ + 0.002, r, g, b, fill);
                else addCubeLines(wr, box.minX - camX - 0.002, box.minY - camY - 0.002, box.minZ - camZ - 0.002,
                        box.maxX - camX + 0.002, box.maxY - camY + 0.002, box.maxZ - camZ + 0.002, r, g, b, outline);
                vertices += 24;
            }
            tess.draw();
            if (debug != null) { debug.draws++; debug.vertices += vertices; }
        }
    }

    private static void addCubeQuads(WorldRenderer wr,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a) {
        // DOWN
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        // UP
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        // NORTH
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        // SOUTH
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        // WEST
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        // EAST
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
    }

    private static void addCubeLines(WorldRenderer wr,
                                     double x1, double y1, double z1,
                                     double x2, double y2, double z2,
                                     float r, float g, float b, float a) {
        // Bottom
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y1, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        // Top
        wr.pos(x1, y2, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y2, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        // Verticals
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z1).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x1, y1, z2).color(r, g, b, a).endVertex(); wr.pos(x1, y2, z2).color(r, g, b, a).endVertex();
    }



    private static void renderTracerTargetNametag(Minecraft mc, RaySample sample,
                                                   double camX, double camY, double camZ, CageTracerDebug.Frame debug) {
        if (sample.name == null || sample.name.isEmpty() || mc.fontRendererObj == null) return;
        RenderManager manager = mc.getRenderManager();
        if (manager == null) return;
        FontRenderer font = mc.fontRendererObj;
        if (sample.nameWidth < 0) sample.nameWidth = font.getStringWidth(sample.name);
        Vec3 hit = sample.ray.hitPos;
        EnumFacing face = sample.ray.face;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(hit.xCoord - camX + (face == null ? 0 : face.getFrontOffsetX() * 0.03),
                    hit.yCoord - camY + 0.18 + (face == null ? 0 : face.getFrontOffsetY() * 0.03),
                    hit.zCoord - camZ + (face == null ? 0 : face.getFrontOffsetZ() * 0.03));
            GlStateManager.rotate(-manager.playerViewY, 0, 1, 0);
            GlStateManager.rotate(LabelBillboardAngles.entityLabelPitchX(manager, mc), 1, 0, 0);
            GlStateManager.scale(-0.025F, -0.025F, 0.025F);
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            font.drawStringWithShadow(sample.name, -sample.nameWidth / 2, 0, sample.color);
            if (debug != null) { debug.labels++; debug.draws++; }
        } finally {
            GlStateManager.disableTexture2D();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.popMatrix();
        }
    }

    /**
     * Use the same resolved text as the player's real nametag so TAB/TAGS formatting and disguise colors carry
     * through to the tracer line, hit marker, block outline, and tracer target label.
     */
    private static int resolveNametagColor(Minecraft mc, EntityPlayer player) {
        String formatted;
        try {
            formatted = GomodPlayerNametagLabels.resolveDisplayedLabel(player);
        } catch (Throwable ignored) {
            return 0xFFFFFF;
        }
        if (formatted == null || formatted.isEmpty()) return 0xFFFFFF;

        List<String> candidates = new ArrayList<String>(5);
        if (player == mc.thePlayer && LocalDisguise.isEnabled()) {
            addNameCandidate(candidates, LocalDisguise.getTargetName());
        }
        addNameCandidate(candidates, RemoteDisguiseManager.getDisguisedNameFor(player));
        try {
            GomodPeerDisguise.PeerName peer = GomodPeerDisguise.getPeerName(player.getGameProfile());
            if (peer != null) addNameCandidate(candidates, peer.targetName);
        } catch (Throwable ignored) {
        }
        addNameCandidate(candidates, player.getName());
        if (player.getGameProfile() != null) addNameCandidate(candidates, player.getGameProfile().getName());

        StringBuilder visible = new StringBuilder(formatted.length());
        int[] formattedPositions = new int[formatted.length()];
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            formattedPositions[visible.length()] = i;
            visible.append(c);
        }
        String visibleText = visible.toString();
        for (String candidate : candidates) {
            int formattedStart = formattedStartOfName(visibleText, formattedPositions, candidate);
            if (formattedStart >= 0) return colorAtFormattedIndex(formatted, formattedStart);
        }
        return 0xFFFFFF;
    }

    private static void addNameCandidate(List<String> candidates, String name) {
        if (name == null) return;
        String candidate = name.trim();
        if (candidate.isEmpty()) return;
        for (String existing : candidates) {
            if (existing.equalsIgnoreCase(candidate)) return;
        }
        candidates.add(candidate);
    }

    /** Returns the raw formatted-text offset for the final visible-name occurrence, or -1 when absent. */
    private static int formattedStartOfName(String visible, int[] formattedPositions, String candidate) {
        String needle = stripFormatting(candidate).trim();
        if (needle.isEmpty()) return -1;
        String lowerText = visible.toLowerCase(Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        int match = -1;
        int searchFrom = 0;
        while (searchFrom <= lowerText.length() - lowerNeedle.length()) {
            int at = lowerText.indexOf(lowerNeedle, searchFrom);
            if (at < 0) break;
            int after = at + lowerNeedle.length();
            boolean antiShufflePrefix = at >= 4 && visible.regionMatches(true, at - 4, "XXXX", 0, 4);
            boolean leftBoundary = at == 0 || !isNameChar(visible.charAt(at - 1)) || antiShufflePrefix;
            boolean rightBoundary = after == visible.length() || !isNameChar(visible.charAt(after));
            if (leftBoundary && rightBoundary) match = at;
            searchFrom = at + 1;
        }
        if (match < 0) return -1;
        return formattedPositions[match];
    }

    private static int colorAtFormattedIndex(String formatted, int formattedStart) {
        char active = 'f';
        for (int i = 0; i + 1 < formattedStart; i++) {
            if (formatted.charAt(i) != '\u00a7') continue;
            char code = Character.toLowerCase(formatted.charAt(++i));
            if (code >= '0' && code <= '9') active = code;
            else if (code >= 'a' && code <= 'f') active = code;
            else if (code == 'r') active = 'f';
        }
        int index = active >= '0' && active <= '9' ? active - '0' : 10 + active - 'a';
        return NAMETAG_COLORS[index];
    }

    private static String stripFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }
}
