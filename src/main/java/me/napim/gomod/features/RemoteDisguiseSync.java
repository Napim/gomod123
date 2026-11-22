/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.weavemc.loader.api.event.PlayerListEvent$Add
 *  net.weavemc.loader.api.event.PlayerListEvent$Remove
 *  net.weavemc.loader.api.event.SubscribeEvent
 *  net.weavemc.loader.api.event.TickEvent
 */
package me.napim.gomod.features;

import me.napim.gomod.features.RemoteDisguiseManager;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.event.PlayerListEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;import net.weavemc.loader.api.event.PacketEvent;

public class RemoteDisguiseSync {    @SubscribeEvent    public void onPacketReceive(PacketEvent.Receive event) {        ReplayPlayerSelectorDisplay.rewriteIncomingSpectatorHudPacket(event.getPacket());    }
    private long lastApplyMs = 0L;
    private long lastPeerCapeApplyMs = 0L;
    private int lastWorldIdentity = 0;

    @SubscribeEvent
    public void onTick(TickEvent event) {        TimedDisguiserStore.tick();
        if (!ClientTickHub.isFeatureEnabled("RemoteDisguiseSync")) return;
        long _profT = FeatureProfiler.begin("RemoteDisguiseSync");
        try {
        Minecraft mc = Minecraft.getMinecraft();        RemoteDisguiseManager.expireTimedEntries();
        if (mc != null && mc.theWorld != null && mc.getNetHandler() != null) {
            long nowPeer = System.currentTimeMillis();
            if (nowPeer - this.lastPeerCapeApplyMs >= 200L) {
                this.lastPeerCapeApplyMs = nowPeer;
                GomodPeerStandaloneCape.applyNetworkOverrides(mc);
            }
        }
        long now;
        if (!RemoteDisguiseManager.hasEntries()) {
            this.lastWorldIdentity = 0;
            return;
        }
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            this.lastWorldIdentity = 0;
            return;
        }
        int worldIdentity = System.identityHashCode(mc.theWorld);
        if (worldIdentity != this.lastWorldIdentity) {
            this.lastWorldIdentity = worldIdentity;
            this.lastApplyMs = 0L;
            RemoteDisguiseManager.applyNow();
        }
        if ((now = System.currentTimeMillis()) - this.lastApplyMs < 200L) {
            return;
        }
        this.lastApplyMs = now;
        RemoteDisguiseManager.applyNow();
        } finally { FeatureProfiler.end("RemoteDisguiseSync", _profT); }
    }

    @SubscribeEvent
    public void onPlayerListAdd(PlayerListEvent.Add event) {
        if (RemoteDisguiseManager.hasEntries()) {
            RemoteDisguiseManager.applyNow();
        }
        GomodPeerStandaloneCape.applyNetworkOverrides(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onPlayerListRemove(PlayerListEvent.Remove event) {
        if (RemoteDisguiseManager.hasEntries()) {
            RemoteDisguiseManager.applyNow();
        }
    }
}
