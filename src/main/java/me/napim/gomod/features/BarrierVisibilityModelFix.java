package me.napim.gomod.features;

import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.block.statemap.BlockStateMapper;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

/**
 * Legacy safety net when {@link Blocks#barrier} still bakes as {@code missingno} despite
 * {@link me.napim.gomod.mixins.BlockStateMapperBarrierMixin} (e.g. another mod replaces models later).
 *
 * <p>Previously this reflectively stripped {@code barrier} from Forge's built-in skip set and then
 * called {@link Minecraft#refreshResources()}, which blocked the client for several seconds on the
 * title screen. Blacklist bypass is now handled entirely by the redirect mixin (same approach as the
 * original BarrierVisibility weave mod); this class no longer triggers a resource reload.
 */
public final class BarrierVisibilityModelFix {

    private static volatile boolean attempted;
    private static volatile boolean forceReloadAttempted;
    private static volatile boolean warnedBrokenModel;
    private static int removeFailTicks;
    private static int postReloadWaitTicks;

    private BarrierVisibilityModelFix() {}

    public static void requestRecheck() {
        attempted = false;
        removeFailTicks = 0;
        postReloadWaitTicks = 0;
    }

    /**
     * Call from the client tick (e.g. Forge bridge) — cheap no-op after first resolution.
     */
    public static void clientTickEnd() {
        if (attempted) {
            return;
        }
        if (!ConfigManager.isBarrierVisibilityEnabled()) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null || mc.getBlockRendererDispatcher() == null || mc.theWorld == null) {
            return;
        }
        GomodResourcePackInjector.ensureInstalled();
        if (!isBarrierModelBroken(mc)) {
            attempted = true;
            return;
        }
        if (postReloadWaitTicks > 0) {
            postReloadWaitTicks--;
            return;
        }
        if (!tryRemoveBarrierFromBuiltInSkipSet(mc)) {
            removeFailTicks++;
            if (removeFailTicks < 20 && !forceReloadAttempted) {
                return;
            }
        }
        if (!forceReloadAttempted) {
            forceReloadAttempted = true;
            System.out.println("[gomod] Barrier block model is missing; reloading resources once so barriers can bake.");
            GomodResourcePackInjector.forceReloadNow();
            BarrierVisibilityAtlasHook.clearPngCache();
            BarrierVisibilityAtlasHook.scheduleApplySavedStyle();
            BarrierVisibilityRangeTicker.requestFullBarrierRemesh();
            postReloadWaitTicks = 20;
            return;
        }
        if (isBarrierModelBroken(mc)) {
            warnBrokenOnce();
        }
        attempted = true;
    }

    private static void warnBrokenOnce() {
        if (warnedBrokenModel) {
            return;
        }
        warnedBrokenModel = true;
        System.err.println("[gomod] Barrier block model still uses missingno after skip-set patch. "
                + "Barrier visibility may look wrong until you reload resources (F3+T) or restart.");
    }

    private static boolean isBarrierModelBroken(Minecraft mc) {
        try {
            BlockRendererDispatcher disp = mc.getBlockRendererDispatcher();
            if (disp == null) {
                return false;
            }
            IBlockState state = Blocks.barrier.getDefaultState();
            IBakedModel model = disp.getBlockModelShapes().getModelForState(state);
            if (model == null) {
                return true;
            }
            TextureAtlasSprite particle = model.getParticleTexture();
            if (particle == null) {
                return true;
            }
            String name = particle.getIconName();
            return name != null && name.contains("missingno");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tryRemoveBarrierFromBuiltInSkipSet(Minecraft mc) {
        try {
            BlockStateMapper mapper = mc.getBlockRendererDispatcher().getBlockModelShapes().getBlockStateMapper();
            if (mapper == null) {
                return false;
            }
            for (Class<?> c = mapper.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null || !Set.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object raw = f.get(mapper);
                    if (!(raw instanceof Set)) {
                        continue;
                    }
                    Set<?> set = (Set<?>) raw;
                    if (!set.isEmpty()) {
                        Object first = set.iterator().next();
                        if (!(first instanceof net.minecraft.block.Block)) {
                            continue;
                        }
                    }
                    if (!set.contains(Blocks.barrier)) {
                        continue;
                    }
                    HashSet<Object> copy = new HashSet<Object>(set);
                    if (!copy.remove(Blocks.barrier)) {
                        continue;
                    }
                    f.set(mapper, copy);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
