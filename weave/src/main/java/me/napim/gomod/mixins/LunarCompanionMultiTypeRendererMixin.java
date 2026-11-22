package me.napim.gomod.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;

/** Enables multiple native Lunar companion entities from one player's cosmetic profile. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.CRICRCRHRRICCICRHICIIIOHRIRHRH."
                + "HHICICROOCIIROOHOORRCIHOICHOIH.OCCRIICCOCOIORRORHIRHCHCOHHHHC",
        remap = false
)
public abstract class LunarCompanionMultiTypeRendererMixin {
    private static final String SPAWN_METHOD =
            "HHCHIRROHRIRRIHCCHHIOCHIIOHRHO(Lcom/moonsworth/lunar/"
                    + "IHCCRCCOHRCCIORHHCROORROCICRHI/OIRIRHRCCIHOHRROCOHICIRIHIIHCH/"
                    + "HHICICROOCIIROOHOORRCIHOICHOIH/IHCCRCCOHRCCIORHHCROORROCICRHI;)V";
    private static final String CLEAR_PLAYER_COMPANIONS =
            "Lcom/moonsworth/lunar/client/CRICRCRHRRICCICRHICIIIOHRIRHRH/"
                    + "HHICICROOCIIROOHOORRCIHOICHOIH/OCCRIICCOCOIORRORHIRHCHCOHHHHC;"
                    + "OCCRIICCOCOIORRORHIRHCHCOHHHHC(Lcom/moonsworth/lunar/"
                    + "IHCCRCCOHRCCIORHHCROORROCICRHI/CIIHHRHHHIOIIHHRROOHRHICIHOOOC/"
                    + "OCCRIICCOCOIORRORHIRHCHCOHHHHC/IOHOHIHHHHORRCCRHCRCHCRIOIHRHH;)V";

    @Inject(method = SPAWN_METHOD, at = @At("HEAD"), require = 0, remap = false)
    private void gomod$recordRendererInvocation(@Coerce Object player,
                                                 CallbackInfo ci) {
        LunarCompanionMultiTypeSupport.recordRendererInvocation(this);
    }

    @Inject(
            method = SPAWN_METHOD,
            at = @At(value = "INVOKE", target = CLEAR_PLAYER_COMPANIONS, shift = At.Shift.AFTER),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$skipIgnoredPlayerCompanions(@Coerce Object player, CallbackInfo ci) {
        if (LunarCompanionMultiTypeSupport.shouldSuppressCompanionSpawn(player)) {
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
