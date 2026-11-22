package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.PlayerTarget;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

public class TeammateTrackerGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 25;
    private static final int BUTTON_WIDTH = 120;
    private static final int POS_FIELD_TOTAL_W = 120;
    private static final int POS_FIELD_GAP = 4;
    private static final int POS_FIELD_BOX_W = (POS_FIELD_TOTAL_W - POS_FIELD_GAP) / 2;
    private static final int SLIDER_W = 120;
    private static final int SLIDER_H = 20;
    private static final HudSliderStyle SCALE_SLIDER_STYLE = HudSliderStyle.VANILLA_KNOB;
    /** Layout (depends on window size). */
    private int layoutLabelX;
    private int layoutRightColX;
    private int layoutRowStartY;
    private GuiScreen parent;
    
    // Buttons
    private GuiButton toggleButton;
    private GuiButton verticalDistanceButton;
    private GuiButton tauntInfoButton;
    private GuiButton glowTeammateButton;
    private GuiButton teammateTrailButton;
    private GuiButton textShadowButton;
    private GuiButton teamInviteNotifierButton;
    private GuiButton editPositionHudButton;
    private GuiButton resetPositionButton;
    private GuiButton backButton;
    
    private int posXInputX, posXInputY;
    private final HudPositionFieldPair positionFields = new HudPositionFieldPair(50, 51,
            PlayerTarget::setCustomPosition, true);
    
    // Opacity slider
    private int opacitySliderX, opacitySliderY;
    private int opacity = 120;
    private HudBackgroundOpacityRow bgOpacityRow;
    private boolean isDraggingOpacity = false;
    
    // Scale slider
    private int scaleSliderX, scaleSliderY;
    private float scale = 1.0f;
    private boolean isDraggingScale = false;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();
    /** Item under cursor in the preview; tooltip is drawn after buttons so lines are not clipped. */
    private ItemStack previewTooltipStack;
    private int previewTooltipMouseX;
    private int previewTooltipMouseY;
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    public TeammateTrackerGUI(GuiScreen parent) {
        this.parent = parent;
    }

    /**
     * Same horizontal rhythm as {@link InventoryHUDGUI}: labels left of center, controls right of center,
     * so long label strings do not collide with buttons.
     */
    private void computeLayout() {
        layoutLabelX = width / 2 - 200;
        layoutRightColX = width / 2 + 80;
        layoutRowStartY = Math.max(90, height / 2 - 140);
    }

    /** Bottom of scrollable content before scroll offset (see {@link GuiSmoothScroll#updateBounds}). */
    private int unscrolledContentBottom() {
        int posX = layoutRowStartY + SPACING * 7 + 20;
        int posBtnRow = posX + SPACING;
        int scaleY = posBtnRow + SPACING + 20;
        int opacityY = scaleY + SPACING;
        return opacityY + SLIDER_H + 20;
    }

    /** Aligns buttons/sliders/inputs with {@link #computeLayout}; call every frame so resizing matches labels. */
    private void syncWidgetPositions() {
        computeLayout();
        int rx = layoutRightColX;
        int rowStartY = layoutRowStartY - scroll.getOffset();
        if (toggleButton == null || editPositionHudButton == null) {
            return;
        }
        toggleButton.xPosition = rx;
        toggleButton.yPosition = rowStartY;
        verticalDistanceButton.xPosition = rx;
        verticalDistanceButton.yPosition = rowStartY + SPACING * 2;
        tauntInfoButton.xPosition = rx;
        tauntInfoButton.yPosition = rowStartY + SPACING * 3;
        glowTeammateButton.xPosition = rx;
        glowTeammateButton.yPosition = rowStartY + SPACING * 4;
        teammateTrailButton.xPosition = rx;
        teammateTrailButton.yPosition = rowStartY + SPACING * 5;
        textShadowButton.xPosition = rx;
        textShadowButton.yPosition = rowStartY + SPACING;
        teamInviteNotifierButton.xPosition = rx;
        teamInviteNotifierButton.yPosition = rowStartY + SPACING * 6;

        posXInputX = rx;
        posXInputY = rowStartY + SPACING * 7 + 20;
        positionFields.syncLayout(posXInputX, posXInputY);

        int halfW = (BUTTON_WIDTH - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        int posBtnRowY = posXInputY + SPACING;
        editPositionHudButton.xPosition = rx;
        editPositionHudButton.yPosition = posBtnRowY;
        editPositionHudButton.width = halfW;
        resetPositionButton.xPosition = rx + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        resetPositionButton.yPosition = posBtnRowY;
        resetPositionButton.width = halfW;

        scaleSliderX = rx;
        scaleSliderY = posBtnRowY + SPACING + 20;
        opacitySliderX = rx;
        opacitySliderY = scaleSliderY + SPACING;

        backButton.xPosition = width / 2 - 100;
        backButton.yPosition = GuiHeader.backButtonY(height, BUTTON_HEIGHT);
    }

    @Override
    public void initGui() {
        super.initGui();
        
        opacity = PlayerTarget.getBackgroundOpacity();
        scale = PlayerTarget.getScale();
        
        computeLayout();

        // Toggle button (positions finalized in syncWidgetPositions)
        toggleButton = new GuiButton(1, layoutRightColX, layoutRowStartY, BUTTON_WIDTH, BUTTON_HEIGHT,
                getToggleButtonText());
        
        verticalDistanceButton = new GuiButton(3, layoutRightColX, layoutRowStartY + SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT,
                getVerticalDistanceButtonText());
        
        tauntInfoButton = new GuiButton(6, layoutRightColX, layoutRowStartY + SPACING * 3, BUTTON_WIDTH, BUTTON_HEIGHT,
                getTauntInfoButtonText());

        glowTeammateButton = new GuiButton(7, layoutRightColX, layoutRowStartY + SPACING * 4, BUTTON_WIDTH, BUTTON_HEIGHT,
                getGlowTeammateButtonText());

        teammateTrailButton = new GuiButton(8, layoutRightColX, layoutRowStartY + SPACING * 5, BUTTON_WIDTH, BUTTON_HEIGHT,
                getTeammateTrailButtonText());

        textShadowButton = new GuiButton(10, layoutRightColX, layoutRowStartY + SPACING, BUTTON_WIDTH, BUTTON_HEIGHT,
                getTextShadowButtonText());

        teamInviteNotifierButton = new GuiButton(11, layoutRightColX, layoutRowStartY + SPACING * 6,
                BUTTON_WIDTH, BUTTON_HEIGHT, getTeamInviteNotifierButtonText());

        int halfW = (BUTTON_WIDTH - GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX) / 2;
        int posXInit = layoutRowStartY + SPACING * 7 + 20;
        int posRowY = posXInit + SPACING;
        editPositionHudButton = new GuiButton(9, layoutRightColX, posRowY, halfW, BUTTON_HEIGHT, GuiHeader.HUD_EDITOR_EDIT_BUTTON);
        resetPositionButton = new GuiButton(5, layoutRightColX + halfW + GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX, posRowY, halfW,
                BUTTON_HEIGHT, GuiHeader.HUD_EDITOR_RESET_BUTTON);

        backButton = new GuiButton(0, width / 2 - 100, GuiHeader.backButtonY(height, BUTTON_HEIGHT), 200, BUTTON_HEIGHT,
                GuiHeader.moduleSettingsBackButtonLabel(this.parent));

        this.buttonList.add(toggleButton);
        this.buttonList.add(verticalDistanceButton);
        this.buttonList.add(tauntInfoButton);
        this.buttonList.add(editPositionHudButton);
        this.buttonList.add(resetPositionButton);
        this.buttonList.add(backButton);
        this.buttonList.add(glowTeammateButton);
        this.buttonList.add(teammateTrailButton);
        this.buttonList.add(textShadowButton);
        this.buttonList.add(teamInviteNotifierButton);
        syncWidgetPositions();
        positionFields.init(fontRendererObj, PlayerTarget.getCustomX(), PlayerTarget.getCustomY(),
                posXInputX, posXInputY);
        Keyboard.enableRepeatEvents(true);
        registerEnabledOverlays();
        bgOpacityRow = new HudBackgroundOpacityRow(PlayerTarget::getBackgroundOpacity,
                PlayerTarget::setBackgroundOpacity, PlayerTarget::getBackgroundColor,
                PlayerTarget::setBackgroundColor);
        bgOpacityRow.setInsetModule(HudOpacitySliderBoxInsets.Module.DEFAULT);
        bgOpacityRow.syncPickerFromConfig();
    }

    private void registerEnabledOverlays() {
        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return PlayerTarget.isEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("glow", glowTeammateButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return PlayerTarget.isGlowTeammateEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("trail", teammateTrailButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return PlayerTarget.isTeammateTrailEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("teamInviteNotifier", teamInviteNotifierButton,
                new ModuleSettingsEnabledOverlays.EnabledCheck() {
                    @Override
                    public boolean isOn() {
                        return ConfigManager.isPlayerTargetTeamInviteNotifierEnabled();
                    }
                });
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        scroll.tick();
        computeLayout();
        scroll.updateBounds(layoutRowStartY, unscrolledContentBottom(), layoutRowStartY,
                GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, 10));
        syncWidgetPositions();
        
        // Draw title
        String title = EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "Teammate Tracker Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        
        int labelX = layoutLabelX;
        int scrollOff = scroll.getOffset();
        int rowStartY = layoutRowStartY - scrollOff;
        
        int fadeTop = scroll.getVisibleTop();
        int fadeBottom = scroll.getVisibleBottom();
        int fadeMax = scroll.getMaxScroll();
        GuiScrollFade.drawLabel(fontRendererObj, "Teammate Tracker HUD", labelX, rowStartY, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Show vertical distance", labelX, rowStartY + SPACING * 2, 6,
                BUTTON_HEIGHT, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Show taunt info", labelX, rowStartY + SPACING * 3, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Skin glow", labelX, rowStartY + SPACING * 4, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Travel trail", labelX, rowStartY + SPACING * 5, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Text shadow", labelX, rowStartY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Team invite notifier", labelX, rowStartY + SPACING * 6, 6,
                BUTTON_HEIGHT, fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Position X,Y:", labelX, posXInputY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawLabel(fontRendererObj, "Edit Position:", labelX, posXInputY + SPACING, 6, BUTTON_HEIGHT,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Scale", labelX, scaleSliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        GuiScrollFade.drawStringAt(fontRendererObj, "Background Opacity", labelX, opacitySliderY + 4, 0xFFFFFF,
                fadeTop, fadeBottom, scrollOff, fadeMax);
        
        positionFields.setScrollOffset(scrollOff);
        positionFields.draw();

        drawScaleSlider(scaleSliderX, scaleSliderY, "Scale: " + String.format("%.1f", scale));

        opacity = PlayerTarget.getBackgroundOpacity();
        if (bgOpacityRow != null) {
            bgOpacityRow.setBounds(opacitySliderX, opacitySliderY, SLIDER_W, SLIDER_H);
            bgOpacityRow.drawSlider(fontRendererObj, "Opacity: " + opacity);
        }
        
        // Draw preview example
        drawPreviewExample(mouseX, mouseY);

        scroll.drawScrollbar(width, mouseY);

        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerButton(mc, mouseX, mouseY);
        }
        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 99);
        enabledOverlays.drawAllFaded(fadeTop, fadeBottom, scrollOff, fadeMax);
        GomodSearchHighlight.draw(buttonList, TeammateTrackerGUI.class);
        if (bgOpacityRow != null) {
            bgOpacityRow.drawPickerOverlay(mc, mouseX, mouseY, partialTicks);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        if (previewTooltipStack != null) {
            renderItemTooltip(mc, previewTooltipStack, previewTooltipMouseX, previewTooltipMouseY);
        }
    }
    
    private void drawPreviewHudText(String text, int x, int y, int color) {
        HudModuleTextDraw.draw(fontRendererObj, text, x, y, color, PlayerTarget.isTextShadow());
    }

    /**
     * Draws a preview of the teammate tracker to show users what it will look like
     */
    private void drawPreviewExample(int mouseX, int mouseY) {
        previewTooltipStack = null;
        final boolean[] isHoveringCursor = {false}; // Track cursor hover state (array for mutability in try block)
        try {
            // Calculate preview position using actual coordinates from settings
            // If user is editing, show live preview of what they're typing
            int previewX = positionFields.parseXOrDefault(PlayerTarget.getCustomX());
            int previewY = positionFields.parseYOrDefault(PlayerTarget.getCustomY());
            
            // Apply scale transformation for preview
            GlStateManager.pushMatrix();
            GlStateManager.translate(previewX, previewY, 0);
            GlStateManager.scale(scale, scale, 1.0f);
            GlStateManager.translate(-previewX, -previewY, 0);
            
            int headSize = 20;
            int padding = 4;
            int iconSize = 16;
            
            // ALWAYS show example data (gallantdrop1196) in the GUI preview
            // Don't show actual tracked player to avoid confusion
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.entity.player.EntityPlayer currentTarget = null; // Always null for example
            
            String displayName;
            String hpText;
            String distText;
            ResourceLocation actualSkin = null;
            
            // Always use example data - show GallantDrop1196 with [TEAM] prefix
            displayName = EnumChatFormatting.WHITE + "[TEAM] " + EnumChatFormatting.GREEN + "GallantDrop1196";
            hpText = EnumChatFormatting.GREEN + "20 " + EnumChatFormatting.RED + "\u2764";
            distText = PlayerTarget.isShowVerticalDistance() ? 
                "15.5m (" + EnumChatFormatting.GREEN + "\u25B2" + EnumChatFormatting.RESET + " 2.3)" : "15.5m";
            
            // Calculate widths
            int textX = previewX + headSize + 6;
            int nameW = fontRendererObj.getStringWidth(displayName);
            int hpW = fontRendererObj.getStringWidth(hpText);
            int distW = fontRendererObj.getStringWidth(distText) + iconSize + 4;
            int contentW = Math.max(nameW, Math.max(hpW, distW));
            int iconLineY = previewY + headSize + 4;
            
            // Calculate equipment width (5 items horizontally)
            int equipmentWidth = (5 * 18) + (4 * 2); // 5 items of 18px + 4 gaps of 2px
            int equipmentHeight = 18 + 4; // Item size + spacing below cursor
            
            // Add taunt info height if enabled
            if (PlayerTarget.isShowTauntInfo()) {
                equipmentHeight += fontRendererObj.FONT_HEIGHT + 4; // Font height + spacing
            }
            
            // Expand contentW to include equipment if it's wider
            contentW = Math.max(contentW, equipmentWidth);
            
            // Calculate background bounds
            int left = previewX - padding;
            int right = textX + contentW + padding + 2;
            int bottom = Math.max(previewY + headSize, iconLineY + iconSize) + equipmentHeight + padding;
            int top = previewY - padding;
            
            // Draw background if enabled (using exact opacity setting)
            if (PlayerTarget.isShowBackground()) {
                Gui.drawRect(left, top, right, bottom, (opacity << 24) | 0x000000);
            }
            GlStateManager.color(1f, 1f, 1f, 1f);
            
            // Draw player head - use actual target's skin if available, otherwise use default
            ResourceLocation skin;
            if (actualSkin != null) {
                skin = actualSkin;
            } else if (currentTarget == null) {
                // When showing example, try to get GallantDrop1196's skin if they're in the world
                skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
                if (mc != null && mc.theWorld != null) {
                    for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
                        if (player.getName().equalsIgnoreCase("GallantDrop1196")) {
                            try {
                                if (mc.getNetHandler() != null) {
                                    net.minecraft.client.network.NetworkPlayerInfo info = 
                                        mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                                    if (info != null && info.getLocationSkin() != null) {
                                        skin = info.getLocationSkin();
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } else {
                skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
            }
            
            if (mc != null && mc.getTextureManager() != null) {
                mc.getTextureManager().bindTexture(skin);
                GlStateManager.color(1f, 1f, 1f, 1f);
                // Face layer
                Gui.drawScaledCustomSizeModalRect(previewX, previewY, 8, 8, 8, 8, headSize, headSize, 64, 64);
                // Hat layer
                Gui.drawScaledCustomSizeModalRect(previewX, previewY, 40, 8, 8, 8, headSize, headSize, 64, 64);
                
                // No red overlay in example preview
            }
            
            // Draw text (respect Text shadow toggle — same as live teammate tracker)
            drawPreviewHudText(displayName, textX, previewY + 2, 0xFFFFFF);
            drawPreviewHudText(hpText, textX, previewY + 2 + fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFF);
            
            // Draw rotating cursor icon (with animation or actual angle)
            int cursorY = previewY + headSize + 4;
            int cursorX = previewX + (headSize / 2);
            float angleDeg;
            
            // Animate cursor between normal and shift (switch every 2 seconds during rotation)
            long time = System.currentTimeMillis();
            boolean targetSneaking = (time / 2000) % 2 == 0; // Switch every 2 seconds
            
            // Always use slow rotation for preview (it's an example)
            angleDeg = (time % 3600) / 10.0f; // Slow rotation for preview
            
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.translate(cursorX, cursorY + (iconSize / 2.0f), 0.0f);
            GlStateManager.rotate(angleDeg, 0, 0, 1);
            
            // Use the same cursor loading method as PlayerTarget (shift if target is sneaking)
            ResourceLocation cursorTexture = PlayerTarget.getCursorTexture(targetSneaking);
            if (cursorTexture != null && mc != null && mc.getTextureManager() != null) {
                try {
                    mc.getTextureManager().bindTexture(cursorTexture);
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    int cw = PlayerTarget.getCursorWidth(targetSneaking);
                    int ch = PlayerTarget.getCursorHeight(targetSneaking);
                    Gui.drawScaledCustomSizeModalRect(
                        -(iconSize / 2), -(iconSize / 2),
                        0, 0,
                        cw, ch,
                        iconSize, iconSize,
                        cw, ch
                    );
                } catch (Throwable e) {
                    // Texture failed to bind, draw fallback
                    GlStateManager.disableTexture2D();
                    Gui.drawRect(-1, -iconSize/2, 1, iconSize/2, 0xFFFFFF00);
                    GlStateManager.enableTexture2D();
                }
            } else {
                // No cursor texture available, draw simple fallback
                GlStateManager.disableTexture2D();
                GlStateManager.color(1f, 1f, 0f, 1f);
                Gui.drawRect(-1, -iconSize/2, 1, iconSize/2, 0xFFFFFF00); // Vertical line
                Gui.drawRect(-iconSize/4, -iconSize/2, iconSize/4, -iconSize/2 + 2, 0xFFFFFF00); // Top arrow
                GlStateManager.enableTexture2D();
            }
            
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
            
            // Transform mouse coordinates to account for scale (needed for hover detection)
            int scaledMouseX = (int)(previewX + (mouseX - previewX) / scale);
            int scaledMouseY = (int)(previewY + (mouseY - previewY) / scale);
            
            // Check if mouse is hovering over the cursor for tooltip (store for later)
            isHoveringCursor[0] = scaledMouseX >= cursorX - iconSize / 2 && scaledMouseX <= cursorX + iconSize / 2 &&
                                  scaledMouseY >= cursorY && scaledMouseY <= cursorY + iconSize;
            
            // Draw distance text
            drawPreviewHudText(distText, cursorX + iconSize + 4, cursorY + 2, 0xAAAAFF);
            
            // Draw example equipment items (5 slots with actual items)
            int equipmentStartX = cursorX - (18 / 2); // Center first item under cursor
            int equipmentY = cursorY + iconSize + 4;
            int itemSize = 18;
            int spacing = 2;
            
            // Create example items ONLY if showing example for GallantDrop1196 (not tracking anyone)
            net.minecraft.item.ItemStack[] exampleItems = new net.minecraft.item.ItemStack[5];
            if (currentTarget == null && displayName.contains("GallantDrop1196")) {
                try {
                    // Stone sword with Sharpness 1
                    exampleItems[0] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.stone_sword);
                    exampleItems[0].addEnchantment(net.minecraft.enchantment.Enchantment.sharpness, 1);
                    
                    // Iron helmet
                    exampleItems[1] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_helmet);
                    
                    // Iron chestplate
                    exampleItems[2] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_chestplate);
                    
                    // Chainmail leggings
                    exampleItems[3] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.chainmail_leggings);
                    
                    // Iron boots
                    exampleItems[4] = new net.minecraft.item.ItemStack(net.minecraft.init.Items.iron_boots);
                } catch (Exception e) {
                    // Fallback to empty if items can't be created
                }
            }
            
            // First pass: Draw all slot backgrounds without lighting
            GlStateManager.disableLighting();
            for (int i = 0; i < 5; i++) {
                int slotX = equipmentStartX + (i * (itemSize + spacing));
                
                // Draw slot background (match actual rendering - semi-transparent)
                int bgColor = 0xC01A1A1A; // Semi-transparent dark gray (75% opacity)
                Gui.drawRect(slotX, equipmentY, slotX + itemSize, equipmentY + itemSize, bgColor);
                
                // Draw subtle border (match actual rendering)
                int borderColor = 0x80373737; // Semi-transparent dark gray border
                // Top
                Gui.drawRect(slotX, equipmentY, slotX + itemSize, equipmentY + 1, borderColor);
                // Bottom
                Gui.drawRect(slotX, equipmentY + itemSize - 1, slotX + itemSize, equipmentY + itemSize, borderColor);
                // Left
                Gui.drawRect(slotX, equipmentY, slotX + 1, equipmentY + itemSize, borderColor);
                // Right
                Gui.drawRect(slotX + itemSize - 1, equipmentY, slotX + itemSize, equipmentY + itemSize, borderColor);
            }
            
            // Second pass: Render items with lighting
            net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableDepth();
            GlStateManager.color(1f, 1f, 1f, 1f);
            
            for (int i = 0; i < 5; i++) {
                int slotX = equipmentStartX + (i * (itemSize + spacing));
                
                // Render item if available
                if (exampleItems[i] != null) {
                    mc.getRenderItem().zLevel = 200.0F;
                    // Center 16x16 item in 18x18 slot (1 pixel offset)
                    mc.getRenderItem().renderItemIntoGUI(exampleItems[i], slotX + 1, equipmentY + 1);
                    mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, exampleItems[i], slotX + 1, equipmentY + 1, null);
                    mc.getRenderItem().zLevel = 0.0F;
                }
            }
            
            // Cleanup rendering state
            GlStateManager.disableDepth();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableBlend();
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.color(1f, 1f, 1f, 1f);
            
            // Draw example taunt status if enabled
            if (PlayerTarget.isShowTauntInfo()) {
                int tauntY = equipmentY + 18 + 4;
                String tauntText = EnumChatFormatting.WHITE + "Taunt: " + EnumChatFormatting.GREEN + "READY";
                drawPreviewHudText(tauntText, equipmentStartX, tauntY, 0xFFFFFF);
            }
            
            GlStateManager.popMatrix();
            
            // Draw cursor tooltip if hovering (match real implementation format)
            if (isHoveringCursor[0]) {
                // Format coordinates exactly like the real tracker does (single line with decimals)
                String coords = String.format("X: %.1f  Y: %.1f  Z: %.1f", 123.5, 64.0, -456.2);
                
                // Calculate tooltip dimensions
                int tooltipWidth = fontRendererObj.getStringWidth(coords) + 6;
                int tooltipHeight = 18;
                
                // Position tooltip near mouse cursor
                int tooltipX = mouseX + 12;
                int tooltipY = mouseY - 12;
                
                // Keep tooltip on screen
                net.minecraft.client.gui.ScaledResolution sr = new net.minecraft.client.gui.ScaledResolution(mc);
                if (tooltipX + tooltipWidth > sr.getScaledWidth()) {
                    tooltipX = sr.getScaledWidth() - tooltipWidth - 4;
                }
                if (tooltipY + tooltipHeight > sr.getScaledHeight()) {
                    tooltipY = sr.getScaledHeight() - tooltipHeight - 4;
                }
                if (tooltipY < 4) {
                    tooltipY = 4;
                }
                
                // Draw background (match PlayerTarget implementation exactly)
                int backgroundColor = 0xF0100010;
                int borderColor = 0x505000FF;
                
                GlStateManager.disableRescaleNormal();
                net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                
                // Background
                Gui.drawRect(tooltipX - 3, tooltipY - 4, tooltipX + tooltipWidth + 3, tooltipY + tooltipHeight - 4, backgroundColor);
                // Top border
                Gui.drawRect(tooltipX - 3, tooltipY - 4, tooltipX + tooltipWidth + 3, tooltipY - 3, borderColor);
                // Bottom border
                Gui.drawRect(tooltipX - 3, tooltipY + tooltipHeight - 5, tooltipX + tooltipWidth + 3, tooltipY + tooltipHeight - 4, borderColor);
                // Left border (purple line)
                Gui.drawRect(tooltipX - 4, tooltipY - 3, tooltipX - 3, tooltipY + tooltipHeight - 4, borderColor);
                // Right border (purple line)
                Gui.drawRect(tooltipX + tooltipWidth + 3, tooltipY - 3, tooltipX + tooltipWidth + 4, tooltipY + tooltipHeight - 4, borderColor);
                
                // Draw coordinates text
                drawPreviewHudText(coords, tooltipX, tooltipY, 0xFFFFFFFF);
                
                GlStateManager.enableLighting();
                GlStateManager.enableDepth();
                net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
                GlStateManager.enableRescaleNormal();
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            
            // Check for hover on example items and show tooltips (only if not showing cursor tooltip)
            // Use the same scaled mouse coordinates we calculated earlier
            if (exampleItems != null && !isHoveringCursor[0]) {
                for (int i = 0; i < 5; i++) {
                    int slotX = equipmentStartX + (i * (itemSize + spacing));
                    if (exampleItems[i] != null && scaledMouseX >= slotX && scaledMouseX <= slotX + itemSize && 
                        scaledMouseY >= equipmentY && scaledMouseY <= equipmentY + itemSize) {
                        previewTooltipStack = exampleItems[i];
                        previewTooltipMouseX = mouseX;
                        previewTooltipMouseY = mouseY;
                        break; // Only show one tooltip at a time
                    }
                }
            }
            
        } catch (Throwable e) {
            // Silently fail if preview can't be drawn
        }
        
        // Final GL state cleanup to prevent GUI buttons from being shadowed when hovering items
        try {
            Minecraft mc2 = Minecraft.getMinecraft();
            if (mc2 != null) {
                try { mc2.getRenderItem().zLevel = 0.0F; } catch (Throwable ignored) {}
            }
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {}
    }
    
    /**
     * Renders a tooltip for an item in the preview (vanilla lines, including advanced item tooltips).
     */
    private void renderItemTooltip(Minecraft mc, ItemStack item, int x, int y) {
        try {
            @SuppressWarnings("unchecked")
            List<String> tooltip = item.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
            if (tooltip == null || tooltip.isEmpty()) {
                return;
            }

            List<String> lines = new ArrayList<String>(tooltip);
            if (item.getItem() == Items.stone_sword) {
                HudItemTooltipHelper.applyStoneSwordFirstLineColor(lines, item, mc.gameSettings.advancedItemTooltips);
            }

            GlStateManager.pushMatrix();
            try {
                GlStateManager.disableLighting();
                RenderHelper.disableStandardItemLighting();
                GlStateManager.disableDepth();
                GlStateManager.depthMask(false);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.enableTexture2D();
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                GlStateManager.translate(0.0f, 0.0f, 400.0f);
                drawHoveringText(lines, x, y);
            } finally {
                GlStateManager.depthMask(true);
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Draws a scale slider similar to InventoryHUD
     */
    private float scaleNormalized() {
        return (scale - 0.5f) / 3.5f;
    }

    private void drawScaleSlider(int x, int y, String label) {
        SCALE_SLIDER_STYLE.draw(fontRendererObj, x, y, SLIDER_W, SLIDER_H, scaleNormalized(),
                0xFF4CAF50, label);
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
        try {
            super.mouseClicked(mouseX, mouseY, mouseButton);
        } catch (Exception e) {
            // Ignore
        }
        positionFields.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseX >= scaleSliderX && mouseX <= scaleSliderX + SLIDER_W &&
            mouseY >= scaleSliderY && mouseY <= scaleSliderY + SLIDER_H) {
            isDraggingScale = true;
            updateScaleFromMouse(mouseX);
        }
        
        // Check opacity slider
        if (bgOpacityRow != null) {
            bgOpacityRow.setBounds(opacitySliderX, opacitySliderY, SLIDER_W, SLIDER_H);
            if (bgOpacityRow.mouseClicked(mc, mouseX, mouseY, mouseButton)) {
                return;
            }
        }
    }
    
    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        
        if (isDraggingScale) {
            updateScaleFromMouse(mouseX);
        }
        
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseClickMove(mouseX, mouseY);
        }
    }
    
    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
        isDraggingScale = false;
        isDraggingOpacity = false;
        if (bgOpacityRow != null) {
            bgOpacityRow.mouseReleased(mouseX, mouseY);
        }
    }
    
    private void updateScaleFromMouse(int mouseX) {
        float normalizedPos = SCALE_SLIDER_STYLE.normalizedFromMouseX(mouseX, scaleSliderX, SLIDER_W);
        float newScale = 0.5f + normalizedPos * 3.5f;

        if (Math.abs(newScale - scale) > 0.05f) {
            scale = newScale;
            PlayerTarget.setScale(scale);
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
            try {
                super.keyTyped(typedChar, keyCode);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                // Back
                this.mc.displayGuiScreen(parent);
                break;
            case 1: {
                boolean now = !PlayerTarget.isEnabled();
                PlayerTarget.setEnabledFromGUI(now);
                toggleButton.displayString = getToggleButtonText();
                enabledOverlays.onToggled("main", now);
                break;
            }
            case 3: {
                PlayerTarget.setShowVerticalDistance(!PlayerTarget.isShowVerticalDistance());
                verticalDistanceButton.displayString = getVerticalDistanceButtonText();
                break;
            }
            case 5:
                // Reset position
                PlayerTarget.resetToDefaultPosition();
                positionFields.sync(PlayerTarget.getCustomX(), PlayerTarget.getCustomY());
                break;
            case 9:
                this.mc.displayGuiScreen(new GomodHudEditorGUI(this,
                        EnumChatFormatting.GRAY + "Back to Teammate Tracker Settings"));
                break;
            case 6: {
                PlayerTarget.setShowTauntInfo(!PlayerTarget.isShowTauntInfo());
                tauntInfoButton.displayString = getTauntInfoButtonText();
                break;
            }
            case 7: {
                boolean wasOn = PlayerTarget.isGlowTeammateEnabled();
                PlayerTarget.cycleTeammateGlowMode();
                glowTeammateButton.displayString = getGlowTeammateButtonText();
                boolean nowOn = PlayerTarget.isGlowTeammateEnabled();
                if (nowOn && !wasOn) {
                    enabledOverlays.onToggled("glow", true);
                } else if (!nowOn && wasOn) {
                    enabledOverlays.onToggled("glow", false);
                }
                break;
            }
            case 8: {
                boolean now = !PlayerTarget.isTeammateTrailEnabled();
                PlayerTarget.setTeammateTrailEnabled(now);
                teammateTrailButton.displayString = getTeammateTrailButtonText();
                enabledOverlays.onToggled("trail", now);
                break;
            }
            case 10: {
                PlayerTarget.toggleTextShadow();
                textShadowButton.displayString = getTextShadowButtonText();
                break;
            }
            case 11: {
                boolean now = !ConfigManager.isPlayerTargetTeamInviteNotifierEnabled();
                ConfigManager.setPlayerTargetTeamInviteNotifierEnabled(now);
                teamInviteNotifierButton.displayString = getTeamInviteNotifierButtonText();
                enabledOverlays.onToggled("teamInviteNotifier", now);
                break;
            }
        }
    }

    private String getToggleButtonText() {
        return PlayerTarget.isEnabled() ? 
            EnumChatFormatting.GREEN + "Enabled" : 
            EnumChatFormatting.RED + "Disabled";
    }

    private String getVerticalDistanceButtonText() {
        return PlayerTarget.isShowVerticalDistance() ? 
            EnumChatFormatting.GREEN + "Enabled" : 
            EnumChatFormatting.RED + "Disabled";
    }
    
    private String getTauntInfoButtonText() {
        return PlayerTarget.isShowTauntInfo() ? 
            EnumChatFormatting.GREEN + "Enabled" : 
            EnumChatFormatting.RED + "Disabled";
    }

    private String getGlowTeammateButtonText() {
        switch (PlayerTarget.getTeammateGlowMode()) {
            case GREEN:
                return EnumChatFormatting.GREEN + "Green";
            case CHAMELEON:
                return EnumChatFormatting.AQUA + "Chameleon";
            default:
                return EnumChatFormatting.RED + "Disabled";
        }
    }

    private String getTeammateTrailButtonText() {
        return PlayerTarget.isTeammateTrailEnabled() ?
            EnumChatFormatting.GREEN + "Enabled" :
            EnumChatFormatting.RED + "Disabled";
    }

    private String getTextShadowButtonText() {
        return PlayerTarget.isTextShadow() ?
            EnumChatFormatting.GREEN + "Enabled" :
            EnumChatFormatting.RED + "Disabled";
    }

    private String getTeamInviteNotifierButtonText() {
        return ConfigManager.isPlayerTargetTeamInviteNotifierEnabled()
                ? EnumChatFormatting.GREEN + "Enabled"
                : EnumChatFormatting.RED + "Disabled";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
