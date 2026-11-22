package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.BlitzChestItemsCombatTier;
import me.napim.gomod.features.BlitzChestItemsData;
import me.napim.gomod.features.BlitzVaultItemsData;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.util.HudGuiItemIcon;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Browser for Blitz survival-games loot chest items (from {@code blitz_chest_dump_*.txt} merges).
 */
public final class BlitzChestItemsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final Gui TEXTURE_DRAW = new Gui() {};
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 2;
    private static final int GRID_PAD_X = 16;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLLBAR_GAP = 8;
    private static final int CHEST_SIDE_PANEL_GAP = 12;
    private static final int CHEST_SORT_ROW_H = 18;
    private static final int CHEST_SORT_ROW_GAP = 2;
    private static final int CHEST_SORT_ICON_DRAW = 11;
    private static final int CHEST_SORT_ICON_GAP = 1;
    private static final int CHEST_SORT_ICON_MAX = 8;
    private static final int CHEST_SORT_ICONS_RESERVE_W =
            CHEST_SORT_ICON_DRAW * CHEST_SORT_ICON_MAX + CHEST_SORT_ICON_GAP * (CHEST_SORT_ICON_MAX - 1);
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 4;
    private static final int CONTROL_PILL_H = 14;
    private static final int CONTROL_PILL_PAD_X = 6;
    private static final int CONTROL_PILL_GAP = 3;
    private static final int TABS_TO_CONTROLS_GAP = 4;
    private static final int CONTROLS_TO_SUMMARY_GAP = 6;
    private static final int CONTROL_PILLS_LEFT = 12;
    private static final int SUMMARY_HEIGHT = 112;
    private static final int SUMMARY_GAP = 8;
    private static final int SUMMARY_BAR_H = 4;
    /** Row height; must fit {@link #OBSERVED_ICON_CELL} (same 18px slot as the main grid). */
    private static final int OBSERVED_LINE_H = 18;
    /** Icon cell width/height — matches {@link #SLOT_SIZE} so stack overlays align. */
    private static final int OBSERVED_ICON_CELL = SLOT_SIZE;
    private static final int LOOT_MIX_LEGEND_LINE_H = 10;
    private static final int OBSERVED_ICON_GAP = 4;
    private static final int OBSERVED_ROW_PAD = 6;
    private static final int OBSERVED_LABEL_BAR_GAP = 10;
    private static final int OBSERVED_BAR_MIN_W = 28;
    private static final int OBSERVED_BAR_RIGHT_TRIM = 4;
    /** Widest C column so percentages line up; bar start comes from global max label width. */
    private static final String OBSERVED_CHEST_SLOT = "C 100.0% ";

    private static final int CHEST_BLOCK_HEADER_H = 14;
    private static final int CHEST_BLOCK_PAD_BOTTOM = 10;

    private static final int COL_PANEL_BG = 0xC8101816;
    private static final int COL_PANEL_BORDER = 0xFF2D6B2D;
    private static final int COL_SLOT_BG = 0xFF2A2A2A;
    private static final int COL_SLOT_HOVER = 0xFF6BC36B;
    private static final int COL_SLOT_PREF_BG = 0xFF214521;
    private static final int COL_SLOT_PREF_HOVER = 0xFF63C963;
    /** Muted blue outer ring for catalog variant rows (same weight as {@link #COL_SLOT_PREF_BG}). */
    private static final int COL_SLOT_VARIANT_BG = 0xFF1E3540;
    private static final int COL_SLOT_VARIANT_HOVER = 0xFF4A8FA0;
    private static final int COL_TAB_BG = 0xC0182218;
    private static final int COL_TAB_BG_HOVER = 0xC02A4A2A;
    private static final int COL_TAB_BG_ACTIVE = 0xD03D8B3D;
    private static final int COL_TAB_BORDER = 0xFF2D6B2D;
    private static final int COL_TAB_BORDER_ACTIVE = 0xFF8DDC8D;
    private static final int COL_BAR_BG = 0xD0081808;
    private static final int COL_BAR_BORDER = 0xFF2D6B2D;
    private static final int COL_BAR_FOCUS = 0xFF8DDC8D;
    private static final int COL_SUMMARY_BG = 0xC0121A12;
    private static final int COL_SUMMARY_BORDER = 0xFF2D6B2D;
    /** Faint outline only — empty bar area shows panel background through it. */
    private static final int COL_BAR_OUTLINE = 0x354A5048;
    private static final int COL_BAR_FILL_CHEST = 0xFF5CB85C;

    private static final String FILTER_ALL = "All";
    private static final String FILTER_CHESTS = "Chests";

    private enum ChestSortMode {
        CHEST_NUMBER("Chest #"),
        ITEM_COUNT("Item count"),
        ANY_ARMOR("Any Armor"),
        IRON_ARMOR("Iron Armor"),
        CHAINMAIL_ARMOR("Chainmail Armor"),
        GOLD_ARMOR("Gold Armor"),
        LEATHER_ARMOR("Leather Armor"),
        WEAPONS("Weapons"),
        FOOD("Food"),
        MATERIALS("Materials"),
        ENCHANT_BOTTLES("Enchant bottles");

        final String label;

        ChestSortMode(String label) {
            this.label = label;
        }

        static ChestSortMode fromConfig(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return CHEST_NUMBER;
            }
            String normalized = raw.trim().toUpperCase(Locale.US);
            if ("BOWS".equals(normalized)) {
                return WEAPONS;
            }
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return CHEST_NUMBER;
            }
        }

        ItemStack[] sortIconStacks() {
            switch (this) {
                case CHEST_NUMBER:
                    return stackArray(new ItemStack(Blocks.chest));
                case ITEM_COUNT:
                    return stackArray(new ItemStack(Blocks.chest), new ItemStack(Blocks.chest),
                            new ItemStack(Blocks.chest));
                case ANY_ARMOR:
                    return stacks(Items.leather_helmet, Items.iron_chestplate,
                            Items.chainmail_leggings, Items.golden_boots);
                case IRON_ARMOR:
                    return stacks(Items.iron_helmet, Items.iron_chestplate, Items.iron_leggings, Items.iron_boots);
                case CHAINMAIL_ARMOR:
                    return stacks(Items.chainmail_helmet, Items.chainmail_chestplate, Items.chainmail_leggings, Items.chainmail_boots);
                case GOLD_ARMOR:
                    return stacks(Items.golden_helmet, Items.golden_chestplate, Items.golden_leggings, Items.golden_boots);
                case LEATHER_ARMOR:
                    return stacks(Items.leather_helmet, Items.leather_chestplate, Items.leather_leggings, Items.leather_boots);
                case WEAPONS:
                    return stacks(Items.stone_sword, Items.wooden_sword, Items.golden_sword, Items.stone_axe);
                case FOOD:
                    return stacks(Items.cooked_beef, Items.cooked_porkchop, Items.cooked_fish,
                            Items.beef, Items.porkchop, Items.fish, Items.golden_apple, Items.apple);
                case MATERIALS:
                    return stacks(Items.diamond, Items.iron_ingot, Items.gold_ingot, Items.stick);
                case ENCHANT_BOTTLES:
                    return stackArray(new ItemStack(Items.experience_bottle, 1),
                            new ItemStack(Items.experience_bottle, 2));
                default:
                    return new ItemStack[0];
            }
        }

        private static ItemStack[] stacks(Item... items) {
            if (items == null || items.length == 0) {
                return new ItemStack[0];
            }
            ItemStack[] out = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                out[i] = items[i] == null ? null : new ItemStack(items[i]);
            }
            return out;
        }

        private static ItemStack[] stackArray(ItemStack... stacks) {
            return stacks == null ? new ItemStack[0] : stacks;
        }
    }

    private final GuiScreen parent;
    private final List<TabRect> tabs = new ArrayList<TabRect>();
    private GuiButton backButton;
    private GuiTextField searchField;

    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private final GuiSmoothScroll chestScroll = new GuiSmoothScroll();
    private final GuiSmoothScroll observedChancesScroll = new GuiSmoothScroll();

    private boolean dataInitComplete;
    private final Set<String> activeFilters = new LinkedHashSet<String>();
    private String lastFilterSignature = "";
    private boolean aggregateVariants = false;
    private boolean aggregateCombatTier = false;
    private boolean chestBrowserMode;
    private ChestSortMode chestSortMode = ChestSortMode.CHEST_NUMBER;
    private String lastChestSortCacheKey = "__init__";
    private final List<ChestSortRowRect> chestSortRows = new ArrayList<ChestSortRowRect>();
    private int chestSortScrollPx;
    private int chestSortMaxScrollPx;
    private int chestSortListX;
    private int chestSortListY;
    private int chestSortListW;
    private int chestSortListH;
    private int chestGalleryX;
    private int chestGalleryY;
    private int chestGalleryW;
    private int chestGalleryH;
    private int chestGalleryContentH;

    private List<BlitzChestItemsData.Entry> filtered = Collections.emptyList();
    private BlitzChestItemsData.Entry hoveredEntry;
    private BlitzChestItemsData.ChestSlot hoveredChestSlot;
    private List<BlitzChestItemsData.LoggedChest> sortedChests = Collections.emptyList();
    private List<BlitzChestItemsData.Entry> cachedTopObserved = Collections.emptyList();
    private List<CategorySlice> cachedCategorySlices = Collections.emptyList();
    private String lastSummaryCacheKey = "";
    private int cachedSummaryTextColumnW = 0;
    private String lastSummaryBarLayoutKey = "";

    private int gridLeft;
    private int gridTop;
    private int gridWidth;
    private int gridVisibleHeight;
    private int gridContentHeight;
    private int columns;
    private int sortPillX;
    private int sortPillY;
    private int sortPillW;
    private int aggregatePillX;
    private int aggregatePillY;
    private int aggregatePillW;
    private int combatTierPillX;
    private int combatTierPillY;
    private int combatTierPillW;
    private int observedSummaryPanelX;
    private int observedSummaryPanelY;
    private int observedSummaryPanelW;
    private int observedSummaryPanelH;

    public BlitzChestItemsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (!GomodInternalFeatures.isBlitzChestItemsBrowserEnabled()) {
            if (parent != null) {
                mc.displayGuiScreen(parent);
            } else {
                mc.displayGuiScreen(null);
            }
            return;
        }
        super.initGui();
        if (activeFilters.isEmpty()) {
            setAllCategoryFilters();
        }
        scroll.setWheelTuning(72, 3600, 120);
        // Chest contents gallery: gentler wheel (smaller impulse / queue than the item grid).
        chestScroll.setWheelTuning(24, 1200, 64);
        BlitzChestItemsData.scheduleWarmup();
        aggregateVariants = ConfigManager.isBlitzChestItemsAggregateVariants();
        aggregateCombatTier = ConfigManager.isBlitzChestItemsAggregateCombatTier();
        chestSortMode = ChestSortMode.fromConfig(ConfigManager.getBlitzChestItemsChestSortMode());
        ScaledResolution sr = new ScaledResolution(mc);
        int centerX = sr.getScaledWidth() / 2;
        int searchWidth = Math.min(260, sr.getScaledWidth() - 80);
        int searchX = centerX - searchWidth / 2;
        int searchY = 60;
        searchField = new GuiTextField(910, fontRendererObj, searchX + 14, searchY + 4, searchWidth - 18, 12);
        searchField.setMaxStringLength(48);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setTextColor(0xE8FFE8);
        searchField.setDisabledTextColour(0x668866);
        int backY = GuiHeader.backButtonY(sr.getScaledHeight(), BUTTON_HEIGHT);
        backButton = new GuiButton(0, centerX - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");
        buttonList.add(backButton);
        tryCompleteDataInit(sr);
    }

    private void tryCompleteDataInit(ScaledResolution sr) {
        BlitzChestItemsData.pumpBuildBatch(BlitzChestItemsData.BUILD_BATCH_GUI);
        if (!BlitzChestItemsData.isReady()) {
            dataInitComplete = false;
            return;
        }
        if (dataInitComplete) {
            layoutTabs(sr);
            layoutGrid(sr);
            rebuildFilteredIfNeeded();
            return;
        }
        dataInitComplete = true;
        rebuildFilteredIfNeeded();
        rebuildSortedChestsIfNeeded();
        layoutTabs(sr);
        layoutGrid(sr);
    }

    private void layoutTabs(ScaledResolution sr) {
        tabs.clear();
        int centerX = sr.getScaledWidth() / 2;
        int tabsY = 82;
        List<String> categories = new ArrayList<String>();
        categories.add(FILTER_ALL);
        categories.add(FILTER_CHESTS);
        for (String cat : BlitzChestItemsData.getCategoryOrder()) {
            if (BlitzChestItemsData.getCategoryCount(cat) > 0) {
                categories.add(cat);
            }
        }
        if (categories.isEmpty()) {
            return;
        }
        int firstRowCount = Math.min(2, categories.size());
        addCenteredTabRow(categories.subList(0, firstRowCount), centerX, tabsY);
        if (categories.size() > firstRowCount) {
            addWrappedCenteredTabRows(categories.subList(firstRowCount, categories.size()),
                    centerX, tabsY + TAB_HEIGHT + TAB_GAP, sr.getScaledWidth() - 24);
        }
        if (!tabs.isEmpty()) {
            int pillsTop = tabsBottomY() + TABS_TO_CONTROLS_GAP;
            String sortText = getSortPillText();
            sortPillW = fontRendererObj.getStringWidth(sortText) + CONTROL_PILL_PAD_X * 2;
            sortPillX = CONTROL_PILLS_LEFT;
            sortPillY = pillsTop;
            String aggregateText = getAggregatePillText();
            aggregatePillW = fontRendererObj.getStringWidth(aggregateText) + CONTROL_PILL_PAD_X * 2;
            aggregatePillX = sortPillX + sortPillW + CONTROL_PILL_GAP;
            aggregatePillY = pillsTop;
            String combatTierText = getCombatTierPillText();
            combatTierPillW = fontRendererObj.getStringWidth(combatTierText) + CONTROL_PILL_PAD_X * 2;
            combatTierPillX = aggregatePillX + aggregatePillW + CONTROL_PILL_GAP;
            combatTierPillY = pillsTop;
        }
    }

    private int tabsBottomY() {
        if (tabs.isEmpty()) {
            return 82 + TAB_HEIGHT;
        }
        TabRect anchor = tabs.get(tabs.size() - 1);
        return anchor.y + TAB_HEIGHT;
    }

    private int controlPillsBlockHeight() {
        return CONTROL_PILL_H;
    }

    private void addWrappedCenteredTabRows(List<String> categories, int centerX, int startY, int maxRowWidth) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        List<String> row = new ArrayList<String>();
        int rowWidth = 0;
        int y = startY;
        for (String cat : categories) {
            int tabWidth = getTabWidth(cat);
            int nextWidth = row.isEmpty() ? tabWidth : rowWidth + TAB_GAP + tabWidth;
            if (!row.isEmpty() && nextWidth > maxRowWidth) {
                addCenteredTabRow(row, centerX, y);
                row.clear();
                y += TAB_HEIGHT + TAB_GAP;
                rowWidth = 0;
            }
            row.add(cat);
            rowWidth = rowWidth == 0 ? tabWidth : rowWidth + TAB_GAP + tabWidth;
        }
        if (!row.isEmpty()) {
            addCenteredTabRow(row, centerX, y);
        }
    }

    private void addCenteredTabRow(List<String> categories, int centerX, int y) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        int totalWidth = 0;
        for (int i = 0; i < categories.size(); i++) {
            totalWidth += getTabWidth(categories.get(i));
            if (i > 0) {
                totalWidth += TAB_GAP;
            }
        }
        int x = centerX - totalWidth / 2;
        for (String cat : categories) {
            int countSuffix = tabCountForLabel(cat);
            int w = getTabWidth(cat);
            tabs.add(new TabRect(cat, countSuffix, x, y, w, TAB_HEIGHT));
            x += w + TAB_GAP;
        }
    }

    private int tabCountForLabel(String label) {
        if (FILTER_ALL.equals(label)) {
            return BlitzChestItemsData.getAll().size();
        }
        if (FILTER_CHESTS.equals(label)) {
            return BlitzChestItemsData.getLoggedChestCount();
        }
        return BlitzChestItemsData.getCategoryCount(label);
    }

    private int getTabWidth(String label) {
        return fontRendererObj.getStringWidth(label + " " + tabCountForLabel(label)) + 14;
    }

    private int getSummaryTopY() {
        return tabsBottomY() + TABS_TO_CONTROLS_GAP + controlPillsBlockHeight() + CONTROLS_TO_SUMMARY_GAP;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(mc);
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        if (!BlitzChestItemsData.isReady()) {
            drawLoadingScreen(sr);
            tryCompleteDataInit(sr);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        tryCompleteDataInit(sr);

        hoveredEntry = null;
        hoveredChestSlot = null;

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Blitz Chest Items"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(),
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawSearchBar(mouseX, mouseY);
        drawTabs(mouseX, mouseY);
        rebuildSummaryCachesIfNeeded();
        drawSummaryPanels(sr, mouseX, mouseY);

        if (chestBrowserMode) {
            drawChestBrowser(sr, mouseX, mouseY);
            drawChestSortPanel(mouseX, mouseY);
        } else {
            drawItemGrid(mouseX, mouseY);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawCombatTierPillHoverTooltip(mouseX, mouseY);
        drawAggregateVariantsPillHoverTooltip(mouseX, mouseY);

        if (hoveredChestSlot != null && chestBrowserMode) {
            drawChestSlotTooltip(hoveredChestSlot, mouseX, mouseY);
        } else if (hoveredEntry != null && !chestBrowserMode) {
            drawEntryTooltip(hoveredEntry, mouseX, mouseY);
        }
    }

    private void drawLoadingScreen(ScaledResolution sr) {
        int pct = Math.round(BlitzChestItemsData.getLoadProgress() * 100f);
        String line = EnumChatFormatting.GREEN + "Loading chest item database... " + pct + "%";
        int tw = fontRendererObj.getStringWidth(line);
        fontRendererObj.drawStringWithShadow(line, sr.getScaledWidth() / 2 - tw / 2,
                sr.getScaledHeight() / 2 - 4, 0xFFFFFFFF);
    }

    private void drawSearchBar(int mouseX, int mouseY) {
        if (searchField == null) {
            return;
        }
        int barX = searchField.xPosition - 14;
        int barY = searchField.yPosition - 4;
        int barW = searchField.width + 18;
        int barH = 20;
        Gui.drawRect(barX, barY, barX + barW, barY + barH, COL_BAR_BG);
        int border = searchField.isFocused() ? COL_BAR_FOCUS : COL_BAR_BORDER;
        drawHollowBorder(barX, barY, barX + barW, barY + barH, border);
        GomodSearchBar.drawMagnifierIcon(barX + 8, barY + barH / 2,
                searchField.isFocused() ? 0xFFAAFFAA : 0xFF66BB66);
        if (!searchField.isFocused() && (searchField.getText() == null || searchField.getText().isEmpty())) {
            fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Search food, materials, gear...",
                    searchField.xPosition + 1, searchField.yPosition + 2, 0xFFFFFFFF);
        }
        searchField.drawTextBox();
    }

    private void drawTabs(int mouseX, int mouseY) {
        for (TabRect tab : tabs) {
            boolean active = FILTER_CHESTS.equals(tab.label) ? chestBrowserMode
                    : (!chestBrowserMode && isTabActive(tab.label));
            boolean hover = mouseX >= tab.x && mouseX < tab.x + tab.w && mouseY >= tab.y && mouseY < tab.y + tab.h;
            int bg = active ? COL_TAB_BG_ACTIVE : (hover ? COL_TAB_BG_HOVER : COL_TAB_BG);
            int border = active ? COL_TAB_BORDER_ACTIVE : COL_TAB_BORDER;
            Gui.drawRect(tab.x, tab.y, tab.x + tab.w, tab.y + tab.h, bg);
            drawHollowBorder(tab.x, tab.y, tab.x + tab.w, tab.y + tab.h, border);
            String text = (active ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY) + tab.label
                    + " " + EnumChatFormatting.DARK_GRAY + tab.count;
            int tw = fontRendererObj.getStringWidth(text);
            fontRendererObj.drawStringWithShadow(text, tab.x + (tab.w - tw) / 2, tab.y + 5, 0xFFFFFFFF);
        }
        String sortText = getSortPillText();
        drawControlPill(sortPillX, sortPillY, sortPillW, CONTROL_PILL_H, sortText, mouseX, mouseY, true);
        drawControlPill(aggregatePillX, aggregatePillY, aggregatePillW, CONTROL_PILL_H,
                getAggregatePillText(), mouseX, mouseY, aggregateVariants);
        drawControlPill(combatTierPillX, combatTierPillY, combatTierPillW, CONTROL_PILL_H,
                getCombatTierPillText(), mouseX, mouseY, aggregateCombatTier);
    }

    private String getSortPillText() {
        String mode = ConfigManager.getBlitzChestItemsSortMode();
        if ("MOST_OBSERVED".equals(mode)) {
            return EnumChatFormatting.GREEN + "Sort: Most seen";
        }
        if ("CATEGORY".equals(mode)) {
            return EnumChatFormatting.AQUA + "Sort: Category";
        }
        return EnumChatFormatting.WHITE + "Sort: Default";
    }

    private String getAggregatePillText() {
        return aggregateVariants
                ? EnumChatFormatting.GREEN + "Combine variants: On"
                : EnumChatFormatting.GRAY + "Combine variants: Off";
    }

    private String getCombatTierPillText() {
        return aggregateCombatTier
                ? EnumChatFormatting.GREEN + "Combine tier: On"
                : EnumChatFormatting.GRAY + "Combine tier: Off";
    }

    private void drawCombatTierPillHoverTooltip(int mouseX, int mouseY) {
        if (mouseX < combatTierPillX || mouseX >= combatTierPillX + combatTierPillW
                || mouseY < combatTierPillY || mouseY >= combatTierPillY + CONTROL_PILL_H) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        for (String line : BlitzChestItemsCombatTier.controlPillTooltipLines()) {
            lines.add(EnumChatFormatting.GRAY + line);
        }
        drawItemHoverTooltip(lines, mouseX, mouseY);
    }

    private void drawAggregateVariantsPillHoverTooltip(int mouseX, int mouseY) {
        if (mouseX < aggregatePillX || mouseX >= aggregatePillX + aggregatePillW
                || mouseY < aggregatePillY || mouseY >= aggregatePillY + CONTROL_PILL_H) {
            return;
        }
        List<String> lines = new ArrayList<String>();
        for (String line : BlitzChestItemsData.aggregateVariantsControlPillTooltipLines()) {
            lines.add(EnumChatFormatting.GRAY + line);
        }
        drawItemHoverTooltip(lines, mouseX, mouseY);
    }

    private void drawControlPill(int x, int y, int w, int h, String text, int mouseX, int mouseY, boolean active) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int fill = active ? (hover ? 0x605A6F84 : 0x90242424) : 0xA0141414;
        drawWidgetChrome(x, y, w, h, hover, active, fill);
        int tw = fontRendererObj.getStringWidth(text);
        fontRendererObj.drawStringWithShadow(text, x + (w - tw) / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    private void drawWidgetChrome(int x, int y, int w, int h, boolean mouseOver, boolean active, int fill) {
        HudGlState.restoreForOverlayText();
        int hoverState = active ? (mouseOver ? 2 : 1) : 0;
        int texV = 46 + hoverState * 20;
        float alpha = active ? 1.0F : 0.5F;

        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.getTextureManager().bindTexture(WIDGETS);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        int half = w / 2;
        int rightW = w - half;
        TEXTURE_DRAW.drawTexturedModalRect(x, y, 0, texV, half, h);
        TEXTURE_DRAW.drawTexturedModalRect(x + half, y, 200 - rightW, texV, rightW, h);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        int inset = 1;
        int l = x + inset;
        int t = y + inset;
        int r = x + w - inset;
        int b = y + h - inset;
        Gui.drawRect(l, t, r, b, fill);
        int stroke = mouseOver && active ? 0xFFB9D8F2 : 0xFF000000;
        Gui.drawRect(l - 1, t - 1, r + 1, t, stroke);
        Gui.drawRect(l - 1, b, r + 1, b + 1, stroke);
        Gui.drawRect(l - 1, t, l, b, stroke);
        Gui.drawRect(r, t, r + 1, b, stroke);
    }

    private void cycleSortMode() {
        String mode = ConfigManager.getBlitzChestItemsSortMode();
        if ("MOST_OBSERVED".equals(mode)) {
            mode = "CATEGORY";
        } else if ("CATEGORY".equals(mode)) {
            mode = "DEFAULT";
        } else {
            mode = "MOST_OBSERVED";
        }
        ConfigManager.setBlitzChestItemsSortMode(mode);
    }

    private List<BlitzChestItemsData.Entry> applyCatalogAggregates(List<BlitzChestItemsData.Entry> entries) {
        List<BlitzChestItemsData.Entry> next = new ArrayList<BlitzChestItemsData.Entry>(entries);
        if (aggregateVariants) {
            next = BlitzChestItemsData.aggregateByItemType(next);
        }
        if (aggregateCombatTier) {
            next = BlitzChestItemsData.aggregateByCombatTier(next);
        }
        return next;
    }

    private List<BlitzChestItemsData.Entry> applyDisplayProcessing(List<BlitzChestItemsData.Entry> entries) {
        List<BlitzChestItemsData.Entry> next = applyCatalogAggregates(entries);
        String sortMode = ConfigManager.getBlitzChestItemsSortMode();
        if ("MOST_OBSERVED".equals(sortMode)) {
            Collections.sort(next, new Comparator<BlitzChestItemsData.Entry>() {
                @Override
                public int compare(BlitzChestItemsData.Entry a, BlitzChestItemsData.Entry b) {
                    int c = Integer.compare(b.observedCount, a.observedCount);
                    return c != 0 ? c : a.id.compareTo(b.id);
                }
            });
        } else if ("CATEGORY".equals(sortMode)) {
            Collections.sort(next, BlitzChestItemsData.getCategorySortComparator());
        }
        return next;
    }

    private void setAllCategoryFilters() {
        activeFilters.clear();
        activeFilters.add(FILTER_ALL);
    }

    private boolean isAllCategoryFilterActive() {
        return activeFilters.isEmpty() || activeFilters.contains(FILTER_ALL);
    }

    private boolean isCategoryFilterActive(String category) {
        if (FILTER_ALL.equals(category)) {
            return isAllCategoryFilterActive();
        }
        return activeFilters.contains(category);
    }

    private void toggleCategoryFilter(String category) {
        if (category == null || category.trim().isEmpty()) {
            return;
        }
        if (FILTER_ALL.equals(category)) {
            setAllCategoryFilters();
            return;
        }
        activeFilters.remove(FILTER_ALL);
        if (!activeFilters.add(category)) {
            activeFilters.remove(category);
        }
        if (activeFilters.isEmpty()) {
            setAllCategoryFilters();
        }
    }

    private String currentFilterSignature() {
        if (isAllCategoryFilterActive()) {
            return FILTER_ALL;
        }
        List<String> labels = new ArrayList<String>(activeFilters);
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    private boolean isTabActive(String tab) {
        if (FILTER_CHESTS.equals(tab)) {
            return chestBrowserMode;
        }
        return isCategoryFilterActive(tab);
    }

    private void rebuildSummaryBarLayoutCacheIfNeeded() {
        StringBuilder key = new StringBuilder(128);
        key.append(aggregateVariants).append('|').append(aggregateCombatTier).append('|');
        key.append(currentFilterSignature()).append('|');
        key.append(BlitzChestItemsData.getObservedValidCaptures()).append('|');
        for (BlitzChestItemsData.Entry entry : BlitzChestItemsData.getAll()) {
            if (entry == null) {
                continue;
            }
            key.append(entry.id).append(':').append(entry.meta).append(':').append(entry.observedCount).append(';');
        }
        String layoutKey = key.toString();
        if (layoutKey.equals(lastSummaryBarLayoutKey)) {
            return;
        }
        lastSummaryBarLayoutKey = layoutKey;

        List<BlitzChestItemsData.Entry> source = new ArrayList<BlitzChestItemsData.Entry>();
        if (chestBrowserMode || isAllCategoryFilterActive()) {
            source.addAll(BlitzChestItemsData.getAll());
        } else {
            for (BlitzChestItemsData.Entry entry : BlitzChestItemsData.getAll()) {
                if (activeFilters.contains(entry.category)) {
                    source.add(entry);
                }
            }
        }
        source = applyCatalogAggregates(source);
        updateObservedLabelColumnWidth(source);
    }

    private int summaryBarStartX(int panelX, int panelW) {
        int panelRight = panelX + panelW;
        int textX = panelX + OBSERVED_ROW_PAD;
        int idealBarX = textX + cachedSummaryTextColumnW + OBSERVED_LABEL_BAR_GAP;
        int minBarX = textX + fontRendererObj.getStringWidth(OBSERVED_CHEST_SLOT) + OBSERVED_LABEL_BAR_GAP;
        int maxBarX = panelRight - OBSERVED_ROW_PAD - OBSERVED_BAR_MIN_W - OBSERVED_BAR_RIGHT_TRIM;
        if (maxBarX < minBarX) {
            return minBarX;
        }
        return Math.max(minBarX, Math.min(idealBarX, maxBarX));
    }

    private int summaryBarWidth(int panelX, int panelW, int barX) {
        int maxW = panelX + panelW - OBSERVED_ROW_PAD - OBSERVED_BAR_RIGHT_TRIM - barX;
        return Math.max(OBSERVED_BAR_MIN_W, maxW);
    }

    private void rebuildSummaryCachesIfNeeded() {
        String key = currentFilterSignature() + "|" + chestBrowserMode + "|" + aggregateVariants
                + "|" + aggregateCombatTier + "|" + ConfigManager.getBlitzChestItemsSortMode() + "|"
                + BlitzChestItemsData.getObservedValidCaptures()
                + "|" + BlitzChestItemsData.getObservedSlotCaptures() + "|" + filtered.size();
        if (key.equals(lastSummaryCacheKey)) {
            return;
        }
        lastSummaryCacheKey = key;

        List<BlitzChestItemsData.Entry> source = new ArrayList<BlitzChestItemsData.Entry>();
        if (chestBrowserMode || isAllCategoryFilterActive()) {
            source.addAll(BlitzChestItemsData.getAll());
        } else {
            for (BlitzChestItemsData.Entry entry : BlitzChestItemsData.getAll()) {
                if (activeFilters.contains(entry.category)) {
                    source.add(entry);
                }
            }
        }
        source = applyCatalogAggregates(source);

        List<BlitzChestItemsData.Entry> ranked = new ArrayList<BlitzChestItemsData.Entry>();
        for (BlitzChestItemsData.Entry entry : source) {
            if (entry != null && entry.observedCount > 0) {
                ranked.add(entry);
            }
        }
        Collections.sort(ranked, new Comparator<BlitzChestItemsData.Entry>() {
            @Override
            public int compare(BlitzChestItemsData.Entry a, BlitzChestItemsData.Entry b) {
                int c = Integer.compare(b.observedCount, a.observedCount);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        cachedTopObserved = ranked;
        observedChancesScroll.reset();

        Map<String, Integer> slotsByCategory = new LinkedHashMap<String, Integer>();
        for (String cat : BlitzChestItemsData.getCategoryOrder()) {
            slotsByCategory.put(cat, 0);
        }
        for (BlitzChestItemsData.Entry entry : source) {
            if (entry == null || entry.observedSlotCount <= 0) {
                continue;
            }
            Integer prev = slotsByCategory.get(entry.category);
            int next = (prev == null ? 0 : prev) + entry.observedSlotCount;
            slotsByCategory.put(entry.category, next);
        }
        List<CategorySlice> slices = new ArrayList<CategorySlice>();
        for (String cat : BlitzChestItemsData.getCategoryOrder()) {
            Integer count = slotsByCategory.get(cat);
            slices.add(new CategorySlice(cat, count == null ? 0 : count, categoryBarColor(cat)));
        }
        Collections.sort(slices, new Comparator<CategorySlice>() {
            @Override
            public int compare(CategorySlice a, CategorySlice b) {
                int c = Integer.compare(b.slotCount, a.slotCount);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(BlitzChestItemsData.categoryRank(a.category),
                        BlitzChestItemsData.categoryRank(b.category));
            }
        });
        cachedCategorySlices = slices;
    }

    private void updateObservedLabelColumnWidth(List<BlitzChestItemsData.Entry> labelSource) {
        int prefixW = fontRendererObj.getStringWidth(OBSERVED_CHEST_SLOT);
        int maxW = prefixW + fontRendererObj.getStringWidth("Item");
        if (labelSource != null) {
            for (BlitzChestItemsData.Entry entry : labelSource) {
                if (entry != null && entry.observedCount > 0) {
                    maxW = Math.max(maxW, prefixW + OBSERVED_ICON_CELL + OBSERVED_ICON_GAP
                            + labelPixelWidth(formattedItemName(entry)));
                }
            }
        }
        cachedSummaryTextColumnW = maxW;
    }

    private int labelPixelWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return fontRendererObj.getStringWidth(text) + boldRenderExtraWidth(text);
    }

    /** Extra pixels bold text needs beyond {@link net.minecraft.client.gui.FontRenderer#getStringWidth}. */
    private int boldRenderExtraWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int extra = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    bold = false;
                }
                i++;
                continue;
            }
            if (bold) {
                extra++;
            }
        }
        return extra;
    }

    private int tooltipLineWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return fontRendererObj.getStringWidth(text) + boldRenderExtraWidth(text);
    }

    private void drawItemHoverTooltip(List<String> tooltip, int mouseX, int mouseY) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }

        GlStateManager.disableDepth();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();

        int maxLineW = 0;
        for (String line : tooltip) {
            maxLineW = Math.max(maxLineW, tooltipLineWidth(line));
        }
        maxLineW += 2;

        int tooltipX = mouseX + 12;
        int tooltipY = mouseY - 12;
        int tooltipH = 8;
        if (tooltip.size() > 1) {
            tooltipH += 2 + (tooltip.size() - 1) * 10;
        }

        if (tooltipX + maxLineW > width) {
            tooltipX -= 28 + maxLineW;
        }
        if (tooltipY + tooltipH + 6 > height) {
            tooltipY = height - tooltipH - 6;
        }

        zLevel = 400.0F;
        int bg = -267386864;
        drawGradientRect(tooltipX - 3, tooltipY - 4, tooltipX + maxLineW + 3, tooltipY - 3, bg, bg);
        drawGradientRect(tooltipX - 3, tooltipY + tooltipH + 3, tooltipX + maxLineW + 3, tooltipY + tooltipH + 4, bg, bg);
        drawGradientRect(tooltipX - 3, tooltipY - 3, tooltipX + maxLineW + 3, tooltipY + tooltipH + 3, bg, bg);
        drawGradientRect(tooltipX - 4, tooltipY - 3, tooltipX - 3, tooltipY + tooltipH + 3, bg, bg);
        drawGradientRect(tooltipX + maxLineW + 3, tooltipY - 3, tooltipX + maxLineW + 4, tooltipY + tooltipH + 3, bg, bg);

        int borderLight = 1347420415;
        int borderDark = (borderLight & 16711422) >> 1 | borderLight & -16777216;
        drawGradientRect(tooltipX - 3, tooltipY - 3 + 1, tooltipX - 3 + 1, tooltipY + tooltipH + 3 - 1, borderLight, borderDark);
        drawGradientRect(tooltipX + maxLineW + 2, tooltipY - 3 + 1, tooltipX + maxLineW + 3, tooltipY + tooltipH + 3 - 1, borderLight, borderDark);
        drawGradientRect(tooltipX - 3, tooltipY - 3, tooltipX + maxLineW + 3, tooltipY - 3 + 1, borderLight, borderLight);
        drawGradientRect(tooltipX - 3, tooltipY + tooltipH + 3, tooltipX + maxLineW + 3, tooltipY + tooltipH + 3 - 1, borderDark, borderDark);

        int lineY = tooltipY;
        for (int i = 0; i < tooltip.size(); i++) {
            fontRendererObj.drawStringWithShadow(tooltip.get(i), tooltipX, lineY, -1);
            if (i == 0) {
                lineY += 2;
            }
            lineY += 10;
        }

        zLevel = 0.0F;
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        HudGlState.restoreForOverlayText();
    }

    private void drawSummaryPanels(ScaledResolution sr, int mouseX, int mouseY) {
        rebuildSummaryBarLayoutCacheIfNeeded();
        int panelTop = getSummaryTopY();
        int outerLeft = 12;
        int outerRight = sr.getScaledWidth() - 12;
        int totalWidth = Math.max(120, outerRight - outerLeft);
        int panelWidth = (totalWidth - SUMMARY_GAP) / 2;
        int leftX = outerLeft;
        int rightX = leftX + panelWidth + SUMMARY_GAP;
        int rightWidth = outerRight - rightX;

        drawTopObservedPanel(leftX, panelTop, panelWidth, SUMMARY_HEIGHT, mouseX, mouseY);
        drawCategoryMixPanel(rightX, panelTop, rightWidth, SUMMARY_HEIGHT);
    }

    private void drawTopObservedPanel(int x, int y, int w, int h, int mouseX, int mouseY) {
        observedSummaryPanelX = x;
        observedSummaryPanelY = y;
        observedSummaryPanelW = w;
        observedSummaryPanelH = h;

        Gui.drawRect(x, y, x + w, y + h, COL_SUMMARY_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_SUMMARY_BORDER);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Observed Chances", x + 6, y + 6, 0xFFFFFFFF);
        fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "C = chests with one+",
                x + 6, y + 18, 0xFFFFFFFF);

        int totalCaptures = BlitzChestItemsData.getObservedValidCaptures();
        if (totalCaptures <= 0 || cachedTopObserved.isEmpty()) {
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "No observed data yet.",
                    x + 6, y + 36, 0xFFFFFFFF);
            return;
        }

        int listTop = y + 31;
        int listBottom = y + h - 5;
        int lineH = OBSERVED_LINE_H;
        int contentH = Math.max(lineH, cachedTopObserved.size() * lineH);
        observedChancesScroll.tick();
        observedChancesScroll.updateBounds(listTop, listTop + contentH, listTop, listBottom);
        observedChancesScroll.drawScrollbarAt(x + w - 8, mouseY, 3, 0x30FFFFFF, 0xEFFFFFFF);

        int clipX = x + 4;
        int clipW = Math.max(10, w - 17);
        int clipH = Math.max(1, listBottom - listTop);
        scissor(mc, clipX, listTop, clipW, clipH);
        try {
            int offset = observedChancesScroll.getOffset();
            int first = Math.max(0, (offset - lineH) / lineH);
            int last = Math.min(cachedTopObserved.size() - 1,
                    (offset + (listBottom - listTop) + lineH) / lineH);
            HudGuiItemIcon.beginGuiScreenItemBatch(mc);
            try {
                for (int i = first; i <= last; i++) {
                    int lineY = listTop + i * lineH - offset;
                    if (!isObservedRowVisible(lineY, listTop, listBottom, lineH)) {
                        continue;
                    }
                    drawObservedEntryIcons(cachedTopObserved.get(i), x, lineY);
                    scissor(mc, clipX, listTop, clipW, clipH);
                }
            } finally {
                HudGuiItemIcon.endGuiScreenItemBatch(mc);
            }
            HudGlState.restoreForOverlayText();
            for (int i = first; i <= last; i++) {
                int lineY = listTop + i * lineH - offset;
                if (!isObservedRowVisible(lineY, listTop, listBottom, lineH)) {
                    continue;
                }
                drawObservedEntryText(x, w - 10, lineY, cachedTopObserved.get(i), totalCaptures);
            }
        } finally {
            unscissor();
        }
    }

    private static boolean isObservedRowVisible(int lineY, int listTop, int listBottom, int lineH) {
        return lineY + lineH > listTop && lineY < listBottom;
    }

    private int observedEntryIconCellY(int lineY) {
        return lineY + Math.max(0, (OBSERVED_LINE_H - OBSERVED_ICON_CELL) / 2);
    }

    /** Vertically centers row text with the 18px item icon. */
    private int observedEntryTextY(int lineY) {
        int iconY = observedEntryIconCellY(lineY);
        return iconY + Math.max(0, (OBSERVED_ICON_CELL - fontRendererObj.FONT_HEIGHT) / 2);
    }

    private void drawObservedEntryIcons(BlitzChestItemsData.Entry entry, int panelX, int lineY) {
        if (entry == null) {
            return;
        }
        int textX = panelX + OBSERVED_ROW_PAD;
        int cfChestW = fontRendererObj.getStringWidth(OBSERVED_CHEST_SLOT);
        int iconX = textX + cfChestW;
        int iconY = observedEntryIconCellY(lineY);
        int iconInnerX = iconX + 1;
        int iconInnerY = iconY + 1;
        boolean composite = entry.mergedCombatTier && entry.tierCompositeStacks != null
                && !entry.tierCompositeStacks.isEmpty();
        try {
            if (composite) {
                HudGuiItemIcon.drawCompositeGuiScreenSlotInBatch(mc, entry.tierCompositeStacks, iconInnerX, iconInnerY);
            } else if (entry.stack != null && entry.stack.getItem() != null) {
                ItemStack iconStack = observedIconStack(entry);
                HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, iconStack, iconInnerX, iconInnerY);
                if (!entry.mergedCombatTier && iconStack.stackSize > 1) {
                    HudGuiItemIcon.drawGuiScreenSlotOverlayInBatch(mc, fontRendererObj, iconStack, iconInnerX, iconInnerY);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private ItemStack observedIconStack(BlitzChestItemsData.Entry entry) {
        if (entry == null || entry.stack == null) {
            return null;
        }
        int amount = Math.max(1, entry.count);
        if (entry.stack.stackSize == amount) {
            return entry.stack;
        }
        ItemStack copy = entry.stack.copy();
        copy.stackSize = (byte) Math.min(127, amount);
        return copy;
    }

    private void drawObservedEntryText(int panelX, int panelW, int lineY, BlitzChestItemsData.Entry entry,
                                       int totalCaptures) {
        if (entry == null) {
            return;
        }
        int textY = observedEntryTextY(lineY);
        int textX = panelX + OBSERVED_ROW_PAD;
        int cfChestW = fontRendererObj.getStringWidth(OBSERVED_CHEST_SLOT);
        int iconX = textX + cfChestW;
        int labelStartX = iconX + OBSERVED_ICON_CELL + OBSERVED_ICON_GAP;

        String chestPct = formatPercentCapped(entry.observedCount, totalCaptures);
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "C " + EnumChatFormatting.YELLOW + chestPct,
                textX, textY, 0xFFFFFFFF);

        String label = formattedItemName(entry);
        int labelMaxW = Math.max(8, summaryBarStartX(panelX, panelW) - labelStartX - 2);
        fontRendererObj.drawStringWithShadow(trimToWidth(label, labelMaxW), labelStartX, textY, 0xFFFFFFFF);

        int barX = summaryBarStartX(panelX, panelW);
        int barW = summaryBarWidth(panelX, panelW, barX);
        float frac = totalCaptures <= 0 ? 0f
                : Math.min(1f, (float) Math.min(entry.observedCount, totalCaptures) / (float) totalCaptures);
        drawSummaryHorizontalBar(barX, textY + 1, barW, SUMMARY_BAR_H, frac, COL_BAR_FILL_CHEST);
    }

    private void drawCategoryMixPanel(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, COL_SUMMARY_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_SUMMARY_BORDER);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Loot mix", x + 6, y + 6, 0xFFFFFFFF);
        fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Slot share by category",
                x + 6, y + 18, 0xFFFFFFFF);

        int totalSlots = 0;
        for (CategorySlice slice : cachedCategorySlices) {
            totalSlots += slice.slotCount;
        }
        if (totalSlots <= 0) {
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "No slot data yet.",
                    x + 6, y + 36, 0xFFFFFFFF);
            return;
        }

        int barX = x + 6;
        int barY = y + 28;
        int barW = w - 12;
        int barH = 8;
        drawHollowBorder(barX, barY, barX + barW, barY + barH, COL_BAR_OUTLINE);
        int cursor = barX;
        for (CategorySlice slice : cachedCategorySlices) {
            if (slice.slotCount <= 0) {
                continue;
            }
            int segW = Math.max(1, Math.round(barW * (slice.slotCount / (float) totalSlots)));
            if (cursor + segW > barX + barW) {
                segW = barX + barW - cursor;
            }
            if (segW <= 0) {
                continue;
            }
            Gui.drawRect(cursor, barY, cursor + segW, barY + barH, slice.color);
            cursor += segW;
        }

        int legendTop = barY + barH + 4;
        int legendBottom = y + h - 6;
        int legendH = Math.max(1, legendBottom - legendTop);
        scissor(mc, barX, legendTop, barW, legendH);
        try {
            drawCategoryMixLegend(barX, legendTop, barW, totalSlots);
        } finally {
            unscissor();
        }
    }

    /** Vertical legend — one category per line (compact; clipped to panel). */
    private void drawCategoryMixLegend(int barX, int legendTop, int barW, int totalSlots) {
        int row = 0;
        for (int i = 0; i < cachedCategorySlices.size(); i++) {
            CategorySlice slice = cachedCategorySlices.get(i);
            if (slice.slotCount <= 0) {
                continue;
            }
            int ly = legendTop + row * LOOT_MIX_LEGEND_LINE_H;
            String pct = formatPercent(slice.slotCount, totalSlots);
            String label = trimToWidth(slice.category + " " + pct, Math.max(8, barW - 8));
            Gui.drawRect(barX, ly + 1, barX + 5, ly + 6, slice.color);
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + label, barX + 7, ly, 0xFFFFFFFF);
            row++;
        }
    }

    private static void drawSummaryHorizontalBar(int barX, int barY, int barW, int barH, float fraction,
                                                 int fillColor) {
        int x2 = barX + barW;
        int y2 = barY + barH;
        int outline = COL_BAR_OUTLINE;
        Gui.drawRect(barX, barY, x2, barY + 1, outline);
        Gui.drawRect(barX, y2 - 1, x2, y2, outline);
        Gui.drawRect(barX, barY, barX + 1, y2, outline);
        Gui.drawRect(x2 - 1, barY, x2, y2, outline);
        int fillW = Math.max(0, Math.min(barW, Math.round(barW * Math.max(0f, Math.min(1f, fraction)))));
        if (fillW > 0) {
            Gui.drawRect(barX, barY, barX + fillW, barY + barH, fillColor);
        }
    }

    private static int categoryBarColor(String category) {
        if (BlitzChestItemsData.CAT_FOOD.equals(category)) {
            return 0xFFE8A040;
        }
        if (BlitzChestItemsData.CAT_MATERIALS.equals(category)) {
            return 0xFFB0B8C8;
        }
        if (BlitzChestItemsData.CAT_WEAPONS.equals(category)) {
            return 0xFFE06060;
        }
        if (BlitzChestItemsData.CAT_ARMOR.equals(category)) {
            return 0xFF6080E0;
        }
        if (BlitzChestItemsData.CAT_FISHING_RODS.equals(category)) {
            return 0xFF8B6914;
        }
        if (BlitzChestItemsData.CAT_POTIONS.equals(category)) {
            return 0xFFB060E0;
        }
        return 0xFF909090;
    }

    private String formatPercentCapped(int count, int total) {
        if (count <= 0 || total <= 0) {
            return "0%";
        }
        int capped = Math.min(count, total);
        return String.format(Locale.US, "%.0f%%", (double) capped * 100.0D / (double) total);
    }

    private String formatPercent(int count, int total) {
        if (count <= 0 || total <= 0) {
            return "0%";
        }
        return String.format(Locale.US, "%.0f%%", (double) count * 100.0D / (double) total);
    }

    private String formattedItemName(BlitzChestItemsData.Entry entry) {
        if (entry == null) {
            return "";
        }
        if ((entry.mergedVariants || entry.mergedCombatTier) && entry.displayName != null && !entry.displayName.isEmpty()) {
            return BlitzChestItemsData.normalizeFormatting(entry.displayName);
        }
        if (entry.stack != null) {
            try {
                String fromStack = entry.stack.getDisplayName();
                if (fromStack != null && !fromStack.isEmpty()) {
                    return BlitzChestItemsData.normalizeFormatting(fromStack);
                }
            } catch (Throwable ignored) {
            }
        }
        if (entry.displayName != null && !entry.displayName.isEmpty()) {
            return BlitzChestItemsData.normalizeFormatting(entry.displayName);
        }
        return EnumChatFormatting.WHITE + entry.id;
    }

    private String plainItemName(BlitzChestItemsData.Entry entry) {
        return stripFormatting(formattedItemName(entry)).trim();
    }

    private String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace('\uFFFD', '\u00A7');
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\u00A7' && i + 1 < normalized.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void drawSlotInner(int x, int y) {
        Gui.drawRect(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xFF000000);
    }

    /** Single 1px outer ring — draw once after item icons so stroke weight stays even. */
    private static void drawSlotOuterRing(int x, int y, boolean hover, boolean variant) {
        int ring = hover
                ? (variant ? COL_SLOT_VARIANT_HOVER : COL_SLOT_PREF_HOVER)
                : (variant ? COL_SLOT_VARIANT_BG : COL_SLOT_PREF_BG);
        Gui.drawRect(x, y, x + SLOT_SIZE, y + 1, ring);
        Gui.drawRect(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, ring);
        Gui.drawRect(x, y, x + 1, y + SLOT_SIZE, ring);
        Gui.drawRect(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, ring);
    }

    private static final class CategorySlice {
        final String category;
        final int slotCount;
        final int color;

        CategorySlice(String category, int slotCount, int color) {
            this.category = category;
            this.slotCount = slotCount;
            this.color = color;
        }
    }

    private void drawItemGrid(int mouseX, int mouseY) {
        hoveredEntry = null;
        int panelLeft = gridLeft - 4;
        int panelTop = gridTop - 4;
        int panelRight = gridLeft + gridWidth + SCROLLBAR_GAP + SCROLLBAR_W + 4;
        int panelBottom = gridTop + gridVisibleHeight + 4;
        Gui.drawRect(panelLeft, panelTop, panelRight, panelBottom, COL_PANEL_BG);
        drawHollowBorder(panelLeft, panelTop, panelRight, panelBottom, COL_PANEL_BORDER);

        if (filtered.isEmpty()) {
            String hint = EnumChatFormatting.DARK_GRAY + "No items — use /blitzchestdumpdebug, then merge dumps.";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawStringWithShadow(hint, gridLeft + (gridWidth - tw) / 2,
                    gridTop + gridVisibleHeight / 2 - 4, 0xFFFFFFFF);
            return;
        }

        int rows = (filtered.size() + Math.max(1, columns) - 1) / Math.max(1, columns);
        gridContentHeight = Math.max(SLOT_SIZE, rows * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP);
        scroll.tick();
        scroll.updateBounds(gridTop, gridTop + gridContentHeight, gridTop, gridTop + gridVisibleHeight);
        scroll.drawScrollbarAt(gridLeft + gridWidth + SCROLLBAR_GAP, mouseY, SCROLLBAR_W, 0x35FFFFFF, 0xF2FFFFFF);

        scissor(mc, gridLeft, gridTop, gridWidth, gridVisibleHeight);
        try {
            List<int[]> ringPass = new ArrayList<int[]>();
            int hoverX = -1;
            int hoverY = -1;
            HudGuiItemIcon.beginGuiScreenItemBatch(mc);
            try {
                int scrollOffset = scroll.getOffset();
                int rowStride = SLOT_SIZE + SLOT_GAP;
                int totalRows = (filtered.size() + Math.max(1, columns) - 1) / Math.max(1, columns);
                int firstVisibleRow = Math.max(0, (scrollOffset - 2) / Math.max(1, rowStride));
                int lastVisibleRow = Math.min(totalRows - 1,
                        (scrollOffset + gridVisibleHeight + 2) / Math.max(1, rowStride));
                int startIndex = Math.max(0, firstVisibleRow * columns);
                int endIndex = Math.min(filtered.size(), Math.max(startIndex, (lastVisibleRow + 1) * columns));
                for (int i = startIndex; i < endIndex; i++) {
                    int col = i % columns;
                    int row = i / columns;
                    int x = gridLeft + col * (SLOT_SIZE + SLOT_GAP);
                    int y = gridTop + row * (SLOT_SIZE + SLOT_GAP) - scrollOffset;
                    if (y + SLOT_SIZE < gridTop - 2 || y > gridTop + gridVisibleHeight + 2) {
                        continue;
                    }
                    BlitzChestItemsData.Entry entry = filtered.get(i);
                    boolean hover = mouseX >= x && mouseX < x + SLOT_SIZE
                            && mouseY >= y && mouseY < y + SLOT_SIZE
                            && mouseX >= gridLeft && mouseX < gridLeft + gridWidth
                            && mouseY >= gridTop && mouseY < gridTop + gridVisibleHeight;
                    boolean variant = BlitzChestItemsData.hasCatalogVariants(entry);
                    drawSlotInner(x, y);
                    if (entry.mergedCombatTier && !entry.tierCompositeStacks.isEmpty()) {
                        HudGuiItemIcon.drawCompositeGuiScreenSlotInBatch(mc, entry.tierCompositeStacks, x + 1, y + 1);
                    } else {
                        HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, entry.stack, x + 1, y + 1);
                    }
                    if (entry.stack != null && !entry.mergedCombatTier) {
                        try {
                            mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, entry.stack, x + 1, y + 1, null);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hover) {
                        hoveredEntry = entry;
                        hoverX = x;
                        hoverY = y;
                    }
                    ringPass.add(new int[] { x, y, hover ? 1 : 0, variant ? 1 : 0 });
                }
            } finally {
                HudGuiItemIcon.endGuiScreenItemBatch(mc);
            }
            HudGlState.restoreForOverlayText();
            for (int[] slot : ringPass) {
                drawSlotOuterRing(slot[0], slot[1], slot[2] != 0, slot[3] != 0);
            }
            if (hoverX >= 0) {
                GlStateManager.disableDepth();
                int hi = 0xC0FFFFFF;
                Gui.drawRect(hoverX, hoverY, hoverX + SLOT_SIZE, hoverY + 1, hi);
                Gui.drawRect(hoverX, hoverY + SLOT_SIZE - 1, hoverX + SLOT_SIZE, hoverY + SLOT_SIZE, hi);
                Gui.drawRect(hoverX, hoverY, hoverX + 1, hoverY + SLOT_SIZE, hi);
                Gui.drawRect(hoverX + SLOT_SIZE - 1, hoverY, hoverX + SLOT_SIZE, hoverY + SLOT_SIZE, hi);
                GlStateManager.enableDepth();
            }
        } finally {
            unscissor();
        }
    }

    private void drawChestBrowser(ScaledResolution sr, int mouseX, int mouseY) {
        int contentTop = gridTop - 4;
        int contentBottom = GuiHeader.contentBottomAboveBackBar(sr.getScaledHeight(), BUTTON_HEIGHT, 18) - 8;
        int contentLeft = GRID_PAD_X;
        int sortReserve = getRightPanelW() >= 120 ? getRightPanelW() + CHEST_SIDE_PANEL_GAP : 0;
        int contentRight = sr.getScaledWidth() - GRID_PAD_X - sortReserve;

        Gui.drawRect(contentLeft, contentTop, contentRight, contentBottom, COL_PANEL_BG);
        drawHollowBorder(contentLeft, contentTop, contentRight, contentBottom, COL_PANEL_BORDER);

        if (sortedChests.isEmpty()) {
            chestGalleryW = chestGalleryH = 0;
            String hint = EnumChatFormatting.DARK_GRAY + "No per-chest data — run tools/merge_blitz_chest_dumps.py.";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawStringWithShadow(hint, contentLeft + (contentRight - contentLeft - tw) / 2,
                    contentTop + (contentBottom - contentTop) / 2 - 4, 0xFFFFFFFF);
            return;
        }

        rebuildSortedChestsIfNeeded();
        chestGalleryX = contentLeft + 6;
        chestGalleryY = contentTop + 24;
        chestGalleryW = Math.max(80, contentRight - chestGalleryX - SCROLLBAR_GAP - SCROLLBAR_W - 8);
        chestGalleryH = Math.max(24, contentBottom - chestGalleryY - 6);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Chest Contents",
                contentLeft + 8, contentTop + 8, 0xFFFFFFFF);
        String countLine = EnumChatFormatting.DARK_GRAY + "Sorted by "
                + EnumChatFormatting.GRAY + chestSortMode.label
                + EnumChatFormatting.DARK_GRAY + " | "
                + EnumChatFormatting.GRAY + sortedChests.size();
        fontRendererObj.drawStringWithShadow(countLine,
                contentRight - 8 - fontRendererObj.getStringWidth(countLine), contentTop + 8, 0xFFFFFFFF);

        drawChestGallery(mouseX, mouseY);
    }

    private int highestChestSlot(BlitzChestItemsData.LoggedChest chest) {
        int max = -1;
        if (chest != null) {
            for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
                max = Math.max(max, slot.slot);
            }
        }
        return max;
    }

    private int chestGridRows(BlitzChestItemsData.LoggedChest chest) {
        return Math.max(1, (Math.max(chest.slotCount, highestChestSlot(chest) + 1) + 8) / 9);
    }

    private int chestGridHeight(BlitzChestItemsData.LoggedChest chest) {
        int rows = chestGridRows(chest);
        return rows * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
    }

    private int chestBlockHeight(BlitzChestItemsData.LoggedChest chest) {
        return CHEST_BLOCK_HEADER_H + chestGridHeight(chest) + CHEST_BLOCK_PAD_BOTTOM;
    }

    private void drawChestGallery(int mouseX, int mouseY) {
        hoveredChestSlot = null;
        if (chestGalleryW <= 0 || chestGalleryH <= 0) {
            return;
        }

        Gui.drawRect(chestGalleryX, chestGalleryY, chestGalleryX + chestGalleryW, chestGalleryY + chestGalleryH,
                0xA00A120A);
        drawHollowBorder(chestGalleryX, chestGalleryY, chestGalleryX + chestGalleryW, chestGalleryY + chestGalleryH,
                COL_PANEL_BORDER);

        chestGalleryContentH = 0;
        for (BlitzChestItemsData.LoggedChest chest : sortedChests) {
            chestGalleryContentH += chestBlockHeight(chest);
        }
        chestGalleryContentH = Math.max(chestGalleryH, chestGalleryContentH);

        int scrollTrackX = chestGalleryX + chestGalleryW - SCROLLBAR_W - 2;
        int innerRight = scrollTrackX - SCROLLBAR_GAP;
        int scrollTrackTop = chestGalleryY + 2;
        int scrollTrackBottom = chestGalleryY + chestGalleryH - 2;

        chestScroll.tick();
        chestScroll.updateBounds(chestGalleryY, chestGalleryY + chestGalleryContentH, scrollTrackTop, scrollTrackBottom);
        chestScroll.drawScrollbarAt(scrollTrackX, scrollTrackTop, scrollTrackBottom, mouseY,
                SCROLLBAR_W, 0x35FFFFFF, 0xF2FFFFFF);

        int offset = chestScroll.getOffset();
        int clipW = Math.max(1, innerRight - chestGalleryX - 2);
        scissor(mc, chestGalleryX + 1, chestGalleryY + 1, clipW, chestGalleryH - 2);
        try {
            int blockY = chestGalleryY + 4 - offset;
            for (BlitzChestItemsData.LoggedChest chest : sortedChests) {
                int blockH = chestBlockHeight(chest);
                if (blockY + blockH >= chestGalleryY && blockY <= chestGalleryY + chestGalleryH) {
                    drawChestBlock(chest, chestGalleryX + 4, blockY, innerRight - chestGalleryX - 8, mouseX, mouseY);
                }
                blockY += blockH;
            }
        } finally {
            unscissor();
        }
    }

    private void drawChestBlock(BlitzChestItemsData.LoggedChest chest, int x, int y, int w,
                                int mouseX, int mouseY) {
        fontRendererObj.drawStringWithShadow(buildChestGalleryTitle(chest), x + 4, y + 2, 0xFFFFFFFF);

        int rows = chestGridRows(chest);
        int gridW = 9 * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
        int gx = x + Math.max(0, (w - gridW) / 2);
        int gy = y + CHEST_BLOCK_HEADER_H;

        Map<Integer, BlitzChestItemsData.ChestSlot> bySlot = new HashMap<Integer, BlitzChestItemsData.ChestSlot>();
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            bySlot.put(slot.slot, slot);
        }

        List<int[]> ringPass = new ArrayList<int[]>(rows * 9);
        HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        try {
            for (int i = 0; i < rows * 9; i++) {
                int sx = gx + (i % 9) * (SLOT_SIZE + SLOT_GAP);
                int sy = gy + (i / 9) * (SLOT_SIZE + SLOT_GAP);
                BlitzChestItemsData.ChestSlot slot = bySlot.get(i);
                boolean slotHover = mouseX >= sx && mouseX < sx + SLOT_SIZE
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE
                        && mouseX >= chestGalleryX && mouseX < chestGalleryX + chestGalleryW
                        && mouseY >= chestGalleryY && mouseY < chestGalleryY + chestGalleryH;
                boolean variant = slot != null && BlitzChestItemsData.hasCatalogVariants(slot);
                drawSlotInner(sx, sy);
                if (slot != null && slot.stack != null) {
                    HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, slot.stack, sx + 1, sy + 1);
                    try {
                        mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, slot.stack, sx + 1, sy + 1, null);
                    } catch (Throwable ignored) {
                    }
                    if (slotHover) {
                        hoveredChestSlot = slot;
                    }
                }
                ringPass.add(new int[] { sx, sy, slotHover ? 1 : 0, variant ? 1 : 0 });
            }
        } finally {
            HudGuiItemIcon.endGuiScreenItemBatch(mc);
        }
        HudGlState.restoreForOverlayText();
        for (int[] ring : ringPass) {
            drawSlotOuterRing(ring[0], ring[1], ring[2] != 0, ring[3] != 0);
        }
    }

    private String buildChestGalleryTitle(BlitzChestItemsData.LoggedChest chest) {
        String title = EnumChatFormatting.AQUA + "Chest #" + chest.index;
        if (chestSortMode != ChestSortMode.CHEST_NUMBER) {
            String metricText;
            if (chestSortMode == ChestSortMode.MATERIALS) {
                metricText = formatMaterialsChestSortMetric(chest);
            } else if (chestSortMode == ChestSortMode.ENCHANT_BOTTLES) {
                metricText = formatEnchantBottleChestSortMetric(chest);
            } else if (chestSortMode == ChestSortMode.WEAPONS) {
                metricText = formatWeaponsChestSortMetric(chest);
            } else {
                int metric = chestSortMetric(chest);
                metricText = metric > 0 ? String.valueOf(metric) : "";
            }
            if (!metricText.isEmpty()) {
                title += EnumChatFormatting.DARK_GRAY + "  (" + EnumChatFormatting.GRAY + metricText
                        + EnumChatFormatting.DARK_GRAY + ")";
            }
        }
        if (chest != null && !chest.capturedAt.isEmpty()) {
            title += EnumChatFormatting.GRAY + "  " + chest.capturedAt;
        }
        return title;
    }

    private void rebuildSortedChestsIfNeeded() {
        List<BlitzChestItemsData.LoggedChest> all = BlitzChestItemsData.getLoggedChests();
        String key = chestSortMode.name() + "|" + all.size();
        if (key.equals(lastChestSortCacheKey)) {
            return;
        }
        lastChestSortCacheKey = key;
        List<BlitzChestItemsData.LoggedChest> next = new ArrayList<BlitzChestItemsData.LoggedChest>(all);
        Collections.sort(next, new Comparator<BlitzChestItemsData.LoggedChest>() {
            @Override
            public int compare(BlitzChestItemsData.LoggedChest a, BlitzChestItemsData.LoggedChest b) {
                if (chestSortMode == ChestSortMode.CHEST_NUMBER) {
                    return Integer.compare(a.index, b.index);
                }
                if (chestSortMode == ChestSortMode.MATERIALS) {
                    int materialCmp = compareMaterialChestSort(a, b);
                    if (materialCmp != 0) {
                        return materialCmp;
                    }
                    return Integer.compare(a.index, b.index);
                }
                if (chestSortMode == ChestSortMode.ENCHANT_BOTTLES) {
                    int bottleCmp = compareEnchantBottleChestSort(a, b);
                    if (bottleCmp != 0) {
                        return bottleCmp;
                    }
                    return Integer.compare(a.index, b.index);
                }
                if (chestSortMode == ChestSortMode.WEAPONS) {
                    int weaponCmp = compareWeaponChestSort(a, b);
                    if (weaponCmp != 0) {
                        return weaponCmp;
                    }
                    return Integer.compare(a.index, b.index);
                }
                int metric = Integer.compare(chestSortMetric(b), chestSortMetric(a));
                if (metric != 0) {
                    return metric;
                }
                return Integer.compare(a.index, b.index);
            }
        });
        sortedChests = next;
        chestScroll.reset();
    }

    private int chestSortMetric(BlitzChestItemsData.LoggedChest chest) {
        if (chest == null) {
            return 0;
        }
        switch (chestSortMode) {
            case ANY_ARMOR:
                return countAnyArmorInChest(chest);
            case IRON_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_IRON);
            case CHAINMAIL_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_CHAIN);
            case GOLD_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_GOLD);
            case LEATHER_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_LEATHER);
            case WEAPONS:
                return countWeaponsInChest(chest);
            case FOOD:
                return countBlitzCategoryInChest(chest, BlitzChestItemsData.CAT_FOOD);
            case MATERIALS:
                return countChestSortMaterialsInChest(chest);
            case ENCHANT_BOTTLES:
                return countEnchantBottlesInChest(chest);
            case ITEM_COUNT:
                return countItemsInChest(chest);
            case CHEST_NUMBER:
            default:
                return chest.index;
        }
    }

    private static int countBlitzCategoryInChest(BlitzChestItemsData.LoggedChest chest, String category) {
        int n = 0;
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            if (category.equals(BlitzChestItemsData.classifyCategory(slot.id))) {
                n++;
            }
        }
        return n;
    }

    /** Non-empty item stacks logged for this chest snapshot. */
    private static int countItemsInChest(BlitzChestItemsData.LoggedChest chest) {
        if (chest == null || chest.slots == null) {
            return 0;
        }
        return chest.slots.size();
    }

    private static int countAnyArmorInChest(BlitzChestItemsData.LoggedChest chest) {
        return countBlitzCategoryInChest(chest, BlitzChestItemsData.CAT_ARMOR);
    }

    private static final String[] CHEST_SORT_MATERIAL_IDS = {
            "minecraft:diamond",
            "minecraft:iron_ingot",
            "minecraft:gold_ingot",
            "minecraft:stick"
    };

    /** Stone sword → wooden sword → golden sword → stone axe (priority for Weapons sort). */
    private static final String[] CHEST_SORT_WEAPON_IDS = {
            "minecraft:stone_sword",
            "minecraft:wooden_sword",
            "minecraft:golden_sword",
            "minecraft:stone_axe"
    };

    private static int compareWeaponChestSort(BlitzChestItemsData.LoggedChest a,
                                              BlitzChestItemsData.LoggedChest b) {
        int[] countsA = countWeaponTiersInChest(a);
        int[] countsB = countWeaponTiersInChest(b);
        for (int i = 0; i < CHEST_SORT_WEAPON_IDS.length; i++) {
            int cmp = Integer.compare(countsB[i], countsA[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int[] countWeaponTiersInChest(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = new int[CHEST_SORT_WEAPON_IDS.length];
        if (chest == null) {
            return counts;
        }
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            int tier = weaponSortTier(slot.id);
            if (tier >= 0) {
                counts[tier]++;
            }
        }
        return counts;
    }

    private static int weaponSortTier(String id) {
        if (id == null) {
            return -1;
        }
        String lower = id.toLowerCase(Locale.US);
        for (int i = 0; i < CHEST_SORT_WEAPON_IDS.length; i++) {
            if (CHEST_SORT_WEAPON_IDS[i].equals(lower)) {
                return i;
            }
        }
        return -1;
    }

    private static String formatWeaponsChestSortMetric(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = countWeaponTiersInChest(chest);
        char[] labels = {'S', 'W', 'G', 'A'};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (counts[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(counts[i]).append(labels[i]);
        }
        return sb.toString();
    }

    private static int compareMaterialChestSort(BlitzChestItemsData.LoggedChest a,
                                                BlitzChestItemsData.LoggedChest b) {
        int[] countsA = countMaterialTiersInChest(a);
        int[] countsB = countMaterialTiersInChest(b);
        for (int i = 0; i < CHEST_SORT_MATERIAL_IDS.length; i++) {
            int cmp = Integer.compare(countsB[i], countsA[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int[] countMaterialTiersInChest(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = new int[CHEST_SORT_MATERIAL_IDS.length];
        if (chest == null) {
            return counts;
        }
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            int tier = materialSortTier(slot.id);
            if (tier >= 0) {
                counts[tier]++;
            }
        }
        return counts;
    }

    private static int materialSortTier(String id) {
        if (id == null) {
            return -1;
        }
        String lower = id.toLowerCase(Locale.US);
        for (int i = 0; i < CHEST_SORT_MATERIAL_IDS.length; i++) {
            if (CHEST_SORT_MATERIAL_IDS[i].equals(lower)) {
                return i;
            }
        }
        return -1;
    }

    private static String formatMaterialsChestSortMetric(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = countMaterialTiersInChest(chest);
        char[] labels = {'D', 'I', 'G', 'S'};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (counts[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(counts[i]).append(labels[i]);
        }
        return sb.toString();
    }

    private static final String CHEST_SORT_ENCHANT_BOTTLE_ID = "minecraft:experience_bottle";

    private static int compareEnchantBottleChestSort(BlitzChestItemsData.LoggedChest a,
                                                     BlitzChestItemsData.LoggedChest b) {
        int[] countsA = countEnchantBottleStacksInChest(a);
        int[] countsB = countEnchantBottleStacksInChest(b);
        int cmp = Integer.compare(countsB[0], countsA[0]);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(countsB[1], countsA[1]);
    }

    /** [0] = slots with 2 bottles, [1] = slots with 1 bottle. */
    private static int[] countEnchantBottleStacksInChest(BlitzChestItemsData.LoggedChest chest) {
        int stacksOfTwo = 0;
        int stacksOfOne = 0;
        if (chest == null || chest.slots == null) {
            return new int[] { 0, 0 };
        }
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            if (!isEnchantBottleSlot(slot)) {
                continue;
            }
            if (slot.count >= 2) {
                stacksOfTwo++;
            } else if (slot.count == 1) {
                stacksOfOne++;
            }
        }
        return new int[] { stacksOfTwo, stacksOfOne };
    }

    private static boolean isEnchantBottleSlot(BlitzChestItemsData.ChestSlot slot) {
        if (slot == null || slot.id == null) {
            return false;
        }
        return CHEST_SORT_ENCHANT_BOTTLE_ID.equalsIgnoreCase(slot.id.trim());
    }

    private static String formatEnchantBottleChestSortMetric(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = countEnchantBottleStacksInChest(chest);
        StringBuilder sb = new StringBuilder();
        if (counts[0] > 0) {
            sb.append(counts[0]).append("x2");
        }
        if (counts[1] > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(counts[1]).append("x1");
        }
        return sb.toString();
    }

    private static int countEnchantBottlesInChest(BlitzChestItemsData.LoggedChest chest) {
        int[] counts = countEnchantBottleStacksInChest(chest);
        return counts[0] + counts[1];
    }

    private static int countChestSortMaterialsInChest(BlitzChestItemsData.LoggedChest chest) {
        int n = 0;
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            if (materialSortTier(slot.id) >= 0) {
                n++;
            }
        }
        return n;
    }

    private static int countWeaponsInChest(BlitzChestItemsData.LoggedChest chest) {
        int n = 0;
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            if (!BlitzChestItemsData.CAT_WEAPONS.equals(BlitzChestItemsData.classifyCategory(slot.id))) {
                continue;
            }
            String lower = slot.id == null ? "" : slot.id.toLowerCase(Locale.US);
            if (!"minecraft:bow".equals(lower)) {
                n++;
            }
        }
        return n;
    }

    private static int countCategoryInChest(BlitzChestItemsData.LoggedChest chest, String category) {
        int n = 0;
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            if (category.equals(BlitzVaultItemsData.classifyItemCategory(slot.id))) {
                n++;
            }
        }
        return n;
    }

    private static int countArmorInChest(BlitzChestItemsData.LoggedChest chest, String tier) {
        int n = 0;
        for (BlitzChestItemsData.ChestSlot slot : chest.slots) {
            String cat = BlitzVaultItemsData.classifyItemCategory(slot.id);
            if (!isArmorCategory(cat)) {
                continue;
            }
            if (tier.equals(BlitzVaultItemsData.classifyItemTier(slot.id))) {
                n++;
            }
        }
        return n;
    }

    private static boolean isArmorCategory(String category) {
        return BlitzVaultItemsData.CAT_HELMET.equals(category)
                || BlitzVaultItemsData.CAT_CHESTPLATE.equals(category)
                || BlitzVaultItemsData.CAT_LEGGINGS.equals(category)
                || BlitzVaultItemsData.CAT_BOOTS.equals(category);
    }

    private int getRightPanelX() {
        return gridLeft + gridWidth + SCROLLBAR_GAP + SCROLLBAR_W + CHEST_SIDE_PANEL_GAP;
    }

    private int getRightPanelY() {
        return gridTop - 4;
    }

    private int getRightPanelW() {
        return Math.max(0, (width - 18) - getRightPanelX());
    }

    private int getSidePanelH() {
        return gridVisibleHeight + 8;
    }

    private void drawChestSortPanel(int mouseX, int mouseY) {
        int x = getRightPanelX();
        int y = getRightPanelY();
        int w = getRightPanelW();
        int h = getSidePanelH();
        chestSortRows.clear();
        chestSortListH = 0;
        if (w < 120 || h < 80) {
            return;
        }

        Gui.drawRect(x, y, x + w, y + h, COL_PANEL_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_PANEL_BORDER);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Chest Sort", x + 6, y + 6, 0xFFFFFFFF);

        int rowX = x + 6;
        int rowW = Math.max(60, w - 12);
        int listTop = y + 20;
        int listBottom = y + h - 8;
        int listH = Math.max(0, listBottom - listTop);
        int rowStride = CHEST_SORT_ROW_H + CHEST_SORT_ROW_GAP;
        int contentH = ChestSortMode.values().length * rowStride - CHEST_SORT_ROW_GAP;
        chestSortMaxScrollPx = Math.max(0, contentH - listH);
        chestSortScrollPx = Math.min(Math.max(0, chestSortScrollPx), chestSortMaxScrollPx);
        chestSortListX = rowX;
        chestSortListY = listTop;
        chestSortListW = rowW;
        chestSortListH = listH;

        scissor(mc, rowX, listTop, rowW, listH);
        int rowY = listTop - chestSortScrollPx;
        for (ChestSortMode mode : ChestSortMode.values()) {
            if (rowY + CHEST_SORT_ROW_H < listTop || rowY > listBottom) {
                rowY += rowStride;
                continue;
            }
            ChestSortRowRect row = new ChestSortRowRect(mode, rowX, rowY, rowW, CHEST_SORT_ROW_H);
            chestSortRows.add(row);
            boolean active = mode == chestSortMode;
            boolean hover = row.contains(mouseX, mouseY);
            int bg = active ? COL_TAB_BG_ACTIVE : (hover ? COL_TAB_BG_HOVER : COL_TAB_BG);
            int border = active ? COL_TAB_BORDER_ACTIVE : COL_TAB_BORDER;
            Gui.drawRect(row.x, row.y, row.x + row.w, row.y + row.h, bg);
            drawHollowBorder(row.x, row.y, row.x + row.w, row.y + row.h, border);
            int labelMaxW = Math.max(20, rowW - CHEST_SORT_ICONS_RESERVE_W - 10);
            String label = chestSortRowLabel(active, mode.label);
            fontRendererObj.drawStringWithShadow(trimToWidth(label, labelMaxW), row.x + 4, row.y + 5, 0xFFFFFFFF);
            rowY += rowStride;
        }
        restoreChestSortListScissor();
        HudGlState.restoreForOverlayText();
        HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        try {
            for (ChestSortRowRect row : chestSortRows) {
                drawChestSortRowBlockIcons(mc, row.x + row.w - 4, row.y, row.h, row.mode.sortIconStacks());
                drawChestSortRowLitIcons(mc, row.x + row.w - 4, row.y, row.h, row.mode.sortIconStacks());
            }
        } finally {
            HudGuiItemIcon.endGuiScreenItemBatch(mc);
        }
        restoreChestSortListScissor();
        HudGlState.restoreForOverlayText();
        unscissor();

        if (chestSortMaxScrollPx > 0) {
            drawMiniVerticalScrollbar(x + w - 5, listTop, 3, listH, chestSortScrollPx, chestSortMaxScrollPx);
        }
    }

    private void restoreChestSortListScissor() {
        if (chestSortListH > 0 && chestSortListW > 0) {
            scissor(mc, chestSortListX, chestSortListY, chestSortListW, chestSortListH);
        }
    }

    /** Row label and trailing icons share the same scroll clip (Chest # and Item count chest stacks). */
    private boolean isChestSortRowContentVisible(int rowY, int rowH) {
        int listTop = chestSortListY;
        int listBottom = chestSortListY + chestSortListH;
        if (rowY + rowH <= listTop || rowY >= listBottom) {
            return false;
        }
        int labelY = rowY + 5;
        int labelBottom = labelY + fontRendererObj.FONT_HEIGHT;
        return labelBottom > listTop && labelY < listBottom;
    }

    private static boolean isChestSortIconInList(int iconY, int listTop, int listBottom) {
        return iconY + CHEST_SORT_ICON_DRAW > listTop && iconY < listBottom;
    }

    /** Item icons (armor, tools, etc.) — lit GUI batch; must not mix with row chrome/text. */
    private void drawChestSortRowLitIcons(Minecraft mc, int rightEdge, int rowY, int rowH, ItemStack[] icons) {
        if (mc == null || icons == null || icons.length == 0) {
            return;
        }
        if (!isChestSortRowContentVisible(rowY, rowH)) {
            return;
        }
        int count = 0;
        for (ItemStack stack : icons) {
            if (isChestSortLitIcon(stack)) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        int totalW = count * CHEST_SORT_ICON_DRAW + (count - 1) * CHEST_SORT_ICON_GAP;
        int startX = rightEdge - totalW;
        int iconY = rowY + (rowH - CHEST_SORT_ICON_DRAW) / 2;
        float scale = (float) CHEST_SORT_ICON_DRAW / 16f;
        int drawn = 0;
        for (ItemStack stack : icons) {
            if (!isChestSortLitIcon(stack)) {
                continue;
            }
            int ix = startX + drawn * (CHEST_SORT_ICON_DRAW + CHEST_SORT_ICON_GAP);
            GlStateManager.pushMatrix();
            GlStateManager.translate(ix, iconY, 0);
            GlStateManager.scale(scale, scale, 1f);
            HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, stack, 0, 0);
            GlStateManager.popMatrix();
            drawn++;
        }
    }

    /**
     * Block icons (Chest # / Item count chest stacks) — flat HUD draw; restores list scissor after each icon
     * because {@link HudGuiItemIcon#drawInventoryHudSlot} disables GL scissor test.
     */
    private void drawChestSortRowBlockIcons(Minecraft mc, int rightEdge, int rowY, int rowH, ItemStack[] icons) {
        if (mc == null || icons == null || icons.length == 0) {
            return;
        }
        if (!isChestSortRowContentVisible(rowY, rowH)) {
            return;
        }
        int listTop = chestSortListY;
        int listBottom = chestSortListY + chestSortListH;
        int count = 0;
        for (ItemStack stack : icons) {
            if (isChestSortBlockIcon(stack)) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        int totalW = count * CHEST_SORT_ICON_DRAW + (count - 1) * CHEST_SORT_ICON_GAP;
        int startX = rightEdge - totalW;
        int iconY = rowY + (rowH - CHEST_SORT_ICON_DRAW) / 2;
        float scale = (float) CHEST_SORT_ICON_DRAW / 16f;
        restoreChestSortListScissor();
        try {
            int drawn = 0;
            for (ItemStack stack : icons) {
                if (!isChestSortBlockIcon(stack)) {
                    continue;
                }
                int ix = startX + drawn * (CHEST_SORT_ICON_DRAW + CHEST_SORT_ICON_GAP);
                if (isChestSortIconInList(iconY, listTop, listBottom)) {
                    HudGuiItemIcon.drawInventoryHudSlot(mc, stack, (float) ix, (float) iconY, scale);
                    restoreChestSortListScissor();
                }
                drawn++;
            }
        } finally {
            restoreChestSortListScissor();
        }
    }

    private static boolean isChestSortLitIcon(ItemStack stack) {
        return stack != null && stack.getItem() != null && !(stack.getItem() instanceof ItemBlock);
    }

    private static boolean isChestSortBlockIcon(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemBlock;
    }

    private boolean consumeChestSortPanelWheel(int mx, int my, int dWheel) {
        if (!chestBrowserMode || dWheel == 0 || chestSortMaxScrollPx <= 0 || chestSortListH <= 0) {
            return false;
        }
        if (mx < chestSortListX || mx >= chestSortListX + chestSortListW
                || my < chestSortListY || my >= chestSortListY + chestSortListH) {
            return false;
        }
        int step = CHEST_SORT_ROW_H * Math.max(1, Math.abs(dWheel) / 120);
        if (dWheel > 0) {
            chestSortScrollPx = Math.max(0, chestSortScrollPx - step);
        } else {
            chestSortScrollPx = Math.min(chestSortMaxScrollPx, chestSortScrollPx + step);
        }
        return true;
    }

    private boolean handleChestSortPanelClick(int mouseX, int mouseY) {
        for (ChestSortRowRect row : chestSortRows) {
            if (row.contains(mouseX, mouseY) && row.mode != chestSortMode) {
                chestSortMode = row.mode;
                lastChestSortCacheKey = "__init__";
                ConfigManager.setBlitzChestItemsChestSortMode(chestSortMode.name());
                return true;
            }
        }
        return false;
    }

    private void drawMiniVerticalScrollbar(int trackX, int trackY, int trackW, int trackH, int scrollPx, int maxScrollPx) {
        Gui.drawRect(trackX, trackY, trackX + trackW, trackY + trackH, 0x50000000);
        if (maxScrollPx <= 0) {
            return;
        }
        int thumbH = Math.max(6, (int) ((float) trackH * ((float) trackH / (float) (trackH + maxScrollPx))));
        thumbH = Math.min(thumbH, trackH);
        int travel = Math.max(1, trackH - thumbH);
        int thumbY = trackY + (int) ((float) travel * ((float) scrollPx / (float) maxScrollPx));
        thumbY = Math.min(trackY + trackH - thumbH, Math.max(trackY, thumbY));
        Gui.drawRect(trackX + 1, thumbY, trackX + trackW - 1, thumbY + thumbH, 0xA0CCCCCC);
    }

    /**
     * Row labels for {@code Chest #}: Lunar/Hypixel treat a bare {@code #} as custom hex color syntax and corrupt
     * the rest of the string. Insert {@link EnumChatFormatting#RESET} before {@code #}.
     */
    private static String chestSortRowLabel(boolean active, String rawLabel) {
        EnumChatFormatting color = active ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY;
        String label = rawLabel == null ? "" : rawLabel;
        int hash = label.indexOf('#');
        if (hash < 0) {
            return color + label;
        }
        return color + label.substring(0, hash) + EnumChatFormatting.RESET + color + label.substring(hash);
    }

    private String trimToWidth(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = fontRendererObj.getStringWidth(ellipsis);
        String trimmed = fontRendererObj.trimStringToWidth(text, Math.max(0, maxWidth - ellipsisWidth));
        return trimmed + ellipsis;
    }

    private void drawChestSlotTooltip(BlitzChestItemsData.ChestSlot slot, int mouseX, int mouseY) {
        if (slot == null || slot.stack == null) {
            return;
        }
        renderStackTooltip(slot.stack, slot.fallbackTooltip, mouseX, mouseY, null);
    }

    private void drawEntryTooltip(BlitzChestItemsData.Entry entry, int mouseX, int mouseY) {
        if (entry == null || entry.stack == null) {
            return;
        }
        renderStackTooltip(entry.stack, entry.fallbackTooltip, mouseX, mouseY, entry);
    }

    private void renderStackTooltip(net.minecraft.item.ItemStack stack, List<String> fallbackTooltip,
                                    int mouseX, int mouseY, BlitzChestItemsData.Entry statsEntry) {
        if (stack == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        boolean restoreAdvanced = false;
        boolean previousAdvanced = false;
        try {
            List<String> tooltip = null;
            if (mc != null && mc.gameSettings != null) {
                previousAdvanced = mc.gameSettings.advancedItemTooltips;
                mc.gameSettings.advancedItemTooltips = false;
                restoreAdvanced = true;
            }
            try {
                tooltip = stack.getTooltip(mc.thePlayer, false);
            } catch (Throwable ignored) {
                tooltip = null;
            }
            if (tooltip == null || tooltip.isEmpty()) {
                tooltip = fallbackTooltip == null
                        ? new ArrayList<String>()
                        : new ArrayList<String>(fallbackTooltip);
            } else {
                tooltip = new ArrayList<String>(tooltip);
            }
            if (tooltip.isEmpty()) {
                if (statsEntry != null && (statsEntry.mergedVariants || statsEntry.mergedCombatTier)) {
                    tooltip.add(formattedItemName(statsEntry));
                } else {
                    tooltip.add(formattedItemNameFromStack(stack, fallbackTooltip));
                }
            } else if (statsEntry != null && (statsEntry.mergedVariants || statsEntry.mergedCombatTier)) {
                tooltip.set(0, formattedItemName(statsEntry));
            }
            if (statsEntry != null && statsEntry.mergedCombatTier) {
                String materials = BlitzChestItemsCombatTier.describeTierMaterials(statsEntry.tierCompositeStacks);
                if (!materials.isEmpty()) {
                    tooltip.add(EnumChatFormatting.DARK_GRAY + "Materials: " + EnumChatFormatting.WHITE + materials);
                }
            }
            BlitzChestItemsData.replaceDyedWithLeatherColorLine(tooltip, stack, fallbackTooltip);
            applyVanillaTooltipColors(stack, tooltip);
            if (statsEntry != null && ConfigManager.isBlitzChestItemsShowSeenInTooltip()) {
                int captures = Math.max(1, BlitzChestItemsData.getObservedValidCaptures());
                tooltip.add(EnumChatFormatting.DARK_GRAY + "Chests: "
                        + EnumChatFormatting.GREEN + statsEntry.observedCount
                        + EnumChatFormatting.DARK_GRAY + "/" + captures
                        + " (" + EnumChatFormatting.GREEN + formatPercentCapped(statsEntry.observedCount, captures)
                        + EnumChatFormatting.DARK_GRAY + ")");
                tooltip.add(EnumChatFormatting.DARK_GRAY + "Slot appearances: "
                        + EnumChatFormatting.AQUA + statsEntry.observedSlotCount);
            }
            drawItemHoverTooltip(tooltip, mouseX, mouseY);
        } catch (Throwable ignored) {
        } finally {
            if (restoreAdvanced && mc != null && mc.gameSettings != null) {
                mc.gameSettings.advancedItemTooltips = previousAdvanced;
            }
            GlStateManager.popMatrix();
        }
    }

    private String formattedItemNameFromStack(net.minecraft.item.ItemStack stack, List<String> fallbackTooltip) {
        if (stack != null) {
            try {
                String fromStack = stack.getDisplayName();
                if (fromStack != null && !fromStack.isEmpty()) {
                    return BlitzChestItemsData.normalizeFormatting(fromStack);
                }
            } catch (Throwable ignored) {
            }
        }
        if (fallbackTooltip != null && !fallbackTooltip.isEmpty()) {
            return BlitzChestItemsData.normalizeFormatting(fallbackTooltip.get(0));
        }
        return EnumChatFormatting.WHITE + "Item";
    }

    private void applyVanillaTooltipColors(net.minecraft.item.ItemStack stack, List<String> tooltip) {
        if (stack == null || tooltip == null || tooltip.isEmpty()) {
            return;
        }
        try {
            tooltip.set(0, String.valueOf(stack.getRarity().rarityColor) + tooltip.get(0));
            for (int i = 1; i < tooltip.size(); i++) {
                String plain = stripFormatting(tooltip.get(i)).trim();
                if (plain.startsWith("+")) {
                    tooltip.set(i, EnumChatFormatting.BLUE + plain);
                } else if (plain.isEmpty() || plain.startsWith("minecraft:") || plain.startsWith("NBT:")) {
                    tooltip.set(i, EnumChatFormatting.GRAY + plain);
                } else if (plain.startsWith("Color:")) {
                    tooltip.set(i, EnumChatFormatting.GRAY + plain);
                } else {
                    tooltip.set(i, EnumChatFormatting.GRAY + plain);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void layoutGrid(ScaledResolution sr) {
        int centerX = sr.getScaledWidth() / 2;
        gridTop = getSummaryTopY() + SUMMARY_HEIGHT + 8;
        int contentBottom = GuiHeader.contentBottomAboveBackBar(sr.getScaledHeight(), BUTTON_HEIGHT, 18);
        int availableWidth = sr.getScaledWidth() - GRID_PAD_X * 2;
        int colsFit = Math.max(1, (availableWidth + SLOT_GAP) / (SLOT_SIZE + SLOT_GAP));
        colsFit = Math.min(colsFit, 18);
        gridLeft = centerX - (colsFit * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP) / 2;
        gridWidth = colsFit * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
        columns = colsFit;
        gridVisibleHeight = Math.max(SLOT_SIZE * 3, contentBottom - gridTop - 10);
    }

    private void rebuildFilteredIfNeeded() {
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.US);
        String signature = currentFilterSignature() + "|" + chestBrowserMode + "|" + query + "|"
                + ConfigManager.getBlitzChestItemsSortMode() + "|" + aggregateVariants + "|" + aggregateCombatTier;
        if (signature.equals(lastFilterSignature)) {
            return;
        }
        lastFilterSignature = signature;

        List<BlitzChestItemsData.Entry> next = new ArrayList<BlitzChestItemsData.Entry>();
        for (BlitzChestItemsData.Entry entry : BlitzChestItemsData.getAll()) {
            if (!isAllCategoryFilterActive() && !activeFilters.contains(entry.category)) {
                continue;
            }
            if (!query.isEmpty() && !entry.filterText.contains(query)) {
                continue;
            }
            next.add(entry);
        }
        filtered = applyDisplayProcessing(next);
        scroll.setOffsetClamped(0);
        lastSummaryCacheKey = "";
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (mouseButton == 0) {
            if (handleTabClick(mouseX, mouseY)) {
                return;
            }
            if (handleSortClick(mouseX, mouseY)) {
                return;
            }
            if (handleAggregateClick(mouseX, mouseY)) {
                return;
            }
            if (handleCombatTierClick(mouseX, mouseY)) {
                return;
            }
            if (chestBrowserMode && handleChestSortPanelClick(mouseX, mouseY)) {
                return;
            }
        }
        if (!chestBrowserMode && observedChancesScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        GuiSmoothScroll active = chestBrowserMode ? chestScroll : scroll;
        if (active.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean handleTabClick(int mouseX, int mouseY) {
        for (TabRect tab : tabs) {
            if (mouseX >= tab.x && mouseX < tab.x + tab.w && mouseY >= tab.y && mouseY < tab.y + tab.h) {
                if (FILTER_CHESTS.equals(tab.label)) {
                    chestBrowserMode = true;
                } else {
                    chestBrowserMode = false;
                    toggleCategoryFilter(tab.label);
                }
                layoutGrid(new ScaledResolution(mc));
                if (searchField != null) {
                    searchField.setFocused(false);
                }
                rebuildFilteredIfNeeded();
                return true;
            }
        }
        return false;
    }

    private boolean handleSortClick(int mouseX, int mouseY) {
        if (mouseX >= sortPillX && mouseX < sortPillX + sortPillW
                && mouseY >= sortPillY && mouseY < sortPillY + CONTROL_PILL_H) {
            cycleSortMode();
            layoutTabs(new ScaledResolution(mc));
            layoutGrid(new ScaledResolution(mc));
            rebuildFilteredIfNeeded();
            return true;
        }
        return false;
    }

    private boolean handleAggregateClick(int mouseX, int mouseY) {
        if (mouseX >= aggregatePillX && mouseX < aggregatePillX + aggregatePillW
                && mouseY >= aggregatePillY && mouseY < aggregatePillY + CONTROL_PILL_H) {
            aggregateVariants = !aggregateVariants;
            ConfigManager.setBlitzChestItemsAggregateVariants(aggregateVariants);
            layoutTabs(new ScaledResolution(mc));
            layoutGrid(new ScaledResolution(mc));
            rebuildFilteredIfNeeded();
            return true;
        }
        return false;
    }

    private boolean handleCombatTierClick(int mouseX, int mouseY) {
        if (mouseX >= combatTierPillX && mouseX < combatTierPillX + combatTierPillW
                && mouseY >= combatTierPillY && mouseY < combatTierPillY + CONTROL_PILL_H) {
            aggregateCombatTier = !aggregateCombatTier;
            ConfigManager.setBlitzChestItemsAggregateCombatTier(aggregateCombatTier);
            layoutTabs(new ScaledResolution(mc));
            layoutGrid(new ScaledResolution(mc));
            rebuildFilteredIfNeeded();
            return true;
        }
        return false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (searchField != null && searchField.textboxKeyTyped(typedChar, keyCode)) {
            rebuildFilteredIfNeeded();
            return;
        }
        if (keyCode == 1) {
            if (parent != null) {
                mc.displayGuiScreen(parent);
            } else {
                mc.displayGuiScreen(null);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        if (chestBrowserMode) {
            int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (dWheel != 0) {
                int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
                int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
                if (consumeChestSortPanelWheel(mx, my, dWheel)) {
                    return;
                }
            }
            chestScroll.handleMouseWheel();
        } else {
            int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (dWheel != 0) {
                int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
                int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
                if (observedSummaryPanelW > 0
                        && mx >= observedSummaryPanelX && mx < observedSummaryPanelX + observedSummaryPanelW
                        && my >= observedSummaryPanelY && my < observedSummaryPanelY + observedSummaryPanelH) {
                    observedChancesScroll.handleMouseWheel();
                    return;
                }
            }
            scroll.handleMouseWheel();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        chestScroll.mouseReleased(state);
        observedChancesScroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    private static void drawHollowBorder(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y1 + 1, color);
        Gui.drawRect(x1, y2 - 1, x2, y2, color);
        Gui.drawRect(x1, y1, x1 + 1, y2, color);
        Gui.drawRect(x2 - 1, y1, x2, y2, color);
    }

    private static void scissor(Minecraft mc, int gridX, int gridY, int gridW, int gridH) {
        try {
            ScaledResolution sr = new ScaledResolution(mc);
            int scale = sr.getScaleFactor();
            int sx = gridX * scale;
            int sw = gridW * scale;
            int sh = gridH * scale;
            int sy = mc.displayHeight - (gridY + gridH) * scale;
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL11.glScissor(sx, sy, sw, sh);
        } catch (Throwable ignored) {
        }
    }

    private static void unscissor() {
        try {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onGuiClosed() {
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return searchField != null && searchField.isFocused();
    }

    private static final class TabRect {
        final String label;
        final int count;
        final int x;
        final int y;
        final int w;
        final int h;

        TabRect(String label, int count, int x, int y, int w, int h) {
            this.label = label;
            this.count = count;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final class ChestSortRowRect {
        final ChestSortMode mode;
        final int x;
        final int y;
        final int w;
        final int h;

        ChestSortRowRect(ChestSortMode mode, int x, int y, int w, int h) {
            this.mode = mode;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
