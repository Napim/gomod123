package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla Controls calls {@link KeyBinding#getKeyDescription()} / {@link KeyBinding#getKeyCategory()},
 * which run {@code I18n.format} on our keys. When mod lang is not on the client {@link net.minecraft.client.resources.Locale}
 * yet, the UI shows raw keys — replace those with gomod translations when available.
 */
@Mixin(KeyBinding.class)
public class KeyBindingGomodLocalizationMixin {

    @Inject(method = {"getKeyDescription", "func_151464_g"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void gomod$localizedDescription(CallbackInfoReturnable<String> cir) {
        String result = cir.getReturnValue();
        if (result == null || !result.startsWith("key.gomod123")) {
            return;
        }
        String localized = GomodKeybinds.localizeKey(result);
        if (!localized.equals(result)) {
            cir.setReturnValue(localized);
        }
    }

    @Inject(method = {"getKeyCategory", "func_151466_e"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void gomod$localizedCategory(CallbackInfoReturnable<String> cir) {
        String result = cir.getReturnValue();
        if (result == null || !result.startsWith("key.categories.gomod123")) {
            return;
        }
        String localized = GomodKeybinds.localizeKey(result);
        if (!localized.equals(result)) {
            cir.setReturnValue(localized);
        }
    }
}
