package me.napim.gomod.gui;

import me.napim.gomod.features.BlocksFinder;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.Arrays;

/**
 * Settings screen for the Blocks Finder ESP module.
 */
public class BlocksFinderGUI extends GuiScreen {

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int ROW   = 24;
    private static final int GAP   = 8;

    private final MiscellaneousGUI parent;

    private GuiButton toggleButton;
    private GuiButton manageBlocksButton;
    private GuiButton espModeButton;
    private BlocksFinderIntSlider fillAlphaSlider;
    private BlocksFinderIntSlider worldOpacitySlider;
    private GuiButton worldOpacityKeybindButton;
    private GuiButton backButton;

    private GuiButton draggingSlider;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public BlocksFinderGUI(MiscellaneousGUI parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        relayout(cx);
    }

    private void relayout(int cx) {
        ScaledResolution sr = new ScaledResolution(mc);
        int sh = sr.getScaledHeight();
        int contentH = 16 + (BTN_H + GAP) * 5 + BTN_H + ROW + BTN_H + 12;
        int top = Math.max(8, (sh - contentH) / 2);
        buttonList.clear();
        int x = cx - BTN_W / 2;
        int y = top + 16;

        toggleButton = new GuiButton(1, x, y, BTN_W, BTN_H, getToggleText());
        y += BTN_H + GAP;
        manageBlocksButton = new GuiButton(2, x, y, BTN_W, BTN_H, getManageText());
        y += BTN_H + GAP;
        espModeButton = new GuiButton(3, x, y, BTN_W, BTN_H, getModeText());
        y += BTN_H + GAP;
        fillAlphaSlider = new BlocksFinderIntSlider(5, x, y, BTN_W, BTN_H,
                "Fill opacity", BlocksFinder.FILL_ALPHA_MIN, BlocksFinder.FILL_ALPHA_MAX,
                ConfigManager.getBlocksFinderFillAlpha());
        y += BTN_H + GAP;
        worldOpacitySlider = new BlocksFinderIntSlider(6, x, y, BTN_W, BTN_H,
                "World opacity", BlocksFinder.WORLD_OPACITY_MIN, BlocksFinder.WORLD_OPACITY_MAX,
                ConfigManager.getBlocksFinderWorldOpacity());
        y += BTN_H + GAP;
        worldOpacityKeybindButton = new GuiButton(12, x, y, BTN_W, BTN_H, getWorldOpacityKeybindText());
        y += BTN_H + ROW + 4;
        backButton = new GuiButton(0, x, y, BTN_W, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.add(toggleButton);
        buttonList.add(manageBlocksButton);
        buttonList.add(espModeButton);
        buttonList.add(fillAlphaSlider);
        buttonList.add(worldOpacitySlider);
        buttonList.add(worldOpacityKeybindButton);
        buttonList.add(backButton);

        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return BlocksFinder.isEnabled();
            }
        });
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Block Finder"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        drawHoverTooltips(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, BlocksFinderGUI.class);
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (isHovering(toggleButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Only works in singleplayer & replays"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(worldOpacitySlider, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Modifies the opacity for every blocks except the ones selected"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(worldOpacityKeybindButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Hold the key and scroll to adjust opacity in-game",
                    EnumChatFormatting.GRAY + "Click to open Controls (gomod123 category)"
            ), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private boolean isHovering(GuiButton btn, int mx, int my) {
        return btn != null
                && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                break;
            case 1:
                if (!BlocksFinder.isEnabled() && !BlocksFinder.isBlocksFinderAllowed(mc)) {
                    me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                            + "\u00a7cBlock Finder only works in singleplayer and replays.");
                    break;
                }
                BlocksFinder.setEnabled(!BlocksFinder.isEnabled());
                toggleButton.displayString = getToggleText();
                enabledOverlays.onToggled("main", BlocksFinder.isEnabled());
                if (parent != null) {
                    parent.refreshBlocksFinderButtonLabel();
                }
                break;
            case 2:
                mc.displayGuiScreen(new BlocksFinderBlockPickerGUI(this));
                break;
            case 3:
                String next = BlocksFinder.nextMode(ConfigManager.getBlocksFinderEspMode());
                ConfigManager.setBlocksFinderEspMode(next);
                espModeButton.displayString = getModeText();
                break;
            case 12:
                mc.displayGuiScreen(new GuiControls(this, mc.gameSettings));
                break;
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (fillAlphaSlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = fillAlphaSlider;
            ConfigManager.setBlocksFinderFillAlpha(fillAlphaSlider.getValue());
        } else if (worldOpacitySlider.mousePressed(mc, mouseX, mouseY)) {
            draggingSlider = worldOpacitySlider;
            BlocksFinder.setWorldOpacity(worldOpacitySlider.getValue());
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingSlider instanceof BlocksFinderIntSlider) {
            BlocksFinderIntSlider s = (BlocksFinderIntSlider) draggingSlider;
            s.dragTo(mouseX);
            if (s == fillAlphaSlider) {
                ConfigManager.setBlocksFinderFillAlpha(s.getValue());
            } else if (s == worldOpacitySlider) {
                BlocksFinder.setWorldOpacity(s.getValue());
            }
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && draggingSlider instanceof BlocksFinderIntSlider) {
            BlocksFinderIntSlider s = (BlocksFinderIntSlider) draggingSlider;
            if (s == fillAlphaSlider) {
                ConfigManager.setBlocksFinderFillAlpha(s.getValue());
            } else if (s == worldOpacitySlider) {
                BlocksFinder.setWorldOpacity(s.getValue());
            }
        }
        draggingSlider = null;
    }

    @Override
    public void onGuiClosed() {
        if (worldOpacityKeybindButton != null) {
            worldOpacityKeybindButton.displayString = getWorldOpacityKeybindText();
        }
        super.onGuiClosed();
        if (parent != null) {
            parent.refreshBlocksFinderButtonLabel();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String getToggleText() {
        return EnumChatFormatting.WHITE + "Block Finder: "
                + (BlocksFinder.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getManageText() {
        int count = BlocksFinder.getTrackedIds().size();
        return EnumChatFormatting.GOLD + "Manage target blocks (" + count + ")...";
    }

    private String getModeText() {
        return EnumChatFormatting.WHITE + "ESP mode: "
                + EnumChatFormatting.AQUA + BlocksFinder.modeLabel(
                        BlocksFinder.normalizeEspMode(ConfigManager.getBlocksFinderEspMode()));
    }

    private String getWorldOpacityKeybindText() {
        String key = GomodKeybinds.formatBlocksFinderWorldOpacityBinding();
        if ("NONE".equals(key)) {
            return EnumChatFormatting.WHITE + "World opacity key: NONE";
        }
        return EnumChatFormatting.WHITE + "World opacity key: " + key + " + SCROLL";
    }

    void refreshManageText() {
        if (manageBlocksButton != null) {
            manageBlocksButton.displayString = getManageText();
        }
    }
}

class BlocksFinderIntSlider extends GuiButton {
    private final String label;
    private final int minValue;
    private final int maxValue;
    private float sliderPos;
    private int value;
    boolean dragging;

    BlocksFinderIntSlider(int id, int x, int y, int w, int h, String label, int min, int max, int current) {
        super(id, x, y, w, h, "");
        this.label = label;
        this.minValue = min;
        this.maxValue = max;
        this.value = clamp(current);
        this.sliderPos = (maxValue == minValue) ? 0f : (float) (value - minValue) / (maxValue - minValue);
        refreshLabel();
    }

    int getValue() {
        return value;
    }

    private void refreshLabel() {
        this.displayString = label + ": " + value;
    }

    private int clamp(int v) {
        return Math.max(minValue, Math.min(maxValue, v));
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
        GlStateManager.color(1f, 1f, 1f, 1f);
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, 46, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, 46, half, 20);
        int knobX = this.xPosition + (int) (this.sliderPos * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
        GlStateManager.color(1f, 1f, 1f, 1f);
        boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height;
        int textColor = !enabled ? 0x808080 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        int tw = mc.fontRendererObj.getStringWidth(displayString);
        mc.fontRendererObj.drawString(displayString, xPosition + (width - tw) / 2, yPosition + 6, textColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!visible || !enabled) {
            return false;
        }
        if (mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width && mouseY < yPosition + height) {
            dragging = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    void dragTo(int mouseX) {
        if (dragging) {
            updateFromMouse(mouseX);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
    }

    private void updateFromMouse(int mouseX) {
        float pos = (float) (mouseX - (xPosition + 4)) / (float) (width - 8);
        sliderPos = Math.max(0f, Math.min(1f, pos));
        value = clamp(minValue + Math.round(sliderPos * (maxValue - minValue)));
        refreshLabel();
    }
}
