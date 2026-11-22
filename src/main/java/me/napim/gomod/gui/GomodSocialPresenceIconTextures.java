package me.napim.gomod.gui;



import me.napim.gomod.render.LabelBillboardAngles;

import me.napim.gomod.render.VanillaEntityLabelRenderer;

import me.napim.gomod.util.GomodClasspathTextures;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.FontRenderer;

import net.minecraft.client.gui.Gui;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.client.renderer.entity.RenderManager;

import net.minecraft.entity.Entity;

import net.minecraft.util.ResourceLocation;



/**

 * Online / offline presence icons for gomod Social ({@code onlineicon.png}, {@code offlineicon.png}).

 */

public final class GomodSocialPresenceIconTextures {



    public static final ResourceLocation ONLINE =

            new ResourceLocation("gomod123", "textures/onlineicon.png");

    public static final ResourceLocation OFFLINE =

            new ResourceLocation("gomod123", "textures/offlineicon.png");



    /** Dock row: icon beside the friend name. */

    public static final int DOCK_DRAW_SIZE = 10;

    private static final int WORLD_ICON_PX = 10;

    private static final int WORLD_NAME_GAP = 2;



    private GomodSocialPresenceIconTextures() {}



    public static void preload(Minecraft mc) {

        if (mc == null) {

            return;

        }

        GomodClasspathTextures.ensureLoaded(mc, ONLINE);

        GomodClasspathTextures.ensureLoaded(mc, OFFLINE);

    }



    /** Center a service-state line and its existing online/offline texture as one group. */

    public static void drawCenteredConnectionStatus(FontRenderer fontRenderer, String formattedLine,

            int screenWidth, int y, boolean connected) {

        if (fontRenderer == null || formattedLine == null) {

            return;

        }

        Minecraft mc = Minecraft.getMinecraft();

        ResourceLocation texture = connected ? ONLINE : OFFLINE;

        if (mc == null || GomodClasspathTextures.ensureLoaded(mc, texture) == null) {

            GuiHeader.drawTitleCenteredWithShadow(fontRenderer, formattedLine, screenWidth, y, 0xCCCCCC);

            return;

        }

        int textWidth = fontRenderer.getStringWidth(formattedLine);

        int gap = 2;

        int groupWidth = textWidth + gap + DOCK_DRAW_SIZE;

        int x = screenWidth / 2 - groupWidth / 2;

        fontRenderer.drawStringWithShadow(formattedLine, x, y, 0xCCCCCC);

        drawDockBadge(x + textWidth + gap, y + (fontRenderer.FONT_HEIGHT - DOCK_DRAW_SIZE) / 2 - 1,

                connected ? 1 : 0);

    }



    /**

     * @param kind {@link SocialUi} presence: 0 offline, 1 online, 2 away

     * @return true if a texture was drawn

     */

    public static boolean drawDockBadge(int x, int y, int kind) {

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null) {

            return false;

        }

        ResourceLocation tex = textureForKind(kind);

        if (tex == null) {

            return false;

        }

        if (GomodClasspathTextures.ensureLoaded(mc, tex) == null) {

            return false;

        }

        int texW = GomodClasspathTextures.getWidth(tex, DOCK_DRAW_SIZE);

        int texH = GomodClasspathTextures.getHeight(tex, DOCK_DRAW_SIZE);

        if (texW <= 0 || texH <= 0) {

            return false;

        }

        GlStateManager.enableTexture2D();

        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        if (kind == 2) {

            GlStateManager.color(1.0F, 0.92F, 0.45F, 1.0F);

        } else {

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        }

        GomodClasspathTextures.bindTexture(mc, tex);

        Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, texW, texH, DOCK_DRAW_SIZE, DOCK_DRAW_SIZE, texW, texH);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        return true;

    }



    /**

     * Billboard icon immediately to the left of the player's nametag text (not on the head model).

     *

     * @param kind 0 offline, 1 online, 2 away

     */

    public static void drawWorldBadgeBesideName(RenderManager renderManager, Minecraft mc, FontRenderer fontRenderer,

            Entity entity, double x, double yFoot, double z, String nameLabel, float labelScale, int kind) {

        if (renderManager == null || mc == null || entity == null || fontRenderer == null) {

            return;

        }

        String label = nameLabel == null ? "" : nameLabel;

        if (label.isEmpty()) {

            label = entity.getName();

        }

        ResourceLocation tex = textureForKind(kind);

        if (tex == null || GomodClasspathTextures.ensureLoaded(mc, tex) == null) {

            return;

        }

        int texW = GomodClasspathTextures.getWidth(tex, WORLD_ICON_PX);

        int texH = GomodClasspathTextures.getHeight(tex, WORLD_ICON_PX);

        if (texW <= 0 || texH <= 0) {

            return;

        }



        int textHalf = fontRenderer.getStringWidth(label) / 2;

        float labelY = (float) yFoot + entity.height + 0.5F;

        int half = WORLD_ICON_PX / 2;



        GlStateManager.pushMatrix();

        try {

            GlStateManager.translate((float) x, labelY, (float) z);

            GlStateManager.rotate(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);

            GlStateManager.rotate(LabelBillboardAngles.entityLabelPitchX(renderManager, mc), 1.0F, 0.0F, 0.0F);

            GlStateManager.scale(-VanillaEntityLabelRenderer.NAMEPLATE_SCALE,

                    -VanillaEntityLabelRenderer.NAMEPLATE_SCALE, VanillaEntityLabelRenderer.NAMEPLATE_SCALE);

            GlStateManager.translate(0.0F, 0.0F, 0.1075F);

            final float pivotY = 4.0F;

            GlStateManager.translate(0.0F, pivotY, 0.0F);

            GlStateManager.scale(labelScale, labelScale, labelScale);

            GlStateManager.translate(0.0F, -pivotY, 0.0F);

            GlStateManager.translate(-textHalf - WORLD_ICON_PX - WORLD_NAME_GAP, 0.0F, 0.0F);



            GlStateManager.enableTexture2D();

            GlStateManager.enableBlend();

            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

            GlStateManager.disableLighting();

            GlStateManager.depthMask(false);

            if (kind == 2) {

                GlStateManager.color(1.0F, 0.92F, 0.45F, 1.0F);

            } else {

                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            }

            GomodClasspathTextures.bindTexture(mc, tex);

            Gui.drawScaledCustomSizeModalRect(-half, -half, 0, 0, texW, texH, WORLD_ICON_PX, WORLD_ICON_PX, texW, texH);

        } finally {

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            GlStateManager.enableLighting();

            GlStateManager.depthMask(true);

            GlStateManager.popMatrix();

        }

    }



    private static ResourceLocation textureForKind(int kind) {

        if (kind == 1 || kind == 2) {

            return ONLINE;

        }

        return OFFLINE;

    }

}


