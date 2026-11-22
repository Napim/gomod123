package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DeathmatchTimerBossBar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * Settings screen for {@link DeathmatchTimerBossBar}.
 */
public class DeathmatchTimerSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 220;
    private static final int GAP = 8;
    private static final int BOSS_BAR_W = 182;
    private static final int BOSS_BAR_H = 5;
    private static final long PREVIEW_CYCLE_MS = 6000L;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private DeathmatchTimerThresholdSlider thresholdSlider;
    private GuiButton backButton;
    private GuiButton selectedButton;
    private int previewBarY;

    public DeathmatchTimerSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        int controlsH = 26 + BUTTON_HEIGHT + GAP + BUTTON_HEIGHT + 24 + BUTTON_HEIGHT;
        int top = Math.max(GuiHeader.standardContentTopAfterTitle(fontRendererObj) + 12, (sh - controlsH) / 2);

        previewBarY = top + 12;
        int y = previewBarY + 20;
        toggleButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getToggleText());
        y += BUTTON_HEIGHT + GAP;
        thresholdSlider = new DeathmatchTimerThresholdSlider(101, cx - BUTTON_WIDTH / 2, y,
                BUTTON_WIDTH, ConfigManager.getDeathmatchTimerBossBarThresholdSeconds());
        thresholdSlider.enabled = ConfigManager.isDeathmatchTimerBossBarEnabled();
        y += BUTTON_HEIGHT + 24;
        backButton = new GuiButton(99, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(thresholdSlider);
        buttonList.add(backButton);
    }

    private String getToggleText() {
        return EnumChatFormatting.WHITE + "Deathmatch timer: "
                + (ConfigManager.isDeathmatchTimerBossBarEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        int cx = width / 2;
        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Deathmatch Timer";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String subtitle = EnumChatFormatting.GRAY + "Shows Game End on the Ender Dragon boss bar";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, subtitle, cx, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        drawBossBarPreview(cx);

        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, DeathmatchTimerSettingsGUI.class);
    }

    private void drawBossBarPreview(int cx) {
        int thresholdSec = ConfigManager.getDeathmatchTimerBossBarThresholdSeconds();
        boolean enabled = ConfigManager.isDeathmatchTimerBossBarEnabled();
        float progress = 1.0F - (float) (System.currentTimeMillis() % PREVIEW_CYCLE_MS) / (float) PREVIEW_CYCLE_MS;
        long previewRemainingMs = Math.round(progress * thresholdSec * 1000.0F);
        float healthScale = enabled ? DeathmatchTimerBossBar.computeHealthScale(previewRemainingMs, thresholdSec) : 0.0F;
        String barText = enabled
                ? DeathmatchTimerBossBar.formatBossBarText(previewRemainingMs)
                : EnumChatFormatting.DARK_GRAY + "Game End: Disabled";

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, enabled ? 1.0F : 0.45F);
        mc.getTextureManager().bindTexture(Gui.icons);
        int barX = cx - BOSS_BAR_W / 2;
        int fillW = (int) (healthScale * (float) (BOSS_BAR_W + 1));
        drawTexturedModalRect(barX, previewBarY, 0, 74, BOSS_BAR_W, BOSS_BAR_H);
        drawTexturedModalRect(barX, previewBarY, 0, 74, BOSS_BAR_W, BOSS_BAR_H);
        if (fillW > 0) {
            drawTexturedModalRect(barX, previewBarY, 0, 79, fillW, BOSS_BAR_H);
        }
        int textX = cx - fontRendererObj.getStringWidth(barText) / 2;
        fontRendererObj.drawStringWithShadow(barText, textX, previewBarY - 10, 0xFFFFFF);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1:
                boolean next = !ConfigManager.isDeathmatchTimerBossBarEnabled();
                ConfigManager.setDeathmatchTimerBossBarEnabled(next);
                DeathmatchTimerBossBar.onSettingChanged();
                toggleButton.displayString = getToggleText();
                if (thresholdSlider != null) {
                    thresholdSlider.enabled = next;
                }
                break;
            case 99:
                closeToParent();
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeToParent() {
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshDeathmatchTimerButtonLabel();
        }
        mc.displayGuiScreen(parent);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (thresholdSlider != null && thresholdSlider.mousePressed(mc, mouseX, mouseY)) {
            selectedButton = thresholdSlider;
            ConfigManager.setDeathmatchTimerBossBarThresholdSeconds(thresholdSlider.getValue());
            DeathmatchTimerBossBar.onSettingChanged();
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof DeathmatchTimerThresholdSlider) {
            DeathmatchTimerThresholdSlider slider = (DeathmatchTimerThresholdSlider) selectedButton;
            slider.mouseDragged(mc, mouseX, mouseY);
            ConfigManager.setDeathmatchTimerBossBarThresholdSeconds(slider.getValue());
            DeathmatchTimerBossBar.onSettingChanged();
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (selectedButton instanceof DeathmatchTimerThresholdSlider) {
            ConfigManager.setDeathmatchTimerBossBarThresholdSeconds(
                    ((DeathmatchTimerThresholdSlider) selectedButton).getValue());
            DeathmatchTimerBossBar.onSettingChanged();
        }
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) {
            selectedButton = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

final class DeathmatchTimerThresholdSlider extends GuiButton {

    private static final int MIN = DeathmatchTimerBossBar.MIN_THRESHOLD_SECONDS;
    private static final int MAX = DeathmatchTimerBossBar.MAX_THRESHOLD_SECONDS;

    private int currentValue;
    private float sliderValue;
    private boolean dragging;

    DeathmatchTimerThresholdSlider(int id, int x, int y, int width, int current) {
        super(id, x, y, width, 20, "");
        this.currentValue = Math.max(MIN, Math.min(MAX, current));
        this.sliderValue = (float) (this.currentValue - MIN) / (float) (MAX - MIN);
        updateLabel();
    }

    private void updateLabel() {
        this.displayString = EnumChatFormatting.WHITE + "Show at: " + EnumChatFormatting.YELLOW + currentValue + "s";
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(buttonTextures);
        GlStateManager.color(1.0F, 1.0F, 1.0F, this.enabled ? 1.0F : 0.5F);
        int v = 46;
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);

        int knobX = this.xPosition + (int) (this.sliderValue * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);

        boolean hovered = this.enabled && mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = !this.enabled ? 0xA0A0A0 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int textY = this.yPosition + (this.height - 8) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.enabled && this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width
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
        if (this.enabled && this.visible && this.dragging) {
            applyMouseX(mouseX);
        }
    }

    private void applyMouseX(int mouseX) {
        int innerLeft = this.xPosition + 4;
        int innerRight = this.xPosition + this.width - 4;
        int clamped = Math.max(innerLeft, Math.min(innerRight, mouseX));
        float normalizedPos = (float) (clamped - innerLeft) / (float) (this.width - 8);
        this.sliderValue = Math.max(0.0F, Math.min(1.0F, normalizedPos));
        this.currentValue = MIN + Math.round(this.sliderValue * (MAX - MIN));
        this.sliderValue = (float) (this.currentValue - MIN) / (float) (MAX - MIN);
        updateLabel();
    }

    int getValue() {
        return currentValue;
    }
}
