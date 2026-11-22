package me.napim.gomod.mixins;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Render.class)
public interface RenderAccessor {
    @Invoker("renderLivingLabel")
    void gomod$invokeRenderLivingLabel(Entity entityIn, String str, double x, double y, double z, int maxDistance);

    @Invoker("renderOffsetLivingLabel")
    void gomod$invokeRenderOffsetLivingLabel(Entity entity, double x, double y, double z, String str,
            float labelScale, double distanceSq);
}
