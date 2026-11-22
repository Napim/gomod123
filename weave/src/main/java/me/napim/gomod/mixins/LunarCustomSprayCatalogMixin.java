package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarCustomSpray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Retired mixin intentionally omitted from {@code gomod.weave.mixins.json}: superseded by
 * {@link me.napim.gomod.weave.LunarCatalogCompatibilityTransform} (retained for offline compatibility checks).
 */
@Deprecated
@Pseudo
@Mixin(targets = LunarCustomSpray.NATIVE_MANAGER_CLASS, remap = false)
public abstract class LunarCustomSprayCatalogMixin {

    @Inject(method = "OOOHRIIRCOHRRRHCCHCCRRORIRORRI()V", at = @At("TAIL"), require = 0, remap = false)
    private void gomod$registerCustomSpray(CallbackInfo callbackInfo) {
        LunarCustomSpray.register(this);
    }
}