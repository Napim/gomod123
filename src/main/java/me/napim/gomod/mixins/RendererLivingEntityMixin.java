package me.napim.gomod.mixins;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.CapeRenderContext;
import me.napim.gomod.features.LocalMobDisguise;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.MobTargetDisplay;
import me.napim.gomod.features.NickRealNametagOverlay;
import me.napim.gomod.features.SpectatorKillerNametagOverlay;
import me.napim.gomod.features.ReplayPlayerHealthTagFix;
import me.napim.gomod.features.GomodFriendPresenceIconOverlay;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.PeeEffectManager;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.TabPerformanceDebug;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.GomodPeerLunarFullAccess;
import me.napim.gomod.features.GomodLunarLayerPolicy;
import me.napim.gomod.features.LunarNametagDebugCapture;
import me.napim.gomod.features.LunarCosmeticRenderContext;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.LunarDisguiseCosmeticVisibility;
import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.entity.player.EntityPlayer;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.SchizoMode;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.util.OutlineRenderingHelper;
import me.napim.gomod.mixins.RendererLivingEntityInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityMixin {
    @Shadow
    protected abstract boolean canRenderName(EntityLivingBase entity);

    private static final Set<Integer> gomod$forcedVisibleEntities = new HashSet<Integer>();
    /** Prevents recursive health-label rendering if {@code renderLivingLabel} re-enters {@code doRender}. */
    private static int gomod$healthUiRecursionDepth = 0;
    /** Hard per-tick cap to avoid pathological stalls when tons of entities are rendered. */
    private static final int GOMOD_HEALTH_UI_MAX_PER_TICK = 12;
    private static int gomod$healthUiTick = Integer.MIN_VALUE;
    private static int gomod$healthUiRenderedThisTick = 0;
    private static final Map<LayerRenderer, Integer> gomod$profileLayerIds =
            new IdentityHashMap<LayerRenderer, Integer>();
    private static final Set<String> gomod$reportedDisguiseLunarLayers = new HashSet<String>();
    private static int gomod$nextProfileLayerId = 1;
    /** Prefer rendering the health label from renderName instead of doRender. */
    private static final boolean GOMOD_HEALTH_UI_USE_RENDER_NAME = true;
    /** Per-renderer flag: renderName path already drew the mob health/name rows for the current doRender call. */
    private boolean gomod$mobHealthNametagRenderedInNamePass = false;
    private boolean gomod$selfOpacitySinglePassLayers = false;
    private boolean gomod$guiPreviewSinglePassLayers = false;
    private int gomod$selfOpacityLocalNametagBypassDepth = 0;
    private long gomod$profilePlayerLayersStart = 0L;
    private long gomod$profilePlayerModelMethodStart = 0L;
    private boolean gomod$profilePlayerLayersBypassed = false;
    private boolean gomod$profilePlayerModelBypassed = false;
    private boolean gomod$modelMountDimActive = false;
    private boolean gomod$modelForceMountAlphaActive = false;
    private boolean gomod$modelMountColorRead = false;
    private long gomod$deepPlayerDoRenderStart = 0L;
    private String gomod$deepPlayerDoRenderSection = null;
    private long gomod$deepPlayerRenderNameStart = 0L;
    private String gomod$deepPlayerRenderNameSection = null;
    private long gomod$playerLagRenderStart;
    private long gomod$playerLagNameStart;
    private long gomod$playerLagModelStart;
    private long gomod$playerLagLayersStart;
    private float gomod$modelMountSavedR = 1.0F;
    private float gomod$modelMountSavedG = 1.0F;
    private float gomod$modelMountSavedB = 1.0F;
    /** One Lunar-layer visibility decision is shared across all layers of the current player render. */
    private EntityLivingBase gomod$lunarLayerPolicyEntity;
    private boolean gomod$lunarLayerPolicyComputed;
    private boolean gomod$lunarLayerPolicySuppress;

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("HEAD"), require = 0)
    private void gomod$deepPlayerDoRenderHead(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        NametagRenderDebugCapture.recordEntityRender("begin", entity, x, y, z);
        LunarCosmeticRenderContext.begin(entity);
        gomod$playerLagRenderStart = entity instanceof EntityPlayer ? TabPerformanceDebug.begin() : 0L;
        gomod$lunarLayerPolicyEntity = entity instanceof EntityPlayer ? entity : null;
        gomod$lunarLayerPolicyComputed = false;
        gomod$lunarLayerPolicySuppress = false;
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer == entity && LocalDisguise.fastEnabledFlag) {
            boolean sneaking = entity.isSneaking();
            int view = mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
            LunarConnectionDiagnostics.recordOnce("disguise.self_do_render_path",
                    "sneaking=" + sneaking + ",view=" + view,
                    "doRenderReached=true sneaking=" + sneaking + " thirdPersonView=" + view);
        }
        boolean bypass = RenderProfiler.shouldBypassPlayerHooks();
        gomod$deepPlayerDoRenderSection = bypass ? "DeepPlayer.RendererLivingEntity.doRender.bypassed"
                : "DeepPlayer.RendererLivingEntity.doRender.active";
        gomod$deepPlayerDoRenderStart = RenderProfiler.beginDeep(gomod$deepPlayerDoRenderSection);
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("RETURN"), require = 0)
    private void gomod$deepPlayerDoRenderReturn(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        NametagRenderDebugCapture.recordEntityRender("return", entity, x, y, z);
        LunarCosmeticRenderContext.end(entity);
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.PLAYER_RENDER, gomod$playerLagRenderStart);
        gomod$playerLagRenderStart = 0L;
        if (gomod$lunarLayerPolicyEntity == entity) {
            gomod$lunarLayerPolicyEntity = null;
            gomod$lunarLayerPolicyComputed = false;
            gomod$lunarLayerPolicySuppress = false;
        }
        if (gomod$deepPlayerDoRenderStart != 0L) {
            RenderProfiler.endDeep(gomod$deepPlayerDoRenderSection, gomod$deepPlayerDoRenderStart);
            gomod$deepPlayerDoRenderStart = 0L;
            gomod$deepPlayerDoRenderSection = null;
        }
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("HEAD"), require = 0)
    private void gomod$deepPlayerRenderNameHead(EntityLivingBase entity, double x, double y, double z,
            CallbackInfo ci) {
        LunarCosmeticRenderContext.beginName(entity);
        NametagRenderDebugCapture.recordNamePass("begin", entity, x, y, z);
        gomod$playerLagNameStart = entity instanceof EntityPlayer ? TabPerformanceDebug.begin() : 0L;
        if (!RenderProfiler.shouldBypassPlayerHooks()
                && SelfOpacity.beginLocalPlayerNametagAlphaBypass(entity)) {
            gomod$selfOpacityLocalNametagBypassDepth++;
        }
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer == entity && LocalDisguise.fastEnabledFlag) {
            boolean sneaking = entity.isSneaking();
            int view = mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
            LunarConnectionDiagnostics.recordOnce("disguise.self_render_name_path",
                    "sneaking=" + sneaking + ",view=" + view,
                    "renderNameReached=true sneaking=" + sneaking + " thirdPersonView=" + view);
        }
        boolean bypass = RenderProfiler.shouldBypassPlayerHooks();
        gomod$deepPlayerRenderNameSection = bypass ? "DeepPlayer.RendererLivingEntity.renderName.bypassed"
                : "DeepPlayer.RendererLivingEntity.renderName.active";
        gomod$deepPlayerRenderNameStart = RenderProfiler.beginDeep(gomod$deepPlayerRenderNameSection);
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("RETURN"), require = 0)
    private void gomod$deepPlayerRenderNameReturn(EntityLivingBase entity, double x, double y, double z,
            CallbackInfo ci) {
        LunarCosmeticRenderContext.endName(entity);
        NametagRenderDebugCapture.recordNamePass("return", entity, x, y, z);
        Minecraft mc = Minecraft.getMinecraft();
        if (gomod$selfOpacityLocalNametagBypassDepth > 0 && mc != null && mc.thePlayer == entity) {
            gomod$selfOpacityLocalNametagBypassDepth--;
            SelfOpacity.endNametagLogoAlphaBypass();
        }
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.PLAYER_NAMETAG, gomod$playerLagNameStart);
        gomod$playerLagNameStart = 0L;
        if (gomod$deepPlayerRenderNameStart != 0L) {
            RenderProfiler.endDeep(gomod$deepPlayerRenderNameSection, gomod$deepPlayerRenderNameStart);
            gomod$deepPlayerRenderNameStart = 0L;
            gomod$deepPlayerRenderNameSection = null;
        }
    }

    /**
     * Observe the native Lunar name pass only during an explicit logo capture.
     * Lunar owns logo drawing; the former overlay no longer consumes a player label.
     */
    @Inject(method = {"renderName", "func_177067_a"}, at = @At("TAIL"), require = 0)
    private void gomod$drawLunarLogoAfterPlayerNamePass(EntityLivingBase entity, double x, double y, double z,
            CallbackInfo ci) {
        if (!LunarNametagDebugCapture.isActive()
                || !(entity instanceof AbstractClientPlayer) || RenderProfiler.shouldBypassPlayerHooks()) {
            return;
        }
        LunarNametagDebugCapture.recordRendererHook();
        try {
            if (!canRenderName(entity)
                    || OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            LunarNametagDebugCapture.recordPrimaryNameCandidate();
            LunarNametagDebugCapture.recordRenderOutcome("native_lunar_renderer_owns_logo");
        } catch (Throwable ignored) {
            LunarNametagDebugCapture.recordRenderOutcome("name_pass_exception_"
                    + ignored.getClass().getSimpleName());
        }
    }

    @Inject(method = {"renderModel", "func_77036_a"}, at = @At("HEAD"), require = 0)
    private void gomod$profilePlayerModelMethodHead(EntityLivingBase entity, float limbSwing,
            float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scale,
            CallbackInfo ci) {
        gomod$playerLagModelStart = entity instanceof EntityPlayer ? TabPerformanceDebug.begin() : 0L;
        if (entity instanceof EntityPlayer && RenderProfiler.shouldGpuSynchronize()) {
            org.lwjgl.opengl.GL11.glFinish();
        }
        gomod$profilePlayerModelBypassed = entity instanceof EntityPlayer
                && RenderProfiler.shouldBypassPlayerHooks();
        gomod$profilePlayerModelMethodStart = entity instanceof EntityPlayer
                ? RenderProfiler.begin(gomod$profilePlayerModelBypassed
                ? "PlayerPipeline.modelMethod.hooksBypassed"
                : "PlayerPipeline.modelMethod.hooksActive") : 0L;

        gomod$modelMountDimActive = false;
        gomod$modelForceMountAlphaActive = false;
        gomod$modelMountColorRead = false;
        LunarDisguiseCosmeticVisibility.beginPlayerModelRender(entity,
                ((RendererLivingEntity) (Object) this).getMainModel());
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;

        // Preserve Lunar/vanilla's original renderModel invocation. Only layer gomod's
        // temporary GL state around that call when a feature genuinely needs it.
        if (OutlineRenderingHelper.isInOutlineMode()) {
            int rgb = OutlineRenderingHelper.getGlowColorRgb();
            GlStateManager.color(((rgb >> 16) & 0xFF) / 255.0F,
                    ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            return;
        }

        gomod$modelMountDimActive = MountHeadDim.shouldApplyModelDimInRenderModelRedirect(entity);
        gomod$modelForceMountAlphaActive = !gomod$modelMountDimActive
                && MountHeadDim.shouldApplyMountDimGlHooks()
                && MountHeadDim.isLocalPlayerRiddenMount(entity);
        if (gomod$modelMountDimActive) {
            float alpha = MountHeadDim.getAlphaForMount(entity);
            gomod$modelMountColorRead = MountHeadDim.useGlColorRead();
            if (gomod$modelMountColorRead) {
                float[] rgb = new float[3];
                MountHeadDim.readCurrentGlRgb(rgb);
                gomod$modelMountSavedR = rgb[0];
                gomod$modelMountSavedG = rgb[1];
                gomod$modelMountSavedB = rgb[2];
                MountHeadDim.applyTintRgbAlpha(rgb[0], rgb[1], rgb[2], alpha);
            } else {
                MountHeadDim.applyTintRgbAlpha(1.0F, 1.0F, 1.0F, alpha);
            }
        } else if (gomod$modelForceMountAlphaActive) {
            MountHeadDim.forceActiveMountGlColor();
        }
    }

    @Inject(method = {"renderModel", "func_77036_a"}, at = @At("RETURN"), require = 0)
    private void gomod$profilePlayerModelMethodReturn(EntityLivingBase entity, float limbSwing,
            float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scale,
            CallbackInfo ci) {
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.PLAYER_MODEL, gomod$playerLagModelStart);
        gomod$playerLagModelStart = 0L;
        LunarDisguiseCosmeticVisibility.endPlayerModelRender();
        if (gomod$profilePlayerModelMethodStart != 0L) {
            if (RenderProfiler.shouldGpuSynchronize()) {
                org.lwjgl.opengl.GL11.glFinish();
            }
            RenderProfiler.end(gomod$profilePlayerModelBypassed
                    ? "PlayerPipeline.modelMethod.hooksBypassed"
                    : "PlayerPipeline.modelMethod.hooksActive", gomod$profilePlayerModelMethodStart);
            gomod$profilePlayerModelMethodStart = 0L;
        }
        if (gomod$modelMountDimActive) {
            if (gomod$modelMountColorRead) {
                MountHeadDim.restoreTintRgb(gomod$modelMountSavedR, gomod$modelMountSavedG,
                        gomod$modelMountSavedB);
            } else {
                MountHeadDim.restoreTintRgb(1.0F, 1.0F, 1.0F);
            }
        } else if (gomod$modelForceMountAlphaActive) {
            MountHeadDim.forceActiveMountGlColor();
        }
        gomod$modelMountDimActive = false;
        gomod$modelForceMountAlphaActive = false;
        gomod$modelMountColorRead = false;
    }

    /**
     * A hurt entity is normally drawn a second time with a red brightness overlay.  For the deferred transparent
     * local mount that second blended pass also re-blends pixels from nametags already present in the framebuffer,
     * making them suddenly look like an ESP overlay.  The normal model still flashes through its regular damage
     * animation; only the redundant brightness pass is skipped for this one transparent entity.
     */
    @Inject(method = {"setBrightness", "func_177092_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$suppressTransparentMountHurtBrightness(EntityLivingBase entity, float partialTicks,
            boolean combineTextures, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (entity != null && entity.hurtTime > 0
                    && MountHeadDim.isLocalPlayerRiddenMount(entity)
                    && MountHeadDim.getAlphaForMount(entity) < 0.999f) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }

    // Note: Outline rendering mode state is now tracked in OutlineRenderingHelper utility class

    // --- Roleplay: freeze mc.thePlayer's visual rotation ---
    // During roleplay the mouse still drives mc.thePlayer.rotationYaw/Pitch (needed for
    // movement & combat), but we don't want the model we see from the target's viewpoint
    // to spin with every mouse move.  We save the live values at doRender HEAD and
    // overwrite them with the movement-based body yaw so the head faces forward, then
    // restore the real values at TAIL so gameplay is unaffected.
    private float gomod$rp_savedYaw;
    private float gomod$rp_savedPrevYaw;
    private float gomod$rp_savedYawHead;
    private float gomod$rp_savedPrevYawHead;
    private float gomod$rp_savedPitch;
    private float gomod$rp_savedPrevPitch;
    private float gomod$rp_savedRenderYawOffset;
    private float gomod$rp_savedPrevRenderYawOffset;
    private boolean gomod$rp_didFreezeRotation = false;

    // --- Schizo: force other players to face mc.thePlayer ---
    private float gomod$schizo_savedYaw;
    private float gomod$schizo_savedPrevYaw;
    private float gomod$schizo_savedYawHead;
    private float gomod$schizo_savedPrevYawHead;
    private float gomod$schizo_savedPitch;
    private float gomod$schizo_savedPrevPitch;
    private float gomod$schizo_savedRenderYawOffset;
    private float gomod$schizo_savedPrevRenderYawOffset;
    private boolean gomod$schizo_didOverride = false;
    
    /**
     * Force nametag rendering for mc.thePlayer during roleplay mode.
     * Vanilla suppresses the nametag for the local player (canRenderName returns false),
     * but during roleplay we're viewing from another player's perspective and should
     * see our own nametag.
     */
    @Inject(method = "canRenderName", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$forceNametagInRoleplay(EntityLivingBase entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            if (!RoleplayMode.isEnabled()) return;
            if (!(entity instanceof EntityPlayer)) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && entity == mc.thePlayer) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = {"canRenderName", "func_177070_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$forceMobNametags(EntityLivingBase entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            // MobNametags is explicitly player-excluding. Keep only the player-specific
            // replay/spectator decisions for players and skip the mob feature chain.
            if (entity instanceof EntityPlayer) {
                if (VisibleSpecs.isRuntimeActiveForRender()
                        && VisibleSpecs.shouldForcePlayerNametagForLocalSpectator((EntityPlayer) entity)) {
                    cir.setReturnValue(true);
                    return;
                }
                if (GomodFixes.isWeaveRuntime()) {
                    if (VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                        cir.setReturnValue(true);
                        return;
                    }
                    if (VisibleSpecs.isReplayContext()
                            && VisibleSpecs.isApplySettingsToReplaysEnabled()
                            && SpectatorKillerNametagOverlay.shouldShowFor((EntityPlayer) entity)) {
                        cir.setReturnValue(true);
                    }
                }
                return;
            }
            EntityLivingBase proxy = LocalMobDisguise.getProxyEntity();
            if (proxy != null && entity == proxy) {
                cir.setReturnValue(false);
                return;
            }
            if (MobNametags.shouldForceNameRender(entity)) {
                cir.setReturnValue(true);
                return;
            }
            if (entity instanceof EntityPlayer
                    && VisibleSpecs.shouldForcePlayerNametagForLocalSpectator((EntityPlayer) entity)) {
                cir.setReturnValue(true);
                return;
            }
            if (VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                cir.setReturnValue(true);
                return;
            }
            if (entity instanceof EntityPlayer && VisibleSpecs.isReplayContext()
                    && VisibleSpecs.isApplySettingsToReplaysEnabled()
                    && SpectatorKillerNametagOverlay.shouldShowFor((EntityPlayer) entity)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$forceWeaveReplayHealthStack(EntityLivingBase entity, double x, double y, double z,
            CallbackInfo ci) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            // On Forge the normal player nametag bridge owns this path; only Lunar needs the replay fallback.
            if (!GomodFixes.isWeaveRuntime()) {
                return;
            }
            if (MountPreviewHelper.shouldSuppressGuiPreviewNametag(entity)) {
                ci.cancel();
                return;
            }
            // Lunar can bypass renderName entirely for replay actors. When it does call us, suppress its partial
            // nameplate; the complete stack is drawn from doRender TAIL, which Lunar does consistently invoke.
            if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(entity)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            System.err.println("[gomod] replay health nametag fix: " + t.getMessage());
        }
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("TAIL"), require = 0)
    private void gomod$forceWeaveReplayHealthStackAfterEntity(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        // Forge runs the normal RenderPlayer/NametagLayerBridge path. This tail injection is a Lunar/Weave
        // compatibility fallback; invoking it on Forge made every player pay fallback checks in the hot path.
        if (!GomodFixes.isWeaveRuntime()) return;
        long replayProfile = entity instanceof EntityPlayer
                ? RenderProfiler.begin("GomodPlayerHooks.replayNameplateTail") : 0L;
        try {
            ReplayPlayerHealthTagFix.renderForcedStack(
                    (RendererLivingEntity) (Object) this, entity, x, y, z);
        } finally {
            RenderProfiler.end("GomodPlayerHooks.replayNameplateTail", replayProfile);
        }
        long nickProfile = entity instanceof EntityPlayer
                ? RenderProfiler.begin("GomodPlayerHooks.nickNameplateTail") : 0L;
        try {
            if (GomodFixes.isWeaveRuntime() && entity instanceof EntityPlayer
                    && !OutlineRenderingHelper.isInOutlineMode()
                    && !OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                NickRealNametagOverlay.tryRenderStandingNametagFallback(
                        (EntityPlayer) entity, (Render) (Object) this, x, y, z);
                MobTargetDisplay.tryRenderStandingAggroMarkerFallback(
                        (EntityPlayer) entity, (Render) (Object) this, x, y, z);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] replay health nametag doRender fix: " + t.getMessage());
        } finally {
            RenderProfiler.end("GomodPlayerHooks.nickNameplateTail", nickProfile);
            // Only the Lunar/Weave cloak bridge consumes this context. Forge has no
            // matching consumer, so avoid the ThreadLocal stack work there.
            if (GomodFixes.isWeaveRuntime()) {
                CapeRenderContext.end(entity);
            }
        }
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("HEAD"), require = 0)
    private void gomod$resetMobHealthNametagRenderFlag(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) {
            gomod$mobHealthNametagRenderedInNamePass = false;
            return;
        }
        gomod$mobHealthNametagRenderedInNamePass = false;
        // Keep the context for Weave/Lunar cloak hooks, but remove its per-player
        // ThreadLocal push from the Forge render hot path.
        if (GomodFixes.isWeaveRuntime()) {
            CapeRenderContext.begin(entity);
        }
    }

    /**
     * Strategy 7: dim the whole {@code renderLivingAt} pass (model + vanilla layers together).
     * require=0: Lunar bytecode may differ; if this mixin doesn't apply, use strategies 1–6.
     */
    @Redirect(
        method = "doRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;renderLivingAt(Lnet/minecraft/entity/EntityLivingBase;DDD)V"),
        require = 0
    )
    private void gomod$mountStrategy7RenderLivingAt(
            net.minecraft.client.renderer.entity.RendererLivingEntity self,
            EntityLivingBase entity,
        double x,
        double y,
        double z
    ) {
        try {
            if (!MountHeadDim.shouldApplyStrategy7LivingAtWrap(entity)) {
                gomod$invokeRenderLivingAtProfiled(self, entity, x, y, z);
                return;
            }
            float a = MountHeadDim.getAlphaForMount(entity);
            if (MountHeadDim.useGlColorRead()) {
                float[] rgb = new float[3];
                MountHeadDim.readCurrentGlRgb(rgb);
                MountHeadDim.applyTintRgbAlpha(rgb[0], rgb[1], rgb[2], a);
                gomod$invokeRenderLivingAtProfiled(self, entity, x, y, z);
                MountHeadDim.restoreTintRgb(rgb[0], rgb[1], rgb[2]);
            } else {
                MountHeadDim.applyTintRgbAlpha(1f, 1f, 1f, a);
                gomod$invokeRenderLivingAtProfiled(self, entity, x, y, z);
                MountHeadDim.restoreTintRgb(1f, 1f, 1f);
            }
        } catch (Throwable t) {
            gomod$invokeRenderLivingAtProfiled(self, entity, x, y, z);
        }
    }

    private static void gomod$invokeRenderLivingAtProfiled(
            net.minecraft.client.renderer.entity.RendererLivingEntity self,
            EntityLivingBase entity, double x, double y, double z) {
        if (!(entity instanceof EntityPlayer)) {
            ((RendererLivingEntityInvoker) self).gomod$invokeRenderLivingAt(entity, x, y, z);
            return;
        }
        boolean bypass = RenderProfiler.shouldBypassPlayerHooks();
        String section = "DeepPlayer.RendererLivingEntity.renderLivingAt."
                + (bypass ? "bypassed" : "active");
        long start = RenderProfiler.beginDeep(section);
        try {
            ((RendererLivingEntityInvoker) self).gomod$invokeRenderLivingAt(entity, x, y, z);
        } finally {
            RenderProfiler.endDeep(section, start);
        }
    }

    /**
     * Horse armor / extra layers draw after {@code renderModel}; apply the same alpha for the full mount.
     */
    @Inject(method = {"renderLayers", "func_177093_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$mountDimBeforeLayers(EntityLivingBase entitylivingbaseIn, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$playerLagLayersStart = entitylivingbaseIn instanceof EntityPlayer ? TabPerformanceDebug.begin() : 0L;
        if (entitylivingbaseIn instanceof EntityPlayer && RenderProfiler.shouldGpuSynchronize()) {
            org.lwjgl.opengl.GL11.glFinish();
        }
        gomod$profilePlayerLayersBypassed = entitylivingbaseIn instanceof EntityPlayer
                && RenderProfiler.shouldBypassPlayerHooks();
        gomod$profilePlayerLayersStart = entitylivingbaseIn instanceof EntityPlayer
                ? RenderProfiler.begin(gomod$profilePlayerLayersBypassed
                ? "PlayerPipeline.layers.hooksBypassed"
                : "PlayerPipeline.layers.hooksActive") : 0L;
        if (entitylivingbaseIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) {
            gomod$guiPreviewSinglePassLayers = false;
            gomod$selfOpacitySinglePassLayers = false;
            return;
        }
        try {
            gomod$guiPreviewSinglePassLayers = false;
            if (MountPreviewHelper.shouldSkipGuiPreviewLayersInColor(entitylivingbaseIn)) {
                ci.cancel();
                return;
            }
            gomod$guiPreviewSinglePassLayers =
                    MountPreviewHelper.beginGuiPreviewSinglePassLayerColor(entitylivingbaseIn);
            gomod$selfOpacitySinglePassLayers = false;
            if (SelfOpacity.shouldSkipSinglePassLayersInColor(entitylivingbaseIn)) {
                ci.cancel();
                return;
            }
            gomod$selfOpacitySinglePassLayers = SelfOpacity.beginSinglePassLayerColor(entitylivingbaseIn);
            MountHeadDim.beginLayerAlphaTint(entitylivingbaseIn);
            if (MountHeadDim.shouldApplyMountDimGlHooks()
                    && MountHeadDim.isLocalPlayerRiddenMount(entitylivingbaseIn)) {
                MountHeadDim.forceActiveMountGlColor();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"renderLayers", "func_177093_a"}, at = @At("RETURN"), require = 0)
    private void gomod$mountDimAfterLayers(EntityLivingBase entitylivingbaseIn, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.PLAYER_LAYERS, gomod$playerLagLayersStart);
        gomod$playerLagLayersStart = 0L;
        if (entitylivingbaseIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) {
            if (gomod$profilePlayerLayersStart != 0L && RenderProfiler.shouldGpuSynchronize()) {
                org.lwjgl.opengl.GL11.glFinish();
            }
            RenderProfiler.end(gomod$profilePlayerLayersBypassed
                    ? "PlayerPipeline.layers.hooksBypassed"
                    : "PlayerPipeline.layers.hooksActive", gomod$profilePlayerLayersStart);
            gomod$profilePlayerLayersStart = 0L;
            return;
        }
        try {
            MountHeadDim.endLayerAlphaTint();
            if (MountHeadDim.shouldApplyMountDimGlHooks()
                    && MountHeadDim.isLocalPlayerRiddenMount(entitylivingbaseIn)) {
                MountHeadDim.forceActiveMountGlColor();
            }
        } catch (Throwable ignored) {
        } finally {
            if (gomod$guiPreviewSinglePassLayers) {
                gomod$guiPreviewSinglePassLayers = false;
                MountPreviewHelper.endGuiPreviewSinglePassLayerColor();
            }
            if (gomod$selfOpacitySinglePassLayers) {
                gomod$selfOpacitySinglePassLayers = false;
                SelfOpacity.endSinglePassLayerColor();
            }
            if (gomod$profilePlayerLayersStart != 0L && RenderProfiler.shouldGpuSynchronize()) {
                org.lwjgl.opengl.GL11.glFinish();
            }
            RenderProfiler.end(gomod$profilePlayerLayersBypassed
                    ? "PlayerPipeline.layers.hooksBypassed"
                    : "PlayerPipeline.layers.hooksActive", gomod$profilePlayerLayersStart);
            gomod$profilePlayerLayersStart = 0L;
        }
        try {
            if (entitylivingbaseIn instanceof EntityPlayer) {
                ModelBase mainModel = ((RendererLivingEntity) (Object) this).getMainModel();
                if (mainModel instanceof ModelBiped) {
                    PeeEffectManager.renderPlayerSkinStains(
                            (EntityPlayer) entitylivingbaseIn, (ModelBiped) mainModel, scale);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Break the aggregate player-layer timing down by Lunar/vanilla layer implementation. */
    @Redirect(
            method = {"renderLayers", "func_177093_a"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/LayerRenderer;doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V"),
            require = 0
    )
    private void gomod$profileIndividualPlayerLayer(LayerRenderer layer, EntityLivingBase entity,
            float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
            float netHeadYaw, float headPitch, float scale) {
        // Lunar registers hats/wings/auras/emotes as ordinary player layers. Classify the layer once and
        // reuse the player's visibility policy across the current doRender call.
        if (gomod$shouldSuppressLunarCosmeticLayer(layer, entity)) {
            return;
        }
        if (!(entity instanceof EntityPlayer) || !RenderProfiler.isRecording()) {
            layer.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks,
                    netHeadYaw, headPitch, scale);
            return;
        }
        String simpleName = layer.getClass().getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            simpleName = layer.getClass().getName();
        }
        Integer layerId = gomod$profileLayerIds.get(layer);
        if (layerId == null) {
            layerId = Integer.valueOf(gomod$nextProfileLayerId++);
            gomod$profileLayerIds.put(layer, layerId);
        }
        String mode = RenderProfiler.shouldBypassPlayerHooks() ? ".hooksBypassed" : ".hooksActive";
        String section = "PlayerLayer." + simpleName + "#" + layerId + mode;
        if (RenderProfiler.shouldGpuSynchronize()) {
            org.lwjgl.opengl.GL11.glFinish();
        }
        long profile = RenderProfiler.begin(section);
        try {
            layer.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks,
                    netHeadYaw, headPitch, scale);
        } finally {
            if (RenderProfiler.shouldGpuSynchronize()) {
                org.lwjgl.opengl.GL11.glFinish();
            }
            RenderProfiler.end(section, profile);
        }
    }

    private boolean gomod$shouldSuppressLunarCosmeticLayer(LayerRenderer layer,
            EntityLivingBase entity) {
        if (layer == null || !(entity instanceof EntityPlayer) || RenderProfiler.shouldBypassPlayerHooks()) {
            return false;
        }
        if (!GomodLunarLayerPolicy.isLunarCosmeticLayer(layer.getClass())) {
            return false;
        }
        if (gomod$lunarLayerPolicyEntity == entity && gomod$lunarLayerPolicyComputed) {
            return gomod$lunarLayerPolicySuppress;
        }
        long profile = RenderProfiler.begin("GomodPlayerHooks.lunarLayerPolicy");
        try {
            String className = layer.getClass().getName();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                if (gomod$lunarLayerPolicyEntity == entity) {
                    gomod$lunarLayerPolicySuppress = false;
                    gomod$lunarLayerPolicyComputed = true;
                }
                return false;
            }
            boolean suppress;
            if (me.napim.gomod.features.LunarRemoteDisguiseCosmetics.shouldHideBridge(entity)) {
                suppress = true;
            } else if (entity != mc.thePlayer) {
                java.util.UUID playerId = ((EntityPlayer) entity).getUniqueID();
                suppress = GomodPeerLunarFullAccess.shouldHideLunarCosmetics(playerId);
            } else {
                suppress = LocalDisguise.fastEnabledFlag
                        && ConfigManager.isDisguiseHideLunarCosmeticsOnWeave();
            }
            if (suppress && entity == mc.thePlayer && LocalDisguise.fastEnabledFlag
                    && ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()) {
                synchronized (gomod$reportedDisguiseLunarLayers) {
                    if (gomod$reportedDisguiseLunarLayers.add(className)) {
                        LunarConnectionDiagnostics.record("disguise.lunar_layer_suppressed",
                                "layer=" + className);
                    }
                }
            }
            if (gomod$lunarLayerPolicyEntity == entity) {
                gomod$lunarLayerPolicySuppress = suppress;
                gomod$lunarLayerPolicyComputed = true;
            }
            return suppress;
        } catch (Throwable ignored) {
            if (gomod$lunarLayerPolicyEntity == entity) {
                gomod$lunarLayerPolicySuppress = false;
                gomod$lunarLayerPolicyComputed = true;
            }
            return false;
        } finally {
            RenderProfiler.end("GomodPlayerHooks.lunarLayerPolicy", profile);
        }
    }

    /**
     * Skip shadow rendering during our custom glow outline pass.
     *
     * The shadow is a textured quad drawn on the ground at the entity's feet.
     * Because our outline pass disables GL_TEXTURE_2D globally (to produce a
     * flat-color silhouette for the shader), the shadow renders as a solid
     * colored disc on the ground, which the outline shader then adds glow to.
     *
     * By cancelling renderShadow during outline mode we eliminate that disc so
     * only the player body+gear silhouette ends up in the outline framebuffer.
     * require=0 means the injection silently does nothing if the method name
     * doesn't match this particular build's mapping.
     */
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipShadowInOutlinePass(Entity entityIn, double x, double y, double z, float shadowAlpha, float partialTicks, CallbackInfo ci) {
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()
                || OutlineRenderingHelper.isModEntityOutlinePassActive()) {
            ci.cancel();
        }
    }

    /**
     * Scale ground-shadow alpha with our opacity systems:
     * - /opacityme: local-player shadow fades with model alpha
     * - VisibleSpecs opacity: revealed invisible players' shadows fade too
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "renderShadow",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true,
            require = 0
    )
    private float gomod$scaleShadowAlphaForOpacity(float shadowAlpha, Entity entityIn, double x, double y, double z, float partialTicks) {
        float a = shadowAlpha;
        try {
            if (SelfOpacity.shouldApplyToEntity(entityIn)) {
                a *= Math.max(0.0f, Math.min(1.0f, SelfOpacity.getRenderAlpha()));
            }
            if (VisibleSpecs.shouldApplyOpacityToEntity(entityIn)) {
                a *= VisibleSpecs.getRenderOpacity();
            }
        } catch (Throwable ignored) {
        }
        return a;
    }

    /**
     * Defer player nametag rendering to after tile entities.
     *
     * Root cause: {@code RenderGlobal.renderEntities} draws tile entities (chests …)
     * AFTER regular entities. Nametags use {@code glDisable(GL_DEPTH_TEST)} but tile
     * entities rendered later overwrite those color pixels, making the nametag
     * invisible when a chest is between the camera and the player.
     *
     * Fix: suppress the render here, store the arguments, replay at TAIL of
     * {@code renderEntities} via {@link DeferredNametagRenderer}.
     */
    /**
     * Health / combined mob rows: draw via {@code renderLivingLabel} at entity-pass timing (not world-last
     * {@link me.napim.gomod.render.VanillaEntityLabelRenderer}).
     */
    @Inject(method = {"renderName", "func_177067_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$renderMobHealthNametagAtName(EntityLivingBase entityIn, double x, double y, double z,
            CallbackInfo ci) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        // Mob health rows are never supported for players. Keep player-specific name/disguise
        // hooks running, but avoid entering the mob health/deferred-label pipeline entirely.
        if (entityIn instanceof EntityPlayer) return;
        if (gomod$shouldDeferMobHealthNametagThroughChests(entityIn, x, y, z)) {
            ci.cancel();
            return;
        }
        if (gomod$drawMobHealthNametagRows(entityIn, x, y, z)) {
            ci.cancel();
        }
    }

    private boolean gomod$drawMobHealthNametagRows(EntityLivingBase entityIn, double x, double y, double z) {
        try {
            if (DeferredNametagRenderer.isFlushing()) {
                return false;
            }
            if (gomod$shouldDeferMobHealthNametagThroughChests(entityIn, x, y, z)) {
                return false;
            }
            boolean rendered = MobHealthDisplay.drawHealthNametagRows(entityIn, x, y, z);
            if (rendered) {
                gomod$mobHealthNametagRenderedInNamePass = true;
            }
            return rendered;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderMobHealthNametagDoRenderFallback(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        if (entity instanceof EntityPlayer) return;
        try {
            if (gomod$mobHealthNametagRenderedInNamePass) {
                return;
            }
            if (gomod$shouldDeferMobHealthNametagThroughChests(entity, x, y, z)) {
                return;
            }
            gomod$drawMobHealthNametagRows(entity, x, y, z);
        } catch (Throwable ignored) {
        }
    }

    private boolean gomod$shouldDeferMobHealthNametagThroughChests(EntityLivingBase entity,
            double x, double y, double z) {
        try {
            return DeferredNametagRenderer.shouldDeferThroughChests(entity, x, y, z)
                    && MobHealthDisplay.shouldRenderHealthNametagFor(entity);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$deferNametag(EntityLivingBase entityIn, double x, double y, double z, CallbackInfo ci) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(entityIn, x, y, z)) {
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldSuppressNametag(entityIn)) {
            if (!(entityIn instanceof EntityPlayer)
                    || !SpectatorKillerNametagOverlay.shouldUseMainIgnSlotForKiller((EntityPlayer) entityIn)) {
                ci.cancel();
                return;
            }
        }
        if (VisibleSpecs.isFlushingDeferredNametags()) {
            return;
        }
        // During the deferred-flush pass, let renderName proceed normally.
        if (DeferredNametagRenderer.isFlushing()) return;
        // VisibleSpecs draws spectator-opacity bodies after the normal nametag flush point.
        // Do not enqueue these labels for a flush that has already happened this frame:
        // skip the depth-only prepass, then replay the color-pass nametag after opacity
        // hooks are reset so label alpha stays vanilla.
        if (VisibleSpecs.isDeferredOpacityFlushActive()) {
            if (VisibleSpecs.isDeferredOpacityPrepassActive()) {
                ci.cancel();
            } else if (VisibleSpecs.shouldDeferNametagForOpacity(entityIn)) {
                VisibleSpecs.scheduleDeferredNametag((RendererLivingEntity)(Object)this, entityIn, x, y, z);
                ci.cancel();
            }
            return;
        }
        // Let the outline-mode / suppress-nametag inject (below) handle these.
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) return;

        // The remaining branches are mob-disguise/mob-nametag handling. Player
        // disguise labels use the player-specific label mixins instead.
        if (entityIn instanceof EntityPlayer) {
            if (DeferredNametagRenderer.enqueueSelfOpacityReplay(
                    (RendererLivingEntity) (Object) this, entityIn, x, y, z)) {
                ci.cancel();
                return;
            }
            if (!DeferredNametagRenderer.shouldDeferThroughChests(entityIn, x, y, z)) return;
            DeferredNametagRenderer.enqueue((RendererLivingEntity) (Object) this, entityIn, x, y, z);
            ci.cancel();
            return;
        }

        // Let existing inject handle the mob-disguise proxy cancel.
        try {
            EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
            if (mobProxy != null && entityIn == mobProxy) return;
        } catch (Throwable ignored) {}
        // Let existing inject handle custom mob nametag rendering.
        try {
            if (MobNametags.shouldRenderMobNametag(entityIn)) {
                if (MobHealthDisplay.shouldRenderPlainMobNameInEntityTail(entityIn)) {
                    ci.cancel();
                    return;
                }
                if (MobHealthDisplay.shouldRenderHealthNametagFor(entityIn)) {
                    if (DeferredNametagRenderer.shouldDeferThroughChests(entityIn, x, y, z)) {
                        ci.cancel();
                    }
                    return;
                }
                if (DeferredNametagRenderer.shouldDeferThroughChests(entityIn, x, y, z)) {
                    ci.cancel();
                }
                return;
            }
        } catch (Throwable ignored) {}
        return;
    }

    /**
     * Suppress nametag rendering during the outline pass.
     * Without this the floating name-plate renders as a flat-colour plane
     * that the outline shader puts a glow border around.
     * require=0 — silently ignored if the method name doesn't match this build.
     */
    @Inject(method = {"renderName", "func_177067_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$skipNametagInOutlinePass(EntityLivingBase entityIn, double x, double y, double z, CallbackInfo ci) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(entityIn, x, y, z)) {
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldSuppressNametag(entityIn)) {
            if (!(entityIn instanceof EntityPlayer)
                    || !SpectatorKillerNametagOverlay.shouldUseMainIgnSlotForKiller((EntityPlayer) entityIn)) {
                ci.cancel();
                return;
            }
        }

        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            ci.cancel();
            return;
        }

        // Player name/disguise labels are handled by the player label mixins. Avoid
        // probing the mob-disguise proxy and armor-stand path for every player render.
        if (entityIn instanceof EntityPlayer) return;

        try {
            EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
            if (mobProxy != null && entityIn == mobProxy) {
                ci.cancel();
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (!(entityIn instanceof EntityArmorStand) || !LocalDisguise.isEnabled()) {
                return;
            }

            String displayName = entityIn.getDisplayName() == null ? null : entityIn.getDisplayName().getFormattedText();
            if (LocalDisguise.shouldHideRenderedLabel(displayName)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("TAIL"), require = 0)
    private void gomod$nickRealIgnAboveNametag(EntityLivingBase entityIn, double x, double y, double z, CallbackInfo ci) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            if (!(entityIn instanceof EntityPlayer)) {
                return;
            }
            EntityPlayer ep = (EntityPlayer) entityIn;
            // Lunar can need a standing replay fallback here; Forge's normal bridge handles standing overlays.
            if (GomodFixes.isWeaveRuntime()) {
                ReplayPlayerHealthTagFix.renderWeaveReplayExtrasAfterVanillaName(
                        (RendererLivingEntity) (Object) this, ep, x, y, z);
            }
            // The remaining fallbacks are exclusively for sneaking players. Avoid invoking their config, replay,
            // and player-map checks for every ordinary standing player in Forge's renderName hot path.
            if (!ep.isSneaking()) {
                return;
            }
            NickRealNametagOverlay.tryRenderSneakingNametagFallback(ep, (Render) (Object) this, x, y, z);
            MobTargetDisplay.tryRenderSneakingAggroMarker(ep, (Render) (Object) this, x, y, z);
            SpectatorKillerNametagOverlay.tryRenderSneakingKillerNametag(ep, (Render) (Object) this, x, y, z);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderHealthUiWithName(EntityLivingBase entity, double x, double y, double z, CallbackInfo ci) {
        if (!GOMOD_HEALTH_UI_USE_RENDER_NAME || !GomodFixes.isWeaveRuntime()) {
            return;
        }
        try {
            // The forced replay stack owns this row now; avoid a second health-only label if renderName fires.
            if (VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                return;
            }
            if (gomod$healthUiRecursionDepth > 0) {
                return;
            }
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            if (!VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                return;
            }

            int currentTick = entity.ticksExisted;
            if (currentTick != gomod$healthUiTick) {
                gomod$healthUiTick = currentTick;
                gomod$healthUiRenderedThisTick = 0;
            }
            if (gomod$healthUiRenderedThisTick >= GOMOD_HEALTH_UI_MAX_PER_TICK) {
                return;
            }

            String rowText = VisibleSpecs.buildReplayHealthNametag(entity);
            if (rowText == null || rowText.isEmpty()) {
                return;
            }
            double labelY = y + 0.28D;
            gomod$healthUiRenderedThisTick++;
            gomod$healthUiRecursionDepth++;
            try {
                ((RenderAccessor) this).gomod$invokeRenderLivingLabel(entity, rowText, x, labelY, z, 64);
            } finally {
                gomod$healthUiRecursionDepth--;
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }
    }

    @Inject(method = {"doRender", "func_76986_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderHorseFoodNametagFallback(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer) return;
        try {
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            HorseDonkeyTracker.renderFoodNametagFallbackLabel(entity, x, y, z);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draw plain mob nametags here so they stay independent of {@code renderName} / health-tag hooks.
     * This covers HUD Panel and health-tag modes where the current mob's health tag is disabled.
     */
    @Inject(method = {"doRender", "func_76986_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderMobNametagHudPanelOnly(EntityLivingBase entity, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer) return;
        try {
            if (!MobHealthDisplay.shouldRenderPlainMobNameInEntityTail(entity)) {
                return;
            }
            if (!MobNametags.shouldRenderMobNametag(entity)) {
                return;
            }
            if (DeferredNametagRenderer.shouldDeferThroughChests(entity, x, y, z)) {
                return;
            }
            if (VisibleSpecs.shouldSuppressNametag(entity)) {
                return;
            }
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
            if (mobProxy != null && entity == mobProxy) {
                return;
            }
            // Preserve vanilla's dim through-wall nameplate pass. The chest workaround only controls the
            // separate late replay after tile entities; it must not suppress ordinary mob label visibility.
            MobNametags.renderMobNametagAt(entity, x, y, z, 0.0D, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hide the target player's model while roleplay mode is active in first-person view
     * so the camera inside their head doesn't see a face full of polygons.
     * In second/third-person view the model is shown normally.
     *
     * Also freezes mc.thePlayer's visual head rotation so the mouse doesn't visually
     * spin our model when viewed from the target's perspective.
     */
    @Inject(method = "doRender", at = @At("HEAD"), cancellable = true)
    private void gomod$hideRoleplayTarget(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        try {
            if (!(entity instanceof EntityPlayer)) return;
            if (!RoleplayMode.isEnabled()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;

            // Hide the roleplay target in first-person
            if (mc.gameSettings.thirdPersonView == 0) {
                EntityPlayer target = RoleplayMode.getTargetPlayer();
                if (target != null && entity.getEntityId() == target.getEntityId()) {
                    if (gomod$schizo_didOverride) {
                        gomod$schizo_didOverride = false;
                        entity.rotationYaw         = gomod$schizo_savedYaw;
                        entity.prevRotationYaw     = gomod$schizo_savedPrevYaw;
                        entity.rotationYawHead     = gomod$schizo_savedYawHead;
                        entity.prevRotationYawHead = gomod$schizo_savedPrevYawHead;
                        entity.rotationPitch       = gomod$schizo_savedPitch;
                        entity.prevRotationPitch   = gomod$schizo_savedPrevPitch;
                        entity.renderYawOffset     = gomod$schizo_savedRenderYawOffset;
                        entity.prevRenderYawOffset = gomod$schizo_savedPrevRenderYawOffset;
                    }
                    ci.cancel();
                    return;
                }
            }

            // Freeze mc.thePlayer's visual rotation so mouse input doesn't visually
            // rotate our character when seen from the target's viewpoint.
            if (entity == mc.thePlayer) {
                float frozenBodyYaw = RoleplayMode.updateAndGetLocalFrozenBodyYaw(mc.thePlayer);
                float frozenPrevBodyYaw = RoleplayMode.getLocalFrozenPrevBodyYaw();

                gomod$rp_savedYaw         = entity.rotationYaw;
                gomod$rp_savedPrevYaw     = entity.prevRotationYaw;
                gomod$rp_savedYawHead     = entity.rotationYawHead;
                gomod$rp_savedPrevYawHead = entity.prevRotationYawHead;
                gomod$rp_savedPitch       = entity.rotationPitch;
                gomod$rp_savedPrevPitch   = entity.prevRotationPitch;
                gomod$rp_savedRenderYawOffset = entity.renderYawOffset;
                gomod$rp_savedPrevRenderYawOffset = entity.prevRenderYawOffset;
                gomod$rp_didFreezeRotation = true;

                // Freeze the local player's full rendered yaw state so only real movement
                // changes the model orientation; mouse look should not visually spin it.
                entity.renderYawOffset     = frozenBodyYaw;
                entity.prevRenderYawOffset = frozenPrevBodyYaw;
                entity.rotationYawHead     = frozenBodyYaw;
                entity.prevRotationYawHead = frozenPrevBodyYaw;
                entity.rotationYaw         = frozenBodyYaw;
                entity.prevRotationYaw     = frozenPrevBodyYaw;
                entity.rotationPitch       = 0.0f;
                entity.prevRotationPitch   = 0.0f;
            }

        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }
    }

    /**
     * Schizo mode: force other players to face the local player while rendering.
     * We save/restore their real rotation so gameplay is unaffected.
     */
    @Inject(method = "doRender", at = @At("HEAD"))
    private void gomod$forceFaceLocalPlayer(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        try {
            if (!SchizoMode.isEnabled()) return;
            if (!(entity instanceof EntityPlayer)) return;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || entity == mc.thePlayer) return;

            // If roleplay mode would hide this target in first-person, skip override
            if (RoleplayMode.isEnabled() && mc.gameSettings.thirdPersonView == 0) {
                EntityPlayer target = RoleplayMode.getTargetPlayer();
                if (target != null && entity.getEntityId() == target.getEntityId()) {
                    return;
                }
            }
            double dx = mc.thePlayer.posX - entity.posX;
            double dz = mc.thePlayer.posZ - entity.posZ;
            double dy = (mc.thePlayer.posY + mc.thePlayer.getEyeHeight()) - (entity.posY + entity.getEyeHeight());

            float yaw = (float) (Math.atan2(dz, dx) * 180.0d / Math.PI) - 90.0f;
            float pitch = (float) (-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0d / Math.PI);

            gomod$schizo_savedYaw         = entity.rotationYaw;
            gomod$schizo_savedPrevYaw     = entity.prevRotationYaw;
            gomod$schizo_savedYawHead     = entity.rotationYawHead;
            gomod$schizo_savedPrevYawHead = entity.prevRotationYawHead;
            gomod$schizo_savedPitch       = entity.rotationPitch;
            gomod$schizo_savedPrevPitch   = entity.prevRotationPitch;
            gomod$schizo_savedRenderYawOffset = entity.renderYawOffset;
            gomod$schizo_savedPrevRenderYawOffset = entity.prevRenderYawOffset;
            gomod$schizo_didOverride = true;

            entity.renderYawOffset     = yaw;
            entity.prevRenderYawOffset = yaw;
            entity.rotationYawHead     = yaw;
            entity.prevRotationYawHead = yaw;
            entity.rotationYaw         = yaw;
            entity.prevRotationYaw     = yaw;
            entity.rotationPitch       = pitch;
            entity.prevRotationPitch   = pitch;
        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }
    }

    @Inject(method = "doRender", at = @At("HEAD"))
    private void gomod$forceVisibleHead(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (entity instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = entity instanceof EntityPlayer
                ? RenderProfiler.begin("GomodPlayerHooks.visibleSpecsGate") : 0L;
        try {
            if (!(entity instanceof EntityPlayer)) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;
            EntityPlayer player = (EntityPlayer) entity;

            boolean shouldShow = VisibleSpecs.shouldShowPlayerRemoteForRender(player);

            if (!shouldShow) return;

            if (entity.isInvisible()) {
                gomod$forcedVisibleEntities.add(entity.getEntityId());
                entity.setInvisible(false);
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }
        finally { RenderProfiler.end("GomodPlayerHooks.visibleSpecsGate", profile); }
    }

    @Inject(method = "doRender", at = @At("RETURN"))
    private void gomod$restoreColorAfterOutline(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        // Restore color state after rendering (in case we modified it during outline mode)
        if (entity instanceof EntityPlayer && (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer())) {
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // Restore mc.thePlayer's real rotation after the roleplay visual freeze
        if (gomod$rp_didFreezeRotation) {
            gomod$rp_didFreezeRotation = false;
            entity.rotationYaw         = gomod$rp_savedYaw;
            entity.prevRotationYaw     = gomod$rp_savedPrevYaw;
            entity.rotationYawHead     = gomod$rp_savedYawHead;
            entity.prevRotationYawHead = gomod$rp_savedPrevYawHead;
            entity.rotationPitch       = gomod$rp_savedPitch;
            entity.prevRotationPitch   = gomod$rp_savedPrevPitch;
            entity.renderYawOffset     = gomod$rp_savedRenderYawOffset;
            entity.prevRenderYawOffset = gomod$rp_savedPrevRenderYawOffset;
        }

        // Restore other players' real rotation after schizo override
        if (gomod$schizo_didOverride) {
            gomod$schizo_didOverride = false;
            entity.rotationYaw         = gomod$schizo_savedYaw;
            entity.prevRotationYaw     = gomod$schizo_savedPrevYaw;
            entity.rotationYawHead     = gomod$schizo_savedYawHead;
            entity.prevRotationYawHead = gomod$schizo_savedPrevYawHead;
            entity.rotationPitch       = gomod$schizo_savedPitch;
            entity.prevRotationPitch   = gomod$schizo_savedPrevPitch;
            entity.renderYawOffset     = gomod$schizo_savedRenderYawOffset;
            entity.prevRenderYawOffset = gomod$schizo_savedPrevRenderYawOffset;
        }

        // Restore invisibility flag if we forced it off at HEAD.
        try {
            if (gomod$forcedVisibleEntities.remove(entity.getEntityId())) {
                entity.setInvisible(true);
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }

    }
    
    @Inject(method = "doRender", at = @At("TAIL"))
    private void gomod$renderHealthUi(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (GOMOD_HEALTH_UI_USE_RENDER_NAME) {
            return;
        }
        try {
            if (VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                return;
            }
            if (gomod$healthUiRecursionDepth > 0) {
                return;
            }
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            // Only render for players and check distance like nametags do
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) {
                return;
            }

            if (!VisibleSpecs.shouldShowReplayHealthNametag(entity)) {
                return;
            }

            int currentTick = entity.ticksExisted;
            if (currentTick != gomod$healthUiTick) {
                gomod$healthUiTick = currentTick;
                gomod$healthUiRenderedThisTick = 0;
            }
            if (gomod$healthUiRenderedThisTick >= GOMOD_HEALTH_UI_MAX_PER_TICK) {
                return;
            }
            
            // Calculate distance to player (same logic as nametag distance check)
            double distance = mc.thePlayer.getDistanceToEntity(entity);
            int maxDistance = 64; // Default nametag render distance
            
            // Check if entity is within nametag render distance
            if (distance > maxDistance) {
                return;
            }

            try {
                String rowText = VisibleSpecs.buildReplayHealthNametag(entity);
                if (rowText != null && !rowText.isEmpty()) {
                    double labelY = y + 0.28D;
                    gomod$healthUiRenderedThisTick++;
                    gomod$healthUiRecursionDepth++;
                    try {
                        ((RenderAccessor) this).gomod$invokeRenderLivingLabel(entity, rowText, x, labelY, z, maxDistance);
                    } finally {
                        gomod$healthUiRecursionDepth--;
                    }
                }
            } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }
            
            // Player Queuer ESP: chameleon outline in RenderGlobalMixin (include light blue, exclude red)
            // so Lunar / renderEntitySimple-only paths still show it.

            // Do not render a custom wireframe for GlowPlayer; the real outline is handled by RenderGlobalMixin
	        } catch (Throwable t) { System.err.println("[gomod] Error in RendererLivingEntityMixin: " + t.getMessage()); }

	        // Removed the supplemental bounding-box outline so only the vanilla outline shader is used
    }

    @Inject(method = {"renderName", "func_177067_a"}, at = @At("TAIL"), require = 0)
    private void gomod$renderHorseFoodNametag(EntityLivingBase entityIn, double x, double y, double z, CallbackInfo ci) {
        if (entityIn instanceof EntityPlayer) return;
        try {
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            HorseDonkeyTracker.renderFoodNametag(entityIn, x, y, z);
        } catch (Throwable ignored) {
        }
    }
}
