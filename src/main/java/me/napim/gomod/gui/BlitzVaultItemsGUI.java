package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzChestItemsData;
import me.napim.gomod.features.BlitzVaultItemsData;
import me.napim.gomod.features.BlitzVaultEnchantLevelInsights;
import me.napim.gomod.features.BlitzVaultLootInsights;
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
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.lwjgl.input.Mouse;

/**
 * Showcases every Blitz survival games vault chest item captured from logged vault chest dumps, with
 * filterable category tabs, a search box and a vanilla-style scrollable slot grid.
 */
public final class BlitzVaultItemsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final Gui TEXTURE_DRAW = new Gui() {};

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;

    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 2;
    private static final int GRID_PAD_X = 16;
    private static final int ITEM_SCROLLBAR_GAP = 8;
    private static final int ITEM_SCROLLBAR_W = 4;
    private static final int ITEM_SIDE_PANEL_GAP = 12;

    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 4;
    // Summary panels intentionally hug their content so the main grid + side panels get more space.
    private static final int SUMMARY_HEIGHT = 98;
    private static final int SUMMARY_BAR_H = 4;
    private static final int OBSERVED_ROW_PAD = 6;
    private static final int OBSERVED_LABEL_BAR_GAP = 6;
    private static final int OBSERVED_BAR_MIN_W = 36;
    /** Widest C/F columns so percentages line up; bar start comes from global max label width. */
    private static final String OBSERVED_CF_CHEST_SLOT = "C 100.0% ";
    private static final String OBSERVED_CF_FIND_SLOT = "F 9999.9% ";
    /** Faint outline only — empty bar area shows panel background through it. */
    private static final int COL_BAR_OUTLINE = 0x354A5048;
    private static final int COL_BAR_FILL_CHEST = 0xFF5CB85C;
    private static final int SUMMARY_GAP = 8;
    private static final ResourceLocation DELETE_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/deleteicon10.png");
    private static final ResourceLocation DELETE_ICON_TEXTURE_FALLBACK =
            new ResourceLocation("gomod123", "runtime/deleteicon10");
    /** Native pixel size of deleteicon10 / uparrow10 / downarrow10 (shared canvas). */
    private static final int MINI_ICON_10_CANVAS_W = 52;
    private static final int MINI_ICON_10_CANVAS_H = 61;
    private static final ResourceLocation UP_ARROW_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/uparrow10.png");
    private static final ResourceLocation UP_ARROW_ICON_TEXTURE_FALLBACK =
            new ResourceLocation("gomod123", "runtime/uparrow10");
    private static final ResourceLocation DOWN_ARROW_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/downarrow10.png");
    private static final ResourceLocation DOWN_ARROW_ICON_TEXTURE_FALLBACK =
            new ResourceLocation("gomod123", "runtime/downarrow10");
    private static final ResourceLocation RESET_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/resetbutton10.png");
    private static final ResourceLocation RESET_ICON_TEXTURE_FALLBACK =
            new ResourceLocation("gomod123", "runtime/resetbutton10");
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final int RESET_ICON_TEXTURE_W = 97;
    private static final int RESET_ICON_TEXTURE_H = 96;
    private static final int MINI_BUTTON_ICON_DRAW_W = 7;
    private static final int MINI_BUTTON_ICON_DRAW_H = 8;
    private static final int MINI_BUTTON_ICON_OFFSET_X = 0;
    private static final Map<String, ResourceLocation> MINI_ICON_TEXTURE_CACHE = new HashMap<String, ResourceLocation>();
    private static final Set<String> MINI_ICON_TEXTURE_MISSING = new HashSet<String>();
    /** Classpath path → [width, height] read from PNG (avoids stale hard-coded UV size after art changes). */
    private static final Map<String, int[]> MINI_ICON_DIMENSIONS_CACHE = new HashMap<String, int[]>();
    private static final java.util.regex.Pattern ATTACK_DAMAGE_PATTERN =
            java.util.regex.Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");

    private static final int COL_PANEL_BG = 0xC8101816;
    private static final int COL_PANEL_BORDER = 0xFF2D6B2D;
    private static final int COL_SLOT_BG = 0xFF2A2A2A;
    private static final int COL_SLOT_HOVER = 0xFF6BC36B;
    private static final int COL_SLOT_PREF_BG = 0xFF214521;
    private static final int COL_SLOT_PREF_HOVER = 0xFF63C963;
    private static final int COL_SLOT_BLACK_BG = 0xFF4A2020;
    private static final int COL_SLOT_BLACK_HOVER = 0xFFD06A6A;
    private static final int COL_SLOT_INNER_LIGHT = 0xFF1F1F1F;
    private static final int COL_SLOT_INNER_DARK = 0xFF0A0A0A;
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

    /** Vertical space reserved at the bottom of the left panel for the Blacklisted block (title, field, list). */
    private static final int MANAGE_BLACKLIST_RESERVED = 84;
    private static final int TAGGED_ROW_STRIDE = 16;
    private static final int CHEST_GALLERY_SCROLLBAR_W = 4;
    private static final int CHEST_GALLERY_SCROLLBAR_GAP = 4;
    private static final int CHEST_GALLERY_SCROLLBAR_INSET = 3;
    private static final int CHEST_BLOCK_HEADER_H = 14;
    private static final int CHEST_BLOCK_PAD_BOTTOM = 10;
    private static final int CHEST_SORT_ROW_H = 18;
    private static final int CHEST_SORT_ROW_GAP = 2;
    private static final int CHEST_SORT_ICON_DRAW = 11;
    private static final int CHEST_SORT_ICON_GAP = 1;
    /** Width reserved for up to four 11px armor icons on a sort row. */
    private static final int CHEST_SORT_ICONS_RESERVE_W = CHEST_SORT_ICON_DRAW * 5 + CHEST_SORT_ICON_GAP * 4;

    private static final String FILTER_ALL = "All";
    private static final String FILTER_CHESTS = "Chests";
    private static final String FILTER_MULTI = "__multi__";

    private enum SortMode {
        DEFAULT("Default"),
        MOST_ENCHANTS("Most Enchants"),
        FEWEST_ENCHANTS("Fewest Enchants"),
        MOST_OBSERVED("Most Seen"),
        BEST_MATERIAL("Best Material");

        final String label;
        SortMode(String label) { this.label = label; }
        SortMode next() {
            SortMode[] v = values();
            return v[(ordinal() + 1) % v.length];
        }
    }

    private enum SortKey {
        ENCHANT_COUNT_ASC("Fewest Enchants"),
        ENCHANT_COUNT_DESC("Most Enchants"),
        CATEGORY("Category"),
        TIER("Material"),
        ATTACK_DAMAGE("Attack Damage"),
        ENCHANT_PRIORITY("Enchant Priority"),
        CHEST_ODDS("Chest %"),
        SLOT_ODDS("Finds/Chest"),
        ITEM_ID("Item Id"),
        NAME("Name");

        final String label;
        SortKey(String label) { this.label = label; }
    }

    private enum ChestSortMode {
        CHEST_NUMBER("Chest #"),
        DIAMOND_ARMOR("Diamond Armor"),
        IRON_ARMOR("Iron Armor"),
        CHAINMAIL_ARMOR("Chainmail Armor"),
        GOLD_ARMOR("Gold Armor"),
        LEATHER_ARMOR("Leather Armor"),
        DIAMOND_HELMET("Diamond Helmet"),
        DIAMOND_CHESTPLATE("Diamond Chestplate"),
        DIAMOND_LEGGINGS("Diamond Leggings"),
        DIAMOND_BOOTS("Diamond Boots"),
        BOWS("Bows"),
        SWORDS("Swords");

        final String label;
        ChestSortMode(String label) { this.label = label; }

        static ChestSortMode fromConfig(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return CHEST_NUMBER;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException ignored) {
                return CHEST_NUMBER;
            }
        }

        ItemStack[] sortIconStacks() {
            switch (this) {
                case CHEST_NUMBER:
                    return stackArray(new ItemStack(Blocks.chest));
                case DIAMOND_ARMOR:
                    return stacks(Items.diamond_helmet, Items.diamond_chestplate, Items.diamond_leggings, Items.diamond_boots);
                case IRON_ARMOR:
                    return stacks(Items.iron_helmet, Items.iron_chestplate, Items.iron_leggings, Items.iron_boots);
                case CHAINMAIL_ARMOR:
                    return stacks(Items.chainmail_helmet, Items.chainmail_chestplate, Items.chainmail_leggings, Items.chainmail_boots);
                case GOLD_ARMOR:
                    return stacks(Items.golden_helmet, Items.golden_chestplate, Items.golden_leggings, Items.golden_boots);
                case LEATHER_ARMOR:
                    return stacks(Items.leather_helmet, Items.leather_chestplate, Items.leather_leggings, Items.leather_boots);
                case DIAMOND_HELMET:
                    return stacks(Items.diamond_helmet);
                case DIAMOND_CHESTPLATE:
                    return stacks(Items.diamond_chestplate);
                case DIAMOND_LEGGINGS:
                    return stacks(Items.diamond_leggings);
                case DIAMOND_BOOTS:
                    return stacks(Items.diamond_boots);
                case BOWS:
                    return stacks(Items.bow);
                case SWORDS:
                    return stacks(Items.diamond_sword, Items.iron_sword, Items.stone_sword,
                            Items.golden_sword, Items.wooden_sword);
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
    private final Set<String> activeFilters = new LinkedHashSet<String>();
    private final List<SortKey> sortKeys = new ArrayList<SortKey>();
    private String lastSortSignature = "__init__";

    private final List<SortRowRect> sortRows = new ArrayList<SortRowRect>();
    private int addSortBtnX, addSortBtnY, addSortBtnW, addSortBtnH;
    private int resetSortBtnX, resetSortBtnY, resetSortBtnW, resetSortBtnH;
    private final List<String> preferredKeys = new ArrayList<String>();
    private final List<String> blacklistedKeys = new ArrayList<String>();
    private final List<RuleOptionDef> allRuleOptions = new ArrayList<RuleOptionDef>();
    private final List<TaggedRowRect> preferredRows = new ArrayList<TaggedRowRect>();
    private final List<TaggedRowRect> blacklistedRows = new ArrayList<TaggedRowRect>();
    private final List<RuleOptionRect> ruleOptionRows = new ArrayList<RuleOptionRect>();
    private GuiTextField preferredRuleField;
    private GuiTextField blacklistedRuleField;

    private int preferredTaggedScrollPx;
    private int blacklistedTaggedScrollPx;
    private int preferredDropdownScrollIndex;
    private int blacklistedDropdownScrollIndex;

    private int prefTaggedWheelX, prefTaggedWheelY, prefTaggedWheelW, prefTaggedWheelH, prefTaggedMaxScrollPx;
    private int blTaggedWheelX, blTaggedWheelY, blTaggedWheelW, blTaggedWheelH, blTaggedMaxScrollPx;
    private int prefDropdownWheelX, prefDropdownWheelY, prefDropdownWheelW, prefDropdownWheelH, prefDropdownMaxScrollIdx;
    private int blDropdownWheelX, blDropdownWheelY, blDropdownWheelW, blDropdownWheelH, blDropdownMaxScrollIdx;

    /** null = follow the active tab + search; "All" = all items; any category name = that category. */
    private String pinnedObsCategory = null;
    private int obsPinBtnX, obsPinBtnY, obsPinBtnW, obsPinBtnH;
    private int enchantLevelsBtnX, enchantLevelsBtnY, enchantLevelsBtnW, enchantLevelsBtnH;
    private int enchantSummaryPanelX, enchantSummaryPanelY, enchantSummaryPanelW, enchantSummaryPanelH;
    private int observedSummaryPanelX, observedSummaryPanelY, observedSummaryPanelW, observedSummaryPanelH;

    private GuiTextField searchField;
    private List<BlitzVaultItemsData.Entry> filtered = new ArrayList<BlitzVaultItemsData.Entry>();
    private String lastQuery = "__init__";
    private String lastFilter = "__init__";
    private String lastSummaryCacheKey = "__init__";
    private SummarySelection cachedSummarySelection = new SummarySelection(
            Collections.<BlitzVaultItemsData.Entry>emptyList(), FILTER_ALL);
    private BlitzVaultLootInsights.Snapshot cachedLootInsights =
            new BlitzVaultLootInsights.Snapshot(Collections.<BlitzVaultLootInsights.Row>emptyList(),
                    Collections.<BlitzVaultLootInsights.Row>emptyList(), 0, 0);
    /** Global max C/F + label width so summary bars share one start X (all filters / enchants). */
    private int cachedSummaryTextColumnW = 0;
    private String lastSummaryBarLayoutKey = "";

    private GuiButton backButton;

    private int gridLeftX;
    private int gridTopY;
    private int gridWidth;
    private int columns;
    private int gridVisibleHeight;
    private int gridContentHeight;

    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private final GuiSmoothScroll chestGalleryScroll = new GuiSmoothScroll();
    private final GuiSmoothScroll enchantChancesScroll = new GuiSmoothScroll();
    private final GuiSmoothScroll observedChancesScroll = new GuiSmoothScroll();
    private BlitzVaultItemsData.Entry hoveredEntry = null;
    private BlitzVaultItemsData.VaultSlot hoveredChestSlot = null;
    private List<String> hoveredUiTooltip = null;
    private boolean dataInitComplete;
    private boolean chestBrowserMode = false;
    private ChestSortMode chestSortMode = ChestSortMode.CHEST_NUMBER;
    private String lastChestSortCacheKey = "__init__";
    private List<BlitzVaultItemsData.VaultChest> sortedChests = Collections.emptyList();
    private final List<ChestSortRowRect> chestSortRows = new ArrayList<ChestSortRowRect>();
    private int chestSortScrollPx;
    private int chestSortMaxScrollPx;
    private int chestSortListX, chestSortListY, chestSortListW, chestSortListH;
    private int chestGalleryX, chestGalleryY, chestGalleryW, chestGalleryH;
    private int chestGalleryContentH;

    /** Warm classpath mini-button textures so first GUI open does not decode PNGs on the main thread. */
    public static void preloadAssets() {
        resolveMiniIconDimensions("/assets/gomod123/textures/resetbutton10.png", RESET_ICON_TEXTURE_W,
                RESET_ICON_TEXTURE_H);
        resolveMiniIconDimensions("/assets/gomod123/textures/uparrow10.png", MINI_ICON_10_CANVAS_W,
                MINI_ICON_10_CANVAS_H);
        resolveMiniIconDimensions("/assets/gomod123/textures/downarrow10.png", MINI_ICON_10_CANVAS_W,
                MINI_ICON_10_CANVAS_H);
        resolveMiniIconDimensions("/assets/gomod123/textures/deleteicon.png", MINI_ICON_10_CANVAS_W,
                MINI_ICON_10_CANVAS_H);
    }

    public BlitzVaultItemsGUI(GuiScreen parent) {
        this.parent = parent;
        setAllCategoryFilters();
    }

    @Override
    public void initGui() {
        super.initGui();
        scroll.setWheelTuning(72, 3600, 120);
        chestGalleryScroll.setWheelTuning(72, 3600, 120);
        BlitzVaultItemsData.scheduleWarmup();
        loadSavedUiState();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());

        int centerX = sr.getScaledWidth() / 2;
        int searchWidth = Math.min(260, sr.getScaledWidth() - 80);
        int searchX = centerX - searchWidth / 2;
        int searchY = 60;

        searchField = new GuiTextField(900, fontRendererObj, searchX + 14, searchY + 4, searchWidth - 18, 12);
        searchField.setMaxStringLength(48);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setTextColor(0xE8FFE8);
        searchField.setDisabledTextColour(0x668866);
        preferredRuleField = new GuiTextField(901, fontRendererObj, 0, 0, 0, 12);
        preferredRuleField.setMaxStringLength(32);
        preferredRuleField.setEnableBackgroundDrawing(false);
        preferredRuleField.setTextColor(0xE8FFE8);
        preferredRuleField.setDisabledTextColour(0x668866);
        blacklistedRuleField = new GuiTextField(902, fontRendererObj, 0, 0, 0, 12);
        blacklistedRuleField.setMaxStringLength(32);
        blacklistedRuleField.setEnableBackgroundDrawing(false);
        blacklistedRuleField.setTextColor(0xFFE8E8);
        blacklistedRuleField.setDisabledTextColour(0x886666);

        int backY = GuiHeader.backButtonY(sr.getScaledHeight(), BUTTON_HEIGHT);
        backButton = new GuiButton(0, centerX - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");
        this.buttonList.add(backButton);

        tryCompleteDataInit(sr);
    }

    private void tryCompleteDataInit(ScaledResolution sr) {
        BlitzVaultItemsData.pumpBuildBatch(BlitzVaultItemsData.BUILD_BATCH_GUI);
        if (!BlitzVaultItemsData.isReady()) {
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
        rebuildRuleOptions();
        normalizeTaggedLists();
        layoutTabs(sr);
        layoutGrid(sr);
        rebuildFilteredIfNeeded();
    }

    private void drawLoadingScreen(ScaledResolution sr) {
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Blitz Vault Items"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(),
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        int pct = Math.round(BlitzVaultItemsData.getLoadProgress() * 100f);
        String line = EnumChatFormatting.GREEN + "Loading vault item database... " + pct + "%";
        int tw = fontRendererObj.getStringWidth(line);
        fontRendererObj.drawStringWithShadow(line, sr.getScaledWidth() / 2 - tw / 2, sr.getScaledHeight() / 2 - 4,
                0xFFFFFFFF);
    }

    private void loadSavedUiState() {
        sortKeys.clear();
        loadSavedSortKeys();
        pinnedObsCategory = normalizeSavedObservedCategory(ConfigManager.getBlitzVaultItemsObservedCategory());
        preferredKeys.clear();
        preferredKeys.addAll(ConfigManager.getBlitzVaultItemsPreferredKeys());
        blacklistedKeys.clear();
        blacklistedKeys.addAll(ConfigManager.getBlitzVaultItemsBlacklistedKeys());
        chestSortMode = ChestSortMode.fromConfig(ConfigManager.getBlitzVaultItemsChestSortMode());
        lastChestSortCacheKey = "__init__";
        // normalizeTaggedLists runs after rebuildRuleOptions() — options are empty until vault data loads.
        lastSortSignature = "__init__";
        lastQuery = "__init__";
        lastFilter = "__init__";
    }

    private void normalizeTaggedLists() {
        for (int i = preferredKeys.size() - 1; i >= 0; i--) {
            String key = preferredKeys.get(i);
            if (key == null || key.trim().isEmpty() || preferredKeys.indexOf(key) != i || !isKnownSelector(key)) {
                preferredKeys.remove(i);
            }
        }
        for (int i = blacklistedKeys.size() - 1; i >= 0; i--) {
            String key = blacklistedKeys.get(i);
            if (key == null || key.trim().isEmpty() || blacklistedKeys.indexOf(key) != i
                    || preferredKeys.contains(key) || !isKnownSelector(key)) {
                blacklistedKeys.remove(i);
            }
        }
    }

    private void persistTaggedLists() {
        normalizeTaggedLists();
        ConfigManager.setBlitzVaultItemsPreferredKeys(preferredKeys);
        ConfigManager.setBlitzVaultItemsBlacklistedKeys(blacklistedKeys);
    }

    private void rebuildRuleOptions() {
        allRuleOptions.clear();
        Set<String> seen = new LinkedHashSet<String>();

        List<String> enchantNames = collectEnchantOptionNames();
        Collections.sort(enchantNames, String.CASE_INSENSITIVE_ORDER);
        for (String enchant : enchantNames) {
            addRuleOption(seen, selectorId("enchant", enchant), "Enchant: " + enchant);
        }
        for (String category : BlitzVaultItemsData.getCategoryOrder()) {
            if (BlitzVaultItemsData.getCategoryCount(category) > 0) {
                addRuleOption(seen, selectorId("category", category), "Category: " + category);
            }
        }
        for (String tier : BlitzVaultItemsData.getTiersWithItems()) {
            addRuleOption(seen, selectorId("tier", tier), "Tier: " + tier);
        }
        addRuleOption(seen, selectorId("other", "Plain"), "Other: Plain");
    }

    private void addRuleOption(Set<String> seen, String id, String label) {
        if (id == null || id.isEmpty() || label == null || label.isEmpty() || seen.contains(id)) {
            return;
        }
        seen.add(id);
        allRuleOptions.add(new RuleOptionDef(id, label));
    }

    private List<String> collectEnchantOptionNames() {
        return new ArrayList<String>(BlitzVaultItemsData.getEnchantFilterOptions());
    }

    private boolean isKnownSelector(String selectorId) {
        if (selectorId == null || selectorId.trim().isEmpty()) {
            return false;
        }
        for (RuleOptionDef option : allRuleOptions) {
            if (selectorId.equals(option.id)) {
                return true;
            }
        }
        return false;
    }

    private void loadSavedSortKeys() {
        String raw = ConfigManager.getBlitzVaultItemsSortKeys();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                SortKey key = parseSortKey(part);
                if (key != null && isVisibleSortKey(key) && !sortKeys.contains(key)) {
                    sortKeys.add(key);
                }
            }
        }
        if (sortKeys.isEmpty()) {
            sortKeys.addAll(legacyDefaultSortKeys(ConfigManager.getBlitzVaultItemsSortMode()));
        }
        if (sortKeys.isEmpty()) {
            sortKeys.addAll(defaultSortKeys());
        }
    }

    private SortKey parseSortKey(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return SortKey.valueOf(raw.trim().toUpperCase(Locale.US));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isVisibleSortKey(SortKey key) {
        return key != null && key != SortKey.ITEM_ID && key != SortKey.NAME;
    }

    private List<SortKey> getVisibleSortKeys() {
        List<SortKey> out = new ArrayList<SortKey>();
        for (SortKey key : SortKey.values()) {
            if (isVisibleSortKey(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private List<SortKey> legacyDefaultSortKeys(String raw) {
        SortMode legacy = SortMode.DEFAULT;
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                legacy = SortMode.valueOf(raw.trim().toUpperCase(Locale.US));
            } catch (Throwable ignored) {
                legacy = SortMode.DEFAULT;
            }
        }
        List<SortKey> keys = new ArrayList<SortKey>();
        switch (legacy) {
            case MOST_ENCHANTS:
                keys.add(SortKey.ENCHANT_COUNT_DESC);
                keys.add(SortKey.CATEGORY);
                keys.add(SortKey.TIER);
                keys.add(SortKey.ENCHANT_PRIORITY);
                keys.add(SortKey.ITEM_ID);
                return keys;
            case FEWEST_ENCHANTS:
                keys.add(SortKey.ENCHANT_COUNT_ASC);
                keys.add(SortKey.CATEGORY);
                keys.add(SortKey.TIER);
                keys.add(SortKey.ENCHANT_PRIORITY);
                keys.add(SortKey.ITEM_ID);
                return keys;
            case MOST_OBSERVED:
                keys.add(SortKey.CHEST_ODDS);
                keys.add(SortKey.SLOT_ODDS);
                keys.add(SortKey.CATEGORY);
                keys.add(SortKey.TIER);
                keys.add(SortKey.ITEM_ID);
                return keys;
            case BEST_MATERIAL:
                keys.add(SortKey.CATEGORY);
                keys.add(SortKey.TIER);
                keys.add(SortKey.ENCHANT_PRIORITY);
                keys.add(SortKey.ITEM_ID);
                return keys;
            case DEFAULT:
            default:
                return defaultSortKeys();
        }
    }

    private List<SortKey> defaultSortKeys() {
        List<SortKey> keys = new ArrayList<SortKey>();
        keys.add(SortKey.CHEST_ODDS);
        return keys;
    }

    private String currentSortSignature() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sortKeys.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sortKeys.get(i).name());
        }
        return sb.toString();
    }

    private void persistSortKeys() {
        ConfigManager.setBlitzVaultItemsSortKeys(currentSortSignature());
    }

    private String normalizeSavedObservedCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (FILTER_ALL.equalsIgnoreCase(trimmed)) {
            return FILTER_ALL;
        }
        for (String cat : BlitzVaultItemsData.getCategoryOrder()) {
            if (cat.equalsIgnoreCase(trimmed)) {
                return cat;
            }
        }
        return null;
    }

    private void layoutTabs(ScaledResolution sr) {
        tabs.clear();
        int centerX = sr.getScaledWidth() / 2;
        int tabsY = 82;
        List<String> categories = new ArrayList<String>();
        categories.add(FILTER_ALL);
        categories.add(FILTER_CHESTS);
        for (String cat : BlitzVaultItemsData.getCategoryOrder()) {
            if (countInCategory(cat) > 0) {
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
            int countSuffix = cat.equals(FILTER_ALL)
                    ? BlitzVaultItemsData.getEntryCount()
                    : cat.equals(FILTER_CHESTS)
                    ? BlitzVaultItemsData.getVaultChestCount()
                    : countInCategory(cat);
            int w = getTabWidth(cat);
            tabs.add(new TabRect(cat, countSuffix, x, y, w, TAB_HEIGHT));
            x += w + TAB_GAP;
        }
    }

    private int getTabWidth(String label) {
        int countSuffix = label.equals(FILTER_ALL)
                ? BlitzVaultItemsData.getEntryCount()
                : label.equals(FILTER_CHESTS)
                ? BlitzVaultItemsData.getVaultChestCount()
                : countInCategory(label);
        return fontRendererObj.getStringWidth(label + " " + countSuffix) + 14;
    }

    private void layoutGrid(ScaledResolution sr) {
        int centerX = sr.getScaledWidth() / 2;
        int gridTop = getSummaryTopY() + SUMMARY_HEIGHT + 8;

        int contentBottom = GuiHeader.contentBottomAboveBackBar(sr.getScaledHeight(), BUTTON_HEIGHT, 18);
        int availableWidth = sr.getScaledWidth() - GRID_PAD_X * 2;
        int colsFit = Math.max(1, (availableWidth + SLOT_GAP) / (SLOT_SIZE + SLOT_GAP));
        colsFit = Math.min(colsFit, 18);

        gridLeftX = centerX - (colsFit * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP) / 2;
        gridTopY = gridTop;
        gridWidth = colsFit * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
        columns = colsFit;
        gridVisibleHeight = Math.max(SLOT_SIZE * 3, contentBottom - gridTopY - 10);
    }

    private int countInCategory(String cat) {
        return BlitzVaultItemsData.getCategoryCount(cat);
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
            if (i > 0) sb.append(',');
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    private String currentSummaryFilterLabel() {
        if (isAllCategoryFilterActive()) {
            return FILTER_ALL;
        }
        if (activeFilters.size() == 1) {
            return activeFilters.iterator().next();
        }
        return FILTER_MULTI;
    }

    private void rebuildFilteredIfNeeded() {
        String q = searchField == null ? "" : searchField.getText();
        if (q == null) q = "";
        q = q.toLowerCase(Locale.US).trim();
        String filterSignature = currentFilterSignature();
        String sortSignature = currentSortSignature();
        if (q.equals(lastQuery) && filterSignature.equals(lastFilter) && sortSignature.equals(lastSortSignature)) {
            return;
        }
        lastQuery = q;
        lastFilter = filterSignature;
        lastSortSignature = sortSignature;
        List<BlitzVaultItemsData.Entry> next = new ArrayList<BlitzVaultItemsData.Entry>();
        for (BlitzVaultItemsData.Entry e : BlitzVaultItemsData.getAll()) {
            if (!isAllCategoryFilterActive() && !activeFilters.contains(e.category)) {
                continue;
            }
            if (!q.isEmpty() && !e.filterText.contains(q)) {
                continue;
            }
            next.add(e);
        }
        Comparator<BlitzVaultItemsData.Entry> comp = buildSortComparator();
        if (comp != null) {
            Collections.sort(next, comp);
        }
        filtered = next;
        int rows = (filtered.size() + columns - 1) / Math.max(1, columns);
        gridContentHeight = Math.max(SLOT_SIZE, rows * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP);
        scroll.reset();
    }

    private String currentSummaryCacheKey() {
        String q = searchField == null ? "" : searchField.getText();
        if (q == null) q = "";
        String pin = pinnedObsCategory == null ? "__auto__" : pinnedObsCategory;
        return pin + "|" + currentFilterSignature() + "|" + q.toLowerCase(Locale.US).trim()
                + "|" + BlitzVaultItemsData.getEntryCount()
                + "|" + BlitzVaultItemsData.getObservedValidCaptures()
                + "|" + BlitzVaultItemsData.getObservedSlotCaptures();
    }

    private void rebuildSummaryCachesIfNeeded() {
        String key = currentSummaryCacheKey();
        if (key.equals(lastSummaryCacheKey)) {
            return;
        }
        lastSummaryCacheKey = key;

        cachedSummarySelection = resolveSummarySelection();
        BlitzVaultLootInsights.rebuildIfNeeded();
        cachedLootInsights = BlitzVaultLootInsights.get();
        BlitzVaultEnchantLevelInsights.get();
        observedChancesScroll.reset();
        enchantChancesScroll.reset();
        rebuildSummaryBarLayoutCacheIfNeeded();
    }

    private int measureSummaryRowPrefixWidth() {
        return fontRendererObj.getStringWidth(OBSERVED_CF_CHEST_SLOT)
                + fontRendererObj.getStringWidth(OBSERVED_CF_FIND_SLOT);
    }

    private int measureSummaryRowTextWidth(String label) {
        if (label == null) {
            label = "";
        }
        return measureSummaryRowPrefixWidth() + fontRendererObj.getStringWidth(label);
    }

    private void rebuildSummaryBarLayoutCacheIfNeeded() {
        StringBuilder key = new StringBuilder(128);
        key.append(BlitzVaultItemsData.getObservedValidCaptures()).append('|');
        BlitzVaultLootInsights.Snapshot snap = cachedLootInsights;
        for (BlitzVaultLootInsights.Row row : snap.materialRows) {
            key.append(row.label).append(':').append(row.fraction).append(';');
        }
        for (BlitzVaultLootInsights.Row row : snap.enchantCountRows) {
            key.append(row.label).append(':').append(row.fraction).append(';');
        }
        String layoutKey = key.toString();
        if (layoutKey.equals(lastSummaryBarLayoutKey)) {
            return;
        }
        lastSummaryBarLayoutKey = layoutKey;

        int maxW = 0;
        for (BlitzVaultLootInsights.Row row : snap.materialRows) {
            maxW = Math.max(maxW, measureInsightRowTextWidth(row));
        }
        for (BlitzVaultLootInsights.Row row : snap.enchantCountRows) {
            maxW = Math.max(maxW, measureInsightRowTextWidth(row));
        }
        cachedSummaryTextColumnW = maxW;
    }

    private int measureInsightRowTextWidth(BlitzVaultLootInsights.Row row) {
        if (row == null) {
            return 0;
        }
        return fontRendererObj.getStringWidth(row.label) + fontRendererObj.getStringWidth(
                BlitzVaultLootInsights.formatPercent(row.fraction)) + 6;
    }

    private int summaryBarStartX(int panelX, int panelW) {
        int panelRight = panelX + panelW;
        int textX = panelX + OBSERVED_ROW_PAD;
        int idealBarX = textX + cachedSummaryTextColumnW + OBSERVED_LABEL_BAR_GAP;
        int minBarX = textX + 72;
        int maxBarX = panelRight - OBSERVED_ROW_PAD - OBSERVED_BAR_MIN_W;
        if (maxBarX < minBarX) {
            return minBarX;
        }
        return Math.max(minBarX, Math.min(idealBarX, maxBarX));
    }

    private int summaryBarWidth(int panelX, int panelW, int barX) {
        return Math.max(OBSERVED_BAR_MIN_W, panelX + panelW - OBSERVED_ROW_PAD - barX);
    }

    private Comparator<BlitzVaultItemsData.Entry> buildSortComparator() {
        return new Comparator<BlitzVaultItemsData.Entry>() {
            @Override
            public int compare(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b) {
                if (a == b) {
                    return 0;
                }
                if (a == null) {
                    return 1;
                }
                if (b == null) {
                    return -1;
                }
                int tagged = Integer.compare(tagRank(a), tagRank(b));
                if (tagged != 0) {
                    return tagged;
                }
                for (SortKey key : sortKeys) {
                    int c = compareSortKey(a, b, key);
                    if (c != 0) {
                        return c;
                    }
                }
                int tie = compareBaseTieBreak(a, b);
                if (tie != 0) {
                    return tie;
                }
                return compareEntryId(a, b);
            }
        };
    }

    /** Stable final tie-break — TimSort requires a total order (never return 0 for unequal entries). */
    private static int compareEntryId(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b) {
        if (a.id == null && b.id == null) {
            return 0;
        }
        if (a.id == null) {
            return 1;
        }
        if (b.id == null) {
            return -1;
        }
        return a.id.compareTo(b.id);
    }

    private int compareSortKey(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b, SortKey key) {
        if (a == null || b == null || key == null) {
            return 0;
        }
        switch (key) {
            case ENCHANT_COUNT_ASC:
                return Integer.compare(a.enchantments.size(), b.enchantments.size());
            case ENCHANT_COUNT_DESC:
                return Integer.compare(b.enchantments.size(), a.enchantments.size());
            case CATEGORY:
                return Integer.compare(categoryRank(a.category), categoryRank(b.category));
            case TIER:
                return Integer.compare(tierRank(a), tierRank(b));
            case ATTACK_DAMAGE:
                return compareAttackDamage(a, b);
            case ENCHANT_PRIORITY:
                return compareEnchantPriority(a, b);
            case CHEST_ODDS:
                return Integer.compare(b.observedCount, a.observedCount);
            case SLOT_ODDS:
                return Integer.compare(b.observedSlotCount, a.observedSlotCount);
            case ITEM_ID:
                return a.id.compareTo(b.id);
            case NAME:
                return stripFormatting(a.displayName).compareToIgnoreCase(stripFormatting(b.displayName));
            default:
                return 0;
        }
    }

    private int compareBaseTieBreak(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b) {
        int c = Integer.compare(categoryRank(a.category), categoryRank(b.category));
        if (c != 0) return c;
        int t = Integer.compare(tierRank(a), tierRank(b));
        if (t != 0) return t;
        int ep = compareEnchantPriority(a, b);
        if (ep != 0) return ep;
        int ec = Integer.compare(b.enchantments.size(), a.enchantments.size());
        if (ec != 0) return ec;
        return compareEntryId(a, b);
    }

    private int compareAttackDamage(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b) {
        int damage = Float.compare(effectiveAttackDamage(b), effectiveAttackDamage(a));
        if (damage != 0) {
            return damage;
        }
        int tier = Integer.compare(tierRank(a), tierRank(b));
        if (tier != 0) {
            return tier;
        }
        return compareEnchantPriority(a, b);
    }

    private float effectiveAttackDamage(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return 0.0F;
        }
        float base = baseAttackDamage(entry);
        if (entry.sharpnessLevel > 0) {
            base += entry.sharpnessLevel * 1.25F;
        }
        return base;
    }

    private float baseAttackDamage(BlitzVaultItemsData.Entry entry) {
        if (entry == null || entry.fallbackTooltip == null) {
            return 0.0F;
        }
        for (String line : entry.fallbackTooltip) {
            String plain = stripFormatting(line).trim().toLowerCase(Locale.US);
            if (!plain.contains("attack damage")) {
                continue;
            }
            java.util.regex.Matcher matcher = ATTACK_DAMAGE_PATTERN.matcher(plain);
            if (matcher.find()) {
                try {
                    return Float.parseFloat(matcher.group(1));
                } catch (Throwable ignored) {
                    return 0.0F;
                }
            }
        }
        return 0.0F;
    }

    private int tagRank(BlitzVaultItemsData.Entry entry) {
        if (matchesAnySelector(entry, preferredKeys)) {
            return 0;
        }
        if (matchesAnySelector(entry, blacklistedKeys)) {
            return 2;
        }
        return 1;
    }

    private boolean matchesAnySelector(BlitzVaultItemsData.Entry entry, List<String> selectors) {
        if (entry == null || selectors == null || selectors.isEmpty()) {
            return false;
        }
        for (String selector : selectors) {
            if (matchesSelector(entry, selector)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSelector(BlitzVaultItemsData.Entry entry, String selectorId) {
        if (entry == null || selectorId == null) {
            return false;
        }
        String[] parts = splitSelector(selectorId);
        if (parts == null) {
            return false;
        }
        String kind = parts[0];
        String value = parts[1];
        if ("category".equals(kind)) {
            return value.equals(entry.category);
        }
        if ("tier".equals(kind)) {
            return value.equals(entry.tier);
        }
        if ("enchant".equals(kind)) {
            return entryHasEnchant(entry, value);
        }
        if ("other".equals(kind)) {
            return "Plain".equals(value) && (entry.enchantments == null || entry.enchantments.isEmpty());
        }
        return false;
    }

    private boolean entryHasEnchant(BlitzVaultItemsData.Entry entry, String expectedBase) {
        if (entry == null || expectedBase == null || entry.enchantments == null || entry.enchantments.isEmpty()) {
            return false;
        }
        for (String raw : entry.enchantments.keySet()) {
            if (expectedBase.equalsIgnoreCase(stripEnchantLevel(raw))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesEnchantInSelectors(String enchantBase, List<String> selectors) {
        if (enchantBase == null || enchantBase.isEmpty() || selectors == null || selectors.isEmpty()) {
            return false;
        }
        for (String selector : selectors) {
            String[] parts = splitSelector(selector);
            if (parts != null && "enchant".equals(parts[0]) && enchantBase.equalsIgnoreCase(parts[1])) {
                return true;
            }
        }
        return false;
    }

    /** Top-priority enchant rules → green; blacklisted enchant rules → red; otherwise gray. */
    private EnumChatFormatting enchantBucketColorForLine(String enchantLine) {
        String base = stripEnchantLevel(stripFormatting(enchantLine).trim());
        if (matchesEnchantInSelectors(base, preferredKeys)) {
            return EnumChatFormatting.GREEN;
        }
        if (matchesEnchantInSelectors(base, blacklistedKeys)) {
            return EnumChatFormatting.RED;
        }
        return EnumChatFormatting.GRAY;
    }

    private String formatEnchantBucketText(String enchantLine) {
        return enchantBucketColorForLine(enchantLine) + abbreviatePattern(enchantLine);
    }

    private String stripEnchantLevel(String label) {
        String text = stripFormatting(label).trim();
        int idx = text.lastIndexOf(' ');
        if (idx > 0) {
            String last = text.substring(idx + 1).trim();
            if (isRomanNumeral(last)) {
                return text.substring(0, idx).trim();
            }
        }
        return text;
    }

    private boolean isRomanNumeral(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = Character.toUpperCase(token.charAt(i));
            if (c != 'I' && c != 'V' && c != 'X' && c != 'L' && c != 'C' && c != 'D' && c != 'M') {
                return false;
            }
        }
        return true;
    }

    private String selectorId(String kind, String value) {
        return kind + "|" + value;
    }

    private String[] splitSelector(String selectorId) {
        int idx = selectorId == null ? -1 : selectorId.indexOf('|');
        if (idx <= 0 || idx >= selectorId.length() - 1) {
            return null;
        }
        return new String[] {selectorId.substring(0, idx), selectorId.substring(idx + 1)};
    }

    private int categoryRank(String category) {
        int idx = BlitzVaultItemsData.getCategoryOrder().indexOf(category);
        return idx < 0 ? BlitzVaultItemsData.getCategoryOrder().size() : idx;
    }

    private int tierRank(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return 999;
        }
        if (isWeaponOrToolCategory(entry.category)) {
            if (BlitzVaultItemsData.TIER_DIAMOND.equals(entry.tier)) return 0;
            if (BlitzVaultItemsData.TIER_IRON.equals(entry.tier)) return 1;
            if (BlitzVaultItemsData.TIER_STONE.equals(entry.tier)) return 2;
            if (BlitzVaultItemsData.TIER_WOOD.equals(entry.tier)) return 3;
            if (BlitzVaultItemsData.TIER_GOLD.equals(entry.tier)) return 4;
            return 5;
        }
        if (isGearCategory(entry.category)) {
            if (BlitzVaultItemsData.TIER_DIAMOND.equals(entry.tier)) return 0;
            if (BlitzVaultItemsData.TIER_IRON.equals(entry.tier)) return 1;
            if (BlitzVaultItemsData.TIER_CHAIN.equals(entry.tier)) return 2;
            if (BlitzVaultItemsData.TIER_GOLD.equals(entry.tier)) return 3;
            if (BlitzVaultItemsData.TIER_LEATHER.equals(entry.tier)) return 4;
            return 5;
        }
        if (BlitzVaultItemsData.TIER_DIAMOND.equals(entry.tier)) return 0;
        if (BlitzVaultItemsData.TIER_IRON.equals(entry.tier)) return 1;
        if (BlitzVaultItemsData.TIER_STONE.equals(entry.tier)) return 2;
        if (BlitzVaultItemsData.TIER_WOOD.equals(entry.tier)) return 3;
        if (BlitzVaultItemsData.TIER_GOLD.equals(entry.tier)) return 4;
        if (BlitzVaultItemsData.TIER_LEATHER.equals(entry.tier)) return 5;
        if (BlitzVaultItemsData.TIER_CHAIN.equals(entry.tier)) return 6;
        return 7;
    }

    private boolean isWeaponOrToolCategory(String category) {
        return BlitzVaultItemsData.CAT_SWORD.equals(category)
                || BlitzVaultItemsData.CAT_AXE.equals(category)
                || BlitzVaultItemsData.CAT_PICKAXE.equals(category)
                || BlitzVaultItemsData.CAT_SHOVEL.equals(category)
                || BlitzVaultItemsData.CAT_HOE.equals(category);
    }

    private boolean isGearCategory(String category) {
        return BlitzVaultItemsData.CAT_HELMET.equals(category)
                || BlitzVaultItemsData.CAT_CHESTPLATE.equals(category)
                || BlitzVaultItemsData.CAT_LEGGINGS.equals(category)
                || BlitzVaultItemsData.CAT_BOOTS.equals(category);
    }

    private int compareEnchantPriority(BlitzVaultItemsData.Entry a, BlitzVaultItemsData.Entry b) {
        if (a == null || b == null) {
            return 0;
        }
        int category = Integer.compare(categoryRank(a.category), categoryRank(b.category));
        if (category != 0) {
            return category;
        }
        if (isWeaponOrToolCategory(a.category) && isWeaponOrToolCategory(b.category)) {
            int sharpness = Integer.compare(b.sharpnessLevel, a.sharpnessLevel);
            if (sharpness != 0) return sharpness;
            int fireAspect = Integer.compare(b.fireAspectLevel, a.fireAspectLevel);
            if (fireAspect != 0) return fireAspect;
            int knockback = Integer.compare(b.knockbackLevel, a.knockbackLevel);
            if (knockback != 0) return knockback;
            return Integer.compare(tierRank(a), tierRank(b));
        }
        if (BlitzVaultItemsData.CAT_BOW.equals(a.category) && BlitzVaultItemsData.CAT_BOW.equals(b.category)) {
            int power = Integer.compare(b.powerLevel, a.powerLevel);
            if (power != 0) return power;
            int flame = Integer.compare(b.flameLevel, a.flameLevel);
            if (flame != 0) return flame;
            int punch = Integer.compare(b.punchLevel, a.punchLevel);
            if (punch != 0) return punch;
            int infinity = Integer.compare(b.infinityLevel, a.infinityLevel);
            if (infinity != 0) return infinity;
            return Integer.compare(tierRank(a), tierRank(b));
        }
        if (isGearCategory(a.category) && isGearCategory(b.category)) {
            int protection = Integer.compare(b.protectionLevel, a.protectionLevel);
            if (protection != 0) return protection;
            int depth = Integer.compare(b.depthStriderLevel, a.depthStriderLevel);
            if (depth != 0) return depth;
            int feather = Integer.compare(b.featherFallingLevel, a.featherFallingLevel);
            if (feather != 0) return feather;
            int proj = Integer.compare(b.projectileProtectionLevel, a.projectileProtectionLevel);
            if (proj != 0) return proj;
            int fire = Integer.compare(b.fireProtectionLevel, a.fireProtectionLevel);
            if (fire != 0) return fire;
            int thorns = Integer.compare(b.thornsLevel, a.thornsLevel);
            if (thorns != 0) return thorns;
            int blast = Integer.compare(b.blastProtectionLevel, a.blastProtectionLevel);
            if (blast != 0) return blast;
            int respiration = Integer.compare(b.respirationLevel, a.respirationLevel);
            if (respiration != 0) return respiration;
            int aqua = Integer.compare(b.aquaAffinityLevel, a.aquaAffinityLevel);
            if (aqua != 0) return aqua;
            int unbreaking = Integer.compare(b.unbreakingLevel, a.unbreakingLevel);
            if (unbreaking != 0) return unbreaking;
            return Integer.compare(tierRank(a), tierRank(b));
        }
        return Integer.compare(categoryRank(a.category), categoryRank(b.category));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!dataInitComplete) {
            BlitzVaultItemsData.pumpBuildBatch(BlitzVaultItemsData.BUILD_BATCH_GUI);
        }
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
        if (preferredRuleField != null) {
            preferredRuleField.updateCursorCounter();
        }
        if (blacklistedRuleField != null) {
            blacklistedRuleField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        hoveredUiTooltip = null;
        hoveredChestSlot = null;

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        if (!dataInitComplete) {
            tryCompleteDataInit(sr);
        }
        if (!dataInitComplete) {
            drawLoadingScreen(sr);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }
        layoutTabs(sr);
        layoutGrid(sr);
        rebuildFilteredIfNeeded();
        rebuildSummaryCachesIfNeeded();

        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Blitz Vault Items"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(),
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        int totalCaptures = BlitzVaultItemsData.getObservedValidCaptures();
        int totalSlots = BlitzVaultItemsData.getObservedSlotCaptures();
        String vaultCountLine = EnumChatFormatting.DARK_GRAY + "Vault chests data: "
                + EnumChatFormatting.GRAY + totalCaptures
                + EnumChatFormatting.DARK_GRAY + " | Total items data: "
                + EnumChatFormatting.GRAY + totalSlots;
        int vaultCountWidth = fontRendererObj.getStringWidth(vaultCountLine);
        fontRendererObj.drawStringWithShadow(vaultCountLine,
                (sr.getScaledWidth() - vaultCountWidth) / 2, GuiHeader.STANDARD_TITLE_Y + 12, 0xFFFFFFFF);

        drawSearchBar(mouseX, mouseY);
        drawTabs(mouseX, mouseY);
        drawSummaryPanels(sr, mouseX, mouseY);

        if (chestBrowserMode) {
            drawChestBrowser(sr, mouseX, mouseY);
            drawChestSortPanel(mouseX, mouseY);
            super.drawScreen(mouseX, mouseY, partialTicks);
            if (hoveredChestSlot != null) {
                renderItemTooltip(hoveredChestSlot.stack, hoveredChestSlot.fallbackTooltip, mouseX, mouseY, null);
            } else if (hoveredUiTooltip != null && !hoveredUiTooltip.isEmpty()) {
                renderUiTooltip(hoveredUiTooltip, mouseX, mouseY);
            }
            return;
        }

        int contentBottom = GuiHeader.contentBottomAboveBackBar(sr.getScaledHeight(), BUTTON_HEIGHT, 18);
        int gridBottom = Math.min(contentBottom - 14, gridTopY + gridVisibleHeight);
        gridVisibleHeight = Math.max(SLOT_SIZE, gridBottom - gridTopY);

        int rows = (filtered.size() + Math.max(1, columns) - 1) / Math.max(1, columns);
        gridContentHeight = Math.max(SLOT_SIZE, rows * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP);

        scroll.tick();
        scroll.updateBounds(gridTopY, gridTopY + gridContentHeight, gridTopY, gridTopY + gridVisibleHeight);
        scroll.drawScrollbarAt(gridLeftX + gridWidth + ITEM_SCROLLBAR_GAP, mouseY,
                ITEM_SCROLLBAR_W, 0x35FFFFFF, 0xF2FFFFFF);

        drawGrid(mouseX, mouseY);
        drawLeftManagePanel(mouseX, mouseY);
        drawSortPanel(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (hoveredChestSlot != null) {
            renderItemTooltip(hoveredChestSlot.stack, hoveredChestSlot.fallbackTooltip, mouseX, mouseY, null);
        } else if (hoveredEntry != null) {
            renderItemTooltip(hoveredEntry, mouseX, mouseY);
        } else if (hoveredUiTooltip != null && !hoveredUiTooltip.isEmpty()) {
            renderUiTooltip(hoveredUiTooltip, mouseX, mouseY);
        }
    }

    private void drawSearchBar(int mouseX, int mouseY) {
        if (searchField == null) return;
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
            fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Search items, enchants, materials...",
                    searchField.xPosition + 1, searchField.yPosition + 2, 0xFFFFFFFF);
        }
        searchField.drawTextBox();
    }

    private void drawTabs(int mouseX, int mouseY) {
        for (TabRect tab : tabs) {
            boolean active = FILTER_CHESTS.equals(tab.label) ? chestBrowserMode
                    : (!chestBrowserMode && isCategoryFilterActive(tab.label));
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
    }

    private ItemStack drawGrid(int mouseX, int mouseY) {
        int panelLeft = gridLeftX - 4;
        int panelTop = gridTopY - 4;
        int panelRight = gridLeftX + gridWidth + ITEM_SCROLLBAR_GAP + ITEM_SCROLLBAR_W + 4;
        int panelBottom = gridTopY + gridVisibleHeight + 4;
        Gui.drawRect(panelLeft, panelTop, panelRight, panelBottom, COL_PANEL_BG);
        drawHollowBorder(panelLeft, panelTop, panelRight, panelBottom, COL_PANEL_BORDER);

        if (filtered.isEmpty()) {
            String msg = EnumChatFormatting.DARK_GRAY + "No items match your search.";
            int mw = fontRendererObj.getStringWidth(msg);
            fontRendererObj.drawStringWithShadow(msg, gridLeftX + (gridWidth - mw) / 2,
                    gridTopY + gridVisibleHeight / 2 - 4, 0xFFFFFFFF);
            return null;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ItemStack hoveredStack = null;
        hoveredEntry = null;
        int hoverX = -1;
        int hoverY = -1;

        scissor(mc, gridLeftX, gridTopY, gridWidth, gridVisibleHeight);
        try {
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
                    int x = gridLeftX + col * (SLOT_SIZE + SLOT_GAP);
                    int y = gridTopY + row * (SLOT_SIZE + SLOT_GAP) - scrollOffset;
                    if (y + SLOT_SIZE < gridTopY - 2 || y > gridTopY + gridVisibleHeight + 2) {
                        continue;
                    }
                    BlitzVaultItemsData.Entry entry = filtered.get(i);
                    boolean hover = mouseX >= x && mouseX < x + SLOT_SIZE
                            && mouseY >= y && mouseY < y + SLOT_SIZE
                            && mouseX >= gridLeftX && mouseX < gridLeftX + gridWidth
                            && mouseY >= gridTopY && mouseY < gridTopY + gridVisibleHeight;

                    drawSlotBackground(x, y, hover, tagRank(entry));

                    HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, entry.stack, x + 1, y + 1);
                    if (entry.stack != null) {
                        try {
                            mc.getRenderItem().renderItemOverlayIntoGUI(fontRendererObj, entry.stack, x + 1, y + 1, null);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hover) {
                        hoveredStack = entry.stack;
                        hoveredEntry = entry;
                        hoverX = x;
                        hoverY = y;
                    }
                }
            } finally {
                HudGuiItemIcon.endGuiScreenItemBatch(mc);
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
        return hoveredStack;
    }

    private void drawChestBrowser(ScaledResolution sr, int mouseX, int mouseY) {
        List<BlitzVaultItemsData.VaultChest> chests = BlitzVaultItemsData.getVaultChests();
        int contentTop = gridTopY - 4;
        int contentBottom = GuiHeader.contentBottomAboveBackBar(sr.getScaledHeight(), BUTTON_HEIGHT, 18) - 8;
        int contentLeft = 16;
        int sortReserve = getRightPanelW() >= 120 ? getRightPanelW() + ITEM_SIDE_PANEL_GAP : 0;
        int contentRight = sr.getScaledWidth() - 16 - sortReserve;
        int contentH = Math.max(70, contentBottom - contentTop);

        Gui.drawRect(contentLeft, contentTop, contentRight, contentBottom, COL_PANEL_BG);
        drawHollowBorder(contentLeft, contentTop, contentRight, contentBottom, COL_PANEL_BORDER);

        if (chests.isEmpty()) {
            chestGalleryW = chestGalleryH = 0;
            String title = EnumChatFormatting.YELLOW + "Raw chest snapshots are not bundled in this build.";
            String line1 = EnumChatFormatting.GRAY + "This dataset has aggregate counts for "
                    + BlitzVaultItemsData.getObservedValidCaptures() + " vault chests, but not the exact per-chest contents.";
            String line2 = EnumChatFormatting.DARK_GRAY + "Re-run tools/merge_blitz_vault_chest_dumps.py after this update to write vault_chests into the JSON.";
            int y = contentTop + contentH / 2 - 18;
            drawCenteredLine(title, sr.getScaledWidth() / 2, y);
            drawCenteredLine(line1, sr.getScaledWidth() / 2, y + 14);
            drawCenteredLine(line2, sr.getScaledWidth() / 2, y + 26);
            return;
        }

        rebuildSortedChestsIfNeeded();
        chestGalleryX = contentLeft + 6;
        chestGalleryY = contentTop + 24;
        chestGalleryW = Math.max(80, contentRight - chestGalleryX - CHEST_GALLERY_SCROLLBAR_GAP
                - CHEST_GALLERY_SCROLLBAR_W - 8);
        chestGalleryH = Math.max(24, contentBottom - chestGalleryY - 6);

        String header = EnumChatFormatting.GOLD + "Chest Contents";
        fontRendererObj.drawStringWithShadow(header, contentLeft + 8, contentTop + 8, 0xFFFFFFFF);
        String countLine = EnumChatFormatting.DARK_GRAY + "Sorted by "
                + EnumChatFormatting.GRAY + chestSortMode.label
                + EnumChatFormatting.DARK_GRAY + " | "
                + EnumChatFormatting.GRAY + sortedChests.size();
        fontRendererObj.drawStringWithShadow(countLine,
                contentRight - 8 - fontRendererObj.getStringWidth(countLine), contentTop + 8, 0xFFFFFFFF);

        drawChestGallery(mouseX, mouseY);
    }

    private void drawCenteredLine(String text, int centerX, int y) {
        fontRendererObj.drawStringWithShadow(text, centerX - fontRendererObj.getStringWidth(text) / 2, y, 0xFFFFFFFF);
    }

    private void rebuildSortedChestsIfNeeded() {
        List<BlitzVaultItemsData.VaultChest> all = BlitzVaultItemsData.getVaultChests();
        String key = chestSortMode.name() + "|" + all.size();
        if (key.equals(lastChestSortCacheKey)) {
            return;
        }
        lastChestSortCacheKey = key;
        List<BlitzVaultItemsData.VaultChest> next = new ArrayList<BlitzVaultItemsData.VaultChest>(all);
        Collections.sort(next, new Comparator<BlitzVaultItemsData.VaultChest>() {
            @Override
            public int compare(BlitzVaultItemsData.VaultChest a, BlitzVaultItemsData.VaultChest b) {
                if (chestSortMode == ChestSortMode.CHEST_NUMBER) {
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
        chestGalleryScroll.reset();
    }

    private int chestSortMetric(BlitzVaultItemsData.VaultChest chest) {
        if (chest == null) {
            return 0;
        }
        switch (chestSortMode) {
            case DIAMOND_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_DIAMOND);
            case IRON_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_IRON);
            case CHAINMAIL_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_CHAIN);
            case GOLD_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_GOLD);
            case LEATHER_ARMOR:
                return countArmorInChest(chest, BlitzVaultItemsData.TIER_LEATHER);
            case DIAMOND_HELMET:
                return countArmorPieceInChest(chest, BlitzVaultItemsData.CAT_HELMET, BlitzVaultItemsData.TIER_DIAMOND);
            case DIAMOND_CHESTPLATE:
                return countArmorPieceInChest(chest, BlitzVaultItemsData.CAT_CHESTPLATE, BlitzVaultItemsData.TIER_DIAMOND);
            case DIAMOND_LEGGINGS:
                return countArmorPieceInChest(chest, BlitzVaultItemsData.CAT_LEGGINGS, BlitzVaultItemsData.TIER_DIAMOND);
            case DIAMOND_BOOTS:
                return countArmorPieceInChest(chest, BlitzVaultItemsData.CAT_BOOTS, BlitzVaultItemsData.TIER_DIAMOND);
            case BOWS:
                return countCategoryInChest(chest, BlitzVaultItemsData.CAT_BOW);
            case SWORDS:
                return countCategoryInChest(chest, BlitzVaultItemsData.CAT_SWORD);
            case CHEST_NUMBER:
            default:
                return chest.index;
        }
    }

    private static int countCategoryInChest(BlitzVaultItemsData.VaultChest chest, String category) {
        int n = 0;
        for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
            if (category.equals(BlitzVaultItemsData.classifyItemCategory(slot.id))) {
                n++;
            }
        }
        return n;
    }

    private static int countArmorInChest(BlitzVaultItemsData.VaultChest chest, String tier) {
        int n = 0;
        for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
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

    private static int countArmorPieceInChest(BlitzVaultItemsData.VaultChest chest, String category, String tier) {
        int n = 0;
        for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
            if (category.equals(BlitzVaultItemsData.classifyItemCategory(slot.id))
                    && tier.equals(BlitzVaultItemsData.classifyItemTier(slot.id))) {
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

    private int chestGridRows(BlitzVaultItemsData.VaultChest chest) {
        return Math.max(1, (Math.max(chest.slotCount, highestChestSlot(chest) + 1) + 8) / 9);
    }

    private int chestGridHeight(BlitzVaultItemsData.VaultChest chest) {
        int rows = chestGridRows(chest);
        return rows * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
    }

    private int chestBlockHeight(BlitzVaultItemsData.VaultChest chest) {
        return CHEST_BLOCK_HEADER_H + chestGridHeight(chest) + CHEST_BLOCK_PAD_BOTTOM;
    }

    private void drawChestGallery(int mouseX, int mouseY) {
        if (chestGalleryW <= 0 || chestGalleryH <= 0) {
            return;
        }
        Gui.drawRect(chestGalleryX, chestGalleryY, chestGalleryX + chestGalleryW, chestGalleryY + chestGalleryH,
                0xA00A120A);
        drawHollowBorder(chestGalleryX, chestGalleryY, chestGalleryX + chestGalleryW, chestGalleryY + chestGalleryH,
                COL_PANEL_BORDER);

        chestGalleryContentH = 0;
        for (BlitzVaultItemsData.VaultChest chest : sortedChests) {
            chestGalleryContentH += chestBlockHeight(chest);
        }
        chestGalleryContentH = Math.max(chestGalleryH, chestGalleryContentH);

        int scrollTrackX = chestGalleryX + chestGalleryW - CHEST_GALLERY_SCROLLBAR_W - CHEST_GALLERY_SCROLLBAR_INSET;
        int innerRight = scrollTrackX - CHEST_GALLERY_SCROLLBAR_GAP;
        int scrollTrackTop = chestGalleryY + CHEST_GALLERY_SCROLLBAR_INSET;
        int scrollTrackBottom = chestGalleryY + chestGalleryH - CHEST_GALLERY_SCROLLBAR_INSET;

        chestGalleryScroll.tick();
        chestGalleryScroll.updateBounds(chestGalleryY, chestGalleryY + chestGalleryContentH,
                scrollTrackTop, scrollTrackBottom);
        chestGalleryScroll.drawScrollbarAt(scrollTrackX, scrollTrackTop, scrollTrackBottom, mouseY,
                CHEST_GALLERY_SCROLLBAR_W, 0x30FFFFFF, 0xEFFFFFFF);

        int offset = chestGalleryScroll.getOffset();
        int clipW = Math.max(1, innerRight - chestGalleryX - 2);
        scissor(Minecraft.getMinecraft(), chestGalleryX + 1, chestGalleryY + 1, clipW, chestGalleryH - 2);
        try {
            int blockY = chestGalleryY + 4 - offset;
            for (BlitzVaultItemsData.VaultChest chest : sortedChests) {
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

    private void drawChestBlock(BlitzVaultItemsData.VaultChest chest, int x, int y, int w,
                                int mouseX, int mouseY) {
        int metric = chestSortMetric(chest);
        String title = EnumChatFormatting.AQUA + "Chest #" + chest.index;
        if (chestSortMode != ChestSortMode.CHEST_NUMBER && metric > 0) {
            title += EnumChatFormatting.DARK_GRAY + "  (" + EnumChatFormatting.GRAY + metric
                    + EnumChatFormatting.DARK_GRAY + ")";
        }
        fontRendererObj.drawStringWithShadow(title, x + 4, y + 2, 0xFFFFFFFF);

        int rows = chestGridRows(chest);
        int gridW = 9 * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
        int gx = x + Math.max(0, (w - gridW) / 2);
        int gy = y + CHEST_BLOCK_HEADER_H;

        Map<Integer, BlitzVaultItemsData.VaultSlot> bySlot = new HashMap<Integer, BlitzVaultItemsData.VaultSlot>();
        for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
            bySlot.put(slot.slot, slot);
        }

        Minecraft mc = Minecraft.getMinecraft();
        HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        try {
            for (int i = 0; i < rows * 9; i++) {
                int sx = gx + (i % 9) * (SLOT_SIZE + SLOT_GAP);
                int sy = gy + (i / 9) * (SLOT_SIZE + SLOT_GAP);
                BlitzVaultItemsData.VaultSlot slot = bySlot.get(i);
                boolean hover = mouseX >= sx && mouseX < sx + SLOT_SIZE
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE
                        && mouseX >= chestGalleryX && mouseX < chestGalleryX + chestGalleryW
                        && mouseY >= chestGalleryY && mouseY < chestGalleryY + chestGalleryH;
                drawSlotBackground(sx, sy, hover, 0);
                if (slot != null && slot.stack != null) {
                    HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, slot.stack, sx + 1, sy + 1);
                    if (hover) {
                        hoveredChestSlot = slot;
                    }
                }
            }
        } finally {
            HudGuiItemIcon.endGuiScreenItemBatch(mc);
        }
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

        Minecraft mc = Minecraft.getMinecraft();
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
        HudGlState.restoreForOverlayText();
        HudGuiItemIcon.beginGuiScreenItemBatch(mc);
        try {
            for (ChestSortRowRect row : chestSortRows) {
                drawChestSortRowLitIcons(mc, row.x + row.w - 4, row.y, row.h, row.mode.sortIconStacks());
            }
        } finally {
            HudGuiItemIcon.endGuiScreenItemBatch(mc);
        }
        HudGlState.restoreForOverlayText();
        for (ChestSortRowRect row : chestSortRows) {
            drawChestSortRowBlockIcons(mc, row.x + row.w - 4, row.y, row.h, row.mode.sortIconStacks());
        }
        unscissor();

        if (chestSortMaxScrollPx > 0) {
            drawMiniVerticalScrollbar(x + w - 5, listTop, 3, listH, chestSortScrollPx, chestSortMaxScrollPx);
        }
    }

    private void drawChestSortRowLitIcons(Minecraft mc, int rightEdge, int rowY, int rowH, ItemStack[] icons) {
        if (mc == null || icons == null || icons.length == 0) {
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

    private void drawChestSortRowBlockIcons(Minecraft mc, int rightEdge, int rowY, int rowH, ItemStack[] icons) {
        if (mc == null || icons == null || icons.length == 0) {
            return;
        }
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
        int drawn = 0;
        for (ItemStack stack : icons) {
            if (!isChestSortBlockIcon(stack)) {
                continue;
            }
            int ix = startX + drawn * (CHEST_SORT_ICON_DRAW + CHEST_SORT_ICON_GAP);
            HudGuiItemIcon.drawInventoryHudSlot(mc, stack, (float) ix, (float) iconY, scale);
            drawn++;
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
                ConfigManager.setBlitzVaultItemsChestSortMode(chestSortMode.name());
                return true;
            }
        }
        return false;
    }

    private boolean isMouseInsideChestSortPanel(int mouseX, int mouseY) {
        return isMouseInsideSortPanel(mouseX, mouseY);
    }

    private int highestChestSlot(BlitzVaultItemsData.VaultChest chest) {
        int max = -1;
        if (chest != null) {
            for (BlitzVaultItemsData.VaultSlot slot : chest.slots) {
                max = Math.max(max, slot.slot);
            }
        }
        return max;
    }

    private int getLeftPanelX() {
        return 12;
    }

    private int getLeftPanelY() {
        return gridTopY - 4;
    }

    private int getLeftPanelW() {
        return Math.max(0, gridLeftX - getLeftPanelX() - 10);
    }

    private void drawLeftManagePanel(int mouseX, int mouseY) {
        int x = getLeftPanelX();
        int y = getLeftPanelY();
        int w = getLeftPanelW();
        int h = getSidePanelH();
        preferredRows.clear();
        blacklistedRows.clear();
        ruleOptionRows.clear();
        if (w < 120 || h < 100) {
            prefTaggedWheelH = 0;
            blTaggedWheelH = 0;
            prefDropdownWheelH = 0;
            blDropdownWheelH = 0;
            return;
        }

        Gui.drawRect(x, y, x + w, y + h, COL_PANEL_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_PANEL_BORDER);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Item Buckets", x + 6, y + 6, 0xFFFFFFFF);

        int sectionX = x + 6;
        int sectionW = Math.max(60, w - 12);
        int maxY = y + h - 8;

        int blacklistZoneTop = maxY - MANAGE_BLACKLIST_RESERVED;
        if (blacklistZoneTop < y + 48) {
            blacklistZoneTop = Math.min(maxY - 40, y + 48);
        }
        // Ensure Top Priority section always has enough height to show its list (rows were being clipped entirely).
        int preferredTop = y + 23;
        int minPreferredBottom = preferredTop + 72;
        if (blacklistZoneTop < minPreferredBottom) {
            blacklistZoneTop = Math.min(maxY - 40, minPreferredBottom);
        }

        prefDropdownWheelH = blDropdownWheelH = 0;
        prefDropdownMaxScrollIdx = blDropdownMaxScrollIdx = 0;

        drawRuleSection(true, sectionX, preferredTop, sectionW, blacklistZoneTop, mouseX, mouseY);
        drawRuleSection(false, sectionX, blacklistZoneTop + 6, sectionW, maxY, mouseX, mouseY);
    }

    private void drawRuleSection(boolean preferred, int x, int y, int w, int listAreaBottom, int mouseX, int mouseY) {
        GuiTextField field = preferred ? preferredRuleField : blacklistedRuleField;
        List<String> selectors = preferred ? preferredKeys : blacklistedKeys;
        List<TaggedRowRect> rows = preferred ? preferredRows : blacklistedRows;
        String title = preferred ? EnumChatFormatting.GREEN + "Top Priority" : EnumChatFormatting.RED + "Blacklisted";

        fontRendererObj.drawStringWithShadow(title + EnumChatFormatting.DARK_GRAY + " (" + selectors.size() + ")", x, y, 0xFFFFFFFF);

        int fieldBlockH = 21;
        int fieldY = listAreaBottom - fieldBlockH;
        if (fieldY < y + 22) {
            fieldY = y + 22;
        }
        int listTop = y + 11;
        int listH = fieldY - listTop - 4;
        int rowH = 14;
        int rowStride = TAGGED_ROW_STRIDE;

        if (listH < 10) {
            if (preferred) {
                prefTaggedWheelH = 0;
                prefTaggedMaxScrollPx = 0;
            } else {
                blTaggedWheelH = 0;
                blTaggedMaxScrollPx = 0;
            }
            layoutRuleField(field, x, fieldY, w);
            drawRuleField(field, preferred, mouseX, mouseY);
            return;
        }

        int contentH = selectors.isEmpty() ? 0 : selectors.size() * rowStride - 2;
        int maxScrollPx = Math.max(0, contentH - listH);
        if (preferred) {
            preferredTaggedScrollPx = Math.min(Math.max(0, preferredTaggedScrollPx), maxScrollPx);
            prefTaggedWheelX = x;
            prefTaggedWheelY = listTop;
            prefTaggedWheelW = w;
            prefTaggedWheelH = listH;
            prefTaggedMaxScrollPx = maxScrollPx;
        } else {
            blacklistedTaggedScrollPx = Math.min(Math.max(0, blacklistedTaggedScrollPx), maxScrollPx);
            blTaggedWheelX = x;
            blTaggedWheelY = listTop;
            blTaggedWheelW = w;
            blTaggedWheelH = listH;
            blTaggedMaxScrollPx = maxScrollPx;
        }
        int scrollPx = preferred ? preferredTaggedScrollPx : blacklistedTaggedScrollPx;

        Minecraft mc = Minecraft.getMinecraft();
        scissor(mc, x, listTop, w, listH);
        try {
            int drawY = listTop - scrollPx;
            for (int i = 0; i < selectors.size(); i++) {
                if (drawY + rowH >= listTop && drawY < listAreaBottom) {
                    TaggedRowRect row = new TaggedRowRect(preferred, i, selectors.get(i), x, drawY, w, rowH);
                    rows.add(row);
                    drawTaggedRow(row, mouseX, mouseY);
                }
                drawY += rowStride;
            }
        } finally {
            unscissor();
        }

        if (maxScrollPx > 0) {
            drawMiniVerticalScrollbar(x + w - 5, listTop, 4, listH, scrollPx, maxScrollPx);
        }

        layoutRuleField(field, x, fieldY, w);
        drawRuleField(field, preferred, mouseX, mouseY);
        if (field != null && field.isFocused()) {
            int dropdownBottom = fieldY - 2;
            int dropdownTop = Math.max(listTop, dropdownBottom - 78);
            drawRuleDropdown(preferred, field, x, dropdownTop, w, dropdownBottom, mouseX, mouseY);
        }
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

    private void layoutRuleField(GuiTextField field, int x, int y, int w) {
        if (field == null) {
            return;
        }
        field.xPosition = x + 4;
        field.yPosition = y + 5;
        field.width = Math.max(20, w - 8);
        field.height = 12;
    }

    private void drawRuleField(GuiTextField field, boolean preferred, int mouseX, int mouseY) {
        if (field == null) {
            return;
        }
        int barX = field.xPosition - 4;
        int barY = field.yPosition - 5;
        int barW = field.width + 8;
        int barH = 18;
        int bg = preferred ? 0xA0203F20 : 0xA03F2020;
        int border = preferred
                ? (field.isFocused() ? COL_BAR_FOCUS : COL_TAB_BORDER_ACTIVE)
                : (field.isFocused() ? 0xFFFFA0A0 : 0xFFB55B5B);
        Gui.drawRect(barX, barY, barX + barW, barY + barH, bg);
        drawHollowBorder(barX, barY, barX + barW, barY + barH, border);
        if (!field.isFocused() && (field.getText() == null || field.getText().isEmpty())) {
            fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Enchant, tier, category...", field.xPosition, field.yPosition + 1, 0xFFFFFFFF);
        }
        field.drawTextBox();
    }

    private int drawRuleDropdown(boolean preferred, GuiTextField field, int x, int y, int w, int listAreaBottom, int mouseX, int mouseY) {
        List<RuleOptionDef> options = getFilteredRuleOptions(field.getText());
        if (options.isEmpty()) {
            if (preferred) {
                prefDropdownWheelH = 0;
            } else {
                blDropdownWheelH = 0;
            }
            return y;
        }
        int rowH = 12;
        int space = listAreaBottom - y;
        if (space < rowH + 4) {
            if (preferred) {
                prefDropdownWheelH = 0;
            } else {
                blDropdownWheelH = 0;
            }
            return y;
        }
        int maxRows = Math.max(1, Math.min(6, space / (rowH + 1)));
        int maxScrollIdx = Math.max(0, options.size() - maxRows);
        int scrollIdx = preferred ? preferredDropdownScrollIndex : blacklistedDropdownScrollIndex;
        if (scrollIdx > maxScrollIdx) {
            scrollIdx = maxScrollIdx;
            if (preferred) {
                preferredDropdownScrollIndex = scrollIdx;
            } else {
                blacklistedDropdownScrollIndex = scrollIdx;
            }
        }
        int rowsToDraw = Math.min(maxRows, options.size() - scrollIdx);
        int boxH = rowsToDraw * (rowH + 1) + 3;
        Gui.drawRect(x, y, x + w, y + boxH, COL_PANEL_BG);
        drawHollowBorder(x, y, x + w, y + boxH, preferred ? COL_TAB_BORDER_ACTIVE : 0xFFB55B5B);
        int rowY = y + 2;
        for (int i = 0; i < rowsToDraw; i++) {
            RuleOptionDef option = options.get(scrollIdx + i);
            boolean hover = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= rowY && mouseY < rowY + rowH;
            int bg = hover ? (preferred ? 0xA0336633 : 0xA0663333) : (preferred ? 0x90224422 : 0x90442222);
            Gui.drawRect(x + 1, rowY, x + w - 1, rowY + rowH, bg);
            ruleOptionRows.add(new RuleOptionRect(preferred, option.id, x + 1, rowY, w - 2, rowH));
            fontRendererObj.drawString(trimToWidth(option.label, w - 8), x + 4, rowY + 2, 0xFFFFFFFF);
            rowY += rowH + 1;
        }
        if (preferred) {
            prefDropdownWheelX = x;
            prefDropdownWheelY = y;
            prefDropdownWheelW = w;
            prefDropdownWheelH = boxH;
            prefDropdownMaxScrollIdx = maxScrollIdx;
        } else {
            blDropdownWheelX = x;
            blDropdownWheelY = y;
            blDropdownWheelW = w;
            blDropdownWheelH = boxH;
            blDropdownMaxScrollIdx = maxScrollIdx;
        }
        if (maxScrollIdx > 0) {
            drawMiniVerticalScrollbar(x + w - 6, y + 2, 3, Math.max(8, boxH - 4), scrollIdx, maxScrollIdx);
        }
        return y + boxH + 3;
    }

    private List<RuleOptionDef> getFilteredRuleOptions(String query) {
        String q = query == null ? "" : stripFormatting(query).trim().toLowerCase(Locale.US);
        List<RuleOptionDef> out = new ArrayList<RuleOptionDef>();
        for (RuleOptionDef option : allRuleOptions) {
            if (option == null) {
                continue;
            }
            if (q.isEmpty() || option.filterText.contains(q)) {
                out.add(option);
            }
        }
        return out;
    }

    private void drawTaggedRow(TaggedRowRect row, int mouseX, int mouseY) {
        int bg = row.preferred ? 0xCC2B5E2B : 0xCC7A2D2D;
        if (row.contains(mouseX, mouseY)) {
            bg = row.preferred ? 0xFF3A7A3A : 0xFF9A3A3A;
        }
        Gui.drawRect(row.x, row.y, row.x + row.w, row.y + row.h, bg);
        drawHollowBorder(row.x, row.y, row.x + row.w, row.y + row.h, row.preferred ? 0xFF9BEE9B : 0xFFFFA0A0);

        drawMiniRemoveButton(row.removeX(), row.miniY(), 10, row.miniH(), mouseX, mouseY, true);
        String label = resolveSelectorLabel(row.key);
        int labelW = Math.max(20, row.removeX() - row.x - 8);
        int labelY = row.y + Math.max(1, (row.h - 8) / 2);
        fontRendererObj.drawString(trimToWidth(label, labelW), row.x + 4, labelY, 0xFFFFFFFF);
    }

    private String resolveSelectorLabel(String selectorId) {
        for (RuleOptionDef option : allRuleOptions) {
            if (option != null && selectorId.equals(option.id)) {
                return EnumChatFormatting.WHITE + option.label;
            }
        }
        String[] parts = splitSelector(selectorId);
        if (parts == null) {
            return EnumChatFormatting.DARK_GRAY + "Missing rule";
        }
        return EnumChatFormatting.WHITE + capitalize(parts[0]) + ": " + parts[1];
    }

    private int getRightPanelX() {
        return gridLeftX + gridWidth + ITEM_SCROLLBAR_GAP + ITEM_SCROLLBAR_W + ITEM_SIDE_PANEL_GAP;
    }

    private int getRightPanelY() {
        return gridTopY - 4;
    }

    private int getRightPanelW() {
        return Math.max(0, (width - 18) - getRightPanelX());
    }

    private int getSidePanelH() {
        return gridVisibleHeight + 8;
    }

    private void drawSortPanel(int mouseX, int mouseY) {
        int x = getRightPanelX();
        int y = getRightPanelY();
        int w = getRightPanelW();
        int h = getSidePanelH();
        sortRows.clear();
        addSortBtnW = addSortBtnH = resetSortBtnW = resetSortBtnH = 0;
        if (w < 120 || h < 80) {
            return;
        }

        Gui.drawRect(x, y, x + w, y + h, COL_PANEL_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_PANEL_BORDER);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.AQUA + "Sort Priorities", x + 6, y + 6, 0xFFFFFFFF);
        fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Top row wins first.", x + 6, y + 18, 0xFFFFFFFF);

        int rowX = x + 6;
        int rowW = Math.max(60, w - 12);
        int listTop = y + 31;
        int listBottom = y + h - 28;
        int rowCount = Math.max(1, sortKeys.size());
        int availableForRows = Math.max(54, listBottom - listTop);
        int rowGap = 2;
        int rowH = Math.min(18, Math.max(10,
                (availableForRows - rowGap * Math.max(0, rowCount - 1)) / rowCount));
        int rowStep = rowH + rowGap;

        int rowY = listTop;
        for (int i = 0; i < sortKeys.size(); i++) {
            SortRowRect row = new SortRowRect(i, sortKeys.get(i), rowX, rowY, rowW, rowH);
            sortRows.add(row);
            drawSortRow(row, mouseX, mouseY);
            rowY += rowStep;
        }

        int buttonsY = y + h - 24;
        drawSortFooterButtons(x, buttonsY, w, mouseX, mouseY);
    }

    private void drawSortRow(SortRowRect row, int mouseX, int mouseY) {
        boolean hover = row.contains(mouseX, mouseY);
        int bg = hover ? COL_TAB_BG_HOVER : COL_TAB_BG;
        Gui.drawRect(row.x, row.y, row.x + row.w, row.y + row.h, bg);
        drawHollowBorder(row.x, row.y, row.x + row.w, row.y + row.h, COL_TAB_BORDER);

        int miniY = row.miniY();
        int miniH = row.miniH();
        drawMiniArrowButton(row.upX(), miniY, row.miniW(), miniH, true, mouseX, mouseY, row.index > 0);
        drawMiniArrowButton(row.downX(), miniY, row.miniW(), miniH, false, mouseX, mouseY, row.index < sortKeys.size() - 1);
        drawMiniRemoveButton(row.removeX(), miniY, row.miniW(), miniH, mouseX, mouseY, sortKeys.size() > 1);

        String label = EnumChatFormatting.GRAY + "" + (row.index + 1) + ". " + EnumChatFormatting.WHITE + row.key.label;
        int labelX = row.labelX();
        int labelW = Math.max(20, row.removeX() - 4 - labelX);
        int labelY = row.y + Math.max(2, (row.h - 8) / 2);
        fontRendererObj.drawString(trimToWidth(label, labelW), labelX, labelY, 0xFFFFFFFF);
        if (row.key == SortKey.ENCHANT_PRIORITY
                && row.labelContains(mouseX, mouseY)
                && !row.upContains(mouseX, mouseY)
                && !row.downContains(mouseX, mouseY)
                && !row.removeContains(mouseX, mouseY)) {
            hoveredUiTooltip = buildEnchantPriorityTooltip();
        }
    }

    private void drawMiniArrowButton(int x, int y, int w, int h, boolean up, int mouseX, int mouseY, boolean active) {
        drawMiniWidgetChrome(x, y, w, h, mouseX, mouseY, active);
        ResourceLocation texture = up ? UP_ARROW_ICON_TEXTURE : DOWN_ARROW_ICON_TEXTURE;
        ResourceLocation fallback = up ? UP_ARROW_ICON_TEXTURE_FALLBACK : DOWN_ARROW_ICON_TEXTURE_FALLBACK;
        String classpath = up
                ? "/assets/gomod123/textures/uparrow10.png"
                : "/assets/gomod123/textures/downarrow10.png";
        if (drawMiniIcon(x, y, w, h, texture, fallback, classpath, MINI_ICON_10_CANVAS_W, MINI_ICON_10_CANVAS_H,
                active ? 1.0F : 0.35F, MINI_BUTTON_ICON_DRAW_W, MINI_BUTTON_ICON_DRAW_H)) {
            return;
        }
        String text = up ? "^" : "v";
        int textX = x + (w - fontRendererObj.getStringWidth(text)) / 2;
        int textY = y + Math.max(0, (h - fontRendererObj.FONT_HEIGHT) / 2);
        fontRendererObj.drawString(text, textX, textY, active ? 0xFFFFFFFF : 0xFF666666);
    }

    private void drawMiniRemoveButton(int x, int y, int w, int h, int mouseX, int mouseY, boolean active) {
        drawMiniWidgetChrome(x, y, w, h, mouseX, mouseY, active);
        if (!drawMiniIcon(x, y, w, h, DELETE_ICON_TEXTURE, DELETE_ICON_TEXTURE_FALLBACK,
                "/assets/gomod123/textures/deleteicon10.png", MINI_ICON_10_CANVAS_W, MINI_ICON_10_CANVAS_H,
                active ? (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h ? 1.0F : 0.82F) : 0.35F,
                MINI_BUTTON_ICON_DRAW_W, MINI_BUTTON_ICON_DRAW_H)) {
            int textX = x + (w - fontRendererObj.getStringWidth("x")) / 2;
            int textY = y + Math.max(0, (h - fontRendererObj.FONT_HEIGHT) / 2);
            fontRendererObj.drawString("x", textX, textY, active ? 0xFFFFFFFF : 0xFF666666);
        }
    }

    private boolean drawMiniIcon(int x, int y, int w, int h, ResourceLocation texture, ResourceLocation fallbackTexture,
                                 String classpathPath, int textureW, int textureH, float alpha, int maxDrawW, int maxDrawH) {
        ResourceLocation resolvedTexture = resolveMiniIconTexture(texture, fallbackTexture, classpathPath);
        int[] dims = resolveMiniIconDimensions(classpathPath, textureW, textureH);
        textureW = dims[0];
        textureH = dims[1];
        if (resolvedTexture == null || textureW <= 0 || textureH <= 0) {
            return false;
        }
        int srcX = 0;
        int srcY = 0;
        int srcW = textureW;
        int srcH = textureH;
        if (classpathPath != null && classpathPath.endsWith("resetbutton10.png")) {
            srcX = 11;
            srcY = 13;
            srcW = 77;
            srcH = 70;
        }
        int contentX = x + 1;
        int contentY = y + 1;
        int contentW = Math.max(1, w - 2);
        int contentH = Math.max(1, h - 2);
        float scale = Math.min((contentW - 2) / (float) srcW, (contentH - 2) / (float) srcH);
        int iconW = Math.max(1, Math.round(srcW * scale));
        int iconH = Math.max(1, Math.round(srcH * scale));
        if (maxDrawW > 0 && iconW > maxDrawW) {
            float fit = maxDrawW / (float) iconW;
            iconW = maxDrawW;
            iconH = Math.max(1, Math.round(iconH * fit));
        }
        if (maxDrawH > 0 && iconH > maxDrawH) {
            float fit = maxDrawH / (float) iconH;
            iconH = maxDrawH;
            iconW = Math.max(1, Math.round(iconW * fit));
        }
        int iconX = contentX + (contentW - iconW) / 2 + MINI_BUTTON_ICON_OFFSET_X;
        int iconY = contentY + (contentH - iconH) / 2;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(resolvedTexture);
        Gui.drawScaledCustomSizeModalRect(iconX, iconY, srcX, srcY, srcW, srcH, iconW, iconH, textureW, textureH);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        return true;
    }

    private void drawMiniWidgetChrome(int x, int y, int w, int h, int mouseX, int mouseY, boolean active) {
        boolean mouseOver = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawWidgetChrome(x, y, w, h, mouseOver, active, !active ? 0xA0141414 : (mouseOver ? 0x60465C72 : 0x90242424));
    }

    private void drawWidgetChrome(int x, int y, int w, int h, boolean mouseOver, boolean active, int fill) {
        HudGlState.restoreForOverlayText();
        int hoverState = active ? (mouseOver ? 2 : 1) : 0;
        int texV = 46 + hoverState * 20;
        float alpha = active ? 1.0F : 0.5F;

        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        Minecraft.getMinecraft().getTextureManager().bindTexture(WIDGETS);
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

    private static int[] resolveMiniIconDimensions(String classpathPath, int defaultW, int defaultH) {
        int w = defaultW > 0 ? defaultW : MINI_ICON_10_CANVAS_W;
        int h = defaultH > 0 ? defaultH : MINI_ICON_10_CANVAS_H;
        if (classpathPath == null || classpathPath.isEmpty()) {
            return new int[] {w, h};
        }
        int[] cached = MINI_ICON_DIMENSIONS_CACHE.get(classpathPath);
        if (cached != null) {
            return cached;
        }
        try (InputStream stream = BlitzVaultItemsGUI.class.getResourceAsStream(classpathPath)) {
            if (stream != null) {
                BufferedImage img = ImageIO.read(stream);
                if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                    w = img.getWidth();
                    h = img.getHeight();
                }
            }
        } catch (Throwable ignored) {
        }
        int[] resolved = new int[] {w, h};
        MINI_ICON_DIMENSIONS_CACHE.put(classpathPath, resolved);
        return resolved;
    }

    private ResourceLocation resolveMiniIconTexture(ResourceLocation texture, ResourceLocation fallbackTexture, String classpathPath) {
        if (texture == null) {
            return null;
        }
        String key = texture.toString();
        if (MINI_ICON_TEXTURE_CACHE.containsKey(key)) {
            return MINI_ICON_TEXTURE_CACHE.get(key);
        }
        if (MINI_ICON_TEXTURE_MISSING.contains(key)) {
            return null;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getResourceManager() != null) {
            try {
                mc.getResourceManager().getResource(texture);
                MINI_ICON_TEXTURE_CACHE.put(key, texture);
                return texture;
            } catch (Exception ignored) {
            }
        }

        if (mc != null && mc.getTextureManager() != null && fallbackTexture != null && classpathPath != null) {
            try (InputStream stream = BlitzVaultItemsGUI.class.getResourceAsStream(classpathPath)) {
                if (stream != null) {
                    BufferedImage img = ImageIO.read(stream);
                    if (img != null) {
                        mc.getTextureManager().loadTexture(fallbackTexture, new DynamicTexture(img));
                        MINI_ICON_TEXTURE_CACHE.put(key, fallbackTexture);
                        return fallbackTexture;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        MINI_ICON_TEXTURE_MISSING.add(key);
        return null;
    }

    private void drawSortFooterButtons(int panelX, int y, int panelW, int mouseX, int mouseY) {
        String add = "+ Add";
        int addW = fontRendererObj.getStringWidth(add) + 14;
        int resetW = 22;
        int gap = 6;

        addSortBtnX = panelX + 6;
        addSortBtnY = y;
        addSortBtnW = addW;
        addSortBtnH = 18;

        resetSortBtnX = addSortBtnX + addW + gap;
        resetSortBtnY = y;
        resetSortBtnW = resetW;
        resetSortBtnH = 18;

        drawFooterButton(addSortBtnX, addSortBtnY, addSortBtnW, addSortBtnH, add,
                mouseX, mouseY, sortKeys.size() < getVisibleSortKeys().size());
        drawFooterResetButton(resetSortBtnX, resetSortBtnY, resetSortBtnW, resetSortBtnH, mouseX, mouseY, true);
    }

    private void drawFooterButton(int x, int y, int w, int h, String text, int mouseX, int mouseY, boolean active) {
        int bg = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h ? COL_TAB_BG_HOVER : COL_TAB_BG;
        Gui.drawRect(x, y, x + w, y + h, bg);
        drawHollowBorder(x, y, x + w, y + h, active ? COL_TAB_BORDER_ACTIVE : COL_TAB_BORDER);
        fontRendererObj.drawStringWithShadow(active ? EnumChatFormatting.WHITE + text : EnumChatFormatting.DARK_GRAY + text,
                x + 7, y + 5, 0xFFFFFFFF);
    }

    private void drawFooterResetButton(int x, int y, int w, int h, int mouseX, int mouseY, boolean active) {
        int bg = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h ? COL_TAB_BG_HOVER : COL_TAB_BG;
        Gui.drawRect(x, y, x + w, y + h, bg);
        drawHollowBorder(x, y, x + w, y + h, active ? COL_TAB_BORDER_ACTIVE : COL_TAB_BORDER);
        if (!drawMiniIcon(x + 1, y + 1, Math.max(1, w - 2), Math.max(1, h - 2),
                RESET_ICON_TEXTURE, RESET_ICON_TEXTURE_FALLBACK, "/assets/gomod123/textures/resetbutton10.png",
                RESET_ICON_TEXTURE_W, RESET_ICON_TEXTURE_H, active ? 1.0F : 0.55F, 12, 12)) {
            fontRendererObj.drawStringWithShadow(active ? EnumChatFormatting.WHITE + "R" : EnumChatFormatting.DARK_GRAY + "R",
                    x + (w - fontRendererObj.getStringWidth("R")) / 2, y + 5, 0xFFFFFFFF);
        }
    }

    private boolean handleManagePanelClick(int mouseX, int mouseY) {
        int panelX = getLeftPanelX();
        int panelY = getLeftPanelY();
        int panelW = getLeftPanelW();
        int panelH = getSidePanelH();
        if (panelW < 120 || panelH < 100) {
            return false;
        }
        boolean inside = mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH;
        if (!inside) {
            if (preferredRuleField != null) preferredRuleField.setFocused(false);
            if (blacklistedRuleField != null) blacklistedRuleField.setFocused(false);
            preferredDropdownScrollIndex = 0;
            blacklistedDropdownScrollIndex = 0;
            return false;
        }

        for (RuleOptionRect row : ruleOptionRows) {
            if (row.contains(mouseX, mouseY)) {
                addSelector(row.preferred ? preferredKeys : blacklistedKeys,
                        row.preferred ? blacklistedKeys : preferredKeys, row.selectorId);
                if (row.preferred && preferredRuleField != null) {
                    preferredRuleField.setText("");
                    preferredRuleField.setFocused(false);
                }
                if (!row.preferred && blacklistedRuleField != null) {
                    blacklistedRuleField.setText("");
                    blacklistedRuleField.setFocused(false);
                }
                if (searchField != null) {
                    searchField.setFocused(false);
                }
                preferredDropdownScrollIndex = 0;
                blacklistedDropdownScrollIndex = 0;
                lastQuery = "__changed__";
                return true;
            }
        }

        if (preferredRuleField != null) {
            preferredRuleField.mouseClicked(mouseX, mouseY, 0);
            if (preferredRuleField.isFocused()) {
                if (blacklistedRuleField != null) blacklistedRuleField.setFocused(false);
                if (searchField != null) searchField.setFocused(false);
                return true;
            }
        }
        if (blacklistedRuleField != null) {
            blacklistedRuleField.mouseClicked(mouseX, mouseY, 0);
            if (blacklistedRuleField.isFocused()) {
                if (preferredRuleField != null) preferredRuleField.setFocused(false);
                if (searchField != null) searchField.setFocused(false);
                return true;
            }
        }

        for (TaggedRowRect row : preferredRows) {
            if (row.removeContains(mouseX, mouseY)) {
                preferredKeys.remove(row.key);
                persistTaggedLists();
                lastQuery = "__changed__";
                return true;
            }
        }
        for (TaggedRowRect row : blacklistedRows) {
            if (row.removeContains(mouseX, mouseY)) {
                blacklistedKeys.remove(row.key);
                persistTaggedLists();
                lastQuery = "__changed__";
                return true;
            }
        }
        return inside;
    }

    private void addSelector(List<String> target, List<String> other, String selectorId) {
        if (selectorId == null || selectorId.trim().isEmpty()) {
            return;
        }
        if (other != null) {
            other.remove(selectorId);
        }
        if (target != null && !target.contains(selectorId)) {
            target.add(selectorId);
        }
        persistTaggedLists();
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private boolean handleSortPanelClick(int mouseX, int mouseY) {
        for (SortRowRect row : sortRows) {
            if (row.index > 0 && row.upContains(mouseX, mouseY)) {
                Collections.swap(sortKeys, row.index, row.index - 1);
                persistSortKeys();
                lastQuery = "__changed__";
                return true;
            }
            if (row.index < sortKeys.size() - 1 && row.downContains(mouseX, mouseY)) {
                Collections.swap(sortKeys, row.index, row.index + 1);
                persistSortKeys();
                lastQuery = "__changed__";
                return true;
            }
            if (sortKeys.size() > 1 && row.removeContains(mouseX, mouseY)) {
                sortKeys.remove(row.index);
                persistSortKeys();
                lastQuery = "__changed__";
                return true;
            }
            if (row.labelContains(mouseX, mouseY)) {
                sortKeys.set(row.index, nextSortKey(row.key));
                persistSortKeys();
                lastQuery = "__changed__";
                return true;
            }
        }
        if (sortKeys.size() < getVisibleSortKeys().size()
                && mouseX >= addSortBtnX && mouseX < addSortBtnX + addSortBtnW
                && mouseY >= addSortBtnY && mouseY < addSortBtnY + addSortBtnH) {
            SortKey next = firstUnusedSortKey();
            if (next != null) {
                sortKeys.add(next);
                persistSortKeys();
                lastQuery = "__changed__";
            }
            return true;
        }
        if (mouseX >= resetSortBtnX && mouseX < resetSortBtnX + resetSortBtnW
                && mouseY >= resetSortBtnY && mouseY < resetSortBtnY + resetSortBtnH) {
            sortKeys.clear();
            sortKeys.addAll(defaultSortKeys());
            persistSortKeys();
            lastQuery = "__changed__";
            return true;
        }
        return false;
    }

    private SortKey nextSortKey(SortKey current) {
        List<SortKey> values = getVisibleSortKeys();
        int start = current == null ? -1 : values.indexOf(current);
        for (int i = 1; i <= values.size(); i++) {
            SortKey next = values.get((start + i + values.size()) % values.size());
            if (next == current || !sortKeys.contains(next)) {
                return next;
            }
        }
        return current == null ? SortKey.ENCHANT_COUNT_ASC : current;
    }

    private SortKey firstUnusedSortKey() {
        for (SortKey key : getVisibleSortKeys()) {
            if (!sortKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private int getSummaryTopY() {
        if (!tabs.isEmpty()) {
            return tabs.get(tabs.size() - 1).y + TAB_HEIGHT + 8;
        }
        return 108;
    }

    private void drawSummaryPanels(ScaledResolution sr, int mouseX, int mouseY) {
        rebuildSummaryBarLayoutCacheIfNeeded();
        int panelTop = getSummaryTopY();
        int panelHeight = SUMMARY_HEIGHT;
        int outerLeft = 12;
        int outerRight = sr.getScaledWidth() - 12;
        int totalWidth = Math.max(120, outerRight - outerLeft);
        int panelWidth = (totalWidth - SUMMARY_GAP) / 2;
        int leftX = outerLeft;
        int rightX = leftX + panelWidth + SUMMARY_GAP;
        int rightWidth = outerRight - rightX;

        drawObservedChancesPanel(leftX, panelTop, panelWidth, panelHeight, mouseX, mouseY);
        drawPatternSummaryPanel(rightX, panelTop, rightWidth, panelHeight, mouseX, mouseY);
    }

    private void drawObservedChancesPanel(int x, int y, int w, int h, int mouseX, int mouseY) {
        observedSummaryPanelX = x;
        observedSummaryPanelY = y;
        observedSummaryPanelW = w;
        observedSummaryPanelH = h;

        Gui.drawRect(x, y, x + w, y + h, COL_SUMMARY_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_SUMMARY_BORDER);

        String title = EnumChatFormatting.AQUA + "Observed Chances";
        fontRendererObj.drawStringWithShadow(title, x + 6, y + 6, 0xFFFFFFFF);

        BlitzVaultLootInsights.Snapshot snap = cachedLootInsights;
        int vaults = snap.vaultCaptures;

        String subtitle;
        if (!snap.hasData()) {
            subtitle = EnumChatFormatting.DARK_GRAY + "No observed vault data yet.";
        } else {
            subtitle = EnumChatFormatting.DARK_GRAY + String.valueOf(vaults)
                    + " vaults · armor slot mix + vault weapon rates.";
        }
        fontRendererObj.drawString(subtitle, x + 6, y + 18, 0xFFFFFFFF);

        if (!snap.hasData()) {
            return;
        }

        drawInsightRowsPanel(x, y, w, h, mouseX, mouseY, snap.materialRows, observedChancesScroll, COL_BAR_FILL_CHEST);
    }

    private void drawInsightRow(int panelX, int panelW, int lineY, BlitzVaultLootInsights.Row row, int barColor) {
        if (row == null) {
            return;
        }
        int textX = panelX + OBSERVED_ROW_PAD;
        String pct = BlitzVaultLootInsights.formatPercent(row.fraction);
        int pctW = fontRendererObj.getStringWidth(pct);
        int barX = summaryBarStartX(panelX, panelW);
        int barW = summaryBarWidth(panelX, panelW, barX);
        int pctX = Math.max(textX, barX - pctW - 4);
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.WHITE + row.label, textX, lineY, 0xFFFFFFFF);
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + pct, pctX, lineY, 0xFFFFFFFF);
        drawSummaryHorizontalBar(barX, lineY + 1, barW, SUMMARY_BAR_H, row.fraction, barColor);
    }

    private void drawInsightRowsPanel(int x, int y, int w, int h, int mouseX, int mouseY,
                                      List<BlitzVaultLootInsights.Row> rows, GuiSmoothScroll scroll, int barColor) {
        int listTop = y + 31;
        int listBottom = y + h - 5;
        int lineH = 12;
        int rowCount = rows == null ? 0 : rows.size();
        int contentH = Math.max(lineH, rowCount * lineH);
        scroll.tick();
        scroll.updateBounds(listTop, listTop + contentH, listTop, listBottom);
        scroll.drawScrollbarAt(x + w - 8, mouseY, 3, 0x30FFFFFF, 0xEFFFFFFF);

        int clipW = Math.max(10, w - 17);
        scissor(Minecraft.getMinecraft(), x + 4, listTop, clipW, Math.max(1, listBottom - listTop));
        try {
            int offset = scroll.getOffset();
            int first = Math.max(0, (offset - lineH) / lineH);
            int last = Math.min(rowCount - 1, (offset + (listBottom - listTop) + lineH) / lineH);
            for (int i = first; i <= last; i++) {
                int lineY = listTop + i * lineH - offset;
                drawInsightRow(x, w - 10, lineY, rows.get(i), barColor);
            }
        } finally {
            unscissor();
        }
    }

    private void drawEnchantPanelHeaderButtons(int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        int btnH = 14;
        int btnY = panelY + 3;

        String levelsLabel = EnumChatFormatting.GRAY + "Levels";
        enchantLevelsBtnW = fontRendererObj.getStringWidth(levelsLabel) + 8;
        enchantLevelsBtnH = btnH;
        enchantLevelsBtnX = panelX + panelW - enchantLevelsBtnW - 5;
        enchantLevelsBtnY = btnY;

        boolean levelsHover = mouseX >= enchantLevelsBtnX && mouseX < enchantLevelsBtnX + enchantLevelsBtnW
                && mouseY >= enchantLevelsBtnY && mouseY < enchantLevelsBtnY + enchantLevelsBtnH;

        drawWidgetChrome(enchantLevelsBtnX, enchantLevelsBtnY, enchantLevelsBtnW, enchantLevelsBtnH, levelsHover, true,
                levelsHover ? 0x605A6F84 : 0x90242424);
        fontRendererObj.drawStringWithShadow(levelsHover ? EnumChatFormatting.WHITE + "Levels" : levelsLabel,
                enchantLevelsBtnX + (enchantLevelsBtnW - fontRendererObj.getStringWidth(levelsLabel)) / 2,
                enchantLevelsBtnY + (enchantLevelsBtnH - 8) / 2, 0xFFFFFFFF);
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

    private void drawObsCategoryPill(int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        String pillText = pinnedObsCategory == null
                ? EnumChatFormatting.GRAY + "Auto"
                : (FILTER_ALL.equals(pinnedObsCategory)
                        ? EnumChatFormatting.WHITE + "All"
                        : EnumChatFormatting.YELLOW + pinnedObsCategory);
        int pillW = fontRendererObj.getStringWidth(pillText) + 10;
        int pillX = panelX + panelW - pillW - 5;
        int pillY = panelY + 3;
        int pillH = 14;

        obsPinBtnX = pillX;
        obsPinBtnY = pillY;
        obsPinBtnW = pillW;
        obsPinBtnH = pillH;

        boolean mouseOver = mouseX >= pillX && mouseX < pillX + pillW && mouseY >= pillY && mouseY < pillY + pillH;
        drawWidgetChrome(pillX, pillY, pillW, pillH, mouseOver, true, mouseOver ? 0x605A6F84 : 0x90242424);
        fontRendererObj.drawStringWithShadow(pillText, pillX + (pillW - fontRendererObj.getStringWidth(pillText)) / 2,
                pillY + (pillH - 8) / 2, 0xFFFFFFFF);
    }

    private void cycleObsCategory() {
        List<String> options = new ArrayList<String>();
        options.add(null);
        options.add(FILTER_ALL);
        for (String cat : BlitzVaultItemsData.getCategoryOrder()) {
            if (hasObservedData(cat)) options.add(cat);
        }
        if (options.size() <= 1) return;
        int cur = options.indexOf(pinnedObsCategory);
        pinnedObsCategory = options.get((cur + 1) % options.size());
        ConfigManager.setBlitzVaultItemsObservedCategory(pinnedObsCategory == null ? "" : pinnedObsCategory);
    }

    private boolean hasObservedData(String cat) {
        for (BlitzVaultItemsData.Entry e : BlitzVaultItemsData.getAll()) {
            if (cat.equals(e.category) && (e.observedCount > 0 || e.observedSlotCount > 0)) return true;
        }
        return false;
    }

    private void drawPatternSummaryPanel(int x, int y, int w, int h, int mouseX, int mouseY) {
        enchantSummaryPanelX = x;
        enchantSummaryPanelY = y;
        enchantSummaryPanelW = w;
        enchantSummaryPanelH = h;

        Gui.drawRect(x, y, x + w, y + h, COL_SUMMARY_BG);
        drawHollowBorder(x, y, x + w, y + h, COL_SUMMARY_BORDER);

        String title = EnumChatFormatting.GOLD + "Enchant Count Odds";
        fontRendererObj.drawStringWithShadow(title, x + 6, y + 6, 0xFFFFFFFF);

        drawEnchantPanelHeaderButtons(x, y, w, mouseX, mouseY);

        BlitzVaultLootInsights.Snapshot snap = cachedLootInsights;

        String subtitle;
        if (!snap.hasData()) {
            subtitle = EnumChatFormatting.DARK_GRAY + "No observed vault data yet.";
        } else {
            subtitle = EnumChatFormatting.DARK_GRAY + "Share of armor slots by enchant count (slot-weighted).";
        }
        fontRendererObj.drawString(subtitle, x + 6, y + 18, 0xFFFFFFFF);

        if (!snap.hasData()) {
            return;
        }

        drawInsightRowsPanel(x, y, w, h, mouseX, mouseY, snap.enchantCountRows, enchantChancesScroll, 0xFFB060E0);
    }

    /** Chest percent capped at 100% (chest = unique vaults with at least one matching item). */
    private String formatPercentCapped(int count, int total) {
        if (count <= 0 || total <= 0) {
            return "0.0%";
        }
        int capped = Math.min(count, total);
        return String.format(Locale.US, "%.1f%%", (double) capped * 100.0D / (double) total);
    }

    private SummarySelection resolveSummarySelection() {
        if (pinnedObsCategory == null) {
            return new SummarySelection(filtered, currentSummaryFilterLabel());
        }
        if (FILTER_ALL.equals(pinnedObsCategory)) {
            return new SummarySelection(BlitzVaultItemsData.getAll(), FILTER_ALL);
        }
        return new SummarySelection(collectEntriesForCategory(pinnedObsCategory), pinnedObsCategory);
    }

    private List<BlitzVaultItemsData.Entry> collectEntriesForCategory(String category) {
        List<BlitzVaultItemsData.Entry> out = new ArrayList<BlitzVaultItemsData.Entry>();
        if (category == null || category.trim().isEmpty()) {
            return out;
        }
        for (BlitzVaultItemsData.Entry e : BlitzVaultItemsData.getAll()) {
            if (category.equals(e.category)) {
                out.add(e);
            }
        }
        return out;
    }

    private List<PatternSummary> buildPatternSummaries(List<BlitzVaultItemsData.Entry> source) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        for (BlitzVaultItemsData.Entry entry : source) {
            int weight = patternWeight(entry);
            if (entry == null || weight <= 0) {
                continue;
            }
            String label = buildPatternLabel(entry);
            if (label.isEmpty()) {
                continue;
            }
            Integer cur = counts.get(label);
            counts.put(label, (cur == null ? 0 : cur) + weight);
        }
        List<PatternSummary> out = new ArrayList<PatternSummary>(counts.size());
        for (Map.Entry<String, Integer> row : counts.entrySet()) {
            if (row.getValue() != null && row.getValue() > 0) {
                out.add(new PatternSummary(row.getKey(), row.getValue()));
            }
        }
        Collections.sort(out, new Comparator<PatternSummary>() {
            @Override
            public int compare(PatternSummary a, PatternSummary b) {
                int count = Integer.compare(b.count, a.count);
                if (count != 0) return count;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private int patternWeight(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return 0;
        }
        if (entry.observedSlotCount > 0) {
            return entry.observedSlotCount;
        }
        return Math.max(0, entry.observedCount);
    }

    private String buildPatternLabel(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return "";
        }
        String base = stripFormatting(entry.displayName).trim();
        String detail = buildEnchantSummary(entry);
        if (base.isEmpty()) {
            return detail;
        }
        if (detail.isEmpty()) {
            return abbreviatePattern(base);
        }
        return EnumChatFormatting.WHITE + abbreviatePattern(base) + EnumChatFormatting.DARK_GRAY + " | " + detail;
    }

    private String buildPatternPanelTitle(String effectiveCat) {
        if (effectiveCat == null || FILTER_ALL.equals(effectiveCat)) {
            return "Most Common Patterns";
        }
        if (FILTER_MULTI.equals(effectiveCat)) {
            return "Most Common Selected Patterns";
        }
        return "Most Common " + patternCategoryLabel(effectiveCat) + " Patterns";
    }

    private String buildPatternSubtitle(String effectiveCat) {
        if (effectiveCat == null || FILTER_ALL.equals(effectiveCat)) {
            return "Based on logged item roll patterns.";
        }
        if (FILTER_MULTI.equals(effectiveCat)) {
            return "Based on logged rolls from the selected categories.";
        }
        return "Based on logged " + patternCategoryLabel(effectiveCat).toLowerCase(Locale.US) + " rolls.";
    }

    private String buildPatternEmptySubtitle(String effectiveCat) {
        if (effectiveCat == null || FILTER_ALL.equals(effectiveCat)) {
            return "No logged pattern data yet.";
        }
        if (FILTER_MULTI.equals(effectiveCat)) {
            return "No logged pattern data for the selected categories yet.";
        }
        return "No logged " + patternCategoryLabel(effectiveCat).toLowerCase(Locale.US) + " pattern data yet.";
    }

    private String patternCategoryLabel(String category) {
        if (category == null || category.isEmpty()) {
            return "Item";
        }
        if (BlitzVaultItemsData.CAT_BOW.equals(category)) return "Bow";
        if (BlitzVaultItemsData.CAT_SWORD.equals(category)) return "Sword";
        if (BlitzVaultItemsData.CAT_AXE.equals(category)) return "Axe";
        if (BlitzVaultItemsData.CAT_HELMET.equals(category)) return "Helmet";
        if (BlitzVaultItemsData.CAT_CHESTPLATE.equals(category)) return "Chestplate";
        if (BlitzVaultItemsData.CAT_LEGGINGS.equals(category)) return "Leggings";
        if (BlitzVaultItemsData.CAT_BOOTS.equals(category)) return "Boots";
        if (BlitzVaultItemsData.CAT_PICKAXE.equals(category)) return "Pickaxe";
        if (BlitzVaultItemsData.CAT_SHOVEL.equals(category)) return "Shovel";
        if (BlitzVaultItemsData.CAT_HOE.equals(category)) return "Hoe";
        if (BlitzVaultItemsData.CAT_ROD.equals(category)) return "Rod";
        return stripFormatting(category);
    }

    private String buildObservedEntryLabel(BlitzVaultItemsData.Entry entry) {
        if (entry == null) {
            return "";
        }
        String base = stripFormatting(entry.displayName);
        String detail = buildEnchantSummary(entry);
        if (detail.isEmpty()) {
            return base;
        }
        return EnumChatFormatting.WHITE + base + EnumChatFormatting.DARK_GRAY + " | " + detail;
    }

    private String buildEnchantSummary(BlitzVaultItemsData.Entry entry) {
        if (entry == null || entry.fallbackTooltip == null || entry.fallbackTooltip.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (int i = 1; i < entry.fallbackTooltip.size(); i++) {
            String raw = stripFormatting(entry.fallbackTooltip.get(i)).trim();
            if (raw.isEmpty() || raw.startsWith("minecraft:") || raw.startsWith("NBT:") || raw.startsWith("+")) {
                continue;
            }
            parts.add(abbreviatePattern(raw));
            if (parts.size() >= 3) {
                break;
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(EnumChatFormatting.GRAY).append(", ");
            }
            sb.append(formatEnchantBucketText(parts.get(i)));
        }
        return sb.toString();
    }

    private String abbreviatePattern(String label) {
        if (label == null) {
            return "";
        }
        return stripFormatting(label).trim();
    }

    /** See {@link BlitzChestItemsGUI#chestSortRowLabel(boolean, String)}. */
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

    private String formatPercent(int count, int total) {
        if (count <= 0 || total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.US, "%.1f%%", (double) count * 100.0D / (double) total);
    }

    private String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private void drawSlotBackground(int x, int y, boolean hover, int tagRank) {
        int bg;
        if (tagRank <= 0) {
            bg = hover ? COL_SLOT_PREF_HOVER : COL_SLOT_PREF_BG;
        } else if (tagRank >= 2) {
            bg = hover ? COL_SLOT_BLACK_HOVER : COL_SLOT_BLACK_BG;
        } else {
            bg = hover ? COL_SLOT_HOVER : COL_SLOT_BG;
        }
        Gui.drawRect(x, y, x + SLOT_SIZE, y + SLOT_SIZE, bg);
        Gui.drawRect(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0xFF000000);
    }

    private void drawHollowBorder(int x1, int y1, int x2, int y2, int color) {
        Gui.drawRect(x1, y1, x2, y1 + 1, color);
        Gui.drawRect(x1, y2 - 1, x2, y2, color);
        Gui.drawRect(x1, y1, x1 + 1, y2, color);
        Gui.drawRect(x2 - 1, y1, x2, y2, color);
    }

    private List<String> buildEnchantPriorityTooltip() {
        List<String> tooltip = new ArrayList<String>();
        tooltip.add(EnumChatFormatting.YELLOW + "Enchant Priority");
        tooltip.add(EnumChatFormatting.GRAY + "Weapons/Tools: " + EnumChatFormatting.WHITE
                + "Sharpness > Fire Aspect > Knockback");
        tooltip.add(EnumChatFormatting.GRAY + "Bows: " + EnumChatFormatting.WHITE
                + "Power > Flame > Punch > Infinity");
        tooltip.add(EnumChatFormatting.GRAY + "Armor: " + EnumChatFormatting.WHITE
                + "Protection > Depth Strider > Feather Falling");
        tooltip.add(EnumChatFormatting.GRAY + "        " + EnumChatFormatting.WHITE
                + "Projectile Protection > Fire Protection");
        tooltip.add(EnumChatFormatting.GRAY + "        " + EnumChatFormatting.WHITE
                + "Thorns > Blast Protection > Respiration");
        tooltip.add(EnumChatFormatting.GRAY + "        " + EnumChatFormatting.WHITE
                + "Aqua Affinity > Unbreaking");
        return tooltip;
    }

    private void renderUiTooltip(List<String> tooltip, int mouseX, int mouseY) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        try {
            drawHoveringText(tooltip, mouseX, mouseY);
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        }
    }

    private void renderItemTooltip(BlitzVaultItemsData.Entry entry, int mouseX, int mouseY) {
        if (entry == null || entry.stack == null) return;
        renderItemTooltip(entry.stack, entry.fallbackTooltip, mouseX, mouseY, entry);
    }

    private void renderItemTooltip(ItemStack stack, List<String> fallbackTooltip, int mouseX, int mouseY,
                                   BlitzVaultItemsData.Entry observedEntry) {
        if (stack == null) return;
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
                tooltip = new ArrayList<String>(fallbackTooltip == null
                        ? Collections.<String>emptyList() : fallbackTooltip);
            } else {
                tooltip = new ArrayList<String>(tooltip);
            }
            BlitzChestItemsData.replaceDyedWithLeatherColorLine(tooltip, stack, fallbackTooltip);
            applyVanillaTooltipColors(stack, tooltip);
            if (observedEntry != null) {
                appendSeenTooltipLines(tooltip, observedEntry);
            }
            drawHoveringText(tooltip, mouseX, mouseY);
        } catch (Throwable ignored) {
        } finally {
            if (restoreAdvanced && mc != null && mc.gameSettings != null) {
                mc.gameSettings.advancedItemTooltips = previousAdvanced;
            }
        }
        GlStateManager.popMatrix();
    }

    private void appendSeenTooltipLines(List<String> tooltip, BlitzVaultItemsData.Entry entry) {
        if (tooltip == null || entry == null) {
            return;
        }
        int totalCaptures = BlitzVaultItemsData.getObservedValidCaptures();
        tooltip.add(EnumChatFormatting.DARK_GRAY + "Vault chests: "
                + EnumChatFormatting.GREEN + entry.observedCount
                + EnumChatFormatting.DARK_GRAY + "/" + totalCaptures
                + " (" + EnumChatFormatting.GREEN + formatPercent(entry.observedCount, totalCaptures)
                + EnumChatFormatting.DARK_GRAY + ")");
        tooltip.add(EnumChatFormatting.DARK_GRAY + "Finds per chest: "
                + EnumChatFormatting.AQUA + entry.observedSlotCount
                + EnumChatFormatting.DARK_GRAY + "/" + totalCaptures
                + " (" + EnumChatFormatting.AQUA + formatPercent(entry.observedSlotCount, totalCaptures)
                + EnumChatFormatting.DARK_GRAY + ")");
    }

    private void applyVanillaTooltipColors(ItemStack stack, List<String> tooltip) {
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
                } else {
                    tooltip.set(i, enchantBucketColorForLine(plain) + plain);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void scissor(Minecraft mc, int gridX, int gridY, int gridW, int gridH) {
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

    private void unscissor() {
        try {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) return;
        if (button.id == 0) {
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (chestBrowserMode && chestGalleryScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (!chestBrowserMode && observedChancesScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (!chestBrowserMode && enchantChancesScroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (!chestBrowserMode && scroll.mouseClicked(mouseX, mouseY, mouseButton)) {
            return;
        }
        if (!chestBrowserMode && mouseButton != 0 && isMouseInsideSortPanel(mouseX, mouseY)) {
            return;
        }
        if (chestBrowserMode && mouseButton != 0 && isMouseInsideChestSortPanel(mouseX, mouseY)) {
            return;
        }
        if (mouseButton == 0) {
            if (chestBrowserMode && handleChestSortPanelClick(mouseX, mouseY)) {
                return;
            }
            if (!chestBrowserMode && handleManagePanelClick(mouseX, mouseY)) {
                return;
            }
            if (enchantLevelsBtnW > 0 && mouseX >= enchantLevelsBtnX && mouseX < enchantLevelsBtnX + enchantLevelsBtnW
                    && mouseY >= enchantLevelsBtnY && mouseY < enchantLevelsBtnY + enchantLevelsBtnH) {
                mc.displayGuiScreen(new BlitzVaultEnchantLevelGUI(this));
                return;
            }
            if (!chestBrowserMode && handleSortPanelClick(mouseX, mouseY)) {
                return;
            }
            for (TabRect tab : tabs) {
                if (mouseX >= tab.x && mouseX < tab.x + tab.w && mouseY >= tab.y && mouseY < tab.y + tab.h) {
                    if (FILTER_CHESTS.equals(tab.label)) {
                        chestBrowserMode = true;
                        if (searchField != null) searchField.setFocused(false);
                    } else {
                        chestBrowserMode = false;
                        toggleCategoryFilter(tab.label);
                    }
                    return;
                }
            }
        }
        if (searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            if (searchField.isFocused()) {
                if (preferredRuleField != null) preferredRuleField.setFocused(false);
                if (blacklistedRuleField != null) blacklistedRuleField.setFocused(false);
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean isMouseInsideSortPanel(int mouseX, int mouseY) {
        int x = getRightPanelX();
        int y = getRightPanelY();
        int w = getRightPanelW();
        int h = getSidePanelH();
        return w >= 120 && h >= 80
                && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + h;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        scroll.mouseReleased(state);
        chestGalleryScroll.mouseReleased(state);
        observedChancesScroll.mouseReleased(state);
        enchantChancesScroll.mouseReleased(state);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (chestBrowserMode) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (dWheel != 0 && consumeChestSortPanelWheel(mx, my, dWheel)) {
                return;
            }
            chestGalleryScroll.handleMouseWheel();
            return;
        }
        if (dWheel != 0) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (consumeRuleDropdownWheelIfHovered(true, mx, my, dWheel)
                    || consumeRuleDropdownWheelIfHovered(false, mx, my, dWheel)) {
                return;
            }
            if (observedSummaryPanelW > 0
                    && mx >= observedSummaryPanelX && mx < observedSummaryPanelX + observedSummaryPanelW
                    && my >= observedSummaryPanelY && my < observedSummaryPanelY + observedSummaryPanelH) {
                observedChancesScroll.handleMouseWheel();
                return;
            }
            if (enchantSummaryPanelW > 0
                    && mx >= enchantSummaryPanelX && mx < enchantSummaryPanelX + enchantSummaryPanelW
                    && my >= enchantSummaryPanelY && my < enchantSummaryPanelY + enchantSummaryPanelH) {
                enchantChancesScroll.handleMouseWheel();
                return;
            }
            int stepPx = TAGGED_ROW_STRIDE * Math.max(1, Math.abs(dWheel) / 120);
            if (prefTaggedWheelH > 0 && prefTaggedMaxScrollPx > 0
                    && mx >= prefTaggedWheelX && mx < prefTaggedWheelX + prefTaggedWheelW
                    && my >= prefTaggedWheelY && my < prefTaggedWheelY + prefTaggedWheelH) {
                if (dWheel > 0) {
                    preferredTaggedScrollPx = Math.max(0, preferredTaggedScrollPx - stepPx);
                } else {
                    preferredTaggedScrollPx = Math.min(prefTaggedMaxScrollPx, preferredTaggedScrollPx + stepPx);
                }
                return;
            }
            if (blTaggedWheelH > 0 && blTaggedMaxScrollPx > 0
                    && mx >= blTaggedWheelX && mx < blTaggedWheelX + blTaggedWheelW
                    && my >= blTaggedWheelY && my < blTaggedWheelY + blTaggedWheelH) {
                if (dWheel > 0) {
                    blacklistedTaggedScrollPx = Math.max(0, blacklistedTaggedScrollPx - stepPx);
                } else {
                    blacklistedTaggedScrollPx = Math.min(blTaggedMaxScrollPx, blacklistedTaggedScrollPx + stepPx);
                }
                return;
            }
        }
        scroll.handleMouseWheel();
    }

    private boolean consumeRuleDropdownWheelIfHovered(boolean preferred, int mx, int my, int dWheel) {
        GuiTextField field = preferred ? preferredRuleField : blacklistedRuleField;
        if (field == null || !field.isFocused()) {
            return false;
        }
        int maxIdx = preferred ? prefDropdownMaxScrollIdx : blDropdownMaxScrollIdx;
        int wheelH = preferred ? prefDropdownWheelH : blDropdownWheelH;
        if (maxIdx <= 0 || wheelH <= 0) {
            return false;
        }
        int ux = preferred ? prefDropdownWheelX : blDropdownWheelX;
        int uy = preferred ? prefDropdownWheelY : blDropdownWheelY;
        int uw = preferred ? prefDropdownWheelW : blDropdownWheelW;
        int uh = preferred ? prefDropdownWheelH : blDropdownWheelH;
        if (mx < ux || mx >= ux + uw || my < uy || my >= uy + uh) {
            return false;
        }
        int step = Math.max(1, Math.abs(dWheel) / 120);
        if (preferred) {
            if (dWheel > 0) {
                preferredDropdownScrollIndex = Math.max(0, preferredDropdownScrollIndex - step);
            } else {
                preferredDropdownScrollIndex = Math.min(maxIdx, preferredDropdownScrollIndex + step);
            }
        } else {
            if (dWheel > 0) {
                blacklistedDropdownScrollIndex = Math.max(0, blacklistedDropdownScrollIndex - step);
            } else {
                blacklistedDropdownScrollIndex = Math.min(maxIdx, blacklistedDropdownScrollIndex + step);
            }
        }
        return true;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (handleRuleFieldKeyTyped(preferredRuleField, preferredKeys, blacklistedKeys, typedChar, keyCode)) {
            return;
        }
        if (handleRuleFieldKeyTyped(blacklistedRuleField, blacklistedKeys, preferredKeys, typedChar, keyCode)) {
            return;
        }
        if (searchField != null && searchField.isFocused()) {
            if (keyCode == 1) {
                searchField.setFocused(false);
                return;
            }
            if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(searchField, preferredRuleField, blacklistedRuleField);
    }

    private boolean handleRuleFieldKeyTyped(GuiTextField field, List<String> ownList, List<String> otherList,
                                            char typedChar, int keyCode) {
        if (field == null || !field.isFocused()) {
            return false;
        }
        if (keyCode == 1) {
            field.setFocused(false);
            if (field == preferredRuleField) {
                preferredDropdownScrollIndex = 0;
            } else if (field == blacklistedRuleField) {
                blacklistedDropdownScrollIndex = 0;
            }
            return true;
        }
        if (keyCode == 28 || keyCode == 156) {
            List<RuleOptionDef> options = getFilteredRuleOptions(field.getText());
            if (!options.isEmpty()) {
                int pick = field == preferredRuleField ? preferredDropdownScrollIndex : blacklistedDropdownScrollIndex;
                pick = Math.min(Math.max(0, pick), options.size() - 1);
                addSelector(ownList, otherList, options.get(pick).id);
                field.setText("");
                field.setFocused(false);
                preferredDropdownScrollIndex = 0;
                blacklistedDropdownScrollIndex = 0;
                lastQuery = "__changed__";
                return true;
            }
        }
        if (field.textboxKeyTyped(typedChar, keyCode)) {
            return true;
        }
        return false;
    }

    private static final class SummarySelection {
        final List<BlitzVaultItemsData.Entry> source;
        final String effectiveCategory;

        SummarySelection(List<BlitzVaultItemsData.Entry> source, String effectiveCategory) {
            this.source = source == null ? Collections.<BlitzVaultItemsData.Entry>emptyList() : source;
            this.effectiveCategory = effectiveCategory == null || effectiveCategory.trim().isEmpty()
                    ? FILTER_ALL
                    : effectiveCategory;
        }
    }

    private static final class PatternSummary {
        final String label;
        final int count;

        PatternSummary(String label, int count) {
            this.label = label == null ? "" : label;
            this.count = Math.max(0, count);
        }
    }

    private static final class RuleOptionDef {
        final String id;
        final String label;
        final String filterText;

        RuleOptionDef(String id, String label) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.filterText = this.label.toLowerCase(Locale.US);
        }
    }

    private static final class RuleOptionRect {
        final boolean preferred;
        final String selectorId;
        final int x;
        final int y;
        final int w;
        final int h;

        RuleOptionRect(boolean preferred, String selectorId, int x, int y, int w, int h) {
            this.preferred = preferred;
            this.selectorId = selectorId;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
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

    private static final class SortRowRect {
        private static final int MINI_W = 13;
        private static final int MINI_GAP = 3;
        final int index;
        final SortKey key;
        final int x;
        final int y;
        final int w;
        final int h;

        SortRowRect(int index, SortKey key, int x, int y, int w, int h) {
            this.index = index;
            this.key = key;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        int upX() { return x + 4; }
        int downX() { return upX() + MINI_W + MINI_GAP; }
        int removeX() { return x + w - 4 - MINI_W; }
        int labelX() { return downX() + MINI_W + 5; }
        int miniY() { return y + Math.max(1, (h - miniH()) / 2); }
        int miniH() { return Math.max(10, Math.min(14, h - 2)); }
        int miniW() { return MINI_W; }

        boolean upContains(int mx, int my) {
            return mx >= upX() && mx < upX() + miniW() && my >= miniY() && my < miniY() + miniH();
        }

        boolean downContains(int mx, int my) {
            return mx >= downX() && mx < downX() + miniW() && my >= miniY() && my < miniY() + miniH();
        }

        boolean removeContains(int mx, int my) {
            return mx >= removeX() && mx < removeX() + miniW() && my >= miniY() && my < miniY() + miniH();
        }

        boolean labelContains(int mx, int my) {
            return mx >= labelX() && mx < removeX() - 4 && my >= y && my < y + h;
        }
    }

    private static final class TaggedRowRect {
        final boolean preferred;
        final int index;
        final String key;
        final int x;
        final int y;
        final int w;
        final int h;

        TaggedRowRect(boolean preferred, int index, String key, int x, int y, int w, int h) {
            this.preferred = preferred;
            this.index = index;
            this.key = key;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        int removeX() {
            return x + w - 14;
        }

        int miniY() {
            return y + Math.max(1, (h - miniH()) / 2);
        }

        int miniH() {
            return Math.max(8, Math.min(12, h - 2));
        }

        boolean removeContains(int mx, int my) {
            return mx >= removeX() && mx < removeX() + 10 && my >= miniY() && my < miniY() + miniH();
        }
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
}
