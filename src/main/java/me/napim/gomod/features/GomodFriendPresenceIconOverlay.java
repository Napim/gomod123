package me.napim.gomod.features;

import me.napim.gomod.render.NametagLayerBridge;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Hook for gomod online/offline icons beside friends in the world (nametag height).
 * Rendering is disabled — presence is shown in the Social dock only.
 */
public final class GomodFriendPresenceIconOverlay {

    static {
        NametagLayerBridge.register(GomodFriendPresenceIconOverlay::onAfterVanillaPlayerNameplate);
    }

    private GomodFriendPresenceIconOverlay() {}

    public static void ensureHooksLoaded() {
    }

    private static void onAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
            double x, double footY, double z, float labelScale, double distanceSq, String renderedLabel) {
        tryRender(player, x, footY, z, renderedLabel, labelScale);
    }

    public static void tryRender(EntityPlayer player, double x, double y, double z) {
        tryRender(player, x, y, z, null, 1.0F);
    }

    public static void tryRender(EntityPlayer player, double x, double y, double z, String nameLabel,
            float labelScale) {
        // Intentionally disabled — do not draw gomod presence badges on in-world nametags.
    }
}
