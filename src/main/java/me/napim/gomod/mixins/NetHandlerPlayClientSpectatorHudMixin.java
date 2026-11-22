package me.napim.gomod.mixins;

import me.napim.gomod.features.ReplayPlayerSelectorDisplay;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S02PacketChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Final packet-entry hook for Hypixel's live spectator Target action bar. */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientSpectatorHudMixin {
    @Inject(method = {"handleChat", "func_147251_a"}, at = @At("HEAD"), require = 0)
    private void gomod$rewriteSpectatorTargetBeforeLunar(S02PacketChat packet, CallbackInfo ci) {
        ReplayPlayerSelectorDisplay.rewriteIncomingSpectatorHudPacket(packet);
    }
}
