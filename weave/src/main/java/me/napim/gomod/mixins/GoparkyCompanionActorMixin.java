package me.napim.gomod.mixins;

import me.napim.gomod.features.GoparkyBlitzReactions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Lunar's live GEK actor so Goparky's smoke and local hitbox follow its real position. */
// Superseded by LunarCompanionAndConflictTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IHHCCCCRCOHRCRROIIIIHHOOHRHRCC."
                + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC.IHHHICHHRICRCIOHCIOHHOHIOIORRH."
                + "CICOHIIRRHHIIHRROHOICOCRHHHHOO",
        remap = false
)
public abstract class GoparkyCompanionActorMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$rememberGoparkyActor(
            @Coerce Object owner,
            @Coerce Object companionManager,
            @Coerce Object companionDefinition,
            @Coerce Object model,
            @Coerce Object attributes,
            CallbackInfo ci) {
        GoparkyBlitzReactions.rememberGoparkyActor(this, owner, companionDefinition);
        me.napim.gomod.features.GomodCompanionPositionSync.rememberActor(this, owner, companionDefinition);
    }
}
