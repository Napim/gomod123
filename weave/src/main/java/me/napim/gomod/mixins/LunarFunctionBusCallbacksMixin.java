package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLunarUiCallbacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers Gomod's Locker callbacks independently of Lunar's changing callback-owner classes. */
@Pseudo
@Mixin(targets = "com.moonsworth.webosr.javascript.FunctionBus", remap = false)
public abstract class LunarFunctionBusCallbacksMixin {
    @Unique
    private boolean gomod$registeringStableLockerCallbacks;
    @Unique
    private boolean gomod$stableLockerCallbacksRegistered;

    @Shadow
    public abstract void subscribe(Object callbacks);

    @Inject(method = "subscribe(Ljava/lang/Object;)V", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$registerStableLockerCallbacks(Object callbacks, CallbackInfo ci) {
        if (!gomod$registeringStableLockerCallbacks && !gomod$stableLockerCallbacksRegistered) {
            gomod$registeringStableLockerCallbacks = true;
            try {
                subscribe(GomodLunarUiCallbacks.class);
                gomod$stableLockerCallbacksRegistered = true;
            } catch (RuntimeException error) {
                System.err.println("[gomod] Could not register Locker UI callbacks: "
                        + error.getClass().getSimpleName());
            } finally {
                gomod$registeringStableLockerCallbacks = false;
            }
        }
    }
}
