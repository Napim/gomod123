package me.napim.gomod.mixins;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodPeerLunarFullAccess;
import me.napim.gomod.features.GoparkyBlitzReactions;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Runtime helpers for Lunar's native single-Companion player spawner. */
public final class LunarCompanionMultiTypeSupport {
    private static final String CLEAR_COMPANIONS_METHOD_22243 = "OCCRIICCOCOIORRORHIRHCHCOHHHHC";
    private static final String SPAWN_COMPANIONS_METHOD_22243 = "HHCHIRROHRIRRIHCCHHIOCHIIOHRHO";
    private static final String CLEAR_COMPANIONS_METHOD_2231 = "ICCRRCCIOOIRICIHHCIOROHHROOHHR";
    private static final String SPAWN_COMPANIONS_METHOD_2231 = "OHCHICOOCCRHOOHIRCHRCCCCRRHRHI";
    private static final String CLEAR_COMPANIONS_METHOD_CURRENT = "IOCRHHIIICRROOCIRRRRCOCRIRCHIR";
    private static final String SPAWN_COMPANIONS_METHOD_CURRENT = "CCIRCCCOIRRIHIOHOIHCCIOHCCIIRO";
    private static final AtomicBoolean RENDERER_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean RETRY_MERGE_FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean IGNORE_SUPPRESSION_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicInteger RETRY_MERGE_COUNT = new AtomicInteger();
    private static volatile Object lunarRenderer;
    @SuppressWarnings("rawtypes")
    private static volatile Map pendingCompanionRetries;

    private LunarCompanionMultiTypeSupport() {
    }

    /**
     * Incoming profiles may legitimately contain several companions even when this viewer has
     * disabled the separate equip-multiple preference. Preserve FullAccess-profile rendering,
     * and also render the local user's multiple companions when the local equip option is enabled.
     */
    public static boolean isMultiCompanionRenderingEnabled() {
        return ConfigManager.isLunarTweaksEnabled()
                || ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics();
    }

    public static void recordRendererInvocation(Object renderer) {
        if (renderer != null) {
            lunarRenderer = renderer;
            GoparkyBlitzReactions.rememberLunarRenderer(renderer);
        }
        if (RENDERER_REPORTED.compareAndSet(false, true)) {
            LunarConnectionDiagnostics.record("cosmetics.multi_companion",
                    "renderer=active; nativeFirstMatchReturn=patched; fullAccessOrLocalEquip=true; localIgnore=active");
        }
    }

    /** Called immediately after Lunar clears this player's existing companion state. */
    public static boolean shouldSuppressCompanionSpawn(Object lunarPlayer) {
        try {
            Object rawId = invokeNoArg(lunarPlayer, "bridge$getUniqueID");
            if (!(rawId instanceof UUID)
                    || !GomodPeerLunarFullAccess.shouldHideLunarCosmetics((UUID) rawId)) {
                return false;
            }
            if (IGNORE_SUPPRESSION_REPORTED.compareAndSet(false, true)) {
                LunarConnectionDiagnostics.record("cosmetics.local_ignore",
                        "companionSpawnSuppressed=true; priorStateCleared=true");
            }
            return true;
        } catch (Throwable error) {
            if (REFRESH_FAILURE_REPORTED.compareAndSet(false, true)) {
                LunarConnectionDiagnostics.record("cosmetics.local_ignore",
                        "companionFilterFailed=" + error.getClass().getSimpleName());
            }
            return false;
        }
    }

    /**
     * Re-run Lunar's own cleanup/spawn routine for an online player after their name is
     * added to or removed from the local filter. This makes edits take effect immediately.
     */
    @SuppressWarnings("rawtypes")
    public static int refreshPlayerByName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return 0;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Object renderer = lunarRenderer;
        if (mc == null || mc.theWorld == null || renderer == null) {
            return 0;
        }

        int refreshed = 0;
        for (Object candidate : mc.theWorld.playerEntities) {
            if (!(candidate instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) candidate;
            if (player == mc.thePlayer || player.getName() == null
                    || !playerName.equalsIgnoreCase(player.getName())) {
                continue;
            }
            try {
                // This Lunar method also removes the UUID's own pending retry before the
                // public spawner's early-return check can see it.
                try {
                    invokeNativeCleanup(renderer, player);
                } catch (Throwable cleanupError) {
                    if (REFRESH_FAILURE_REPORTED.compareAndSet(false, true)) {
                        LunarConnectionDiagnostics.record("cosmetics.local_ignore",
                                "nativeCleanupFailed=" + cleanupError.getClass().getSimpleName());
                    }
                }
                Map retries = pendingCompanionRetries;
                if (retries != null) {
                    retries.remove(player.getUniqueID());
                }
                invokeNativeSpawner(renderer, player);
                refreshed++;
            } catch (Throwable error) {
                if (REFRESH_FAILURE_REPORTED.compareAndSet(false, true)) {
                    LunarConnectionDiagnostics.record("cosmetics.local_ignore",
                            "playerRefreshFailed=" + error.getClass().getSimpleName());
                }
            }
        }
        if (refreshed > 0) {
            LunarConnectionDiagnostics.record("cosmetics.local_ignore",
                    "nearbyPlayersRefreshed=" + refreshed);
        }
        return refreshed;
    }

    private static void invokeNativeSpawner(Object renderer, EntityPlayer player) throws Exception {
        for (Method method : renderer.getClass().getMethods()) {
                if (!isSpawnMethod(method.getName())
                    || method.getParameterTypes().length != 1
                    || !method.getParameterTypes()[0].isInstance(player)) {
                continue;
            }
            method.invoke(renderer, player);
            return;
        }
        throw new NoSuchMethodException("Lunar companion player-spawn method");
    }

    private static void invokeNativeCleanup(Object renderer, EntityPlayer player) throws Exception {
        for (Class<?> type = renderer.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isClearMethod(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0].isInstance(player)) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(renderer, player);
                return;
            }
        }
        throw new NoSuchMethodException("Lunar companion cleanup method");
    }

    private static boolean isSpawnMethod(String name) {
        return SPAWN_COMPANIONS_METHOD_CURRENT.equals(name)
                || SPAWN_COMPANIONS_METHOD_2231.equals(name)
                || SPAWN_COMPANIONS_METHOD_22243.equals(name);
    }

    private static boolean isClearMethod(String name) {
        return CLEAR_COMPANIONS_METHOD_CURRENT.equals(name)
                || CLEAR_COMPANIONS_METHOD_2231.equals(name)
                || CLEAR_COMPANIONS_METHOD_22243.equals(name);
    }

    /**
     * Lunar stores a single pending Companion retry per player UUID. After the renderer is
     * allowed to continue through the outfit, combine additional not-yet-loaded models into
     * that existing retry entry instead of silently dropping them.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object mergeRetry(Map retries, Object key, Function mappingFunction) {
        if (retries != null) {
            pendingCompanionRetries = retries;
        }
        if (!isMultiCompanionRenderingEnabled()) {
            return retries.computeIfAbsent(key, mappingFunction);
        }
        return mergeRetryWhenEnabled(retries, key, mappingFunction);
    }

    /** Pure retry-entry merge, separated so the sequencing behavior can be verified offline. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object mergeRetryWhenEnabled(Map retries, Object key, Function mappingFunction) {
        Object existing = retries.get(key);
        if (existing == null) {
            return retries.computeIfAbsent(key, mappingFunction);
        }

        try {
            Object additional = mappingFunction.apply(key);
            if (additional == null) {
                return existing;
            }

            BooleanSupplier previousSupplier = (BooleanSupplier) invokeNoArg(existing, "left");
            BooleanSupplier additionalSupplier = (BooleanSupplier) invokeNoArg(additional, "left");
            if (previousSupplier == null || additionalSupplier == null) {
                throw new IllegalStateException("Lunar retry pair has no BooleanSupplier");
            }

            invokeOneArg(existing, "left", new RetrySequence(previousSupplier, additionalSupplier));
            // A second model may need its own resource-load retries; restart the shared budget.
            try {
                invokeOneArg(existing, "right", Integer.valueOf(10));
            } catch (NoSuchMethodException ignored) {
                // Keep Lunar's existing retry budget if this Lunar build changes the pair API.
            }

            int merged = RETRY_MERGE_COUNT.incrementAndGet();
            LunarConnectionDiagnostics.record("cosmetics.multi_companion",
                    "pendingCompanionRetryMerged=" + merged);
            return existing;
        } catch (Throwable error) {
            if (RETRY_MERGE_FAILURE_REPORTED.compareAndSet(false, true)) {
                LunarConnectionDiagnostics.record("cosmetics.multi_companion",
                        "retryMergeFailed=" + error.getClass().getSimpleName());
                System.err.println("[gomod] Could not combine Lunar Companion load retries: "
                        + error.getClass().getSimpleName());
            }
            return existing;
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static void invokeOneArg(Object target, String name, Object argument) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (argument == null || parameter.isInstance(argument)
                    || (parameter == int.class && argument instanceof Integer)) {
                method.invoke(target, argument);
                return;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static final class RetrySequence implements BooleanSupplier {
        private final BooleanSupplier[] suppliers;
        private final boolean[] completed;

        private RetrySequence(BooleanSupplier first, BooleanSupplier second) {
            this.suppliers = new BooleanSupplier[] {first, second};
            this.completed = new boolean[suppliers.length];
        }

        @Override
        public boolean getAsBoolean() {
            for (int i = 0; i < suppliers.length; i++) {
                if (!completed[i]) {
                    completed[i] = suppliers[i].getAsBoolean();
                }
            }
            for (boolean done : completed) {
                if (!done) {
                    return false;
                }
            }
            return true;
        }
    }
}
