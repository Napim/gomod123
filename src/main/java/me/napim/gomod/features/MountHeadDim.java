package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.lang.reflect.Field;

/**
 * Lowers the opacity of your mount in first and third person.
 * Strategy 8 uses {@code RenderManager} + {@code GlStateManager} hooks (see config {@code mountHeadDimStrategy}).
 * Local player model drawn as a passenger is excluded from GL hooks so you stay solid in third person.
 */
public final class MountHeadDim {
    /**
     * Depth mode 6 draws the mount twice: opaque depth shell (like mode 1) then transparent color (like mode 2).
     * Inner {@code renderEntitySimple} calls use this phase for GL setup.
     */
    public enum MountHybridPhase {
        NONE,
        PREPASS,
        COLOR
    }

    private static final FloatBuffer COLOR_BUF = BufferUtils.createFloatBuffer(4);

    private static final ThreadLocal<MountHybridPhase> mountHybridPhase =
            ThreadLocal.withInitial(() -> MountHybridPhase.NONE);
    /** True only while {@link #runHybridMountDrawIfNeeded} runs the two inner {@code renderEntitySimple} calls. */
    private static final ThreadLocal<Boolean> hybridMountInnerCall = ThreadLocal.withInitial(() -> false);

    private static EntityLivingBase activeRideDimEntity = null;

    private static float layerSavedR = 1f;
    private static float layerSavedG = 1f;
    private static float layerSavedB = 1f;
    private static boolean layerTintActive = false;

    private MountHeadDim() {
    }

    public static int getStrategy() {
        return ConfigManager.getMountHeadDimStrategy();
    }

    public static void setStrategy(int strategy) {
        ConfigManager.setMountHeadDimStrategy(strategy);
    }

    public static String describeStrategy(int strategy) {
        switch (strategy) {
            case 1:
                return "RenderHorse frame + GL color read + model + layers (original)";
            case 2:
                return "No frame; detect EntityHorse each draw + GL read + model + layers";
            case 3:
                return "No frame; EntityHorse + GL read + main model only (no layer pass)";
            case 4:
                return "RenderHorse frame + fixed white tint + layers (broken GL read workaround)";
            case 5:
                return "No frame; EntityHorse + white tint + layers";
            case 6:
                return "No frame; ANY entity you ride (not only EntityHorse) + GL read + layers";
            case 7:
                return "Wrap renderLivingAt (full body+layers in one pass; requires mixin apply)";
            case 8:
                return "DEFAULT: wrap RenderManager.renderEntitySimple (world pass; works when inner hooks miss)";
            default:
                return "unknown";
        }
    }

    /**
     * Strategies 1–7 tint inside {@link me.napim.gomod.mixins.RendererLivingEntityMixin}.
     * Strategy 8+ tints in {@link me.napim.gomod.mixins.RenderManagerMixin} around {@code renderEntitySimple}.
     */
    public static boolean useLegacyRendererLivingEntityTint() {
        return getStrategy() < 8 && isFirstPersonView();
    }

    /**
     * Strategy-8 path (defer + {@code GlStateManager} hooks). Must not depend on {@code getAlpha() &lt; 0.99f}:
     * that made opacity x1.00 use vanilla rendering and any lower value switch pipelines, which looked
     * “fully transparent” (broken deferred state) for many users.
     */
    public static boolean useRenderManagerEntitySimpleTint() {
        return isEnabled() && (getStrategy() >= 8 || !isFirstPersonView());
    }

    private static boolean isFirstPersonView() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 0;
    }

    /** Pass mode 1–4 from config; see {@link #describeFlushMode(int)} and {@code mountFlushMode}. */
    public static int getFlushMode() {
        return ConfigManager.getMountFlushMode();
    }

    /** Strategy 8 + pass mode ≠ 3: skip mount in the main entity loop and flush later. */
    public static boolean shouldDeferMountDraw() {
        return useRenderManagerEntitySimpleTint() && (!isFirstPersonView() || getFlushMode() != 3);
    }

    /** Flush after {@code RenderGlobal.renderEntities} TAIL (pass 1). */
    public static boolean shouldFlushAfterEntitiesTail() {
        return shouldDeferMountDraw() && isFirstPersonView() && getFlushMode() == 1;
    }

    /** Flush after translucent {@code renderBlockLayer} (pass 2; better for stained glass). */
    public static boolean shouldFlushAfterTranslucentLayer() {
        return shouldDeferMountDraw() && (!isFirstPersonView() || getFlushMode() == 2);
    }

    /** Flush at {@code EntityRenderer.renderWorld} TAIL (pass 4; latest in frame). */
    public static boolean shouldFlushAfterRenderWorld() {
        return shouldDeferMountDraw() && getFlushMode() == 4;
    }

    public static String describeFlushMode(int mode) {
        switch (mode) {
            case 1:
                return "After entity pass (flush at renderEntities TAIL)";
            case 2:
                return "After translucent blocks (stained glass / water first)";
            case 3:
                return "No defer — normal draw order (simplest GL)";
            case 4:
                return "After renderWorld TAIL (latest; OF/Lunar fallback)";
            default:
                return "unknown";
        }
    }

    /** Saved across outer {@code renderEntitySimple} mount pass (alpha test would discard low-alpha fragments). */
    private static boolean mountDimSavedAlphaTest = false;
    private static boolean mountDimSavedCullFace = false;
    private static boolean patcherHorseOpacityOverrideActive = false;
    private static float patcherHorseOpacitySaved = 1.0f;
    private static Field patcherRiddenHorseOpacityField = null;
    private static boolean patcherRiddenHorseOpacityLookupDone = false;
    private static boolean patcherHorseOpacityRuntimeCompatActive = false;
    private static float patcherHorseOpacityRuntimeSaved = 1.0f;

    private static final ThreadLocal<Integer> renderManagerSimpleDepth = ThreadLocal.withInitial(() -> 0);
    /** While &gt; 0, local player is being drawn as rider — skip mount GL overrides (avoids ghostly body). */
    private static final ThreadLocal<Integer> mountPassengerGlSuppressDepth = ThreadLocal.withInitial(() -> 0);

    /**
     * Strategy 8 + pass 1/2/4: skip the mount in the main entity loop and flush once (see {@link #getFlushMode()}).
     */
    private static volatile Entity deferredMountEntity = null;
    private static volatile float deferredMountPartialTicks = 0f;
    private static final ThreadLocal<Boolean> flushingDeferredMount = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> worldRenderEntitiesDepth = ThreadLocal.withInitial(() -> 0);

    /** While strategy-8 mount GL hooks run, the entity being dimmed (for per-type opacity). */
    private static final ThreadLocal<Entity> mountDimGlTarget = ThreadLocal.withInitial(() -> null);

    public static void beginWorldRenderEntitiesPass() {
        applyPatcherHorseOpacityRuntimeCompat();
        worldRenderEntitiesDepth.set(worldRenderEntitiesDepth.get() + 1);
    }

    public static void endWorldRenderEntitiesPass() {
        int d = worldRenderEntitiesDepth.get() - 1;
        worldRenderEntitiesDepth.set(Math.max(0, d));
    }

    private static boolean isInWorldRenderEntitiesPass() {
        return worldRenderEntitiesDepth.get() > 0;
    }

    public static boolean shouldSuppressPatcherRiddenHorseOpacity() {
        return isEnabled() && getStrategy() >= 8;
    }

    private static void applyPatcherHorseOpacityRuntimeCompat() {
        try {
            Field field = getPatcherRiddenHorseOpacityField();
            if (field == null) {
                return;
            }
            if (!isEnabled()) {
                if (patcherHorseOpacityRuntimeCompatActive) {
                    field.setFloat(null, patcherHorseOpacityRuntimeSaved);
                    patcherHorseOpacityRuntimeCompatActive = false;
                }
                return;
            }
            float current = field.getFloat(null);
            if (!patcherHorseOpacityRuntimeCompatActive) {
                patcherHorseOpacityRuntimeSaved = current;
                patcherHorseOpacityRuntimeCompatActive = true;
            }
            // While gomod owns transparent mounts, force Patcher's ridden-horse
            // opacity feature off. Letting it run at 0.999 still mutates blend,
            // alpha-test, and color state every frame on some clients.
            if (current != 1.0f) {
                field.setFloat(null, 1.0f);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean shouldSkipFirstPassMountRender(Entity entity) {
        if (flushingDeferredMount.get()) {
            return false;
        }
        if (!shouldDeferMountDraw() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        return entity == mc.thePlayer.ridingEntity
                && (isFirstPersonView() || getAlphaForMount(entity) < 1.0F);
    }

    public static void scheduleDeferredMount(Entity entity, float partialTicks) {
        if (entity == null) {
            return;
        }
        deferredMountEntity = entity;
        deferredMountPartialTicks = partialTicks;
    }

    /**
     * Fallback for clients whose world entity loop does not hit our RenderGlobal
     * redirect. Cancelling at RenderManager level prevents the normal opaque/depth
     * mount draw from happening before the deferred transparent pass.
     */
    public static boolean shouldCancelAndScheduleNormalMountRender(Entity entity, float partialTicks) {
        if (!isInWorldRenderEntitiesPass() && !GomodFixes.isWeaveRuntime()) {
            return false;
        }
        if (shouldSkipFirstPassMountRender(entity)) {
            scheduleDeferredMount(entity, partialTicks);
            return true;
        }
        return false;
    }

    public static boolean hasDeferredMountRender() {
        return deferredMountEntity != null;
    }

    /**
     * Draw the scheduled mount over the scene already in the framebuffer.
     */
    public static void flushDeferredMountRender(net.minecraft.client.renderer.entity.RenderManager rm) {
        if (SelfOpacity.hasDeferredRender()) {
            return;
        }
        Entity m = deferredMountEntity;
        float pt = deferredMountPartialTicks;
        deferredMountEntity = null;
        if (m == null || rm == null || !useRenderManagerEntitySimpleTint()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || m != mc.thePlayer.ridingEntity) {
            return;
        }
        flushingDeferredMount.set(true);
        int savedAlphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float savedAlphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        boolean savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean thirdPerson = !isFirstPersonView();
        boolean savedShadow = false;
        int savedTextureUnit = OpenGlHelper.defaultTexUnit;
        boolean savedLighting = false;
        boolean savedLightmap = false;
        int savedLightmapTexture = 0;
        boolean restoreThirdPersonLighting = false;
        try {
            if (thirdPerson) {
                savedShadow = rm.isRenderShadow();
                savedTextureUnit = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
                savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                savedLightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                savedLightmapTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GL11.glPushAttrib(GL11.GL_LIGHTING_BIT);
                restoreThirdPersonLighting = true;
                RenderHelper.enableStandardItemLighting();
                mc.entityRenderer.enableLightmap();
                // The deferred depth shell must never include the ground shadow.
                rm.setRenderShadow(false);
            }
            beginPatcherHorseOpacityCompat();
            if (getAlphaForMount(m) < 0.99F) {
                renderMountDepth(rm, m, pt);
            }
            mountHybridPhase.set(MountHybridPhase.COLOR);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            rm.renderEntitySimple(m, pt);
        } catch (Throwable failure) {
            System.err.println("[gomod] Transparent mount draw: " + failure);
        } finally {
            endPatcherHorseOpacityCompat();
            mountHybridPhase.set(MountHybridPhase.NONE);
            GlStateManager.alphaFunc(savedAlphaFunc, savedAlphaRef);
            if (savedAlphaTest) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (savedBlend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GL11.glPolygonOffset(0.0f, 0.0f);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GlStateManager.colorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            if (restoreThirdPersonLighting) {
                rm.setRenderShadow(savedShadow);
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GlStateManager.bindTexture(savedLightmapTexture);
                if (savedLightmap) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
                GlStateManager.setActiveTexture(savedTextureUnit);
                RenderHelper.disableStandardItemLighting();
                if (savedLighting) GlStateManager.enableLighting();
                GL11.glPopAttrib();
            }
            flushingDeferredMount.set(false);
        }
    }

    private static boolean renderMountDepth(RenderManager rm, Entity mount, float partialTicks) {
        mountHybridPhase.set(MountHybridPhase.PREPASS);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        boolean savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_CULL_FACE);
        try {
            return rm.renderEntitySimple(mount, partialTicks);
        } finally {
            if (!savedCull) GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }

    private static void beginPatcherHorseOpacityCompat() {
        try {
            Field field = getPatcherRiddenHorseOpacityField();
            if (field == null) {
                return;
            }
            patcherHorseOpacitySaved = field.getFloat(null);
            if (patcherHorseOpacitySaved != 1.0f) {
                field.setFloat(null, 1.0f);
                patcherHorseOpacityOverrideActive = true;
            }
        } catch (Throwable ignored) {
            patcherHorseOpacityOverrideActive = false;
        }
    }

    private static void endPatcherHorseOpacityCompat() {
        if (!patcherHorseOpacityOverrideActive) {
            return;
        }
        try {
            Field field = getPatcherRiddenHorseOpacityField();
            if (field != null) {
                field.setFloat(null, patcherHorseOpacitySaved);
            }
        } catch (Throwable ignored) {
        } finally {
            patcherHorseOpacityOverrideActive = false;
        }
    }

    private static Field getPatcherRiddenHorseOpacityField() {
        if (patcherRiddenHorseOpacityLookupDone) {
            return patcherRiddenHorseOpacityField;
        }
        patcherRiddenHorseOpacityLookupDone = true;
        try {
            Class<?> clazz = loadPatcherClass("club.sk1er.patcher.config.PatcherConfig");
            Field field = clazz.getDeclaredField("riddenHorseOpacity");
            field.setAccessible(true);
            patcherRiddenHorseOpacityField = field;
        } catch (Throwable ignored) {
            patcherRiddenHorseOpacityField = null;
            // Mod classes/fields are fixed for this launch. Cache absence too: retrying
            // here makes Lunar search its classpath on every world-render pass.
        }
        return patcherRiddenHorseOpacityField;
    }

    private static Class<?> loadPatcherClass(String name) throws ClassNotFoundException {
        ClassLoader launchLoader = getLaunchClassLoader();
        if (launchLoader != null) {
            try {
                return Class.forName(name, false, launchLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(name, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(name, false, MountHeadDim.class.getClassLoader());
    }

    private static ClassLoader getLaunchClassLoader() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", false,
                    MountHeadDim.class.getClassLoader());
            Field field = launch.getDeclaredField("classLoader");
            Object loader = field.get(null);
            return loader instanceof ClassLoader ? (ClassLoader) loader : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int getDepthMode() {
        return ConfigManager.getMountDimDepthMode();
    }

    public static String describeDepthMode(int mode) {
        switch (mode) {
            case 1: return "Normal depth write (entities behind mount hidden)";
            case 2: return "depthMask(false) — entities visible, no mount tint over them";
            case 3: return "glPolygonOffset push mount back — nearby entities may show through";
            case 4: return "depthFunc(GL_ALWAYS) + depthMask(false) — mount draws as overlay";
            case 5: return "depthFunc(GL_ALWAYS) + normal depthMask — mount always passes, writes depth";
            case 6:
                return "Hybrid: depth shell (mode-1 look) + transparent pass (mode-2 glass)";
            default: return "unknown";
        }
    }

    public static MountHybridPhase getMountHybridPhase() {
        return mountHybridPhase.get();
    }

    private static boolean isMountHybridPassActive() {
        return mountHybridPhase.get() != MountHybridPhase.NONE;
    }

    /** Prepass must not apply dim alpha hooks; also used to block alpha-test re-enable (holes in Z). */
    public static boolean isMountHybridPrepassPhase() {
        return mountHybridPhase.get() == MountHybridPhase.PREPASS;
    }

    private static boolean shouldUseHybridMountPasses(Entity entity) {
        if (!useRenderManagerEntitySimpleTint() || entity == null) {
            return false;
        }
        int dm = getDepthMode();
        if (dm != 6) {
            return false;
        }
        return getAlphaForMount(entity) < 0.99F;
    }

    /**
     * Strategy 8 + depth 6: run depth prepass + transparent pass inside one outer {@code renderEntitySimple}.
     *
     * @return boxed result to return from {@code renderEntitySimple} if handled; {@code null} to run vanilla
     */
    public static Boolean runHybridMountDrawIfNeeded(RenderManager rm, Entity entityIn, float partialTicks) {
        if (rm == null || entityIn == null || !isFirstPersonView() || !shouldUseHybridMountPasses(entityIn)) {
            return null;
        }
        // The deferred flush above already drives explicit PREPASS/COLOR calls.
        // Let those inner renderEntitySimple calls run vanilla once each instead
        // of wrapping them in another hybrid pair.
        if (isMountHybridPassActive()) {
            return null;
        }
        if (hybridMountInnerCall.get()) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entityIn != mc.thePlayer.ridingEntity) {
            return null;
        }

        hybridMountInnerCall.set(true);
        try {
            boolean r1 = renderMountDepth(rm, entityIn, partialTicks);

            // ── Pass B: same as depth mode 2 color (glass-friendly), tested against prepass depth ──
            mountHybridPhase.set(MountHybridPhase.COLOR);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            boolean r2 = rm.renderEntitySimple(entityIn, partialTicks);

            mountHybridPhase.set(MountHybridPhase.NONE);
            return Boolean.valueOf(r1 && r2);
        } catch (Throwable t) {
            mountHybridPhase.set(MountHybridPhase.NONE);
            try {
                GlStateManager.colorMask(true, true, true, true);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.disableBlend();
            } catch (Throwable ignored) {
            }
            System.err.println("[gomod] MountHeadDim hybrid mount draw: " + t.getMessage());
            return Boolean.FALSE;
        } finally {
            mountHybridPhase.set(MountHybridPhase.NONE);
            GlStateManager.depthMask(true);
            hybridMountInnerCall.set(false);
        }
    }

    /** Effective depth mode for GL switch while drawing the dimmed mount (mode 6 COLOR acts like 2). */
    private static int effectiveDepthModeForMountGl() {
        int dm = getDepthMode();
        if (isMountHybridPassActive() && mountHybridPhase.get() == MountHybridPhase.COLOR) {
            return 2;
        }
        return dm;
    }

    /**
     * True while inside the entire mount render, including nested passenger (player) rendering.
     * Used to block GL state changes like enableAlpha() throughout the mount pass.
     */
    public static boolean isRenderingMountDim() {
        if (!useRenderManagerEntitySimpleTint() || !isEnabled()) {
            return false;
        }
        return renderManagerSimpleDepth.get() > 0;
    }

    public static boolean isLocalPlayerRiddenMount(Entity entity) {
        if (!useRenderManagerEntitySimpleTint() || !isEnabled() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && entity == mc.thePlayer.ridingEntity
                && getAlphaForMount(entity) < 0.99f;
    }

    public static void enforceTransparentMountRenderState() {
        if (!isRenderingMountDim() || isMountHybridPrepassPhase()) {
            return;
        }
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (effectiveDepthModeForMountGl() == 2 || effectiveDepthModeForMountGl() == 4) {
            GlStateManager.depthMask(false);
        }
        GL11.glColor4f(1.0f, 1.0f, 1.0f, getAlphaForActiveMountGl());
    }

    /** Cheap hot-path test; this depth is opened only for gomod-owned mount rendering. */
    public static boolean hasAnyGlInterceptionState() {
        return renderManagerSimpleDepth.get() > 0;
    }

    public static void forceActiveMountGlColor() {
        if (!shouldApplyMountDimGlHooks()) {
            return;
        }
        GL11.glColor4f(1.0f, 1.0f, 1.0f, getAlphaForActiveMountGl());
    }

    /**
     * True while inside our dimmed mount's {@code renderEntitySimple}, excluding nested draws of
     * {@code thePlayer} as passenger (see {@code RenderManagerMixin} {@code renderEntity} hooks).
     */
    public static boolean shouldApplyMountDimGlHooks() {
        if (isMountHybridPrepassPhase()) {
            return false;
        }
        return renderManagerSimpleDepth.get() > 0 && mountPassengerGlSuppressDepth.get() == 0;
    }

    /**
     * Nested {@link net.minecraft.client.renderer.entity.RenderManager#renderEntity} HEAD
     * (local player riding the dimmed mount).
     */
    public static void onRenderEntityPassengerHead(Entity entity) {
        if (renderManagerSimpleDepth.get() <= 0 || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer) {
            return;
        }
        Entity mount = mc.thePlayer.ridingEntity;
        if (mount == null || entity.ridingEntity != mount) {
            return;
        }
        if (mountPassengerGlSuppressDepth.get() == 0 && renderManagerSimpleDepth.get() > 0) {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        mountPassengerGlSuppressDepth.set(mountPassengerGlSuppressDepth.get() + 1);
    }

    /**
     * Nested {@code renderEntity} RETURN for the same passenger.
     */
    public static void onRenderEntityPassengerTail(Entity entity) {
        if (mountPassengerGlSuppressDepth.get() <= 0 || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer) {
            return;
        }
        Entity mount = mc.thePlayer.ridingEntity;
        if (mount == null || entity.ridingEntity != mount) {
            return;
        }
        int d = mountPassengerGlSuppressDepth.get() - 1;
        mountPassengerGlSuppressDepth.set(Math.max(0, d));
        // Re-apply mount dim GL state after the rider sub-render.
        // While mountPassengerGlSuppressDepth > 0, shouldApplyMountDimGlHooks() was false,
        // so GlStateManager.enableAlpha() and disableBlend() were NOT blocked.
        // The player render code calls enableAlpha() which re-enables GL_ALPHA_TEST with
        // threshold 0.1 — at opacity < 0.1 every opaque-texture fragment of the mount
        // (geometry drawn AFTER the rider sub-render: remaining layers, saddle, etc.) is
        // discarded by the alpha test, making the mount flash / disappear.  The lower the
        // opacity the more fragments fail the test, hence `flashing more the less opacity.`
        // disableBlend() being unblocked similarly causes those parts to render fully opaque.
        if (d == 0 && renderManagerSimpleDepth.get() > 0 && !isMountHybridPrepassPhase()) {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, getAlphaForActiveMountGl());
        }
    }

    /**
     * {@link net.minecraft.client.renderer.entity.RenderManager#renderEntitySimple} HEAD.
     * Enables blend once; alpha forcing is handled by {@code GlStateManagerMixin}.
     */
    public static void onRenderEntitySimpleHead(Entity entity) {
        if (!useRenderManagerEntitySimpleTint() || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer.ridingEntity) {
            return;
        }
        if (!isFirstPersonView() && !flushingDeferredMount.get()) {
            return;
        }
        int d = renderManagerSimpleDepth.get();
        // Hybrid prepass: only track nesting; outer hybrid already set color/depth masks.
        if (isMountHybridPrepassPhase()) {
            renderManagerSimpleDepth.set(d + 1);
            return;
        }
        if (d == 0) {
            mountDimGlTarget.set(entity);
            mountDimSavedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            mountDimSavedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
            GL11.glEnable(GL11.GL_CULL_FACE);

            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, getAlphaForActiveMountGl());

            int dm = effectiveDepthModeForMountGl();
            switch (dm) {
                case 2:
                    GlStateManager.depthMask(false);
                    break;
                case 3:
                    GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
                    GL11.glPolygonOffset(1.0f, 1.0f);
                    break;
                case 4:
                    GlStateManager.depthMask(false);
                    GL11.glDepthFunc(GL11.GL_ALWAYS);
                    break;
                case 5:
                    GL11.glDepthFunc(GL11.GL_ALWAYS);
                    break;
                default:
                    break;
            }
        }
        renderManagerSimpleDepth.set(d + 1);
    }

    /**
     * {@link net.minecraft.client.renderer.entity.RenderManager#renderEntitySimple} TAIL.
     */
    public static void onRenderEntitySimpleTail(Entity entity) {
        if (!useRenderManagerEntitySimpleTint() || entity == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer.ridingEntity) {
            return;
        }
        if (!isFirstPersonView() && !flushingDeferredMount.get()) {
            return;
        }
        if (isMountHybridPrepassPhase()) {
            int pd = renderManagerSimpleDepth.get() - 1;
            renderManagerSimpleDepth.set(Math.max(0, pd));
            return;
        }
        int d = renderManagerSimpleDepth.get() - 1;
        renderManagerSimpleDepth.set(Math.max(0, d));
        if (d == 0) {
            int dm = effectiveDepthModeForMountGl();
            // The vanilla hurt/brightness pass touches extra GL state. Always restore
            // depth testing before later entities and nametags are rendered, otherwise
            // a damaged transparent mount can make labels visible through blocks.
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GlStateManager.enableDepth();
            switch (dm) {
                case 2:
                    GlStateManager.depthMask(true);
                    break;
                case 3:
                    GL11.glPolygonOffset(0.0f, 0.0f);
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                    break;
                case 4:
                    GlStateManager.depthMask(true);
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    break;
                case 5:
                    GL11.glDepthFunc(GL11.GL_LEQUAL);
                    break;
                default:
                    break;
            }
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            if (!mountDimSavedCullFace) {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (mountDimSavedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
            GlStateManager.disableBlend();
            mountDimGlTarget.remove();
        }
    }

    public static boolean isEnabled() {
        return ConfigManager.isMountHeadDimEnabled();
    }

    public static void setEnabledSilent(boolean enabled) {
        ConfigManager.setMountHeadDimEnabled(enabled);
    }

    /**
     * Legacy single alpha (horse); prefer {@link #getAlphaForMount(Entity)} when the mount entity is known.
     */
    public static float getAlpha() {
        return ConfigManager.getMountHeadDimAlpha();
    }

    /**
     * Opacity for the given mount when transparent mounts are enabled; {@code 1} when disabled or unknown type.
     */
    public static float getAlphaForMount(Entity entity) {
        if (!isEnabled() || entity == null) {
            return 1f;
        }
        if (entity instanceof EntityHorse) {
            EntityHorse h = (EntityHorse) entity;
            int type = 0;
            try {
                type = h.getHorseType();
            } catch (Throwable ignored) {
            }
            if (type == 0) {
                return ConfigManager.getMountAlphaHorse();
            }
            return ConfigManager.getMountAlphaDonkey();
        }
        if (entity instanceof EntityPig) {
            return ConfigManager.getMountAlphaPig();
        }
        if (entity instanceof EntityBoat) {
            return ConfigManager.getMountAlphaBoat();
        }
        if (entity instanceof EntityMinecart) {
            return ConfigManager.getMountAlphaWagon();
        }
        if (entity instanceof EntityLivingBase) {
            return ConfigManager.getMountAlphaHorse();
        }
        return 1f;
    }

    /** Used from {@code GlStateManagerMixin} while {@link #shouldApplyMountDimGlHooks()} is true. */
    public static float getAlphaForActiveMountGl() {
        Entity e = mountDimGlTarget.get();
        if (e != null) {
            return getAlphaForMount(e);
        }
        return getAlpha();
    }

    public static String getToggleLabel() {
        return isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF";
    }

    /** Strategies 1 and 4 use {@code RenderHorse} mixin to mark the mount for the frame. */
    public static boolean usesRenderHorseActiveMarker() {
        int s = getStrategy();
        return s == 1 || s == 4;
    }

    public static boolean useGlColorRead() {
        int s = getStrategy();
        return s != 4 && s != 5;
    }

    private static boolean isRiddenByLocalPlayer(Entity e) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && mc.thePlayer.ridingEntity == e;
    }

    private static boolean isHorseMount(EntityLivingBase e) {
        return e instanceof EntityHorse && isRiddenByLocalPlayer(e);
    }

    private static boolean isAnyLivingMount(EntityLivingBase e) {
        return e != null && isRiddenByLocalPlayer(e);
    }

    /**
     * Tint in {@code renderModel} redirect — skipped for strategy 7 (handled by {@code renderLivingAt} wrap).
     */
    public static boolean shouldApplyModelDimInRenderModelRedirect(EntityLivingBase entity) {
        if (!useLegacyRendererLivingEntityTint() || !isEnabled() || getAlphaForMount(entity) >= 0.99f) {
            return false;
        }
        int s = getStrategy();
        if (s == 7) {
            return false;
        }
        switch (s) {
            case 1:
            case 4:
                return isActiveDimTarget(entity);
            case 2:
            case 3:
            case 5:
                return isHorseMount(entity);
            case 6:
                return isAnyLivingMount(entity);
            default:
                return isActiveDimTarget(entity);
        }
    }

    public static boolean shouldApplyLayerDim(EntityLivingBase entity) {
        if (!useLegacyRendererLivingEntityTint() || !isEnabled() || getAlphaForMount(entity) >= 0.99f) {
            return false;
        }
        int s = getStrategy();
        if (s == 3 || s == 7) {
            return false;
        }
        switch (s) {
            case 1:
            case 4:
                return isActiveDimTarget(entity);
            case 2:
            case 5:
                return isHorseMount(entity);
            case 6:
                return isAnyLivingMount(entity);
            default:
                return false;
        }
    }

    /** Strategy 7: dim inside {@code renderLivingAt} redirect (whole model + layers). */
    public static boolean shouldApplyStrategy7LivingAtWrap(EntityLivingBase entity) {
        return isEnabled()
            && useLegacyRendererLivingEntityTint()
            && getAlphaForMount(entity) < 0.99f
            && getStrategy() == 7
            && isAnyLivingMount(entity);
    }

    public static boolean shouldDimThisMount(Entity entity) {
        if (!isEnabled() || !isFirstPersonView() || !(entity instanceof EntityHorse)) {
            return false;
        }
        if (getAlphaForMount(entity) >= 0.99f) {
            return false;
        }
        return isRiddenByLocalPlayer(entity);
    }

    public static void setActiveRideForFrame(EntityLivingBase entity) {
        activeRideDimEntity = entity;
    }

    public static void clearActiveRideForFrame() {
        if (layerTintActive) {
            GlStateManager.color(layerSavedR, layerSavedG, layerSavedB, 1.0f);
            GlStateManager.disableBlend();
            layerTintActive = false;
        }
        activeRideDimEntity = null;
    }

    public static boolean isActiveDimTarget(Entity entity) {
        return entity != null && entity == activeRideDimEntity;
    }

    public static void readCurrentGlRgb(float[] outRgb) {
        ((Buffer) COLOR_BUF).clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR_BUF);
        outRgb[0] = COLOR_BUF.get(0);
        outRgb[1] = COLOR_BUF.get(1);
        outRgb[2] = COLOR_BUF.get(2);
    }

    public static void applyTintRgbAlpha(float r, float g, float b, float a) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(r, g, b, a);
    }

    public static void restoreTintRgb(float r, float g, float b) {
        GlStateManager.color(r, g, b, 1.0f);
        GlStateManager.disableBlend();
    }

    public static void beginLayerAlphaTint(EntityLivingBase entity) {
        if (!shouldApplyLayerDim(entity)) {
            return;
        }
        float a = getAlphaForMount(entity);
        if (a >= 0.99f) {
            return;
        }
        if (useGlColorRead()) {
            float[] rgb = new float[3];
            readCurrentGlRgb(rgb);
            layerSavedR = rgb[0];
            layerSavedG = rgb[1];
            layerSavedB = rgb[2];
            applyTintRgbAlpha(layerSavedR, layerSavedG, layerSavedB, a);
        } else {
            layerSavedR = 1f;
            layerSavedG = 1f;
            layerSavedB = 1f;
            applyTintRgbAlpha(1f, 1f, 1f, a);
        }
        layerTintActive = true;
    }

    public static void endLayerAlphaTint() {
        if (!layerTintActive) {
            return;
        }
        restoreTintRgb(layerSavedR, layerSavedG, layerSavedB);
        layerTintActive = false;
    }
}
