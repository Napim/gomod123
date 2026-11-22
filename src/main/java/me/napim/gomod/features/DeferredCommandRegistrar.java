package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.weavemc.loader.api.event.StartGameEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

/**
 * Registers all commands after Minecraft is fully initialized to avoid early
 * class loading issues that can silently break command registration.
 */
public final class DeferredCommandRegistrar {
    private boolean registered = false;
    private long lastAttemptMs = 0L;
    private int attempts = 0;

    @SubscribeEvent
    public void onStartGame(StartGameEvent.Post event) {
        registerIfNeeded();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        registerIfNeeded();
    }

    private void registerIfNeeded() {
        if (registered || Main.areCommandsRegistered()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < 2000L) {
            return;
        }
        lastAttemptMs = now;
        attempts++;
        int registeredCount = Main.registerCommands();
        if (registeredCount > 0 || Main.areCommandsRegistered()) {
            registered = true;
            System.out.println("[gomod] Commands registered (deferred).");
        } else {
            System.err.println("[gomod] Command registration attempt " + attempts + " failed; will retry.");
        }
    }
}
