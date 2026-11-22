package me.napim.gomod.render;

import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Mirrors the vertical layout decisions in {@link net.minecraft.client.renderer.entity.RenderPlayer}
 * {@code renderOffsetLivingLabel}: tab-list health row (below name in screen space) vs single-line name,
 * and the world-space step between stacked nametag rows.
 */
public final class VanillaPlayerNametagLayout {

    /** Same threshold as vanilla {@code RenderPlayer.renderOffsetLivingLabel} ({@code 10} blocks squared). */
    public static final double VANILLA_TAB_HEALTH_NAMETAG_DISTANCE_SQ = 100.0;

    /** Same factor vanilla uses between the health row and the IGN row ({@code FontRenderer.FONT_HEIGHT * 1.15f * scale}). */
    public static final float VANILLA_NAMETAG_LINE_GAP_FACTOR = 1.15F;

    /** Scale passed by vanilla {@code RenderPlayer} for ordinary world nameplates. */
    public static final float VANILLA_WORLD_LABEL_SCALE = 0.02666667F;

    private VanillaPlayerNametagLayout() {}

    /**
     * World-space delta along {@code y} between two adjacent vanilla nametag rows for the given label scale
     * (the {@code p_177069_9_} argument to {@code renderOffsetLivingLabel}).
     */
    public static double lineSpacingWorldY(Render render, float labelScale) {
        if (render == null) {
            return 0.0;
        }
        int fh = render.getFontRendererFromRenderManager().FONT_HEIGHT;
        return (double) fh * (double) VANILLA_NAMETAG_LINE_GAP_FACTOR * (double) labelScale;
    }

    /**
     * True when vanilla draws the extra {@code renderLivingLabel} pass for tab-list health before the main name
     * (same checks as {@code RenderPlayer.renderOffsetLivingLabel}, including its sneaking suppression).
     *
     * @param distanceSq squared horizontal distance passed into {@code renderOffsetLivingLabel} (vanilla uses
     *                   {@link #VANILLA_TAB_HEALTH_NAMETAG_DISTANCE_SQ} as the cutoff).
     */
    public static boolean wouldRenderTabHealthNametagRow(AbstractClientPlayer player, double distanceSq) {
        // Vanilla hides the tab-health line while the player is sneaking. Callers use this predicate for
        // both drawing and undoing vanilla's extra-row name offset, so keep the layout in sync with that rule.
        if (player == null || player.isSneaking() || distanceSq >= VANILLA_TAB_HEALTH_NAMETAG_DISTANCE_SQ) {
            return false;
        }
        try {
            if (player.worldObj == null) {
                return false;
            }
            Scoreboard sb = player.worldObj.getScoreboard();
            if (sb == null) {
                return false;
            }
            ScoreObjective obj = sb.getObjectiveInDisplaySlot(2);
            if (obj == null) {
                return false;
            }
            Score score = sb.getValueFromObjective(player.getName(), obj);
            return score != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Foot {@code y} one vanilla row <em>above</em> the main name line (real-IGN overlay, extra decorations, etc.).
     */
    public static double footYOneRowAboveMainName(Render render, double mainNameFootY, float labelScale) {
        return mainNameFootY + lineSpacingWorldY(render, labelScale);
    }

    /**
     * Foot {@code y} for the main IGN after vanilla's close-range tab-health step-up (hearts row drawn or skipped).
     *
     * @param incomingFootY {@code y} from {@code RenderPlayer.renderOffsetLivingLabel} before that step-up
     */
    public static double footYAfterVanillaTabHealthStep(Render render, AbstractClientPlayer player,
            double incomingFootY, float labelScale, double distanceSq) {
        if (!wouldRenderTabHealthNametagRow(player, distanceSq)) {
            return incomingFootY;
        }
        return incomingFootY + lineSpacingWorldY(render, labelScale);
    }

    /**
     * Foot {@code y} where the main IGN line is (or should be) drawn — includes {@code ONLY_IGN} hearts-slot placement.
     */
    public static double resolveMainIgnFootY(Render render, AbstractClientPlayer player, double incomingFootY,
            float labelScale, double distanceSq) {
        double afterTabHealth = footYAfterVanillaTabHealthStep(render, player, incomingFootY, labelScale, distanceSq);
        return VisibleSpecs.adjustMainNameFootYForOnlyIgnMode(render, player, afterTabHealth, labelScale, distanceSq);
    }

    /**
     * Foot {@code y} for the main IGN line from {@link NametagLayerBridge} (already resolved via
     * {@link #resolveMainIgnFootY}).
     */
    public static double resolveMainNameFootYFromBridge(Render render, double footYFromBridge, float labelScale,
            AbstractClientPlayer player, double distanceSq) {
        return footYFromBridge;
    }

    /** Foot {@code y} after stepping {@code rowsAboveMainName} vanilla rows above the main IGN line. */
    public static double footYAfterRowsAboveMainName(Render render, double mainNameFootY, float labelScale,
            int rowsAboveMainName) {
        double y = mainNameFootY;
        for (int i = 0; i < rowsAboveMainName; i++) {
            y = footYOneRowAboveMainName(render, y, labelScale);
        }
        return y;
    }
}
