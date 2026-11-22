package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarCosmeticAppearanceRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Catches late Minecraft render-type texture binds while a Lunar cosmetic scope is active. */
@Mixin(GlStateManager.class)
public abstract class LunarCosmeticAppearanceGlBindingMixin {
    @Inject(method = "bindTexture(I)V", at = @At("RETURN"))
    private static void gomod$cosmeticAppearance(int texture, CallbackInfo ci) {
        LunarCosmeticAppearanceRenderer.afterMinecraftTextureBind(texture);
    }
}
