package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarCosmeticAppearanceRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class LunarCosmeticAppearanceTextureMixin {
    @Inject(method="bindTexture",at=@At("RETURN"))
    private void gomod$cosmeticAppearance(ResourceLocation resource,CallbackInfo ci) {
        LunarCosmeticAppearanceRenderer.afterTextureBind();
    }
    @Inject(method="onResourceManagerReload",at=@At("HEAD"))
    private void gomod$clearCosmeticAppearance(IResourceManager resources,CallbackInfo ci) {
        LunarCosmeticAppearanceRenderer.clearTextures();
    }
}
