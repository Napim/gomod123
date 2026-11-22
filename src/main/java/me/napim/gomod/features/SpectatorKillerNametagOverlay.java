package me.napim.gomod.features;



import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.NametagLayerBridge;
import me.napim.gomod.render.PlayerNametagOverlayStack;
import net.minecraft.client.Minecraft;

import net.minecraft.client.entity.AbstractClientPlayer;

import net.minecraft.client.renderer.entity.Render;

import net.minecraft.entity.player.EntityPlayer;

import net.minecraft.util.EnumChatFormatting;



/**

 * Extra nametag row above the spectator's highest nametag line: who killed them (KillCounter chat tracking).

 * Placement uses {@link PlayerNametagOverlayStack} (same stack as {@link NickRealNametagOverlay} /

 * {@link MobTargetDisplay} via {@link NametagLayerBridge}).

 */

public final class SpectatorKillerNametagOverlay {



    private static final int RENDER_DISTANCE = 64;



    static {

        NametagLayerBridge.register(SpectatorKillerNametagOverlay::onAfterVanillaPlayerNameplate);

    }



    private SpectatorKillerNametagOverlay() {}



    public static void ensureHooksLoaded() {

    }



    public static boolean isEnabled() {

        return ConfigManager.isVisibleSpecsKillerNametagEnabled();

    }



    public static void setEnabled(boolean enabled) {

        ConfigManager.setVisibleSpecsKillerNametagEnabled(enabled);

    }



    public static boolean shouldUseMainIgnSlotForKiller(EntityPlayer player) {
        return VisibleSpecs.NAMETAG_MODE_OFF.equals(VisibleSpecs.getNametagMode()) && shouldShowFor(player);
    }

    public static boolean shouldShowFor(EntityPlayer player) {
        if (!isEnabled() || player == null) {
            return false;
        }
        if (MountPreviewHelper.isVisibleSpecsPreviewPlayer(player)) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || player == mc.thePlayer) {
            return false;
        }
        if (VisibleSpecs.isReplayContext()) {
            if (!VisibleSpecs.isApplySettingsToReplaysEnabled()) {
                return false;
            }
            // The relation is historical and intentionally survives seeking. Only display it while the victim is
            // actually a spectator at the current replay timestamp.
            if (!VisibleSpecs.isReplaySpectatorSettingsTarget(player)) {
                return false;
            }
            String replayKiller = KillCounter.getKillerForVictim(player.getName());
            return replayKiller != null && !replayKiller.trim().isEmpty();
        }
        if (!VisibleSpecs.shouldShowPlayerRemote(player)) {
            return false;
        }
        if (!KillCounter.isPlayerDead(player.getName())) {
            return false;
        }
        String killer = KillCounter.getKillerForVictim(player.getName());
        return killer != null && !killer.trim().isEmpty();
    }



    public static String buildLabel(EntityPlayer player) {
        String killer;
        if (MountPreviewHelper.isVisibleSpecsPreviewPlayer(player)) {
            killer = VisibleSpecs.PREVIEW_KILLER;
        } else {
            killer = KillCounter.getKillerForVictim(player.getName());
        }
        if (killer == null || killer.trim().isEmpty()) {
            return null;
        }
        String killerTrimmed = killer.trim();
        EnumChatFormatting killerColor = KillCounter.isPlayerDead(killerTrimmed)
                ? EnumChatFormatting.BLUE
                : EnumChatFormatting.RED;
        return EnumChatFormatting.GRAY + "Killer: "
                + killerColor + killerTrimmed + EnumChatFormatting.RESET;

    }



    private static void onAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
            double x, double footYOfMainNameLine, double z, float labelScale, double distanceSq,
            String renderedLabel) {
        try {
            // Lunar's native event hook inserts this row directly into its nametag stack.
            if (GomodFixes.isWeaveRuntime()
                    && LunarNametagCompatibility.isKillerHandledByLunarNametagStack(player)) {
                return;
            }
            if (!shouldShowFor(player)) {
                return;
            }
            String label = buildLabel(player);
            if (label == null) {
                return;
            }
            double footY = shouldUseMainIgnSlotForKiller(player)
                    ? footYOfMainNameLine
                    : PlayerNametagOverlayStack.footYOneRowAboveTopOverlay(
                            render, footYOfMainNameLine, labelScale, player, distanceSq);
            renderKillerLabel(player, render, label, x, footY, z);

        } catch (Throwable ignored) {

        }

    }



    /**

     * Sneaking players never hit {@code RenderPlayer.renderOffsetLivingLabel}, so draw from {@code renderName} TAIL.

     */

    public static void tryRenderSneakingKillerNametag(EntityPlayer player, Render render, double x, double y, double z) {

        if (!shouldShowFor(player) || render == null || !player.isSneaking()) {

            return;

        }

        if (GomodFixes.isWeaveRuntime()
                && LunarNametagCompatibility.isKillerHandledByLunarNametagStack(player)) {
            return;
        }

        String label = buildLabel(player);

        if (label == null) {

            return;

        }

        double footY = shouldUseMainIgnSlotForKiller(player)
                ? y
                : PlayerNametagOverlayStack.sneakingFootYOneRowAboveTopOverlay(render, y, player);
        final EntityPlayer target = player;
        final Render targetRender = render;
        final double drawX = x;
        final double drawY = footY;
        final double drawZ = z;
        final String text = label;
        GomodPlayerNametagLabels.renderKillerLabel(new Runnable() {
            @Override
            public void run() {
                NickRealNametagOverlay.renderSneakingStyleNametagPlate(
                        target, targetRender, drawX, drawY, drawZ, text);
            }
        });

    }

    private static void renderKillerLabel(EntityPlayer player, Render render, String label,
            double x, double y, double z) {
        final RenderAccessor accessor = (RenderAccessor) render;
        final EntityPlayer target = player;
        final String text = label;
        final double drawX = x;
        final double drawY = y;
        final double drawZ = z;
        GomodPlayerNametagLabels.renderKillerLabel(new Runnable() {
            @Override
            public void run() {
                accessor.gomod$invokeRenderLivingLabel(target, text, drawX, drawY, drawZ, RENDER_DISTANCE);
            }
        });
    }

}

