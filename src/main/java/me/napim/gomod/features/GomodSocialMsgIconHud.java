package me.napim.gomod.features;

import me.napim.gomod.gui.GomodSocialGUI;
import me.napim.gomod.gui.GomodSocialMsgIconTextures;
import me.napim.gomod.gui.SocialDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import org.lwjgl.input.Mouse;

/**
 * Persistent top-right gomod Social message icon with unread badge.
 * Hidden while a social toast notification is visible.
 */
public final class GomodSocialMsgIconHud {

    private static final int MARGIN = 6;
    private static final int GAP = 4;

    private static boolean prevMouseLeft;
    private static long lastClickMs;
    private static int pendingOpenAction;

    public GomodSocialMsgIconHud() {}

    public static boolean shouldShow() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        if (isBlockedScreen(mc.currentScreen)) {
            return false;
        }
        if (!ConfigManager.isSocialMsgIconHudEnabled()) {
            return false;
        }
        if (GomodSocialToastVisibility.isAnyToastShowing()) {
            return false;
        }
        if (IncomingFriendDmOverlay.suppressesMsgIconHud()) {
            return false;
        }
        return GomodSocialUnreadCounter.hasUnread()
                || incomingFriendRequestCount() > 0
                || IncomingCapeDesigns.pendingCount() > 0;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("GomodSocialMsgIconHud")) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (!shouldShow() || !canClickOnCurrentScreen(mc.currentScreen)) {
            prevMouseLeft = Mouse.isButtonDown(0);
            pendingOpenAction = 0;
            return;
        }

        boolean down = Mouse.isButtonDown(0);
        if (!down && pendingOpenAction != 0) {
            int action = pendingOpenAction;
            pendingOpenAction = 0;
            openAction(action);
        }
        if (down && !prevMouseLeft) {
            int mx = scaledMouseX(mc);
            int my = scaledMouseY(mc);
            int hit = hitIcon(mc, mx, my);
            if (hit != 0 && System.currentTimeMillis() - lastClickMs >= 250L) {
                lastClickMs = System.currentTimeMillis();
                IncomingFriendDmOverlay.dismiss();
                pendingOpenAction = hit;
            }
        }
        prevMouseLeft = down;
    }

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post event) {
        if (!ClientTickHub.isFeatureEnabled("GomodSocialMsgIconHud")) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || mc.thePlayer == null || !shouldShow()) {
            return;
        }
        render(mc);
    }

    public static void renderOnGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || mc.thePlayer == null || !shouldShow()) {
            return;
        }
        render(mc);
    }

    private static boolean isBlockedScreen(GuiScreen screen) {
        return false;
    }

    private static boolean canClickOnCurrentScreen(GuiScreen screen) {
        return screen == null || screen instanceof GuiChat;
    }

    private static void render(Minecraft mc) {
        int mentionCount = GomodSocialUnreadCounter.publicMentionUnread();
        int dmCount = GomodSocialUnreadCounter.dmUnread();
        int friendCount = incomingFriendRequestCount();
        int capeCount = IncomingCapeDesigns.pendingCount();
        if (mentionCount <= 0 && dmCount <= 0 && friendCount <= 0 && capeCount <= 0) {
            return;
        }

        int mx = scaledMouseX(mc);
        int my = scaledMouseY(mc);
        if (mentionCount > 0) {
            int[] b = iconBounds(mc, 1);
            if (GomodSocialMsgIconTextures.drawPublic(b[0], b[1], inBounds(mx, my, b))) {
                drawCornerBadge(mc.fontRendererObj, b[0] + b[2], b[1], mentionCount);
            }
        }
        if (dmCount > 0) {
            int[] b = iconBounds(mc, 2);
            if (GomodSocialMsgIconTextures.drawDm(b[0], b[1], inBounds(mx, my, b))) {
                drawCornerBadge(mc.fontRendererObj, b[0] + b[2], b[1], dmCount);
            }
        }
        if (friendCount > 0) {
            int[] b = iconBounds(mc, 3);
            if (GomodSocialMsgIconTextures.drawFriend(b[0], b[1], inBounds(mx, my, b))) {
                drawCornerBadge(mc.fontRendererObj, b[0] + b[2], b[1], friendCount);
            }
        }
        if (capeCount > 0) {
            int[] b = iconBounds(mc, 4);
            if (GomodSocialMsgIconTextures.drawCape(b[0], b[1], inBounds(mx, my, b))) {
                drawCornerBadge(mc.fontRendererObj, b[0] + b[2], b[1], capeCount);
            }
        }
    }

    /** @return 0 none, 1 public mention, 2 DM, 3 friend request, 4 cape */
    private static int hitIcon(Minecraft mc, int mx, int my) {
        int[] order = new int[] { 4, 3, 2, 1 };
        for (int type : order) {
            if (countForType(type) > 0 && inBounds(mx, my, iconBounds(mc, type))) {
                return type;
            }
        }
        return 0;
    }

    private static void openBestDmUnread() {
        String bestFriend = null;
        int bestUnread = 0;
        for (String peer : GomodSocialStore.getDirectConversationNames()) {
            if (peer == null || peer.trim().isEmpty()) {
                continue;
            }
            int unread = GomodSocialStore.getSessionUnreadCount(peer);
            if (unread > bestUnread) {
                bestUnread = unread;
                bestFriend = peer;
            }
        }
        if (bestFriend != null && bestUnread > 0) {
            GomodSocialGUI.openChatWith(bestFriend);
            return;
        }
        GomodSocialGUI.openBestUnread();
    }

    /** @return {x, y, w, h} */
    private static int[] iconBounds(Minecraft mc, int type) {
        ScaledResolution sr = new ScaledResolution(mc);
        int size = GomodSocialMsgIconTextures.DRAW_SIZE;
        int slot = slotForType(type);
        int x = sr.getScaledWidth() - size - MARGIN - slot * (size + GAP);
        int y = MARGIN;
        return new int[] { x, y, size, size };
    }

    private static int slotForType(int type) {
        int slot = 0;
        int[] order = new int[] { 4, 3, 2, 1 };
        for (int t : order) {
            if (countForType(t) <= 0) {
                continue;
            }
            if (t == type) {
                return slot;
            }
            slot++;
        }
        return slot;
    }

    private static int countForType(int type) {
        if (type == 1) {
            return GomodSocialUnreadCounter.publicMentionUnread();
        }
        if (type == 2) {
            return GomodSocialUnreadCounter.dmUnread();
        }
        if (type == 3) {
            return incomingFriendRequestCount();
        }
        if (type == 4) {
            return IncomingCapeDesigns.pendingCount();
        }
        return 0;
    }

    private static int incomingFriendRequestCount() {
        int count = ConfigManager.getUnseenGomodIncomingFriendRequestCount();
        String live = IncomingFriendRequestOverlay.peekActiveIncomingFrom();
        if (live != null && ConfigManager.findGomodIncomingFriendRequestIndex(live) < 0) {
            count++;
        }
        return count;
    }

    private static void openAction(int action) {
        if (action == 1) {
            GomodSocialGUI.openPublicChatView();
        } else if (action == 2) {
            openBestDmUnread();
        } else if (action == 3) {
            GomodSocialGUI.openRequestsView();
        } else if (action == 4) {
            String from = IncomingCapeDesigns.firstPendingSender();
            if (from != null && !from.trim().isEmpty()) {
                GomodSocialGUI.openCapesView(from);
            } else {
                GomodSocialGUI.openBestUnread();
            }
        }
    }

    private static boolean inBounds(int mx, int my, int[] b) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    private static int scaledMouseX(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return Mouse.getX() * sr.getScaledWidth() / Math.max(1, mc.displayWidth);
    }

    private static int scaledMouseY(Minecraft mc) {
        ScaledResolution sr = new ScaledResolution(mc);
        return sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / Math.max(1, mc.displayHeight) - 1;
    }

    private static void drawCornerBadge(FontRenderer fr, int iconRight, int iconTop, int count) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        int tw = fr.getStringWidth(s);
        int diam = Math.max(12, tw + 5);
        int bx = iconRight - diam / 2;
        int by = iconTop - diam / 2;
        float cx = bx + diam * 0.5f;
        float cy = by + diam * 0.5f;
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        SocialDraw.circle(cx, cy, diam * 0.5f, 0xFFCC2222);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        fr.drawStringWithShadow(EnumChatFormatting.WHITE + s, bx + (diam - tw) / 2, by + (diam - 8) / 2, 0xFFFFFF);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
}
