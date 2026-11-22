package me.napim.gomod.weave;

import net.weavemc.api.ModInitializer;
import net.weavemc.api.event.EventBus;
import net.weavemc.loader.api.command.CommandBus;
import me.napim.gomod.features.LunarLockerUiBundlePatcher;
import org.jetbrains.annotations.NotNull;

import java.lang.instrument.Instrumentation;

/** Native Lunar/Weave lifecycle entrypoint. */
public final class GomodWeaveEntrypoint implements ModInitializer {
    @Override
    public void preInit(@NotNull Instrumentation instrumentation) {
        System.out.println("[gomod] Weave pre-init");
        LunarLockerUiBundlePatcher.patchSupportedBundles();
        LunarRelayWeaveBootstrap.install(instrumentation);
    }

    @Override
    public void init() {
        System.out.println("[gomod] Initializing native Weave/Lunar build");
        // Lunar may create/populate its cached WebOSR bundle after preInit has already
        // scanned the UI directory. Retry before the player can open the Locker.
        LunarLockerUiBundlePatcher.patchSupportedBundles();

        CommandBus.setPlatformRegistrar(command ->
                net.weavemc.api.command.CommandBus.register(new WeaveCommandAdapter(command)));
        EventBus.subscribe(new WeaveEventBridge());

        // Weave invokes init() at Minecraft.main HEAD. Loading gomod's config here can
        // initialize ItemStack before Lunar has completed its own registries, so the
        // actual mod bootstrap is deferred to StartGameEvent.Post in WeaveEventBridge.
    }
}
