package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MobHealthTagEggCatalog;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid of spawn-egg toggles: green = health tag shown, red = hidden for that mob type.
 */
public class MobHealthTagMobPickerGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BTN_ID_OFFSET = 10000;
    private static final int CELL = 20;
    private static final int GAP = 4;
    private static final int COLS = 12;

    private final GuiScreen parent;
    private GuiButton backButton;
    private final List<MobHealthTagIconButton> eggButtons = new ArrayList<>();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private int gridLeftX;
    private int gridPixelHeight;
    private int baseGridTop;

    public MobHealthTagMobPickerGUI(GuiScreen parent) {
        this.parent = parent;
    }

    private void recomputeGridMetrics() {
        List<MobHealthTagEggCatalog.Entry> entries = MobHealthTagEggCatalog.getEntries();
        int gridWidth = COLS * CELL + (COLS - 1) * GAP;
        gridLeftX = width / 2 - gridWidth / 2;
        int rows = Math.max(1, (entries.size() + COLS - 1) / COLS);
        gridPixelHeight = rows * CELL + (rows - 1) * GAP;

        int titleBottom = GuiHeader.STANDARD_TITLE_Y + 12;
        int contentTop = titleBottom + 18;
        int contentBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        int available = contentBottom - contentTop;
        if (gridPixelHeight <= available) {
            baseGridTop = contentTop + (available - gridPixelHeight) / 2;
        } else {
            baseGridTop = contentTop;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        eggButtons.clear();
        recomputeGridMetrics();

        List<MobHealthTagEggCatalog.Entry> entries = MobHealthTagEggCatalog.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            MobHealthTagEggCatalog.Entry entry = entries.get(i);
            int col = i % COLS;
            int row = i / COLS;
            int x = gridLeftX + col * (CELL + GAP);
            int y = baseGridTop + row * (CELL + GAP);
            final int typeId = entry.entityTypeId;
            MobHealthTagIconButton btn = new MobHealthTagIconButton(
                    BTN_ID_OFFSET + typeId, x, y, CELL, CELL, entry.icon.copy(),
                    () -> ConfigManager.isMobHealthDisplayHealthTagEnabledForType(typeId));
            eggButtons.add(btn);
            buttonList.add(btn);
        }

        backButton = new GuiButton(99, width / 2 - 100, GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200,
                BUTTON_HEIGHT, GuiHeader.moduleSettingsBackButtonLabel(this.parent));
        buttonList.add(backButton);
    }

    private void applyScrollLayout() {
        int scrollOff = scroll.getOffset();
        for (int i = 0; i < eggButtons.size(); i++) {
            MobHealthTagIconButton btn = eggButtons.get(i);
            int col = i % COLS;
            int row = i / COLS;
            btn.xPosition = gridLeftX + col * (CELL + GAP);
            btn.yPosition = baseGridTop + row * (CELL + GAP) - scrollOff;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        scroll.tick();

        String title = EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "Health Tag Mobs";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        recomputeGridMetrics();
        applyScrollLayout();

        String hint = EnumChatFormatting.GRAY + "Select which mobs you don't want the health tag to apply to";
        int hintW = fontRendererObj.getStringWidth(hint);
        fontRendererObj.drawString(hint, width / 2 - hintW / 2, baseGridTop - 14, 0xFFFFFF);

        int contentBottom = baseGridTop + gridPixelHeight;
        scroll.updateBounds(baseGridTop, contentBottom, baseGridTop,
                GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10));
        scroll.drawScrollbar(width, mouseY);
        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);

        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }

        drawEggTooltips(mouseX, mouseY);
    }

    private void drawEggTooltips(int mouseX, int mouseY) {
        List<MobHealthTagEggCatalog.Entry> entries = MobHealthTagEggCatalog.getEntries();
        for (int i = 0; i < eggButtons.size() && i < entries.size(); i++) {
            MobHealthTagIconButton btn = eggButtons.get(i);
            if (btn == null || !btn.visible) {
                continue;
            }
            if (mouseX >= btn.xPosition && mouseX < btn.xPosition + btn.width
                    && mouseY >= btn.yPosition && mouseY < btn.yPosition + btn.height) {
                MobHealthTagEggCatalog.Entry entry = entries.get(i);
                boolean enabled = ConfigManager.isMobHealthDisplayHealthTagEnabledForType(entry.entityTypeId);
                String action = enabled ? "Hide health tag" : "Show health tag";
                drawHoveringText(java.util.Arrays.asList(
                        entry.label,
                        EnumChatFormatting.GRAY + action), mouseX, Math.max(0, mouseY - 12));
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id >= BTN_ID_OFFSET) {
            int typeId = button.id - BTN_ID_OFFSET;
            ConfigManager.toggleMobHealthDisplayHealthTagForType(typeId);
            return;
        }
        if (button.id == 99) {
            GomodMenuTransitions.returnToParentOrMainMenu(this.mc, this.parent);
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) {
            GomodMenuTransitions.returnToParentOrMainMenu(this.mc, this.parent);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
