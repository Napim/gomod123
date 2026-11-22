package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarDisguiseCosmeticVisibility;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scopes only the local first-person arm draw. Lunar 1.8 attaches suit geometry to ModelRenderer;
 * the obsolete 1.7 lunar$onRenderHand callback is not present in the running 1.8 renderer.
 */
@Mixin(value = RenderPlayer.class, priority = 1100)
public abstract class WeaveLunarFirstPersonHandCallbackMixin {

    @Inject(method = {"renderRightArm", "renderLeftArm"}, at = @At("HEAD"), require = 1)
    private void gomod$beginHiddenDisguiseArmScope(AbstractClientPlayer player, CallbackInfo ci) {
        LunarDisguiseCosmeticVisibility.beginFirstPersonArmRender(player);
        me.napim.gomod.features.LunarArmDebugCapture.recordArm(player, this);
    }

    @Inject(method = {"renderRightArm", "renderLeftArm"}, at = @At("RETURN"), require = 1)
    private void gomod$endHiddenDisguiseArmScope(AbstractClientPlayer player, CallbackInfo ci) {
        LunarDisguiseCosmeticVisibility.endFirstPersonArmRender();
    }
}
