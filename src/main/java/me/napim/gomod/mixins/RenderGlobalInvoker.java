package me.napim.gomod.mixins;

import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderGlobal.class)
public interface RenderGlobalInvoker {
    @Invoker("makeEntityOutlineShader")
    void gomod$makeEntityOutlineShader();
}
