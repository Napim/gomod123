package me.napim.gomod.util;

import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityWitch;

import java.util.Random;

/**
 * Plays witch SFX via {@link WitchAutoSound#playWitchSoundManual} (bundled {@code gomod:witch.*} when available,
 * same triple path as auto SFX). Ignores the Misc “witch sounds” toggle so {@code /witchsound} always tests playback.
 */
public final class WitchSounds {
    private static final Random RAND = new Random();
    /** Default max distance (blocks) to search for a witch to attach the sound to. */
    public static final double DEFAULT_WITCH_SEARCH_RANGE = 48.0;

    private WitchSounds() {
    }

    /**
     * @param atPlayer if true, use legacy player-centered playback; if false, play at nearest witch (or fail)
     * @return {@code null} if played; short reason if not (e.g. no witch when {@code !atPlayer})
     */
    public static String play(Minecraft mc, String kind, boolean atPlayer) {
        if (mc == null || mc.thePlayer == null) {
            return "No player.";
        }
        if (mc.theWorld == null) {
            return "No world.";
        }
        EntityPlayerSP p = mc.thePlayer;
        String k = kind == null ? "random" : kind.trim().toLowerCase();
        if ("random".equals(k)) {
            String[] pool = {"idle", "hurt", "death", "drink", "throw"};
            k = pool[RAND.nextInt(pool.length)];
        } else if (!isKnownKind(k)) {
            return "Unknown kind: " + kind;
        }
        if ("throwpotion".equals(k)) {
            k = "throw";
        }

        double x;
        double y;
        double z;
        if (atPlayer) {
            x = p.posX;
            y = p.posY;
            z = p.posZ;
        } else {
            EntityWitch witch = findNearestWitch(mc, DEFAULT_WITCH_SEARCH_RANGE * DEFAULT_WITCH_SEARCH_RANGE);
            if (witch == null) {
                return "No witch within " + (int) DEFAULT_WITCH_SEARCH_RANGE + " blocks. Use: /witchsound " + k + " player";
            }
            x = witch.posX;
            y = witch.posY;
            z = witch.posZ;
        }

        WitchAutoSound.playWitchSoundManual(mc, x, y, z, k);
        return null;
    }

    /**
     * Play a specific idle sample from the gomod pack (e.g. idle3/idle4).
     */
    public static void playIdleSample(Minecraft mc, int sampleIndex, boolean atPlayer) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        int idx = Math.max(1, Math.min(5, sampleIndex));
        double x;
        double y;
        double z;
        if (atPlayer) {
            x = mc.thePlayer.posX;
            y = mc.thePlayer.posY;
            z = mc.thePlayer.posZ;
        } else {
            EntityWitch witch = findNearestWitch(mc, DEFAULT_WITCH_SEARCH_RANGE * DEFAULT_WITCH_SEARCH_RANGE);
            if (witch == null) {
                x = mc.thePlayer.posX;
                y = mc.thePlayer.posY;
                z = mc.thePlayer.posZ;
            } else {
                x = witch.posX;
                y = witch.posY;
                z = witch.posZ;
            }
        }
        WitchAutoSound.playGomodEvent(mc, x, y, z, "witch.idle" + idx);
    }

    /** @deprecated use {@link #play(Minecraft, String, boolean)} */
    @Deprecated
    public static void play(Minecraft mc, String kind) {
        play(mc, kind, true);
    }

    private static boolean isKnownKind(String k) {
        switch (k) {
            case "idle":
            case "hurt":
            case "death":
            case "drink":
            case "throw":
            case "throwpotion":
            case "celebrate":
                return true;
            default:
                return false;
        }
    }

    public static EntityWitch findNearestWitch(Minecraft mc, double maxDistSq) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        EntityWitch best = null;
        double bestD = maxDistSq;
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof EntityWitch)) {
                continue;
            }
            EntityWitch w = (EntityWitch) e;
            if (!w.isEntityAlive()) {
                continue;
            }
            double d = mc.thePlayer.getDistanceSqToEntity(w);
            if (d <= bestD) {
                bestD = d;
                best = w;
            }
        }
        return best;
    }

}
