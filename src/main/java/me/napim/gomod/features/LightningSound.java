package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import me.napim.gomod.util.MinecraftPrivateAccess;

/**
 * Client-side speed and volume for lightning strike sounds
 * ({@code ambient.weather.thunder} + the {@code random.explode} boom layer).
 */
public final class LightningSound {

    private static final ResourceLocation THUNDER = new ResourceLocation("ambient.weather.thunder");
    private static final ResourceLocation EXPLODE = new ResourceLocation("random.explode");
    /** Vanilla {@link EntityLightningBolt} plays thunder at this volume (clamped to 1.0 in {@code SoundManager}). */
    private static final float VANILLA_THUNDER_VOLUME = 10000.0F;
    /** Vanilla {@link EntityLightningBolt} plays {@code random.explode} at this volume. */
    private static final float LIGHTNING_EXPLODE_VOLUME = 2.0F;
    private static final float LIGHTNING_EXPLODE_VOLUME_TOLERANCE = 0.35F;
    private static final String[] PITCH_FIELD = {"pitch", "field_147663_c"};
    private static final String[] VOLUME_FIELD = {"volume", "field_147662_b"};
    private static final String[] LIGHTNING_STATE_FIELD = {"lightningState", "field_70262_b"};

    private LightningSound() {}

    public static void applyToPlayedSound(ISound sound) {
        if (sound == null || !isLightningStrikeSound(sound) || sound instanceof PreviewStrikeSound) {
            return;
        }
        if (LightningSettings.getEffectiveSpeedPercent() != 100.0f) {
            setPitch(sound, LightningSettings.applySoundMultipliers(sound.getPitch()));
        }
        if (LightningSettings.getEffectiveVolumePercent() != 100.0f) {
            applyStrikeVolume(sound);
        }
    }

    /**
     * Vanilla thunder uses volume {@code 10000} so linear attenuation keeps strikes audible across the map;
     * {@code SoundManager} clamps output loudness but still uses the high value for distance falloff.
     * Scale that base by the user's volume % — do not replace it with {@code 0–1} only.
     */
    private static void applyStrikeVolume(ISound sound) {
        ResourceLocation loc = sound.getSoundLocation();
        if (loc == null) {
            return;
        }
        float mult = LightningSettings.getSoundVolumeMultiplier();
        String path = loc.getResourcePath();
        if (THUNDER.getResourcePath().equals(path)) {
            setVolume(sound, VANILLA_THUNDER_VOLUME * mult);
            return;
        }
        if (EXPLODE.getResourcePath().equals(path) && isLightningExplodeVolume(sound.getVolume())) {
            setVolume(sound, LIGHTNING_EXPLODE_VOLUME * mult);
        }
    }

    private static final double PREVIEW_DISTANCE_BLOCKS = 3.0D;

    public static void playPreview(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        World world = mc.theWorld;
        if (world == null) {
            return;
        }
        EntityPlayer player = mc.thePlayer;
        float yawRad = player.rotationYaw * ((float) Math.PI / 180.0F);
        double strikeX = player.posX - Math.sin(yawRad) * PREVIEW_DISTANCE_BLOCKS;
        double strikeZ = player.posZ + Math.cos(yawRad) * PREVIEW_DISTANCE_BLOCKS;
        BlockPos ground = world.getHeight(new BlockPos(strikeX, 0, strikeZ));
        double strikeY = ground.getY();

        float thunderPitch = 0.8F + world.rand.nextFloat() * 0.2F;
        float explodePitch = 0.5F + world.rand.nextFloat() * 0.5F;
        playStrikeSounds(mc, strikeX, strikeY, strikeZ, thunderPitch, explodePitch);

        try {
            EntityLightningBolt bolt = new EntityLightningBolt(world, strikeX, strikeY, strikeZ);
            skipBoltStrikeSound(bolt);
            world.addWeatherEffect(bolt);
        } catch (Throwable ignored) {
        }
    }

    private static void playStrikeSounds(Minecraft mc, double x, double y, double z,
            float thunderPitch, float explodePitch) {
        if (mc.getSoundHandler() == null) {
            return;
        }
        ThunderStrikeSound thunder = new ThunderStrikeSound(THUNDER, VANILLA_THUNDER_VOLUME, thunderPitch, x, y, z, true);
        applyPreviewModifiers(thunder);
        mc.getSoundHandler().playSound(thunder);

        ThunderStrikeSound explode = new ThunderStrikeSound(EXPLODE, LIGHTNING_EXPLODE_VOLUME, explodePitch, x, y, z, false);
        applyPreviewModifiers(explode);
        mc.getSoundHandler().playSound(explode);
    }

    private static void applyPreviewModifiers(ISound sound) {
        if (!isLightningStrikeSound(sound)) {
            return;
        }
        if (LightningSettings.getEffectiveSpeedPercent() != 100.0f) {
            setPitch(sound, LightningSettings.applySoundMultipliers(sound.getPitch()));
        }
        if (LightningSettings.getEffectiveVolumePercent() != 100.0f) {
            applyStrikeVolume(sound);
        }
    }

    private static void skipBoltStrikeSound(EntityLightningBolt bolt) {
        try {
            MinecraftPrivateAccess.setPrivateValue(EntityLightningBolt.class, bolt, Integer.valueOf(1), LIGHTNING_STATE_FIELD);
        } catch (Throwable ignored) {
        }
    }

    public static void playPreviewPostCage(Minecraft mc) {
        try {
            LightningSettings.setPreviewPostCageSound(true);
            playPreview(mc);
        } finally {
            LightningSettings.setPreviewPostCageSound(false);
        }
    }

    /** @deprecated use {@link #isLightningStrikeSound(ISound)} */
    @Deprecated
    public static boolean isThunderSound(ISound sound) {
        return isLightningStrikeSound(sound);
    }

    public static boolean isLightningStrikeSound(ISound sound) {
        if (sound == null) {
            return false;
        }
        ResourceLocation loc = sound.getSoundLocation();
        if (loc == null) {
            return false;
        }
        String path = loc.getResourcePath();
        if (THUNDER.getResourcePath().equals(path)) {
            return true;
        }
        return EXPLODE.getResourcePath().equals(path) && isLightningExplodeVolume(sound.getVolume());
    }

    private static boolean isLightningExplodeVolume(float volume) {
        return volume >= LIGHTNING_EXPLODE_VOLUME - LIGHTNING_EXPLODE_VOLUME_TOLERANCE
                && volume <= LIGHTNING_EXPLODE_VOLUME + LIGHTNING_EXPLODE_VOLUME_TOLERANCE;
    }

    private static void setPitch(ISound sound, float pitch) {
        if (!(sound instanceof PositionedSound)) {
            return;
        }
        setFloatField((PositionedSound) sound, PITCH_FIELD, Math.max(0.05f, Math.min(4.0f, pitch)));
    }

    private static void setVolume(ISound sound, float volume) {
        if (!(sound instanceof PositionedSound)) {
            return;
        }
        setFloatField((PositionedSound) sound, VOLUME_FIELD, Math.max(0.0f, volume));
    }

    private static void setFloatField(PositionedSound sound, String[] names, float value) {
        try {
            MinecraftPrivateAccess.setPrivateValue(PositionedSound.class, sound, Float.valueOf(value), names);
        } catch (Throwable ignored) {
        }
    }

    public interface PreviewStrikeSound {}

    private static final class ThunderStrikeSound extends PositionedSound implements PreviewStrikeSound {
        ThunderStrikeSound(ResourceLocation loc, float vol, float pit, double x, double y, double z, boolean global) {
            super(loc);
            this.volume = vol;
            this.pitch = pit;
            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;
            this.attenuationType = global ? AttenuationType.NONE : AttenuationType.LINEAR;
        }
    }
}
