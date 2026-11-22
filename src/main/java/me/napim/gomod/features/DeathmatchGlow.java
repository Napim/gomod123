package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DeathmatchGlow feature.
 * Renders a pulsing red glow on ground blocks along the perimeter of a
 * map-specific deathmatch boundary zone.
 *
 * The glow uses the same entity-outline FBO + Gaussian-blur shader pipeline
 * that /glowplayer uses — geometry is drawn into the outline framebuffer so
 * the shader spreads a halo onto the surrounding/floor area, exactly like the
 * "ground glow" artefact that was seen under /glowplayer feet.
 *
 * Usage: /glowdm <map>   — activate for map
 *        /glowdm off     — deactivate
 *        /glowdm list    — list registered maps
 */
public final class DeathmatchGlow {

    // ── Map definition ───────────────────────────────────────────────────────

    public static final class MapBounds {
        public final String displayName;
        public final double centerX;
        public final double centerZ;
        /** Half-width (and half-depth) of the square border zone in blocks. */
        public final int radius;

        public MapBounds(String displayName, double centerX, double centerZ, int radius) {
            this.displayName = displayName;
            this.centerX     = centerX;
            this.centerZ     = centerZ;
            this.radius      = radius;
        }

        public double getMinX() { return centerX - radius; }
        public double getMaxX() { return centerX + radius; }
        public double getMinZ() { return centerZ - radius; }
        public double getMaxZ() { return centerZ + radius; }
    }

    // ── Map registry ──────────────────────────────────────────────────────────

    private static File deathmatchMapsFile() {
        return new File(GomodPaths.getGomod123Dir(), "deathmatch_maps.json");
    }

    private static final Map<String, MapBounds> REGISTRY = new HashMap<>();

    /** Loads maps from JSON, then seeds any missing built-in defaults. */
    public static void loadConfig() {
        // Built-in defaults (only used when not present in JSON)
        Map<String, MapBounds> defaults = new HashMap<>();
        defaults.put("gulch", new MapBounds("Gulch", -202, -2, 16));

        try {
            File file = deathmatchMapsFile();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    com.google.gson.JsonObject root =
                            new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                    for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                        try {
                            com.google.gson.JsonObject o = entry.getValue().getAsJsonObject();
                            String displayName = o.has("name") ? o.get("name").getAsString() : entry.getKey();
                            double cx     = o.get("centerX").getAsDouble();
                            double cz     = o.get("centerZ").getAsDouble();
                            int    radius = o.get("radius").getAsInt();
                            REGISTRY.put(entry.getKey().toLowerCase(), new MapBounds(displayName, cx, cz, radius));
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DeathmatchGlow] Failed to load config: " + e.getMessage());
        }

        // Seed defaults that are not yet in the registry
        for (Map.Entry<String, MapBounds> def : defaults.entrySet()) {
            REGISTRY.putIfAbsent(def.getKey(), def.getValue());
        }
        // Persist so the file exists with at least the defaults
        saveConfig();
    }

    private static void saveConfig() {
        try {
            File dir = GomodPaths.getGomod123Dir();
            if (!dir.exists()) dir.mkdirs();
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            for (Map.Entry<String, MapBounds> entry : REGISTRY.entrySet()) {
                MapBounds b = entry.getValue();
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("name",    b.displayName);
                o.addProperty("centerX", b.centerX);
                o.addProperty("centerZ", b.centerZ);
                o.addProperty("radius",  b.radius);
                root.add(entry.getKey(), o);
            }
            try (FileWriter writer = new FileWriter(deathmatchMapsFile())) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (Exception e) {
            System.err.println("[DeathmatchGlow] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Adds or updates a map entry and persists to JSON.
     * @param key         map key (case-insensitive)
     * @param displayName human-readable name
     * @param centerX     X coordinate of center
     * @param centerZ     Z coordinate of center
     * @param radius      half-size of the square zone in blocks
     */
    public static void editMap(String key, String displayName, double centerX, double centerZ, int radius) {
        MapBounds bounds = new MapBounds(displayName, centerX, centerZ, radius);
        REGISTRY.put(key.toLowerCase(), bounds);
        saveConfig();
        // If this map is currently active, hot-swap the reference so changes show immediately
        if (key.toLowerCase().equals(activeKey)) {
            activeMap = bounds;
        }
        System.out.println("[DeathmatchGlow] Saved map '" + key + "': center (" + (int)centerX + ", " + (int)centerZ + "), radius " + radius);
    }

    /** Register a new map in memory only (used for runtime-only entries). */
    public static void register(String key, MapBounds bounds) {
        REGISTRY.put(key.toLowerCase(), bounds);
    }

    public static Set<String> getMapNames() {
        return REGISTRY.keySet();
    }

    /** Returns the MapBounds for the given key, or null if not found. */
    public static MapBounds getMap(String key) {
        return REGISTRY.get(key.toLowerCase());
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private static boolean   enabled    = false;
    private static MapBounds activeMap  = null;
    private static String    activeKey  = null; // tracked so editMap can refresh activeMap

    private DeathmatchGlow() {}

    /**
     * Activates the glow for the named map.
     * @param mapName case-insensitive map key
     * @return true if the map was found and activated, false if unknown
     */
    public static boolean activate(String mapName) {
        MapBounds bounds = REGISTRY.get(mapName.toLowerCase());
        if (bounds == null) return false;
        activeKey = mapName.toLowerCase();
        activeMap = bounds;
        enabled   = true;
        System.out.println("[DeathmatchGlow] Activated for map: " + bounds.displayName
                + " (center " + (int)bounds.centerX + ", " + (int)bounds.centerZ
                + ", radius " + bounds.radius + ")");
        return true;
    }

    public static void deactivate() {
        enabled   = false;
        activeMap = null;
        activeKey = null;
        System.out.println("[DeathmatchGlow] Deactivated.");
    }

    public static boolean isEnabled()      { return enabled; }
    public static MapBounds getActiveMap() { return activeMap; }

    // ── Rendering constants ───────────────────────────────────────────────────

    /** Maximum render distance from the player (blocks). */
    private static final int RENDER_DISTANCE = 80;
    /** How many blocks above/below player Y level to search for surface. */
    private static final int Y_SCAN_UP   = 10;
    private static final int Y_SCAN_DOWN = 15;
    /**
     * Width of the outer-edge strip drawn into the FBO for each wall.
     * Thin (0.08 blocks) so the Gaussian blur sees a single line per wall,
     * not the full block top-face → no inter-block seam glow.
     */
    private static final double STRIP = 0.08;
    /**
     * How far above the block surface the strip quad sits.
     * Helps with depth precision at oblique angles.
     */
    private static final double ABOVE = 0.05;

    // ── FBO geometry render ───────────────────────────────────────────────────

    /**
     * Draws thin outer-edge strips for the 4 border walls into the outline FBO.
     *
     * Radius semantics: {@code radius} is the TOTAL side length of the zone.
     *   radius=1  →  1×1  block zone  (perimeter around 1 block)
     *   radius=10 →  10×10 block zone
     *
     * The strip outer edges sit at the mathematical boundary of the zone (the
     * outer face of the first/last block column or row), so no extra blocks leak
     * outside the intended boundary.
     *
     * Depth function is overridden to GL_ALWAYS for DM quads only: horizontal
     * ground quads are extremely prone to depth precision failures (black glow in
     * first person) when GL_LEQUAL is used with a blitted depth buffer.  Since
     * ground-level quads pose no practical ESP concern, GL_ALWAYS is safe here.
     * GL_LEQUAL is restored before returning.
     */
    public static void renderBorderIntoFBO(double viewX, double viewY, double viewZ,
                                           float partialTicks, Minecraft mc) {
        if (!enabled || activeMap == null) return;
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;

        // ── Pulse: red channel 0.55→1.0 (period ~2 s) ───────────────────────
        long  now       = System.currentTimeMillis();
        float brightAmt = (float)(Math.sin(now / 1000.0 * Math.PI) * 0.5 + 0.5);
        float red       = 0.55f + brightAmt * 0.45f;
        GlStateManager.color(red, 0.0f, 0.0f, 1.0f);

        // GL_ALWAYS: depth testing is bypassed entirely for DM quads.
        // Polygon offset is already disabled by the caller, so no near-plane clipping.
        GlStateManager.depthFunc(519); // GL_ALWAYS

        // ── Interpolated player position ─────────────────────────────────────
        double playerX = mc.thePlayer.lastTickPosX + (mc.thePlayer.posX - mc.thePlayer.lastTickPosX) * partialTicks;
        double playerY = mc.thePlayer.lastTickPosY + (mc.thePlayer.posY - mc.thePlayer.lastTickPosY) * partialTicks;
        double playerZ = mc.thePlayer.lastTickPosZ + (mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ) * partialTicks;
        int pBY = (int) Math.floor(playerY);

        // ── Border column bounds ─────────────────────────────────────────────
        // radius = TOTAL side length of the zone.
        //   bMinX = centerX_int - (radius / 2)          (integer floor division)
        //   bMaxX = centerX_int + ((radius - 1) / 2)    (integer floor division)
        // Examples (center=0):
        //   radius=1 → bMin=0,  bMax=0  → 1 column  ✓
        //   radius=2 → bMin=-1, bMax=0  → 2 columns ✓
        //   radius=10→ bMin=-5, bMax=4  → 10 columns ✓
        int cxInt = (int) Math.floor(activeMap.centerX);
        int czInt = (int) Math.floor(activeMap.centerZ);
        int r = activeMap.radius;
        int bMinX = cxInt - (r / 2);
        int bMaxX = cxInt + ((r - 1) / 2);
        int bMinZ = czInt - (r / 2);
        int bMaxZ = czInt + ((r - 1) / 2);

        GlStateManager.disableTexture2D();
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);

        Tessellator   tess = Tessellator.getInstance();
        WorldRenderer wr   = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);

        // ── NORTH wall (bz = bMinZ): strip on the north outer edge ───────────
        for (int bx = bMinX; bx <= bMaxX; bx++) {
            double dx = bx + 0.5 - playerX, dz = bMinZ + 0.5 - playerZ;
            if (dx*dx + dz*dz > (double)(RENDER_DISTANCE * RENDER_DISTANCE)) continue;
            int sy = findSurface(mc, bx, bMinZ, pBY);
            if (sy == -1) continue;
            double rx  = bx    - viewX;
            double ry  = sy + 1.0 + ABOVE - viewY;
            double rzN = bMinZ - viewZ;           // outer (north) edge of zone
            wr.pos(rx,       ry, rzN        ).endVertex();
            wr.pos(rx + 1.0, ry, rzN        ).endVertex();
            wr.pos(rx + 1.0, ry, rzN + STRIP).endVertex();
            wr.pos(rx,       ry, rzN + STRIP).endVertex();
        }

        // ── SOUTH wall (bz = bMaxZ): strip on the south outer edge ───────────
        for (int bx = bMinX; bx <= bMaxX; bx++) {
            double dx = bx + 0.5 - playerX, dz = bMaxZ + 0.5 - playerZ;
            if (dx*dx + dz*dz > (double)(RENDER_DISTANCE * RENDER_DISTANCE)) continue;
            int sy = findSurface(mc, bx, bMaxZ, pBY);
            if (sy == -1) continue;
            double rx  = bx         - viewX;
            double ry  = sy + 1.0 + ABOVE - viewY;
            double rzS = (bMaxZ + 1) - viewZ;    // outer (south) edge of zone
            wr.pos(rx,       ry, rzS - STRIP).endVertex();
            wr.pos(rx + 1.0, ry, rzS - STRIP).endVertex();
            wr.pos(rx + 1.0, ry, rzS        ).endVertex();
            wr.pos(rx,       ry, rzS        ).endVertex();
        }

        // ── WEST wall (bx = bMinX): strip on the west outer edge ─────────────
        for (int bz = bMinZ; bz <= bMaxZ; bz++) {
            double dx = bMinX + 0.5 - playerX, dz = bz + 0.5 - playerZ;
            if (dx*dx + dz*dz > (double)(RENDER_DISTANCE * RENDER_DISTANCE)) continue;
            int sy = findSurface(mc, bMinX, bz, pBY);
            if (sy == -1) continue;
            double rxW = bMinX - viewX;           // outer (west) edge of zone
            double ry  = sy + 1.0 + ABOVE - viewY;
            double rz  = bz    - viewZ;
            wr.pos(rxW,         ry, rz      ).endVertex();
            wr.pos(rxW + STRIP, ry, rz      ).endVertex();
            wr.pos(rxW + STRIP, ry, rz + 1.0).endVertex();
            wr.pos(rxW,         ry, rz + 1.0).endVertex();
        }

        // ── EAST wall (bx = bMaxX): strip on the east outer edge ─────────────
        for (int bz = bMinZ; bz <= bMaxZ; bz++) {
            double dx = bMaxX + 0.5 - playerX, dz = bz + 0.5 - playerZ;
            if (dx*dx + dz*dz > (double)(RENDER_DISTANCE * RENDER_DISTANCE)) continue;
            int sy = findSurface(mc, bMaxX, bz, pBY);
            if (sy == -1) continue;
            double rxE = (bMaxX + 1) - viewX;    // outer (east) edge of zone
            double ry  = sy + 1.0 + ABOVE - viewY;
            double rz  = bz          - viewZ;
            wr.pos(rxE - STRIP, ry, rz      ).endVertex();
            wr.pos(rxE,         ry, rz      ).endVertex();
            wr.pos(rxE,         ry, rz + 1.0).endVertex();
            wr.pos(rxE - STRIP, ry, rz + 1.0).endVertex();
        }

        tess.draw();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CULL_FACE);

        // Restore GL_LEQUAL for any subsequent rendering in this pass
        GlStateManager.depthFunc(515);
    }

    /** Finds the top non-air block Y near {@code pBY}. Returns -1 if none found. */
    private static int findSurface(Minecraft mc, int bx, int bz, int pBY) {
        for (int by = pBY + Y_SCAN_UP; by >= pBY - Y_SCAN_DOWN; by--) {
            if (!mc.theWorld.isAirBlock(new BlockPos(bx, by, bz))) return by;
        }
        return -1;
    }
}
