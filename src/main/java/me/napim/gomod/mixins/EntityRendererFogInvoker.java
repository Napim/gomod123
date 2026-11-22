package me.napim.gomod.mixins;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to Minecraft's current camera fog setup for late world labels. */
@Mixin(EntityRenderer.class)
public interface EntityRendererFogInvoker {
    @Invoker("setupFog")
    void gomod$invokeSetupFog(int startCoords, float partialTicks);
}
