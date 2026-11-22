package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarTabLogoCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Captures the exact icon/badge width Lunar itself computes for each TAB UUID. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.CIHCIRRHROHOCORHOCOHCORHHOHCRH"
                + ".ICHRRCIICOIIRHCIOOICORICHOOCHI.IRIHHHORIIHHOOICCOIROCHICOHIRR",
        remap = false
)
public abstract class LunarTabLogoWidthMixin {
    @Inject(
            method = "IIRHIOIOIHRIIROCOCROHCROCORICO(Ljava/util/UUID;)I",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$captureTabLogoWidth(UUID playerId, CallbackInfoReturnable<Integer> cir) {
        LunarTabLogoCompatibility.recordLogoWidth(playerId, cir.getReturnValueI());
    }
}
