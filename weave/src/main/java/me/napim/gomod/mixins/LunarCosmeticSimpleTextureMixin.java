package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarCosmeticAppearanceRenderer;
import net.minecraft.client.renderer.texture.SimpleTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleTexture.class)
public abstract class LunarCosmeticSimpleTextureMixin {
    @Inject(method = "loadTexture", at = @At("RETURN"))
    private void gomod$appearanceSourceReloaded(CallbackInfo ci) {
        LunarCosmeticAppearanceRenderer.onSimpleTextureLoaded((SimpleTexture) (Object) this);
    }
}
