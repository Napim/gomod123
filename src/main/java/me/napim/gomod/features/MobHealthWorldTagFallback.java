package me.napim.gomod.features;

import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/** Native Weave world-pass marker retained for older event routing; the bridge owns the late mob health draw. */
public final class MobHealthWorldTagFallback {
    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
    }
}
