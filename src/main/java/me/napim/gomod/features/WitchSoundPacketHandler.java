package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Hypixel / MP often omit client potion entities and held-item sync; server still sends
 * {@link S29PacketSoundEffect} for witch drink/throw. Re-fire local SFX at packet coords.
 */
public final class WitchSoundPacketHandler {

    public WitchSoundPacketHandler() {
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        try {
            if (!ConfigManager.isWitchSoundsEnabled()) {
                return;
            }
            if (!WitchAutoSound.isPacketReplayEnabled()) {
                return;
            }
            if (!(event.getPacket() instanceof S29PacketSoundEffect)) {
                return;
            }
            S29PacketSoundEffect pkt = (S29PacketSoundEffect) event.getPacket();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            Object world = mc.theWorld;
            Object connection = mc.getNetHandler();
            Runnable handle = () -> {
                if (mc.theWorld == world && mc.getNetHandler() == connection) {
                    handleSoundPacket(mc, pkt);
                }
            };
            if (mc.isCallingFromMinecraftThread()) {
                handle.run();
            } else {
                mc.addScheduledTask(handle);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleSoundPacket(Minecraft mc, S29PacketSoundEffect pkt) {
        try {
            if (!ConfigManager.isWitchSoundsEnabled() || !WitchAutoSound.isPacketReplayEnabled()) {
                return;
            }
            String name = pkt.getSoundName();
            if (name == null) {
                return;
            }
            double x = pkt.getX() / 8.0D;
            double y = pkt.getY() / 8.0D;
            double z = pkt.getZ() / 8.0D;
            WitchAutoSound.onServerWitchSoundPacket(mc, name, x, y, z);
        } catch (Throwable ignored) {
        }
    }
}
