package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzChallengeBadgeEquip;
import me.napim.gomod.features.BlitzChallenges;
import me.napim.gomod.features.BlitzChallenges.ChallengeId;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Browse, start, and track Blitz SG tiered self-challenges.
 */
public class BlitzChallengesGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int COL_PANEL_BG = 0xD0101216;
    private static final int COL_PANEL_BORDER = 0xFFCC7722;
    private static final int COL_PANEL_BORDER_DIM = 0xFF4A3828;
    private static final int COL_ACTIVE_BG = 0x90331808;
    private static final int COL_ROW_HOVER = 0x35FFFFFF;
    private static final int COL_ROW_SELECTED = 0x45CC7722;
    private static final int COL_TIER_DONE = 0x4022AA44;
    private static final int COL_TIER_NEXT = 0x50CC9922;

    private static final int PAD = 12;
    private static final int GAP = 10;
    private static final int BTN_H = 20;
    private static final int LIST_ROW_H = 22;
    private static final int LIST_ICON_SIZE = 16;
    private static final int LIST_ICON_PAD = 3;
    private static final int LIST_PROGRESS_RIGHT_PAD = 8;
    /** Detail header row: fits 16px icon + gap before meta line. */
    private static final int DETAIL_TITLE_ROW_H = LIST_ICON_SIZE + 8;
    private static final int TIER_ROW_H = 18;
    private static final int FOOTER_H = 28;

    private static final int ID_BACK = 0;
    private static final int ID_HUD = 1;
    private static final int ID_CANCEL = 2;
    private static final int ID_START = 3;
    private static final int ID_BADGES = 4;

    private final GuiScreen parent;
    private ChallengeId selected = ChallengeId.BLOOD_HOUR;

    private final GuiSmoothScroll listScroll = new GuiSmoothScroll().setWheelTuning(14, 56, 2);
    private final GuiSmoothScroll tierScroll = new GuiSmoothScroll().setWheelTuning(12, 48, 2);

    /** Cached layout for the current frame (panels + text anchors). */
    private Layout layout = new Layout();

    public BlitzChallengesGUI(GuiScreen parent) {
        this.parent = parent;
    }

    public BlitzChallengesGUI(GuiScreen parent, ChallengeId initialSelection) {
        this.parent = parent;
        if (initialSelection != null) {
            this.selected = initialSelection;
        }
    }

    @Override
    public void initGui() {
        buttonList.clear();
        layout = computeLayout();

        int centerX = width / 2;
        int btnCount = 4;
        int btnW = Math.min(108, Math.max(72, (width - 48) / btnCount - 8));
        int btnGap = 8;
        int totalW = btnCount * btnW + (btnCount - 1) * btnGap;
        int startX = centerX - totalW / 2;
        int footerY = layout.footerY;

        buttonList.add(new GuiButton(ID_BACK, startX, footerY, btnW, BTN_H,
                EnumChatFormatting.GRAY + "Back"));
        buttonList.add(new GuiButton(ID_HUD, startX + btnW + btnGap, footerY, btnW, BTN_H, hudButtonLabel()));
        buttonList.add(new GuiButton(ID_BADGES, startX + (btnW + btnGap) * 2, footerY, btnW, BTN_H, badgesButtonLabel()));
        buttonList.add(new GuiButton(ID_CANCEL, startX + (btnW + btnGap) * 3, footerY, btnW, BTN_H,
                EnumChatFormatting.RED + "Cancel challenge"));

        int startBtnW = Math.min(200, layout.detailRight - layout.detailLeft - PAD * 2);
        int startBtnX = layout.detailLeft + (layout.detailRight - layout.detailLeft - startBtnW) / 2;
        GuiButton start = new GuiButton(ID_START, startBtnX, layout.startButtonY, startBtnW, BTN_H, startButtonLabel());
        start.enabled = canStartSelected();
        buttonList.add(start);

        updateCancelButton();
        refreshScrollBounds();
    }

    private int listHeaderHeight() {
        // "Challenges" + "Click to select" + gap before first row
        return PAD + fontRendererObj.FONT_HEIGHT + 2 + fontRendererObj.FONT_HEIGHT + 10;
    }

    private int listContentTop() {
        return layout.listTop + listHeaderHeight();
    }

    private void refreshScrollBounds() {
        layout = computeLayout();

        ChallengeId[] all = ChallengeId.values();
        int listContentTop = listContentTop();
        int listContentBottom = listContentTop + all.length * LIST_ROW_H;
        listScroll.updateBounds(listContentTop, listContentBottom, listContentTop, layout.listBottom - PAD);

        int tierCount = selected != null ? selected.getTierCount() : 0;
        int tierContentBottom = layout.tierListTop + tierCount * TIER_ROW_H + 4;
        tierScroll.updateBounds(layout.tierListTop, tierContentBottom, layout.tierListTop, layout.tierListBottom);
    }

    private Layout computeLayout() {
        Layout l = new Layout();
        int margin = Math.max(10, width / 36);
        int top = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj) + 6;
        int bottom = GuiHeader.backButtonY(height, BTN_H) - FOOTER_H - 14;
        l.footerY = bottom + 6;

        int totalW = width - margin * 2;
        int listW = Math.max(148, Math.min(220, totalW * 2 / 5));
        l.listLeft = margin;
        l.listRight = margin + listW;
        l.listTop = top;
        l.listBottom = bottom;

        l.detailLeft = l.listRight + GAP;
        l.detailRight = width - margin;
        l.detailTop = top;
        l.detailBottom = bottom;

        int cursorY = l.detailTop + PAD;
        if (BlitzChallenges.hasActiveChallenge()) {
            l.activeBannerTop = cursorY;
            List<String> hudLines = BlitzChallenges.buildHudLines();
            int bannerH = 14 + fontRendererObj.FONT_HEIGHT
                    + hudLines.size() * (fontRendererObj.FONT_HEIGHT + 2) + 8;
            l.activeBannerBottom = cursorY + bannerH;
            cursorY = l.activeBannerBottom + PAD;
        } else {
            l.activeBannerTop = 0;
            l.activeBannerBottom = 0;
        }

        l.detailTitleY = cursorY;
        cursorY += DETAIL_TITLE_ROW_H;

        l.detailMetaY = cursorY;
        cursorY += fontRendererObj.FONT_HEIGHT + 6;

        if (selected != null && !BlitzChallenges.isFullyCompleted(selected)) {
            int next = BlitzChallenges.getNextAvailableTierIndex(selected);
            List<String> descLines = wrapColoredLines(
                    EnumChatFormatting.YELLOW + selected.formatDescription(next),
                    l.detailRight - l.detailLeft - PAD * 2);
            l.detailDescY = cursorY;
            l.detailDescLineCount = descLines.size();
            cursorY += descLines.size() * (fontRendererObj.FONT_HEIGHT + 2) + 8;
        } else {
            l.detailDescY = 0;
            l.detailDescLineCount = 0;
        }

        if (selected != null && BlitzChallenges.isFullyCompleted(selected)) {
            l.detailCompleteY = cursorY;
            cursorY += fontRendererObj.FONT_HEIGHT + 10;
        } else {
            l.detailCompleteY = 0;
        }

        if (selected != null && selected.isDodgeSensitive()) {
            l.dodgeWarningY = cursorY;
            cursorY += fontRendererObj.FONT_HEIGHT + 6;
        } else {
            l.dodgeWarningY = 0;
        }

        l.tierListTop = cursorY;
        l.startButtonY = l.detailBottom - BTN_H - 6;
        l.tierListBottom = l.startButtonY - 10;
        return l;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Blitz Challenges",
                width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Tiered goals  \u00b7  "
                        + EnumChatFormatting.GOLD + BlitzChallengeBadgeEquip.getUnlockedBadgeCount()
                        + EnumChatFormatting.GRAY + "/7 badges",
                width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        layout = computeLayout();
        listScroll.tick();
        tierScroll.tick();

        drawPanel(layout.listLeft, layout.listTop, layout.listRight, layout.listBottom, COL_PANEL_BORDER_DIM);
        drawPanel(layout.detailLeft, layout.detailTop, layout.detailRight, layout.detailBottom, COL_PANEL_BORDER);

        drawListPanelHeader();
        HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        try {
            drawChallengeList(mouseX, mouseY);
            drawDetailPanel(mouseX, mouseY);
        } finally {
            HudGuiItemIcon.endGuiScreenItemBatch(mc);
        }

        listScroll.drawScrollbarAt(layout.listRight - 5, mouseY, 3, COL_PANEL_BORDER_DIM, 0xE8FFAA66);
        if (tierScroll.hasScroll()) {
            tierScroll.drawScrollbarAt(layout.detailRight - 5, mouseY, 3, COL_PANEL_BORDER_DIM, 0xE8FFAA66);
        }

        for (GuiButton b : buttonList) {
            b.drawButton(mc, mouseX, mouseY);
        }
    }

    private void drawListPanelHeader() {
        int x = layout.listLeft + PAD;
        int y = layout.listTop + PAD;
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + "Challenges", x, y, 0xFFFFFF);
        fontRendererObj.drawString(
                EnumChatFormatting.DARK_GRAY + "Click to select",
                x, y + fontRendererObj.FONT_HEIGHT + 2, 0x888888);
    }

    private void drawChallengeList(int mouseX, int mouseY) {
        ChallengeId[] all = ChallengeId.values();
        int x = layout.listLeft + PAD;
        int w = layout.listRight - layout.listLeft - PAD * 2;
        int contentTop = listContentTop();
        int y = contentTop - listScroll.getOffset();

        for (int i = 0; i < all.length; i++) {
            ChallengeId id = all[i];
            if (y + LIST_ROW_H < contentTop || y > layout.listBottom - PAD) {
                y += LIST_ROW_H;
                continue;
            }

            boolean hover = mouseX >= x && mouseX <= x + w
                    && mouseY >= y && mouseY < y + LIST_ROW_H - 2;
            boolean sel = id == selected;
            if (sel) {
                Gui.drawRect(x, y, x + w, y + LIST_ROW_H - 2, COL_ROW_SELECTED);
            } else if (hover) {
                Gui.drawRect(x, y, x + w, y + LIST_ROW_H - 2, COL_ROW_HOVER);
            }

            String status;
            if (BlitzChallenges.isFullyCompleted(id)) {
                status = EnumChatFormatting.GREEN + "\u2713";
            } else if (BlitzChallenges.hasActiveChallenge() && id == BlitzChallenges.getActiveChallenge()) {
                status = EnumChatFormatting.AQUA + "\u25B6";
            } else {
                status = EnumChatFormatting.DARK_GRAY + "\u2022";
            }

            int tx = x + 4;
            fontRendererObj.drawStringWithShadow(status, tx, y + 6, 0xFFFFFF);
            tx += 10;

            int iconX = tx;
            int iconY = y + (LIST_ROW_H - LIST_ICON_SIZE) / 2 - 1;
            ItemStack icon = id.createIconStack();
            HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, icon, iconX, iconY);
            tx = iconX + LIST_ICON_SIZE + LIST_ICON_PAD;

            String nameColor = sel ? EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD
                    : EnumChatFormatting.WHITE + "";
            fontRendererObj.drawStringWithShadow(nameColor + id.getDisplayName(), tx, y + 6, 0xFFFFFF);

            String progress = EnumChatFormatting.GRAY + ""
                    + BlitzChallenges.getCompletedTierCount(id) + "/" + id.getTierCount();
            int progressW = fontRendererObj.getStringWidth(progress);
            fontRendererObj.drawStringWithShadow(progress, x + w - progressW - LIST_PROGRESS_RIGHT_PAD,
                    y + 6, 0xFFFFFF);

            y += LIST_ROW_H;
        }
    }

    private void drawDetailPanel(int mouseX, int mouseY) {
        if (selected == null) {
            return;
        }

        if (BlitzChallenges.hasActiveChallenge()) {
            drawActiveBanner();
        }

        int textX = layout.detailLeft + PAD;
        int maxW = layout.detailRight - layout.detailLeft - PAD * 2;
        int titleTextX = textX + LIST_ICON_SIZE + LIST_ICON_PAD + 2;
        int detailIconY = layout.detailTitleY + (fontRendererObj.FONT_HEIGHT - LIST_ICON_SIZE) / 2;

        HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, selected.createIconStack(), textX, detailIconY);
        fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + selected.getDisplayName(),
                titleTextX, layout.detailTitleY, 0xFFFFFF);

        StringBuilder meta = new StringBuilder();
        meta.append(EnumChatFormatting.GRAY).append("Solo & Teams");
        String time = selected.formatTimeLimitLabel();
        if (time != null) {
            meta.append(EnumChatFormatting.DARK_GRAY).append("  \u00b7  ")
                    .append(EnumChatFormatting.DARK_AQUA).append(time);
        }
        fontRendererObj.drawStringWithShadow(meta.toString(), textX, layout.detailMetaY, 0xFFFFFF);

        if (layout.detailDescLineCount > 0) {
            int next = BlitzChallenges.getNextAvailableTierIndex(selected);
            List<String> lines = wrapColoredLines(
                    EnumChatFormatting.YELLOW + selected.formatDescription(next), maxW);
            int dy = layout.detailDescY;
            for (String line : lines) {
                fontRendererObj.drawStringWithShadow(line, textX, dy, 0xFFFFFF);
                dy += fontRendererObj.FONT_HEIGHT + 2;
            }
        } else if (layout.detailCompleteY > 0) {
            fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.GREEN + "All tiers completed — badge unlocked!",
                    textX, layout.detailCompleteY, 0xFFFFFF);
        }

        if (layout.dodgeWarningY > 0) {
            fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.DARK_RED + "Dodging a queue fails this challenge.",
                    textX, layout.dodgeWarningY, 0xFFFFFF);
        }

        drawTierRows(textX, maxW, mouseX, mouseY);
    }

    private void drawActiveBanner() {
        int left = layout.detailLeft + PAD;
        int right = layout.detailRight - PAD;
        int top = layout.activeBannerTop;
        int bottom = layout.activeBannerBottom;

        Gui.drawRect(left, top, right, bottom, COL_ACTIVE_BG);
        Gui.drawRect(left, top, right, top + 1, COL_PANEL_BORDER);

        ChallengeId active = BlitzChallenges.getActiveChallenge();
        fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GREEN + "ACTIVE  "
                        + EnumChatFormatting.GOLD + active.getDisplayName()
                        + EnumChatFormatting.YELLOW + "  " + BlitzChallenges.formatTierRoman(BlitzChallenges.getActiveTierIndex()),
                left + 6, top + 5, 0xFFFFFF);

        int lineY = top + 5 + fontRendererObj.FONT_HEIGHT + 3;
        for (String line : BlitzChallenges.buildHudLines()) {
            if (lineY + fontRendererObj.FONT_HEIGHT > bottom - 4) {
                break;
            }
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + line, left + 6, lineY, 0xFFFFFF);
            lineY += fontRendererObj.FONT_HEIGHT + 2;
        }
    }

    private void drawTierRows(int left, int maxW, int mouseX, int mouseY) {
        int right = left + maxW;
        int next = BlitzChallenges.getNextAvailableTierIndex(selected);
        int y = layout.tierListTop - tierScroll.getOffset();

        for (int i = 0; i < selected.getTierCount(); i++) {
            if (y + TIER_ROW_H >= layout.tierListTop && y <= layout.tierListBottom) {
                boolean hover = mouseX >= left && mouseX <= right
                        && mouseY >= y && mouseY < y + TIER_ROW_H;
                boolean done = BlitzChallenges.isTierCompleted(selected, i);
                boolean isNext = i == next;

                if (done) {
                    Gui.drawRect(left, y, right, y + TIER_ROW_H - 1, COL_TIER_DONE);
                } else if (isNext) {
                    Gui.drawRect(left, y, right, y + TIER_ROW_H - 1, COL_TIER_NEXT);
                } else if (hover) {
                    Gui.drawRect(left, y, right, y + TIER_ROW_H - 1, COL_ROW_HOVER);
                }

                String icon = done ? EnumChatFormatting.GREEN + "\u2713 "
                        : isNext ? EnumChatFormatting.GOLD + "\u2605 "
                        : EnumChatFormatting.DARK_GRAY + "\u2022 ";
                String label = icon + EnumChatFormatting.WHITE + "Tier "
                        + BlitzChallenges.formatTierRoman(i)
                        + EnumChatFormatting.GRAY + " - "
                        + selected.formatTierTargetLabelColored(i);
                if (isNext && !BlitzChallenges.isFullyCompleted(selected)) {
                    label += EnumChatFormatting.YELLOW + "  (next)";
                }
                fontRendererObj.drawStringWithShadow(label, left + 4, y + 4, 0xFFFFFF);
            }
            y += TIER_ROW_H;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && trySelectChallengeAt(mouseX, mouseY)) {
            return;
        }
        if (listScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (tierScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        listScroll.mouseReleased(state);
        tierScroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    private boolean trySelectChallengeAt(int mouseX, int mouseY) {
        if (mouseX < layout.listLeft + PAD || mouseX > layout.listRight - PAD) {
            return false;
        }
        ChallengeId[] all = ChallengeId.values();
        int contentTop = listContentTop();
        int y = contentTop - listScroll.getOffset();
        for (ChallengeId id : all) {
            if (mouseY >= y && mouseY < y + LIST_ROW_H - 2
                    && mouseY >= contentTop
                    && mouseY <= layout.listBottom - PAD) {
                if (id != selected) {
                    selected = id;
                    tierScroll.reset();
                    initGui();
                }
                return true;
            }
            y += LIST_ROW_H;
        }
        return false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (mx >= layout.listLeft && mx <= layout.listRight
                && my >= layout.listTop && my <= layout.listBottom) {
            listScroll.handleMouseWheel();
        } else if (mx >= layout.detailLeft && mx <= layout.detailRight
                && my >= layout.tierListTop && my <= layout.tierListBottom) {
            tierScroll.handleMouseWheel();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_BACK:
                GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
                break;
            case ID_HUD:
                BlitzChallenges.setHudEnabled(!BlitzChallenges.isHudEnabled());
                button.displayString = hudButtonLabel();
                break;
            case ID_CANCEL:
                BlitzChallenges.stopChallenge();
                initGui();
                break;
            case ID_START:
                if (selected != null && canStartSelected()) {
                    int tier = BlitzChallenges.getNextAvailableTierIndex(selected);
                    if (BlitzChallenges.startChallenge(selected, tier)) {
                        initGui();
                    }
                }
                break;
            case ID_BADGES:
                mc.displayGuiScreen(new BlitzChallengeBadgeGUI(this,
                        selected != null && BlitzChallenges.isFullyCompleted(selected) ? selected : null));
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            GomodMenuTransitions.returnToParentOrMainMenu(mc, parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean canStartSelected() {
        return selected != null
                && !BlitzChallenges.isFullyCompleted(selected)
                && !BlitzChallenges.hasActiveChallenge();
    }

    private String startButtonLabel() {
        if (selected == null) {
            return EnumChatFormatting.GRAY + "Select a challenge";
        }
        if (BlitzChallenges.isFullyCompleted(selected)) {
            return EnumChatFormatting.GREEN + "All tiers complete!";
        }
        if (BlitzChallenges.hasActiveChallenge()) {
            return EnumChatFormatting.DARK_GRAY + "Finish active challenge first";
        }
        int tierIndex = BlitzChallenges.getNextAvailableTierIndex(selected);
        return EnumChatFormatting.GOLD + "Start " + BlitzChallenges.formatTierRoman(tierIndex)
                + EnumChatFormatting.YELLOW + "  ("
                + selected.formatTierTargetLabelColored(tierIndex) + ")";
    }

    private String hudButtonLabel() {
        return EnumChatFormatting.YELLOW + "HUD  "
                + (BlitzChallenges.isHudEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String badgesButtonLabel() {
        int unlocked = BlitzChallengeBadgeEquip.getUnlockedBadgeCount();
        return EnumChatFormatting.GOLD + "Badges  "
                + EnumChatFormatting.AQUA + unlocked + "/7";
    }

    private void updateCancelButton() {
        for (GuiButton b : buttonList) {
            if (b.id == ID_CANCEL) {
                b.enabled = BlitzChallenges.hasActiveChallenge();
                break;
            }
        }
    }

    private void drawPanel(int left, int top, int right, int bottom, int borderColor) {
        Gui.drawRect(left, top, right, bottom, COL_PANEL_BG);
        Gui.drawRect(left, top, right, top + 1, borderColor);
        Gui.drawRect(left, bottom - 1, right, bottom, borderColor);
        Gui.drawRect(left, top, left + 1, bottom, borderColor);
        Gui.drawRect(right - 1, top, right, bottom, borderColor);
    }

    private List<String> wrapColoredLines(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String plain = text.replaceAll("\u00A7.", "");
        String[] words = plain.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fontRendererObj.getStringWidth(trial) > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        if (out.isEmpty()) {
            out.add(plain);
        }
        return out;
    }

    private static final class Layout {
        int listLeft, listTop, listRight, listBottom;
        int detailLeft, detailTop, detailRight, detailBottom;
        int activeBannerTop, activeBannerBottom;
        int detailTitleY, detailMetaY, detailDescY, detailDescLineCount, detailCompleteY;
        int dodgeWarningY;
        int tierListTop, tierListBottom;
        int startButtonY, footerY;
    }
}
