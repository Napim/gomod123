package me.napim.gomod.mixins;

import me.napim.gomod.features.IdleGuiPlayerRenderFix;
import me.napim.gomod.features.GlowPlayer;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.LightningRenderFix;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.EntityNametagLimiter;
import me.napim.gomod.features.NickRealNametagOverlay;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.features.DeathmatchGlow;
import me.napim.gomod.features.GomodBlockOverlay;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.WaterClarity;
import me.napim.gomod.Main;
import me.napim.gomod.features.ChestCheckpointLabels;
import me.napim.gomod.features.PlayerQueuer;
import me.napim.gomod.util.GomodDeferredQueuerOutline;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.render.GomodOutlineRenderBridge;
import me.napim.gomod.util.GlowTrailRenderer;
import me.napim.gomod.util.EntityOutlineDepthUtil;
import me.napim.gomod.util.GomodChestWorldOverlayFrame;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumWorldBlockLayer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Adds an entity-outline pass (vanilla framebuffer + shader) for players in the GlowPlayer list.
 * Mirrors the approach used by LobbyGlow while limiting scope to our explicit glow list.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin implements GomodOutlineRenderBridge {

    private static final float gomod$vanillaAlphaCutoff = 0.1f;
    private static final float gomod$waterClarityAlphaCutoff = 0.001f;

    /**
     * Outline FBO sole clip: added to (entity Y − render manager {@code renderPosY}). Vertices below the
     * resulting plane are clipped so glow does not smear onto the floor. A small positive value was too
     * high and cut off the bottom of boot models; a modest negative value keeps soles visible.
     */
    private static final double GOMOD_OUTLINE_SOLE_CLIP_Y_OFFSET = -0.11;
    /** Extra sole-clip for chameleon + forced solid tint (/glowplayer, teammate Green): less floor tile glow under feet. */
    private static final double GOMOD_OUTLINE_SOLE_CLIP_Y_EXTRA_FORCED_CHAMELEON_TINT = 0.06;

    @Shadow private WorldClient theWorld;
    @Final @Shadow private RenderManager renderManager;
    @Final @Shadow private Minecraft mc;
    @Shadow private Framebuffer entityOutlineFramebuffer;
    @Shadow private ShaderGroup entityOutlineShader;

    // Note: Outline rendering mode state is now tracked in OutlineRenderingHelper utility class

    /**
     * Vanilla's {@code renderEntities} outline branch draws every entity that passes its outline rules
     * when {@code isRenderEntityOutlines()} is true. Faking spectator/key to true made that branch run for
     * gomod → global outlines + nametags. LobbyGlow skips this branch entirely and runs a custom pass; we
     * do the same and rely on {@link #gomodDrawPlayerGlowOutlines}.
     */
    @Redirect(
        method = "renderEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;isRenderEntityOutlines()Z", ordinal = 0),
        require = 0
    )
    private boolean gomod$skipVanillaOutlineBranchInRenderEntities(RenderGlobal renderGlobal) {
        return false;
    }

    /**
     * After real spectator / outline-key checks, report true when gomod still needs the outline shader/FBO
     * initialized or other code paths that consult this method. We do not replace {@code isKeyDown} /
     * {@code isSpectator} (that made vanilla think the player held the outline key globally).
     */
    @Inject(method = "isRenderEntityOutlines", at = @At("RETURN"), cancellable = true, require = 0)
    private void gomod$extendOutlineGate(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        if (gomod$shouldExtendEntityOutlineGate()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
        method = "renderEntities",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
            shift = At.Shift.BEFORE,
            args = {"ldc=entities"},
            ordinal = 2
        ),
        locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void gomod$renderGlowPlayers(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci, int pass, double d0, double d1, double d2, Entity entity, double d3, double d4, double d5, List<Entity> list) {
        gomodDrawPlayerGlowOutlines(list, d0, d1, d2, camera, partialTicks);
    }

    // Overload to handle OptiFine/vanilla signature variance
    @Inject(
        method = "renderEntities",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V",
            shift = At.Shift.BEFORE,
            args = {"ldc=entities"},
            ordinal = 2
        ),
        locals = LocalCapture.CAPTURE_FAILSOFT
    )
    private void gomod$renderGlowPlayersNoOF(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci, int pass, double d0, double d1, double d2, Entity entity, double d3, double d4, double d5, List<Entity> list, boolean bool0, boolean bool1, int i1) {
        gomodDrawPlayerGlowOutlines(list, d0, d1, d2, camera, partialTicks);
    }

    private static long gomod$lastDebugTs = 0L;
    private static boolean gomod$hadRendered = false;
    private static boolean gomod$trailRenderedThisFrame = false;
    /** Per-frame dedup: prevents {@link #gomodDrawPlayerGlowOutlines} from running more than once per frame. */
    private static boolean gomod$outlineRenderedThisFrame = false;
    private static long gomod$lastRenderEntitiesHeadMs = 0L;

    private static boolean gomod$shouldExtendEntityOutlineGate() {
        if (RenderProfiler.shouldBypassPlayerHooks()) {
            return false;
        }
        if (DeathmatchGlow.isEnabled()) {
            return true;
        }
        if (!GlowPlayer.getPlayers().isEmpty() || !GlowPlayer.getPlayers2().isEmpty()) {
            return true;
        }
        if (PlayerQueuer.isEnabled()
                && (!PlayerQueuer.getTargets().isEmpty() || !PlayerQueuer.getExcludeTargets().isEmpty())) {
            return true;
        }
        // Teammate trails are drawn by GlowTrailRenderer in the world pass and do not need Lunar's
        // entity-outline framebuffer. Extending this gate for the trail-only setting unnecessarily
        // invokes Lunar's outline render hook even in solo games with no teammate to render.
        if (PlayerTarget.isGlowTeammateEnabled()) {
            return true;
        }
        if (gomod$hadRendered) {
            return true;
        }
        return false;
    }

    /**
     * Transparent mount (strategy 8): draw the ridden horse/donkey after other entities so alpha blends
     * with mobs/blocks already in the framebuffer ({@link MountHeadDim#flushDeferredMountRender}).
     */
    @Redirect(
        method = "renderEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/RenderManager;renderEntitySimple(Lnet/minecraft/entity/Entity;F)Z"),
        require = 0
    )
    private boolean gomod$deferTransparentMountFirstPass(RenderManager rm, Entity entity, float partialTicks) {
        long __rpDefer = RenderProfiler.begin("RenderGlobal.renderEntitySimple.deferGate");
        boolean __rpDeepPlayer = entity instanceof EntityPlayer;
        boolean __rpDeepBypassed = __rpDeepPlayer && RenderProfiler.shouldBypassPlayerHooks();
        String __rpDeepSection = __rpDeepPlayer
                ? "DeepPlayer.RenderGlobal.deferProbe." + (__rpDeepBypassed ? "bypassed" : "active") : null;
        long __rpDeepProbe = __rpDeepPlayer ? RenderProfiler.beginDeep(__rpDeepSection) : 0L;
        try {
            try {
                boolean bypassPlayer = entity instanceof EntityPlayer
                        && RenderProfiler.shouldBypassPlayerHooks();
                boolean localPlayer = entity instanceof EntityPlayer
                        && this.mc != null && entity == this.mc.thePlayer;
                // A remote player can never be the local player's ridden mount and self-opacity only targets
                // the local player. Avoid both feature probes for every remote player on Forge/Patcher.
                boolean checkPlayerOnlyDeferrals = !bypassPlayer
                        && (!(entity instanceof EntityPlayer) || localPlayer);
                if (checkPlayerOnlyDeferrals && MountHeadDim.shouldSkipFirstPassMountRender(entity)) {
                    MountHeadDim.scheduleDeferredMount(entity, partialTicks);
                    return false;
                }
                if (checkPlayerOnlyDeferrals && SelfOpacity.shouldSkipAndDefer(entity)) {
                    SelfOpacity.scheduleDeferred(entity, partialTicks);
                    return false;
                }
                if (LightningRenderFix.shouldSkipAndDefer(entity, partialTicks)) {
                    return false;
                }
            } catch (Throwable t) {
                System.err.println("[gomod] deferTransparentMount redirect: " + t.getMessage());
            } finally {
                RenderProfiler.endDeep(__rpDeepSection, __rpDeepProbe);
            }
            long __rpVanilla = RenderProfiler.begin("RenderGlobal.renderEntitySimple.vanilla");
            boolean __rpPlayerBypassed = entity instanceof EntityPlayer
                    && RenderProfiler.shouldBypassPlayerHooks();
            String __rpEntityType = entity instanceof EntityPlayer
                    ? (__rpPlayerBypassed
                    ? "RenderGlobal.renderEntitySimple.player.hooksBypassed"
                    : "RenderGlobal.renderEntitySimple.player.hooksActive")
                    : "RenderGlobal.renderEntitySimple.nonPlayer";
            boolean __rpGpuSync = entity instanceof EntityPlayer && RenderProfiler.shouldGpuSynchronize();
            if (__rpGpuSync) {
                GL11.glFinish();
            }
            String __rpGpuSection = __rpPlayerBypassed
                    ? "RenderGlobal.renderEntitySimple.playerGpuSynchronized.hooksBypassed"
                    : "RenderGlobal.renderEntitySimple.playerGpuSynchronized.hooksActive";
            long __rpGpuStart = __rpGpuSync ? RenderProfiler.begin(__rpGpuSection) : 0L;
            long __rpEntityTypeStart = RenderProfiler.begin(__rpEntityType);
            try {
                boolean rendered = rm.renderEntitySimple(entity, partialTicks);
                if (rendered && entity != null && IdleGuiPlayerRenderFix.shouldApply(Minecraft.getMinecraft())) {
                    IdleGuiPlayerRenderFix.markRendered(entity.getEntityId());
                }
                return rendered;
            } finally {
                if (__rpGpuSync) {
                    GL11.glFinish();
                    RenderProfiler.end(__rpGpuSection, __rpGpuStart);
                }
                RenderProfiler.end(__rpEntityType, __rpEntityTypeStart);
                RenderProfiler.end("RenderGlobal.renderEntitySimple.vanilla", __rpVanilla);
            }
        } finally {
            RenderProfiler.end("RenderGlobal.renderEntitySimple.deferGate", __rpDefer);
        }
    }

    /** Pass 2: flush after stained glass / water ({@code mountFlushMode == 2}). */
    @Inject(
        method = "renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I",
        at = @At("HEAD"),
        require = 0
    )
    private void gomod$lowerAlphaCutoffForTransparentWater(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass,
                                                            Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            SelfOpacity.noteTranslucentBlockLayerEntered();
            VisibleSpecs.noteTranslucentBlockLayerEntered();
            if (WaterClarity.isEnabled()) {
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, gomod$waterClarityAlphaCutoff);
                try {
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(GL11.GL_GREATER, gomod$waterClarityAlphaCutoff);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Pass 2: flush after stained glass / water ({@code mountFlushMode == 2}). */
    @Inject(
        method = "renderBlockLayer(Lnet/minecraft/util/EnumWorldBlockLayer;DILnet/minecraft/entity/Entity;)I",
        at = @At("TAIL"),
        require = 0
    )
    private void gomod$flushDeferredMountAfterTranslucent(EnumWorldBlockLayer blockLayerIn, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        if (blockLayerIn == EnumWorldBlockLayer.TRANSLUCENT) {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, gomod$vanillaAlphaCutoff);
            try {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, gomod$vanillaAlphaCutoff);
            } catch (Throwable ignored) {
            }
            // VisibleSpecs + self opacity (first person): flush after water/translucents at renderBlockLayer TAIL when
            // SelfOpacity.shouldFlushSelfOpacityInRenderGlobalTranslucentTail(). Third person post-translucent flush is
            // in EntityRendererSelfOpacityTranslucentMixin (after renderBlockLayer returns). EntityRendererMountFlushMixin
            // fallback if translucent is skipped.
            try {
                if (MountHeadDim.shouldFlushAfterTranslucentLayer()) {
                    MountHeadDim.flushDeferredMountRender(this.renderManager);
                }
                if (VisibleSpecs.hasDeferredRender()) {
                    VisibleSpecs.flushDeferred(this.renderManager);
                }
                // The local translucent body must be composited after distant translucent spectators.
                if (SelfOpacity.hasDeferredRender() && SelfOpacity.shouldFlushSelfOpacityInRenderGlobalTranslucentTail()) {
                    SelfOpacity.flushDeferred(this.renderManager, SelfOpacity.DeferredFlushSite.TRANSLUCENT_LAYER_TAIL,
                            pass);
                }
            } catch (Throwable t) {
                System.err.println("[gomod] flushDeferredAfterTranslucent: " + t.getMessage());
            }
            return;
        }
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void gomod$debugHead(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        gomod$lastRenderEntitiesHeadMs = System.currentTimeMillis();
        try {
            MountHeadDim.beginWorldRenderEntitiesPass();
            SelfOpacity.beginEntitiesPass();
            if (!RenderProfiler.shouldBypassPlayerHooks()) {
                VisibleSpecs.beginEntitiesPass();
            }
            GomodBlockOverlay.beginWorldEntitiesPass();
        } catch (Throwable ignored) {
        }
        gomod$trailRenderedThisFrame = false;
        gomod$outlineRenderedThisFrame = false;
        try {
            DeferredNametagRenderer.clear();
        } catch (Throwable ignored) {
        }
        if (!GlowPlayer.isDebug()) return;
        long now = System.currentTimeMillis();
        if (now - gomod$lastDebugTs < 1000) return;
        gomod$lastDebugTs = now;
        int worldCount = 0;
        int matchCount = 0;
        try {
            if (this.theWorld != null) {
                java.util.List<Entity> all = this.theWorld.getLoadedEntityList();
                worldCount = all != null ? all.size() : 0;
                if (all != null) {
                    for (Entity e : all) {
                        if (e instanceof EntityPlayer) {
                            EntityPlayer ep = (EntityPlayer) e;
                            if (GlowPlayer.isGlowingPlayer(ep) || GlowPlayer.isGlowingPlayer2(ep)) matchCount++;
                        }
                    }
                }
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in RenderGlobalMixin: " + t.getMessage()); }
        boolean fboOk = this.entityOutlineFramebuffer != null;
        boolean shaderOk = this.entityOutlineShader != null;
        Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.AQUA + "Glow debug(head): worldEntities=" + worldCount + ", glowMatches=" + matchCount + ", FBO=" + fboOk + ", Shader=" + shaderOk);
        System.out.println("[gomod][GlowDebug] head: worldEntities=" + worldCount + ", glowMatches=" + matchCount + ", FBO=" + fboOk + ", Shader=" + shaderOk);
    }

    @Override
    public void gomod$renderGomodOutlineFromWorldEvent(float partialTicks) {
        try {
            if (this.theWorld == null || !gomod$shouldExtendEntityOutlineGate()) {
                return;
            }
            long lastEntitiesHeadAgeMs = System.currentTimeMillis() - gomod$lastRenderEntitiesHeadMs;
            if (gomod$lastRenderEntitiesHeadMs == 0L || lastEntitiesHeadAgeMs > 1000L) {
                gomod$outlineRenderedThisFrame = false;
            }
            if (gomod$outlineRenderedThisFrame) {
                return;
            }
            gomodDrawPlayerGlowOutlines(this.theWorld.getLoadedEntityList(), 0.0, 0.0, 0.0, null, partialTicks);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in RenderGlobalMixin world-event outline fallback: " + t.getMessage());
        }
    }

    /**
     * Merges {@link net.minecraft.world.World#playerEntities} into the render-entities list when any
     * of them need the outline FBO but are missing from vanilla's frustum-culled batch for this frame.
     */
    private List<Entity> gomod$extendOutlineEntityList(List<Entity> fromRender) {
        if (this.theWorld == null || fromRender == null) {
            return fromRender;
        }
        boolean mightNeedOffscreen = PlayerTarget.isGlowTeammateEnabled()
                || PlayerTarget.isTeammateTrailEnabled()
                || !GlowPlayer.getPlayers().isEmpty()
                || !GlowPlayer.getPlayers2().isEmpty()
                || (PlayerQueuer.isEnabled()
                        && (!PlayerQueuer.getTargets().isEmpty() || !PlayerQueuer.getExcludeTargets().isEmpty()));
        if (!mightNeedOffscreen) {
            return fromRender;
        }
        HashSet<Integer> seen = new HashSet<Integer>();
        ArrayList<Entity> out = new ArrayList<Entity>();
        for (Entity e : fromRender) {
            if (e == null) {
                continue;
            }
            out.add(e);
            seen.add(e.getEntityId());
        }
        for (EntityPlayer ep : this.theWorld.playerEntities) {
            if (ep == null || seen.contains(ep.getEntityId())) {
                continue;
            }
            String n = ep.getName();
            if (n == null) {
                continue;
            }
            if (GlowPlayer.isGlowingPlayer(ep) || GlowPlayer.isGlowingPlayer2(ep)) {
                out.add(ep);
                seen.add(ep.getEntityId());
                continue;
            }
            if (PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(ep)) {
                out.add(ep);
                seen.add(ep.getEntityId());
                continue;
            }
            if (PlayerTarget.isTeammateTrailEnabled() && PlayerTarget.isEntityCurrentTeammate(ep)) {
                out.add(ep);
                seen.add(ep.getEntityId());
                continue;
            }
            if (PlayerQueuer.isEnabled()
                    && (PlayerQueuer.isQueuedTargetPlayer(ep) || PlayerQueuer.isQueuedExcludePlayer(ep))) {
                out.add(ep);
                seen.add(ep.getEntityId());
            }
        }
        return out;
    }

    /**
     * Not named {@code displayGlowOutlines}: OptiFine adds a method with that name; a name collision can make
     * our glow pass accidentally call OptiFine's implementation (Java 9+ buffer covariant returns → crash on Java 8).
     */
    private void gomodDrawPlayerGlowOutlines(List<Entity> entities, double x, double y, double z, ICamera camera, float partialTicks) {
        long __gomodRpGlowAll = RenderProfiler.begin("RenderGlobal.gomodDrawPlayerGlowOutlines");
        try {
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            return;
        }
        // Ensure outline pipeline exists (needed on vanilla / non-OF)
        if ((this.entityOutlineFramebuffer == null || this.entityOutlineShader == null) && (Object) this instanceof RenderGlobalInvoker) {
            try {
                ((RenderGlobalInvoker) (Object) this).gomod$makeEntityOutlineShader();
            } catch (Throwable t) {
                // ignore; will bail out below if still unavailable
            }
        }
        // Only run if resources exist (outline pipeline available)
        if (this.entityOutlineFramebuffer == null || this.entityOutlineShader == null || this.mc == null || this.mc.thePlayer == null) return;
        // Dedup: the method can be called from multiple injection points per frame.
        // Only the first successful invocation should actually render.
        if (gomod$outlineRenderedThisFrame) return;

        // Vanilla passes a frustum-culled list; /glowplayer-style outlines must still run for loaded
        // targets (teammate, manual glow, queuer) even when they are outside this frame's render list.
        List<Entity> outlineEntities = gomod$extendOutlineEntityList(entities);

        boolean any = false;
        int matchCount = 0;
        for (Entity e : outlineEntities) {
            if (e instanceof EntityPlayer) {
                EntityPlayer ep = (EntityPlayer) e;
                String name = ep.getName();
                if (name != null && (GlowPlayer.isGlowingPlayer(ep)
                        || GlowPlayer.isGlowingPlayer2(ep)
                        || (PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(ep))
                        || (PlayerTarget.isTeammateTrailEnabled() && PlayerTarget.isEntityCurrentTeammate(ep))
                        || PlayerQueuer.isQueuedTargetPlayer(ep)
                        || PlayerQueuer.isQueuedExcludePlayer(ep))) {
                    any = true;
                    matchCount++;
                }
            }
        }
        // Also consider DeathmatchGlow — we need the FBO pass even without glowing entities
        if (!any && DeathmatchGlow.isEnabled()) any = true;
        if (!any) {
            if (GlowPlayer.isDebug()) {
                long now = System.currentTimeMillis();
                if (now - gomod$lastDebugTs > 1000) {
                    Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.YELLOW + "Glow debug: no matching players in render list this frame.");
                    gomod$lastDebugTs = now;
                }
            }
            // If we rendered in a previous frame, clear the outline framebuffer and run an empty shader pass
            if (gomod$hadRendered) {
                try {
                    GlStateManager.disableFog();
                    this.entityOutlineFramebuffer.framebufferClear();
                    this.entityOutlineFramebuffer.bindFramebuffer(false);
                    this.theWorld.theProfiler.endStartSection("entityOutlinesClear");
                    RenderHelper.disableStandardItemLighting();
                    this.renderManager.setRenderOutlines(false);
                    GlStateManager.depthMask(false);
                    // Run shader once to composite an empty outline
                    this.entityOutlineShader.loadShaderGroup(partialTicks);
                    GlStateManager.enableLighting();
                    GlStateManager.depthMask(true);
                    this.mc.getFramebuffer().bindFramebuffer(false);
                    GlStateManager.enableFog();
                    GlStateManager.enableBlend();
                    GlStateManager.enableColorMaterial();
                    GlStateManager.depthFunc(515);
                    GlStateManager.enableDepth();
                    GlStateManager.enableAlpha();
                } catch (Throwable t) { System.err.println("[gomod] Error in RenderGlobalMixin: " + t.getMessage()); }
                gomod$hadRendered = false;
            }
            gomod$outlineRenderedThisFrame = true;
            return;
        }

        if (GlowPlayer.isDebug()) {
            long now = System.currentTimeMillis();
            if (now - gomod$lastDebugTs > 1000) {
                boolean fboOk = this.entityOutlineFramebuffer != null;
                boolean shaderOk = this.entityOutlineShader != null;
                Main.sendMessage(Main.CHAT_PREFIX + net.minecraft.util.EnumChatFormatting.GREEN + "Glow debug: matches=" + matchCount + ", FBO=" + fboOk + ", Shader=" + shaderOk);
                gomod$lastDebugTs = now;
            }
        }

        try {
        GlStateManager.disableFog();
        this.entityOutlineFramebuffer.framebufferClear();
        this.entityOutlineFramebuffer.bindFramebuffer(false);
        OutlineRenderingHelper.setModEntityOutlinePassActive(true);
        final boolean gomod$wantPlayerQueuerDepth = gomod$anyStandalonePlayerQueuerInList(outlineEntities);
        final boolean gomod$queuerDepthAgainstScene = gomod$wantPlayerQueuerDepth
                && SelfOpacity.shouldPlayerQueuerOutlineDepthTestAgainstScene();
        final boolean gomod$blitSceneDepthForPlayerQueuer = gomod$queuerDepthAgainstScene
                && EntityOutlineDepthUtil.tryCopyMainDepthToOutline(this.mc, this.entityOutlineFramebuffer);
        // ── THROUGH-WALL GLOW (flat / teammate / glowplayer / glowplayer2) ──
        // Depth test off (see-through-walls). Per-entity we set depthMask(true) so the outline FBO
        // still gets depth from body+armor for the entity_outline edge shader (global mask false here
        // would leave only skin-deep depth from any stray writes). Player Queuer depth blit path
        // temporarily sets mask false again while compositing against scene depth.
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        this.theWorld.theProfiler.endStartSection("entityOutlines");
        RenderHelper.disableStandardItemLighting();
        
        // ── Flat teammate / trail-only glow ──
        // Keep renderOutlines=false and no textures: LayerArmorBaseMixin pretends renderOutlines
        // is false during isInOutlineMode so armor still writes depth (full silhouette).
        // /glowplayer, /glowplayer2, teammate chameleon, and Player Queuer use the textured chameleon branch;
        // /glowplayer forces {@link GlowPlayer#getColorRgb()} (default green); queuer forces include/exclude RGB.
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        
        // Set flag to indicate we're in outline rendering mode
        OutlineRenderingHelper.setOutlineMode(true);

        // ── Z-FIGHTING FIX ──
        // The depth blit copied the scene depth into the outline FBO.  Re-rendering
        // the same entities would produce nearly-identical depth, but floating-point
        // precision differences between the two render passes cause z-fighting
        // (flickering + partial black silhouettes, especially in first person).
        // A small polygon offset pushes the outline polygons slightly toward the
        // camera so they always win the GL_LEQUAL test against their own scene
        // depth, while still being properly occluded by closer geometry (walls).
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL);
        org.lwjgl.opengl.GL11.glPolygonOffset(-1.0f, -1.0f);

        // ── GROUND GLOW FIX ──
        // GL clip plane removes geometry below ~feet (see GOMOD_OUTLINE_SOLE_CLIP_Y_OFFSET) so blur
        // does not smear onto the floor. Must stay slightly below boot soles, not through the ankles.
        // Plane (0, 1, 0, -clipY) keeps vertices with Y >= clipY in the plane's coordinate space.
        // Allocate once per outline pass (not per entity). Use Buffer.clear()/flip() on the
        // java.nio.Buffer type so calls resolve on Java 8 — DoubleBuffer's covariant returns
        // (Java 9+) cause NoSuchMethodError under JDK 8 when bytecode expects DoubleBuffer.flip().
        java.nio.DoubleBuffer gomod$clipBuf = java.nio.ByteBuffer.allocateDirect(32)
                .order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();

        List<GomodDeferredQueuerOutline> gomod$deferredPlayerQueuerChameleon = new ArrayList<GomodDeferredQueuerOutline>();

        for (Entity entity : outlineEntities) {
            if (!(entity instanceof EntityPlayer)) continue;
            EntityPlayer ep = (EntityPlayer) entity;
            String name = ep.getName();
            if (name == null) continue;
            boolean isQueuerInclude = PlayerQueuer.isQueuedTargetPlayer(ep);
            boolean isQueuerExclude = PlayerQueuer.isQueuedExcludePlayer(ep);
            boolean isQueuerOutline = isQueuerInclude || isQueuerExclude;
            boolean isManualV1 = GlowPlayer.isGlowingPlayer(ep);
            boolean isManualV2 = GlowPlayer.isGlowingPlayer2(ep);
            boolean isManualGlow = isManualV1 || isManualV2;
            boolean isTeammateGlow = PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(ep);
            // Trail-only: teammate trail is on but glow is off — record positions but skip FBO render
            boolean isTeammateTrailOnly = !isManualGlow && !isTeammateGlow
                    && PlayerTarget.isTeammateTrailEnabled() && PlayerTarget.isEntityCurrentTeammate(ep);
            if (!isManualGlow && !isTeammateGlow && !isTeammateTrailOnly && !isQueuerOutline) continue;

            if (!isManualGlow && !isTeammateGlow) {
                if (isQueuerExclude) {
                    gomod$deferredPlayerQueuerChameleon.add(
                            new GomodDeferredQueuerOutline(ep, PlayerQueuer.QUEUER_EXCLUDE_OUTLINE_RGB));
                    continue;
                }
                if (isQueuerInclude) {
                    gomod$deferredPlayerQueuerChameleon.add(
                            new GomodDeferredQueuerOutline(ep, PlayerQueuer.QUEUER_INCLUDE_OUTLINE_RGB));
                    continue;
                }
            }

            boolean chameleonStyle;
            int entityColorRgb;
            if (isManualV1) {
                // Same chameleon silhouette as /glowplayer2; color comes from forced tint (default green), not team skin.
                chameleonStyle = true;
                entityColorRgb = GlowPlayer.getColorRgb();
            } else if (isManualV2) {
                // Vanilla team-tinted textured outline (no forced RGB).
                chameleonStyle = true;
                entityColorRgb = 0xFFFFFF;
            } else if (isTeammateGlow) {
                // GREEN: same chameleon shell as /glowplayer with teammate color as forced tint. CHAMELEON: /glowplayer2-style team tint only.
                if (PlayerTarget.isTeammateChameleonOutlineMode()) {
                    chameleonStyle = true;
                    entityColorRgb = 0xFFFFFF;
                } else {
                    chameleonStyle = true;
                    entityColorRgb = PlayerTarget.getGlowTeammateColorRgb();
                }
            } else if (isTeammateTrailOnly) {
                chameleonStyle = false;
                entityColorRgb = PlayerTarget.getGlowTeammateColorRgb();
            } else {
                continue;
            }
            if ((isTeammateGlow || isTeammateTrailOnly || isManualV1) && ep.hurtTime > 0) {
                entityColorRgb = 0xFF3B30;
            }
            float cr = ((entityColorRgb >> 16) & 0xFF) / 255.0f;
            float cg = ((entityColorRgb >>  8) & 0xFF) / 255.0f;
            float cb = ( entityColorRgb        & 0xFF) / 255.0f;
            // Teammate Chameleon outline uses white/team tint in the FBO; trail should match Green mode (teammate RGB).
            int trailRecordRgb = (isTeammateGlow && PlayerTarget.isTeammateChameleonOutlineMode() && ep.hurtTime <= 0)
                    ? PlayerTarget.getGlowTeammateColorRgb()
                    : entityColorRgb;
            // Record trail using the sub-tick interpolated position so we capture
            // smooth movement between game ticks at render frame-rate, not just
            // the raw 20Hz tick positions (which produce visible gaps at high speed).
            if (isManualGlow || (isTeammateGlow && PlayerTarget.isTeammateTrailEnabled()) || isTeammateTrailOnly) {
                double trailX = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
                double trailY = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
                double trailZ = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
                String trailKey = isManualV1 ? GlowPlayer.resolveMatchedGlowName(ep)
                        : (isManualV2 ? GlowPlayer.resolveMatchedGlowName2(ep) : null);
                GlowTrailRenderer.recordPosition(trailKey != null ? trailKey : name, trailX, trailY, trailZ, trailRecordRgb);
            }
            // Trail-only entities: positions recorded above, no FBO outline unless also a queuer include/exclude
            if (isTeammateTrailOnly && !isQueuerOutline) continue;
            boolean flag = this.mc.getRenderViewEntity() instanceof EntityLivingBase && ((EntityLivingBase) this.mc.getRenderViewEntity()).isPlayerSleeping();
            if (entity != this.mc.getRenderViewEntity() || this.mc.gameSettings.thirdPersonView != 0 || flag) {
                // Set up clip plane to trim the sole without chopping boot geometry.
                double renderPosY = (this.mc.getRenderManager() instanceof me.napim.gomod.mixins.RenderManagerAccessor)
                        ? ((me.napim.gomod.mixins.RenderManagerAccessor) this.mc.getRenderManager()).gomod$getRenderPosY()
                        : 0.0;
                boolean useForcedChameleonTint = isManualV1
                        || (isTeammateGlow && !PlayerTarget.isTeammateChameleonOutlineMode());
                double soleClip = GOMOD_OUTLINE_SOLE_CLIP_Y_OFFSET
                        + (useForcedChameleonTint ? GOMOD_OUTLINE_SOLE_CLIP_Y_EXTRA_FORCED_CHAMELEON_TINT : 0.0);
                double clipY = (entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks)
                        - renderPosY + soleClip;
                ((java.nio.Buffer) gomod$clipBuf).clear();
                gomod$clipBuf.put(0.0).put(1.0).put(0.0).put(-clipY);
                ((java.nio.Buffer) gomod$clipBuf).flip();
                org.lwjgl.opengl.GL11.glClipPlane(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0, gomod$clipBuf);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0);

                if (chameleonStyle) {
                    // /glowplayer + teammate Green: forced solid tint. /glowplayer2 + teammate Chameleon + queuer tint: vanilla/team tint paths.
                    OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(true);
                    try {
                        if (useForcedChameleonTint) {
                            OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(entityColorRgb);
                            me.napim.gomod.util.ForcedSolidTintTextureEnv.pushRgb(entityColorRgb);
                        }
                        try {
                            OutlineRenderingHelper.setOutlineMode(false);
                            OutlineRenderingHelper.setGlowColorRgb(0xFFFFFF);
                            // Textured silhouette like /glowplayer2; texture combiner forces solid RGB (see ForcedSolidTintTextureEnv).
                            GlStateManager.enableTexture2D();
                            GlStateManager.enableLighting();
                            RenderHelper.enableStandardItemLighting();
                            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                            this.renderManager.setRenderOutlines(true);
                            try {
                                GlStateManager.depthMask(true);
                                this.renderManager.renderEntitySimple(entity, partialTicks);
                            } finally {
                                this.renderManager.setRenderOutlines(false);
                            }
                            RenderHelper.disableStandardItemLighting();
                            GlStateManager.disableTexture2D();
                            GlStateManager.disableLighting();
                            OutlineRenderingHelper.setOutlineMode(true);
                        } finally {
                            if (useForcedChameleonTint) {
                                me.napim.gomod.util.ForcedSolidTintTextureEnv.pop();
                                OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(-1);
                            }
                        }
                    } finally {
                        OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(false);
                    }
                } else {
                    // Trail-only (+ optional queuer) flat silhouette (armor depth via OutlineRenderingHelper).
                    OutlineRenderingHelper.setOutlineMode(true);
                    OutlineRenderingHelper.setGlowColorRgb(entityColorRgb);
                    GlStateManager.color(cr, cg, cb, 1.0f);
                    GlStateManager.disableTexture2D();
                    GlStateManager.disableLighting();
                    GlStateManager.depthMask(true);
                    this.renderManager.renderEntitySimple(entity, partialTicks);
                }

                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0);
            }
        }

        // Player Queuer stand-alone: chameleon outline + forced tint.
        // Depth-tested against scene unless self-opacity (see gomod$queuerDepthAgainstScene).
        if (!gomod$deferredPlayerQueuerChameleon.isEmpty()) {
            gomod$renderPlayerQueuerOutlineBatch(gomod$deferredPlayerQueuerChameleon, partialTicks,
                    gomod$blitSceneDepthForPlayerQueuer, gomod$clipBuf);
        }

        // Clear flag when done with outline rendering
        OutlineRenderingHelper.setOutlineMode(false);

        // ── Deathmatch border glow ────────────────────────────────────────────────
        // Rendered into the same FBO (still bound) so the Gaussian-blur shader
        // spreads a glow halo along the perimeter line — same visual effect as
        // /glowplayer.  Polygon offset is still active so the quads win against
        // the blitted scene depth of the block face at the same Y coordinate.
        if (DeathmatchGlow.isEnabled()) {
            try {
                // DM quads use GL_ALWAYS — polygon offset is not needed and actively
                // harmful: negative offset on horizontal quads in first-person pushes
                // clip-space depth past the near clip plane, clipping the quad and
                // leaving the FBO empty → blur composites over black.
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL);
                // depthMask false: don't corrupt the blitted scene depth in the FBO.
                GlStateManager.depthMask(false);
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                DeathmatchGlow.renderBorderIntoFBO(
                        this.mc.getRenderManager().viewerPosX,
                        this.mc.getRenderManager().viewerPosY,
                        this.mc.getRenderManager().viewerPosZ,
                        partialTicks, this.mc);
                GlStateManager.depthMask(true);
            } catch (Throwable t) { System.err.println("[gomod] Error in RenderGlobalMixin: " + t.getMessage()); }
        }

        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL);

        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        
        RenderHelper.enableStandardItemLighting();
        GlStateManager.depthMask(false);
        this.entityOutlineShader.loadShaderGroup(partialTicks);
        GlStateManager.enableLighting();
        GlStateManager.depthMask(true);
        this.mc.getFramebuffer().bindFramebuffer(false);
        GlStateManager.enableFog();
        GlStateManager.enableBlend();
        GlStateManager.enableColorMaterial();
        GlStateManager.depthFunc(515);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        gomod$hadRendered = true;
        gomod$outlineRenderedThisFrame = true;
        } finally {
            try {
                if (this.mc != null && this.mc.getFramebuffer() != null) {
                    this.mc.getFramebuffer().bindFramebuffer(false);
                }
            } catch (Throwable ignored) {
            }
            OutlineRenderingHelper.resetLeakProneOutlineClientState();
        }
        } finally {
            RenderProfiler.end("RenderGlobal.gomodDrawPlayerGlowOutlines", __gomodRpGlowAll);
        }
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void gomod$beginChestOverlayFrame(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        try {
            IdleGuiPlayerRenderFix.beginFrame();
            RenderProfiler.markRenderEntitiesFrame();
            OutlineRenderingHelper.resetLeakProneOutlineClientState();
            GomodChestWorldOverlayFrame.beginEntityRenderFrame();
            MobNametags.beginWorldMobNametagFrame();
            MobNametags.captureWorldMobNametagFog();
            MobHealthDisplay.beginWorldHealthNametagFrame();
            EntityNametagLimiter.beginFrame(this.mc, renderViewEntity);
            NickRealNametagOverlay.beginWorldNametagFrame();
            LightningRenderFix.clearDeferred();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void gomod$endVisibleSpecsEntitiesPass(Entity renderViewEntity, ICamera camera, float partialTicks,
            CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            VisibleSpecs.endEntitiesPass();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void gomod$idleGuiRenderMissingPlayers(Entity renderViewEntity, ICamera camera, float partialTicks,
            CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            if (!IdleGuiPlayerRenderFix.shouldRenderMissingPlayers(this.mc)
                    || this.theWorld == null || this.renderManager == null
                    || renderViewEntity == null) {
                return;
            }
            double cx = renderViewEntity.posX;
            double cy = renderViewEntity.posY;
            double cz = renderViewEntity.posZ;
            double maxDistSq = (double) (this.mc.gameSettings.renderDistanceChunks * 16) * (this.mc.gameSettings.renderDistanceChunks * 16);
            for (EntityPlayer player : this.theWorld.playerEntities) {
                if (player == null || player.isDead || player == this.mc.thePlayer) {
                    continue;
                }
                if (IdleGuiPlayerRenderFix.wasRenderedThisFrame(player.getEntityId())) {
                    continue;
                }
                if (player.getDistanceSq(cx, cy, cz) > maxDistSq) {
                    continue;
                }
                if (VisibleSpecs.shouldApplyOpacityToEntity(player)) {
                    VisibleSpecs.scheduleDeferred(player, partialTicks);
                    IdleGuiPlayerRenderFix.markRendered(player.getEntityId());
                    continue;
                }
                if (this.renderManager.renderEntitySimple(player, partialTicks)) {
                    IdleGuiPlayerRenderFix.markRendered(player.getEntityId());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Dedicated TAIL injection that always renders the glow trail, even when no
     * glowing players are visible (they've walked away, changed dimension, etc.).
     * The outline pass above sets gomod$trailRenderedThisFrame=true when it already
     * rendered the trail, so this dedup flag prevents a double draw.
     */
    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void gomod$aRenderEntitiesTrailThenOrderedDeferredFlushes(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        long __rpTailAll = RenderProfiler.begin("RenderGlobal.renderEntities.tail.total");
        try {
            try {
                // Ensure we are writing to the main scene framebuffer, not the outline FBO
                if (this.mc != null && this.mc.getFramebuffer() != null) {
                    this.mc.getFramebuffer().bindFramebuffer(false);
                }
                if (!gomod$trailRenderedThisFrame && GlowTrailRenderer.hasTrailPoints()) {
                    long t = RenderProfiler.begin("RenderGlobal.tail.GlowTrailRenderer");
                    try {
                        GlowTrailRenderer.cleanupAndRender(partialTicks);
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.GlowTrailRenderer", t);
                    }
                    gomod$trailRenderedThisFrame = true;
                }
                long tCp = RenderProfiler.begin("RenderGlobal.tail.ChestCheckpointLabels");
                try {
                    ChestCheckpointLabels.render(partialTicks);
                } finally {
                    RenderProfiler.end("RenderGlobal.tail.ChestCheckpointLabels", tCp);
                }
                long tMh = RenderProfiler.begin("RenderGlobal.tail.MobHealthDisplay.worldNametags");
                try {
                    MobHealthDisplay.renderWorldHealthNametags(partialTicks);
                } finally {
                    RenderProfiler.end("RenderGlobal.tail.MobHealthDisplay.worldNametags", tMh);
                }
                // This is the Lunar fallback for transformed RenderPlayer paths that skip the normal nametag bridge.
                // Forge already uses that bridge, so scanning every loaded player here only adds a per-frame
                // player-count-dependent cost with no visible benefit.
                if (!RenderProfiler.shouldBypassPlayerHooks()
                        && me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
                    long tDn = RenderProfiler.begin("RenderGlobal.tail.NickRealNametagOverlay.worldFallback");
                    try {
                        NickRealNametagOverlay.renderMissingWorldNametags(partialTicks);
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.NickRealNametagOverlay.worldFallback", tDn);
                    }
                }
                if (MountHeadDim.shouldFlushAfterEntitiesTail()) {
                    long tM = RenderProfiler.begin("RenderGlobal.tail.MountHeadDim.flush");
                    try {
                        MountHeadDim.flushDeferredMountRender(this.renderManager);
                    } catch (Throwable t) {
                        System.err.println("[gomod] flushDeferredMount: " + t.getMessage());
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.MountHeadDim.flush", tM);
                    }
                }
                if (LightningRenderFix.hasDeferred()) {
                    long tLt = RenderProfiler.begin("RenderGlobal.tail.LightningRenderFix.flush");
                    try {
                        LightningRenderFix.flushDeferred(this.renderManager);
                    } catch (Throwable t) {
                        System.err.println("[gomod] flushDeferredLightning: " + t.getMessage());
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.LightningRenderFix.flush", tLt);
                    }
                }
                if (SelfOpacity.hasDeferredRender() && !SelfOpacity.shouldFlushSelfOpacityAfterTranslucentBlocks()) {
                    long tSo = RenderProfiler.begin("RenderGlobal.tail.SelfOpacity.flush");
                    try {
                        SelfOpacity.flushDeferred(this.renderManager, SelfOpacity.DeferredFlushSite.RENDER_ENTITIES_TAIL);
                    } catch (Throwable t) {
                        System.err.println("[gomod] flushDeferredSelfOpacity (entities tail): " + t.getMessage());
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.SelfOpacity.flush", tSo);
                    }
                }
                if (!RenderProfiler.shouldBypassPlayerHooks()) {
                    long tN = RenderProfiler.begin("RenderGlobal.tail.DeferredNametagRenderer.flush");
                    try {
                        DeferredNametagRenderer.flushAfterRenderEntities();
                    } catch (Throwable t) {
                        System.err.println("[gomod] flushDeferredNametags: " + t.getMessage());
                    } finally {
                        RenderProfiler.end("RenderGlobal.tail.DeferredNametagRenderer.flush", tN);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[gomod] Error in RenderGlobalMixin: " + t.getMessage());
            }
        } finally {
            RenderProfiler.end("RenderGlobal.renderEntities.tail.total", __rpTailAll);
        }
    }

    // Fallback: tail after {@link #gomod$aRenderEntitiesTrailThenOrderedDeferredFlushes} (name prefix z- sorts later).
    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void gomod$zRenderGlowPlayersTailFallback(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        try {
            if (this.theWorld != null) {
                try {
                    gomodDrawPlayerGlowOutlines(this.theWorld.getLoadedEntityList(), 0.0, 0.0, 0.0, camera, partialTicks);
                } catch (Throwable t) { System.err.println("[gomod] Error in RenderGlobalMixin: " + t.getMessage()); }
            }
        } finally {
            try {
                MountHeadDim.endWorldRenderEntitiesPass();
            } catch (Throwable ignored) {
            }
        }
    }

    /** True if any entity will use the Player Queuer chameleon + main-scene-depth path this frame. */
    private static boolean gomod$anyStandalonePlayerQueuerInList(List<Entity> entities) {
        if (!PlayerQueuer.isEnabled()) {
            return false;
        }
        try {
            for (Entity e : entities) {
                if (!(e instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer ep = (EntityPlayer) e;
                if (!PlayerQueuer.isQueuedTargetPlayer(ep) && !PlayerQueuer.isQueuedExcludePlayer(ep)) {
                    continue;
                }
                if (GlowPlayer.isGlowingPlayer(ep) || GlowPlayer.isGlowingPlayer2(ep)) {
                    continue;
                }
                String name = ep.getName();
                if (name == null) {
                    continue;
                }
                if (PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(ep)) {
                    continue;
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void gomod$renderPlayerQueuerOutlineBatch(List<GomodDeferredQueuerOutline> batch, float partialTicks,
                                                      boolean blitSceneDepth,
                                                      java.nio.DoubleBuffer clipBuf) {
        if (batch == null || batch.isEmpty() || clipBuf == null) {
            return;
        }
        if (blitSceneDepth) {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(515);
            GlStateManager.depthMask(false);
        }
        for (GomodDeferredQueuerOutline gomod$queuerOutline : batch) {
            EntityPlayer ep = gomod$queuerOutline.player;
            if (ep == null) {
                continue;
            }
            int gomod$queuerTintRgb = gomod$queuerOutline.tintRgb;
            Entity entity = ep;
            boolean flag = this.mc.getRenderViewEntity() instanceof EntityLivingBase
                    && ((EntityLivingBase) this.mc.getRenderViewEntity()).isPlayerSleeping();
            if (entity != this.mc.getRenderViewEntity() || this.mc.gameSettings.thirdPersonView != 0 || flag) {
                double renderPosY = (this.mc.getRenderManager() instanceof RenderManagerAccessor)
                        ? ((RenderManagerAccessor) this.mc.getRenderManager()).gomod$getRenderPosY()
                        : 0.0;
                double clipY = (entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks)
                        - renderPosY + GOMOD_OUTLINE_SOLE_CLIP_Y_OFFSET;
                ((java.nio.Buffer) clipBuf).clear();
                clipBuf.put(0.0).put(1.0).put(0.0).put(-clipY);
                ((java.nio.Buffer) clipBuf).flip();
                org.lwjgl.opengl.GL11.glClipPlane(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0, clipBuf);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0);

                OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(true);
                try {
                    OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(gomod$queuerTintRgb);
                    me.napim.gomod.util.ForcedSolidTintTextureEnv.pushRgb(gomod$queuerTintRgb);
                    try {
                        OutlineRenderingHelper.setOutlineMode(false);
                        OutlineRenderingHelper.setGlowColorRgb(0xFFFFFF);
                        GlStateManager.enableTexture2D();
                        GlStateManager.enableLighting();
                        RenderHelper.enableStandardItemLighting();
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        this.renderManager.setRenderOutlines(true);
                        try {
                            GlStateManager.depthMask(true);
                            this.renderManager.renderEntitySimple(entity, partialTicks);
                        } finally {
                            this.renderManager.setRenderOutlines(false);
                        }
                        RenderHelper.disableStandardItemLighting();
                        GlStateManager.disableTexture2D();
                        GlStateManager.disableLighting();
                        OutlineRenderingHelper.setOutlineMode(true);
                    } finally {
                        me.napim.gomod.util.ForcedSolidTintTextureEnv.pop();
                        OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(-1);
                    }
                } finally {
                    OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(false);
                }
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CLIP_PLANE0);
            }
        }
        if (blitSceneDepth) {
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
        }
    }

}
