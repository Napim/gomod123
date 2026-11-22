package me.napim.gomod.mixins;

import me.napim.gomod.features.ManualGomodCapeRenderer;
import me.napim.gomod.features.DebugHiddenPlayers;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderPlayer.class)
public class RenderPlayerGomodCapeMixin {
    /**
     * The direct RenderPlayer guard covers Replay Mod render/export paths which
     * bypass RenderManager#doRenderEntity. It runs before opening the cape scope
     * so cancelling cannot leave ManualGomodCapeRenderer in an unbalanced state.
     */
    @Inject(method = {"doRender", "func_76986_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$beginLocalCapeFallbackScope(AbstractClientPlayer entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = RenderProfiler.begin("GomodPlayerHooks.renderPlayerHead");
        try {
        if (DebugHiddenPlayers.shouldHide(entity) || BlitzPregamePlayerToggle.shouldHide(entity)) {
            ci.cancel();
            return;
        }
        ManualGomodCapeRenderer.beginPlayerRender(entity);
        me.napim.gomod.features.LunarRemoteDisguiseCosmetics.beginPlayerRender(entity);
        } finally {
            RenderProfiler.end("GomodPlayerHooks.renderPlayerHead", profile);
        }
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderLocalCapeFallback(AbstractClientPlayer entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = RenderProfiler.begin("GomodPlayerHooks.renderPlayerTail");
        try {
            // The old tail fallback could run after RenderPlayer had already popped the entity matrix,
            // drawing the gomod cape backward/down by the player's feet. The renderLayers pass is the
            // safe place for gomod's direct cape render; keep this TAIL hook only to close the scope.
        } finally {
            me.napim.gomod.features.LunarRemoteDisguiseCosmetics.endPlayerRender();
            ManualGomodCapeRenderer.endPlayerRender(entity);
            RenderProfiler.end("GomodPlayerHooks.renderPlayerTail", profile);
        }
    }
}
