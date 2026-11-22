package me.napim.gomod.features;

import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Render-overlay handler for instant map detection in MapQueuer.
 * Uses RenderGameOverlayEvent (fires every frame, reliable on Lunar+Weave)
 * so the player is notified immediately when a target map appears on the
 * scoreboard, instead of waiting for the 5-second scheduler cycle.
 * 
 * IMPORTANT: This handler NEVER sends /play commands or triggers requeue.
 * It only detects the map and notifies the player + stops the queuer.
 */
public class MapQueuerTickHandler {

    /** Throttle: only check every ~200 ms to avoid burning CPU */
    private long lastCheckMs = 0;
    private static final long CHECK_INTERVAL_MS = 200;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        // Only run when MapQueuer is active
        if (!MapQueuer.isEnabled() || !MapQueuer.isRunning()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = now;

        // Delegate to MapQueuer's instant check (only does map detection, no requeue)
        MapQueuer.instantMapCheck();
    }
}
