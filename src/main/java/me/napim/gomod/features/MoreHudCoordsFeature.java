package me.napim.gomod.features;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.BiomeGenBase;
import me.napim.gomod.gui.HudOpacitySliderBoxInsets;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.util.HudGlState;
import org.lwjgl.opengl.GL11;

/**
 * Coordinates HUD ported from Powns' Coords HUD: X/Y/Z, optional biome, compass ring with heading dot.
 */
public final class MoreHudCoordsFeature {
    private static final ThreadLocal<CoordinateFormats> COORDINATE_FORMATS =
            ThreadLocal.withInitial(CoordinateFormats::new);

    /** Session-keyed HUD skin so account switches never reuse {@code thePlayer} / tab caches. */
    private static UUID hudSkinSessionId;
    private static String hudSkinSessionName;
    /** Minotar full-skin download target (may lag behind {@link #hudSkinGpuLocation}). */
    private static ResourceLocation hudSkinLocation;
    /** CPU→GPU copy of the downloaded atlas; avoids stale ThreadDownloadImageData GL uploads. */
    private static ResourceLocation hudSkinGpuLocation;
    private static final int SKIN_ATLAS_SIZE = 64;

    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final int PAD_X = 2;
    private static final int PAD_Y = 3;
    private static final int VALUE_GAP = 4;
    private static final int COMPASS_GAP = 8;
    /** Small gap between the Z row and the biome line (gentle push, not a full line). */
    private static final int BIOME_TOP_GAP_PX = 3;
    /** Compass height is based on this many coord axis rows (X/Y/Z), then scaled up slightly. */
    private static final int COMPASS_AXIS_ROWS = 3;
    private static final int COMPASS_INNER_PAD = 2;
    private static final float COMPASS_RING_LINE_WIDTH = 2.0F;
    /** Extra background padding past the value column in vertical layout. */
    private static final int VERTICAL_VALUE_RIGHT_PAD = 4;
    /** Nudge vertical direction label down to visually center on the player head (px). */
    private static final int VERTICAL_CARDINAL_Y_NUDGE = 1;
    /** Nudge compass N/S cardinals right for visual centering on the ring. */
    private static final int COMPASS_NS_X_NUDGE = 1;
    /** Nudge compass W cardinal right for visual centering on the ring. */
    private static final int COMPASS_W_X_NUDGE = 1;
    /** Extra orbit room beyond text + compass footprint when dragging in the HUD editor. */
    private static final int COMPASS_NUDGE_MAX_EXTRA = 48;
    /** Minimum gap between the coords text column and the compass ring in compass layout. */
    private static final int COMPASS_TEXT_MIN_GAP = 2;
    /** Vertical / text-column opacity box sits 2px lower than padUp (matches editor preview). */
    private static final int VERTICAL_BG_TOP_INSET = 2;
    /** Opacity margin around compass geom (must match {@link #compassPartUnscaledBounds}). */
    private static final int COMPASS_BG_MARGIN = 2;

    public static final int DEFAULT_COMPASS_RING_RADIUS_OFFSET = -6;
    public static final int DEFAULT_COMPASS_DOT_ORBIT_OFFSET = -1;
    public static final int DEFAULT_COMPASS_CARDINAL_OUTSET = 1;

    /** Fixed back→front draw order (not user-tunable; HUD overlay ignores GL Z). */
    private static final int DRAW_COORDS = 150;
    private static final int DRAW_RING = 200;
    private static final int DRAW_DOT = 210;
    private static final int DRAW_CARDINALS = 250;

    private static final int DEPTH_TIE_RING = 0;
    private static final int DEPTH_TIE_DOT = 1;
    private static final int DEPTH_TIE_CARDINAL = 2;
    private static final int DEPTH_TIE_COORDS = 3;
    private static final double PREVIEW_X = 123.456;
    private static final double PREVIEW_Y = 64.5;
    private static final double PREVIEW_Z = -789.012;

    private MoreHudCoordsFeature() {
    }

    public enum PrefixStyle {
        REGULAR,
        REGULAR_NO_COLON,
        PARENTHESES,
        SQUARE_BRACKETS,
        NONE,
        /** {@code X>}, {@code Y>}, {@code Z>} */
        ARROW_GT
    }

    public static void render(Minecraft mc, FontRenderer fr, int leftX, int topY, boolean preview) {
        if (mc == null || fr == null) {
            return;
        }
        Layout layout = measureLayout(mc, fr, preview);
        int bg = ConfigManager.getMoreHudCoordsBg();
        if (bg > 0) {
            if (ConfigManager.isMoreHudCoordsVertical()) {
                drawBackgroundRect(leftX, topY, 0, VERTICAL_BG_TOP_INSET, layout.width, layout.height, bg);
            } else if (ConfigManager.isMoreHudCoordsCompassOnly()) {
                CompassGeom geom = measureCompassGeomForLayout(fr, layout);
                int[] compassBb = new int[4];
                compassPartUnscaledBounds(geom, compassBb);
                drawBackgroundRect(leftX, topY, compassBb[0], compassBb[1], compassBb[2], compassBb[3], bg);
            } else {
                int[] textBb = new int[4];
                textBlockUnscaledBounds(fr, layout, textBb);
                drawBackgroundRect(leftX, topY, textBb[0], textBb[1], textBb[2], textBb[3], bg);
                CompassGeom geom = measureCompassGeomForLayout(fr, layout);
                int[] compassBb = new int[4];
                compassPartUnscaledBounds(geom, compassBb);
                drawBackgroundRect(leftX, topY, compassBb[0], compassBb[1], compassBb[2], compassBb[3], bg);
            }
        }
        double px = preview ? PREVIEW_X : mc.thePlayer.posX;
        double py = preview ? PREVIEW_Y : mc.thePlayer.posY;
        double pz = preview ? PREVIEW_Z : mc.thePlayer.posZ;
        String sx = formatCoord(px);
        String sy = formatCoord(py);
        String sz = formatCoord(pz);
        int prefixCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsPrefixColor() & 0xFFFFFF);
        int textCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsTextColor() & 0xFFFFFF);
        int biomeCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsBiomeColor() & 0xFFFFFF);
        int cardinalCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsDirectionColor() & 0xFFFFFF);
        int ringCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsCompassRingColor() & 0xFFFFFF);
        int dotCol = 0xFF000000 | (ConfigManager.getMoreHudCoordsCompassDotColor() & 0xFFFFFF);
        boolean shadow = ConfigManager.isMoreHudCoordsShadow();
        if (ConfigManager.isMoreHudCoordsVertical()) {
            drawVertical(mc, fr, leftX, topY, layout, sx, sy, sz, prefixCol, textCol, biomeCol, cardinalCol, shadow,
                    preview);
        } else if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            drawCompassOnly(mc, fr, leftX, topY, cardinalCol, ringCol, dotCol, shadow, preview);
        } else {
            drawCompassLayout(mc, fr, leftX, topY, layout, sx, sy, sz, prefixCol, textCol, biomeCol, cardinalCol,
                    ringCol, dotCol, shadow, preview);
        }
    }

    public static void screenBounds(Minecraft mc, FontRenderer fr, int leftX, int topY, float scale, boolean preview,
            int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        if (mc == null || fr == null) {
            out[0] = leftX;
            out[1] = topY;
            out[2] = 24;
            out[3] = 24;
            return;
        }
        Layout layout = measureLayout(mc, fr, preview);
        if (ConfigManager.isMoreHudCoordsVertical()) {
            // Match drawBackgroundRect vertical inset (same as text opacity box).
            partRectToScreen(leftX, topY, scale, 0, VERTICAL_BG_TOP_INSET, layout.width, layout.height, out);
        } else if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            CompassGeom geom = measureCompassGeomForLayout(fr, layout);
            int[] bb = new int[4];
            compassPartUnscaledBounds(geom, bb);
            partRectToScreen(leftX, topY, scale, bb[0], bb[1], bb[2], bb[3], out);
        } else {
            out[0] = leftX;
            out[1] = topY;
            out[2] = Math.max(1, (int) Math.ceil(layout.width * scale));
            out[3] = Math.max(1, (int) Math.ceil(layout.height * scale));
        }
    }

    /** Coords text column only (full block when vertical). Used by HUD editor — matches opacity background rect. */
    public static void textBlockScreenBounds(Minecraft mc, FontRenderer fr, int leftX, int topY, float scale,
            boolean preview, int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        if (mc == null || fr == null) {
            out[0] = leftX;
            out[1] = topY;
            out[2] = 24;
            out[3] = 24;
            return;
        }
        Layout layout = measureLayout(mc, fr, preview);
        int[] bb = new int[4];
        if (ConfigManager.isMoreHudCoordsVertical()) {
            bb[0] = 0;
            bb[1] = VERTICAL_BG_TOP_INSET;
            bb[2] = layout.width;
            bb[3] = layout.height;
        } else if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            CompassGeom geom = measureCompassGeomForLayout(fr, layout);
            compassPartUnscaledBounds(geom, bb);
        } else {
            textBlockUnscaledBounds(fr, layout, bb);
        }
        partRectToScreen(leftX, topY, scale, bb[0], bb[1], bb[2], bb[3], out);
    }

    /** Compass ring region (horizontal layout only). Anchor is compass center on screen. */
    public static void compassPartScreenBounds(Minecraft mc, FontRenderer fr, int leftX, int topY, float scale,
            boolean preview, int[] out, int[] anchorOut) {
        if (out == null || out.length < 4) {
            return;
        }
        if (mc == null || fr == null) {
            out[0] = leftX;
            out[1] = topY;
            out[2] = 32;
            out[3] = 32;
            if (anchorOut != null && anchorOut.length >= 2) {
                anchorOut[0] = leftX + 16;
                anchorOut[1] = topY + 16;
            }
            return;
        }
        Layout layout = measureLayout(mc, fr, preview);
        CompassGeom geom = measureCompassGeomForLayout(fr, layout);
        int[] bb = new int[4];
        compassPartUnscaledBounds(geom, bb);
        partRectToScreen(leftX, topY, scale, bb[0], bb[1], bb[2], bb[3], out);
        if (anchorOut != null && anchorOut.length >= 2) {
            anchorOut[0] = leftX + (int) Math.round(geom.cx * scale);
            anchorOut[1] = topY + (int) Math.round(geom.cy * scale);
        }
    }

    /**
     * Clamps a HUD-editor drag anchor (compass center, screen px).
     *
     * @param avoidTextOverlap when false (live HUD editor drag), the compass follows the cursor inside orbit
     *                         bounds only; when true (sanitize / load), nudge is pushed off the text column.
     */
    public static int[] clampCompassDragAnchorScreen(Minecraft mc, int screenAnchorX, int screenAnchorY,
            boolean avoidTextOverlap) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return new int[] { screenAnchorX, screenAnchorY };
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudCoordsScale();
        int leftX = sw / 2 + ConfigManager.getMoreHudCoordsOffsetCenterX();
        int topY = ConfigManager.getMoreHudCoordsPosY();
        int[] nudge = screenToCompassNudge(mc, fr, leftX, topY, sc, screenAnchorX, screenAnchorY);
        Layout layout = measureLayout(mc, fr, true);
        int[] nudgeBounds = compassNudgeBoundsUnscaled(fr, layout);
        nudge[0] = clampInt(nudge[0], nudgeBounds[0], nudgeBounds[1]);
        nudge[1] = clampInt(nudge[1], nudgeBounds[2], nudgeBounds[3]);
        if (avoidTextOverlap) {
            nudge = clampCompassNudgeAvoidTextOverlap(fr, layout, nudge[0], nudge[1]);
        }
        return compassCenterScreenFromNudge(mc, fr, leftX, topY, sc, nudge[0], nudge[1]);
    }

    public static int[] clampCompassDragAnchorScreen(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        return clampCompassDragAnchorScreen(mc, screenAnchorX, screenAnchorY, true);
    }

    public static void applyCompassDragToConfig(Minecraft mc, int screenAnchorX, int screenAnchorY) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return;
        }
        int[] clamped = clampCompassDragAnchorScreen(mc, screenAnchorX, screenAnchorY, false);
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        float sc = ConfigManager.getMoreHudCoordsScale();
        int leftX = sw / 2 + ConfigManager.getMoreHudCoordsOffsetCenterX();
        int topY = ConfigManager.getMoreHudCoordsPosY();
        int[] nudge = screenToCompassNudge(mc, fr, leftX, topY, sc, clamped[0], clamped[1]);
        ConfigManager.setMoreHudCoordsCompassNudge(nudge[0], nudge[1]);
    }

    /**
     * HUD editor arrow keys: move compass nudge in screen space (1:1 with arrow direction at current scale).
     */
    public static void nudgeCompassByScreen(Minecraft mc, int screenDx, int screenDy) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null || (screenDx == 0 && screenDy == 0)) {
            return;
        }
        float sc = ConfigManager.getMoreHudCoordsScale();
        int unscaledDx = (int) Math.round(screenDx / (double) sc);
        int unscaledDy = (int) Math.round(screenDy / (double) sc);
        Layout layout = measureLayout(mc, fr, true);
        int[] bounds = compassNudgeBoundsUnscaled(fr, layout);
        int nx = clampInt(ConfigManager.getMoreHudCoordsCompassNudgeX() + unscaledDx, bounds[0], bounds[1]);
        int ny = clampInt(ConfigManager.getMoreHudCoordsCompassNudgeY() + unscaledDy, bounds[2], bounds[3]);
        ConfigManager.setMoreHudCoordsCompassNudge(nx, ny);
    }

    private static int[] screenToCompassNudge(Minecraft mc, FontRenderer fr, int leftX, int topY, float scale,
            int screenAnchorX, int screenAnchorY) {
        int nux = (int) Math.round((screenAnchorX - leftX) / (double) scale);
        int nuy = (int) Math.round((screenAnchorY - topY) / (double) scale);
        int[] def = defaultCompassCenterUnscaled(fr, measureLayout(mc, fr, true));
        return new int[] { nux - def[0], nuy - def[1] };
    }

    private static int[] compassCenterScreenFromNudge(Minecraft mc, FontRenderer fr, int leftX, int topY, float scale,
            int nudgeX, int nudgeY) {
        int[] def = defaultCompassCenterUnscaled(fr, measureLayout(mc, fr, true));
        return new int[] {
                leftX + (int) Math.round((def[0] + nudgeX) * scale),
                topY + (int) Math.round((def[1] + nudgeY) * scale)
        };
    }

    private static int compassLeftUnscaled(Layout layout) {
        if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            return layout.padLeft;
        }
        return layout.padLeft + layout.coordsBlockW + COMPASS_GAP;
    }

    /** Default compass center in module-unscaled coords (no nudge). */
    private static int[] defaultCompassCenterUnscaled(FontRenderer fr, Layout layout) {
        int lineH = fr.FONT_HEIGHT;
        int compassLeft = compassLeftUnscaled(layout);
        int yX = layout.padUp;
        int yZ = layout.padUp + 2 * lineH;
        int radius = Math.max(4, yZ - yX);
        return new int[] {
                compassLeft + COMPASS_INNER_PAD + radius,
                yZ + lineH / 2
        };
    }

    /** Unscaled nudge limits {@code [minX, maxX, minY, maxY]} relative to default compass center. */
    private static int[] compassNudgeBoundsUnscaled(FontRenderer fr, Layout layout) {
        int[] def = defaultCompassCenterUnscaled(fr, layout);
        int lineH = fr.FONT_HEIGHT;
        int yX = layout.padUp;
        int yZ = layout.padUp + 2 * lineH;
        int compassLeft = compassLeftUnscaled(layout);
        CompassGeom geom = measureCompassGeomWithNudge(fr, compassLeft, yX, yZ, 0, 0);
        int r = geom.radius;
        int halfW = Math.round(geom.boundsW / 2.0F);
        int halfH = Math.round(geom.boundsH / 2.0F);

        if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            int max = Math.max(32, r + COMPASS_NUDGE_MAX_EXTRA);
            return new int[] { -max, max, -max, max };
        }

        float textCx = layout.padLeft + layout.coordsBlockW / 2.0F;
        float textCy = layout.padUp + layout.coordsBlockH / 2.0F;
        int orbitX = layout.coordsBlockW + geom.boundsW + COMPASS_GAP + COMPASS_NUDGE_MAX_EXTRA;
        int orbitY = layout.coordsBlockH + geom.boundsH + COMPASS_NUDGE_MAX_EXTRA;

        int minCx = Math.round(textCx - orbitX);
        int maxCx = Math.round(textCx + orbitX);
        int minCy = Math.round(textCy - orbitY);
        int maxCy = Math.round(textCy + orbitY);

        if (minCx > maxCx) {
            minCx = maxCx = def[0];
        }
        if (minCy > maxCy) {
            minCy = maxCy = def[1];
        }

        return new int[] {
                minCx - def[0],
                maxCx - def[0],
                minCy - def[1],
                maxCy - def[1]
        };
    }

    /** Max absolute nudge stored in config for the current layout (editor orbit radius). */
    public static int compassNudgeConfigLimit(Minecraft mc) {
        FontRenderer fr = mc == null ? null : mc.fontRendererObj;
        if (fr == null) {
            return 256;
        }
        int[] bounds = compassNudgeBoundsUnscaled(fr, measureLayout(mc, fr, true));
        return Math.max(64, Math.max(Math.max(Math.abs(bounds[0]), Math.abs(bounds[1])),
                Math.max(Math.abs(bounds[2]), Math.abs(bounds[3]))));
    }

    private static CompassGeom measureCompassGeomWithNudge(FontRenderer fr, int compassLeft, int yX, int yZ,
            int nudgeX, int nudgeY) {
        int lineH = fr.FONT_HEIGHT;
        int radius = Math.max(4, yZ - yX);
        float cy = yZ + lineH / 2.0F + nudgeY;
        float cx = compassLeft + COMPASS_INNER_PAD + radius + nudgeX;
        int twMax = Math.max(fr.getStringWidth("N"), Math.max(fr.getStringWidth("W"), fr.getStringWidth("E")));
        int layoutPad = compassLayoutPadPx();
        int top = Math.round(cy - radius - lineH / 2.0F) - layoutPad;
        int bottom = Math.round(cy + radius + lineH / 2.0F) + layoutPad;
        int boundsW = COMPASS_INNER_PAD * 2 + radius * 2 + twMax + layoutPad * 2;
        int boundsH = Math.max(lineH * 3, bottom - top + COMPASS_INNER_PAD);
        return new CompassGeom(cx, cy, radius, boundsW, boundsH, lineH);
    }

    private static boolean compassGeomOverlapsTextBlock(CompassGeom geom, Layout layout) {
        if (ConfigManager.isMoreHudCoordsVertical() || ConfigManager.isMoreHudCoordsCompassOnly()) {
            return false;
        }
        int textL = layout.padLeft;
        int textR = layout.padLeft + layout.coordsBlockW;
        int textT = layout.padUp;
        int textB = layout.padUp + layout.coordsBlockH;
        int compL = Math.round(geom.cx - geom.boundsW / 2.0F);
        int compR = Math.round(geom.cx + geom.boundsW / 2.0F);
        int compT = Math.round(geom.cy - geom.boundsH / 2.0F);
        int compB = Math.round(geom.cy + geom.boundsH / 2.0F);
        return compL < textR + COMPASS_TEXT_MIN_GAP && compR > textL && compT < textB && compB > textT;
    }

    private static int[] clampCompassNudgeAvoidTextOverlap(FontRenderer fr, Layout layout, int nudgeX, int nudgeY) {
        if (ConfigManager.isMoreHudCoordsVertical() || ConfigManager.isMoreHudCoordsCompassOnly()) {
            return new int[] { nudgeX, nudgeY };
        }
        int compassLeft = compassLeftUnscaled(layout);
        int lineH = fr.FONT_HEIGHT;
        int yX = layout.padUp;
        int yZ = layout.padUp + 2 * lineH;
        int[] bounds = compassNudgeBoundsUnscaled(fr, layout);
        int nx = clampInt(nudgeX, bounds[0], bounds[1]);
        int ny = clampInt(nudgeY, bounds[2], bounds[3]);
        float textCx = layout.padLeft + layout.coordsBlockW / 2.0F;
        float textCy = layout.padUp + layout.coordsBlockH / 2.0F;

        int guard = 0;
        while (guard++ < 128) {
            CompassGeom geom = measureCompassGeomWithNudge(fr, compassLeft, yX, yZ, nx, ny);
            if (!compassGeomOverlapsTextBlock(geom, layout)) {
                return new int[] { nx, ny };
            }
            float dx = geom.cx - textCx;
            float dy = geom.cy - textCy;
            if (Math.abs(dx) >= Math.abs(dy)) {
                nx += dx >= 0.0F ? 1 : -1;
            } else {
                ny += dy >= 0.0F ? 1 : -1;
            }
            nx = clampInt(nx, bounds[0], bounds[1]);
            ny = clampInt(ny, bounds[2], bounds[3]);
        }
        return new int[] { nx, ny };
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static void partRectToScreen(int anchorLeftX, int anchorTopY, float scale, int uLeft, int uTop, int uRight,
            int uBottom, int[] out) {
        out[0] = anchorLeftX + (int) Math.round(uLeft * scale);
        out[1] = anchorTopY + (int) Math.round(uTop * scale);
        out[2] = Math.max(1, (int) Math.ceil((uRight - uLeft) * scale));
        out[3] = Math.max(1, (int) Math.ceil((uBottom - uTop) * scale));
    }

    private static void drawVertical(Minecraft mc, FontRenderer fr, int x, int y, Layout layout, String sx, String sy,
            String sz, int prefixCol, int textCol, int biomeCol, int dirCol, boolean shadow, boolean preview) {
        int tx = x + layout.padLeft;
        int ty = y + layout.padUp;
        int valueX = axisValueX(fr, tx, layout.prefixColW);
        int lineH = fr.FONT_HEIGHT;
        ty = drawAxisRow(fr, tx, valueX, ty, "X", sx, prefixCol, textCol, shadow);
        ty = drawAxisRow(fr, tx, valueX, ty, "Y", sy, prefixCol, textCol, shadow);
        ty = drawAxisRow(fr, tx, valueX, ty, "Z", sz, prefixCol, textCol, shadow);
        if (ConfigManager.isMoreHudCoordsDirection()) {
            int cardinalX = verticalDirectionCardinalX(fr, tx, layout.prefixColW);
            drawVerticalDirectionRow(mc, fr, tx, ty, cardinalX, dirCol, shadow, preview);
            ty += lineH;
        }
        if (ConfigManager.isMoreHudCoordsBiome()) {
            ty += BIOME_TOP_GAP_PX;
            drawText(fr, biomeName(mc, preview), tx, ty, biomeCol, shadow);
        }
    }

    private static void drawVerticalDirectionRow(Minecraft mc, FontRenderer fr, int headX, int dirY, int cardinalX,
            int dirCol, boolean shadow, boolean preview) {
        int lineH = fr.FONT_HEIGHT;
        int headSize = lineH;
        if (ConfigManager.isMoreHudCoordsShowFace()) {
            drawPlayerHead(mc, headX, dirY, headSize);
        }
        int cardY = dirY + Math.max(0, (headSize - lineH) / 2) + VERTICAL_CARDINAL_Y_NUDGE;
        drawText(fr, cardinalDirection(mc, preview), cardinalX, cardY, dirCol, shadow);
    }

    /** Facing label X when it shares a column with axis prefixes (vertical layout). */
    private static int verticalDirectionCardinalX(FontRenderer fr, int headX, int prefixColW) {
        int valueAligned = axisValueX(fr, headX, prefixColW);
        if (!ConfigManager.isMoreHudCoordsShowFace()) {
            return valueAligned;
        }
        return Math.max(valueAligned, headX + fr.FONT_HEIGHT + VALUE_GAP);
    }

    private static int verticalDirectionRowWidth(FontRenderer fr, int prefixColW, int padLeft) {
        int headX = padLeft;
        return verticalDirectionCardinalX(fr, headX, prefixColW) - headX + fr.getStringWidth("NW");
    }

    /** Compass ring only (no coord rows or biome). */
    private static void drawCompassOnly(Minecraft mc, FontRenderer fr, int x, int y, int cardinalCol, int ringCol,
            int dotCol, boolean shadow, boolean preview) {
        Layout layout = measureLayout(mc, fr, preview);
        sanitizeCompassNudgeForLayout(fr, layout);
        CompassGeom geom = measureCompassGeomForLayout(fr, layout);
        drawCompass(mc, fr, x, y, geom, cardinalCol, ringCol, dotCol, shadow, preview);
    }

    /** Compass + coords column (replaces Powns inline horizontal). */
    private static void drawCompassLayout(Minecraft mc, FontRenderer fr, int x, int y, Layout layout, String sx,
            String sy, String sz, int prefixCol, int textCol, int biomeCol, int cardinalCol, int ringCol, int dotCol,
            boolean shadow, boolean preview) {
        int tx = x + layout.padLeft;
        int ty = y + layout.padUp;
        int valueX = axisValueX(fr, tx, layout.prefixColW);
        sanitizeCompassNudgeForLayout(fr, layout);
        CompassGeom geom = measureCompassGeomForLayout(fr, layout);

        List<DepthPass> passes = new ArrayList<DepthPass>(4);
        passes.add(new DepthPass(DRAW_COORDS, DEPTH_TIE_COORDS, new Runnable() {
            @Override
            public void run() {
                int rowY = ty;
                rowY = drawAxisRow(fr, tx, valueX, rowY, "X", sx, prefixCol, textCol, shadow);
                rowY = drawAxisRow(fr, tx, valueX, rowY, "Y", sy, prefixCol, textCol, shadow);
                rowY = drawAxisRow(fr, tx, valueX, rowY, "Z", sz, prefixCol, textCol, shadow);
                if (ConfigManager.isMoreHudCoordsBiome()) {
                    drawText(fr, biomeName(mc, preview), tx, rowY + BIOME_TOP_GAP_PX, biomeCol, shadow);
                }
            }
        }));
        appendCompassDepthPasses(mc, fr, x, y, geom, cardinalCol, ringCol, dotCol, shadow, preview, passes);
        runDepthPasses(passes);
    }

    /** Correct saved nudges that would draw the compass over the coords text column. */
    private static void sanitizeCompassNudgeForLayout(FontRenderer fr, Layout layout) {
        if (ConfigManager.isMoreHudCoordsVertical() || ConfigManager.isMoreHudCoordsCompassOnly()) {
            return;
        }
        int[] bounds = compassNudgeBoundsUnscaled(fr, layout);
        int nx = clampInt(ConfigManager.getMoreHudCoordsCompassNudgeX(), bounds[0], bounds[1]);
        int ny = clampInt(ConfigManager.getMoreHudCoordsCompassNudgeY(), bounds[2], bounds[3]);
        int[] clamped = clampCompassNudgeAvoidTextOverlap(fr, layout, nx, ny);
        if (clamped[0] != ConfigManager.getMoreHudCoordsCompassNudgeX()
                || clamped[1] != ConfigManager.getMoreHudCoordsCompassNudgeY()) {
            ConfigManager.setMoreHudCoordsCompassNudge(clamped[0], clamped[1]);
        }
    }

    private static void drawCompass(Minecraft mc, FontRenderer fr, int anchorX, int anchorY, CompassGeom geom,
            int cardinalCol, int ringCol, int dotCol, boolean shadow, boolean preview) {
        List<DepthPass> passes = new ArrayList<DepthPass>(3);
        appendCompassDepthPasses(mc, fr, anchorX, anchorY, geom, cardinalCol, ringCol, dotCol, shadow, preview,
                passes);
        runDepthPasses(passes);
        resetHudTextureColor();
    }

    private static void appendCompassDepthPasses(Minecraft mc, FontRenderer fr, int anchorX, int anchorY,
            final CompassGeom geom, final int cardinalCol, final int ringCol, final int dotCol, final boolean shadow,
            final boolean preview, List<DepthPass> passes) {
        final int ringRadius = geom.radius + ConfigManager.getMoreHudCoordsCompassRingRadiusOffset();
        final int dotOrbitExtra = ConfigManager.getMoreHudCoordsCompassDotOrbitOffset();
        final CompassGeom screenGeom = new CompassGeom(anchorX + geom.cx, anchorY + geom.cy, geom.radius, geom.boundsW,
                geom.boundsH, geom.lineH);
        final float cx = screenGeom.cx;
        final float cy = screenGeom.cy;
        final int headSize = fr.FONT_HEIGHT;
        passes.add(new DepthPass(DRAW_RING, DEPTH_TIE_RING, new Runnable() {
            @Override
            public void run() {
                HudGlState.restoreForOverlayText();
                drawCompassRing(cx, cy, ringRadius, ringCol);
            }
        }));
        passes.add(new DepthPass(DRAW_DOT, DEPTH_TIE_DOT, new Runnable() {
            @Override
            public void run() {
                HudGlState.restoreForOverlayText();
                drawCompassHeadingDot(cx, cy, ringRadius, resolveYaw(mc, preview), dotCol, dotOrbitExtra);
                if (ConfigManager.isMoreHudCoordsShowFace()) {
                    resetHudTextureColor();
                    HudGlState.restoreForOverlayText();
                    GlStateManager.disableDepth();
                    GlStateManager.depthMask(false);
                    drawPlayerHead(mc, Math.round(cx - headSize / 2.0F), Math.round(cy - headSize / 2.0F), headSize);
                    GlStateManager.depthMask(true);
                    GlStateManager.enableDepth();
                }
            }
        }));
        passes.add(new DepthPass(DRAW_CARDINALS, DEPTH_TIE_CARDINAL, new Runnable() {
            @Override
            public void run() {
                HudGlState.restoreForOverlayText();
                drawCompassCardinals(fr, screenGeom, cardinalCol, shadow);
            }
        }));
    }

    public static boolean usesCompassRingLayout() {
        return ConfigManager.isMoreHudCoordsCompassLayout() || ConfigManager.isMoreHudCoordsCompassOnly();
    }

    private static void runDepthPasses(List<DepthPass> passes) {
        Collections.sort(passes, new Comparator<DepthPass>() {
            @Override
            public int compare(DepthPass a, DepthPass b) {
                int c = Integer.compare(a.depth, b.depth);
                return c != 0 ? c : Integer.compare(a.tie, b.tie);
            }
        });
        for (DepthPass pass : passes) {
            pass.draw.run();
        }
    }

    private static final class DepthPass {
        final int depth;
        final int tie;
        final Runnable draw;

        DepthPass(int depth, int tie, Runnable draw) {
            this.depth = depth;
            this.tie = tie;
            this.draw = draw;
        }
    }

    /**
     * Derived from coord rows: {@code radius = yZ - yX}, {@code cy = yZ + lineH/2} so W/E sit on the Z row
     * and {@code cy - radius = yX + lineH/2} so N sits on the X row. {@code cx = compassLeft + radius}.
     */
    private static int compassLayoutPadPx() {
        int ringOut = Math.max(0, ConfigManager.getMoreHudCoordsCompassRingRadiusOffset());
        int cardOut = Math.max(0, ConfigManager.getMoreHudCoordsCompassCardinalOutset());
        int dotOut = Math.max(0, ConfigManager.getMoreHudCoordsCompassDotOrbitOffset());
        return ringOut + Math.max(cardOut, dotOut);
    }

    private static CompassGeom measureCompassGeom(FontRenderer fr, int compassLeft, int yX, int yZ) {
        int lineH = fr.FONT_HEIGHT;
        int radius = Math.max(4, yZ - yX);
        float cy = yZ + lineH / 2.0F + ConfigManager.getMoreHudCoordsCompassNudgeY();
        float cx = compassLeft + COMPASS_INNER_PAD + radius + ConfigManager.getMoreHudCoordsCompassNudgeX();
        int twMax = Math.max(fr.getStringWidth("N"), Math.max(fr.getStringWidth("W"), fr.getStringWidth("E")));
        int layoutPad = compassLayoutPadPx();
        int top = Math.round(cy - radius - lineH / 2.0F) - layoutPad;
        int bottom = Math.round(cy + radius + lineH / 2.0F) + layoutPad;
        int boundsW = COMPASS_INNER_PAD * 2 + radius * 2 + twMax + layoutPad * 2;
        int boundsH = Math.max(lineH * 3, bottom - top + COMPASS_INNER_PAD);
        return new CompassGeom(cx, cy, radius, boundsW, boundsH, lineH);
    }

    private static final class CompassGeom {
        final float cx;
        final float cy;
        final int radius;
        final int boundsW;
        final int boundsH;
        final int lineH;

        CompassGeom(float cx, float cy, int radius, int boundsW, int boundsH, int lineH) {
            this.cx = cx;
            this.cy = cy;
            this.radius = radius;
            this.boundsW = boundsW;
            this.boundsH = boundsH;
            this.lineH = lineH;
        }
    }

    /** Cardinals on the ring; positive outset pushes each letter away from the center. */
    private static void drawCompassCardinals(FontRenderer fr, CompassGeom geom, int dirCol, boolean shadow) {
        float cx = geom.cx;
        float cy = geom.cy;
        int r = geom.radius + ConfigManager.getMoreHudCoordsCompassCardinalOutset();
        int lineH = geom.lineH;
        int halfH = lineH / 2;
        int twN = fr.getStringWidth("N");
        int twE = fr.getStringWidth("E");
        int twS = fr.getStringWidth("S");
        int twW = fr.getStringWidth("W");
        int rowY = Math.round(cy - halfH);
        drawText(fr, "N", Math.round(cx - twN / 2.0F) + COMPASS_NS_X_NUDGE, Math.round(cy - r - halfH), dirCol,
                shadow);
        drawText(fr, "S", Math.round(cx - twS / 2.0F) + COMPASS_NS_X_NUDGE, Math.round(cy + r - halfH), dirCol,
                shadow);
        drawText(fr, "E", Math.round(cx + r - twE / 2.0F), rowY, dirCol, shadow);
        drawText(fr, "W", Math.round(cx - r - twW / 2.0F) + COMPASS_W_X_NUDGE, rowY, dirCol, shadow);
    }

    private static float resolveYaw(Minecraft mc, boolean preview) {
        if (preview) {
            return (System.currentTimeMillis() % 360000L) / 1000.0F;
        }
        return mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0F;
    }

    /** Filled annulus — avoids GL thick-line miter gaps at the diagonal (NW/NE/SW/SE) joints. */
    private static void drawCompassRing(float cx, float cy, int radius, int ringCol) {
        int ringRgb = ringCol & 0xFFFFFF;
        float r = ((ringRgb >> 16) & 0xFF) / 255f;
        float g = ((ringRgb >> 8) & 0xFF) / 255f;
        float b = (ringRgb & 0xFF) / 255f;
        float half = COMPASS_RING_LINE_WIDTH * 0.5f;
        float outerR = radius + half;
        float innerR = Math.max(0.5f, radius - half);

        beginHudVectorDraw();
        GlStateManager.color(r, g, b, 0.95f);
        int segments = 96;
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            GL11.glVertex2f(cx + cos * outerR, cy + sin * outerR);
            GL11.glVertex2f(cx + cos * innerR, cy + sin * innerR);
        }
        GL11.glEnd();
        endHudVectorDraw();
    }

    /**
     * Heading marker on the ring (0° = north at top); orbit follows the ring centerline.
     */
    private static void drawCompassHeadingDot(float cx, float cy, int ringRadius, float mcYaw, int dotRgb,
            int orbitExtraPx) {
        float degFromNorth = mcYaw - 180.0F;
        float a = (float) Math.toRadians(degFromNorth);
        float sin = (float) Math.sin(a);
        float cos = (float) Math.cos(a);
        float orbitR = ringRadius + COMPASS_RING_LINE_WIDTH * 0.5f + orbitExtraPx;
        float dotX = cx + sin * orbitR;
        float dotY = cy - cos * orbitR;
        int fill = 0xFF000000 | (dotRgb & 0xFFFFFF);
        int px = Math.round(dotX);
        int py = Math.round(dotY);
        int pr = Math.max(2, Math.min(3, (ringRadius + 4) / 9));

        HudGlState.restoreForOverlayText();
        GlStateManager.enableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        drawDisc1px(px, py, pr + 1, 0xFF000000);
        drawDisc1px(px, py, pr, fill);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        resetHudTextureColor();
    }

    private static void drawDisc1px(int centerX, int centerY, int radius, int argb) {
        if (radius <= 0) {
            return;
        }
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= r2) {
                    Gui.drawRect(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, argb);
                }
            }
        }
    }

    private static void beginHudVectorDraw() {
        HudGlState.restoreForOverlayText();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    private static void endHudVectorDraw() {
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        resetHudTextureColor();
    }

    /** Ring/dot vector draws use tinted GL color; textured heads must be drawn at white. */
    private static void resetHudTextureColor() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawFilledCircle(float cx, float cy, float radius, int argb) {
        int ir = Math.max(1, Math.round(radius));
        if (radius <= 0f) {
            return;
        }
        int alpha = (argb >> 24) & 0xFF;
        if (alpha == 0) {
            argb |= 0xFF000000;
            alpha = 0xFF;
        }
        float a = alpha / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        beginHudVectorDraw();
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, a);
        GL11.glVertex2f(cx, cy);
        int segments = Math.max(64, ir * 16);
        for (int i = 0; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            GL11.glColor4f(r, g, b, a);
            GL11.glVertex2f(cx + (float) Math.cos(ang) * radius, cy + (float) Math.sin(ang) * radius);
        }
        GL11.glEnd();
        endHudVectorDraw();
        resetHudTextureColor();
    }

    private static int drawAxisRow(FontRenderer fr, int prefixX, int valueX, int y, String axis, String value,
            int prefixCol, int textCol, boolean shadow) {
        String tag = formatAxisTag(axis);
        drawText(fr, tag, prefixX, y, prefixCol, shadow);
        int gap = valueGapAfterPrefix();
        int vx = tag.isEmpty() ? prefixX : Math.max(valueX, prefixX + fr.getStringWidth(tag) + gap);
        drawText(fr, value, vx, y, textCol, shadow);
        return y + fr.FONT_HEIGHT;
    }

    /**
     * Powns-style face from the local player's bound skin atlas (respects {@link LocalDisguise} via mixins).
     * Falls back to a session-keyed {@code minotar.net/skin/…} download when no player skin is available.
     */
    private static void drawPlayerHead(Minecraft mc, int x, int y, int size) {
        ResourceLocation skin = resolveHudSkin(mc);
        if (skin == null) {
            return;
        }
        HudGlState.restoreForOverlayText();
        resetHudTextureColor();
        GlStateManager.enableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.getTextureManager().bindTexture(skin);
        float atlas = skinAtlasDimension(mc, skin);
        Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, size, size, atlas, atlas);
        Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, size, size, atlas, atlas);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        resetHudTextureColor();
    }

    private static ResourceLocation resolveHudSkin(Minecraft mc) {
        ResourceLocation live = resolveLocalPlayerHudSkin(mc);
        if (live != null) {
            return live;
        }
        return resolveSessionHudSkin(mc);
    }

    /** Same skin path as the rendered local player (disguise / tab overrides). */
    private static ResourceLocation resolveLocalPlayerHudSkin(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            if (mc.thePlayer != null) {
                ResourceLocation skin = mc.thePlayer.getLocationSkin();
                if (skin != null) {
                    return skin;
                }
            }
        } catch (Throwable ignored) {
        }
        if (LocalDisguise.shouldApplySkinOverride()) {
            ResourceLocation disguiseSkin = LocalDisguise.getSkinLocation();
            if (disguiseSkin != null) {
                return disguiseSkin;
            }
        }
        return null;
    }

    private static ResourceLocation resolveSessionHudSkin(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return null;
        }
        GameProfile profile = mc.thePlayer.getGameProfile();
        if (profile == null) {
            return null;
        }
        UUID sessionId = profile.getId();
        if (sessionId == null) {
            return null;
        }
        String sessionName = profile.getName() != null ? profile.getName() : "";
        if (hudSkinLocation != null && sessionId.equals(hudSkinSessionId)
                && sessionName.equalsIgnoreCase(hudSkinSessionName != null ? hudSkinSessionName : "")) {
            return resolveSessionHudSkinForDraw(mc);
        }
        deleteSessionHudSkinTextures(mc);
        hudSkinSessionId = sessionId;
        hudSkinSessionName = sessionName;
        hudSkinGpuLocation = null;
        hudSkinLocation = downloadSessionHudSkin(mc, sessionId, sessionName);
        return resolveSessionHudSkinForDraw(mc);
    }

    private static ResourceLocation resolveSessionHudSkinForDraw(Minecraft mc) {
        ResourceLocation gpu = tryPromoteHudSkinToGpu(mc);
        if (gpu != null) {
            return gpu;
        }
        return hudSkinLocation;
    }

    private static ResourceLocation downloadSessionHudSkin(Minecraft mc, UUID sessionId, String sessionName) {
        String pathKey = sessionId.toString().replace("-", "");
        ResourceLocation rl = new ResourceLocation("gomod123", "coords_hud_skin/" + pathKey);
        String url = buildSessionSkinUrl(sessionId, sessionName);
        try {
            TextureManager tm = mc.getTextureManager();
            tm.loadTexture(rl, new ThreadDownloadImageData(null, url, null, null));
            return rl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildSessionSkinUrl(UUID sessionId, String sessionName) {
        if (sessionName != null && !sessionName.isEmpty()) {
            try {
                String enc = URLEncoder.encode(sessionName, StandardCharsets.UTF_8.name()).replace("+", "%20");
                return "https://minotar.net/skin/" + enc;
            } catch (Throwable ignored) {
            }
        }
        String undashed = sessionId.toString().replace("-", "");
        return "https://crafatar.com/skins/" + undashed + "?default=MHF_Steve";
    }

    private static ResourceLocation tryPromoteHudSkinToGpu(Minecraft mc) {
        if (mc == null || hudSkinLocation == null || hudSkinSessionId == null) {
            return hudSkinGpuLocation;
        }
        if (hudSkinGpuLocation != null
                && mc.getTextureManager().getTexture(hudSkinGpuLocation) instanceof DynamicTexture) {
            return hudSkinGpuLocation;
        }
        ITextureObject tex = mc.getTextureManager().getTexture(hudSkinLocation);
        if (!(tex instanceof ThreadDownloadImageData)) {
            return hudSkinGpuLocation;
        }
        BufferedImage img = readBufferedImageField((ThreadDownloadImageData) tex);
        if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
            return null;
        }
        Boolean uploaded = readBooleanField(tex, "textureUploaded", "field_110559_g");
        if (!Boolean.TRUE.equals(uploaded)) {
            return null;
        }
        try {
            String pathKey = hudSkinSessionId.toString().replace("-", "");
            ResourceLocation gpuRl = new ResourceLocation("gomod123", "coords_hud_gpu/" + pathKey);
            mc.getTextureManager().deleteTexture(gpuRl);
            mc.getTextureManager().loadTexture(gpuRl, new DynamicTexture(copyBufferedImage(img)));
            hudSkinGpuLocation = gpuRl;
            return gpuRl;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BufferedImage copyBufferedImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copy.getGraphics().drawImage(src, 0, 0, null);
        return copy;
    }

    private static float skinAtlasDimension(Minecraft mc, ResourceLocation skin) {
        if (mc == null || skin == null) {
            return SKIN_ATLAS_SIZE;
        }
        ITextureObject tex = mc.getTextureManager().getTexture(skin);
        if (tex instanceof ThreadDownloadImageData) {
            BufferedImage img = readBufferedImageField((ThreadDownloadImageData) tex);
            if (img != null) {
                return Math.max(img.getWidth(), img.getHeight());
            }
        }
        return SKIN_ATLAS_SIZE;
    }

    private static void deleteSessionHudSkinTextures(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (hudSkinLocation != null) {
            try {
                mc.getTextureManager().deleteTexture(hudSkinLocation);
            } catch (Throwable ignored) {
            }
        }
        if (hudSkinGpuLocation != null) {
            try {
                mc.getTextureManager().deleteTexture(hudSkinGpuLocation);
            } catch (Throwable ignored) {
            }
        }
        try {
            Field mapField = TextureManager.class.getDeclaredField("mapTextureObjects");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ResourceLocation, ITextureObject> map =
                    (Map<ResourceLocation, ITextureObject>) mapField.get(mc.getTextureManager());
            if (map == null) {
                return;
            }
            List<ResourceLocation> stale = new ArrayList<ResourceLocation>();
            for (ResourceLocation rl : map.keySet()) {
                if (rl != null && "gomod123".equals(rl.getResourceDomain())
                        && rl.getResourcePath() != null
                        && (rl.getResourcePath().startsWith("coords_hud_skin/")
                                || rl.getResourcePath().startsWith("coords_hud_avatar/")
                                || rl.getResourcePath().startsWith("coords_hud_gpu/"))) {
                    stale.add(rl);
                }
            }
            for (ResourceLocation rl : stale) {
                try {
                    mc.getTextureManager().deleteTexture(rl);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Boolean readBooleanField(Object obj, String... names) {
        for (String name : names) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof Boolean) {
                    return (Boolean) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static BufferedImage readBufferedImageField(ThreadDownloadImageData dl) {
        for (String name : new String[]{"bufferedImage", "field_110560_d"}) {
            try {
                Field f = dl.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(dl);
                if (v instanceof BufferedImage) {
                    return (BufferedImage) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int geomLeft(CompassGeom geom) {
        return Math.round(geom.cx - geom.boundsW / 2.0F);
    }

    private static int geomTop(CompassGeom geom) {
        return Math.round(geom.cy - geom.boundsH / 2.0F);
    }

    private static int geomRight(CompassGeom geom) {
        return Math.round(geom.cx + geom.boundsW / 2.0F);
    }

    private static int geomBottom(CompassGeom geom) {
        return Math.round(geom.cy + geom.boundsH / 2.0F);
    }

    /** Shift compass so its bounding box top-left is {@code (alignLeft, alignTop)}. */
    private static CompassGeom alignCompassGeomTo(CompassGeom geom, int alignLeft, int alignTop) {
        float dx = alignLeft - geomLeft(geom);
        float dy = alignTop - geomTop(geom);
        return new CompassGeom(geom.cx + dx, geom.cy + dy, geom.radius, geom.boundsW, geom.boundsH, geom.lineH);
    }

    private static void drawBackgroundRect(int leftX, int topY, int uLeft, int uTop, int uRight, int uBottom, int bg) {
        if (bg <= 0 || uRight <= uLeft || uBottom <= uTop) {
            return;
        }
        HudEditorBackgroundMask.drawRect(leftX + uLeft, topY + uTop, leftX + uRight, topY + uBottom,
                MoreHudBackgroundColors.coordsPixel(bg));
    }

    /** Unscaled bounds {@code [left, top, right, bottom]} relative to widget anchor for the X/Y/Z text block. */
    private static void textBlockUnscaledBounds(FontRenderer fr, Layout layout, int[] out) {
        if (ConfigManager.isMoreHudCoordsVertical()) {
            out[0] = 0;
            out[1] = 0;
            out[2] = layout.width;
            out[3] = layout.height;
            return;
        }
        // Vertical+Compass: text-column opacity matches compass opacity width when coords are narrow.
        out[0] = 0;
        out[1] = VERTICAL_BG_TOP_INSET;
        int textRight = layout.padLeft + layout.coordsBlockW + VERTICAL_VALUE_RIGHT_PAD + layout.padRight;
        out[2] = Math.max(textRight, compassPartBackgroundWidthForCombinedLayout(fr, layout));
        out[3] = layout.padUp + layout.coordsBlockH + layout.padDown;
    }

    /** Unscaled bounds for the compass ring (horizontal / compass-only layouts). */
    private static void compassPartUnscaledBounds(CompassGeom geom, int[] out) {
        out[0] = geomLeft(geom) - COMPASS_BG_MARGIN;
        out[1] = geomTop(geom) - COMPASS_BG_MARGIN;
        out[2] = geomRight(geom) + COMPASS_BG_MARGIN;
        out[3] = geomBottom(geom) + COMPASS_BG_MARGIN;
    }

    /** Opacity-box width of the compass ring in Vertical+Compass (same geom as live draw). */
    private static int compassPartBackgroundWidthForCombinedLayout(FontRenderer fr, Layout layout) {
        int lineH = fr.FONT_HEIGHT;
        int compassLeft = layout.padLeft + layout.coordsBlockW + COMPASS_GAP;
        int yX = layout.padUp;
        int yZ = layout.padUp + 2 * lineH;
        CompassGeom geom = measureCompassGeom(fr, compassLeft, yX, yZ);
        int[] bb = new int[4];
        compassPartUnscaledBounds(geom, bb);
        return bb[2] - bb[0];
    }

    private static CompassGeom measureCompassGeomForLayout(FontRenderer fr, Layout layout) {
        int lineH = fr.FONT_HEIGHT;
        int yX = layout.padUp;
        int yZ = layout.padUp + 2 * lineH;
        int compassLeft = compassLeftUnscaled(layout);
        CompassGeom geom = measureCompassGeom(fr, compassLeft, yX, yZ);
        if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            geom = alignCompassGeomTo(geom, layout.padLeft, layout.padUp);
        }
        return geom;
    }

    private static void applyBgSizeFromExtents(Layout layout, HudOpacitySliderBoxInsets.TextBackgroundPad bgPad,
            int innerTop, int innerRight, int innerBottom) {
        layout.width = innerRight + bgPad.right;
        layout.height = (innerBottom - innerTop) + bgPad.up + bgPad.down;
    }

    private static Layout measureLayout(Minecraft mc, FontRenderer fr, boolean preview) {
        Layout layout = new Layout();
        layout.prefixColW = prefixColumnWidth(fr);
        layout.valueColW = valueColumnWidth(fr, mc, preview);
        int lineH = fr.FONT_HEIGHT;
        layout.coordsBlockW = coordsTextBlockWidth(fr, layout.valueColW);
        int coordsRowsH = 3 * lineH;
        if (ConfigManager.isMoreHudCoordsVertical()) {
            if (ConfigManager.isMoreHudCoordsDirection()) {
                coordsRowsH += lineH;
            }
            if (ConfigManager.isMoreHudCoordsBiome()) {
                coordsRowsH += BIOME_TOP_GAP_PX + lineH;
            }
        } else if (ConfigManager.isMoreHudCoordsBiome()) {
            coordsRowsH += BIOME_TOP_GAP_PX + lineH;
        }
        layout.coordsBlockH = coordsRowsH;

        HudOpacitySliderBoxInsets.TextBackgroundPad bgPad =
                HudOpacitySliderBoxInsets.coordsHudBackgroundPad(ConfigManager.isMoreHudCoordsShadow());
        layout.padLeft = bgPad.left;
        layout.padUp = bgPad.up;
        layout.padRight = bgPad.right;
        layout.padDown = bgPad.down;

        if (ConfigManager.isMoreHudCoordsVertical()) {
            int innerW = layout.coordsBlockW + VERTICAL_VALUE_RIGHT_PAD;
            layout.width = bgPad.left + innerW + bgPad.right;
            layout.height = bgPad.up + layout.coordsBlockH + bgPad.down;
            if (ConfigManager.isMoreHudCoordsDirection()) {
                int dirRowW = verticalDirectionRowWidth(fr, layout.prefixColW, bgPad.left);
                layout.width = Math.max(layout.width, bgPad.left + dirRowW + VERTICAL_VALUE_RIGHT_PAD + bgPad.right);
            }
        } else if (ConfigManager.isMoreHudCoordsCompassOnly()) {
            int compassLeft = bgPad.left;
            int yX = bgPad.up;
            int yZ = bgPad.up + 2 * lineH;
            CompassGeom geom = alignCompassGeomTo(measureCompassGeom(fr, compassLeft, yX, yZ), bgPad.left, bgPad.up);
            layout.coordsBlockW = 0;
            layout.coordsBlockH = 0;
            layout.width = geomRight(geom) + COMPASS_BG_MARGIN + bgPad.right;
            layout.height = geomBottom(geom) + COMPASS_BG_MARGIN + bgPad.down;
        } else {
            int compassLeft = bgPad.left + layout.coordsBlockW + COMPASS_GAP;
            int yX = bgPad.up;
            int yZ = bgPad.up + 2 * lineH;
            CompassGeom geom = measureCompassGeom(fr, compassLeft, yX, yZ);
            int innerTop = Math.min(bgPad.up, geomTop(geom));
            int innerRight = Math.max(bgPad.left + layout.coordsBlockW, geomRight(geom));
            int innerBottom = Math.max(bgPad.up + layout.coordsBlockH, geomBottom(geom));
            applyBgSizeFromExtents(layout, bgPad, innerTop, innerRight, innerBottom);
        }
        layout.width = Math.max(24, layout.width);
        layout.height = Math.max(lineH + bgPad.up + bgPad.down, layout.height);
        return layout;
    }

    /** Axis label before the numeric value, e.g. {@code Y:}, {@code (Y)}, {@code [Y]}. */
    public static String formatAxisTag(String axis) {
        PrefixStyle style = prefixStyleFromConfig();
        switch (style) {
            case PARENTHESES:
                return "(" + axis + ")";
            case SQUARE_BRACKETS:
                return "[" + axis + "]";
            case NONE:
                return "";
            case ARROW_GT:
                return axis + ">";
            case REGULAR_NO_COLON:
                return axis + " ";
            case REGULAR:
            default:
                return axis + ":";
        }
    }

    private static int valueGapAfterPrefix() {
        PrefixStyle style = prefixStyleFromConfig();
        if (style == PrefixStyle.REGULAR_NO_COLON) {
            return 0;
        }
        return VALUE_GAP;
    }

    /** @deprecated use {@link #formatAxisTag(String)} */
    public static String formatPrefix(String axis) {
        return formatAxisTag(axis);
    }

    public static PrefixStyle prefixStyleFromConfig() {
        int i = ConfigManager.getMoreHudCoordsPrefixStyle();
        PrefixStyle[] values = PrefixStyle.values();
        if (i < 0 || i >= values.length) {
            return PrefixStyle.REGULAR;
        }
        return values[i];
    }

    public static String formatCoord(double value) {
        return COORDINATE_FORMATS.get().format(value, ConfigManager.getMoreHudCoordsDecimals());
    }

    private static final class CoordinateFormats {
        // Layout and drawing both format X, Y and Z. Retain their labels while the values are stable.
        private final long[] values = new long[3];
        private final String[] labels = new String[3];
        private int next;
        private int decimals = -1;
        private Locale integerLocale;
        private DecimalFormat integerFormat;

        private String format(double value, int precision) {
            Locale locale = precision <= 0 ? Locale.getDefault(Locale.Category.FORMAT) : null;
            if (precision != decimals || !java.util.Objects.equals(locale, integerLocale)) {
                for (int i = 0; i < labels.length; i++) labels[i] = null;
                next = 0;
                decimals = precision;
                integerLocale = locale;
                if (locale != null) integerFormat = new DecimalFormat("##");
            }
            long bits = Double.doubleToLongBits(value);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == bits && labels[i] != null) return labels[i];
            }
            String label = precision <= 0 ? integerFormat.format(value)
                    : BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).toPlainString();
            values[next] = bits;
            labels[next] = label;
            next = (next + 1) % labels.length;
            return label;
        }
    }

    private static int prefixColumnWidth(FontRenderer fr) {
        return Math.max(fr.getStringWidth(formatAxisTag("X")),
                Math.max(fr.getStringWidth(formatAxisTag("Y")), fr.getStringWidth(formatAxisTag("Z"))));
    }

    private static int valueColumnWidth(FontRenderer fr, Minecraft mc, boolean preview) {
        double px = preview ? PREVIEW_X : (mc != null && mc.thePlayer != null ? mc.thePlayer.posX : PREVIEW_X);
        double py = preview ? PREVIEW_Y : (mc != null && mc.thePlayer != null ? mc.thePlayer.posY : PREVIEW_Y);
        double pz = preview ? PREVIEW_Z : (mc != null && mc.thePlayer != null ? mc.thePlayer.posZ : PREVIEW_Z);
        String sx = formatCoord(px);
        String sy = formatCoord(py);
        String sz = formatCoord(pz);
        int actualW = Math.max(fr.getStringWidth(sx), Math.max(fr.getStringWidth(sy), fr.getStringWidth(sz)));
        int minW = fr.getStringWidth(verticalMinDigitWidthSample());
        return Math.max(actualW, minW);
    }

    /** Sample string for minimum value column width (five integer digits + decimals). */
    private static String verticalMinDigitWidthSample() {
        int decimals = ConfigManager.getMoreHudCoordsDecimals();
        if (decimals <= 0) {
            return "10000";
        }
        StringBuilder sb = new StringBuilder("10000.");
        for (int i = 0; i < decimals; i++) {
            sb.append('0');
        }
        return sb.toString();
    }

    private static int coordsTextBlockWidth(FontRenderer fr, int valueColW) {
        return prefixColumnWidth(fr) + valueGapAfterPrefix() + valueColW;
    }

    private static String biomeName(Minecraft mc, boolean preview) {
        if (preview) {
            return "Plains";
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return "";
        }
        BlockPos pos = new BlockPos(player.posX, player.posY, player.posZ);
        BiomeGenBase biome = mc.theWorld.getBiomeGenForCoords(pos);
        return biome != null && biome.biomeName != null ? biome.biomeName : "";
    }

    private static int axisValueX(FontRenderer fr, int prefixX, int prefixColW) {
        String tag = formatAxisTag("X");
        int valueX = prefixX + prefixColW;
        if (tag != null && !tag.isEmpty()) {
            valueX = Math.max(valueX, prefixX + fr.getStringWidth(tag) + valueGapAfterPrefix());
        }
        return valueX;
    }

    private static String cardinalDirection(Minecraft mc, boolean preview) {
        if (preview) {
            return "NE";
        }
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return "N";
        }
        int yaw = Math.round(player.rotationYaw + 180.0F);
        while (yaw < 0) {
            yaw += 360;
        }
        int sector = 360 / DIRECTIONS.length;
        int index = (yaw + sector / 2) / sector;
        return DIRECTIONS[index % DIRECTIONS.length];
    }

    private static void drawText(FontRenderer fr, String text, int x, int y, int color, boolean shadow) {
        if (text == null || text.isEmpty()) {
            return;
        }
        HudGlState.restoreForOverlayText();
        int rgb = color & 0xFFFFFF;
        if (shadow) {
            fr.drawStringWithShadow(text, x, y, rgb);
        } else {
            fr.drawString(text, x, y, rgb);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static final class Layout {
        int width;
        int height;
        int prefixColW;
        int valueColW;
        int coordsBlockW;
        int coordsBlockH;
        int padLeft;
        int padUp;
        int padRight;
        int padDown;
    }
}
