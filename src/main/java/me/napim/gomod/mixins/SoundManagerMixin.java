package me.napim.gomod.mixins;

import me.napim.gomod.features.LightningSound;
import me.napim.gomod.features.PlayersPlayAudio;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.SpawnedMobStepSoundMute;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public class SoundManagerMixin {

    @Inject(method = "playSound", at = @At("HEAD"), cancellable = true)
    private void gomod$remixPlayersPlaySound(ISound sound, CallbackInfo ci) {
        // Packet, relay and native UI callbacks can arrive off-thread. Vanilla's
        // playingSounds BiMap must not change while updateAllSounds iterates it.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && !mc.isCallingFromMinecraftThread()) {
            Object world = mc.theWorld;
            Object connection = mc.getNetHandler();
            mc.addScheduledTask(() -> {
                if (mc.theWorld == world && mc.getNetHandler() == connection) {
                    ((SoundManager) (Object) this).playSound(sound);
                }
            });
            ci.cancel();
            return;
        }

        LightningSound.applyToPlayedSound(sound);
        if (gomod$isHiddenPlayerStepSound(sound) || SpawnedMobStepSoundMute.shouldMutePlayedSound(sound)) {
            ci.cancel();
            return;
        }
        if (PlayersPlayAudio.isReplayingPaneSounds()) {
            return;
        }
        if (PlayersPlayAudio.remixSound(sound)) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayedSound", at = @At("HEAD"), cancellable = true)
    private void gomod$queueDelayedSoundOnClient(ISound sound, int delay, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && !mc.isCallingFromMinecraftThread()) {
            Object world = mc.theWorld;
            Object connection = mc.getNetHandler();
            mc.addScheduledTask(() -> {
                if (mc.theWorld == world && mc.getNetHandler() == connection) {
                    ((SoundManager) (Object) this).playDelayedSound(sound, delay);
                }
            });
            ci.cancel();
            return;
        }
        if (gomod$isHiddenPlayerStepSound(sound) || SpawnedMobStepSoundMute.shouldMutePlayedSound(sound)) {
            ci.cancel();
        }
    }

    /**
     * Some servers and replay streams deliver footsteps as positional sounds
     * rather than invoking Entity#playStepSound on the remote player.
     */
    private static boolean gomod$isHiddenPlayerStepSound(ISound sound) {
        try {
            if (sound == null || sound.getSoundLocation() == null) {
                return false;
            }
            String path = sound.getSoundLocation().getResourcePath();
            if (path == null) {
                return false;
            }
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (!(lower.startsWith("step.") || lower.contains(".step.")
                    || lower.endsWith(".step") || lower.contains("footstep"))) {
                return false;
            }
            return BlitzPregamePlayerToggle.isHiddenPlayerNear(
                    sound.getXPosF(), sound.getYPosF(), sound.getZPosF(), 2.25D);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
