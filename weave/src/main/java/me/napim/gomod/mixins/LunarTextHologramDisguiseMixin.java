package me.napim.gomod.mixins;

import me.napim.gomod.features.LeaderboardHologramDebugCapture;
import me.napim.gomod.features.LocalDisguise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Rewrites text passed through Lunar's separate in-world text-hologram renderer. */
// Superseded by LunarLeaderboardHologramTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.RCHORCICHCHOCCCROHOOHICROIOHRI."
                + "RCHICCCIHRCRCHCIRRIIRRHICORHHO.HRIRRHOHCCRCCRROHIRORORRORICIH."
                + "OOIOHHHCIICRRRCICOIIIRRRICOCIC.IHIRRHORRRRHICCOHOHORCHIHHHIOO."
                + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC.CICOHIIRRHHIIHRROHOICOCRHHHHOO."
                + "RCHICCCIHRCRCHCIRRIIRRHICORHHO",
        remap = false
)
public abstract class LunarTextHologramDisguiseMixin {
    @ModifyArg(
            method = "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
                            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/HRIRRHOHCCRCCRROHIRORORRORICIH/"
                            + "OOIOHHHCIICRRRCICOIIIRRRICOCIC/IIHOCCORIIHOORIOHCCRHRRCCCHIHR/"
                            + "RCHORCICHCHOCCCROHOOHICROIOHRI;CRRHRIRIHOHRHRCCHRCOICHOOIHOIC:"
                            + "(Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
                            + "RCHOIOIIRCHIRRHIIHHRHOORCCIHCH;DDDLjava/lang/String;ZZZFL"
                            + "com/moonsworth/lunar/client/util/RCHICCCIHRCRCHCIRRIIRRHICORHHO/"
                            + "ORCOIHCCICICIHRCCCCHHHRICHHICI;)V",
                    ordinal = 0,
                    remap = false
            ),
            index = 4,
            require = 0,
            remap = false
    )
    private String gomod$rewriteLunarLeaderboardHologram(String input) {
        String output = LocalDisguise.rewriteLunarLeaderboardTextHologram(input);
        LeaderboardHologramDebugCapture.recordLunarTextHologram(
                input, output, LocalDisguise.isEnabled());
        return output;
    }
}
