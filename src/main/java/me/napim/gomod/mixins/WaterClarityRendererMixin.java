package me.napim.gomod.mixins;

import me.napim.gomod.features.WaterClarity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optional: reduces underwater fog density by intercepting vanilla's water-branch fog density call.
 * Surface "see-through" water uses {@link WaterClarityBlockDispatcherMixin} + {@link WaterClarityBlockLiquidMixin}.
 */
@Mixin(EntityRenderer.class)
public abstract class WaterClarityRendererMixin {

    @Redirect(
            method = {"setupFog", "func_78468_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;setFogDensity(F)V"),
            require = 0
    )
    private void gomod$redirectWaterFogDensity(float density, int startCoords, float partialTicks) {
        EntityLivingBase living = gomod$getUnderwaterViewEntity(partialTicks);
        if (living != null) {
            density = WaterClarity.getUnderwaterFogDensity(living);
        }
        GlStateManager.setFogDensity(density);
    }

    private static EntityLivingBase gomod$getUnderwaterViewEntity(float partialTicks) {
        try {
            if (!WaterClarity.isEnabled() || !WaterClarity.isUnderwaterFogTweakEnabled()) {
                return null;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return null;
            }
            Entity view = mc.getRenderViewEntity();
            if (!(view instanceof EntityLivingBase)) {
                return null;
            }
            EntityLivingBase living = (EntityLivingBase) view;
            boolean underwater = WaterClarity.isViewSubmergedAtPartialTicks(mc, view, partialTicks);
            return underwater ? living : null;
        } catch (Throwable t) {
            System.err.println("[gomod] WaterClarityRendererMixin: " + t.getMessage());
            return null;
        }
    }
}
