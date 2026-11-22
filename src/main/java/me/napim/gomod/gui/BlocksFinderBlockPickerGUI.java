package me.napim.gomod.gui;



import me.napim.gomod.features.BlocksFinder;

import me.napim.gomod.features.ConfigManager;

import me.napim.gomod.util.HudGlState;

import me.napim.gomod.util.HudGuiItemIcon;

import net.minecraft.block.Block;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.Gui;

import net.minecraft.client.gui.GuiButton;

import net.minecraft.client.gui.GuiScreen;

import net.minecraft.client.gui.GuiTextField;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.init.Blocks;

import net.minecraft.item.Item;

import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import org.lwjgl.opengl.GL11;

import net.minecraft.util.EnumChatFormatting;

import net.minecraft.util.ResourceLocation;



import java.io.IOException;

import java.util.ArrayList;

import java.util.Collections;

import java.util.Comparator;

import java.util.HashMap;

import java.util.HashSet;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Set;



/**

 * Block search / picker: common shortcuts, searchable grid with icons, inline color controls on selected blocks.

 */

public class BlocksFinderBlockPickerGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {



    private static final int GRID_COLS = 3;

    private static final int GRID_CELL_H = 18;

    private static final int GRID_CELL_H_TRACKED = 22;

    private static final int HEADER_H = 12;

    private static final int ICON_SIZE = 16;

    private static final int BTN_H = 20;

    private static final int BTN_W = 120;

    private static final int PADDING = 6;

    private static final int PICKER_SIZE = 20;

    private static final int RESET_SIZE = 20;

    private static final int CELL_CONTROLS_W = PICKER_SIZE + RESET_SIZE + 4;

    private static final int ID_RESET_BASE = 3000;



    private enum RowKind { HEADER, BLOCK }



    private static final class ListRow {

        final RowKind kind;

        final String header;

        final String regName;

        final String displayName;

        final Block block;



        ListRow(String header) {

            this.kind = RowKind.HEADER;

            this.header = header;

            this.regName = null;

            this.displayName = null;

            this.block = null;

        }



        ListRow(String regName, String displayName, Block block) {

            this.kind = RowKind.BLOCK;

            this.header = null;

            this.regName = regName;

            this.displayName = displayName;

            this.block = block;

        }

    }



    private static final class TrackedColorRow {

        final String regName;

        final HsvColorPickerWidget picker = new HsvColorPickerWidget();

        final HudCompactResetButton resetButton;



        TrackedColorRow(String regName, int resetId) {

            this.regName = regName;

            this.resetButton = new HudCompactResetButton(resetId, 0, 0);

            picker.setExpandBelow(true);

            picker.setListener(new HsvColorPickerWidget.Listener() {

                @Override

                public void onColorChanged(int rgb) {

                    ConfigManager.setBlocksFinderBlockColor(regName, rgb);

                }



                @Override

                public void onColorChangeComplete(int rgb) {

                    ConfigManager.setBlocksFinderBlockColor(regName, rgb);

                    ConfigManager.saveConfig();

                }

            });

        }



        void syncFromConfig() {

            picker.syncFromRgb(ConfigManager.getBlocksFinderBlockColorRgb(regName));

        }

    }



    private final Map<String, String[]> blockCatalog = new HashMap<String, String[]>();

    private final Map<String, Block> blockById = new HashMap<String, Block>();

    private final List<ListRow> filteredRows = new ArrayList<ListRow>();

    private final Map<String, TrackedColorRow> colorRowsById = new LinkedHashMap<String, TrackedColorRow>();



    private GuiTextField searchField;

    private GuiButton clearTrackedButton;

    private GuiButton backButton;



    private int scrollPixels;

    private int listX;

    private int listY;

    private int listW;

    private int listH;



    private final BlocksFinderGUI parent;



    public BlocksFinderBlockPickerGUI(BlocksFinderGUI parent) {

        this.parent = parent;

    }



    @Override

    public void initGui() {

        super.initGui();

        HsvColorPickerWidget.resetIconCache();

        if (blockCatalog.isEmpty()) {

            buildBlockCatalog();

        }

        rebuildColorRows();



        ScaledResolution sr = new ScaledResolution(mc);

        int sw = sr.getScaledWidth();

        int sh = sr.getScaledHeight();



        int topReserve = 50;

        int bottomReserve = 36;

        listX = PADDING + 2;

        listW = sw - PADDING * 2 - 4;

        listY = topReserve + 4;

        listH = sh - listY - bottomReserve - 8;



        searchField = new GuiTextField(0, fontRendererObj, PADDING + 2, 32, sw - PADDING * 2 - 4, 16);

        searchField.setMaxStringLength(64);

        searchField.setFocused(true);

        searchField.setText("");



        int btnY = sh - bottomReserve + 6;

        clearTrackedButton = new GuiButton(1, PADDING, btnY, BTN_W, BTN_H, "Clear all tracked");

        backButton = new GuiButton(0, sw - PADDING - BTN_W, btnY, BTN_W, BTN_H, EnumChatFormatting.GRAY + "Back");

        buttonList.clear();

        buttonList.add(clearTrackedButton);

        buttonList.add(backButton);

        for (TrackedColorRow row : colorRowsById.values()) {

            buttonList.add(row.resetButton);

        }



        scrollPixels = 0;

        clampScroll();

        applyFilter("");

    }



    private void buildBlockCatalog() {

        blockCatalog.clear();

        blockById.clear();

        for (Object obj : Block.blockRegistry) {

            Block block = (Block) obj;

            if (block == null || block == Blocks.air) {

                continue;

            }

            Item item = Item.getItemFromBlock(block);

            if (item == null) {

                continue;

            }

            ResourceLocation name = (ResourceLocation) Block.blockRegistry.getNameForObject(block);

            if (name == null) {

                continue;

            }

            String regName = name.toString();

            String display = formatDisplayName(name.getResourcePath());

            blockCatalog.put(regName, new String[]{regName, display});

            blockById.put(regName, block);

        }

    }



    private void rebuildColorRows() {

        colorRowsById.clear();

        List<String> tracked = new ArrayList<String>(BlocksFinder.getTrackedIds());

        Collections.sort(tracked, new Comparator<String>() {

            @Override

            public int compare(String a, String b) {

                String[] ea = blockCatalog.get(a);

                String[] eb = blockCatalog.get(b);

                String da = ea != null ? ea[1] : a;

                String db = eb != null ? eb[1] : b;

                return da.compareToIgnoreCase(db);

            }

        });

        int resetId = ID_RESET_BASE;

        for (String regName : tracked) {

            TrackedColorRow row = new TrackedColorRow(regName, resetId++);

            row.syncFromConfig();

            colorRowsById.put(regName, row);

        }

    }



    private static String formatDisplayName(String path) {

        String[] words = path.replace('_', ' ').split(" ");

        StringBuilder sb = new StringBuilder();

        for (String w : words) {

            if (w.isEmpty()) {

                continue;

            }

            if (sb.length() > 0) {

                sb.append(' ');

            }

            sb.append(Character.toUpperCase(w.charAt(0)));

            sb.append(w.substring(1));

        }

        return sb.toString();

    }



    private static boolean isCommonBlock(String regName) {

        for (String id : BlocksFinder.COMMON_BLOCK_IDS) {

            if (id.equals(regName)) {

                return true;

            }

        }

        return false;

    }



    private static int cellHeightFor(String regName, Set<String> tracked) {

        return tracked.contains(regName) ? GRID_CELL_H_TRACKED : GRID_CELL_H;

    }

    /** Matches the block's color-picker / saved ESP color. */
    private static int blockNameTextColor(String regName, boolean isTracked) {
        if (isTracked || ConfigManager.hasBlocksFinderBlockColorOverride(regName)
                || ConfigManager.hasBlocksFinderBuiltinBlockColor(regName)) {
            return 0xFF000000 | ConfigManager.getBlocksFinderBlockColorRgb(regName);
        }
        return 0xCCCCCC;
    }

    private void layoutCellControls(TrackedColorRow row, int cellX, int cellY, int cellW, int cellH) {

        int resetX = cellX + cellW - RESET_SIZE - 2;

        int pickerX = resetX - PICKER_SIZE - 2;

        int ctrlY = cellY + (cellH - PICKER_SIZE) / 2;

        row.picker.setPosition(pickerX, ctrlY);

        row.resetButton.xPosition = resetX;

        row.resetButton.yPosition = cellY + (cellH - RESET_SIZE) / 2;

        row.resetButton.visible = true;

    }



    private void applyFilter(String query) {

        filteredRows.clear();

        String q = query.trim().toLowerCase();

        Set<String> tracked = BlocksFinder.getTrackedIds();

        Set<String> seen = new HashSet<String>();



        if (q.isEmpty()) {

            filteredRows.add(new ListRow(EnumChatFormatting.GOLD + "Common blocks"));

            for (String id : BlocksFinder.COMMON_BLOCK_IDS) {

                addBlockRowIfExists(id, seen);

            }

            if (!tracked.isEmpty()) {

                filteredRows.add(new ListRow(EnumChatFormatting.GREEN + "Selected"));

                List<String> sortedTracked = new ArrayList<String>(tracked);

                Collections.sort(sortedTracked, trackedNameComparator());

                for (String id : sortedTracked) {

                    addBlockRowIfExists(id, seen);

                }

            }

            filteredRows.add(new ListRow(EnumChatFormatting.GRAY + "All blocks"));

            List<String[]> rest = new ArrayList<String[]>(blockCatalog.values());

            Collections.sort(rest, nameComparator());

            for (String[] entry : rest) {

                if (!seen.contains(entry[0])) {

                    filteredRows.add(new ListRow(entry[0], entry[1], blockById.get(entry[0])));

                    seen.add(entry[0]);

                }

            }

        } else {

            List<String[]> matches = new ArrayList<String[]>();

            for (String[] entry : blockCatalog.values()) {

                if (entry[1].toLowerCase().contains(q) || entry[0].toLowerCase().contains(q)) {

                    matches.add(entry);

                }

            }

            Collections.sort(matches, nameComparator());



            List<String[]> commonMatches = new ArrayList<String[]>();

            List<String[]> trackedMatches = new ArrayList<String[]>();

            List<String[]> otherMatches = new ArrayList<String[]>();

            for (String[] entry : matches) {

                if (isCommonBlock(entry[0])) {

                    commonMatches.add(entry);

                } else if (tracked.contains(entry[0])) {

                    trackedMatches.add(entry);

                } else {

                    otherMatches.add(entry);

                }

            }

            if (!commonMatches.isEmpty()) {

                filteredRows.add(new ListRow(EnumChatFormatting.GOLD + "Common blocks"));

                for (String[] entry : commonMatches) {

                    filteredRows.add(new ListRow(entry[0], entry[1], blockById.get(entry[0])));

                }

            }

            if (!trackedMatches.isEmpty()) {

                filteredRows.add(new ListRow(EnumChatFormatting.GREEN + "Selected"));

                for (String[] entry : trackedMatches) {

                    filteredRows.add(new ListRow(entry[0], entry[1], blockById.get(entry[0])));

                }

            }

            if (!otherMatches.isEmpty()) {

                if (!commonMatches.isEmpty() || !trackedMatches.isEmpty()) {

                    filteredRows.add(new ListRow(EnumChatFormatting.GRAY + "Other matches"));

                }

                for (String[] entry : otherMatches) {

                    filteredRows.add(new ListRow(entry[0], entry[1], blockById.get(entry[0])));

                }

            }

        }



        clampScroll();

    }



    private int browseContentHeight() {

        Set<String> tracked = BlocksFinder.getTrackedIds();

        int h = 0;

        int col = 0;

        int rowMaxH = 0;
        for (ListRow row : filteredRows) {
            if (row.kind == RowKind.HEADER) {
                if (col > 0) {
                    h += rowMaxH;
                    col = 0;
                    rowMaxH = 0;
                }
                h += HEADER_H;
            } else {
                rowMaxH = Math.max(rowMaxH, cellHeightFor(row.regName, tracked));
                col++;
                if (col >= GRID_COLS) {
                    h += rowMaxH;
                    col = 0;
                    rowMaxH = 0;
                }
            }
        }
        if (col > 0) {
            h += rowMaxH;
        }
        return h;
    }



    private String truncateForCell(String name, int maxPx) {

        if (name == null) {

            return "";

        }

        if (fontRendererObj.getStringWidth(name) <= maxPx) {

            return name;

        }

        String ell = "...";

        int ellW = fontRendererObj.getStringWidth(ell);

        while (name.length() > 1 && fontRendererObj.getStringWidth(name) + ellW > maxPx) {

            name = name.substring(0, name.length() - 1);

        }

        return name + ell;

    }



    private int maxScrollPixels() {

        return Math.max(0, browseContentHeight() - listH);

    }



    private void clampScroll() {

        int max = maxScrollPixels();

        if (scrollPixels < 0) {

            scrollPixels = 0;

        } else if (scrollPixels > max) {

            scrollPixels = max;

        }

    }



    private void addBlockRowIfExists(String regName, Set<String> seen) {

        if (seen.contains(regName)) {

            return;

        }

        String[] entry = blockCatalog.get(regName);

        if (entry != null) {

            filteredRows.add(new ListRow(entry[0], entry[1], blockById.get(entry[0])));

            seen.add(regName);

        }

    }



    private static Comparator<String[]> nameComparator() {

        return new Comparator<String[]>() {

            @Override

            public int compare(String[] a, String[] b) {

                return a[1].compareToIgnoreCase(b[1]);

            }

        };

    }



    private Comparator<String> trackedNameComparator() {

        return new Comparator<String>() {

            @Override

            public int compare(String a, String b) {

                String[] ea = blockCatalog.get(a);

                String[] eb = blockCatalog.get(b);

                String da = ea != null ? ea[1] : a;

                String db = eb != null ? eb[1] : b;

                return da.compareToIgnoreCase(db);

            }

        };

    }



    private static final int GUI_BG_COLOR = 0x48000000;

    private static final int GUI_PANEL_COLOR = 0x90222222;

    private static final int GUI_PANEL_ALT_COLOR = 0x901A1A1A;



    private void drawTranslucentBackground() {

        ScaledResolution sr = new ScaledResolution(mc);

        int w = sr.getScaledWidth();

        int h = sr.getScaledHeight();

        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GlStateManager.disableDepth();

        drawRect(0, 0, w, h, GUI_BG_COLOR);

        GlStateManager.enableDepth();

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

    }



    @Override

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {

        drawTranslucentBackground();

        GuiHeader.drawTopLeft(this);



        ScaledResolution sr = new ScaledResolution(mc);

        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Block Finder \u2014 Target Blocks";

        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);



        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "Search:", PADDING + 2, 22, 0xFFFFFF);

        searchField.drawTextBox();



        drawRect(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, GUI_PANEL_COLOR);

        drawBrowseList(mouseX, mouseY);



        Set<String> tracked = BlocksFinder.getTrackedIds();

        String info = EnumChatFormatting.GRAY + String.valueOf(tracked.size()) + " tracked  \u2022  "

                + BlocksFinder.getEffectiveScanRange(mc) + " block scan radius";

        fontRendererObj.drawString(info, listX, listY + listH + 4, 0x888888);



        super.drawScreen(mouseX, mouseY, partialTicks);

        drawExpandedPickers(mouseX, mouseY, partialTicks);

        HudGlState.restoreForOverlayText();

    }



    private void drawExpandedPickers(int mouseX, int mouseY, float partialTicks) {

        HsvColorPickerWidget open = null;

        for (TrackedColorRow row : colorRowsById.values()) {

            if (row.picker.isOpenOrAnimating()) {

                open = row.picker;

                break;

            }

        }

        if (open == null) {

            return;

        }

        GlStateManager.pushMatrix();

        try {

            HudGlState.restoreForOverlayText();

            GlStateManager.disableDepth();

            GlStateManager.depthMask(false);

            for (TrackedColorRow row : colorRowsById.values()) {

                if (row.picker != open) {

                    row.picker.drawButton(mc, mouseX, mouseY);

                }

            }

            open.drawExpanded(mc, mouseX, mouseY, partialTicks);

            open.drawButton(mc, mouseX, mouseY);

        } finally {

            GlStateManager.depthMask(true);

            GlStateManager.enableDepth();

            GlStateManager.popMatrix();

        }

    }



    private void drawBrowseList(int mouseX, int mouseY) {

        Set<String> tracked = BlocksFinder.getTrackedIds();

        int listBottom = listY + listH;

        int contentH = browseContentHeight();

        boolean needsBar = contentH > listH;

        int rowW = needsBar ? listW - 6 : listW;

        int cellW = rowW / GRID_COLS;



        enableListScissor();

        int y = listY - scrollPixels;

        int gridCol = 0;

        int gridRowY = y;

        int gridRowH = GRID_CELL_H;



        HudGuiItemIcon.beginGuiScreenItemBatch(mc);

        try {

            for (int i = 0; i < filteredRows.size(); i++) {

                ListRow row = filteredRows.get(i);



                if (row.kind == RowKind.HEADER) {

                    if (gridCol > 0) {

                        y += gridRowH;

                        gridCol = 0;

                    }

                    if (y + HEADER_H <= listY) {

                        y += HEADER_H;

                        gridRowY = y;

                        continue;

                    }

                    if (y >= listBottom) {

                        break;

                    }

                    fontRendererObj.drawString(row.header, listX + 4, y + 2, 0xFFCCAA44);

                    y += HEADER_H;

                    gridRowY = y;

                    continue;

                }



                int cellH = cellHeightFor(row.regName, tracked);

                if (gridCol == 0) {

                    gridRowY = y;

                    gridRowH = cellH;

                } else {

                    gridRowH = Math.max(gridRowH, cellH);

                }



                if (gridRowY + gridRowH <= listY) {

                    gridCol++;

                    if (gridCol >= GRID_COLS) {

                        y += gridRowH;

                        gridCol = 0;

                    }

                    continue;

                }

                if (gridRowY >= listBottom) {

                    break;

                }



                int cellX = listX + gridCol * cellW;

                boolean isTracked = tracked.contains(row.regName);

                boolean hovered = mouseX >= cellX && mouseX < cellX + cellW

                        && mouseY >= gridRowY && mouseY < gridRowY + gridRowH;

                int bg = hovered ? 0x90333344 : GUI_PANEL_ALT_COLOR;

                if (isTracked) {

                    bg = hovered ? 0x902A4030 : 0x901E3028;

                }

                Gui.drawRect(cellX, gridRowY, cellX + cellW - 1, gridRowY + gridRowH, bg);



                int iconX = cellX + 2;

                int iconY = gridRowY + (gridRowH - ICON_SIZE) / 2;

                drawBlockIcon(row.block, iconX, iconY);



                int nameColor = blockNameTextColor(row.regName, isTracked);

                int textX = iconX + ICON_SIZE + 3;

                int textY = gridRowY + (gridRowH - 8) / 2;

                int reserved = isTracked ? CELL_CONTROLS_W + 12 : 8;

                String label = truncateForCell(row.displayName, cellW - ICON_SIZE - reserved);

                fontRendererObj.drawStringWithShadow(label, textX, textY, nameColor);

                if (isTracked) {

                    int checkX = textX + fontRendererObj.getStringWidth(label) + 2;

                    fontRendererObj.drawStringWithShadow("\u2714", checkX, textY, nameColor);



                    TrackedColorRow colorRow = colorRowsById.get(row.regName);

                    if (colorRow != null) {

                        layoutCellControls(colorRow, cellX, gridRowY, cellW, gridRowH);

                        if (!colorRow.picker.isOpenOrAnimating()) {

                            colorRow.picker.drawButton(mc, mouseX, mouseY);

                        }

                    }

                }



                gridCol++;

                if (gridCol >= GRID_COLS) {

                    y += gridRowH;

                    gridCol = 0;

                }

            }

            if (gridCol > 0) {

                y += gridRowH;

            }

        } finally {

            HudGuiItemIcon.endGuiScreenItemBatch(mc);

        }

        disableListScissor();



        if (needsBar) {

            int barX = listX + listW - 4;

            Gui.drawRect(barX, listY, barX + 4, listY + listH, 0xFF444444);

            float fraction = Math.min(1f, (float) listH / contentH);

            int trackH = Math.max(10, (int) (listH * fraction));

            int maxScroll = maxScrollPixels();

            int trackY = listY + (maxScroll <= 0 ? 0 : (int) ((float) scrollPixels / maxScroll * (listH - trackH)));

            Gui.drawRect(barX, trackY, barX + 4, trackY + trackH, 0xFFAAAAAA);

        }

    }



    private void enableListScissor() {

        ScaledResolution sr = new ScaledResolution(mc);

        int scale = sr.getScaleFactor();

        int sx = listX * scale;

        int sw = listW * scale;

        int sh = listH * scale;

        int sy = mc.displayHeight - (listY + listH) * scale;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);

        GL11.glScissor(sx, sy, sw, sh);

    }



    private void disableListScissor() {

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

    }



    private String findBlockAtBrowsePosition(int mouseX, int mouseY) {

        Set<String> tracked = BlocksFinder.getTrackedIds();

        boolean needsBar = browseContentHeight() > listH;

        int rowW = needsBar ? listW - 6 : listW;

        int cellW = rowW / GRID_COLS;

        int y = listY - scrollPixels;

        int gridCol = 0;

        int gridRowY = y;

        int gridRowH = GRID_CELL_H;



        for (ListRow row : filteredRows) {

            if (row.kind == RowKind.HEADER) {

                if (gridCol > 0) {

                    y += gridRowH;

                    gridCol = 0;

                }

                y += HEADER_H;

                gridRowY = y;

                continue;

            }

            int cellH = cellHeightFor(row.regName, tracked);

            if (gridCol == 0) {

                gridRowY = y;

                gridRowH = cellH;

            } else {

                gridRowH = Math.max(gridRowH, cellH);

            }

            int cellX = listX + gridCol * cellW;

            if (mouseY >= gridRowY && mouseY < gridRowY + gridRowH

                    && mouseX >= cellX && mouseX < cellX + cellW) {

                if (tracked.contains(row.regName) && mouseX >= cellX + cellW - CELL_CONTROLS_W) {

                    return null;

                }

                return row.regName;

            }

            gridCol++;

            if (gridCol >= GRID_COLS) {

                y += gridRowH;

                gridCol = 0;

            }

        }

        return null;

    }



    private void drawBlockIcon(Block block, int x, int y) {

        if (block == null || block == Blocks.air) {

            return;

        }

        Item item = Item.getItemFromBlock(block);

        if (item == null) {

            return;

        }

        try {

            HudGuiItemIcon.drawGuiScreenSlotInBatch(mc, new ItemStack(item), x, y);

        } catch (Throwable ignored) {

        }

    }



    @Override

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {

        for (TrackedColorRow row : colorRowsById.values()) {

            if (row.picker.mouseClicked(mc, mouseX, mouseY, mouseButton)) {

                collapseOtherPickers(row.picker);

                return;

            }

            if (row.resetButton.mousePressed(mc, mouseX, mouseY)) {

                return;

            }

        }



        if (mouseX >= listX && mouseX < listX + listW

                && mouseY >= listY && mouseY < listY + listH) {

            String hit = findBlockAtBrowsePosition(mouseX, mouseY);

            if (hit != null) {

                toggleBlock(hit);

                return;

            }

        }



        searchField.mouseClicked(mouseX, mouseY, mouseButton);

        super.mouseClicked(mouseX, mouseY, mouseButton);

    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        for (TrackedColorRow row : colorRowsById.values()) {
            row.picker.mouseClickMove(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        for (TrackedColorRow row : colorRowsById.values()) {
            row.picker.mouseReleased(mouseX, mouseY);
        }
    }

    private void collapseOtherPickers(HsvColorPickerWidget keep) {

        for (TrackedColorRow row : colorRowsById.values()) {

            if (row.picker != keep) {

                row.picker.collapse();

            }

        }

    }



    private void toggleBlock(String regName) {

        if (BlocksFinder.getTrackedIds().contains(regName)) {

            BlocksFinder.removeBlock(regName);

        } else {

            BlocksFinder.addBlock(regName);

        }

        onTrackedChanged();

    }



    private void onTrackedChanged() {

        rebuildColorRows();

        buttonList.clear();

        buttonList.add(clearTrackedButton);

        buttonList.add(backButton);

        for (TrackedColorRow row : colorRowsById.values()) {

            buttonList.add(row.resetButton);

        }

        applyFilter(searchField.getText());

    }



    @Override

    protected void actionPerformed(GuiButton button) {

        if (button.id >= ID_RESET_BASE && button.id < ID_RESET_BASE + 500) {

            int idx = button.id - ID_RESET_BASE;

            int i = 0;

            for (TrackedColorRow row : colorRowsById.values()) {

                if (i == idx) {

                    ConfigManager.resetBlocksFinderBlockColor(row.regName);

                    row.syncFromConfig();

                    break;

                }

                i++;

            }

            return;

        }

        switch (button.id) {

            case 0:

                if (parent != null) {

                    parent.refreshManageText();

                }

                mc.displayGuiScreen(parent);

                break;

            case 1:

                BlocksFinder.clearAllTracked();

                onTrackedChanged();

                break;

        }

    }



    @Override

    public void keyTyped(char c, int keyCode) throws IOException {

        if (keyCode == Keyboard.KEY_ESCAPE) {

            HsvColorPickerWidget open = findOpenPicker();

            if (open != null) {

                open.collapse();

                return;

            }

            if (parent != null) {

                parent.refreshManageText();

            }

            mc.displayGuiScreen(parent);

            return;

        }

        searchField.textboxKeyTyped(c, keyCode);

        scrollPixels = 0;

        applyFilter(searchField.getText());

    }



    private HsvColorPickerWidget findOpenPicker() {

        for (TrackedColorRow row : colorRowsById.values()) {

            if (row.picker.isOpenOrAnimating()) {

                return row.picker;

            }

        }

        return null;

    }



    @Override

    public void handleMouseInput() throws IOException {

        super.handleMouseInput();

        int wheel = GuiScreen.isShiftKeyDown() ? 0 : org.lwjgl.input.Mouse.getEventDWheel();

        if (wheel != 0) {

            int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;

            int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;

            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {

                int delta = wheel > 0 ? -14 : 14;

                scrollPixels = Math.max(0, Math.min(maxScrollPixels(), scrollPixels + delta));

            }

        }

    }



    @Override

    public boolean shouldBlockGomodMenuMovementAndDragLook() {

        return true;

    }



    @Override

    public boolean doesGuiPauseGame() {

        return false;

    }

}


