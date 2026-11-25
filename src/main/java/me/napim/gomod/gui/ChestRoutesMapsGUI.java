package me.napim.gomod.gui;

import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRouteStorage;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MapQueuer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists saved chest-route maps (keys from {@code chest_routes.json}); opens per-map detail on click.
 */
public class ChestRoutesMapsGUI extends GuiScreen {

    private final GuiScreen parent;
    private GuiButton backButton;
    private GuiButton renderToggleButton;
    private GuiButton settingsButton;
    private GuiButton helpButton;
    private GuiButton[] mapButtons;
    private final List<MapEntry> mapEntries = new ArrayList<>();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    private int savedMapCount = 0;
    private int totalRouteCount = 0;

    private static final int COLS = 4;
    private static final int BTN_W = 100;
    private static final int BTN_H = 20;
    private static final int GAP = 5;
    private static final int GRID_WIDTH = COLS * (BTN_W + GAP) - GAP; // 415
    private static final int TOOLBAR_BTN_W = (GRID_WIDTH - GAP) / 2; // 205
    private static final int TOOLBAR_TO_GRID_GAP = 10;
    private static final int MIN_CONTENT_TOP = 56;
    private static final int HELP_GAP_ABOVE_BACK = 4;
    private static final int BOTTOM_GAP_ABOVE_BACK = 12;
    private static final int BASE_ID = 500;

    // Must match the visual ordering in `MapQueuerGUI` so the grid positions feel identical.
    private static final String[] MAPQUEUER_GUI_ORDER = {
            // Solo-only maps
            "Alexandria", "Aelin's Tower", "Bastion", "Caelum", "Caelum v2", "Citadel", "Enthorran", "Greece", "Hamani",
            "Mithril Revived", "Peaks", "Riverside", "Seafloor", "Shroom Valley", "Thorin", "Valley",
            // Both modes
            "Alice", "Cattle Drive", "City", "Despair v1", "Despair v2", "Docks v1", "Docks v2", "Egypt",
            "Gulch", "Impact", "KTulu Island", "Moonbase", "Pandora", "Persia", "Pixelville", "Proxima", "Ruins",
            "Shogun", "Winter", "Woodlands",
            // Teams-only maps
            "Darkstone", "Mirador Basin", "Stoneguard"
    };

    public ChestRoutesMapsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ChestRouteStorage.load();
        rebuildEntriesAndButtons();
    }

    private void rebuildEntriesAndButtons() {
        buttonList.clear();

        List<String> savedKeys = new ArrayList<>(ChestRouteStorage.listMapKeys());
        Set<String> savedKeySet = new java.util.HashSet<>(savedKeys);

        mapEntries.clear();
        savedMapCount = 0;
        totalRouteCount = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String name : MAPQUEUER_GUI_ORDER) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String key = mapDisplayToKey(name);
            if (!seen.add(key)) {
                continue;
            }
            MapEntry entry = buildMapEntry(key, name, savedKeySet.contains(key));
            mapEntries.add(entry);
            if (entry.hasRoute) {
                savedMapCount++;
                totalRouteCount += entry.routeCount;
            }
        }
        for (String key : savedKeys) {
            if (seen.add(key)) {
                MapEntry entry = buildMapEntry(key, prettifyMapName(key), true);
                mapEntries.add(entry);
                savedMapCount++;
                totalRouteCount += entry.routeCount;
            }
        }

        int backY = GuiHeader.backButtonY(height, BTN_H);
        int helpY = getHelpButtonY();
        backButton = new GuiButton(0, width / 2 - 75, backY, 150, BTN_H,
                EnumChatFormatting.GRAY + "Back");
        renderToggleButton = new GuiButton(5, 0, 0, TOOLBAR_BTN_W, BTN_H, getRenderToggleText());
        settingsButton = new GuiButton(2, 0, 0, GRID_WIDTH - TOOLBAR_BTN_W - GAP, BTN_H,
                EnumChatFormatting.AQUA + "Chest Routes Settings");
        helpButton = new GuiButton(3, width / 2 - 75, helpY, 150, BTN_H,
                EnumChatFormatting.YELLOW + "Help");

        buttonList.add(backButton);
        buttonList.add(renderToggleButton);
        buttonList.add(settingsButton);
        buttonList.add(helpButton);

        mapButtons = new GuiButton[mapEntries.size()];
        for (int i = 0; i < mapEntries.size(); i++) {
            MapEntry e = mapEntries.get(i);
            String label = colorPrefixForMap(e.displayName) + e.displayName;
            if (!e.hasRoute) {
                label = EnumChatFormatting.STRIKETHROUGH.toString() + EnumChatFormatting.GRAY + label;
            }
            mapButtons[i] = new GuiButton(BASE_ID + i, 0, 0, BTN_W, BTN_H, label);
            buttonList.add(mapButtons[i]);
        }

        scroll.reset();
        updateScrollBounds();
        repositionLayout();
    }

    private static MapEntry buildMapEntry(String key, String displayName, boolean hasRoute) {
        if (!hasRoute) {
            return new MapEntry(key, displayName, false, 0, 0, null, false);
        }
        List<String> paths = ChestRouteStorage.listPathNames(key);
        int totalCp = 0;
        boolean anyLoaded = false;
        for (String p : paths) {
            List<Vec3> pts = ChestRouteStorage.getPath(key, p);
            if (pts != null) {
                totalCp += pts.size();
            }
            if (ChestRouteManager.isPathLoaded(p)) {
                anyLoaded = true;
            }
        }
        String mainPath = ChestRouteStorage.getEffectiveMainPath(key);
        return new MapEntry(key, displayName, !paths.isEmpty(), paths.size(), totalCp, mainPath, anyLoaded);
    }

    private String getRenderToggleText() {
        return ConfigManager.isChestRoutesRenderEnabled()
                ? "Render: " + EnumChatFormatting.GREEN + "ON"
                : "Render: " + EnumChatFormatting.RED + "OFF";
    }

    private int getHelpButtonY() {
        return GuiHeader.backButtonY(height, BTN_H) - BTN_H - HELP_GAP_ABOVE_BACK;
    }

    private int getVisibleBottom() {
        return getHelpButtonY() - BOTTOM_GAP_ABOVE_BACK;
    }

    private int getGridHeight() {
        if (mapEntries.isEmpty()) {
            return 0;
        }
        int rows = (mapEntries.size() + COLS - 1) / COLS;
        return rows * BTN_H + (rows - 1) * GAP;
    }

    private boolean needsGridScroll() {
        int available = getVisibleBottom() - MIN_CONTENT_TOP;
        int totalBlock = BTN_H + TOOLBAR_TO_GRID_GAP + getGridHeight();
        return totalBlock > available;
    }

    private int getToolbarY() {
        if (needsGridScroll()) {
            return MIN_CONTENT_TOP;
        }
        int available = getVisibleBottom() - MIN_CONTENT_TOP;
        int totalBlock = BTN_H + TOOLBAR_TO_GRID_GAP + getGridHeight();
        return MIN_CONTENT_TOP + (available - totalBlock) / 2;
    }

    private int getGridViewportTop() {
        return getToolbarY() + BTN_H + TOOLBAR_TO_GRID_GAP;
    }

    private void updateScrollBounds() {
        int gridTop = getGridViewportTop();
        int visibleBottom = getVisibleBottom();
        if (mapEntries.isEmpty()) {
            scroll.updateBounds(gridTop, gridTop, gridTop, visibleBottom);
            return;
        }
        int contentBottom = gridTop + getGridHeight();
        scroll.updateBounds(gridTop, contentBottom, gridTop, visibleBottom);
    }

    private void repositionLayout() {
        int centerX = width / 2;
        int left = centerX - GRID_WIDTH / 2;
        int toolbarY = getToolbarY();

        if (renderToggleButton != null) {
            renderToggleButton.xPosition = left;
            renderToggleButton.yPosition = toolbarY;
        }
        if (settingsButton != null) {
            settingsButton.xPosition = left + TOOLBAR_BTN_W + GAP;
            settingsButton.yPosition = toolbarY;
        }

        int gridTop = getGridViewportTop();
        int visibleBottom = getVisibleBottom();
        int scrollOff = scroll.getOffset();
        for (int i = 0; i < mapButtons.length; i++) {
            int row = i / COLS;
            int col = i % COLS;
            int x = left + col * (BTN_W + GAP);
            int y = gridTop + row * (BTN_H + GAP) - scrollOff;
            mapButtons[i].xPosition = x;
            mapButtons[i].yPosition = y;
            mapButtons[i].visible = y >= gridTop && y + BTN_H <= visibleBottom;
            if (mapButtons[i].visible) {
                mapButtons[i].enabled = mapEntries.get(i).hasRoute;
            } else {
                mapButtons[i].enabled = false;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + "Chest Routes",
                width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        boolean renderOn = ConfigManager.isChestRoutesRenderEnabled();
        String summary = EnumChatFormatting.WHITE + "" + savedMapCount + EnumChatFormatting.GRAY
                + (savedMapCount == 1 ? " map" : " maps")
                + EnumChatFormatting.DARK_GRAY + "  |  "
                + EnumChatFormatting.WHITE + totalRouteCount + EnumChatFormatting.GRAY
                + (totalRouteCount == 1 ? " route" : " routes")
                + EnumChatFormatting.DARK_GRAY + "  |  "
                + EnumChatFormatting.GRAY + "Rendering "
                + (renderOn ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, summary, width, 36, 0xFFFFFF);

        scroll.tick();
        updateScrollBounds();
        repositionLayout();

        int left = width / 2 - GRID_WIDTH / 2;
        int gridTop = getGridViewportTop();
        int visibleBottom = getVisibleBottom();

        scroll.drawScrollbarAt(left + GRID_WIDTH + 8, gridTop, visibleBottom, mouseY, 4, 0x50000000, 0xE8FFFFFF);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 0, 2, 3, 5);
        if (renderToggleButton != null && renderToggleButton.visible) {
            renderToggleButton.drawButton(mc, mouseX, mouseY);
        }
        if (settingsButton != null && settingsButton.visible) {
            settingsButton.drawButton(mc, mouseX, mouseY);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        if (helpButton != null && helpButton.visible) {
            helpButton.drawButton(mc, mouseX, mouseY);
        }

        drawHoverTooltips(mouseX, mouseY);
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        for (int i = 0; i < mapButtons.length; i++) {
            GuiButton btn = mapButtons[i];
            if (btn == null || !btn.visible || !isMouseOver(btn, mouseX, mouseY)) {
                continue;
            }
            MapEntry e = mapEntries.get(i);
            List<String> lines = new ArrayList<>();
            lines.add(colorPrefixForMap(e.displayName) + "" + EnumChatFormatting.BOLD + e.displayName);
            if (e.hasRoute) {
                lines.add(EnumChatFormatting.GRAY + "Routes: " + EnumChatFormatting.WHITE + e.routeCount
                        + EnumChatFormatting.DARK_GRAY + "  |  "
                        + EnumChatFormatting.GRAY + "Checkpoints: " + EnumChatFormatting.WHITE + e.totalCheckpoints);
                if (e.mainPath != null) {
                    lines.add(EnumChatFormatting.GRAY + "Main: " + EnumChatFormatting.WHITE + e.mainPath);
                }
                if (e.anyLoaded) {
                    lines.add(EnumChatFormatting.GREEN + "Currently shown in world");
                }
                lines.add(EnumChatFormatting.DARK_GRAY + "Click to manage routes");
            } else {
                lines.add(EnumChatFormatting.GRAY + "No saved routes yet");
                lines.add(EnumChatFormatting.DARK_GRAY + "Use /ce on in-game to record one");
            }
            renderTooltipOnTop(lines, mouseX, mouseY);
            return;
        }
    }

    private void renderTooltipOnTop(List<String> lines, int mouseX, int mouseY) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 400);
        drawHoveringText(lines, mouseX, mouseY);
        GlStateManager.popMatrix();
    }

    private static boolean isMouseOver(GuiButton btn, int mouseX, int mouseY) {
        return mouseX >= btn.xPosition && mouseX < btn.xPosition + btn.width
                && mouseY >= btn.yPosition && mouseY < btn.yPosition + btn.height;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
        if (Mouse.getEventDWheel() != 0) {
            repositionLayout();
        }
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
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 2) {
            mc.displayGuiScreen(new ChestRoutesSettingsGUI(this));
            return;
        }
        if (button.id == 3) {
            mc.displayGuiScreen(new ChestRoutesHelpDialog(this));
            return;
        }
        if (button.id == 5) {
            ConfigManager.setChestRoutesRenderEnabled(!ConfigManager.isChestRoutesRenderEnabled());
            ChestRouteManager.reloadStorageAndRefreshGlow();
            renderToggleButton.displayString = getRenderToggleText();
            return;
        }
        if (button.id >= BASE_ID && button.id < BASE_ID + mapEntries.size()) {
            int i = button.id - BASE_ID;
            MapEntry entry = mapEntries.get(i);
            if (entry.hasRoute) {
                mc.displayGuiScreen(new ChestRoutesMapDetailGUI(this, entry.key));
            }
        }
    }

    /** Reload keys after deleting a map or route from a child GUI. */
    public void refreshFromDisk() {
        ChestRouteStorage.load();
        rebuildEntriesAndButtons();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String prettifyMapName(String slug) {
        if (slug == null || slug.isEmpty()) {
            return "Unknown";
        }
        String[] parts = slug.split("_+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if (p.length() == 1) {
                out.append(Character.toUpperCase(p.charAt(0)));
            } else {
                out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
            }
        }
        return out.length() == 0 ? slug : out.toString();
    }

    private static String mapDisplayToKey(String mapName) {
        String lower = mapName == null ? "" : mapName.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean prevUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alnum) {
                sb.append(c);
                prevUnderscore = false;
            } else if (!prevUnderscore) {
                sb.append('_');
                prevUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) {
            out = out.substring(1);
        }
        while (out.endsWith("_")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String colorPrefixForMap(String mapName) {
        if (MapQueuer.isTeamsOnlyMap(mapName)) {
            return EnumChatFormatting.RED.toString();
        }
        if (MapQueuer.isSoloOnlyMap(mapName)) {
            return EnumChatFormatting.BLUE.toString();
        }
        return EnumChatFormatting.WHITE.toString();
    }

    private static class MapEntry {
        final String key;
        final String displayName;
        final boolean hasRoute;
        final int routeCount;
        final int totalCheckpoints;
        final String mainPath;
        final boolean anyLoaded;

        private MapEntry(String key, String displayName, boolean hasRoute,
                         int routeCount, int totalCheckpoints, String mainPath, boolean anyLoaded) {
            this.key = key;
            this.displayName = displayName;
            this.hasRoute = hasRoute;
            this.routeCount = routeCount;
            this.totalCheckpoints = totalCheckpoints;
            this.mainPath = mainPath;
            this.anyLoaded = anyLoaded;
        }
    }

    private static class ChestRoutesHelpDialog extends GuiScreen {
        private static final int DIALOG_WIDTH = 380;
        private static final int DIALOG_PAD_X = 14;
        private static final int LINE_H = 11;
        private static final int BORDER_COLOR = 0xFF4CAF50;

        private final GuiScreen parent;
        private GuiButton closeButton;
        private List<String> bodyLines;
        private int dialogHeight;
        private int dialogY;

        private ChestRoutesHelpDialog(GuiScreen parent) {
            this.parent = parent;
        }

        private int dialogLeft() {
            return (width - DIALOG_WIDTH) / 2;
        }

        @Override
        public void initGui() {
            int innerW = DIALOG_WIDTH - DIALOG_PAD_X * 2;
            bodyLines = buildBodyLines(fontRendererObj, innerW);
            int textHeight = 0;
            for (String line : bodyLines) {
                textHeight += line.isEmpty() ? (LINE_H / 2) : LINE_H;
            }
            dialogHeight = 28 + textHeight + 34;
            int cx = width / 2;
            dialogY = (height - dialogHeight) / 2;
            closeButton = new GuiButton(0, cx - 50, dialogY + dialogHeight - 26, 100, 20,
                    EnumChatFormatting.GRAY + "Close");
            buttonList.clear();
            buttonList.add(closeButton);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawDefaultBackground();
            int left = dialogLeft();
            int top = dialogY;
            int right = left + DIALOG_WIDTH;
            int bottom = top + dialogHeight;
            int cx = width / 2;

            Gui.drawRect(left, top, right, bottom, 0xCC000000);
            Gui.drawRect(left, top, right, top + 1, BORDER_COLOR);
            Gui.drawRect(left, bottom - 1, right, bottom, BORDER_COLOR);
            Gui.drawRect(left, top, left + 1, bottom, BORDER_COLOR);
            Gui.drawRect(right - 1, top, right, bottom, BORDER_COLOR);

            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                    EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Chest Routes Help",
                    cx, top + 10, 0xFFFFFF);

            int textX = left + DIALOG_PAD_X;
            int y = top + 28;
            if (bodyLines != null) {
                for (String line : bodyLines) {
                    if (line.isEmpty()) {
                        y += LINE_H / 2;
                    } else {
                        fontRendererObj.drawString(line, textX, y, 0xFFFFFF);
                        y += LINE_H;
                    }
                }
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        private static List<String> buildBodyLines(FontRenderer fr, int maxPx) {
            String w = EnumChatFormatting.WHITE.toString();
            String g = EnumChatFormatting.GRAY.toString();
            String a = EnumChatFormatting.AQUA.toString();
            String y = EnumChatFormatting.YELLOW.toString();

            List<String> out = new ArrayList<>();
            out.addAll(wrapText(fr, w
                    + "Chest Routes lets you record, save, and display glowing 3D chest-route trails on Blitz "
                    + "Survival Games maps so you can follow your favorite chest paths off spawn.", maxPx));
            out.add("");
            out.addAll(wrapText(fr, w
                    + "Routes are saved per map and can automatically load when cages open, starting from your "
                    + "cage spawn position. Click any map with saved routes to show/hide individual trails, "
                    + "choose a Main route, or delete routes.", maxPx));
            out.add("");
            out.addAll(wrapText(fr, y + "Controls & Commands:", maxPx));
            out.addAll(wrapText(fr, a + "/ce on" + w + " / " + a + "/ce save <name>" + g
                    + " - Start the checkpoint editor and save your draft route.", maxPx));
            out.addAll(wrapText(fr, a + "RMB on block" + g
                    + " - Place a checkpoint on the block you are looking at.", maxPx));
            out.addAll(wrapText(fr, a + "LMB on checkpoint" + g
                    + " - Tap to delete, or hold to drag (scroll wheel adjusts height).", maxPx));
            out.addAll(wrapText(fr, a + "/cro load" + w + " / " + a + "unload" + w + " / " + a + "autoload on|off" + g
                    + " - Show, hide, or auto-load routes at game start.", maxPx));
            out.addAll(wrapText(fr, a + "Chest Routes Settings" + g
                    + " - Configure render toggle and editor/view keybinds.", maxPx));
            return out;
        }

        private static List<String> wrapText(FontRenderer fr, String body, int maxPx) {
            if (body == null || body.isEmpty()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(fr.listFormattedStringToWidth(body, maxPx));
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
            int left = dialogLeft();
            int top = dialogY;
            if (mouseX < left || mouseX > left + DIALOG_WIDTH || mouseY < top || mouseY > top + dialogHeight) {
                mc.displayGuiScreen(parent);
                return;
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(parent);
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button.id == 0) {
                mc.displayGuiScreen(parent);
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
