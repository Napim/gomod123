package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.GomodLogoColorSync;
import me.napim.gomod.features.LunarNametagDebugCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures real Lunar+ selections in the current 2.23.0 Locker callback. */
// Superseded by LunarColorCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = {"com.moonsworth.lunar.client.OOIRIHRRCHRIHRHHCOOCICRIRCHIIO."
                + "COCIICOHOOCCHCHRIRHHCHORROOCOR.OHOIIOOOCOOIRIHRIHIIRROIICRICH."
                + "RCRHRICCICICHOOCCCIRRCIRCICIOC.HOCIIIHOIOHRCHOHOHRROCCHCOOHOC",
                "com.moonsworth.lunar.client.CIRIIHCCROCIOIRORIOIOCHHIIHRCC."
                        + "CICOHIIRRHHIIHRROHOICOCRHHHHOO.ROHIOOCICRORHRCIHHHHHHRICCCRRC."
                        + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC.OOIOHHHCIICRRRCICOIIIRRRICOCIC"},
        remap = false
)
public abstract class LunarPlusLockerSelectionCurrentMixin {
    private static final String SET_COLOR =
            "OHHCOCHOHIROHCIRIHICHICHOCOOIC(Ljava/lang/Integer;)V";
    private static final String SET_COLOR_2231 =
            "HCIIIRIHOICHHHRIOHOIHHHCRHHHOI(Ljava/lang/Integer;)V";

    @Inject(method = {SET_COLOR, SET_COLOR_2231}, at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private static void gomod$traceColorClick(@Coerce Integer color, CallbackInfo ci) {
        LunarNametagDebugCapture.recordColorFlow("plus_callback_enter", "plus",
                color == null ? -1 : color.intValue(), "current_locker_callback");
        if (color == null) return;
        if (color.intValue() == GomodLunarPlusLocker.NO_PLUS_COLOR) {
            GomodLunarPlusLocker.selectNoPlus();
            ci.cancel();
        } else if (GomodLunarPlusLocker.isFullAccessEnabled()) {
            // Capture the body color before Lunar's native callback changes its profile.
            GomodLogoColorSync.rememberLocalLunarPlusColor(color.intValue());
        }
    }

    @Inject(method = {SET_COLOR, SET_COLOR_2231}, at = @At("RETURN"), require = 0, remap = false)
    private static void gomod$rememberSelectedColor(@Coerce Integer color, CallbackInfo ci) {
        if (color != null && color.intValue() != GomodLunarPlusLocker.NO_PLUS_COLOR) {
            LunarNametagDebugCapture.recordColorFlow("plus_callback_return", "plus",
                    color.intValue(), "native_callback_returned");
            GomodLunarPlusLocker.selectLunarColor(color.intValue());
        }
    }
}
