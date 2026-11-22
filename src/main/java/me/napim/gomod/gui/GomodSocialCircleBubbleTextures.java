package me.napim.gomod.gui;



import me.napim.gomod.util.GomodClasspathTextures;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.FontRenderer;

import net.minecraft.util.EnumChatFormatting;

import net.minecraft.util.ResourceLocation;



/**

 * Pill bubble for workspace tags (THREAD, CHANNEL GLOBAL, TEMP GROUP) in {@link SocialUi#drawWorkspaceBar}.

 * Horizontally: fixed rounded ends, stretchable middle.

 */

public final class GomodSocialCircleBubbleTextures {



    public static final ResourceLocation TEXTURE =

            new ResourceLocation("gomod123", "textures/circlebubbleicon.png");



    private static final int TEX_W = 319;

    private static final int TEX_H = 108;

    private static final int LABEL_PAD_X = 14;

    /** Draw height inside the 48px workspace bar. */

    private static final int DRAW_H = 18;



    private GomodSocialCircleBubbleTextures() {}



    public static void preload(Minecraft mc) {

        if (mc != null) {

            GomodClasspathTextures.ensureLoaded(mc, TEXTURE);

        }

    }



    public static int drawHeight() {

        return DRAW_H;

    }



    public static int widthForHeight(int height) {

        if (height <= 0) {

            return 0;

        }

        int capDraw = SocialPillTextureDraw.capDrawForHeight(height, TEX_W, TEX_H);

        return capDraw * 2 + 4;

    }



    public static int widthForLabel(FontRenderer fr, String label) {

        if (fr == null || label == null || label.isEmpty()) {

            return widthForHeight(DRAW_H);

        }

        return Math.max(widthForHeight(DRAW_H), fr.getStringWidth(label) + LABEL_PAD_X * 2);

    }



    /**

     * Draws the bubble right-aligned at {@code rightX}, vertically centered on {@code centerY}.

     *

     * @return true if the PNG bubble was drawn (caller can skip fallback pill)

     */

    public static boolean drawLabelRight(FontRenderer fr, int rightX, int centerY, String label) {

        if (fr == null || label == null || label.isEmpty()) {

            return false;

        }

        int w = widthForLabel(fr, label);

        int h = DRAW_H;

        int x = rightX - w;

        int y = centerY - h / 2 + 1;

        if (!draw(x, y, w, h)) {

            return false;

        }

        String draw = EnumChatFormatting.GOLD + label;

        int textW = fr.getStringWidth(draw);

        int textX = x + (w - textW) / 2;

        int textY = y + (h - 8) / 2;

        fr.drawString(draw, textX, textY, SocialTheme.TEMP);

        return true;

    }



    public static boolean draw(int x, int y, int width, int height) {

        return SocialPillTextureDraw.drawHorizontal3Slice(TEXTURE, x, y, width, height, TEX_W, TEX_H);

    }

}

