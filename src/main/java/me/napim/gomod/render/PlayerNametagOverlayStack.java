package me.napim.gomod.render;

import me.napim.gomod.features.MobTargetDisplay;
import me.napim.gomod.features.NickRealNametagOverlay;
import me.napim.gomod.features.LunarNametagCompatibility;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Shared vertical stacking for extra player nametag rows hooked via {@link NametagLayerBridge}
 * (same pattern as {@link NickRealNametagOverlay} and {@link MobTargetDisplay}).
 * <p>
 * Row {@code 1} is the first line above the vanilla main IGN plate; higher indices stack upward.
 */
public final class PlayerNametagOverlayStack {

    /** Sneaking nametag path: approximate one row when label scale is unavailable. */
    private static final double SNEAKING_FALLBACK_ROW_STEP_BLOCKS = 0.26D;

    private PlayerNametagOverlayStack() {}

    /**
     * How many mod overlay rows already sit above the main IGN line (nick, mob-target marker, etc.).
     */
    public static int countOverlayRowsAboveMainIgn(EntityPlayer player) {
        if (player == null) {
            return 0;
        }
        int rows = LunarNametagCompatibility.countNativeRowsAboveMainName();
        if (NickRealNametagOverlay.wouldShowRealIgnNametagLine(player)) {
            rows++;
        }
        if (MobTargetDisplay.wouldShowAggroNametagRow(player)) {
            rows++;
        }
        return rows;
    }

    /**
     * Foot {@code y} for a new overlay on {@code rowIndexAboveMainIgn} (1 = first row above main IGN).
     */
    public static double footYForRowAboveMainIgn(Render render, double footYFromBridge, float labelScale,
            AbstractClientPlayer player, double distanceSq, int rowIndexAboveMainIgn) {
        double mainFootY = VanillaPlayerNametagLayout.resolveMainNameFootYFromBridge(
                render, footYFromBridge, labelScale, player, distanceSq);
        return VanillaPlayerNametagLayout.footYAfterRowsAboveMainName(
                render, mainFootY, labelScale, Math.max(1, rowIndexAboveMainIgn));
    }

    /** Foot {@code y} one row above the current top overlay (nick / mob-target), or above main IGN if none. */
    public static double footYOneRowAboveTopOverlay(Render render, double footYFromBridge, float labelScale,
            AbstractClientPlayer player, double distanceSq) {
        return footYForRowAboveMainIgn(render, footYFromBridge, labelScale, player, distanceSq,
                countOverlayRowsAboveMainIgn(player) + 1);
    }

    /**
     * Sneaking players: vanilla never calls {@code renderOffsetLivingLabel}; base {@code y} is the sneaking
     * nameplate foot from {@code renderName}.
     */
    public static double sneakingFootYForRowAboveMain(Render render, double vanillaSneakingNameFootY,
            EntityPlayer player, int rowIndexAboveMainIgn) {
        double step = sneakingRowStepWorldY(render);
        return vanillaSneakingNameFootY + step * (double) Math.max(1, rowIndexAboveMainIgn);
    }

    public static double sneakingFootYOneRowAboveTopOverlay(Render render, double vanillaSneakingNameFootY,
            EntityPlayer player) {
        return sneakingFootYForRowAboveMain(render, vanillaSneakingNameFootY, player,
                countOverlayRowsAboveMainIgn(player) + 1);
    }

    private static double sneakingRowStepWorldY(Render render) {
        if (render != null) {
            return VanillaPlayerNametagLayout.lineSpacingWorldY(render, VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE);
        }
        return SNEAKING_FALLBACK_ROW_STEP_BLOCKS;
    }
}
