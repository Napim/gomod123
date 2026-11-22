package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarFullDisguise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures complete Lunar 2.22.43 cosmetics pushes so /disguisefull can use the live profile. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IOCIHHIRIROCOIROOCORCCIOOIIIRR."
                + "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH",
        remap = false
)
public abstract class LunarFullDisguisePushCapture2243Mixin {
    private static final String PLAYER_COSMETICS_PUSH =
            "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH(" +
                    "Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;)V";

    @Inject(method = PLAYER_COSMETICS_PUSH, at = @At("RETURN"), require = 0, remap = false)
    private void gomod$captureFullDisguiseProfile(@Coerce Object push, CallbackInfo ci) {
        LunarFullDisguise.onCosmeticsPush(push);
    }
}
