package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLogoColorSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers Lunar's selected badge before later profile refreshes can replace it. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IHCOHRRIIRROCRRRRRHCOCROIOCOII."
                + "OIOIOCRCRHRHRHICCROORCRHCCRHHR.HIIIRCIHHCOIRCOHOIIRRCRIHHRICI",
        remap = false
)
public abstract class LunarBadgeManagerSelectionMixin {

    @Inject(
            method = {"CORRIROORHRIRIHCRHROIOCRCCIOIH("
                    + "Lcom/moonsworth/lunar/client/CICIOHICRCOOHRCHOCOCOCIRHCIOCR/"
                    + "RRRRRIRCCIIORHHCCCHICOCRRCOOOH;)V"},
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$badgeSelectionChanged(@Coerce Object badge, CallbackInfo ci) {
        // The ID setter delegates here with the resolved badge, including null for None.
        // Capturing that object avoids the catalog getter whose obfuscated name changes.
        GomodLogoColorSync.rememberNativeBadgeSelection(badge);
    }
}
