package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarFullDisguise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Lunar 2.23.1's V2 outfit store used by its player-cosmetics renderer. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.IICCIHIHCROIRHOCOIICHHCCIOHORC",
        remap = false
)
public abstract class LunarFullDisguiseOutfitManager2231Mixin {
    private static final String PLAYER_COSMETICS_PUSH =
            "CICOHIIRRHHIIHRROHOICOCRHHHHOO(" +
                    "Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;)V";

    @Inject(method = PLAYER_COSMETICS_PUSH, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$captureFullDisguiseOutfitManager(@Coerce Object push, CallbackInfo ci) {
        LunarFullDisguise.onOutfitManagerAvailable(this);
    }
}
