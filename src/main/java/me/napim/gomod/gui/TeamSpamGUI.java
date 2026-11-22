package me.napim.gomod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import me.napim.gomod.features.TeamSpamManager;
import me.napim.gomod.features.TeamSpamManager.Player;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.command.TeamSpamCommand;
import me.napim.gomod.features.UnknownNickFinder;
import me.napim.gomod.Main;
import me.napim.gomod.features.TeamNickFinderDateFormat;
import me.napim.gomod.features.TeamInviteResponseHandler;
import me.napim.gomod.util.HudGlState;

import java.util.List;
import java.util.Collections;

public class TeamSpamGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private GuiScreen parent;
    private GuiButton backButton;
    private GuiButton addPlayerButton;
    private GuiButton removePlayerButton;
    private GuiButton refreshButton;
    private GuiButton clearButton;
    private GuiButton toggleSpamButton;
    private GuiButton unknownNickFinderButton;
    private GuiButton dateDisplaySettingsButton;
    
    // Static reference for external refresh
    private static TeamSpamGUI currentInstance = null;
    
    private GuiTextField playerNameField;
    private GuiTextField removePlayerField;
    
    private List<Player> players;
    /** Scroll offset in rows. */
    private int scrollOffset = 0;
    private static final int PLAYERS_PER_PAGE = 18;
    // Scrollbar properties (matching InventoryHUD)
    private int maxScroll = 0;
    private static final int SCROLL_STEP = 3;

    // Draggable scrollbar thumb
    private boolean draggingScrollBar = false;
    private int scrollGrabbedAtY = 0;
    private int SCROLL_BAR_LEFT = 0, SCROLL_BAR_TOP = 0, SCROLL_BAR_RIGHT = 0, SCROLL_BAR_BOTTOM = 0;
    
    // Refresh animation state (static so it persists across GUI reopen)
    public static volatile boolean isRefreshing = false;
    public static volatile long lastRefreshCompletedMs = 0L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> refreshStartedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> refreshCompletedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFRESH_COMPLETE_FADE_MS = 900L;
    /** Parallel Mojang+Hypixel lookups per refresh (doubled vs original for ~2× throughput on I/O). */
    private static final int REFRESH_WORKER_COUNT = 12;
    // Start spam visual state
    private volatile boolean isSpamming = false;
    private volatile String currentInvited = "";
    
    // Store last refresh changes for clickable [Check] button
    private static volatile List<RefreshChange> lastRefreshChanges = null;
    /** If true, starting this GUI triggers refresh automatically once initialized. */
    private static volatile boolean autoRefreshOnOpen = false;
    
    public TeamSpamGUI(GuiScreen parent) {
        this.parent = parent;
        currentInstance = this;
    }

    /** Open Teams Nick Finder and immediately trigger the same refresh used by the GUI button. */
    public static void openAndStartRefresh() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen parent = mc.currentScreen;
        autoRefreshOnOpen = true;
        mc.displayGuiScreen(new TeamSpamGUI(parent));
    }
    
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (currentInstance == this) {
            currentInstance = null;
        }
    }
    
    /**
     * Static method to refresh the current GUI instance
     */
    public static void refreshCurrentGUI() {
        if (currentInstance != null) {
            currentInstance.refreshPlayerList();
            System.out.println("[TeamSpamGUI] Refreshed current GUI instance");
        }
    }
    
    /**
     * Get the last refresh changes for displaying in the results dialog
     */
    public static List<RefreshChange> getLastRefreshChanges() {
        return lastRefreshChanges;
    }
    
    @Override
    public void initGui() {
        int centerX = width / 2;
        int startY = GuiHeader.STANDARD_SUBTITLE_Y;
        
        // Load players and sort them using the same logic as TeamSpamCommand
        players = TeamSpamManager.getPlayers();
        // Sort players so MVP++ always appear above non-MVP++ after refresh.
        // Within the same MVP++ group, sort by: most recently found first, then most recently added, then alphabetically.
        Collections.sort(players, (p1, p2) -> {
            // Primary: MVP++ first (ensure those who no longer have MVP++ go below)
            if (p1.hasMVPPlusPlus() && !p2.hasMVPPlusPlus()) return -1;
            if (!p1.hasMVPPlusPlus() && p2.hasMVPPlusPlus()) return 1;

            // Secondary: most recently found first (only players who were actually found online)
            long found1 = p1.getLastFoundTimestamp();
            long found2 = p2.getLastFoundTimestamp();
            boolean p1HasFound = found1 > 0;
            boolean p2HasFound = found2 > 0;

            // Players with a lastFoundTimestamp appear before those without
            if (p1HasFound && !p2HasFound) return -1;
            if (!p1HasFound && p2HasFound) return 1;

            // If both have found timestamps, newest first
            if (p1HasFound && p2HasFound && found1 != found2) {
                return Long.compare(found2, found1);
            }

            // Tertiary: most recently added first among players with same found status
            long added1 = p1.getAddedTimestamp();
            long added2 = p2.getAddedTimestamp();
            int addedCompare = Long.compare(added2, added1);
            if (addedCompare != 0) return addedCompare;

            // Quaternary: alphabetical by username
            return p1.getUsername().compareToIgnoreCase(p2.getUsername());
        });
        
        // Back button
        backButton = new GuiButton(0, centerX - 75, GuiHeader.backButtonY(height, 20), 150, 20, EnumChatFormatting.GRAY + "Back to Menu");
        
        // Single player edit field with Add/Remove buttons (centered box)
        playerNameField = new GuiTextField(1, fontRendererObj, centerX - 80, startY, 160, 20);
        playerNameField.setMaxStringLength(16);
        // Switch positions: Add on right, Remove on left
        removePlayerButton = new GuiButton(4, centerX - 160 - 10, startY, 80, 20, "Remove");
        removePlayerField = new GuiTextField(3, fontRendererObj, 0, 0, 0, 0); // unused now
        addPlayerButton = new GuiButton(2, centerX + 90, startY, 80, 20, "Add");
        
        // Initially disable both buttons until text is entered
        addPlayerButton.enabled = false;
        removePlayerButton.enabled = false;
        
        // Toggle Spam button (single centered)
        String toggleLabel = me.napim.gomod.command.TeamSpamCommand.isRunning() ? (EnumChatFormatting.RED + "Stop Spam") : (EnumChatFormatting.GREEN + "Start Spam");
        toggleSpamButton = new GuiButton(8, centerX - 60, startY + 60, 120, 20, toggleLabel);
        
        // Control buttons - same level as Start/Stop
        refreshButton = new GuiButton(5, centerX - 100, startY + 90, 80, 20, "Refresh");
        clearButton = new GuiButton(6, centerX + 20, startY + 90, 80, 20, "Clear All");
        
        // Unknown Nick Finder button - below other buttons
        String nickFinderLabel = UnknownNickFinder.isRunning() ? 
            (EnumChatFormatting.RED + "Stop Finder") : 
            (EnumChatFormatting.AQUA + "Find Unknown");
        unknownNickFinderButton = new GuiButton(9, centerX - 125, startY + 118, 120, 20, nickFinderLabel);
        dateDisplaySettingsButton = new TeamNickFinderDateSettingsLauncherButton(11, centerX + 5, startY + 118, 120, 20);
        
        // Initialize scroll
        scrollOffset = 0;
        computeMaxScroll();
        
        // Add all buttons
        buttonList.add(backButton);
        buttonList.add(addPlayerButton);
        buttonList.add(removePlayerButton);
        buttonList.add(refreshButton);
        buttonList.add(clearButton);
        buttonList.add(toggleSpamButton);
        buttonList.add(unknownNickFinderButton);
        buttonList.add(dateDisplaySettingsButton);
        if (autoRefreshOnOpen) {
            autoRefreshOnOpen = false;
            startAsyncRefresh();
        }
    }
    
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        HudGlState.restoreForOverlayText();
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        updateRefreshButtonVisualState();
        
        int centerX = width / 2;
        int startY = GuiHeader.STANDARD_SUBTITLE_Y;
        
        // Title
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Teams Nick Finder", centerX, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        
        // Add player section
        drawCenteredString(fontRendererObj, "Add Player:", centerX, startY + 5, 0xFFFFFF);
        playerNameField.drawTextBox();
        
        // Removed old secondary remove label/box
        
        // Player list
        drawCenteredString(fontRendererObj, "Players in List (" + players.size() + "):", centerX, startY + 144, 0xFFFFFF);
        
        // Display players with scroll offset
        int baseY = startY + 164;
        int endIndex = Math.min(scrollOffset + PLAYERS_PER_PAGE, players.size());
        int leftX = centerX - 220;
        int rightX = centerX + 220;
        
        // Prevent overlapping with back button: limit visible bottom to above the back button
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10); // stop before back button
        
        // Track tooltip data to render AFTER all players (prevents color corruption)
        java.util.List<String> pendingTooltip = null;
        int tooltipX = 0;
        int tooltipY = 0;
        
        for (int i = scrollOffset; i < endIndex; i++) {
            Player player = players.get(i);
            int fontH = fontRendererObj.FONT_HEIGHT;
            int yPos = baseY + (i - scrollOffset) * 15;
            boolean hovered = (mouseX >= leftX && mouseX <= rightX && mouseY >= yPos && mouseY <= yPos + fontH);
            String numberPrefix = (i + 1) + ". ";
            String displayPrefix = hovered ? (EnumChatFormatting.GREEN + ">> " + EnumChatFormatting.RESET) : numberPrefix;
            String playerText = buildDisplayName(player);
            
            // yPos already computed above
            
            // Stop rendering if we would overlap the back button area
            if (yPos + 8 > visibleBottom) {
                break;
            }
            
            // Default: last found/invited time; hover: when the entry was added to the list
            if (hovered) {
                playerText += EnumChatFormatting.RESET.toString() + EnumChatFormatting.GRAY + " (Added: "
                        + TeamNickFinderDateFormat.formatListTimestamp(player.getAddedTimestamp()) + ")";
            } else {
                long lastFound = player.getLastFoundTimestamp() > 0 ? player.getLastFoundTimestamp()
                        : player.getAddedTimestamp();
                playerText += EnumChatFormatting.RESET.toString() + EnumChatFormatting.GRAY + " (Last found: "
                        + TeamNickFinderDateFormat.formatListTimestamp(lastFound) + ")";
            }
            
            // Check if this player has the green "found" overlay
            TeamInviteResponseHandler.FoundPlayer found = TeamInviteResponseHandler.getFoundPlayer(player.getUsername());
            boolean hasGreenOverlay = found != null;
            long now = System.currentTimeMillis();
            
            // Hover highlight with consistent horizontal width and extra top padding
            // Skip if player has green overlay to avoid ugly black rectangle peeking through
            if (hovered && !hasGreenOverlay) {
                int startX = leftX - 6; // extend horizontally so all rows align
                int endX = rightX + 6;
                int topY = yPos - 4; // a little more above the text
                int bottomY = yPos + fontH + 2; // slight padding below
                net.minecraft.client.gui.Gui.drawRect(startX, topY, endX, bottomY, 0x40000000);
            }
            drawRefreshOverlay(player, leftX, rightX, yPos, fontH, now);
            // Start spam overlay: highlight current invited and any recently found players
            // Pull latest values from command (thread-safe enough for GUI)
            currentInvited = TeamSpamCommand.lastInvitedPlayer;
            // Current invited row: blue overlay (persist across reopen by checking lastInvitedPlayer)
            boolean effectiveSpamming = isSpamming || (currentInvited != null && !currentInvited.isEmpty());
            if (effectiveSpamming && currentInvited != null && !currentInvited.isEmpty() && player.getUsername().equalsIgnoreCase(currentInvited)) {
                int startX2 = leftX - 6;
                int endX2 = rightX + 6;
                int topY2 = yPos - 2;
                int bottomY2 = yPos + fontH + 1;
                net.minecraft.client.gui.Gui.drawRect(startX2, topY2, endX2, bottomY2, 0x604488FF);
            }
            // Found rows use the current world's invite result, never a historical denicker alias.
            if (found != null) {
                int startX3 = leftX - 6;
                int endX3 = rightX + 6;
                int topY3 = yPos - 2;
                int bottomY3 = yPos + fontH + 1;
                net.minecraft.client.gui.Gui.drawRect(startX3, topY3, endX3, bottomY3, 0x6033DD33);
                // Render after the list/buttons so the tooltip stays on top.
                if (hovered) {
                    pendingTooltip = new java.util.ArrayList<>();
                    if (found.nickname == null) {
                        pendingTooltip.add(EnumChatFormatting.GOLD + "Nickname: " + EnumChatFormatting.GRAY + "unknown");
                        pendingTooltip.add(EnumChatFormatting.GRAY + "The server confirmed this player is in the game,");
                        pendingTooltip.add(EnumChatFormatting.GRAY + "but did not reveal their nickname.");
                    } else {
                        String coloredNick = found.coloredNickname;
                        if (coloredNick == null || coloredNick.isEmpty()) {
                            coloredNick = EnumChatFormatting.WHITE + found.nickname;
                        }
                        pendingTooltip.add(EnumChatFormatting.GOLD + "Nickname: " + EnumChatFormatting.RESET
                                + coloredNick + EnumChatFormatting.RESET);
                    }
                    tooltipX = mouseX;
                    tooltipY = Math.max(0, mouseY - 12);
                }
            }
            // Center the entire row: prefix + head + name
            int headSize = 10;
            int prefixWidth = fontRendererObj.getStringWidth(displayPrefix);
            int nameWidth = fontRendererObj.getStringWidth(playerText);
            int totalWidth = prefixWidth + 4 + headSize + 4 + nameWidth;
            int rowStartX = centerX - (totalWidth / 2);
            int prefixX = rowStartX;
            int headX = prefixX + prefixWidth + 4;
            int textX = headX + headSize + 4;
            int headY = yPos - 2;
            fontRendererObj.drawStringWithShadow(displayPrefix, prefixX, yPos, 0xFFFFFF);
            
            // Validate UUID before drawing head, fallback to steve if needed
            String uuid = player.getUuid();
            if (uuid == null || uuid.isEmpty() || uuid.length() < 32) {
                // Invalid UUID, use default steve skin or just skip drawing head
                // For now, we'll just try to draw it, but the drawPlayerHead method might need a fallback
            }
            drawPlayerHead(player.getUuid(), headX, headY + 1, headSize);
            
            fontRendererObj.drawStringWithShadow(playerText, textX, yPos, 0xFFFFFF);
            // skip centered draw for this row
            continue;
        }
        
        // Draw scrollbar (matching InventoryHUD style)
        drawScrollbar(mouseY);
        
        // IMPORTANT: Draw buttons BEFORE tooltip to prevent GL state corruption
        super.drawScreen(mouseX, mouseY, partialTicks);
        
        // Draw pending tooltip LAST (after super.drawScreen) to prevent GL color state corruption
        if (pendingTooltip != null) {
            GlStateManager.pushMatrix();
            try {
                HudGlState.restoreForOverlayText();
                this.drawHoveringText(pendingTooltip, tooltipX, tooltipY);
            } finally {
                GlStateManager.popMatrix();
                // glPopAttrib restores driver flags without updating GlStateManager's cached flags.
                // Restore through the managed API so hovering cannot change subsequent GUI/world brightness.
                RenderHelper.disableStandardItemLighting();
                HudGlState.restoreForOverlayText();
            }
        }
    }
    
    private String buildDisplayName(Player p) {
        String rank = getPlayerRank(p);
        String tag = HypixelTabStatsRankCache.formatOfficialRankTag(
                rank, p.getRankPlusColor(), p.getMonthlyRankColor());
        String nameColor = HypixelTabStatsRankCache.formatOfficialUsernameColor(rank, p.getMonthlyRankColor());
        return (tag.isEmpty() ? "" : tag + " ") + nameColor + p.getUsername() + EnumChatFormatting.RESET;
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: // Back
                mc.displayGuiScreen(parent);
                break;
            
            case 2: // Add Player
                if (!button.enabled) {
                    return; // Button is disabled, don't process
                }
                String playerName = playerNameField.getText().trim();
                if (!playerName.isEmpty()) {
                    final String usernameToAdd = playerName;
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Adding player " + usernameToAdd + "...");
                    new Thread(() -> {
                        String result = TeamSpamManager.addPlayer(usernameToAdd);
                        Main.sendMessage(result);
                        refreshPlayerListAndScrollToTop();
                    }).start();
                    playerNameField.setText("");
                    // Disable button again after adding
                    addPlayerButton.enabled = false;
                }
                break;
            
            case 4: // Remove Player
                if (!button.enabled) {
                    return; // Button is disabled, don't process
                }
                String removeName = playerNameField.getText().trim();
                removeName = sanitizeName(removeName);
                if (!removeName.isEmpty()) {
                    String result = TeamSpamManager.removePlayer(removeName);
                    Main.sendMessage(result);
                    playerNameField.setText("");
                    refreshPlayerList();
                    // Disable both buttons again after removing
                    addPlayerButton.enabled = false;
                    removePlayerButton.enabled = false;
                }
                break;
            
            case 5: // Refresh
                if (TeamSpamGUI.isRefreshing) break;
                startAsyncRefresh();
                break;
            
            case 6: // Clear All
                // Show confirmation dialog
                mc.displayGuiScreen(new GomodConfirmDialog(
                    (result, id) -> {
                        if (result) {
                            TeamSpamManager.clearPlayers();
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Cleared all players from team spam list.");
                            refreshPlayerList();
                        }
                        mc.displayGuiScreen(this);
                    },
                    EnumChatFormatting.RED + "Clear All Players?",
                    EnumChatFormatting.YELLOW + "This will permanently remove ALL players from your team spam list.\n\n" +
                    EnumChatFormatting.WHITE + "Players removed: " + EnumChatFormatting.RED + players.size() + "\n" +
                    EnumChatFormatting.GRAY + "This action cannot be undone!",
                    0
                ));
                break;
            
            case 8: // Toggle Spam
                // Run synchronously so the state is updated before we refresh the label
                TeamSpamCommand command = new TeamSpamCommand();
                command.handle(new String[0]); // Toggle command
                boolean nowRunning = me.napim.gomod.command.TeamSpamCommand.isRunning();
                isSpamming = nowRunning;
                toggleSpamButton.displayString = nowRunning ? (EnumChatFormatting.RED + "Stop Spam") : (EnumChatFormatting.GREEN + "Start Spam");
                break;
            
            case 9: // Unknown Nick Finder
                if (UnknownNickFinder.isRunning()) {
                    // Stop the finder
                    UnknownNickFinder.stop();
                    unknownNickFinderButton.displayString = EnumChatFormatting.AQUA + "Find Unknown";
                } else {
                    UnknownNickFinder.start();
                    unknownNickFinderButton.displayString = UnknownNickFinder.isRunning()
                            ? (EnumChatFormatting.RED + "Stop Finder")
                            : (EnumChatFormatting.AQUA + "Find Unknown");
                }
                break;
            case 11:
                mc.displayGuiScreen(new TeamSpamDateSettingsGUI(this));
                break;
        }
    }
    
    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        super.keyTyped(typedChar, keyCode);
        playerNameField.textboxKeyTyped(typedChar, keyCode);
        removePlayerField.textboxKeyTyped(typedChar, keyCode);
        
        // Enable/disable Add and Remove Player buttons based on text field content
        if (addPlayerButton != null) {
            addPlayerButton.enabled = !playerNameField.getText().trim().isEmpty();
        }
        if (removePlayerButton != null) {
            removePlayerButton.enabled = !playerNameField.getText().trim().isEmpty();
        }
        
        if (keyCode == 28) { // Enter key
            if (addPlayerButton.enabled) {
                actionPerformed(addPlayerButton);
            }
        }
    }
    
    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        // Click + drag scrollbar thumb
        if (mouseButton == 0 && this.maxScroll > 0
                && isMouseInBox(mouseX, mouseY, SCROLL_BAR_LEFT, SCROLL_BAR_RIGHT, SCROLL_BAR_TOP, SCROLL_BAR_BOTTOM)) {
            this.draggingScrollBar = true;
            this.scrollGrabbedAtY = mouseY - SCROLL_BAR_TOP;
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        playerNameField.mouseClicked(mouseX, mouseY, mouseButton);
        removePlayerField.mouseClicked(mouseX, mouseY, mouseButton);

        // Keep right-click available for drag-look on empty/list area.
        if (mouseButton != 0) {
            return;
        }

        // Detect clicks on player rows to prompt Update/Delete
        int centerX = width / 2;
        int startY = GuiHeader.STANDARD_SUBTITLE_Y;
        int baseY = startY + 164;
        int rowHeight = 15;
        // Clickable horizontal bounds around the centered list text
        int leftX = centerX - 220;
        int rightX = centerX + 220;
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10); // stop before back button
        
        int endIndex = Math.min(scrollOffset + PLAYERS_PER_PAGE, players.size());
        for (int i = scrollOffset; i < endIndex; i++) {
            int yPos = baseY + (i - scrollOffset) * rowHeight;
            if (yPos + 8 > visibleBottom) break; // don't allow clicks below visible area
            int fontH = fontRendererObj.FONT_HEIGHT;
            // Only clickable if the same hover condition that shows the highlight is true
            boolean hovered = (mouseX >= leftX && mouseX <= rightX && mouseY >= yPos && mouseY <= yPos + fontH);
            if (hovered) {
                final Player clickedPlayer = players.get(i);
                // Pass the raw username to avoid rank tags confusing backend lookups
                mc.displayGuiScreen(new TeamPlayerProfileGUI(this, clickedPlayer));
                break;
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && this.draggingScrollBar) {
            this.draggingScrollBar = false;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }
    
    @Override
    public void updateScreen() {
        super.updateScreen();
        playerNameField.updateCursorCounter();
        removePlayerField.updateCursorCounter();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(playerNameField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
    
    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        int maxPossibleOffset = Math.max(0, players.size() - PLAYERS_PER_PAGE);
        if (dWheel != 0 && maxPossibleOffset > 0) {
            if (dWheel > 0) {
                scrollOffset = Math.max(scrollOffset - SCROLL_STEP, 0);
            } else {
                scrollOffset = Math.min(scrollOffset + SCROLL_STEP, maxPossibleOffset);
            }
        }
    }

    private void startAsyncRefresh() {
        TeamSpamGUI.isRefreshing = true;
        refreshStartedAt.clear();
        refreshCompletedAt.clear();
        updateRefreshButtonVisualState();

        final java.util.List<Player> snapshot = new java.util.ArrayList<>(players);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Refreshing player list...");

        Thread refreshThread = new Thread(() -> {
            final java.util.List<RefreshChange> changes = java.util.Collections.synchronizedList(new java.util.ArrayList<RefreshChange>());
            final java.util.List<String> refreshErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
            try {
                if (!validateRefreshApiKey()) {
                    return;
                }

                if (!me.napim.gomod.command.TeamSpamCommand.RefreshCooldownPublic.allow()) {
                    long waitMs = me.napim.gomod.command.TeamSpamCommand.RefreshCooldownPublic.timeRemainingMs();
                    long mins = waitMs / 60000;
                    long secs = (waitMs % 60000) / 1000;
                    scheduleChatMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Refresh is on cooldown. Try again in " + mins + "m " + secs + "s.");
                    return;
                }

                if (snapshot.isEmpty()) {
                    scheduleRefreshComplete(new java.util.ArrayList<RefreshChange>(), new java.util.ArrayList<String>());
                    return;
                }

                int workerCount = Math.max(1, Math.min(REFRESH_WORKER_COUNT, snapshot.size()));
                java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(workerCount, runnable -> {
                    Thread worker = new Thread(runnable, "gomod-teamspam-refresh-worker");
                    worker.setDaemon(true);
                    return worker;
                });
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(snapshot.size());

                try {
                    for (Player player : snapshot) {
                        executor.submit(() -> {
                            markRefreshStarted(player);
                            try {
                                String beforeDisplay = buildDisplayName(player);
                                TeamSpamManager.updatePlayerInfoInPlace(player, refreshErrors);
                                String afterDisplay = buildDisplayName(player);
                                if (!afterDisplay.equals(beforeDisplay)) {
                                    changes.add(new RefreshChange(player.getUsername(), beforeDisplay, afterDisplay));
                                }
                            } catch (Exception e) {
                                refreshErrors.add(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Refresh error for "
                                        + EnumChatFormatting.WHITE + player.getUsername() + EnumChatFormatting.RED + ": "
                                        + EnumChatFormatting.GRAY + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                            } finally {
                                markRefreshCompleted(player);
                                latch.countDown();
                            }
                        });
                    }

                    latch.await();
                } finally {
                    executor.shutdownNow();
                }

                TeamSpamManager.savePlayers();
                scheduleRefreshComplete(new java.util.ArrayList<RefreshChange>(changes), new java.util.ArrayList<String>(refreshErrors));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduleChatMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Refresh was interrupted.");
            } finally {
                TeamSpamGUI.isRefreshing = false;
                TeamSpamGUI.lastRefreshCompletedMs = System.currentTimeMillis();
                refreshStartedAt.clear();
                runOnClient(this::updateRefreshButtonVisualState);
            }
        }, "gomod-teamspam-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private boolean validateRefreshApiKey() {
        try {
            String apiKey = me.napim.gomod.features.ApiKeyManager.getApiKey("hypixel");
            if (apiKey == null || apiKey.trim().isEmpty()) {
                runOnClient(() -> showHypixelApiKeyMessage(false));
                return false;
            }

            try {
                com.google.gson.JsonObject response = me.napim.gomod.features.HypixelApiHelper.fetchJson(
                        "https://api.hypixel.net/counts?key=" + java.net.URLEncoder.encode(apiKey.trim(), "UTF-8")
                );
                if (response == null || !response.has("success") || !response.get("success").getAsBoolean()) {
                    runOnClient(() -> showHypixelApiKeyMessage(true));
                    return false;
                }
            } catch (Exception e) {
                runOnClient(() -> showHypixelApiKeyMessage(true));
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            runOnClient(() -> showHypixelApiKeyMessage(true));
            return false;
        }
    }

    private void showHypixelApiKeyMessage(boolean invalid) {
        String devUrl = "https://developer.hypixel.net/";
        net.minecraft.util.IChatComponent prefix = new net.minecraft.util.ChatComponentText(Main.CHAT_PREFIX);
        net.minecraft.util.ChatComponentText part1 = new net.minecraft.util.ChatComponentText(
                (invalid ? net.minecraft.util.EnumChatFormatting.RED + "Hypixel API key invalid/expired - "
                        : net.minecraft.util.EnumChatFormatting.YELLOW + "No Hypixel API key set. ")
        );
        net.minecraft.util.ChatComponentText hint = new net.minecraft.util.ChatComponentText(
                net.minecraft.util.EnumChatFormatting.AQUA + " [" +
                        net.minecraft.util.EnumChatFormatting.AQUA + "" + net.minecraft.util.EnumChatFormatting.BOLD + "Click here to get a new key" +
                        net.minecraft.util.EnumChatFormatting.RESET + net.minecraft.util.EnumChatFormatting.AQUA + "]"
        );
        hint.getChatStyle().setChatClickEvent(new net.minecraft.event.ClickEvent(net.minecraft.event.ClickEvent.Action.OPEN_URL, devUrl));
        hint.getChatStyle().setChatHoverEvent(new net.minecraft.event.HoverEvent(net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
                new net.minecraft.util.ChatComponentText(net.minecraft.util.EnumChatFormatting.AQUA + "@https://developer.hypixel.net/")
        ));
        net.minecraft.util.ChatComponentText part2 = new net.minecraft.util.ChatComponentText(
                (invalid ? net.minecraft.util.EnumChatFormatting.RED : net.minecraft.util.EnumChatFormatting.YELLOW) +
                        " then save it in your gomod API Keys settings."
        );

        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(prefix.appendSibling(part1).appendSibling(hint).appendSibling(part2));
        }
    }

    private void scheduleRefreshComplete(java.util.List<RefreshChange> changes, java.util.List<String> refreshErrors) {
        runOnClient(() -> {
            refreshPlayerList();
            TeamSpamGUI.lastRefreshChanges = new java.util.ArrayList<RefreshChange>(changes);
            updateRefreshButtonVisualState();

            reportTeamSpamRefreshErrors(refreshErrors);

            int changedCount = changes.size();
            net.minecraft.util.ChatComponentText mainMsg = new net.minecraft.util.ChatComponentText(
                    Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Refresh complete. " +
                            EnumChatFormatting.YELLOW + "(" + changedCount + " updated) "
            );
            net.minecraft.util.ChatComponentText checkButton = new net.minecraft.util.ChatComponentText(
                    EnumChatFormatting.AQUA + "[Check]"
            );
            checkButton.getChatStyle().setChatHoverEvent(
                    new net.minecraft.event.HoverEvent(
                            net.minecraft.event.HoverEvent.Action.SHOW_TEXT,
                            new net.minecraft.util.ChatComponentText(EnumChatFormatting.YELLOW + "Check updates")
                    )
            );
            checkButton.getChatStyle().setChatClickEvent(
                    new net.minecraft.event.ClickEvent(
                            net.minecraft.event.ClickEvent.Action.RUN_COMMAND,
                            "/teamspamrefresh"
                    )
            );
            mainMsg.appendSibling(checkButton);

            try {
                if (Minecraft.getMinecraft().thePlayer != null) {
                    Minecraft.getMinecraft().thePlayer.addChatMessage(mainMsg);
                }
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Refresh complete. " + EnumChatFormatting.YELLOW + "(" + changedCount + " updated)");
            }

            if (mc != null) {
                mc.displayGuiScreen(new RefreshResultsDialog(TeamSpamGUI.this, changes));
            }
        });
    }

    private void scheduleChatMessage(String message) {
        runOnClient(() -> Main.sendMessage(message));
    }

    /** After refresh completes: one chat line per failure (capped), plus a summary if there are many. */
    private void reportTeamSpamRefreshErrors(java.util.List<String> refreshErrors) {
        if (refreshErrors == null || refreshErrors.isEmpty()) {
            return;
        }
        final int maxLines = 8;
        int n = refreshErrors.size();
        for (int i = 0; i < Math.min(maxLines, n); i++) {
            Main.sendMessage(refreshErrors.get(i));
        }
        if (n > maxLines) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "... and " + (n - maxLines)
                    + " more refresh errors (full list in client log).");
        }
        for (String line : refreshErrors) {
            System.err.println("[TeamSpamGUI/refresh] " + line.replaceAll("\u00a7.", ""));
        }
    }

    private void runOnClient(Runnable task) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || task == null) {
            return;
        }
        minecraft.addScheduledTask(task);
    }

    private void updateRefreshButtonVisualState() {
        if (refreshButton != null) {
            refreshButton.enabled = !TeamSpamGUI.isRefreshing;
            refreshButton.displayString = "Refresh";
        }
    }

    private void drawRefreshOverlay(Player player, int leftX, int rightX, int yPos, int fontHeight, long now) {
        if (!TeamSpamGUI.isRefreshing && refreshCompletedAt.isEmpty()) {
            return;
        }

        String refreshKey = getRefreshKey(player);
        if (refreshKey == null || refreshKey.isEmpty()) {
            return;
        }

        int startX = leftX - 6;
        int endX = rightX + 6;
        int topY = yPos - 2;
        int bottomY = yPos + fontHeight + 1;

        Long completedAt = refreshCompletedAt.get(refreshKey);
        if (completedAt != null) {
            long ageMs = now - completedAt;
            if (ageMs >= REFRESH_COMPLETE_FADE_MS) {
                refreshCompletedAt.remove(refreshKey, completedAt);
                return;
            }

            float remaining = 1.0F - (ageMs / (float) REFRESH_COMPLETE_FADE_MS);
            int alpha = 26 + Math.max(0, Math.min(85, (int) (remaining * 85.0F)));
            int color = (alpha << 24) | 0x33CC55;
            net.minecraft.client.gui.Gui.drawRect(startX, topY, endX, bottomY, color);
            return;
        }

        Long startedAt = refreshStartedAt.get(refreshKey);
        if (startedAt != null) {
            double pulse = 0.55D + (0.45D * Math.sin((now - startedAt) / 90.0D));
            int alpha = 28 + Math.max(0, Math.min(70, (int) Math.round(pulse * 70.0D)));
            int color = (alpha << 24) | 0x2FA84A;
            net.minecraft.client.gui.Gui.drawRect(startX, topY, endX, bottomY, color);
        }
    }

    private void markRefreshStarted(Player player) {
        String refreshKey = getRefreshKey(player);
        if (refreshKey == null || refreshKey.isEmpty()) {
            return;
        }
        refreshCompletedAt.remove(refreshKey);
        refreshStartedAt.put(refreshKey, System.currentTimeMillis());
    }

    private void markRefreshCompleted(Player player) {
        String refreshKey = getRefreshKey(player);
        if (refreshKey == null || refreshKey.isEmpty()) {
            return;
        }
        refreshStartedAt.remove(refreshKey);
        refreshCompletedAt.put(refreshKey, System.currentTimeMillis());
    }

    private String getRefreshKey(Player player) {
        if (player == null) {
            return null;
        }
        String uuid = player.getUuid();
        if (uuid != null && !uuid.trim().isEmpty()) {
            return uuid.trim().toLowerCase();
        }
        String username = player.getUsername();
        return username == null ? null : username.trim().toLowerCase();
    }
    
    public void refreshPlayerList() {
        players = TeamSpamManager.getPlayers();
        // Sort players so MVP++ always appear above non-MVP++ after refresh.
        // Within the same MVP++ group, sort by: most recently found first, then most recently added, then alphabetically.
        Collections.sort(players, (p1, p2) -> {
            // Primary: MVP++ first (ensure those who no longer have MVP++ go below)
            if (p1.hasMVPPlusPlus() && !p2.hasMVPPlusPlus()) return -1;
            if (!p1.hasMVPPlusPlus() && p2.hasMVPPlusPlus()) return 1;

            // Secondary: most recently found first (only players who were actually found online)
            long found1 = p1.getLastFoundTimestamp();
            long found2 = p2.getLastFoundTimestamp();
            boolean p1HasFound = found1 > 0;
            boolean p2HasFound = found2 > 0;

            // Players with a lastFoundTimestamp appear before those without
            if (p1HasFound && !p2HasFound) return -1;
            if (!p1HasFound && p2HasFound) return 1;

            // If both have found timestamps, newest first
            if (p1HasFound && p2HasFound && found1 != found2) {
                return Long.compare(found2, found1);
            }

            // Tertiary: most recently added first among players with same found status
            long added1 = p1.getAddedTimestamp();
            long added2 = p2.getAddedTimestamp();
            int addedCompare = Long.compare(added2, added1);
            if (addedCompare != 0) return addedCompare;

            // Quaternary: alphabetical by username
            return p1.getUsername().compareToIgnoreCase(p2.getUsername());
        });
        
        // Debug: Log the GUI sorted order
        System.out.println("[TeamSpamGUI] GUI sorted player order:");
        for (int i = 0; i < Math.min(5, players.size()); i++) {
            Player p = players.get(i);
            System.out.println("  " + (i+1) + ". " + p.getUsername() + " (found: " + p.getLastFoundTimestamp() + ")");
        }
        
        // Reset scroll if needed
        if (scrollOffset >= players.size()) {
            scrollOffset = Math.max(0, players.size() - PLAYERS_PER_PAGE);
        }
        
        // Update max scroll
        computeMaxScroll();
    }
    
    private void refreshPlayerListAndScrollToTop() {
        refreshPlayerList();
        scrollOffset = 0; // Scroll to top to show newly added player
    }
    
    private String getPlayerRank(Player player) {
        String rank = player.getRank();
        if (rank != null && !rank.isEmpty()) {
            return rank;
        }
        // Fallback to old method if rank is not set
        if (player.hasMVPPlusPlus()) {
            return "MVP++";
        }
        return "Default";
    }

    /** Formats ranks that do not use the MVP+/MVP++ split-color rules. */
    private String getRankTag(String rank, Player player) {
        if ("STAFF".equals(rank)) {
            return EnumChatFormatting.RED + "[" + EnumChatFormatting.GOLD + "\u12de"
                    + EnumChatFormatting.RED + "]";
        }
        return getRankColor(rank, player) + "[" + rank + "]";
    }
    
    private String getRankColor(String rank, Player player) {
        switch (rank) {
            case "STAFF":
                return EnumChatFormatting.RED + ""; // &c
            case "OWNER":
                return EnumChatFormatting.DARK_RED + ""; // &4
            case "ADMIN":
                return EnumChatFormatting.RED + ""; // &c
            case "GM":
                return EnumChatFormatting.DARK_GREEN + ""; // &2
            case "YOUTUBER":
                return EnumChatFormatting.GOLD + ""; // &6
            case "MOJANG":
                return EnumChatFormatting.GOLD + ""; // &6
            case "EVENTS":
                return EnumChatFormatting.LIGHT_PURPLE + ""; // &d
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + ""; // &d
            case "MVP++":
                return getMVPPlusPlusColor(player.getMonthlyRankColor());
            case "MVP+":
                return getMVPPlusColor(player.getRankPlusColor());
            case "MVP":
                return EnumChatFormatting.AQUA + ""; // &b
            case "VIP+":
                return EnumChatFormatting.GOLD + ""; // &6 (VIP+ is always gold)
            case "VIP":
                return EnumChatFormatting.GREEN + ""; // &a
            default:
                return EnumChatFormatting.YELLOW + ""; // &e
        }
    }
    
    private String getUsernameColor(String rank) {
        switch (rank) {
            case "STAFF":
                return EnumChatFormatting.RED + ""; // &c
            case "OWNER":
                return EnumChatFormatting.DARK_RED + ""; // &4
            case "ADMIN":
                return EnumChatFormatting.RED + ""; // &c
            case "GM":
                return EnumChatFormatting.DARK_GREEN + ""; // &2
            case "YOUTUBER":
                return EnumChatFormatting.GOLD + ""; // &6
            case "MOJANG":
                return EnumChatFormatting.GOLD + ""; // &6
            case "EVENTS":
                return EnumChatFormatting.LIGHT_PURPLE + ""; // &d
            case "INNIT":
                return EnumChatFormatting.LIGHT_PURPLE + ""; // &d
            case "MVP++":
                // Username color follows monthlyRankColor for MVP++
                // Note: We don't have player here, so this method isn't used for MVP++ usernames anymore.
                return EnumChatFormatting.AQUA + "";
            case "MVP+":
                return EnumChatFormatting.AQUA + ""; // &b (MVP+ usernames are always aqua)
            case "MVP":
                return EnumChatFormatting.AQUA + ""; // &b
            case "VIP+":
                return EnumChatFormatting.GREEN + ""; // &a (VIP+ usernames are always green)
            case "VIP":
                return EnumChatFormatting.GREEN + ""; // &a
            default:
                return EnumChatFormatting.WHITE + ""; // &f
        }
    }
    
    private String getMVPPlusPlusColor(String monthlyRankColor) {
        if (monthlyRankColor == null || monthlyRankColor.isEmpty()) {
            return EnumChatFormatting.GOLD + ""; // Default gold (§6)
        }
        
        // Exact mapping as specified in the API documentation
        switch (monthlyRankColor) {
            case "DEFAULT": return EnumChatFormatting.GOLD + ""; // §6 - Gold (Default MVP++ color)
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + ""; // §5 - Dark Purple
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + ""; // §d - Light Purple
            case "DARK_AQUA": return EnumChatFormatting.DARK_AQUA + ""; // §3 - Dark Aqua
            case "AQUA": return EnumChatFormatting.AQUA + ""; // §b - Aqua
            case "RED": return EnumChatFormatting.RED + ""; // §c - Red
            case "GOLD": return EnumChatFormatting.GOLD + ""; // §6 - Gold (same as DEFAULT)
            case "GREEN": return EnumChatFormatting.GREEN + ""; // §a - Green
            case "YELLOW": return EnumChatFormatting.YELLOW + ""; // §e - Yellow
            case "WHITE": return EnumChatFormatting.WHITE + ""; // §f - White
            case "BLUE": return EnumChatFormatting.BLUE + ""; // §9 - Blue
            case "DARK_GREEN": return EnumChatFormatting.DARK_GREEN + ""; // §2 - Dark Green
            case "DARK_RED": return EnumChatFormatting.DARK_RED + ""; // §4 - Dark Red
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + ""; // §8 - Dark Gray
            case "BLACK": return EnumChatFormatting.BLACK + ""; // §0 - Black
            case "DARK_BLUE": return EnumChatFormatting.DARK_BLUE + ""; // §1 - Dark Blue
            default: return EnumChatFormatting.GOLD + ""; // §6 - Default to gold if not found
        }
    }
    
    private String getMVPPlusColor(String rankPlusColor) {
        if (rankPlusColor == null || rankPlusColor.isEmpty()) {
            return EnumChatFormatting.GOLD + ""; // Default gold
        }
        
        switch (rankPlusColor) {
            case "RED": return EnumChatFormatting.RED + ""; // &c
            case "BLUE": return EnumChatFormatting.BLUE + ""; // &9
            case "GREEN": return EnumChatFormatting.GREEN + ""; // &a
            case "YELLOW": return EnumChatFormatting.YELLOW + ""; // &e
            case "GOLD": return EnumChatFormatting.GOLD + ""; // &6
            case "AQUA": return EnumChatFormatting.AQUA + ""; // &b
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + ""; // &d
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + ""; // &5
            case "WHITE": return EnumChatFormatting.WHITE + ""; // &f
            case "GRAY": return EnumChatFormatting.GRAY + ""; // &7
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + ""; // &8
            case "BLACK": return EnumChatFormatting.BLACK + ""; // &0
            default: return EnumChatFormatting.GOLD + ""; // Default to gold
        }
    }
    
    private String getMVPPlusPlusSignsColor(String rankPlusColor) {
        if (rankPlusColor == null || rankPlusColor.isEmpty()) {
            return EnumChatFormatting.GOLD + ""; // Default gold (§6)
        }
        
        switch (rankPlusColor) {
            case "DEFAULT": return EnumChatFormatting.GOLD + ""; // §6 - Gold
            case "DARK_PURPLE": return EnumChatFormatting.DARK_PURPLE + ""; // §5 - Dark Purple
            case "LIGHT_PURPLE": return EnumChatFormatting.LIGHT_PURPLE + ""; // §d - Light Purple
            case "DARK_AQUA": return EnumChatFormatting.DARK_AQUA + ""; // §3 - Dark Aqua
            case "AQUA": return EnumChatFormatting.AQUA + ""; // §b - Aqua
            case "RED": return EnumChatFormatting.RED + ""; // §c - Red
            case "GOLD": return EnumChatFormatting.GOLD + ""; // §6 - Gold
            case "GREEN": return EnumChatFormatting.GREEN + ""; // §a - Green
            case "YELLOW": return EnumChatFormatting.YELLOW + ""; // §e - Yellow
            case "WHITE": return EnumChatFormatting.WHITE + ""; // §f - White
            case "BLUE": return EnumChatFormatting.BLUE + ""; // §9 - Blue
            case "DARK_GREEN": return EnumChatFormatting.DARK_GREEN + ""; // §2 - Dark Green
            case "DARK_RED": return EnumChatFormatting.DARK_RED + ""; // §4 - Dark Red
            case "DARK_GRAY": return EnumChatFormatting.DARK_GRAY + ""; // §8 - Dark Gray
            case "BLACK": return EnumChatFormatting.BLACK + ""; // §0 - Black
            case "DARK_BLUE": return EnumChatFormatting.DARK_BLUE + ""; // §1 - Dark Blue
            default: return EnumChatFormatting.GOLD + ""; // §6 - Default to gold
        }
    }
    
    private void drawScrollbar(int mouseY) {
        // Draw a scrollbar indicator if needed (matching InventoryHUD style)
        int maxPossibleOffset = Math.max(0, players.size() - PLAYERS_PER_PAGE);
        if (maxPossibleOffset > 0) {
            int trackX = this.width - 10; // right side padding
            int trackWidth = 4;

            // Match player list geometry in drawScreen (startY = STANDARD_SUBTITLE_Y, list base = startY+164, row pitch 15)
            final int startY = GuiHeader.STANDARD_SUBTITLE_Y;
            final int baseY = startY + 164;
            final int rowPitch = 15;
            int trackTop = baseY;
            int visibleBottom = GuiHeader.contentBottomAboveBackBar(this.height, 20, 10);
            // End track at the bottom of the last list row slot, not past it into the back-button zone
            int listGridBottom = baseY + PLAYERS_PER_PAGE * rowPitch;
            int trackBottom = Math.min(visibleBottom, listGridBottom);
            int trackHeight = Math.max(1, trackBottom - trackTop);

            int totalContentHeight = Math.max(1, players.size() * rowPitch);
            int thumbHeight = Math.max(20, (int) (trackHeight * Math.min(1.0f, trackHeight / (float) totalContentHeight)));
            thumbHeight = Math.min(thumbHeight, trackHeight);

            // Thumb position based on scrollOffset
            int maxThumbTravel = trackHeight - thumbHeight;
            int thumbY = trackTop + (maxPossibleOffset == 0 ? 0 : (int) (maxThumbTravel * (scrollOffset / (float) maxPossibleOffset)));

            // Track background
            net.minecraft.client.gui.Gui.drawRect(trackX, trackTop, trackX + trackWidth, trackBottom, 0x60000000);
            // Thumb (lighter)
            net.minecraft.client.gui.Gui.drawRect(trackX, thumbY, trackX + trackWidth, thumbY + thumbHeight, 0x90FFFFFF);

            // Save thumb rect for click+drag
            this.SCROLL_BAR_LEFT = trackX;
            this.SCROLL_BAR_TOP = thumbY;
            this.SCROLL_BAR_RIGHT = trackX + trackWidth;
            this.SCROLL_BAR_BOTTOM = thumbY + thumbHeight;

            // Dragging: map mouse Y to scrollOffset
            if (this.draggingScrollBar) {
                int relativeMouseY = mouseY - trackTop - this.scrollGrabbedAtY;
                int clamped = Math.max(0, Math.min(maxThumbTravel, relativeMouseY));
                int newOffset = (maxThumbTravel <= 0) ? 0 : (int) ((float) maxPossibleOffset * ((float) clamped / (float) maxThumbTravel));
                this.scrollOffset = Math.max(0, Math.min(maxPossibleOffset, newOffset));
            }
        }
    }
    
    private void computeMaxScroll() {
        // scrollOffset is a ROW index, not pixels
        maxScroll = Math.max(0, players.size() - PLAYERS_PER_PAGE);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
    }

    private boolean isMouseInBox(int mouseX, int mouseY, int boxLeft, int boxRight, int boxTop, int boxBottom) {
        return mouseX >= boxLeft && mouseX < boxRight && mouseY >= boxTop && mouseY < boxBottom;
    }

    
    private void drawPlayerHead(String uuid, int x, int y, int size) {
        try {
            // Prefer Minotar head render by UUID (fast, consistent)
            if (uuid == null || uuid.isEmpty()) {
                uuid = "00000000000000000000000000000000"; // fallback steve
            } else {
                // Remove dashes if present, Minotar supports both but clean is better
                uuid = uuid.replace("-", "");
            }
            
            // Use Minotar instead of Crafatar (Crafatar has been unreliable)
            String url = "https://minotar.net/avatar/" + uuid + "/16";
            ResourceLocation rl = new ResourceLocation("minotar/avatar/" + uuid);
            TextureManager tm = Minecraft.getMinecraft().getTextureManager();
            ITextureObject tex = tm.getTexture(rl);
            if (tex == null) {
                // REFLECTION SAFETY: Use proper ThreadDownloadImageData constructor
                ThreadDownloadImageData data = new ThreadDownloadImageData(null, url, null, null);
                tm.loadTexture(rl, data);
            }
            tm.bindTexture(rl);
            // Draw whole 16x16 head image
            float z = this.zLevel;
            this.zLevel = 50.0F;
            // Save current color state
            net.minecraft.client.renderer.GlStateManager.pushMatrix();
            // Ensure white color (avoid black tint)
            net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            net.minecraft.client.renderer.GlStateManager.enableBlend();
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
            // Restore color state
            net.minecraft.client.renderer.GlStateManager.popMatrix();
            this.zLevel = z;
        } catch (Exception e) {
            // Log the actual error for debugging
            System.err.println("[gomod] Player head drawing error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String sanitizeName(String input) {
        if (input == null) return "";
        // Strip Minecraft color codes (§x)
        String cleaned = input.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "");
        // Remove any leading rank tags like [MVP], [MVP+], [MVP++] etc.
        cleaned = cleaned.replaceAll("^\\[[^]]+\\]\\s+", "");
        return cleaned.trim();
    }

    /** Opens {@link TeamSpamDateSettingsGUI}; draws clock item + {@code Date & time…} label. */
    private static final class TeamNickFinderDateSettingsLauncherButton extends GuiButton {

        private static final ItemStack CLOCK_STACK = new ItemStack(Items.clock);

        TeamNickFinderDateSettingsLauncherButton(int id, int x, int y, int w, int h) {
            super(id, x, y, w, h, "");
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) {
                return;
            }
            String saved = this.displayString;
            this.displayString = "";
            super.drawButton(mc, mouseX, mouseY);
            this.displayString = saved;

            String label = EnumChatFormatting.GOLD + "Date & time…";
            int iconSize = 16;
            int gap = 3;
            int lw = mc.fontRendererObj.getStringWidth(label);
            int total = iconSize + gap + lw;
            int ix = this.xPosition + (this.width - total) / 2;
            int ty = this.yPosition + (this.height - 8) / 2;
            int iconY = this.yPosition + (this.height - iconSize) / 2;
            try {
                RenderHelper.enableGUIStandardItemLighting();
                GlStateManager.enableDepth();
                mc.getRenderItem().zLevel = 100.0F;
                mc.getRenderItem().renderItemIntoGUI(CLOCK_STACK, ix, iconY);
                mc.getRenderItem().zLevel = 0.0F;
                RenderHelper.disableStandardItemLighting();
            } catch (Throwable ignored) {
            }
            GlStateManager.disableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);
            mc.fontRendererObj.drawStringWithShadow(label, ix + iconSize + gap, ty, 0xFFFFFF);
        }
    }

    // Simple action dialog to choose Update or Delete for a player
    private static class PlayerActionDialog extends GuiScreen {
        private final GuiScreen parent;
        private final String username;
        private final String uuid;
        private GuiButton updateButton;
        private GuiButton deleteButton;
        private GuiButton cancelButton;

        public PlayerActionDialog(GuiScreen parent, String rawUsername, String uuid) {
            this.parent = parent;
            this.username = rawUsername; // ensure raw username without rank tags is used
            this.uuid = uuid;
        }

        @Override
        public void initGui() {
            int centerX = width / 2;
            int centerY = height / 2;
            int buttonWidth = 100;
            int spacing = 24;

            updateButton = new GuiButton(1, centerX - buttonWidth - 10, centerY + 10, buttonWidth, 20, EnumChatFormatting.GREEN + "Update");
            deleteButton = new GuiButton(2, centerX + 10, centerY + 10, buttonWidth, 20, EnumChatFormatting.RED + "Delete");
            cancelButton = new GuiButton(0, centerX - 50, centerY + 40 + spacing, 100, 20, EnumChatFormatting.GRAY + "Cancel");

            buttonList.clear();
            buttonList.add(updateButton);
            buttonList.add(deleteButton);
            buttonList.add(cancelButton);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            // Use the same official Hypixel prefix/name colors as the main list and /infop.
            String display = EnumChatFormatting.WHITE + username;
            try {
                java.util.List<TeamSpamManager.Player> players = TeamSpamManager.getPlayers();
                TeamSpamManager.Player p = null;
                for (TeamSpamManager.Player it : players) {
                    if ((uuid != null && !uuid.isEmpty() && uuid.equalsIgnoreCase(it.getUuid())) ||
                        (it.getUsername() != null && it.getUsername().equalsIgnoreCase(username))) {
                        p = it; break;
                    }
                }
                if (p != null) {
                    display = ((TeamSpamGUI) parent).buildDisplayName(p);
                }
            } catch (Exception ignored) {}

            int titleY = height / 2 - 56; // move up so it doesn't touch buttons
            drawCenteredString(fontRendererObj, display + EnumChatFormatting.RESET, width / 2, titleY, 0xFFFFFF);
            // Draw player head below the name (also higher)
            int headSize = 32;
            int headX = width / 2 - headSize / 2;
            int headY = titleY + 12;
            ((TeamSpamGUI) parent).drawPlayerHead(uuid, headX, headY, headSize);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == cancelButton) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (button == updateButton) {
                mc.displayGuiScreen(parent);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Updating " + username + "...");
                new Thread(() -> {
                    String result = TeamSpamManager.updatePlayerInfo(username);
                    Main.sendMessage(result);
                    if (parent instanceof TeamSpamGUI) {
                        ((TeamSpamGUI) parent).refreshPlayerListAndScrollToTop();
                    }
                }).start();
                return;
            }
            if (button == deleteButton) {
                // Immediately remove without confirmation
                String msg = TeamSpamManager.removePlayer(username);
                Main.sendMessage(msg);
                if (parent instanceof TeamSpamGUI) {
                    ((TeamSpamGUI) parent).refreshPlayerList();
                }
                mc.displayGuiScreen(parent);
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }

    // Holds an individual change from refresh
    public static class RefreshChange {
        private final String username;
        private final String uuid;
        private final String beforeDisplay;
        private final String afterDisplay;

        public RefreshChange(String username, String beforeDisplay, String afterDisplay) {
            this.username = username;
            this.uuid = findPlayerUuid(username);
            this.beforeDisplay = beforeDisplay;
            this.afterDisplay = afterDisplay;
        }

        public String getUsername() { return username; }
        public String getUuid() { return uuid; }
        public String getBeforeDisplay() { return beforeDisplay; }
        public String getAfterDisplay() { return afterDisplay; }
        
        private String findPlayerUuid(String username) {
            // Find the player's UUID from the current player list
            List<Player> currentPlayers = TeamSpamManager.getPlayers();
            for (Player player : currentPlayers) {
                if (player.getUsername().equalsIgnoreCase(username)) {
                    return player.getUuid();
                }
            }
            return null; // Fallback if not found
        }
    }

    // Dialog that shows the list of refreshed players with before -> after display names
    public static class RefreshResultsDialog extends GuiScreen {
        private final GuiScreen parent;
        private final java.util.List<RefreshChange> changes;
        private GuiButton closeButton;
        private int scrollOffset = 0;
        private static final int ROW_HEIGHT = 14;

        public RefreshResultsDialog(GuiScreen parent, java.util.List<RefreshChange> changes) {
            this.parent = parent;
            this.changes = changes == null ? java.util.Collections.emptyList() : changes;
        }

        @Override
        public void initGui() {
            int centerX = width / 2;
            closeButton = new GuiButton(0, centerX - 50, GuiHeader.backButtonY(height, 20), 100, 20, EnumChatFormatting.GRAY + "Close");
            buttonList.clear();
            buttonList.add(closeButton);
            scrollOffset = 0;
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            int centerX = width / 2;
            String title = EnumChatFormatting.GOLD + "Refresh Results" + EnumChatFormatting.RESET;
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, centerX, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
            String subtitle = EnumChatFormatting.YELLOW + "Updated entries: " + changes.size();
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, subtitle, centerX, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

            int contentTop = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj);
            int contentBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10);
            int visibleRows = Math.max(0, (contentBottom - contentTop) / ROW_HEIGHT);
            int endIndex = Math.min(changes.size(), scrollOffset + visibleRows);

            // Center the content area
            int contentWidth = Math.min(600, width - 80); // Max width of 600px, with 40px margin on each side
            int leftX = centerX - contentWidth / 2;
            int rightX = centerX + contentWidth / 2;

            for (int i = scrollOffset; i < endIndex; i++) {
                int y = contentTop + (i - scrollOffset) * ROW_HEIGHT;
                // Row background for readability
                if ((i & 1) == 0) {
                    net.minecraft.client.gui.Gui.drawRect(leftX - 6, y - 2, rightX + 6, y + fontRendererObj.FONT_HEIGHT + 2, 0x20000000);
                }
                RefreshChange c = changes.get(i);
                String before = c.getBeforeDisplay();
                String after = c.getAfterDisplay();
                String arrow = EnumChatFormatting.GRAY + "  ->  " + EnumChatFormatting.RESET;

                // Build centered composite string
                String fullText = before + EnumChatFormatting.RESET + arrow + after + EnumChatFormatting.RESET;
                int textWidth = fontRendererObj.getStringWidth(fullText);
                int textX = centerX - textWidth / 2;
                
                // Draw avatar head next to the text (left of the text)
                int headSize = 10;
                int headX = textX - headSize - 4; // Position head to the left of the text
                int headY = y - 1;
                drawPlayerHead(c.getUuid(), headX, headY, headSize);
                
                // Draw the text centered
                fontRendererObj.drawStringWithShadow(fullText, textX, y, 0xFFFFFF);
            }

            // Simple scrollbar thumb
            int maxOffset = Math.max(0, changes.size() - visibleRows);
            if (maxOffset > 0) {
                int trackX = width - 10;
                int trackTop = contentTop;
                int trackBottom = contentBottom;
                int trackHeight = trackBottom - trackTop;
                int thumbHeight = Math.max(20, (int) (trackHeight * (visibleRows / (float) Math.max(1, changes.size()))));
                int maxThumbTravel = trackHeight - thumbHeight;
                int thumbY = trackTop + (int) (maxThumbTravel * (scrollOffset / (float) maxOffset));
                net.minecraft.client.gui.Gui.drawRect(trackX, trackTop, trackX + 4, trackBottom, 0x60000000);
                net.minecraft.client.gui.Gui.drawRect(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0x90FFFFFF);
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        public void handleMouseInput() throws java.io.IOException {
            super.handleMouseInput();
            int contentTop = 44;
            int contentBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10);
            int visibleRows = Math.max(0, (contentBottom - contentTop) / ROW_HEIGHT);
            int maxOffset = Math.max(0, changes.size() - visibleRows);
            int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (dWheel != 0 && maxOffset > 0) {
                if (dWheel > 0) {
                    scrollOffset = Math.max(0, scrollOffset - 3);
                } else {
                    scrollOffset = Math.min(maxOffset, scrollOffset + 3);
                }
            }
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
        
        private void drawPlayerHead(String uuid, int x, int y, int size) {
            try {
                // Prefer Minotar head render by UUID (fast, consistent)
                if (uuid == null || uuid.isEmpty()) {
                    uuid = "00000000000000000000000000000000"; // fallback steve
                } else {
                    // Remove dashes if present, Minotar supports both but clean is better
                    uuid = uuid.replace("-", "");
                }
                
                // Use Minotar instead of Crafatar (Crafatar has been unreliable)
                String url = "https://minotar.net/avatar/" + uuid + "/16";
                ResourceLocation rl = new ResourceLocation("minotar/avatar/" + uuid);
                TextureManager tm = Minecraft.getMinecraft().getTextureManager();
                ITextureObject tex = tm.getTexture(rl);
                if (tex == null) {
                    // REFLECTION SAFETY: Use proper ThreadDownloadImageData constructor
                    ThreadDownloadImageData data = new ThreadDownloadImageData(null, url, null, null);
                    tm.loadTexture(rl, data);
                }
                tm.bindTexture(rl);
                // Draw whole 16x16 head image
                float z = this.zLevel;
                this.zLevel = 50.0F;
                // Save current color state
                net.minecraft.client.renderer.GlStateManager.pushMatrix();
                // Ensure white color (avoid black tint)
                net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                net.minecraft.client.renderer.GlStateManager.enableBlend();
                Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
                // Restore color state
                net.minecraft.client.renderer.GlStateManager.popMatrix();
                this.zLevel = z;
            } catch (Exception e) {
                // Log the actual error for debugging
                System.err.println("[gomod] Player head drawing error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
