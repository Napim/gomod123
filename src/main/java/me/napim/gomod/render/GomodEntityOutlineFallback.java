package me.napim.gomod.render;

import me.napim.gomod.features.DeathmatchGlow;
import me.napim.gomod.features.GlowPlayer;
import me.napim.gomod.features.PlayerQueuer;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.util.EntityOutlineDepthUtil;
import me.napim.gomod.util.ForcedSolidTintTextureEnv;
import me.napim.gomod.util.GlowTrailRenderer;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Weave/Lunar-safe entity outline pass. Lunar can load/bake RenderGlobal before our RenderGlobal mixin
 * attaches, so this path drives vanilla's outline framebuffer by reflection from the Weave world event.
 */
public final class GomodEntityOutlineFallback {
    private static final double OUTLINE_SOLE_CLIP_Y_OFFSET = -0.11;
    private static final double OUTLINE_SOLE_CLIP_Y_EXTRA_FORCED_TINT = 0.06;

    private static Field outlineFramebufferField;
    private static Field outlineShaderField;
    private static Method makeOutlineShaderMethod;
    private static boolean reflectionLookedUp;
    private static boolean hadRendered;
    private static long lastAttemptMs;
    private static long lastRenderMs;
    private static int lastTargetCount;
    private static boolean lastSceneDepthCopied;
    private static String lastStatus = "not run";

    private GomodEntityOutlineFallback() {
    }

    public static void renderFromWeaveWorldEvent(Minecraft mc, float partialTicks) {
        long profile = RenderProfiler.begin("Weave.RenderWorld.EntityOutlineFallback");
        try {
            lastAttemptMs = System.currentTimeMillis();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.renderGlobal == null
                    || mc.getRenderManager() == null) {
                lastStatus = "missing mc/world/player/renderGlobal";
                return;
            }
            if (!shouldRunOutlinePass()) {
                clearPreviousIfNeeded(mc, partialTicks);
                lastStatus = "idle";
                return;
            }

            OutlinePipeline pipeline = ensureOutlinePipeline(mc);
            if (pipeline == null || pipeline.framebuffer == null || pipeline.shader == null) {
                lastStatus = "vanilla outline framebuffer/shader unavailable";
                return;
            }

            List<OutlineTarget> targets = collectTargets(mc, partialTicks);
            lastTargetCount = targets.size();
            if (targets.isEmpty() && !DeathmatchGlow.isEnabled()) {
                clearPreviousIfNeeded(mc, partialTicks, pipeline);
                lastStatus = "no loaded outline targets";
                return;
            }

            if (drawOutlinePass(mc, partialTicks, pipeline, targets)) {
                lastRenderMs = System.currentTimeMillis();
                lastStatus = "rendered targets=" + targets.size();
            }
        } catch (Throwable t) {
            lastStatus = "error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            System.err.println("[gomod] Weave entity outline fallback: " + t.getMessage());
        } finally {
            RenderProfiler.end("Weave.RenderWorld.EntityOutlineFallback", profile);
        }
    }

    public static String debugDumpCompact() {
        long now = System.currentTimeMillis();
        return "WeaveOutlineFallback{status=" + lastStatus
                + ",targets=" + lastTargetCount
                + ",lastAttemptMsAgo=" + (lastAttemptMs > 0L ? now - lastAttemptMs : -1L)
                + ",lastRenderMsAgo=" + (lastRenderMs > 0L ? now - lastRenderMs : -1L)
                + ",sceneDepthCopied=" + lastSceneDepthCopied
                + ",reflectionLookedUp=" + reflectionLookedUp
                + ",fbField=" + (outlineFramebufferField != null)
                + ",shaderField=" + (outlineShaderField != null)
                + ",makeShader=" + (makeOutlineShaderMethod != null)
                + "}";
    }

    private static boolean shouldRunOutlinePass() {
        if (DeathmatchGlow.isEnabled()) {
            return true;
        }
        if (!GlowPlayer.getPlayers().isEmpty() || !GlowPlayer.getPlayers2().isEmpty()) {
            return true;
        }
        if (PlayerTarget.isGlowTeammateEnabled() || PlayerTarget.isTeammateTrailEnabled()) {
            return true;
        }
        return PlayerQueuer.isEnabled()
                && (!PlayerQueuer.getTargets().isEmpty() || !PlayerQueuer.getExcludeTargets().isEmpty());
    }

    private static List<OutlineTarget> collectTargets(Minecraft mc, float partialTicks) {
        List<OutlineTarget> targets = new ArrayList<OutlineTarget>();
        List<EntityPlayer> players = new ArrayList<EntityPlayer>();
        try {
            players.addAll(mc.theWorld.playerEntities);
        } catch (Throwable ignored) {
        }
        for (EntityPlayer ep : players) {
            if (ep == null || ep.getName() == null) {
                continue;
            }

            boolean manualV1 = GlowPlayer.isGlowingPlayer(ep);
            boolean manualV2 = GlowPlayer.isGlowingPlayer2(ep);
            boolean teammateGlow = PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(ep);
            boolean teammateTrailOnly = !manualV1 && !manualV2 && !teammateGlow
                    && PlayerTarget.isTeammateTrailEnabled() && PlayerTarget.isEntityCurrentTeammate(ep);
            boolean queuerInclude = PlayerQueuer.isQueuedTargetPlayer(ep);
            boolean queuerExclude = PlayerQueuer.isQueuedExcludePlayer(ep);
            boolean queuerOnly = !manualV1 && !manualV2 && !teammateGlow && !teammateTrailOnly
                    && (queuerInclude || queuerExclude);

            if (!manualV1 && !manualV2 && !teammateGlow && !teammateTrailOnly && !queuerOnly) {
                continue;
            }

            int rgb = 0xFFFFFF;
            boolean forcedTint = false;
            boolean renderBody = true;
            if (manualV1) {
                rgb = GlowPlayer.getColorRgb();
                forcedTint = true;
            } else if (manualV2) {
                rgb = 0xFFFFFF;
            } else if (teammateGlow) {
                if (PlayerTarget.isTeammateChameleonOutlineMode()) {
                    rgb = 0xFFFFFF;
                } else {
                    rgb = PlayerTarget.getGlowTeammateColorRgb();
                    forcedTint = true;
                }
            } else if (teammateTrailOnly) {
                rgb = PlayerTarget.getGlowTeammateColorRgb();
                renderBody = false;
            } else if (queuerOnly) {
                rgb = queuerExclude ? PlayerQueuer.QUEUER_EXCLUDE_OUTLINE_RGB : PlayerQueuer.QUEUER_INCLUDE_OUTLINE_RGB;
                forcedTint = true;
            }

            if ((manualV1 || teammateGlow || teammateTrailOnly) && ep.hurtTime > 0) {
                rgb = 0xFF3B30;
                forcedTint = true;
            }

            if (manualV1 || manualV2 || (teammateGlow && PlayerTarget.isTeammateTrailEnabled()) || teammateTrailOnly) {
                recordTrail(ep, partialTicks, rgb);
            }
            if (renderBody) {
                targets.add(new OutlineTarget(ep, rgb, forcedTint));
            }
        }
        return targets;
    }

    private static void recordTrail(EntityPlayer ep, float partialTicks, int rgb) {
        try {
            double x = ep.lastTickPosX + (ep.posX - ep.lastTickPosX) * partialTicks;
            double y = ep.lastTickPosY + (ep.posY - ep.lastTickPosY) * partialTicks;
            double z = ep.lastTickPosZ + (ep.posZ - ep.lastTickPosZ) * partialTicks;
            String key = GlowPlayer.resolveMatchedGlowName(ep);
            if (key == null) {
                key = GlowPlayer.resolveMatchedGlowName2(ep);
            }
            GlowTrailRenderer.recordPosition(key != null ? key : ep.getName(), x, y, z, rgb);
        } catch (Throwable ignored) {
        }
    }

    private static boolean drawOutlinePass(Minecraft mc, float partialTicks, OutlinePipeline pipeline,
                                        List<OutlineTarget> targets) {
        try {
            GlStateManager.disableFog();
            pipeline.framebuffer.framebufferClear();
            pipeline.framebuffer.bindFramebuffer(false);
            OutlineRenderingHelper.setModEntityOutlinePassActive(true);
            lastSceneDepthCopied = EntityOutlineDepthUtil.tryCopyMainDepthToOutline(mc, pipeline.framebuffer);
            if (!lastSceneDepthCopied && !targets.isEmpty()) {
                lastStatus = "scene depth unavailable; skipped player outline to avoid through-block ESP";
                hadRendered = false;
                return false;
            }

            if (lastSceneDepthCopied) {
                GlStateManager.enableDepth();
                GlStateManager.depthFunc(515);
            } else {
                GlStateManager.disableDepth();
            }
            GlStateManager.depthMask(false);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();

            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(-1.0f, -1.0f);

            DoubleBuffer clipBuf = ByteBuffer.allocateDirect(32)
                    .order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();
            for (OutlineTarget target : targets) {
                drawTarget(mc, partialTicks, target, clipBuf);
            }

            if (DeathmatchGlow.isEnabled()) {
                try {
                    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                    GlStateManager.depthMask(false);
                    GlStateManager.disableTexture2D();
                    GlStateManager.disableLighting();
                    DeathmatchGlow.renderBorderIntoFBO(
                            mc.getRenderManager().viewerPosX,
                            mc.getRenderManager().viewerPosY,
                            mc.getRenderManager().viewerPosZ,
                            partialTicks,
                            mc);
                    GlStateManager.depthMask(true);
                } catch (Throwable t) {
                    System.err.println("[gomod] Weave deathmatch outline fallback: " + t.getMessage());
                }
            }

            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
            OutlineRenderingHelper.setOutlineMode(false);
            OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(false);

            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.depthMask(false);
            pipeline.shader.loadShaderGroup(partialTicks);
            GlStateManager.enableLighting();
            GlStateManager.depthMask(true);
            mc.getFramebuffer().bindFramebuffer(false);
            GlStateManager.enableFog();
            GlStateManager.enableBlend();
            GlStateManager.enableColorMaterial();
            GlStateManager.depthFunc(515);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            hadRendered = true;
            return true;
        } finally {
            try {
                if (mc != null && mc.getFramebuffer() != null) {
                    mc.getFramebuffer().bindFramebuffer(false);
                }
            } catch (Throwable ignored) {
            }
            OutlineRenderingHelper.resetLeakProneOutlineClientState();
        }
    }

    private static void drawTarget(Minecraft mc, float partialTicks, OutlineTarget target, DoubleBuffer clipBuf) {
        EntityPlayer ep = target.player;
        if (ep == null) {
            return;
        }
        Entity view = mc.getRenderViewEntity();
        boolean sleeping = view instanceof EntityLivingBase && ((EntityLivingBase) view).isPlayerSleeping();
        if (ep == view && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 0 && !sleeping) {
            return;
        }

        RenderManager rm = mc.getRenderManager();
        double soleClip = OUTLINE_SOLE_CLIP_Y_OFFSET
                + (target.forcedTint ? OUTLINE_SOLE_CLIP_Y_EXTRA_FORCED_TINT : 0.0);
        double clipY = (ep.lastTickPosY + (ep.posY - ep.lastTickPosY) * partialTicks)
                - rm.viewerPosY + soleClip;
        ((Buffer) clipBuf).clear();
        clipBuf.put(0.0).put(1.0).put(0.0).put(-clipY);
        ((Buffer) clipBuf).flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0, clipBuf);
        GL11.glEnable(GL11.GL_CLIP_PLANE0);

        OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(true);
        try {
            if (target.forcedTint) {
                OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(target.rgb);
                ForcedSolidTintTextureEnv.pushRgb(target.rgb);
            }
            try {
                OutlineRenderingHelper.setOutlineMode(false);
                OutlineRenderingHelper.setGlowColorRgb(0xFFFFFF);
                GlStateManager.enableTexture2D();
                GlStateManager.enableLighting();
                RenderHelper.enableStandardItemLighting();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                rm.setRenderOutlines(true);
                try {
                    GlStateManager.depthMask(true);
                    rm.renderEntitySimple(ep, partialTicks);
                } finally {
                    rm.setRenderOutlines(false);
                }
            } finally {
                if (target.forcedTint) {
                    ForcedSolidTintTextureEnv.pop();
                    OutlineRenderingHelper.setForcedChameleonOutlineTintRgb(-1);
                }
            }
        } finally {
            OutlineRenderingHelper.setSuppressNametagInOutlineFramebuffer(false);
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
        }
    }

    private static void clearPreviousIfNeeded(Minecraft mc, float partialTicks) {
        if (!hadRendered || mc == null || mc.renderGlobal == null) {
            return;
        }
        clearPreviousIfNeeded(mc, partialTicks, ensureOutlinePipeline(mc));
    }

    private static void clearPreviousIfNeeded(Minecraft mc, float partialTicks, OutlinePipeline pipeline) {
        if (!hadRendered || pipeline == null || pipeline.framebuffer == null || pipeline.shader == null) {
            return;
        }
        try {
            GlStateManager.disableFog();
            pipeline.framebuffer.framebufferClear();
            pipeline.framebuffer.bindFramebuffer(false);
            RenderHelper.disableStandardItemLighting();
            if (mc.getRenderManager() != null) {
                mc.getRenderManager().setRenderOutlines(false);
            }
            GlStateManager.depthMask(false);
            pipeline.shader.loadShaderGroup(partialTicks);
            GlStateManager.enableLighting();
            GlStateManager.depthMask(true);
            if (mc.getFramebuffer() != null) {
                mc.getFramebuffer().bindFramebuffer(false);
            }
            GlStateManager.enableFog();
            GlStateManager.enableBlend();
            GlStateManager.enableColorMaterial();
            GlStateManager.depthFunc(515);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
        } catch (Throwable t) {
            System.err.println("[gomod] Weave entity outline fallback clear: " + t.getMessage());
        } finally {
            hadRendered = false;
            OutlineRenderingHelper.resetLeakProneOutlineClientState();
        }
    }

    private static OutlinePipeline ensureOutlinePipeline(Minecraft mc) {
        RenderGlobal renderGlobal = mc.renderGlobal;
        lookupReflection(renderGlobal.getClass());
        try {
            Framebuffer fb = getFramebuffer(renderGlobal);
            ShaderGroup shader = getShader(renderGlobal);
            if ((fb == null || shader == null) && makeOutlineShaderMethod != null) {
                makeOutlineShaderMethod.invoke(renderGlobal);
                fb = getFramebuffer(renderGlobal);
                shader = getShader(renderGlobal);
            }
            if (fb == null || shader == null) {
                return null;
            }
            return new OutlinePipeline(fb, shader);
        } catch (Throwable t) {
            lastStatus = "reflection failed: " + t.getMessage();
            return null;
        }
    }

    private static Framebuffer getFramebuffer(RenderGlobal renderGlobal) throws IllegalAccessException {
        Object value = outlineFramebufferField != null ? outlineFramebufferField.get(renderGlobal) : null;
        return value instanceof Framebuffer ? (Framebuffer) value : null;
    }

    private static ShaderGroup getShader(RenderGlobal renderGlobal) throws IllegalAccessException {
        Object value = outlineShaderField != null ? outlineShaderField.get(renderGlobal) : null;
        return value instanceof ShaderGroup ? (ShaderGroup) value : null;
    }

    private static void lookupReflection(Class<?> renderGlobalClass) {
        if (reflectionLookedUp) {
            return;
        }
        reflectionLookedUp = true;
        outlineFramebufferField = findField(renderGlobalClass,
                "entityOutlineFramebuffer", "field_175015_z", "A");
        outlineShaderField = findField(renderGlobalClass,
                "entityOutlineShader", "field_174991_A", "B");
        makeOutlineShaderMethod = findMethod(renderGlobalClass,
                "makeEntityOutlineShader", "func_174966_b", "b");
    }

    private static Field findField(Class<?> start, String... names) {
        Class<?> c = start;
        while (c != null) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> start, String... names) {
        Class<?> c = start;
        while (c != null) {
            for (String name : names) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static final class OutlineTarget {
        final EntityPlayer player;
        final int rgb;
        final boolean forcedTint;

        OutlineTarget(EntityPlayer player, int rgb, boolean forcedTint) {
            this.player = player;
            this.rgb = rgb & 0xFFFFFF;
            this.forcedTint = forcedTint;
        }
    }

    private static final class OutlinePipeline {
        final Framebuffer framebuffer;
        final ShaderGroup shader;

        OutlinePipeline(Framebuffer framebuffer, ShaderGroup shader) {
            this.framebuffer = framebuffer;
            this.shader = shader;
        }
    }
}
