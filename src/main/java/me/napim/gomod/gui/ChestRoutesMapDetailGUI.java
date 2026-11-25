package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRoutePalette;
import me.napim.gomod.features.ChestRouteStorage;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-map chest routes manager: route list with show/hide toggles,
 * main-route selection, and two-click delete confirmation.
 */
public class ChestRoutesMapDetailGUI extends GuiScreen {
    private static final int ROUTE_ROW_HEIGHT = 25;
    private static final int BUTTON_HEIGHT = 20;
    private static final int CONTENT_MAX_WIDTH = 415;
    private static final int TOOLBAR_GAP = 5;
    private static final int TOOLBAR_TO_LIST_GAP = 10;
    private static final int MIN_CONTENT_TOP = 56;
    private static final int BOTTOM_GAP_ABOVE_BACK = 14;
    private static final int ROW_BUTTON_GAP = 4;
    private static final long CONFIRM_TIMEOUT_MS = 4000L;

    private static final int BASE_TOGGLE_ID = 4000;
    private static final int BASE_MAIN_ID = 5000;
    private static final int BASE_DELETE_ID = 7000;

    private final GuiScreen parent;
    private final String mapKey;
    private GuiButton backButton;
    private GuiButton showAllButton;
    private GuiButton hideAllButton;
    private GuiButton refreshButton;
    private GuiButton deleteMapButton;

    private final GuiSmoothScroll scroll = new GuiSmoothScroll();
    private final List<RouteRow> rows = new ArrayList<>();
    private String effectiveMainPath = null;
    private int totalCheckpoints = 0;

    private int pendingDeleteIndex = -1;
    private long pendingDeleteExpiresMs = 0L;
    private boolean pendingDeleteMap = false;
    private long pendingDeleteMapExpiresMs = 0L;

    public ChestRoutesMapDetailGUI(GuiScreen parent, String mapKey) {
        this.parent = parent;
        this.mapKey = mapKey;
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuild(true);
    }

    private void rebuild(boolean resetScroll) {
        buttonList.clear();
        clearPendingConfirmations();

        ChestRouteStorage.load();
        rows.clear();
        List<String> paths = ChestRouteStorage.listPathNames(mapKey);
        effectiveMainPath = ChestRouteStorage.getEffectiveMainPath(mapKey);
        totalCheckpoints = 0;

        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            List<Vec3> pts = ChestRouteStorage.getPath(mapKey, p);
            int n = pts != null ? pts.size() : 0;
            totalCheckpoints += n;
            boolean isMain = p.equals(effectiveMainPath);
            boolean loaded = ChestRouteManager.isPathLoaded(p);
            int colorRgb = ChestRoutePalette.rgbForIndex(i);
            rows.add(new RouteRow(p, n, isMain, loaded, colorRgb));
        }

        int backY = GuiHeader.backButtonY(height, BUTTON_HEIGHT);
        backButton = new GuiButton(0, width / 2 - 75, backY, 150, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Maps");

        int colW = getColumnWidth();
        int toolbarBtnW = (colW - TOOLBAR_GAP * 3) / 4;
        showAllButton = new GuiButton(2, 0, 0, toolbarBtnW, BUTTON_HEIGHT, EnumChatFormatting.GREEN + "Show All");
        hideAllButton = new GuiButton(3, 0, 0, toolbarBtnW, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Hide All");
        refreshButton = new GuiButton(1, 0, 0, toolbarBtnW, BUTTON_HEIGHT, EnumChatFormatting.AQUA + "Refresh");
        deleteMapButton = new GuiButton(4, 0, 0, colW - (toolbarBtnW + TOOLBAR_GAP) * 3, BUTTON_HEIGHT,
                EnumChatFormatting.RED + "Delete Map");

        showAllButton.enabled = !rows.isEmpty();
        deleteMapButton.enabled = !rows.isEmpty();

        buttonList.add(backButton);
        buttonList.add(showAllButton);
        buttonList.add(hideAllButton);
        buttonList.add(refreshButton);
        buttonList.add(deleteMapButton);

        for (int i = 0; i < rows.size(); i++) {
            RouteRow row = rows.get(i);
            GuiButton delBtn = new GuiButton(BASE_DELETE_ID + i, 0, 0, 52, BUTTON_HEIGHT,
                    EnumChatFormatting.RED + "Del");
            GuiButton toggleBtn = new GuiButton(BASE_TOGGLE_ID + i, 0, 0, 58, BUTTON_HEIGHT,
                    row.loaded ? EnumChatFormatting.GREEN + "Shown" : EnumChatFormatting.RED + "Hidden");
            GuiButton mainBtn = new GuiButton(BASE_MAIN_ID + i, 0, 0, 58, BUTTON_HEIGHT,
                    row.isMain ? EnumChatFormatting.WHITE + "Main" : EnumChatFormatting.GRAY + "Set Main");
            mainBtn.enabled = !row.isMain;

            buttonList.add(delBtn);
            buttonList.add(toggleBtn);
            buttonList.add(mainBtn);
        }

        if (resetScroll) {
            scroll.reset();
        }
        updateScrollBounds();
        repositionAll();
    }

    private void clearPendingConfirmations() {
        pendingDeleteIndex = -1;
        pendingDeleteExpiresMs = 0L;
        pendingDeleteMap = false;
        pendingDeleteMapExpiresMs = 0L;
    }

    private int getColumnWidth() {
        return Math.min(CONTENT_MAX_WIDTH, Math.max(280, width - 36));
    }

    private int getColumnLeft() {
        return (width - getColumnWidth()) / 2;
    }

    private int getVisibleBottom() {
        return GuiHeader.contentBottomAboveBackBar(height, BUTTON_HEIGHT, BOTTOM_GAP_ABOVE_BACK);
    }

    private int getListContentHeight() {
        if (rows.isEmpty()) {
            return 56;
        }
        return rows.size() * ROUTE_ROW_HEIGHT;
    }

    private boolean needsListScroll() {
        int available = getVisibleBottom() - MIN_CONTENT_TOP;
        int totalBlock = BUTTON_HEIGHT + TOOLBAR_TO_LIST_GAP + getListContentHeight();
        return totalBlock > available;
    }

    private int getToolbarY() {
        if (needsListScroll()) {
            return MIN_CONTENT_TOP;
        }
        int available = getVisibleBottom() - MIN_CONTENT_TOP;
        int totalBlock = BUTTON_HEIGHT + TOOLBAR_TO_LIST_GAP + getListContentHeight();
        return MIN_CONTENT_TOP + (available - totalBlock) / 2;
    }

    private int getListViewportTop() {
        return getToolbarY() + BUTTON_HEIGHT + TOOLBAR_TO_LIST_GAP;
    }

    private void updateScrollBounds() {
        int listTop = getListViewportTop();
        int visibleBottom = getVisibleBottom();
        if (rows.isEmpty()) {
            scroll.updateBounds(listTop, listTop, listTop, visibleBottom);
            return;
        }
        int contentBottom = listTop + getListContentHeight();
        scroll.updateBounds(listTop, contentBottom, listTop, visibleBottom);
    }

    private void repositionAll() {
        int colLeft = getColumnLeft();
        int colW = getColumnWidth();
        int colRight = colLeft + colW;
        int toolbarY = getToolbarY();
        int toolbarBtnW = (colW - TOOLBAR_GAP * 3) / 4;

        if (showAllButton != null) {
            showAllButton.xPosition = colLeft;
            showAllButton.yPosition = toolbarY;
        }
        if (hideAllButton != null) {
            hideAllButton.xPosition = colLeft + toolbarBtnW + TOOLBAR_GAP;
            hideAllButton.yPosition = toolbarY;
        }
        if (refreshButton != null) {
            refreshButton.xPosition = colLeft + (toolbarBtnW + TOOLBAR_GAP) * 2;
            refreshButton.yPosition = toolbarY;
        }
        if (deleteMapButton != null) {
            deleteMapButton.xPosition = colLeft + (toolbarBtnW + TOOLBAR_GAP) * 3;
            deleteMapButton.yPosition = toolbarY;
            deleteMapButton.displayString = pendingDeleteMap
                    ? EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "Confirm?"
                    : EnumChatFormatting.RED + "Delete Map";
        }

        int listTop = getListViewportTop();
        int visibleBottom = getVisibleBottom();
        int scrollOff = scroll.getOffset();

        for (int i = 0; i < rows.size(); i++) {
            GuiButton del = findButton(BASE_DELETE_ID + i);
            GuiButton toggle = findButton(BASE_TOGGLE_ID + i);
            GuiButton main = findButton(BASE_MAIN_ID + i);
            if (del == null || toggle == null || main == null) {
                continue;
            }
            int rowY = listTop + i * ROUTE_ROW_HEIGHT - scrollOff;
            int buttonY = rowY + (ROUTE_ROW_HEIGHT - BUTTON_HEIGHT) / 2;
            boolean vis = buttonY >= listTop && buttonY + BUTTON_HEIGHT <= visibleBottom;

            del.visible = vis;
            del.enabled = vis;
            del.displayString = (pendingDeleteIndex == i)
                    ? EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "Confirm?"
                    : EnumChatFormatting.RED + "Del";

            toggle.visible = vis;
            toggle.enabled = vis;

            main.visible = vis;
            main.enabled = vis && !rows.get(i).isMain;

            if (vis) {
                del.xPosition = colRight - del.width;
                del.yPosition = buttonY;
                main.xPosition = del.xPosition - ROW_BUTTON_GAP - main.width;
                main.yPosition = buttonY;
                toggle.xPosition = main.xPosition - ROW_BUTTON_GAP - toggle.width;
                toggle.yPosition = buttonY;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.currentTimeMillis();
        if (pendingDeleteIndex >= 0 && now > pendingDeleteExpiresMs) {
            pendingDeleteIndex = -1;
        }
        if (pendingDeleteMap && now > pendingDeleteMapExpiresMs) {
            pendingDeleteMap = false;
        }

        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        String title = EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + prettifyMapName(mapKey);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        String subtitle = EnumChatFormatting.WHITE + "" + rows.size() + EnumChatFormatting.GRAY
                + (rows.size() == 1 ? " route" : " routes")
                + EnumChatFormatting.DARK_GRAY + "  |  "
                + EnumChatFormatting.WHITE + totalCheckpoints + EnumChatFormatting.GRAY + " checkpoints"
                + (effectiveMainPath != null
                        ? EnumChatFormatting.DARK_GRAY + "  |  " + EnumChatFormatting.GRAY + "Main: "
                                + EnumChatFormatting.WHITE + effectiveMainPath
                        : "");
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, subtitle, width, 36, 0xFFFFFF);

        scroll.tick();
        updateScrollBounds();
        repositionAll();

        int colLeft = getColumnLeft();
        int colW = getColumnWidth();
        int colRight = colLeft + colW;
        int listTop = getListViewportTop();
        int visibleBottom = getVisibleBottom();

        if (rows.isEmpty()) {
            int emptyY = listTop + 16;
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                    EnumChatFormatting.WHITE + "No saved routes for this map",
                    width, emptyY, 0xFFFFFF);
            GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                    EnumChatFormatting.GRAY + "Use /ce on in-game to record checkpoints, then /ce save",
                    width, emptyY + 14, 0xAAAAAA);
        } else {
            int scrollOff = scroll.getOffset();
            int maxTextWidth = Math.max(80, colW - 190);
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listTop + i * ROUTE_ROW_HEIGHT - scrollOff;
                int buttonY = rowY + (ROUTE_ROW_HEIGHT - BUTTON_HEIGHT) / 2;
                if (buttonY < listTop || buttonY + BUTTON_HEIGHT > visibleBottom) {
                    continue;
                }
                RouteRow row = rows.get(i);

                int swatchColor = 0xFF000000 | (row.colorRgb & 0xFFFFFF);
                Gui.drawRect(colLeft, buttonY + 4, colLeft + 3, buttonY + BUTTON_HEIGHT - 4, swatchColor);

                String prefix = row.isMain ? (EnumChatFormatting.WHITE + "\u2605 ") : (EnumChatFormatting.WHITE + "");
                String suffix = EnumChatFormatting.DARK_GRAY + "  (" + EnumChatFormatting.GRAY + row.checkpoints
                        + (row.checkpoints == 1 ? " checkpoint" : " checkpoints") + EnumChatFormatting.DARK_GRAY + ")";
                String displayName = trimToWidth(row.name,
                        Math.max(40, maxTextWidth - fontRendererObj.getStringWidth(suffix) - 10));
                int textY = buttonY + (BUTTON_HEIGHT - fontRendererObj.FONT_HEIGHT) / 2;
                fontRendererObj.drawStringWithShadow(prefix + displayName + suffix, colLeft + 8, textY, 0xFFFFFF);
            }
        }

        scroll.drawScrollbarAt(colRight + 8, listTop, visibleBottom, mouseY, 4, 0x50000000, 0xE8FFFFFF);

        scroll.drawFadedButtonsExcluding(mc, fontRendererObj, buttonList, mouseX, mouseY, 0, 1, 2, 3, 4);
        if (showAllButton != null && showAllButton.visible) {
            showAllButton.drawButton(mc, mouseX, mouseY);
        }
        if (hideAllButton != null && hideAllButton.visible) {
            hideAllButton.drawButton(mc, mouseX, mouseY);
        }
        if (refreshButton != null && refreshButton.visible) {
            refreshButton.drawButton(mc, mouseX, mouseY);
        }
        if (deleteMapButton != null && deleteMapButton.visible) {
            deleteMapButton.drawButton(mc, mouseX, mouseY);
        }
        if (backButton != null && backButton.visible) {
            backButton.drawButton(mc, mouseX, mouseY);
        }

        drawRowTooltips(mouseX, mouseY);
    }

    private void drawRowTooltips(int mouseX, int mouseY) {
        if (deleteMapButton != null && deleteMapButton.visible && deleteMapButton.enabled
                && isMouseOver(deleteMapButton, mouseX, mouseY)) {
            List<String> lines = new ArrayList<>();
            lines.add(EnumChatFormatting.RED + "Delete All Routes for " + prettifyMapName(mapKey));
            lines.add(EnumChatFormatting.GRAY + (pendingDeleteMap
                    ? "Click again to permanently remove this map"
                    : "Click twice to confirm"));
            renderTooltipOnTop(lines, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            GuiButton del = findButton(BASE_DELETE_ID + i);
            if (del != null && del.visible && isMouseOver(del, mouseX, mouseY)) {
                List<String> lines = new ArrayList<>();
                lines.add(EnumChatFormatting.RED + "Delete \"" + rows.get(i).name + "\"");
                lines.add(EnumChatFormatting.GRAY + (pendingDeleteIndex == i
                        ? "Click again to permanently delete"
                        : "Click twice to confirm"));
                renderTooltipOnTop(lines, mouseX, mouseY);
                return;
            }
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

    private String trimToWidth(String text, int maxWidth) {
        if (text == null || fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int target = Math.max(10, maxWidth - fontRendererObj.getStringWidth(ellipsis));
        return fontRendererObj.trimStringToWidth(text, target) + ellipsis;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        scroll.handleMouseWheel();
        if (Mouse.getEventDWheel() != 0) {
            repositionAll();
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
            if (parent instanceof ChestRoutesMapsGUI) {
                ((ChestRoutesMapsGUI) parent).refreshFromDisk();
            }
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            rebuild(false);
            return;
        }
        if (button.id == 2) {
            clearPendingConfirmations();
            String err = ChestRouteManager.loadAllPathsForMap(mapKey, ChestRouteManager.getCagesSpawnFeet());
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            }
            rebuild(false);
            return;
        }
        if (button.id == 3) {
            clearPendingConfirmations();
            ChestRouteManager.unloadRoute();
            rebuild(false);
            return;
        }
        if (button.id == 4) {
            long now = System.currentTimeMillis();
            if (!pendingDeleteMap || now > pendingDeleteMapExpiresMs) {
                clearPendingConfirmations();
                pendingDeleteMap = true;
                pendingDeleteMapExpiresMs = now + CONFIRM_TIMEOUT_MS;
                return;
            }
            if (ChestRouteStorage.deleteMap(mapKey)) {
                ChestRouteManager.reloadStorageAndRefreshGlow();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Deleted all routes for " + prettifyMapName(mapKey) + ".");
                if (parent instanceof ChestRoutesMapsGUI) {
                    ((ChestRoutesMapsGUI) parent).refreshFromDisk();
                }
                mc.displayGuiScreen(parent);
            }
            return;
        }
        if (button.id >= BASE_DELETE_ID && button.id < BASE_DELETE_ID + rows.size()) {
            int idx = button.id - BASE_DELETE_ID;
            String p = rows.get(idx).name;
            long now = System.currentTimeMillis();
            if (pendingDeleteIndex != idx || now > pendingDeleteExpiresMs) {
                clearPendingConfirmations();
                pendingDeleteIndex = idx;
                pendingDeleteExpiresMs = now + CONFIRM_TIMEOUT_MS;
                return;
            }
            String err = ChestRouteManager.deleteSavedPath(p, mapKey);
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Deleted route \"" + p + "\" for " + mapKey + ".");
            }
            rebuild(false);
            return;
        }
        if (button.id >= BASE_TOGGLE_ID && button.id < BASE_TOGGLE_ID + rows.size()) {
            clearPendingConfirmations();
            int idx = button.id - BASE_TOGGLE_ID;
            String p = rows.get(idx).name;
            if (ChestRouteManager.isPathLoaded(p)) {
                ChestRouteManager.unloadPath(p);
            } else {
                String err = ChestRouteManager.loadRouteCagesOrExplicit(ChestRouteManager.getCagesSpawnFeet(), mapKey, p);
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                }
            }
            rebuild(false);
            return;
        }
        if (button.id >= BASE_MAIN_ID && button.id < BASE_MAIN_ID + rows.size()) {
            clearPendingConfirmations();
            int idx = button.id - BASE_MAIN_ID;
            String p = rows.get(idx).name;
            String err = ChestRouteManager.setMainPathForMap(mapKey, p);
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Set main route for " + mapKey + ": " + p);
            }
            rebuild(false);
        }
    }

    private GuiButton findButton(int id) {
        for (Object o : buttonList) {
            if (o instanceof GuiButton && ((GuiButton) o).id == id) {
                return (GuiButton) o;
            }
        }
        return null;
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
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                out.append(p.substring(1).toLowerCase());
            }
        }
        return out.length() == 0 ? slug : out.toString();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class RouteRow {
        final String name;
        final int checkpoints;
        final boolean isMain;
        final boolean loaded;
        final int colorRgb;

        private RouteRow(String name, int checkpoints, boolean isMain, boolean loaded, int colorRgb) {
            this.name = name;
            this.checkpoints = checkpoints;
            this.isMain = isMain;
            this.loaded = loaded;
            this.colorRgb = colorRgb;
        }
    }
}
