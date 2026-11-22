package me.napim.gomod.mixins;

import me.napim.gomod.features.KillCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Moves Lunar's separate logo draw along with the head/name of an indented TAB Victims row. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.RCHORCICHCHOCCCROHOOHICROIOHRI"
                + ".RCHICCCIHRCRCHCIRRIIRRHICORHHO.HICICRCHRRCHHIOIICIRIRCIIRICHI"
                + ".CRRHRIRIHOHRHRCCHRCOICHOOIHOIC",
        remap = false
)
public abstract class LunarTabVictimLogoOffsetMixin {
    private static final String LOGO_RENDERER_DESC =
            "Lcom/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
                    + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/HICICRCHRRCHHIOIICIRIRCIIRICHI/"
                    + "CICOHIIRRHHIIHRROHOICOCRHHHHOO;FFLjava/util/UUID;)Z";

    @ModifyVariable(
            method = "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC(" + LOGO_RENDERER_DESC,
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false
    )
    private float gomod$offsetBasicLogoForVictim(float x) {
        return KillCounter.offsetCurrentTabVictimLogoX(x);
    }

    @ModifyVariable(
            method = "CICOHIIRRHHIIHRROHOICOCRHHHHOO(" + LOGO_RENDERER_DESC,
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0, remap = false
    )
    private float gomod$offsetPlusLogoForVictim(float x) {
        return KillCounter.offsetCurrentTabVictimLogoX(x);
    }
}
