package me.napim.gomod.gui;

import me.napim.gomod.features.AdvancedRenderDistance;
import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.PerspectiveDistance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;

public class PerspectiveGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;

    private GuiButton toggleButton;
    private GuiButton resetOnExitButton;
    private GuiButton requireHoldButton;
    private GuiButton smoothZoomButton;
    private GuiButton lockScrollButton;
    private GuiButton zoomKeyButton;
    private GuiButton advancedRenderDistanceKeyButton;
    private GuiButton setKeyButton;
    private GuiButton fovToggleButton;
    private GuiButton backButton;

    // Slider for scrolling speed
    private SpeedSlider speedSlider;
    
    // Slider for smooth speed
    private SmoothSlider smoothSlider;
    
    // FOV slider
    private PerspectiveFOVSlider fovSlider;

    // no temp vars needed
    private int awaitingKey = -1;
    private int awaitingZoomKey = -1;
    private int awaitingAdvancedRenderDistanceKey = -1;
    private GuiButton selectedButton = null;

    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = sr.getScaledHeight() / 2;

        // Load current values (no longer needed for sliders)

        int rightColumnX = width / 2 + 80;
        int rowStartY = centerY - 100;
        int buttonWidth = 120;
        int fovSliderX = rightColumnX + buttonWidth + 10; // Position FOV slider to the right of toggle button

        toggleButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        resetOnExitButton = new GuiButton(2, rightColumnX, rowStartY + SPACING, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isResetOnExit() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        requireHoldButton = new GuiButton(4, rightColumnX, rowStartY + SPACING * 2, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isRequireHold() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        smoothZoomButton = new GuiButton(5, rightColumnX, rowStartY + SPACING * 3, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isSmoothZoom() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        // Lock scroll right after Smooth zoom transitions
        lockScrollButton = new GuiButton(7, rightColumnX, rowStartY + SPACING * 4, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isLockScroll() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        
        setKeyButton = new GuiButton(3, rightColumnX, rowStartY + SPACING * 5, buttonWidth, BUTTON_HEIGHT,
                formatKey(PerspectiveDistance.getResetKeyCode()));
        zoomKeyButton = new GuiButton(6, fovSliderX, rowStartY + SPACING * 2, buttonWidth, BUTTON_HEIGHT,
                formatKey(PerspectiveDistance.getZoomKeyCode()));

        // Create speed slider (Minecraft-style)
        speedSlider = new SpeedSlider(101, rightColumnX, rowStartY + SPACING * 6, 
                "Speed", 0.20f, 3.00f, PerspectiveDistance.getScrollStep());
        
        // Create smooth speed slider (Minecraft-style) - positioned to the right of smooth zoom toggle
        smoothSlider = new SmoothSlider(102, fovSliderX, rowStartY + SPACING * 3, 
                "Smooth", 0.01f, 1.0f, me.napim.gomod.features.ConfigManager.getPerspectiveSmoothSpeed());
        
        // Custom Perspective FOV lives at row 8 (not 7) so there's a one-row gap separating it
        // from the Scrolling speed row above — they belong to different categories.
        fovToggleButton = new GuiButton(8, rightColumnX, rowStartY + SPACING * 8, buttonWidth, BUTTON_HEIGHT,
                (PerspectiveDistance.isFOVEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));

        fovSlider = new PerspectiveFOVSlider(100, fovSliderX, rowStartY + SPACING * 8,
                "FOV", 10.0f, 170.0f, PerspectiveDistance.getFOV());

        advancedRenderDistanceKeyButton = new GuiButton(9, rightColumnX, rowStartY + SPACING * 9, buttonWidth, BUTTON_HEIGHT,
                formatAdvancedRenderDistanceKey(AdvancedRenderDistance.getKeyCode()));

        // Back button
        backButton = new GuiButton(99, width / 2 - 100, GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");

        this.buttonList.add(toggleButton);
        this.buttonList.add(resetOnExitButton);
        this.buttonList.add(requireHoldButton);
        this.buttonList.add(smoothZoomButton);
        this.buttonList.add(lockScrollButton);
        this.buttonList.add(setKeyButton);
        this.buttonList.add(zoomKeyButton);
        this.buttonList.add(speedSlider);
        this.buttonList.add(fovToggleButton);
        this.buttonList.add(fovSlider);
        this.buttonList.add(smoothSlider);
        this.buttonList.add(advancedRenderDistanceKeyButton);
        this.buttonList.add(backButton);

        enabledOverlays.trackModuleToggle("perspective", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return PerspectiveDistance.isEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("fov", fovToggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return PerspectiveDistance.isFOVEnabled();
            }
        });
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        me.napim.gomod.gui.GuiHeader.drawTopLeft(this);

        // Match gomod menu button color: BLUE
        String title = EnumChatFormatting.BLUE + "" + EnumChatFormatting.BOLD + "Perspective Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        scroll.tick();
        int labelX = width / 2 - 200;
        int rowStartY = height / 2 - 100;
        updateScrollBounds(rowStartY);

        int y0 = rowStartY - scroll.getOffset();
        int visibleTop = scroll.getVisibleTop();
        int visibleBottom = scroll.getVisibleBottom();
        int scrollOff = scroll.getOffset();
        int maxScroll = scroll.getMaxScroll();
        GuiScrollFade.drawLabel(fontRendererObj, "Advanced perspective", labelX, y0, 6, BUTTON_HEIGHT,
                visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Reset zoom when quitting adv. perspective", labelX, y0 + SPACING, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Zoom only while holding the zoom key", labelX, y0 + SPACING * 2, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Smooth zoom transitions", labelX, y0 + SPACING * 3, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Reset zoom", labelX, y0 + SPACING * 5, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Scrolling speed", labelX, y0 + SPACING * 6, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Custom Perspective FOV", labelX, y0 + SPACING * 8, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);
        GuiScrollFade.drawLabel(fontRendererObj, "Advanced render distance", labelX, y0 + SPACING * 9, 6,
                BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);

        // Lock scroll label is directly after Smooth zoom transitions
        int lockLabelRow = 4; // immediately after Smooth zoom transitions row
        GuiScrollFade.drawLabel(fontRendererObj, "Lock hotbar scroll while in adv. perspective", labelX,
                y0 + SPACING * lockLabelRow, 6, BUTTON_HEIGHT, visibleTop, visibleBottom, scrollOff, maxScroll);

        // Update positions of buttons/sliders with scroll
        if (toggleButton != null) toggleButton.yPosition = y0;
        if (resetOnExitButton != null) resetOnExitButton.yPosition = y0 + SPACING;
        if (requireHoldButton != null) requireHoldButton.yPosition = y0 + SPACING * 2;
        if (zoomKeyButton != null) zoomKeyButton.yPosition = y0 + SPACING * 2; // Zoom key button at row 2, to the right
        if (smoothZoomButton != null) smoothZoomButton.yPosition = y0 + SPACING * 3;
        if (smoothSlider != null) smoothSlider.yPosition = y0 + SPACING * 3; // Smooth slider at row 3, to the right
        if (setKeyButton != null) setKeyButton.yPosition = y0 + SPACING * 5;
        if (speedSlider != null) speedSlider.yPosition = y0 + SPACING * 6;
        if (fovToggleButton != null) fovToggleButton.yPosition = y0 + SPACING * 8; // FOV toggle at row 8 (one-row gap above)
        if (fovSlider != null) fovSlider.yPosition = y0 + SPACING * 8; // FOV slider at row 8 (one-row gap above)
        if (advancedRenderDistanceKeyButton != null) advancedRenderDistanceKeyButton.yPosition = y0 + SPACING * 9;
        // Lock scroll is the row immediately after Smooth zoom transitions
        if (lockScrollButton != null) lockScrollButton.yPosition = y0 + SPACING * 4;

        scroll.drawScrollbar(width, mouseY);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        enabledOverlays.drawAllFaded(visibleTop, visibleBottom, scrollOff, maxScroll);
        GomodSearchHighlight.draw(buttonList, PerspectiveGUI.class);

        if (isHovering(toggleButton, mouseX, mouseY)) {
            drawHoverTooltipOnTop(Arrays.asList(
                    EnumChatFormatting.AQUA + "Advanced perspective",
                    EnumChatFormatting.GRAY + "Only works in singleplayer, lobbies and replays"
            ), mouseX, mouseY);
        }
        if (isHovering(advancedRenderDistanceKeyButton, mouseX, mouseY)) {
            drawHoverTooltipOnTop(Arrays.asList(
                    EnumChatFormatting.AQUA + "Advanced render distance",
                    EnumChatFormatting.GRAY + "Hold the key and scroll to change the render distance block by block"
            ), mouseX, mouseY);
        }
    }

    private boolean isHovering(GuiButton btn, int mx, int my) {
        return btn != null
                && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    /** Draw after enabled overlay pass so text sits above the green highlight. */
    private void drawHoverTooltipOnTop(java.util.List<String> lines, int mouseX, int mouseY) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 400);
        GlStateManager.disableDepth();
        try {
            drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }




    
    
    
    

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: {
                boolean now = !PerspectiveDistance.isEnabled();
                PerspectiveDistance.setEnabled(now);
                toggleButton.displayString = (now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled");
                enabledOverlays.onToggled("perspective", now);
                break;
            }
            case 2:
                PerspectiveDistance.setResetOnExit(!PerspectiveDistance.isResetOnExit());
                resetOnExitButton.displayString = (PerspectiveDistance.isResetOnExit() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
                break;
            case 3:
                awaitingKey = 1;
                setKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
                break;
            case 4:
                PerspectiveDistance.setRequireHold(!PerspectiveDistance.isRequireHold());
                requireHoldButton.displayString = (PerspectiveDistance.isRequireHold() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
                break;
            case 5:
                PerspectiveDistance.setSmoothZoom(!PerspectiveDistance.isSmoothZoom());
                smoothZoomButton.displayString = (PerspectiveDistance.isSmoothZoom() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
                break;
            case 6:
                awaitingZoomKey = 1;
                zoomKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
                break;
            case 7:
                PerspectiveDistance.setLockScroll(!PerspectiveDistance.isLockScroll());
                lockScrollButton.displayString = (PerspectiveDistance.isLockScroll() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
                break;
            case 8: {
                boolean now = !PerspectiveDistance.isFOVEnabled();
                PerspectiveDistance.setFOVEnabled(now);
                fovToggleButton.displayString = (now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled");
                enabledOverlays.onToggled("fov", now);
                break;
            }
            case 9:
                awaitingAdvancedRenderDistanceKey = 1;
                advancedRenderDistanceKeyButton.displayString = EnumChatFormatting.YELLOW + "Press a key...";
                break;
            case 100:
                // FOV slider changed
                if (button instanceof PerspectiveFOVSlider) {
                    PerspectiveFOVSlider slider = (PerspectiveFOVSlider) button;
                    PerspectiveDistance.setFOV(slider.getValue());
                }
                break;
            case 101:
                // Speed slider changed
                if (button instanceof SpeedSlider) {
                    SpeedSlider slider = (SpeedSlider) button;
                    PerspectiveDistance.setScrollStep(slider.getValue());
                }
                break;
            case 102:
                // Smooth slider changed
                if (button instanceof SmoothSlider) {
                    SmoothSlider slider = (SmoothSlider) button;
                    me.napim.gomod.features.ConfigManager.setPerspectiveSmoothSpeed(slider.getValue());
                }
                break;
            case 99: // Back button
                GomodMenuTransitions.openMainMenuWithoutAnimation(this.mc);
                break;
        }
    }
    
    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        
        // Handle slider clicks
        for (GuiButton button : this.buttonList) {
            if (button instanceof SpeedSlider || button instanceof SmoothSlider || button instanceof PerspectiveFOVSlider) {
                if (button.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
                    this.selectedButton = button;
                    // Save the value immediately on click
                    if (button instanceof SpeedSlider) {
                        SpeedSlider slider = (SpeedSlider) button;
                        PerspectiveDistance.setScrollStep(slider.getValue());
                    } else if (button instanceof SmoothSlider) {
                        SmoothSlider slider = (SmoothSlider) button;
                        me.napim.gomod.features.ConfigManager.setPerspectiveSmoothSpeed(slider.getValue());
                    } else if (button instanceof PerspectiveFOVSlider) {
                        PerspectiveFOVSlider slider = (PerspectiveFOVSlider) button;
                        PerspectiveDistance.setFOV(slider.getValue());
                    }
                    break;
                }
            }
        }
    }
    
    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        
        // Handle slider dragging
        if (this.selectedButton instanceof SpeedSlider || this.selectedButton instanceof SmoothSlider || this.selectedButton instanceof PerspectiveFOVSlider) {
            if (this.selectedButton instanceof SpeedSlider) {
                ((SpeedSlider) this.selectedButton).dragTo(mouseX);
            } else if (this.selectedButton instanceof SmoothSlider) {
                ((SmoothSlider) this.selectedButton).dragTo(mouseX);
            } else if (this.selectedButton instanceof PerspectiveFOVSlider) {
                ((PerspectiveFOVSlider) this.selectedButton).dragTo(mouseX);
            }
            
            // Save the value immediately during dragging
            if (this.selectedButton instanceof SpeedSlider) {
                SpeedSlider slider = (SpeedSlider) this.selectedButton;
                PerspectiveDistance.setScrollStep(slider.getValue());
            } else if (this.selectedButton instanceof SmoothSlider) {
                SmoothSlider slider = (SmoothSlider) this.selectedButton;
                me.napim.gomod.features.ConfigManager.setPerspectiveSmoothSpeed(slider.getValue());
            } else if (this.selectedButton instanceof PerspectiveFOVSlider) {
                PerspectiveFOVSlider slider = (PerspectiveFOVSlider) this.selectedButton;
                PerspectiveDistance.setFOV(slider.getValue());
            }
        }
    }
    
    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        
        // Handle slider release
        if (this.selectedButton instanceof SpeedSlider || this.selectedButton instanceof SmoothSlider || this.selectedButton instanceof PerspectiveFOVSlider) {
            this.selectedButton.mouseReleased(mouseX, mouseY);
            
            // Save the final value when releasing
            if (this.selectedButton instanceof SpeedSlider) {
                SpeedSlider slider = (SpeedSlider) this.selectedButton;
                PerspectiveDistance.setScrollStep(slider.getValue());
            } else if (this.selectedButton instanceof SmoothSlider) {
                SmoothSlider slider = (SmoothSlider) this.selectedButton;
                me.napim.gomod.features.ConfigManager.setPerspectiveSmoothSpeed(slider.getValue());
            } else if (this.selectedButton instanceof PerspectiveFOVSlider) {
                PerspectiveFOVSlider slider = (PerspectiveFOVSlider) this.selectedButton;
                PerspectiveDistance.setFOV(slider.getValue());
            }
        }
        this.selectedButton = null;
    }

    private void updateScrollBounds(int rowStartY) {
        int contentBottom = rowStartY + SPACING * 9 + BUTTON_HEIGHT + 8;
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10);
        scroll.updateBounds(rowStartY, contentBottom, rowStartY, visibleBottom);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (awaitingKey == 1) {
            if (keyCode == 1) { // ESC clears keybind
                awaitingKey = -1;
                PerspectiveDistance.setResetKeyCode(0);
                GomodKeybinds.applyConfigBindings();
                setKeyButton.displayString = formatKey(PerspectiveDistance.getResetKeyCode());
                return;
            }
            PerspectiveDistance.setResetKeyCode(keyCode);
            GomodKeybinds.applyConfigBindings();
            awaitingKey = -1;
            setKeyButton.displayString = formatKey(PerspectiveDistance.getResetKeyCode());
            return;
        }
        
        if (awaitingZoomKey == 1) {
            if (keyCode == 1) { // ESC clears keybind
                awaitingZoomKey = -1;
                PerspectiveDistance.setZoomKeyCode(0);
                GomodKeybinds.applyConfigBindings();
                zoomKeyButton.displayString = formatKey(PerspectiveDistance.getZoomKeyCode());
                return;
            }
            PerspectiveDistance.setZoomKeyCode(keyCode);
            GomodKeybinds.applyConfigBindings();
            awaitingZoomKey = -1;
            zoomKeyButton.displayString = formatKey(PerspectiveDistance.getZoomKeyCode());
            return;
        }

        if (awaitingAdvancedRenderDistanceKey == 1) {
            if (keyCode == 1) { // ESC clears keybind
                awaitingAdvancedRenderDistanceKey = -1;
                GomodKeybinds.applyAdvancedRenderDistanceKeyCode(0);
                advancedRenderDistanceKeyButton.displayString = formatAdvancedRenderDistanceKey(AdvancedRenderDistance.getKeyCode());
                return;
            }
            GomodKeybinds.applyAdvancedRenderDistanceKeyCode(keyCode);
            awaitingAdvancedRenderDistanceKey = -1;
            advancedRenderDistanceKeyButton.displayString = formatAdvancedRenderDistanceKey(AdvancedRenderDistance.getKeyCode());
            return;
        }
        
        super.keyTyped(typedChar, keyCode);
    }

    private String formatKey(int keyCode) {
        if (keyCode <= 0) {
            return "Key: " + EnumChatFormatting.WHITE + "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        if (name == null || name.trim().isEmpty()) {
            name = "UNKNOWN";
        }
        return "Key: " + EnumChatFormatting.WHITE + name;
    }

    private String formatAdvancedRenderDistanceKey(int keyCode) {
        if (keyCode <= 0) {
            return "Key: " + EnumChatFormatting.WHITE + "NONE";
        }
        String name = Keyboard.getKeyName(keyCode);
        if (name == null || name.trim().isEmpty()) {
            name = "UNKNOWN";
        }
        return "Key: " + EnumChatFormatting.WHITE + name + EnumChatFormatting.GRAY + " + SCROLL";
    }


    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingKey == 1 || awaitingZoomKey == 1 || awaitingAdvancedRenderDistanceKey == 1;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

// Custom slider for speed (Minecraft-style)
class SpeedSlider extends GuiButton {
    private final float minValue;
    private final float maxValue;
    private float currentValue;
    private float sliderValue;
    private boolean dragging = false;
    
    public SpeedSlider(int id, int x, int y, String name, float min, float max, float current) {
        super(id, x, y, 120, 20, "");
        this.minValue = min;
        this.maxValue = max;
        this.currentValue = current;
        this.sliderValue = (current - min) / (max - min);
        this.displayString = String.format("Speed: x%.2f", currentValue);
    }
    
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            // Draw vanilla button background (fixed normal state; no hover brightening)
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46; // always use dark normal row (no hover brightening)
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);
            
            // Draw slider knob
            int knobX = this.xPosition + (int)(this.sliderValue * (this.width - 8));
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
            
            // Text on top
            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0; // vanilla: yellow on hover, gray otherwise
            int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
            int textX = this.xPosition + (this.width - textWidth) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
        }
    }
    
    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width &&
            mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            // Immediately update value on click
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("Speed: x%.2f", currentValue);
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
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("Speed: x%.2f", currentValue);
        }
    }
    
    public float getValue() {
        return currentValue;
    }

    public void dragTo(int mouseX) {
        float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
        this.displayString = String.format("Speed: x%.2f", currentValue);
    }
}

// Custom slider for smooth speed (Minecraft-style)
class SmoothSlider extends GuiButton {
    private final float minValue;
    private final float maxValue;
    private float currentValue;
    private float sliderValue;
    private boolean dragging = false;
    
    public SmoothSlider(int id, int x, int y, String name, float min, float max, float current) {
        super(id, x, y, 120, 20, "");
        this.minValue = min;
        this.maxValue = max;
        this.currentValue = current;
        this.sliderValue = (current - min) / (max - min);
        this.displayString = String.format("Smooth: %.2f", currentValue);
    }
    
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46; // keep dark background
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);
            int knobX = this.xPosition + (int)(this.sliderValue * (this.width - 8));
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
            int textX = this.xPosition + (this.width - textWidth) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
        }
    }
    
    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width &&
            mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            // Immediately update value on click
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("Smooth: %.2f", currentValue);
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
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("Smooth: %.2f", currentValue);
        }
    }
    
    public float getValue() {
        return currentValue;
    }

    public void dragTo(int mouseX) {
        float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
        this.displayString = String.format("Smooth: %.2f", currentValue);
    }
}

// Custom slider for perspective FOV (Minecraft-style)
class PerspectiveFOVSlider extends GuiButton {
    private final float minValue;
    private final float maxValue;
    private float currentValue;
    private float sliderValue;
    private boolean dragging = false;
    
    public PerspectiveFOVSlider(int id, int x, int y, String name, float min, float max, float current) {
        super(id, x, y, 120, 20, "");
        this.minValue = min;
        this.maxValue = max;
        this.currentValue = current;
        this.sliderValue = (current - min) / (max - min);
        this.displayString = String.format("FOV: %.0f", currentValue);
    }
    
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible) {
            mc.getTextureManager().bindTexture(GuiButton.buttonTextures);
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            int v = 46; // keep dark background
            int half = this.width / 2;
            this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
            this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);
            int knobX = this.xPosition + (int)(this.sliderValue * (this.width - 8));
            this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
            this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);
            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
            int textColor = hovered ? 0xFFFFA0 : 0xE0E0E0;
            int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
            int textX = this.xPosition + (this.width - textWidth) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
        }
    }
    
    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width &&
            mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            // Immediately update value on click
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("FOV: %.0f", currentValue);
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
            float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
            normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
            this.sliderValue = normalizedPos;
            this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
            this.displayString = String.format("FOV: %.0f", currentValue);
        }
    }
    
    public float getValue() {
        return currentValue;
    }

    public void dragTo(int mouseX) {
        float normalizedPos = (float)(mouseX - (this.xPosition + 4)) / (float)(this.width - 8);
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentValue = minValue + (normalizedPos * (maxValue - minValue));
        this.displayString = String.format("FOV: %.0f", currentValue);
    }
}
