package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.IdleGuiPlayerRenderFix;
import me.napim.gomod.features.LunarUiInputState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drains accumulated mouse deltas while a GUI is open so render state stays fresh.
 * Does not call {@code Mouse.setCursorPosition} — safe for watchdog / anti-cheat.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererIdleGuiFixMixin {

    @Inject(method = { "updateCameraAndRender", "func_181560_a" }, at = @At("HEAD"), require = 0)
    private void gomod$syncMouseWhileGuiOpen(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!IdleGuiPlayerRenderFix.shouldApply(mc)) {
                return;
            }
            // GomodMenuDragLookMixin consumes LWJGL deltas on gomod screens — draining here first breaks drag-look.
            if (gomod$deferMouseDrainToDragLook(mc)) {
                return;
            }
            Mouse.getDX();
            Mouse.getDY();
            if (mc.mouseHelper != null) {
                mc.mouseHelper.mouseXYChange();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean gomod$deferMouseDrainToDragLook(Minecraft mc) {
        if (GomodMenuSettings.isMenuDragLookEnabled(mc)) {
            return true;
        }
        // The Lunar overlay uses the same drag-look handler even though it is not a Minecraft screen.
        return mc != null
                && mc.currentScreen != null
                && mc.theWorld != null
                && LunarUiInputState.isOverlayContext(mc)
                && !LunarUiInputState.isTextInputFocused()
                && GomodMenuSettings.isLunarMenuControlsEnabled()
                && GomodMenuSettings.isDragLookEnabled();
    }
}
