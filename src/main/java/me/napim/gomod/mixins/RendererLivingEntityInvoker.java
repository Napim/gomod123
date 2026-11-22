package me.napim.gomod.mixins;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RendererLivingEntity.class)
public interface RendererLivingEntityInvoker {
    @Invoker("renderModel")
    void gomod$invokeRenderModel(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scale);

    @Invoker("renderLivingAt")
    void gomod$invokeRenderLivingAt(EntityLivingBase entity, double x, double y, double z);

    @Invoker("renderName")
    void gomod$invokeRenderName(EntityLivingBase entity, double x, double y, double z);
}
