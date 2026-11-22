package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MobNametags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.util.Arrays;

/**
 * GUI for configuring Mob Health Display settings
 */
public class MobHealthDisplayGUI extends GuiScreen {
    private final GuiScreen returnParent;

    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int SLIDER_HEIGHT = 20;
    private static final int SLIDER_WIDTH = 120;
    private static final HudSliderStyle OPACITY_SLIDER_STYLE = HudSliderStyle.FILL_BAR;
    private static final HudSliderStyle DISTANCE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;
    private static final int POS_FIELD_TOTAL_W = 120;
    private static final int POS_FIELD_GAP = 4;
    private static final int POS_FIELD_BOX_W = (POS_FIELD_TOTAL_W - POS_FIELD_GAP) / 2;
    private static final int POS_FIELD_H = BUTTON_HEIGHT;
    private static final int MOB_FILTER_BTN_SIZE = 20;
    private static final int MOB_FILTER_GAP = 4;
    /** Spawn-egg metadata for chicken / ocelot / rabbit in 1.8.9. */
    private static final int SPAWN_EGG_CHICKEN = 93;
    private static final int SPAWN_EGG_OCELOT = 98;
    private static final int SPAWN_EGG_RABBIT = 101;
    
    // Toggle buttons
    private GuiButton toggleButton;
    private GuiButton showEffectsButton;
    private GuiButton textShadowButton;
    private GuiButton displayModeButton;
    private GuiButton mobNametagsButton;
    private MobHealthTagIconButton chickenFilterButton;
    private MobHealthTagIconButton ocelotFilterButton;
    private MobHealthTagIconButton rabbitFilterButton;
    private GuiButton allMobsPickerButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton backButton;
    
    private int posXInputX, posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            MobHealthDisplay::setCustomPosition);
    
    // Opacity slider
    private int opacitySliderX, opacitySliderY;
    private int opacity = 120;
    private HudBackgroundOpacityRow bgOpacityRow;
    private boolean isDraggingOpacity = false;
    
    // Distance slider
    private int distanceSliderX, distanceSliderY;
    private double maxDistance = 32.0;
    private boolean isDraggingDistance = false;
    
    // Persistence time slider
    private int persistenceSliderX, persistenceSliderY;
    private double persistenceTime = 2.0;
    private boolean isDraggingPersistence = false;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    public MobHealthDisplayGUI() {
        this(null);
    }

    public MobHealthDisplayGUI(GuiScreen returnParent) {
        this.returnParent = returnParent;
    }

    @Override
    public void initGui() {
        super.initGui();
        
        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());
        int centerY = scaledResolution.getScaledHeight() / 2;
        
        // Load current values
        loadCurrentValues();
        
        // Two-column layout
        int rightColumnX = width / 2 + 80;
        int rowStartY = Math.max(90, centerY - 100);
        int buttonWidth = 120;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;

        toggleButton = new GuiButton(0, rightColumnX, rowStartY, buttonWidth, BUTTON_HEIGHT,
            (MobHealthDisplay.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));

        showEffectsButton = new GuiButton(4, rightColumnX, rowStartY + SPACING, buttonWidth, BUTTON_HEIGHT,
                getShowEffectsButtonText());

        textShadowButton = new GuiButton(5, rightColumnX, rowStartY + SPACING * 2, buttonWidth, BUTTON_HEIGHT,
                getTextShadowButtonText());

        displayModeButton = new GuiButton(6, rightColumnX, rowStartY + SPACING * 3, buttonWidth, BUTTON_HEIGHT,
                getDisplayModeButtonText());

        int filterRowY = rowStartY + SPACING * 3;
        int filterX = rightColumnX + buttonWidth + MOB_FILTER_GAP;
        chickenFilterButton = new MobHealthTagIconButton(7, filterX, filterRowY, MOB_FILTER_BTN_SIZE, BUTTON_HEIGHT,
                new ItemStack(Items.spawn_egg, 1, SPAWN_EGG_CHICKEN),
                () -> ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_CHICKEN));
        filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        ocelotFilterButton = new MobHealthTagIconButton(8, filterX, filterRowY, MOB_FILTER_BTN_SIZE, BUTTON_HEIGHT,
                new ItemStack(Items.spawn_egg, 1, SPAWN_EGG_OCELOT),
                () -> ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_OCELOT));
        filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        rabbitFilterButton = new MobHealthTagIconButton(9, filterX, filterRowY, MOB_FILTER_BTN_SIZE, BUTTON_HEIGHT,
                new ItemStack(Items.spawn_egg, 1, SPAWN_EGG_RABBIT),
                () -> ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_RABBIT));
        filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        allMobsPickerButton = new GuiButton(10, filterX, filterRowY, MOB_FILTER_BTN_SIZE, BUTTON_HEIGHT,
                EnumChatFormatting.GOLD + "..");

        mobNametagsButton = new GuiButton(11, rightColumnX, rowStartY + SPACING * 4, buttonWidth, BUTTON_HEIGHT,
                getMobNametagsButtonText());
        refreshMobNametagsButtonState();

        posXInputX = rightColumnX;
        posXInputY = rowStartY + SPACING * 5 + 20;
        positionFields.init(fontRendererObj, MobHealthDisplay.getPanelX(), MobHealthDisplay.getPanelY(),
                posXInputX, posXInputY);

        int posRowY = posXInputY + SPACING;
        editPositionHudButton = new GuiButton(3, rightColumnX, posRowY, halfW, BUTTON_HEIGHT, GuiHeader.HUD_EDITOR_EDIT_BUTTON);
        resetPositionButton = new GuiButton(2, rightColumnX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX, posRowY, halfW,
                BUTTON_HEIGHT, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        distanceSliderX = rightColumnX;
        distanceSliderY = posRowY + SPACING + 20;

        persistenceSliderX = rightColumnX;
        persistenceSliderY = distanceSliderY + SPACING;

        opacitySliderX = rightColumnX;
        opacitySliderY = persistenceSliderY + SPACING;
        
        // Back button
        backButton = new GuiButton(99, width / 2 - 100, GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                GuiHeader.moduleSettingsBackButtonLabel(this.returnParent));
        
        this.buttonList.add(toggleButton);
        this.buttonList.add(showEffectsButton);
        this.buttonList.add(textShadowButton);
        this.buttonList.add(displayModeButton);
        this.buttonList.add(mobNametagsButton);
        updateMobFilterButtonVisibility();
        this.buttonList.add(editPositionHudButton);
        this.buttonList.add(resetPositionButton);
        this.buttonList.add(backButton);
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(MobHealthDisplay::getBackgroundOpacity,
                MobHealthDisplay::setBackgroundOpacity, MobHealthDisplay::getBackgroundColor,
                MobHealthDisplay::setBackgroundColor);
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
    }

    private void updateMobFilterButtonVisibility() {
        boolean show = true;
        if (chickenFilterButton != null) {
            chickenFilterButton.visible = show;
            if (show && !buttonList.contains(chickenFilterButton)) {
                buttonList.add(chickenFilterButton);
            } else if (!show) {
                buttonList.remove(chickenFilterButton);
            }
        }
        if (ocelotFilterButton != null) {
            ocelotFilterButton.visible = show;
            if (show && !buttonList.contains(ocelotFilterButton)) {
                buttonList.add(ocelotFilterButton);
            } else if (!show) {
                buttonList.remove(ocelotFilterButton);
            }
        }
        if (rabbitFilterButton != null) {
            rabbitFilterButton.visible = show;
            if (show && !buttonList.contains(rabbitFilterButton)) {
                buttonList.add(rabbitFilterButton);
            } else if (!show) {
                buttonList.remove(rabbitFilterButton);
            }
        }
        if (allMobsPickerButton != null) {
            allMobsPickerButton.visible = show;
            if (show && !buttonList.contains(allMobsPickerButton)) {
                buttonList.add(allMobsPickerButton);
            } else if (!show) {
                buttonList.remove(allMobsPickerButton);
            }
        }
    }

    private void registerEnabledOverlays() {
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return MobHealthDisplay.isEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("mobNametags", mobNametagsButton,
                new ModuleSettingsEnabledOverlays.EnabledCheck() {
                    @Override
                    public boolean isOn() {
                        return MobNametags.isEnabled() || MobHealthDisplay.isMobNametagsForcedByCurrentMode();
                    }
                });
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }
    
    private void loadCurrentValues() {
        opacity = MobHealthDisplay.getBackgroundOpacity();
        maxDistance = MobHealthDisplay.getMaxDistance();
        persistenceTime = MobHealthDisplay.getPersistenceTime();
    }
    
    private void applyScrollLayout(int rowStartY) {
        int rx = width / 2 + 80;
        int buttonWidth = 120;
        toggleButton.xPosition = rx;
        toggleButton.yPosition = rowStartY;
        showEffectsButton.xPosition = rx;
        showEffectsButton.yPosition = rowStartY + SPACING;
        textShadowButton.xPosition = rx;
        textShadowButton.yPosition = rowStartY + SPACING * 2;
        displayModeButton.xPosition = rx;
        displayModeButton.yPosition = rowStartY + SPACING * 3;
        mobNametagsButton.xPosition = rx;
        mobNametagsButton.yPosition = rowStartY + SPACING * 4;
        layoutMobFilterButtons(rx, rowStartY + SPACING * 3, buttonWidth);
        int posRowY = rowStartY + SPACING * 5 + 20 + SPACING;
        int halfW = (buttonWidth - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        editPositionHudButton.xPosition = rx;
        editPositionHudButton.yPosition = posRowY;
        editPositionHudButton.width = halfW;
        resetPositionButton.xPosition = rx + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        resetPositionButton.yPosition = posRowY;
        resetPositionButton.width = halfW;
        posXInputX = rx;
        posXInputY = rowStartY + SPACING * 5 + 20;
        positionFields.syncLayout(posXInputX, posXInputY);
        distanceSliderX = rx;
        distanceSliderY = posRowY + SPACING + 20;
        persistenceSliderX = rx;
        persistenceSliderY = distanceSliderY + SPACING;
        opacitySliderX = rx;
        opacitySliderY = persistenceSliderY + SPACING;
    }

    private void layoutMobFilterButtons(int rightColumnX, int rowY, int displayModeWidth) {
        int filterX = rightColumnX + displayModeWidth + MOB_FILTER_GAP;
        if (chickenFilterButton != null) {
            chickenFilterButton.xPosition = filterX;
            chickenFilterButton.yPosition = rowY;
            filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        }
        if (ocelotFilterButton != null) {
            ocelotFilterButton.xPosition = filterX;
            ocelotFilterButton.yPosition = rowY;
            filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        }
        if (rabbitFilterButton != null) {
            rabbitFilterButton.xPosition = filterX;
            rabbitFilterButton.yPosition = rowY;
            filterX += MOB_FILTER_BTN_SIZE + MOB_FILTER_GAP;
        }
        if (allMobsPickerButton != null) {
            allMobsPickerButton.xPosition = filterX;
            allMobsPickerButton.yPosition = rowY;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        scroll.tick();

        String title = EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "Mob Health Display Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawPreviewPanel();

        int labelX = width / 2 - 200;
        int baseRowStartY = Math.max(90, height / 2 - 100);
        int scrollOff = scroll.getOffset();
        int rowStartY = baseRowStartY - scrollOff;
        applyScrollLayout(rowStartY);
        refreshMobNametagsButtonState();

        int contentBottom = opacitySliderY + SLIDER_HEIGHT + 20;
        scroll.updateBounds(baseRowStartY, contentBottom, baseRowStartY,
                GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10));
        int fadeTop = scroll.getVisibleTop();
        int fadeBottom = scroll.getVisibleBottom();
        int fadeMax = scroll.getMaxScroll();
        GuiScrollFade.drawLabel(fontRendererObj, "Toggle Mob Health Display", labelX, rowStartY, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Show effects", labelX, rowStartY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Text shadow", labelX, rowStartY + SPACING * 2, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Display mode", labelX, rowStartY + SPACING * 3, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Mob nametags", labelX, rowStartY + SPACING * 4, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Position X,Y:", labelX, posXInputY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Edit Position:", labelX, posXInputY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Max Distance", labelX, distanceSliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Persistence Time", labelX, persistenceSliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Background Opacity", labelX, opacitySliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);

        positionFields.setScrollOffset(scrollOff);
        positionFields.draw();

        drawStyledSlider(distanceSliderX, distanceSliderY, distanceNormalized(), 0xFF4CAF50,
                formatMaxDistanceButtonLabel(maxDistance), DISTANCE_SLIDER_STYLE);
        drawStyledSlider(persistenceSliderX, persistenceSliderY, persistenceNormalized(), 0xFFFF9800,
                String.format("%.1f sec", persistenceTime), DISTANCE_SLIDER_STYLE);
        opacity = MobHealthDisplay.getBackgroundOpacity();
        if (bgOpacityRow != null) {
            bgOpacityRow.setBounds(opacitySliderX, opacitySliderY, SLIDER_WIDTH, SLIDER_HEIGHT);
            bgOpacityRow.drawSlider(fontRendererObj, "Opacity: " + opacity);
        }

        scroll.drawScrollbar(width, mouseY);

        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerButton(mc, mouseX, mouseY);
        }
        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);
        enabledOverlays.drawAllFaded(fadeTop, fadeBottom, scrollOff, fadeMax);
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        drawMobFilterTooltips(mouseX, mouseY);
        drawMobNametagsTooltip(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, MobHealthDisplayGUI.class);
    }

    private void drawMobFilterTooltips(int mouseX, int mouseY) {
        if (chickenFilterButton != null && isMouseOver(chickenFilterButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(getMobFilterTooltip("chickens",
                    ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_CHICKEN))), mouseX,
                    Math.max(0, mouseY - 12));
            return;
        }
        if (ocelotFilterButton != null && isMouseOver(ocelotFilterButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(getMobFilterTooltip("ocelots",
                    ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_OCELOT))), mouseX,
                    Math.max(0, mouseY - 12));
            return;
        }
        if (rabbitFilterButton != null && isMouseOver(rabbitFilterButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(getMobFilterTooltip("rabbits",
                    ConfigManager.isMobHealthDisplayHealthTagEnabledForType(SPAWN_EGG_RABBIT))), mouseX,
                    Math.max(0, mouseY - 12));
            return;
        }
        if (allMobsPickerButton != null && isMouseOver(allMobsPickerButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(EnumChatFormatting.GRAY + "Choose mobs for health tag"), mouseX,
                    Math.max(0, mouseY - 12));
        }
    }

    private void drawMobNametagsTooltip(int mouseX, int mouseY) {
        if (mobNametagsButton != null
                && MobHealthDisplay.isMobNametagsForcedByCurrentMode()
                && isMouseOver(mobNametagsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Mob nametags are forced on by "
                            + EnumChatFormatting.WHITE + "HUD + Healthtag 2",
                    EnumChatFormatting.GRAY + "because that mode combines the mob name and health in one tag."),
                    mouseX, Math.max(0, mouseY - 12));
        }
    }

    private static String getMobFilterTooltip(String mobPlural, boolean enabled) {
        if (enabled) {
            return EnumChatFormatting.GRAY + "Disable health tag for " + mobPlural;
        }
        return EnumChatFormatting.GRAY + "Enable health tag for " + mobPlural;
    }

    private static boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }
    
    /**
     * Draws a preview section showing how the mob health display would look
     */
    private void drawPreviewSection() {
        int previewY = 60;
        String previewLabel = "Preview:";
        int previewLabelWidth = fontRendererObj.getStringWidth(previewLabel);
        fontRendererObj.drawString(previewLabel, width / 2 - previewLabelWidth / 2, previewY, 0xFFFFFF);
        
        // Build the preview text based on current settings
        String previewText = buildPreviewText();
        int previewTextWidth = fontRendererObj.getStringWidth(previewText);
        fontRendererObj.drawString(previewText, width / 2 - previewTextWidth / 2, previewY + 15, 0xFFFFFF);
        
        // Draw preview panel at the configured position
        drawPreviewPanel();
    }
    
    /**
     * Builds the preview text showing current settings
     */
    private String buildPreviewText() {
        StringBuilder preview = new StringBuilder();
        preview.append(EnumChatFormatting.GRAY).append("Settings: ");
        
        if (MobHealthDisplay.isEnabled()) {
            preview.append(EnumChatFormatting.GREEN).append("Enabled");
        } else {
            preview.append(EnumChatFormatting.RED).append("Disabled");
        }
        
        preview.append(EnumChatFormatting.GRAY).append(" | Distance: ");
        preview.append(EnumChatFormatting.YELLOW).append(String.format("%.1f", maxDistance));
        preview.append(EnumChatFormatting.GRAY).append(" blocks");
        
        return preview.toString();
    }
    
    /**
     * Draws a preview panel showing how the mob health display would appear
     * This matches the actual MobHealthDisplay implementation
     */
    private void drawPreviewPanel() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            net.minecraft.entity.passive.EntityCow cow = new net.minecraft.entity.passive.EntityCow(mc.theWorld);
            cow.setHealth(8.0F);
            if (MobHealthDisplay.isShowEffects()) {
                cow.addPotionEffect(new PotionEffect(Potion.moveSpeed.id, 32767, 0));
            }

            if (MobHealthDisplay.isPanelMode()) {
                boolean showNametagOnCow = MobHealthDisplay.isNametagMode();
                MobHealthDisplay.drawMobPanel(mc, cow, showNametagOnCow);
            } else if (MobHealthDisplay.isNametagMode()) {
                MobHealthDisplay.drawNametagOnlyGuiPreview(mc, cow);
            }
        } catch (Throwable ignored) {}
    }

    private static String getShowEffectsButtonText() {
        return MobHealthDisplay.isShowEffects()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private static String getTextShadowButtonText() {
        return MobHealthDisplay.isTextShadow()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private static String getDisplayModeButtonText() {
        String mode = MobHealthDisplay.getDisplayMode();
        if ("NAMETAG".equals(mode)) {
            return EnumChatFormatting.AQUA + "Health tag";
        }
        if ("BOTH".equals(mode)) {
            return EnumChatFormatting.YELLOW + "HUD + Health tag";
        }
        if ("BOTH2".equals(mode)) {
            return EnumChatFormatting.GOLD + "HUD + Healthtag 2";
        }
        return EnumChatFormatting.WHITE + "HUD Panel";
    }

    private static String getMobNametagsButtonText() {
        return (MobNametags.isEnabled() || MobHealthDisplay.isMobNametagsForcedByCurrentMode())
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    private void refreshMobNametagsButtonState() {
        if (mobNametagsButton == null) {
            return;
        }
        if (MobHealthDisplay.isMobNametagsForcedByCurrentMode()) {
            MobHealthDisplay.enforceMobNametagsForCurrentMode();
        }
        mobNametagsButton.displayString = getMobNametagsButtonText();
        mobNametagsButton.enabled = !MobHealthDisplay.isMobNametagsForcedByCurrentMode();
    }

    /**
     * Formats health text exactly like the real MobHealthDisplay implementation
     */
    private String formatHealthText(float hp, float maxHp) {
        try {
            int currentHp = (int) Math.ceil(hp);
            int maxHpShown = (int) Math.ceil(maxHp);
            EnumChatFormatting col;
            
            // Color based on percentage of max health (matching real implementation)
            float percentage = hp / maxHp;
            if (percentage >= 0.75f) {
                col = EnumChatFormatting.GREEN;
            } else if (percentage >= 0.40f) {
                col = EnumChatFormatting.YELLOW;
            } else {
                col = EnumChatFormatting.RED;
            }
            
            // Format as current/max with heart icon (matching real implementation)
            return col.toString() + currentHp + "/" + maxHpShown + " " + EnumChatFormatting.RED + "\u2764" + EnumChatFormatting.RESET;
        } catch (Throwable e) {
            int currentHp = (int) Math.ceil(hp);
            int maxHpShown = (int) Math.ceil(maxHp);
            return currentHp + "/" + maxHpShown + " " + "\u00A7c\u2764\u00A7r";
        }
    }
    
    private void drawStyledSlider(int x, int y, float normalized, int accentArgb, String label, HudSliderStyle style) {
        drawStyledSlider(x, y, normalized, accentArgb, label, style, null);
    }

    private void drawStyledSlider(int x, int y, float normalized, int accentArgb, String label, HudSliderStyle style,
            HudOpacitySliderBoxInsets.Module insetModule) {
        style.draw(fontRendererObj, x, y, SLIDER_WIDTH, SLIDER_HEIGHT, normalized, accentArgb, label, insetModule);
    }

    private float opacityNormalized() {
        return opacity / 255.0f;
    }

    private float distanceNormalized() {
        return (float) ((maxDistance - 1.0) / 47.0);
    }

    private float persistenceNormalized() {
        return (float) (persistenceTime / 10.0);
    }

    /**
     * Max-distance slider label: {@code "48 blocks"} — no {@code "Distance:"} prefix (label row above says "Max Distance").
     */
    private static String formatMaxDistanceButtonLabel(double blocks) {
        double r = Math.round(blocks * 10.0) / 10.0;
        if (Math.abs(r - Math.rint(r)) < 0.001) {
            return String.format("%d blocks", (int) Math.rint(r));
        }
        return String.format("%.1f blocks", r);
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
        positionFields.mouseClicked(mouseX, mouseY, mouseButton);

        if (bgOpacityRow != null && bgOpacityRow.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
            return;
        }
        if (hitSlider(mouseX, mouseY, distanceSliderX, distanceSliderY)) {
            isDraggingDistance = true;
            updateDistanceFromMouse(mouseX);
        }
        if (hitSlider(mouseX, mouseY, persistenceSliderX, persistenceSliderY)) {
            isDraggingPersistence = true;
            updatePersistenceFromMouse(mouseX);
        }
    }
    
    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
        
        if (isDraggingDistance) {
            updateDistanceFromMouse(mouseX);
        }
        
        if (isDraggingPersistence) {
            updatePersistenceFromMouse(mouseX);
        }
    }
    
    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        isDraggingOpacity = false;
        isDraggingDistance = false;
        isDraggingPersistence = false;
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseReleased(mouseX, mouseY);
        }
    }
    
    private static boolean hitSlider(int mouseX, int mouseY, int sliderX, int sliderY) {
        return mouseX >= sliderX && mouseX <= sliderX + SLIDER_WIDTH
                && mouseY >= sliderY && mouseY <= sliderY + SLIDER_HEIGHT;
    }

    private void updateDistanceFromMouse(int mouseX) {
        float n = DISTANCE_SLIDER_STYLE.normalizedFromMouseX(mouseX, distanceSliderX, SLIDER_WIDTH);
        if (n >= 0.995f) {
            n = 1.0f;
        }
        double newDistance = Math.max(1.0, Math.min(48.0, 1.0 + n * 47.0));
        if (Math.abs(newDistance - maxDistance) > 0.01) {
            maxDistance = newDistance;
            MobHealthDisplay.setMaxDistance(maxDistance);
        }
    }

    private void updatePersistenceFromMouse(int mouseX) {
        float n = DISTANCE_SLIDER_STYLE.normalizedFromMouseX(mouseX, persistenceSliderX, SLIDER_WIDTH);
        if (n >= 0.995f) {
            n = 1.0f;
        }
        double newPersistence = Math.max(0.0, Math.min(10.0, n * 10.0));
        if (Math.abs(newPersistence - persistenceTime) > 0.01) {
            persistenceTime = newPersistence;
            MobHealthDisplay.setPersistenceTime(persistenceTime);
        }
    }
    
    @Override
    public void updateScreen() {
        super.updateScreen();
        positionFields.updateCursorCounter();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (positionFields.keyTyped(typedChar, keyCode, () -> GomodMenuTransitions.closeToGame(this.mc))) {
            return;
        }

        if (keyCode == 1) { // ESC
            GomodMenuTransitions.closeToGame(this.mc);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }
    
    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: { // Toggle
                MobHealthDisplay.toggle();
                boolean now = MobHealthDisplay.isEnabled();
                toggleButton.displayString = now ? EnumChatFormatting.GREEN + "Enabled"
                        : EnumChatFormatting.RED + "Disabled";
                enabledOverlays.onToggled("main", now);
                break;
            }

            case 4: { // Show effects
                MobHealthDisplay.toggleShowEffects();
                showEffectsButton.displayString = getShowEffectsButtonText();
                break;
            }

            case 5: {
                MobHealthDisplay.toggleTextShadow();
                textShadowButton.displayString = getTextShadowButtonText();
                break;
            }

            case 6: {
                boolean wasForced = MobHealthDisplay.isMobNametagsForcedByCurrentMode();
                MobHealthDisplay.cycleDisplayMode();
                displayModeButton.displayString = getDisplayModeButtonText();
                refreshMobNametagsButtonState();
                if (!wasForced && MobHealthDisplay.isMobNametagsForcedByCurrentMode()) {
                    enabledOverlays.onToggled("mobNametags", true);
                }
                updateMobFilterButtonVisibility();
                break;
            }

            case 11: {
                if (MobHealthDisplay.isMobNametagsForcedByCurrentMode()) {
                    refreshMobNametagsButtonState();
                    break;
                }
                MobNametags.setEnabled(!MobNametags.isEnabled());
                refreshMobNametagsButtonState();
                enabledOverlays.onToggled("mobNametags", MobNametags.isEnabled());
                break;
            }

            case 7:
                MobHealthDisplay.toggleHealthTagChicken();
                break;

            case 8:
                MobHealthDisplay.toggleHealthTagOcelot();
                break;

            case 9:
                MobHealthDisplay.toggleHealthTagRabbit();
                break;

            case 10:
                mc.displayGuiScreen(new MobHealthTagMobPickerGUI(this));
                break;
                
            case 2: // Reset Position
                MobHealthDisplay.resetPosition();
                positionFields.sync(MobHealthDisplay.getPanelX(), MobHealthDisplay.getPanelY());
                break;

            case 3:
                mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Mob Health Display Settings"));
                break;

            case 99: // Back button
                GomodMenuTransitions.returnToParentOrMainMenu(this.mc, this.returnParent);
                break;
        }
    }
    
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
