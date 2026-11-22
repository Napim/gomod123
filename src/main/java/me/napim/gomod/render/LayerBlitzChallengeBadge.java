package me.napim.gomod.render;

import me.napim.gomod.features.BlitzChallengeBadgeEquip;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

/**
 * Renders the equipped Blitz challenge badge on the local player (and GUI preview clone).
 */
public final class LayerBlitzChallengeBadge implements LayerRenderer<AbstractClientPlayer> {

    private final RenderPlayer playerRenderer;

    public LayerBlitzChallengeBadge(RenderPlayer playerRenderer) {
        this.playerRenderer = playerRenderer;
    }

    @Override
    public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                              float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        BlitzChallengeBadgeEquip.renderOnPlayer(playerRenderer, player, scale);
    }

    @Override
    public boolean shouldCombineTextures() {
        return false;
    }
}
