package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarNametagDebugCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in payload-shape tracing at Lunar's complete PlayerCosmeticsPushV2 dispatch boundary. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.COCIICOHOOCCHCHRIRHHCHORROOCOR."
                + "RCRHRICCICICHOOCCCIRRCIRCICIOC",
        remap = false
)
public abstract class LunarCosmeticsPushDispatcherDiagnosticsMixin {
    private static final String METHOD =
            "RCRHRICCICICHOOCCCIRRCIRCICIOC(Lcom/lunarclient/websocket/"
                    + "cosmetic/v2/PlayerCosmeticsPushV2;)V";

    @Inject(method = METHOD, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$pushDispatchBegin(@Coerce Object push, CallbackInfo ci) {
        LunarNametagDebugCapture.recordV2CosmeticsPushStage("dispatch_begin", push);
    }

    @Inject(method = METHOD, at = @At("RETURN"), require = 0, remap = false)
    private void gomod$pushDispatchReturn(@Coerce Object push, CallbackInfo ci) {
        LunarNametagDebugCapture.recordV2CosmeticsPushStage("dispatch_return", push);
    }
}
