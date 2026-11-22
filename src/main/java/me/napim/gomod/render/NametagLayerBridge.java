package me.napim.gomod.render;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fires after vanilla draws the main IGN line in {@link net.minecraft.client.renderer.entity.RenderPlayer}
 * {@code renderOffsetLivingLabel}. Callbacks receive the same foot {@code y} passed into that call (after tab-health
 * step-up when close), so extra rows can use
 * {@link VanillaPlayerNametagLayout} / {@link PlayerNametagOverlayStack} so extra rows align with the vanilla
 * nameplate instead of fixed offsets.
 * <p>
 * <b>Not called</b> for the sneaking nametag path (vanilla draws that inside
 * {@link net.minecraft.client.renderer.entity.RendererLivingEntity#renderName} without calling
 * {@code RenderPlayer.renderOffsetLivingLabel}).
 */
public final class NametagLayerBridge {

    @FunctionalInterface
    public interface AfterVanillaPlayerNameplateCallback {
        /**
         * @param footYOfMainNameLine world foot {@code y} vanilla used for the main name {@code renderLivingLabel}
         * @param labelScale          {@code p_177069_9_} passed to {@code renderOffsetLivingLabel}
         * @param distanceSq          squared distance argument ({@code p_177069_10_} / vanilla local) to
         *                            {@code renderOffsetLivingLabel}
         * @param renderedLabel       formatted string vanilla just drew (disguise / ping / team colors applied)
         */
        void onAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
                double x, double footYOfMainNameLine, double z, float labelScale, double distanceSq,
                String renderedLabel);
    }

    private static final List<AfterVanillaPlayerNameplateCallback> CALLBACKS = new CopyOnWriteArrayList<>();

    private NametagLayerBridge() {}

    public static void register(AfterVanillaPlayerNameplateCallback cb) {
        if (cb != null && !CALLBACKS.contains(cb)) {
            CALLBACKS.add(cb);
        }
    }

    public static void unregister(AfterVanillaPlayerNameplateCallback cb) {
        CALLBACKS.remove(cb);
    }

    /** @noinspection unused — called from mixin */
    public static void fireAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
            double x, double footYOfMainNameLine, double z, float labelScale, double distanceSq,
            String renderedLabel) {
        String label = renderedLabel == null ? "" : renderedLabel;
        for (AfterVanillaPlayerNameplateCallback cb : CALLBACKS) {
            try {
                cb.onAfterVanillaPlayerNameplate(player, render, x, footYOfMainNameLine, z, labelScale, distanceSq, label);
            } catch (Throwable ignored) {
            }
        }
    }
}
