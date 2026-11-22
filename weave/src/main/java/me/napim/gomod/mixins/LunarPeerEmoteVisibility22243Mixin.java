package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodPeerLunarFullAccess;
import me.napim.gomod.features.LunarPeerEmoteControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Filters peer emotes through a normal helper; Mixin definitions are never loaded by callers. */
@Pseudo
@Mixin(targets = "com.moonsworth.lunar.client.COCIICOHOOCCHCHRIRHHCHORROOCOR.RCRHRICCICICHOOCCCIRRCIRCICIOC", remap = false)
public abstract class LunarPeerEmoteVisibility22243Mixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$capturePeerEmoteDispatcher(CallbackInfo ci) {
        LunarPeerEmoteControl.captureDispatcher(this, "RCRHRICCICICHOOCCCIRRCIRCICIOC");
    }

    @Inject(method = "RCRHRICCICICHOOCCCIRRCIRCICIOC(Lcom/lunarclient/websocket/emote/v1/UseEmotePush;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void gomod$filterPeerFullAccessEmote(@Coerce Object push, CallbackInfo ci) {
        LunarPeerEmoteControl.captureDispatcher(this, "RCRHRICCICICHOOCCCIRRCIRCICIOC");
        if (GomodPeerLunarFullAccess.shouldSuppressEmotePush(push)) {
            LunarPeerEmoteControl.stopFromPush(push);
            ci.cancel();
        }
    }
}
