package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;

/** Plays an audible cue once when the player is moved into Blitz cages. */
public final class BlitzCageEntrySound {
    private static int lastWorldIdentity;
    private static boolean wasInCages;

    private BlitzCageEntrySound() {}

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            lastWorldIdentity = 0;
            wasInCages = false;
            return;
        }
        int worldIdentity = System.identityHashCode(mc.theWorld);
        if (worldIdentity != lastWorldIdentity) {
            lastWorldIdentity = worldIdentity;
            wasInCages = false;
        }
        boolean inCages = GameContext.isBlitzCagePhaseSidebar(mc);
        if (inCages && !wasInCages && ConfigManager.isGameTeleporterSoundEnabled()) {
            mc.thePlayer.playSound("mob.ghast.fireball", 2.0F, 0.0F);
        }
        wasInCages = inCages;
    }
}
