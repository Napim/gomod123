package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.render.NametagFogState;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.EXTFramebufferObject;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static me.napim.gomod.Main.CHAT_PREFIX;
import static me.napim.gomod.Main.sendMessage;

/**
 * Makes the local player's own model semi-transparent.
 * Applies to the local player's gameplay model, hands and held items.
 *
 * Uses the same broad strategy as {@link MountHeadDim} strategy 8 (defer + blend + scaled colour alpha).
 * {@link #flushDeferred} runs after the translucent block layer when water, ice, stained glass, or translucent
 * barriers must already be in the framebuffer before the self-opacity depth prepass; otherwise prepass depth can
 * hide those blocks when you look through yourself (first person and third person when self-opacity defers the local
 * player).
 * <p>
 * <strong>Third person after translucents:</strong> the deferred flush runs in {@code EntityRenderer#renderWorldPass}
 * immediately <em>after</em> the {@code renderBlockLayer(TRANSLUCENT)} call returns ({@link DeferredFlushSite#TRANSLUCENT_WORLD_PASS_POST_BLOCK_LAYER}),
 * not at {@link DeferredFlushSite#TRANSLUCENT_LAYER_TAIL} inside {@code RenderGlobal#renderBlockLayer}. The in-method TAIL hook
 * leaves projection/modelview in a state that skews {@code renderEntitySimple} for some void/sky views and with
 * PolyBlur &quot;Phosphor&quot;; the post-return site matches vanilla's outer pass.
 * {@link DeferredFlushPolygonOffsetGuard}, {@link DeferredFlushEntityDrawMatrixGuard}, and {@link DeferredFlushMainFramebufferGuard}
 * still apply during the flush.
 * If the translucent layer is skipped for a frame, {@link me.napim.gomod.mixins.EntityRendererMountFlushMixin}
 * still flushes deferred self opacity at {@code EntityRenderer} renderWorld RETURN.
 * Player skins still need a low alpha-test threshold during the colour pass (unlike horses); turning alpha
 * test off makes base skin geometry effectively disappear while overlays may still draw.
 * In-game debug: {@code /opacityme debug} (toggle), {@code on|off}, {@code snapshot}. JVM:
 * {@code -Dgomod.debugSelfOpacityGlint=true} also logs {@link EntityOpacityGlintBlendTracker} to stderr.
 * <p>
 * Deferred flush diagnostics (which hook ran + GL snapshot) — stderr every flush when enabled:
 * {@code /opacityme flushdebug} or JVM {@code -Dgomod.debugSelfOpacityFlush=true}. Reproduce the &quot;giant
 * feet&quot; glitch, then send the {@code [gomod][flushDiag]} lines from {@code latest.log}.
 */
public final class SelfOpacity {

    /** Where {@link #flushDeferred} was invoked from (for diagnosing wrong matrix / GL state at flush time). */
    public enum DeferredFlushSite {
        /** Inside {@link net.minecraft.client.renderer.RenderGlobal#renderBlockLayer} TAIL — used for first-person post-translucent flush. */
        TRANSLUCENT_LAYER_TAIL,
        /**
         * Right after {@code EntityRenderer#renderWorldPass} invokes {@code RenderGlobal#renderBlockLayer(TRANSLUCENT, ...)}
         * returns — used for third person so GL state matches the outer world pass (avoids void / PolyBlur Phosphor zoom feet).
         */
        TRANSLUCENT_WORLD_PASS_POST_BLOCK_LAYER,
        RENDER_ENTITIES_TAIL,
        RENDER_WORLD_FALLBACK
    }

    private enum SelfRenderPhase {
        NONE,
        PREPASS,
        COLOR
    }

    private SelfOpacity() {}

    // Deferred render strategy: player is drawn after tile entities so it blends
    // correctly over chests/signs in front AND remains visible behind them.

    /** How many levels deep we are inside a self-opacity renderEntitySimple call. */
    private static int glHooksDepth = 0;
    /** Animated player layers render once during PREPASS so their pose cannot change between opacity passes. */
    private static int singlePassLayerColorDepth = 0;
    private static boolean savedAlphaTest = false;
    private static boolean savedCullFace = false;
    private static final ThreadLocal<SelfRenderPhase> renderPhase =
            ThreadLocal.withInitial(() -> SelfRenderPhase.NONE);
    /** Keep Lunar's native nametag color compilation/draw opaque while the local model is translucent. */
    private static final ThreadLocal<NametagLogoOpacityScope> nametagLogoOpacityScope =
            ThreadLocal.withInitial(() -> new NametagLogoOpacityScope());

    private static final class NametagLogoOpacityScope {
        int depth;
        boolean colorSaved;
        float red;
        float green;
        float blue;
        float alpha;
        // LWJGL 2's glGetFloat buffer overload requires room for a 4x4 value even
        // when querying GL_CURRENT_COLOR. A four-float buffer throws and was
        // silently skipping the save/reset below, leaving the Lunar logo dimmed.
        final FloatBuffer currentColor = BufferUtils.createFloatBuffer(16);
    }

    // Lunar rewrites RenderGlobal on some clients. Prefer the Forge-style deferred two-pass render when our
    // RenderGlobal redirect catches the row; keep the RenderManager direct path as a fallback when it does not.
    private static final boolean WEAVE_RUNTIME = detectWeaveRuntime();
    private static int weaveDirectRenderDepth = 0;
    private static Entity weaveDirectEntity = null;
    private static final ThreadLocal<Boolean> weaveDirectTwoPassOuterCall =
            ThreadLocal.withInitial(() -> false);
    private static boolean weaveDirectSavedBlend = false;
    private static boolean weaveDirectSavedAlphaTest = false;
    private static boolean weaveDirectSavedCullFace = false;
    private static boolean weaveDirectSavedDepthMask = true;
    private static boolean weaveDirectLogged = false;
    private static int guiInventoryRenderDepth = 0;
    /** Prevents our raw GL hook from multiplying an inventory alpha that is already final. */
    private static boolean forcingGuiInventoryRawColor;
    private static boolean guiInventorySavedBlend = false;
    private static boolean guiInventorySavedAlphaTest = false;
    private static boolean guiInventorySavedCullFace = false;
    private static boolean guiInventorySavedDepthMask = true;
    private static boolean guiInventoryLogged = false;
    private static volatile boolean guiInventoryDiagnosticEnabled = false;
    private static long guiInventoryDiagnosticLastLogMs = 0L;
    private static int guiInventoryDiagnosticFrame = 0;
    private static int guiInventoryDiagnosticBeginCalls = 0;
    private static int guiInventoryDiagnosticMaxDepth = 0;
    private static int guiInventoryDiagnosticForceCalls = 0;
    private static int guiInventoryDiagnosticBridgeColorCalls = 0;
    private static int guiInventoryDiagnosticRawColorCalls = 0;
    private static int guiInventoryDiagnosticBlendFuncCalls = 0;
    private static int guiInventoryDiagnosticDisableBlendAttempts = 0;
    private static int guiInventoryDiagnosticRawDisableBlendAttempts = 0;
    private static String guiInventoryDiagnosticBeginState = "<none>";
    private static volatile String guiInventoryDiagnosticLastLine = "No inventory render captured yet.";
    private static int externalCosmeticRenderDepth = 0;
    private static boolean externalCosmeticOpenedPhase = false;
    private static boolean externalCosmeticDepthOnly = false;
    private static boolean externalCosmeticSavedBlend = false;
    private static boolean externalCosmeticSavedAlphaTest = false;
    private static boolean externalCosmeticSavedDepthMask = true;
    private static boolean externalCosmeticLogged = false;

    // ── first-person arm opacity state ────────────────────────────────────────
    /** True while {@code ItemRenderer.renderItemInFirstPerson} is executing with self-opacity active. */
    private static boolean firstPersonArmActive = false;
    /** Nested draw scope for the first-person held item, using the same alpha as the hand. */
    private static int firstPersonHeldItemOpacityDepth = 0;
    /** Scoped marker for raw glColor calls whose alpha was already computed by an opacity owner. */
    private static int rawGlColorAlphaFinalDepth = 0;
    private static volatile int firstPersonItemDebugStagesLogged = 0;

    public static final int FP_ITEM_DEBUG_FRAME_ENTRY = 1;
    public static final int FP_ITEM_DEBUG_FRAME_RETURN = 1 << 1;
    public static final int FP_ITEM_DEBUG_RENDER_ENTRY = 1 << 2;
    public static final int FP_ITEM_DEBUG_RENDER_RETURN = 1 << 3;
    public static final int FP_ITEM_DEBUG_MODEL_ENTRY = 1 << 4;
    public static final int FP_ITEM_DEBUG_MODEL_RETURN = 1 << 5;
    public static final int FP_ITEM_DEBUG_VERTEX_HOOK = 1 << 6;
    public static final int FP_ITEM_DEBUG_VERTEX_SCALE = 1 << 7;
    public static final int FP_ITEM_DEBUG_GL_COLOR = 1 << 8;
    public static final int FP_ITEM_DEBUG_RAW_COLOR = 1 << 9;
    public static final int FP_ITEM_DEBUG_BLEND_BLOCK = 1 << 10;
    public static final int FP_ITEM_DEBUG_RAW_BLEND_BLOCK = 1 << 11;
    public static final int FP_ITEM_DEBUG_VERTEX_BUFFER = 1 << 12;

    public static void beginFirstPersonArm() {
        firstPersonArmActive = true;
        EntityOpacityGlintBlendTracker.resetGlintBlendDepth();
    }

    public static void endFirstPersonArm() {
        firstPersonArmActive = false;
    }

    public static boolean isFirstPersonArmActive() {
        return firstPersonArmActive;
    }

    /** The combined hand/item control obeys module, first-person, blindness and disguise state. */
    public static boolean shouldUseFirstPersonHeldItemOpacity() {
        Minecraft mc = Minecraft.getMinecraft();
        return isActive(mc)
                && mc.gameSettings != null
                && mc.gameSettings.thirdPersonView == 0
                && !LocalMobDisguise.isActive();
    }

    public static boolean beginFirstPersonHeldItemOpacity() {
        if (!shouldUseFirstPersonHeldItemOpacity()) return false;
        if (firstPersonHeldItemOpacityDepth++ == 0) {
            EntityOpacityGlintBlendTracker.resetGlintBlendDepth();
        }
        return true;
    }

    public static void endFirstPersonHeldItemOpacity() {
        if (firstPersonHeldItemOpacityDepth <= 0) {
            firstPersonHeldItemOpacityDepth = 0;
            return;
        }
        if (--firstPersonHeldItemOpacityDepth == 0) {
            EntityOpacityGlintBlendTracker.resetGlintBlendDepth();
        }
    }

    public static boolean isFirstPersonHeldItemOpacityActive() {
        return firstPersonHeldItemOpacityDepth > 0;
    }

    public static void beginRawGlColorAlphaFinalScope() {
        rawGlColorAlphaFinalDepth++;
    }

    public static void endRawGlColorAlphaFinalScope() {
        if (rawGlColorAlphaFinalDepth > 0) rawGlColorAlphaFinalDepth--;
    }

    public static boolean isRawGlColorAlphaFinalScopeActive() {
        return rawGlColorAlphaFinalDepth > 0;
    }

    // ── deferred render state ────────────────────────────────────────────────
    private static volatile Entity deferredEntity = null;
    private static volatile float deferredPartialTicks = 0f;
    private static final java.util.concurrent.atomic.AtomicBoolean flushingDeferred =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Set false at {@link RenderGlobalMixin} {@code renderEntities} HEAD; set true when the translucent
     * block layer begins ({@code renderBlockLayer(TRANSLUCENT)} HEAD). If we skip translucent entirely
     * (empty view / fast flags), deferred self-opacity must flush at {@code EntityRenderer} renderWorld RETURN
     * or the player stays invisible for the frame.
     */
    private static volatile boolean translucentBlockLayerEnteredThisEntitiesPass = false;

    private static final boolean FLUSH_DIAGNOSTIC_FROM_JVM = Boolean.getBoolean("gomod.debugSelfOpacityFlush");
    private static final AtomicInteger flushDiagnosticSequence = new AtomicInteger();
    private static final FloatBuffer flushDiagMatrixBuf = BufferUtils.createFloatBuffer(16);

    // ── chat / log debug (see /opacityme debug) ─────────────────────────────
    private static volatile boolean selfOpacityDebugEnabled = false;
    private static volatile boolean flushDiagnosticDebugEnabled = false;
    private static final ConcurrentLinkedQueue<String> SELF_OPACITY_DEBUG_QUEUE = new ConcurrentLinkedQueue<>();

    public static boolean isSelfOpacityDebugEnabled() {
        return selfOpacityDebugEnabled;
    }

    public static void setSelfOpacityDebugEnabled(boolean enabled) {
        selfOpacityDebugEnabled = enabled;
        firstPersonItemDebugStagesLogged = 0;
        if (!enabled) {
            SELF_OPACITY_DEBUG_QUEUE.clear();
        }
    }

    public static boolean toggleSelfOpacityDebugEnabled() {
        selfOpacityDebugEnabled = !selfOpacityDebugEnabled;
        firstPersonItemDebugStagesLogged = 0;
        if (!selfOpacityDebugEnabled) {
            SELF_OPACITY_DEBUG_QUEUE.clear();
        }
        return selfOpacityDebugEnabled;
    }

    public static boolean shouldLogFirstPersonItemDebug(int stage) {
        return selfOpacityDebugEnabled && (firstPersonItemDebugStagesLogged & stage) == 0;
    }

    /** One-shot-per-stage telemetry for tracing the actual first-person item draw. */
    public static void logFirstPersonItemDebug(int stage, String stageName, ItemStack stack, String details) {
        if (!shouldLogFirstPersonItemDebug(stage)) return;
        firstPersonItemDebugStagesLogged |= stage;
        try {
            String item = stack == null ? "<none>"
                    : Item.getIdFromItem(stack.getItem()) + ":" + stack.getMetadata();
            float currentAlpha = Float.NaN;
            try {
                FloatBuffer color = BufferUtils.createFloatBuffer(16);
                GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
                currentAlpha = color.get(3);
            } catch (Throwable ignored) {
            }
            enqueueSelfOpacityDebugLine("fpItem " + stageName
                    + " item=" + item
                    + " enabled=" + isEnabled()
                    + " active=" + isActive()
                    + " arm=" + firstPersonArmActive
                    + " itemScopeDepth=" + firstPersonHeldItemOpacityDepth
                    + " itemAlpha=" + String.format(java.util.Locale.ROOT, "%.3f", getRenderAlpha())
                    + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + " alphaTest=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    + " colorAlpha=" + String.format(java.util.Locale.ROOT, "%.3f", currentAlpha)
                    + " details=" + (details == null ? "" : details));
        } catch (Throwable t) {
            enqueueSelfOpacityDebugLine("fpItem " + stageName + " diagnosticError="
                    + t.getClass().getSimpleName());
        }
    }

    public static boolean isFlushDiagnosticDebugEnabled() {
        return FLUSH_DIAGNOSTIC_FROM_JVM || flushDiagnosticDebugEnabled;
    }

    public static void setFlushDiagnosticDebugEnabled(boolean enabled) {
        flushDiagnosticDebugEnabled = enabled;
    }

    public static boolean toggleFlushDiagnosticDebugEnabled() {
        flushDiagnosticDebugEnabled = !flushDiagnosticDebugEnabled;
        return flushDiagnosticDebugEnabled;
    }

    /**
     * Queues one line for {@link #tickSelfOpacityDebugChatDrain()} and always mirrors to stderr for log files.
     */
    public static void enqueueSelfOpacityDebugLine(String line) {
        String s = line == null ? "" : line;
        if (s.length() > 240) {
            s = s.substring(0, 237) + "...";
        }
        System.err.println("[gomod][opacityDbg] " + s);
        if (selfOpacityDebugEnabled) {
            SELF_OPACITY_DEBUG_QUEUE.offer(s);
        }
    }

    /** Called from {@code MinecraftRunTickFeaturesMixin} once per client tick. */
    public static void tickSelfOpacityDebugChatDrain() {
        if (!selfOpacityDebugEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        int n = 0;
        while (n < 30 && !SELF_OPACITY_DEBUG_QUEUE.isEmpty()) {
            String msg = SELF_OPACITY_DEBUG_QUEUE.poll();
            if (msg != null) {
                sendMessage(CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[opacityDbg] " + msg);
            }
            n++;
        }
    }

    /** Immediate multi-line status (does not require debug mode). */
    public static void sendSelfOpacityDebugSnapshot() {
        Minecraft mc = Minecraft.getMinecraft();
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GOLD + "=== Self opacity snapshot ===");
        if (mc == null) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + "mc null");
            return;
        }
        boolean disableDuringBlindness = isDisableDuringBlindnessEnabled();
        boolean blindnessActive = VisibleSpecs.isBlindnessNametagLimitActive();
        boolean blindnessSuppressionActive = disableDuringBlindness && blindnessActive;
        sendMessage(CHAT_PREFIX + "enabled=" + isEnabled()
                + " active=" + isActive()
                + " blindAutoOff=" + disableDuringBlindness
                + " blindnessActive=" + blindnessActive
                + " blindnessSuppressed=" + blindnessSuppressionActive
                + " alpha=" + String.format("%.2f", getAlpha())
                + " F5=" + (mc.gameSettings != null ? mc.gameSettings.thirdPersonView : -1));
        sendMessage(CHAT_PREFIX + "flushDeferred=" + flushingDeferred.get()
                + " glHooksDepth=" + glHooksDepth
                + " phase=" + renderPhase.get()
                + " translucentLayerPass=" + translucentBlockLayerEnteredThisEntitiesPass
                + " tpPostTransFlush=" + shouldFlushSelfOpacityAfterTranslucentBlocks());
        sendMessage(CHAT_PREFIX + "deferNest=" + EntityOpacityGlintBlendTracker.peekDeferredFlushNesting()
                + " glintDepth=" + EntityOpacityGlintBlendTracker.peekGlintBlendDepth()
                + " skipGlintAlpha=" + EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip());
        sendMessage(CHAT_PREFIX + "recordBlendCtx=" + EntityOpacityGlintBlendTracker.shouldRecordRawOpenGlBlendForGlint()
                + " deferEntityCtx=" + EntityOpacityGlintBlendTracker.isBlendTrackingActiveForOpacityGlint()
                + " glQueryGlint=" + EntityOpacityGlintBlendTracker.isOpenGlReportingGlintBlend());
        sendMessage(CHAT_PREFIX + EntityOpacityGlintBlendTracker.formatOpenGlBlendFactorsOrError());
        sendMessage(CHAT_PREFIX + "FParm=" + firstPersonArmActive
                + " scaleAlphaPhase=" + shouldScaleAlphaInCurrentPhase()
                + " keepBlend=" + shouldKeepBlendEnabled());
        sendMessage(CHAT_PREFIX + "firstPersonHandAlpha="
                + String.format(java.util.Locale.ROOT, "%.3f", getFirstPersonHandAlpha())
                + " itemScopeActive=" + isFirstPersonHeldItemOpacityActive()
                + " itemAlpha=" + String.format(java.util.Locale.ROOT, "%.3f", getRenderAlpha())
                + " itemBlendGuard=" + shouldKeepFirstPersonHeldItemBlendEnabled());
        sendMessage(CHAT_PREFIX + "flushDiag jvm=" + FLUSH_DIAGNOSTIC_FROM_JVM
                + " chatToggle=" + flushDiagnosticDebugEnabled
                + " active=" + isFlushDiagnosticDebugEnabled());
        if (mc.thePlayer != null) {
            sendMessage(CHAT_PREFIX + enchantSummary(mc.thePlayer.getHeldItem(), "held"));
            for (int i = 0; i < 4; i++) {
                sendMessage(CHAT_PREFIX + enchantSummary(mc.thePlayer.inventory.armorInventory[i], "armor" + i));
            }
        }
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GOLD + "=== end snapshot ===");
    }

    private static String enchantSummary(ItemStack stack, String label) {
        if (stack == null) {
            return label + "=null";
        }
        return label + "=" + stack.getUnlocalizedName()
                + " enchantVisual=" + stack.isItemEnchanted()
                + " glintNBT=" + (stack.getTagCompound() != null && stack.getTagCompound().hasKey("ench"));
    }

    // ── public API ──────────────────────────────────────────────────────────

    public static boolean isEnabled() {
        return ConfigManager.isSelfOpacityEnabled();
    }

    public static void setGuiInventoryDiagnosticEnabled(boolean enabled) {
        guiInventoryDiagnosticEnabled = enabled;
        if (enabled) {
            guiInventoryDiagnosticLastLogMs = 0L;
            guiInventoryDiagnosticLastLine = "Waiting for an inventory mannequin render...";
        }
    }

    public static boolean toggleGuiInventoryDiagnosticEnabled() {
        setGuiInventoryDiagnosticEnabled(!guiInventoryDiagnosticEnabled);
        return guiInventoryDiagnosticEnabled;
    }

    public static boolean isGuiInventoryDiagnosticEnabled() {
        return guiInventoryDiagnosticEnabled;
    }

    public static void sendGuiInventoryDiagnosticSnapshot() {
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GOLD + "Inventory opacity diagnostic: "
                + EnumChatFormatting.GRAY + guiInventoryDiagnosticLastLine);
        System.err.println("[gomod][inventoryOpacityDiag][snapshot] " + guiInventoryDiagnosticLastLine);
    }

    public static boolean isActive() {
        return isActive(Minecraft.getMinecraft());
    }

    private static boolean isActive(Minecraft mc) {
        return isEnabled()
                && mc != null
                && mc.theWorld != null
                && mc.thePlayer != null
                && mc.thePlayer.worldObj == mc.theWorld
                && isEnabledForCurrentPerspective()
                && !isTemporarilyDisabledByBlindness();
    }

    public static boolean isEnabledForCurrentPerspective() {
        // Perspective-specific toggles were removed. The main opacity applies in both F5 views;
        // the first-person hand has its own alpha control.
        return true;
    }

    public static boolean isDisableDuringBlindnessEnabled() {
        return ConfigManager.isSelfOpacityDisableDuringBlindnessEnabled();
    }

    public static void setDisableDuringBlindnessEnabled(boolean enabled) {
        ConfigManager.setSelfOpacityDisableDuringBlindnessEnabled(enabled);
    }

    public static boolean isTemporarilyDisabledByBlindness() {
        return isDisableDuringBlindnessEnabled() && VisibleSpecs.isBlindnessNametagLimitActive();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setSelfOpacityEnabled(enabled);
    }

    public static float getAlpha() {
        return ConfigManager.getSelfOpacityAlpha();
    }

    /** Alpha to use from GL/vertex hooks; first-person hand and item share one setting. */
    public static float getRenderAlpha() {
        if (firstPersonArmActive || firstPersonHeldItemOpacityDepth > 0) {
            float handAlpha = ConfigManager.getSelfOpacityFirstPersonHandAlpha();
            float visibleSpecsAlpha = VisibleSpecs.shouldApplyToLocalFirstPersonArm()
                    ? VisibleSpecs.getRenderOpacity() : 1.0F;
            return Math.min(handAlpha, visibleSpecsAlpha);
        }
        return getAlpha();
    }

    public static float getFirstPersonHandAlpha() {
        return ConfigManager.getSelfOpacityFirstPersonHandAlpha();
    }

    public static void setFirstPersonHandAlpha(float alpha) {
        ConfigManager.setSelfOpacityFirstPersonHandAlpha(alpha);
    }

    public static void setAlpha(float alpha) {
        ConfigManager.setSelfOpacityAlpha(alpha);
    }

    /**
     * Player Queuer stand-alone outlines depth-test against the main scene so glow does not show through walls.
     * Self-opacity writes a depth prepass for the local player before {@code renderEntities}; blitting that depth
     * into the outline FBO rejects other players' silhouettes when they are behind your body. Skip scene depth
     * while self-opacity is active so queuer glows remain visible through your semi-transparent model.
     */
    public static boolean shouldPlayerQueuerOutlineDepthTestAgainstScene() {
        return !(isActive() && getAlpha() < 0.99f);
    }

    // ── deferred-render helpers ──────────────────────────────────────────────

    /** Used by {@code RenderGlobalMixin} redirect to skip the normal entity pass. */
    public static boolean shouldSkipAndDefer(Entity entity) {
        if (flushingDeferred.get()) return false;
        return shouldApplyToEntity(entity);
    }

    public static void scheduleDeferred(Entity entity, float partialTicks) {
        deferredEntity = entity;
        deferredPartialTicks = partialTicks;
    }

    public static boolean hasDeferredRender() {
        return deferredEntity != null;
    }

    /**
     * When {@code true}, {@link #flushDeferred} runs at translucent block layer TAIL inside {@link net.minecraft.client.renderer.RenderGlobal}.
     * First person only (third person uses {@link #shouldFlushSelfOpacityAfterTranslucentWorldPassPostBlockLayer()}).
     */
    public static boolean shouldFlushSelfOpacityInRenderGlobalTranslucentTail() {
        return shouldFlushSelfOpacityAfterTranslucentBlocks() && isFirstPersonCamera();
    }

    /**
     * When {@code true}, {@link #flushDeferred} runs in {@code EntityRenderer#renderWorldPass} immediately after
     * {@code renderBlockLayer(TRANSLUCENT)} returns. Third person only.
     */
    public static boolean shouldFlushSelfOpacityAfterTranslucentWorldPassPostBlockLayer() {
        return shouldFlushSelfOpacityAfterTranslucentBlocks() && !isFirstPersonCamera();
    }

    private static boolean isFirstPersonCamera() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null || mc.gameSettings == null || mc.gameSettings.thirdPersonView == 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * When {@code true}, deferred self-opacity should run after translucent terrain (water, ice, …) for depth prepass.
     * {@link #shouldFlushSelfOpacityInRenderGlobalTranslucentTail()} and
     * {@link #shouldFlushSelfOpacityAfterTranslucentWorldPassPostBlockLayer()} choose the hook site.
     */
    public static boolean shouldFlushSelfOpacityAfterTranslucentBlocks() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0) {
                return isActive() && getAlpha() < 0.99f;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Called from {@code RenderGlobal.renderEntities} HEAD — same cadence as {@link MountHeadDim#beginWorldRenderEntitiesPass}. */
    public static void beginEntitiesPass() {
        DeferredFlushEntityDrawMatrixGuard.captureWorldMatrices();
        translucentBlockLayerEnteredThisEntitiesPass = false;
    }

    /** Called from {@code RenderGlobal.renderBlockLayer} HEAD when {@code TRANSLUCENT}. */
    public static void noteTranslucentBlockLayerEntered() {
        translucentBlockLayerEnteredThisEntitiesPass = true;
    }

    /**
     * True when deferred local player is still queued at renderWorld RETURN. This also catches Lunar/Weave paths
     * where RenderManager saw the player but the normal translucent-layer redirect did not flush it.
     */
    public static boolean needsRenderWorldFallbackFlush() {
        return hasDeferredRender();
    }

    /**
     * Render the deferred player after tile entities have drawn (mode 3).
     * {@code site} must match the mixin hook (used for {@code /opacityme flushdiag} / {@code -Dgomod.debugSelfOpacityFlush}).
     */
    public static void flushDeferred(net.minecraft.client.renderer.entity.RenderManager rm, DeferredFlushSite site) {
        flushDeferred(rm, site, -1);
    }

    /**
     * @param worldRenderPass {@link net.minecraft.client.renderer.RenderGlobal#renderBlockLayer} {@code pass}
     *                        when flushing from a translucent hook; {@code -1} elsewhere
     */
    public static void flushDeferred(net.minecraft.client.renderer.entity.RenderManager rm, DeferredFlushSite site,
                                     int worldRenderPass) {
        if (rm == null || !hasDeferredRender()) {
            return;
        }
        // Acquire before clearing: if we nulled {@link #deferredEntity} first and compareAndSet failed,
        // we could skip drawing for an entire frame (brief total invisibility).
        if (!flushingDeferred.compareAndSet(false, true)) {
            if (isFlushDiagnosticDebugEnabled()) {
                System.err.println("[gomod][flushDiag] seq=" + flushDiagnosticSequence.incrementAndGet()
                        + " SKIP compareAndSetFailed site=" + site);
            }
            return;
        }
        Entity e = deferredEntity;
        float pt = deferredPartialTicks;
        deferredEntity = null;
        deferredPartialTicks = 0f;
        if (e == null) {
            flushingDeferred.set(false);
            return;
        }
        if (!shouldApplyToEntity(e)) {
            flushingDeferred.set(false);
            return;
        }
        int diagSeq = isFlushDiagnosticDebugEnabled() ? flushDiagnosticSequence.incrementAndGet() : -1;
        DeferredFlushEntityDrawMatrixGuard.Snap matrixSnap = DeferredFlushEntityDrawMatrixGuard.pushModelViewForEntityDraw();
        DeferredFlushMainFramebufferGuard.Snap fbSnap =
                (site == DeferredFlushSite.TRANSLUCENT_LAYER_TAIL
                        || site == DeferredFlushSite.TRANSLUCENT_WORLD_PASS_POST_BLOCK_LAYER)
                ? DeferredFlushMainFramebufferGuard.enter(Minecraft.getMinecraft())
                : DeferredFlushMainFramebufferGuard.enter(null);
        // Translucent terrain (water, ice, stained glass, sub-100% barrier quads) usually runs with GL lighting
        // off. When this flush runs after translucent terrain, enable lighting so the model is not flat black.
        boolean savedFog = false;
        boolean savedLighting = false;
        NametagFogState.Snapshot savedBlindnessFog = null;
        try {
            savedFog = GL11.glIsEnabled(GL11.GL_FOG);
            savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            if (VisibleSpecs.isBlindnessNametagLimitActive()) {
                // The deferred draw normally disables fog because it runs after translucent terrain.
                // Under Blindness, that removes fog from the player's model while the world stays fogged,
                // making the same configured alpha look much more solid. Reapply vanilla fog for both
                // self-opacity passes, then restore the exact pre-flush fog state below.
                savedBlindnessFog = NametagFogState.restoreWorldPassTemporarily();
            } else {
                GlStateManager.disableFog();
            }
            GlStateManager.enableLighting();
            RenderHelper.enableStandardItemLighting();
        } catch (Throwable ignored) {
        }
        emitDeferredFlushDiagnostics(site, "prePolyClear", e, pt, diagSeq);
        DeferredFlushPolygonOffsetGuard.Snap polySnap = DeferredFlushPolygonOffsetGuard.pushClearForEntityDraw();
        emitDeferredFlushDiagnostics(site, "postPolyClear", e, pt, diagSeq);
        try {
            EntityOpacityGlintBlendTracker.enterDeferredOpacityFlush();
            EntityOpacityGlintBlendTracker.restoreStandardBlendIfGlintDepthNonZero();
            if (WEAVE_RUNTIME) LunarPetOpacityPass.beginFrame();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            renderPhase.set(SelfRenderPhase.PREPASS);
            GlStateManager.disableBlend();
            GlStateManager.colorMask(false, false, false, false);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            rm.renderEntitySimple(e, pt);
            if (WEAVE_RUNTIME) LunarPetOpacityPass.writeDepth();

            renderPhase.set(SelfRenderPhase.COLOR);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            rm.renderEntitySimple(e, pt);
            if (WEAVE_RUNTIME) LunarPetOpacityPass.composite();
            DeferredNametagRenderer.flushAfterSelfOpacity();
        } catch (Throwable ignored) {
        } finally {
            if (WEAVE_RUNTIME) LunarPetOpacityPass.finishFrame();
            DeferredFlushPolygonOffsetGuard.popAfterEntityDraw(polySnap);
            emitDeferredFlushDiagnostics(site, "postFlushPopPoly", e, pt, diagSeq);
            EntityOpacityGlintBlendTracker.exitDeferredOpacityFlush();
            renderPhase.set(SelfRenderPhase.NONE);
            glHooksDepth = 0;
            clearNametagLogoAlphaBypass();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            try {
                RenderHelper.disableStandardItemLighting();
                if (savedLighting) {
                    GlStateManager.enableLighting();
                } else {
                    GlStateManager.disableLighting();
                }
                if (savedBlindnessFog != null) {
                    NametagFogState.restoreCurrent(savedBlindnessFog);
                } else if (savedFog) {
                    GlStateManager.enableFog();
                } else {
                    GlStateManager.disableFog();
                }
            } catch (Throwable ignored) {
            }
            try {
                GlStateManager.disableBlend();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            } catch (Throwable ignored) {
            }
            DeferredFlushMainFramebufferGuard.leave(fbSnap);
            DeferredFlushEntityDrawMatrixGuard.popAfterEntityDraw(matrixSnap);
            flushingDeferred.set(false);
        }
        if (MountHeadDim.hasDeferredMountRender() && !MountHeadDim.shouldFlushAfterRenderWorld()) {
            MountHeadDim.flushDeferredMountRender(rm);
        }
    }

    private static void emitDeferredFlushDiagnostics(DeferredFlushSite site, String phase, Entity e, float pt, int seq) {
        if (seq < 0) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            StringBuilder sb = new StringBuilder(360);
            sb.append("seq=").append(seq).append(" phase=").append(phase).append(" site=").append(site.name());
            int f5 = -9;
            if (mc != null && mc.gameSettings != null) {
                f5 = mc.gameSettings.thirdPersonView;
            }
            sb.append(" F5=").append(f5);
            if (e != null) {
                sb.append(String.format(" pos=%.1f,%.1f,%.1f yaw=%.1f pitch=%.1f pt=%.3f",
                        e.posX, e.posY, e.posZ,
                        e.rotationYaw, e.rotationPitch, pt));
            }
            sb.append(' ').append(flushDiagFormatGlSnapshot());
            System.err.println("[gomod][flushDiag] " + sb);
        } catch (Throwable t) {
            System.err.println("[gomod][flushDiag] seq=" + seq + " phase=" + phase + " ERROR " + t);
        }
    }

    private static String flushDiagFormatGlSnapshot() {
        StringBuilder b = new StringBuilder(280);
        try {
            int mm = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
            b.append("matrixMode=").append(mm);
            boolean poFill = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            boolean poLine = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_LINE);
            float pof = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
            float pou = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            b.append(String.format(" polyOff f=%b l=%b factor=%.4f units=%.4f", poFill, poLine, pof, pou));
            b.append(" depthT=").append(GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
            b.append(" depthW=").append(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            b.append(" depthFn=").append(GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
            b.append(" blend=").append(GL11.glIsEnabled(GL11.GL_BLEND));
            b.append(" cull=").append(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            b.append(" alphaT=").append(GL11.glIsEnabled(GL11.GL_ALPHA_TEST));
            flushDiagMatrixBuf.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, flushDiagMatrixBuf);
            float m00 = flushDiagMatrixBuf.get(0);
            float m11 = flushDiagMatrixBuf.get(5);
            float m22 = flushDiagMatrixBuf.get(10);
            float m30 = flushDiagMatrixBuf.get(12);
            float m31 = flushDiagMatrixBuf.get(13);
            float m32 = flushDiagMatrixBuf.get(14);
            b.append(String.format(" mvD(%.3f,%.3f,%.3f) mvT(%.2f,%.2f,%.2f)", m00, m11, m22, m30, m31, m32));
            flushDiagMatrixBuf.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, flushDiagMatrixBuf);
            float p00 = flushDiagMatrixBuf.get(0);
            float p11 = flushDiagMatrixBuf.get(5);
            float p22 = flushDiagMatrixBuf.get(10);
            b.append(String.format(" prD(%.4f,%.4f,%.4f)", p00, p11, p22));
            if (OpenGlHelper.framebufferSupported) {
                try {
                    int fb = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
                    b.append(" fb=").append(fb);
                } catch (Throwable ignored2) {
                }
            }
        } catch (Throwable t) {
            b.append("glSnapErr=").append(t.getClass().getSimpleName());
        }
        return b.toString();
    }

    /**
     * Returns true when {@code entity} is the local player and self-opacity
     * is active with a non-trivial alpha value.
     */
    public static boolean shouldApplyToEntity(Entity entity) {
        if (!isActive() || entity == null) return false;
        if (me.napim.gomod.gui.MountPreviewHelper.shouldBypassWorldOpacityForGuiPreview(entity)) {
            return false;
        }
        if (getAlpha() >= 0.99f) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer) {
            return false;
        }
        // The inventory mannequin is excluded by its short draw scope. Merely having GuiInventory open must not
        // disable opacity on the real third-person world player rendered behind the screen.
        if (guiInventoryRenderDepth > 0) {
            return false;
        }
        if (shouldSuppressLocalDeadSpectatorBody(mc)) {
            return false;
        }
        Entity view = mc.getRenderViewEntity();
        return view == null || view == mc.thePlayer || RoleplayMode.isEnabled()
                || PlayersPlayMode.getCurrentRenderTarget() != null;
    }

    public static boolean shouldSuppressLocalDeadSpectatorBody(Minecraft mc) {
        try {
            return isActive()
                    && getAlpha() < 0.99f
                    && mc != null
                    && mc.thePlayer != null
                    && VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * True while we are inside a dimmed self-renderEntitySimple call.
     * Used by {@link me.napim.gomod.mixins.GlStateManagerMixin} to intercept
     * blend / alpha-test GL calls.
     */
    public static boolean isGlHooksActive() {
        return glHooksDepth > 0;
    }

    /** Allocation-free hot-path gate used by GL mixins during ordinary entity rendering. */
    public static boolean hasAnyGlInterceptionState() {
        return glHooksDepth > 0
                || firstPersonArmActive
                || firstPersonHeldItemOpacityDepth > 0
                || externalCosmeticRenderDepth > 0
                || singlePassLayerColorDepth > 0
                || guiInventoryRenderDepth > 0;
    }

    /** Match VisibleSpecs behavior: don't scale alpha during depth prepass. */
    public static boolean shouldScaleAlphaInCurrentPhase() {
        if (isNametagLogoAlphaBypassActive()) return false;
        if (firstPersonHeldItemOpacityDepth > 0) return true;
        if (firstPersonArmActive) return true;
        if (externalCosmeticRenderDepth > 0) return !externalCosmeticDepthOnly;
        return glHooksDepth > 0
                && (renderPhase.get() != SelfRenderPhase.PREPASS || singlePassLayerColorDepth > 0);
    }

    /** Bracket Lunar's native logo component compilation/draw to keep it clear while the self model fades. */
    public static void beginNametagLogoAlphaBypass() {
        beginNametagLogoAlphaBypass(false);
    }

    /**
     * Brackets the local player's complete nametag pass. Lunar can draw its cached logo list after its
     * compiler hook, or after the self-opacity color phase has ended; in either case the local label must
     * start with an opaque current color while preserving its RGB tint.
     */
    public static boolean beginLocalPlayerNametagAlphaBypass(Entity entity) {
        if (!shouldApplyToEntity(entity)) {
            return false;
        }
        beginNametagLogoAlphaBypass(true);
        return true;
    }

    private static void beginNametagLogoAlphaBypass(boolean forceOpaqueCurrentColor) {
        NametagLogoOpacityScope scope = nametagLogoOpacityScope.get();
        boolean bypassingSelfOpacity = scope.depth == 0
                && (forceOpaqueCurrentColor || shouldScaleAlphaInCurrentPhase() || shouldForceWeaveColorAlpha());
        scope.depth++;
        if (!bypassingSelfOpacity) {
            return;
        }
        try {
            scope.currentColor.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, scope.currentColor);
            scope.red = scope.currentColor.get(0);
            scope.green = scope.currentColor.get(1);
            scope.blue = scope.currentColor.get(2);
            scope.alpha = scope.currentColor.get(3);
            scope.colorSaved = true;
            // Keep the caller's tint and remove only the player-opacity alpha, then restore the
            // caller's exact color at scope exit.
            GlStateManager.color(scope.red, scope.green, scope.blue, 1.0F);
        } catch (Throwable ignored) {
            scope.colorSaved = false;
        }
    }

    public static void endNametagLogoAlphaBypass() {
        NametagLogoOpacityScope scope = nametagLogoOpacityScope.get();
        if (scope.depth <= 0) {
            scope.depth = 0;
            scope.colorSaved = false;
            return;
        }
        try {
            if (scope.depth == 1 && scope.colorSaved) {
                GlStateManager.color(scope.red, scope.green, scope.blue, scope.alpha);
            }
        } catch (Throwable ignored) {
        } finally {
            scope.depth--;
            if (scope.depth == 0) {
                scope.colorSaved = false;
            }
        }
    }

    private static boolean isNametagLogoAlphaBypassActive() {
        return nametagLogoOpacityScope.get().depth > 0;
    }

    private static void clearNametagLogoAlphaBypass() {
        NametagLogoOpacityScope scope = nametagLogoOpacityScope.get();
        scope.depth = 0;
        scope.colorSaved = false;
    }

    /** True while the local-player invisible depth pass is rendering. */
    public static boolean isSelfOpacityDepthPrepassActive() {
        return glHooksDepth > 0 && renderPhase.get() == SelfRenderPhase.PREPASS;
    }

    /** Render-pass label for payload-free diagnostics around Lunar's deferred cosmetic draws. */
    public static String renderPhaseForDiagnostics() {
        SelfRenderPhase phase = renderPhase.get();
        return phase == null ? SelfRenderPhase.NONE.name() : phase.name();
    }

    /**
     * Render armor and cosmetic layers once, visibly, in the depth pass. Cosmetic animations may update
     * between two renderer calls; drawing those layers in both passes produces the alternating/flashing pose.
     */
    public static boolean beginSinglePassLayerColor(EntityLivingBase entity) {
        if (usesLunarLayerDepthPasses() || !shouldApplyToEntity(entity) || !isSelfOpacityDepthPrepassActive()) {
            return false;
        }
        if (singlePassLayerColorDepth++ == 0) {
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.depthMask(true);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            // This call goes through GlStateManagerMixin, which applies the local-player fade
            // while singlePassLayerColorDepth is active. Seed it with opaque alpha here so the
            // fade is applied once instead of squaring it (notably visible on Lunar wing layers).
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    public static void endSinglePassLayerColor() {
        if (singlePassLayerColorDepth <= 0) {
            singlePassLayerColorDepth = 0;
            return;
        }
        if (--singlePassLayerColorDepth == 0) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(false, false, false, false);
        }
    }

    public static boolean shouldSkipSinglePassLayersInColor(EntityLivingBase entity) {
        return !usesLunarLayerDepthPasses() && guiInventoryRenderDepth <= 0
                && shouldApplyToEntity(entity) && glHooksDepth > 0
                && renderPhase.get() == SelfRenderPhase.COLOR;
    }

    private static boolean usesLunarLayerDepthPasses() {
        return WEAVE_RUNTIME && flushingDeferred.get();
    }

    public static boolean isSinglePassLayerColorActive() {
        return singlePassLayerColorDepth > 0;
    }

    public static boolean shouldScaleVertexAlphaOnRenderThread() {
        if (!shouldScaleAlphaInCurrentPhase()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.isCallingFromMinecraftThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldApplyToGuiInventoryEntity(Entity entity) {
        if (!isEnabled() || !isEnabledForCurrentPerspective()
                || isTemporarilyDisabledByBlindness() || getAlpha() >= 0.99F || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer) {
            return false;
        }
        // This scope identifies the inventory mannequin so the world-opacity path can leave it opaque.
        return mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiInventory;
    }

    public static boolean beginGuiInventoryEntityRender(Entity entity) {
        if (!shouldApplyToGuiInventoryEntity(entity)) {
            return false;
        }
        guiInventoryRenderDepth++;
        return true;
    }

    public static void endGuiInventoryEntityRender(Entity entity) {
        if (guiInventoryRenderDepth <= 0) {
            guiInventoryRenderDepth = 0;
            return;
        }
        guiInventoryRenderDepth--;
    }

    public static boolean shouldApplyToLocalPlayerBridge(Object playerBridge) {
        if (!isActive() || getAlpha() >= 0.99f || playerBridge == null) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null
                    && mc.thePlayer != null
                    && guiInventoryRenderDepth <= 0
                    && PlayerStandaloneCape.isLocalPlayerBridge(playerBridge)
                    && !shouldSuppressLocalDeadSpectatorBody(mc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean beginExternalLocalPlayerCosmeticRender(Object playerBridge) {
        if (!shouldApplyToLocalPlayerBridge(playerBridge)) {
            return false;
        }
        if (externalCosmeticRenderDepth++ > 0) {
            return true;
        }
        externalCosmeticOpenedPhase = false;
        externalCosmeticDepthOnly = false;
        try {
            if (!externalCosmeticLogged) {
                externalCosmeticLogged = true;
                System.out.println("[gomod] self-opacity Lunar cosmetic render active (alpha=" + getAlpha() + ")");
            }
            if (glHooksDepth <= 0) {
                externalCosmeticOpenedPhase = true;
                externalCosmeticSavedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
                externalCosmeticSavedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
                externalCosmeticSavedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                renderPhase.set(SelfRenderPhase.COLOR);
                glHooksDepth++;
            }
            if (!externalCosmeticOpenedPhase
                    && glHooksDepth > 0
                    && renderPhase.get() == SelfRenderPhase.PREPASS) {
                externalCosmeticDepthOnly = usesLunarLayerDepthPasses();
                GlStateManager.colorMask(!externalCosmeticDepthOnly, !externalCosmeticDepthOnly,
                        !externalCosmeticDepthOnly, !externalCosmeticDepthOnly);
                GlStateManager.depthMask(true);
                if (externalCosmeticDepthOnly) {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
                    GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                    return true;
                }
            }
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(renderPhase.get() == SelfRenderPhase.PREPASS);
            // Genesis can skip gomod's GL mixins, so set the real cloak alpha at this native bridge too.
            if (isGuiInventoryOpacityRenderActive()) {
                forceGuiInventoryRawColor(1.0F, 1.0F, 1.0F);
            } else {
                GL11.glColor4f(1.0F, 1.0F, 1.0F, getAlpha());
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void endExternalLocalPlayerCosmeticRender() {
        if (externalCosmeticRenderDepth <= 0) {
            externalCosmeticRenderDepth = 0;
            return;
        }
        externalCosmeticRenderDepth--;
        if (externalCosmeticRenderDepth > 0) {
            return;
        }
        if (externalCosmeticOpenedPhase) {
            if (glHooksDepth > 0) {
                glHooksDepth--;
            }
            renderPhase.set(SelfRenderPhase.NONE);
        }
        try {
            // Close the phase before writing white so the raw GL opacity hook leaves its alpha at 1.
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            if (renderPhase.get() == SelfRenderPhase.PREPASS) {
                GL11.glDepthMask(true);
                GlStateManager.colorMask(false, false, false, false);
            }
            if (externalCosmeticOpenedPhase) {
                GL11.glDepthMask(externalCosmeticSavedDepthMask);
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                if (externalCosmeticSavedAlphaTest) {
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GlStateManager.enableAlpha();
                } else {
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GlStateManager.disableAlpha();
                }
                if (externalCosmeticSavedBlend) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GlStateManager.enableBlend();
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                    GlStateManager.disableBlend();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            externalCosmeticOpenedPhase = false;
            externalCosmeticDepthOnly = false;
        }
    }

    public static boolean shouldForceExternalCosmeticNoDepthWrite() {
        return (externalCosmeticRenderDepth > 0 || glHooksDepth > 0 && usesLunarLayerDepthPasses())
                && renderPhase.get() != SelfRenderPhase.PREPASS
                && isActive()
                && getAlpha() < 0.99f;
    }

    public static boolean isExternalCosmeticDepthOnly() {
        return externalCosmeticRenderDepth > 0 && externalCosmeticDepthOnly;
    }

    static int lunarCosmeticMeshPhase() {
        if (externalCosmeticRenderDepth <= 0 || !usesLunarLayerDepthPasses()) return 0;
        return externalCosmeticDepthOnly ? 1 : 2;
    }

    /** Lunar can change depth and color masks between the cosmetic callback and its mesh upload. */
    public static void applyExternalCosmeticDepthMasks() {
        if (externalCosmeticRenderDepth <= 0 || !usesLunarLayerDepthPasses()) return;
        boolean color = !externalCosmeticDepthOnly;
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(!color);
        GL11.glColorMask(color, color, color, color);
    }

    /** Weave-only direct player render; consumed by GlStateManagerMixin without affecting Forge's deferred path. */
    public static boolean isWeaveDirectPlayerRenderActive() {
        return WEAVE_RUNTIME && weaveDirectRenderDepth > 0;
    }

    /** Raw GL colour fallback for Lunar's rewritten third-person player and first-person hand renderers. */
    public static boolean shouldForceWeaveColorAlpha() {
        return WEAVE_RUNTIME && !isNametagLogoAlphaBypassActive()
                && ((isWeaveDirectPlayerRenderActive()
                && renderPhase.get() != SelfRenderPhase.PREPASS)
                || firstPersonArmActive || firstPersonHeldItemOpacityDepth > 0);
    }

    public static boolean isGuiInventoryOpacityRenderActive() {
        return false;
    }

    public static boolean isForcingGuiInventoryRawColor() {
        return forcingGuiInventoryRawColor;
    }

    public static boolean shouldGuardGuiInventoryStandardBlend(int sourceFactor, int destinationFactor) {
        return isGuiInventoryOpacityRenderActive()
                && !forcingGuiInventoryRawColor;
    }

    /** Reasserts only the framebuffer blend rule; safe to call after a renderer blend-state reset. */
    public static void forceGuiInventoryBlendState() {
        if (!isGuiInventoryOpacityRenderActive()) {
            return;
        }
        boolean wasForcing = forcingGuiInventoryRawColor;
        forcingGuiInventoryRawColor = true;
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(1.0F, 1.0F, 1.0F, getAlpha());
            GL11.glBlendFunc(32771 /* GL_CONSTANT_ALPHA */, 32772 /* GL_ONE_MINUS_CONSTANT_ALPHA */);
        } finally {
            forcingGuiInventoryRawColor = wasForcing;
        }
    }

    public static void noteGuiInventoryBridgeColorCall() {
        if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
            guiInventoryDiagnosticBridgeColorCalls++;
        }
    }

    public static void noteGuiInventoryRawColorCall() {
        if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
            guiInventoryDiagnosticRawColorCalls++;
        }
    }

    public static void noteGuiInventoryBlendFuncCall() {
        if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
            guiInventoryDiagnosticBlendFuncCalls++;
        }
    }

    public static void noteGuiInventoryDisableBlendAttempt() {
        if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
            guiInventoryDiagnosticDisableBlendAttempts++;
        }
    }

    public static void noteGuiInventoryRawDisableBlendAttempt() {
        if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
            guiInventoryDiagnosticRawDisableBlendAttempts++;
        }
    }

    /**
     * Applies inventory opacity at the blend stage, even when vanilla, OptiFine, or Lunar replaces
     * the current model colour with opaque white. The alpha test still discards transparent skin
     * texels, while every surviving fragment is composited with the configured opacity.
     */
    public static void forceGuiInventoryRawColor(float red, float green, float blue) {
        forcingGuiInventoryRawColor = true;
        try {
            if (guiInventoryDiagnosticEnabled && guiInventoryRenderDepth > 0) {
                guiInventoryDiagnosticForceCalls++;
            }
            forceGuiInventoryBlendState();
            // Keep alpha on the current colour too. If a third-party renderer uses a direct blend
            // entry point that cannot be intercepted, ordinary SRC_ALPHA blending still stays dimmed.
            GL11.glColor4f(red, green, blue, getAlpha());
        } finally {
            forcingGuiInventoryRawColor = false;
        }
    }

    private static void logGuiInventoryDiagnosticIfDue() {
        if (!guiInventoryDiagnosticEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - guiInventoryDiagnosticLastLogMs < 750L) {
            return;
        }
        guiInventoryDiagnosticLastLogMs = now;
        Minecraft mc = Minecraft.getMinecraft();
        String screen = mc != null && mc.currentScreen != null
                ? mc.currentScreen.getClass().getName() : "null";
        int f5 = mc != null && mc.gameSettings != null ? mc.gameSettings.thirdPersonView : -1;
        String endState = captureGuiInventoryGlState();
        String line = "frame=" + guiInventoryDiagnosticFrame
                + " screen=" + screen
                + " F5=" + f5
                + " configuredAlpha=" + getAlpha()
                + " beginCalls=" + guiInventoryDiagnosticBeginCalls
                + " maxDepth=" + guiInventoryDiagnosticMaxDepth
                + " forceCalls=" + guiInventoryDiagnosticForceCalls
                + " bridgeColorCalls=" + guiInventoryDiagnosticBridgeColorCalls
                + " rawColorCalls=" + guiInventoryDiagnosticRawColorCalls
                + " blendFuncCalls=" + guiInventoryDiagnosticBlendFuncCalls
                + " disableBlendAttempts=" + guiInventoryDiagnosticDisableBlendAttempts
                + " rawDisableBlendAttempts=" + guiInventoryDiagnosticRawDisableBlendAttempts
                + " beginGL={" + guiInventoryDiagnosticBeginState + "}"
                + " endGL={" + endState + "}";
        guiInventoryDiagnosticLastLine = line;
        System.err.println("[gomod][inventoryOpacityDiag] " + line);
    }

    private static String captureGuiInventoryGlState() {
        try {
            // LWJGL 2 requires room for the largest glGetFloat vector even for GL_CURRENT_COLOR.
            FloatBuffer color = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            float currentAlpha = color.get(3);
            return "blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + ",srcRGB=" + GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
                    + ",dstRGB=" + GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
                    + ",alphaTest=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    + ",depthMask=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + ",colorA=" + currentAlpha
                    + ",glError=" + GL11.glGetError();
        } catch (Throwable t) {
            return "queryError=" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    /**
     * During deferred third-person color pass we keep blend on so layer code cannot turn it off.
     * First-person arm/item opacity also keeps alpha blending through Lunar's render-state changes,
     * and yields whenever the enchant glint blend is active.
     */
    public static boolean shouldKeepBlendEnabled() {
        return shouldKeepFirstPersonHeldItemBlendEnabled()
                || singlePassLayerColorDepth > 0
                || (isWeaveDirectPlayerRenderActive() && renderPhase.get() == SelfRenderPhase.COLOR)
                || (glHooksDepth > 0 && renderPhase.get() == SelfRenderPhase.COLOR);
    }

    /**
     * Keep blended transparency through first-person hand/item draws, except during enchant glint.
     * The arm scope is the fallback for Lunar paths that bypass the vanilla item-only entry point.
     */
    public static boolean shouldKeepFirstPersonHeldItemBlendEnabled() {
        return (firstPersonHeldItemOpacityDepth > 0 || firstPersonArmActive)
                && getRenderAlpha() < 0.99f
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip();
    }

    /** Do not cancel {@code enableAlpha()}: player skin/layers need the alpha test path (see class javadoc). */
    public static boolean shouldKeepAlphaTestDisabled() {
        return false;
    }

    /**
     * Only third-person-<strong>behind</strong> ({@code thirdPersonView == 1}): that view benefits from
     * back-face culling to reduce the head shell showing through the hair/neck. Front third person
     * ({@code == 2}) applies model/camera transforms that invert winding vs GL's default cull mode;
     * forcing {@code GL_CULL_FACE} there removes almost the entire outer mesh (often read as “only feet
     * in front of the camera”). First person uses {@code == 0} — never force cull there.
     */
    private static boolean shouldEnableCullFaceForDeferredOpacityPass() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) {
                return false;
            }
            return mc.gameSettings.thirdPersonView == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── GL hook callbacks (called from RenderManagerMixin) ──────────────────

    /**
     * Called at the HEAD of {@code RenderManager.renderEntitySimple}.
     * Deferred flush only: set blend + a low alpha-test threshold so cutouts and skin layers render;
     * scaled colour alpha (see {@link me.napim.gomod.mixins.GlStateManagerMixin}) provides semi-transparency.
     */
    public static void onRenderEntitySimpleHead(Entity entity) {
        if (!shouldApplyToEntity(entity)) return;
        if (shouldUseWeaveDirectPlayerRender(entity)) {
            beginWeaveDirectPlayerRender(entity);
            return;
        }
        // Deferred path: skip during the normal entity pass.
        // When flushingDeferred is true we ARE in the deferred flush and must set up GL.
        if (!flushingDeferred.get()) return;
        if (glHooksDepth == 0) {
            EntityOpacityGlintBlendTracker.onEntitySimpleHeadOuterEntry();
            savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            if (renderPhase.get() == SelfRenderPhase.COLOR) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA / ONE_MINUS_SRC_ALPHA
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(false);
            }
            if (shouldEnableCullFaceForDeferredOpacityPass()) {
                // See {@link #shouldEnableCullFaceForDeferredOpacityPass()}; do not enable in front F5.
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
        }
        glHooksDepth++;
    }

    /**
     * Called at the TAIL of {@code RenderManager.renderEntitySimple}.
     * Restores GL blend and alpha-test state after the player has been drawn.
     */
    public static void onRenderEntitySimpleTail(Entity entity) {
        if (WEAVE_RUNTIME && weaveDirectRenderDepth > 0 && entity == weaveDirectEntity
                && !flushingDeferred.get()) {
            endWeaveDirectPlayerRender();
            return;
        }
        if (glHooksDepth <= 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || entity != mc.thePlayer) return;
        glHooksDepth--;
        if (glHooksDepth == 0) {
            GL11.glDepthMask(true); // restore depth writes
            if (!savedCullFace) GL11.glDisable(GL11.GL_CULL_FACE); // restore Minecraft's default (culling off)
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            if (savedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
            GlStateManager.disableBlend();
        }
    }

    private static boolean shouldUseWeaveDirectPlayerRender(Entity entity) {
        if (!WEAVE_RUNTIME || flushingDeferred.get() || hasDeferredRender() || !shouldApplyToEntity(entity)) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0
                    && !me.napim.gomod.util.OutlineRenderingHelper.isInOutlineMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Weave fallback for clients whose rewritten RenderGlobal path does not hit our normal deferred redirect.
     * Queue the local player and cancel this immediate draw; the later deferred flush keeps already-rendered
     * chests/entities visible through the transparent skin.
     */
    public static Boolean runWeaveDirectTwoPassIfNeeded(
            net.minecraft.client.renderer.entity.RenderManager rm, Entity entity, float partialTicks) {
        if (rm == null || entity == null || !shouldUseWeaveDirectPlayerRender(entity)
                || weaveDirectTwoPassOuterCall.get()) {
            return null;
        }
        try {
            scheduleDeferred(entity, partialTicks);
        } catch (Throwable t) {
            System.err.println("[gomod] Weave self-opacity defer: " + t.getMessage());
        }
        return Boolean.FALSE;
    }

    private static void beginWeaveDirectPlayerRender(Entity entity) {
        if (weaveDirectRenderDepth++ > 0) {
            return;
        }
        weaveDirectEntity = entity;
        try {
            if (!weaveDirectLogged) {
                weaveDirectLogged = true;
                System.out.println("[gomod] Weave self-opacity direct renderer active (alpha=" + getAlpha() + ")");
            }
            weaveDirectSavedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            weaveDirectSavedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            weaveDirectSavedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            weaveDirectSavedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            if (shouldEnableCullFaceForDeferredOpacityPass()) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
            if (renderPhase.get() == SelfRenderPhase.PREPASS) {
                GlStateManager.disableBlend();
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDepthMask(true);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(false);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, getAlpha());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void endWeaveDirectPlayerRender() {
        weaveDirectRenderDepth--;
        if (weaveDirectRenderDepth > 0) {
            return;
        }
        weaveDirectRenderDepth = 0;
        weaveDirectEntity = null;
        clearNametagLogoAlphaBypass();
        try {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glDepthMask(weaveDirectSavedDepthMask);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            if (weaveDirectSavedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
            if (weaveDirectSavedCullFace) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (weaveDirectSavedBlend) {
                GL11.glEnable(GL11.GL_BLEND);
                GlStateManager.enableBlend();
            } else {
                GL11.glDisable(GL11.GL_BLEND);
                GlStateManager.disableBlend();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean detectWeaveRuntime() {
        try {
            Class.forName("net.weavemc.api.ModInitializer", false, SelfOpacity.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
