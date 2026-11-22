package me.napim.gomod.util;

import java.io.InputStream;

/**
 * True only when the mod JAR actually ships the referenced .ogg samples (not just {@code sounds.json}).
 */
public final class GomodBundledSounds {

    private GomodBundledSounds() {
    }

    /**
     * {@code sounds.json} lists several witch_idle variants; if none exist on the classpath, prefer vanilla
     * {@code mob.witch.*} so playback is not silent.
     */
    public static boolean hasBundledWitchIdleSample() {
        return resourceExists("/assets/gomod/sounds/mob/witch/witch_idle1.ogg")
                || resourceExists("/assets/gomod/sounds/mob/witch/witch_idle2.ogg")
                || resourceExists("/assets/gomod/sounds/mob/witch/witch_idle3.ogg");
    }

    public static boolean hasBundledWitchDrinkSample() {
        return resourceExists("/assets/gomod/sounds/mob/witch/witch_drink1.ogg")
                || resourceExists("/assets/gomod/sounds/mob/witch/witch_drink2.ogg");
    }

    public static boolean hasBundledWitchThrowSample() {
        return resourceExists("/assets/gomod/sounds/mob/witch/witch_throw1.ogg")
                || resourceExists("/assets/gomod/sounds/mob/witch/witch_throw2.ogg");
    }

    /**
     * Whether the JAR ships the .ogg for this witch kind (so {@code gomod:witch.<kind>} can load).
     */
    public static boolean hasBundledWitchKind(String kind) {
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case "idle":
                return hasBundledWitchIdleSample();
            case "drink":
                return hasBundledWitchDrinkSample();
            case "throw":
                return hasBundledWitchThrowSample();
            case "hurt":
                return resourceExists("/assets/gomod/sounds/mob/witch/witch_hurt1.ogg")
                        || resourceExists("/assets/gomod/sounds/mob/witch/witch_hurt2.ogg");
            case "death":
                return resourceExists("/assets/gomod/sounds/mob/witch/witch_death1.ogg")
                        || resourceExists("/assets/gomod/sounds/mob/witch/witch_death2.ogg");
            default:
                return hasBundledWitchIdleSample();
        }
    }

    private static boolean resourceExists(String path) {
        InputStream in = GomodBundledSounds.class.getResourceAsStream(path);
        if (in == null) {
            return false;
        }
        try {
            return true;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
