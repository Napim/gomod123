package me.napim.gomod.features;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Defers lightning bolts to after the entity pass so players stay visible through thunder
 * (vanilla MC-44244: weather effects render before entities and corrupt depth).
 */
public final class LightningRenderFix {

    private static final List<DeferredBolt> DEFERRED = new ArrayList<DeferredBolt>(8);
    private static final List<Integer> DEFERRED_IDS = new ArrayList<Integer>(8);
    private static final ThreadLocal<Boolean> FLUSHING = ThreadLocal.withInitial(() -> false);
    /**
     * Forge already runs the depth-mask protection in {@code RenderLightningBoltMixin}.  Deferring the
     * entity as well is unsafe there because Forge/OptiFine render dispatch can enter more than one of
     * RenderGlobal's and RenderManager's entity paths for the same weather effect.  Cancelling those
     * nested dispatches and replaying the bolt at the end of the pass caused intermittent stalls when
     * Hypixel spawned lightning for timed events (notably Blitz Star and SkyWars refills).
     */
    private static final boolean DEFER_RENDER_SUPPORTED = !isForgeRuntime();

    private LightningRenderFix() {}

    public static boolean isEnabled() {
        return DEFER_RENDER_SUPPORTED && LightningSettings.isFixRenderEnabled();
    }

    private static boolean isForgeRuntime() {
        try {
            Class.forName("net.minecraftforge.common.MinecraftForge", false,
                    LightningRenderFix.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldSkipAndDefer(Entity entity, float partialTicks) {
        if (!isEnabled() || FLUSHING.get() || !(entity instanceof EntityLightningBolt)) {
            return false;
        }
        int id = entity.getEntityId();
        for (int i = 0; i < DEFERRED_IDS.size(); i++) {
            if (DEFERRED_IDS.get(i) == id) {
                return true;
            }
        }
        DEFERRED_IDS.add(id);
        DEFERRED.add(new DeferredBolt((EntityLightningBolt) entity, partialTicks));
        return true;
    }

    public static boolean hasDeferred() {
        return !DEFERRED.isEmpty();
    }

    public static void flushDeferred(RenderManager rm) {
        if (!isEnabled() || rm == null || DEFERRED.isEmpty()) {
            clearDeferred();
            return;
        }
        List<DeferredBolt> batch = new ArrayList<DeferredBolt>(DEFERRED);
        clearDeferred();
        FLUSHING.set(true);
        boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            for (DeferredBolt entry : batch) {
                Entity bolt = entry.bolt;
                if (bolt == null || bolt.isDead) {
                    continue;
                }
                try {
                    rm.renderEntitySimple(bolt, entry.partialTicks);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            GlStateManager.depthMask(savedDepthMask);
            FLUSHING.set(false);
        }
    }

    public static void clearDeferred() {
        DEFERRED.clear();
        DEFERRED_IDS.clear();
    }

    private static final class DeferredBolt {
        private final EntityLightningBolt bolt;
        private final float partialTicks;

        private DeferredBolt(EntityLightningBolt bolt, float partialTicks) {
            this.bolt = bolt;
            this.partialTicks = partialTicks;
        }
    }
}
