package me.napim.gomod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import me.napim.gomod.features.NametagRenderDebugCapture;
import org.lwjgl.opengl.GL11;


/**
 * In-world label matching vanilla {@link net.minecraft.client.renderer.entity.Render#renderLivingLabel} scale and
 * plate. Through-block rendering uses vanilla's translucent depth-off pass followed by its solid depth-tested pass.
 */
public final class VanillaEntityLabelRenderer {

    /** Same as {@link net.minecraft.client.renderer.entity.RenderLivingBase} nameplate scale. */
    public static final float NAMEPLATE_SCALE = 0.026666672F;

    /** Billboard-local Z (negative = toward camera) for the depth-tested solid pass. */
    private static final float MOB_LABEL_CAMERA_BIAS_BASE = -3.5F;
    private static final float MOB_LABEL_CAMERA_BIAS_PER_SIZE = -1.35F;
    private static final int VANILLA_THROUGH_WALL_TEXT_ALPHA = 0x20;

    private VanillaEntityLabelRenderer() {
    }

    /**
     * @param x,y,z   viewer-relative coordinates (same as {@code Render.doRender})
     * @param footYExtra added to foot {@code y} before vanilla {@code + height + 0.5f}
     */
    public static void render(Entity entity, String text, double x, double yFoot, double z, int maxDistance,
            double footYExtra, int textRgb) {
        render(entity, text, x, yFoot, z, maxDistance, footYExtra, textRgb, 1.0F, 1.0F, false, true);
    }

    /**
     * @param contentScale uniform scale around the label center (1 = full size); values below 1 shrink the label
     * @param contentAlpha multiplies background and text opacity (0–1)
     */
    public static void render(Entity entity, String text, double x, double yFoot, double z, int maxDistance,
            double footYExtra, int textRgb, float contentScale, float contentAlpha) {
        render(entity, text, x, yFoot, z, maxDistance, footYExtra, textRgb, contentScale, contentAlpha, false, true);
    }

    public static void render(Entity entity, String text, double x, double yFoot, double z, int maxDistance,
            double footYExtra, int textRgb, float contentScale, float contentAlpha, boolean prioritizeOverEntityBody) {
        render(entity, text, x, yFoot, z, maxDistance, footYExtra, textRgb, contentScale, contentAlpha,
                prioritizeOverEntityBody, true);
    }

    /**
     * @param prioritizeOverEntityBody enables the scoped close-F5 local-player occlusion pass after ray checks
     * @param fadeThroughWalls when true, draws a dim depth-off pass like vanilla nameplates; when false, depth-tested
     *        only (hidden behind blocks)
     */
    public static void render(Entity entity, String text, double x, double yFoot, double z, int maxDistance,
            double footYExtra, int textRgb, float contentScale, float contentAlpha, boolean prioritizeOverEntityBody,
            boolean fadeThroughWalls) {
        render(entity, text, x, yFoot, z, maxDistance, footYExtra, textRgb, contentScale, contentAlpha,
                prioritizeOverEntityBody, fadeThroughWalls, prioritizeOverEntityBody);
    }

    /**
     * Extended mob-label path that separates close-F5 local-player overlap from the optional camera-space offset.
     * A close mob name can draw over the local avatar only when block and other-entity ray checks are clear.
     */
    public static void render(Entity entity, String text, double x, double yFoot, double z, int maxDistance,
            double footYExtra, int textRgb, float contentScale, float contentAlpha, boolean prioritizeOverEntityBody,
            boolean fadeThroughWalls, boolean offsetTowardCamera) {
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager renderManager = mc == null ? null : mc.getRenderManager();
        FontRenderer fontRenderer = mc == null ? null : mc.fontRendererObj;
        Entity viewer = mc == null ? null : mc.getRenderViewEntity();
        if (renderManager == null || fontRenderer == null || viewer == null || entity == null) return;
        if (contentScale < 0.02F || contentAlpha < 0.02F) return;
        if (entity.getDistanceSqToEntity(viewer) > (double) (maxDistance * maxDistance)) return;
        int textWidth = fontRenderer.getStringWidth(text) / 2;
        float labelY = (float) (yFoot + footYExtra) + entity.height + 0.5F;
        NametagRenderDebugCapture.recordBillboard("begin", entity, text, "gomod-billboard",
                fadeThroughWalls, prioritizeOverEntityBody);
        NametagRenderState.begin("gomod-billboard");
        try {
            GlStateManager.pushAttrib();
            try {
                drawLabelBillboard(renderManager, mc, fontRenderer, entity, text, x, yFoot, labelY, z, textRgb,
                        contentScale, contentAlpha, textWidth, prioritizeOverEntityBody, fadeThroughWalls,
                        offsetTowardCamera);
            } finally { GlStateManager.popAttrib(); }
        } finally {
            NametagRenderState.end();
            NametagRenderDebugCapture.recordBillboard("end", entity, text, "gomod-billboard",
                    fadeThroughWalls, prioritizeOverEntityBody);
        }
    }

    private static void drawLabelBillboard(RenderManager renderManager, Minecraft mc, FontRenderer fontRenderer,
            Entity entity, String text, double x, double yFoot, float labelY, double z, int textRgb, float contentScale,
            float contentAlpha, int textWidth, boolean prioritizeOverEntityBody, boolean fadeThroughWalls,
            boolean offsetTowardCamera) {
        GlStateManager.pushMatrix();
        applyLabelBillboard(renderManager, mc, entity, x, labelY, z, contentScale, offsetTowardCamera);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        float ba = 0.25F * contentAlpha;
        int aSolid = Math.max(1, Math.min(0xFF, Math.round(0xFF * contentAlpha)));
        int solid = (aSolid << 24) | (textRgb & 0x00FFFFFF);

        if (fadeThroughWalls) {
            int dimAlpha = Math.max(1, Math.min(0xFF,
                    Math.round(VANILLA_THROUGH_WALL_TEXT_ALPHA * contentAlpha)));
            int dimText = (dimAlpha << 24) | (textRgb & 0x00FFFFFF);
            drawVanillaThroughWallPass(entity, textWidth, text, dimText, ba,
                    tessellator, worldRenderer, fontRenderer, fadeThroughWalls, prioritizeOverEntityBody);
            drawDepthTestedText(entity, textWidth, text, solid, fontRenderer,
                    fadeThroughWalls, prioritizeOverEntityBody);
        } else {
            drawDepthTestedLabel(entity, textWidth, text, solid, ba, tessellator, worldRenderer,
                    fontRenderer, fadeThroughWalls, prioritizeOverEntityBody);
        }

        // Match vanilla renderLivingLabel tail and restore the caller's expected fixed-function state.
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void drawVanillaThroughWallPass(Entity entity, int textWidth, String text, int dimText,
            float backgroundAlpha, Tessellator tessellator, WorldRenderer worldRenderer,
            FontRenderer fontRenderer, boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        drawLabelQuadAndText(textWidth, text, dimText, backgroundAlpha,
                tessellator, worldRenderer, fontRenderer, entity, "dim-depth-off",
                fadeThroughWalls, prioritizeOverEntityBody);
        GlStateManager.depthMask(oldDepthMask);
        if (oldDepthTest) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
    }

    private static void drawDepthTestedLabel(Entity entity, int textWidth, String text, int solid,
            float backgroundAlpha, Tessellator tessellator, WorldRenderer worldRenderer,
            FontRenderer fontRenderer, boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        drawLabelQuadAndText(textWidth, text, solid, backgroundAlpha, tessellator, worldRenderer,
                fontRenderer, entity, "solid-depth-tested", fadeThroughWalls, prioritizeOverEntityBody);
    }

    private static void drawDepthTestedText(Entity entity, int textWidth, String text, int solid,
            FontRenderer fontRenderer, boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        NametagRenderDebugCapture.recordBillboardStage("solid-text-depth-tested", entity, text, "gomod-billboard",
                fadeThroughWalls, prioritizeOverEntityBody);
        fontRenderer.drawString(text, -textWidth, 0, solid, false);
    }

    private static void drawLabelQuadAndText(int textWidth, String text, int solid, float backgroundAlpha,
            Tessellator tessellator, WorldRenderer worldRenderer, FontRenderer fontRenderer, Entity entity,
            String stage, boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        GlStateManager.disableTexture2D();
        NametagRenderDebugCapture.recordBillboardStage(stage, entity, text, "gomod-billboard",
                fadeThroughWalls, prioritizeOverEntityBody);
        worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        worldRenderer.pos(-textWidth - 1, -1, 0.0D).color(0.0F, 0.0F, 0.0F, backgroundAlpha).endVertex();
        worldRenderer.pos(-textWidth - 1, 8, 0.0D).color(0.0F, 0.0F, 0.0F, backgroundAlpha).endVertex();
        worldRenderer.pos(textWidth + 1, 8, 0.0D).color(0.0F, 0.0F, 0.0F, backgroundAlpha).endVertex();
        worldRenderer.pos(textWidth + 1, -1, 0.0D).color(0.0F, 0.0F, 0.0F, backgroundAlpha).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        fontRenderer.drawString(text, -textWidth, 0, solid, false);
    }

    private static void applyLabelBillboard(RenderManager renderManager, Minecraft mc, Entity entity, double x,
            float labelY, double z, float contentScale, boolean offsetTowardCamera) {
        GlStateManager.translate((float) x, labelY, (float) z);
        if (offsetTowardCamera) {
            pullTowardCamera(x, labelY, z, entity);
        }
        GlStateManager.rotate(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(LabelBillboardAngles.entityLabelPitchX(renderManager, mc), 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-NAMEPLATE_SCALE, -NAMEPLATE_SCALE, NAMEPLATE_SCALE);
        GlStateManager.translate(0.0F, 0.0F, 0.1075F);
        if (offsetTowardCamera) {
            GlStateManager.translate(0.0F, 0.0F, mobLabelCameraBias(entity));
        }
        final float pivotY = 4.0F;
        GlStateManager.translate(0.0F, pivotY, 0.0F);
        GlStateManager.scale(contentScale, contentScale, contentScale);
        GlStateManager.translate(0.0F, -pivotY, 0.0F);
    }

    /** Viewer-relative pull so the depth-tested solid pass sits slightly in front of the mob hull. */
    private static void pullTowardCamera(double x, float labelY, double z, Entity entity) {
        double dx = x;
        double dy = labelY;
        double dz = z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.15D) {
            return;
        }
        double pull = 0.2D + Math.min(1.8D, entity.height * 0.07D + entity.width * 0.2D);
        GlStateManager.translate((float) (-dx / dist * pull), (float) (-dy / dist * pull),
                (float) (-dz / dist * pull));
    }

    private static float mobLabelCameraBias(Entity entity) {
        if (entity == null) {
            return MOB_LABEL_CAMERA_BIAS_BASE;
        }
        float extent = Math.max(entity.height, entity.width * 2.0F);
        if (extent <= 2.0F) {
            return MOB_LABEL_CAMERA_BIAS_BASE;
        }
        float extra = Math.min(18.0F, (extent - 2.0F) * MOB_LABEL_CAMERA_BIAS_PER_SIZE);
        return MOB_LABEL_CAMERA_BIAS_BASE + extra;
    }
}
