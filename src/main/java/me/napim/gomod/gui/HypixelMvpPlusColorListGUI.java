package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Scrollable MVP+ plus-color picker (Hypixel official disguise), similar interaction to {@link TeamSpamGUI} lists.
 */
public class HypixelMvpPlusColorListGUI extends GuiScreen {

    static final String[] MVP_PLUS_COLORS = HypixelPrefixSettingsGUI.MVP_PLUS_COLORS;

    private static final int SCROLL_STEP = 3;
    private static final int ROW_H = 12;
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

    public HypixelMvpPlusColorListGUI(GuiScreen parent) {
        this.parent = parent;
    }

    private static int mvpPlusKeyDotColor(int indexInPalette, String key) {
        if (indexInPalette <= 4) {
            return 0xFFFFAA00;
        }
        if ("AQUA".equals(key) || "BLUE".equals(key)) {
            return 0xFF55FFFF;
        }
        return 0xFF888888;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.scrollOffset = 0;
        layoutPanel();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 60, this.height - 30, 120, 20,
                EnumChatFormatting.GRAY + "Back"));
        recomputeScrollBounds();
    }

    private void layoutPanel() {
        int cx = this.width / 2;
        this.panelLeft = cx - 148;
        this.panelRight = cx + 148;
        this.panelTop = 58;
        int avail = this.height - this.panelTop - 42;
        this.rowsVisible = Math.max(8, Math.min(22, avail / ROW_H));
        int listH = PANEL_PAD + this.rowsVisible * ROW_H + PANEL_PAD;
        this.panelBottom = this.panelTop + listH;
        this.listInnerLeft = this.panelLeft + 5;
        this.firstRowTop = this.panelTop + PANEL_PAD;
    }

    private void recomputeScrollBounds() {
        layoutPanel();
        int n = MVP_PLUS_COLORS.length;
        this.maxScroll = Math.max(0, n - this.rowsVisible);
        this.scrollOffset = Math.min(this.scrollOffset, this.maxScroll);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            recomputeScrollBounds();
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
        recomputeScrollBounds();
        int end = Math.min(this.scrollOffset + this.rowsVisible, MVP_PLUS_COLORS.length);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowTop = this.firstRowTop + (i - this.scrollOffset) * ROW_H;
            if (mouseY >= rowTop && mouseY < rowTop + ROW_H && mouseX >= this.panelLeft && mouseX <= this.panelRight) {
                ConfigManager.setDisguiseHypixelMvpPlusColor(MVP_PLUS_COLORS[i]);
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
        recomputeScrollBounds();
        int cx = this.width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GOLD + "MVP+ (warm / aqua first = Hypixel-style)",
                cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Click · scroll wheel", cx, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);

        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, 0x40FFFFFF);
        drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + 1, 0x88A0A0A0);
        drawRect(this.panelLeft, this.panelBottom - 1, this.panelRight, this.panelBottom, 0x88A0A0A0);
        drawRect(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelBottom, 0x88A0A0A0);
        drawRect(this.panelRight - 1, this.panelTop, this.panelRight, this.panelBottom, 0x88A0A0A0);

        int end = Math.min(this.scrollOffset + this.rowsVisible, MVP_PLUS_COLORS.length);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowTop = this.firstRowTop + (i - this.scrollOffset) * ROW_H;
            String key = MVP_PLUS_COLORS[i];
            String preview = LocalDisguise.previewOfficialMvpPlusBracket(key)
                    + EnumChatFormatting.WHITE + " " + LocalDisguise.getHypixelPrefixPreviewDisplayName();
            String line = preview + EnumChatFormatting.GRAY + "  " + key;
            boolean hover = mouseX >= this.panelLeft && mouseX <= this.panelRight
                    && mouseY >= rowTop && mouseY < rowTop + ROW_H;
            if (hover) {
                drawRect(this.panelLeft + 1, rowTop, this.panelRight - 1, rowTop + ROW_H, 0x44FFFFFF);
            }
            int textY = rowTop + TEXT_DY;
            int dot = mvpPlusKeyDotColor(i, key);
            drawRect(this.listInnerLeft, textY + 3, this.listInnerLeft + 3, textY + 7, 0xFF000000 | (dot & 0xFFFFFF));
            this.fontRendererObj.drawStringWithShadow(line, this.listInnerLeft + 6, textY, 0xFFFFFF);
        }

        if (this.maxScroll > 0) {
            this.fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.DARK_GRAY + "(" + (this.scrollOffset + 1) + "–" + end + " / " + MVP_PLUS_COLORS.length + ")",
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
