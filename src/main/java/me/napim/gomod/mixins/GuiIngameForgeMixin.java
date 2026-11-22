package me.napim.gomod.mixins;

import me.napim.gomod.features.MountXpOverlay;
import net.minecraft.client.gui.GuiIngame;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge replaces vanilla {@link GuiIngame#renderHorseJumpBar} with {@link GuiIngameForge#renderJumpBar(int, int)}
 * and never calls {@code super.renderGameOverlay()}, so {@link GuiIngameMixin} mount hooks do not run on Forge.
 */
@Mixin(value = GuiIngameForge.class, remap = false)
public class GuiIngameForgeMixin {

    @Inject(method = "renderGameOverlay", at = @At("HEAD"))
    private void gomod$beginMountXpHudFrame(float partialTicks, CallbackInfo ci) {
        MountXpOverlay.beginHudFrame();
    }

    @Inject(method = "renderJumpBar", at = @At("RETURN"))
    private void gomod$mountXpOverlayAfterForgeJumpBar(int width, int height, CallbackInfo ci) {
        try {
            MountXpOverlay.renderAfterHud((GuiIngame) (Object) this, width, height);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in GuiIngameForgeMixin.mountXpOverlayAfterJumpBar: " + t.getMessage());
        }
    }

}
