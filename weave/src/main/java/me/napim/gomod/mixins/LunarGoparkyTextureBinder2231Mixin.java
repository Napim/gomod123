package me.napim.gomod.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anchors the version-guarded Lunar GEK texture-bind bytecode redirect. */
@Pseudo
@Mixin(targets = "com.moonsworth.lunar.client.HIRCHOCCIROORHOHOOHOIOHOOIHRCR."
        + "CICOHIIRRHHIIHRROHOICOCRHHHHOO.IHHHICHHRICRCIOHCIOHHOHIOIORRH."
        + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO", remap = false)
public abstract class LunarGoparkyTextureBinder2231Mixin {
    private static final String BIND_METHOD = "CICOHIIRRHHIIHRROHOICOCRHHHHOO(Lcom/moonsworth/lunar/client/"
            + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/ICCRRCCIOOIRICIHHCIOROHHROOHHR;"
            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
            + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO;)V";

    @Inject(method = BIND_METHOD, at = @At("RETURN"),
            require = 0, remap = false)
    private static void gomod$anchorTextureBindRedirect(
            @Coerce Object renderer,
            @Coerce Object model,
            CallbackInfo ci) {
        // The actual invocation is replaced by GomodMixinPlugin after this class is merged.
    }
}
