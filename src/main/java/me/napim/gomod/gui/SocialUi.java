package me.napim.gomod.gui;

import me.napim.gomod.features.FriendListScanner;
import me.napim.gomod.features.GomodSocialChat;
import me.napim.gomod.features.GomodSocialForums;
import me.napim.gomod.features.GomodSocialPublicChat;
import me.napim.gomod.features.GomodSocialStore;
import me.napim.gomod.features.GomodSocialTempGroups;
import me.napim.gomod.features.GomodServiceStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

/**
 * Modern paint routines for the gomod Social tab.
 *
 * <p>Everything here is purely cosmetic – layout decisions still live in
 * {@link GomodSocialGUI}. The drawing relies on {@link SocialDraw} primitives
 * (rounded rects, gradients, glows) so the UI matches the rest of the modern
 * design language used in the mod.
 */
public final class SocialUi {

    public enum DockPanel { FRIENDS, GROUPS, THREADS, PUBLIC }

    /** Tab hit boxes shared by draw + click (fixes Privacy opening Requests). */
    public static final class WorkspaceTabLayout {
        public final int left;
        public final int right;

        WorkspaceTabLayout(int left, int right) {
            this.left = left;
            this.right = right;
        }

        public int width() {
            return right - left;
        }
    }

    private SocialUi() {}

    // ============================================================== chrome

    /** Painted background of the dock (left rail). */
    public static void drawDockGradient(int l, int t, int r, int b) {
        SocialDraw.verticalGradient(l, t, r, b, SocialTheme.DOCK_HIGHLIGHT, SocialTheme.DOCK);
        // Right edge separator (soft).
        SocialDraw.fillRect(r - 1, t, r, b, SocialTheme.DOCK_EDGE);
        SocialDraw.fillRect(r, t, r + 1, b, 0x40000000);
    }

    /** Painted background of the workspace (right panel). */
    public static void drawWorkspaceGradient(int l, int t, int r, int b) {
        SocialDraw.verticalGradient(l, t, r, b, SocialTheme.WORKSPACE_TOP, SocialTheme.WORKSPACE);
        // Faint left edge so the dock keeps separation.
        SocialDraw.fillRect(l, t, l + 1, b, 0x55000000);
        SocialDraw.fillRect(l + 1, t, l + 2, b, SocialTheme.BORDER_SUBTLE);
    }

    /** Backdrop dim behind the whole screen (replaces hard 50% black). */
    public static void drawBackdrop(int w, int h) {
        drawBackdrop(w, h, SocialTheme.BACKDROP);
    }

    /** Backdrop dim with an explicit strength (menu overlays use a lighter dim). */
    public static void drawBackdrop(int w, int h, int color) {
        SocialDraw.fillRect(0, 0, w, h, color);
    }

    // ============================================================== brand

    private static final int BRAND_HEAD_SIZE = 22;

    /** Local player head at the top of the dock (replaces the green gem). */
    public static void drawBrandHead(Minecraft mc, int x, int y) {
        GomodSocialStore.ensureLocalOnlineSession();
        if (mc != null && mc.thePlayer != null) {
            PlayerHeadTextureUtil.drawMinotarAvatar(mc.thePlayer.getName(), x, y, BRAND_HEAD_SIZE, 1);
        }
    }

    public static void drawBrandTitle(FontRenderer fr, Minecraft mc, int x, int y) {
        fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "Social", x, y, SocialTheme.TEXT_BRIGHT);
        fr.drawString(GomodSocialChat.localPlayerOnlineStatusLine(mc), x, y + 11, SocialTheme.TEXT_DIM);
        String location = GomodSocialChat.localPlayerOnlineLocationLine(mc);
        if (location != null && !location.isEmpty()) {
            int maxW = SocialTheme.DOCK_W - x - 10;
            if (maxW > 0 && fr.getStringWidth(location) > maxW) {
                location = fr.trimStringToWidth(location, maxW - fr.getStringWidth("...")) + "...";
            }
            fr.drawString(location, x, y + 20, SocialTheme.TEXT_DIM);
        }
    }

    /** Shown when the HTTP relay / gomod Service is unreachable; Social still loads local data. */
    public static void drawServiceDisconnectedBanner(FontRenderer fr, int l, int t, int r) {
        if (GomodServiceStatus.isServiceConnected()) {
            return;
        }
        int b = t + SocialTheme.SERVICE_BANNER_H;
        SocialDraw.roundedBorder(l, t, r, b, SocialTheme.RADIUS_SM, SocialTheme.SERVICE_WARN_BG,
                SocialTheme.SERVICE_WARN_BORDER);
        int padX = 8;
        int maxW = r - l - padX * 2;
        drawBannerLineFitted(fr, GomodServiceStatus.statusBannerLine1(), l, t + 5, r, maxW, SocialTheme.TEXT);
        drawBannerLineFitted(fr, GomodServiceStatus.statusBannerLine2(), l, t + 16, r, maxW, SocialTheme.TEXT_MUTED);
    }

    /** Center a banner line; scale down slightly if it would overflow the box width. */
    private static void drawBannerLineFitted(FontRenderer fr, String text, int l, int y, int r, int maxW, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int tw = fr.getStringWidth(text);
        float scale = 1.0f;
        if (tw > maxW && maxW > 0) {
            scale = maxW / (float) tw;
        }
        if (scale < 0.99f) {
            int scaledW = (int) (tw * scale);
            int x = l + (r - l - scaledW) / 2;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, 1.0f);
            fr.drawString(text, 0, 0, color);
            GlStateManager.popMatrix();
        } else {
            fr.drawString(text, l + (r - l - tw) / 2, y, color);
        }
    }

    // ============================================================== tabs

    private static final float DOCK_TAB_TEXT_SCALE = 0.82f;

    public static void drawDockTabs(FontRenderer fr, int l, int t, int w, DockPanel active, boolean hidePublicBadge) {
        int h = SocialTheme.DOCK_TABS_H - 6;
        SocialDraw.roundedFill(l, t, l + w, t + h, SocialTheme.RADIUS_MD, SocialTheme.PANEL_INNER);
        String[] labels = { "Friends", "Groups", "Threads", "Public" };
        DockPanel[] panels = DockPanel.values();
        int count = labels.length;
        int colW = w / count;
        int padding = 3;
        int publicUnread = hidePublicBadge ? 0 : GomodSocialPublicChat.getUnreadCount();
        for (int i = 0; i < count; i++) {
            int x = l + i * colW + padding;
            int x2 = l + (i + 1) * colW - padding;
            if (i == count - 1) {
                x2 = l + w - padding;
            }
            boolean on = panels[i] == active;
            int tabT = t + padding;
            int tabB = t + h - padding;
            if (on) {
                SocialDraw.roundedFill(x, tabT, x2, tabB, SocialTheme.RADIUS_SM, SocialTheme.TAB_ON);
                SocialDraw.fillRect(x + 6, tabB - 2, x2 - 6, tabB, SocialTheme.TAB_ACCENT);
            }
            String label = labels[i];
            String s = (on ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY) + label;
            int badgeReserve = (i == 3 && publicUnread > 0) ? circleBadgeWidth(fr, publicUnread) + 2 : 0;
            int labelW = dockTabLabelWidth(fr, label);
            int innerW = (x2 - x) - badgeReserve;
            int textX = x + (innerW - labelW) / 2;
            int textY = t + (h - 8) / 2;
            drawScaledTabLabel(fr, s, textX, textY, on ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT_MUTED);
            if (i == 3 && publicUnread > 0) {
                drawCornerCountBadge(fr, x2 - 3, tabT + 2, publicUnread);
            }
        }
    }

    public static void drawDockTabs(FontRenderer fr, int l, int t, int w, DockPanel active) {
        drawDockTabs(fr, l, t, w, active, false);
    }

    private static void drawScaledTabLabel(FontRenderer fr, String text, int x, int y, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(DOCK_TAB_TEXT_SCALE, DOCK_TAB_TEXT_SCALE, 1.0f);
        fr.drawString(text, 0, 0, color);
        GlStateManager.popMatrix();
    }

    private static void drawDockTabUnreadBadge(FontRenderer fr, int anchorRight, int y, int count) {
        drawCircleCountBadge(fr, anchorRight, y + 6, count);
    }

    /** Count pill centered in the space after a workspace-tab label. */
    private static void drawInlineCountBadge(FontRenderer fr, int areaL, int areaT, int areaR, int areaB, int count) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        int tw = fr.getStringWidth(s);
        int diam = Math.max(12, tw + 5);
        int bx = areaL + Math.max(0, areaR - areaL - diam) / 2;
        int by = areaT + Math.max(0, areaB - areaT - diam) / 2;
        drawCountBadgePill(fr, bx, by, diam, s, tw);
    }

    /** Red pill badge in the top-right corner of a tab/pill (does not shift label text). */
    private static void drawCornerCountBadge(FontRenderer fr, int anchorRight, int anchorTop, int count) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        int tw = fr.getStringWidth(s);
        int diam = Math.max(14, tw + 6);
        int bx = anchorRight - diam;
        int by = anchorTop;
        drawCountBadgePill(fr, bx, by, diam, s, tw);
    }

    private static final int BADGE_FILL = 0xFF2A2A30;
    private static final int BADGE_TEXT = 0xFFFF4444;

    private static void drawCountBadgePill(FontRenderer fr, int bx, int by, int diam, String s, int tw) {
        float cx = bx + diam * 0.5f;
        float cy = by + diam * 0.5f;
        float radius = diam * 0.5f;
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        SocialDraw.circle(cx, cy, radius, BADGE_FILL);
        SocialDraw.circle(cx, cy, radius, SocialDraw.withAlpha(BADGE_TEXT, 48));
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        int textX = Math.round(cx - tw * 0.5F);
        int textY = Math.round(cy - fr.FONT_HEIGHT * 0.5F);
        fr.drawString(s, textX, textY, BADGE_TEXT);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /** Red pill count badge anchored by its right edge (friend rows). */
    private static void drawCircleCountBadge(FontRenderer fr, int anchorRight, int centerY, int count) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        int tw = fr.getStringWidth(s);
        int diam = Math.max(14, tw + 6);
        int bx = anchorRight - diam;
        int by = centerY - diam / 2;
        drawCountBadgePill(fr, bx, by, diam, s, tw);
    }

    private static int circleBadgeWidth(FontRenderer fr, int count) {
        if (count <= 0) {
            return 0;
        }
        String s = count > 99 ? "99+" : String.valueOf(count);
        return Math.max(14, fr.getStringWidth(s) + 6);
    }

    private static int dockTabLabelWidth(FontRenderer fr, String plain) {
        return (int) (fr.getStringWidth(plain) * DOCK_TAB_TEXT_SCALE);
    }

    public static int dockTabAt(int mx, int my, int l, int t, int w) {
        int h = SocialTheme.DOCK_TABS_H - 6;
        if (my < t || my > t + h) {
            return -1;
        }
        if (mx < l || mx > l + w) {
            return -1;
        }
        int colW = w / DockPanel.values().length;
        int idx = (mx - l) / colW;
        return idx >= 0 && idx < DockPanel.values().length ? idx : -1;
    }

    public static void drawPublicDockInfo(FontRenderer fr, int l, int t, int r, int h) {
        SocialDraw.roundedFill(l, t, r, t + h, SocialTheme.RADIUS_MD, SocialTheme.CARD);
        int y = t + 12;
        fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "Public chat", l + 10, y,
                SocialTheme.TEXT_BRIGHT);
        y += 14;
        fr.drawString(EnumChatFormatting.GRAY + "Channel: " + EnumChatFormatting.AQUA + GomodSocialPublicChat.channelLabel(),
                l + 10, y, SocialTheme.TEXT);
        y += 12;
        fr.drawString(EnumChatFormatting.GRAY + "Everyone on this channel can read", l + 10, y, SocialTheme.TEXT_MUTED);
        y += 10;
        fr.drawString(EnumChatFormatting.GRAY + "and post messages.", l + 10, y, SocialTheme.TEXT_MUTED);
        y += 14;
        int unread = GomodSocialPublicChat.getUnreadCount();
        if (unread > 0) {
            fr.drawString(EnumChatFormatting.GRAY + String.valueOf(unread) + " unread message"
                            + (unread == 1 ? "" : "s"),
                    l + 10, y, SocialTheme.TEXT_MUTED);
        }
    }

    // ============================================================== search

    public static void drawSearchBox(FontRenderer fr, GuiTextFieldStyled field, int l, int t, int w) {
        int b = t + 26;
        int boxH = b - t;
        int border = field.field.isFocused() ? SocialTheme.ACCENT : SocialTheme.BORDER_SUBTLE;
        SocialDraw.roundedFill(l, t, l + w, b, SocialTheme.RADIUS_MD, SocialTheme.PANEL_INNER);
        if (field.field.isFocused()) {
            SocialDraw.roundedRect(l, t, l + w, b, SocialTheme.RADIUS_MD, border);
        }
        if (!GomodSocialSearchIconTextures.draw(l, t, boxH)) {
            SocialDraw.circle(l + 9, t + 13, 3.0f, SocialTheme.TEXT_DIM);
            SocialDraw.circle(l + 9, t + 13, 1.8f, SocialTheme.PANEL_INNER);
            SocialDraw.fillRect(l + 11, t + 15, l + 14, t + 16, SocialTheme.TEXT_DIM);
        }
        int textL = l + GomodSocialSearchIconTextures.insetLeft();
        layoutTextField(field.field, textL, t + 2, l + w - 4, b - 2);
        field.drawInLayout(fr, EnumChatFormatting.DARK_GRAY + "Search\u2026");
    }

    public static int searchBoxIconInset() {
        return GomodSocialSearchIconTextures.insetLeft();
    }

    /** Position a text field to fill the inner area of a rounded inset box. */
    public static void layoutTextField(net.minecraft.client.gui.GuiTextField field, int l, int t, int r, int b) {
        if (field == null) {
            return;
        }
        int boxH = Math.max(8, b - t);
        field.xPosition = l;
        field.yPosition = t + Math.max(0, (boxH - 8) / 2);
        field.width = Math.max(8, r - l);
        field.height = Math.max(8, boxH);
    }

    /**
     * Focus a field when clicking anywhere inside the outer bubble ({@code hitL..hitR}).
     * {@code textInsetLeft} reserves space for icons (e.g. search magnifier).
     */
    public static void focusTextFieldAt(net.minecraft.client.gui.GuiTextField field, int hitL, int hitT, int hitR, int hitB,
            int textInsetLeft, int mouseX, int mouseY, int mouseButton) {
        if (field == null) {
            return;
        }
        int innerL = hitL + Math.max(4, textInsetLeft);
        int innerT = hitT + 2;
        int innerR = hitR - 4;
        int innerB = hitB - 2;
        layoutTextField(field, innerL, innerT, innerR, innerB);
        if (inRect(mouseX, mouseY, hitL, hitT, hitR - hitL, hitB - hitT)) {
            field.setFocused(true);
            int cx = Math.max(field.xPosition, Math.min(mouseX, field.xPosition + Math.max(0, field.width - 1)));
            int cy = Math.max(field.yPosition, Math.min(mouseY, field.yPosition + Math.max(0, field.height - 1)));
            field.mouseClicked(cx, cy, mouseButton);
        } else if (field.isFocused()) {
            field.setFocused(false);
        }
    }

    public static void focusTextFieldAt(net.minecraft.client.gui.GuiTextField field, int hitL, int hitT, int hitR, int hitB,
            int mouseX, int mouseY, int mouseButton) {
        focusTextFieldAt(field, hitL, hitT, hitR, hitB, 4, mouseX, mouseY, mouseButton);
    }

    /** Generic inset field background (used by modal / composer). */
    public static void drawInsetField(int l, int t, int r, int b) {
        SocialDraw.roundedFill(l, t, r, b, SocialTheme.RADIUS_MD, SocialTheme.PANEL_INNER);
    }

    // ============================================================== buttons

    /** "Open Social" expand button — accent fill, clean flat look. */
    public static void drawExpandButton(FontRenderer fr, int l, int t, int r, int b, boolean hover) {
        int fill = hover ? SocialTheme.ACCENT_BRIGHT : SocialTheme.ACCENT;
        SocialDraw.roundedRect(l, t, r, b, SocialTheme.RADIUS_MD, fill);
        drawCenteredLabel(fr, l, t, r, b,
                EnumChatFormatting.BOLD + "Open Social", SocialTheme.TEXT_INVERSE);
    }

    /** Primary accent button — solid fill, brighter on hover. */
    public static void drawPrimaryButton(FontRenderer fr, int l, int t, int r, int b, String text, boolean hover) {
        int fill = hover ? SocialTheme.ACCENT_BRIGHT : SocialTheme.ACCENT;
        SocialDraw.roundedRect(l, t, r, b, buttonRadius(l, t, r, b), fill);
        drawCenteredLabel(fr, l, t, r, b, text, SocialTheme.TEXT_INVERSE);
    }

    /** Ghost / secondary button — subtle panel fill with hover highlight. */
    public static void drawGhostButton(FontRenderer fr, int l, int t, int r, int b, String text, boolean hover) {
        int fill = hover ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
        SocialDraw.roundedRect(l, t, r, b, buttonRadius(l, t, r, b), fill);
        drawCenteredLabel(fr, l, t, r, b, text, hover ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT);
    }

    /** Destructive action (Decline, Remove, etc.) — red fill, white label. */
    public static void drawDangerButton(FontRenderer fr, int l, int t, int r, int b, String text, boolean hover) {
        int fill = hover ? 0xFFE04A4A : SocialTheme.DANGER;
        SocialDraw.roundedRect(l, t, r, b, buttonRadius(l, t, r, b), fill);
        drawCenteredLabel(fr, l, t, r, b, text, SocialTheme.TEXT_INVERSE);
    }

    /** Icon-only button. */
    public static void drawIconButton(FontRenderer fr, int l, int t, int r, int b, String glyph, boolean hover) {
        int fill = hover ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
        SocialDraw.roundedRect(l, t, r, b, buttonRadius(l, t, r, b), fill);
        drawCenteredGlyph(fr, l, t, r, b, glyph, hover ? SocialTheme.TEXT : SocialTheme.TEXT_MUTED);
    }

    /** Footer "+ Add friend" / "+ Create" CTA link row. */
    public static void drawDashedLink(FontRenderer fr, int l, int t, int r, int b, String text, boolean hover) {
        int fill = hover ? SocialTheme.CARD_HOVER : SocialTheme.PANEL_INNER;
        SocialDraw.roundedRect(l, t, r, b, buttonRadius(l, t, r, b), fill);
        drawCenteredLabel(fr, l, t, r, b, EnumChatFormatting.BOLD + text,
                hover ? SocialTheme.ACCENT_BRIGHT : SocialTheme.ACCENT);
    }

    // ============================================================== labels

    public static void drawSectionLabel(FontRenderer fr, String text, int x, int y) {
        fr.drawString(EnumChatFormatting.BOLD + text.toUpperCase(), x, y, SocialTheme.TEXT_DIM);
    }

    public static void drawGroupHint(FontRenderer fr, int l, int t, int r) {
        int b = t + SocialTheme.GROUP_HINT_H;
        SocialDraw.roundedFill(l, t, r, b, SocialTheme.RADIUS_MD, SocialTheme.TEMP_BG);
        String line1 = EnumChatFormatting.GOLD + "\u23F1 " + EnumChatFormatting.GRAY + "Temp groups";
        String line2 = EnumChatFormatting.GRAY + "expire after 24h idle";
        int cx = (l + r) / 2;
        int w1 = fr.getStringWidth(line1);
        int w2 = fr.getStringWidth(line2);
        int blockH = 18;
        int y0 = t + (SocialTheme.GROUP_HINT_H - blockH) / 2;
        fr.drawString(line1, cx - w1 / 2, y0, SocialTheme.TEXT);
        fr.drawString(line2, cx - w2 / 2, y0 + 10, SocialTheme.TEXT_MUTED);
    }

    // ============================================================== rows

    /** Friend dock row with avatar, presence dot, unread count. */
    public static void drawFriendRow(FontRenderer fr, Minecraft mc, String name, int l, int t, int r,
            boolean selected, boolean hover, boolean away, int unread, int mx, int my) {
        int rowH = SocialTheme.DOCK_ROW_H;
        int b = t + rowH - 2;
        if (selected) {
            SocialDraw.bevelPanel(l, t + 1, r, b, SocialTheme.RADIUS_SM, SocialTheme.ROW_SEL);
            SocialDraw.fillRect(l + 2, t + 4, l + 4, b - 3, SocialTheme.ROW_SEL_BAR);
        } else if (hover) {
            SocialDraw.fillRect(l, t + 1, r, b, SocialTheme.ROW_HOVER);
        }

        int head = 24;
        int hx = l + 8;
        int hy = t + (rowH - head) / 2;
        PlayerHeadTextureUtil.drawMinotarAvatar(name, hx, hy, head, 1);

        int textX = hx + head + 8;
        int nameY = t + 8;
        drawPresenceRing(textX - GomodSocialPresenceIconTextures.DOCK_DRAW_SIZE - 3,
                nameY + (8 - GomodSocialPresenceIconTextures.DOCK_DRAW_SIZE) / 2, presenceKind(name, mc));
        fr.drawString(EnumChatFormatting.WHITE + name, textX, nameY,
                selected ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT);
        fr.drawString(GomodSocialChat.friendSubtitle(name, mc), textX, t + 19, SocialTheme.TEXT_MUTED);

        if (unread > 0) {
            drawBadge(fr, r - 26, t + 9, unread);
        }
    }

    public static void drawGroupRow(FontRenderer fr, Minecraft mc, GomodSocialTempGroups.Group g, int l, int t, int r,
            boolean selected, boolean hover, int mx, int my) {
        int rowH = SocialTheme.DOCK_ROW_H;
        int b = t + rowH - 2;
        if (selected) {
            SocialDraw.bevelPanel(l, t + 1, r, b, SocialTheme.RADIUS_SM, SocialTheme.ROW_SEL);
            SocialDraw.fillRect(l + 2, t + 4, l + 4, b - 3, SocialTheme.ROW_SEL_BAR);
        } else if (hover) {
            SocialDraw.fillRect(l, t + 1, r, b, SocialTheme.ROW_HOVER);
        }

        int head = 24;
        int hx = l + 8;
        int hy = t + (rowH - head) / 2;
        PlayerHeadTextureUtil.drawMinotarAvatar(GomodSocialTempGroups.creatorName(g), hx, hy, head, 1);

        int textX = hx + head + 8;
        fr.drawString(EnumChatFormatting.WHITE + g.name, textX, t + 8, SocialTheme.TEXT);
        long hrs = GomodSocialTempGroups.hoursUntilExpiry(g);
        fr.drawString(EnumChatFormatting.GOLD + "\u23F1 " + hrs + "h left", textX, t + 19, SocialTheme.TEMP);
    }

    public static void drawThreadDockRow(FontRenderer fr, GomodSocialForums.ThreadEntry entry, int l, int t, int r,
            boolean selected, boolean hover) {
        if (entry == null || entry.thread == null) {
            return;
        }
        GomodSocialForums.Thread th = entry.thread;
        int rowH = SocialTheme.FORUM_ROW_H;
        int b = t + rowH - 2;
        if (selected) {
            SocialDraw.bevelPanel(l, t + 1, r, b, SocialTheme.RADIUS_SM, SocialTheme.ROW_SEL);
            SocialDraw.fillRect(l + 2, t + 4, l + 4, b - 3, SocialTheme.ROW_SEL_BAR);
        } else if (hover) {
            SocialDraw.fillRect(l, t + 1, r, b, SocialTheme.ROW_HOVER);
        }
        fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + th.title,
                l + 12, t + 9, selected ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT);
        fr.drawString(EnumChatFormatting.GRAY + "by " + th.author + " \u00b7 " + th.posts.size() + " replies",
                l + 12, t + 23, SocialTheme.TEXT_DIM);
    }

    /** Compact action pill (ShareInv, Screenshot, etc.) */
    public static void drawActionPill(FontRenderer fr, int l, int t, int r, int b, String text, boolean hover) {
        drawActionPill(fr, l, t, r, b, text, hover ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT, hover);
    }

    public static void drawActionPill(FontRenderer fr, int l, int t, int r, int b, String text, int textColor,
            boolean hover) {
        float radius = (b - t) * 0.5f;
        int fill = hover ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
        SocialDraw.roundedRect(l, t, r, b, radius, fill);
        drawCenteredLabel(fr, l, t, r, b, text, textColor);
    }

    /** Low-profile friend actions keep the conversation as the primary surface. */
    public static void drawCompactChatAction(FontRenderer fr,int l,int t,int r,int b,String text,int textColor,boolean hover,GomodSocialClickableButtonTextures.Style style){
        if(r-l<12)return;
        int fill=actionButtonFill(style,hover),outline=actionButtonOutline(style,hover);
        SocialDraw.buttonChrome(l,t,r,b,buttonRadius(l,t,r,b),fill,0,outline,false);
        SocialDraw.fillRect(l+2,t+1,r-2,t+2,hover?0x26FFFFFF:0x16FFFFFF);
        String label=fr.getStringWidth(text)>r-l-8?fr.trimStringToWidth(text,Math.max(1,r-l-14))+"…":text;
        drawCenteredLabel(fr,l,t,r,b,label,actionButtonTextColor(style));
    }

    /** Social chat actions — semantic Social buttons (Send / ShareInv / Screenshot / Remove friend). */
    public static void drawClickableActionButton(FontRenderer fr, int l, int t, int r, int b, String text,
            int textColor, boolean hover, GomodSocialClickableButtonTextures.Style style) {
        int fill = actionButtonFill(style, hover);
        int outline = actionButtonOutline(style, hover);
        SocialDraw.buttonChrome(l, t, r, b, buttonRadius(l, t, r, b), fill, 0, outline, false);
        SocialDraw.fillRect(l + 2, t + 1, r - 2, t + 2, hover ? 0x26FFFFFF : 0x16FFFFFF);
        drawCenteredLabel(fr, l, t, r, b, text, actionButtonTextColor(style));
    }

    private static int actionButtonFill(GomodSocialClickableButtonTextures.Style style, boolean hover) {
        if (style == GomodSocialClickableButtonTextures.Style.SEND) {
            return hover ? SocialTheme.ACCENT_BRIGHT : SocialTheme.ACCENT;
        }
        if (style == GomodSocialClickableButtonTextures.Style.SCREENSHOT) {
            return hover ? 0xFF7BB7FF : SocialTheme.INFO;
        }
        if (style == GomodSocialClickableButtonTextures.Style.SHARE_INV) {
            return hover ? 0xFFD7B85C : SocialTheme.TEMP;
        }
        if (style == GomodSocialClickableButtonTextures.Style.REMOVE_FRIEND) {
            return hover ? 0xFFE04A4A : SocialTheme.DANGER;
        }
        return hover ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
    }

    private static int actionButtonOutline(GomodSocialClickableButtonTextures.Style style, boolean hover) {
        if (style == GomodSocialClickableButtonTextures.Style.SEND) {
            return hover ? 0xFF8BE58F : SocialTheme.ACCENT_DARK;
        }
        if (style == GomodSocialClickableButtonTextures.Style.SCREENSHOT) {
            return hover ? 0xFFB8D7FF : 0xFF3F78C0;
        }
        if (style == GomodSocialClickableButtonTextures.Style.SHARE_INV) {
            return hover ? 0xFFFFD77A : 0xFF765C22;
        }
        if (style == GomodSocialClickableButtonTextures.Style.REMOVE_FRIEND) {
            return hover ? 0xFFFF8A8A : 0xFF842D2D;
        }
        return hover ? SocialTheme.BORDER_LIGHT : SocialTheme.BORDER_SUBTLE;
    }

    private static int actionButtonTextColor(GomodSocialClickableButtonTextures.Style style) {
        if (style == GomodSocialClickableButtonTextures.Style.SEND
                || style == GomodSocialClickableButtonTextures.Style.SHARE_INV) {
            return SocialTheme.TEXT_INVERSE;
        }
        return SocialTheme.TEXT_BRIGHT;
    }

    /** gomod Social privacy toggle ({@code enabledbutton.png} / {@code disabledbutton.png}). */
    public static void drawToggleSwitch(int sx, int sy, int sw, int sh, boolean on, boolean hover) {
        if (GomodSocialToggleTextures.draw(sx, sy, on, hover)) {
            return;
        }
        float radius = sh * 0.5f;
        int track = on ? SocialTheme.ACCENT : (hover ? SocialTheme.CARD_HOVER : SocialTheme.CARD);
        SocialDraw.roundedRect(sx, sy, sx + sw, sy + sh, radius, track);
        float knobR  = (sh - 4) * 0.5f;
        float knobCx = on ? sx + sw - sh * 0.5f + 1 : sx + sh * 0.5f - 1;
        float knobCy = sy + sh * 0.5f;
        SocialDraw.circle(knobCx, knobCy, knobR, on ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT_MUTED);
    }

    public static int toggleSwitchWidth() {
        return GomodSocialToggleTextures.drawWidth();
    }

    public static int toggleSwitchHeight() {
        return GomodSocialToggleTextures.drawHeight();
    }

    /** Vanilla menu button click (privacy toggles, etc.). */
    public static void playButtonClickSound(Minecraft mc) {
        if (mc == null || mc.getSoundHandler() == null) {
            return;
        }
        try {
            mc.getSoundHandler().playSound(
                    net.minecraft.client.audio.PositionedSoundRecord.create(
                            new ResourceLocation("gui.button.press"), 1.0F));
        } catch (Throwable ignored) {
        }
    }

    // ============================================================== workspace

    public static void drawWorkspaceBar(FontRenderer fr, int l, int t, int r, int b, String title, String tag) {
        SocialDraw.fillRect(l, t, r, b, 0x40000000);
        SocialDraw.fillRect(l, b - 1, r, b, SocialTheme.BORDER_SUBTLE);
        fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + (title == null ? "Social" : title),
                l + 40, t + (b - t - 8) / 2, SocialTheme.TEXT_BRIGHT);
        if (tag != null && !tag.isEmpty()) {
            int barCenterY = t + (b - t) / 2;
            int rightX = r - 14;
            if (!GomodSocialCircleBubbleTextures.drawLabelRight(fr, rightX, barCenterY, tag)) {
                int tw = fr.getStringWidth(tag) + 16;
                int tx = r - tw - 14;
                int ty = t + (b - t - 14) / 2;
                int by = ty + 14;
                float pillR = (by - ty) / 2f;
                SocialDraw.roundedFill(tx, ty, tx + tw, by, pillR, SocialTheme.TEMP_BG);
                fr.drawString(EnumChatFormatting.GOLD + tag, tx + 8, ty + 3, SocialTheme.TEMP);
            }
        }
    }

    public static WorkspaceTabLayout[] layoutWorkspaceTabs(FontRenderer fr, int l, int w, int requestBadge) {
        String[] labels = { "Chat", "Screenshots", "Capes", "Requests", "Privacy" };
        int padX = 12;
        int gap = 6;
        int cursor = l + padX;
        WorkspaceTabLayout[] tabs = new WorkspaceTabLayout[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int tw = fr.getStringWidth(labels[i]);
            int badgeExtra = (i == 3 && requestBadge > 0) ? circleBadgeWidth(fr, requestBadge) + 4 : 0;
            int tabW = tw + 20 + badgeExtra;
            tabs[i] = new WorkspaceTabLayout(cursor, cursor + tabW);
            cursor += tabW + gap;
        }
        return tabs;
    }

    public static void drawWorkspaceTabs(FontRenderer fr, int l, int t, int w, int active, int requestBadge) {
        SocialDraw.fillRect(l, t, l + w, t + SocialTheme.WORKSPACE_TABS_H, 0x18000000);
        SocialDraw.fillRect(l, t + SocialTheme.WORKSPACE_TABS_H - 1, l + w, t + SocialTheme.WORKSPACE_TABS_H,
                SocialTheme.BORDER_SUBTLE);
        String[] labels = { "Chat", "Screenshots", "Capes", "Requests", "Privacy" };
        WorkspaceTabLayout[] tabs = layoutWorkspaceTabs(fr, l, w, requestBadge);
        int rowH = SocialTheme.WORKSPACE_TABS_H;
        int pillH = 24;
        int pillT = t + (rowH - pillH) / 2;
        for (int i = 0; i < labels.length; i++) {
            WorkspaceTabLayout tab = tabs[i];
            boolean on = i == active;
            int pillL = tab.left;
            int pillR = tab.right;
            int fill = on ? SocialTheme.TAB_ON : SocialTheme.PANEL_INNER;
            SocialDraw.roundedFill(pillL, pillT, pillR, pillT + pillH, SocialTheme.RADIUS_SM, fill);
            if (on) {
                SocialDraw.fillRect(pillL + 8, pillT + pillH - 2, pillR - 8, pillT + pillH, SocialTheme.TAB_ACCENT);
            }
            String plain = labels[i];
            int labelW = fr.getStringWidth(plain);
            int textX = pillL + 10;
            int textY = pillT + (pillH - 8) / 2;
            fr.drawString((on ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY) + plain,
                    textX, textY, on ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT_MUTED);
            if (i == 3 && requestBadge > 0) {
                drawInlineCountBadge(fr, textX + labelW, pillT, pillR, pillT + pillH, requestBadge);
            }
        }
    }

    public static int workspaceTabAt(FontRenderer fr, int mx, int my, int l, int t, int w, int requestBadge) {
        if (my < t || my > t + SocialTheme.WORKSPACE_TABS_H) {
            return -1;
        }
        WorkspaceTabLayout[] tabs = layoutWorkspaceTabs(fr, l, w, requestBadge);
        for (int i = 0; i < tabs.length; i++) {
            if (mx >= tabs[i].left && mx < tabs[i].right) {
                return i;
            }
        }
        return -1;
    }

    public static void drawExpiryBanner(FontRenderer fr, int l, int t, int r, GomodSocialTempGroups.Group g) {
        int b = t + SocialTheme.EXPIRY_BANNER_H;
        SocialDraw.roundedFill(l + 4, t + 2, r - 4, b - 2, SocialTheme.RADIUS_SM, SocialTheme.TEMP_BG);
        fr.drawString(EnumChatFormatting.GOLD + "\u23F1 " + GomodSocialTempGroups.expiryHint(g),
                l + 14, t + (SocialTheme.EXPIRY_BANNER_H - 8) / 2, SocialTheme.TEMP);
    }

    public static void drawCard(int l, int t, int r, int b) {
        SocialDraw.dropShadow(l, t, r, b, 5, SocialTheme.SHADOW);
        SocialDraw.roundedRect(l, t, r, b, SocialTheme.RADIUS_MD, SocialTheme.CARD);
    }

    public static void drawCardElevated(int l, int t, int r, int b) {
        SocialDraw.dropShadow(l, t, r, b, 8, SocialTheme.SHADOW);
        SocialDraw.roundedRect(l, t, r, b, SocialTheme.RADIUS_LG, SocialTheme.CARD_ELEVATED);
    }

    /** Legacy compat. */
    public static void drawBevelRect(int l, int t, int r, int b, int fill) {
        SocialDraw.roundedRect(l, t, r, b, SocialTheme.RADIUS_MD, fill);
    }

    // ============================================================== misc

    public static void drawScrollbar(int trackR, int top, int height, int contentH, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int x = trackR - 4;
        SocialDraw.roundedRect(x, top, x + 3, top + height, 2, SocialTheme.SCROLL_TRACK);
        int thumbH = Math.max(18, height * height / Math.max(1, contentH));
        int thumbY = top + (int) ((long) (height - thumbH) * scroll / maxScroll);
        SocialDraw.roundedRect(x - 1, thumbY, x + 4, thumbY + thumbH, 2, SocialTheme.SCROLL_THUMB);
    }

    public static void drawBadge(FontRenderer fr, int x, int y, int n) {
        String s = n > 99 ? "99+" : String.valueOf(n);
        int tw = fr.getStringWidth(s);
        int diam = Math.max(14, tw + 6);
        drawCircleCountBadge(fr, x + diam, y + 7, n);
    }

    /** Online / away / offline badge on friend avatars (textures with circle fallback). */
    public static void drawPresenceRing(int x, int y, int kind) {
        if (GomodSocialPresenceIconTextures.drawDockBadge(x, y, kind)) {
            return;
        }
        int color = kind == 1 ? SocialTheme.ONLINE : (kind == 2 ? SocialTheme.AWAY : SocialTheme.OFFLINE);
        float cx = x + 3;
        float cy = y + 3;
        if (kind == 1) {
            SocialDraw.smoothCircle(cx, cy, 5.0f, SocialDraw.withAlpha(color, 55));
        }
        SocialDraw.smoothCircle(cx, cy, 3.0f, color);
    }

    private static float buttonRadius(int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (w <= h + 6) {
            return Math.min(SocialTheme.RADIUS_MD, Math.max(2.0f, h * 0.22f));
        }
        return Math.min(SocialTheme.RADIUS_BTN, Math.max(SocialTheme.RADIUS_MD, h * 0.32f));
    }

    private static void drawCenteredLabel(FontRenderer fr, int l, int t, int r, int b, String text, int color) {
        int tw = fr.getStringWidth(text);
        fr.drawString(text, l + (r - l - tw) / 2, t + (b - t - 8) / 2, color);
    }

    private static void drawCenteredGlyph(FontRenderer fr, int l, int t, int r, int b, String glyph, int color) {
        int tw = fr.getStringWidth(glyph);
        fr.drawString(glyph, l + (r - l - tw) / 2, t + (b - t - 8) / 2, color);
    }

    /** Legacy dot (kept for compatibility). */
    public static void drawStatusDot(int x, int y, int kind) {
        drawPresenceRing(x, y, kind);
    }

    /** 0 offline, 1 online, 2 away */
    public static int presenceKind(String name, Minecraft mc) {
        if (FriendListScanner.isSocialOnline(name, mc)) {
            String live = FriendListScanner.resolveGomodFriendStatusLabel(name, mc);
            if (live != null && live.contains("Away") && !live.contains("Online")) {
                return 2;
            }
            return 1;
        }
        String live = FriendListScanner.resolveGomodFriendStatusLabel(name, mc);
        if (live != null && live.contains("Away")) {
            return 2;
        }
        return 0;
    }

    public static String presenceLabel(String name, Minecraft mc) {
        int k = presenceKind(name, mc);
        if (k == 1) {
            return EnumChatFormatting.GREEN + "Online";
        }
        if (k == 2) {
            return EnumChatFormatting.YELLOW + "Away";
        }
        return EnumChatFormatting.GRAY + "Offline";
    }

    public static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ============================================================== inputs

    /** Minimal text field drawer (transparent, draws own caret via vanilla GuiTextField). */
    public static final class GuiTextFieldStyled {
        public final net.minecraft.client.gui.GuiTextField field;

        public GuiTextFieldStyled(int id, FontRenderer fr, int maxLen) {
            field = new net.minecraft.client.gui.GuiTextField(id, fr, 0, 0, 100, 18);
            field.setMaxStringLength(maxLen);
            field.setCanLoseFocus(true);
            field.setEnableBackgroundDrawing(false);
        }

        public void draw(FontRenderer fr, int x, int y, int w, String placeholder) {
            layoutTextField(field, x, y, x + w, y + 18);
            drawInLayout(fr, placeholder);
        }

        public void drawInLayout(FontRenderer fr, String placeholder) {
            if (field.getText().isEmpty() && !field.isFocused()) {
                fr.drawString(placeholder, field.xPosition, field.yPosition, SocialTheme.TEXT_DIM);
            }
            field.drawTextBox();
        }
    }
}
