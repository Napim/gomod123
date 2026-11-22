package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.MojangUsernameResolver;
import me.napim.gomod.features.MoreHudPotionIcons;
import me.napim.gomod.features.PlayerQueuer;
import net.minecraft.potion.Potion;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.gui.Gui;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple GUI for managing PlayerQueuer targets and toggle.
 */
public class PlayerQueuerGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final ResourceLocation DELETE_ICON = new ResourceLocation("gomod123:textures/deleteicon.png");
    private static final ResourceLocation DELETE_ICON_FALLBACK = new ResourceLocation("gomod123:runtime/deleteicon");
    private static final ResourceLocation DEATH_TARGET_ICON = new ResourceLocation("gomod123:textures/deathtarget.png");
    private static final ResourceLocation DEATH_TARGET_ACTIVE_ICON = new ResourceLocation("gomod123:textures/deathtarget_active.png");
    private static final ResourceLocation DEATH_TARGET_ICON_FALLBACK = new ResourceLocation("gomod123:runtime/deathtarget");
    private static final ResourceLocation DEATH_TARGET_ACTIVE_ICON_FALLBACK = new ResourceLocation("gomod123:runtime/deathtarget_active");
    private static boolean deleteIconLoaded = false;
    private static boolean deleteIconTried = false;
    private static boolean deleteIconUsingFallback = false;
    private static int deleteIconW = 16;
    private static int deleteIconH = 16;
    private static boolean deathTargetIconLoaded = false;
    private static boolean deathTargetIconTried = false;
    private static boolean deathTargetIconUsingFallback = false;
    private final GuiScreen parent;
    private GuiButton backButton;
    private GuiButton helpButton;
    private GuiButton toggleButton;
    private GuiButton includeAddButton;
    private GuiButton includeClearButton;
    private GuiButton excludeAddButton;
    private GuiButton excludeClearButton;
    private GuiButton includeModeButton;
    private GuiButton includeRulesButton;
    private GuiButton excludeRulesButton;
    private GuiButton excludeModeButton;
    private PlayerQueuerPreStartSlider preStartSlider;
    /** Local drag target; do not use GuiScreen's private selectedButton (IllegalAccessError at runtime). */
    private GuiButton selectedButton;
    private GuiTextField includeField;
    private GuiTextField excludeField;

    private List<String> includeList;
    private List<String> excludeList;

    // Scrolling for lists
    private int includeIndexOffset = 0;
    private int excludeIndexOffset = 0;
    private static final int ROW_HEIGHT = 15; // Increased to fit player heads
    private static final int ROW_ACTION_ICON_SIZE = 10;
    private static final int ROW_GLOW_ICON_X = 38;
    private static final int ROW_GLOW_ICON_DODGE_X = 26;
    private static final int ROW_DEATH_ICON_X = 26;
    private static final int ROW_DELETE_ICON_X = 14;
    private static final int SCROLL_ROWS_STEP = 3;
    /** Synthetic list rows used to show dynamic rules beside named players. */
    private static final String RULE_ALL_NICKS = "\u0001pq-rule-all-nicks";
    private static final String RULE_WLR_RANGE = "\u0001pq-rule-wlr-range";
    /** Include/Exclude panel top (fields + lists). */
    private static final int PANEL_TOP_Y = 68;
    /** Slider track sits above Enabled; hint text one line higher. */
    private static final int BOTTOM_SLIDER_GAP_ABOVE_TOGGLE = 8;
    /** Vertical gap between stacked bottom-bar buttons (Help / Enabled above Back). */
    private static final int BOTTOM_BAR_STACK_GAP = 4;
    /** Include/Exclude panel body width (list + fields). */
    private static final int PANEL_BODY_WIDTH_PX = 260;
    /** Text input outer width inside include/exclude panels. */
    private static final int PANEL_FIELD_WIDTH_PX = PANEL_BODY_WIDTH_PX - 90;
    /**
     * Panel list height = {@code height - this}; keeps boxed lists above the pre-start slider and hint,
     * with bottom controls stacked on {@link GuiHeader#backButtonY}.
     */
    private static final int PANEL_BODY_HEIGHT_MINUS_SCREEN = 232;
    private static final int LIST_TOP_OFFSET_FROM_PANEL_TOP = 54;
    /** {@code panelBodyHeight - listHeight}: space for field rows above the list. */
    private static final int PANEL_BODY_TO_LIST_HEIGHT_OFFSET = 78;
    
    // Cache for player UUIDs and properly capitalized names
    private static final Map<String, String> playerUUIDCache = new ConcurrentHashMap<>();
    private static final Map<String, String> playerNameCache = new ConcurrentHashMap<>();
    private static final Set<String> playerNameFetchInFlight = ConcurrentHashMap.newKeySet();
    /** TAB name cycle state for include/exclude fields (Blitz pregame only). */
    private List<String> pregameNameTabCache = new ArrayList<>();
    private int pregameNameTabIndex = 0;
    private String pregameNameTabPrefix = "";
    private GuiTextField pregameNameTabField;

    public PlayerQueuerGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int centerX = width / 2;
        int panelWidth = PANEL_BODY_WIDTH_PX;
        int gutter = 24;
        int leftX = centerX - panelWidth - gutter;
        int rightX = centerX + gutter;
        int topY = PANEL_TOP_Y;

        int backY = GuiHeader.backButtonY(height, 20);
        int helpY = backY - BOTTOM_BAR_STACK_GAP - 20;
        int toggleY = helpY - BOTTOM_BAR_STACK_GAP - 20;
        int sliderWidth = Math.min(220, Math.max(152, width - 48));
        int sliderX = (width - sliderWidth) / 2;
        int sliderY = toggleY - 20 - BOTTOM_SLIDER_GAP_ABOVE_TOGGLE;
        preStartSlider = new PlayerQueuerPreStartSlider(14, sliderX, sliderY, sliderWidth);

        // Status row
        // Place Enabled above Help/Back at bottom center
        toggleButton = new GuiButton(0, centerX - 60, toggleY, 120, 20,
            PlayerQueuer.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        // Small mode buttons within panels
        includeModeButton = new GuiButton(5, leftX + panelWidth - 80, topY + 24, 80, 20,
            PlayerQueuer.isRequireAllInclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY");
        excludeModeButton = new GuiButton(10, rightX + panelWidth - 80, topY + 24, 80, 20,
            PlayerQueuer.isRequireAllExclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY");

        // Queue panel controls
        // Row 1: Queue field + Add button + Queue mode
        includeField = new GuiTextField(1, fontRendererObj, leftX, topY, PANEL_FIELD_WIDTH_PX, 20);
        includeField.setMaxStringLength(16);
        includeField.setEnableBackgroundDrawing(false);
        includeAddButton = new GuiButton(2, leftX + panelWidth - 80, topY, 80, 20, "Add Queue");
        includeModeButton = new GuiButton(5, leftX + panelWidth - 60, topY + 24, 60, 20,
            PlayerQueuer.isRequireAllInclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY");
        // Row 2: Clear
        includeClearButton = new GuiButton(4, leftX, topY + 24, 80, 20, "Clear All");
        includeRulesButton = new GuiButton(13, leftX + 86, topY + 24, 108, 20,
                EnumChatFormatting.AQUA + "Queue Rules");

        // Dodge panel controls
        // Row 1: Dodge field + Add button + Dodge mode
        excludeField = new GuiTextField(7, fontRendererObj, rightX, topY, PANEL_FIELD_WIDTH_PX, 20);
        excludeField.setMaxStringLength(16);
        excludeField.setEnableBackgroundDrawing(false);
        excludeAddButton = new GuiButton(8, rightX + panelWidth - 80, topY, 80, 20, "Add Dodge");
        excludeModeButton = new GuiButton(10, rightX + panelWidth - 60, topY + 24, 60, 20,
            PlayerQueuer.isRequireAllExclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY");
        // Row 2: Clear
        excludeClearButton = new GuiButton(11, rightX, topY + 24, 80, 20, "Clear All");
        excludeRulesButton = new GuiButton(15, rightX + 86, topY + 24, 108, 20,
                EnumChatFormatting.DARK_RED + "Dodge Rules");

        helpButton = new GuiButton(12, centerX - 60, helpY, 120, 20, EnumChatFormatting.YELLOW + "Help");
        backButton = new GuiButton(6, centerX - 60, backY, 120, 20, EnumChatFormatting.GRAY + "Back to Menu");

        buttonList.clear();
        buttonList.add(includeModeButton);
        buttonList.add(excludeModeButton);
        buttonList.add(includeAddButton);
        buttonList.add(includeClearButton);
        buttonList.add(includeRulesButton);
        buttonList.add(excludeAddButton);
        buttonList.add(excludeClearButton);
        buttonList.add(excludeRulesButton);
        buttonList.add(preStartSlider);
        buttonList.add(toggleButton);
        buttonList.add(helpButton);
        buttonList.add(backButton);

        refreshLists();
        includeIndexOffset = 0;
        excludeIndexOffset = 0;
        selectedButton = null;
        updateButtons();
    }

    private int pqPanelBodyHeightPx() {
        return Math.max(96, height - PANEL_BODY_HEIGHT_MINUS_SCREEN);
    }

    private int pqListTopPx() {
        return PANEL_TOP_Y + LIST_TOP_OFFSET_FROM_PANEL_TOP;
    }

    private int pqListHeightPx() {
        return pqPanelBodyHeightPx() - PANEL_BODY_TO_LIST_HEIGHT_OFFSET;
    }

    private void refreshLists() {
        includeList = PlayerQueuer.getTargets();
        excludeList = PlayerQueuer.getExcludeTargets();
        if (PlayerQueuer.isIncludeAllNicks()) includeList.add(RULE_ALL_NICKS);
        if (PlayerQueuer.isIncludeWlrRange()) includeList.add(RULE_WLR_RANGE);
        if (PlayerQueuer.isExcludeAllNicks()) excludeList.add(RULE_ALL_NICKS);
        if (PlayerQueuer.isExcludeWlrRange()) excludeList.add(RULE_WLR_RANGE);
    }

    private void updateButtons() {
        boolean hasText = includeField.getText() != null && !includeField.getText().trim().isEmpty();
        includeAddButton.enabled = hasText;
        boolean hasExText = excludeField.getText() != null && !excludeField.getText().trim().isEmpty();
        excludeAddButton.enabled = hasExText;
        toggleButton.displayString = PlayerQueuer.isEnabled() ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled";
        includeModeButton.displayString = PlayerQueuer.isRequireAllInclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY";
        excludeModeButton.displayString = PlayerQueuer.isRequireAllExclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updatePreStartSliderDrag(mouseX, mouseY);
        drawDefaultBackground();
        int centerX = width / 2;
        int panelWidth = PANEL_BODY_WIDTH_PX;
        int panelHeight = pqPanelBodyHeightPx();
        int gutter = 24;
        int leftX = centerX - panelWidth - gutter;
        int rightX = centerX + gutter;
        int topY = PANEL_TOP_Y;

        // Match gomod menu button color: DARK_PURPLE
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.DARK_PURPLE + "" + EnumChatFormatting.BOLD + "Player Queuer", centerX, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String includeInfo = PlayerQueuer.isRequireAllInclude() ? (EnumChatFormatting.GOLD + "Queue: ALL") : (EnumChatFormatting.GREEN + "Queue: ANY");
        String excludeInfo = PlayerQueuer.isRequireAllExclude() ? (EnumChatFormatting.GOLD + "Dodge: ALL") : (EnumChatFormatting.GREEN + "Dodge: ANY");
        String modeInfo = includeInfo + EnumChatFormatting.RESET + "  |  " + excludeInfo;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.YELLOW + "Modes: " + EnumChatFormatting.RESET + modeInfo, centerX, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        // Panels
        drawPanel(leftX - 6, topY - 6, panelWidth + 12, panelHeight + 12);
        drawPanel(rightX - 6, topY - 6, panelWidth + 12, panelHeight + 12);

        // Headings (centered over each panel)
        String includeTitle = EnumChatFormatting.AQUA + "Queue (stay when present)  [" + includeList.size() + "]";
        int includeTitleX = leftX + Math.max(0, (panelWidth - fontRendererObj.getStringWidth(includeTitle)) / 2);
        drawString(fontRendererObj, includeTitle, includeTitleX, topY - 18, 0xFFFFFF);
        String excludeTitle = EnumChatFormatting.DARK_RED + "Dodge (requeue if present)  [" + excludeList.size() + "]";
        int excludeTitleX = rightX + Math.max(0, (panelWidth - fontRendererObj.getStringWidth(excludeTitle)) / 2);
        drawString(fontRendererObj, excludeTitle, excludeTitleX, topY - 18, 0xFFFFFF);

        // List areas
        int listTop = pqListTopPx();
        int listHeight = pqListHeightPx();
        drawList(includeList, leftX, listTop, panelWidth, listHeight, includeIndexOffset, false);
        drawList(excludeList, rightX, listTop, panelWidth, listHeight, excludeIndexOffset, true);

        // Input fields
        HudPositionFieldPair.drawField(includeField, leftX, topY, PANEL_FIELD_WIDTH_PX);
        HudPositionFieldPair.drawField(excludeField, rightX, topY, PANEL_FIELD_WIDTH_PX);

        int bottomBackY = GuiHeader.backButtonY(height, 20);
        int bottomHelpY = bottomBackY - BOTTOM_BAR_STACK_GAP - 20;
        int bottomToggleY = bottomHelpY - BOTTOM_BAR_STACK_GAP - 20;
        int bottomSliderY = bottomToggleY - 20 - BOTTOM_SLIDER_GAP_ABOVE_TOGGLE;
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();
        String digitLightRed = EnumChatFormatting.RED.toString();
        drawCenteredString(fontRendererObj,
                g + "Time before requeuing (after the \"" + y + "The game starts in " + digitLightRed + "3" + y
                        + " seconds!" + g + "\" line)",
                centerX, bottomSliderY - 14, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, PlayerQueuerGUI.class);
        drawModeTooltip(mouseX, mouseY);
    }

    private void updatePreStartSliderDrag(int mouseX, int mouseY) {
        if (!(this.selectedButton instanceof PlayerQueuerPreStartSlider)) {
            return;
        }
        if (Mouse.isButtonDown(0)) {
            ((PlayerQueuerPreStartSlider) this.selectedButton).mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
        } else {
            this.selectedButton.mouseReleased(mouseX, mouseY);
            this.selectedButton = null;
        }
    }

    private void drawModeTooltip(int mouseX, int mouseY) {
        if (isMouseOverButton(includeModeButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Queue mode",
                    EnumChatFormatting.GREEN + "ANY" + EnumChatFormatting.GRAY
                            + ": queue if one target matches.",
                    EnumChatFormatting.GOLD + "ALL" + EnumChatFormatting.GRAY
                            + ": queue only if every target matches."
            ), mouseX, mouseY);
        } else if (isMouseOverButton(excludeModeButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.DARK_RED + "Dodge mode",
                    EnumChatFormatting.GREEN + "ANY" + EnumChatFormatting.GRAY
                            + ": dodge if one target matches.",
                    EnumChatFormatting.GOLD + "ALL" + EnumChatFormatting.GRAY
                            + ": dodge only if every target matches."
            ), mouseX, mouseY);
        }
    }

    private static boolean isMouseOverButton(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    private void drawPanel(int x, int y, int w, int h) {
        int bg = 0x40000000;
        int border = 0x90FFFFFF;
        net.minecraft.client.gui.Gui.drawRect(x, y, x + w, y + h, bg);
        // Draw panel border without double-stacking alpha at the corners.
        net.minecraft.client.gui.Gui.drawRect(x, y, x + w, y + 1, border);
        net.minecraft.client.gui.Gui.drawRect(x, y + h - 1, x + w, y + h, border);
        net.minecraft.client.gui.Gui.drawRect(x, y + 1, x + 1, y + h - 1, border);
        net.minecraft.client.gui.Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    private void drawList(List<String> items, int x, int y, int w, int h, int indexOffset, boolean excludePanel) {
        if (items == null) return;
        int maxStart = maxListScrollStart(items, h);
        int start = Math.max(0, Math.min(indexOffset, maxStart));
        int content = items.size();
        boolean showScroll = listContentHeight(items, 0) > h;

        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

        int yy = y;
        for (int i = start; i < items.size(); i++) {
            String t = items.get(i);
            int rowH = rowHeightForEntry(t);
            if (yy + rowH > y + h) {
                break;
            }

            boolean hovering = mouseX >= x && mouseX <= x + w && mouseY >= yy && mouseY <= yy + rowH;
            int rowUsableWidth = w - (showScroll ? 6 : 0);

            if ((i & 1) == 0) {
                net.minecraft.client.gui.Gui.drawRect(x, yy, x + w, yy + rowH, 0x18000000);
            }
            if (hovering) {
                net.minecraft.client.gui.Gui.drawRect(x, yy, x + w, yy + rowH, 0x80000000);
                if (!isRuleRow(t)) {
                    boolean dodgeListed = PlayerQueuer.isExcludeTargetListed(t);
                    drawGlowToggleIcon(x + w - (dodgeListed ? ROW_GLOW_ICON_DODGE_X : ROW_GLOW_ICON_X),
                            yy + 3, PlayerQueuer.isPlayerGlowEnabled(t));
                    if (!dodgeListed) {
                        drawDeathTrackIcon(x + w - ROW_DEATH_ICON_X, yy + 3, PlayerQueuer.isDeathTrackListed(t));
                    }
                }
                drawDeleteIcon(x + w - ROW_DELETE_ICON_X, yy + 3);
            }

            if (isRuleRow(t)) {
                String label = ruleRowLabel(t, excludePanel);
                int availableW = rowUsableWidth - 18;
                String clipped = fontRendererObj.trimStringToWidth(label, Math.max(20, availableW));
                int labelX = x + Math.max(2, (rowUsableWidth - fontRendererObj.getStringWidth(clipped)) / 2);
                fontRendererObj.drawStringWithShadow(clipped, labelX, yy + 3, 0xFFFFFF);
                yy += rowH;
                continue;
            }

            int headSize = 10;
            int gapAfterHead = 4;
            int headY = yy + 2;

            String displayName = getCapitalizedName(t);
            String denickAlias = PlayerQueuer.getDenickAliasForDisplay(t);
            EnumChatFormatting nameColor = denickAlias != null
                    ? EnumChatFormatting.RED
                    : (excludePanel ? EnumChatFormatting.DARK_RED : EnumChatFormatting.RED);
            String nameDisplay = nameColor + displayName;
            if (denickAlias != null) {
                nameDisplay += EnumChatFormatting.GOLD + " ("
                        + getCapitalizedName(denickAlias.toLowerCase(Locale.ROOT)) + ")";
            }
            int nameWidth = fontRendererObj.getStringWidth(nameDisplay);
            boolean dodgeListed = PlayerQueuer.isExcludeTargetListed(t);
            boolean deathTracked = !dodgeListed && PlayerQueuer.isDeathTrackListed(t);
            int deathIconOuterWidth = deathTracked ? (2 + 10) : 0;
            int rowContentWidth = headSize + gapAfterHead + nameWidth + deathIconOuterWidth;
            int startX = x + Math.max(0, (rowUsableWidth - rowContentWidth) / 2);
            int headX = startX;
            int nameX = headX + headSize + gapAfterHead;

            String uuid = getPlayerUUID(t);
            drawPlayerHead(uuid, headX, headY, headSize);
            fontRendererObj.drawStringWithShadow(nameDisplay, nameX, yy + 3, 0xFFFFFF);

            if (deathTracked) {
                int iconX = nameX + nameWidth + 2;
                int iconY = yy + 2;
                drawDeathTrackIcon(iconX, iconY, true);
            }

            yy += rowH;
        }

        if (showScroll) {
            int trackX = x + w - 4;
            int trackTop = y;
            int trackHeight = h;
            int totalHeight = listContentHeight(items, 0);
            int thumbHeight = Math.max(20, (int) (trackHeight * (h / (float) totalHeight)));
            int maxTravel = trackHeight - thumbHeight;
            int scrolled = listContentHeight(items, 0) - listContentHeight(items, start);
            int thumbY = trackTop + (int) (maxTravel * (scrolled / (float) Math.max(1, totalHeight - h)));
            net.minecraft.client.gui.Gui.drawRect(trackX, trackTop, trackX + 3, trackTop + trackHeight, 0x40000000);
            net.minecraft.client.gui.Gui.drawRect(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0x90FFFFFF);
        }
    }

    private static int rowHeightForEntry(String storedLower) {
        return ROW_HEIGHT;
    }

    private static boolean isRuleRow(String value) {
        return RULE_ALL_NICKS.equals(value) || RULE_WLR_RANGE.equals(value);
    }

    private static String ruleRowLabel(String value, boolean excludePanel) {
        String color = (excludePanel ? EnumChatFormatting.DARK_RED : EnumChatFormatting.AQUA).toString();
        if (RULE_ALL_NICKS.equals(value)) {
            return color + "[RULE] Nicks";
        }
        double threshold = excludePanel ? PlayerQueuer.getExcludeWlrThreshold()
                : PlayerQueuer.getIncludeWlrThreshold();
        return color + "[RULE] WLR >= " + formatRuleWlr(threshold);
    }

    private static String formatRuleWlr(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static int listContentHeight(List<String> items, int fromIndex) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = fromIndex; i < items.size(); i++) {
            total += rowHeightForEntry(items.get(i));
        }
        return total;
    }

    private static int maxListScrollStart(List<String> items, int viewportHeight) {
        if (items == null || items.isEmpty() || viewportHeight <= 0) {
            return 0;
        }
        if (listContentHeight(items, 0) <= viewportHeight) {
            return 0;
        }
        for (int start = 0; start < items.size(); start++) {
            if (listContentHeight(items, start) <= viewportHeight) {
                return start;
            }
        }
        return Math.max(0, items.size() - 1);
    }

    /**
     * Ensures the delete icon texture is loaded with fallback mechanisms
     */
    private static void ensureDeleteIconLoaded() {
        if (deleteIconLoaded) return; // Already loaded successfully
        if (deleteIconTried) return; // Already tried and failed
        deleteIconTried = true;
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getResourceManager() == null) return;
        
        try {
            // Try loading from resource pack first
            mc.getResourceManager().getResource(DELETE_ICON);
            deleteIconLoaded = true;
            deleteIconUsingFallback = false;
            deleteIconW = 16; // Default dimensions
            deleteIconH = 16;
            System.out.println("[PlayerQueuerGUI] Delete icon loaded from resource pack");
            return;
        } catch (Exception e) {
            System.out.println("[PlayerQueuerGUI] Could not load delete icon from resource pack: " + e.getMessage());
        }
        
        // Fallback: load from classpath
        try {
            java.io.InputStream stream = PlayerQueuerGUI.class.getResourceAsStream("/assets/gomod123/textures/deleteicon.png");
            if (stream != null) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(stream);
                if (img != null) {
                    deleteIconW = img.getWidth();
                    deleteIconH = img.getHeight();
                    DynamicTexture dyn = new DynamicTexture(img);
                    mc.getTextureManager().loadTexture(DELETE_ICON_FALLBACK, dyn);
                    deleteIconLoaded = true;
                    deleteIconUsingFallback = true;
                    System.out.println("[PlayerQueuerGUI] Delete icon loaded from fallback (runtime) - " + deleteIconW + "x" + deleteIconH);
                    try { stream.close(); } catch (Exception ignored) {}
                    return;
                }
            }
            System.out.println("[PlayerQueuerGUI] Failed to load delete icon from classpath");
        } catch (Throwable e) {
            System.out.println("[PlayerQueuerGUI] Error loading delete icon: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Invisibility effect icon — toggles include/exclude list aura (on by default).
     */
    private void drawGlowToggleIcon(int x, int y, boolean glowEnabled) {
        try {
            int strokeColor = glowEnabled ? 0xFF55FF55 : 0xFFFF5555;
            if (glowEnabled) {
                MoreHudPotionIcons.drawPotionIconWithStroke(mc, Potion.invisibility, x, y, ROW_ACTION_ICON_SIZE,
                        strokeColor, 1.0F, 1.0F, 1.0F, 1.0F);
            } else {
                MoreHudPotionIcons.drawPotionIconWithStroke(mc, Potion.invisibility, x, y, ROW_ACTION_ICON_SIZE,
                        strokeColor, 0.45F, 0.45F, 0.45F, 0.55F);
            }
        } catch (Exception e) {
            String icon = glowEnabled ? EnumChatFormatting.GREEN + "G" : EnumChatFormatting.RED + "G";
            fontRendererObj.drawString(icon, x, y, 0xFFFFFF);
        }
    }

    /** Death-requeue row icon for {@link PlayerQueuerHelpGUI} (active / tracked state). */
    public static void drawDeathRequeuerIconForHelp(int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        drawDeathTrackIconAt(mc, mc.fontRendererObj, x, y, true);
    }

    /** Glow (invisibility) row icon for {@link PlayerQueuerHelpGUI} — enabled state as in the player list. */
    public static void drawGlowIconForHelp(int x, int y) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        try {
            MoreHudPotionIcons.drawPotionIconWithStroke(mc, Potion.invisibility, x, y, ROW_ACTION_ICON_SIZE,
                    0xFF55FF55, 1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Exception e) {
            mc.fontRendererObj.drawString(EnumChatFormatting.GREEN + "G", x, y, 0xFFFFFF);
        }
    }

    /**
     * Draws the death track icon at the specified position
     */
    private void drawDeathTrackIcon(int x, int y, boolean isTracked) {
        drawDeathTrackIconAt(mc, fontRendererObj, x, y, isTracked);
    }

    private static void drawDeathTrackIconAt(Minecraft mc, net.minecraft.client.gui.FontRenderer fr,
            int x, int y, boolean isTracked) {
        ensureDeathTargetIconLoaded();

        if (!deathTargetIconLoaded) {
            String icon = isTracked ? EnumChatFormatting.GREEN + "D" : EnumChatFormatting.GRAY + "D";
            fr.drawString(icon, x, y, 0xFFFFFF);
            return;
        }

        try {
            ResourceLocation texture;
            if (deathTargetIconUsingFallback) {
                texture = isTracked ? DEATH_TARGET_ACTIVE_ICON_FALLBACK : DEATH_TARGET_ICON_FALLBACK;
            } else {
                texture = isTracked ? DEATH_TARGET_ACTIVE_ICON : DEATH_TARGET_ICON;
            }
            mc.getTextureManager().bindTexture(texture);

            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, 10, 10, 16, 16);

            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        } catch (Exception e) {
            String icon = isTracked ? EnumChatFormatting.GREEN + "D" : EnumChatFormatting.GRAY + "D";
            fr.drawString(icon, x, y, 0xFFFFFF);
        }
    }
    
    /**
     * Ensures the death target icon texture is loaded with fallback mechanisms
     */
    private static void ensureDeathTargetIconLoaded() {
        if (deathTargetIconLoaded) return; // Already loaded successfully
        if (deathTargetIconTried) return; // Already tried and failed
        deathTargetIconTried = true;
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getResourceManager() == null) return;
        
        try {
            // Try loading from resource pack first
            mc.getResourceManager().getResource(DEATH_TARGET_ICON);
            mc.getResourceManager().getResource(DEATH_TARGET_ACTIVE_ICON);
            deathTargetIconLoaded = true;
            deathTargetIconUsingFallback = false;
            System.out.println("[PlayerQueuerGUI] Death target icons loaded from resource pack");
            return;
        } catch (Exception e) {
            System.out.println("[PlayerQueuerGUI] Could not load death target icons from resource pack: " + e.getMessage());
        }
        
        // Fallback: load from classpath
        try {
            java.io.InputStream stream1 = PlayerQueuerGUI.class.getResourceAsStream("/assets/gomod123/textures/deathtarget.png");
            java.io.InputStream stream2 = PlayerQueuerGUI.class.getResourceAsStream("/assets/gomod123/textures/deathtarget_active.png");
            
            if (stream1 != null && stream2 != null) {
                java.awt.image.BufferedImage img1 = javax.imageio.ImageIO.read(stream1);
                java.awt.image.BufferedImage img2 = javax.imageio.ImageIO.read(stream2);
                
                if (img1 != null && img2 != null) {
                    DynamicTexture dyn1 = new DynamicTexture(img1);
                    DynamicTexture dyn2 = new DynamicTexture(img2);
                    mc.getTextureManager().loadTexture(DEATH_TARGET_ICON_FALLBACK, dyn1);
                    mc.getTextureManager().loadTexture(DEATH_TARGET_ACTIVE_ICON_FALLBACK, dyn2);
                    deathTargetIconLoaded = true;
                    deathTargetIconUsingFallback = true;
                    System.out.println("[PlayerQueuerGUI] Death target icons loaded from fallback (runtime)");
                    try { stream1.close(); } catch (Exception ignored) {}
                    try { stream2.close(); } catch (Exception ignored) {}
                    return;
                }
            }
            System.out.println("[PlayerQueuerGUI] Failed to load death target icons from classpath");
        } catch (Throwable e) {
            System.out.println("[PlayerQueuerGUI] Error loading death target icons: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Draws the delete icon at the specified position
     */
    private void drawDeleteIcon(int x, int y) {
        // Ensure texture is loaded
        ensureDeleteIconLoaded();
        
        // Only draw if texture loaded successfully
        if (!deleteIconLoaded) {
            // Fallback to text if icon fails to load
            String deleteText = EnumChatFormatting.RED + "" + EnumChatFormatting.ITALIC + "Delete";
            fontRendererObj.drawString(deleteText, x, y, 0xFFFFFF);
            return;
        }
        
        try {
            // Bind the appropriate texture (resource pack or runtime fallback)
            mc.getTextureManager().bindTexture(deleteIconUsingFallback ? DELETE_ICON_FALLBACK : DELETE_ICON);
            
            // Save current state
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            
        // Draw the icon (scale down to fit within the hover area - 10x10 for tight fit)
        net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, deleteIconW, deleteIconH, 10, 10, deleteIconW, deleteIconH);
            
            // Restore state
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        } catch (Exception e) {
            // Fallback to text if rendering fails
            String deleteText = EnumChatFormatting.RED + "" + EnumChatFormatting.ITALIC + "Delete";
            fontRendererObj.drawString(deleteText, x, y, 0xFFFFFF);
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 15) {
            GuiTextField focused = null;
            if (includeField.isFocused()) {
                focused = includeField;
            } else if (excludeField.isFocused()) {
                focused = excludeField;
            }
            if (focused != null && tryTabCompletePregamePlayerName(focused)) {
                updateButtons();
                return;
            }
        } else {
            clearPregameNameTabCompletionState();
        }

        super.keyTyped(typedChar, keyCode);
        includeField.textboxKeyTyped(typedChar, keyCode);
        excludeField.textboxKeyTyped(typedChar, keyCode);

        // Handle ENTER key to add players
        if (keyCode == 28) { // ENTER key
            if (includeField.isFocused()) {
                String name = includeField.getText().trim();
                if (!name.isEmpty()) {
                    PlayerQueuer.addTargetSilent(name);
                    includeField.setText("");
                    refreshLists();
                    includeIndexOffset = 0;
                    updateButtons();
                }
            } else if (excludeField.isFocused()) {
                String name = excludeField.getText().trim();
                if (!name.isEmpty()) {
                    PlayerQueuer.addExcludeTargetSilent(name);
                    excludeField.setText("");
                    refreshLists();
                    excludeIndexOffset = 0;
                    updateButtons();
                }
            }
        }
        
        updateButtons();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0) {
            if (preStartSlider != null && preStartSlider.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
                this.selectedButton = preStartSlider;
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        int centerX = width / 2;
        int panelWidth = PANEL_BODY_WIDTH_PX;
        int gutter = 24;
        int leftX = centerX - panelWidth - gutter;
        int rightX = centerX + gutter;
        HudPositionFieldPair.mouseClicked(includeField, mouseX, mouseY, mouseButton, leftX, PANEL_TOP_Y,
                PANEL_FIELD_WIDTH_PX);
        HudPositionFieldPair.mouseClicked(excludeField, mouseX, mouseY, mouseButton, rightX, PANEL_TOP_Y,
                PANEL_FIELD_WIDTH_PX);
        
        // Handle clicks on player list items for deletion
        if (mouseButton == 0) { // Left click
            int panelHeight = pqPanelBodyHeightPx();
            int listTop = pqListTopPx();
            int listHeight = pqListHeightPx();
            
            // Check include list clicks
            if (mouseX >= leftX && mouseX <= leftX + panelWidth && mouseY >= listTop && mouseY <= listTop + listHeight) {
                handleListClick(includeList, leftX, listTop, panelWidth, listHeight, includeIndexOffset, true);
            }
            // Check exclude list clicks
            else if (mouseX >= rightX && mouseX <= rightX + panelWidth && mouseY >= listTop && mouseY <= listTop + listHeight) {
                handleListClick(excludeList, rightX, listTop, panelWidth, listHeight, excludeIndexOffset, false);
            }
        }
        
        updateButtons();
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (this.selectedButton instanceof PlayerQueuerPreStartSlider) {
            ((PlayerQueuerPreStartSlider) this.selectedButton).mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (this.selectedButton instanceof PlayerQueuerPreStartSlider) {
            this.selectedButton.mouseReleased(mouseX, mouseY);
            this.selectedButton = null;
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }
    
    private void handleListClick(List<String> items, int x, int y, int w, int h, int indexOffset, boolean isIncludeList) {
        if (items == null || items.isEmpty()) return;

        int maxStart = maxListScrollStart(items, h);
        int start = Math.max(0, Math.min(indexOffset, maxStart));

        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

        int yy = y;
        for (int i = start; i < items.size(); i++) {
            String playerName = items.get(i);
            int rowH = rowHeightForEntry(playerName);
            if (yy + rowH > y + h) {
                break;
            }

            if (mouseX >= x && mouseX <= x + w && mouseY >= yy && mouseY <= yy + rowH) {
                boolean dodgeListed = !isRuleRow(playerName) && PlayerQueuer.isExcludeTargetListed(playerName);
                int glowIconX = x + w - (dodgeListed ? ROW_GLOW_ICON_DODGE_X : ROW_GLOW_ICON_X);
                int deathTrackIconX = x + w - ROW_DEATH_ICON_X;
                int deleteIconX = x + w - ROW_DELETE_ICON_X;

                if (isRuleRow(playerName)) {
                    if (mouseX >= deleteIconX && mouseX <= deleteIconX + ROW_ACTION_ICON_SIZE) {
                        if (RULE_ALL_NICKS.equals(playerName)) {
                            if (isIncludeList) PlayerQueuer.setIncludeAllNicksSilent(false);
                            else PlayerQueuer.setExcludeAllNicksSilent(false);
                        } else {
                            if (isIncludeList) {
                                PlayerQueuer.setIncludeWlrThresholdSilent(false,
                                        PlayerQueuer.getIncludeWlrThreshold());
                            } else {
                                PlayerQueuer.setExcludeWlrThresholdSilent(false,
                                        PlayerQueuer.getExcludeWlrThreshold());
                            }
                        }
                        refreshLists();
                    }
                    updateButtons();
                    break;
                }

                if (mouseX >= glowIconX && mouseX <= glowIconX + ROW_ACTION_ICON_SIZE) {
                    boolean glowOn = PlayerQueuer.togglePlayerGlow(playerName);
                    String capitalizedName = getCapitalizedName(playerName);
                    if (glowOn) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Glow enabled for "
                                + EnumChatFormatting.YELLOW + capitalizedName);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Glow disabled for "
                                + EnumChatFormatting.YELLOW + capitalizedName);
                    }
                } else if (!PlayerQueuer.isExcludeTargetListed(playerName)
                        && mouseX >= deathTrackIconX && mouseX <= deathTrackIconX + ROW_ACTION_ICON_SIZE) {
                    boolean nowTracked = PlayerQueuer.toggleDeathTracking(playerName);
                    String capitalizedName = getCapitalizedName(playerName);
                    if (nowTracked) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Player requeuer enabled for " + EnumChatFormatting.YELLOW + capitalizedName + 
                                       EnumChatFormatting.GRAY + " - will requeue when they die or win");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player requeuer disabled for " + EnumChatFormatting.YELLOW + capitalizedName);
                    }
                } else if (mouseX >= deleteIconX && mouseX <= deleteIconX + ROW_ACTION_ICON_SIZE) {
                    if (isIncludeList) {
                        PlayerQueuer.removeTargetSilent(playerName);
                        refreshLists();
                        includeIndexOffset = 0;
                    } else {
                        PlayerQueuer.removeExcludeTargetSilent(playerName);
                        refreshLists();
                        excludeIndexOffset = 0;
                    }
                }
                updateButtons();
                break;
            }
            yy += rowH;
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = org.lwjgl.input.Mouse.getEventDWheel();
        if (d != 0) {
            int panelWidth = PANEL_BODY_WIDTH_PX;
            int panelHeight = pqPanelBodyHeightPx();
            int gutter = 24;
            int centerX = width / 2;
            int leftX = centerX - panelWidth - gutter;
            int rightX = centerX + gutter;
            int topY = PANEL_TOP_Y;
            int listTop = pqListTopPx();
            int listHeight = pqListHeightPx();
            int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

            // Determine which panel mouse is over
            boolean overInclude = mouseX >= leftX && mouseX <= leftX + panelWidth && mouseY >= listTop && mouseY <= listTop + listHeight;
            boolean overExclude = mouseX >= rightX && mouseX <= rightX + panelWidth && mouseY >= listTop && mouseY <= listTop + listHeight;
            int deltaRows = d > 0 ? -SCROLL_ROWS_STEP : SCROLL_ROWS_STEP;
            if (overInclude) {
                int maxStart = maxListScrollStart(includeList, listHeight);
                includeIndexOffset = Math.max(0, Math.min(maxStart, includeIndexOffset + deltaRows));
            } else if (overExclude) {
                int maxStart = maxListScrollStart(excludeList, listHeight);
                excludeIndexOffset = Math.max(0, Math.min(maxStart, excludeIndexOffset + deltaRows));
            }
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button == backButton) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button == helpButton) {
            mc.displayGuiScreen(new PlayerQueuerHelpGUI(this));
            return;
        }
        if (button == toggleButton) {
            boolean now = !PlayerQueuer.isEnabled();
            PlayerQueuer.setEnabledSilent(now);
            updateButtons();
            return;
        }
        if (button == includeAddButton) {
            String name = includeField.getText().trim();
            if (!name.isEmpty()) {
                PlayerQueuer.addTargetSilent(name);
                includeField.setText("");
                refreshLists();
                includeIndexOffset = 0;
                updateButtons();
            }
            return;
        }
        if (button == includeClearButton) {
            confirmClearAll(true);
            return;
        }
        if (button == includeModeButton) {
            PlayerQueuer.setRequireAllIncludeSilent(!PlayerQueuer.isRequireAllInclude());
            includeModeButton.displayString = PlayerQueuer.isRequireAllInclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY";
            updateButtons();
            return;
        }
        if (button == includeRulesButton) {
            mc.displayGuiScreen(new PlayerQueuerIncludeRulesGUI(this, false));
            return;
        }
        if (button == excludeRulesButton) {
            mc.displayGuiScreen(new PlayerQueuerIncludeRulesGUI(this, true));
            return;
        }
        if (button == excludeModeButton) {
            PlayerQueuer.setRequireAllExcludeSilent(!PlayerQueuer.isRequireAllExclude());
            excludeModeButton.displayString = PlayerQueuer.isRequireAllExclude() ? EnumChatFormatting.GOLD + "ALL" : EnumChatFormatting.GREEN + "ANY";
            updateButtons();
            return;
        }
        if (button == excludeAddButton) { // ExAdd
            String name = excludeField.getText().trim();
            if (!name.isEmpty()) {
                PlayerQueuer.addExcludeTargetSilent(name);
                excludeField.setText("");
                refreshLists();
                excludeIndexOffset = 0;
                updateButtons();
            }
            return;
        }
        if (button == excludeClearButton) {
            confirmClearAll(false);
            return;
        }
    }

    private void confirmClearAll(boolean clearInclude) {
        refreshLists();
        int count = clearInclude ? PlayerQueuer.getTargets().size() : PlayerQueuer.getExcludeTargets().size();
        String title = clearInclude
                ? EnumChatFormatting.RED + "Clear All Queue Players?"
                : EnumChatFormatting.RED + "Clear All Dodge Players?";
        String listLabel = clearInclude ? "queue" : "dodge";
        String body = EnumChatFormatting.YELLOW + "This will permanently remove ALL players from your " + listLabel
                + " list.\n\n"
                + EnumChatFormatting.WHITE + "Players removed: " + EnumChatFormatting.RED + count + "\n"
                + EnumChatFormatting.GRAY + "This action cannot be undone!";
        mc.displayGuiScreen(new GomodConfirmDialog(
                (confirmed, id) -> {
                    if (confirmed) {
                        if (clearInclude) {
                            PlayerQueuer.clearTargetsSilent();
                            includeIndexOffset = 0;
                        } else {
                            PlayerQueuer.clearExcludeTargetsSilent();
                            excludeIndexOffset = 0;
                        }
                        refreshLists();
                        updateButtons();
                    }
                    mc.displayGuiScreen(this);
                },
                title,
                body,
                0));
    }

    private void clearPregameNameTabCompletionState() {
        pregameNameTabCache.clear();
        pregameNameTabIndex = 0;
        pregameNameTabPrefix = "";
        pregameNameTabField = null;
    }

    /**
     * TAB cycles IGNs from the current Blitz pregame (world + tab list). Returns true if handled.
     */
    private boolean tryTabCompletePregamePlayerName(GuiTextField field) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !GameContext.isBlitzPregameLobby(mc)) {
            return false;
        }

        String current = field.getText();
        if (current == null) {
            current = "";
        }
        String prefix = current.trim();

        if (field != pregameNameTabField || !prefix.equalsIgnoreCase(pregameNameTabPrefix)) {
            pregameNameTabCache = collectPregamePlayerNames(prefix);
            pregameNameTabIndex = 0;
            pregameNameTabPrefix = prefix;
            pregameNameTabField = field;
        }

        if (pregameNameTabCache.isEmpty()) {
            return false;
        }

        if (!prefix.isEmpty()) {
            for (int i = 0; i < pregameNameTabCache.size(); i++) {
                if (pregameNameTabCache.get(i).equalsIgnoreCase(prefix)) {
                    pregameNameTabIndex = (i + 1) % pregameNameTabCache.size();
                    break;
                }
            }
        }

        String next = pregameNameTabCache.get(pregameNameTabIndex);
        pregameNameTabIndex = (pregameNameTabIndex + 1) % pregameNameTabCache.size();
        field.setText(next);
        field.setCursorPosition(next.length());
        return true;
    }

    private static List<String> collectPregamePlayerNames(String prefix) {
        List<String> players = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !GameContext.isBlitzPregameLobby(mc)) {
            return players;
        }

        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();

        if (mc.theWorld != null) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == null || player == mc.thePlayer) {
                    continue;
                }
                addPregameNameIfMatches(players, seen, player.getName(), lowerPrefix);
            }
        }

        if (mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfoMap() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info == null || info.getGameProfile() == null) {
                    continue;
                }
                addPregameNameIfMatches(players, seen, info.getGameProfile().getName(), lowerPrefix);
            }
        }

        Collections.sort(players, String.CASE_INSENSITIVE_ORDER);
        return players;
    }

    private static void addPregameNameIfMatches(List<String> out, Set<String> seen, String raw, String lowerPrefix) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        if (plain == null) {
            return;
        }
        plain = plain.trim();
        if (plain.isEmpty() || plain.length() > 16) {
            return;
        }
        String key = plain.toLowerCase(Locale.ROOT);
        if (seen.contains(key)) {
            return;
        }
        if (!lowerPrefix.isEmpty() && !key.startsWith(lowerPrefix)) {
            return;
        }
        seen.add(key);
        out.add(plain);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(includeField, excludeField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Gets the properly capitalized player name from cache or Mojang API
     */
    private String getCapitalizedName(String lowercaseName) {
        if (lowercaseName == null) return "Unknown";
        String key = lowercaseName.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return "";
        
        // Check cache first
        if (playerNameCache.containsKey(key)) {
            return playerNameCache.get(key);
        }
        if (playerNameFetchInFlight.add(key)) {
            new Thread(() -> {
                try {
                    MojangUsernameResolver.MojangProfile profile = MojangUsernameResolver.resolveProfile(key);
                    if (profile != null) {
                        if (profile.name != null && !profile.name.isEmpty()) {
                            playerNameCache.put(key, profile.name);
                        }
                        if (profile.id != null && !profile.id.isEmpty()) {
                            playerUUIDCache.put(key, profile.id);
                        }
                    }
                } finally {
                    playerNameFetchInFlight.remove(key);
                }
            }, "PlayerQueuer-NameFetch").start();
        }
        
        // Return capitalized version as fallback
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }
    
    /**
     * Gets player UUID from cache or Mojang API
     */
    private String getPlayerUUID(String lowercaseName) {
        if (lowercaseName == null) return "00000000000000000000000000000000";
        String key = lowercaseName.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return "00000000000000000000000000000000";
        
        // Check cache
        if (playerUUIDCache.containsKey(key)) {
            return playerUUIDCache.get(key);
        }
        
        // Return Steve head as fallback while fetching
        return "00000000000000000000000000000000";
    }
    
    /**
     * Draws a player head using Minotar
     */
    private void drawPlayerHead(String uuid, int x, int y, int size) {
        try {
            if (uuid == null || uuid.isEmpty()) {
                uuid = "00000000000000000000000000000000"; // fallback steve
            } else {
                // Remove dashes for Minotar
                uuid = uuid.replace("-", "");
            }
            
            // Use Minotar instead of Crafatar
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
            
            // Save and set proper render state
            float z = this.zLevel;
            this.zLevel = 50.0F;
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            
            // Draw the head
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
            
            // Restore state
            GlStateManager.popMatrix();
            this.zLevel = z;
        } catch (Exception e) {
            // Silently fail - no head will be drawn
        }
    }

}


