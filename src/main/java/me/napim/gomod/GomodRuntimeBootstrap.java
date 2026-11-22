package me.napim.gomod;

import me.napim.gomod.coremod.GomodMixinBootstrap;
import me.napim.gomod.features.BarrierVisibilityAtlasHook;
import me.napim.gomod.features.BlitzVaultItemsData;
import me.napim.gomod.features.BlocksFinderWorldOpacityAtlasHook;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.gui.BlitzVaultItemsGUI;
import me.napim.gomod.features.WaterClarity;
import me.napim.gomod.features.WaterClarityAtlasHook;
import me.napim.gomod.forge.ForgeEventBridge;
import me.napim.gomod.util.GomodClasspathTextures;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.weavemc.loader.api.command.CommandBus;
import net.weavemc.loader.api.event.EventBus;
import net.weavemc.loader.api.event.StartGameEvent;

/**
 * Initializes gomod when Forge never loads {@link GomodForgeMod} (common on Lunar + coremod-only jars).
 * Safe to call from {@code @Mod} preInit and from {@link me.napim.gomod.mixins.MinecraftRunTickFeaturesMixin}.
 */
public final class GomodRuntimeBootstrap {

    private static volatile boolean started;
    private static volatile boolean startGamePosted;
    private static volatile boolean forgeBridgeRegistered;
    private static volatile boolean forgeCommandRegistrarRegistered;
    private static volatile boolean asyncPreInitRequested;

    private GomodRuntimeBootstrap() {
    }

    public static boolean isStarted() {
        return started;
    }

    /** Marks the shared runtime initialized when the real Weave entrypoint owns lifecycle/events. */
    public static void completeWeaveBootstrap() {
        synchronized (GomodRuntimeBootstrap.class) {
            if (started) {
                return;
            }
            started = true;
            forgeBridgeRegistered = false;
        }
        queueHudIconPreload();
        System.out.println("[gomod] Runtime bootstrap complete (native Weave 1.3.3).");
    }

    public static void ensureStarted() {
        ensureForgeCommandRegistrarRegistered();
        if (started) {
            return;
        }
        if (!Main.isPreInitComplete()) {
            if (shouldDeferPreInitToBackground()) {
                requestAsyncPreInit();
                return;
            }
            synchronized (GomodRuntimeBootstrap.class) {
                if (!Main.isPreInitComplete()) {
                    System.out.println("[gomod] Runtime bootstrap: Main.preInit on loader thread...");
                    ConfigManager.setMoreHudEnabled(true);
                    new Main().preInit();
                }
            }
        }
        completeBootstrapIfNeeded();
    }

    /** Avoid blocking the client tick / main menu; loader threads (Forge / Weave) may init synchronously. */
    private static boolean shouldDeferPreInitToBackground() {
        if (asyncPreInitRequested) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.isCallingFromMinecraftThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void requestAsyncPreInit() {
        if (asyncPreInitRequested) {
            return;
        }
        synchronized (GomodRuntimeBootstrap.class) {
            if (asyncPreInitRequested || Main.isPreInitComplete()) {
                return;
            }
            asyncPreInitRequested = true;
            Thread worker = new Thread(() -> {
                try {
                    if (!Main.isPreInitComplete()) {
                        System.out.println("[gomod] Runtime bootstrap: loading Main.preInit on background thread...");
                        ConfigManager.setMoreHudEnabled(true);
                        new Main().preInit();
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] Async preInit failed: " + t);
                    t.printStackTrace();
                }
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null) {
                    try {
                        mc.addScheduledTask(GomodRuntimeBootstrap::completeBootstrapIfNeeded);
                    } catch (Throwable ignored) {
                        completeBootstrapIfNeeded();
                    }
                } else {
                    completeBootstrapIfNeeded();
                }
            }, "gomod-preinit");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void completeBootstrapIfNeeded() {
        if (started) {
            return;
        }
        if (!Main.isPreInitComplete()) {
            return;
        }
        synchronized (GomodRuntimeBootstrap.class) {
            if (started) {
                return;
            }
            try {
                if (!GomodMixinBootstrap.isConfigRegistered()) {
                    System.err.println("[gomod] CRITICAL: gomod.mixins.json was never registered — mixin features "
                            + "may not work. Check latest.log for MixinTweaker / classpath errors.");
                }
                ensureForgeBridgeRegistered();
                started = true;
                System.out.println("[gomod] Runtime bootstrap complete (commands, HUD bridge, resource pack).");
                queueStartGameEvent();
                queueWaterClarityChunkReloadIfEnabled();
                queueHudIconPreload();
            } catch (Throwable t) {
                System.err.println("[gomod] Runtime bootstrap failed: " + t);
                t.printStackTrace();
            }
        }
    }

    private static void ensureForgeBridgeRegistered() {
        if (forgeBridgeRegistered) {
            return;
        }
        synchronized (GomodRuntimeBootstrap.class) {
            if (forgeBridgeRegistered) {
                return;
            }
            ForgeEventBridge bridge = new ForgeEventBridge();
            MinecraftForge.EVENT_BUS.register(bridge);
            FMLCommonHandler.instance().bus().register(bridge);
            MinecraftForge.EVENT_BUS.register(new BarrierVisibilityAtlasHook());
            MinecraftForge.EVENT_BUS.register(new BlocksFinderWorldOpacityAtlasHook());
            MinecraftForge.EVENT_BUS.register(new WaterClarityAtlasHook());
            BarrierVisibilityAtlasHook.scheduleApplySavedStyle();
            forgeBridgeRegistered = true;
        }
    }

    private static void ensureForgeCommandRegistrarRegistered() {
        if (forgeCommandRegistrarRegistered) {
            return;
        }
        synchronized (GomodRuntimeBootstrap.class) {
            if (forgeCommandRegistrarRegistered) {
                return;
            }
            CommandBus.setPlatformRegistrar(command ->
                    ClientCommandHandler.instance.registerCommand(command));
            forgeCommandRegistrarRegistered = true;
        }
    }

    /** Mirrors {@link GomodForgeMod#init} when only the fallback bootstrap path runs. */
    public static void ensureStartGamePosted() {
        if (!started || startGamePosted) {
            return;
        }
        queueStartGameEvent();
    }

    private static void queueHudIconPreload() {
        try {
            GomodResourcePackInjector.ensureInstalled();
        } catch (Throwable ignored) {
        }
        GomodClasspathTextures.schedulePreloadHudIcons();
        BlitzVaultItemsData.scheduleWarmup();
    }

    private static void queueWaterClarityChunkReloadIfEnabled() {
        if (!ConfigManager.isWaterClarityEnabled()) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        Runnable reload = () -> {
            if (mc.theWorld == null) {
                return;
            }
            WaterClarityAtlasHook.syncFromConfig();
            WaterClarity.reloadChunkMeshesForSurfaceClarity();
        };
        try {
            mc.addScheduledTask(reload);
        } catch (Throwable t) {
            reload.run();
        }
    }

    private static void queueStartGameEvent() {
        if (startGamePosted) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        Runnable post = () -> {
            if (startGamePosted) {
                return;
            }
            startGamePosted = true;
            try {
                EventBus.post(new StartGameEvent.Post());
            } catch (Throwable ignored) {
            }
        };
        try {
            if (mc.isCallingFromMinecraftThread()) {
                post.run();
            } else {
                mc.addScheduledTask(post);
            }
        } catch (Throwable t) {
            post.run();
        }
    }
}
