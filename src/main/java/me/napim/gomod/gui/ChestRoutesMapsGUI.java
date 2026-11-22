package me.napim.gomod.gui;

import me.napim.gomod.features.ChestRouteStorage;
import me.napim.gomod.features.MapQueuer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
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
    private GuiButton settingsButton;
    private GuiButton helpButton;
    private GuiButton[] mapButtons;
    private List<MapEntry> mapEntries = new ArrayList<>();
    private final GuiSmoothScroll scroll = new GuiSmoothScroll();

    private static final int COLS = 4;
    private static final int BTN_W = 100;
    private static final int BTN_H = 20;
    private static final int GAP = 5;
    private static final int CONTENT_TOP = 72;
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
        buttonList.clear();

        ChestRouteStorage.load();

        // Keep persisted ordering for any "extra" saved keys not present in MapQueuer.
        // Known MapQueuer maps are already inserted in their MapQueuer order.
        List<String> savedKeys = new ArrayList<>(ChestRouteStorage.listMapKeys());
        Set<String> savedKeySet = new java.util.HashSet<>(savedKeys);

        mapEntries.clear();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : MAPQUEUER_GUI_ORDER) {
                if (name == null || name.trim().isEmpty()) continue;
                String key = mapDisplayToKey(name);
                if (!seen.add(key)) continue;
                mapEntries.add(new MapEntry(key, name, savedKeySet.contains(key)));
            }
        for (String key : savedKeys) {
            if (seen.add(key)) {
                mapEntries.add(new MapEntry(key, prettifyMapName(key), true));
            }
        }

        int backY = GuiHeader.backButtonY(height, BTN_H);
        backButton = new GuiButton(0, width / 2 - 75, backY, 150, BTN_H,
                EnumChatFormatting.GRAY + "Back");
        int settingsY = GuiHeader.backButtonY(height, BTN_H) - 10 - BTN_H;
        settingsButton = new GuiButton(2, width / 2 - 75, settingsY, 150, BTN_H,
                EnumChatFormatting.AQUA + "Chest Routes Settings");
        helpButton = new GuiButton(3, width - 28, 8, 20, 20, "?");
        buttonList.add(backButton);
        buttonList.add(settingsButton);
        buttonList.add(helpButton);

        mapButtons = new GuiButton[mapEntries.size()];
        for (int i = 0; i < mapEntries.size(); i++) {
            MapEntry e = mapEntries.get(i);
            String label = colorPrefixForMap(e.displayName) + e.displayName;
            if (!e.hasRoute) {
                // Match MapQueuer-style disabled look: gray + strikethrough overlay.
                label = EnumChatFormatting.STRIKETHROUGH.toString() + EnumChatFormatting.GRAY + label;
            }
            mapButtons[i] = new GuiButton(BASE_ID + i, 0, 0, BTN_W, BTN_H,
                    label);
            buttonList.add(mapButtons[i]);
        }
        repositionMapButtons();
    }

    private void updateScrollBounds() {
        if (mapEntries.isEmpty()) {
            scroll.updateBounds(CONTENT_TOP, CONTENT_TOP, CONTENT_TOP,
                    GuiHeader.contentBottomAboveBackBar(height, BTN_H, 10));
            return;
        }
        int rows = (mapEntries.size() + COLS - 1) / COLS;
        int contentBottom = CONTENT_TOP + rows * (BTN_H + GAP);
        int visibleBottom = GuiHeader.contentBottomAboveBackBar(height, BTN_H, 10);
        scroll.updateBounds(CONTENT_TOP, contentBottom, CONTENT_TOP, visibleBottom);
    }

    private void repositionMapButtons() {
        int centerX = width / 2;
        int gridW = COLS * (BTN_W + GAP) - GAP;
        int left = centerX - gridW / 2;
        int scrollOff = scroll.getOffset();
        for (int i = 0; i < mapButtons.length; i++) {
            int row = i / COLS;
            int col = i % COLS;
            int x = left + col * (BTN_W + GAP);
            int y = CONTENT_TOP + row * (BTN_H + GAP) - scrollOff;
            mapButtons[i].xPosition = x;
            mapButtons[i].yPosition = y;
            mapButtons[i].visible = y >= CONTENT_TOP - BTN_H && y <= height - 38;
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
                EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Chest routes — maps"
                        + EnumChatFormatting.RESET,
                width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        if (mapEntries.isEmpty()) {
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                    EnumChatFormatting.GRAY + "No saved routes yet. Use /ce save …",
                    width, height / 2, 0xFFFFFF);
        }

        scroll.tick();
        updateScrollBounds();
        repositionMapButtons();
        scroll.drawScrollbar(width, mouseY);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 0, 2, 3);
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }
        if (settingsButton != null && settingsButton.visible) {
            settingsButton.drawButton(mc, mouseX, mouseY);
        }
        if (helpButton != null && helpButton.visible) {
            helpButton.drawButton(mc, mouseX, mouseY);
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
        if (Mouse.getEventDWheel() != 0) {
            repositionMapButtons();
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
        if (button.id >= BASE_ID && button.id < BASE_ID + mapEntries.size()) {
            int i = button.id - BASE_ID;
            MapEntry entry = mapEntries.get(i);
            if (entry.hasRoute) {
                mc.displayGuiScreen(new ChestRoutesMapDetailGUI(this, entry.key));
            }
        }
    }

    /** Reload keys after deleting a map from the child GUI. */
    public void refreshFromDisk() {
        initGui();
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
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
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
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String colorPrefixForMap(String mapName) {
        if (MapQueuer.isTeamsOnlyMap(mapName)) return EnumChatFormatting.RED.toString();
        if (MapQueuer.isSoloOnlyMap(mapName)) return EnumChatFormatting.BLUE.toString();
        return EnumChatFormatting.WHITE.toString();
    }

    private static class MapEntry {
        final String key;
        final String displayName;
        final boolean hasRoute;

        private MapEntry(String key, String displayName, boolean hasRoute) {
            this.key = key;
            this.displayName = displayName;
            this.hasRoute = hasRoute;
        }
    }

    private static class ChestRoutesHelpDialog extends GuiScreen {
        private final GuiScreen parent;
        private GuiButton closeButton;

        private ChestRoutesHelpDialog(GuiScreen parent) {
            this.parent = parent;
        }

        @Override
        public void initGui() {
            int cx = width / 2;
            closeButton = new GuiButton(0, cx - 60, height / 2 + 62, 120, 20, EnumChatFormatting.GRAY + "Close");
            buttonList.clear();
            buttonList.add(closeButton);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, width, height, 0x70000000);
            int cx = width / 2;
            int top = height / 2 - 64;
            GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, EnumChatFormatting.GOLD + "Chest Routes Help", cx, top, 0xFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "/ce on" + EnumChatFormatting.GRAY + " to edit the draft route", cx, top + 16, 0xFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "LMB checkpoint: tap delete, hold drag", cx, top + 28, 0xFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "While dragging: mouse wheel raises/lowers Y", cx, top + 40, 0xFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "RMB while sneaking adds checkpoints", cx, top + 52, 0xFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Use Settings for keybinds and undo", cx, top + 64, 0xFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
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
