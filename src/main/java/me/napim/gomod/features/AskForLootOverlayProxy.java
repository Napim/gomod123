package me.napim.gomod.features;

import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

public final class AskForLootOverlayProxy {
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        AskForLoot.handleRenderOverlay(event);
    }
}
