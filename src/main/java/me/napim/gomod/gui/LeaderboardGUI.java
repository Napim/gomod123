package me.napim.gomod.gui;

import com.google.gson.JsonObject;
import me.napim.gomod.features.GoStatsLeaderboardClient;
import me.napim.gomod.features.GoStatsLeaderboardPageSizeStore;
import me.napim.gomod.features.GoStatsLeaderboardRefreshCountStore;
import me.napim.gomod.features.GoStatsLeaderboardBatchRefresh;
import me.napim.gomod.features.GoStatsLeaderboardBatchRefreshListener;
import me.napim.gomod.features.LeaderboardRefreshDebugCapture;
import me.napim.gomod.features.GoStatsBlitzClient;
import me.napim.gomod.features.GoStatsPlayerPresence;
import me.napim.gomod.features.GoStatsContributions;
import me.napim.gomod.features.GoStatsLeaderboardSyncQueue;
import me.napim.gomod.features.HypixelPlayerDataCache;
import me.napim.gomod.features.HypixelPlayerRequests;
import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.features.TeamSpamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.io.File;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scrollable view of the shared GoStats Blitz leaderboard, ranked by overall kills. */
public final class LeaderboardGUI extends GuiScreen implements GoStatsLeaderboardBatchRefreshListener,
        GomodMenuKeyboardCaptureGuard {
    private static final int ID_BACK = 0;
    private static final int ID_REFRESH = 1;
    private static final int ID_SCOPE = 2;
    private static final int ID_SORT = 3;
    private static final int ID_PAGE_SIZE = 4;
    private static final int ID_APPLY_FILTERS = 5;
    private static final int ID_KIT = 6;
    private static final int ID_UPDATE_TOP_350 = 7;
    private static final int ID_FILTERS = 8;
    private static final int ID_UPDATE_ACTION = 9;
    private static final int ID_FILTER_VALUE = 10;
    private static final int ROW_HEIGHT = 15;
    private static final int KIT_ROW_HEIGHT = 15;
    private static final int KIT_LEVEL_HEAD_GAP = 3;
    private static final int KIT_LEVEL_MARKER_SLOT_WIDTH = 24;
    private static final int KIT_LEVEL_NAME_GAP = 2;
    private static final int LAST_ACTION_COLUMN_WIDTH = 124;
    private static final int MAX_VISIBLE_ROWS = 18;
    private static final int INITIAL_ANCHOR_PAGE_SIZE = 20;
    private static final int MAX_SUGGESTION_ROWS = 5;
    private static final int SUGGESTION_ROW_HEIGHT = 14;
    private static final long SUGGESTION_DEBOUNCE_MS = 200L;
    private static final int TEXT = 0xFFEAF2FA;
    private static final int MUTED = 0xFF91A1AF;
    private static final int GREEN = 0xFF65D68A;
    private static final int CYAN = 0xFF55FFFF;
    private static final float HIDDEN_KILL_COUNTER_OPACITY = 0.45F;
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final Pattern LEADING_DISPLAY_NUMBER = Pattern.compile(
            "^\\s*([+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)");
    private static final Pattern DISPLAY_DURATION = Pattern.compile(
            "^\\s*(?:(\\d[\\d,]*)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?\\s*$");
    private static final String[] SCOPES = {"overall", "solo", "teams", "kit"};
    private static final String[] SCOPE_LABELS = {"Overall", "Solo", "Teams", "Kit"};
    private static final int[] PAGE_SIZES = {100, 200, 500};
    private static final String[] OVERALL_METRICS = {
            "kills", "wins", "deaths", "kdr", "wlr", "games", "time_played",
            "playtime_per_game", "blitz_stars_used", "blitz_stars_per_game", "coins",
            "prestige_2", "items_enchanted_tim", "blocks_traveled_pig",
            "blocks_traveled_boat", "blocks_traveled_minecart", "blocks_traveled_horse",
            "chests_opened", "experience", "exp_per_game", "damage_ratio",
            "taunt_kills", "mobs_spawned", "potions_drunk", "potions_thrown"
    };
    private static final String[] OVERALL_LABELS = {
            "Overall Kills", "Overall Wins", "Deaths", "KDR", "WLR",
            "Games Played", "Playtime", "Playtime / Game", "Blitz Stars Used",
            "Blitz Stars / Game", "Coins", "Prestige II", "Items Enchanted (Tim)",
            "Pig Blocks Traveled", "Boat Blocks Traveled", "Minecart Blocks Traveled",
            "Horse Blocks Traveled", "Chests Opened", "Kit Experience", "EXP / Game",
            "Damage Ratio", "Taunt Kills", "Mobs Spawned", "Potions Drunk", "Potions Thrown"
    };
    private static final String[] MODE_METRICS = {"kills", "wins"};
    private static final String[] MODE_LABELS = {"Kills", "Wins"};
    private static final String[] SOLO_METRICS = {"kills", "solo_chaos_kills", "wins", "solo_chaos_wins"};
    private static final String[] SOLO_LABELS = {"Kills", "Chaos Kills", "Wins", "Chaos Wins"};
    private static final String[] TRAVEL_METRICS = {
            "blocks_traveled_pig", "blocks_traveled_boat",
            "blocks_traveled_minecart", "blocks_traveled_horse"
    };
    private static final String[] KIT_METRICS = {
            "wins", "solo_wins", "team_wins", "kills", "deaths", "wlr", "kdr", "games",
            "experience", "exp_per_game", "time_played", "playtime_per_game",
            "arrows_hit", "arrows_fired", "arrow_accuracy", "damage_dealt", "damage_taken",
            "damage_ratio", "damage_per_kill", "taunt_kills", "taunt_ratio", "mobs_spawned",
            "mobs_spawned_per_game", "chests_opened", "chests_per_game", "potions_drunk",
            "potions_drunk_per_game", "potions_thrown", "potions_thrown_per_game"
    };
    private static final String[] KIT_LABELS = {
            "Wins", "Solo Wins", "Team Wins", "Kills", "Deaths", "WLR", "KDR", "Games",
            "Experience", "XP / Game", "Playtime", "Playtime / Game",
            "Arrows Hit", "Arrows Fired", "Arrow Accuracy", "Damage Dealt", "Damage Taken",
            "Damage Ratio", "Damage / Kill", "Taunt Kills", "Taunt Ratio", "Mobs Spawned",
            "Mobs / Game", "Chests Opened", "Chests / Game", "Potions Drunk",
            "Potions Drunk / Game", "Potions Thrown", "Potions Thrown / Game"
    };
    private static final String[][] RANK_FILTER_OPTIONS = {
            {"NONE", "No rank"},
            {"VIP", "[VIP]"},
            {"VIP_PLUS", "[VIP+]"},
            {"MVP", "[MVP]"},
            {"MVP_PLUS", "[MVP+]"},
            {"SUPERSTAR", "[MVP++]"},
            {"YOUTUBER", "[YOUTUBE]"},
            {"HELPER", "[HELPER]"},
            {"MODERATOR", "[MOD]"},
            {"STAFF", "[STAFF]"},
            {"GM", "[GM]"},
            {"ADMIN", "[ADMIN]"},
            {"OWNER", "[OWNER]"},
            {"BUILD_TEAM", "[BUILD TEAM]"},
            {"BUILDER", "[BUILDER]"},
            {"MOJANG", "[MOJANG]"},
            {"EVENTS", "[EVENTS]"},
            {"INNIT", "[INNIT]"},
            {"PIG", "[PIG]"},
            {"PIG_PLUS", "[PIG+]"},
            {"APPRENTICE", "[APPRENTICE]"},
            {"GAME_DESIGNER", "[GAME DESIGNER]"}
    };
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss", Locale.ROOT);

    private static final class FilterSummaryToken {
        final String text;
        final int color;

        FilterSummaryToken(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static final class DisplayedStatChange {
        final String label;
        final String previousValue;
        final String updatedValue;
        final String delta;
        final int color;

        DisplayedStatChange(String label, String previousValue, String updatedValue,
                            String delta, int color) {
            this.label = label;
            this.previousValue = previousValue;
            this.updatedValue = updatedValue;
            this.delta = delta;
            this.color = color;
        }
    }

    private final GuiScreen parent;
    private final List<GoStatsLeaderboardClient.Player> players = new ArrayList<GoStatsLeaderboardClient.Player>();
    private final List<GoStatsLeaderboardClient.Player> playerSuggestions =
            new ArrayList<GoStatsLeaderboardClient.Player>();
    private final Set<String> seenUuids = new HashSet<String>();
    private GuiButton backButton;
    private GuiButton refreshButton;
    private GuiButton scopeButton;
    private GuiButton sortButton;
    private GuiButton pageSizeButton;
    private GuiButton kitSelectorButton;
    private GuiButton applyFiltersButton;
    private GuiButton updateTop350Button;
    private GuiButton updateActionButton;
    private GuiButton filtersButton;
    private GuiTextField searchField;
    private final List<String> kitNames = new ArrayList<String>();
    private int kitIndex;
    private int openSelectorId = -1;
    private int selectorScrollOffset;
    private int selectorLeft;
    private int selectorTop;
    private int selectorRight;
    private int selectorBottom;
    private int selectorHeaderHeight;
    private int selectorRowHeight;
    private int selectorVisibleRows;
    private int searchLabelX;
    private int totalPlayers;
    private int nextOffset;
    private int loadedStartOffset;
    private int scrollOffset;
    private int requestGeneration;
    private int sortIndex;
    private int scopeIndex;
    private int pageSizeIndex;
    private int refreshCountIndex;
    private String activeFilterCategory = "";
    private final Set<String> selectedRankFilters = new LinkedHashSet<String>();
    private final Set<String> pendingRankFilters = new LinkedHashSet<String>();
    private boolean filterEditActive;
    private GoStatsLeaderboardClient.KitFilters pendingKitFilters;
    private boolean editingKitFilterValue;
    private String filterValueEditSearchBackup = "";
    private String filterValueEditAppliedSearchBackup = "";
    private GoStatsLeaderboardClient.KitFilters kitFilters =
            new GoStatsLeaderboardClient.KitFilters("", "", "", "", "", "");
    private boolean kitSelectionExplicit;
    private GoStatsLeaderboardClient.KitFilterCatalog kitFilterCatalog =
            GoStatsLeaderboardClient.cachedKitFilterCatalog();
    private boolean filterCatalogLoading;
    private boolean filterCatalogAttempted;
    private String filterCatalogError = "";
    private boolean loading;
    private String pageLoadingDirection = "";
    private String loadError;
    private int loadErrorScrollOffset;
    private int loadErrorTextWidth;
    private String appliedKit = "";
    private String appliedSearch = "";
    private String searchHighlightUuid = "";
    private boolean searchWindowLoaded;
    private String refreshStatus = "";
    private final Set<String> refreshingUuids = new HashSet<String>();
    private final Set<String> manuallyRefreshedUuids = new HashSet<String>();
    private static final int MAX_RETAINED_DISPLAYED_STAT_CHANGES = 1200;
    private final LinkedHashMap<String, List<DisplayedStatChange>> displayedStatChangesByUuid =
            new LinkedHashMap<String, List<DisplayedStatChange>>();
    // Keep just presence metadata for recently refreshed rows so filter reloads can restore it.
    private static final int MAX_RETAINED_REFRESH_PRESENCE = 512;
    private final LinkedHashMap<String, RefreshedPresence> refreshedPresenceByUuid =
            new LinkedHashMap<String, RefreshedPresence>();
    private final java.util.Map<String, Long> batchRefreshStartedAt = new java.util.HashMap<String, Long>();
    private final java.util.Map<String, Long> batchRefreshCompletedAt = new java.util.HashMap<String, Long>();
    private final java.util.Map<String, Long> batchRefreshFailedAt = new java.util.HashMap<String, Long>();
    private GoStatsLeaderboardBatchRefresh.Job top350Job;
    private long top350JobId = -1L;
    private int top350Started;
    private int top350Completed;
    private int top350Updated;
    private int top350Skipped;
    private int top350TargetCount;
    private boolean top350Refreshing;
    private boolean top350LoadFailed;
    private String lastTop350Update = "";
    private static final long SESSION_HOVER_DWELL_MS = 350L;
    private GoStatsLeaderboardClient.Player hoveredPlayer;
    private String hoveredSessionUuid = "";
    private long hoveredSessionSinceMs;
    private int panelLeft;
    private int panelRight;
    private int listContentLeft;
    private int listContentRight;
    private int listTop;
    private int listBottom;
    private int visibleRows;
    private int scrollbarLeft;
    private int scrollbarRight;
    private int scrollbarTrackTop;
    private int scrollbarTrackBottom;
    private int scrollbarThumbTop;
    private int scrollbarThumbBottom;
    private boolean draggingScrollbar;
    private int scrollbarGrabOffset;
    private String pendingSuggestionQuery = "";
    private long suggestionRequestAfterMs;
    private int suggestionGeneration;
    private int selectedSuggestionIndex = -1;
    private boolean suggestionsVisible;
    private boolean searchHintShown;
    private boolean searchHintVisible;
    private boolean searchHintDismissed;

    public LeaderboardGUI(GuiScreen parent) {
        this.parent = parent;
        this.pageSizeIndex = pageSizeIndexFor(GoStatsLeaderboardPageSizeStore.getPageSize());
        this.refreshCountIndex = refreshCountIndexFor(GoStatsLeaderboardRefreshCountStore.getRefreshCount());
    }

    public LeaderboardGUI(GuiScreen parent, long initialKillCountSearch) {
        this(parent);
        this.appliedSearch = "!" + Math.max(0L, initialKillCountSearch);
    }

    private static int pageSizeIndexFor(int pageSize) {
        for (int index = 0; index < PAGE_SIZES.length; index++) {
            if (PAGE_SIZES[index] == pageSize) return index;
        }
        return 0;
    }

    private static int refreshCountIndexFor(int count) {
        int[] options = GoStatsLeaderboardRefreshCountStore.getOptions();
        for (int index = 0; index < options.length; index++) {
            if (options[index] == count) return index;
        }
        return options.length - 1;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        clearSuggestions();
        searchHintShown = false;
        searchHintVisible = false;
        searchHintDismissed = false;
        loadKitOptions();
        backButton = new GuiButton(ID_BACK, 0, 0, 150, 20,
                EnumChatFormatting.GRAY + "Back to Miscellaneous");
        scopeButton = new SelectorButton(ID_SCOPE, 0, 0, 72, 20, scopeButtonText(), 4, 20);
        sortButton = new SelectorButton(ID_SORT, 0, 0, 164, 20, sortButtonText(), 4, 20);
        pageSizeButton = new SelectorButton(ID_PAGE_SIZE, 0, 0, 90, 20, pageSizeButtonText(), 4, 20);
        refreshButton = new GuiButton(ID_REFRESH, 0, 0, 82, 20,
                EnumChatFormatting.YELLOW + "Refresh");
        applyFiltersButton = new GuiButton(ID_APPLY_FILTERS, 0, 0, 72, 20,
                EnumChatFormatting.GREEN + "Search");
        kitSelectorButton = new SelectorButton(ID_KIT, 0, 0, 110, 20, kitSelectorButtonText(), 22, 20);
        updateTop350Button = new SelectorButton(ID_UPDATE_TOP_350, 0, 0, 82, 20,
                updateCountButtonText(), 4, 20);
        updateActionButton = new GuiButton(ID_UPDATE_ACTION, 0, 0, 48, 20,
                EnumChatFormatting.YELLOW + "Update");
        filtersButton = new SelectorButton(ID_FILTERS, 0, 0, 64, 20, filtersButtonText(), 4, 20);
        searchField = new GuiTextField(1, fontRendererObj, 0, 0, 160, 18);
        searchField.setMaxStringLength(64);
        searchField.setCanLoseFocus(true);
        searchField.setText(appliedSearch);
        // Fetch future catalog changes in the background while the user views the leaderboard.
        // The current catalog is bundled/cached, so opening Filters never waits for this request.
        loadKitFilterCatalog();
        buttonList.add(scopeButton);
        buttonList.add(sortButton);
        buttonList.add(pageSizeButton);
        buttonList.add(refreshButton);
        buttonList.add(kitSelectorButton);
        buttonList.add(applyFiltersButton);
        buttonList.add(updateTop350Button);
        buttonList.add(updateActionButton);
        buttonList.add(filtersButton);
        buttonList.add(backButton);
        layoutControls();
        if (players.isEmpty() && !loading && loadError == null) {
            resetAndFetch();
        }
    }

    private void layoutControls() {
        int centerX = width / 2;
        int panelHalfWidth = Math.min(330, Math.max(180, width / 2 - 12));
        panelLeft = centerX - panelHalfWidth;
        panelRight = centerX + panelHalfWidth;
        int backY = GuiHeader.backButtonY(height, 20);
        if (backButton != null) {
            backButton.xPosition = centerX - backButton.width / 2;
            backButton.yPosition = backY;
        }
        boolean kitScope = "kit".equals(currentScope());
        boolean searchActive = !appliedSearch.isEmpty()
                || searchField != null && !searchField.getText().trim().isEmpty();
        int filterButtonWidth = filtersButtonWidth();
        int topRowWidth = 72 + 164 + 90 + 70 + 15;
        int x = centerX - topRowWidth / 2;
        if (scopeButton != null) {
            scopeButton.width = 72;
            scopeButton.xPosition = x;
            scopeButton.yPosition = 60;
            scopeButton.enabled = !top350Refreshing;
            scopeButton.displayString = scopeButtonText();
            x += scopeButton.width + 5;
        }
        if (sortButton != null) {
            sortButton.width = 164;
            sortButton.xPosition = x;
            sortButton.yPosition = 60;
            sortButton.enabled = !top350Refreshing;
            sortButton.displayString = sortButtonText();
            x += sortButton.width + 5;
        }
        if (pageSizeButton != null) {
            pageSizeButton.width = 90;
            pageSizeButton.xPosition = x;
            pageSizeButton.yPosition = 60;
            pageSizeButton.enabled = !top350Refreshing;
            pageSizeButton.displayString = pageSizeButtonText();
            x += pageSizeButton.width + 5;
        }
        if (refreshButton != null) {
            refreshButton.width = 70;
            refreshButton.xPosition = x;
            refreshButton.yPosition = 60;
            refreshButton.enabled = !loading && !top350Refreshing;
            refreshButton.displayString = loading
                    ? EnumChatFormatting.GRAY + "Loading..."
                    : EnumChatFormatting.YELLOW + "Refresh";
            x += refreshButton.width + 5;
        }
        if (kitSelectorButton != null && searchField != null && applyFiltersButton != null) {
            if (kitScope) {
                int kitRowY = 87;
                int kitRowWidth = 110 + 5 + 40 + 3 + 80 + 3 + 48 + 5
                        + filterButtonWidth + 5 + 82 + 5 + 60;
                int start = centerX - kitRowWidth / 2;
                kitSelectorButton.visible = true;
                kitSelectorButton.width = 110;
                kitSelectorButton.enabled = !top350Refreshing;
                kitSelectorButton.xPosition = start;
                kitSelectorButton.yPosition = kitRowY;
                kitSelectorButton.displayString = kitSelectorButtonText();

                searchLabelX = start + 115;
                searchField.xPosition = searchLabelX + 43;
                searchField.yPosition = kitRowY + 1;
                searchField.width = 80;
                applyFiltersButton.width = 48;
                applyFiltersButton.xPosition = searchField.xPosition + searchField.width + 3;
                applyFiltersButton.displayString = editingKitFilterValue
                        ? EnumChatFormatting.GREEN + "Apply"
                        : EnumChatFormatting.GREEN + "Search";
                filtersButton.visible = true;
                filtersButton.width = filterButtonWidth;
                filtersButton.xPosition = applyFiltersButton.xPosition + applyFiltersButton.width + 5;
                filtersButton.yPosition = kitRowY;
                filtersButton.enabled = !top350Refreshing;
                filtersButton.displayString = filtersButtonText();

                updateTop350Button.visible = true;
                updateTop350Button.width = 82;
                updateTop350Button.xPosition = filtersButton.xPosition + filtersButton.width + 5;
                updateTop350Button.yPosition = kitRowY;
                updateTop350Button.enabled = !top350Refreshing && !searchActive;
                updateTop350Button.displayString = updateCountButtonText();

                updateActionButton.visible = true;
                updateActionButton.width = 60;
                updateActionButton.xPosition = updateTop350Button.xPosition + updateTop350Button.width + 5;
                updateActionButton.yPosition = kitRowY;
                updateActionButton.enabled = !loading && (top350Refreshing || !searchActive);
                updateActionButton.displayString = top350Refreshing
                        ? EnumChatFormatting.RED + "Stop"
                        : EnumChatFormatting.YELLOW + "Update";
            } else {
                kitSelectorButton.visible = false;
                kitSelectorButton.enabled = false;
                applyFiltersButton.width = 68;
                int rowWidth = 53 + 172 + 5 + applyFiltersButton.width + 5
                        + filterButtonWidth + 5 + 82 + 5 + 60;
                int start = centerX - rowWidth / 2;
                searchLabelX = start;
                searchField.xPosition = start + 53;
                searchField.yPosition = 88;
                searchField.width = 172;
                applyFiltersButton.xPosition = searchField.xPosition + searchField.width + 5;
                filtersButton.visible = true;
                filtersButton.width = filterButtonWidth;
                filtersButton.xPosition = applyFiltersButton.xPosition + applyFiltersButton.width + 5;
                filtersButton.yPosition = 87;
                filtersButton.enabled = !top350Refreshing;
                filtersButton.displayString = filtersButtonText();

                updateTop350Button.visible = true;
                updateTop350Button.width = 82;
                updateTop350Button.xPosition = filtersButton.xPosition + filtersButton.width + 5;
                updateTop350Button.yPosition = 87;
                updateTop350Button.enabled = !top350Refreshing && !searchActive;
                updateTop350Button.displayString = updateCountButtonText();
                updateActionButton.visible = true;
                updateActionButton.width = 60;
                updateActionButton.xPosition = updateTop350Button.xPosition + updateTop350Button.width + 5;
                updateActionButton.yPosition = 87;
                updateActionButton.enabled = !loading && (top350Refreshing || !searchActive);
                updateActionButton.displayString = top350Refreshing
                        ? EnumChatFormatting.RED + "Stop"
                        : EnumChatFormatting.YELLOW + "Update";
            }
            applyFiltersButton.yPosition = 87;
            applyFiltersButton.enabled = !top350Refreshing;
        }
        listTop = 184;
        listBottom = GuiHeader.contentBottomAboveBackBar(height, 20, 10);
        visibleRows = Math.max(1, Math.min(MAX_VISIBLE_ROWS, (listBottom - listTop) / leaderboardRowHeight()));
    }

    private void loadKitOptions() {
        kitNames.clear();
        try {
            kitNames.addAll(DisguiseBlitzKitSelectorOverride.getPreviewKitOrder());
        } catch (Throwable ignored) {
        }
        if (kitNames.isEmpty()) kitNames.add("Scout");
        int found = -1;
        for (int i = 0; i < kitNames.size(); i++) {
            if (kitNames.get(i).equalsIgnoreCase(appliedKit)) {
                found = i;
                break;
            }
        }
        kitIndex = found >= 0 ? found : Math.max(0, Math.min(kitIndex, kitNames.size() - 1));
        appliedKit = kitNames.get(kitIndex);
    }

    private void loadKitFilterCatalog() {
        if (filterCatalogLoading || filterCatalogAttempted) return;
        filterCatalogAttempted = true;
        filterCatalogLoading = true;
        GoStatsLeaderboardClient.fetchKitFilterCatalog((catalog, error) -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null) return;
            minecraft.addScheduledTask(() -> {
                filterCatalogLoading = false;
                if (catalog != null) kitFilterCatalog = catalog;
                filterCatalogError = error == null ? "" : error;
                layoutControls();
            });
        });
    }

    private int currentRefreshCount() {
        int[] options = GoStatsLeaderboardRefreshCountStore.getOptions();
        return options[Math.max(0, Math.min(options.length - 1, refreshCountIndex))];
    }

    private int activeKitFilterCount() {
        int count = 0;
        if ("kit".equals(currentScope()) && !kitFilters.level.isEmpty()) count++;
        if (!kitFilters.finisher.isEmpty()) count++;
        if (!kitFilters.victory.isEmpty()) count++;
        if (!kitFilters.aura.isEmpty()) count++;
        if (!kitFilters.taunt.isEmpty()) count++;
        if (!kitFilters.killEffect.isEmpty()) count++;
        if (!kitFilters.killCounter.isEmpty()) count++;
        if (!selectedRankFilters.isEmpty()) count++;
        return count;
    }

    private String filtersButtonText() {
        int count = activeKitFilterCount();
        return EnumChatFormatting.GRAY + "Filters"
                + (count == 0 ? "" : " " + EnumChatFormatting.YELLOW + "(" + count + ")");
    }

    private int filtersButtonWidth() {
        return Math.max(64, fontRendererObj.getStringWidth(filtersButtonText()) + 28);
    }

    private List<String> kitFilterCategories() {
        List<String> options = new ArrayList<String>();
        options.add(kitLevelFilterTitle() + ": "
                + filterValueLabel("level", currentFilterValue("level")));
        options.add("Finisher: " + filterValueLabel("finisher", currentFilterValue("finisher")));
        options.add("Victory dance: " + filterValueLabel("victory", currentFilterValue("victory")));
        options.add("Aura: " + filterValueLabel("aura", currentFilterValue("aura")));
        options.add("Taunt: " + filterValueLabel("taunt", currentFilterValue("taunt")));
        String killEffectSummary = kitFilterCatalog != null && kitFilterCatalog.killEffectFilteringSupported
                ? filterValueLabel("kill_effect", currentFilterValue("kill_effect"))
                : "service update required";
        options.add("Kill effect: " + killEffectSummary);
        options.add("Kill counter: " + filterValueLabel("kill_counter", currentFilterValue("kill_counter")));
        Set<String> ranks = filterEditActive ? pendingRankFilters : selectedRankFilters;
        options.add("Rank: " + rankFilterSummary(ranks));
        return options;
    }

    private String kitLevelFilterTitle() {
        if ("kit".equals(currentScope())) return "Kit level";
        if (!kitSelectionExplicit || appliedKit == null || appliedKit.trim().isEmpty())
            return "Kit level";
        return "Kit level (" + currentKitName() + ")";
    }

    private String kitForCurrentQuery() {
        return "kit".equals(currentScope()) || kitSelectionExplicit ? currentKitName() : "";
    }

    private String filterValueLabel(String category, String value) {
        if (value == null || value.isEmpty())
            return "kill_counter".equals(category) ? "Both" : "Any";
        if ("level".equals(category)) {
            Set<String> levels = selectedKitLevelTokens(value);
            StringBuilder summary = new StringBuilder();
            for (String level : levels) {
                if (summary.length() > 0) summary.append(", ");
                summary.append(levelLabel(level));
            }
            return summary.toString();
        }
        if ("kill_counter".equals(category)) return "enabled".equalsIgnoreCase(value) ? "Enabled" : "Disabled";
        if ("taunt".equals(category) && ("None".equalsIgnoreCase(value)
                || "Unknown".equalsIgnoreCase(value) || "DEFAULT".equalsIgnoreCase(value))) {
            return "Default";
        }
        if ("finisher".equals(category) || "victory".equals(category) || "aura".equals(category)
                || "taunt".equals(category) || "kill_effect".equals(category))
            return displayProfileCosmeticLabel(value);
        return value.replace('_', ' ');
    }

    private String rankFilterSummary(Set<String> selected) {
        if (selected == null || selected.isEmpty()) return "Any";
        StringBuilder summary = new StringBuilder();
        for (String[] option : RANK_FILTER_OPTIONS) {
            if (!selected.contains(option[0])) continue;
            if (summary.length() > 0) summary.append(", ");
            summary.append(rankFilterOptionLabel(option[0]));
        }
        return summary.length() == 0 ? "Any" : summary.toString();
    }

    /** Matches gostats123's cosmetic label formatting while retaining raw API values internally. */
    public static String displayProfileCosmeticLabel(String value) {
        if (value == null || value.trim().isEmpty()) return "None";
        String raw = value.trim();
        if ("Any".equalsIgnoreCase(raw)) return raw;
        if ("RAPID_FIRE".equals(raw.replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT)))
            return "Flaming Arrows";
        String[] words = raw.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            String upper = word.toUpperCase(Locale.ROOT);
            if ("TNT".equals(upper) || "UFO".equals(upper) || "DJ".equals(upper)
                    || "XP".equals(upper) || "VIP".equals(upper)) {
                label.append(upper);
            } else {
                label.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return label.length() == 0 ? "None" : label.toString();
    }

    private String filterOptionDisplay(String category, String value) {
        if ("finisher".equals(category) || "victory".equals(category) || "aura".equals(category)
                || "taunt".equals(category) || "kill_effect".equals(category))
            return displayProfileCosmeticLabel(value);
        if ("ranks".equals(category)) return rankFilterOptionLabel(value);
        return value;
    }

    private String filterCategoryForIndex(int index) {
        switch (index) {
            case 0: return "level";
            case 1: return "finisher";
            case 2: return "victory";
            case 3: return "aura";
            case 4: return "taunt";
            case 5: return "kill_effect";
            case 6: return "kill_counter";
            case 7: return "ranks";
            default: return "";
        }
    }

    private String filterCategoryTitle(String category) {
        if ("level".equals(category)) return kitLevelFilterTitle();
        if ("finisher".equals(category)) return "Finisher";
        if ("victory".equals(category)) return "Victory dance";
        if ("aura".equals(category)) return "Aura";
        if ("taunt".equals(category)) return "Taunt";
        if ("kill_effect".equals(category)) return "Kill effect";
        if ("ranks".equals(category)) return "Ranks";
        return "Kill counter";
    }

    private List<String> filterValues(String category) {
        List<String> values = new ArrayList<String>();
        values.add("kill_counter".equals(category) ? "Both" : "Any");
        if ("level".equals(category)) {
            Collections.addAll(values, "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
                    "✫", "✫✫");
        } else if ("kill_counter".equals(category)) {
            values.add("Enabled");
            values.add("Disabled");
        } else if ("finisher".equals(category) || "victory".equals(category)
                || "aura".equals(category) || "taunt".equals(category)) {
            boolean taunt = "taunt".equals(category);
            values.add(taunt ? "Default" : "None");
            if (kitFilterCatalog != null) {
                List<String> catalog = "finisher".equals(category) ? kitFilterCatalog.finishers
                        : "victory".equals(category) ? kitFilterCatalog.victories
                        : "aura".equals(category) ? kitFilterCatalog.auras : kitFilterCatalog.taunts;
                for (String option : catalog) {
                    String normalized = option == null ? "" : option.replace('_', ' ').trim();
                    if ("none".equalsIgnoreCase(normalized)
                            || (taunt && "default".equalsIgnoreCase(normalized)))
                        continue;
                    values.add(option);
                }
            }
        } else if ("kill_effect".equals(category)) {
            List<String> catalog = kitFilterCatalog == null
                    ? java.util.Arrays.asList("SPEED", "RESISTANCE", "REGENERATION", "GRAVEDIGGER",
                            "FLAMING_ARROWS", "LEVEL_UP", "RANDOM")
                    : kitFilterCatalog.killEffects;
            for (String option : catalog) {
                if (option != null && !option.trim().isEmpty() && !values.contains(option)) values.add(option);
            }
        } else if ("ranks".equals(category)) {
            for (String[] rank : RANK_FILTER_OPTIONS) values.add(rank[0]);
        }
        return values;
    }

    private String rankFilterOptionLabel(String key) {
        if ("Any".equals(key)) return key;
        for (String[] rank : RANK_FILTER_OPTIONS) {
            if (rank[0].equals(key)) return rank[1];
        }
        return key.replace('_', ' ');
    }

    private String rankFilterColoredLabel(String key) {
        switch (key) {
            case "NONE": return EnumChatFormatting.GRAY + "No rank";
            case "VIP_PLUS": return HypixelTabStatsRankCache.formatOfficialRankTag("VIP+", "", "");
            case "MVP_PLUS": return HypixelTabStatsRankCache.formatOfficialRankTag("MVP+", "", "");
            case "SUPERSTAR": return HypixelTabStatsRankCache.formatOfficialRankTag("MVP++", "", "");
            case "YOUTUBER": return HypixelTabStatsRankCache.formatOfficialRankTag("YOUTUBER", "", "");
            case "HELPER": return EnumChatFormatting.DARK_AQUA + "[HELPER]";
            case "MODERATOR": return EnumChatFormatting.DARK_GREEN + "[MOD]";
            case "STAFF": return EnumChatFormatting.RED + "[STAFF]";
            case "OWNER": return EnumChatFormatting.DARK_RED + "[OWNER]";
            case "BUILD_TEAM": return EnumChatFormatting.DARK_AQUA + "[BUILD TEAM]";
            case "BUILDER": return EnumChatFormatting.DARK_AQUA + "[BUILDER]";
            case "PIG": return EnumChatFormatting.LIGHT_PURPLE + "[PIG]";
            case "PIG_PLUS": return EnumChatFormatting.LIGHT_PURPLE + "[PIG"
                    + EnumChatFormatting.AQUA + "+" + EnumChatFormatting.LIGHT_PURPLE + "]";
            case "APPRENTICE": return EnumChatFormatting.YELLOW + "[APPRENTICE]";
            case "GAME_DESIGNER": return EnumChatFormatting.YELLOW + "[GAME DESIGNER]";
            default:
                return HypixelTabStatsRankCache.formatOfficialRankTag(key, "", "");
        }
    }

    private String filterSummaryPrefix(String category) {
        return "level".equals(category) ? kitLevelFilterTitle() + ": " : "Rank: ";
    }

    private List<FilterSummaryToken> filterSummaryTokens(String category) {
        List<FilterSummaryToken> tokens = new ArrayList<FilterSummaryToken>();
        if ("level".equals(category)) {
            Set<String> levels = selectedKitLevelTokens(currentFilterValue("level"));
            if (levels.isEmpty()) {
                tokens.add(new FilterSummaryToken("Any", MUTED));
            } else {
                for (String level : levels) {
                    String label = levelLabel(level);
                    tokens.add(new FilterSummaryToken(label, kitFilterLevelColor(label)));
                }
            }
        } else if ("ranks".equals(category)) {
            Set<String> ranks = filterEditActive ? pendingRankFilters : selectedRankFilters;
            if (ranks.isEmpty()) {
                tokens.add(new FilterSummaryToken("Any", MUTED));
            } else {
                for (String[] option : RANK_FILTER_OPTIONS) {
                    if (ranks.contains(option[0])) {
                        tokens.add(new FilterSummaryToken(rankFilterColoredLabel(option[0]), TEXT));
                    }
                }
            }
        }
        return tokens;
    }

    private int filterSummaryLineCount(String category, int availableWidth) {
        List<FilterSummaryToken> tokens = filterSummaryTokens(category);
        int prefixWidth = fontRendererObj.getStringWidth(filterSummaryPrefix(category));
        int cursor = Math.min(prefixWidth, Math.max(1, availableWidth));
        int lines = 1;
        boolean hasTokenOnLine = false;
        for (FilterSummaryToken token : tokens) {
            int separatorWidth = hasTokenOnLine ? fontRendererObj.getStringWidth(", ") : 0;
            int tokenWidth = fontRendererObj.getStringWidth(token.text);
            if (cursor + separatorWidth + tokenWidth > availableWidth && cursor > 0) {
                lines++;
                cursor = tokenWidth;
                hasTokenOnLine = true;
            } else {
                cursor += separatorWidth + tokenWidth;
                hasTokenOnLine = true;
            }
        }
        return Math.max(1, lines);
    }

    private int filterCategoryRowHeight(int menuWidth) {
        int availableWidth = Math.max(1, menuWidth - 36);
        int lines = 1;
        for (String category : new String[] {"level", "ranks"}) {
            lines = Math.max(lines, filterSummaryLineCount(category, availableWidth));
        }
        return Math.max(15, Math.min(64, 4 + lines * 10));
    }

    private boolean grayAnyFilterCategory(String category) {
        return "finisher".equals(category) || "victory".equals(category) || "aura".equals(category)
                || "taunt".equals(category) || "kill_effect".equals(category)
                || "kill_counter".equals(category);
    }

    private boolean grayDefaultFilterValue(String category, String value) {
        return "Any".equalsIgnoreCase(value)
                || ("kill_counter".equals(category) && "Both".equalsIgnoreCase(value));
    }

    private boolean hasAppliedFilter(String category) {
        if ("ranks".equals(category)) return !selectedRankFilters.isEmpty();
        String value;
        if ("level".equals(category)) value = kitFilters.level;
        else if ("finisher".equals(category)) value = kitFilters.finisher;
        else if ("victory".equals(category)) value = kitFilters.victory;
        else if ("aura".equals(category)) value = kitFilters.aura;
        else if ("taunt".equals(category)) value = kitFilters.taunt;
        else if ("kill_effect".equals(category)) value = kitFilters.killEffect;
        else if ("kill_counter".equals(category)) value = kitFilters.killCounter;
        else return false;
        if ("level".equals(category) && !"kit".equals(currentScope())) return false;
        return value != null && !value.trim().isEmpty() && !"Any".equalsIgnoreCase(value.trim());
    }

    /** True when this category's visible draft differs from the values currently sent to gostats123. */
    private boolean hasUnappliedFilterChange(String category) {
        if (!filterEditActive) return false;
        if ("ranks".equals(category)) return !pendingRankFilters.equals(selectedRankFilters);
        String draft = currentFilterValue(category);
        String applied;
        if ("level".equals(category)) applied = kitFilters.level;
        else if ("finisher".equals(category)) applied = kitFilters.finisher;
        else if ("victory".equals(category)) applied = kitFilters.victory;
        else if ("aura".equals(category)) applied = kitFilters.aura;
        else if ("taunt".equals(category)) applied = kitFilters.taunt;
        else if ("kill_effect".equals(category)) applied = kitFilters.killEffect;
        else if ("kill_counter".equals(category)) applied = kitFilters.killCounter;
        else return false;
        if (draft == null) draft = "";
        if (applied == null) applied = "";
        return !draft.equals(applied);
    }

    private boolean filterCategoryEnabled(String category) {
        if ("level".equals(category)) return "kit".equals(currentScope());
        if ("kill_effect".equals(category))
            return kitFilterCatalog != null && kitFilterCatalog.killEffectFilteringSupported;
        return true;
    }

    private String filterCategoryPrefix(String category) {
        if ("finisher".equals(category)) return "Finisher: ";
        if ("victory".equals(category)) return "Victory dance: ";
        if ("aura".equals(category)) return "Aura: ";
        if ("taunt".equals(category)) return "Taunt: ";
        if ("kill_effect".equals(category)) return "Kill effect: ";
        if ("kill_counter".equals(category)) return "Kill counter: ";
        return "";
    }

    private void drawFilterCategoryDefaultValue(String category, String value,
            int x, int y, int availableWidth) {
        String prefix = filterCategoryPrefix(category);
        int valueWidth = fontRendererObj.getStringWidth(value);
        String visiblePrefix = fitText(prefix, Math.max(0, availableWidth - valueWidth));
        fontRendererObj.drawStringWithShadow(visiblePrefix, x, y, TEXT);
        fontRendererObj.drawStringWithShadow(value, x + fontRendererObj.getStringWidth(visiblePrefix),
                y, MUTED);
    }

    private void drawFilterCategorySummary(String category, int x, int rowTop, int availableWidth) {
        String prefix = filterSummaryPrefix(category);
        String visiblePrefix = fitText(prefix, availableWidth);
        fontRendererObj.drawStringWithShadow(visiblePrefix, x, rowTop + 2, TEXT);
        int cursorX = x + fontRendererObj.getStringWidth(visiblePrefix);
        int lineY = rowTop + 2;
        int line = 1;
        boolean hasTokenOnLine = false;
        int rightEdge = x + availableWidth;
        for (FilterSummaryToken token : filterSummaryTokens(category)) {
            int separatorWidth = hasTokenOnLine ? fontRendererObj.getStringWidth(", ") : 0;
            int tokenWidth = fontRendererObj.getStringWidth(token.text);
            if (cursorX + separatorWidth + tokenWidth > rightEdge && cursorX > x) {
                line++;
                lineY += 10;
                cursorX = x;
                hasTokenOnLine = false;
                separatorWidth = 0;
            }
            if (lineY + fontRendererObj.FONT_HEIGHT > rowTop + selectorRowHeight) return;
            if (separatorWidth > 0) {
                fontRendererObj.drawStringWithShadow(", ", cursorX, lineY, MUTED);
                cursorX += separatorWidth;
            }
            fontRendererObj.drawStringWithShadow(token.text, cursorX, lineY, token.color);
            cursorX += tokenWidth;
            hasTokenOnLine = true;
        }
    }

    private String currentFilterValue(String category) {
        if ("ranks".equals(category)) {
            Set<String> ranks = filterEditActive ? pendingRankFilters : selectedRankFilters;
            return ranks.isEmpty() ? "" : ranks.size() + " selected";
        }
        GoStatsLeaderboardClient.KitFilters filters = filterEditActive && pendingKitFilters != null
                ? pendingKitFilters : kitFilters;
        if ("level".equals(category)) return filters.level;
        if ("finisher".equals(category)) return filters.finisher;
        if ("victory".equals(category)) return filters.victory;
        if ("aura".equals(category)) return filters.aura;
        if ("taunt".equals(category)) return filters.taunt;
        if ("kill_effect".equals(category)) return filters.killEffect;
        return filters.killCounter;
    }

    private Set<String> selectedKitLevelTokens(String value) {
        Set<String> selected = new LinkedHashSet<String>();
        if (value == null || value.trim().isEmpty()) return selected;
        for (String token : value.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) selected.add(normalized);
        }
        return selected;
    }

    private String joinKitLevelTokens(Set<String> selected) {
        StringBuilder result = new StringBuilder();
        for (String token : selected) {
            if (result.length() > 0) result.append(',');
            result.append(token);
        }
        return result.toString();
    }

    private String filterParamValue(String category, String option) {
        if (isUnfilteredFilterOption(category, option)) return "";
        if ("level".equals(category)) {
            if ("✫".equals(option) || "Prestige I".equalsIgnoreCase(option)) return "p1";
            if ("✫✫".equals(option) || "Prestige II".equalsIgnoreCase(option)) return "p2";
            String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
            for (int index = 0; index < roman.length; index++) {
                if (roman[index].equalsIgnoreCase(option)) return String.valueOf(index + 1);
            }
        } else if ("kill_counter".equals(category)) {
            return option.toLowerCase(Locale.ROOT);
        } else if (("finisher".equals(category) || "victory".equals(category) || "aura".equals(category))
                && "None".equalsIgnoreCase(option)) {
            return "None";
        } else if ("taunt".equals(category) && ("Default".equalsIgnoreCase(option)
                || "None".equalsIgnoreCase(option) || "Unknown".equalsIgnoreCase(option))) {
            return "DEFAULT";
        }
        return option;
    }

    private boolean isUnfilteredFilterOption(String category, String option) {
        return "Any".equalsIgnoreCase(option)
                || ("kill_counter".equals(category) && "Both".equalsIgnoreCase(option));
    }

    private String levelLabel(String value) {
        if ("p1".equalsIgnoreCase(value) || "Prestige I".equalsIgnoreCase(value)) return "✫";
        if ("p2".equalsIgnoreCase(value) || "Prestige II".equalsIgnoreCase(value)) return "✫✫";
        try {
            int level = Integer.parseInt(value);
            String[] roman = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
            return level > 0 && level < roman.length ? roman[level] : value;
        } catch (Exception ignored) {
            return value;
        }
    }

    private void setFilter(String category, String value) {
        kitFilters = new GoStatsLeaderboardClient.KitFilters(
                "level".equals(category) ? value : kitFilters.level,
                "finisher".equals(category) ? value : kitFilters.finisher,
                "victory".equals(category) ? value : kitFilters.victory,
                "aura".equals(category) ? value : kitFilters.aura,
                "taunt".equals(category) ? value : kitFilters.taunt,
                "kill_counter".equals(category) ? value : kitFilters.killCounter,
                "kill_effect".equals(category) ? value : kitFilters.killEffect,
                kitFilters.ranks, kitFilters.levelKit);
    }

    private void setPendingFilter(String category, String value) {
        GoStatsLeaderboardClient.KitFilters filters = pendingKitFilters == null ? kitFilters : pendingKitFilters;
        pendingKitFilters = new GoStatsLeaderboardClient.KitFilters(
                "level".equals(category) ? value : filters.level,
                "finisher".equals(category) ? value : filters.finisher,
                "victory".equals(category) ? value : filters.victory,
                "aura".equals(category) ? value : filters.aura,
                "taunt".equals(category) ? value : filters.taunt,
                "kill_counter".equals(category) ? value : filters.killCounter,
                "kill_effect".equals(category) ? value : filters.killEffect,
                filters.ranks, filters.levelKit);
    }

    private void beginFilterEdit() {
        pendingKitFilters = new GoStatsLeaderboardClient.KitFilters(kitFilters.level, kitFilters.finisher,
                kitFilters.victory, kitFilters.aura, kitFilters.taunt, kitFilters.killCounter,
                kitFilters.killEffect, kitFilters.ranks, kitFilters.levelKit);
        pendingRankFilters.clear();
        pendingRankFilters.addAll(selectedRankFilters);
        filterEditActive = true;
    }

    private void resetPendingFilters() {
        if (!filterEditActive) beginFilterEdit();
        pendingKitFilters = new GoStatsLeaderboardClient.KitFilters("", "", "", "", "", "");
        pendingRankFilters.clear();
        refreshStatus = "";
    }

    private void applyPendingFilters() {
        GoStatsLeaderboardClient.KitFilters draft = pendingKitFilters == null ? kitFilters : pendingKitFilters;
        boolean filtersChanged = !sameKitFilters(kitFilters, draft)
                || !selectedRankFilters.equals(pendingRankFilters);
        kitFilters = draft;
        selectedRankFilters.clear();
        selectedRankFilters.addAll(pendingRankFilters);
        filterEditActive = false;
        pendingKitFilters = null;
        pendingRankFilters.clear();
        activeFilterCategory = "";
        openSelectorId = -1;
        selectorScrollOffset = 0;
        if (filtersChanged) {
            refreshStatus = "";
            resetAndFetch();
        }
        layoutControls();
    }

    private boolean sameKitFilters(GoStatsLeaderboardClient.KitFilters left,
                                  GoStatsLeaderboardClient.KitFilters right) {
        return selectedKitLevelTokens(left.level).equals(selectedKitLevelTokens(right.level))
                && left.finisher.equals(right.finisher)
                && left.victory.equals(right.victory) && left.aura.equals(right.aura)
                && left.taunt.equals(right.taunt) && left.killCounter.equals(right.killCounter)
                && left.killEffect.equals(right.killEffect);
    }

    private void beginCustomFilterValueEntry() {
        editingKitFilterValue = true;
        filterValueEditSearchBackup = searchField == null ? "" : searchField.getText();
        filterValueEditAppliedSearchBackup = appliedSearch;
        clearSuggestions();
        if (searchField != null) {
            searchField.setText("");
            searchField.setFocused(true);
        }
        openSelectorId = -1;
        refreshStatus = "Type the exact " + filterCategoryTitle(activeFilterCategory)
                + " name below, then click Apply.";
        layoutControls();
    }

    private void cancelCustomFilterValueEntry() {
        if (!editingKitFilterValue) return;
        editingKitFilterValue = false;
        activeFilterCategory = "";
        openSelectorId = filterEditActive ? ID_FILTERS : -1;
        selectorScrollOffset = 0;
        appliedSearch = filterValueEditAppliedSearchBackup;
        if (searchField != null) {
            searchField.setText(filterValueEditSearchBackup);
            searchField.setFocused(false);
        }
        clearSuggestions();
        refreshStatus = "Filter entry cancelled.";
        layoutControls();
    }

    private void resetAndFetch() {
        clearSuggestions();
        requestGeneration++;
        loading = false;
        pageLoadingDirection = "";
        players.clear();
        seenUuids.clear();
        searchHighlightUuid = "";
        searchWindowLoaded = false;
        totalPlayers = 0;
        nextOffset = 0;
        loadedStartOffset = 0;
        scrollOffset = 0;
        loadError = null;
        loadErrorScrollOffset = 0;
        fetchNextPage();
    }

    private void fetchNextPage() {
        if (loading || nextOffset > 0 && nextOffset >= totalPlayers) return;
        final int generation = requestGeneration;
        final int requestedOffset = nextOffset;
        loading = true;
        pageLoadingDirection = requestedOffset > 0 ? "below" : "";
        layoutControls();
        GoStatsLeaderboardClient.Callback callback = new GoStatsLeaderboardClient.Callback() {
                    @Override
                    public void onComplete(final GoStatsLeaderboardClient.Page page, final String error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) return;
                        minecraft.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                if (generation != requestGeneration) return;
                                loading = false;
                                pageLoadingDirection = "";
                                if (error != null) {
                                    loadError = error;
                                    loadErrorScrollOffset = 0;
                                    layoutControls();
                                    return;
                                }
                                loadError = null;
                                loadErrorScrollOffset = 0;
                                totalPlayers = Math.max(0, page.total);
                                if (requestedOffset == 0) {
                                    searchWindowLoaded = isJumpSearch() || page.searchAnchorUuid != null;
                                }
                                if (players.isEmpty() && requestedOffset == 0) {
                                    loadedStartOffset = page.offset;
                                }
                                if (page.searchAnchorUuid != null) {
                                    searchHighlightUuid = page.searchAnchorUuid;
                                }
                                int before = nextOffset;
                                for (GoStatsLeaderboardClient.Player player : page.players) {
                                    player = restoreRetainedRefreshPresence(player);
                                    String key = player.uuid == null || player.uuid.isEmpty()
                                            ? player.username.toLowerCase(Locale.ROOT)
                                            : normalizeLeaderboardUuid(player.uuid);
                                    if (seenUuids.add(key)) players.add(player);
                                }
                                nextOffset = page.offset + page.players.size();
                                if (nextOffset <= before && nextOffset < totalPlayers) {
                                    nextOffset = Math.min(totalPlayers, before + currentPageSize());
                                }
                                if (page.searchAnchorUuid != null) {
                                    int matchIndex = indexOfPlayer(page.searchAnchorUuid);
                                    if (matchIndex >= 0) {
                                        scrollOffset = Math.max(0, matchIndex - visibleRows / 2);
                                    }
                                }
                                layoutControls();
                                maybeLoadMore();
                            }
                        });
                    }
                };
        if (isJumpSearch() && requestedOffset == 0) {
            GoStatsLeaderboardClient.fetchPageAroundSearch(currentScope(), currentMetric(),
                    kitForCurrentQuery(), appliedSearch, currentPageSize(), currentKitFilters(), callback);
        } else if (requestedOffset == 0 && isIgnSuggestionQuery(appliedSearch)) {
            GoStatsLeaderboardClient.fetchPageAroundName(currentScope(), currentMetric(),
                    kitForCurrentQuery(), appliedSearch,
                    Math.min(INITIAL_ANCHOR_PAGE_SIZE, currentPageSize()), currentKitFilters(), callback);
        } else {
            GoStatsLeaderboardClient.fetchPage(requestedOffset, currentScope(), currentMetric(),
                    kitForCurrentQuery(), searchWindowLoaded ? "" : appliedSearch, currentPageSize(),
                    currentKitFilters(), callback);
        }
    }

    private boolean isJumpSearch() {
        return appliedSearch.startsWith("!") || appliedSearch.startsWith("#");
    }

    private void clearSuggestions() {
        suggestionGeneration++;
        GoStatsLeaderboardClient.cancelSuggestions();
        pendingSuggestionQuery = "";
        playerSuggestions.clear();
        suggestionsVisible = false;
        selectedSuggestionIndex = -1;
    }

    private boolean isIgnSuggestionQuery(String query) {
        return query != null && query.matches("[A-Za-z0-9_]{2,16}");
    }

    private void scheduleSearchSuggestions() {
        if (searchField == null) return;
        if (editingKitFilterValue) {
            clearSuggestions();
            return;
        }
        String query = searchField.getText().trim();
        suggestionGeneration++;
        GoStatsLeaderboardClient.cancelSuggestions();
        pendingSuggestionQuery = "";
        playerSuggestions.clear();
        suggestionsVisible = false;
        selectedSuggestionIndex = -1;
        if (!isIgnSuggestionQuery(query)) return;
        pendingSuggestionQuery = query;
        suggestionRequestAfterMs = System.currentTimeMillis() + SUGGESTION_DEBOUNCE_MS;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField == null) return;
        if (!searchField.isFocused()) {
            if (!pendingSuggestionQuery.isEmpty() || suggestionsVisible) clearSuggestions();
            return;
        }
        if (pendingSuggestionQuery.isEmpty()
                || System.currentTimeMillis() < suggestionRequestAfterMs) return;

        final String query = pendingSuggestionQuery;
        pendingSuggestionQuery = "";
        final int generation = ++suggestionGeneration;
        GoStatsLeaderboardClient.fetchSuggestions(currentScope(), currentMetric(),
                kitForCurrentQuery(), query,
                currentKitFilters(),
                new GoStatsLeaderboardClient.Callback() {
                    @Override
                    public void onComplete(final GoStatsLeaderboardClient.Page page, final String error) {
                        final Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) return;
                        minecraft.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                if (generation != suggestionGeneration || searchField == null
                                        || !searchField.isFocused()
                                        || !query.equals(searchField.getText().trim())) return;
                                playerSuggestions.clear();
                                if (error == null && page != null) {
                                    for (int pass = 0; pass < 2
                                            && playerSuggestions.size() < MAX_SUGGESTION_ROWS; pass++) {
                                        for (GoStatsLeaderboardClient.Player player : page.players) {
                                            boolean startsWith = player.username.regionMatches(true, 0,
                                                    query, 0, query.length());
                                            if (startsWith != (pass == 0)) continue;
                                            playerSuggestions.add(player);
                                            if (playerSuggestions.size() >= MAX_SUGGESTION_ROWS) break;
                                        }
                                    }
                                }
                                suggestionsVisible = !playerSuggestions.isEmpty();
                                selectedSuggestionIndex = -1;
                            }
                        });
                    }
                });
    }

    private boolean isSearchFieldHovered(int mouseX, int mouseY) {
        return searchField != null && mouseX >= searchField.xPosition
                && mouseX < searchField.xPosition + searchField.width
                && mouseY >= searchField.yPosition && mouseY < searchField.yPosition + 18;
    }

    private int suggestionBoxWidth() {
        return Math.min(220, Math.max(searchField == null ? 0 : searchField.width,
                panelRight - panelLeft - 2));
    }

    private int suggestionBoxLeft() {
        int boxWidth = suggestionBoxWidth();
        return Math.max(panelLeft + 1,
                Math.min(searchField == null ? panelLeft + 1 : searchField.xPosition,
                        panelRight - boxWidth - 1));
    }

    private int suggestionBoxTop() {
        return searchField == null ? 0 : searchField.yPosition + 20;
    }

    private int suggestionIndexAt(int mouseX, int mouseY) {
        if (!suggestionsVisible || searchField == null || !searchField.isFocused()
                || playerSuggestions.isEmpty()) return -1;
        int count = Math.min(MAX_SUGGESTION_ROWS, playerSuggestions.size());
        int left = suggestionBoxLeft();
        int top = suggestionBoxTop();
        int right = left + suggestionBoxWidth();
        int bottom = top + count * SUGGESTION_ROW_HEIGHT;
        if (mouseX < left || mouseX >= right || mouseY < top || mouseY >= bottom) return -1;
        return (mouseY - top) / SUGGESTION_ROW_HEIGHT;
    }

    private void drawPlayerSuggestions(int mouseX, int mouseY) {
        if (!suggestionsVisible || searchField == null || !searchField.isFocused()
                || playerSuggestions.isEmpty()) return;
        int count = Math.min(MAX_SUGGESTION_ROWS, playerSuggestions.size());
        int left = suggestionBoxLeft();
        int top = suggestionBoxTop();
        int right = left + suggestionBoxWidth();
        int bottom = top + count * SUGGESTION_ROW_HEIGHT;
        int hovered = suggestionIndexAt(mouseX, mouseY);
        Gui.drawRect(left, top, right, bottom, 0xF010151B);
        Gui.drawRect(left, top, right, top + 1, 0xFF526170);
        Gui.drawRect(left, bottom - 1, right, bottom, 0xFF526170);
        Gui.drawRect(left, top, left + 1, bottom, 0xFF526170);
        Gui.drawRect(right - 1, top, right, bottom, 0xFF526170);
        for (int index = 0; index < count; index++) {
            int rowTop = top + index * SUGGESTION_ROW_HEIGHT;
            if (index == hovered || index == selectedSuggestionIndex) {
                Gui.drawRect(left + 1, rowTop, right - 1, rowTop + SUGGESTION_ROW_HEIGHT,
                        0xFF344452);
            }
            GoStatsLeaderboardClient.Player player = playerSuggestions.get(index);
            PlayerHeadTextureUtil.drawMinotarAvatar(player.uuid, left + 3, rowTop + 2, 10);
            String name = fitText(buildRankedUsernameText(player), suggestionBoxWidth() - 22);
            fontRendererObj.drawStringWithShadow(name, left + 18, rowTop + 3, TEXT);
        }
    }

    private void selectSuggestion(int index) {
        if (searchField == null || index < 0 || index >= playerSuggestions.size()) return;
        searchField.setText(playerSuggestions.get(index).username);
        searchHintDismissed = true;
        applyFilters();
    }

    private int maxScroll() {
        return Math.max(0, players.size() - visibleRows);
    }

    private int leaderboardRowHeight() {
        return "kit".equals(currentScope()) ? KIT_ROW_HEIGHT : ROW_HEIGHT;
    }

    private void clampScroll() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
    }

    private void maybeLoadMore() {
        clampScroll();
        if (top350Refreshing) return;
        int prefetchRows = players.size() < currentPageSize() ? 1 : 22;
        if (!loading && loadError == null && nextOffset < totalPlayers
                && scrollOffset + visibleRows >= players.size() - prefetchRows) {
            fetchNextPage();
        }
    }

    private void maybeLoadPrevious() {
        if (loading || players.isEmpty() || loadedStartOffset <= 0 || scrollOffset > 2) return;
        final int requestedOffset = Math.max(0, loadedStartOffset - currentPageSize());
        if (requestedOffset >= loadedStartOffset) return;
        final int generation = requestGeneration;
        loading = true;
        pageLoadingDirection = "above";
        layoutControls();
        String search = searchWindowLoaded ? "" : appliedSearch;
        GoStatsLeaderboardClient.fetchPage(requestedOffset, currentScope(), currentMetric(),
                kitForCurrentQuery(), search, currentPageSize(), currentKitFilters(), new GoStatsLeaderboardClient.Callback() {
                    @Override
                    public void onComplete(final GoStatsLeaderboardClient.Page page, final String error) {
                        Minecraft minecraft = Minecraft.getMinecraft();
                        if (minecraft == null) return;
                        minecraft.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                if (generation != requestGeneration) return;
                                loading = false;
                                pageLoadingDirection = "";
                                if (error != null) {
                                    refreshStatus = "Could not load earlier players: " + error;
                                    layoutControls();
                                    return;
                                }
                                totalPlayers = Math.max(totalPlayers, page.total);
                                int scrollBeforePrepend = scrollOffset;
                                List<GoStatsLeaderboardClient.Player> earlier =
                                        new ArrayList<GoStatsLeaderboardClient.Player>();
                                for (GoStatsLeaderboardClient.Player player : page.players) {
                                    String key = player.uuid == null || player.uuid.isEmpty()
                                            ? player.username.toLowerCase(Locale.ROOT) : player.uuid;
                                    if (seenUuids.add(key)) earlier.add(player);
                                }
                                loadedStartOffset = page.offset;
                                if (!earlier.isEmpty()) {
                                    players.addAll(0, earlier);
                                    scrollOffset = Math.max(0,
                                            earlier.size() - visibleRows + scrollBeforePrepend);
                                    refreshStatus = "";
                                }
                                layoutControls();
                                clampScroll();
                            }
                        });
                    }
                });
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) return;
        if (button.id == ID_BACK) {
            mc.displayGuiScreen(parent);
        } else if (editingKitFilterValue && button.id != ID_APPLY_FILTERS) {
            return;
        } else if (button.id == ID_UPDATE_TOP_350 && !top350Refreshing) {
            openSelectorId = openSelectorId == ID_UPDATE_TOP_350 ? -1 : ID_UPDATE_TOP_350;
            selectorScrollOffset = 0;
        } else if (button.id == ID_UPDATE_ACTION) {
            if (top350Refreshing) {
                if (top350Job != null) top350Job.cancel();
                refreshStatus = "Stopping the leaderboard update after the current request...";
                layoutControls();
            } else if (!loading && appliedSearch.isEmpty()
                    && (searchField == null || searchField.getText().trim().isEmpty())) {
                startTop350Refresh();
            }
        } else if (button.id == ID_REFRESH && !loading && !top350Refreshing) {
            resetAndFetch();
        } else if ((button.id == ID_SCOPE || button.id == ID_SORT
                || button.id == ID_PAGE_SIZE || button.id == ID_KIT
                ) && !top350Refreshing) {
            openSelectorId = openSelectorId == button.id ? -1 : button.id;
            selectorScrollOffset = 0;
        } else if (button.id == ID_FILTERS && !top350Refreshing) {
            loadKitFilterCatalog();
            activeFilterCategory = "";
            if (openSelectorId == ID_FILTERS) {
                openSelectorId = -1;
            } else {
                if (!filterEditActive) beginFilterEdit();
                openSelectorId = ID_FILTERS;
            }
            selectorScrollOffset = 0;
        } else if (button.id == ID_APPLY_FILTERS && !top350Refreshing) {
            applyFilters();
        }
    }

    private void applyFilters() {
        if (top350Refreshing) return;
        if (editingKitFilterValue) {
            String value = searchField == null ? "" : searchField.getText().trim();
            if (value.isEmpty()) {
                refreshStatus = "Enter a filter value first.";
                return;
            }
            if (filterEditActive) {
                setPendingFilter(activeFilterCategory, value);
            } else {
                setFilter(activeFilterCategory, value);
            }
            editingKitFilterValue = false;
            activeFilterCategory = "";
            appliedSearch = filterValueEditAppliedSearchBackup;
            if (searchField != null) {
                searchField.setText(filterValueEditSearchBackup);
                searchField.setFocused(false);
            }
            clearSuggestions();
            if (filterEditActive) {
                refreshStatus = "Filter staged: " + value + ". Choose more filters or press Done.";
                openSelectorId = ID_FILTERS;
            } else {
                refreshStatus = "Filter applied: " + value + ".";
                resetAndFetch();
            }
            layoutControls();
            return;
        }
        appliedSearch = searchField == null ? appliedSearch : searchField.getText().trim();
        refreshStatus = "";
        resetAndFetch();
        if (searchField != null) searchField.setFocused(false);
    }

    private void startTop350Refresh() {
        boolean searchActive = !appliedSearch.isEmpty()
                || searchField != null && !searchField.getText().trim().isEmpty();
        if (top350Refreshing || searchActive) return;
        top350Refreshing = true;
        top350LoadFailed = false;
        top350Job = null;
        top350JobId = -1L;
        top350Started = 0;
        top350Completed = 0;
        top350Updated = 0;
        top350Skipped = 0;
        top350TargetCount = 0;
        lastTop350Update = "";
        manuallyRefreshedUuids.clear();
        refreshedPresenceByUuid.clear();
        batchRefreshStartedAt.clear();
        batchRefreshCompletedAt.clear();
        batchRefreshFailedAt.clear();
        String targetLabel = "kit".equals(currentScope()) ? appliedKit : currentScopeLabel();
        refreshStatus = "Loading the first " + currentRefreshCount() + " " + targetLabel + " "
                + currentSortLabel().toLowerCase(Locale.ROOT) + " players...";
        layoutControls();

        List<GoStatsLeaderboardClient.Player> loadedTopPlayers = loadedStartOffset == 0
                && !players.isEmpty() && players.get(0).rank == 1
                ? new ArrayList<GoStatsLeaderboardClient.Player>(players) : null;
        GoStatsLeaderboardBatchRefresh.Job job = GoStatsLeaderboardBatchRefresh.start(this,
                currentScope(), kitForCurrentQuery(),
                currentMetric(), currentRefreshCount(),
                currentKitFilters(),
                loadedTopPlayers, totalPlayers);
        if (job == null) {
            top350Refreshing = false;
            refreshStatus = "A leaderboard update is already running.";
            layoutControls();
            return;
        }
        top350Job = job;
        top350JobId = job.getId();
        File debugFile = LeaderboardRefreshDebugCapture.getOutputFile(top350JobId);
        if (debugFile != null) {
            me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Leaderboard refresh diagnostics are recording to: " + EnumChatFormatting.WHITE
                    + debugFile.getAbsolutePath());
        }
        layoutControls();
    }

    @Override
    public void onTop350PlayersLoaded(long jobId, List<GoStatsLeaderboardClient.Player> topPlayers,
                                      int total) {
        if (jobId != top350JobId || !top350Refreshing) return;
        boolean keepLoadedRows = loadedStartOffset == 0 && topPlayers != null && players.size() >= topPlayers.size();
        if (keepLoadedRows) {
            for (int i = 0; i < topPlayers.size(); i++) {
                if (!players.get(i).uuid.equalsIgnoreCase(topPlayers.get(i).uuid)) {
                    keepLoadedRows = false;
                    break;
                }
            }
        }
        if (!keepLoadedRows) {
            players.clear();
            seenUuids.clear();
            if (topPlayers != null) {
                for (GoStatsLeaderboardClient.Player player : topPlayers) {
                    if (player == null) continue;
                    players.add(player);
                    seenUuids.add(player.uuid == null || player.uuid.isEmpty()
                            ? player.username.toLowerCase(Locale.ROOT) : player.uuid);
                }
            }
        }
        totalPlayers = Math.max(0, total);
        loadedStartOffset = 0;
        nextOffset = players.size();
        loadError = null;
        top350TargetCount = topPlayers == null ? 0 : topPlayers.size();
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
        refreshStatus = "Starting updates for " + top350TargetCount + " players sorted by "
                + currentSortLabel().toLowerCase(Locale.ROOT) + "...";
        layoutControls();
    }

    @Override
    public void onTop350PlayerStarted(long jobId, GoStatsLeaderboardClient.Player player,
                                      int started, int total) {
        if (jobId != top350JobId || !top350Refreshing || player == null) return;
        top350Started = started;
        String uuid = normalizeLeaderboardUuid(player.uuid);
        manuallyRefreshedUuids.remove(uuid);
        batchRefreshCompletedAt.remove(uuid);
        batchRefreshFailedAt.remove(uuid);
        batchRefreshStartedAt.put(uuid, System.currentTimeMillis());
        refreshStatus = "Updating " + started + "/" + total + " · " + player.username + "...";
        layoutControls();
    }

    @Override
    public void onTop350PlayerCompleted(long jobId, GoStatsLeaderboardClient.Player previous,
                                        GoStatsLeaderboardClient.Player updated, String error,
                                        int completed, int total) {
        if (jobId != top350JobId || !top350Refreshing || previous == null) return;
        top350Completed = completed;
        String uuid = normalizeLeaderboardUuid(previous.uuid);
        batchRefreshStartedAt.remove(uuid);
        if (error == null && updated != null) {
            top350Updated++;
            if (!uuid.isEmpty()) rememberRefreshedPresence(updated);
            batchRefreshFailedAt.remove(uuid);
            batchRefreshCompletedAt.put(uuid, System.currentTimeMillis());
            int index = indexOfPlayer(previous.uuid);
            if (index >= 0) {
                rememberDisplayedStatChanges(previous, updated);
                int firstLoadedRank = players.isEmpty() ? 1 : players.get(0).rank;
                players.set(index, updated);
                players.sort((left, right) -> {
                    int valueOrder = Double.compare(right.metricValue, left.metricValue);
                    return valueOrder != 0 ? valueOrder : left.username.compareToIgnoreCase(right.username);
                });
                for (int i = 0; i < players.size(); i++) {
                    players.set(i, GoStatsLeaderboardClient.withRank(players.get(i), firstLoadedRank + i));
                }
                int newIndex = indexOfPlayer(previous.uuid);
                if (newIndex >= 0 && newIndex < scrollOffset) scrollOffset = newIndex;
                else if (newIndex >= scrollOffset + visibleRows)
                    scrollOffset = newIndex - visibleRows + 1;
                clampScroll();
            }
            double difference = updated.metricValue - previous.metricValue;
            String delta = difference > 0.0 ? " (+" + formatMetric(difference) + ")"
                    : difference < 0.0 ? " (" + formatMetric(difference) + ")" : " (+0)";
            lastTop350Update = updated.username + " · " + currentSortLabel() + " "
                    + formatMetric(updated.metricValue) + delta
                    + (updated.refreshCacheLabel.isEmpty() ? "" : " · " + updated.refreshCacheLabel);
            refreshStatus = "Refreshed " + completed + "/" + total + " · " + lastTop350Update;
        } else {
            top350Skipped++;
            batchRefreshFailedAt.put(uuid, System.currentTimeMillis());
            String detail = error == null || error.trim().isEmpty() ? "request failed" : error;
            refreshStatus = "Skipped " + completed + "/" + total + " · "
                    + previous.username + " · " + detail;
        }
        layoutControls();
    }

    @Override
    public void onTop350LoadFailed(long jobId, String error) {
        if (jobId != top350JobId) return;
        top350LoadFailed = true;
        top350Refreshing = false;
        top350Job = null;
        refreshStatus = "Could not start the leaderboard update: " + error;
        layoutControls();
    }

    @Override
    public void onTop350Finished(long jobId, int updated, int skipped, int completed,
                                 int total, boolean cancelled) {
        if (jobId != top350JobId) return;
        top350Refreshing = false;
        top350Job = null;
        top350Updated = updated;
        top350Skipped = skipped;
        top350Completed = completed;
        batchRefreshStartedAt.clear();
        File debugFile = LeaderboardRefreshDebugCapture.getOutputFile(jobId);
        if (debugFile != null) {
            boolean debugFinalized = LeaderboardRefreshDebugCapture.isFinalized(jobId);
            me.napim.gomod.Main.sendMessage(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + (debugFinalized ? "Leaderboard refresh debug saved: "
                    : "Leaderboard refresh finished; debug is still recording database uploads: ")
                    + EnumChatFormatting.WHITE
                    + debugFile.getAbsolutePath());
        }
        if (!top350LoadFailed) {
            if (cancelled) {
                refreshStatus = "Stopped after " + completed + "/" + total + " · "
                        + updated + " profiles refreshed, " + skipped + " skipped";
            } else if (total <= 0) {
                refreshStatus = "No players were available to update.";
            } else {
                refreshStatus = "Finished " + completed + "/" + total + " · "
                        + updated + " profiles refreshed, " + skipped + " skipped"
                        + (lastTop350Update.isEmpty() ? "" : " · latest " + lastTop350Update);
            }
        }
        layoutControls();
        maybeLoadMore();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        layoutControls();
        boolean hoveringSearch = isSearchFieldHovered(mouseX, mouseY);
        if (hoveringSearch && !searchHintShown && !searchHintDismissed) {
            searchHintShown = true;
            searchHintVisible = true;
        } else if (!hoveringSearch) {
            searchHintVisible = false;
        }
        clampScroll();
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Leaderboard",
                width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String subtitle = "gostats123 " + currentScopeLabel().toLowerCase(Locale.ROOT)
                + " leaderboard · sorted by " + currentSortLabel().toLowerCase(Locale.ROOT);
        if ("kit".equals(currentScope()) && !appliedKit.isEmpty()) subtitle += " · " + appliedKit;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GRAY + subtitle,
                width / 2, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        String summary;
        if (totalPlayers > 0) {
            summary = NUMBER.format(totalPlayers) + " players tracked in the gostats123 database";
        } else if (loadError != null) {
            summary = "gostats123 is unavailable — click Refresh to try again";
        } else {
            summary = "Loading players from gostats123...";
        }
        GoStatsLeaderboardSyncQueue.Status sync = GoStatsContributions.leaderboardSyncStatus();
        if (sync.hasActivity()) {
            summary = "gostats123 sync: " + sync.saved + " saved · " + sync.pending + " pending"
                    + (sync.failed > 0 ? " · " + sync.failed + " failed (hover player for details)" : "");
        }
        drawCenteredString(fontRendererObj,
                (loadError == null ? EnumChatFormatting.GRAY : EnumChatFormatting.RED) + summary,
                width / 2, 112, 0xFFFFFFFF);
        if (!pageLoadingDirection.isEmpty()) {
            int dotCount = 1 + (int) ((System.currentTimeMillis() / 500L) % 3L);
            StringBuilder dots = new StringBuilder(dotCount);
            for (int dot = 0; dot < dotCount; dot++) dots.append('.');
            int loadingY = "below".equalsIgnoreCase(pageLoadingDirection) ? listBottom + 1 : 131;
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + "Loading players " + pageLoadingDirection + dots,
                    width / 2, loadingY, MUTED);
        } else if (top350Refreshing) {
            HypixelPlayerRequests.ApiKeyRateLimitStatus rateLimit =
                    HypixelPlayerRequests.getCurrentApiKeyRateLimitStatus();
            if (rateLimit.active && rateLimit.remainingMs > 0L) {
                String reason = rateLimit.retryAfter
                        ? "429 cooldown"
                        : rateLimit.quotaUsed + "/" + rateLimit.quotaLimit + " in 5m";
                String waitLine = EnumChatFormatting.YELLOW + "Waiting for Hypixel API limit ("
                        + reason + ") · resuming in " + formatCooldown(rateLimit.remainingMs)
                        + EnumChatFormatting.GRAY + " (" + top350Completed + "/" + top350TargetCount + ")";
                drawCenteredString(fontRendererObj, waitLine, width / 2, 131, 0xFFFFFFFF);
            } else if (!refreshStatus.isEmpty()) {
                drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + refreshStatus,
                        width / 2, 131, MUTED);
            }
        } else if (!refreshStatus.isEmpty()) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + refreshStatus,
                    width / 2, 131, MUTED);
        }
        String sectionTitle = players.isEmpty() && totalPlayers == 0
                && (loading || loadError != null)
                ? "Leaderboard"
                : "Players in Leaderboard ("
                    + NUMBER.format(totalPlayers > 0 ? totalPlayers : players.size()) + "):";
        drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + sectionTitle,
                width / 2, 154, 0xFFFFFFFF);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY
                        + (editingKitFilterValue ? "Value:" : "IGN/#/!:"),
                searchLabelX, 92, MUTED);
        if (searchField != null) searchField.drawTextBox();

        int end = Math.min(players.size(), scrollOffset + visibleRows);
        int leftX = panelLeft + 6;
        int rightX = panelRight - 14;
        loadErrorTextWidth = Math.max(80, rightX - leftX - 12);
        boolean kitScope = "kit".equals(currentScope());
        boolean modeScope = "solo".equals(currentScope()) || "teams".equals(currentScope());
        boolean showLastActionColumn = false;
        for (int index = scrollOffset; index < end; index++) {
            String uuid = players.get(index).uuid;
            if (uuid != null && manuallyRefreshedUuids.contains(normalizeLeaderboardUuid(uuid))) {
                showLastActionColumn = true;
                break;
            }
        }
        int rankColumnWidth = rankColumnNeeded(kitScope);
        List<String> overallColumns = overallDisplayColumns();
        boolean showOverallColumns = !overallColumns.isEmpty();
        boolean centeredLayout = showOverallColumns || kitScope || modeScope;
        int columnGap = showOverallColumns || modeScope ? 5 : kitScope ? 6 : 12;
        int[] overallColumnWidths = showOverallColumns
                ? overallColumnWidths(overallColumns) : new int[0];
        int overallColumnsWidth = 0;
        for (int i = 0; i < overallColumnWidths.length; i++) {
            overallColumnsWidth += overallColumnWidths[i];
            if (i > 0) overallColumnsWidth += 4;
        }
        int metricColumnWidth = 0;
        if (!showOverallColumns) {
            metricColumnWidth = Math.max(58,
                    fontRendererObj.getStringWidth(currentSortLabel().toUpperCase(Locale.ROOT)) + 8);
            metricColumnWidth = Math.min(kitScope ? 104 : 132, metricColumnWidth);
        }
        int playerWidthLimit = kitScope ? 380 : 224;
        int desiredPlayerColumnWidth = centeredLayout
                ? Math.max(150, Math.min(360,
                kitScope
                        ? kitPlayerColumnNeeded(0, players.size())
                        : overallPlayerColumnNeeded(0, players.size())))
                : playerWidthLimit;
        int trailingColumnsWidth = showOverallColumns ? overallColumnsWidth : metricColumnWidth;
        int lastActionColumnWidth;
        int playerColumnWidth;
        if (centeredLayout) {
            // Keep the same centered table and action slot in Overall and Kit scopes.
            int tableWidth = rankColumnWidth + columnGap + desiredPlayerColumnWidth
                    + columnGap + LAST_ACTION_COLUMN_WIDTH + columnGap + trailingColumnsWidth;
            int availableScreenWidth = Math.max(1, width - 16);
            if (tableWidth > availableScreenWidth) {
                desiredPlayerColumnWidth = Math.max(36,
                        desiredPlayerColumnWidth - (tableWidth - availableScreenWidth));
                tableWidth = rankColumnWidth + columnGap + desiredPlayerColumnWidth
                        + columnGap + LAST_ACTION_COLUMN_WIDTH + columnGap + trailingColumnsWidth;
            }
            leftX = Math.max(4, (width - tableWidth) / 2);
            rightX = leftX + tableWidth;
            lastActionColumnWidth = LAST_ACTION_COLUMN_WIDTH;
            playerColumnWidth = desiredPlayerColumnWidth;
        } else {
            int playerColumnLeft = leftX + rankColumnWidth + columnGap;
            int requiredColumnGaps = columnGap * (showLastActionColumn ? 2 : 1);
            int availablePlayerColumnWidth = rightX - playerColumnLeft
                    - requiredColumnGaps - trailingColumnsWidth;
            lastActionColumnWidth = showLastActionColumn
                    ? Math.min(LAST_ACTION_COLUMN_WIDTH,
                    Math.max(0, availablePlayerColumnWidth - desiredPlayerColumnWidth)) : 0;
            playerColumnWidth = Math.max(36, Math.min(desiredPlayerColumnWidth,
                    availablePlayerColumnWidth - lastActionColumnWidth));
        }
        int rankCenter = leftX + rankColumnWidth / 2;
        int playerColumnLeft = leftX + rankColumnWidth + columnGap;
        int playerCenter = playerColumnLeft + playerColumnWidth / 2;
        int lastActionColumnLeft = playerColumnLeft + playerColumnWidth + columnGap;
        int overallColumnsLeft = playerColumnLeft + playerColumnWidth + columnGap
                + (centeredLayout || lastActionColumnWidth > 0
                ? lastActionColumnWidth + columnGap : 0);
        int metricCenter = showOverallColumns ? -1
                : playerColumnLeft + playerColumnWidth + columnGap
                + (centeredLayout || showLastActionColumn
                ? lastActionColumnWidth + columnGap : 0)
                + metricColumnWidth / 2;
        int listLeft = centeredLayout ? leftX : panelLeft;
        int listRight = centeredLayout ? rightX : panelRight - 8;
        listContentLeft = listLeft;
        listContentRight = listRight;
        int headerY = listTop - 16;
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "POS",
                rankCenter, headerY, MUTED);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "PLAYER",
                playerCenter, headerY, MUTED);
        if (showLastActionColumn && lastActionColumnWidth > 0) {
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "LAST ACTION",
                    lastActionColumnLeft + lastActionColumnWidth / 2, headerY, MUTED);
        }
        if (showOverallColumns) {
            int columnLeft = overallColumnsLeft;
            for (int i = 0; i < overallColumns.size(); i++) {
                int columnWidth = overallColumnWidths[i];
                String key = overallColumns.get(i);
                drawCenteredString(fontRendererObj, overallColumnLabel(key),
                        columnLeft + columnWidth / 2, headerY, overallColumnColor(key));
                columnLeft += columnWidth + 4;
            }
        } else {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.GRAY + currentSortLabel().toUpperCase(Locale.ROOT),
                    metricCenter, headerY, MUTED);
        }
        Gui.drawRect(leftX, listTop - 4, rightX, listTop - 3, 0x60303A44);
        int visibleBottom = listBottom;
        hoveredPlayer = null;
        for (int index = scrollOffset; index < end; index++) {
            GoStatsLeaderboardClient.Player player = players.get(index);
            int rowHeight = leaderboardRowHeight();
            int yPos = listTop + (index - scrollOffset) * rowHeight;
            if (yPos + fontRendererObj.FONT_HEIGHT > visibleBottom) break;
            boolean hovered = openSelectorId == -1
                    && mouseX >= listLeft && mouseX <= listRight
                    && mouseY >= yPos && mouseY < yPos + rowHeight;
            boolean searchMatch = !searchHighlightUuid.isEmpty()
                    && searchHighlightUuid.equalsIgnoreCase(player.uuid);

            int headSize = 10;
            String kitLevel = kitLevelText(player);
            int kitLevelWidth = kitLevel.isEmpty() ? 0 : fontRendererObj.getStringWidth(kitLevel);
            String killPrefix = buildKillCountPrefixText(player);
            int killPrefixWidth = fontRendererObj.getStringWidth(killPrefix);
            int nameGapWidth = fontRendererObj.getStringWidth(" ");
            int headX = playerColumnLeft;
            int levelSlotX = headX + headSize + KIT_LEVEL_HEAD_GAP;
            boolean showKitLevelSlot = "kit".equals(currentScope())
                    && !"rambo".equalsIgnoreCase(appliedKit.trim());
            int levelSlotWidth = showKitLevelSlot ? KIT_LEVEL_MARKER_SLOT_WIDTH : 0;
            int levelX = levelSlotX + Math.max(0, (levelSlotWidth - kitLevelWidth) / 2);
            int nameAreaLeft;
            if (!showKitLevelSlot) {
                nameAreaLeft = headX + headSize + 5;
            } else {
                nameAreaLeft = levelSlotX + levelSlotWidth + KIT_LEVEL_NAME_GAP;
            }
            int nameAreaRight = playerColumnLeft + playerColumnWidth - 4;
            int nameAreaWidth = Math.max(0, nameAreaRight - nameAreaLeft);
            boolean showLastAction = player.uuid != null
                    && manuallyRefreshedUuids.contains(normalizeLeaderboardUuid(player.uuid));
            boolean hiddenPresence = "hidden".equalsIgnoreCase(player.presenceStatus);
            boolean onlinePresence = "online".equalsIgnoreCase(player.presenceStatus);
            EnumChatFormatting lastActionColor = hiddenPresence ? EnumChatFormatting.DARK_RED
                    : onlinePresence ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY;
            String lastActionText = showLastAction
                    ? EnumChatFormatting.DARK_GRAY + " · " + lastActionColor
                    + "Last Action: " + (player.lastActionSeconds > 0L
                    ? relativeTime(player.lastActionSeconds) : "Unknown")
                    : "";
            String playerName = fitText(buildRankedUsernameText(player),
                    Math.max(0, nameAreaWidth - killPrefixWidth - nameGapWidth));
            int textX = nameAreaLeft;
            int playerNameX = textX + killPrefixWidth + nameGapWidth;
            String valueText = formatMetric(player.metricValue);

            if (hovered) {
                Gui.drawRect(listLeft, yPos - 4, listRight,
                        yPos + fontRendererObj.FONT_HEIGHT + 2, 0x40000000);
            }
            if (searchMatch) {
                Gui.drawRect(listLeft + 1, yPos - 2, listRight - 1,
                        yPos + fontRendererObj.FONT_HEIGHT + 1, 0x35FFFFFF);
                Gui.drawRect(listLeft + 1, yPos - 2, listLeft + 2,
                        yPos + fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFFFF);
            }
            drawBatchRefreshOverlay(player, yPos, System.currentTimeMillis(), listLeft, listRight);
            drawDisplayedStatChangeMarker(player, yPos, listLeft, listRight);
            int rankColor = player.rank == 1 ? 0xFFFFD75A
                    : player.rank == 2 ? 0xFFD4D9E0
                    : player.rank == 3 ? 0xFFD79A68 : MUTED;
            if (hovered) {
                hoveredPlayer = player;
            }
            String rankText = EnumChatFormatting.RESET + "#" + player.rank;
            drawCenteredString(fontRendererObj, rankText, rankCenter, yPos, rankColor);
            PlayerHeadTextureUtil.drawMinotarAvatar(player.uuid, headX, yPos - 1, headSize);
            drawPresenceBubble(player, headX, yPos - 1, headSize);
            if (!kitLevel.isEmpty()) {
                fontRendererObj.drawStringWithShadow(kitLevel, levelX, yPos,
                        kitLevelColor(player));
            }
            fontRendererObj.drawStringWithShadow(killPrefix, textX, yPos, killCountTextColor(player));
            fontRendererObj.drawStringWithShadow(playerName, playerNameX, yPos, TEXT);
            if (!lastActionText.isEmpty() && lastActionColumnWidth > 0) {
                int lastActionCenterX = lastActionColumnLeft + lastActionColumnWidth / 2;
                drawCenteredString(fontRendererObj, lastActionText, lastActionCenterX, yPos, MUTED);
            }
            if (showOverallColumns) {
                int columnLeft = overallColumnsLeft;
                for (int i = 0; i < overallColumns.size(); i++) {
                    String key = overallColumns.get(i);
                    int columnWidth = overallColumnWidths[i];
                    drawCenteredString(fontRendererObj, overallColumnValue(player, key),
                            columnLeft + columnWidth / 2, yPos, overallColumnColor(key));
                    columnLeft += columnWidth + 4;
                }
            } else {
                int valueColor = metricValueColor(currentMetric());
                drawCenteredString(fontRendererObj, valueText, metricCenter, yPos, valueColor);
            }
        }

        if (players.isEmpty()) {
            if (loadError == null) {
                drawCenteredString(fontRendererObj, "Loading leaderboard results...",
                        width / 2, listTop + 12, MUTED);
            } else {
                drawLeaderboardLoadError(width / 2);
            }
        }

        drawScrollbar(listRight);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (kitSelectorButton != null && kitSelectorButton.visible) {
            BlitzKitIconRenderer.draw(currentKitName(), kitSelectorButton.xPosition + 5,
                    kitSelectorButton.yPosition + 2);
        }
        if (openSelectorId != -1) {
            drawSelectorMenu(mouseX, mouseY);
        } else if (suggestionsVisible && searchField != null && searchField.isFocused()) {
            drawPlayerSuggestions(mouseX, mouseY);
        }
        if (openSelectorId == -1 && hoveredPlayer != null) {
            updateHoveredSessionTarget(hoveredPlayer.uuid);
        } else {
            hoveredSessionUuid = "";
            hoveredSessionSinceMs = 0L;
        }
        if (hoveringSearch && searchHintVisible && !searchHintDismissed) {
            List<String> hint = new ArrayList<String>();
            hint.add(EnumChatFormatting.YELLOW + "#123" + EnumChatFormatting.GRAY
                    + ": jump to leaderboard rank 123.");
            hint.add(EnumChatFormatting.YELLOW + "!5000" + EnumChatFormatting.GRAY
                    + ": closest value for the current sort.");
            hint.add(EnumChatFormatting.GRAY + "Type 2+ letters for tracked player suggestions.");
            drawHoveringText(hint, mouseX, mouseY);
        } else if (openSelectorId == -1 && isMouseOver(updateTop350Button, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(EnumChatFormatting.GRAY
                    + "Choose how many players to refresh"), mouseX, mouseY);
        } else if (openSelectorId == -1 && isMouseOver(updateActionButton, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(EnumChatFormatting.GRAY
                    + (top350Refreshing ? "Stop the current update" : "Refresh the selected number of players")),
                    mouseX, mouseY);
        } else if (openSelectorId == -1 && hoveredPlayer != null) {
            List<String> tooltip = new ArrayList<String>();
            addLastUpdatedTooltip(tooltip, hoveredPlayer);
            addDisplayedStatChangesTooltip(tooltip, hoveredPlayer);
            addPresenceTooltip(tooltip, hoveredPlayer);
            tooltip.add(refreshingUuids.contains(hoveredPlayer.uuid)
                    ? EnumChatFormatting.GREEN + "Refreshing stats..."
                    : EnumChatFormatting.YELLOW + "Right-click to update");
            drawPlayerTooltip(hoveredPlayer, tooltip, mouseX, mouseY);
        }
    }

    private void drawScrollbar(int listRight) {
        clampScroll();
        int maxPossibleOffset = Math.max(0, players.size() - visibleRows);
        if (maxPossibleOffset == 0) {
            scrollbarLeft = scrollbarRight = scrollbarTrackTop = scrollbarTrackBottom = 0;
            scrollbarThumbTop = scrollbarThumbBottom = 0;
            return;
        }
        scrollbarLeft = Math.min(width - 2, listRight + 5);
        scrollbarRight = scrollbarLeft + 1;
        scrollbarTrackTop = listTop;
        int rowHeight = leaderboardRowHeight();
        scrollbarTrackBottom = Math.min(listBottom, listTop + visibleRows * rowHeight);
        int trackHeight = Math.max(1, scrollbarTrackBottom - scrollbarTrackTop);
        int totalContentHeight = Math.max(1, players.size() * rowHeight);
        int thumbHeight = Math.max(20, (int) (trackHeight * Math.min(1.0F,
                trackHeight / (float) totalContentHeight)));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int maxThumbTravel = trackHeight - thumbHeight;
        scrollbarThumbTop = scrollbarTrackTop + (maxThumbTravel <= 0 ? 0
                : (int) (maxThumbTravel * (scrollOffset / (float) maxPossibleOffset)));
        scrollbarThumbBottom = scrollbarThumbTop + thumbHeight;
        Gui.drawRect(scrollbarLeft, scrollbarTrackTop, scrollbarRight, scrollbarTrackBottom, 0x60000000);
        Gui.drawRect(scrollbarLeft, scrollbarThumbTop, scrollbarRight, scrollbarThumbBottom, 0x90FFFFFF);
    }

    private void drawLeaderboardLoadError(int centerX) {
        String message = "Could not load the leaderboard: " + loadError;
        List<String> lines = wrapText(message, Math.max(80, loadErrorTextWidth));
        if (lines.isEmpty()) lines.add("Could not load the leaderboard.");

        int lineHeight = fontRendererObj.FONT_HEIGHT + 2;
        int startY = listTop + 12;
        int retryY = listBottom - fontRendererObj.FONT_HEIGHT - 2;
        int availableLines = Math.max(1, (retryY - startY - 2) / lineHeight);
        boolean scrollable = lines.size() > availableLines;
        int visibleLines = scrollable ? Math.max(1, availableLines - 1)
                : Math.min(lines.size(), availableLines);
        int maxScroll = Math.max(0, lines.size() - visibleLines);
        loadErrorScrollOffset = Math.max(0, Math.min(maxScroll, loadErrorScrollOffset));

        int end = Math.min(lines.size(), loadErrorScrollOffset + visibleLines);
        for (int i = loadErrorScrollOffset; i < end; i++) {
            drawCenteredString(fontRendererObj, lines.get(i), centerX,
                    startY + (i - loadErrorScrollOffset) * lineHeight, 0xFFFF7887);
        }

        if (scrollable) {
            int indicatorY = retryY - lineHeight;
            String indicator = "Error lines " + (loadErrorScrollOffset + 1) + "-" + end
                    + " of " + lines.size() + " · scroll to read full error";
            drawCenteredString(fontRendererObj, fitText(indicator, Math.max(80, loadErrorTextWidth)),
                    centerX, indicatorY, MUTED);
        } else {
            retryY = Math.min(retryY, startY + lines.size() * lineHeight + 2);
        }
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Use Refresh to retry.",
                centerX, retryY, MUTED);
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.trim().isEmpty() || maxWidth <= 0) return lines;
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        StringBuilder line = new StringBuilder();
        for (String word : normalized.split("\\s+")) {
            String candidate = line.length() == 0 ? word : line.toString() + " " + word;
            if (fontRendererObj.getStringWidth(candidate) <= maxWidth) {
                if (line.length() > 0) line.append(' ');
                line.append(word);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (fontRendererObj.getStringWidth(word) <= maxWidth) {
                line.append(word);
                continue;
            }
            int start = 0;
            while (start < word.length()) {
                int end = word.length();
                while (end > start + 1
                        && fontRendererObj.getStringWidth(word.substring(start, end)) > maxWidth) {
                    end--;
                }
                lines.add(word.substring(start, end));
                start = end;
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private int loadErrorScrollVisibleLines(int lineCount) {
        int lineHeight = fontRendererObj.FONT_HEIGHT + 2;
        int startY = listTop + 12;
        int retryY = listBottom - fontRendererObj.FONT_HEIGHT - 2;
        int availableLines = Math.max(1, (retryY - startY - 2) / lineHeight);
        return lineCount > availableLines ? Math.max(1, availableLines - 1)
                : Math.min(Math.max(1, lineCount), availableLines);
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (fontRendererObj.getStringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && fontRendererObj.getStringWidth(text.substring(0, end) + ellipsis) > maxWidth) end--;
        return end <= 0 ? "" : text.substring(0, end) + ellipsis;
    }

    private String currentMetric() {
        String[] metrics = currentMetrics();
        return metrics[Math.max(0, Math.min(metrics.length - 1, sortIndex))];
    }

    private String currentSortLabel() {
        String[] labels = currentMetricLabels();
        return labels[Math.max(0, Math.min(labels.length - 1, sortIndex))];
    }

    private String sortButtonText() {
        return EnumChatFormatting.YELLOW + currentSortLabel();
    }

    private String currentScope() {
        return SCOPES[Math.max(0, Math.min(SCOPES.length - 1, scopeIndex))];
    }

    private GoStatsLeaderboardClient.KitFilters currentKitFilters() {
        String ranks = serializedRankFilters();
        String level = "kit".equals(currentScope()) ? kitFilters.level : "";
        return new GoStatsLeaderboardClient.KitFilters(level, kitFilters.finisher,
                kitFilters.victory, kitFilters.aura, kitFilters.taunt,
                kitFilters.killCounter, kitFilters.killEffect, ranks,
                "kit".equals(currentScope()) || kitSelectionExplicit ? currentKitName() : "");
    }

    private String serializedRankFilters() {
        StringBuilder serialized = new StringBuilder();
        for (String[] option : RANK_FILTER_OPTIONS) {
            if (!selectedRankFilters.contains(option[0])) continue;
            if (serialized.length() > 0) serialized.append(',');
            serialized.append(option[0]);
        }
        return serialized.toString();
    }

    private String currentScopeLabel() {
        return SCOPE_LABELS[Math.max(0, Math.min(SCOPE_LABELS.length - 1, scopeIndex))];
    }

    private String scopeButtonText() {
        return EnumChatFormatting.YELLOW + currentScopeLabel();
    }

    private String currentKitName() {
        return kitNames.isEmpty() ? (appliedKit.isEmpty() ? "Scout" : appliedKit)
                : kitNames.get(Math.max(0, Math.min(kitNames.size() - 1, kitIndex)));
    }

    private String kitSelectorButtonText() {
        return EnumChatFormatting.YELLOW + currentKitName();
    }

    private String updateCountButtonText() {
        return EnumChatFormatting.YELLOW + "Top " + currentRefreshCount();
    }

    private int currentPageSize() {
        return PAGE_SIZES[Math.max(0, Math.min(PAGE_SIZES.length - 1, pageSizeIndex))];
    }

    private String pageSizeButtonText() {
        return EnumChatFormatting.GRAY + "Rows: " + EnumChatFormatting.YELLOW + currentPageSize();
    }

    private GuiButton selectorButton(int id) {
        switch (id) {
            case ID_SCOPE: return scopeButton;
            case ID_SORT: return sortButton;
            case ID_PAGE_SIZE: return pageSizeButton;
            case ID_UPDATE_TOP_350: return updateTop350Button;
            case ID_KIT: return kitSelectorButton;
            case ID_FILTERS:
            case ID_FILTER_VALUE: return filtersButton;
            default: return null;
        }
    }

    private List<String> selectorOptions(int id) {
        List<String> options = new ArrayList<String>();
        if (id == ID_SCOPE) {
            for (String label : SCOPE_LABELS) options.add(label);
        } else if (id == ID_SORT) {
            for (String label : currentMetricLabels()) options.add(label);
        } else if (id == ID_PAGE_SIZE) {
            for (int size : PAGE_SIZES) options.add(NUMBER.format(size) + " players");
        } else if (id == ID_UPDATE_TOP_350) {
            for (int size : GoStatsLeaderboardRefreshCountStore.getOptions())
                options.add(NUMBER.format(size) + " players");
        } else if (id == ID_KIT) {
            options.addAll(kitNames);
        } else if (id == ID_FILTERS) {
            options.addAll(kitFilterCategories());
        } else if (id == ID_FILTER_VALUE) {
            options.addAll(filterValues(activeFilterCategory));
        }
        return options;
    }

    private int selectorSelectedIndex(int id) {
        if (id == ID_SCOPE) return scopeIndex;
        if (id == ID_SORT) return sortIndex;
        if (id == ID_PAGE_SIZE) return pageSizeIndex;
        if (id == ID_UPDATE_TOP_350) return refreshCountIndex;
        if (id == ID_KIT) return kitIndex;
        if (id == ID_FILTER_VALUE) {
            if ("ranks".equals(activeFilterCategory))
                return pendingRankFilters.isEmpty() ? 0 : -1;
            if ("level".equals(activeFilterCategory))
                return currentFilterValue("level").isEmpty() ? 0 : -1;
            List<String> values = selectorOptions(id);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                if (isUnfilteredFilterOption(activeFilterCategory, value)
                        && currentFilterValue(activeFilterCategory).isEmpty()) return index;
                if (!isUnfilteredFilterOption(activeFilterCategory, value)
                        && filterParamMatches(activeFilterCategory,
                        filterParamValue(activeFilterCategory, value), currentFilterValue(activeFilterCategory)))
                    return index;
            }
        }
        return -1;
    }

    private String selectorTitle(int id) {
        if (id == ID_SCOPE) return "Choose leaderboard";
        if (id == ID_SORT) return "Sort by";
        if (id == ID_PAGE_SIZE) return "Players per page";
        if (id == ID_UPDATE_TOP_350) return "Players to update";
        if (id == ID_FILTERS) return "Choose filters";
        if (id == ID_FILTER_VALUE) return "Filter by " + filterCategoryTitle(activeFilterCategory);
        return "Choose kit";
    }

    private boolean filterParamMatches(String category, String optionValue, String selectedValue) {
        if (optionValue == null || selectedValue == null) return false;
        if ("level".equals(category)) {
            return selectedKitLevelTokens(selectedValue).contains(optionValue.toLowerCase(Locale.ROOT));
        }
        if ("finisher".equals(category) || "victory".equals(category) || "aura".equals(category)
                || "taunt".equals(category) || "kill_effect".equals(category)) {
            return optionValue.replace('_', ' ').trim().equalsIgnoreCase(selectedValue.replace('_', ' ').trim());
        }
        return optionValue.equalsIgnoreCase(selectedValue);
    }

    private boolean selectorOptionSelected(int selector, int index, String option, int selectedIndex) {
        if (selector != ID_FILTER_VALUE) return index == selectedIndex;
        if ("ranks".equals(activeFilterCategory)) {
            if ("Any".equals(option)) return pendingRankFilters.isEmpty();
            return pendingRankFilters.contains(option);
        }
        if ("level".equals(activeFilterCategory)) {
            if ("Any".equals(option)) return currentFilterValue("level").isEmpty();
            return filterParamMatches("level", filterParamValue("level", option),
                    currentFilterValue("level"));
        }
        return index == selectedIndex;
    }

    private void closeSelector() {
        activeFilterCategory = "";
        openSelectorId = -1;
        selectorScrollOffset = 0;
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    private String fitSelectorButtonText(String text, int maxWidth) {
        if (text == null || maxWidth <= 0 || fontRendererObj.getStringWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int textLimit = Math.max(0, maxWidth - fontRendererObj.getStringWidth(ellipsis));
        StringBuilder fitted = new StringBuilder();
        int usedWidth = 0;
        for (int index = 0; index < text.length();) {
            char current = text.charAt(index);
            if (current == '\u00A7' && index + 1 < text.length()) {
                fitted.append(current).append(text.charAt(index + 1));
                index += 2;
                continue;
            }
            int characterLength = Character.isHighSurrogate(current) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1)) ? 2 : 1;
            String character = text.substring(index, index + characterLength);
            int characterWidth = fontRendererObj.getStringWidth(character);
            if (usedWidth + characterWidth > textLimit) break;
            fitted.append(character);
            usedWidth += characterWidth;
            index += characterLength;
        }
        return fitted.length() == 0 ? ellipsis : fitted.append(ellipsis).toString();
    }

    /** Draws selector labels inside a reserved text area, leaving the chevron its own clear slot. */
    private final class SelectorButton extends GuiButton {
        private final int leftTextInset;
        private final int rightTextInset;

        private SelectorButton(int id, int x, int y, int width, int height, String label,
                               int leftTextInset, int rightTextInset) {
            super(id, x, y, width, height, label);
            this.leftTextInset = leftTextInset;
            this.rightTextInset = rightTextInset;
        }

        @Override
        public void drawButton(Minecraft client, int mouseX, int mouseY) {
            if (!visible) return;
            String label = displayString;
            displayString = "";
            try {
                super.drawButton(client, mouseX, mouseY);
            } finally {
                displayString = label;
            }

            int textLeft = xPosition + leftTextInset;
            int textAreaWidth = Math.max(0, width - leftTextInset - rightTextInset);
            String fitted = fitSelectorButtonText(label, textAreaWidth);
            int textX = textLeft + Math.max(0,
                    (textAreaWidth - client.fontRendererObj.getStringWidth(fitted)) / 2);
            int textY = yPosition + (height - 8) / 2;
            int textColor = !enabled ? 0xFFA0A0A0 : hovered ? 0xFFFFF0A0 : 0xFFE0E0E0;
            client.fontRendererObj.drawStringWithShadow(fitted, textX, textY, textColor);

            // Draw the dropdown chevron relative to this button, well inside its right edge.
            int centerX = xPosition + width - 10;
            int chevronTop = yPosition + (height - 3) / 2;
            for (int row = 0; row < 3; row++) {
                int halfWidth = 2 - row;
                Gui.drawRect(centerX - halfWidth, chevronTop + row,
                        centerX + halfWidth + 1, chevronTop + row + 1, 0xFFD5DCE3);
            }
        }
    }

    private void drawSelectorMenu(int mouseX, int mouseY) {
        GuiButton anchor = selectorButton(openSelectorId);
        List<String> options = selectorOptions(openSelectorId);
        if (anchor == null || options.isEmpty()) {
            openSelectorId = -1;
            return;
        }
        int columns = selectorColumnCount(openSelectorId);
        int optionRows = (options.size() + columns - 1) / columns;
        boolean compactCountMenu = openSelectorId == ID_UPDATE_TOP_350;
        boolean filterCategoryMenu = openSelectorId == ID_FILTERS;
        boolean rankFilterMenu = openSelectorId == ID_FILTER_VALUE
                && "ranks".equals(activeFilterCategory);
        boolean levelFilterMenu = openSelectorId == ID_FILTER_VALUE
                && "level".equals(activeFilterCategory);
        boolean multiValueFilterMenu = rankFilterMenu || levelFilterMenu;
        selectorHeaderHeight = compactCountMenu ? 16 : filterCategoryMenu || multiValueFilterMenu ? 22 : 18;
        int menuWidth = selectorMenuWidth(options, columns);
        int filterCellWidth = filterCategoryMenu
                ? Math.max(1, (menuWidth - 4) / columns) : menuWidth;
        selectorRowHeight = filterCategoryMenu ? filterCategoryRowHeight(filterCellWidth) : 15;
        int belowRows = Math.max(1, (height - 10 - anchor.yPosition - anchor.height
                - selectorHeaderHeight - 4) / selectorRowHeight);
        int aboveRows = Math.max(1, (anchor.yPosition - selectorHeaderHeight - 12) / selectorRowHeight);
        int fitRows = Math.max(belowRows, aboveRows);
        int visibleRowLimit = compactCountMenu ? 4 : filterCategoryMenu ? optionRows : 7;
        selectorVisibleRows = Math.max(1, Math.min(Math.min(visibleRowLimit, optionRows), fitRows));
        selectorLeft = Math.max(panelLeft + 2, Math.min(anchor.xPosition,
                panelRight - menuWidth - 2));
        selectorTop = anchor.yPosition + anchor.height + 2;
        if (selectorTop + selectorHeaderHeight + selectorVisibleRows * selectorRowHeight > height - 8) {
            selectorTop = Math.max(8, anchor.yPosition - selectorHeaderHeight
                    - selectorVisibleRows * selectorRowHeight - 2);
        }
        selectorRight = selectorLeft + menuWidth;
        selectorBottom = selectorTop + selectorHeaderHeight + selectorVisibleRows * selectorRowHeight + 2;
        selectorScrollOffset = Math.max(0, Math.min(selectorScrollOffset,
                optionRows - selectorVisibleRows));

        Gui.drawRect(selectorLeft - 1, selectorTop - 1, selectorRight + 1, selectorBottom + 1, 0xFF111820);
        Gui.drawRect(selectorLeft, selectorTop, selectorRight, selectorBottom, 0xF2222B34);
        Gui.drawRect(selectorLeft, selectorTop, selectorRight, selectorTop + 1, 0xFF536273);
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + selectorTitle(openSelectorId),
                selectorLeft + 7, selectorTop + (compactCountMenu ? 4
                        : filterCategoryMenu || multiValueFilterMenu ? 7 : 5), MUTED);
        if (filterCategoryMenu) {
            drawFilterMenuActionButton(mouseX, mouseY, "Reset");
            drawFilterMenuActionButton(mouseX, mouseY, "Done");
        }
        else if (multiValueFilterMenu) drawFilterMenuActionButton(mouseX, mouseY, "Back");

        int selected = selectorSelectedIndex(openSelectorId);
        for (int row = 0; row < selectorVisibleRows; row++) {
            int rowTop = selectorTop + selectorHeaderHeight + row * selectorRowHeight;
            int rowBottom = rowTop + selectorRowHeight;
            int cellWidth = (menuWidth - 4) / columns;
            for (int column = 0; column < columns; column++) {
                int optionIndex = (selectorScrollOffset + row) * columns + column;
                if (optionIndex >= options.size()) continue;
                int cellLeft = selectorLeft + 2 + column * cellWidth;
                int cellRight = column == columns - 1 ? selectorRight - 2 : cellLeft + cellWidth;
                boolean hovered = mouseX >= cellLeft && mouseX < cellRight
                        && mouseY >= rowTop && mouseY < rowBottom;
                String category = filterCategoryMenu ? filterCategoryForIndex(optionIndex) : "";
                boolean categoryEnabled = !filterCategoryMenu || filterCategoryEnabled(category);
                boolean appliedFilter = filterCategoryMenu && hasAppliedFilter(category);
                boolean pendingFilter = filterCategoryMenu && hasUnappliedFilterChange(category);
                boolean optionSelected = selectorOptionSelected(openSelectorId, optionIndex,
                        options.get(optionIndex), selected);
                if (!categoryEnabled) {
                    // Keep Kit level visible in other scopes, but make its disabled state obvious.
                } else if (pendingFilter) {
                    Gui.drawRect(cellLeft, rowTop, cellRight, rowBottom,
                            hovered ? 0xFF66502B : 0xFF493A20);
                    Gui.drawRect(cellLeft, rowTop, cellLeft + 2, rowBottom,
                            hovered ? 0xFFFFC04A : 0xFFD79B35);
                } else if (appliedFilter) {
                    Gui.drawRect(cellLeft, rowTop, cellRight, rowBottom,
                            hovered ? 0xFF315641 : 0xFF263D32);
                    Gui.drawRect(cellLeft, rowTop, cellLeft + 2, rowBottom,
                            hovered ? 0xFF82E7A0 : 0xFF58BA78);
                } else if (optionSelected) {
                    Gui.drawRect(cellLeft, rowTop, cellRight, rowBottom,
                            hovered ? 0xFF315641 : 0xFF263D32);
                } else if (hovered) {
                    Gui.drawRect(cellLeft, rowTop, cellRight, rowBottom, 0xFF303B46);
                }
                int textX = cellLeft + 6;
                String rawOption = options.get(optionIndex);
                if (openSelectorId == ID_KIT) {
                    BlitzKitIconRenderer.drawCentered(options.get(optionIndex),
                            cellLeft + 2, rowTop, 16, selectorRowHeight, 0.75F);
                    textX += 22;
                } else if (openSelectorId == ID_FILTERS) {
                    drawSelectorItemIcon(filterCategoryIcon(category), cellLeft + 4, rowTop,
                            selectorRowHeight, 0.75F);
                    textX += 22;
                } else if (openSelectorId == ID_FILTER_VALUE
                        && !"ranks".equals(activeFilterCategory)) {
                    drawSelectorItemIcon(filterValueIcon(activeFilterCategory, rawOption),
                            cellLeft + 4, rowTop, selectorRowHeight, 0.75F);
                    textX += 22;
                }
                int optionWidth = cellRight - textX - 4;
                String optionLabel = openSelectorId == ID_FILTER_VALUE
                        ? filterOptionDisplay(activeFilterCategory, rawOption) : rawOption;
                String optionText;
                if (multiValueFilterMenu && !"Any".equals(rawOption)) {
                    String checkColor = optionSelected ? EnumChatFormatting.GREEN.toString()
                            : EnumChatFormatting.DARK_GRAY.toString();
                    String checkboxLabel = rankFilterMenu ? rankFilterColoredLabel(rawOption)
                            : EnumChatFormatting.RESET + filterOptionDisplay(activeFilterCategory, rawOption);
                    optionText = fitText(checkColor + (optionSelected ? "[x] " : "[ ] ")
                            + checkboxLabel + EnumChatFormatting.RESET, optionWidth);
                } else {
                    optionText = fitText(optionLabel, optionWidth);
                }
                if (filterCategoryMenu && !categoryEnabled) {
                    fontRendererObj.drawStringWithShadow(fitText(rawOption, optionWidth),
                            textX, rowTop + 3, 0xFF737C85);
                } else if (filterCategoryMenu && ("level".equals(category) || "ranks".equals(category))) {
                    drawFilterCategorySummary(category, textX, rowTop + 1, optionWidth);
                } else if (filterCategoryMenu && grayAnyFilterCategory(category)
                        && grayDefaultFilterValue(category, filterValueLabel(category,
                        currentFilterValue(category)))) {
                    int textY = rowTop + (selectorRowHeight - fontRendererObj.FONT_HEIGHT) / 2;
                    drawFilterCategoryDefaultValue(category,
                            filterValueLabel(category, currentFilterValue(category)),
                            textX, textY, optionWidth);
                } else if (multiValueFilterMenu && "Any".equals(rawOption)) {
                    String anyColor = optionSelected ? EnumChatFormatting.GREEN.toString()
                            : EnumChatFormatting.WHITE.toString();
                    fontRendererObj.drawStringWithShadow(anyColor + optionText,
                            textX, rowTop + 3, TEXT);
                } else if (openSelectorId == ID_FILTER_VALUE && "kill_counter".equals(activeFilterCategory)
                        && "Both".equalsIgnoreCase(rawOption) && !optionSelected) {
                    fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + optionText,
                            textX, rowTop + 3, MUTED);
                } else if (openSelectorId == ID_FILTER_VALUE && "level".equals(activeFilterCategory)
                        && !"Any".equalsIgnoreCase(rawOption)) {
                    fontRendererObj.drawStringWithShadow(optionText, textX, rowTop + 3,
                            kitFilterLevelColor(rawOption));
                } else if (rankFilterMenu) {
                    fontRendererObj.drawStringWithShadow(optionText, textX, rowTop + 3, TEXT);
                } else {
                    String color = optionSelected ? EnumChatFormatting.GREEN.toString()
                            : EnumChatFormatting.WHITE.toString();
                    fontRendererObj.drawStringWithShadow(color + optionText,
                            textX, rowTop + 3, TEXT);
                }
            }
        }
        if (optionRows > selectorVisibleRows) {
            int trackX = selectorRight - 3;
            int trackTop = selectorTop + selectorHeaderHeight;
            int trackBottom = trackTop + selectorVisibleRows * selectorRowHeight;
            Gui.drawRect(trackX, trackTop + 1, trackX + 1, trackBottom - 1, 0x80465462);
            int thumbHeight = Math.max(10, (trackBottom - trackTop) * selectorVisibleRows / optionRows);
            int travel = Math.max(0, trackBottom - trackTop - thumbHeight);
            int thumbTop = trackTop + (optionRows <= selectorVisibleRows ? 0
                    : travel * selectorScrollOffset / (optionRows - selectorVisibleRows));
            Gui.drawRect(trackX, thumbTop, trackX + 1, thumbTop + thumbHeight, 0xFFD5DCE3);
        }
    }

    private void drawFilterMenuActionButton(int mouseX, int mouseY, String label) {
        int buttonWidth = "Done".equals(label) ? 38 : "Reset".equals(label) ? 42 : 44;
        int buttonRight = selectorRight - 5;
        if ("Reset".equals(label)) buttonRight -= 38 + 4;
        int buttonLeft = buttonRight - buttonWidth;
        int buttonTop = selectorTop + 3;
        int buttonBottom = selectorTop + selectorHeaderHeight - 3;
        boolean hovered = mouseX >= buttonLeft && mouseX < buttonRight
                && mouseY >= buttonTop && mouseY < buttonBottom;
        boolean reset = "Reset".equals(label);
        Gui.drawRect(buttonLeft - 1, buttonTop - 1, buttonRight + 1, buttonBottom + 1,
                reset ? (hovered ? 0xFFFF7777 : 0xFF8D4242)
                        : (hovered ? 0xFFFFD75A : 0xFF62745D));
        Gui.drawRect(buttonLeft, buttonTop, buttonRight, buttonBottom,
                reset ? (hovered ? 0xFF983838 : 0xFF642828)
                        : (hovered ? 0xFF49683F : 0xFF354C38));
        String styledLabel = (reset ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW) + label;
        int textX = buttonLeft + (buttonRight - buttonLeft - fontRendererObj.getStringWidth(styledLabel)) / 2;
        int textY = buttonTop + (buttonBottom - buttonTop - 8) / 2;
        fontRendererObj.drawStringWithShadow(styledLabel, textX, textY, TEXT);
    }

    private String filterMenuActionButtonAt(int mouseX, int mouseY) {
        boolean filterCategoryMenu = openSelectorId == ID_FILTERS;
        boolean rankFilterMenu = openSelectorId == ID_FILTER_VALUE
                && "ranks".equals(activeFilterCategory);
        boolean levelFilterMenu = openSelectorId == ID_FILTER_VALUE
                && "level".equals(activeFilterCategory);
        if (!filterCategoryMenu && !rankFilterMenu && !levelFilterMenu) return null;
        int buttonTop = selectorTop + 3;
        int buttonBottom = selectorTop + selectorHeaderHeight - 3;
        if (mouseY < buttonTop || mouseY >= buttonBottom) return null;
        int buttonRight = selectorRight - 5;
        if (filterCategoryMenu) {
            int doneLeft = buttonRight - 38;
            if (mouseX >= doneLeft && mouseX < buttonRight) return "Done";
            int resetRight = doneLeft - 4;
            int resetLeft = resetRight - 42;
            return mouseX >= resetLeft && mouseX < resetRight ? "Reset" : null;
        }
        int buttonLeft = buttonRight - 44;
        return mouseX >= buttonLeft && mouseX < buttonRight ? "Back" : null;
    }

    private void returnToFilterCategories() {
        activeFilterCategory = "";
        openSelectorId = ID_FILTERS;
        selectorScrollOffset = 0;
    }

    public static ItemStack profileCosmeticIcon(String category, String value) {
        if ("afterkill".equals(category)) {
            String key = value == null ? "" : value.trim().replace(' ', '_').replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            switch (key) {
                case "SPEED": return new ItemStack(Items.feather);
                case "RESISTANCE": return new ItemStack(Items.iron_chestplate);
                case "REGENERATION": return new ItemStack(Items.golden_apple);
                case "FLAMING_ARROWS": return new ItemStack(Items.arrow);
                case "FLAME_ARROW":
                case "FLAME":
                case "RAPID_FIRE": return new ItemStack(Items.arrow);
                case "LEVEL_UP": return new ItemStack(Items.experience_bottle);
                case "GRAVEDIGGER": return new ItemStack(Items.rotten_flesh);
                case "RANDOM": return blockItem(Blocks.beacon, 0);
                default: return new ItemStack(Items.blaze_powder);
            }
        }
        if ("taunt".equals(category) && (value == null || value.trim().isEmpty()))
            return new ItemStack(Items.magma_cream);
        if (("finisher".equals(category) || "aura".equals(category) || "victory".equals(category))
                && (value == null || value.trim().isEmpty() || "None".equalsIgnoreCase(value.trim())
                || "Unknown".equalsIgnoreCase(value.trim()))) {
            return filterCategoryIcon(category);
        }
        return filterValueIcon(category, value);
    }

    private static ItemStack filterCategoryIcon(String category) {
        if ("level".equals(category)) return new ItemStack(Items.stone_sword);
        if ("finisher".equals(category)) return new ItemStack(Items.skull, 1, 0);
        if ("victory".equals(category)) return new ItemStack(Items.cake);
        if ("aura".equals(category)) return new ItemStack(Items.blaze_powder);
        if ("taunt".equals(category)) return new ItemStack(Items.magma_cream);
        if ("kill_effect".equals(category)) return profileCosmeticIcon("afterkill", "SPEED");
        if ("kill_counter".equals(category)) return new ItemStack(Items.iron_helmet);
        if ("ranks".equals(category)) return new ItemStack(Items.name_tag);
        return null;
    }

    public static ItemStack filterValueIcon(String category, String option) {
        if (option == null || option.trim().isEmpty() || "Any".equalsIgnoreCase(option)) return null;
        if ("ranks".equals(category)) return null;
        if ("kill_counter".equals(category)) {
            if ("Enabled".equalsIgnoreCase(option)) return new ItemStack(Items.dye, 1, 10);
            if ("Disabled".equalsIgnoreCase(option)) return new ItemStack(Items.dye, 1, 8);
        }
        if ("kill_effect".equals(category)) return profileCosmeticIcon("afterkill", option);
        if ("None".equalsIgnoreCase(option)) return blockItem(Blocks.barrier, 0);
        if ("taunt".equals(category) && "Default".equalsIgnoreCase(option))
            return new ItemStack(Items.magma_cream);
        String key = option.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);

        if ("finisher".equals(category)) {
            switch (key) {
                case "CREEPER_FIREWORK": return new ItemStack(Items.fireworks);
                case "EXPLOSIONS": return blockItem(Blocks.tnt, 0);
                case "GRAVESTONE": return blockItem(Blocks.stone_slab, 3);
                case "INFESTATION": return new ItemStack(Items.bone);
                case "LIGHTNING_STRIKE": return new ItemStack(Items.blaze_rod);
                case "RABBIT_GIFT": return new ItemStack(Items.spawn_egg, 1, 101);
                case "REKT_HOLOGRAM": return new ItemStack(Items.sugar);
                case "SQUID_MISSILE": return new ItemStack(Items.dye, 1, 0);
            }
        } else if ("victory".equals(category)) {
            switch (key) {
                case "BLAZING_SPEED": return new ItemStack(Items.blaze_powder);
                case "COLORFUL_CHICKEN_RAIN": return new ItemStack(Items.dye, 1, 5);
                case "CREEPER": return new ItemStack(Items.skull, 1, 4);
                case "DRAGON_RIDER": return blockItem(Blocks.dragon_egg, 0);
                case "EARTH": return blockItem(Blocks.grass, 0);
                case "LEAPING_BUNNY": return new ItemStack(Items.rabbit_foot);
                case "METEOR_SHOWER": return new ItemStack(Items.fire_charge);
                case "PLAYER_FIREWORK": return new ItemStack(Items.fireworks);
            }
        } else if ("aura".equals(category)) {
            switch (key) {
                case "BLUE_PARTICLE": return blockItem(Blocks.wool, 11);
                case "DAYLIGHT_PARTICLE": return blockItem(Blocks.daylight_detector, 0);
                case "DRAGON_EGG_PARTICLE": return blockItem(Blocks.dragon_egg, 0);
                case "DUST_PARTICLE": return new ItemStack(Items.redstone);
                case "ENCHANTING_PARTICLE": return blockItem(Blocks.enchanting_table, 0);
                case "FLOWER_TRAIL": return blockItem(Blocks.red_flower, 0);
                case "GREEN_PARTICLE": return blockItem(Blocks.wool, 13);
                case "LAVA_PARTICLE": return new ItemStack(Items.lava_bucket);
                case "MYCELIUM_TRAIL": return blockItem(Blocks.mycelium, 0);
                case "NETHERRACK_TRAIL": return blockItem(Blocks.netherrack, 0);
                case "PORTAL_PARTICLE": return blockItem(Blocks.obsidian, 0);
                case "RAINBOW_PARTICLE": return blockItem(Blocks.wool, 0);
                case "RED_PARTICLE": return blockItem(Blocks.wool, 14);
                case "SLIME_PARTICLE": return new ItemStack(Items.slime_ball);
                case "SNOW_PARTICLE": return new ItemStack(Items.snowball);
                case "TRON_BLUE_TRAIL": return blockItem(Blocks.stained_glass, 11);
                case "TRON_GREEN_TRAIL": return blockItem(Blocks.stained_glass, 13);
                case "TRON_RAINBOW_TRAIL": return blockItem(Blocks.stained_glass, 0);
                case "TRON_RED_TRAIL": return blockItem(Blocks.stained_glass, 14);
                case "WATER_PARTICLE": return new ItemStack(Items.water_bucket);
                case "WHEAT_TRAIL": return new ItemStack(Items.wheat);
            }
        } else if ("taunt".equals(category)) {
            switch (key) {
                case "BAT_DUDE": return new ItemStack(Items.spawn_egg, 1, 44);
                case "CHICKEN_DANCE": return new ItemStack(Items.chicken);
                case "COOKIE": return new ItemStack(Items.cookie);
                case "FIREWORK": return new ItemStack(Items.fireworks);
                case "I_REFUSE": return new ItemStack(Items.redstone);
                case "KILLER_BUNNY": return new ItemStack(Items.rabbit_hide);
                case "PIG_DANCE": return new ItemStack(Items.porkchop);
                case "PRESTIGE": return new ItemStack(Items.gold_ingot);
                case "RICH_JAMES": return new ItemStack(Items.diamond);
                case "SHEEP_PARADE": return blockItem(Blocks.wool, 0);
                case "VILLAGER_MUSIC_BAND": return new ItemStack(Items.emerald);
                case "WOLF_PACK": return new ItemStack(Items.bone);
            }
        }
        return filterCategoryIcon(category);
    }

    private static ItemStack blockItem(net.minecraft.block.Block block, int metadata) {
        Item item = Item.getItemFromBlock(block);
        return item == null ? null : new ItemStack(item, 1, metadata);
    }

    private void drawSelectorItemIcon(ItemStack icon, int iconLeft, int rowTop,
                                      int rowHeight, float scale) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (icon == null || minecraft == null || minecraft.getRenderItem() == null || scale <= 0.0F) return;
        float iconSize = 16.0F * scale;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableDepth();
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.translate(iconLeft,
                    rowTop + (rowHeight - iconSize) / 2.0F, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            minecraft.getRenderItem().renderItemAndEffectIntoGUI(icon, 0, 0);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private int selectorColumnCount(int id) {
        return id == ID_UPDATE_TOP_350 ? 2 : 1;
    }

    private int selectorMenuWidth(List<String> options, int columns) {
        int widestOption = 0;
        for (String option : options) {
            String display = openSelectorId == ID_FILTER_VALUE
                    ? filterOptionDisplay(activeFilterCategory, option) : option;
            widestOption = Math.max(widestOption, fontRendererObj.getStringWidth(display));
        }
        int optionInset = openSelectorId == ID_KIT || openSelectorId == ID_FILTERS
                || openSelectorId == ID_FILTER_VALUE ? 34 : 10;
        int optionWidth = 4 + Math.max(1, columns) * (widestOption + optionInset);
        int titleWidth = fontRendererObj.getStringWidth(selectorTitle(openSelectorId)) + 14;
        if (openSelectorId == ID_FILTERS) {
            titleWidth = Math.max(titleWidth, fontRendererObj.getStringWidth(selectorTitle(openSelectorId))
                    + 42 + 4 + 38 + 16);
        } else if (openSelectorId == ID_FILTER_VALUE && ("ranks".equals(activeFilterCategory)
                || "level".equals(activeFilterCategory))) {
            titleWidth = Math.max(titleWidth, fontRendererObj.getStringWidth(selectorTitle(openSelectorId))
                    + 44 + fontRendererObj.getStringWidth("Back") + 16);
        }
        int availableWidth = Math.max(1, panelRight - panelLeft - 4);
        return Math.min(availableWidth, Math.max(titleWidth, optionWidth));
    }

    private boolean selectorMenuContains(int mouseX, int mouseY) {
        return openSelectorId != -1 && mouseX >= selectorLeft && mouseX <= selectorRight
                && mouseY >= selectorTop && mouseY <= selectorBottom;
    }

    private void chooseSelectorOption(int mouseX, int mouseY) {
        int optionY = mouseY - selectorTop - selectorHeaderHeight;
        if (optionY < 0 || selectorRowHeight <= 0) return;
        int columns = selectorColumnCount(openSelectorId);
        int optionRow = optionY / selectorRowHeight;
        int cellWidth = Math.max(1, (selectorRight - selectorLeft - 4) / columns);
        int column = columns == 1 ? 0
                : Math.max(0, Math.min(columns - 1, (mouseX - selectorLeft - 2) / cellWidth));
        int index = (selectorScrollOffset + optionRow) * columns + column;
        List<String> options = selectorOptions(openSelectorId);
        if (index < 0 || index >= options.size()) return;
        int selector = openSelectorId;
        if (selector == ID_UPDATE_TOP_350) {
            int[] refreshOptions = GoStatsLeaderboardRefreshCountStore.getOptions();
            if (index >= refreshOptions.length) return;
            refreshCountIndex = index;
            GoStatsLeaderboardRefreshCountStore.setRefreshCount(refreshOptions[index]);
            refreshStatus = "Update size saved: " + currentRefreshCount() + " players.";
            openSelectorId = -1;
            selectorScrollOffset = 0;
            layoutControls();
            return;
        }
        boolean changed = false;
        if (selector == ID_SCOPE && scopeIndex != index) {
            scopeIndex = index;
            sortIndex = 0;
            changed = true;
        } else if (selector == ID_SORT && sortIndex != index) {
            sortIndex = index;
            changed = true;
        } else if (selector == ID_PAGE_SIZE && pageSizeIndex != index) {
            pageSizeIndex = index;
            GoStatsLeaderboardPageSizeStore.setPageSize(PAGE_SIZES[index]);
            changed = true;
        } else if (selector == ID_KIT && kitIndex != index) {
            kitSelectionExplicit = true;
            kitIndex = index;
            appliedKit = kitNames.get(index);
            changed = true;
        } else if (selector == ID_KIT) {
            changed = !kitSelectionExplicit;
            kitSelectionExplicit = true;
        } else if (selector == ID_FILTERS) {
            activeFilterCategory = filterCategoryForIndex(index);
            if (activeFilterCategory.isEmpty()) return;
            if (!filterCategoryEnabled(activeFilterCategory)) {
                if ("kill_effect".equals(activeFilterCategory)) {
                    refreshStatus = "Kill effect filtering needs a gostats123 service update.";
                    layoutControls();
                }
                return;
            }
            if (kitFilterCatalog == null && !filterCatalogLoading) loadKitFilterCatalog();
            openSelectorId = ID_FILTER_VALUE;
            selectorScrollOffset = 0;
            return;
        } else if (selector == ID_FILTER_VALUE) {
            String option = options.get(index);
            if (option.startsWith("Loading choices") || option.startsWith("Unavailable")) return;
            if ("level".equals(activeFilterCategory)) {
                if ("Any".equalsIgnoreCase(option)) {
                    setPendingFilter("level", "");
                    returnToFilterCategories();
                    return;
                }
                Set<String> selectedLevels = selectedKitLevelTokens(currentFilterValue("level"));
                String level = filterParamValue("level", option).toLowerCase(Locale.ROOT);
                if (!selectedLevels.remove(level)) selectedLevels.add(level);
                setPendingFilter("level", joinKitLevelTokens(selectedLevels));
                return;
            }
            if ("ranks".equals(activeFilterCategory)) {
                if ("Any".equals(option)) {
                    pendingRankFilters.clear();
                    returnToFilterCategories();
                    return;
                }
                if (!pendingRankFilters.remove(option)) pendingRankFilters.add(option);
                return;
            }
            setPendingFilter(activeFilterCategory, filterParamValue(activeFilterCategory, option));
            returnToFilterCategories();
            return;
        }
        openSelectorId = -1;
        if (changed) {
            refreshStatus = "";
            resetAndFetch();
        }
    }

    private String[] currentMetrics() {
        if ("kit".equals(currentScope())) return KIT_METRICS;
        if ("solo".equals(currentScope())) return SOLO_METRICS;
        if ("teams".equals(currentScope())) return MODE_METRICS;
        return OVERALL_METRICS;
    }

    private String[] currentMetricLabels() {
        if ("kit".equals(currentScope())) return KIT_LABELS;
        if ("solo".equals(currentScope())) return SOLO_LABELS;
        if ("teams".equals(currentScope())) return MODE_LABELS;
        return OVERALL_LABELS;
    }

    private String formatMetric(long value) {
        return formatMetric((double) value);
    }

    /** Mirrors the related stat columns shown by gostats123's leaderboard. */
    private List<String> overallDisplayColumns() {
        String scope = currentScope();
        String metric = currentMetric();
        List<String> columns = new ArrayList<String>();
        if ("kit".equals(scope)) {
            if ("solo_wins".equals(metric) || "team_wins".equals(metric)) {
                columns.add("solo_wins");
                columns.add("team_wins");
                columns.add("total_wins");
            } else if ("kills".equals(metric)) {
                columns.add("kills");
                columns.add("wins");
                columns.add("deaths");
                columns.add("kdr");
            } else if ("wins".equals(metric)) {
                columns.add("wins");
                columns.add("kills");
                columns.add("deaths");
                columns.add("kdr");
            } else if ("deaths".equals(metric)) {
                columns.add("deaths");
                columns.add("kills");
                columns.add("wins");
                columns.add("kdr");
            } else {
                columns.add(metric);
                columns.add("kills");
                columns.add("wins");
                columns.add("kdr");
            }
            return columns;
        }
        if ("solo".equals(scope) || "teams".equals(scope)) {
            if ("kills".equals(metric)) {
                columns.add("solo_kills");
                columns.add("team_kills");
                columns.add("total_kills");
            } else if ("wins".equals(metric)) {
                columns.add("solo_wins");
                columns.add("team_wins");
                columns.add("total_wins");
            } else if ("solo_chaos_kills".equals(metric)) {
                columns.add("solo_chaos_kills");
                columns.add("solo_chaos_wins");
            } else if ("solo_chaos_wins".equals(metric)) {
                columns.add("solo_chaos_wins");
                columns.add("solo_chaos_kills");
                columns.add("total_wins");
            }
            return columns;
        }
        if (!"overall".equals(scope)) return Collections.emptyList();
        if ("kills".equals(metric)) {
            columns.add("kills");
            columns.add("wins");
            columns.add("deaths");
            columns.add("kdr");
        } else if ("wins".equals(metric)) {
            columns.add("wins");
            columns.add("kills");
            columns.add("deaths");
            columns.add("kdr");
        } else if ("deaths".equals(metric)) {
            columns.add("deaths");
            columns.add("kills");
            columns.add("wins");
            columns.add("kdr");
        } else if ("kdr".equals(metric)) {
            columns.add("kdr");
            columns.add("solo_kdr");
            columns.add("team_kdr");
        } else if ("wlr".equals(metric)) {
            columns.add("wlr");
            columns.add("solo_wlr");
            columns.add("team_wlr");
        } else if ("blitz_stars_used".equals(metric)) {
            columns.add("blitz_stars_used");
            columns.add("blitz_stars_per_game");
            columns.add("games");
        } else if ("blitz_stars_per_game".equals(metric)) {
            columns.add("blitz_stars_per_game");
            columns.add("blitz_stars_used");
            columns.add("games");
        } else if ("prestige_2".equals(metric)) {
            columns.add("prestige_2");
            columns.add("prestige_1_count");
            columns.add("level_x_count");
            columns.add("coins");
            columns.add("kills");
        } else if (isTravelMetric(metric)) {
            columns.add(metric);
            for (String travelMetric : TRAVEL_METRICS) {
                if (!travelMetric.equals(metric)) columns.add(travelMetric);
            }
        } else {
            columns.add(metric);
            columns.add("kills");
            columns.add("wins");
            columns.add("kdr");
        }
        return columns;
    }

    private boolean isTravelMetric(String metric) {
        for (String travelMetric : TRAVEL_METRICS) {
            if (travelMetric.equals(metric)) return true;
        }
        return false;
    }

    /** Fit the rank column to all loaded positions so 4+ digit ranks stay inside row highlights. */
    private int rankColumnNeeded(boolean kitScope) {
        int widest = fontRendererObj.getStringWidth("POS");
        for (int index = 0; index < players.size(); index++) {
            GoStatsLeaderboardClient.Player player = players.get(index);
            widest = Math.max(widest,
                    fontRendererObj.getStringWidth("#" + player.rank));
        }
        return computeRankColumnWidth(kitScope, widest);
    }

    static int computeRankColumnWidth(boolean kitScope, int widestRankTextWidth) {
        return Math.max(kitScope ? 32 : 42, widestRankTextWidth + 14);
    }

    private int[] overallColumnWidths(List<String> columns) {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            String key = columns.get(i);
            widths[i] = Math.max(34, fontRendererObj.getStringWidth(overallColumnLabel(key)) + 6);
        }
        for (int index = 0; index < players.size(); index++) {
            GoStatsLeaderboardClient.Player player = players.get(index);
            for (int column = 0; column < columns.size(); column++) {
                widths[column] = Math.max(widths[column],
                        fontRendererObj.getStringWidth(overallColumnValue(player, columns.get(column))) + 8);
            }
        }
        return widths;
    }

    /** Fit the player block to all loaded names so scrolling does not shift adjacent columns. */
    private int overallPlayerColumnNeeded(int start, int end) {
        int widest = 0;
        for (int index = start; index < end; index++) {
            GoStatsLeaderboardClient.Player player = players.get(index);
            String killPrefix = buildKillCountPrefixText(player);
            String rankedName = buildRankedUsernameText(player);
            int textWidth = fontRendererObj.getStringWidth(killPrefix)
                    + fontRendererObj.getStringWidth(" ")
                    + fontRendererObj.getStringWidth(rankedName);
            widest = Math.max(widest, textWidth);
        }
        // Avatar, name inset and a few pixels before the adjacent action column.
        return widest + 19;
    }

    /** Fit Kit names after the reserved level marker so the action and metric columns stay aligned. */
    private int kitPlayerColumnNeeded(int start, int end) {
        int widest = 0;
        boolean showKitLevelSlot = !"rambo".equalsIgnoreCase(appliedKit.trim());
        int nameInset = showKitLevelSlot
                ? 10 + KIT_LEVEL_HEAD_GAP + KIT_LEVEL_MARKER_SLOT_WIDTH + KIT_LEVEL_NAME_GAP
                : 10 + 5;
        for (int index = start; index < end; index++) {
            GoStatsLeaderboardClient.Player player = players.get(index);
            String killPrefix = buildKillCountPrefixText(player);
            String rankedName = buildRankedUsernameText(player);
            int textWidth = fontRendererObj.getStringWidth(killPrefix)
                    + fontRendererObj.getStringWidth(" ")
                    + fontRendererObj.getStringWidth(rankedName);
            widest = Math.max(widest, nameInset + textWidth + 4);
        }
        return widest;
    }

    private String overallColumnLabel(String key) {
        if ("solo_kills".equals(key)) return "SOLO KILLS";
        if ("team_kills".equals(key)) return "TEAMS KILLS";
        if ("total_kills".equals(key) || "total_wins".equals(key)) return "TOTAL";
        if ("solo_wins".equals(key)) return "SOLO WINS";
        if ("team_wins".equals(key)) return "TEAMS WINS";
        if ("solo_chaos_kills".equals(key)) return "CHAOS KILLS";
        if ("solo_chaos_wins".equals(key)) return "CHAOS WINS";
        if ("kills".equals(key)) return "KILLS";
        if ("wins".equals(key)) return "WINS";
        if ("deaths".equals(key)) return "DEATHS";
        if ("kdr".equals(key)) return "KDR";
        if ("wlr".equals(key)) return "WLR";
        if ("solo_kdr".equals(key)) return "SOLO KDR";
        if ("team_kdr".equals(key)) return "TEAMS KDR";
        if ("solo_wlr".equals(key)) return "SOLO WLR";
        if ("team_wlr".equals(key)) return "TEAMS WLR";
        if ("games".equals(key)) return "GAMES";
        if ("time_played".equals(key)) return "PLAYTIME";
        if ("playtime_per_game".equals(key)) return "PLAYTIME / GAME";
        if ("blitz_stars_used".equals(key)) return "BLITZ STARS USED";
        if ("blitz_stars_per_game".equals(key)) return "BLITZ STARS/GAME";
        if ("coins".equals(key)) return "COINS";
        if ("prestige_2".equals(key) || "prestige_2_count".equals(key)) return "✫✫";
        if ("prestige_1_count".equals(key)) return "✫";
        if ("level_x_count".equals(key)) return "X";
        if ("items_enchanted_tim".equals(key)) return "ITEMS ENCHANTED";
        if ("blocks_traveled_pig".equals(key)) return "PIG";
        if ("blocks_traveled_boat".equals(key)) return "BOAT";
        if ("blocks_traveled_minecart".equals(key)) return "MINECART";
        if ("blocks_traveled_horse".equals(key)) return "HORSE";
        if ("chests_opened".equals(key)) return "CHESTS OPENED";
        if ("experience".equals(key)) return "KIT EXPERIENCE";
        if ("exp_per_game".equals(key)) return "EXP / GAME";
        if ("damage_ratio".equals(key)) return "DAMAGE RATIO";
        if ("taunt_kills".equals(key)) return "TAUNT KILLS";
        if ("mobs_spawned".equals(key)) return "MOBS SPAWNED";
        if ("potions_drunk".equals(key)) return "POTIONS DRUNK";
        if ("potions_thrown".equals(key)) return "POTIONS THROWN";
        return currentSortLabel().toUpperCase(Locale.ROOT);
    }

    private String overallColumnValue(GoStatsLeaderboardClient.Player player, String key) {
        if ("kit".equals(currentScope())) return kitColumnValue(player, key);
        if ("solo_kills".equals(key)) return formatModeComponent(player.soloKills, player.kills);
        if ("team_kills".equals(key)) return formatModeComponent(player.teamKills, player.kills);
        if ("total_kills".equals(key)) return NUMBER.format(player.kills);
        if ("solo_wins".equals(key)) return formatModeComponent(player.soloWins, player.wins);
        if ("team_wins".equals(key)) return formatModeComponent(player.teamWins, player.wins);
        if ("total_wins".equals(key)) return NUMBER.format(player.wins);
        if ("coins".equals(key)) return NUMBER.format(player.coins);
        if ("solo_chaos_kills".equals(key)) {
            long value = Math.max(0L, Math.round(player.supplementalStat(key,
                    key.equals(currentMetric()) ? player.metricValue : 0.0D)));
            return formatModeComponent(value, player.kills);
        }
        if ("solo_chaos_wins".equals(key)) {
            long value = Math.max(0L, Math.round(player.supplementalStat(key,
                    key.equals(currentMetric()) ? player.metricValue : 0.0D)));
            return formatModeComponent(value, player.wins);
        }
        if ("kills".equals(key)) return NUMBER.format(player.kills);
        if ("wins".equals(key)) return NUMBER.format(player.wins);
        if ("deaths".equals(key)) return NUMBER.format(player.deaths);
        if ("kdr".equals(key)) return formatRatio(player.kills, player.deaths);
        if ("wlr".equals(key)) return formatRatio(player.wins, player.deaths);
        if ("solo_kdr".equals(key)) return formatRatio(player.soloKills, player.deaths);
        if ("team_kdr".equals(key)) return formatRatio(player.teamKills, player.deaths);
        if ("solo_wlr".equals(key)) return formatRatio(player.soloWins, player.deaths);
        if ("team_wlr".equals(key)) return formatRatio(player.teamWins, player.deaths);
        if ("prestige_2".equals(key))
            return NUMBER.format(Math.max(0L, Math.round(player.metricValue)));
        if ("prestige_1_count".equals(key)) return NUMBER.format(player.prestige1Count);
        if ("prestige_2_count".equals(key)) return NUMBER.format(player.prestige2Count);
        if ("level_x_count".equals(key)) return NUMBER.format(player.levelXCount);
        if ("blitz_stars_used".equals(key) || "blitz_stars_per_game".equals(key)) {
            double value = player.supplementalStat(key,
                    key.equals(currentMetric()) ? player.metricValue : 0.0D);
            return formatColumnMetric(key, value);
        }
        if (isTravelMetric(key)) {
            return NUMBER.format(Math.max(0L, Math.round(player.supplementalStat(key,
                    key.equals(currentMetric()) ? player.metricValue : 0.0D))));
        }
        return formatMetric(player.metricValue);
    }

    private String kitColumnValue(GoStatsLeaderboardClient.Player player, String key) {
        long totalWins = Math.max(0L, Math.round(player.supplementalStat("kit_total_wins",
                "wins".equals(currentMetric()) || "solo_wins".equals(currentMetric())
                        || "team_wins".equals(currentMetric()) ? player.metricValue : 0.0D)));
        if ("solo_wins".equals(key)) {
            long value = Math.max(0L, Math.round(player.supplementalStat("kit_solo_wins",
                    key.equals(currentMetric()) ? player.metricValue : 0.0D)));
            return formatModeComponent(value, totalWins);
        }
        if ("team_wins".equals(key)) {
            long value = Math.max(0L, Math.round(player.supplementalStat("kit_team_wins",
                    key.equals(currentMetric()) ? player.metricValue : 0.0D)));
            return formatModeComponent(value, totalWins);
        }
        if ("total_wins".equals(key)) return NUMBER.format(totalWins);
        double fallback = key.equals(currentMetric()) ? player.metricValue : 0.0D;
        return formatColumnMetric(key, player.supplementalStat("kit_" + key, fallback));
    }

    private String formatModeComponent(long value, long total) {
        long safeValue = Math.max(0L, value);
        long safeTotal = Math.max(0L, total);
        int percentage = safeTotal > 0L
                ? (int) ((double) safeValue / (double) safeTotal * 100.0D) : 0;
        return NUMBER.format(safeValue) + " (" + percentage + "%)";
    }

    private String formatColumnMetric(String key, double value) {
        if ("time_played".equals(key) || "playtime_per_game".equals(key)) {
            long seconds = Math.max(0L, (long) value);
            if (seconds >= 3600L) return NUMBER.format(seconds / 3600L) + "h " + (seconds % 3600L) / 60L + "m";
            if (seconds >= 60L) return NUMBER.format(seconds / 60L) + "m";
            return seconds + "s";
        }
        if ("kdr".equals(key) || "wlr".equals(key)
                || "exp_per_game".equals(key) || "arrow_accuracy".equals(key)
                || "damage_ratio".equals(key) || "damage_per_kill".equals(key)
                || "taunt_ratio".equals(key) || "mobs_spawned_per_game".equals(key)
                || "chests_per_game".equals(key) || "potions_drunk_per_game".equals(key)
                || "blitz_stars_per_game".equals(key)) {
            String formatted = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))
                    .format(value);
            if ("arrow_accuracy".equals(key) || "damage_ratio".equals(key)
                    || "taunt_ratio".equals(key)) {
                return new DecimalFormat("#,##0.0", DecimalFormatSymbols.getInstance(Locale.ROOT))
                        .format(value) + "%";
            }
            return formatted;
        }
        if (Math.abs(value - Math.rint(value)) < 0.005D) {
            return NUMBER.format((long) Math.rint(value));
        }
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }

    private String formatRatio(long numerator, long denominator) {
        return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT))
                .format(numerator / (double) Math.max(1L, denominator));
    }

    private int overallColumnColor(String key) {
        if ("kit".equals(currentScope()) && "total_wins".equals(key)) return 0xFFFFB30E;
        return metricValueColor(key);
    }

    private int metricValueColor(String metric) {
        if (metric == null) return CYAN;
        if ("kills".equals(metric) || "solo_kills".equals(metric) || "team_kills".equals(metric)
                || "total_kills".equals(metric) || "solo_chaos_kills".equals(metric))
            return 0xFF2AFF00;
        if ("wins".equals(metric) || "solo_wins".equals(metric) || "team_wins".equals(metric)
                || "total_wins".equals(metric) || "solo_chaos_wins".equals(metric))
            return 0xFFFCFF00;
        if ("deaths".equals(metric)) return 0xFFFF0000;
        if ("kdr".equals(metric) || "wlr".equals(metric)
                || "solo_kdr".equals(metric) || "team_kdr".equals(metric)
                || "solo_wlr".equals(metric) || "team_wlr".equals(metric)
                || "coins".equals(metric)) return 0xFFFFB30E;
        if ("prestige".equals(metric) || "prestige_2".equals(metric)
                || "prestige_1_count".equals(metric) || "prestige_2_count".equals(metric)) return 0xFFFFAA00;
        if ("level_x_count".equals(metric)) return 0xFFB00000;
        if ("blitz_stars_used".equals(metric) || "blitz_stars_per_game".equals(metric))
            return 0xFFFED907;
        if ("blocks_traveled_pig".equals(metric)) return 0xFFFF7DAF;
        if ("blocks_traveled_boat".equals(metric)) return 0xFFB0703E;
        if ("blocks_traveled_minecart".equals(metric)) return 0xFFBEBEBE;
        if ("blocks_traveled_horse".equals(metric)) return 0xFFDDB249;
        return CYAN;
    }

    private String formatMetric(double value) {
        if ("time_played".equals(currentMetric())
                || "playtime_per_game".equals(currentMetric())) {
            long minutes = Math.max(0L, (long) value / 60L);
            long hours = minutes / 60L;
            long days = hours / 24L;
            if (days > 0L) return NUMBER.format(days) + "d " + (hours % 24L) + "h";
            if (hours > 0L) return NUMBER.format(hours) + "h " + (minutes % 60L) + "m";
            return NUMBER.format(minutes) + "m";
        }
        if ("blitz_stars_per_game".equals(currentMetric())
                || "exp_per_game".equals(currentMetric())) {
            return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
        }
        String formatted = Math.abs(value - Math.rint(value)) < 0.005
                ? NUMBER.format((long) Math.rint(value))
                : new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
        if ("arrow_accuracy".equals(currentMetric()) || "damage_ratio".equals(currentMetric())
                || "taunt_ratio".equals(currentMetric())) return formatted + "%";
        return formatted;
    }

    private String kitLevelText(GoStatsLeaderboardClient.Player player) {
        if (player == null || !"kit".equals(currentScope()) || player.kit == null
                || "rambo".equalsIgnoreCase(player.kit.trim())) return "";
        if (player.kitPrestige >= 2L) return "✫✫";
        if (player.kitPrestige == 1L) return "✫";
        long level = player.kitLevel;
        if (level <= 0L && (player.games > 0L || isBasicKitName(player.kit))) level = 1L;
        if (level <= 0L) return "";
        switch ((int) level) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            case 9: return "IX";
            case 10: return "X";
            default: return Long.toString(level);
        }
    }

    private boolean isBasicKitName(String kit) {
        if (kit == null) return false;
        String normalized = kit.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "archer".equals(normalized) || "meatmaster".equals(normalized)
                || "speleologist".equals(normalized) || "baker".equals(normalized)
                || "knight".equals(normalized) || "guardian".equals(normalized)
                || "scout".equals(normalized) || "hunter".equals(normalized)
                || "hypetrain".equals(normalized) || "fisherman".equals(normalized)
                || "armorer".equals(normalized);
    }

    private int kitLevelColor(GoStatsLeaderboardClient.Player player) {
        if (player == null || player.kitPrestige > 0L) return 0xFFFFAA00;
        long level = player.kitLevel;
        if (level <= 0L && (player.games > 0L || isBasicKitName(player.kit))) level = 1L;
        return kitLevelColorForLevel(level);
    }

    private int kitFilterLevelColor(String option) {
        String value = filterParamValue("level", option);
        if ("p1".equalsIgnoreCase(value) || "p2".equalsIgnoreCase(value)) return 0xFFFFAA00;
        try {
            return kitLevelColorForLevel(Long.parseLong(value));
        } catch (Exception ignored) {
            return MUTED;
        }
    }

    private int kitLevelColorForLevel(long level) {
        switch ((int) level) {
            case 1: return 0xFF55FF55;
            case 2: return 0xFF54FA55;
            case 3: return 0xFF08A504;
            case 4: return 0xFF00A805;
            case 5:
            case 6: return 0xFFFFFF56;
            case 7: return 0xFFFDA900;
            case 8: return 0xFFF8A500;
            case 9: return 0xFFFF5556;
            case 10: return 0xFFB00000;
            default: return MUTED;
        }
    }

    private void drawBatchRefreshOverlay(GoStatsLeaderboardClient.Player player, int yPos, long now,
                                         int listLeft, int listRight) {
        if (player == null || player.uuid == null) return;
        String uuid = player.uuid.toLowerCase(Locale.ROOT);
        int left = listLeft + 1;
        int right = listRight - 1;
        int top = yPos - 2;
        int bottom = yPos + fontRendererObj.FONT_HEIGHT + 1;
        uuid = normalizeLeaderboardUuid(uuid);
        Long completedAt = batchRefreshCompletedAt.get(uuid);
        if (completedAt != null) {
            long age = now - completedAt;
            if (age >= 900L) {
                batchRefreshCompletedAt.remove(uuid);
            } else {
                float remaining = 1.0F - age / 900.0F;
                int alpha = 26 + Math.max(0, Math.min(85, (int) (remaining * 85.0F)));
                Gui.drawRect(left, top, right, bottom, (alpha << 24) | 0x0033CC55);
                return;
            }
        }
        Long failedAt = batchRefreshFailedAt.get(uuid);
        if (failedAt != null) {
            long age = now - failedAt;
            if (age >= 900L) {
                batchRefreshFailedAt.remove(uuid);
            } else {
                float remaining = 1.0F - age / 900.0F;
                int alpha = 24 + Math.max(0, Math.min(76, (int) (remaining * 76.0F)));
                Gui.drawRect(left, top, right, bottom, (alpha << 24) | 0x00D74B57);
                return;
            }
        }
        Long startedAt = batchRefreshStartedAt.get(uuid);
        if (startedAt != null) {
            double pulse = 0.55D + 0.45D * Math.sin((now - startedAt) / 90.0D);
            int alpha = 28 + Math.max(0, Math.min(70, (int) Math.round(pulse * 70.0D)));
            Gui.drawRect(left, top, right, bottom, (alpha << 24) | 0x002FA84A);
        }
    }

    private void drawPresenceBubble(GoStatsLeaderboardClient.Player player, int headX, int headY, int headSize) {
        if (player == null || player.presenceStatus == null || player.presenceStatus.trim().isEmpty()) return;
        String status = GoStatsPlayerPresence.displayedStatus(player.uuid, player.presenceStatus)
                .toLowerCase(Locale.ROOT);
        int color = "online".equals(status) ? 0xFF31D266
                : "hidden".equals(status) ? 0xFF8B0000 : 0xFF969DA7;
        int left = headX + headSize - 4;
        int top = headY - 2;
        int outline = 0xFF101820;
        // A rounded 7px status bubble with a dark rim and a filled center, not a cross-shaped dot.
        int[] outerWidths = {3, 5, 7, 7, 7, 5, 3};
        int[] innerWidths = {0, 3, 5, 5, 5, 3, 0};
        for (int row = 0; row < outerWidths.length; row++) {
            int outerLeft = left + (7 - outerWidths[row]) / 2;
            Gui.drawRect(outerLeft, top + row, outerLeft + outerWidths[row], top + row + 1, outline);
            if (innerWidths[row] > 0) {
                int innerLeft = left + (7 - innerWidths[row]) / 2;
                Gui.drawRect(innerLeft, top + row, innerLeft + innerWidths[row], top + row + 1, color);
            }
        }
    }

    private void updateHoveredSessionTarget(String uuid) {
        String normalized = normalizeLeaderboardUuid(uuid);
        if (normalized.isEmpty()) {
            hoveredSessionUuid = "";
            hoveredSessionSinceMs = 0L;
            return;
        }
        if (!normalized.equals(hoveredSessionUuid)) {
            hoveredSessionUuid = normalized;
            hoveredSessionSinceMs = System.currentTimeMillis();
        }
    }

    private boolean isHoveredSessionDwellMet(String uuid) {
        String normalized = normalizeLeaderboardUuid(uuid);
        return !normalized.isEmpty()
                && normalized.equals(hoveredSessionUuid)
                && hoveredSessionSinceMs > 0L
                && System.currentTimeMillis() - hoveredSessionSinceMs >= SESSION_HOVER_DWELL_MS;
    }

    private void addPresenceTooltip(List<String> tooltip, GoStatsLeaderboardClient.Player player) {
        if (player == null || !wasManuallyRefreshed(player)) return;

        boolean batchRefreshing = top350Refreshing || GoStatsLeaderboardBatchRefresh.isRunning();
        String status = player.presenceStatus == null ? "" : player.presenceStatus.trim();
        GoStatsPlayerPresence.SessionDetails session = GoStatsPlayerPresence.cachedSessionDetails(player.uuid);
        if (session == null && !batchRefreshing && isHoveredSessionDwellMet(player.uuid)) {
            GoStatsPlayerPresence.requestSessionDetails(player.uuid);
            session = GoStatsPlayerPresence.cachedSessionDetails(player.uuid);
        }
        status = GoStatsPlayerPresence.displayedStatus(player.uuid, status);
        if ("online".equalsIgnoreCase(status)) {
            tooltip.add(EnumChatFormatting.GREEN + "Status: Online");
        } else if ("offline".equalsIgnoreCase(status)) {
            tooltip.add(EnumChatFormatting.RED + "Status: Offline");
        } else if ("hidden".equalsIgnoreCase(status)) {
            tooltip.add(EnumChatFormatting.DARK_RED + "Status: Hidden");
        }
        if ("online".equalsIgnoreCase(status)) {
            if (player.lastLoginMs > 0L) {
                tooltip.add(EnumChatFormatting.GREEN + "Login Time: " + durationAgo(player.lastLoginMs));
            }
            if (session == null) {
                if (!batchRefreshing) {
                    tooltip.add(EnumChatFormatting.GRAY + "Session details: Loading...");
                }
            } else if (!session.available) {
                tooltip.add(EnumChatFormatting.GRAY + "Session details unavailable");
            } else if (session.online) {
                if (!session.gameType.isEmpty()) {
                    tooltip.add(EnumChatFormatting.GREEN + "Game: " + formatLastGameName(session.gameType));
                }
                String mode = session.mode;
                if ("dynamic".equalsIgnoreCase(mode) && !session.map.isEmpty()) mode = session.map;
                if (!mode.isEmpty()) {
                    tooltip.add(EnumChatFormatting.GREEN + "Mode: " + displaySessionMode(mode));
                }
                if (!session.map.isEmpty() && !session.map.equalsIgnoreCase(mode)) {
                    tooltip.add(EnumChatFormatting.GREEN + "Map: " + session.map);
                }
                if (player.lastActionSeconds > 0L) {
                    tooltip.add(EnumChatFormatting.GREEN + "Last Action: "
                            + formatPresenceAction(player.lastAction));
                    tooltip.add(EnumChatFormatting.GREEN + "Last Action Time: "
                            + durationAgo(player.lastActionSeconds * 1000L));
                }
            }
        } else if (player.lastActionSeconds > 0L) {
            String action = formatPresenceAction(player.lastAction);
            tooltip.add(EnumChatFormatting.GREEN + "Last Action: " + action + " - "
                    + formatEpochSeconds(player.lastActionSeconds));
        }
        if (!"hidden".equalsIgnoreCase(status) && !"online".equalsIgnoreCase(status)
                && player.lastLoginMs > 0L
                && player.lastGameType != null
                && !player.lastGameType.trim().isEmpty()) {
            tooltip.add(EnumChatFormatting.GREEN + "Last Game: "
                    + formatLastGameName(player.lastGameType) + EnumChatFormatting.GREEN + " - "
                    + formatEpochSeconds(player.lastLoginMs / 1000L));
        }
        if (session == null) {
            if (!batchRefreshing) {
                tooltip.add(EnumChatFormatting.GRAY + "Recent Games: Loading...");
            }
        } else {
            addRecentGamesTooltip(tooltip, session);
        }
    }

    private void addRecentGamesTooltip(List<String> tooltip,
                                       GoStatsPlayerPresence.SessionDetails session) {
        if (!session.recentGamesAvailable) {
            tooltip.add(EnumChatFormatting.GRAY + "Recent Games: Unavailable");
            return;
        }
        if (session.recentGames.isEmpty()) {
            return;
        }
        tooltip.add(EnumChatFormatting.GOLD + "Recent Games:");
        for (GoStatsPlayerPresence.RecentGame game : session.recentGames) {
            String mode = displayRecentGameMode(game.gameType, game.mode);
            String map = game.map.isEmpty() ? "N/A" : game.map;
            tooltip.add(formatLastGameName(game.gameType) + EnumChatFormatting.GRAY + " · "
                    + EnumChatFormatting.WHITE + mode + EnumChatFormatting.GRAY + " · "
                    + EnumChatFormatting.WHITE + map);
            tooltip.add(EnumChatFormatting.GRAY + "Started: " + EnumChatFormatting.WHITE
                    + formatRecentGameAge(game.startedAtMs) + EnumChatFormatting.GRAY + " · Ended: "
                    + EnumChatFormatting.WHITE + (game.endedAtMs > 0L
                    ? formatRecentGameAge(game.endedAtMs) : "N/A"));
        }
    }

    private String displayRecentGameMode(String gameType, String mode) {
        if (mode == null || mode.trim().isEmpty()) return "N/A";
        String normalizedGame = gameType == null ? "" : gameType.trim().toUpperCase(Locale.ROOT);
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("SURVIVAL_GAMES".equals(normalizedGame) || "BLITZSG".equals(normalizedGame)) {
            if ("solo_normal".equals(normalizedMode) || "solo".equals(normalizedMode)) return "Solo";
            if ("teams_normal".equals(normalizedMode) || "teams".equals(normalizedMode)) return "Doubles";
        }
        return displaySessionMode(mode);
    }

    private String formatRecentGameAge(long timestampMs) {
        if (timestampMs <= 0L) return "N/A";
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestampMs) / 1000L);
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (days > 0L) return days + "d, " + hours + "h ago";
        if (hours > 0L) return hours + "h, " + minutes + "m ago";
        if (minutes > 0L) return minutes + "m, " + remainingSeconds + "s ago";
        return seconds + "s ago";
    }

    private String durationAgo(long timestampMs) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestampMs) / 1000L);
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        if (days > 0L) return days + "d, " + hours + "h ago";
        if (hours > 0L) return hours + "h, " + minutes + "m ago";
        if (minutes > 0L) return minutes + "m ago";
        return seconds + "s ago";
    }

    private String displaySessionMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return "";
        String normalized = mode.trim().replace('_', ' ').replace('-', ' ');
        if ("dynamic".equalsIgnoreCase(normalized)) return "Dynamic";
        String[] words = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            String lower = word.toLowerCase(Locale.ROOT);
            result.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return result.toString();
    }

    private String formatPresenceAction(String action) {
        if (action == null || action.trim().isEmpty()) return "Unknown";
        String normalized = action.trim().replace('_', ' ');
        String[] words = normalized.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            String lower = word.toLowerCase(Locale.ROOT);
            if ("sw".equals(lower)) result.append("SkyWars");
            else if ("pit".equals(lower)) result.append("Pit");
            else if ("uhc".equals(lower)) result.append("UHC");
            else if ("exp".equals(lower)) result.append("EXP");
            else result.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return result.length() == 0 ? "Unknown" : result.toString();
    }

    private String formatLastGameName(String gameType) {
        if (gameType == null || gameType.trim().isEmpty()) return "Unknown";
        switch (gameType.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "ARCADE": return "§cA§6r§ec§aa§bd§de§f";
            case "ARENA":
            case "ARENA_BRAWL":
                return EnumChatFormatting.DARK_AQUA + "Arena " + EnumChatFormatting.GOLD + "Brawl"
                        + EnumChatFormatting.RESET;
            case "BEDWARS":
            case "BED_WARS":
                return EnumChatFormatting.RED + "Bed" + EnumChatFormatting.WHITE + "Wars"
                        + EnumChatFormatting.RESET;
            case "SURVIVAL_GAMES":
            case "BLITZSG":
                return EnumChatFormatting.DARK_GREEN + "Blitz" + EnumChatFormatting.GOLD + "SG"
                        + EnumChatFormatting.RESET;
            case "BUILD_BATTLE":
                return EnumChatFormatting.LIGHT_PURPLE + "Build Battle" + EnumChatFormatting.RESET;
            case "MCGO":
            case "COPS_AND_CRIMS":
                return EnumChatFormatting.GOLD + "Cops " + EnumChatFormatting.WHITE + "and "
                        + EnumChatFormatting.GREEN + "Crims" + EnumChatFormatting.RESET;
            case "DUELS": return EnumChatFormatting.AQUA + "Duels" + EnumChatFormatting.RESET;
            case "HOUSING": return EnumChatFormatting.BLUE + "Housing" + EnumChatFormatting.RESET;
            case "WALLS3":
            case "MEGAWALLS":
                return EnumChatFormatting.GRAY + "MegaWalls" + EnumChatFormatting.RESET;
            case "MURDER_MYSTERY":
                return EnumChatFormatting.DARK_RED + "Murder Mystery" + EnumChatFormatting.RESET;
            case "PAINTBALL":
                return EnumChatFormatting.AQUA + "Paint" + EnumChatFormatting.WHITE + "ball"
                        + EnumChatFormatting.RESET;
            case "PIT":
                return EnumChatFormatting.YELLOW + "The " + EnumChatFormatting.GREEN + "Pit"
                        + EnumChatFormatting.RESET;
            case "PROTOTYPE":
                return EnumChatFormatting.DARK_AQUA + "Proto" + EnumChatFormatting.WHITE + "type"
                        + EnumChatFormatting.RESET;
            case "QUAKECRAFT":
            case "QUAKE":
                return EnumChatFormatting.DARK_PURPLE + "Quake" + EnumChatFormatting.RESET;
            case "SKYBLOCK":
                return EnumChatFormatting.AQUA + "Sky" + EnumChatFormatting.WHITE + "block"
                        + EnumChatFormatting.RESET;
            case "SKYCLASH":
                return EnumChatFormatting.DARK_GRAY + "Sky" + EnumChatFormatting.GOLD + "clash"
                        + EnumChatFormatting.RESET;
            case "SKYWARS":
                return EnumChatFormatting.AQUA + "Sky" + EnumChatFormatting.YELLOW + "Wars"
                        + EnumChatFormatting.RESET;
            case "SUPER_SMASH":
            case "SMASH_HEROES":
                return EnumChatFormatting.LIGHT_PURPLE + "Smash " + EnumChatFormatting.YELLOW + "Heroes"
                        + EnumChatFormatting.RESET;
            case "SPEED_UHC":
                return EnumChatFormatting.AQUA + "Speed" + EnumChatFormatting.GOLD + "UHC"
                        + EnumChatFormatting.RESET;
            case "TNTGAMES":
            case "TNT_GAMES":
                return EnumChatFormatting.RED + "T" + EnumChatFormatting.WHITE + "N"
                        + EnumChatFormatting.RED + "T Games" + EnumChatFormatting.RESET;
            case "GINGERBREAD":
            case "TURBO_KART_RACERS":
                return EnumChatFormatting.GREEN + "Turbo Kart Racers" + EnumChatFormatting.RESET;
            case "UHC": return EnumChatFormatting.GOLD + "UHC" + EnumChatFormatting.RESET;
            case "VAMPIREZ":
                return EnumChatFormatting.DARK_RED + "VampireZ" + EnumChatFormatting.RESET;
            case "WALLS": return EnumChatFormatting.YELLOW + "Walls" + EnumChatFormatting.RESET;
            case "BATTLEGROUND":
            case "WARLORDS":
                return EnumChatFormatting.RED + "War" + EnumChatFormatting.YELLOW + "lords"
                        + EnumChatFormatting.RESET;
            case "WOOL_GAMES":
            case "WOOLWARS":
                return EnumChatFormatting.RED + "Wool" + EnumChatFormatting.BLUE + "Wars"
                        + EnumChatFormatting.RESET;
            case "TRUE_COMBAT":
            case "CRAZY_WALLS":
                return EnumChatFormatting.WHITE + "Crazy Walls" + EnumChatFormatting.RESET;
            case "LEGACY":
            case "CLASSIC":
                return EnumChatFormatting.AQUA + "Cla" + EnumChatFormatting.WHITE + "s"
                        + EnumChatFormatting.RED + "sic" + EnumChatFormatting.RESET;
            case "REPLAY": return EnumChatFormatting.WHITE + "Replay" + EnumChatFormatting.RESET;
            case "SMP": return EnumChatFormatting.WHITE + "SMP" + EnumChatFormatting.RESET;
            default:
                return EnumChatFormatting.WHITE + formatPresenceAction(gameType) + EnumChatFormatting.RESET;
        }
    }

    private boolean wasManuallyRefreshed(GoStatsLeaderboardClient.Player player) {
        return player != null && player.uuid != null && !player.uuid.trim().isEmpty()
                && manuallyRefreshedUuids.contains(normalizeLeaderboardUuid(player.uuid));
    }

    private String relativeTime(long epochSeconds) {
        long age = Math.max(0L, Instant.now().getEpochSecond() - epochSeconds);
        if (age < 60L) return age + "s ago";
        if (age < 3600L) return age / 60L + "m ago";
        if (age < 86400L) return age / 3600L + "h ago";
        if (age < 604800L) return age / 86400L + "d ago";
        if (age < 31_536_000L) return age / 604800L + "w ago";
        return age / 31_536_000L + "y ago";
    }

    private String formatEpochSeconds(long epochSeconds) {
        try {
            Instant instant = Instant.ofEpochSecond(epochSeconds);
            return UPDATED_AT.withZone(ZoneId.systemDefault()).format(instant)
                    + " (" + relativeTime(epochSeconds) + ")";
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private String buildKillCountPrefixText(GoStatsLeaderboardClient.Player player) {
        return (player.kills >= 100_000L ? EnumChatFormatting.BOLD.toString() : "")
                + "[" + formatKillCount(player.kills) + "]";
    }

    private String buildRankedUsernameText(GoStatsLeaderboardClient.Player player) {
        String rank = normalizeRank(player.rankDisplay);
        if (rank.isEmpty()) return EnumChatFormatting.GRAY + player.username;
        String tag = HypixelTabStatsRankCache.formatOfficialRankTag(
                player.rankDisplay, player.rankPlusColor, player.monthlyRankColor);
        String nameColor = HypixelTabStatsRankCache.formatOfficialUsernameColor(
                player.rankDisplay, player.monthlyRankColor);
        return (tag.isEmpty() ? "" : tag + " ") + nameColor + player.username;
    }

    private String formatKillCount(long kills) {
        long safeKills = Math.max(0L, kills);
        if (safeKills >= 1_000_000L) return safeKills / 1_000_000L + "M";
        if (safeKills >= 1_000L) return safeKills / 1_000L + "K";
        return NUMBER.format(safeKills);
    }

    private int killCountTextColor(GoStatsLeaderboardClient.Player player) {
        int alpha = player.killCounterEnabled ? 0xFF : (int) (255.0F * HIDDEN_KILL_COUNTER_OPACITY);
        return (alpha << 24) | killCountRgb(player.kills);
    }

    private int killCountRgb(long kills) {
        if (kills < 1_000L) return 0x00AAAAAA;
        if (kills < 25_000L) return 0x00FFFF55;
        if (kills < 50_000L) return 0x0055FF55;
        if (kills < 75_000L) return 0x00FF5555;
        if (kills < 100_000L) return 0x0055FFFF;
        if (kills < 150_000L) return 0x00FFAA00;
        if (kills < 200_000L) return 0x00AA00AA;
        if (kills < 250_000L) return 0x00AA0000;
        if (kills < 300_000L) return 0x005555FF;
        if (kills < 400_000L) return 0x0000AA00;
        return 0x00FFAA00;
    }

    private void drawPlayerTooltip(GoStatsLeaderboardClient.Player player,
                                   List<String> detailLines, int mouseX, int mouseY) {
        String killPrefix = buildKillCountPrefixText(player);
        String rankedName = buildRankedUsernameText(player);
        List<String> lines = new ArrayList<String>(detailLines.size() + 1);
        lines.add(killPrefix + " " + rankedName);
        lines.addAll(detailLines);

        int maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, fontRendererObj.getStringWidth(line));
        int left = mouseX + 12;
        int top = mouseY - 12;
        int tooltipHeight = 8;
        if (lines.size() > 1) tooltipHeight += 2 + (lines.size() - 1) * 10;
        if (left + maxWidth > width) left -= 28 + maxWidth;
        if (top + tooltipHeight + 6 > height) top = height - tooltipHeight - 6;

        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        float previousZ = zLevel;
        zLevel = 300.0F;
        int background = 0xF0100010;
        drawGradientRect(left - 3, top - 4, left + maxWidth + 3, top - 3, background, background);
        drawGradientRect(left - 3, top + tooltipHeight + 3, left + maxWidth + 3,
                top + tooltipHeight + 4, background, background);
        drawGradientRect(left - 3, top - 3, left + maxWidth + 3,
                top + tooltipHeight + 3, background, background);
        drawGradientRect(left - 4, top - 3, left - 3, top + tooltipHeight + 3, background, background);
        drawGradientRect(left + maxWidth + 3, top - 3, left + maxWidth + 4,
                top + tooltipHeight + 3, background, background);
        drawGradientRect(left - 3, top - 3, left - 2, top + tooltipHeight + 3,
                0x505000FF, 0x5028007F);
        drawGradientRect(left + maxWidth + 2, top - 3, left + maxWidth + 3,
                top + tooltipHeight + 3, 0x505000FF, 0x5028007F);
        drawGradientRect(left - 3, top - 3, left + maxWidth + 3, top - 2,
                0x505000FF, 0x505000FF);
        drawGradientRect(left - 3, top + tooltipHeight + 2, left + maxWidth + 3,
                top + tooltipHeight + 3, 0x5028007F, 0x5028007F);

        int lineY = top;
        fontRendererObj.drawStringWithShadow(killPrefix, left, lineY, killCountTextColor(player));
        int nameLeft = left + fontRendererObj.getStringWidth(killPrefix)
                + fontRendererObj.getStringWidth(" ");
        fontRendererObj.drawStringWithShadow(rankedName, nameLeft, lineY, 0xFFFFFFFF);
        if (lines.size() > 1) lineY += 2;
        lineY += 10;
        for (String line : detailLines) {
            fontRendererObj.drawStringWithShadow(line, left, lineY, 0xFFFFFFFF);
            lineY += 10;
        }

        zLevel = previousZ;
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        RenderHelper.enableStandardItemLighting();
        GlStateManager.enableRescaleNormal();
    }

    private String normalizeRank(String rank) {
        if (rank == null) return "";
        String value = rank.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty() || "DEFAULT".equals(value) || "NORMAL".equals(value)
                || "NONE".equals(value) || "UNRANKED".equals(value)) return "";
        return "YOUTUBE".equals(value) ? "YOUTUBER" : value;
    }

    private String formatLastUpdated(GoStatsLeaderboardClient.Player player) {
        String value = player.lastChecked;
        if (value == null || value.trim().isEmpty() || "never".equalsIgnoreCase(value)) value = player.fetchedAt;
        if (value == null || value.trim().isEmpty() || "never".equalsIgnoreCase(value)) return "never";
        try {
            Instant timestamp = OffsetDateTime.parse(value).toInstant();
            long seconds = Math.max(0L, Duration.between(timestamp, Instant.now()).getSeconds());
            String ago = seconds < 60 ? seconds + "s ago"
                    : seconds < 3600 ? seconds / 60 + "m ago"
                    : seconds < 86400 ? seconds / 3600 + "h ago" : seconds / 86400 + "d ago";
            return UPDATED_AT.withZone(ZoneId.systemDefault()).format(timestamp) + " (" + ago + ")";
        } catch (Exception ignored) {
            return value;
        }
    }

    private void addLastUpdatedTooltip(List<String> tooltip, GoStatsLeaderboardClient.Player player) {
        String officialValue = player.lastChecked;
        if (officialValue == null || officialValue.trim().isEmpty() || "never".equalsIgnoreCase(officialValue))
            officialValue = player.fetchedAt;
        Instant official = parseTimestamp(officialValue);
        Instant community = parseTimestamp(player.communityObservedAt);
        if (community != null && (official == null || community.isAfter(official))) {
            tooltip.add(EnumChatFormatting.GRAY + "Latest gomod observation: "
                    + formatLastUpdatedValue(player.communityObservedAt));
            if (official != null) tooltip.add(EnumChatFormatting.DARK_GRAY + "Official gostats123 check: "
                    + formatLastUpdatedValue(officialValue));
        } else {
            tooltip.add(EnumChatFormatting.GRAY + "Last updated: " + formatLastUpdated(player));
        }
        if (!player.refreshCacheLabel.isEmpty()) {
            tooltip.add(EnumChatFormatting.YELLOW + "Cached Hypixel profile · " + formatLastUpdatedValue(player.fetchedAt));
        }
        if (GoStatsContributions.isLeaderboardSyncPending(player.uuid)) {
            String dots = (System.currentTimeMillis() / 500L) % 2L == 0L ? ".." : "...";
            tooltip.add(EnumChatFormatting.GRAY + "gostats123 database sync pending" + dots);
        }
        String syncError = GoStatsContributions.leaderboardSyncError(player.uuid);
        if (!syncError.isEmpty()) {
            tooltip.add(EnumChatFormatting.RED + "gostats123 database sync failed:");
            tooltip.add(EnumChatFormatting.RED + fitText(syncError, Math.max(120, width - 50)));
        }
    }

    private void drawDisplayedStatChangeMarker(GoStatsLeaderboardClient.Player player, int yPos,
                                               int listLeft, int listRight) {
        if (player == null || player.uuid == null) return;
        List<DisplayedStatChange> changes = displayedStatChangesByUuid.get(
                normalizeLeaderboardUuid(player.uuid));
        if (changes == null || changes.isEmpty()) return;
        int top = yPos - 2;
        int bottom = yPos + fontRendererObj.FONT_HEIGHT + 1;
        Gui.drawRect(listLeft + 1, top, listRight - 1, bottom, 0x383AD4D0);
        Gui.drawRect(listLeft + 1, top, listLeft + 3, bottom, 0xFF42D8D0);
    }

    private void addDisplayedStatChangesTooltip(List<String> tooltip,
                                                GoStatsLeaderboardClient.Player player) {
        if (player == null || player.uuid == null) return;
        List<DisplayedStatChange> changes = displayedStatChangesByUuid.get(
                normalizeLeaderboardUuid(player.uuid));
        if (changes == null || changes.isEmpty()) return;
        tooltip.add(EnumChatFormatting.AQUA + "Displayed stats changed since the previous values:");
        for (DisplayedStatChange change : changes) {
            EnumChatFormatting statColor = closestChatColor(change.color);
            String delta = change.delta == null ? ""
                    : " " + EnumChatFormatting.AQUA + "(" + change.delta + ")";
            tooltip.add(statColor + change.label + ": "
                    + statColor + change.previousValue
                    + EnumChatFormatting.GRAY + " → "
                    + statColor + change.updatedValue + delta);
        }
    }

    private EnumChatFormatting closestChatColor(int rgb) {
        final EnumChatFormatting[] colors = {
                EnumChatFormatting.BLACK, EnumChatFormatting.DARK_BLUE,
                EnumChatFormatting.DARK_GREEN, EnumChatFormatting.DARK_AQUA,
                EnumChatFormatting.DARK_RED, EnumChatFormatting.DARK_PURPLE,
                EnumChatFormatting.GOLD, EnumChatFormatting.GRAY,
                EnumChatFormatting.DARK_GRAY, EnumChatFormatting.BLUE,
                EnumChatFormatting.GREEN, EnumChatFormatting.AQUA,
                EnumChatFormatting.RED, EnumChatFormatting.LIGHT_PURPLE,
                EnumChatFormatting.YELLOW, EnumChatFormatting.WHITE
        };
        final int[] palette = {
                0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
                0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
                0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        int targetRed = (rgb >> 16) & 0xFF;
        int targetGreen = (rgb >> 8) & 0xFF;
        int targetBlue = rgb & 0xFF;
        int closest = 0;
        long closestDistance = Long.MAX_VALUE;
        for (int index = 0; index < palette.length; index++) {
            int redDifference = targetRed - ((palette[index] >> 16) & 0xFF);
            int greenDifference = targetGreen - ((palette[index] >> 8) & 0xFF);
            int blueDifference = targetBlue - (palette[index] & 0xFF);
            long distance = (long) redDifference * redDifference
                    + (long) greenDifference * greenDifference
                    + (long) blueDifference * blueDifference;
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = index;
            }
        }
        return colors[closest];
    }

    private String displayedStatDelta(String key, String previousValue, String updatedValue) {
        boolean duration = "time_played".equals(key) || "playtime_per_game".equals(key);
        BigDecimal previous = duration
                ? parseDisplayedDurationSeconds(previousValue) : parseLeadingDisplayedNumber(previousValue);
        BigDecimal updated = duration
                ? parseDisplayedDurationSeconds(updatedValue) : parseLeadingDisplayedNumber(updatedValue);
        if (previous == null || updated == null) return null;

        BigDecimal delta = updated.subtract(previous);
        String amount;
        if (duration) {
            amount = formatColumnMetric(key, delta.abs().doubleValue());
        } else if (delta.stripTrailingZeros().scale() <= 0) {
            amount = NUMBER.format(delta.abs().longValue());
        } else {
            amount = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT))
                    .format(delta.abs());
        }
        return (delta.signum() < 0 ? "-" : "+") + amount;
    }

    private BigDecimal parseLeadingDisplayedNumber(String value) {
        if (value == null) return null;
        Matcher matcher = LEADING_DISPLAY_NUMBER.matcher(value);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal parseDisplayedDurationSeconds(String value) {
        if (value == null) return null;
        Matcher matcher = DISPLAY_DURATION.matcher(value);
        if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null
                && matcher.group(3) == null)) return null;
        try {
            BigDecimal seconds = BigDecimal.ZERO;
            if (matcher.group(1) != null) {
                seconds = seconds.add(new BigDecimal(matcher.group(1).replace(",", ""))
                        .multiply(BigDecimal.valueOf(3600L)));
            }
            if (matcher.group(2) != null) seconds = seconds.add(new BigDecimal(matcher.group(2))
                    .multiply(BigDecimal.valueOf(60L)));
            if (matcher.group(3) != null) seconds = seconds.add(new BigDecimal(matcher.group(3)));
            return seconds;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void rememberDisplayedStatChanges(GoStatsLeaderboardClient.Player previous,
                                              GoStatsLeaderboardClient.Player updated) {
        if (previous == null || updated == null || previous.uuid == null) return;
        String uuid = normalizeLeaderboardUuid(previous.uuid);
        if (uuid.isEmpty()) return;

        List<String> columns = overallDisplayColumns();
        boolean showColumns = !columns.isEmpty();
        if (!showColumns) columns = Collections.singletonList(currentMetric());
        List<DisplayedStatChange> changes = new ArrayList<DisplayedStatChange>();
        for (String key : columns) {
            String oldValue = displayedStatValue(previous, key, showColumns);
            String newValue = displayedStatValue(updated, key, showColumns);
            if (!oldValue.equals(newValue)) {
                String label = showColumns ? overallColumnLabel(key) : currentSortLabel();
                changes.add(new DisplayedStatChange(label, oldValue, newValue,
                        displayedStatDelta(key, oldValue, newValue), overallColumnColor(key)));
            }
        }
        displayedStatChangesByUuid.remove(uuid);
        if (!changes.isEmpty()) displayedStatChangesByUuid.put(uuid, changes);
        while (displayedStatChangesByUuid.size() > MAX_RETAINED_DISPLAYED_STAT_CHANGES) {
            String oldest = displayedStatChangesByUuid.keySet().iterator().next();
            displayedStatChangesByUuid.remove(oldest);
        }
    }

    private String displayedStatValue(GoStatsLeaderboardClient.Player player, String key,
                                      boolean showColumns) {
        return showColumns ? overallColumnValue(player, key) : formatMetric(player.metricValue);
    }

    private String formatLastUpdatedValue(String value) {
        Instant timestamp = parseTimestamp(value);
        if (timestamp == null) return value == null || value.trim().isEmpty() ? "never" : value;
        long seconds = Math.max(0L, Duration.between(timestamp, Instant.now()).getSeconds());
        String ago = seconds < 60 ? seconds + "s ago"
                : seconds < 3600 ? seconds / 60 + "m ago"
                : seconds < 86400 ? seconds / 3600 + "h ago" : seconds / 86400 + "d ago";
        return UPDATED_AT.withZone(ZoneId.systemDefault()).format(timestamp) + " (" + ago + ")";
    }

    private Instant parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty() || "never".equalsIgnoreCase(value)) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void refreshPlayer(final GoStatsLeaderboardClient.Player player) {
        refreshPlayer(player, null);
    }

    /**
     * A profile opened from this list also updates its source row. The exact child screen is
     * allowed to finish in the background so the status bubble and last-action line are ready
     * when the user returns to the leaderboard.
     */
    private void refreshPlayer(final GoStatsLeaderboardClient.Player player, final GuiScreen profileScreen) {
        if (player == null || player.uuid == null || player.uuid.trim().isEmpty()) return;
        if (refreshingUuids.contains(player.uuid)) {
            refreshStatus = "An update for " + player.username + " is already in progress.";
            layoutControls();
            return;
        }

        final String normalizedUuid = player.uuid.replace("-", "").toLowerCase(Locale.ROOT);
        final int generation = requestGeneration;
        final String requestedScope = currentScope();
        final String requestedMetric = currentMetric();
        final String requestedKit = appliedKit;
        long cooldownMs = HypixelPlayerRequests.cooldownRemainingMs(normalizedUuid);
        if (cooldownMs > 0L) {
            // Sync the row from a full profile already fetched elsewhere, without making another
            // Hypixel request or presenting that cached response as a new update.
            JsonObject cachedProfile = HypixelPlayerDataCache.getFreshByUuid(normalizedUuid);
            if (isUsableFullHypixelProfile(cachedProfile)) {
                applyProfileToRow(player, cachedProfile, requestedScope, requestedMetric, requestedKit);
            }
            refreshStatus = "Already updated. Try again in " + formatCooldown(cooldownMs) + ".";
            layoutControls();
            return;
        }

        refreshingUuids.add(player.uuid);
        manuallyRefreshedUuids.remove(normalizedUuid);
        refreshStatus = "Refreshing " + player.username + " from Hypixel...";
        layoutControls();
        Thread worker = new Thread(() -> {
            GoStatsLeaderboardClient.Player refreshed = null;
            GoStatsPlayerPresence.Presence presence = null;
            boolean usedRecentProfile = false;
            String error = null;
            try {
                JsonObject profile = GoStatsBlitzClient.fetchLeaderboardRefreshProfile(player.uuid);
                usedRecentProfile = HypixelPlayerRequests.isCached(profile);
                if (usedRecentProfile) {
                    // Another gomod feature won the race and already fetched this UUID. Reuse its
                    // current response, but do not submit or report a second refresh.
                    presence = GoStatsPlayerPresence.fromPlayerProfile(profile);
                    refreshed = GoStatsLeaderboardClient.fromFreshHypixelProfile(player, profile,
                            requestedScope, requestedMetric, requestedKit);
                    refreshed = GoStatsLeaderboardClient.withRankInfoFallback(refreshed, player);
                    refreshed = GoStatsLeaderboardClient.withPresence(refreshed, presence.status,
                            presence.lastAction, presence.lastLoginMs, presence.lastLogoutMs,
                            presence.lastActionSeconds, presence.lastGameType);
                    refreshed = GoStatsLeaderboardClient.withoutRefreshCacheLabel(refreshed);
                } else {
                    presence = GoStatsPlayerPresence.fromPlayerProfile(profile);
                    refreshed = GoStatsLeaderboardClient.fromFreshHypixelProfile(player, profile,
                            requestedScope, requestedMetric, requestedKit);
                    refreshed = GoStatsLeaderboardClient.withRankInfoFallback(refreshed, player);
                    refreshed = GoStatsLeaderboardClient.withPresence(refreshed, presence.status,
                            presence.lastAction, presence.lastLoginMs, presence.lastLogoutMs,
                            presence.lastActionSeconds, presence.lastGameType);
                    GoStatsContributions.queueLeaderboardRefresh(profile);
                }
            } catch (Exception failure) {
                error = failure.getMessage();
                if (error == null || error.trim().isEmpty()) error = "request failed";
            }
            final String finalError = error;
            final GoStatsLeaderboardClient.Player finalPlayer = refreshed;
            final boolean finalUsedRecentProfile = usedRecentProfile;
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null) return;
            minecraft.addScheduledTask(() -> {
                refreshingUuids.remove(player.uuid);
                boolean expectedScreen = minecraft.currentScreen == LeaderboardGUI.this
                        || profileScreen != null && minecraft.currentScreen == profileScreen;
                if (generation != requestGeneration || !expectedScreen
                        || !requestedScope.equals(currentScope()) || !requestedMetric.equals(currentMetric())
                        || !requestedKit.equals(appliedKit)) return;
                if (finalError != null) {
                    long remaining = HypixelPlayerRequests.cooldownRemainingMs(normalizedUuid);
                    refreshStatus = remaining > 0L
                            ? "Already updated. Try again in " + formatCooldown(remaining) + "."
                            : "Could not update " + player.username + ": " + finalError;
                    layoutControls();
                    return;
                }
                if (finalPlayer == null) return;
                int newIndex = replacePlayerRow(player, finalPlayer);
                if (newIndex < 0) return;
                long remaining = HypixelPlayerRequests.cooldownRemainingMs(normalizedUuid);
                refreshStatus = finalUsedRecentProfile
                        ? remaining > 0L
                                ? "Already updated. Try again in " + formatCooldown(remaining) + "."
                                : "Already updated; reused the recent player profile."
                        : "Updated " + player.username + " · #" + (newIndex + 1);
                layoutControls();
            });
        }, "gomod-gostats-player-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean isUsableFullHypixelProfile(JsonObject profile) {
        return profile != null
                && "Hypixel".equalsIgnoreCase(HypixelPlayerRequests.source(profile))
                && !HypixelPlayerRequests.blitzOnly(profile);
    }

    private void applyProfileToRow(GoStatsLeaderboardClient.Player previous, JsonObject profile,
                                   String scope, String metric, String kit) {
        if (previous == null || !isUsableFullHypixelProfile(profile)) return;
        try {
            GoStatsPlayerPresence.Presence presence = GoStatsPlayerPresence.fromPlayerProfile(profile);
            GoStatsLeaderboardClient.Player refreshed = GoStatsLeaderboardClient.fromFreshHypixelProfile(
                    previous, profile, scope, metric, kit);
            refreshed = GoStatsLeaderboardClient.withRankInfoFallback(refreshed, previous);
            refreshed = GoStatsLeaderboardClient.withPresence(refreshed, presence.status,
                    presence.lastAction, presence.lastLoginMs, presence.lastLogoutMs,
                    presence.lastActionSeconds, presence.lastGameType);
            refreshed = GoStatsLeaderboardClient.withoutRefreshCacheLabel(refreshed);
            replacePlayerRow(previous, refreshed);
        } catch (Exception ignored) { }
    }

    private int replacePlayerRow(GoStatsLeaderboardClient.Player previous,
                                 GoStatsLeaderboardClient.Player refreshed) {
        int index = indexOfPlayer(previous.uuid);
        if (index < 0) return -1;
        rememberDisplayedStatChanges(previous, refreshed);
        rememberRefreshedPresence(refreshed);
        int firstLoadedRank = players.isEmpty() ? 1 : players.get(0).rank;
        players.set(index, refreshed);
        players.sort((left, right) -> {
            int valueOrder = Double.compare(right.metricValue, left.metricValue);
            return valueOrder != 0 ? valueOrder : left.username.compareToIgnoreCase(right.username);
        });
        for (int i = 0; i < players.size(); i++)
            players.set(i, GoStatsLeaderboardClient.withRank(players.get(i), firstLoadedRank + i));
        int newIndex = indexOfPlayer(previous.uuid);
        if (newIndex >= 0 && newIndex < scrollOffset) scrollOffset = newIndex;
        else if (newIndex >= scrollOffset + visibleRows)
            scrollOffset = newIndex - visibleRows + 1;
        clampScroll();
        return newIndex;
    }

    private String formatCooldown(long milliseconds) {
        long seconds = Math.max(1L, (milliseconds + 999L) / 1000L);
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return remainingSeconds == 0L ? minutes + "m" : minutes + "m " + remainingSeconds + "s";
    }

    private int indexOfPlayer(String uuid) {
        if (uuid == null) return -1;
        for (int i = 0; i < players.size(); i++) {
            if (uuid.equalsIgnoreCase(players.get(i).uuid)) return i;
        }
        return -1;
    }

    private void rememberRefreshedPresence(GoStatsLeaderboardClient.Player player) {
        if (player == null || player.uuid == null) return;
        String uuid = normalizeLeaderboardUuid(player.uuid);
        if (uuid.isEmpty()) return;
        refreshedPresenceByUuid.remove(uuid);
        refreshedPresenceByUuid.put(uuid, new RefreshedPresence(player));
        manuallyRefreshedUuids.add(uuid);
        while (refreshedPresenceByUuid.size() > MAX_RETAINED_REFRESH_PRESENCE) {
            String oldest = refreshedPresenceByUuid.keySet().iterator().next();
            refreshedPresenceByUuid.remove(oldest);
            manuallyRefreshedUuids.remove(oldest);
        }
    }

    private GoStatsLeaderboardClient.Player restoreRetainedRefreshPresence(
            GoStatsLeaderboardClient.Player player) {
        if (player == null || player.uuid == null) return player;
        String uuid = normalizeLeaderboardUuid(player.uuid);
        RefreshedPresence presence = refreshedPresenceByUuid.get(uuid);
        if (presence == null || !manuallyRefreshedUuids.contains(uuid)) return player;
        return GoStatsLeaderboardClient.withPresence(player, presence.status, presence.action,
                presence.lastLoginMs, presence.lastLogoutMs, presence.lastActionSeconds,
                presence.lastGameType);
    }

    private static String normalizeLeaderboardUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    private static final class RefreshedPresence {
        private final String status;
        private final String action;
        private final long lastLoginMs;
        private final long lastLogoutMs;
        private final long lastActionSeconds;
        private final String lastGameType;

        private RefreshedPresence(GoStatsLeaderboardClient.Player player) {
            this.status = player.presenceStatus;
            this.action = player.lastAction;
            this.lastLoginMs = player.lastLoginMs;
            this.lastLogoutMs = player.lastLogoutMs;
            this.lastActionSeconds = player.lastActionSeconds;
            this.lastGameType = player.lastGameType;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (openSelectorId != -1 && selectorMenuContains(mouseX, mouseY)) {
            int optionCount = selectorOptions(openSelectorId).size();
            int columns = selectorColumnCount(openSelectorId);
            int optionRows = (optionCount + columns - 1) / columns;
            int step = wheel > 0 ? -3 : 3;
            selectorScrollOffset = Math.max(0, Math.min(optionRows - selectorVisibleRows,
                    selectorScrollOffset + step));
            return;
        }
        if (openSelectorId != -1) return;
        if (players.isEmpty()) {
            if (loadError != null && mouseX >= panelLeft && mouseX <= panelRight
                    && mouseY >= listTop && mouseY < listBottom) {
                int textWidth = loadErrorTextWidth > 0 ? loadErrorTextWidth
                        : Math.max(80, panelRight - panelLeft - 28);
                int lineCount = wrapText("Could not load the leaderboard: " + loadError,
                        textWidth).size();
                int maxScroll = Math.max(0, lineCount - loadErrorScrollVisibleLines(lineCount));
                loadErrorScrollOffset = Math.max(0, Math.min(maxScroll,
                        loadErrorScrollOffset + (wheel > 0 ? -2 : 2)));
            }
            return;
        }
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset + (wheel > 0 ? -3 : 3)));
        maybeLoadPrevious();
        maybeLoadMore();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (editingKitFilterValue && keyCode == Keyboard.KEY_ESCAPE) {
            cancelCustomFilterValueEntry();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && openSelectorId != -1) {
            closeSelector();
            return;
        }
        if (searchField != null && searchField.isFocused()) {
            if (suggestionsVisible && keyCode == Keyboard.KEY_DOWN) {
                selectedSuggestionIndex = Math.min(playerSuggestions.size() - 1,
                        selectedSuggestionIndex + 1);
                return;
            }
            if (suggestionsVisible && keyCode == Keyboard.KEY_UP) {
                selectedSuggestionIndex = selectedSuggestionIndex <= 0
                        ? playerSuggestions.size() - 1 : selectedSuggestionIndex - 1;
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                if (selectedSuggestionIndex >= 0) selectSuggestion(selectedSuggestionIndex);
                else applyFilters();
                return;
            }
        }
        boolean handled = false;
        if (searchField != null) {
            String before = searchField.getText();
            handled |= searchField.textboxKeyTyped(typedChar, keyCode);
            if (!before.equals(searchField.getText())) scheduleSearchSuggestions();
        }
        if (!handled) super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return searchField != null && searchField.isFocused();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (openSelectorId != -1) {
            if (mouseButton == 0 && selectorMenuContains(mouseX, mouseY)) {
                String action = filterMenuActionButtonAt(mouseX, mouseY);
                if (action != null) {
                    if (openSelectorId == ID_FILTERS) {
                        if ("Reset".equals(action)) resetPendingFilters();
                        else applyPendingFilters();
                    } else {
                        returnToFilterCategories();
                    }
                    return;
                }
                chooseSelectorOption(mouseX, mouseY);
                return;
            }
            GuiButton activeSelector = selectorButton(openSelectorId);
            if (mouseButton == 0 && activeSelector != null
                    && mouseX >= activeSelector.xPosition
                    && mouseX < activeSelector.xPosition + activeSelector.width
                    && mouseY >= activeSelector.yPosition
                    && mouseY < activeSelector.yPosition + activeSelector.height) {
                closeSelector();
            } else if (mouseButton == 0) {
                closeSelector();
            }
            return;
        }
        if (mouseButton == 0) {
            int suggestionIndex = suggestionIndexAt(mouseX, mouseY);
            if (suggestionIndex >= 0) {
                selectSuggestion(suggestionIndex);
                return;
            }
        }
        if (isSearchFieldHovered(mouseX, mouseY) && mouseButton == 0) {
            searchHintDismissed = true;
            searchHintVisible = false;
        } else {
            clearSuggestions();
        }
        int rowLeft = listContentRight > listContentLeft ? listContentLeft : panelLeft;
        int rowRight = listContentRight > listContentLeft ? listContentRight : panelRight - 8;
        if (mouseButton == 1 && !top350Refreshing && openSelectorId == -1 && mouseX >= rowLeft
                && mouseX <= rowRight && mouseY >= listTop && mouseY < listBottom) {
            int row = (mouseY - listTop) / leaderboardRowHeight() + scrollOffset;
            if (row >= scrollOffset && row < Math.min(players.size(), scrollOffset + visibleRows)) {
                refreshPlayer(players.get(row));
                return;
            }
        }
        if (searchField != null) searchField.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 && scrollbarRight > scrollbarLeft
                && mouseX >= scrollbarLeft - 4 && mouseX <= scrollbarRight + 4
                && mouseY >= scrollbarTrackTop && mouseY <= scrollbarTrackBottom) {
            if (mouseY >= scrollbarThumbTop && mouseY <= scrollbarThumbBottom) {
                draggingScrollbar = true;
                scrollbarGrabOffset = mouseY - scrollbarThumbTop;
            } else {
                setScrollFromScrollbar(mouseY - (scrollbarThumbBottom - scrollbarThumbTop) / 2);
            }
            maybeLoadMore();
            maybeLoadPrevious();
            return;
        }
        if (mouseButton == 0 && mouseX >= rowLeft && mouseX <= rowRight
                && mouseY >= listTop && mouseY <= listBottom) {
            int row = (mouseY - listTop) / leaderboardRowHeight() + scrollOffset;
            if (row >= scrollOffset && row < Math.min(players.size(), scrollOffset + visibleRows)) {
                GoStatsLeaderboardClient.Player selected = players.get(row);
                TeamPlayerProfileGUI profile = new TeamPlayerProfileGUI(this,
                        new TeamSpamManager.Player(selected.username, selected.uuid), null, false, true, selected);
                refreshPlayer(selected, profile);
                mc.displayGuiScreen(profile);
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        clearSuggestions();
        super.onGuiClosed();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && draggingScrollbar) {
            setScrollFromScrollbar(mouseY - scrollbarGrabOffset);
            maybeLoadPrevious();
            maybeLoadMore();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    private void setScrollFromScrollbar(int thumbTop) {
        int trackHeight = Math.max(1, scrollbarTrackBottom - scrollbarTrackTop);
        int thumbHeight = Math.max(1, scrollbarThumbBottom - scrollbarThumbTop);
        int travel = Math.max(0, trackHeight - thumbHeight);
        int relative = Math.max(0, Math.min(travel, thumbTop - scrollbarTrackTop));
        scrollOffset = travel == 0 ? 0 : Math.round(maxScroll() * relative / (float) travel);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) draggingScrollbar = false;
        super.mouseReleased(mouseX, mouseY, state);
    }
}
