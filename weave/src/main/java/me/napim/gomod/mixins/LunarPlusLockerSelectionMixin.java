package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.LunarNametagDebugCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Handles Lunar's native setColor callback, including local-only native zero for No+. */
// Superseded by LunarColorCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.COOHHRRCORRHIRIHRORIHRHICCIOIH."
                + "IOCIHHIRIROCOIROOCORCCIOOIIIRR.COHCCRCCIOORIRIIRIRCIHHCHIRCOH."
                + "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH.OIRIRHRCCIHOHRROCOHICIRIHIIHCH",
        remap = false
)
public abstract class LunarPlusLockerSelectionMixin {
    private static final String SET_COLOR =
            "CRCOOHRORRHCOOCROOHROCRIROCCIC(Ljava/lang/Integer;)V";

    @Inject(method = SET_COLOR, at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void gomod$interceptNoPlus(@Coerce Integer color, CallbackInfo ci) {
        LunarNametagDebugCapture.recordColorFlow("plus_callback_enter", "plus",
                color == null ? -1 : color.intValue(), "legacy_locker_callback");
        if (color != null && color.intValue() == GomodLunarPlusLocker.NO_PLUS_COLOR) {
            GomodLunarPlusLocker.selectNoPlus();
            ci.cancel();
        }
    }

    @Inject(method = SET_COLOR, at = @At("RETURN"), require = 0, remap = false)
    private static void gomod$refreshSelectedColorPreview(@Coerce Integer color, CallbackInfo ci) {
        if (color != null && color.intValue() != GomodLunarPlusLocker.NO_PLUS_COLOR) {
            LunarNametagDebugCapture.recordColorFlow("plus_callback_return", "plus",
                    color.intValue(), "legacy_native_callback_returned");
            GomodLunarPlusLocker.selectLunarColor(color.intValue());
        }
    }
}
