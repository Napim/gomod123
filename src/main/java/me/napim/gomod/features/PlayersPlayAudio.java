package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Pane-aware audio remix for PlayersPlay.
 * Keeps the real listener on the local player and spawns pane-panned duplicates.
 */
public final class PlayersPlayAudio {
    private static boolean replayingPaneSounds = false;

    private PlayersPlayAudio() {}

    public static boolean isReplayingPaneSounds() {
        return replayingPaneSounds;
    }

    public static boolean remixSound(ISound sound) {
        if (replayingPaneSounds || !PlayersPlayMode.isEnabled() || sound == null) {
            return false;
        }
        if (LightningSound.isLightningStrikeSound(sound)) {
            return false;
        }
        if (sound.getAttenuationType() == ISound.AttenuationType.NONE) {
            return false;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getSoundHandler() == null) {
            return false;
        }

        EntityPlayer[] targets = PlayersPlayMode.getRenderedTargetsSnapshot();
        if (!hasAnyTargets(targets)) {
            targets = PlayersPlayMode.getActiveTargets();
        }
        boolean playedAny = false;

        replayingPaneSounds = true;
        try {
            for (int i = 0; i < targets.length && i < 4; i++) {
                EntityPlayer target = targets[i];
                if (target == null) continue;

                float paneVolume = computePaneVolume(sound, target);
                if (paneVolume <= 0.01F) continue;

                PaneSound paneSound = createPaneSound(sound, mc.thePlayer, i, paneVolume);
                if (paneSound == null) continue;
                mc.getSoundHandler().playSound(paneSound);
                playedAny = true;
            }
        } finally {
            replayingPaneSounds = false;
        }

        return playedAny;
    }

    private static boolean hasAnyTargets(EntityPlayer[] targets) {
        if (targets == null) {
            return false;
        }
        for (EntityPlayer target : targets) {
            if (target != null) {
                return true;
            }
        }
        return false;
    }

    private static float computePaneVolume(ISound sound, EntityPlayer target) {
        double dx = sound.getXPosF() - target.posX;
        double dy = sound.getYPosF() - (target.posY + target.getEyeHeight());
        double dz = sound.getZPosF() - target.posZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float baseVolume = sound.getVolume();
        float maxDistance = 16.0F * Math.max(1.0F, baseVolume);
        if (distance >= maxDistance) {
            return 0.0F;
        }

        float distanceFactor = 1.0F - (float) (distance / maxDistance);
        return Math.max(0.0F, baseVolume * distanceFactor);
    }

    private static PaneSound createPaneSound(ISound original, EntityPlayer listener, int paneIndex, float paneVolume) {
        if (listener == null) return null;

        float xOffset = (paneIndex % 2 == 0) ? -1.75F : 1.75F;
        float yOffset = (paneIndex < 2) ? 0.45F : -0.45F;
        float zOffset = (paneIndex < 2) ? -0.35F : 0.35F;

        return new PaneSound(
                original.getSoundLocation(),
                paneVolume,
                original.getPitch(),
                original.canRepeat(),
                original.getRepeatDelay(),
                listener.posX + xOffset,
                listener.posY + listener.getEyeHeight() + yOffset,
                listener.posZ + zOffset,
                original.getAttenuationType()
        );
    }

    private static final class PaneSound extends PositionedSound {
        private PaneSound(ResourceLocation soundLocation, float volume, float pitch, boolean repeat, int repeatDelay, double x, double y, double z, ISound.AttenuationType attenuationType) {
            super(soundLocation);
            this.volume = volume;
            this.pitch = pitch;
            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;
            this.repeat = repeat;
            this.repeatDelay = repeatDelay;
            this.attenuationType = attenuationType;
        }
    }
}
