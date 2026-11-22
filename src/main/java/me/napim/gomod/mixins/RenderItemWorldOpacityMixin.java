package me.napim.gomod.mixins;

import me.napim.gomod.features.BlocksFinderWorldOpacityAtlasHook;
import me.napim.gomod.features.SelfOpacity;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory / hotbar item icons use the block atlas — restore full-opacity sprites while drawing GUI items.
 */
@Mixin(RenderItem.class)
public abstract class RenderItemWorldOpacityMixin {

    @Unique
    private int gomod$firstPersonModelOpacityScopes = 0;

    @Unique
    // Keep this a plain ThreadLocal: Lunar's transformed RenderItem class cannot load
    // anonymous helper classes merged from mixins in this renderer.
    private static final ThreadLocal<Boolean> GOMOD_INSIDE_EFFECT_GUI = new ThreadLocal<Boolean>();

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resources/model/IBakedModel;)V",
            at = @At("HEAD"), require = 0)
    private void gomod$worldOpacityModelHead(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        BlocksFinderWorldOpacityAtlasHook.beginItemGuiRender(stack, model);
    }

    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resources/model/IBakedModel;)V",
            at = @At("RETURN"), require = 0)
    private void gomod$worldOpacityModelReturn(ItemStack stack, IBakedModel model, CallbackInfo ci) {
        BlocksFinderWorldOpacityAtlasHook.endItemGuiRender();
    }

    @Inject(method = "renderItemModelTransform", at = @At("HEAD"), require = 0)
    private void gomod$worldOpacityModelTransformHead(ItemStack stack, IBakedModel model,
                                                      ItemCameraTransforms.TransformType cameraTransformType,
                                                      CallbackInfo ci) {
        BlocksFinderWorldOpacityAtlasHook.beginItemGuiRender(stack, model);
        boolean itemOpacityScopeOpened = false;
        if (SelfOpacity.isFirstPersonArmActive()) {
            itemOpacityScopeOpened = SelfOpacity.beginFirstPersonHeldItemOpacity();
            if (itemOpacityScopeOpened) gomod$firstPersonModelOpacityScopes++;
        }
        if ((itemOpacityScopeOpened || cameraTransformType == ItemCameraTransforms.TransformType.FIRST_PERSON)
                && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_MODEL_ENTRY)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_MODEL_ENTRY,
                    "RenderItem.renderItemModelTransform:HEAD", stack,
                    "transform=" + cameraTransformType + " model="
                            + (model == null ? "null" : model.getClass().getName())
                            + " arm=" + SelfOpacity.isFirstPersonArmActive()
                            + " itemScopeOpened=" + itemOpacityScopeOpened);
        }
    }

    @Inject(method = "renderItemModelTransform", at = @At("RETURN"), require = 0)
    private void gomod$worldOpacityModelTransformReturn(ItemStack stack, IBakedModel model,
                                                        ItemCameraTransforms.TransformType cameraTransformType,
                                                        CallbackInfo ci) {
        boolean itemOpacityScopeActive = gomod$firstPersonModelOpacityScopes > 0;
        if ((itemOpacityScopeActive || cameraTransformType == ItemCameraTransforms.TransformType.FIRST_PERSON)
                && SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_MODEL_RETURN)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_MODEL_RETURN,
                    "RenderItem.renderItemModelTransform:RETURN", stack,
                    "transform=" + cameraTransformType + " itemScopeActive=" + itemOpacityScopeActive);
        }
        if (itemOpacityScopeActive) {
            gomod$firstPersonModelOpacityScopes--;
            SelfOpacity.endFirstPersonHeldItemOpacity();
        }
        BlocksFinderWorldOpacityAtlasHook.endItemGuiRender();
    }

    @Inject(
            method = {
                    "renderItemAndEffectIntoGUI(Lnet/minecraft/item/ItemStack;II)V",
                    "func_180450_b", "func_184391_a"
            },
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$worldOpacityEffectGuiHead(ItemStack stack, int x, int y, CallbackInfo ci) {
        GOMOD_INSIDE_EFFECT_GUI.set(Boolean.TRUE);
        BlocksFinderWorldOpacityAtlasHook.beginItemGuiRender(stack);
    }

    @Inject(
            method = {
                    "renderItemAndEffectIntoGUI(Lnet/minecraft/item/ItemStack;II)V",
                    "func_180450_b", "func_184391_a"
            },
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$worldOpacityEffectGuiReturn(ItemStack stack, int x, int y, CallbackInfo ci) {
        BlocksFinderWorldOpacityAtlasHook.endItemGuiRender();
        GOMOD_INSIDE_EFFECT_GUI.set(Boolean.FALSE);
    }

    @Inject(
            method = {"renderItemIntoGUI(Lnet/minecraft/item/ItemStack;II)V", "func_175042_a"},
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$worldOpacityItemGuiHead(ItemStack stack, int x, int y, CallbackInfo ci) {
        if (!Boolean.TRUE.equals(GOMOD_INSIDE_EFFECT_GUI.get())) {
            BlocksFinderWorldOpacityAtlasHook.beginItemGuiRender(stack);
        }
    }

    @Inject(
            method = {"renderItemIntoGUI(Lnet/minecraft/item/ItemStack;II)V", "func_175042_a"},
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$worldOpacityItemGuiReturn(ItemStack stack, int x, int y, CallbackInfo ci) {
        if (!Boolean.TRUE.equals(GOMOD_INSIDE_EFFECT_GUI.get())) {
            BlocksFinderWorldOpacityAtlasHook.endItemGuiRender();
        }
    }
}
