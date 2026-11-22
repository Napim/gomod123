package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.gui.FriendRequestNotificationGUI;
import me.napim.gomod.gui.PlayerHeadTextureUtil;
import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Non-blocking toast for incoming gomod friend requests: top-right slide-in.
 */
public final class IncomingFriendRequestOverlay {
    private static final int PAD = 5;
    private static final int HEAD_SIZE = 26;
    private static final int PANEL_W = 190;
    private static final int PANEL_H = 56;
    private static final int FOOTER_H = 16;
    /** Lift footer buttons slightly so they sit inside the panel border. */
    private static final int FOOTER_RAISE_PX = 3;

    private static final long INTRO_MS = 340L;
    private static final long HOLD_MS = 7_000L;
    private static final long FADE_OUT_MS = 520L;

    private static volatile String activeFrom;
    private static volatile long showStartMs;
    private static volatile long fadeOutStartMs;

    private static boolean prevMouseLeft;
    private static long lastClickMs;

    public IncomingFriendRequestOverlay() {}

    public static int panelHeightPx() {
        return PANEL_H;
    }

    /** @return true when a new request was shown; false for duplicates/already-pending requests. */
    public static boolean show(String fromName) {
        if (fromName == null) {
            return false;
        }
        String f = GomodSocialRequests.resolveIncomingPeer(fromName);
        if (f.isEmpty() || isSelfName(f)) {
            return false;
        }
        if (!GomodSocialRequests.addIncoming(f)) {
            return false;
        }
        activeFrom = f;
        showStartMs = System.currentTimeMillis();
        fadeOutStartMs = 0L;
        ShareInvManager.playIncomingInvitePling();
        return true;
    }

    public static boolean isShowing() {
        return activeFrom != null && !activeFrom.isEmpty();
    }

    /**
     * Name on the incoming friend-request toast if one is visible, otherwise {@code null}.
     * (Used by {@link me.napim.gomod.gui.GomodFriendsGUI} notifications — no persistent inbox yet.)
     */
    public static String peekActiveIncomingFrom() {
        if (!isShowing()) {
            return null;
        }
        String s = activeFrom.trim();
        return s.isEmpty() ? null : s;
    }

    public static void dismiss() {
        activeFrom = null;
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
        int top = 6;
        if (IncomingScreenshotOverlay.isShowing()) {
            top += IncomingScreenshotOverlay.panelHeightPx(mc) + 6;
        }
        return Math.max(2, Math.min(sr.getScaledHeight() - PANEL_H - 2, top));
    }

    /**
     * @return 0 none, 1 accept, 2 decline, 3 hide, 4 open (panel body)
     */
    private static int hitTest(Minecraft mc, int mouseX, int mouseY, int drawLeft, int drawTop, int slidePx) {
        int x0 = drawLeft + slidePx;
        int y0 = drawTop;
        if (mouseX < x0 || mouseY < y0 || mouseX >= x0 + PANEL_W || mouseY >= y0 + PANEL_H) {
            return 0;
        }
        int footerTop = y0 + PANEL_H - FOOTER_H - FOOTER_RAISE_PX;
        if (mouseY >= footerTop) {
            int innerW = PANEL_W - PAD * 2;
            int seg = Math.max(1, innerW / 3);
            int relX = mouseX - (x0 + PAD);
            if (relX < seg) return 1;
            if (relX < seg * 2) return 2;
            return 3;
        }
        return 4;
    }

    private static void handleClick(Minecraft mc, int action) {
        String from = activeFrom;
        if (from == null || from.isEmpty()) {
            dismiss();
            return;
        }
        if (action == 1) {
            GomodSocialRequests.removeIncoming(from);
            ConfigManager.addGomodFriend(from);
            GomodFriendRelay.postFriendAccept(from);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Accepted "
                    + EnumChatFormatting.AQUA + from + EnumChatFormatting.GREEN + "'s friend request.");
            dismiss();
        } else if (action == 2) {
            GomodSocialRequests.removeIncoming(from);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Declined friend request from "
                    + EnumChatFormatting.AQUA + from + EnumChatFormatting.GRAY + ".");
            dismiss();
        } else if (action == 3) {
            dismiss();
        } else if (action == 4) {
            GomodSocialRequests.markIncomingSeen(from);
            FriendRequestNotificationGUI.open(from);
            dismiss();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("IncomingFriendRequestOverlay")) {
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
        // The toast is also rendered over gomod Social and other GUI screens, so it must remain
        // clickable there just like the DM and Public-message toasts.
        if (down && !prevMouseLeft) {
            int mx = scaledMouseX(mc);
            int my = scaledMouseY(mc);
            int left = resolveLeft(mc);
            int top = resolveTop(mc);
            long now = System.currentTimeMillis();
            int slidePx = resolveSlidePx(now);
            int action = hitTest(mc, mx, my, left, top, slidePx);
            if (action != 0) {
                if (now - lastClickMs >= 250L) {
                    lastClickMs = now;
                    handleClick(mc, action);
                }
            }
        }
        prevMouseLeft = down;

        if (!isShowing()) {
            return;
        }
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
        if (!ClientTickHub.isFeatureEnabled("IncomingFriendRequestOverlay")) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        if (!isShowing()) {
            return;
        }
        render(mc);
    }

    static void render(Minecraft mc) {
        String from = activeFrom;
        if (from == null || from.isEmpty()) {
            return;
        }

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
        Gui.drawRect(drawLeft, drawTop, drawLeft + PANEL_W, drawTop + PANEL_H, bg);
        Gui.drawRect(drawLeft, drawTop, drawLeft + PANEL_W, drawTop + 1, 0xA0FFFFFF);
        Gui.drawRect(drawLeft, drawTop + PANEL_H - 1, drawLeft + PANEL_W, drawTop + PANEL_H, 0xA0FFFFFF);
        Gui.drawRect(drawLeft, drawTop, drawLeft + 1, drawTop + PANEL_H, 0xA0FFFFFF);
        Gui.drawRect(drawLeft + PANEL_W - 1, drawTop, drawLeft + PANEL_W, drawTop + PANEL_H, 0xA0FFFFFF);

        int bodyH = PANEL_H - FOOTER_H - FOOTER_RAISE_PX;
        int headX = drawLeft + PAD;
        int headY = drawTop + Math.max(PAD, (bodyH - HEAD_SIZE) / 2);
        PlayerHeadTextureUtil.drawMinotarAvatar(from, headX, headY, HEAD_SIZE, 1);

        int textX = headX + HEAD_SIZE + 6;
        String title = EnumChatFormatting.GOLD + "Friend request";
        mc.fontRendererObj.drawStringWithShadow(title, textX, drawTop + PAD, 0xFFFFFF);
        String line = EnumChatFormatting.GRAY + "From: " + EnumChatFormatting.AQUA + from;
        mc.fontRendererObj.drawStringWithShadow(line, textX, drawTop + PAD + mc.fontRendererObj.FONT_HEIGHT + 2,
                0xFFFFFF);

        int footerTop = drawTop + PANEL_H - FOOTER_H - FOOTER_RAISE_PX;
        int innerW = PANEL_W - PAD * 2;
        int seg = Math.max(1, innerW / 3);
        int mx = scaledMouseX(mc);
        int my = scaledMouseY(mc);
        int hovered = -1;
        int action = hitTest(mc, mx, my, left, top, slidePx);
        if (action == 1) hovered = 0;
        else if (action == 2) hovered = 1;
        else if (action == 3) hovered = 2;

        int bx = drawLeft + PAD;
        for (int i = 0; i < 3; i++) {
            int x0 = bx + i * seg;
            int x1 = bx + (i + 1) * seg - 1;
            boolean ho = hovered == i;
            int bgBtn = ho ? 0x98B8B8B8 : 0x70303030;
            Gui.drawRect(x0, footerTop, x1, footerTop + FOOTER_H, bgBtn);
            Gui.drawRect(x0, footerTop, x1, footerTop + 1, 0x80FFFFFF);
            String lab = i == 0 ? (EnumChatFormatting.GREEN + "Accept")
                    : i == 1 ? (EnumChatFormatting.RED + "Decline")
                    : (EnumChatFormatting.GRAY + "Hide");
            int lw = mc.fontRendererObj.getStringWidth(lab);
            int labelY = footerTop + (FOOTER_H - mc.fontRendererObj.FONT_HEIGHT) / 2 + 1;
            mc.fontRendererObj.drawString(lab, x0 + (seg - lw) / 2, labelY, 0xFFFFFF);
        }

        SocialToastProgressBar.draw(drawLeft, drawTop + PANEL_H, PANEL_W, showStartMs, INTRO_MS, HOLD_MS, alpha);

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

