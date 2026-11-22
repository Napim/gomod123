package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * RoleplayMode - places the camera at a target player's exact eye position
 * and inherits their look angles, giving a first-person "watch through their eyes" view.
 *
 * Cheat-detection safety:
 *  - Completely client-side: no packets are sent or modified.
 *  - The local player still moves, attacks, and interacts normally.
 *  - Only the RENDERING camera is redirected; server-side position is never faked.
 */
public final class RoleplayMode {
    private RoleplayMode() {}

    private static boolean   enabled              = false;
    private static String    targetPlayerName     = null;
    private static int       originalThirdPersonView = 0;

    // Smoothed camera yaw/pitch set by RoleplayRendererMixin every frame.
    // RenderManagerMixin reads these for nametag billboard orientation.
    private static float     smoothCameraYaw      = 0.0f;
    private static float     smoothCameraPitch    = 0.0f;

    // Scoping flag: true only while ItemRenderer.renderItemInFirstPerson is executing.
    // AbstractClientPlayerMixin checks this to swap the skin only during arm rendering.
    private static boolean   renderingFirstPersonArm = false;

    // Frozen visual body yaw for the local player while roleplay is active.
    // This keeps the rendered model from following raw mouse input.
    private static float     localFrozenBodyYaw = 0.0f;
    private static float     localFrozenPrevBodyYaw = 0.0f;
    private static boolean   localFrozenBodyYawInitialized = false;

    // -----------------------------------------------------------------------
    // State accessors
    // -----------------------------------------------------------------------

    public static boolean isEnabled() {
        return enabled;
    }

    public static String getTargetPlayerName() {
        return targetPlayerName;
    }

    /** Returns the smoothed camera yaw computed by RoleplayRendererMixin. */
    public static float getSmoothCameraYaw()  { return smoothCameraYaw;  }
    /** Returns the smoothed camera pitch computed by RoleplayRendererMixin. */
    public static float getSmoothCameraPitch() { return smoothCameraPitch; }

    /** True only while the first-person arm is being rendered (scoping flag for skin swap). */
    public static boolean isRenderingFirstPersonArm() { return renderingFirstPersonArm; }
    public static void setRenderingFirstPersonArm(boolean v) { renderingFirstPersonArm = v; }

    /** Called every frame from RoleplayRendererMixin to publish the smoothed angles. */
    public static void setSmoothCameraAngles(float yaw, float pitch) {
        smoothCameraYaw   = yaw;
        smoothCameraPitch = pitch;
    }

    public static float updateAndGetLocalFrozenBodyYaw(EntityPlayer player) {
        if (player == null) return 0.0f;

        if (!localFrozenBodyYawInitialized) {
            localFrozenBodyYaw = player.renderYawOffset;
            localFrozenPrevBodyYaw = player.prevRenderYawOffset;
            localFrozenBodyYawInitialized = true;
            return localFrozenBodyYaw;
        }

        double dx = player.posX - player.lastTickPosX;
        double dz = player.posZ - player.lastTickPosZ;
        double movementSq = dx * dx + dz * dz;

        if (movementSq > 1.0E-4D) {
            float movementYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0f;
            localFrozenPrevBodyYaw = localFrozenBodyYaw;
            localFrozenBodyYaw += MathHelper.wrapAngleTo180_float(movementYaw - localFrozenBodyYaw);
        } else {
            localFrozenPrevBodyYaw = localFrozenBodyYaw;
        }

        return localFrozenBodyYaw;
    }

    public static float getLocalFrozenPrevBodyYaw() {
        return localFrozenPrevBodyYaw;
    }

    public static void resetLocalFrozenBodyYaw() {
        localFrozenBodyYaw = 0.0f;
        localFrozenPrevBodyYaw = 0.0f;
        localFrozenBodyYawInitialized = false;
    }

    /**
     * Looks up the target player entity in the current world.
     * Returns null if the player cannot be found (e.g. out of render range).
     */
    public static EntityPlayer getTargetPlayer() {
        if (targetPlayerName == null) return null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return null;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player.getName().equalsIgnoreCase(targetPlayerName)) {
                    return player;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // -----------------------------------------------------------------------
    // Enable / disable
    // -----------------------------------------------------------------------

    /**
     * Enables roleplay mode targeting the given player username.
     * Returns false if the player cannot be found in the current world.
     */
    public static boolean enable(String playerName) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return false;

            EntityPlayer target = null;
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player.getName().equalsIgnoreCase(playerName)) {
                    target = player;
                    break;
                }
            }
            if (target == null) return false;

            // Disable Selfie if it is currently active so the two modes don't conflict.
            if (Selfie.isEnabled()) {
                Selfie.setEnabled(false);
            }
            if (PlayersPlayMode.isEnabled()) {
                PlayersPlayMode.disable();
            }
            targetPlayerName        = target.getName(); // preserve exact capitalisation
            enabled                 = true;
            originalThirdPersonView = mc.gameSettings.thirdPersonView; // snapshot to restore on exit
            // First-person is required: third-person camera + renderViewEntity swap fights vanilla
            // (hand pass, orbit offset, bobbing) and reads as rendering glitches.
            mc.gameSettings.thirdPersonView = 0;
            resetLocalFrozenBodyYaw();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Disables roleplay mode and restores the original camera view setting.
     */
    public static void disable() {
        try {
            if (enabled) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    mc.gameSettings.thirdPersonView = originalThirdPersonView;
                }
            }
        } catch (Throwable ignored) {}

        enabled          = false;
        targetPlayerName = null;
        resetLocalFrozenBodyYaw();
    }
}
