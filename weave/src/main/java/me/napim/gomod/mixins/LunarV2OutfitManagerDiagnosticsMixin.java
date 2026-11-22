package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarNametagDebugCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in payload-shape tracing around Lunar's native V2 outfit-map update. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OIOIICIHIOORICIIHRRIHIHOOCOOOO."
                + "HCHORCCCCHCOHHHROOCIRICCCIRHHH.CCIRHRIIOCHHIIRHROIHHOOIIHROHC",
        remap = false
)
public abstract class LunarV2OutfitManagerDiagnosticsMixin {
    private static final String METHOD =
            "COCIICOHOOCCHCHRIRHHCHORROOCOR(Lcom/lunarclient/websocket/"
                    + "cosmetic/v2/PlayerCosmeticsPushV2;)V";

    @Inject(method = METHOD, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$outfitStoreBegin(@Coerce Object push, CallbackInfo ci) {
        LunarNametagDebugCapture.recordV2CosmeticsPushStage("outfit_store_begin", push);
    }

    @Inject(method = METHOD, at = @At("RETURN"), require = 0, remap = false)
    private void gomod$outfitStoreReturn(@Coerce Object push, CallbackInfo ci) {
        LunarNametagDebugCapture.recordV2CosmeticsPushStage("outfit_store_return", push);
        me.napim.gomod.features.LunarArmDebugCapture.recordOutfitPush(push);
    }
}
