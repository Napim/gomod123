package me.napim.gomod.features;

import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import org.lwjgl.input.Mouse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/** Top-right toast for incoming gomod cape designs. */
public final class IncomingCapeDesignOverlay {
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 78;
    private static final int PAD = 5;
    private static final int PREVIEW_W = 50;
    private static final int PREVIEW_H = 25;
    private static final int FOOTER_H = 16;
    private static final int FOOTER_RAISE_PX = 3;
    private static final long INTRO_MS = 340L;
    private static final long HOLD_MS = 8_000L;
    private static final long FADE_OUT_MS = 520L;

    private static volatile String activeId;
    private static volatile String activeFrom;
    private static volatile String activeLabel;
    private static volatile boolean activeAccepted;
    private static volatile ResourceLocation activeTexture;
    private static volatile long showStartMs;
    private static volatile long fadeOutStartMs;
    private static volatile int generation;
    private static volatile boolean openCapeGuiOnMouseRelease;

    private static boolean prevMouseLeft;
    private static long lastClickMs;

    public IncomingCapeDesignOverlay() {}

    public static void showPending(String id, String from, String label, String imageBase64) {
        show(id, from, label, imageBase64, false);
    }

    public static void showAccepted(String from, String label, String imageBase64) {
        show("", from, label, imageBase64, true);
    }

    private static void show(final String id, String from, String label, String imageBase64, boolean accepted) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        clear(mc, true);
        activeId = id == null ? "" : id;
        activeFrom = from == null ? "" : from.trim();
        activeLabel = label == null || label.trim().isEmpty() ? "Shared cape" : label.trim();
        activeAccepted = accepted;
        showStartMs = System.currentTimeMillis();
        fadeOutStartMs = 0L;
        ShareInvManager.playIncomingInvitePling();
        final int gen = ++generation;
        final String payload = imageBase64;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedImage decoded = null;
                try {
                    byte[] bytes = Base64.getDecoder().decode(payload.trim());
                    decoded = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (decoded != null) {
                        decoded = PlayerCustomCapes.normalizeCapeImage(decoded);
                    }
                } catch (Throwable ignored) {
                }
                final BufferedImage img = decoded;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || img == null) {
                            return;
                        }
                        activeTexture = new ResourceLocation("gomod123", "incoming_cape/" + gen);
                        mc.getTextureManager().loadTexture(activeTexture, new DynamicTexture(img));
                    }
                });
            }
        }, "gomod-incoming-cape-decode").start();
    }

    public static boolean isShowing() {
        return activeFrom != null && !activeFrom.isEmpty();
    }

    public static int panelHeightPx() {
        return PANEL_H;
    }

    public static void dismiss(Minecraft mc) {
        clear(mc, true);
    }

    private static void clear(Minecraft mc, boolean deleteTexture) {
        ResourceLocation old = activeTexture;
        activeId = null;
        activeFrom = null;
        activeLabel = null;
        activeAccepted = false;
        activeTexture = null;
        showStartMs = 0L;
        fadeOutStartMs = 0L;
        openCapeGuiOnMouseRelease = false;
        if (deleteTexture && old != null && mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().deleteTexture(old);
        }
    }

    private static int resolveLeft(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return Math.max(4, sr.getScaledWidth() - PANEL_W - 6);
    }

    private static int resolveTop(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        int top = 6;
        if (IncomingScreenshotOverlay.isShowing()) top += IncomingScreenshotOverlay.panelHeightPx(mc) + 6;
        if (IncomingFriendRequestOverlay.isShowing()) top += IncomingFriendRequestOverlay.panelHeightPx() + 6;
        if (IncomingFriendDmOverlay.isShowing()) top += IncomingFriendDmOverlay.panelHeightPx(mc) + 6;
        if (IncomingPublicPingOverlay.isShowing()) top += IncomingPublicPingOverlay.panelHeightPx(mc) + 6;
        return Math.max(2, Math.min(sr.getScaledHeight() - PANEL_H - 2, top));
    }

    private static int resolveSlidePx(long now) {
        int px = 0;
        if (showStartMs > 0L) {
            float t = (now - showStartMs) / (float) INTRO_MS;
            if (t < 1.0F) {
                float u = 1.0F - (1.0F - Math.min(1.0F, t)) * (1.0F - Math.min(1.0F, t));
                px = Math.round((1.0F - u) * (float) SocialMessageToastLayout.SLIDE_INTRO_PX);
            }
        }
        if (fadeOutStartMs > 0L) {
            float ft = (now - fadeOutStartMs) / (float) FADE_OUT_MS;
            px = Math.round(Math.min(1.0F, Math.max(0.0F, ft)) * (float) SocialMessageToastLayout.SLIDE_INTRO_PX);
        }
        return px;
    }

    private static int scaledMouseX(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return Mouse.getX() * sr.getScaledWidth() / Math.max(1, mc.displayWidth);
    }

    private static int scaledMouseY(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return sr.getScaledHeight() - (Mouse.getY() * sr.getScaledHeight() / Math.max(1, mc.displayHeight)) - 1;
    }

    private static int hitTest(int mx, int my, int left, int top, int slidePx) {
        int x0 = left + slidePx;
        if (mx < x0 || my < top || mx >= x0 + PANEL_W || my >= top + PANEL_H) return 0;
        int footerTop = top + PANEL_H - FOOTER_H - FOOTER_RAISE_PX;
        if (my < footerTop) return activeAccepted ? 1 : 0;
        int innerW = PANEL_W - PAD * 2;
        int segs = activeAccepted ? 2 : 3;
        int seg = Math.max(1, innerW / segs);
        int relX = mx - (x0 + PAD);
        if (activeAccepted) return relX < seg ? 4 : 3;
        if (relX < seg) return 1;
        if (relX < seg * 2) return 2;
        return 3;
    }

    private static void handleClick(Minecraft mc, int action) {
        String id = activeId;
        String from = activeFrom;
        if (action == 1 && !activeAccepted) {
            boolean ok = IncomingCapeDesigns.accept(id);
            if (!ok) {
                me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Failed to save cape design from " + EnumChatFormatting.AQUA + from + ".");
            }
            dismiss(mc);
        } else if (action == 2 && !activeAccepted) {
            IncomingCapeDesigns.decline(id);
            dismiss(mc);
        } else if (action == 3) {
            dismiss(mc);
        } else if (action == 4 || (action == 1 && activeAccepted)) {
            dismiss(mc);
            // This toast polls the physical mouse button outside GuiScreen. Opening immediately hands the held
            // press to the selector and can leave its input state wedged. Open on a later tick after release.
            openCapeGuiOnMouseRelease = true;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("IncomingCapeDesignOverlay")) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        boolean down = Mouse.isButtonDown(0);
        if (openCapeGuiOnMouseRelease && !down) {
            openCapeGuiOnMouseRelease = false;
            prevMouseLeft = false;
            IncomingCapeDesigns.openCapeGui();
            return;
        }
        if (!isShowing()) {
            prevMouseLeft = down;
            return;
        }
        if (down && !prevMouseLeft) {
            long now = System.currentTimeMillis();
            int action = hitTest(scaledMouseX(mc), scaledMouseY(mc), resolveLeft(mc), resolveTop(mc), resolveSlidePx(now));
            if (action != 0 && now - lastClickMs >= 250L) {
                lastClickMs = now;
                handleClick(mc, action);
            }
        }
        prevMouseLeft = down;
        if (!isShowing()) return;
        long now = System.currentTimeMillis();
        if (fadeOutStartMs <= 0L && now - showStartMs >= HOLD_MS) fadeOutStartMs = now;
        if (fadeOutStartMs > 0L && now - fadeOutStartMs >= FADE_OUT_MS) dismiss(mc);
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (!ClientTickHub.isFeatureEnabled("IncomingCapeDesignOverlay")) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || !isShowing()) return;
        render(mc);
    }

    static void render(Minecraft mc) {
        long now = System.currentTimeMillis();
        float alpha = 1.0F;
        if (showStartMs > 0L && fadeOutStartMs <= 0L) {
            float t = (now - showStartMs) / (float) INTRO_MS;
            if (t < 1.0F) alpha = 1.0F - (1.0F - t) * (1.0F - t);
        }
        if (fadeOutStartMs > 0L) {
            float ft = (now - fadeOutStartMs) / (float) FADE_OUT_MS;
            alpha *= Math.max(0.0F, 1.0F - Math.min(1.0F, ft));
        }
        int left = resolveLeft(mc);
        int top = resolveTop(mc);
        int slidePx = resolveSlidePx(now);
        int drawLeft = left + slidePx;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);

        Gui.drawRect(drawLeft, top, drawLeft + PANEL_W, top + PANEL_H, ((int) (210 * alpha) << 24) | 0x101010);
        Gui.drawRect(drawLeft, top, drawLeft + PANEL_W, top + 1, 0xA0FFFFFF);
        Gui.drawRect(drawLeft, top + PANEL_H - 1, drawLeft + PANEL_W, top + PANEL_H, 0xA0FFFFFF);
        Gui.drawRect(drawLeft, top, drawLeft + 1, top + PANEL_H, 0xA0FFFFFF);
        Gui.drawRect(drawLeft + PANEL_W - 1, top, drawLeft + PANEL_W, top + PANEL_H, 0xA0FFFFFF);

        int previewX = drawLeft + PAD;
        int previewY = top + 18;
        ResourceLocation tex = activeTexture;
        if (tex != null) {
            mc.getTextureManager().bindTexture(tex);
            Gui.drawScaledCustomSizeModalRect(previewX, previewY, 0, 0, 64, 32, PREVIEW_W, PREVIEW_H, 64, 32);
        } else {
            Gui.drawRect(previewX, previewY, previewX + PREVIEW_W, previewY + PREVIEW_H, 0x55303030);
        }

        int textX = previewX + PREVIEW_W + 8;
        mc.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD
                + (activeAccepted ? "Cape received" : "Cape design"), textX, top + PAD, 0xFFFFFF);
        mc.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "From: " + EnumChatFormatting.AQUA + activeFrom,
                textX, top + PAD + 11, 0xFFFFFF);
        String label = activeLabel == null ? "Shared cape" : activeLabel;
        while (label.length() > 3 && mc.fontRendererObj.getStringWidth(label) > PANEL_W - textX + drawLeft - PAD) {
            label = label.substring(0, label.length() - 4) + "...";
        }
        mc.fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + label, textX, top + PAD + 22, 0xFFFFFF);

        drawFooter(mc, drawLeft, top, left, slidePx);
        SocialToastProgressBar.draw(drawLeft, top + PANEL_H, PANEL_W, showStartMs, INTRO_MS, HOLD_MS, alpha);

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private static void drawFooter(Minecraft mc, int drawLeft, int top, int left, int slidePx) {
        int footerTop = top + PANEL_H - FOOTER_H - FOOTER_RAISE_PX;
        int innerW = PANEL_W - PAD * 2;
        int segs = activeAccepted ? 2 : 3;
        int seg = Math.max(1, innerW / segs);
        int hoveredAction = hitTest(scaledMouseX(mc), scaledMouseY(mc), left, top, slidePx);
        for (int i = 0; i < segs; i++) {
            int x0 = drawLeft + PAD + i * seg;
            int x1 = i == segs - 1 ? drawLeft + PANEL_W - PAD : drawLeft + PAD + (i + 1) * seg - 1;
            boolean hover = (!activeAccepted && hoveredAction == i + 1) || (activeAccepted && ((i == 0 && hoveredAction == 4) || (i == 1 && hoveredAction == 3)));
            Gui.drawRect(x0, footerTop, x1, footerTop + FOOTER_H, hover ? 0x98B8B8B8 : 0x70303030);
            Gui.drawRect(x0, footerTop, x1, footerTop + 1, 0x80FFFFFF);
            String lab;
            if (activeAccepted) {
                lab = i == 0 ? EnumChatFormatting.AQUA + "View" : EnumChatFormatting.GRAY + "Hide";
            } else {
                lab = i == 0 ? EnumChatFormatting.GREEN + "Accept"
                        : i == 1 ? EnumChatFormatting.RED + "Decline"
                        : EnumChatFormatting.GRAY + "Hide";
            }
            int lw = mc.fontRendererObj.getStringWidth(lab);
            mc.fontRendererObj.drawString(lab, x0 + (x1 - x0 - lw) / 2,
                    footerTop + (FOOTER_H - mc.fontRendererObj.FONT_HEIGHT) / 2 + 1, 0xFFFFFF);
        }
    }
}
