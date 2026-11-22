package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers {@link ClasspathGomodResourcePack} with the vanilla repository so {@code gomod:} assets resolve.
 * Resource reload is queued for the next client tick so the first witch SFX does not block the current frame
 * on a synchronous {@link Minecraft#refreshResources()} (can take seconds on large pack sets).
 */
public final class GomodResourcePackInjector {

    private static final int MAX_INSTALL_ATTEMPTS = 40;
    private static final Set<String> LOGGED_ONCE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static volatile boolean installTaskScheduled;
    private static volatile boolean installSucceeded;
    private static volatile boolean installAbandoned;
    private static volatile int installFailCount;
    /** Pack is registered; full {@link Minecraft#refreshResources()} waits until in a world (avoids main-menu stalls). */
    private static volatile boolean packRegistered;
    private static volatile boolean refreshPendingInWorld;
    private static volatile boolean refreshInProgress;
    private static volatile boolean forceReloadQueued;
    private static volatile int forceReloadAttempts;
    private static volatile long forceReloadRetryAfterMs;
    private static final int MAX_FORCE_RELOAD_ATTEMPTS = 4;
    private static final long FORCE_RELOAD_RETRY_DELAY_MS = 10000L;
    private static final long SOUND_PROBE_INTERVAL_NANOS = 1_000_000_000L;
    private static final ResourceLocation SOUNDS_JSON = new ResourceLocation("gomod", "sounds.json");
    private static final ResourceLocation WITCH_SAMPLE = new ResourceLocation("gomod", "sounds/mob/witch/witch_drink1.ogg");

    private static IResourceManager soundResourceManager;
    private static boolean soundReloadObserved;
    private static boolean soundResourcesReady;
    private static long nextSoundProbeNanos;
    private static long soundResourceGeneration;

    private static void log(String msg) {
        System.out.println("[gomod][injector] " + msg);
    }

    private static void logOnce(String key, String msg) {
        if (LOGGED_ONCE.add(key)) {
            log(msg);
        }
    }

    private GomodResourcePackInjector() {
    }

    public static boolean wasSuccessfullyInstalled() {
        return installSucceeded;
    }

    public static boolean isPackRegistered() {
        return packRegistered;
    }

    public static boolean isGomodDomainActive() {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return false;
        }
        return hasWitchSoundResources(mc);
    }

    /** Client-thread generation of the active resource set, including same-manager reloads. */
    public static long getSoundResourceGeneration() {
        return soundResourceGeneration;
    }

    /** True only when the active resource manager can open the requested packaged resource. */
    public static boolean hasActiveResource(net.minecraft.util.ResourceLocation location) {
        if (location == null) {
            return false;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return false;
        }
        if (mc == null || mc.getResourceManager() == null) {
            return false;
        }
        try {
            net.minecraft.client.resources.IResource resource = mc.getResourceManager().getResource(location);
            try (InputStream in = resource.getInputStream()) {
                return in != null;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers the classpath pack without {@link Minecraft#refreshResources()} (that reload freezes the client).
     * Sounds/assets load lazily when needed; HUD icons use {@link me.napim.gomod.util.GomodClasspathTextures}.
     */
    public static void tickDeferredRefresh(Minecraft mc) {
        // intentionally empty — full resource reload removed to avoid main-menu / join-world freezes
    }

    public static void ensureInstalled() {
        if (installSucceeded || installAbandoned || packRegistered) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            logOnce("mc-threw", "ensureInstalled() Minecraft.getMinecraft() threw: " + t.getMessage());
            return;
        }
        if (mc == null) {
            return;
        }
        Runnable registerAndQueueRefresh = () -> {
            try {
                if (installSucceeded) {
                    return;
                }
                ClasspathGomodResourcePack pack = new ClasspathGomodResourcePack();
                boolean hasGomodSounds = pack.resourceExists(new net.minecraft.util.ResourceLocation("gomod", "sounds.json"));
                boolean hasGomod123Assets = pack.resourceExists(
                        new net.minecraft.util.ResourceLocation("gomod123", "lang/en_US.lang"));
                if (!hasGomodSounds && !hasGomod123Assets) {
                    installAbandoned = true;
                    logOnce("no-assets", "ensureInstalled(): no gomod / gomod123 assets on classpath");
                    return;
                }
                if (isGomodPackAlreadyRegistered(mc)) {
                    queueDeferredRefreshResources(mc);
                    return;
                }
                if (tryAppendToRepository(mc, pack)) {
                    log("ensureInstalled(): appended gomod pack; scheduling refresh");
                    queueDeferredRefreshResources(mc);
                    return;
                }
                if (tryAppendToDefaultResourcePacks(mc, pack)) {
                    log("ensureInstalled(): appended gomod pack via defaultResourcePacks; scheduling refresh");
                    queueDeferredRefreshResources(mc);
                    return;
                }
                if (++installFailCount >= MAX_INSTALL_ATTEMPTS) {
                    installAbandoned = true;
                    logOnce("install-give-up",
                            "ensureInstalled(): gave up registering gomod resource pack after " + MAX_INSTALL_ATTEMPTS + " attempts");
                }
            } catch (Throwable t) {
                logOnce("register-failed", "ensureInstalled() register path failed: " + t.getMessage());
            }
        };

        if (mc.isCallingFromMinecraftThread()) {
            registerAndQueueRefresh.run();
            return;
        }
        synchronized (GomodResourcePackInjector.class) {
            if (installTaskScheduled) {
                return;
            }
            installTaskScheduled = true;
        }
        try {
            mc.addScheduledTask(() -> {
                try {
                    registerAndQueueRefresh.run();
                } finally {
                    synchronized (GomodResourcePackInjector.class) {
                        installTaskScheduled = false;
                    }
                }
            });
        } catch (Throwable t) {
            synchronized (GomodResourcePackInjector.class) {
                installTaskScheduled = false;
            }
            logOnce("schedule-failed", "ensureInstalled() schedule failed: " + t.getMessage());
        }
    }

    public static void ensureDomainLoaded() {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        if (hasWitchSoundResources(mc)) {
            installSucceeded = true;
            forceReloadAttempts = 0;
            forceReloadRetryAfterMs = 0L;
            return;
        }
        ensureInstalled();
        synchronized (GomodResourcePackInjector.class) {
            long now = System.currentTimeMillis();
            if (forceReloadQueued || forceReloadAttempts >= MAX_FORCE_RELOAD_ATTEMPTS
                    || now < forceReloadRetryAfterMs) {
                return;
            }
            forceReloadAttempts++;
            forceReloadRetryAfterMs = now + FORCE_RELOAD_RETRY_DELAY_MS;
        }
        log("active gomod sound resources missing; scheduling reload attempt " + forceReloadAttempts
                + "/" + MAX_FORCE_RELOAD_ATTEMPTS);
        queueForceReload(mc);
    }

    public static boolean forceReloadNow() {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return false;
        }
        if (mc == null) {
            return false;
        }
        installAbandoned = false;
        installFailCount = 0;
        ensureInstalled();
        if (mc.isCallingFromMinecraftThread()) {
            synchronized (GomodResourcePackInjector.class) {
                forceReloadAttempts = Math.min(forceReloadAttempts + 1, MAX_FORCE_RELOAD_ATTEMPTS);
                forceReloadRetryAfterMs = System.currentTimeMillis() + FORCE_RELOAD_RETRY_DELAY_MS;
            }
            return forceReloadWithPack(mc);
        }
        synchronized (GomodResourcePackInjector.class) {
            if (forceReloadQueued) {
                return false;
            }
            forceReloadAttempts = Math.min(forceReloadAttempts + 1, MAX_FORCE_RELOAD_ATTEMPTS);
            forceReloadRetryAfterMs = System.currentTimeMillis() + FORCE_RELOAD_RETRY_DELAY_MS;
            forceReloadQueued = true;
        }
        try {
            mc.addScheduledTask(() -> {
                try {
                    forceReloadWithPack(mc);
                } finally {
                    synchronized (GomodResourcePackInjector.class) {
                        forceReloadQueued = false;
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            synchronized (GomodResourcePackInjector.class) {
                forceReloadQueued = false;
            }
            return false;
        }
    }

    private static boolean isGomodPackAlreadyRegistered(Minecraft mc) {
        ResourcePackRepository repo = MinecraftPrivateAccess.getResourcePackRepository(mc);
        if (listContainsGomodPack(MinecraftPrivateAccess.getRepositoryEntries(repo))) {
            return true;
        }
        return listContainsGomodPack(MinecraftPrivateAccess.getDefaultResourcePacks(mc));
    }

    private static boolean listContainsGomodPack(List<?> entries) {
        if (entries == null) {
            return false;
        }
        for (Object o : entries) {
            if (o == null) {
                continue;
            }
            IResourcePack rp = MinecraftPrivateAccess.getResourcePackFromEntry(o);
            if (rp instanceof ClasspathGomodResourcePack) {
                return true;
            }
            if (o instanceof ClasspathGomodResourcePack) {
                return true;
            }
        }
        return false;
    }

    private static void queueForceReload(Minecraft mc) {
        if (mc == null) {
            return;
        }
        synchronized (GomodResourcePackInjector.class) {
            if (forceReloadQueued) {
                return;
            }
            forceReloadQueued = true;
        }
        try {
            mc.addScheduledTask(() -> {
                try {
                    forceReloadWithPack(mc);
                } finally {
                    synchronized (GomodResourcePackInjector.class) {
                        forceReloadQueued = false;
                    }
                }
            });
        } catch (Throwable t) {
            synchronized (GomodResourcePackInjector.class) {
                forceReloadQueued = false;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean tryAppendToRepository(Minecraft mc, ClasspathGomodResourcePack pack) {
        ResourcePackRepository repo = MinecraftPrivateAccess.getResourcePackRepository(mc);
        if (repo == null) {
            logOnce("repo-null", "tryAppendToRepository: repo is null");
            return false;
        }
        List<ResourcePackRepository.Entry> entries = MinecraftPrivateAccess.getRepositoryEntries(repo);
        if (entries == null) {
            logOnce("repo-entries", "tryAppendToRepository: could not find repository entry list");
            return false;
        }
        Object entry = newRepositoryEntry(repo, pack);
        if (entry == null) {
            logOnce("repo-entry-ctor", "tryAppendToRepository: could not construct repository entry");
            return false;
        }
        ((List) entries).add(entry);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean tryAppendToDefaultResourcePacks(Minecraft mc, ClasspathGomodResourcePack pack) {
        List<IResourcePack> defaults = MinecraftPrivateAccess.getDefaultResourcePacks(mc);
        if (defaults == null) {
            logOnce("default-packs", "defaultResourcePacks: list not found");
            return false;
        }
        try {
            for (Object o : defaults) {
                if (o instanceof ClasspathGomodResourcePack) {
                    return true;
                }
            }
            ((List) defaults).add(pack);
            return true;
        } catch (Throwable t) {
            logOnce("default-packs-add", "defaultResourcePacks add failed: " + t.getMessage());
            return false;
        }
    }

    private static void queueDeferredRefreshResources(Minecraft mc) {
        if (mc == null || installSucceeded) {
            return;
        }
        packRegistered = true;
        invalidateSoundResources(mc.getResourceManager());
        installSucceeded = hasWitchSoundResources(mc);
        refreshPendingInWorld = false;
        log("gomod pack registered; active sound resources=" + installSucceeded
                + ", gomod namespace=" + hasGomodDomain(mc)
                + " (skipped blocking refreshResources).");
    }

    private static Object newRepositoryEntry(ResourcePackRepository repo, ClasspathGomodResourcePack pack) {
        try {
            Constructor<?> c = ResourcePackRepository.Entry.class.getDeclaredConstructor(
                    ResourcePackRepository.class,
                    net.minecraft.client.resources.IResourcePack.class);
            c.setAccessible(true);
            return c.newInstance(repo, pack);
        } catch (Throwable t1) {
            try {
                for (Constructor<?> c : ResourcePackRepository.Entry.class.getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && net.minecraft.client.resources.IResourcePack.class.isAssignableFrom(p[0])) {
                        c.setAccessible(true);
                        return c.newInstance(pack);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean hasGomodDomain(Minecraft mc) {
        if (mc == null || mc.getResourceManager() == null) {
            return false;
        }
        try {
            java.util.Set<String> domains = mc.getResourceManager().getResourceDomains();
            return domains.contains("gomod") || domains.contains("gomod123");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasWitchSoundResources(Minecraft mc) {
        if (mc == null || mc.getResourceManager() == null) {
            return false;
        }
        IResourceManager manager = mc.getResourceManager();
        if (!mc.isCallingFromMinecraftThread()) {
            return probeSoundResources(manager);
        }
        if (manager != soundResourceManager) {
            soundResourceManager = manager;
            soundReloadObserved = false;
            invalidateSoundResources(manager);
            if (manager instanceof IReloadableResourceManager) {
                try {
                    ((IReloadableResourceManager) manager)
                            .registerReloadListener(GomodResourcePackInjector::invalidateSoundResources);
                    soundReloadObserved = true;
                } catch (Throwable ignored) {
                    // A transformed manager can fall back to the same bounded periodic probe.
                }
            }
        }
        if (soundResourcesReady && soundReloadObserved) {
            return true;
        }
        long now = System.nanoTime();
        if (nextSoundProbeNanos == 0L || now - nextSoundProbeNanos >= 0L) {
            soundResourcesReady = probeSoundResources(manager);
            nextSoundProbeNanos = now + SOUND_PROBE_INTERVAL_NANOS;
        }
        return soundResourcesReady;
    }

    private static void invalidateSoundResources(IResourceManager manager) {
        if (manager == soundResourceManager) {
            soundResourcesReady = false;
            nextSoundProbeNanos = 0L;
            soundResourceGeneration++;
        }
    }

    private static boolean probeSoundResources(IResourceManager manager) {
        return canReadResource(manager, SOUNDS_JSON) && canReadResource(manager, WITCH_SAMPLE);
    }

    private static boolean canReadResource(IResourceManager manager, ResourceLocation location) {
        try {
            net.minecraft.client.resources.IResource resource = manager.getResource(location);
            try (InputStream in = resource.getInputStream()) {
                return in != null && in.read() >= 0;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean forceReloadWithPack(Minecraft mc) {
        try {
            List<IResourcePack> packs = new ArrayList<>();
            List<IResourcePack> defaults = MinecraftPrivateAccess.getDefaultResourcePacks(mc);
            if (defaults != null) {
                packs.addAll(defaults);
            }
            ClasspathGomodResourcePack pack = new ClasspathGomodResourcePack();
            if (!containsGomodPack(packs)) {
                packs.add(pack);
            }
            ResourcePackRepository repo = MinecraftPrivateAccess.getResourcePackRepository(mc);
            if (repo != null) {
                List<ResourcePackRepository.Entry> entries = MinecraftPrivateAccess.getRepositoryEntries(repo);
                if (entries != null) {
                    for (Object e : entries) {
                        IResourcePack rp = MinecraftPrivateAccess.getResourcePackFromEntry(e);
                        if (rp != null && !packs.contains(rp)) {
                            packs.add(rp);
                        }
                    }
                }
            }
            boolean directReload = invokeReloadResources(mc, packs);
            invalidateSoundResources(mc.getResourceManager());
            if (!directReload || !hasWitchSoundResources(mc)) {
                log("forceReloadWithPack: direct resource-manager reload "
                        + (directReload ? "did not activate bundled assets" : "unavailable")
                        + "; using Minecraft.refreshResources()");
                mc.refreshResources();
                invalidateSoundResources(mc.getResourceManager());
            }
            boolean active = hasWitchSoundResources(mc);
            installSucceeded = active;
            log("forceReloadWithPack: done; sound resources active=" + active
                    + ", gomod namespace=" + hasGomodDomain(mc));
            return active;
        } catch (Throwable t) {
            logOnce("force-reload-failed", "forceReloadWithPack failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean invokeReloadResources(Minecraft mc, List<IResourcePack> packs) {
        if (mc == null || mc.getResourceManager() == null) {
            return false;
        }
        Object rm = mc.getResourceManager();
        Method reload = null;
        for (Class<?> type = rm.getClass(); type != null && reload == null; type = type.getSuperclass()) {
            for (Method m : type.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(packs.getClass())) {
                    String n = m.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("reload")) {
                        reload = m;
                        break;
                    }
                }
            }
        }
        if (reload == null) {
            return false;
        }
        try {
            reload.setAccessible(true);
            reload.invoke(rm, packs);
            return true;
        } catch (Throwable t) {
            logOnce("force-reload-invoke", "forceReloadWithPack: " + reload.getName()
                    + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean containsGomodPack(List<IResourcePack> packs) {
        if (packs == null) {
            return false;
        }
        for (IResourcePack p : packs) {
            if (p instanceof ClasspathGomodResourcePack) {
                return true;
            }
        }
        return false;
    }
}
