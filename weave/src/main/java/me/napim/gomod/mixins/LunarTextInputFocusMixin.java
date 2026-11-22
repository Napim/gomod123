package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarUiInputState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disabled: WebOSR focus JNI crashed on 2026-10-02; this mixin is not registered. */
// Disabled due to WebOSR focus JNI crash; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.webosr.wrappers.Browser",
        remap = false
)
public abstract class LunarTextInputFocusMixin implements LunarUiInputState.TextInputFocusSource {
    @Inject(method = "<init>(J)V", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$trackInputFocus(long handle, CallbackInfo ci) {
        if (handle != 0L) {
            LunarUiInputState.registerTextInputFocusSource(this);
        }
    }

    @Override
    public boolean gomod$hasTextInputFocus() {
        // Do not reintroduce these JNI calls: invalid/native-thread-affine handles can
        // cause EXCEPTION_ACCESS_VIOLATION, which a Java catch cannot recover from.
        return false;
    }
}
