package me.napim.gomod.mixins;

import me.napim.gomod.command.TeamSpamCommand;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public abstract class TeamInviteSendGuardMixin {
    @Inject(method="sendPacket(Lnet/minecraft/network/Packet;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void gomod$guardTeamInvite(Packet<?> packet,CallbackInfo ci){if(TeamSpamCommand.interceptInvitePacket(packet))ci.cancel();}
    @Inject(method="sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void gomod$guardTeamInviteWithListeners(Packet<?> packet,GenericFutureListener<? extends Future<? super Void>> listener,
            GenericFutureListener<? extends Future<? super Void>>[] listeners,CallbackInfo ci){if(TeamSpamCommand.interceptInvitePacket(packet))ci.cancel();}
}
