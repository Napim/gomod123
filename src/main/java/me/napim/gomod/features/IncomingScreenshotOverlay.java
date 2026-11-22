package me.napim.gomod.features;

import me.napim.gomod.gui.HudEditorModuleTransition;
import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.gui.MoreHudSettingsGUI;
import me.napim.gomod.gui.ScreenshotViewerGUI;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import org.lwjgl.input.Mouse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * Non-blocking toast for incoming global-chat screenshots: top-right slide-in, configurable in HUD editor.
 */
public final class IncomingScreenshotOverlay {

    private static final int BASE_IMG_W = 150;
    private static final int BASE_IMG_H = 90;
    private static final int PAD = 4;
    private static final int HEADER_EXTRA = 22;
    /** Clickable Open / Save / Hide strip under the preview. */
    private static final int FOOTER_ACTION_H = 14;
    /** Extra pixels below vertical center for footer button labels. */
    private static final int FOOTER_LABEL_Y_OFFSET = 2;
    private static final long INTRO_MS = 420L;
    private static final long FADE_OUT_MS = 520L;
    private static final long HOLD_MS = 6_000L;

    private static volatile IncomingScreenshotManager.IncomingScreenshot activeShot;
    private static volatile ResourceLocation activeTexture;
    private static volatile int imgW = 1;
    private static volatile int imgH = 1;
    private static volatile long showStartMs;
    private static volatile long fadeOutStartMs;
    private static volatile int textureLoadGeneration;

    private static volatile ResourceLocation editorPreviewTexture;
    private static volatile int editorPreviewImgW = 1;
    private static volatile int editorPreviewImgH = 1;
    private static volatile int editorPreviewLoadGeneration;
    private static volatile boolean editorPreviewLoadStarted;

    private static boolean prevMouseLeftToast;
    private static long lastToastPointerActionMs;

    public IncomingScreenshotOverlay() {}

    public static void show(final IncomingScreenshotManager.IncomingScreenshot shot) {
        if (shot == null || shot.bytes == null || shot.bytes.length == 0) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        final int gen = ++textureLoadGeneration;
        clearInternal(mc, true);
        final byte[] payload = shot.bytes;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedImage img = null;
                try {
                    img = ImageIO.read(new ByteArrayInputStream(payload));
                    if (img != null) {
                        img = ScreenshotImageTextureUtil.toArgbCopy(img);
                    }
                } catch (Throwable ignored) {
                }
                final BufferedImage decoded = img;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != textureLoadGeneration) {
                            return;
                        }
                        if (decoded == null) {
                            return;
                        }
                        ResourceLocation rl = ScreenshotImageTextureUtil.registerDynamicTexture(mc, decoded, "gc_in");
                        if (rl == null) {
                            return;
                        }
                        activeShot = shot;
                        activeTexture = rl;
                        imgW = Math.max(1, decoded.getWidth());
                        imgH = Math.max(1, decoded.getHeight());
                        showStartMs = System.currentTimeMillis();
                        fadeOutStartMs = 0L;
                    }
                });
            }
        }, "gomod-incoming-screenshot-decode").start();
    }

    private static void clearInternal(Minecraft mc, boolean deleteTexture) {
        ResourceLocation rl = activeTexture;
        activeShot = null;
        activeTexture = null;
        imgW = 1;
        imgH = 1;
        fadeOutStartMs = 0L;
        if (deleteTexture && mc != null && mc.getTextureManager() != null && rl != null) {
            mc.getTextureManager().deleteTexture(rl);
        }
    }

    public static void dismiss(Minecraft mc) {
        clearInternal(mc, true);
    }

    /** Clears cached HUD-editor preview texture (reloads on next editor preview draw). */
    public static void resetEditorPreviewCache(Minecraft mc) {
        editorPreviewLoadStarted = false;
        editorPreviewLoadGeneration++;
        ResourceLocation rl = editorPreviewTexture;
        editorPreviewTexture = null;
        editorPreviewImgW = 1;
        editorPreviewImgH = 1;
        if (mc != null && mc.getTextureManager() != null && rl != null) {
            mc.getTextureManager().deleteTexture(rl);
        }
    }

    public static boolean isShowing() {
        return activeShot != null && activeTexture != null;
    }

    private static final class PanelMetrics {
        final int panelW;
        final int panelH;
        final int pad;
        final int headerH;
        final int imgAreaH;
        final int footerActionH;
        final int slidePx;

        PanelMetrics(int panelW, int panelH, int pad, int headerH, int imgAreaH,
                int footerActionH, int slidePx) {
            this.panelW = panelW;
            this.panelH = panelH;
            this.pad = pad;
            this.headerH = headerH;
            this.imgAreaH = imgAreaH;
            this.footerActionH = footerActionH;
            this.slidePx = slidePx;
        }
    }

    /**
     * All regions use the same scaled pixel sizes so preview, footer, and hit-tests line up at any HUD scale.
     */
    private static PanelMetrics metrics(Minecraft mc, boolean editorPreview) {
        return metricsForScale(ConfigManager.getIncomingScreenshotHudScale(), editorPreview);
    }

    private static PanelMetrics metricsForScale(float sc, boolean editorPreview) {
        int pad = Math.max(2, Math.round(PAD * sc));
        int approxFh = Math.max(8, Math.round(9.0f * sc));
        int headMax = Math.max(8, Math.min(18, Math.round(10.0f * sc)));
        int headerNeed = pad + approxFh + 2 + Math.max(approxFh, headMax) + 2;
        int headerH = Math.max(Math.max(22, Math.round(HEADER_EXTRA * sc)), headerNeed);
        int imgAreaH = Math.max(28, Math.round(BASE_IMG_H * sc));
        int footerActionH = Math.max(12, Math.round(FOOTER_ACTION_H * sc));
        int panelW = Math.max(48, Math.round((BASE_IMG_W + PAD * 2) * sc));
        int panelH = headerH + pad + imgAreaH + pad + footerActionH;

        int slidePx = 0;
        if (!editorPreview && showStartMs > 0L) {
            long now = System.currentTimeMillis();
            float t = (now - showStartMs) / (float) INTRO_MS;
            if (t < 1.0F) {
                float u = 1.0F - (1.0F - Math.min(1.0F, t)) * (1.0F - Math.min(1.0F, t));
                slidePx = Math.round((1.0F - u) * 28.0F);
            }
        }
        return new PanelMetrics(panelW, panelH, pad, headerH, imgAreaH, footerActionH, slidePx);
    }

    /** Panel size for an arbitrary toast scale (used by More HUD settings gallery at 1.0). */
    public static void incomingGalleryPanelSize(float hudScale, int[] outW_H) {
        if (outW_H == null || outW_H.length < 2) {
            return;
        }
        PanelMetrics m = metricsForScale(hudScale, true);
        outW_H[0] = m.panelW;
        outW_H[1] = m.panelH;
    }

    /**
     * Draws the editor-style incoming-screenshot toast at explicit coordinates without touching stored HUD position.
     */
    public static void renderGalleryAt(Minecraft mc, int drawLeft, int drawTop, float hudScale) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        IncomingScreenshotManager.IncomingScreenshot shot =
                new IncomingScreenshotManager.IncomingScreenshot("PreviewPlayer", "preview.png", "image/png", new byte[0], 0L);
        PanelMetrics m = metricsForScale(hudScale, true);
        int panelW = m.panelW;
        int panelH = m.panelH;
        float alpha = 0.92F;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);

        int bg = (int) (200 * alpha) << 24 | 0x101010;
        Gui.drawRect(drawLeft, drawTop, drawLeft + panelW, drawTop + panelH, bg);
        drawPanelOuterBorder(drawLeft, drawTop, panelW, panelH);

        int headSize = Math.max(8, Math.min(18, Math.round(10.0f * hudScale)));
        int titleX = drawLeft + m.pad;
        int titleY = drawTop + m.pad;
        String title = EnumChatFormatting.LIGHT_PURPLE + "Screenshot";
        HudModuleTextDraw.draw(mc.fontRendererObj, title, titleX, titleY, 0xFFFFFF,
                ConfigManager.isIncomingScreenshotTextShadow());

        int fh = mc.fontRendererObj.FONT_HEIGHT;
        int nameRowY = drawTop + m.pad + fh + 2;
        int headY = nameRowY + Math.max(0, (fh - headSize) / 2);
        int headX = drawLeft + m.pad;
        InventoryHUD.drawFriendHudPreviewHead(mc, headX, headY, headSize);
        String previewPeer = EnumChatFormatting.AQUA + InventoryHUD.getFriendHudPreviewDisplayName();
        int nameX = headX + headSize + 4;
        HudModuleTextDraw.draw(mc.fontRendererObj, previewPeer, nameX, nameRowY, 0xFFFFFF,
                ConfigManager.isIncomingScreenshotTextShadow());

        int innerTop = drawTop + m.headerH;
        int innerH = m.imgAreaH;
        int innerW = m.panelW - m.pad * 2;
        Gui.drawRect(drawLeft + m.pad, innerTop, drawLeft + m.pad + innerW, innerTop + innerH, 0xFF000000);
        drawInnerPreviewImage(mc, drawLeft + m.pad, innerTop, innerW, innerH, alpha);

        int footTop = innerTop + innerH;
        int seg = Math.max(1, innerW / 3);
        int bx = drawLeft + m.pad;
        for (int i = 0; i < 3; i++) {
            int x0 = bx + i * seg;
            int x1 = bx + (i + 1) * seg - 1;
            int bgBtn = 0x50202020;
            Gui.drawRect(x0, footTop, x1, footTop + m.footerActionH, bgBtn);
            Gui.drawRect(x0, footTop, x1, footTop + 1, 0x80FFFFFF);
            String lab = i == 0 ? EnumChatFormatting.AQUA + "Open"
                    : i == 1 ? EnumChatFormatting.GREEN + "Save"
                    : EnumChatFormatting.GRAY + "Hide";
            int lw = mc.fontRendererObj.getStringWidth(lab);
            int labelY = footTop + (m.footerActionH - mc.fontRendererObj.FONT_HEIGHT) / 2 + FOOTER_LABEL_Y_OFFSET;
            mc.fontRendererObj.drawString(lab, x0 + (seg - lw) / 2, labelY, 0xFFFFFF);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    public static int panelWidthPx(Minecraft mc) {
        return metrics(mc, false).panelW;
    }

    public static int panelHeightPx(Minecraft mc) {
        return metrics(mc, false).panelH;
    }

    public static int resolveLeft(Minecraft mc) {
        if (mc == null) {
            return 0;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int w = panelWidthPx(mc);
        int stored = ConfigManager.getIncomingScreenshotHudPosX();
        if (stored < 0) {
            return Math.max(4, sr.getScaledWidth() - w - 6);
        }
        return Math.max(0, Math.min(sr.getScaledWidth() - w - 2, stored));
    }

    public static int resolveTop(Minecraft mc) {
        if (mc == null) {
            return 0;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int h = panelHeightPx(mc);
        int y = ConfigManager.getIncomingScreenshotHudPosY();
        return Math.max(2, Math.min(sr.getScaledHeight() - h - 2, y));
    }

    public static void setHudPosition(int left, int top) {
        ConfigManager.setIncomingScreenshotHudPosX(left);
        ConfigManager.setIncomingScreenshotHudPosY(top);
    }

    private static int scaledMouseX(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return Mouse.getX() * sr.getScaledWidth() / Math.max(1, mc.displayWidth);
    }

    private static int scaledMouseY(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return sr.getScaledHeight() - (Mouse.getY() * sr.getScaledHeight() / Math.max(1, mc.displayHeight)) - 1;
    }

    private static void applyToastPointerZone(Minecraft mc, int zone) {
        if (zone == 1 || zone == 2) {
            mc.displayGuiScreen(new ScreenshotViewerGUI(activeShot,
                    mc.currentScreen instanceof GuiMainMenu ? mc.currentScreen : null));
            dismiss(mc);
        } else if (zone == 3) {
            try {
                IncomingScreenshotManager.saveToSharedScreenshots(activeShot);
            } catch (Exception ignored) {
            }
            dismiss(mc);
        } else if (zone == 4) {
            dismiss(mc);
        }
    }

    private static void pollToastMouseClick(Minecraft mc) {
        boolean down = Mouse.isButtonDown(0);
        if (!isToastInteractionScreen(mc)) {
            // In normal gameplay the grabbed mouse still reports button presses and coordinates.
            // Never let an attack/use click activate the toast's Open / Save / Hide hitboxes.
            prevMouseLeftToast = down;
            return;
        }
        if (down && !prevMouseLeftToast) {
            int zone = hitTestActions(mc, scaledMouseX(mc), scaledMouseY(mc));
            if (zone != 0) {
                long now = System.currentTimeMillis();
                if (now - lastToastPointerActionMs >= 350L) {
                    lastToastPointerActionMs = now;
                    applyToastPointerZone(mc, zone);
                }
            }
        }
        prevMouseLeftToast = down;
    }

    /**
     * @return 0 none, 1 open (preview), 2 open (footer), 3 save, 4 hide
     */
    private static int hitTestActions(Minecraft mc, int mouseX, int mouseY) {
        if (!isShowing() || !isToastInteractionScreen(mc)) {
            return 0;
        }
        PanelMetrics m = metrics(mc, false);
        int left = resolveLeft(mc);
        int top = resolveTop(mc);
        int drawLeft = left + m.slidePx;
        int drawTop = top;
        if (mouseX < drawLeft || mouseY < drawTop || mouseX >= drawLeft + m.panelW || mouseY >= drawTop + m.panelH) {
            return 0;
        }
        int innerTop = drawTop + m.headerH;
        int innerW = m.panelW - m.pad * 2;
        int innerH = m.imgAreaH;
        int innerLeft = drawLeft + m.pad;
        if (mouseX >= innerLeft && mouseX < innerLeft + innerW
                && mouseY >= innerTop && mouseY < innerTop + innerH) {
            return 1;
        }
        int footTop = innerTop + innerH;
        if (mouseY < footTop || mouseY >= footTop + m.footerActionH) {
            return 0;
        }
        int seg = Math.max(1, innerW / 3);
        int relX = mouseX - innerLeft;
        if (relX < seg) {
            return 2;
        }
        if (relX < seg * 2) {
            return 3;
        }
        return 4;
    }

    /**
     * @return -1 none, 0 Open, 1 Save, 2 Hide (matches footer segment index).
     */
    private static int resolveHoveredFooterSegment(Minecraft mc, boolean hudEditorPreview, int drawLeft, int drawTop,
            PanelMetrics m, int mouseX, int mouseY) {
        if (mc == null) {
            return -1;
        }
        int innerTop = drawTop + m.headerH;
        int innerW = m.panelW - m.pad * 2;
        int innerH = m.imgAreaH;
        int innerLeft = drawLeft + m.pad;
        int footTop = innerTop + innerH;
        if (mouseY < footTop || mouseY >= footTop + m.footerActionH) {
            return -1;
        }
        if (mouseX < innerLeft || mouseX >= innerLeft + innerW) {
            return -1;
        }
        if (!hudEditorPreview) {
            if (!isShowing()) {
                return -1;
            }
            int z = hitTestActions(mc, mouseX, mouseY);
            if (z == 2) {
                return 0;
            }
            if (z == 3) {
                return 1;
            }
            if (z == 4) {
                return 2;
            }
            return -1;
        }
        int seg = Math.max(1, innerW / 3);
        int relX = mouseX - innerLeft;
        if (relX < seg) {
            return 0;
        }
        if (relX < seg * 2) {
            return 1;
        }
        return 2;
    }

    private static boolean isToastInteractionScreen(Minecraft mc) {
        return GomodSocialMainMenuNotifications.isMenuScreen(mc, mc == null ? null : mc.currentScreen)
                || (mc != null && mc.currentScreen instanceof GuiChat);
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("IncomingScreenshotOverlay")) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (!isShowing()) {
            prevMouseLeftToast = Mouse.isButtonDown(0);
            return;
        }

        if (ConfigManager.isIncomingScreenshotOverlayEnabled()) {
            pollToastMouseClick(mc);
        }
        if (!isShowing()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (fadeOutStartMs <= 0L && now - showStartMs >= HOLD_MS) {
            fadeOutStartMs = now;
        }
        if (fadeOutStartMs > 0L && now - fadeOutStartMs >= FADE_OUT_MS) {
            dismiss(mc);
        }
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (!ClientTickHub.isFeatureEnabled("IncomingScreenshotOverlay")) {
            return;
        }
        if (!ConfigManager.isIncomingScreenshotOverlayEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        if (HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT)) {
            return;
        }
        render(mc, false);
    }

    public static void render(Minecraft mc, boolean hudEditorPreview) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        IncomingScreenshotManager.IncomingScreenshot shot = activeShot;
        ResourceLocation tex = activeTexture;
        if (!hudEditorPreview && (shot == null || tex == null)) {
            return;
        }
        if (hudEditorPreview) {
            shot = new IncomingScreenshotManager.IncomingScreenshot("PreviewPlayer", "preview.png", "image/png", new byte[0], 0L);
            tex = null;
        }

        PanelMetrics m = metrics(mc, hudEditorPreview);
        int panelW = m.panelW;
        int panelH = m.panelH;
        int left = resolveLeft(mc);
        int top = resolveTop(mc);

        long now = System.currentTimeMillis();
        float intro = 1.0F;
        if (!hudEditorPreview && showStartMs > 0L) {
            float t = (now - showStartMs) / (float) INTRO_MS;
            if (t < 1.0F) {
                float u = 1.0F - (1.0F - Math.min(1.0F, t)) * (1.0F - Math.min(1.0F, t));
                intro = u;
            }
        }
        float alpha = intro;
        if (!hudEditorPreview && fadeOutStartMs > 0L) {
            float ft = (now - fadeOutStartMs) / (float) FADE_OUT_MS;
            alpha *= Math.max(0.0F, 1.0F - Math.min(1.0F, ft));
        }
        if (hudEditorPreview) {
            alpha = 0.92F;
        }

        int drawLeft = left + m.slidePx;
        int drawTop = top;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);

        int bg = (int) (200 * alpha) << 24 | 0x101010;
        HudEditorBackgroundMask.drawRect(drawLeft, drawTop, drawLeft + panelW, drawTop + panelH, bg);
        if (!hudEditorPreview) {
            drawPanelOuterBorder(drawLeft, drawTop, panelW, panelH);
        }

        float scHud = ConfigManager.getIncomingScreenshotHudScale();
        int headSize = Math.max(8, Math.min(18, Math.round(10.0f * scHud)));
        int titleX = drawLeft + m.pad;
        int titleY = drawTop + m.pad;
        String title = EnumChatFormatting.LIGHT_PURPLE + "Screenshot";
        HudModuleTextDraw.draw(mc.fontRendererObj, title, titleX, titleY, 0xFFFFFF,
                ConfigManager.isIncomingScreenshotTextShadow());

        int fh = mc.fontRendererObj.FONT_HEIGHT;
        int nameRowY = drawTop + m.pad + fh + 2;
        int headY = nameRowY + Math.max(0, (fh - headSize) / 2);
        int headX = drawLeft + m.pad;
        if (hudEditorPreview) {
            InventoryHUD.drawFriendHudPreviewHead(mc, headX, headY, headSize);
        } else if (shot.sender != null && !shot.sender.isEmpty()) {
            InventoryHUD.drawIncomingScreenshotSenderHead(mc, shot.sender, headX, headY, headSize);
        }
        String previewPeer = EnumChatFormatting.AQUA + InventoryHUD.getFriendHudPreviewDisplayName();
        String from = hudEditorPreview ? previewPeer : EnumChatFormatting.GRAY + shot.sender;
        int nameX = headX + headSize + 4;
        HudModuleTextDraw.draw(mc.fontRendererObj, from, nameX, nameRowY, 0xFFFFFF,
                ConfigManager.isIncomingScreenshotTextShadow());

        int innerTop = drawTop + m.headerH;
        int mx = scaledMouseX(mc);
        int my = scaledMouseY(mc);
        int hoveredFooterSeg = resolveHoveredFooterSegment(mc, hudEditorPreview, drawLeft, drawTop, m, mx, my);

        int innerH = m.imgAreaH;
        int innerW = m.panelW - m.pad * 2;
        Gui.drawRect(drawLeft + m.pad, innerTop, drawLeft + m.pad + innerW, innerTop + innerH, 0xFF000000);

        if (!hudEditorPreview && tex != null) {
            drawScaledTextureInInner(mc, tex, drawLeft + m.pad, innerTop, innerW, innerH, imgW, imgH, alpha);
        } else if (hudEditorPreview) {
            drawInnerPreviewImage(mc, drawLeft + m.pad, innerTop, innerW, innerH, alpha);
        }

        int footTop = innerTop + innerH;
        int seg = Math.max(1, innerW / 3);
        int bx = drawLeft + m.pad;
        for (int i = 0; i < 3; i++) {
            int x0 = bx + i * seg;
            int x1 = bx + (i + 1) * seg - 1;
            boolean ho = hoveredFooterSeg == i;
            int bgBtn = hudEditorPreview ? (ho ? 0x78A8A8A8 : 0x50202020) : (ho ? 0x98B8B8B8 : 0x70303030);
            Gui.drawRect(x0, footTop, x1, footTop + m.footerActionH, bgBtn);
            Gui.drawRect(x0, footTop, x1, footTop + 1, 0x80FFFFFF);
            String lab = i == 0 ? EnumChatFormatting.AQUA + "Open"
                    : i == 1 ? EnumChatFormatting.GREEN + "Save"
                    : EnumChatFormatting.GRAY + "Hide";
            int lw = mc.fontRendererObj.getStringWidth(lab);
            int labelY = footTop + (m.footerActionH - mc.fontRendererObj.FONT_HEIGHT) / 2 + FOOTER_LABEL_Y_OFFSET;
            mc.fontRendererObj.drawString(lab, x0 + (seg - lw) / 2, labelY, 0xFFFFFF);
        }

        if (!hudEditorPreview) {
            SocialToastProgressBar.draw(drawLeft, drawTop + panelH, panelW, showStartMs, INTRO_MS, HOLD_MS, alpha);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private static void ensureEditorPreviewTexture(Minecraft mc) {
        if (mc == null || editorPreviewLoadStarted) {
            return;
        }
        editorPreviewLoadStarted = true;
        final int gen = ++editorPreviewLoadGeneration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedImage img = null;
                try {
                    File latest = PlayerScreenshotFiles.findLatest(mc);
                    if (latest != null) {
                        img = ImageIO.read(latest);
                        if (img != null) {
                            img = ScreenshotImageTextureUtil.toArgbCopy(img);
                        }
                    }
                } catch (Throwable ignored) {
                }
                final BufferedImage decoded = img;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != editorPreviewLoadGeneration) {
                            return;
                        }
                        if (decoded == null) {
                            return;
                        }
                        ResourceLocation rl = ScreenshotImageTextureUtil.registerDynamicTexture(mc, decoded,
                                "hud_ss_editor_preview");
                        if (rl == null) {
                            return;
                        }
                        editorPreviewTexture = rl;
                        editorPreviewImgW = Math.max(1, decoded.getWidth());
                        editorPreviewImgH = Math.max(1, decoded.getHeight());
                    }
                });
            }
        }, "gomod-hud-editor-screenshot-preview").start();
    }

    private static void drawInnerPreviewImage(Minecraft mc, int innerLeft, int innerTop, int innerW, int innerH,
            float alpha) {
        ensureEditorPreviewTexture(mc);
        if (editorPreviewTexture != null) {
            drawScaledTextureInInner(mc, editorPreviewTexture, innerLeft, innerTop, innerW, innerH,
                    editorPreviewImgW, editorPreviewImgH, alpha);
            return;
        }
        drawNoScreenshotHint(mc, innerLeft, innerTop, innerW, innerH);
    }

    private static void drawNoScreenshotHint(Minecraft mc, int innerLeft, int innerTop, int innerW, int innerH) {
        String hint = EnumChatFormatting.DARK_GRAY + "No screenshot";
        mc.fontRendererObj.drawString(hint,
                innerLeft + (innerW - mc.fontRendererObj.getStringWidth(hint)) / 2,
                innerTop + innerH / 2 - 4, 0x888888);
    }

    private static void drawScaledTextureInInner(Minecraft mc, ResourceLocation tex, int innerLeft, int innerTop,
            int innerW, int innerH, int srcW, int srcH, float alpha) {
        if (tex == null || mc == null || mc.getTextureManager() == null) {
            return;
        }
        mc.getTextureManager().bindTexture(tex);
        int drawW = innerW;
        int drawHi = Math.max(1, (int) ((double) drawW * (double) srcH / (double) srcW));
        if (drawHi > innerH) {
            drawHi = innerH;
            drawW = Math.max(1, (int) ((double) innerH * (double) srcW / (double) srcH));
        }
        int ix = innerLeft + (innerW - drawW) / 2;
        int iy = innerTop + (innerH - drawHi) / 2;
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        Gui.drawScaledCustomSizeModalRect(ix, iy, 0, 0, srcW, srcH, drawW, drawHi, srcW, srcH);
    }

  /** In-game toast chrome; omitted in HUD editor so {@link me.napim.gomod.gui.GomodHudEditorGUI} selection border is the only outline. */
    private static void drawPanelOuterBorder(int left, int top, int panelW, int panelH) {
        if (panelW < 2 || panelH < 2) {
            return;
        }
        Gui.drawRect(left, top, left + panelW, top + 1, 0xA0FFFFFF);
        Gui.drawRect(left, top + panelH - 1, left + panelW, top + panelH, 0xA0FFFFFF);
        Gui.drawRect(left, top + 1, left + 1, top + panelH - 1, 0xA0FFFFFF);
        Gui.drawRect(left + panelW - 1, top + 1, left + panelW, top + panelH - 1, 0xA0FFFFFF);
    }

    public static void getHudBounds(Minecraft mc, int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        int left = resolveLeft(mc);
        int top = resolveTop(mc);
        out[0] = left;
        out[1] = top;
        out[2] = panelWidthPx(mc);
        out[3] = panelHeightPx(mc);
    }
}
