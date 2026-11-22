package me.napim.gomod.mixins;

import me.napim.gomod.features.ChatMovement;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.LunarUiInputState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame drag-to-look: injects into EntityRenderer.updateCameraAndRender so that
 * mouse deltas are consumed every rendered frame, giving smooth rotation.
 * getDX()/getDY() return accumulated delta since last call; when a GUI screen is open
 * vanilla does not call mouseHelper.mouseXYChange(), so we consume them here safely.
 */
@Mixin(value = EntityRenderer.class, priority = 1100)
public class GomodMenuDragLookMixin {

    private boolean gomod$mouseWasDown = false;
    private boolean gomod$dragLookActive = false;

    @Inject(method = {"updateCameraAndRender", "func_181560_a"}, at = @At("HEAD"), require = 0)
    private void gomod$dragLookPerFrame(float partialTicks, long nanoTime, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                gomod$mouseWasDown = false;
                gomod$dragLookActive = false;
                return;
            }
            // Lunar's Locker is a WebOSR overlay rather than a Minecraft GuiScreen. Use the
            // version-matched WebOSR callbacks for its state; mouse-grab state alone is not a
            // reliable indicator on UmbraWeave.
            boolean lunarOverlay = mc.theWorld != null && LunarUiInputState.isOverlayContext(mc);
            // On UmbraWeave, Lunar's WebOSR UI can be active while currentScreen is null.
            // In ordinary gameplay the mouse is grabbed; requiring it to be released for
            // this null-screen path prevents a stale overlay callback from enabling drag-look.
            boolean lunarOverlayHasFocus = mc.currentScreen != null || !Mouse.isGrabbed();
            boolean chatDrag = mc.currentScreen instanceof GuiChat
                    && ChatMovement.isDragLookEnabled();
            boolean gomodMenuDrag = GomodMenuSettings.isMenuDragLookEnabled(mc);
            boolean lunarMenuDrag = lunarOverlay
                    && lunarOverlayHasFocus
                    && !LunarUiInputState.isTextInputFocused()
                    && GomodMenuSettings.isLunarMenuControlsEnabled()
                    && GomodMenuSettings.isDragLookEnabled();
            if (!chatDrag && !gomodMenuDrag && !lunarMenuDrag) {
                gomod$mouseWasDown = false;
                gomod$dragLookActive = false;
                return;
            }

            boolean mouseDown = Mouse.isButtonDown(1); // right-click drag

            // Lunar renders its Locker as a WebOSR overlay while Minecraft still owns the camera.
            // Leave the mouse deltas alone when RMB is up so vanilla mouse-look remains functional.
            if (lunarMenuDrag && !mouseDown) {
                gomod$mouseWasDown = false;
                gomod$dragLookActive = false;
                return;
            }

            // Rising edge: right-drag look is valid anywhere in a gomod menu. Vanilla GuiButton only reacts to
            // left-click, so excluding button rectangles made large parts of button-heavy menus feel randomly dead.
            if (mouseDown && !gomod$mouseWasDown) {
                gomod$dragLookActive = true;
                // Flush any accumulated delta so the drag starts without a jump.
                Mouse.getDX();
                Mouse.getDY();
            }

            if (!mouseDown) {
                gomod$dragLookActive = false;
            }

            // Always consume per-frame deltas to prevent accumulation between drags.
            int dx = Mouse.getDX();
            int dy = Mouse.getDY();

            if (gomod$dragLookActive && mouseDown && (dx != 0 || dy != 0)) {
                float sens = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
                float scale = sens * sens * sens * 8.0F;
                mc.thePlayer.rotationYaw   += dx * scale * 0.15F;
                // LWJGL Y increases upward; moving mouse up → dy positive → pitch decreases (look up).
                mc.thePlayer.rotationPitch -= dy * scale * 0.15F;
                mc.thePlayer.rotationPitch  = Math.max(-90.0F, Math.min(90.0F, mc.thePlayer.rotationPitch));
            }

            gomod$mouseWasDown = mouseDown;
        } catch (Throwable ignored) {
        }
    }
}
