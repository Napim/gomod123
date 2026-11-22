package me.napim.gomod.gui;

import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

public class KitsPreviewGUI extends GuiScreen {
    private static final int GRID_COLUMNS = 9;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 4;

    private final GuiScreen parent;
    private final List<String> kitNames;
    private final List<ItemStack> previewStacks = new ArrayList<>();
    private int previewLevel;

    private GuiButton previousLevelButton;
    private GuiButton nextLevelButton;
    private GuiButton closeButton;

    public KitsPreviewGUI(GuiScreen parent, int previewLevel) {
        this.parent = parent;
        this.kitNames = DisguiseBlitzKitSelectorOverride.getPreviewKitOrder();
        this.previewLevel = clampPreviewLevel(previewLevel);
        rebuildPreviewStacks();
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int centerX = width / 2;
        int buttonY = height - 30;
        previousLevelButton = new GuiButton(0, centerX - 90, buttonY, 20, 20, "<");
        closeButton = new GuiButton(1, centerX - 50, buttonY, 100, 20, "Done");
        nextLevelButton = new GuiButton(2, centerX + 70, buttonY, 20, 20, ">");

        buttonList.add(previousLevelButton);
        buttonList.add(closeButton);
        buttonList.add(nextLevelButton);
        updateLevelButtons();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }

        switch (button.id) {
            case 0:
                changePreviewLevel(-1);
                break;
            case 1:
                mc.displayGuiScreen(parent);
                break;
            case 2:
                changePreviewLevel(1);
                break;
            default:
                break;
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_LEFT) {
            changePreviewLevel(-1);
            return;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            changePreviewLevel(1);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        int centerX = width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.DARK_AQUA + "" + EnumChatFormatting.BOLD + "Blitz Kit Preview",
                centerX, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Hover a kit to inspect the disguised selector tooltip.",
                centerX, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + "Previewing " + EnumChatFormatting.YELLOW + getPreviewLevelLabel(previewLevel),
                centerX, 56, 0xFFFFFF);

        drawPreviewGrid(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawPreviewGrid(int mouseX, int mouseY) {
        if (previewStacks.isEmpty()) {
            return;
        }

        int gridWidth = GRID_COLUMNS * SLOT_SIZE + (GRID_COLUMNS - 1) * SLOT_GAP;
        int rows = (previewStacks.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
        int startX = (width - gridWidth) / 2;
        int startY = 68;
        ItemStack hoveredStack = null;

        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();

        for (int i = 0; i < previewStacks.size(); i++) {
            int column = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            int x = startX + column * (SLOT_SIZE + SLOT_GAP);
            int y = startY + row * (SLOT_SIZE + SLOT_GAP);
            boolean hovered = mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE;

            Gui.drawRect(x - 1, y - 1, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, hovered ? 0x80E6C25A : 0x60404040);
            Gui.drawRect(x, y, x + SLOT_SIZE, y + SLOT_SIZE, 0xB0101010);

            ItemStack stack = previewStacks.get(i);
            if (stack != null) {
                mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
                mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, stack, x, y, null);
            }

            if (hovered) {
                hoveredStack = stack;
            }
        }

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();

        int bottom = startY + rows * SLOT_SIZE + Math.max(0, rows - 1) * SLOT_GAP;
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "Use the arrows or Left/Right to change levels.",
                width / 2, bottom + 10, 0xFFFFFF);

        if (hoveredStack != null) {
            renderPreviewTooltip(hoveredStack, mouseX, mouseY);
        }
    }

    private void renderPreviewTooltip(ItemStack stack, int mouseX, int mouseY) {
        if (stack == null) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        renderToolTip(stack, mouseX, mouseY);
        GlStateManager.popMatrix();
    }

    private void changePreviewLevel(int delta) {
        int nextLevel = clampPreviewLevel(previewLevel + delta);
        if (nextLevel == previewLevel) {
            return;
        }

        previewLevel = nextLevel;
        rebuildPreviewStacks();
        updateLevelButtons();
    }

    private void rebuildPreviewStacks() {
        previewStacks.clear();
        for (String kitName : kitNames) {
            previewStacks.add(DisguiseBlitzKitSelectorOverride.buildPreviewStack(kitName, previewLevel));
        }
    }

    private void updateLevelButtons() {
        if (previousLevelButton != null) {
            previousLevelButton.enabled = previewLevel > 1;
        }
        if (nextLevelButton != null) {
            nextLevelButton.enabled = previewLevel < 12;
        }
    }

    private static int clampPreviewLevel(int previewLevel) {
        return Math.max(1, Math.min(12, previewLevel));
    }

    private static String getPreviewLevelLabel(int previewLevel) {
        if (previewLevel == 11) {
            return "Prestige I";
        }
        if (previewLevel == 12) {
            return "Prestige II";
        }

        String[] romanNumerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return "Level " + romanNumerals[Math.max(1, previewLevel) - 1];
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
