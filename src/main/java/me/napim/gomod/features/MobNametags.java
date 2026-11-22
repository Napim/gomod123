package me.napim.gomod.features;

import me.napim.gomod.render.VanillaEntityLabelRenderer;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.render.NametagFogState;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.client.renderer.entity.RenderManager;

import java.util.HashSet;
import java.util.Set;

public final class MobNametags {
    public static final int MAX_DISTANCE = 64;
    private static final double CLOSE_THIRD_PERSON_NATIVE_LABEL_DISTANCE_SQ = 100.0D;
    private static final Set<Integer> worldMobNametagsRenderedThisFrame = new HashSet<Integer>();
    private static final Set<Integer> worldMobNametagsForcedThisFrame = new HashSet<Integer>();
    private static long worldMobNametagFrameBeginAtMs = 0L;

    private MobNametags() {
    }

    public static boolean isEnabled() {
        return ConfigManager.isMobNametagsEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setMobNametagsEnabled(enabled);
    }

    public static boolean shouldForceNameRender(EntityLivingBase entity) {
        return !MobHealthDisplay.isRenderingGuiMobPreview()
                && isSupportedMob(entity)
                && (isEnabled() || MobTargetDisplay.shouldRenderTargetNametag(entity)
                || MobHealthDisplay.shouldForceMobNametagsForCombinedMode(entity)
                || MobHealthDisplay.shouldForceHealthNametagNameRender(entity));
    }

    public static boolean shouldRenderMobNametag(EntityLivingBase entity) {
        return !MobHealthDisplay.isRenderingGuiMobPreview()
                && isSupportedMob(entity)
                && (isEnabled() || MobTargetDisplay.shouldRenderTargetNametag(entity)
                || MobHealthDisplay.shouldForceMobNametagsForCombinedMode(entity));
    }

    /**
     * Used by {@link me.napim.gomod.mixins.RenderLivingLabelMixin} during vanilla nameplate draw.
     */
    public static String resolveLivingLabel(EntityLivingBase entity, String vanillaLabel) {
        if (!shouldRenderMobNametag(entity)) {
            return vanillaLabel;
        }
        String custom = formatLabelForRender(entity);
        return custom != null ? custom : vanillaLabel;
    }

    public static String resolveNametagName(EntityLivingBase entity) {
        boolean highlightTarget = MobTargetDisplay.shouldRenderTargetNametag(entity);
        String label = getMobLabel(entity, highlightTarget);
        if (label == null || label.trim().isEmpty()) {
            try {
                label = entity.getName();
            } catch (Throwable ignored) {
                label = null;
            }
        }
        if (label == null || label.trim().isEmpty()) {
            return null;
        }
        return EnumChatFormatting.getTextWithoutFormattingCodes(label);
    }

    public static void beginWorldMobNametagFrame() {
        worldMobNametagsRenderedThisFrame.clear();
        worldMobNametagsForcedThisFrame.clear();
        worldMobNametagFrameBeginAtMs = System.currentTimeMillis();
    }

    /** Capture Minecraft's fog before world/tile and late-overlay passes can modify it. */
    public static void captureWorldMobNametagFog() {
        NametagFogState.captureWorldPass();
    }

    public static boolean hasFreshWorldMobNametagFrame() {
        long age = System.currentTimeMillis() - worldMobNametagFrameBeginAtMs;
        return age >= 0L && age <= 250L;
    }

    public static void markWorldMobNametagRendered(EntityLivingBase entity) {
        if (entity != null) {
            worldMobNametagsRenderedThisFrame.add(Integer.valueOf(entity.getEntityId()));
        }
    }

    private static boolean wasWorldMobNametagRendered(EntityLivingBase entity) {
        return entity != null && worldMobNametagsRenderedThisFrame.contains(Integer.valueOf(entity.getEntityId()));
    }

    private static boolean wasWorldMobNametagForced(EntityLivingBase entity) {
        return entity != null && worldMobNametagsForcedThisFrame.contains(Integer.valueOf(entity.getEntityId()));
    }

    private static void markWorldMobNametagForced(EntityLivingBase entity) {
        if (entity != null) {
            worldMobNametagsForcedThisFrame.add(Integer.valueOf(entity.getEntityId()));
        }
    }

    public static void renderWorldMobNametags(float partialTicks) {
        renderWorldMobNametags(partialTicks, false);
    }

    public static void renderWorldMobNametags(float partialTicks, boolean forceRedraw) {
        renderWorldMobNametags(partialTicks, forceRedraw, forceRedraw);
    }

    /**
     * Weave's late world event must still fill labels skipped by Lunar while the through-chests option is enabled,
     * but it must not redraw labels already emitted by the normal or deferred entity pass.
     */
    public static void renderMissingWorldMobNametagsThroughChests(float partialTicks) {
        renderWorldMobNametags(partialTicks, true, false);
    }

    private static void renderWorldMobNametags(float partialTicks, boolean throughChestsFallback,
            boolean redrawExisting) {
        if (MobHealthDisplay.isRenderingGuiMobPreview()) {
            return;
        }
        if (!throughChestsFallback && VisibleSpecs.shouldAllowNametagThroughBlocksNow()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderManager() == null) {
            return;
        }
        try {
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            if (!hasFreshWorldMobNametagFrame()) {
                beginWorldMobNametagFrame();
            }
            RenderManager rm = mc.getRenderManager();
            for (Object obj : mc.theWorld.loadedEntityList) {
                if (!(obj instanceof EntityLivingBase)) {
                    continue;
                }
                EntityLivingBase entity = (EntityLivingBase) obj;
                if (!redrawExisting && (wasWorldMobNametagRendered(entity) || wasWorldMobNametagForced(entity))) {
                    continue;
                }
                if (redrawExisting && wasWorldMobNametagForced(entity)) {
                    continue;
                }
                if (!shouldRenderMobNametag(entity)) {
                    continue;
                }
                if (MobHealthDisplay.shouldRenderHealthNametagFor(entity)) {
                    continue;
                }
                if (!throughChestsFallback && MobHealthDisplay.shouldRenderPlainMobNameInEntityTail(entity)) {
                    continue;
                }
                EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
                if (mobProxy != null && entity == mobProxy) {
                    continue;
                }
                double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - rm.viewerPosX;
                double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - rm.viewerPosY;
                double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - rm.viewerPosZ;
                if (throughChestsFallback && VisibleSpecs.isWaterBetweenViewAndNametag(entity, x, y, z,
                        MobHealthDisplay.mobLabelBaseYOffset(entity))) {
                    continue;
                }
                renderMobNametagAt(entity, x, y, z, 0.0D, true, true);
                if (throughChestsFallback) {
                    markWorldMobNametagForced(entity);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void renderMobNametag(EntityLivingBase entity, double x, double y, double z) {
        if (MobHealthDisplay.shouldRenderHealthNametagFor(entity)
                && !MobHealthDisplay.isCombinedNametagMode()) {
            return;
        }
        renderMobNametagAt(entity, x, y, z, 0.0D);
    }

    /** Health-display stacked row; uses vanilla {@code renderLivingLabel}. */
    public static void renderMobNametagAt(EntityLivingBase entity, double x, double y, double z, double footYExtra) {
        // Keep vanilla's faint depth-off nameplate pass independent from the separate chest replay workaround.
        // This also lets the late Lunar fallback remain visible behind the local F5 model.
        renderMobNametagAt(entity, x, y, z, footYExtra, true);
    }

    /**
     * Explicit visibility variant used by health-display fallback names. A mob excluded from healthtags must retain
     * the same vanilla through-wall visibility as the health row it replaces.
     */
    public static void renderMobNametagAt(EntityLivingBase entity, double x, double y, double z, double footYExtra,
            boolean fadeThroughWalls) {
        renderMobNametagAt(entity, x, y, z, footYExtra, fadeThroughWalls, false);
    }

    private static void renderMobNametagAt(EntityLivingBase entity, double x, double y, double z,
            double footYExtra, boolean fadeThroughWalls, boolean lateWorldFallback) {
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            return;
        }
        String label = formatLabelForRender(entity);
        if (label == null) {
            return;
        }
        double labelFootYExtra = footYExtra + MobHealthDisplay.mobLabelBaseYOffset(entity);
        boolean canFadeThroughWalls = fadeThroughWalls
                && !VisibleSpecs.isWaterBetweenViewAndNametag(entity, x, y, z, labelFootYExtra);
        if (lateWorldFallback && canFadeThroughWalls && isThirdPersonView()) {
            double nativeLabelY = y + labelFootYExtra;
            if (DeferredNametagRenderer.renderNativeMobLabelNow(
                    entity, label, x, nativeLabelY, z, MAX_DISTANCE)) {
                markWorldMobNametagRendered(entity);
                return;
            }
        }
        boolean closeThirdPerson = shouldDeferCloseThirdPersonLabel(entity);
        if (closeThirdPerson && canFadeThroughWalls) {
            double nativeLabelY = y + labelFootYExtra;
            if (DeferredNametagRenderer.enqueueNativeMobLabel(entity, label, x, nativeLabelY, z, MAX_DISTANCE)) {
                markWorldMobNametagRendered(entity);
                return;
            }
        }
        NametagFogState.Snapshot previousFog = NametagFogState.restoreWorldPassTemporarily();
        try {
            VanillaEntityLabelRenderer.render(entity, label, x, y, z, MAX_DISTANCE,
                    labelFootYExtra, 0xFFFFFF,
                    1.0F, 1.0F, false, canFadeThroughWalls, false);
        } finally {
            NametagFogState.restoreCurrent(previousFog);
        }
        markWorldMobNametagRendered(entity);
    }

    private static boolean isThirdPersonView() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Use Minecraft's native nameplate renderer at world tail for close third-person mob labels. */
    private static boolean shouldDeferCloseThirdPersonLabel(EntityLivingBase entity) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return entity != null && mc != null && mc.thePlayer != null && mc.gameSettings != null
                    && mc.gameSettings.thirdPersonView != 0
                    && entity.getDistanceSqToEntity(mc.thePlayer)
                            <= CLOSE_THIRD_PERSON_NATIVE_LABEL_DISTANCE_SQ;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String formatLabelForRender(EntityLivingBase entity) {
        boolean highlightTarget = MobTargetDisplay.shouldRenderTargetNametag(entity);
        String label = getMobLabel(entity, highlightTarget);
        if (label == null || label.trim().isEmpty()) {
            try {
                label = entity.getName();
            } catch (Throwable ignored) {
                label = null;
            }
        }
        if (label == null || label.trim().isEmpty()) {
            return null;
        }
        label = EnumChatFormatting.getTextWithoutFormattingCodes(label);
        if (label == null || label.trim().isEmpty()) {
            return null;
        }
        EnumChatFormatting color = EnumChatFormatting.WHITE;
        if (highlightTarget) {
            color = EnumChatFormatting.RED;
        } else if (ConfigManager.isFriendlyMobNametagColorEnabled() && SpawnedMobTracker.isPlayerSpawned(entity)) {
            color = EnumChatFormatting.GREEN;
        }
        return color + label;
    }

    private static boolean isSupportedMob(EntityLivingBase entity) {
        if (entity == null || entity.isDead || !entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityPlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && mc.thePlayer.ridingEntity == entity) {
                if (!MobTargetDisplay.shouldRenderTargetNametag(entity)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return !isIgnoredLobbyDisplayEntity(entity);
    }

    private static boolean isIgnoredLobbyDisplayEntity(EntityLivingBase entity) {
        try {
            if (entity instanceof EntityWither) {
                EntityWither wither = (EntityWither) entity;
                return (wither.isInvisible() && wither.hasCustomName())
                        || (wither.posY > 100.0D && wither.hasCustomName());
            }
            if (entity instanceof EntityDragon) {
                EntityDragon dragon = (EntityDragon) entity;
                return (dragon.isInvisible() && dragon.hasCustomName())
                        || (dragon.posY > 100.0D && dragon.hasCustomName());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String getMobLabel(EntityLivingBase entity, boolean highlightTarget) {
        String formatted = null;
        String unformatted = null;
        try {
            if (entity.getDisplayName() != null) {
                formatted = entity.getDisplayName().getFormattedText();
                unformatted = entity.getDisplayName().getUnformattedText();
            }
        } catch (Throwable ignored) {
        }
        if (formatted == null) {
            try {
                formatted = entity.getName();
            } catch (Throwable ignored) {
                formatted = null;
            }
        }
        if (unformatted == null) {
            try {
                unformatted = entity.getName();
            } catch (Throwable ignored) {
                unformatted = null;
            }
        }
        if (unformatted != null && !unformatted.trim().isEmpty()) {
            return EnumChatFormatting.getTextWithoutFormattingCodes(unformatted);
        }
        if (formatted != null && !formatted.trim().isEmpty()) {
            return EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        }
        try {
            return EnumChatFormatting.getTextWithoutFormattingCodes(entity.getName());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
