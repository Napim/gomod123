package me.napim.gomod.features;



import me.napim.gomod.gui.GomodSocialGUI;

import me.napim.gomod.gui.PlayerHeadTextureUtil;

import me.napim.gomod.shareinv.ShareInvManager;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.FontRenderer;

import net.minecraft.client.gui.Gui;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.util.EnumChatFormatting;

import net.weavemc.loader.api.event.RenderGameOverlayEvent;

import net.weavemc.loader.api.event.SubscribeEvent;

import net.weavemc.loader.api.event.TickEvent;

import org.lwjgl.input.Mouse;



import java.util.List;



/**

 * Top-right toast when someone {@code @mentions} you in gomod Social public chat.

 */

public final class IncomingPublicPingOverlay {

    private static final int PANEL_W = SocialMessageToastLayout.PANEL_W;

    private static final int PAD = SocialMessageToastLayout.PAD;

    private static final int HEAD_SIZE = SocialMessageToastLayout.HEAD_SIZE;

    private static final int FOOTER_H = SocialMessageToastLayout.FOOTER_H;

    private static final int FOOTER_RAISE_PX = SocialMessageToastLayout.FOOTER_RAISE_PX;



    private static final long INTRO_MS = 340L;

    private static final long HOLD_MS = 6_500L;

    private static final long FADE_OUT_MS = 520L;



    private static volatile String activeFrom;

    private static volatile String activePreview;

    /** 0 public mention, 1 new thread, 2 group invite, 3 ordinary public message. */
    private static volatile int activeKind;

    private static volatile String activeForumId;

    private static volatile String activeTargetId;

    private static volatile long showStartMs;

    private static volatile long fadeOutStartMs;



    private static boolean prevMouseLeft;

    private static long lastClickMs;



    public IncomingPublicPingOverlay() {}



    public static int panelHeightPx() {

        return panelHeightPx(Minecraft.getMinecraft());

    }



    public static int panelHeightPx(Minecraft mc) {

        if (!isShowing() || mc == null || mc.fontRendererObj == null) {

            return SocialMessageToastLayout.BASE_PANEL_H;

        }

        return SocialMessageToastLayout.panelHeight(mc.fontRendererObj, activePreview);

    }



    public static void show(String fromName, String messageText) {

        if (fromName == null) {

            return;

        }

        String f = fromName.trim();

        if (f.isEmpty() || isSelfName(f)) {

            return;

        }

        if (GomodSocialGUI.isViewingPublicChat()) {

            return;

        }

        activeFrom = f;

        activeKind = 0;

        activeForumId = null;

        activeTargetId = null;

        activePreview = SocialMessageToastLayout.normalizePreview(

                GomodSocialPublicMentions.formatDisplayText(messageText));

        showStartMs = System.currentTimeMillis();

        fadeOutStartMs = 0L;

        ShareInvManager.playIncomingInvitePling();

    }

    public static void showPublicMessage(String fromName, String messageText) {
        if (fromName == null || !ConfigManager.isPublicMessagePopupsEnabled()) {
            return;
        }
        String from = fromName.trim();
        if (from.isEmpty() || isSelfName(from) || GomodSocialGUI.isViewingPublicChat()) {
            return;
        }
        activeFrom = from;
        activeKind = 3;
        activeForumId = null;
        activeTargetId = null;
        activePreview = SocialMessageToastLayout.normalizePreview(
                GomodSocialPublicMentions.formatDisplayText(messageText));
        showStartMs = System.currentTimeMillis();
        fadeOutStartMs = 0L;
        ShareInvManager.playIncomingInvitePling();
    }

    public static void showThread(String author, String title, String forumId, String threadId) {
        if (author == null || threadId == null || threadId.trim().isEmpty()) return;
        String from = author.trim();
        if (from.isEmpty() || isSelfName(from) || GomodSocialGUI.isViewingThread(forumId, threadId)) return;
        activeFrom = from;
        activePreview = SocialMessageToastLayout.normalizePreview(title);
        activeKind = 1;
        activeForumId = forumId;
        activeTargetId = threadId.trim();
        showStartMs = System.currentTimeMillis();
        fadeOutStartMs = 0L;
        ShareInvManager.playIncomingInvitePling();
    }

    public static void showGroup(String inviter, String groupName, String groupId) {
        if (inviter == null || groupId == null || groupId.trim().isEmpty()) return;
        String from = inviter.trim();
        if (from.isEmpty() || isSelfName(from) || GomodSocialGUI.isViewingGroup(groupId)) return;
        activeFrom = from;
        activePreview = SocialMessageToastLayout.normalizePreview(groupName);
        activeKind = 2;
        activeForumId = null;
        activeTargetId = groupId.trim();
        showStartMs = System.currentTimeMillis();
        fadeOutStartMs = 0L;
        ShareInvManager.playIncomingInvitePling();
    }



    public static boolean isShowing() {

        return activeFrom != null && !activeFrom.isEmpty();

    }



    public static void dismiss() {

        activeFrom = null;

        activePreview = null;

        activeKind = 0;

        activeForumId = null;

        activeTargetId = null;

        showStartMs = 0L;

        fadeOutStartMs = 0L;

    }



    private static int resolveSlidePx(long now) {

        int px = 0;

        if (showStartMs > 0L) {

            float t = (now - showStartMs) / (float) INTRO_MS;

            if (t < 1.0F) {

                float u = 1.0F - (1.0F - Math.min(1.0F, t)) * (1.0F - Math.min(1.0F, t));

                px = Math.round((1.0F - u) * 18.0F);

            }

        }

        if (fadeOutStartMs > 0L) {

            float ft = (now - fadeOutStartMs) / (float) FADE_OUT_MS;

            px = Math.round(Math.min(1.0F, Math.max(0.0F, ft)) * 18.0F);

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



    private static int resolveLeft(Minecraft mc) {

        ScaledResolution sr = new ScaledResolution(mc);

        return Math.max(4, sr.getScaledWidth() - PANEL_W - 6);

    }



    private static int resolveTop(Minecraft mc) {

        ScaledResolution sr = new ScaledResolution(mc);

        int panelH = panelHeightPx(mc);

        int top = 6;

        if (IncomingScreenshotOverlay.isShowing()) {

            top += IncomingScreenshotOverlay.panelHeightPx(mc) + 6;

        }

        if (IncomingFriendRequestOverlay.isShowing()) {

            top += IncomingFriendRequestOverlay.panelHeightPx() + 6;

        }

        if (IncomingFriendDmOverlay.isShowing()) {

            top += IncomingFriendDmOverlay.panelHeightPx(mc) + 6;

        }

        return Math.max(2, Math.min(sr.getScaledHeight() - panelH - 2, top));

    }



    /** @return 0 none, 1 open chat, 2 hide, 3 panel body (open) */

    private static int hitTest(Minecraft mc, int mouseX, int mouseY, int drawLeft, int drawTop, int slidePx, int panelH) {

        int x0 = drawLeft + slidePx;

        int y0 = drawTop;

        if (mouseX < x0 || mouseY < y0 || mouseX >= x0 + PANEL_W || mouseY >= y0 + panelH) {

            return 0;

        }

        int footerTop = y0 + panelH - FOOTER_H - FOOTER_RAISE_PX;

        if (mouseY >= footerTop) {

            int innerW = PANEL_W - PAD * 2;

            int seg = Math.max(1, innerW / 2);

            int relX = mouseX - (x0 + PAD);

            if (relX < seg) {

                return 1;

            }

            return 2;

        }

        return 3;

    }



    private static void handleClick(int action) {

        if (action == 1 || action == 3) {
            if (activeKind == 1) {
                GomodSocialGUI.openThreadView(activeForumId, activeTargetId);
            } else if (activeKind == 2) {
                GomodSocialGUI.openGroupView(activeTargetId);
            } else {
                GomodSocialGUI.openPublicChatView();
            }

            dismiss();

        } else if (action == 2) {

            dismiss();

        }

    }



    @SubscribeEvent

    public void onTick(TickEvent event) {

        if (!ClientTickHub.isFeatureEnabled("IncomingPublicPingOverlay")) {

            return;

        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null) {

            return;

        }

        if (!isShowing()) {

            prevMouseLeft = Mouse.isButtonDown(0);

            return;

        }



        boolean down = Mouse.isButtonDown(0);

        if (down && !prevMouseLeft) {

            int mx = scaledMouseX(mc);

            int my = scaledMouseY(mc);

            int left = resolveLeft(mc);

            int top = resolveTop(mc);

            int panelH = panelHeightPx(mc);

            long now = System.currentTimeMillis();

            int slidePx = resolveSlidePx(now);

            int action = hitTest(mc, mx, my, left, top, slidePx, panelH);

            if (action != 0 && now - lastClickMs >= 250L) {

                lastClickMs = now;

                handleClick(action);

            }

        }

        prevMouseLeft = down;



        long now = System.currentTimeMillis();

        if (fadeOutStartMs <= 0L && now - showStartMs >= HOLD_MS) {

            fadeOutStartMs = now;

        }

        if (fadeOutStartMs > 0L && now - fadeOutStartMs >= FADE_OUT_MS) {

            dismiss();

        }

    }



    @SubscribeEvent

    public void onRender(RenderGameOverlayEvent.Post event) {

        if (!ClientTickHub.isFeatureEnabled("IncomingPublicPingOverlay")) {

            return;

        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.fontRendererObj == null || !isShowing()) {

            return;

        }

        render(mc);

    }



    static void render(Minecraft mc) {

        String from = activeFrom;

        String preview = activePreview;

        if (from == null || from.isEmpty()) {

            return;

        }



        FontRenderer fr = mc.fontRendererObj;

        int panelH = SocialMessageToastLayout.panelHeight(fr, preview);

        List<String> previewLines = SocialMessageToastLayout.wrapPreview(fr, preview);



        int left = resolveLeft(mc);

        int top = resolveTop(mc);

        long now = System.currentTimeMillis();

        float alpha = 1.0F;

        int slidePx = resolveSlidePx(now);

        if (showStartMs > 0L && fadeOutStartMs <= 0L) {

            float t = (now - showStartMs) / (float) INTRO_MS;

            if (t < 1.0F) {

                float u = 1.0F - (1.0F - Math.min(1.0F, t)) * (1.0F - Math.min(1.0F, t));

                alpha = u;

            }

        }

        if (fadeOutStartMs > 0L) {

            float ft = (now - fadeOutStartMs) / (float) FADE_OUT_MS;

            alpha *= Math.max(0.0F, 1.0F - Math.min(1.0F, ft));

        }



        int drawLeft = left + slidePx;

        int drawTop = top;



        GlStateManager.pushMatrix();

        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GlStateManager.disableDepth();

        GlStateManager.depthMask(false);

        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);



        int bg = (int) (200 * alpha) << 24 | 0x101010;

        Gui.drawRect(drawLeft, drawTop, drawLeft + PANEL_W, drawTop + panelH, bg);

        Gui.drawRect(drawLeft, drawTop, drawLeft + PANEL_W, drawTop + 1, 0xA0FFFFFF);

        Gui.drawRect(drawLeft, drawTop + panelH - 1, drawLeft + PANEL_W, drawTop + panelH, 0xA0FFFFFF);

        Gui.drawRect(drawLeft, drawTop, drawLeft + 1, drawTop + panelH, 0xA0FFFFFF);

        Gui.drawRect(drawLeft + PANEL_W - 1, drawTop, drawLeft + PANEL_W, drawTop + panelH, 0xA0FFFFFF);



        int bodyH = panelH - FOOTER_H - FOOTER_RAISE_PX;

        int headX = drawLeft + PAD;

        int headY = drawTop + Math.max(PAD, (bodyH - HEAD_SIZE) / 2);

        PlayerHeadTextureUtil.drawMinotarAvatar(from, headX, headY, HEAD_SIZE, 1);



        int textX = headX + HEAD_SIZE + 6;

        String heading = activeKind == 1 ? "New thread"
                : (activeKind == 2 ? "Group invite"
                : (activeKind == 3 ? "New Public message" : "Public mention"));

        fr.drawStringWithShadow(EnumChatFormatting.GOLD + heading, textX, drawTop + PAD, 0xFFFFFF);

        fr.drawStringWithShadow(EnumChatFormatting.GRAY + "From: " + EnumChatFormatting.AQUA + from,

                textX, drawTop + PAD + fr.FONT_HEIGHT + 1, 0xFFFFFF);

        int previewY = drawTop + PAD + fr.FONT_HEIGHT * 2 + 1;

        for (String line : previewLines) {

            fr.drawStringWithShadow(line, textX, previewY, 0xFFFFFF);

            previewY += fr.FONT_HEIGHT;

        }



        int footerTop = drawTop + panelH - FOOTER_H - FOOTER_RAISE_PX;

        int innerW = PANEL_W - PAD * 2;

        int seg = Math.max(1, innerW / 2);

        int mx = scaledMouseX(mc);

        int my = scaledMouseY(mc);

        int hovered = -1;

        int action = hitTest(mc, mx, my, left, top, slidePx, panelH);

        if (action == 1) {

            hovered = 0;

        } else if (action == 2) {

            hovered = 1;

        }



        int bx = drawLeft + PAD;

        for (int i = 0; i < 2; i++) {

            int x0 = bx + i * seg;

            int x1 = bx + (i + 1) * seg - 1;

            boolean ho = hovered == i;

            int bgBtn = ho ? 0x98B8B8B8 : 0x70303030;

            Gui.drawRect(x0, footerTop, x1, footerTop + FOOTER_H, bgBtn);

            Gui.drawRect(x0, footerTop, x1, footerTop + 1, 0x80FFFFFF);

            String lab = i == 0 ? (EnumChatFormatting.GREEN + "Open")

                    : (EnumChatFormatting.GRAY + "Hide");

            int lw = fr.getStringWidth(lab);

            int labelY = footerTop + (FOOTER_H - fr.FONT_HEIGHT) / 2 + 1;

            fr.drawString(lab, x0 + (seg - lw) / 2, labelY, 0xFFFFFF);

        }

        SocialToastProgressBar.draw(drawLeft, drawTop + panelH, PANEL_W, showStartMs, INTRO_MS, HOLD_MS, alpha);



        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.depthMask(true);

        GlStateManager.enableDepth();

        GlStateManager.disableBlend();

        GlStateManager.popMatrix();

    }



    private static boolean isSelfName(String name) {

        Minecraft mc = Minecraft.getMinecraft();

        String self = GomodSocialPresence.sessionPlayerName(mc);

        return name != null && !self.isEmpty() && name.equalsIgnoreCase(self);

    }

}


