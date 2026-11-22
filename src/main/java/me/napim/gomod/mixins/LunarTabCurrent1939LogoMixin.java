package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarTabLogoCompatibility;
import me.napim.gomod.features.LunarNametagDebugCapture;
import me.napim.gomod.features.LunarDisguiserBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Hooks the Lunar TAB module shipped by the 2026-07-29 19:39 client update. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.RIOCOIOOCROCHCICCCIHROOCIRHRRC"
                + ".IHCOOOCCHRRICHCIOCOROOIRORICCO.IROHIRCHOIIIHHRCHHRICCICRCIRCH"
                + ".HROORCOIRRHCOHHHIOOICOIRCIHRRO",
        remap = false
)
public abstract class LunarTabCurrent1939LogoMixin {
    private static final String MATRIX_DESC =
            "Lcom/moonsworth/lunar/IOOOICHICRCIRCICRCHCICOIRHIIOH/IHCOOOCCHRRICHCIOCOROOIRORICCO/"
                    + "HIHHCIROROHIHHRRHHCOCHIOCOIRIR/RRIOCHORROIOCHROIHOIRHHCCORHIH;";

    @Inject(
            method = "HROORCOIRRHCOHHHIOOICOIRCIHRRO(" + MATRIX_DESC + "FFLjava/util/UUID;)Z",
            at = @At("RETURN"), require = 0, remap = false
    )
    private void gomod$capturePrimaryLogo(@Coerce Object matrix, float x, float y, UUID playerId,
            CallbackInfoReturnable<Boolean> cir) {
        LunarTabLogoCompatibility.recordPrimaryLogoPresence(playerId, cir.getReturnValueZ());
        LunarNametagDebugCapture.recordTabDraw(playerId, "logo", cir.getReturnValueZ());
        LunarTabLogoCompatibility.drawNativeAlignedStats(matrix, x, y, playerId);
    }

    @Inject(
            method = "HORRHROOIRIRHIOIHCORROCCCHCOII(" + MATRIX_DESC + "FFLjava/util/UUID;)Z",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false
    )
    private void gomod$captureBadgeLogo(@Coerce Object matrix, float x, float y, UUID playerId,
            CallbackInfoReturnable<Boolean> cir) {
        if (LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) {
            cir.setReturnValue(Boolean.FALSE);
        }
        LunarTabLogoCompatibility.recordBadgeLogoPresence(playerId, cir.getReturnValueZ());
        int plusRgb = LunarDisguiserBadge.manualFullDisguisePlusRgb(playerId);
        LunarNametagDebugCapture.recordTabDraw(playerId, "plus", cir.getReturnValueZ(),
                plusRgb >= 0, plusRgb);
    }
}
