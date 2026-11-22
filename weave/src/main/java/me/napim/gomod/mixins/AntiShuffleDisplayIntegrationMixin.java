package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LunarDisguiseDisplayCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reapplies an active local disguise after AntiShuffle removes Hypixel's §k/XXXX name masking. */
@Pseudo
@Mixin(targets = "me.ballmc.AntiShuffle.Main", remap = false)
public abstract class AntiShuffleDisplayIntegrationMixin {

    @Inject(
            method = "getUnformattedTextForChat(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void gomod$rewriteDisguiseAfterAntiShuffle(String input,
            CallbackInfoReturnable<String> cir) {
        if (!LocalDisguise.fastEnabledFlag || input == null || !hasAntiShuffleMaskMarker(input)) {
            return;
        }
        String cleaned = cir.getReturnValue();
        if (cleaned == null || cleaned.isEmpty()
                || !LocalDisguise.containsLocalDisguiseSourceNameFast(cleaned)) {
            return;
        }
        String rewritten = LocalDisguise.rewriteRenderedTextFast(cleaned);
        if (rewritten != null && !rewritten.equals(cleaned)) {
            cir.setReturnValue(rewritten);
        }
    }

    private static boolean hasAntiShuffleMaskMarker(String text) {
        return LunarDisguiseDisplayCompatibility.hasHypixelMaskMarker(text)
                || text.indexOf("\u00a7k") >= 0
                || text.indexOf("\u00a7K") >= 0;
    }
}
