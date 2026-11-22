package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Backup path when {@link Entity#handleStatusUpdate} is not invoked for received entity-status packets.
 */
public final class WitchHurtPacketHandler {

    public WitchHurtPacketHandler() {
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        try {
            if (!(event.getPacket() instanceof S19PacketEntityStatus)) {
                return;
            }
            S19PacketEntityStatus pkt = (S19PacketEntityStatus) event.getPacket();
            if (pkt.getOpCode() != 2) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            Object world = mc.theWorld;
            Object connection = mc.getNetHandler();
            Runnable handle = () -> {
                if (mc.theWorld == world && mc.getNetHandler() == connection) {
                    handleHurtPacket(mc, pkt);
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

    private static void handleHurtPacket(Minecraft mc, S19PacketEntityStatus pkt) {
        try {
            Entity ent = pkt.getEntity(mc.theWorld);
            if (!(ent instanceof EntityLivingBase)) {
                return;
            }
            WitchAutoSound.onWitchHurtEntityStatus(mc, (EntityLivingBase) ent);
        } catch (Throwable ignored) {
        }
    }
}
