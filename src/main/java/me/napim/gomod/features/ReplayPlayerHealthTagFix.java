package me.napim.gomod.features;

import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/** Replacement stack for replay player nameplates controlled by spectator settings. */
public final class ReplayPlayerHealthTagFix {

    private static final int MAX_DISTANCE = 64;
    private static int skipNextTailEntityId = Integer.MIN_VALUE;

    private ReplayPlayerHealthTagFix() {
    }

    /**
     * Force-replay-health compatibility owns the whole replay stack. Replay spectator settings use the normal
     * RenderPlayer nameplate path so ONLY_IGN/OFF keep vanilla placement and suppression behavior.
     */
    public static boolean shouldRenderCompleteReplayStack(EntityLivingBase entity) {
        if (!(entity instanceof EntityPlayer) || !VisibleSpecs.isReplayContext()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || entity == mc.thePlayer) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        try {
            if (me.napim.gomod.gui.MountPreviewHelper.isVisibleSpecsPreviewPlayer(player)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return VisibleSpecs.shouldShowReplayHealthNametag(entity);
    }

    /** Per-player decision included in /visiblespecsdebug so the force-health/spectator split is visible. */
    public static String describeReplayStackPlan(EntityPlayer player) {
        if (player == null) {
            return "inactive reason=no-player";
        }
        boolean forceEnabled = GomodFixes.isForceReplayPlayerHealthTagsEnabled();
        boolean replay = VisibleSpecs.isReplayContext();
        boolean target = VisibleSpecs.isReplaySpectatorSettingsTarget(player);
        boolean active = shouldRenderCompleteReplayStack(player);
        String mode = target ? VisibleSpecs.getNametagMode() : VisibleSpecs.NAMETAG_MODE_ON;
        boolean healthRow = active && !target && VisibleSpecs.shouldShowReplayHealthNametag(player);
        boolean nameRow = active && !VisibleSpecs.NAMETAG_MODE_OFF.equals(mode);
        boolean denickCandidate = active && nameRow
                && NickRealNametagOverlay.buildRealIgnOverlayLabel(player) != null;
        boolean denickRow = denickCandidate && !VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode);
        boolean killerRow = active && target && SpectatorKillerNametagOverlay.shouldShowFor(player);
        StringBuilder rows = new StringBuilder();
        if (healthRow) rows.append("health,");
        if (nameRow) rows.append("IGN,");
        if (denickRow) rows.append("denick,");
        if (killerRow) rows.append("killer,");
        if (rows.length() > 0) rows.setLength(rows.length() - 1);
        return "active=" + active + " forceSetting=" + forceEnabled + " replay=" + replay
                + " replayTarget=" + target + " mode=" + mode
                + " healthRow=" + healthRow
                + (target ? " healthReason=spectator-target" : "")
                + " nameRow=" + nameRow + " denickRow=" + denickRow + " killerRow=" + killerRow
                + " plannedRows=[" + rows + "]";
    }

    /**
     * Replaces the replay nameplate with an ordered stack. Returns true when vanilla renderName must be cancelled.
     */
    public static boolean renderForcedStack(RendererLivingEntity renderer, EntityLivingBase entity,
            double x, double y, double z) {
        if (renderer == null || !(entity instanceof EntityPlayer)
                || !shouldRenderCompleteReplayStack(entity)) {
            return false;
        }
        if (entity.getEntityId() == skipNextTailEntityId) {
            skipNextTailEntityId = Integer.MIN_VALUE;
            return true;
        }
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            return true;
        }
        if (VisibleSpecs.isDeferredOpacityPrepassActive()) {
            return true;
        }
        if (VisibleSpecs.isDeferredOpacityFlushActive()) {
            VisibleSpecs.scheduleDeferredReplayNameplateStack(renderer, entity, x, y, z);
            return true;
        }

        return renderForcedStackNow(renderer, entity, x, y, z);
    }

    public static boolean renderForcedStackFromPlayerLayer(RendererLivingEntity renderer, EntityLivingBase entity,
            double x, double y, double z) {
        int savedSkip = skipNextTailEntityId;
        skipNextTailEntityId = Integer.MIN_VALUE;
        boolean rendered = renderForcedStack(renderer, entity, x, y, z);
        if (rendered && entity != null) {
            skipNextTailEntityId = entity.getEntityId();
        } else {
            skipNextTailEntityId = savedSkip;
        }
        return rendered;
    }

    public static boolean renderForcedStackPostOpacity(RendererLivingEntity renderer, EntityLivingBase entity,
            double x, double y, double z) {
        if (renderer == null || !(entity instanceof EntityPlayer)
                || !shouldRenderCompleteReplayStack(entity)) {
            return false;
        }
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            return true;
        }
        return renderForcedStackNow(renderer, entity, x, y, z);
    }

    private static boolean renderForcedStackNow(RendererLivingEntity renderer, EntityLivingBase entity,
            double x, double y, double z) {
        EntityPlayer player = (EntityPlayer) entity;
        Render render = (Render) renderer;
        double rowStep = VanillaPlayerNametagLayout.lineSpacingWorldY(
                render, VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE);
        // renderLivingLabel adds entity.height + 0.5 internally; y here is the entity foot position.
        double rowY = y;

        // "Apply settings to Replays" controls replay spectators only. Alive actors still use this Weave health
        // compatibility stack, but retain their normal name/opacity semantics.
        boolean applySpectatorSettings = VisibleSpecs.isReplaySpectatorSettingsTarget(player);
        String mode = applySpectatorSettings ? VisibleSpecs.getNametagMode() : VisibleSpecs.NAMETAG_MODE_ON;
        // Replay's forced health option is for living replay actors. A replay spectator/dead player is governed by
        // Spectator Nametags and must never regain a health row from the force-health compatibility path.
        boolean showHealth = !applySpectatorSettings
                && VisibleSpecs.shouldShowReplayHealthNametag(entity);
        boolean showName = !VisibleSpecs.NAMETAG_MODE_OFF.equals(mode);

        if (showHealth) {
            draw(renderer, player, buildHealthLabel(player), x, rowY, z);
            rowY += rowStep;
        }
        if (showName) {
            drawPrimary(renderer, player, GomodPlayerNametagLabels.resolveDisplayedLabel(player), x, rowY, z);
            rowY += rowStep;

            String denicker = NickRealNametagOverlay.buildRealIgnOverlayLabel(player);
            if (denicker != null && !denicker.isEmpty()) {
                draw(renderer, player, denicker, x, rowY, z);
                rowY += rowStep;
            }
        }
        if (applySpectatorSettings && SpectatorKillerNametagOverlay.shouldShowFor(player)) {
            drawKiller(renderer, player, SpectatorKillerNametagOverlay.buildLabel(player), x, rowY, z);
        }
        return true;
    }

    /** Standing-name fallback for Weave when its RenderPlayer rewrite bypasses NametagLayerBridge. */
    public static void renderWeaveReplayExtrasAfterVanillaName(RendererLivingEntity renderer, EntityPlayer player,
            double x, double y, double z) {
        if (renderer == null || player == null || player.isSneaking() || !GomodFixes.isWeaveRuntime()
                || !VisibleSpecs.isReplayContext() || !VisibleSpecs.isApplySettingsToReplaysEnabled()
                || shouldRenderCompleteReplayStack(player)) {
            return;
        }
        Render render = (Render) renderer;
        double rowY = y + VanillaPlayerNametagLayout.lineSpacingWorldY(
                render, VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE);
        String denicker = NickRealNametagOverlay.buildRealIgnOverlayLabel(player);
        if (denicker != null && !denicker.isEmpty()) {
            if (!LunarNametagCompatibility.isHandledByLunarNametagStack(player)) {
                draw(renderer, player, denicker, x, rowY, z);
            }
            // Even when Lunar owns the visible denick row, the killer row must reserve its vertical slot.
            rowY += VanillaPlayerNametagLayout.lineSpacingWorldY(
                    render, VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE);
        }
        if (SpectatorKillerNametagOverlay.shouldShowFor(player)) {
            drawKiller(renderer, player, SpectatorKillerNametagOverlay.buildLabel(player), x, rowY, z);
        }
    }

    private static void draw(RendererLivingEntity renderer, EntityPlayer player, String label,
            double x, double y, double z) {
        draw(renderer, player, label, x, y, z, 0);
    }

    private static void drawPrimary(RendererLivingEntity renderer, EntityPlayer player, String label,
            double x, double y, double z) {
        draw(renderer, player, label, x, y, z, 1);
    }

    private static void drawKiller(RendererLivingEntity renderer, EntityPlayer player, String label,
            double x, double y, double z) {
        draw(renderer, player, label, x, y, z, 2);
    }

    private static void draw(RendererLivingEntity renderer, EntityPlayer player, String label,
            double x, double y, double z, final int labelKind) {
        if (label == null || label.isEmpty()) {
            return;
        }
        final RenderAccessor accessor = (RenderAccessor) (Object) renderer;
        final EntityPlayer target = player;
        final String text = label;
        final double drawX = x;
        final double drawY = y;
        final double drawZ = z;
        VisibleSpecs.renderNameplateWithoutOpacityScaling(new Runnable() {
            @Override
            public void run() {
                Runnable renderLabel = new Runnable() {
                    @Override
                    public void run() {
                        LunarDisguiseDisplayCompatibility.beginNametagFontScope(target);
                        try {
                            accessor.gomod$invokeRenderLivingLabel(target, text, drawX, drawY, drawZ, MAX_DISTANCE);
                        } finally {
                            LunarDisguiseDisplayCompatibility.endNametagFontScope();
                        }
                    }
                };
                if (labelKind == 1) {
                    GomodPlayerNametagLabels.renderPrimaryNameLabel(renderLabel);
                } else if (labelKind == 2) {
                    GomodPlayerNametagLabels.renderKillerLabel(renderLabel);
                } else {
                    GomodPlayerNametagLabels.renderCustomLabel(renderLabel);
                }
            }
        });
    }

    private static String buildHealthLabel(EntityLivingBase entity) {
        int health = Math.max(0, (int) Math.ceil(entity.getHealth()));
        return net.minecraft.util.EnumChatFormatting.WHITE.toString() + health
                + net.minecraft.util.EnumChatFormatting.RED + "\u2764"
                + net.minecraft.util.EnumChatFormatting.RESET;
    }
}
