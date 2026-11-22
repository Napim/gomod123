package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.effect.EntityLightningBolt;

import java.awt.Color;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side lightning: shorten bolts, tint, and thunder sound speed/volume (see Lightning settings GUI).
 */
public final class LightningSettings {
    public static final int DEFAULT_BOLT_COLOR_ARGB = 0xFFE6E6FF;
    public static final boolean DEFAULT_NERF_ENABLED = true;
    public static final boolean DEFAULT_FIX_RENDER_ENABLED = true;
    public static final int DEFAULT_BOTTOM_BLOCKS = 2;
    public static final boolean DEFAULT_CUSTOM_COLOR_ENABLED = false;
    public static final boolean DEFAULT_RANDOM_COLOR_ENABLED = false;
    public static final float DEFAULT_OPACITY_PERCENT = 100.0f;
    public static final float DEFAULT_SPEED_PERCENT = 100.0f;
    public static final float DEFAULT_VOLUME_PERCENT = 100.0f;
    public static final boolean DEFAULT_POST_CAGE_SOUND_ENABLED = false;
    public static final int DEFAULT_POST_CAGE_SOUND_DURATION_SEC = 30;
    public static final float DEFAULT_POST_CAGE_SPEED_PERCENT = 100.0f;
    public static final float DEFAULT_POST_CAGE_VOLUME_PERCENT = 45.0f;

    /** Weak entity keys keep the chosen hue stable while a bolt renders without retaining expired bolts. */
    private static final Map<EntityLightningBolt, Integer> RANDOM_BOLT_COLORS =
            Collections.synchronizedMap(new WeakHashMap<EntityLightningBolt, Integer>());

    private LightningSettings() {}

    /** {@code 1.0} = vanilla playback speed. */
    public static float getSoundSpeedMultiplier() {
        return getEffectiveSpeedPercent() / 100.0f;
    }

    public static float getSoundVolumeMultiplier() {
        return getEffectiveVolumePercent() / 100.0f;
    }

    public static boolean hasSoundModification() {
        return getEffectiveSpeedPercent() != 100.0f
                || getEffectiveVolumePercent() != 100.0f;
    }

    public static float applySoundMultipliers(float vanillaPitch) {
        return vanillaPitch * getSoundSpeedMultiplier();
    }

    public static float applySoundVolume(float vanillaVolume) {
        return vanillaVolume * getSoundVolumeMultiplier();
    }

    public static float getEffectiveSpeedPercent() {
        return isPostCageSoundOverrideActive() ? getPostCageSpeedPercent() : getSpeedPercent();
    }

    public static float getEffectiveVolumePercent() {
        return isPostCageSoundOverrideActive() ? getPostCageVolumePercent() : getVolumePercent();
    }

    /** True during the configured window after Blitz cages open (sidebar leaves cage phase). */
    public static boolean isPostCageSoundOverrideActive() {
        if (isPreviewPostCageSound()) {
            return isPostCageSoundEnabled();
        }
        if (!isPostCageSoundEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !GameContext.isOnHypixel(mc) || !GameContext.isBlitzGameActive(mc)) {
            return false;
        }
        long released = KillCounter.getLastBlitzCageReleasedMs();
        if (released <= 0L) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - released;
        return elapsed >= 0L && elapsed <= (long) getPostCageSoundDurationSec() * 1000L;
    }

    /** Applies opacity independently from Fix renders and keeps vanilla color when custom tint is off. */
    public static float[] thunderTintComponents(float vanillaRed, float vanillaGreen,
            float vanillaBlue, float vanillaAlpha) {
        return thunderTintComponents(null, vanillaRed, vanillaGreen, vanillaBlue, vanillaAlpha);
    }

    /** Applies one stable random tint per bolt when enabled, otherwise the saved custom tint. */
    public static float[] thunderTintComponents(EntityLightningBolt bolt, float vanillaRed,
            float vanillaGreen, float vanillaBlue, float vanillaAlpha) {
        float red = vanillaRed;
        float green = vanillaGreen;
        float blue = vanillaBlue;
        if (isRandomColorEnabled() || isCustomColorEnabled()) {
            int rgb = isRandomColorEnabled()
                    ? getRandomBoltColorArgb(bolt) & 0xFFFFFF
                    : getBoltColorArgb() & 0xFFFFFF;
            float cr = ((rgb >> 16) & 0xFF) / 255.0f;
            float cg = ((rgb >> 8) & 0xFF) / 255.0f;
            float cb = (rgb & 0xFF) / 255.0f;
            float peak = Math.max(cr, Math.max(cg, cb));
            float sourcePeak = Math.max(vanillaRed, Math.max(vanillaGreen, vanillaBlue));
            if (sourcePeak <= 0.001f) {
                sourcePeak = 0.5f;
            }
            if (peak < 0.16f) {
                if (peak < 0.001f) {
                    cr = cg = cb = 0.16f;
                } else {
                    float lift = 0.16f / peak;
                    cr = Math.min(1.0f, cr * lift);
                    cg = Math.min(1.0f, cg * lift);
                    cb = Math.min(1.0f, cb * lift);
                }
                peak = Math.max(cr, Math.max(cg, cb));
            }
            red = cr * sourcePeak / peak;
            green = cg * sourcePeak / peak;
            blue = cb * sourcePeak / peak;
        }
        float alpha = vanillaAlpha * getOpacityPercent() / 100.0f;
        return new float[] {red, green, blue, alpha};
    }

    private static int getRandomBoltColorArgb(EntityLightningBolt bolt) {
        if (bolt == null) {
            return createRandomBoltColorArgb();
        }
        synchronized (RANDOM_BOLT_COLORS) {
            Integer color = RANDOM_BOLT_COLORS.get(bolt);
            if (color == null) {
                color = createRandomBoltColorArgb();
                RANDOM_BOLT_COLORS.put(bolt, color);
            }
            return color;
        }
    }

    private static int createRandomBoltColorArgb() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float hue = random.nextFloat();
        float saturation = 0.76f + random.nextFloat() * 0.24f;
        float brightness = 0.86f + random.nextFloat() * 0.14f;
        return 0xFF000000 | (Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF);
    }

    public static boolean isNerfEnabled() {
        return ConfigManager.isLightningNerfEnabled();
    }

    public static boolean isFixRenderEnabled() {
        return ConfigManager.isLightningFixRenderEnabled();
    }

    public static void setFixRenderEnabled(boolean enabled) {
        ConfigManager.setLightningFixRenderEnabled(enabled);
    }

    public static int getBottomBlocks() {
        return ConfigManager.getLightningNerfBottomBlocks();
    }

    public static void setNerfEnabled(boolean enabled) {
        ConfigManager.setLightningNerfEnabled(enabled);
    }

    public static void setBottomBlocks(int blocks) {
        ConfigManager.setLightningNerfBottomBlocks(blocks);
    }

    public static boolean isCustomColorEnabled() {
        return ConfigManager.isLightningCustomColorEnabled();
    }

    public static void setCustomColorEnabled(boolean enabled) {
        ConfigManager.setLightningCustomColorEnabled(enabled);
    }

    public static boolean isRandomColorEnabled() {
        return ConfigManager.isLightningRandomColorEnabled();
    }

    public static void setRandomColorEnabled(boolean enabled) {
        ConfigManager.setLightningRandomColorEnabled(enabled);
    }

    public static int getBoltColorArgb() {
        return ConfigManager.getLightningBoltColorArgb();
    }

    public static void setBoltColorArgb(int argb) {
        ConfigManager.setLightningBoltColorArgb(argb);
    }

    public static float getOpacityPercent() {
        return ConfigManager.getLightningOpacityPercent();
    }

    public static void setOpacityPercent(float percent) {
        ConfigManager.setLightningOpacityPercent(percent);
    }

    public static float getSpeedPercent() {
        return ConfigManager.getLightningSpeedPercent();
    }

    public static void setSpeedPercent(float percent) {
        ConfigManager.setLightningSpeedPercent(percent);
    }

    public static float getVolumePercent() {
        return ConfigManager.getLightningSoundVolumePercent();
    }

    public static void setVolumePercent(float percent) {
        ConfigManager.setLightningSoundVolumePercent(percent);
    }

    public static boolean isPostCageSoundEnabled() {
        return ConfigManager.isLightningPostCageSoundEnabled();
    }

    public static void setPostCageSoundEnabled(boolean enabled) {
        ConfigManager.setLightningPostCageSoundEnabled(enabled);
    }

    public static int getPostCageSoundDurationSec() {
        return ConfigManager.getLightningPostCageSoundDurationSec();
    }

    public static void setPostCageSoundDurationSec(int seconds) {
        ConfigManager.setLightningPostCageSoundDurationSec(seconds);
    }

    public static float getPostCageSpeedPercent() {
        return ConfigManager.getLightningPostCageSpeedPercent();
    }

    public static void setPostCageSpeedPercent(float percent) {
        ConfigManager.setLightningPostCageSpeedPercent(percent);
    }

    public static float getPostCageVolumePercent() {
        return ConfigManager.getLightningPostCageVolumePercent();
    }

    public static void setPostCageVolumePercent(float percent) {
        ConfigManager.setLightningPostCageVolumePercent(percent);
    }

    /** While true, thunder preview and sound mods use the post-cage profile (early-game GUI test). */
    public static boolean isPreviewPostCageSound() {
        return previewPostCageSound;
    }

    static void setPreviewPostCageSound(boolean preview) {
        previewPostCageSound = preview;
    }

    private static boolean previewPostCageSound = false;

    public static void resetAllToDefaults() {
        ConfigManager.resetLightningSettingsToDefaults();
    }

    public static void resetPostCageToDefaults() {
        ConfigManager.resetLightningPostCageSettingsToDefaults();
    }

    /** @deprecated use {@link #isNerfEnabled()} */
    @Deprecated
    public static boolean isEnabled() {
        return isNerfEnabled();
    }

    /** @deprecated use {@link #setNerfEnabled(boolean)} */
    @Deprecated
    public static void setEnabled(boolean enabled) {
        setNerfEnabled(enabled);
    }
}
