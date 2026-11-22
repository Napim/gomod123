package me.napim.gomod.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

/** Binds the multi-companion hooks to Lunar 2.23.1's current player spawner. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ICCRRCCIOOIRICIHHCIOROHHROOHHR",
        remap = false
)
public abstract class LunarCompanionMultiTypeRenderer2231Mixin {
    private static final String SPAWN_METHOD =
            "OHCHICOOCCRHOOHIRCHRCCCCRRHRHI(Lcom/moonsworth/lunar/"
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/OOIOHHHCIICRRRCICOIIIRRRICOCIC/"
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/IHHHICHHRICRCIOHCIOHHOHIOIORRH;)V";
    private static final String CLEAR_PLAYER_COMPANIONS =
            "Lcom/moonsworth/lunar/client/IHCHHIIIHROCIROHROCHRCIROHOICH/"
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/ICCRRCCIOOIRICIHHCIOROHHROOHHR;"
                    + "ICCRRCCIOOIRICIHHCIOROHHROOHHR(Lcom/moonsworth/lunar/"
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/RCHICCCIHRCRCHCIRRIIRRHICORHHO/"
                    + "ICCRRCCIOOIRICIHHCIOROHHROOHHR/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC;)V";

    @Inject(method = SPAWN_METHOD, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$recordRendererInvocation(@Coerce Object lunarPlayer, CallbackInfo ci) {
        LunarCompanionMultiTypeSupport.recordRendererInvocation(this);
    }

    @Inject(
            method = SPAWN_METHOD,
            at = @At(value = "INVOKE", target = CLEAR_PLAYER_COMPANIONS, shift = At.Shift.AFTER),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$skipIgnoredPlayerCompanions(@Coerce Object lunarPlayer, CallbackInfo ci) {
        if (LunarCompanionMultiTypeSupport.shouldSuppressCompanionSpawn(lunarPlayer)) {
            ci.cancel();
        }
    }

    @Redirect(
            method = SPAWN_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;"
                            + "Ljava/util/function/Function;)Ljava/lang/Object;"
            ),
            require = 0,
            remap = false
    )
    @SuppressWarnings("rawtypes")
    private Object gomod$mergePendingCompanionRetries(Map retries, Object playerId,
                                                       Function retryFactory) {
        return LunarCompanionMultiTypeSupport.mergeRetry(retries, playerId, retryFactory);
    }
}
