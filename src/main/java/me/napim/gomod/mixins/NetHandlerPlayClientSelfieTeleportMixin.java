package me.napim.gomod.mixins;

import me.napim.gomod.features.Selfie;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S01PacketJoinGame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the frozen /selfie camera attached to server-authoritative teleports. */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientSelfieTeleportMixin {

    /** Clear only the old server's camera anchor as soon as the connection closes. */
    @Inject(method = {"onDisconnect", "func_147231_a"}, at = @At("HEAD"), require = 0)
    private void gomod$resetSelfieOnDisconnect(IChatComponent reason, CallbackInfo ci) {
        Selfie.resetCameraForReconnect();
    }

    @Inject(method = "handlePlayerPosLook", at = @At("RETURN"))
    private void gomod$recaptureSelfieCameraAfterTeleport(S08PacketPlayerPosLook packet, CallbackInfo ci) {
        if (!Selfie.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc == null ? null : mc.thePlayer;
        if (player != null) {
            Selfie.onPlayerTeleported(player);
        }
    }

    /** Dimension changes can replace the world without sending a position-look packet. */
    @Inject(method = {"handleRespawn", "func_147280_a"}, at = @At("RETURN"), require = 0)
    private void gomod$recaptureSelfieCameraAfterRespawn(S07PacketRespawn packet, CallbackInfo ci) {
        if (!Selfie.isEnabled()) {
            return;
        }
        Selfie.refreshAfterWorldTransfer();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            // Respawn packets may keep the same WorldClient/player identity,
            // so the identity-based refresh alone is not sufficient here.
            Selfie.onPlayerTeleported(mc.thePlayer);
        } else {
            Selfie.refreshAfterWorldTransfer();
        }
    }

    /** Join-game packets can perform a backend transfer without S08. */
    @Inject(method = {"handleJoinGame", "func_147282_a"}, at = @At("RETURN"), require = 0)
    private void gomod$recaptureSelfieCameraAfterJoin(S01PacketJoinGame packet, CallbackInfo ci) {
        if (!Selfie.isEnabled()) {
            return;
        }
        // Let the identity-based path schedule its multi-frame handoff when
        // this packet replaced the backend world/player.
        Selfie.refreshAfterWorldTransfer();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            Selfie.onPlayerTeleported(mc.thePlayer);
        } else {
            Selfie.refreshAfterWorldTransfer();
        }
    }
}
