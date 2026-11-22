package me.napim.gomod.mixins;

import me.napim.gomod.features.BlocksFinder;
import me.napim.gomod.features.BlocksFinderWorldOpacityAtlasHook;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the block atlas is faded, terrain layers need blending — not only CUTOUT (grass/plants) but
 * also SOLID/TRANSLUCENT (stone/planks after {@link BlockWorldOpacityLayerMixin}).
 */
@Mixin(RenderGlobal.class)
public abstract class BlocksFinderWorldOpacityRenderGlobalMixin {

    @Inject(
            method = {"renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I",
                    "func_174977_a", "func_174982_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$worldOpacityLayerBlendHead(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass,
                                                    Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (!BlocksFinder.shouldUseTranslucentTerrainLayer()
                || !BlocksFinderWorldOpacityAtlasHook.isAtlasFaded()) {
            return;
        }
        if (blockLayerIn == EnumWorldBlockLayer.SOLID
                || blockLayerIn == EnumWorldBlockLayer.CUTOUT_MIPPED
                || blockLayerIn == EnumWorldBlockLayer.CUTOUT
                || blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.01f);
        }
    }

    @Inject(
            method = {"renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I",
                    "func_174977_a", "func_174982_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$worldOpacityLayerBlendReturn(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass,
                                                      Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (!BlocksFinder.shouldUseTranslucentTerrainLayer()
                || !BlocksFinderWorldOpacityAtlasHook.isAtlasFaded()) {
            return;
        }
        if (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
            return;
        }
        if (blockLayerIn == EnumWorldBlockLayer.SOLID
                || blockLayerIn == EnumWorldBlockLayer.CUTOUT_MIPPED
                || blockLayerIn == EnumWorldBlockLayer.CUTOUT) {
            GlStateManager.disableBlend();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        }
    }
}
