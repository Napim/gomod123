package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLogoColorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the Gomod-selected body color after Lunar refreshes its tab-logo cache. */
// Superseded by LunarColorCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = {"com.moonsworth.lunar.client.OIOIICIHIOORICIIHRRIHIHOOCOOOO."
                + "HCHORCCCCHCOHHHROOCIRICCCIRHHH.HHORCOHCCICCHCROHRIHHHHCCCRICO",
                "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                        + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ICOIOHIORORICCICRCIHRIRIOHOOOR"},
        remap = false
)
public abstract class LunarTabLogoCacheMixin {

    @Inject(
            method = {"COCIICOHOOCCHCHRIRHHCHORROOCOR(Lcom/lunarclient/websocket/cosmetic/v2/LoadTabLogosResponse;)V",
                    "CICOHIIRRHHIIHRROHOICOCRHHHHOO(Lcom/lunarclient/websocket/cosmetic/v2/LoadTabLogosResponse;)V"},
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$tabLogoCacheLoaded(@Coerce Object response, CallbackInfo ci) {
        GomodLogoColorSync.onTabLogoCacheLoaded(this);
    }
}
