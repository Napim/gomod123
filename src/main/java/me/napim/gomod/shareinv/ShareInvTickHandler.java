package me.napim.gomod.shareinv;

import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.FeatureProfiler;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

/**
 * Drives ShareInv WebSocket connect/reconnect and inventory sync ticks.
 */
public final class ShareInvTickHandler {
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("ShareInvTickHandler")) return;
        long _profT = FeatureProfiler.begin("ShareInvTickHandler");
        try {
            ShareInvManager.onTick(Minecraft.getMinecraft());
        } finally { FeatureProfiler.end("ShareInvTickHandler", _profT); }
    }
}
