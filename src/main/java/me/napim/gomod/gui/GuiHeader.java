package me.napim.gomod.gui;

import me.napim.gomod.features.MixinStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Renders the gomod header (icon + text) at the top-left of GUIs.
 */
public final class GuiHeader {
    /** RGB for {@link EnumChatFormatting#DARK_GREEN} — "gomod123" header / menu branding. */
    public static final int GOMOD_BRAND_RGB = 0x00AA00;

    private static final ResourceLocation ICON = new ResourceLocation("gomod123:textures/gomodicon1.png");
    private static final ResourceLocation ICON_FALLBACK = new ResourceLocation("gomod123:runtime/gomodicon1");
    private static boolean triedLoad = false;
    private static boolean loaded = false;
    private static boolean usingFallback = false;
    private static int iconW = 16;
    private static int iconH = 16;
    private static final int ICON_SHADOW_OFFSET_X = 2;
    private static final int ICON_SHADOW_OFFSET_Y = 2;
    private static final float ICON_SHADOW_ALPHA = 0.65f;
    private static final int BRAND_PAD = 4;
    private static final int BRAND_ICON_DRAW = 24;
    private static final int BRAND_TEXT_GAP = 4;
    private static final int BRAND_TEXT_Y_OFFSET = 6;
    private static final String BRAND_LABEL =
            EnumChatFormatting.DARK_GREEN + "" + EnumChatFormatting.BOLD + "gomod123" + EnumChatFormatting.RESET;

    private GuiHeader() {}

    private static void ensureLoaded() {
        if (triedLoad) return;
        triedLoad = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc != null && mc.getResourceManager() != null) {
                // Check if packaged resource exists
                try {
                    mc.getResourceManager().getResource(ICON);
                    loaded = true;
                    usingFallback = false;
                    System.out.println("[gomod] GUI header icon found: " + ICON);
                    return;
                } catch (Exception e) {
                    System.out.println("[gomod] GUI header icon NOT found via ResourceManager: " + ICON);
                }
            }

            // Fallback: try to load from classpath and register a DynamicTexture
            try {
                java.io.InputStream stream = null;
                if (stream == null) stream = GuiHeader.class.getResourceAsStream("/assets/gomod123/textures/gomodicon1.png");
                if (stream == null) stream = GuiHeader.class.getResourceAsStream("/assets/gomod/textures/gomodicon1.png");
                if (stream != null && mc != null && mc.getTextureManager() != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(stream);
                    if (img != null) {
                        iconW = img.getWidth();
                        iconH = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                        mc.getTextureManager().loadTexture(ICON_FALLBACK, dyn);
                        loaded = true;
                        usingFallback = true;
                        System.out.println("[gomod] GUI header icon loaded via DynamicTexture fallback: " + iconW + "x" + iconH);
                        try { stream.close(); } catch (Exception ignored) {}
                        return;
                    }
                }
                System.out.println("[gomod] GUI header icon fallback load failed (classpath stream null or decode failed)");
            } catch (Throwable t) {
                System.out.println("[gomod] GUI header icon fallback error: " + t.getMessage());
            }
        } catch (Throwable t) {
            System.out.println("[gomod] GUI header icon ensureLoaded error: " + t.getMessage());
        }
        loaded = false;
    }

    public static void drawTopLeft(Gui gui) {
        drawBrandAt(gui, BRAND_PAD, BRAND_PAD);
    }

    /**
     * HUD editor: default top-left branding, but move to bottom-left when a module rect covers it.
     *
     * @param moduleRects {@code [left, top, right, bottom]} screen regions for each HUD module
     */
    public static void drawTopLeftHudEditor(GuiScreen gui, List<int[]> moduleRects) {
        Minecraft mc = Minecraft.getMinecraft();
        if (gui == null || mc == null || mc.fontRendererObj == null) {
            return;
        }
        FontRenderer fr = mc.fontRendererObj;
        int bw = brandWidth(fr);
        int bh = brandHeight(fr);
        int x = BRAND_PAD;
        int y = BRAND_PAD;
        if (brandOverlapsAny(x, y, bw, bh, moduleRects, 2)) {
            y = Math.max(BRAND_PAD, gui.height - bh - BRAND_PAD);
        }
        drawBrandAt(gui, x, y);
    }

    public static int brandWidth(FontRenderer fr) {
        if (fr == null) {
            return BRAND_ICON_DRAW + BRAND_TEXT_GAP + 48;
        }
        return BRAND_ICON_DRAW + BRAND_TEXT_GAP + fr.getStringWidth(BRAND_LABEL);
    }

    public static int brandHeight(FontRenderer fr) {
        if (fr == null) {
            return BRAND_ICON_DRAW;
        }
        return Math.max(BRAND_ICON_DRAW, fr.FONT_HEIGHT + BRAND_TEXT_Y_OFFSET);
    }

    private static boolean brandOverlapsAny(int x, int y, int w, int h, List<int[]> moduleRects, int margin) {
        if (moduleRects == null || moduleRects.isEmpty()) {
            return false;
        }
        int bl = x;
        int bt = y;
        int br = x + w;
        int bb = y + h;
        for (int[] m : moduleRects) {
            if (m == null || m.length < 4) {
                continue;
            }
            int ml = m[0] - margin;
            int mt = m[1] - margin;
            int mr = m[2] + margin;
            int mb = m[3] + margin;
            if (bl < mr && br > ml && bt < mb && bb > mt) {
                return true;
            }
        }
        return false;
    }

    public static void drawBrandAt(Gui gui, int x, int y) {
        ensureLoaded();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || gui == null) {
            return;
        }

        GlStateManager.pushMatrix();
        try {
            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);

            if (loaded) {
                try {
                    if (usingFallback) {
                        mc.getTextureManager().bindTexture(ICON_FALLBACK);
                    } else {
                        mc.getTextureManager().bindTexture(ICON);
                    }
                    GlStateManager.color(0f, 0f, 0f, ICON_SHADOW_ALPHA);
                    Gui.drawScaledCustomSizeModalRect(x + ICON_SHADOW_OFFSET_X, y + ICON_SHADOW_OFFSET_Y,
                            0, 0, iconW, iconH, BRAND_ICON_DRAW, BRAND_ICON_DRAW, iconW, iconH);
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, iconW, iconH, BRAND_ICON_DRAW, BRAND_ICON_DRAW, iconW, iconH);
                } catch (Throwable t) {
                    System.out.println("[gomod] GUI header icon bind/draw error: " + t.getMessage());
                }
            } else {
                System.out.println("[gomod] GUI header icon not loaded; drawing text only");
            }

            mc.fontRendererObj.drawStringWithShadow(BRAND_LABEL, x + BRAND_ICON_DRAW + BRAND_TEXT_GAP, y + BRAND_TEXT_Y_OFFSET,
                    0xFFFFFF);
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }
    }

    // Helper for other GUIs: bind the header icon with fallbacks.
    public static boolean bindIconForDraw() {
        ensureLoaded();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) return false;
            if (usingFallback) {
                mc.getTextureManager().bindTexture(ICON_FALLBACK);
            } else {
                mc.getTextureManager().bindTexture(ICON);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Pixels from the scaled screen bottom to the bottom edge of bottom-bar {@code Back} / {@code Back to Menu}
     * buttons — matches {@link RadialMenuWheelSlotsGUI} (sits just above the XP / hotbar strip).
     */
    public static final int BACK_BUTTON_BOTTOM_MARGIN_FROM_SCREEN = 30;

    /**
     * Top Y for a bottom bar button of height {@code buttonHeight} aligned with the wheel-slots editor.
     */
    public static int backButtonY(int scaledScreenHeight, int buttonHeight) {
        return scaledScreenHeight - BACK_BUTTON_BOTTOM_MARGIN_FROM_SCREEN - buttonHeight;
    }

    /**
     * One pixel below the last row that should stay clear of the bottom back button (for scrollbars / flowing content).
     */
    public static int contentBottomAboveBackBar(int scaledScreenHeight, int buttonHeight, int gapPx) {
        return backButtonY(scaledScreenHeight, buttonHeight) - gapPx;
    }

    /**
     * Redraws bottom-bar buttons (Back / Back to Menu) after HUD opacity color-picker overlays so they stay
     * visible and clickable when the picker row is near the bottom of the screen.
     */
    public static void redrawBackBarButtonsOnTop(Minecraft mc, List<GuiButton> buttons, int mouseX, int mouseY,
            int scaledScreenHeight, int buttonHeight) {
        if (mc == null || buttons == null || buttons.isEmpty()) {
            return;
        }
        int rowY = backButtonY(scaledScreenHeight, buttonHeight);
        for (GuiButton button : buttons) {
            if (button != null && button.visible && button.yPosition == rowY) {
                button.drawButton(mc, mouseX, mouseY);
            }
        }
    }

    /** Primary centered module/settings title Y (matches {@link MiscellaneousGUI}). */
    public static final int STANDARD_TITLE_Y = 20;

    /** Second header line Y for subtitles / descriptions (matches {@link MiscellaneousGUI}). */
    public static final int STANDARD_SUBTITLE_Y = 40;

    /** Third header line Y (status text under subtitle). */
    public static final int STANDARD_STATUS_Y = 55;

    /** Gap between {@link #STANDARD_TITLE_Y} baseline row and the start of main content when no subtitle is used. */
    public static int standardContentTopAfterTitle(FontRenderer fr) {
        int fh = fr != null ? fr.FONT_HEIGHT : 9;
        return STANDARD_TITLE_Y + fh + 8;
    }

    /** Gap between {@link #STANDARD_SUBTITLE_Y} row and dense content (lists, previews). */
    public static int standardContentTopAfterSubtitle(FontRenderer fr) {
        int fh = fr != null ? fr.FONT_HEIGHT : 9;
        return STANDARD_SUBTITLE_Y + fh + 6;
    }

    /** Gap between paired "edit in HUD editor" / reset half-width buttons (total row width stays one full button). */
    public static final int HUD_POSITION_BUTTON_PAIR_GAP_PX = 4;

    /** Total width of a side-by-side Position X + Y field row (matches a full settings button). */
    public static final int HUD_POSITION_FIELDS_TOTAL_W = 120;

    /** Width of one X or Y field in a split pair ({@link #HUD_POSITION_FIELDS_TOTAL_W} + gap). */
    public static final int HUD_POSITION_FIELD_HALF_W =
            (HUD_POSITION_FIELDS_TOTAL_W - HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;

    /** Height of Position X/Y fields (matches standard {@code GuiButton} height). */
    public static final int HUD_POSITION_FIELD_HEIGHT = 20;

    /**
     * Position X/Y numeric field with a 1px inset border so the control matches adjacent buttons
     * (no outward stroke).
     */
    public static void drawHudPositionField(FontRenderer fr, int x, int y, int width, int height, String text,
            boolean editing) {
        if (fr == null || text == null) {
            return;
        }
        int borderColor = 0xFFFFFFFF;
        int fillColor = 0xFF000000;
        Gui.drawRect(x, y, x + width, y + height, fillColor);
        Gui.drawRect(x, y, x + width, y + 1, borderColor);
        Gui.drawRect(x, y + height - 1, x + width, y + height, borderColor);
        Gui.drawRect(x, y, x + 1, y + height, borderColor);
        Gui.drawRect(x + width - 1, y, x + width, y + height, borderColor);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fillColor);
        int textPad = width >= 60 ? 5 : 3;
        fr.drawString(text, x + textPad, y + 6, 0xFFFFFF);
        if (editing && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int caretX = x + textPad + fr.getStringWidth(text);
            Gui.drawRect(caretX, y + 4, caretX + 1, y + height - 4, borderColor);
        }
    }

    /** Opens {@link GomodHudEditorGUI} from module settings (drag overlays, corner handles to resize). */
    public static final String HUD_EDITOR_EDIT_BUTTON = EnumChatFormatting.AQUA + "Edit";

    public static final String HUD_EDITOR_RESET_BUTTON = EnumChatFormatting.GRAY + "Reset";

    public static int getIconWidth() { return iconW; }
    public static int getIconHeight() { return iconH; }

    /**
     * Centered title text for gomod GUIs ({@code viewportWidth} = scaled screen width).
     */
    public static void drawTitleCenteredWithShadow(FontRenderer fr, String text, int viewportWidth, int y, int color) {
        drawScreenCenteredText(fr, text, viewportWidth, y, color);
    }

    /**
     * Same as {@link #drawTitleCenteredWithShadow} but {@code centerX} is already the horizontal center.
     */
    public static void drawTitleCenteredAtWithShadow(FontRenderer fr, String text, int centerX, int y, int color) {
        drawCenteredWithShadow(fr, text, centerX, y, color);
    }

    /** @deprecated Use {@link #drawScreenCenteredText}; kept as an alias. */
    public static void drawTitleCenteredLikeScreenLabel(FontRenderer fr, String text, int screenWidth, int y, int color) {
        drawScreenCenteredText(fr, text, screenWidth, y, color);
    }

    /**
     * Centers text on the scaled screen midpoint ({@link ScaledResolution#getScaledWidth()} when available).
     */
    public static void drawScreenCenteredText(FontRenderer fr, String text, int screenWidth, int y, int color) {
        if (fr == null || text == null) {
            return;
        }
        drawCenteredWithShadow(fr, text, resolveScreenCenterX(screenWidth), y, color);
    }

    private static int resolveScreenCenterX(int screenWidth) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                return new ScaledResolution(mc).getScaledWidth() / 2;
            }
        } catch (Throwable ignored) {
        }
        return screenWidth / 2;
    }

    /**
     * Yellow + bold module title (e.g. {@code HUD Editor}, {@code HUD Editor - CPS}).
     */
    public static void drawYellowBoldTitleCentered(FontRenderer fr, String plainText, int screenWidth, int y) {
        drawColoredBoldTitleCentered(fr, plainText, EnumChatFormatting.YELLOW, screenWidth, y);
    }

    /**
     * Bold title with an optional color prefix; width is measured on plain text + style codes only.
     */
    public static void drawColoredBoldTitleCentered(FontRenderer fr, String plainText,
            EnumChatFormatting color, int screenWidth, int y) {
        if (fr == null || plainText == null || plainText.isEmpty()) {
            return;
        }
        String rendered = (color != null ? color.toString() : "")
                + EnumChatFormatting.BOLD + plainText;
        drawCenteredWithShadow(fr, rendered, resolveScreenCenterX(screenWidth), y, 0xFFFFFF);
    }

    /**
     * Measure and draw with the same {@link FontRenderer} path (no disguise-skip mismatch).
     */
    private static void drawCenteredWithShadow(FontRenderer fr, String text, int centerX, int y, int color) {
        if (fr == null || text == null) {
            return;
        }
        boolean prevSkip = MixinStatus.skipFontRendererDisguiseReplace;
        MixinStatus.skipFontRendererDisguiseReplace = true;
        try {
            int w = measureFormattedStringWidth(fr, text);
            int x = Math.round(centerX - w / 2.0f);
            fr.drawStringWithShadow(text, x, y, color);
        } finally {
            MixinStatus.skipFontRendererDisguiseReplace = prevSkip;
        }
    }

    /**
     * Matches 1.8.9 {@link FontRenderer#getStringWidth(String)} formatting behaviour (bold adds +1 per glyph).
     * Use for centering formatted GUI text (titles, disguise kill brackets, etc.).
     */
    public static int getFormattedStringWidth(FontRenderer fr, String text) {
        return measureFormattedStringWidth(fr, text);
    }

    /**
     * X for text centered in a {@link net.minecraft.client.gui.GuiButton} using vanilla
     * {@link FontRenderer#getStringWidth} (matches {@link FontRenderer#drawStringWithShadow} layout).
     * Prefer this over {@code centerX - getFormattedStringWidth/2} for narrow toggle value cells.
     */
    public static int getButtonCenteredTextX(FontRenderer fr, int buttonX, int buttonWidth, String text) {
        if (fr == null || text == null || text.isEmpty()) {
            return buttonX;
        }
        boolean prevSkip = me.napim.gomod.features.MixinStatus.skipFontRendererDisguiseReplace;
        me.napim.gomod.features.MixinStatus.skipFontRendererDisguiseReplace = true;
        try {
            int sw = fr.getStringWidth(text);
            return buttonX + (buttonWidth - sw + 1) / 2;
        } finally {
            me.napim.gomod.features.MixinStatus.skipFontRendererDisguiseReplace = prevSkip;
        }
    }

    private static int measureFormattedStringWidth(FontRenderer fr, String text) {
        if (fr == null || text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                int code = "0123456789abcdefklmnor".indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (code < 16) {
                    bold = false;
                } else if (code == 17) {
                    bold = true;
                } else if (code == 20) {
                    bold = false;
                }
                i++;
                continue;
            }
            int charW = fr.getCharWidth(c);
            if (charW < 0) {
                continue;
            }
            width += charW;
            if (bold) {
                width++;
            }
        }
        return width;
    }

    /**
     * Bottom bar label for HUD module settings: short {@code Back} when opened from {@link GomodHudEditorGUI},
     * {@code Back to Menu} when returning to the main mod menu (or other non-editor parent).
     */
    public static String moduleSettingsBackButtonLabel(GuiScreen returnOrParent) {
        return (returnOrParent instanceof GomodHudEditorGUI)
                ? EnumChatFormatting.GRAY + "Back"
                : EnumChatFormatting.GRAY + "Back to Menu";
    }
}


