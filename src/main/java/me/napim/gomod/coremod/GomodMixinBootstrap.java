package me.napim.gomod.coremod;

import org.spongepowered.asm.mixin.Mixins;

/**
 * Read-only checks for whether {@code gomod.mixins.json} was picked up by {@code MixinTweaker}.
 * Do not call {@code MixinBootstrap.init()} from gomod code — that must only run via
 * {@code org.spongepowered.asm.launch.MixinTweaker} in the jar manifest.
 */
public final class GomodMixinBootstrap {

    private GomodMixinBootstrap() {}

    public static boolean isConfigRegistered() {
        try {
            for (Object config : Mixins.getConfigs()) {
                if (config != null && "gomod.mixins.json".equals(String.valueOf(config))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return Mixins.getConfigs().toString().contains("gomod.mixins.json");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
