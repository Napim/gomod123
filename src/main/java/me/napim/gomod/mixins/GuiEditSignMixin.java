package me.napim.gomod.mixins;

import me.napim.gomod.gui.DisguiseSignInputManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.tileentity.TileEntitySign;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiEditSign.class)
public class GuiEditSignMixin {
    @Shadow
    private TileEntitySign tileSign;

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void gomod$markSignSubmitted(GuiButton button, CallbackInfo ci) {
        if (!DisguiseSignInputManager.handles(tileSign)) {
            return;
        }
        if (button != null && button.id == 0) {
            boolean submit = !Keyboard.isKeyDown(Keyboard.KEY_ESCAPE);
            DisguiseSignInputManager.finish(tileSign, submit);
            ci.cancel();
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    private void gomod$submitOnEnter(char typedChar, int keyCode, CallbackInfo ci) {
        if (!DisguiseSignInputManager.handles(tileSign)) {
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            DisguiseSignInputManager.finish(tileSign, true);
            ci.cancel();
        }
    }

    @Inject(method = "onGuiClosed", at = @At("HEAD"), cancellable = true)
    private void gomod$handleCustomSignClose(CallbackInfo ci) {
        if (!DisguiseSignInputManager.handles(tileSign)) {
            return;
        }
        DisguiseSignInputManager.finish(tileSign, false);
        ci.cancel();
    }
}
