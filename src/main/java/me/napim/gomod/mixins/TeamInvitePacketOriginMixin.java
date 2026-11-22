package me.napim.gomod.mixins;

import me.napim.gomod.command.TeamSpamCommand;
import net.minecraft.network.play.client.C01PacketChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(C01PacketChatMessage.class)
public abstract class TeamInvitePacketOriginMixin {
    @Inject(method="<init>(Ljava/lang/String;)V",at=@At("RETURN"),require=1)
    private void gomod$captureInviteOwner(String message,CallbackInfo ci){TeamSpamCommand.captureInvitePacket(this,message);}
}
