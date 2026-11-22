package me.napim.gomod.features;

import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

/**
 * Captures feet position when the scoreboard first shows "Cages Open:" and optionally autoloads routes.
 */
public final class ChestRouteTickHandler {

    @SubscribeEvent
    public void onTick(TickEvent event) {
        ClientTickHub.runClientFeatureTicks();
    }
}
