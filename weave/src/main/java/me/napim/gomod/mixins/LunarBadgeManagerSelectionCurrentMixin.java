package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLogoColorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the equipped badge from Lunar's current native badge manager. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OROHROORCHIHCRHOROOHOHORCHHOII."
                + "ICCOIIRCOIOHHHHCIRHHIRICRCROOO.COIOIRHRRORRHHOIIORORRHOOCRHII",
        remap = false
)
public abstract class LunarBadgeManagerSelectionCurrentMixin {

    // Verified against the active 2026-10-26 lunar.jar with SHA-256
    // D7448213A6F4886769055292A5761470483D045A0962AAFDDA7FDC5C15F83A16.
    // The integer equippedBadgeId path delegates to this resolved-object setter.
    @Inject(
            method = {"RHOICRHRRHCICCRORRHHIRIIHHIIHI("
                    + "Lcom/moonsworth/lunar/client/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/"
                    + "IOOCCRIRHHROHIHRCHHRRIOICHIOIR;)V"},
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$badgeSelectionChanged(@Coerce Object badge, CallbackInfo ci) {
        GomodLogoColorSync.rememberNativeBadgeSelection(badge);
    }
}
