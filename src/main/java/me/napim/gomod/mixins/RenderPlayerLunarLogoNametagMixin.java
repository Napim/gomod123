package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarNametagLogoOverlay;
import me.napim.gomod.features.LunarNametagDebugCapture;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.GomodPlayerNametagLabels;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Weave-only bridge for drawing Lunar's TAB logo beside the player's world nametag. */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerLunarLogoNametagMixin {

    @Unique
    private double gomod$lunarLogoNameFootY;
    @Unique
    private static boolean gomod$nametagHookLogged;

    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$captureLunarLogoNameY(AbstractClientPlayer player, double x, double y, double z,
            String label, float labelScale, double distanceSq, CallbackInfo ci) {
        if (!RenderProfiler.shouldBypassPlayerHooks()) {
            LunarNametagDebugCapture.recordRendererHook();
            gomod$lunarLogoNameFootY = y;
            if (!gomod$nametagHookLogged) {
                gomod$nametagHookLogged = true;
                LunarConnectionDiagnostics.record("nametag.mixin_hit", "renderPlayerLabel=true");
            }
        }
    }

    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At("TAIL"),
            require = 0
    )
    private void gomod$drawLunarLogoNametag(AbstractClientPlayer player, double x, double y, double z,
            String label, float labelScale, double distanceSq, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) {
            return;
        }
        if (!GomodPlayerNametagLabels.isPrimaryPlayerNameLabel(player, label)) {
            return;
        }
        LunarNametagDebugCapture.recordPrimaryNameCandidate();
        LunarNametagLogoOverlay.render(player, (Render) (Object) this,
                x, gomod$lunarLogoNameFootY, z, label);
    }
}
