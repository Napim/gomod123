package me.napim.gomod.mixins;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PolyBlur Phosphor runs {@code matrixMode(GL_TEXTURE)} around {@code ShaderGroup#loadShaderGroup} inside
 * {@code EntityRenderer#updateCameraAndRender} and does not restore {@code GL_MODELVIEW}. That leaks into the
 * next frame's world rendering (e.g. deferred self opacity at translucent tail). Reset at end of the method.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererPostEffectsGlCompatMixin {

    @Inject(method = {"updateCameraAndRender", "func_181560_a"}, at = @At("TAIL"), require = 0)
    private void gomod$resetMatrixModeAfterCameraAndPostChain(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        } catch (Throwable ignored) {
        }
    }
}
