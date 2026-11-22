package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodBlockOverlay;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Forge event cancels vanilla's selection box on Forge, but Lunar/Weave does not dispatch that event.
 * Cancel the vanilla block outline at its renderer entry there so the custom block overlay is the only outline.
 */
@Mixin(RenderGlobal.class)
public class RenderGlobalBlockHighlightMixin {

    @Inject(method = {"drawSelectionBox", "func_72731_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$cancelVanillaBlockSelection(EntityPlayer player, MovingObjectPosition mouseOver,
                                                    int execute, float partialTicks, CallbackInfo ci) {
        if (!GomodBlockOverlay.isEnabled() || mouseOver == null
                || mouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        ci.cancel();
    }
}
