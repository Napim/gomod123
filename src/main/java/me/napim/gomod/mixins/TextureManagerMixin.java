package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.GoparkyBlitzReactions;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @ModifyVariable(method = "bindTexture", at = @At("HEAD"), argsOnly = true, require = 0)
    private ResourceLocation gomod$replaceBoundSkinTexture(ResourceLocation original) {
        ResourceLocation replaced = LocalDisguise.getReplacementBoundTexture(original);
        return GoparkyBlitzReactions.getHitTintedTexture(replaced);
    }
}
