package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.ClientGuiHelper;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Sends the name of the player under the crosshair to chat on a bound key.
 * In Blitz Teams <em>in-game</em> (including cage), prefixes with {@code /shout }; pregame / hub lobby sends plain name.
 */
public final class PlayerCaller {
    private static final long TRIGGER_COOLDOWN_MS = 500L;
    private static final double MAX_CALL_DISTANCE = 256.0D;

    private static boolean enabled = false;
    private static int keyCode = 0;
    private static boolean configLoaded = false;
    private static boolean wasKeyDown = false;
    private static boolean ignoreBoundKeyUntilRelease = false;
    private static long lastTriggerMs = 0L;

    public static boolean isEnabled() {
        ensureInitialized();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        ensureInitialized();
        enabled = value;
        ConfigManager.setPlayerCallerEnabled(value);
        ConfigManager.saveConfigNow();
    }

    public static int getKeyCode() {
        ensureInitialized();
        return keyCode;
    }

    public static void setKeyCode(int value) {
        ensureInitialized();
        keyCode = value;
        ignoreBoundKeyUntilRelease = value > 0;
        ConfigManager.setPlayerCallerKeyCode(value);
        ConfigManager.saveConfigNow();
    }

    public static void loadFromConfig() {
        enabled = ConfigManager.isPlayerCallerEnabled();
        keyCode = ConfigManager.getPlayerCallerKeyCode();
        configLoaded = true;
    }

    private static void ensureInitialized() {
        if (!configLoaded) {
            loadFromConfig();
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        handleRenderOverlay();
    }

    private static void handleRenderOverlay() {
        ensureInitialized();
        if (!enabled) {
            wasKeyDown = false;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            wasKeyDown = false;
            return;
        }
        if (ClientGuiHelper.shouldSuppressWorldKeybinds(mc)) {
            wasKeyDown = false;
            return;
        }

        if (keyCode <= 0) {
            wasKeyDown = false;
            return;
        }

        boolean isKeyDown = Keyboard.isKeyDown(keyCode);
        if (ignoreBoundKeyUntilRelease) {
            if (isKeyDown) {
                wasKeyDown = true;
                return;
            }
            ignoreBoundKeyUntilRelease = false;
            wasKeyDown = false;
            return;
        }
        if (!isKeyDown) {
            wasKeyDown = false;
            return;
        }
        if (wasKeyDown) {
            return;
        }
        wasKeyDown = true;

        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < TRIGGER_COOLDOWN_MS) {
            return;
        }

        String targetName = getLookedAtPlayerName(mc);
        if (targetName == null || targetName.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Look at a player first.");
            lastTriggerMs = now;
            return;
        }

        String message = shouldUseShout(mc) ? "/shout " + targetName : targetName;
        try {
            mc.thePlayer.sendChatMessage(message);
            lastTriggerMs = now;
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to call " + targetName + ".");
            lastTriggerMs = now;
        }
    }

    private static String getLookedAtPlayerName(Minecraft mc) {
        try {
            EntityPlayer player = getLookedAtPlayer(mc, MAX_CALL_DISTANCE);
            if (player == null || player == mc.thePlayer) {
                return null;
            }
            if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                return player.getGameProfile().getName().trim();
            }
            String name = player.getName();
            return name == null ? null : name.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EntityPlayer getLookedAtPlayer(Minecraft mc, double distance) {
        try {
            Entity viewer = mc.getRenderViewEntity();
            if (viewer == null || mc.theWorld == null) {
                return null;
            }

            Vec3 eyePos = viewer.getPositionEyes(1.0F);
            Vec3 lookVec = viewer.getLook(1.0F);
            Vec3 reachVec = eyePos.addVector(
                    lookVec.xCoord * distance,
                    lookVec.yCoord * distance,
                    lookVec.zCoord * distance
            );

            MovingObjectPosition blockHit = mc.theWorld.rayTraceBlocks(eyePos, reachVec, false, true, false);
            double closestDistance = blockHit != null && blockHit.hitVec != null
                    ? eyePos.distanceTo(blockHit.hitVec)
                    : distance;
            EntityPlayer pointedPlayer = null;

            java.util.List<Entity> entities = mc.theWorld.getEntitiesWithinAABBExcludingEntity(
                    viewer,
                    viewer.getEntityBoundingBox()
                            .addCoord(lookVec.xCoord * distance, lookVec.yCoord * distance, lookVec.zCoord * distance)
                            .expand(1.0D, 1.0D, 1.0D)
            );

            for (Entity entity : entities) {
                if (!(entity instanceof EntityPlayer) || entity == viewer) {
                    continue;
                }

                float border = entity.getCollisionBorderSize();
                AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
                MovingObjectPosition intercept = box.calculateIntercept(eyePos, reachVec);

                if (box.isVecInside(eyePos)) {
                    if (closestDistance >= 0.0D) {
                        pointedPlayer = (EntityPlayer) entity;
                        closestDistance = 0.0D;
                    }
                    continue;
                }

                if (intercept == null || intercept.hitVec == null) {
                    continue;
                }

                double hitDistance = eyePos.distanceTo(intercept.hitVec);
                if (hitDistance < closestDistance) {
                    pointedPlayer = (EntityPlayer) entity;
                    closestDistance = hitDistance;
                }
            }

            // If the ray misses every player hitbox, choose the player closest to its direction.
            // Compare every loaded player in front of the viewer, but keep the block and distance limits.
            if (pointedPlayer == null) {
                pointedPlayer = getClosestPlayerAlongAim(mc.theWorld.playerEntities, viewer, eyePos, lookVec, closestDistance);
            }

            return pointedPlayer;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static EntityPlayer getClosestPlayerAlongAim(java.util.List<EntityPlayer> players, Entity viewer,
            Vec3 eyePos, Vec3 lookVec, double maxDistance) {
        double lookLengthSquared = lookVec.dotProduct(lookVec);
        if (lookLengthSquared <= 1.0E-8D || maxDistance <= 0.0D) return null;

        EntityPlayer best = null;
        double bestAlignment = -Double.MAX_VALUE;
        double bestProjection = Double.MAX_VALUE;
        for (EntityPlayer player : players) {
            if (player == null || player == viewer) continue;

            AxisAlignedBB box = player.getEntityBoundingBox();
            if (box == null) continue;

            double centerX = (box.minX + box.maxX) * 0.5D;
            double centerY = (box.minY + box.maxY) * 0.5D;
            double centerZ = (box.minZ + box.maxZ) * 0.5D;
            Vec3 toCenter = new Vec3(centerX - eyePos.xCoord, centerY - eyePos.yCoord, centerZ - eyePos.zCoord);
            double centerLengthSquared = toCenter.dotProduct(toCenter);
            if (centerLengthSquared <= 1.0E-8D) continue;

            double projection = toCenter.dotProduct(lookVec) / lookLengthSquared;
            // Keep the fallback in front of the nearest block hit so it cannot select a player through a wall.
            if (projection <= 0.0D || projection > maxDistance + 1.0E-5D) continue;

            double alignment = toCenter.dotProduct(lookVec)
                    / Math.sqrt(lookLengthSquared * centerLengthSquared);
            if (alignment > bestAlignment + 1.0E-9D
                    || (Math.abs(alignment - bestAlignment) <= 1.0E-9D && projection < bestProjection)) {
                best = player;
                bestAlignment = alignment;
                bestProjection = projection;
            }
        }
        return best;
    }

    private static boolean shouldUseShout(Minecraft mc) {
        if (!BlitzTeamsDetector.isBlitzTeamsGame(mc)) {
            return false;
        }
        if (GameContext.isHypixelLobby(mc)) {
            return false;
        }
        // Queue / map waiting lobby: no shout. Cage ("Cages open") and live match: shout.
        if (GameContext.isBlitzPregameLobby(mc) && !GameContext.isBlitzCagePhaseSidebar(mc)) {
            return false;
        }
        return true;
    }
}
