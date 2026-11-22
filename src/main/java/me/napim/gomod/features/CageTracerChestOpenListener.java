package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.server.S24PacketBlockAction;
import net.minecraft.world.World;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/** Stops automatic Blitz cage tracers when the server reports that any chest opened. */
public final class CageTracerChestOpenListener {
    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        if (event == null || !(event.getPacket() instanceof S24PacketBlockAction)) {
            return;
        }

        S24PacketBlockAction packet = (S24PacketBlockAction) event.getPacket();
        // Chest block action 1 updates the lid; data2 is the viewer count, so zero
        // is a close event. Trapped chests inherit BlockChest; ender chests use S24 too.
        if (packet.getData1() != 1 || packet.getData2() <= 0) return;
        Block block = packet.getBlockType();
        if (!(block instanceof BlockChest) && block != Blocks.ender_chest) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;
        final World world = mc.theWorld;
        Runnable markOpened = () -> {
            if (mc.theWorld == world) PlayerTracers.onFirstChestOpened(world);
        };
        try {
            if (mc.isCallingFromMinecraftThread()) markOpened.run();
            else mc.addScheduledTask(markOpened);
        } catch (Throwable ignored) {
        }
    }
}
