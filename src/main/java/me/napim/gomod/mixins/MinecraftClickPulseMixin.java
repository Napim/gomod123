package me.napim.gomod.mixins;

import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.FakeBreak;
import me.napim.gomod.features.ForcePigManager;
import me.napim.gomod.features.GoparkyBlitzReactions;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lunar Client often leaves {@code KeyBinding} / LWJGL {@code Mouse} stale; vanilla still calls these when a click is processed.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClickPulseMixin {

    @Inject(method = {"clickMouse", "func_147116_af"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$forcePigLeftClickPulse(CallbackInfo ci) {
        try {
            Minecraft mc = (Minecraft) (Object) this;
            // Add the command fallback while preserving vanilla's compass swing/attack.
            me.napim.gomod.features.UnbotheredTaunt.handleLeftClick(mc);
            if (GoparkyBlitzReactions.interceptGoparkyHit(mc)) {
                ci.cancel();
                return;
            }
            me.napim.gomod.features.SpectatorRelay.onLeftClick(mc);
            ForcePigManager.signalVanillaLeftClick();
            // Lunar: PlayerControllerMP may not see repeat attacks — match every vanilla clickMouse
            ForcePigManager.onLeftClickWorldInteractionAttempt();
            if (ChestRouteManager.tryLeftClickCheckpointInteraction(mc)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sneak+RMB polling + optional client-only far checkpoint (does NOT change vanilla block reach).
     */
    @Inject(method = {"rightClickMouse", "func_147121_ag"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$chestEditRightClickPulse(CallbackInfo ci) {
        try {
            ChestRouteManager.signalVanillaRightClick();
            Minecraft mc = (Minecraft) (Object) this;
            if (BlitzPregamePlayerToggle.tryHandleRightClick(mc)) {
                ci.cancel();
                return;
            }
            if (FakeBreak.tryRestoreTargetedBrokenBlock(mc)) {
                ci.cancel();
                return;
            }
            if (ChestRouteManager.tryLongRayCheckpointOnRightClick(mc)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
