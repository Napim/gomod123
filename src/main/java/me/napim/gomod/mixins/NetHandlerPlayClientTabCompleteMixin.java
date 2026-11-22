package me.napim.gomod.mixins;

import me.napim.gomod.features.ServerCommandsRequest;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S3APacketTabComplete;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientTabCompleteMixin {
    @Inject(method = "handleTabComplete", at = @At("HEAD"), cancellable = true)
    private void gomod$handleServerCommandsTabComplete(S3APacketTabComplete packet, CallbackInfo ci) {
        if (ServerCommandsRequest.handleTabComplete(packet.func_149630_c())) {
            ci.cancel();
        }
    }
}
