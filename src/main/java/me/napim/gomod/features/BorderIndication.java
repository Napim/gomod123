package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.border.WorldBorder;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * BorderIndication feature for Blitz Survival Games deathmatch mode.
 * Renders a red overlay on blocks that are outside the world border.
 * 
 * Hypixel uses particle effects to show the border, not the vanilla WorldBorder.
 * We detect these particles and track the border bounds.
 */
public class BorderIndication {
    private static boolean enabled = false;
    private static float opacity = 0.3f; // Default opacity for red overlay (0.0-1.0)
    private static int renderDistance = 64; // How far to render the overlay in blocks
    
    // Particle-based border tracking
    private static double trackedBorderMinX = Double.MAX_VALUE;
    private static double trackedBorderMaxX = Double.MIN_VALUE;
    private static double trackedBorderMinZ = Double.MAX_VALUE;
    private static double trackedBorderMaxZ = Double.MIN_VALUE;
    private static long lastParticleTime = 0;
    private static final long PARTICLE_TIMEOUT = 5000; // 5 seconds without particles = reset
    private static boolean hasBorderData = false;
    private static long lastDebugTime = 0;
    private static double lastCheckedBorderSize = 0;

    /**
     * Loads settings from ConfigManager
     */
    public static void loadSettings() {
        enabled = ConfigManager.isBorderIndicationEnabled();
        opacity = ConfigManager.getBorderIndicationOpacity();
        renderDistance = ConfigManager.getBorderIndicationRenderDistance();
    }

    /**
     * Toggles the BorderIndication feature
     */
    public static void toggle() {
        enabled = !enabled;
        ConfigManager.setBorderIndicationEnabled(enabled);
        
        if (enabled) {
            System.out.println("[BorderIndication] Enabled - will detect Hypixel particle borders");
        } else {
            System.out.println("[BorderIndication] Disabled");
            resetBorderData();
        }
    }

    /**
     * Checks if BorderIndication is enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the opacity of the red overlay
     * @param value opacity value (0.0-1.0)
     */
    public static void setOpacity(float value) {
        opacity = Math.max(0.0f, Math.min(1.0f, value));
        ConfigManager.setBorderIndicationOpacity(opacity);
    }

    /**
     * Gets the current opacity setting
     */
    public static float getOpacity() {
        return opacity;
    }

    /**
     * Sets the render distance
     * @param distance render distance in blocks (16-128)
     */
    public static void setRenderDistance(int distance) {
        renderDistance = Math.max(16, Math.min(128, distance));
        ConfigManager.setBorderIndicationRenderDistance(renderDistance);
    }

    /**
     * Gets the current render distance
     */
    public static int getRenderDistance() {
        return renderDistance;
    }
    
    /**
     * Called when a particle is spawned. We track particles that might be border indicators.
     * Hypixel uses REDDUST and FLAME particles to show the border in Blitz Survival Games.
     */
    public static void onParticleSpawn(int particleId, double x, double y, double z) {
        if (!enabled) {
            return;
        }
        
        // Track border particles
        // Particle ID 30 is REDDUST, 26 is FLAME - these form Hypixel's deathmatch border
        if (particleId == 30 || particleId == 26) {
            boolean isNewBorder = !hasBorderData;
            updateBorderBounds(x, z);
            lastParticleTime = System.currentTimeMillis();
            hasBorderData = true;
            
            // Debug output when first detecting border
            if (isNewBorder) {
                System.out.println("[BorderIndication] Detected border particles! Type: " + particleId + " (HYPIXEL DEATHMATCH BORDER)");
                System.out.println("[BorderIndication] Initial bounds: X[" + x + "] Z[" + z + "]");
            }
        }
    }
    
    /**
     * Updates the tracked border bounds based on particle positions
     */
    private static void updateBorderBounds(double x, double z) {
        // Update min/max bounds
        if (x < trackedBorderMinX) trackedBorderMinX = x;
        if (x > trackedBorderMaxX) trackedBorderMaxX = x;
        if (z < trackedBorderMinZ) trackedBorderMinZ = z;
        if (z > trackedBorderMaxZ) trackedBorderMaxZ = z;
    }
    
    /**
     * Resets the tracked border data
     */
    public static void resetBorderData() {
        if (hasBorderData) {
            System.out.println("[BorderIndication] Resetting border data");
        }
        trackedBorderMinX = Double.MAX_VALUE;
        trackedBorderMaxX = Double.MIN_VALUE;
        trackedBorderMinZ = Double.MAX_VALUE;
        trackedBorderMaxZ = Double.MIN_VALUE;
        hasBorderData = false;
        lastParticleTime = 0;
    }

    /**
     * Renders red overlay on blocks outside the border.
     * Should be called from world rendering (e.g., RenderWorldLastEvent).
     */
    public static void render(float partialTicks) {
        if (!enabled) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        
        // Get player position early for debug output
        double playerX = mc.thePlayer.lastTickPosX + (mc.thePlayer.posX - mc.thePlayer.lastTickPosX) * partialTicks;
        double playerY = mc.thePlayer.lastTickPosY + (mc.thePlayer.posY - mc.thePlayer.lastTickPosY) * partialTicks;
        double playerZ = mc.thePlayer.lastTickPosZ + (mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ) * partialTicks;
        
        // Periodic debug output (removed - was every 3 seconds, unnecessary for production)
        long currentTime = System.currentTimeMillis();
        
        // Check if particle data has expired
        if (hasBorderData && (currentTime - lastParticleTime > PARTICLE_TIMEOUT)) {
            resetBorderData();
        }

        double borderMinX, borderMaxX, borderMinZ, borderMaxZ;
        
        // Try particle-based border first (for Hypixel)
        if (hasBorderData) {
            borderMinX = trackedBorderMinX;
            borderMaxX = trackedBorderMaxX;
            borderMinZ = trackedBorderMinZ;
            borderMaxZ = trackedBorderMaxZ;
        } else {
            // Try vanilla WorldBorder (Hypixel might use this after all!)
            WorldBorder border = mc.theWorld.getWorldBorder();
            if (border == null) {
                return;
            }

            // Get border properties
            double borderCenterX = border.getCenterX();
            double borderCenterZ = border.getCenterZ();
            double borderSize = border.getDiameter();
            double borderRadius = borderSize / 2.0;

            // Debug: Check if border is actually valid
            // Default WorldBorder is 60000000 blocks wide
            if (borderSize > 1000000) {
                // This is the default border, not a real game border
                return;
            }

            // Calculate border bounds
            borderMinX = borderCenterX - borderRadius;
            borderMaxX = borderCenterX + borderRadius;
            borderMinZ = borderCenterZ - borderRadius;
            borderMaxZ = borderCenterZ + borderRadius;
            
            // Debug output
            // System.out.println("[BorderIndication] Using vanilla WorldBorder: Size=" + borderSize + " Center=[" + borderCenterX + ", " + borderCenterZ + "]");
        }

        // Get camera position for rendering
        double viewX = mc.getRenderManager().viewerPosX;
        double viewY = mc.getRenderManager().viewerPosY;
        double viewZ = mc.getRenderManager().viewerPosZ;

        // Render blocks around the player that are outside the border
        int playerBlockX = (int) Math.floor(playerX);
        int playerBlockY = (int) Math.floor(playerY);
        int playerBlockZ = (int) Math.floor(playerZ);

        // Set up GL state ONCE for all blocks (not per-block!)
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0f, 0.0f, 0.0f, opacity);
        GL11.glLineWidth(2.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        
        // Scan blocks within render distance
        for (int x = playerBlockX - renderDistance; x <= playerBlockX + renderDistance; x++) {
            for (int z = playerBlockZ - renderDistance; z <= playerBlockZ + renderDistance; z++) {
                // Check if this block position is outside the border
                boolean outsideBorder = (x < borderMinX || x > borderMaxX || z < borderMinZ || z > borderMaxZ);
                
                if (!outsideBorder) {
                    continue;
                }

                // Check if block is close enough to player for visibility
                double distSq = (x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ);
                if (distSq > renderDistance * renderDistance) {
                    continue;
                }

                // Render blocks at player's Y level and a few blocks above/below
                for (int y = playerBlockY - 5; y <= playerBlockY + 10; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    
                    // Only render if there's a solid block at this position
                    if (mc.theWorld.isAirBlock(pos)) {
                        continue;
                    }

                    // Render wireframe box (GL state already set up)
                    drawBlockWireframe(pos, viewX, viewY, viewZ);
                }
            }
        }
        
        // Tear down GL state ONCE after all blocks
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /**
     * Draws a wireframe box around a block. 
     * Assumes GL state is already set up by the caller (no per-block push/pop).
     */
    private static void drawBlockWireframe(BlockPos pos, double viewX, double viewY, double viewZ) {
        AxisAlignedBB boundingBox = new AxisAlignedBB(
            pos.getX() - viewX,
            pos.getY() - viewY,
            pos.getZ() - viewZ,
            pos.getX() + 1 - viewX,
            pos.getY() + 1 - viewY,
            pos.getZ() + 1 - viewZ
        );
        drawSelectionBoundingBox(boundingBox);
    }
    
    /**
     * Draws a wireframe bounding box (like the selection box)
     */
    private static void drawSelectionBoundingBox(AxisAlignedBB box) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(3, DefaultVertexFormats.POSITION);
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        tessellator.draw();
        worldrenderer.begin(3, DefaultVertexFormats.POSITION);
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        tessellator.draw();
        worldrenderer.begin(1, DefaultVertexFormats.POSITION);
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        tessellator.draw();
    }
}

