package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarUiInputState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disabled alongside {@link LunarTextInputFocusMixin} (WebOSR focus JNI crash guard); intentionally
 * omitted from {@code gomod.weave.mixins.json} and asserted absent by {@code LunarRouteCompatibilityCheck}.
 */
@Deprecated
@Pseudo
@Mixin(targets = "com.moonsworth.webosr.NativeHandle", remap = false)
public abstract class LunarNativeHandleFocusCleanupMixin {
    @Inject(method = "close()V", at = @At("HEAD"), require = 0, remap = false)
    private void gomod$untrackInputFocus(CallbackInfo ci) {
        if ((Object) this instanceof LunarUiInputState.TextInputFocusSource) {
            LunarUiInputState.unregisterTextInputFocusSource(
                    (LunarUiInputState.TextInputFocusSource) (Object) this);
        }
    }
}