package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Vec3;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.*;

/**
 * Renders a fading line trail on the ground behind glowing players.
 *
 * Static chest routes: line strip on block tops (depth), multiple colors/paths.
 */
public class GlowTrailRenderer {

    private static long TRAIL_LIFETIME_MS = 7_000;
    private static final double LINE_WIDTH = 0.15;
    private static final double MIN_DIST_SQ = 0.07 * 0.07;
    /** If the player jumps farther than this between samples, treat as teleport — no trail segment. */
    private static final double TELEPORT_BREAK_DIST_SQ = 16.0 * 16.0;
    private static final int MAX_POINTS_PER_PLAYER = 1200;
    private static final float MAX_ALPHA = 0.70f;
    private static final double Y_OFFSET = 0.018;

    private static final Map<String, Deque<TrailPoint>> playerTrails = new LinkedHashMap<>();
    private static final Map<String, double[]> lastPositions = new HashMap<>();

    /** Multiple named chest-route polylines (world space, already smoothed). */
    private static final List<StaticChestRoute> staticChestRoutes = new ArrayList<>();

    /** Fewer vertices along the route (line strip, not a heavy tube mesh). */
    /** Fewer vertices when routes are long (CPU + GPU). */
    private static final double CHEST_RESAMPLE_STEP = 0.62;
    private static final double PULSE_SPEED_BPS = 0.42;
    private static final double PULSE_WIDTH = 2.8;
    private static final float LINE_ALPHA_BASE = 0.78f;
    /** Extra lift after snap (snap already adds 3/16); keep minimal z-fight fix only. */
    private static final double PATH_Y_BIAS = 0.002;

    public static final class RouteSpec {
        public final String pathId;
        public final int colorRgb;
        public final List<Vec3> worldPoints;
        /**
         * When &gt;= 0, all trail points use this spawn time so repeated updates (e.g. CE drag) do not
         * reset the pulse animation every frame.
         */
        public final long stableSpawnTimeMs;

        public RouteSpec(String pathId, int colorRgb, List<Vec3> worldPoints) {
            this(pathId, colorRgb, worldPoints, -1L);
        }

        public RouteSpec(String pathId, int colorRgb, List<Vec3> worldPoints, long stableSpawnTimeMs) {
            this.pathId = pathId;
            this.colorRgb = colorRgb;
            this.worldPoints = worldPoints;
            this.stableSpawnTimeMs = stableSpawnTimeMs;
        }
    }

    private static final class TrailPoint {
        final double x, y, z;
        final long spawnTime;
        final int colorRgb;

        TrailPoint(double x, double y, double z, long t, int c) {
            this.x = x;
            this.y = y;
            this.z = z;
            spawnTime = t;
            colorRgb = c;
        }
    }

    private static final class StaticChestRoute {
        final String id;
        final List<TrailPoint> polyline;
        final int colorRgb;

        StaticChestRoute(String id, List<TrailPoint> polyline, int colorRgb) {
            this.id = id;
            this.polyline = polyline;
            this.colorRgb = colorRgb;
        }
    }

    public static void setLifetimeSeconds(int seconds) {
        TRAIL_LIFETIME_MS = Math.max(1, Math.min(120, seconds)) * 1000L;
    }

    public static int getLifetimeSeconds() {
        return (int) (TRAIL_LIFETIME_MS / 1000L);
    }

    public static void recordPosition(String playerName, double x, double y, double z, int colorRgb) {
        if (playerName == null) {
            return;
        }
        playerName = playerName.trim().toLowerCase(java.util.Locale.ROOT);
        double[] last = lastPositions.get(playerName);
        if (last != null) {
            double dx = x - last[0], dy = y - last[1], dz = z - last[2];
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < MIN_DIST_SQ) {
                return;
            }
            if (distSq > TELEPORT_BREAK_DIST_SQ) {
                Deque<TrailPoint> t = playerTrails.get(playerName);
                if (t != null) {
                    t.clear();
                }
                lastPositions.put(playerName, new double[]{x, y, z});
                return;
            }
        }
        lastPositions.put(playerName, new double[]{x, y, z});
        Deque<TrailPoint> trail = playerTrails.get(playerName);
        if (trail == null) {
            trail = new ArrayDeque<>();
            playerTrails.put(playerName, trail);
        }
        trail.addLast(new TrailPoint(x, y, z, System.currentTimeMillis(), colorRgb));
        while (trail.size() > MAX_POINTS_PER_PLAYER) {
            trail.removeFirst();
        }
    }

    public static boolean hasTrailPoints() {
        if (!staticChestRoutes.isEmpty()) {
            for (StaticChestRoute r : staticChestRoutes) {
                if (r.polyline.size() >= 2) {
                    return true;
                }
            }
        }
        for (Deque<TrailPoint> d : playerTrails.values()) {
            if (d.size() >= 2) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        playerTrails.clear();
        lastPositions.clear();
    }

    /**
     * Stops drawing the glow trail for one player immediately (used when /glowplayer or /glowplayer2
     * removes them — otherwise {@link #cleanupAndRender} keeps rendering until the trail expires.
     */
    public static void removeTrailForPlayer(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        String key = playerName.trim().toLowerCase(java.util.Locale.ROOT);
        playerTrails.remove(key);
        lastPositions.remove(key);
    }

    /** Replaces every static chest route (multiple paths / colors). */
    public static void setStaticChestRoutes(List<RouteSpec> routes) {
        staticChestRoutes.clear();
        if (routes == null) {
            return;
        }
        long tDefault = System.currentTimeMillis();
        for (RouteSpec spec : routes) {
            if (spec == null || spec.worldPoints == null || spec.worldPoints.size() < 2) {
                continue;
            }
            long t = spec.stableSpawnTimeMs >= 0L ? spec.stableSpawnTimeMs : tDefault;
            String id = spec.pathId != null ? spec.pathId : "route";
            List<TrailPoint> keyPts = new ArrayList<>();
            for (Vec3 v : spec.worldPoints) {
                if (v == null) {
                    continue;
                }
                keyPts.add(new TrailPoint(v.xCoord, v.yCoord, v.zCoord, t, spec.colorRgb));
            }
            if (keyPts.size() < 2) {
                continue;
            }
            // Resample once when the route is set — not every render frame.
            List<TrailPoint> dense = resamplePolyline3d(keyPts.toArray(new TrailPoint[0]), CHEST_RESAMPLE_STEP, t);
            if (dense.size() >= 2) {
                staticChestRoutes.add(new StaticChestRoute(id, dense, spec.colorRgb));
            }
        }
    }

    /** @deprecated use {@link #setStaticChestRoutes(List)} */
    @Deprecated
    public static void setStaticChestRouteFromVec3(List<Vec3> worldPoints, int colorRgb) {
        List<RouteSpec> one = new ArrayList<>();
        one.add(new RouteSpec("main", colorRgb, worldPoints));
        setStaticChestRoutes(one);
    }

    public static void clearStaticChestRoute() {
        staticChestRoutes.clear();
    }

    public static boolean hasStaticChestRoute() {
        for (StaticChestRoute r : staticChestRoutes) {
            if (r.polyline.size() >= 2) {
                return true;
            }
        }
        return false;
    }

    /** Uniform arc-length resample in 3D — no ground snap (stays under bridges). */
    private static List<TrailPoint> resamplePolyline3d(TrailPoint[] src, double step, long t) {
        List<TrailPoint> out = new ArrayList<>();
        if (src.length < 2) {
            return out;
        }
        int color = src[0].colorRgb;
        for (int i = 0; i < src.length - 1; i++) {
            TrailPoint a = src[i];
            TrailPoint b = src[i + 1];
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int nSteps = Math.max(1, (int) Math.ceil(len / step));
            for (int s = 0; s <= nSteps; s++) {
                if (i > 0 && s == 0) {
                    continue;
                }
                double f = s / (double) nSteps;
                double x = a.x + dx * f;
                double y = a.y + dy * f;
                double z = a.z + dz * f;
                out.add(new TrailPoint(x, y, z, t, color));
            }
        }
        return out;
    }

    /** Cheap line strip (was a heavy triangle tube — major FPS win). */
    private static void renderChestLineStrip(Tessellator tess, WorldRenderer wr, List<TrailPoint> samples,
                                             double camX, double camY, double camZ, long now, int colorRgb) {
        int n = samples.size();
        if (n < 2) {
            return;
        }
        double[] cum = new double[n];
        cum[0] = 0;
        double total = 0;
        for (int i = 1; i < n; i++) {
            TrailPoint p = samples.get(i);
            TrailPoint q = samples.get(i - 1);
            double dx = p.x - q.x;
            double dy = p.y - q.y;
            double dz = p.z - q.z;
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
            cum[i] = total;
        }
        if (total < 1e-5) {
            total = 1e-5;
        }
        double pulseHead = (now / 1000.0) * PULSE_SPEED_BPS % total;
        float cr = ((colorRgb >> 16) & 0xFF) / 255.0f;
        float cg = ((colorRgb >> 8) & 0xFF) / 255.0f;
        float cb = (colorRgb & 0xFF) / 255.0f;
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) {
            TrailPoint p = samples.get(i);
            float pulse = (float) Math.exp(-Math.pow(Math.abs(cum[i] - pulseHead) / PULSE_WIDTH, 2));
            float a = Math.min(0.95f, LINE_ALPHA_BASE + 0.22f * pulse);
            wr.pos(p.x - camX, p.y - camY + PATH_Y_BIAS, p.z - camZ).color(cr, cg, cb, a).endVertex();
        }
        tess.draw();
    }

    public static void cleanupAndRender(float partialTicks) {
        if (GomodChestWorldOverlayFrame.isTrailAlreadyDrawnThisFrame()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Deque<TrailPoint>>> it = playerTrails.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<TrailPoint>> entry = it.next();
            Deque<TrailPoint> trail = entry.getValue();
            while (!trail.isEmpty() && now - trail.peekFirst().spawnTime > TRAIL_LIFETIME_MS) {
                trail.removeFirst();
            }
            // Keep 1-point trails across frames so the next movement can form a segment.
            // Old logic removed entries whenever size < 2, which wiped the first sample
            // every frame after recordPosition → /glowplayer never showed a trail.
            if (trail.isEmpty()) {
                it.remove();
                lastPositions.remove(entry.getKey());
            }
        }

        if (!hasTrailPoints()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderViewEntity() == null) {
            return;
        }

        net.minecraft.entity.Entity cam = mc.getRenderViewEntity();
        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableDepth();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003f);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        double halfW = LINE_WIDTH / 2.0;

        // ── Player glow trails: additive, ignore depth write (legacy look) ──
        GlStateManager.depthMask(false);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        for (Deque<TrailPoint> trail : playerTrails.values()) {
            if (trail.size() < 2) {
                continue;
            }
            TrailPoint[] pts = trail.toArray(new TrailPoint[0]);
            int n = pts.length;
            double[] lx = new double[n], ly = new double[n], lz = new double[n];
            double[] rx = new double[n], ry = new double[n], rz = new double[n];

            for (int i = 0; i < n; i++) {
                double px, pz;
                if (i == 0) {
                    double dx = pts[1].x - pts[0].x, dz = pts[1].z - pts[0].z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len < 1e-6) {
                        px = halfW;
                        pz = 0;
                    } else {
                        px = -dz / len * halfW;
                        pz = dx / len * halfW;
                    }
                } else if (i == n - 1) {
                    double dx = pts[i].x - pts[i - 1].x, dz = pts[i].z - pts[i - 1].z;
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len < 1e-6) {
                        px = halfW;
                        pz = 0;
                    } else {
                        px = -dz / len * halfW;
                        pz = dx / len * halfW;
                    }
                } else {
                    double dx1 = pts[i].x - pts[i - 1].x, dz1 = pts[i].z - pts[i - 1].z;
                    double len1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
                    double dx2 = pts[i + 1].x - pts[i].x, dz2 = pts[i + 1].z - pts[i].z;
                    double len2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
                    if (len1 < 1e-6 || len2 < 1e-6) {
                        double adx = len1 > 1e-6 ? dx1 : dx2;
                        double adz = len1 > 1e-6 ? dz1 : dz2;
                        double alen = Math.sqrt(adx * adx + adz * adz);
                        px = -adz / alen * halfW;
                        pz = adx / alen * halfW;
                    } else {
                        double p1x = -dz1 / len1, p1z = dx1 / len1;
                        double p2x = -dz2 / len2, p2z = dx2 / len2;
                        double mx = p1x + p2x, mz = p1z + p2z;
                        double mlen = Math.sqrt(mx * mx + mz * mz);
                        if (mlen < 0.01) {
                            px = p1x * halfW;
                            pz = p1z * halfW;
                        } else {
                            mx /= mlen;
                            mz /= mlen;
                            double dot = mx * p1x + mz * p1z;
                            double scale = Math.abs(dot) > 0.07 ? halfW / dot : halfW * 4.0 * Math.signum(dot);
                            scale = Math.max(-halfW * 4, Math.min(halfW * 4, scale));
                            px = mx * scale;
                            pz = mz * scale;
                        }
                    }
                }
                double cx = pts[i].x - camX;
                double cy = pts[i].y - camY + Y_OFFSET;
                double cz = pts[i].z - camZ;
                lx[i] = cx + px;
                ly[i] = cy;
                lz[i] = cz + pz;
                rx[i] = cx - px;
                ry[i] = cy;
                rz[i] = cz - pz;
            }

            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < n - 1; i++) {
                TrailPoint a = pts[i], b = pts[i + 1];
                float ageA = Math.min(1.0f, (float) (now - a.spawnTime) / (float) TRAIL_LIFETIME_MS);
                float ageB = Math.min(1.0f, (float) (now - b.spawnTime) / (float) TRAIL_LIFETIME_MS);
                float alphaA = (1.0f - ageA) * MAX_ALPHA;
                float alphaB = (1.0f - ageB) * MAX_ALPHA;
                if (alphaA < 0.003f && alphaB < 0.003f) {
                    continue;
                }
                float ra = ((a.colorRgb >> 16) & 0xFF) / 255.0f, ga = ((a.colorRgb >> 8) & 0xFF) / 255.0f, ba = (a.colorRgb & 0xFF) / 255.0f;
                float rb = ((b.colorRgb >> 16) & 0xFF) / 255.0f, gb = ((b.colorRgb >> 8) & 0xFF) / 255.0f, bb = (b.colorRgb & 0xFF) / 255.0f;
                wr.pos(rx[i], ry[i], rz[i]).color(ra, ga, ba, alphaA).endVertex();
                wr.pos(lx[i], ly[i], lz[i]).color(ra, ga, ba, alphaA).endVertex();
                wr.pos(lx[i + 1], ly[i + 1], lz[i + 1]).color(rb, gb, bb, alphaB).endVertex();
                wr.pos(rx[i + 1], ry[i + 1], rz[i + 1]).color(rb, gb, bb, alphaB).endVertex();
            }
            tess.draw();
        }

        // ── Static chest routes: line strip + depth (cheap vs old tube mesh) ──
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glLineWidth(8.0F);

        for (StaticChestRoute route : staticChestRoutes) {
            if (route.polyline.size() < 2) {
                continue;
            }
            renderChestLineStrip(tess, wr, route.polyline, camX, camY, camZ, now, route.colorRgb);
        }

        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);

        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.popMatrix();
        GomodChestWorldOverlayFrame.markTrailDrawnThisFrame();
    }
}
