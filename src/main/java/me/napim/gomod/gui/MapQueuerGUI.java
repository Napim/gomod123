package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.features.MapQueuer;
import me.napim.gomod.Main;

public class MapQueuerGUI extends GuiScreen {
    private GuiScreen parent;
    private GuiButton backButton;
    
    // Map buttons - organized in rows
    private GuiButton[] mapButtons;
    boolean[] mapSelected; // Track which maps are selected (package-private for MapButton access)
    
    // Animation state for green overlays
    private static final java.util.Map<String, Long> overlayAnimationStart = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ANIMATION_DURATION_MS = 800L; // Animation duration in milliseconds
    private static final int MAIN_CONTROLS_Y_OFFSET = 10;
    private static final int SCROLL_VISIBLE_TOP = 20;
    
    // Game filter system
    private static final java.util.Map<String, Long> filteredGames = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FILTER_DURATION_MS = 5L * 60L * 1000L; // 5 minutes
    private GuiButton addFilterButton;
    private GuiButton clearFiltersButton;
    private GuiButton infoButton;
    private final String[] allMaps;
    
    private String selectedMode = "solo";
    private GuiButton soloButton;
    private GuiButton teamsButton;
    private GuiButton startButton;
    private GuiButton selectAllButton;
    private GuiButton clearAllButton;
    /** Same grid as map buttons: toggles "only stop in Blitz Hour lobbies" (saved in mapqueuer.json). */
    private GuiButton blitzHourToggleButton;
    /** Persisted preference; applied when you press Start Queuer. */
    boolean blitzHourSelected;

    private static final String BLITZ_HOUR_OVERLAY_KEY = "blitz_hour_slot";

    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    public MapQueuerGUI(GuiScreen parent) {
        this.parent = parent;
        this.allMaps = MapQueuer.getAllMaps();
    }

    @Override
    public void initGui() {
        int centerX = width / 2;
        
        // Load saved map selections
        mapSelected = MapQueuer.loadSelectedMaps();
        selectedMode = MapQueuer.getSavedMode();
        blitzHourSelected = MapQueuer.isRunning()
                ? MapQueuer.isRequireBlitzHour()
                : MapQueuer.loadBlitzHourOnlyPreference();
        
        // Back button
        backButton = new GuiButton(0, centerX - 75, GuiHeader.backButtonY(height, 20), 150, 20,
                EnumChatFormatting.GRAY + "Back to Menu");
        buttonList.add(backButton);
        
        // Mode selection buttons
        soloButton = new GuiButton(3, centerX - 150, 60 + MAIN_CONTROLS_Y_OFFSET, 100, 20, EnumChatFormatting.BLUE + "Solo Mode");
        teamsButton = new GuiButton(4, centerX + 50, 60 + MAIN_CONTROLS_Y_OFFSET, 100, 20, EnumChatFormatting.RED + "Teams Mode");
        buttonList.add(soloButton);
        buttonList.add(teamsButton);
        
        // Selection control buttons
        selectAllButton = new GuiButton(6, centerX - 150, 85 + MAIN_CONTROLS_Y_OFFSET, 100, 20, "Select All");
        clearAllButton = new GuiButton(7, centerX + 50, 85 + MAIN_CONTROLS_Y_OFFSET, 100, 20, "Clear All");
        buttonList.add(selectAllButton);
        buttonList.add(clearAllButton);
        
        // Game filter section - moved to top right (ensure everything fits on screen)
        int filterX = width - 220; // base anchor to keep buttons within bounds
        
        // Get current game ID for the filter button
        String currentGameId = me.napim.gomod.features.MapQueuer.getCurrentGameId();
        String filterButtonText = "Filter Current Map";
        if (currentGameId != null && !currentGameId.isEmpty()) {
            filterButtonText = "Filter " + currentGameId;
            System.out.println("[MapQueuerGUI] Setting filter button text to: " + filterButtonText);
        } else {
            System.out.println("[MapQueuerGUI] No game ID detected, using default button text");
        }
        
        // Make the filter button wider to accommodate game ID
        addFilterButton = new GuiButton(10, filterX + 25, 10, 150, 20, filterButtonText);
        // Center the Clear button below the Filter button
        clearFiltersButton = new GuiButton(11, filterX + 25 + (150 - 58) / 2, 35, 58, 20, "Clear");
        // Use a real GuiButton so it looks and behaves like a button
        infoButton = new GuiButton(12, filterX, 10, 20, 20, "?");
        buttonList.add(addFilterButton);
        buttonList.add(clearFiltersButton);
        buttonList.add(infoButton);
        
        // Start button - use standard button height (20) for correct skin
        startButton = new GuiButton(5, centerX - 75, height - 100 + MAIN_CONTROLS_Y_OFFSET, 150, 20, "Start Queuing");
        buttonList.add(startButton);
        
        // Create map buttons in a grid layout
        createMapButtons();
        
        // Update button states
        updateModeButtons();
        updateStartButton();
        updateMapButtons();
        updateFilterButtons();
    }
    
    private void createMapButtons() {
        mapButtons = new GuiButton[allMaps.length];
        int centerX = width / 2;
        int startY = 115 + MAIN_CONTROLS_Y_OFFSET;
        int buttonWidth = 100;
        int buttonHeight = 20;
        int cols = 4; // 4 columns
        int spacing = 5;
        
        for (int i = 0; i < allMaps.length; i++) {
            int row = i / cols;
            int col = i % cols;
            
            int x = centerX - (cols * (buttonWidth + spacing)) / 2 + col * (buttonWidth + spacing);
            int y = startY + row * (buttonHeight + spacing) - scroll.getOffset();
            
            String mapName = allMaps[i];
            String displayName = mapName;
            
            // Color code based on mode restrictions
            if (MapQueuer.isTeamsOnlyMap(mapName)) {
                displayName = EnumChatFormatting.RED + mapName;
            } else if (MapQueuer.isSoloOnlyMap(mapName)) {
                displayName = EnumChatFormatting.BLUE + mapName;
            } else {
                displayName = EnumChatFormatting.WHITE + mapName;
            }
            
            mapButtons[i] = new GuiButton(100 + i, x, y, buttonWidth, buttonHeight, displayName);
            buttonList.add(mapButtons[i]);
        }

        int slot = allMaps.length;
        int row = slot / cols;
        int col = slot % cols;
        int bx = centerX - (cols * (buttonWidth + spacing)) / 2 + col * (buttonWidth + spacing);
        int by = startY + row * (buttonHeight + spacing) - scroll.getOffset();
        blitzHourToggleButton = new GuiButton(100 + slot, bx, by, buttonWidth, buttonHeight, formatBlitzHourButtonLabel());
        blitzHourToggleButton.enabled = true;
        buttonList.add(blitzHourToggleButton);
    }

    private static String formatBlitzHourButtonLabel() {
        return EnumChatFormatting.YELLOW + "BLITZ HOUR";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Draw background
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        
        // Draw title
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
            EnumChatFormatting.LIGHT_PURPLE + "" + EnumChatFormatting.BOLD + "Map Queuer",
            width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        
        // Draw instructions
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
            EnumChatFormatting.YELLOW.toString() + "Select a map and mode, then start queuing!",
            width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        
        // Game filter buttons are drawn by super.drawScreen
        // Update filter button states in real-time
        updateFilterButtons();
        
        // Draw selected maps and mode (between instructions and mode buttons)
        int selectedCount = getSelectedMapCount();
        if (selectedCount > 0 || blitzHourSelected) {
            String selectedMapsText;
            if (selectedCount > 0) {
                double percentage = MapQueuer.getMapSelectionPercentage(mapSelected, selectedMode);
                selectedMapsText = "Selected: " + selectedCount + " map(s) (" + selectedMode + ") - " +
                    String.format("%.1f", percentage) + "% chance";
                if (blitzHourSelected) {
                    selectedMapsText += EnumChatFormatting.GOLD + " · Blitz Hour";
                }
            } else {
                selectedMapsText = "Selected: " + EnumChatFormatting.GOLD + "Blitz Hour" + EnumChatFormatting.AQUA
                    + " (" + selectedMode + ")";
            }
            drawCenteredString(fontRendererObj, 
                EnumChatFormatting.AQUA.toString() + selectedMapsText, 
                width / 2, 50, 0xFFFFFF);
        }
        
        // Draw filtered games list - moved to top right
        drawFilteredGamesList();
        
        // Status is now shown in chat as clickable message
        
        scroll.tick();
        int scrollOff = scroll.getOffset();
        updateMapScrollBounds();
        int centerX = width / 2;
        if (soloButton != null) soloButton.yPosition = 60 + MAIN_CONTROLS_Y_OFFSET - scrollOff;
        if (teamsButton != null) teamsButton.yPosition = 60 + MAIN_CONTROLS_Y_OFFSET - scrollOff;
        if (selectAllButton != null) selectAllButton.yPosition = 85 + MAIN_CONTROLS_Y_OFFSET - scrollOff;
        if (clearAllButton != null) clearAllButton.yPosition = 85 + MAIN_CONTROLS_Y_OFFSET - scrollOff;

        // Respect visible area for header controls to avoid off-screen click targets
        int visibleTop = SCROLL_VISIBLE_TOP;
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, 20, 10);
        if (soloButton != null) soloButton.visible = soloButton.yPosition >= visibleTop && soloButton.yPosition + soloButton.height <= visibleBottom;
        if (teamsButton != null) teamsButton.visible = teamsButton.yPosition >= visibleTop && teamsButton.yPosition + teamsButton.height <= visibleBottom;
        if (selectAllButton != null) selectAllButton.visible = selectAllButton.yPosition >= visibleTop && selectAllButton.yPosition + selectAllButton.height <= visibleBottom;
        if (clearAllButton != null) clearAllButton.visible = clearAllButton.yPosition >= visibleTop && clearAllButton.yPosition + clearAllButton.height <= visibleBottom;

        int contentTop = 115 + MAIN_CONTROLS_Y_OFFSET;
        int buttonWidth = 100;
        int buttonHeight = 20;
        int cols = 4;
        int spacing = 5;
        int gridSlots = allMaps.length + 1;
        for (int slot = 0; slot < gridSlots; slot++) {
            int row = slot / cols;
            int col = slot % cols;
            int x = centerX - (cols * (buttonWidth + spacing)) / 2 + col * (buttonWidth + spacing);
            int y = contentTop + row * (buttonHeight + spacing) - scroll.getOffset();
            if (slot < allMaps.length) {
                if (mapButtons[slot] != null) {
                    mapButtons[slot].xPosition = x;
                    mapButtons[slot].yPosition = y;
                    mapButtons[slot].visible = y >= visibleTop && y + buttonHeight <= visibleBottom;
                }
            } else if (blitzHourToggleButton != null) {
                blitzHourToggleButton.xPosition = x;
                blitzHourToggleButton.yPosition = y;
                blitzHourToggleButton.visible = y >= visibleTop && y + buttonHeight <= visibleBottom;
            }
        }

        // Position start button: scroll with content only when scrolling is needed
        if (scroll.hasScroll()) {
            int rows = (int) Math.ceil(gridSlots / (double) cols);
            int gridBottom = contentTop + (rows - 1) * (buttonHeight + spacing) + buttonHeight; // precise bottom
            int footerTop = gridBottom + 15; // spacing below grid
            if (startButton != null) startButton.yPosition = footerTop - scrollOff;
        } else {
            if (startButton != null) startButton.yPosition = height - 100 + MAIN_CONTROLS_Y_OFFSET;
        }
        if (startButton != null) {
            startButton.visible = startButton.yPosition >= visibleTop
                    && startButton.yPosition + startButton.height <= visibleBottom;
        }

        scroll.drawScrollbar(width, mouseY);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 0);
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }

        // Draw green overlays for selected map buttons (after buttons are drawn)
        drawSelectedMapOverlays();
        drawMapHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        updateStartButton();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            // Back button
            mc.displayGuiScreen(parent);
        } else if (button.id == 3) {
            // Solo mode
            selectedMode = "solo";
            // Start animation for mode button
            overlayAnimationStart.put("mode_solo", System.currentTimeMillis());
            // Deselect teams-only maps when switching to solo
            deselectIncompatibleMaps();
            updateModeButtons();
            updateMapButtons(); // This will disable incompatible buttons
            updateStartButton();
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.updateSelection(getSelectedMaps(), selectedMode);
            }
        } else if (button.id == 4) {
            // Teams mode
            selectedMode = "teams";
            // Start animation for mode button
            overlayAnimationStart.put("mode_teams", System.currentTimeMillis());
            // Deselect solo-only maps when switching to teams
            deselectIncompatibleMaps();
            updateModeButtons();
            updateMapButtons(); // This will disable incompatible buttons
            updateStartButton();
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.updateSelection(getSelectedMaps(), selectedMode);
            }
        } else if (button.id == 5) {
            // Start / stop toggle on one button
            if (MapQueuer.isRunning()) {
                MapQueuer.stopMapQueuer();
            } else {
                String[] selectedMaps = getSelectedMaps();
                if (selectedMaps.length > 0 || blitzHourSelected) {
                    MapQueuer.startMapQueuer(selectedMaps, selectedMode, blitzHourSelected);
                }
            }
            updateStartButton();
        } else if (button.id == 6) {
            // Select All button
            selectAllMaps();
            updateMapButtons();
            updateStartButton();
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.updateSelection(getSelectedMaps(), selectedMode);
            }
        } else if (button.id == 7) {
            // Clear All button
            clearAllMaps();
            updateMapButtons();
            updateStartButton();
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.updateSelection(getSelectedMaps(), selectedMode);
            }
        } else if (button.id == 10) {
            // Filter Current Map button
            System.out.println("[MapQueuerGUI] Filter button clicked!");
            String currentGameId = me.napim.gomod.features.MapQueuer.getCurrentGameId();
            System.out.println("[MapQueuerGUI] Current game ID: " + currentGameId);
            if (currentGameId != null && !currentGameId.isEmpty()) {
                addGameFilter(currentGameId);
                updateFilterButtons(); // This will update the button text and state
                System.out.println("[MapQueuerGUI] Filter added successfully");
            } else {
                Main.sendMessage(EnumChatFormatting.RED + "No game ID detected! Make sure you're in a game or lobby.");
                System.out.println("[MapQueuerGUI] No game ID detected");
            }
        } else if (button.id == 11) {
            // Clear Filters button
            clearAllFilters();
            updateFilterButtons();
        } else if (button.id == 12) {
            // Info button
            showFilterInfo();
        } else if (button.id == 100 + allMaps.length) {
            boolean wasSelected = blitzHourSelected;
            blitzHourSelected = !blitzHourSelected;
            if (!wasSelected && blitzHourSelected) {
                overlayAnimationStart.put(BLITZ_HOUR_OVERLAY_KEY, System.currentTimeMillis());
            } else if (wasSelected && !blitzHourSelected) {
                overlayAnimationStart.remove(BLITZ_HOUR_OVERLAY_KEY);
            }
            if (blitzHourToggleButton != null) {
                blitzHourToggleButton.displayString = formatBlitzHourButtonLabel();
            }
            updateStartButton();
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.setRequireBlitzHour(blitzHourSelected);
            }
        } else if (button.id >= 100 && button.id < 100 + allMaps.length) {
            // Map selection toggle
            int mapIndex = button.id - 100;
            String mapName = allMaps[mapIndex];
            
            // Check if button is enabled (compatible with current mode)
            if (!button.enabled) {
                // Button is disabled, show appropriate message
                if (selectedMode.equals("solo") && MapQueuer.isTeamsOnlyMap(mapName)) {
                    Main.sendMessage(EnumChatFormatting.RED + mapName + " is only available in Teams mode!");
                } else if (selectedMode.equals("teams") && MapQueuer.isSoloOnlyMap(mapName)) {
                    Main.sendMessage(EnumChatFormatting.RED + mapName + " is only available in Solo mode!");
                }
                return;
            }
            
            boolean wasSelected = mapSelected[mapIndex];
            mapSelected[mapIndex] = !mapSelected[mapIndex];
            
            // Start animation when map becomes selected
            if (!wasSelected && mapSelected[mapIndex]) {
                String mapKey = mapName + "_" + (mapIndex);
                overlayAnimationStart.put(mapKey, System.currentTimeMillis());
            } else if (wasSelected && !mapSelected[mapIndex]) {
                // Cancel animation when map becomes deselected
                String mapKey = mapName + "_" + (mapIndex);
                overlayAnimationStart.remove(mapKey);
            }
            
            // Check if we need to change mode based on selected maps
            validateModeForSelectedMaps();
            
            updateMapButtons();
            updateStartButton();
            
            // Auto-save selection
            MapQueuer.saveSelectedMaps(mapSelected, selectedMode, blitzHourSelected);
            if (MapQueuer.isRunning()) {
                MapQueuer.updateSelection(getSelectedMaps(), selectedMode);
            }
        }
    }
    
    private void updateModeButtons() {
        if (soloButton != null && teamsButton != null) {
            if (selectedMode.equals("solo")) {
                soloButton.displayString = EnumChatFormatting.BLUE + "Solo Mode";
                teamsButton.displayString = EnumChatFormatting.RED + "Teams Mode";
            } else {
                soloButton.displayString = EnumChatFormatting.BLUE + "Solo Mode";
                teamsButton.displayString = EnumChatFormatting.RED + "Teams Mode";
            }
        }
    }
    
    private void updateStartButton() {
        if (startButton == null) {
            return;
        }
        if (MapQueuer.isRunning()) {
            int count = getRunningQueueSelectionCount();
            startButton.displayString = EnumChatFormatting.RED + "Stop Queuing (" + count + ")";
            startButton.enabled = true;
        } else {
            int count = getQueueSelectionCount();
            if (count == 0) {
                startButton.displayString = EnumChatFormatting.GRAY + "Start Queuing";
                startButton.enabled = false;
            } else {
                startButton.displayString = EnumChatFormatting.GOLD + "Start Queuing (" + count + ")";
                startButton.enabled = true;
            }
        }
    }

    /** Maps selected plus Blitz Hour (counts as 1 when enabled). */
    private int getQueueSelectionCount() {
        int count = getSelectedMapCount();
        if (blitzHourSelected) {
            count++;
        }
        return count;
    }

    private int getRunningQueueSelectionCount() {
        int count = MapQueuer.getTargetMaps().length;
        if (MapQueuer.isRequireBlitzHour()) {
            count++;
        }
        return count;
    }
    
    private int getSelectedMapCount() {
        int count = 0;
        for (boolean selected : mapSelected) {
            if (selected) count++;
        }
        return count;
    }
    
    private String[] getSelectedMaps() {
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (int i = 0; i < allMaps.length; i++) {
            if (mapSelected[i]) {
                selected.add(allMaps[i]);
            }
        }
        return selected.toArray(new String[0]);
    }
    
    private void selectAllMaps() {
        for (int i = 0; i < mapSelected.length; i++) {
            String mapName = allMaps[i];
            // Only select maps compatible with current mode
            if (selectedMode.equals("solo")) {
                mapSelected[i] = !MapQueuer.isTeamsOnlyMap(mapName);
            } else if (selectedMode.equals("teams")) {
                mapSelected[i] = !MapQueuer.isSoloOnlyMap(mapName);
            } else {
                mapSelected[i] = true;
            }
        }
        blitzHourSelected = true;
        overlayAnimationStart.put(BLITZ_HOUR_OVERLAY_KEY, System.currentTimeMillis());
        // Update button states after selecting
        updateMapButtons();
    }
    
    private void clearAllMaps() {
        for (int i = 0; i < mapSelected.length; i++) {
            mapSelected[i] = false;
        }
        blitzHourSelected = false;
        overlayAnimationStart.remove(BLITZ_HOUR_OVERLAY_KEY);
        // Update button states after clearing
        updateMapButtons();
    }
    
    private void deselectIncompatibleMaps() {
        for (int i = 0; i < allMaps.length; i++) {
            String mapName = allMaps[i];
            if (selectedMode.equals("solo") && MapQueuer.isTeamsOnlyMap(mapName)) {
                mapSelected[i] = false;
            } else if (selectedMode.equals("teams") && MapQueuer.isSoloOnlyMap(mapName)) {
                mapSelected[i] = false;
            }
        }
    }
    
    private void validateModeForSelectedMaps() {
        boolean hasTeamsOnly = false;
        boolean hasSoloOnly = false;
        
        for (int i = 0; i < allMaps.length; i++) {
            if (mapSelected[i]) {
                if (MapQueuer.isTeamsOnlyMap(allMaps[i])) {
                    hasTeamsOnly = true;
                }
                if (MapQueuer.isSoloOnlyMap(allMaps[i])) {
                    hasSoloOnly = true;
                }
            }
        }
        
        // Auto-switch mode if needed
        if (hasTeamsOnly && selectedMode.equals("solo")) {
            selectedMode = "teams";
            updateModeButtons();
        } else if (hasSoloOnly && selectedMode.equals("teams")) {
            selectedMode = "solo";
            updateModeButtons();
        }
    }
    
    private void updateMapButtons() {
        for (int i = 0; i < mapButtons.length; i++) {
            if (mapButtons[i] != null) {
                String mapName = allMaps[i];
                String displayName = mapName;
                
                // Check if map is compatible with current mode
                boolean isCompatible = true;
                if (selectedMode.equals("solo") && MapQueuer.isTeamsOnlyMap(mapName)) {
                    isCompatible = false;
                } else if (selectedMode.equals("teams") && MapQueuer.isSoloOnlyMap(mapName)) {
                    isCompatible = false;
                }
                
                // Color code based on mode restrictions and compatibility
                if (MapQueuer.isTeamsOnlyMap(mapName)) {
                    displayName = EnumChatFormatting.RED + displayName;
                } else if (MapQueuer.isSoloOnlyMap(mapName)) {
                    displayName = EnumChatFormatting.BLUE + displayName;
                } else {
                    displayName = EnumChatFormatting.WHITE + displayName;
                }
                
                // Add strikethrough and gray out incompatible maps
                if (!isCompatible) {
                    displayName = EnumChatFormatting.STRIKETHROUGH.toString() + EnumChatFormatting.GRAY.toString() + displayName;
                }
                
                mapButtons[i].displayString = displayName;
                mapButtons[i].enabled = isCompatible; // Disable incompatible maps
            }
        }
        if (blitzHourToggleButton != null) {
            blitzHourToggleButton.displayString = formatBlitzHourButtonLabel();
        }
    }

    private void drawSelectedMapOverlays() {
        int centerX = width / 2;
        int contentTop = 115 + MAIN_CONTROLS_Y_OFFSET;
        int buttonWidth = 100;
        int buttonHeight = 20;
        int cols = 4;
        int spacing = 5;
        
        // Draw overlays for selected map buttons and animations
        for (int i = 0; i < allMaps.length; i++) {
            if (mapButtons[i] != null) {
                int row = i / cols;
                int col = i % cols;
                int x = centerX - (cols * (buttonWidth + spacing)) / 2 + col * (buttonWidth + spacing);
                int y = contentTop + row * (buttonHeight + spacing) - scroll.getOffset();
                
                // Only draw overlay if button is visible
                int visibleTop = SCROLL_VISIBLE_TOP;
                int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, 20, 10);
                if (y >= visibleTop && y + buttonHeight <= visibleBottom) {
                    String mapKey = allMaps[i] + "_" + i;
                    
                    // Check if this map has an active animation (regardless of selection state)
                    if (overlayAnimationStart.containsKey(mapKey)) {
                        drawAnimatedGreenOverlay(x, y, buttonWidth, buttonHeight, mapKey);
                    } else if (mapSelected[i]) {
                        // Only draw static overlay if map is selected and no animation
                        drawGreenOverlay(x, y, buttonWidth, buttonHeight);
                    }
                }
            }
        }

        int bhSlot = allMaps.length;
        if (blitzHourToggleButton != null) {
            int row = bhSlot / cols;
            int col = bhSlot % cols;
            int x = centerX - (cols * (buttonWidth + spacing)) / 2 + col * (buttonWidth + spacing);
            int y = contentTop + row * (buttonHeight + spacing) - scroll.getOffset();
            int visibleTop = SCROLL_VISIBLE_TOP;
            int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, 20, 10);
            if (y >= visibleTop && y + buttonHeight <= visibleBottom) {
                if (overlayAnimationStart.containsKey(BLITZ_HOUR_OVERLAY_KEY)) {
                    drawAnimatedGreenOverlay(x, y, buttonWidth, buttonHeight, BLITZ_HOUR_OVERLAY_KEY);
                } else if (blitzHourSelected) {
                    drawGreenOverlay(x, y, buttonWidth, buttonHeight);
                }
            }
        }
        
        // Draw overlays for mode buttons (animations and selected state)
        if (soloButton != null) {
            if (overlayAnimationStart.containsKey("mode_solo")) {
                drawAnimatedGreenOverlay(soloButton.xPosition, soloButton.yPosition, soloButton.width, soloButton.height, "mode_solo");
            } else if (selectedMode.equals("solo")) {
                drawGreenOverlay(soloButton.xPosition, soloButton.yPosition, soloButton.width, soloButton.height);
            }
        }
        if (teamsButton != null) {
            if (overlayAnimationStart.containsKey("mode_teams")) {
                drawAnimatedGreenOverlay(teamsButton.xPosition, teamsButton.yPosition, teamsButton.width, teamsButton.height, "mode_teams");
            } else if (selectedMode.equals("teams")) {
                drawGreenOverlay(teamsButton.xPosition, teamsButton.yPosition, teamsButton.width, teamsButton.height);
            }
        }
    }
    
    private void drawMapHoverTooltip(int mouseX, int mouseY) {
        for (int i = 0; i < allMaps.length; i++) {
            if (mapButtons[i] == null || !mapButtons[i].visible) {
                continue;
            }
            GuiButton btn = mapButtons[i];
            if (mouseX < btn.xPosition || mouseX >= btn.xPosition + btn.width
                    || mouseY < btn.yPosition || mouseY >= btn.yPosition + btn.height) {
                continue;
            }
            String mapName = allMaps[i];
            int maxPlayers = MapQueuer.getMapMaxPlayers(mapName);
            int startPlayers = MapQueuer.getMapStartPlayers(mapName);
            java.util.List<String> lines = new java.util.ArrayList<String>();
            if (MapQueuer.isTeamsOnlyMap(mapName)) {
                lines.add(EnumChatFormatting.RED + mapName);
            } else if (MapQueuer.isSoloOnlyMap(mapName)) {
                lines.add(EnumChatFormatting.BLUE + mapName);
            } else {
                lines.add(EnumChatFormatting.WHITE + mapName);
            }
            lines.add(EnumChatFormatting.GRAY + "Starts at "
                + EnumChatFormatting.GREEN + startPlayers + "/" + maxPlayers
                + EnumChatFormatting.GRAY + " players");
            net.minecraft.client.renderer.GlStateManager.pushMatrix();
            net.minecraft.client.renderer.GlStateManager.translate(0, 0, 400);
            drawHoveringText(lines, mouseX, mouseY);
            net.minecraft.client.renderer.GlStateManager.popMatrix();
            return;
        }
    }

    private void drawGreenOverlay(int x, int y, int width, int height) {
        // Ensure overlay renders above buttons
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(0, 0, 300);
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        
        // Draw green overlay (very transparent to not interfere with text)
        net.minecraft.client.gui.Gui.drawRect(x, y, x + width, y + height, 0x204CAF50);
        
        // Draw green border (thicker for better visibility)
        net.minecraft.client.gui.Gui.drawRect(x, y, x + width, y + 2, 0xFF4CAF50); // Top
        net.minecraft.client.gui.Gui.drawRect(x, y, x + 2, y + height, 0xFF4CAF50); // Left
        net.minecraft.client.gui.Gui.drawRect(x, y + height - 2, x + width, y + height, 0xFF4CAF50); // Bottom
        net.minecraft.client.gui.Gui.drawRect(x + width - 2, y, x + width, y + height, 0xFF4CAF50); // Right
        
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }
    
    private void drawAnimatedGreenOverlay(int x, int y, int width, int height, String mapKey) {
        // Ensure overlay renders above buttons
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(0, 0, 300);
        net.minecraft.client.renderer.GlStateManager.enableBlend();

        // Get animation progress
        Long startTime = overlayAnimationStart.get(mapKey);
        if (startTime == null) {
            // No animation data, draw full border
            drawGreenOverlay(x, y, width, height);
            net.minecraft.client.renderer.GlStateManager.popMatrix();
            return;
        }
        
        // Draw green overlay (very transparent to not interfere with text)
        net.minecraft.client.gui.Gui.drawRect(x, y, x + width, y + height, 0x204CAF50);
        
        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - startTime;
        float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION_MS);
        
        
        // Calculate border completion based on progress
        // Animation: Start from middle of left and right edges, expand up and down simultaneously
        int borderThickness = 2;
        
        // Calculate smooth progress for each direction
        // Left and right sides grow from center outward
        float leftRightProgress = Math.min(1.0f, progress * 2.0f); // First half of animation
        float topBottomProgress = Math.max(0.0f, (progress - 0.5f) * 2.0f); // Second half of animation
        
        // Left side: start from middle, expand up and down smoothly
        int leftCenterY = y + height / 2;
        int leftMaxPixels = height / 2;
        int leftUpPixels = (int) (leftMaxPixels * leftRightProgress);
        int leftDownPixels = (int) (leftMaxPixels * leftRightProgress);
        
        if (leftUpPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x, leftCenterY - leftUpPixels, x + borderThickness, leftCenterY, 0xFF4CAF50);
        }
        if (leftDownPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x, leftCenterY, x + borderThickness, leftCenterY + leftDownPixels, 0xFF4CAF50);
        }
        
        // Right side: start from middle, expand up and down smoothly
        int rightCenterY = y + height / 2;
        int rightMaxPixels = height / 2;
        int rightUpPixels = (int) (rightMaxPixels * leftRightProgress);
        int rightDownPixels = (int) (rightMaxPixels * leftRightProgress);
        
        if (rightUpPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x + width - borderThickness, rightCenterY - rightUpPixels, x + width, rightCenterY, 0xFF4CAF50);
        }
        if (rightDownPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x + width - borderThickness, rightCenterY, x + width, rightCenterY + rightDownPixels, 0xFF4CAF50);
        }
        
        // Top border: draw from left and right edges toward center smoothly
        int topMaxPixels = width / 2;
        int topLeftPixels = (int) (topMaxPixels * topBottomProgress);
        int topRightPixels = (int) (topMaxPixels * topBottomProgress);
        
        if (topLeftPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x, y, x + topLeftPixels, y + borderThickness, 0xFF4CAF50);
        }
        if (topRightPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x + width - topRightPixels, y, x + width, y + borderThickness, 0xFF4CAF50);
        }
        
        // Bottom border: draw from left and right edges toward center smoothly
        int bottomMaxPixels = width / 2;
        int bottomLeftPixels = (int) (bottomMaxPixels * topBottomProgress);
        int bottomRightPixels = (int) (bottomMaxPixels * topBottomProgress);
        
        if (bottomLeftPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x, y + height - borderThickness, x + bottomLeftPixels, y + height, 0xFF4CAF50);
        }
        if (bottomRightPixels > 0) {
            net.minecraft.client.gui.Gui.drawRect(x + width - bottomRightPixels, y + height - borderThickness, x + width, y + height, 0xFF4CAF50);
        }
        
        // Clean up completed animations
        if (progress >= 1.0f) {
            overlayAnimationStart.remove(mapKey);
        }

        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    private void updateMapScrollBounds() {
        int buttonHeight = 20;
        int spacing = 5;
        int cols = 4;
        int rows = (int) Math.ceil((allMaps.length + 1) / (double) cols);
        int contentTop = 60 + MAIN_CONTROLS_Y_OFFSET;
        int gridBottom = 115 + MAIN_CONTROLS_Y_OFFSET + (rows - 1) * (buttonHeight + spacing) + buttonHeight;
        int footerBottom = gridBottom + 15 + 20;
        int contentBottom = Math.max(gridBottom, footerBottom);
        int visibleTop = SCROLL_VISIBLE_TOP;
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10);
        scroll.updateBounds(visibleTop, contentBottom, visibleTop, visibleBottom);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
    
    // Game filter methods
    public static void addGameFilter(String gameId) {
        long currentTime = System.currentTimeMillis();
        filteredGames.put(gameId.toLowerCase(), currentTime + FILTER_DURATION_MS);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Added game filter: " + gameId + " (5 minutes)");
    }
    
    private void showFilterInfo() {
        // Show help dialog instead of chat messages
        mc.displayGuiScreen(new FilterInfoDialog(this));
    }
    
    private void clearAllFilters() {
        filteredGames.clear();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Cleared all game filters");
    }
    
    private void updateFilterButtons() {
        // Update button states based on current filters
        if (addFilterButton != null) {
            // Check if current game ID is available and not already filtered
            String currentGameId = me.napim.gomod.features.MapQueuer.getCurrentGameId();
            boolean hasGameId = currentGameId != null && !currentGameId.isEmpty();
            boolean isAlreadyFiltered = hasGameId && isGameFiltered(currentGameId);
            
            // Update button text based on current state
            if (isAlreadyFiltered) {
                addFilterButton.displayString = EnumChatFormatting.GREEN + "Filtered " + currentGameId;
                addFilterButton.enabled = false;
            } else if (hasGameId) {
                addFilterButton.displayString = "Filter " + currentGameId;
                addFilterButton.enabled = true;
            } else {
                addFilterButton.displayString = "Filter Current Map";
                addFilterButton.enabled = false;
            }
            
            System.out.println("[MapQueuerGUI] updateFilterButtons - Game ID: " + currentGameId + ", hasGameId: " + hasGameId + ", isAlreadyFiltered: " + isAlreadyFiltered + ", button enabled: " + addFilterButton.enabled);
        }
        if (clearFiltersButton != null) {
            clearFiltersButton.enabled = !filteredGames.isEmpty();
        }
    }
    
    private void drawFilteredGamesList() {
        if (filteredGames.isEmpty()) return;
        
        int startY = 60; // Moved to top right area
        int lineHeight = 12;
        int maxLines = 3; // Show up to 3 filtered games
        int lineCount = 0;
        int filterX = width - 200; // Right side of screen
        
        long currentTime = System.currentTimeMillis();
        java.util.Iterator<java.util.Map.Entry<String, Long>> iterator = filteredGames.entrySet().iterator();
        
        while (iterator.hasNext() && lineCount < maxLines) {
            java.util.Map.Entry<String, Long> entry = iterator.next();
            String gameId = entry.getKey();
            Long expireTime = entry.getValue();
            
            if (expireTime <= currentTime) {
                // Filter expired, remove it
                iterator.remove();
                continue;
            }
            
            long remainingMs = expireTime - currentTime;
            long remainingMinutes = remainingMs / (60 * 1000);
            long remainingSeconds = (remainingMs % (60 * 1000)) / 1000;
            
            String timeText = remainingMinutes > 0 ? 
                remainingMinutes + "m " + remainingSeconds + "s" : 
                remainingSeconds + "s";
            
            String displayText = EnumChatFormatting.RED + "Filtered: " + gameId + " (" + timeText + ")";
            drawString(fontRendererObj, displayText, filterX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
        }
    }
    
    // Static method to check if a game is filtered (called from MapQueuer)
    public static boolean isGameFiltered(String gameId) {
        if (gameId == null || gameId.isEmpty()) {
            System.out.println("[MapQueuer] isGameFiltered - Game ID is null or empty");
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        String lowerGameId = gameId.toLowerCase();
        String collapsedInput = collapseConsecutiveDuplicates(lowerGameId);
        
        System.out.println("[MapQueuer] isGameFiltered - Checking game ID: " + gameId + " (lowercase: " + lowerGameId + ", collapsed: " + collapsedInput + ")");
        System.out.println("[MapQueuer] isGameFiltered - Filtered games: " + filteredGames.keySet());
        
        // Check all filtered games for a match (direct or collapsed)
        // This handles scoreboard color code artifacts where §8 before digit 8
        // can cause game IDs like m882DQ vs m82DQ for the same game
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = filteredGames.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Long> entry = it.next();
            String filteredId = entry.getKey();
            Long expireTime = entry.getValue();
            
            if (expireTime <= currentTime) {
                it.remove();
                System.out.println("[MapQueuer] isGameFiltered - Filter expired for " + filteredId + ", removed");
                continue;
            }
            
            String collapsedFilter = collapseConsecutiveDuplicates(filteredId);
            
            // Match if: exact match, or collapsed versions match
            if (lowerGameId.equals(filteredId) || collapsedInput.equals(collapsedFilter)) {
                System.out.println("[MapQueuer] isGameFiltered - Game is filtered! (matched " + filteredId + ")");
                return true;
            }
        }
        
        System.out.println("[MapQueuer] isGameFiltered - No matching filter found");
        return false;
    }
    
    /**
     * Collapses consecutive duplicate characters in a string.
     * e.g. "m882dq" -> "m82dq", "m1122ab" -> "m12ab"
     * This handles scoreboard color code artifacts where §8 before the digit 8
     * causes the game ID to have doubled digits.
     */
    private static String collapseConsecutiveDuplicates(String s) {
        if (s == null || s.length() <= 1) return s;
        StringBuilder sb = new StringBuilder();
        sb.append(s.charAt(0));
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(i - 1)) {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }
    
    /**
     * Returns true if there are any active (non-expired) filtered games.
     * Used by instantMapCheck to decide whether it needs to wait for the game ID.
     */
    public static boolean hasActiveFilters() {
        if (filteredGames.isEmpty()) return false;
        long now = System.currentTimeMillis();
        // Clean expired and check if any remain
        filteredGames.entrySet().removeIf(e -> e.getValue() <= now);
        return !filteredGames.isEmpty();
    }
    
    // Dialog for showing filter help information
    private static class FilterInfoDialog extends GuiScreen {
        private final GuiScreen parent;
        private GuiButton closeButton;
        
        public FilterInfoDialog(GuiScreen parent) {
            this.parent = parent;
        }
        
        @Override
        public void initGui() {
            int centerX = width / 2;
            closeButton = new GuiButton(0, centerX - 50, height / 2 + 54, 100, 20, EnumChatFormatting.GRAY + "Close");
            buttonList.clear();
            buttonList.add(closeButton);
        }
        
        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            
            int centerX = width / 2;
            int centerY = height / 2;
            int dialogWidth = 300;
            int dialogHeight = 80;
            int dialogX = centerX - dialogWidth / 2;
            int dialogY = centerY - dialogHeight / 2;
            
            // Draw dialog background with opacity
            drawRect(dialogX, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xCC000000);
            
            // Draw border
            drawRect(dialogX, dialogY, dialogX + dialogWidth, dialogY + 1, 0xFF4CAF50); // Top
            drawRect(dialogX, dialogY, dialogX + 1, dialogY + dialogHeight, 0xFF4CAF50); // Left
            drawRect(dialogX + dialogWidth - 1, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF4CAF50); // Right
            drawRect(dialogX, dialogY + dialogHeight - 1, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF4CAF50); // Bottom
            
            // Draw title
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.GOLD + "Game Filter Help", centerX, dialogY + 10, 0xFFFFFF);
            
            // Draw help text
            String line1 = EnumChatFormatting.YELLOW + "Click the Filter button (e.g., Filter m188CF) to filter it";
            String line2 = EnumChatFormatting.YELLOW + "Filtered games will be skipped for 5 minutes";
            
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, line1, centerX, dialogY + 25, 0xFFFFFF);
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, line2, centerX, dialogY + 40, 0xFFFFFF);
            
            super.drawScreen(mouseX, mouseY, partialTicks);
        }
        
        @Override
        public void actionPerformed(GuiButton button) {
            if (button == closeButton) {
                mc.displayGuiScreen(parent);
            }
        }
        
        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
