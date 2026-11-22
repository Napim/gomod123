package me.napim.gomod.mixins;

import me.napim.gomod.features.GoparkyBlitzReactions;
import me.napim.gomod.features.GomodCompanionPositionSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The actual companion draw, after world translation and before model yaw. */
@Pseudo
@Mixin(targets = "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
        + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ICCRRCCIOOIRICIHHCIOROHHROOHHR", remap = false)
public abstract class LunarGoparkyRenderer2231Mixin {
    private static final String DRAW = "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC("
            + "Lcom/moonsworth/lunar/client/IHHCCCCRCOHRCRROIIIIHHOOHRHRCC/"
            + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/IHHHICHHRICRCIOHCIOHHOHIOIORRH/CICOHIIRRHHIIHRROHOICOCRHHHHOO;"
            + "Lcom/moonsworth/lunar/client/IHCHHIIIHROCIROHROCHRCIROHOICH/"
            + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/CICOHIIRRHHIIHRROHOICOCRHHHHOO;"
            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
            + "RCHOIOIIRCHIRRHIIHHRHOORCCIHCH;F)V";
    // The matching bytecode edits are inserted by GomodMixinPlugin. Using Mixin's Args helper
    // here makes Lunar's Genesis classloader look for Weave-only Args$N classes at startup.

    @Inject(method = DRAW, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$beginCompanionDraw(@Coerce Object actor, @Coerce Object controller,
            @Coerce Object matrix, float yaw, CallbackInfo ci) {
        GoparkyBlitzReactions.beginCompanionDraw(actor);
    }

    @Inject(method = DRAW, at = @At("RETURN"), require = 0, remap = false)
    private void gomod$endCompanionDraw(CallbackInfo ci) {
        GoparkyBlitzReactions.endCompanionDraw();
    }
}
