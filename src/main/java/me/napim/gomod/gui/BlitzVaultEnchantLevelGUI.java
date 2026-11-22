package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzVaultEnchantLevelInsights;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;

/**
 * Scrollable enchant level odds (I / II / III …) from merged vault logs.
 */
public final class BlitzVaultEnchantLevelGUI extends GuiScreen {

    private static final int COL_PANEL_BG = 0xC0121A12;
    private static final int COL_PANEL_BORDER = 0xFF2D6B2D;
    private static final int PAD = 12;
    private static final int ROW_H = 13;
    private static final int SECTION_H = 11;
    private static final int HIGHLIGHT_H = 10;
    private static final int FOOTER_H = 36;

    private final GuiScreen parent;
    private BlitzVaultEnchantLevelInsights.Snapshot snapshot;

    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int listTop;
    private int listBottom;
    private int listScroll;
    private int listContentH;

    public BlitzVaultEnchantLevelGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        snapshot = BlitzVaultEnchantLevelInsights.get();
        panelLeft = Math.max(8, width / 2 - 200);
        panelRight = Math.min(width - 8, width / 2 + 200);
        panelTop = 32;
        panelBottom = height - FOOTER_H - 6;
        listTop = panelTop + 42;
        listBottom = panelBottom - 4;
        listContentH = computeContentHeight();
        listScroll = 0;

        buttonList.clear();
        int btnW = Math.min(120, panelRight - panelLeft - PAD * 2);
        buttonList.add(new GuiButton(0, panelLeft + PAD, height - FOOTER_H + 4, btnW, 20,
                EnumChatFormatting.GRAY + "Back"));
    }

    private int computeContentHeight() {
        int h = 0;
        String lastGroup = null;
        for (BlitzVaultEnchantLevelInsights.LevelRow row : snapshot.rows) {
            if (!row.equipGroup.equals(lastGroup)) {
                h += SECTION_H;
                lastGroup = row.equipGroup;
            }
            h += ROW_H;
        }
        return Math.max(ROW_H, h);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawYellowBoldTitleCentered(fontRendererObj, "Enchant Level Odds", width, 6);

        drawPanel(panelLeft, panelTop, panelRight, panelBottom);

        int textX = panelLeft + PAD;
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Level mix by gear type",
                textX, panelTop + 8, 0xFFFFFFFF);
        String sub = snapshot.hasData()
                ? EnumChatFormatting.DARK_GRAY + "" + snapshot.vaultCaptures + " vaults · slot-weighted % per level"
                : EnumChatFormatting.DARK_GRAY + "No observed vault data yet.";
        fontRendererObj.drawString(sub, textX, panelTop + 18, 0xFFFFFFFF);

        int hy = panelTop + 30;
        for (String line : snapshot.highlightLines) {
            if (hy + HIGHLIGHT_H > listTop - 2) {
                break;
            }
            fontRendererObj.drawString(line, textX, hy, 0xFFFFFFFF);
            hy += HIGHLIGHT_H;
        }

        if (!snapshot.hasData()) {
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "Merge vault dumps to populate stats.",
                    textX, listTop + 8, 0xFFFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        int maxScroll = Math.max(0, listContentH - (listBottom - listTop));
        listScroll = clamp(listScroll, 0, maxScroll);
        drawRowList(mouseX, mouseY);
        drawMiniScrollbar(panelRight - PAD - 6, listTop, 3, listBottom - listTop, listScroll, maxScroll);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawRowList(int mouseX, int mouseY) {
        int left = panelLeft + PAD + 2;
        int right = panelRight - PAD - 10;
        scissor(panelLeft + PAD, listTop, Math.max(1, panelRight - panelLeft - PAD * 2 - 8),
                listBottom - listTop);
        int y = listTop - listScroll;
        String lastGroup = null;
        for (BlitzVaultEnchantLevelInsights.LevelRow row : snapshot.rows) {
            if (!row.equipGroup.equals(lastGroup)) {
                if (y + SECTION_H >= listTop && y <= listBottom) {
                    fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "— " + row.equipGroup + " —",
                            left, y, 0xFFFFFFFF);
                }
                y += SECTION_H;
                lastGroup = row.equipGroup;
            }
            if (y + ROW_H >= listTop && y <= listBottom) {
                drawLevelRow(left, right, y, row, mouseX, mouseY);
            }
            y += ROW_H;
        }
        unscissor();
    }

    private void drawLevelRow(int left, int right, int y, BlitzVaultEnchantLevelInsights.LevelRow row,
                              int mouseX, int mouseY) {
        boolean hover = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_H;
        if (hover) {
            Gui.drawRect(left, y, right, y + ROW_H - 1, 0x40282828);
        }
        String levels = BlitzVaultEnchantLevelInsights.formatLevelBreakdownColored(row);
        int levelsW = fontRendererObj.getStringWidth(levels);
        int levelsX = right - levelsW;

        String name = EnumChatFormatting.WHITE + row.enchantName;
        int nameMaxW = Math.max(40, levelsX - left - 8);
        fontRendererObj.drawStringWithShadow(trimToWidth(name, nameMaxW), left, y + 2, 0xFFFFFFFF);
        fontRendererObj.drawStringWithShadow(levels, levelsX, y + 2, 0xFFFFFFFF);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (dWheel == 0) {
            return;
        }
        int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (mx < panelLeft || mx >= panelRight || my < listTop || my >= listBottom) {
            return;
        }
        int maxScroll = Math.max(0, listContentH - (listBottom - listTop));
        int step = ROW_H * Math.max(1, Math.abs(dWheel) / 120);
        if (dWheel > 0) {
            listScroll = Math.max(0, listScroll - step);
        } else {
            listScroll = Math.min(maxScroll, listScroll + step);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawPanel(int left, int top, int right, int bottom) {
        Gui.drawRect(left, top, right, bottom, COL_PANEL_BG);
        drawBorder(left, top, right, bottom, COL_PANEL_BORDER);
    }

    private void drawBorder(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private void drawMiniScrollbar(int trackX, int trackY, int trackW, int trackH, int scrollPx, int maxScrollPx) {
        if (maxScrollPx <= 0 || trackH <= 0) {
            return;
        }
        Gui.drawRect(trackX, trackY, trackX + trackW, trackY + trackH, 0x30FFFFFF);
        int thumbH = Math.max(10, (int) ((float) trackH * ((float) trackH / (float) (trackH + maxScrollPx))));
        thumbH = Math.min(thumbH, trackH);
        int travel = Math.max(1, trackH - thumbH);
        int thumbY = trackY + (int) ((float) travel * ((float) scrollPx / (float) maxScrollPx));
        thumbY = Math.min(trackY + trackH - thumbH, Math.max(trackY, thumbY));
        Gui.drawRect(trackX, thumbY, trackX + trackW, thumbY + thumbH, 0xEFFFFFFF);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private String trimToWidth(String text, int maxPx) {
        if (text == null || maxPx <= 0) {
            return "";
        }
        if (fontRendererObj.getStringWidth(text) <= maxPx) {
            return text;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        String suffix = EnumChatFormatting.RESET + "…";
        while (plain.length() > 1 && fontRendererObj.getStringWidth(plain + suffix) > maxPx) {
            plain = plain.substring(0, plain.length() - 1);
        }
        return plain + suffix;
    }

    private void scissor(int x, int y, int w, int h) {
        try {
            int scale = mc.displayHeight / height;
            int sx = x * scale;
            int sy = mc.displayHeight - (y + h) * scale;
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sx, sy, Math.max(0, w * scale), Math.max(0, h * scale));
        } catch (Throwable ignored) {
        }
    }

    private void unscissor() {
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } catch (Throwable ignored) {
        }
    }
}
