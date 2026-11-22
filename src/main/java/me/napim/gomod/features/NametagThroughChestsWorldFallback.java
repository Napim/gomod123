package me.napim.gomod.features;

import me.napim.gomod.render.DeferredNametagRenderer;
import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Native Weave/Lunar fallback for nametags that are deferred until after world/tile rendering.
 */
public final class NametagThroughChestsWorldFallback {
    public NametagThroughChestsWorldFallback() {
        if (!isForgeRuntime()) {
            DeferredNametagRenderer.useWeaveWorldEventFlush();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        // WeaveEventBridge flushes after rebinding Lunar's main framebuffer.
    }

    private static boolean isForgeRuntime() {
        try {
            Class.forName("net.minecraftforge.common.MinecraftForge", false,
                    NametagThroughChestsWorldFallback.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
