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

/** Hooks the current Lunar TAB module's two icon render attempts (main logo and badge). */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OIHHRHRORHROIHOOCIIOIOCOORICOC"
                + ".ORCRCHHRCHRIIRHIRCIHRHOHRCHROC.HORHRRCCIOIHIHIOHCOCIRIHOHRIOR"
                + ".IIOOIIHRIOCCIHHCIRICHIIHIHOHII",
        remap = false
)
public abstract class LunarTabCurrentLogoMixin {
    private static final String MATRIX_DESC =
            "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/"
                    + "COCHICRCCIRHIHRRCHCIOOIOOOHHIH/RHOOHIOCHCHORHHIOHHCHCCICHCCOI;";

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII(" + MATRIX_DESC + "FFLjava/util/UUID;)Z",
            at = @At("RETURN"), require = 0, remap = false
    )
    private void gomod$capturePrimaryLogo(@Coerce Object matrix, float x, float y, UUID playerId,
            CallbackInfoReturnable<Boolean> cir) {
        LunarTabLogoCompatibility.recordPrimaryLogoPresence(playerId, cir.getReturnValueZ());
        LunarNametagDebugCapture.recordTabDraw(playerId, "logo", cir.getReturnValueZ());
        LunarTabLogoCompatibility.drawNativeAlignedStats(matrix, x, y, playerId);
    }

    @Inject(
            method = "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC(" + MATRIX_DESC + "FFLjava/util/UUID;)Z",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false
    )
    private void gomod$captureBadgeLogo(@Coerce Object matrix, float x, float y, UUID playerId,
            CallbackInfoReturnable<Boolean> cir) {
        if (LunarDisguiserBadge.shouldHidePlayerBadge(playerId)) {
            cir.setReturnValue(Boolean.FALSE);
        }
        LunarTabLogoCompatibility.recordBadgeLogoPresence(playerId, cir.getReturnValueZ());
        LunarNametagDebugCapture.recordTabDraw(playerId, "plus", cir.getReturnValueZ());
    }
}
