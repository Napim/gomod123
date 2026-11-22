package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.render.GuiBackdropBlur;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * Custom backdrop: disabled (vanilla) vs enabled (split dim/blur for gomod menus vs other GUIs).
 */
public class GuiBackdropSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 240;
    private static final int ROW = 24;

    private final GuiScreen parent;
    private GuiButton modeButton;
    private GuiButton backButton;
    private GuiBackdropPercentSlider gomodDimSlider;
    private GuiBackdropPercentSlider gomodBlurSlider;
    private GuiBackdropPercentSlider otherDimSlider;
    private GuiBackdropPercentSlider otherBlurSlider;
    private GuiButton selectedButton;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public GuiBackdropSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(this.mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        final int stackAboveBack = (ROW + 4) + ROW + ROW + (ROW + 6) + ROW + ROW + (ROW + 14);
        int titleBottom = 52;
        int modeY = titleBottom + Math.max(0, (backY - 10 - titleBottom - stackAboveBack) / 2);
        modeY = Math.max(12, modeY);
        int y = modeY;
        modeButton = new GuiButton(1, cx - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, BUTTON_HEIGHT, formatModeLabel());
        y += ROW + 4;
        gomodDimSlider = new GuiBackdropPercentSlider(101, cx - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, "gomod menus: dim",
                ConfigManager.getGuiBackdropGomodDimPercent());
        y += ROW;
        gomodBlurSlider = new GuiBackdropPercentSlider(102, cx - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, "gomod menus: blur",
                ConfigManager.getGuiBackdropGomodBlurPercent());
        y += ROW + 6;
        otherDimSlider = new GuiBackdropPercentSlider(103, cx - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, "Other GUIs: dim",
                ConfigManager.getGuiBackdropOtherDimPercent());
        y += ROW;
        otherBlurSlider = new GuiBackdropPercentSlider(104, cx - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, "Other GUIs: blur",
                ConfigManager.getGuiBackdropOtherBlurPercent());
        backButton = new GuiButton(99, cx - 100, backY, 200, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(modeButton);
        buttonList.add(gomodDimSlider);
        buttonList.add(gomodBlurSlider);
        buttonList.add(otherDimSlider);
        buttonList.add(otherBlurSlider);
        buttonList.add(backButton);
        refreshSliderEnabledState();
        enabledOverlays.trackModuleToggle("main", modeButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
            }
        });
    }

    private void refreshSliderEnabledState() {
        boolean enabled = !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
        if (gomodDimSlider != null) {
            gomodDimSlider.enabled = enabled;
        }
        if (gomodBlurSlider != null) {
            gomodBlurSlider.enabled = enabled;
        }
        if (otherDimSlider != null) {
            otherDimSlider.enabled = enabled;
        }
        if (otherBlurSlider != null) {
            otherBlurSlider.enabled = enabled;
        }
    }

    private static String formatModeLabel() {
        if ("VANILLA".equals(ConfigManager.getGuiBackdropMode())) {
            return EnumChatFormatting.GOLD + "Backdrop: " + EnumChatFormatting.RED + "Disabled";
        }
        return EnumChatFormatting.GOLD + "Backdrop: " + EnumChatFormatting.GREEN + "Enabled";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(this.mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "GUI backdrop & blur"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        if (!GuiBackdropBlur.isAvailable()) {
            String warn = EnumChatFormatting.RED + "Blur unavailable (shaders off or unsupported).";
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, warn, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        }
        refreshSliderEnabledState();
        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, GuiBackdropSettingsGUI.class);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            boolean wasOn = !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
            ConfigManager.cycleGuiBackdropMode();
            modeButton.displayString = formatModeLabel();
            refreshSliderEnabledState();
            boolean nowOn = !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
            if (nowOn != wasOn) {
                enabledOverlays.onToggled("main", nowOn);
            }
        } else if (button.id == 99) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiButton b : buttonList) {
            if (b instanceof GuiBackdropPercentSlider) {
                if (b.mousePressed(mc, mouseX, mouseY)) {
                    selectedButton = b;
                    applySlider((GuiBackdropPercentSlider) b);
                    break;
                }
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof GuiBackdropPercentSlider) {
            GuiBackdropPercentSlider s = (GuiBackdropPercentSlider) selectedButton;
            s.mouseDragged(mc, mouseX, mouseY);
            applySlider(s);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        GuiBackdropPercentSlider drag = (selectedButton instanceof GuiBackdropPercentSlider)
                ? (GuiBackdropPercentSlider) selectedButton
                : null;
        if (drag != null) {
            drag.mouseReleased(mouseX, mouseY);
        }
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && drag != null) {
            applySlider(drag);
            ConfigManager.saveConfig();
        }
        selectedButton = null;
    }

    private static void applySlider(GuiBackdropPercentSlider s) {
        int v = s.getIntValue();
        switch (s.id) {
            case 101:
                ConfigManager.setGuiBackdropGomodDimPercentRuntime(v);
                break;
            case 102:
                ConfigManager.setGuiBackdropGomodBlurPercentRuntime(v);
                break;
            case 103:
                ConfigManager.setGuiBackdropOtherDimPercentRuntime(v);
                break;
            case 104:
                ConfigManager.setGuiBackdropOtherBlurPercentRuntime(v);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

final class GuiBackdropPercentSlider extends GuiButton {

    private final String labelPrefix;
    private int currentPercent;
    private float sliderValue;
    private boolean dragging;

    GuiBackdropPercentSlider(int id, int x, int y, int width, String labelPrefix, int currentPercent) {
        super(id, x, y, width, 20, "");
        this.labelPrefix = labelPrefix;
        this.currentPercent = Math.max(0, Math.min(100, currentPercent));
        this.sliderValue = this.currentPercent / 100.0f;
        updateLabel();
    }

    private void updateLabel() {
        this.displayString = labelPrefix + ": " + currentPercent + "%";
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(buttonTextures);
        float c = this.enabled ? 1.0F : 0.45F;
        net.minecraft.client.renderer.GlStateManager.color(c, c, c, 1.0F);
        int v = 46;
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, this.height);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, this.height);

        int knobX = this.xPosition + (int) (this.sliderValue * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, this.height);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, this.height);

        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = !this.enabled ? 0x707070 : hovered ? 0xFFFFA0 : 0xE0E0E0;
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int textY = this.yPosition + (this.height - 8) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!this.enabled) {
            return false;
        }
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width
                && mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            applyMouseX(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    @Override
    public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && this.dragging) {
            applyMouseX(mouseX);
        }
    }

    private void applyMouseX(int mouseX) {
        int innerLeft = this.xPosition + 4;
        int innerRight = this.xPosition + this.width - 4;
        int clamped = Math.max(innerLeft, Math.min(innerRight, mouseX));
        float normalizedPos = (float) (clamped - innerLeft) / (float) (this.width - 8);
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentPercent = Math.round(normalizedPos * 100.0f);
        updateLabel();
    }

    int getIntValue() {
        return currentPercent;
    }
}
