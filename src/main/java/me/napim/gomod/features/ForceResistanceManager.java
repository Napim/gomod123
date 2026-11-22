package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

/**
 * Blitz 1.8.9: Resistance often has {@code showParticles=false}. When enabled, spawns extra
 * {@link EnumParticleTypes#SPELL_MOB} tinted like vanilla resistance for every player in the world
 * who has Resistance (does not modify other potions' particles).
 */
public final class ForceResistanceManager {

    private static volatile boolean enabled;

    private ForceResistanceManager() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean v) {
        enabled = v;
        ConfigManager.setForceResistanceParticlesEnabled(v);
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void applyFromConfig() {
        enabled = ConfigManager.isForceResistanceParticlesEnabled();
    }

    public static void clientTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc != null ? mc.theWorld : null;
        if (world == null) {
            return;
        }
        int base = ConfigManager.getForceResistanceSpawnsPerTick();
        float opacity = ConfigManager.getForceResistanceOpacityScale();
        int n = (int) Math.ceil(base * opacity);
        n = Math.max(0, Math.min(24, n));
        if (n <= 0) {
            return;
        }
        float sat = ConfigManager.getForceResistanceSaturation();
        java.util.Random r = world.rand;
        for (Object o : world.playerEntities) {
            if (!(o instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) o;
            PotionEffect eff = player.getActivePotionEffect(Potion.resistance);
            if (eff == null || eff.getDuration() <= 0) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                double ox = (r.nextDouble() - 0.5) * 0.85;
                double oy = r.nextDouble() * (player.height * 0.9);
                double oz = (r.nextDouble() - 0.5) * 0.85;
                double px = player.posX + ox;
                double py = player.posY + oy;
                double pz = player.posZ + oz;
                double cr = Math.min(1.0, (0.55 + r.nextDouble() * 0.15) * sat);
                double cg = Math.min(1.0, (0.20 + r.nextDouble() * 0.12) * sat);
                double cb = Math.min(1.0, (0.75 + r.nextDouble() * 0.15) * sat);
                world.spawnParticle(EnumParticleTypes.SPELL_MOB, false, px, py, pz, cr, cg, cb, 0);
            }
        }
    }
}
