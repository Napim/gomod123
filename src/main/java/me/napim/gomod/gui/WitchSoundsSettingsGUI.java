package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.util.WitchSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.Arrays;

/**
 * Unified Mob Sounds GUI combining Witch SFX (master toggle + category volume sliders)
 * and Silent Mob Steps (master toggle + Ocelot/Witch step muting toggles).
 */
public class WitchSoundsSettingsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int MAX_COL_WIDTH = 194;
    private static final int MIN_COL_WIDTH = 148;
    private static final int COL_GAP = 22;
    private static final int CARD_PAD_X = 8;
    private static final int CARD_PAD_TOP = 20;
    private static final int CARD_PAD_BOTTOM = 8;

    private final GuiScreen parent;

    private GuiButton witchToggleButton;
    private WitchVolumeSlider sliderIdle;
    private WitchVolumeSlider sliderHurt;
    private WitchVolumeSlider sliderDeath;
    private WitchVolumeSlider sliderThrow;
    private WitchVolumeSlider sliderDrink;
    private WitchVolumeSlider sliderCelebrate;

    private GuiButton silentStepsToggleButton;
    private GuiButton silentOcelotButton;
    private GuiButton silentWitchButton;

    private GuiButton backButton;
    private GuiButton selectedButton;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    private int leftColX;
    private int rightColX;
    private int colWidth;
    private int cardTopY;
    private int cardBottomY;

    public WitchSoundsSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(this.mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        int cx = sw / 2;

        colWidth = Math.min(MAX_COL_WIDTH, Math.max(MIN_COL_WIDTH, (sw - 48 - COL_GAP) / 2));
        leftColX = cx - COL_GAP / 2 - colWidth;
        rightColX = cx + COL_GAP / 2;

        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int titleBottom = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj) + 4;
        int contentBottom = backY - 10;
        int availableH = Math.max(0, contentBottom - titleBottom);

        int numSliders = 6;
        int toggleGap = 6;
        int rowStep = 23;
        int contentStackH = BUTTON_HEIGHT + toggleGap + (numSliders - 1) * rowStep + BUTTON_HEIGHT;
        int totalCardH = CARD_PAD_TOP + contentStackH + CARD_PAD_BOTTOM;
        if (totalCardH > availableH && availableH > 0) {
            rowStep = 21;
            toggleGap = 4;
            contentStackH = BUTTON_HEIGHT + toggleGap + (numSliders - 1) * rowStep + BUTTON_HEIGHT;
            totalCardH = CARD_PAD_TOP + contentStackH + CARD_PAD_BOTTOM;
        }

        cardTopY = titleBottom + Math.max(0, (availableH - totalCardH) / 2);
        cardBottomY = cardTopY + totalCardH;
        int controlsTopY = cardTopY + CARD_PAD_TOP;
        int sliderTopY = controlsTopY + BUTTON_HEIGHT + toggleGap;

        // Left column: Witch Sounds
        witchToggleButton = new MobSoundToggleButton(1, leftColX, controlsTopY, colWidth, BUTTON_HEIGHT, getWitchToggleText());
        sliderIdle = new WitchVolumeSlider(101, leftColX, sliderTopY + 0 * rowStep, colWidth,
                "Idle:", ConfigManager.getWitchSfxVolIdle());
        sliderHurt = new WitchVolumeSlider(102, leftColX, sliderTopY + 1 * rowStep, colWidth,
                "Hurt:", ConfigManager.getWitchSfxVolHurt());
        sliderDeath = new WitchVolumeSlider(103, leftColX, sliderTopY + 2 * rowStep, colWidth,
                "Death:", ConfigManager.getWitchSfxVolDeath());
        sliderThrow = new WitchVolumeSlider(104, leftColX, sliderTopY + 3 * rowStep, colWidth,
                "Throw:", ConfigManager.getWitchSfxVolThrow());
        sliderDrink = new WitchVolumeSlider(105, leftColX, sliderTopY + 4 * rowStep, colWidth,
                "Drink:", ConfigManager.getWitchSfxVolDrink());
        sliderCelebrate = new WitchVolumeSlider(106, leftColX, sliderTopY + 5 * rowStep, colWidth,
                "Celebrate:", ConfigManager.getWitchSfxVolCelebrate());

        // Right column: Silent Mob Steps
        int rightRowStep = 26;
        silentStepsToggleButton = new MobSoundToggleButton(201, rightColX, controlsTopY, colWidth, BUTTON_HEIGHT,
                getSilentStepsToggleText());
        silentOcelotButton = new MobSoundToggleButton(202, rightColX, controlsTopY + rightRowStep, colWidth, BUTTON_HEIGHT,
                getSilentOcelotText());
        silentWitchButton = new MobSoundToggleButton(203, rightColX, controlsTopY + rightRowStep * 2, colWidth, BUTTON_HEIGHT,
                getSilentWitchText());

        int backW = Math.min(220, colWidth * 2 + COL_GAP);
        backButton = new GuiButton(99, cx - backW / 2, backY, backW, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(witchToggleButton);
        buttonList.add(sliderIdle);
        buttonList.add(sliderHurt);
        buttonList.add(sliderDeath);
        buttonList.add(sliderThrow);
        buttonList.add(sliderDrink);
        buttonList.add(sliderCelebrate);
        buttonList.add(silentStepsToggleButton);
        buttonList.add(silentOcelotButton);
        buttonList.add(silentWitchButton);
        buttonList.add(backButton);

        syncControlEnabledState();

        enabledOverlays.trackModuleToggle("witch", witchToggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return ConfigManager.isWitchSoundsEnabled();
            }
        });
        enabledOverlays.trackModuleToggle("silentSteps", silentStepsToggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return ConfigManager.isSilentSpawnedMobStepsEnabled();
            }
        });
    }

    private void syncControlEnabledState() {
        boolean witchOn = ConfigManager.isWitchSoundsEnabled();
        for (GuiButton button : buttonList) {
            if (button instanceof WitchVolumeSlider) {
                button.enabled = witchOn;
                if (!witchOn) {
                    ((WitchVolumeSlider) button).mouseReleased(0, 0);
                }
            }
        }
        if (!witchOn && selectedButton instanceof WitchVolumeSlider) {
            selectedButton = null;
        }
        boolean stepsOn = ConfigManager.isSilentSpawnedMobStepsEnabled();
        if (silentOcelotButton != null) {
            silentOcelotButton.enabled = stepsOn;
        }
        if (silentWitchButton != null) {
            silentWitchButton.enabled = stepsOn;
        }
    }

    private String getWitchToggleText() {
        return EnumChatFormatting.WHITE + "Witch sounds: " + (ConfigManager.isWitchSoundsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getSilentStepsToggleText() {
        return EnumChatFormatting.WHITE + "Silent mob steps: " + (ConfigManager.isSilentSpawnedMobStepsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getSilentOcelotText() {
        return EnumChatFormatting.WHITE + "Mute Ocelot steps: " + (ConfigManager.isSilentSpawnedOcelotSteps()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getSilentWitchText() {
        return EnumChatFormatting.WHITE + "Mute Witch steps: " + (ConfigManager.isSilentSpawnedWitchSteps()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(this.mc);
        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Mob Sounds"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String subtitle = EnumChatFormatting.GRAY + "Configure Witch SFX and spawned witch/ocelots step sounds";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, subtitle, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        drawSectionCard(leftColX, colWidth, EnumChatFormatting.LIGHT_PURPLE + "" + EnumChatFormatting.BOLD + "Witch Sounds", 0x88D070FF);
        drawSectionCard(rightColX, colWidth, EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + "Silent Mob Steps", 0x8855FFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, WitchSoundsSettingsGUI.class);
        drawHoverTooltips(mouseX, mouseY);
    }

    private void drawSectionCard(int colX, int width, String header, int accentColor) {
        int left = colX - CARD_PAD_X;
        int right = colX + width + CARD_PAD_X;
        drawRect(left, cardTopY, right, cardBottomY, 0x45000000);
        drawRect(left, cardTopY, right, cardTopY + 1, 0x35FFFFFF);
        drawRect(left, cardBottomY - 1, right, cardBottomY, 0x35FFFFFF);
        drawRect(left, cardTopY, left + 1, cardBottomY, 0x35FFFFFF);
        drawRect(right - 1, cardTopY, right, cardBottomY, 0x35FFFFFF);

        int hw = fontRendererObj.getStringWidth(header);
        fontRendererObj.drawStringWithShadow(header, colX + (width - hw) / 2, cardTopY + 5, 0xFFFFFF);
        drawRect(colX + 4, cardTopY + 16, colX + width - 4, cardTopY + 17, accentColor);
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (isMouseOver(witchToggleButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.LIGHT_PURPLE + "Witch sounds",
                    EnumChatFormatting.GRAY + "Enables missing 1.8.9 Witch SFX and per-category volumes"), mouseX, mouseY);
        } else if (isMouseOver(silentStepsToggleButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Silent mob steps",
                    EnumChatFormatting.GRAY + "Mutes stepping sounds of Ocelots & Witches you spawn in Blitz"), mouseX, mouseY);
        }
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1: {
                boolean now = !ConfigManager.isWitchSoundsEnabled();
                ConfigManager.setWitchSoundsEnabled(now);
                witchToggleButton.displayString = getWitchToggleText();
                enabledOverlays.onToggled("witch", now);
                syncControlEnabledState();
                try {
                    WitchSounds.playIdleSample(Minecraft.getMinecraft(), now ? 4 : 3, true);
                } catch (Throwable ignored) {
                }
                break;
            }
            case 201: {
                boolean now = !ConfigManager.isSilentSpawnedMobStepsEnabled();
                ConfigManager.setSilentSpawnedMobStepsEnabled(now);
                silentStepsToggleButton.displayString = getSilentStepsToggleText();
                enabledOverlays.onToggled("silentSteps", now);
                syncControlEnabledState();
                break;
            }
            case 202: {
                boolean now = !ConfigManager.isSilentSpawnedOcelotSteps();
                ConfigManager.setSilentSpawnedOcelotSteps(now);
                silentOcelotButton.displayString = getSilentOcelotText();
                break;
            }
            case 203: {
                boolean now = !ConfigManager.isSilentSpawnedWitchSteps();
                ConfigManager.setSilentSpawnedWitchSteps(now);
                silentWitchButton.displayString = getSilentWitchText();
                break;
            }
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
        mc.displayGuiScreen(parent);
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshWitchSoundsButtonLabel();
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiButton b : buttonList) {
            if (b instanceof WitchVolumeSlider && b.enabled) {
                if (b.mousePressed(mc, mouseX, mouseY)) {
                    selectedButton = b;
                    applySliderRuntime((WitchVolumeSlider) b);
                    break;
                }
            }
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof WitchVolumeSlider && selectedButton.enabled) {
            WitchVolumeSlider s = (WitchVolumeSlider) selectedButton;
            s.mouseDragged(mc, mouseX, mouseY);
            applySliderRuntime(s);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        WitchVolumeSlider drag = (selectedButton instanceof WitchVolumeSlider)
                ? (WitchVolumeSlider) selectedButton
                : null;
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && drag != null) {
            applySliderRuntime(drag);
        }
    }

    private void applySliderRuntime(WitchVolumeSlider s) {
        float v = s.getValue();
        switch (s.id) {
            case 101:
                ConfigManager.setWitchSfxVolIdle(v);
                break;
            case 102:
                ConfigManager.setWitchSfxVolHurt(v);
                break;
            case 103:
                ConfigManager.setWitchSfxVolDeath(v);
                break;
            case 104:
                ConfigManager.setWitchSfxVolThrow(v);
                break;
            case 105:
                ConfigManager.setWitchSfxVolDrink(v);
                break;
            case 106:
                ConfigManager.setWitchSfxVolCelebrate(v);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class MobSoundToggleButton extends GuiButton {

        MobSoundToggleButton(int id, int x, int y, int w, int h, String text) {
            super(id, x, y, w, h, text);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) {
                return;
            }
            String label = this.displayString;
            this.displayString = "";
            super.drawButton(mc, mouseX, mouseY);
            this.displayString = label;
            int ty = this.yPosition + (this.height - 8) / 2;
            int tw = mc.fontRendererObj.getStringWidth(label);
            int tx = this.xPosition + (this.width - tw) / 2;
            int color = 0xE0E0E0;
            if (!this.enabled) {
                color = 0xA0A0A0;
            } else if (this.hovered) {
                color = 0xFFFFA0;
            }
            mc.fontRendererObj.drawStringWithShadow(label, tx, ty, color);
        }
    }
}

final class WitchVolumeSlider extends GuiButton {

    private static final float MIN = 0.0f;
    private static final float MAX = 1.0f;

    private final float minValue = MIN;
    private final float maxValue = MAX;
    private final String labelPrefix;
    private float currentValue;
    private float sliderValue;
    private boolean dragging;

    WitchVolumeSlider(int id, int x, int y, int width, String labelPrefix, float current) {
        super(id, x, y, width, 20, "");
        this.labelPrefix = labelPrefix;
        this.currentValue = Math.max(MIN, Math.min(MAX, current));
        this.sliderValue = (MAX <= MIN) ? 0f : (this.currentValue - minValue) / (maxValue - minValue);
        updateLabel();
    }

    private void updateLabel() {
        this.displayString = labelPrefix + String.format(" x%.2f", currentValue);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(buttonTextures);
        if (this.enabled) {
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } else {
            net.minecraft.client.renderer.GlStateManager.color(0.7F, 0.7F, 0.7F, 0.75F);
        }
        int v = 46;
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);

        int knobX = this.xPosition + (int) (this.sliderValue * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        boolean hovered = this.enabled && mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = !this.enabled ? 0xA0A0A0 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int fh = mc.fontRendererObj.FONT_HEIGHT;
        int textY = this.yPosition + (this.height - fh) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
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
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentValue = minValue + normalizedPos * (maxValue - minValue);
        updateLabel();
    }

    float getValue() {
        return currentValue;
    }
}
