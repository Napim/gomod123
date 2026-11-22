package me.napim.gomod.render;

import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.mixins.RendererLivingEntityInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import me.napim.gomod.util.HudGlState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.opengl.GL11;

/**
 * Collects player nametag render calls that were suppressed during the normal entity pass and replays them at the TAIL
 * of {@code RenderGlobal.renderEntities}, after tile entities (chests etc.) have been rendered.
 */
public final class DeferredNametagRenderer {

    private static final List<RenderNameEntry> pendingRenderName = new ArrayList<>();
    private static final List<RenderNameEntry> pendingSelfOpacityRenderName = new ArrayList<>();
    private static final List<NativeMobLabelEntry> pendingNativeMobLabels = new ArrayList<>();
    private static final Set<Integer> pendingSelfOpacityEntityIds = new HashSet<Integer>();
    private static final Set<Integer> chestRayCheckedEntityIds = new HashSet<Integer>();
    private static final Set<Integer> chestRayHitEntityIds = new HashSet<Integer>();
    private static final List<BlockPos> chestPositions = new ArrayList<>();
    private static final int MAX_CHEST_POSITIONS = 256;
    private static boolean chestPositionsCollected;
    private static boolean chestPositionsComplete;
    private static boolean flushing = false;
    private static boolean flushingSelfOpacityReplay = false;
    private static final ThreadLocal<Boolean> renderingQueuedNativeMobLabel = new ThreadLocal<Boolean>();
    private static volatile boolean weaveWorldEventFlush = false;
    private static boolean replayFailureLogged = false;
    private static boolean nativeMobLabelFailureLogged = false;

    private DeferredNametagRenderer() {}

    public static boolean shouldDeferThroughChests() {
        if (!VisibleSpecs.shouldAllowNametagThroughBlocksNow()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            // GUI screens (including gomod's live player previews) already have their own render pass. Replaying
            // world labels at world-tail there creates a visibly doubled/offset nametag on Lunar replay actors.
            if (mc != null && mc.currentScreen != null) {
                return false;
            }
            if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean shouldDeferThroughChests(EntityLivingBase entity, double x, double y, double z) {
        return shouldDeferThroughChests(entity,x,y,z,0.0D);
    }

    public static boolean shouldDeferThroughChests(EntityLivingBase entity, double x, double y, double z,
            double footYExtra) {
        return shouldDeferThroughChests()
                && hasChestBetweenViewAndNametag(entity, x, y, z, footYExtra)
                && VisibleSpecs.shouldAllowNametagThroughBlocksFor(entity, x, y, z, footYExtra);
    }

    /**
     * Tile entities render after ordinary entities. Trace from the rendered camera, including its eye/third-person
     * offset, so a chest in the actual line of sight queues a replay rather than using the camera entity's feet.
     */
    private static boolean hasChestBetweenViewAndNametag(EntityLivingBase entity, double x, double y, double z,
            double footYExtra) {
        if (entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc == null ? null : mc.theWorld;
        RenderManager manager = mc == null ? null : mc.getRenderManager();
        if (world == null || manager == null) return false;
        if (!chestPositionsCollected) {
            collectChestPositions(world);
        }
        if (chestPositionsComplete && chestPositions.isEmpty()) return false;
        Integer entityId = Integer.valueOf(entity.getEntityId());
        if (chestRayCheckedEntityIds.contains(entityId)) {
            return chestRayHitEntityIds.contains(entityId);
        }

        long profile = me.napim.gomod.features.RenderProfiler.begin("DeferredNametags.chestIntersectionCheck");
        boolean hit = false;
        try {
            double labelY = y + entity.height + 0.5D + footYExtra;
            Vec3 start = cameraWorldPosition(mc);
            if (start == null) return false;
            double endX = manager.viewerPosX + x;
            double endY = manager.viewerPosY + labelY;
            double endZ = manager.viewerPosZ + z;
            if (chestPositionsComplete && !mayIntersectChest(start, endX, endY, endZ)) return false;
            Vec3 end = new Vec3(endX, endY, endZ);
            MovingObjectPosition firstBlockHit = world.rayTraceBlocks(start, end, false, true, false);
            if (firstBlockHit != null
                    && firstBlockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && firstBlockHit.getBlockPos() != null) {
                TileEntity firstTile = world.getTileEntity(firstBlockHit.getBlockPos());
                hit = firstTile instanceof TileEntityChest || firstTile instanceof TileEntityEnderChest;
            }
            return hit;
        } catch (Throwable ignored) {
            return false;
        } finally {
            chestRayCheckedEntityIds.add(entityId);
            if (hit) {
                chestRayHitEntityIds.add(entityId);
            }
            me.napim.gomod.features.RenderProfiler.end("DeferredNametags.chestIntersectionCheck", profile);
        }
    }

    private static void collectChestPositions(WorldClient world) {
        chestPositions.clear();
        chestPositionsCollected = true;
        chestPositionsComplete = false;
        try {
            List<TileEntity> tiles = world.loadedTileEntityList;
            if (tiles == null) return;
            for (TileEntity tile : tiles) {
                if (tile instanceof TileEntityChest || tile instanceof TileEntityEnderChest) {
                    BlockPos position = tile.getPos();
                    if (position == null || chestPositions.size() >= MAX_CHEST_POSITIONS) return;
                    chestPositions.add(position);
                }
            }
            chestPositionsComplete = true;
        } catch (RuntimeException ignored) {
            // Keep the original ray path if the client cannot enumerate the loaded tiles.
        }
    }

    /** Reject only boxes outside the segment's bounds. Vanilla still decides the first block hit. */
    private static boolean mayIntersectChest(Vec3 start, double endX, double endY, double endZ) {
        double minX = Math.min(start.xCoord, endX), maxX = Math.max(start.xCoord, endX);
        double minY = Math.min(start.yCoord, endY), maxY = Math.max(start.yCoord, endY);
        double minZ = Math.min(start.zCoord, endZ), maxZ = Math.max(start.zCoord, endZ);
        for (int i = 0; i < chestPositions.size(); i++) {
            BlockPos pos = chestPositions.get(i);
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (x <= maxX && x + 1.0D >= minX && y <= maxY && y + 1.0D >= minY
                    && z <= maxZ && z + 1.0D >= minZ) return true;
        }
        return false;
    }

    public static boolean enqueueSelfOpacityReplay(RendererLivingEntity renderer, EntityLivingBase entity,
                                                double x, double y, double z) {
        if (!shouldReplayOverSelfOpacity(renderer, entity, x, y, z)) {
            return false;
        }
        Integer id = Integer.valueOf(entity.getEntityId());
        if (!pendingSelfOpacityEntityIds.add(id)) {
            return false;
        }
        pendingSelfOpacityRenderName.add(new RenderNameEntry(renderer, entity, x, y, z));
        return true;
    }

    private static boolean shouldReplayOverSelfOpacity(RendererLivingEntity renderer, EntityLivingBase entity,
                                                       double x, double y, double z) {
        if (renderer == null || entity == null || flushing || flushingSelfOpacityReplay) {
            return false;
        }
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.gameSettings == null) {
                return false;
            }
            if (entity == mc.thePlayer || mc.currentScreen != null || mc.gameSettings.thirdPersonView == 0) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        // Only replay remote labels when the local player body is actually going through
        // SelfOpacity's two-pass render. Dead/spectator bodies are deliberately omitted, so
        // queuing labels in that state would cancel their normal draw without a replay pass.
        return SelfOpacity.shouldApplyToEntity(mc.thePlayer)
                && !shouldDeferThroughChests(entity, x, y, z)
                && !VisibleSpecs.shouldSuppressPlayerNametagForBlindness(entity, x, y, z)
                && !VisibleSpecs.isWaterBetweenViewAndNametag(entity, x, y, z);
    }

    /** Player nametags: replay {@code renderName} at flush time. */
    public static void enqueue(RendererLivingEntity renderer, EntityLivingBase entity, double x, double y, double z) {
        if (!shouldDeferThroughChests(entity, x, y, z)) {
            return;
        }
        if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(entity, x, y, z)) {
            return;
        }
        pendingRenderName.add(new RenderNameEntry(renderer, entity, x, y, z));
    }

    private static Vec3 cameraWorldPosition(Minecraft mc) {
        try {
            Entity view = mc == null ? null : mc.getRenderViewEntity();
            if (view == null) return null;
            float partial = mc.timer == null ? 1.0F : mc.timer.renderPartialTicks;
            // Minecraft 1.8's own helper adds the unprojected camera offset to the interpolated view entity.
            return ActiveRenderInfo.projectViewFromEntity(view, partial);
        } catch (Throwable unavailable) { return null; }
    }

    public static boolean isFlushing() {
        return flushing;
    }

    /**
     * Lunar's transformed RenderGlobal can leave the entity-tail callback in a render state where labels are dropped.
     * Weave's RenderWorldEvent is posted later in the same world pass, immediately before hand rendering.
     */
    public static void useWeaveWorldEventFlush() {
        weaveWorldEventFlush = true;
    }

    /** Called at HEAD of {@code RenderGlobal.renderEntities} to avoid stale frames. */
    public static void clear() {
        pendingRenderName.clear();
        pendingSelfOpacityRenderName.clear();
        pendingNativeMobLabels.clear();
        pendingSelfOpacityEntityIds.clear();
        chestRayCheckedEntityIds.clear();
        chestRayHitEntityIds.clear();
        chestPositions.clear();
        chestPositionsCollected = false;
        chestPositionsComplete = false;
    }

    /** Forge/default flush site at RenderGlobal.renderEntities TAIL. */
    public static void flushAfterRenderEntities() {
        if (!weaveWorldEventFlush) {
            flush();
        }
    }

    /** Native Weave flush site, called from RenderWorldEvent after world/tile rendering. */
    public static void flushFromWeaveWorldEvent() {
        if (weaveWorldEventFlush) {
            flush();
        }
    }

    /** Replay queued nametags after tile entities. */
    public static void flush() {
        if (pendingRenderName.isEmpty() && pendingNativeMobLabels.isEmpty()) {
            return;
        }
        flushing = true;
        try {
            for (int i = 0; i < pendingRenderName.size(); i++) {
                RenderNameEntry e = pendingRenderName.get(i);
                String profileSection = e.entity instanceof EntityPlayer
                        ? "DeferredNametags.playerReplay"
                        : "DeferredNametags.mobReplay";
                long replayProfile = me.napim.gomod.features.RenderProfiler.begin(profileSection);
                try {
                    if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(e.entity, e.x, e.y, e.z)) {
                        continue;
                    }
                    if (!VisibleSpecs.shouldAllowNametagThroughBlocksFor(e.entity, e.x, e.y, e.z)) {
                        continue;
                    }
                    int previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
                    boolean previousDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                    boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                    try {
                        // Match vanilla's nametag passes: the first text pass disables depth and is
                        // dim through walls; the second is bright and must respect the chest depth.
                        // GL_ALWAYS made that second pass fully visible through chests.
                        HudGlState.restoreBeforeWorldText();
                        prepareChestReplayDepth();
                        ((RendererLivingEntityInvoker) e.renderer).gomod$invokeRenderName(e.entity, e.x, e.y, e.z);
                        markMobNametagRenderedIfNeeded(e.entity);
                    } catch (Throwable replayFailure) {
                        // Lunar occasionally rejects the protected renderName bridge after transforming RenderPlayer.
                        // The call was already accepted during the normal pass, so drawing its final living label is safe.
                        try {
                            HudGlState.restoreBeforeWorldText();
                            prepareChestReplayDepth();
                            String label = e.entity.getDisplayName() == null
                                    ? e.entity.getName()
                                    : e.entity.getDisplayName().getFormattedText();
                            ((RenderAccessor) e.renderer).gomod$invokeRenderLivingLabel(
                                    e.entity, label, e.x, e.y, e.z, 64);
                            markMobNametagRenderedIfNeeded(e.entity);
                        } catch (Throwable fallbackFailure) {
                            if (!replayFailureLogged) {
                                replayFailureLogged = true;
                                System.err.println("[gomod] Deferred nametag replay failed: "
                                        + replayFailure.getClass().getName() + "; fallback: "
                                        + fallbackFailure.getClass().getName());
                            }
                        }
                    } finally {
                        restoreChestReplayDepth(previousDepthFunction, previousDepthTest, previousDepthMask);
                    }
                } finally {
                    me.napim.gomod.features.RenderProfiler.end(profileSection, replayProfile);
                }
            }
            for (int i = 0; i < pendingNativeMobLabels.size(); i++) {
                NativeMobLabelEntry e = pendingNativeMobLabels.get(i);
                if (e == null || e.renderer == null || e.entity == null) continue;
                Boolean previous = renderingQueuedNativeMobLabel.get();
                renderingQueuedNativeMobLabel.set(Boolean.TRUE);
                NametagFogState.Snapshot previousFog = NametagFogState.restoreWorldPassTemporarily();
                try {
                    HudGlState.restoreBeforeWorldText();
                    NametagRenderState.begin("gomod-native-mob-nameplate");
                    try {
                        e.renderer.gomod$invokeRenderLivingLabel(
                                e.entity, e.label, e.x, e.y, e.z, e.maxDistance);
                    } finally {
                        NametagRenderState.end();
                    }
                } catch (Throwable failure) {
                    if (!nativeMobLabelFailureLogged) {
                        nativeMobLabelFailureLogged = true;
                        System.err.println("[gomod] Native close-range mob nametag failed: "
                                + failure.getClass().getName());
                    }
                } finally {
                    NametagFogState.restoreCurrent(previousFog);
                    if (Boolean.TRUE.equals(previous)) renderingQueuedNativeMobLabel.set(Boolean.TRUE);
                    else renderingQueuedNativeMobLabel.remove();
                }
            }
        } finally {
            flushing = false;
            pendingRenderName.clear();
            pendingNativeMobLabels.clear();
        }
    }

    /** Queue a close F5 mob label for the native renderer after entity models have finished. */
    public static boolean enqueueNativeMobLabel(EntityLivingBase entity, String label,
            double x, double y, double z, int maxDistance) {
        if (entity == null || label == null || label.isEmpty() || flushing) return false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Render<?> renderer = mc == null || mc.getRenderManager() == null
                    ? null : mc.getRenderManager().getEntityRenderObject(entity);
            if (!(renderer instanceof RenderAccessor)) return false;
            pendingNativeMobLabels.add(new NativeMobLabelEntry((RenderAccessor) renderer, entity,
                    label, x, y, z, maxDistance));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Draw a late-world fallback through Minecraft's renderer while restoring the original Blindness fog. */
    public static boolean renderNativeMobLabelNow(EntityLivingBase entity, String label,
            double x, double y, double z, int maxDistance) {
        if (entity == null || label == null || label.isEmpty()) return false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Render<?> renderer = mc == null || mc.getRenderManager() == null
                    ? null : mc.getRenderManager().getEntityRenderObject(entity);
            if (!(renderer instanceof RenderAccessor)) return false;
            Boolean previous = renderingQueuedNativeMobLabel.get();
            renderingQueuedNativeMobLabel.set(Boolean.TRUE);
            NametagFogState.Snapshot previousFog = NametagFogState.restoreWorldPassTemporarily();
            try {
                HudGlState.restoreBeforeWorldText();
                NametagRenderState.begin("gomod-native-world-mob-nameplate");
                try {
                    ((RenderAccessor) renderer).gomod$invokeRenderLivingLabel(
                            entity, label, x, y, z, maxDistance);
                } finally {
                    NametagRenderState.end();
                }
            } finally {
                NametagFogState.restoreCurrent(previousFog);
                if (Boolean.TRUE.equals(previous)) renderingQueuedNativeMobLabel.set(Boolean.TRUE);
                else renderingQueuedNativeMobLabel.remove();
            }
            return true;
        } catch (Throwable failure) {
            if (!nativeMobLabelFailureLogged) {
                nativeMobLabelFailureLogged = true;
                System.err.println("[gomod] Native world mob nametag failed: "
                        + failure.getClass().getName());
            }
            return false;
        }
    }

    /** The native label mixin must preserve the already-resolved queued text verbatim. */
    public static boolean isRenderingQueuedNativeMobLabel() {
        return Boolean.TRUE.equals(renderingQueuedNativeMobLabel.get());
    }

    /** Prime both the Minecraft cache and driver so native disableDepth/enableDepth actually switch the two passes. */
    private static void prepareChestReplayDepth() {
        GlStateManager.enableDepth();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GlStateManager.depthMask(true);
        GL11.glDepthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
    }

    private static void restoreChestReplayDepth(int function, boolean enabled, boolean mask) {
        GlStateManager.depthFunc(function);
        GL11.glDepthFunc(function);
        GlStateManager.depthMask(mask);
        GL11.glDepthMask(mask);
        if (enabled) {
            GlStateManager.enableDepth();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GlStateManager.disableDepth();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    /** Replay player labels after the transparent local body, only for self-opacity body occlusion. */
    public static void flushAfterSelfOpacity() {
        if (pendingSelfOpacityRenderName.isEmpty()) {
            return;
        }
        List<RenderNameEntry> entries = new ArrayList<RenderNameEntry>(pendingSelfOpacityRenderName);
        pendingSelfOpacityRenderName.clear();
        pendingSelfOpacityEntityIds.clear();
        flushingSelfOpacityReplay = true;
        try {
            for (int i = 0; i < entries.size(); i++) {
                RenderNameEntry e = entries.get(i);
                if (e == null || e.renderer == null || e.entity == null) {
                    continue;
                }
                if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(e.entity, e.x, e.y, e.z)) {
                    continue;
                }
                if (VisibleSpecs.isWaterBetweenViewAndNametag(e.entity, e.x, e.y, e.z)) {
                    continue;
                }
                try {
                    ((RendererLivingEntityInvoker) e.renderer).gomod$invokeRenderName(e.entity, e.x, e.y, e.z);
                } catch (Throwable replayFailure) {
                    try {
                        String label = e.entity.getDisplayName() == null
                                ? e.entity.getName()
                                : e.entity.getDisplayName().getFormattedText();
                        ((RenderAccessor) e.renderer).gomod$invokeRenderLivingLabel(
                                e.entity, label, e.x, e.y, e.z, 64);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            flushingSelfOpacityReplay = false;
        }
    }

    private static void markMobNametagRenderedIfNeeded(EntityLivingBase entity) {
        if (MobNametags.shouldRenderMobNametag(entity)) {
            MobNametags.markWorldMobNametagRendered(entity);
        }
    }

    private static final class RenderNameEntry {
        final RendererLivingEntity renderer;
        final EntityLivingBase entity;
        final double x, y, z;
        RenderNameEntry(RendererLivingEntity renderer, EntityLivingBase entity, double x, double y, double z) {
            this.renderer = renderer;
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class NativeMobLabelEntry {
        final RenderAccessor renderer;
        final EntityLivingBase entity;
        final String label;
        final double x, y, z;
        final int maxDistance;

        NativeMobLabelEntry(RenderAccessor renderer, EntityLivingBase entity, String label,
                double x, double y, double z, int maxDistance) {
            this.renderer = renderer;
            this.entity = entity;
            this.label = label;
            this.x = x;
            this.y = y;
            this.z = z;
            this.maxDistance = maxDistance;
        }
    }


}
