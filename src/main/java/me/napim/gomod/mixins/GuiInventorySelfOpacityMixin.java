package me.napim.gomod.mixins;

import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps the inventory mannequin directly. Lunar can rewrite or bypass RenderManager's vanilla
 * entry point, but its inventory still reaches this stable GuiInventory helper.
 */
@Mixin(GuiInventory.class)
public abstract class GuiInventorySelfOpacityMixin {

    @Inject(method = {"drawEntityOnScreen", "func_147046_a"}, at = @At("HEAD"), require = 0)
    private static void gomod$beginInventoryOpacity(int posX, int posY, int scale,
            float mouseX, float mouseY, EntityLivingBase entity, CallbackInfo ci) {
        VisibleSpecs.beginGuiInventoryEntityRender(entity);
        SelfOpacity.beginGuiInventoryEntityRender(entity);
    }

    @Inject(method = {"drawEntityOnScreen", "func_147046_a"}, at = @At("RETURN"), require = 0)
    private static void gomod$endInventoryOpacity(int posX, int posY, int scale,
            float mouseX, float mouseY, EntityLivingBase entity, CallbackInfo ci) {
        SelfOpacity.endGuiInventoryEntityRender(entity);
        VisibleSpecs.endGuiInventoryEntityRender(entity);
    }
}
