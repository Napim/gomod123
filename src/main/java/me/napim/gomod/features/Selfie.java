package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Selfie feature - Freezes the camera at the current position/rotation
 * while allowing the player to move away from it.
 * Like a free camera or photo mode.
 */
public class Selfie {
    private static boolean enabled = false;
    
    // Stored camera position and rotation when selfie mode is activated
    private static double cameraX = 0.0;
    private static double cameraY = 0.0;
    private static double cameraZ = 0.0;
    // Base Y (entity.posY at capture) and captured eye height to neutralize jump/sneak wobble
    private static double cameraBaseY = 0.0;
    private static float cameraEyeHeight = 0.0f;
    private static float cameraPitch = 0.0f;
    private static float cameraYaw = 0.0f;
    private static float cameraPrevPitch = 0.0f;
    private static float cameraPrevYaw = 0.0f;
    /** World/player identities captured with the anchor; Hypixel proxy transfers replace one or both. */
    private static WorldClient capturedWorld;
    private static EntityPlayerSP capturedPlayer;
    /** Number of render frames reserved for a server teleport handoff. */
    private static int cameraHandoffFrames;
    /** Extra frames used to recapture while a replacement world/player settles. */
    private static int worldTransferRefreshFrames;
    private static boolean cameraFrameSuppressed;
    
    // Store original third person view setting to restore later
    private static int originalThirdPersonView = 0;
    
    /**
     * Checks if Selfie mode is enabled.
     * @return true if Selfie mode is enabled, false otherwise
     */
    public static boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Sets whether Selfie mode is enabled and notifies the player.
     * When enabled, captures the current camera position and rotation.
     * @param value true to enable Selfie mode, false to disable
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setSelfieEnabled(value);
        
        if (enabled) {
            // Capture current camera position and rotation
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.thePlayer != null && mc.theWorld != null) {
                    /*
                     * Selfie is anchored to the local player.  The render-view
                     * entity can briefly still be the player from the previous
                     * world while Minecraft is handling a world transfer, so
                     * using it here would freeze the camera at an old-world
                     * position.
                     */
                    Entity viewEntity = getCurrentWorldEntity(mc, mc.getRenderViewEntity());
                    if (viewEntity == null) {
                        enabled = false;
                        return;
                    }
                    
                    // Store position
                    cameraX = viewEntity.posX;
                    cameraBaseY = viewEntity.posY;
                    cameraEyeHeight = viewEntity.getEyeHeight();
                    cameraY = cameraBaseY + cameraEyeHeight;
                    cameraZ = viewEntity.posZ;
                    
                    // Store rotation
                    cameraPitch = viewEntity.rotationPitch;
                    cameraYaw = viewEntity.rotationYaw;
                    cameraPrevPitch = viewEntity.prevRotationPitch;
                    cameraPrevYaw = viewEntity.prevRotationYaw;
                    capturedWorld = mc.theWorld;
                    capturedPlayer = mc.thePlayer;
                    cameraHandoffFrames = 0;
                    worldTransferRefreshFrames = 0;
                    cameraFrameSuppressed = false;
                    
                    // Store current third person view and force to third person (back view)
                    originalThirdPersonView = mc.gameSettings.thirdPersonView;
                    if (mc.gameSettings.thirdPersonView == 0) {
                        mc.gameSettings.thirdPersonView = 1; // Back view
                    }
                    
                    // Selfie mode enabled - camera frozen
                } else {
                    enabled = false;
                    // Failed to enable selfie mode
                }
            } catch (Exception e) {
                enabled = false;
                // Error enabling selfie mode
            }
        } else {
            // Restore original third person view setting
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    mc.gameSettings.thirdPersonView = originalThirdPersonView;
                }
            } catch (Exception e) {
                // Ignore errors when restoring view
            }
            
            // Selfie mode disabled - camera returned to normal
            capturedWorld = null;
            capturedPlayer = null;
            cameraHandoffFrames = 0;
            worldTransferRefreshFrames = 0;
            cameraFrameSuppressed = false;
        }
    }
    
    /**
     * Gets the stored camera X position.
     * @return camera X coordinate
     */
    public static double getCameraX() {
        return cameraX;
    }
    
    /**
     * Gets the stored camera Y position.
     * @return camera Y coordinate
     */
    public static double getCameraY() {
        return cameraY;
    }
    
    /**
     * Gets the stored base Y (entity.posY at capture time).
     */
    public static double getCameraBaseY() {
        return cameraBaseY;
    }
    
    /**
     * Gets the stored eye height at capture time.
     */
    public static float getCameraEyeHeight() {
        return cameraEyeHeight;
    }
    
    /**
     * Gets the stored camera Z position.
     * @return camera Z coordinate
     */
    public static double getCameraZ() {
        return cameraZ;
    }
    
    /**
     * Gets the stored camera pitch (vertical rotation).
     * @return camera pitch in degrees
     */
    public static float getCameraPitch() {
        return cameraPitch;
    }
    
    /**
     * Gets the stored camera yaw (horizontal rotation).
     * @return camera yaw in degrees
     */
    public static float getCameraYaw() {
        return cameraYaw;
    }
    
    /**
     * Gets the stored previous camera pitch for smooth interpolation.
     * @return previous camera pitch in degrees
     */
    public static float getCameraPrevPitch() {
        return cameraPrevPitch;
    }
    
    /**
     * Gets the stored previous camera yaw for smooth interpolation.
     * @return previous camera yaw in degrees
     */
    public static float getCameraPrevYaw() {
        return cameraPrevYaw;
    }

    /** Recaptures the frozen anchor after a server-authoritative teleport or position correction. */
    public static void onPlayerTeleported(Entity viewEntity) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }

        /*
         * Prefer the current local player over the supplied render entity.
         * During a backend/world transfer getRenderViewEntity() may still
         * reference the old player for one frame; accepting that entity would
         * immediately re-freeze the camera at the old location and mark the
         * transfer as handled.
         */
        Entity captureEntity = getCurrentWorldEntity(mc, viewEntity);
        if (captureEntity == null) {
            return;
        }

        // A large correction/world transfer can leave lastTickPos* pointing at
        // the old location for a few frames. Do not render that mixed state.
        boolean anchorJumped = capturedWorld != null
                && (capturedWorld != mc.theWorld || capturedPlayer != mc.thePlayer
                || squaredDistance(cameraX, cameraBaseY, cameraZ,
                                   captureEntity.posX, captureEntity.posY, captureEntity.posZ) > 1.0D);
        captureCameraAnchor(mc, captureEntity);
        if (anchorJumped) {
            cameraHandoffFrames = Math.max(cameraHandoffFrames, 2);
        }
    }

    /**
     * Hypixel lobby NPCs transfer the client to another backend and replace the world/player instead of sending the
     * ordinary in-world position packet. Refresh for a few frames while that identity changes.
     */
    public static void refreshAfterWorldTransfer() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        boolean identityChanged = mc.theWorld != capturedWorld || mc.thePlayer != capturedPlayer;
        if (identityChanged) {
            // The replacement player can exist before its final spawn/teleport
            // coordinates arrive. Keep recapturing for a few render frames.
            worldTransferRefreshFrames = Math.max(worldTransferRefreshFrames, 5);
        }
        if (identityChanged || worldTransferRefreshFrames > 0) {
            // Always recapture from the new local player.  The render-view
            // entity can be stale while Minecraft finishes replacing worlds.
            Entity captureEntity = getCurrentWorldEntity(mc, mc.thePlayer);
            if (captureEntity != null) {
                captureCameraAnchor(mc, captureEntity);
                if (worldTransferRefreshFrames > 0) {
                    worldTransferRefreshFrames--;
                }
            }
        }
    }

    /**
     * Clears only the location/world anchor when a connection is closed.
     * Selfie itself stays enabled so the next login can recapture at its new
     * spawn instead of reusing the disconnected server's coordinates.
     */
    public static void resetCameraForReconnect() {
        if (!enabled) {
            return;
        }
        capturedWorld = null;
        capturedPlayer = null;
        cameraHandoffFrames = 0;
        worldTransferRefreshFrames = Math.max(worldTransferRefreshFrames, 5);
        cameraFrameSuppressed = false;
    }

    /** Starts the render handoff after a teleport. Called once per render frame. */
    public static void beginCameraFrame() {
        cameraFrameSuppressed = enabled
                && (cameraHandoffFrames > 0 || worldTransferRefreshFrames > 0);
        if (cameraHandoffFrames > 0) {
            cameraHandoffFrames--;
        }
    }

    /** Whether selfie camera transforms should be skipped for the current frame. */
    public static boolean isCameraFrameSuppressed() {
        return cameraFrameSuppressed;
    }

    /** Ends the render handoff. */
    public static void endCameraFrame() {
        cameraFrameSuppressed = false;
    }

    private static double squaredDistance(double x1, double y1, double z1,
                                          double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void captureCameraAnchor(Minecraft mc, Entity captureEntity) {
        cameraX = captureEntity.posX;
        cameraBaseY = captureEntity.posY;
        cameraEyeHeight = captureEntity.getEyeHeight();
        cameraY = cameraBaseY + cameraEyeHeight;
        cameraZ = captureEntity.posZ;
        cameraPitch = captureEntity.rotationPitch;
        cameraYaw = captureEntity.rotationYaw;
        cameraPrevPitch = captureEntity.prevRotationPitch;
        cameraPrevYaw = captureEntity.prevRotationYaw;
        capturedWorld = mc.theWorld;
        capturedPlayer = mc.thePlayer;
    }

    /** Returns an entity that belongs to the currently loaded world. */
    private static Entity getCurrentWorldEntity(Minecraft mc, Entity preferred) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        if (mc.thePlayer != null && mc.thePlayer.worldObj == mc.theWorld) {
            return mc.thePlayer;
        }
        if (preferred != null && preferred.worldObj == mc.theWorld) {
            return preferred;
        }
        return null;
    }
    
    /**
     * Renders the recording icon when selfie mode is active.
     * Shows at position (90, 50) as requested.
     */
    public static void renderRecordingIcon() {
        // Recording icon removed per user request
        return;
    }
    
    /**
     * Draws the recording icon using the actual recording_icon.png texture with flashing
     */
    private static void drawRecordingIcon(int x, int y) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            
            // Try to bind the recording icon texture with runtime fallback (mirrors cursor loading)
            ResourceLocation rlPack = new ResourceLocation("gomod123:textures/recording_icon.png");
            boolean bound = false;
            try {
                if (mc != null && mc.getResourceManager() != null) {
                    mc.getResourceManager().getResource(rlPack);
                    mc.getTextureManager().bindTexture(rlPack);
                    bound = true;
                }
            } catch (Throwable ignored) {}
            if (!bound) {
                try {
                    java.io.InputStream s = Selfie.class.getResourceAsStream("/assets/gomod123/textures/recording_icon.png");
                    if (s != null) {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(s);
                        if (img != null) {
                            net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                            ResourceLocation rt = new ResourceLocation("gomod123:runtime/recording_icon");
                            mc.getTextureManager().loadTexture(rt, dyn);
                            mc.getTextureManager().bindTexture(rt);
                            bound = true;
                        }
                        try { s.close(); } catch (Exception ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            if (!bound) {
                // Fallback to simple shapes if we couldn't bind any texture
                throw new RuntimeException("recording_icon not bound");
            }
            
            // Enable blending for transparency
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            
            // Background panel behind the icon (semi-transparent black with slight padding)
            int iconW = 128;
            int iconH = 64;
            int pad = 4;
            int bgColor = 0xAA000000; // ~66% opacity black
            Gui.drawRect(x - pad, y - pad, x + iconW + pad, y + iconH + pad, bgColor);
            
            // Draw at a reasonable size (banner ratio)
            Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, iconW, iconH, iconW, iconH);
            
            // Disable blending
            GlStateManager.disableBlend();
            
        } catch (Exception e) {
            // Fallback to simple shapes if texture fails to load
            try {
                // Draw red circle (8x8 pixels)
                Gui.drawRect(x, y, x + 8, y + 8, 0xFFFF0000); // Solid red circle
                
                // Draw "REC" text next to the circle
                String recText = EnumChatFormatting.RED + "REC";
                Minecraft.getMinecraft().fontRendererObj.drawString(recText, x + 12, y + 1, 0xFFFFFF);
            } catch (Exception e2) {
                // Ignore drawing errors
            }
        }
    }
    
    /**
     * Loads Selfie settings from config.
     * Called during mod initialization.
     */
    public static void loadSetting() {
        // Selfie mode is intentionally not persisted between sessions
        // It should always start disabled when the game launches
        enabled = false;
        capturedWorld = null;
        capturedPlayer = null;
        cameraHandoffFrames = 0;
        worldTransferRefreshFrames = 0;
        cameraFrameSuppressed = false;
        System.out.println("[Selfie] Initialized (always starts disabled)");
    }
}
