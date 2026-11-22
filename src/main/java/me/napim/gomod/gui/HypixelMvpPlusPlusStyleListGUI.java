package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * All distinct MVP++ base × ++ color combinations (dedupes identical previews, e.g. GOLD vs DEFAULT).
 */
public class HypixelMvpPlusPlusStyleListGUI extends GuiScreen {

    private static final class Row {
        final String base;
        final String sign;

        Row(String base, String sign) {
            this.base = base;
            this.sign = sign;
        }
    }

    private static final Row[] ROWS = buildAllRows();

    /** Base and sign color keys for each distinct MVP++ bracket preview (deduped). */
    public static String[][] getUniqueMvpPlusPlusPairs() {
        String[][] a = new String[ROWS.length][2];
        for (int i = 0; i < ROWS.length; i++) {
            a[i][0] = ROWS[i].base;
            a[i][1] = ROWS[i].sign;
        }
        return a;
    }

    private static boolean isGoldFamilyBase(String base) {
        return "GOLD".equals(base) || "DEFAULT".equals(base);
    }

    private static boolean isAquaFamilyBase(String base) {
        return "AQUA".equals(base);
    }

    private static boolean isDarkAquaFamilyBase(String base) {
        return "DARK_AQUA".equals(base);
    }

    private static int baseSortGroup(String base) {
        if (isGoldFamilyBase(base)) {
            return 0;
        }
        if (isAquaFamilyBase(base)) {
            return 1;
        }
        if (isDarkAquaFamilyBase(base)) {
            return 2;
        }
        return 3;
    }

    private static int signSortGroup(String sign) {
        if (isGoldFamilyBase(sign)) {
            return 0;
        }
        if (isAquaFamilyBase(sign)) {
            return 1;
        }
        if (isDarkAquaFamilyBase(sign)) {
            return 2;
        }
        return 3;
    }

    private static String[] sortedMvpPpKeys() {
        String[] k = HypixelPrefixSettingsGUI.MVP_PLUS_PLUS_COLORS.clone();
        Arrays.sort(k, Comparator
                .comparingInt(HypixelMvpPlusPlusStyleListGUI::baseSortGroup)
                .thenComparing(a -> a));
        return k;
    }

    private static Row[] buildAllRows() {
        String[] keys = sortedMvpPpKeys();
        List<Row> tmp = new ArrayList<>();
        for (String base : keys) {
            for (String sign : keys) {
                tmp.add(new Row(base, sign));
            }
        }
        tmp.sort(Comparator
                .comparingInt((Row r) -> baseSortGroup(r.base))
                .thenComparing(r -> r.base)
                .thenComparingInt((Row r) -> signSortGroup(r.sign))
                .thenComparing(r -> r.sign));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Row> out = new ArrayList<>();
        for (Row r : tmp) {
            String pv = LocalDisguise.previewOfficialMvpPlusPlusBracket(r.base, r.sign);
            if (seen.add(pv)) {
                out.add(r);
            }
        }
        return out.toArray(new Row[0]);
    }

    private static int rowDotColor(Row r) {
        if (isGoldFamilyBase(r.base)) {
            return 0xFFFFAA00;
        }
        if (isAquaFamilyBase(r.base) || isDarkAquaFamilyBase(r.base)) {
            return 0xFF55FFFF;
        }
        return 0xFF888888;
    }

    private static final int ROW_H = 12;
    private static final int SCROLL_STEP = 3;
    private static final int PANEL_PAD = 6;
    private static final int TEXT_DY = 3;

    private final GuiScreen parent;
    private int scrollOffset;
    private int maxScroll;
    private int rowsVisible = 12;
    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int listInnerLeft;
    private int firstRowTop;

    public HypixelMvpPlusPlusStyleListGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.scrollOffset = 0;
        layoutPanel();
        this.maxScroll = Math.max(0, ROWS.length - this.rowsVisible);
        this.buttonList.add(new GuiButton(0, this.width / 2 - 60, this.height - 30, 120, 20,
                EnumChatFormatting.GRAY + "Back"));
    }

    private void layoutPanel() {
        int cx = this.width / 2;
        this.panelLeft = cx - 158;
        this.panelRight = cx + 158;
        this.panelTop = 58;
        int avail = this.height - this.panelTop - 42;
        this.rowsVisible = Math.max(10, Math.min(24, avail / ROW_H));
        int listH = PANEL_PAD + this.rowsVisible * ROW_H + PANEL_PAD;
        this.panelBottom = this.panelTop + listH;
        this.listInnerLeft = this.panelLeft + 5;
        this.firstRowTop = this.panelTop + PANEL_PAD;
        this.maxScroll = Math.max(0, ROWS.length - this.rowsVisible);
        this.scrollOffset = Math.min(this.scrollOffset, this.maxScroll);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            layoutPanel();
            if (wheel > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - SCROLL_STEP);
            } else {
                this.scrollOffset = Math.min(this.maxScroll, this.scrollOffset + SCROLL_STEP);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }
        layoutPanel();
        int end = Math.min(this.scrollOffset + this.rowsVisible, ROWS.length);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowIdx = i - this.scrollOffset;
            int rowTop = this.firstRowTop + rowIdx * ROW_H;
            if (mouseY >= rowTop && mouseY < rowTop + ROW_H
                    && mouseX >= this.panelLeft && mouseX <= this.panelRight) {
                Row r = ROWS[i];
                ConfigManager.setDisguiseHypixelMvpPpBaseColor(r.base);
                ConfigManager.setDisguiseHypixelMvpPpSignColor(r.sign);
                LocalDisguise.refreshHypixelRankPresetFromConfig();
                goBack();
                return;
            }
        }
    }

    private void goBack() {
        if (this.parent instanceof HypixelPrefixSettingsGUI) {
            ((HypixelPrefixSettingsGUI) this.parent).syncAfterOfficialPick();
        }
        this.mc.displayGuiScreen(this.parent);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button != null && button.id == 0) {
            goBack();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        layoutPanel();
        int cx = this.width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.LIGHT_PURPLE + "MVP++ (gold / teal = Hypixel-style bases · gray = extra)",
                cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Click · scroll wheel", cx, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);

        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, 0x40FFFFFF);
        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + 1, 0x88A0A0A0);
        drawRect(this.panelLeft, this.panelBottom - 1, this.panelRight, this.panelBottom, 0x88A0A0A0);
        drawRect(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelBottom, 0x88A0A0A0);
        drawRect(this.panelRight - 1, this.panelTop, this.panelRight, this.panelBottom, 0x88A0A0A0);

        int end = Math.min(this.scrollOffset + this.rowsVisible, ROWS.length);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowIdx = i - this.scrollOffset;
            int rowTop = this.firstRowTop + rowIdx * ROW_H;
            Row r = ROWS[i];
            String preview = LocalDisguise.previewOfficialMvpPlusPlusBracket(r.base, r.sign)
                    + EnumChatFormatting.WHITE + " " + LocalDisguise.getHypixelPrefixPreviewDisplayName();
            boolean hover = mouseX >= this.panelLeft && mouseX <= this.panelRight
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H;
            if (hover) {
                drawRect(this.panelLeft + 1, rowTop, this.panelRight - 1, rowTop + ROW_H, 0x44FFFFFF);
            }
            int textY = rowTop + TEXT_DY;
            int dot = rowDotColor(r);
            drawRect(this.listInnerLeft, textY + 3, this.listInnerLeft + 3, textY + 7, 0xFF000000 | (dot & 0xFFFFFF));
            this.fontRendererObj.drawStringWithShadow(preview, this.listInnerLeft + 6, textY, 0xFFFFFF);
        }

        if (this.maxScroll > 0) {
            this.fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.DARK_GRAY + "(" + (this.scrollOffset + 1) + "–" + end + " / " + ROWS.length + " unique)",
                    this.listInnerLeft, this.panelBottom + 5, 0x888888);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
