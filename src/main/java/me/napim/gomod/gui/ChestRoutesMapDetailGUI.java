package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRouteStorage;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-map summary: route names and checkpoint counts.
 */
public class ChestRoutesMapDetailGUI extends GuiScreen {
    private static final int ROUTE_ROW_HEIGHT = 20;

    private final GuiScreen parent;
    private final String mapKey;
    private GuiButton backButton;
    private GuiButton refreshButton;
    private int listScroll = 0;
    private static final int SCROLL_STEP = 14;
    private List<String> lines = new ArrayList<>();
    private List<String> paths = new ArrayList<>();
    private static final int BASE_TOGGLE_ID = 4000;
    private static final int BASE_MAIN_ID = 5000;
    private static final int BASE_DELETE_ID = 7000;
    private String effectiveMainPath = null;

    public ChestRoutesMapDetailGUI(GuiScreen parent, String mapKey) {
        this.parent = parent;
        this.mapKey = mapKey;
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuild();
    }

    private void rebuild() {
        buttonList.clear();
        backButton = new GuiButton(0, width / 2 - 75, height - 30, 150, 20, EnumChatFormatting.GRAY + "Back to maps");
        refreshButton = new GuiButton(1, width / 2 - 75, height - 52, 150, 20, EnumChatFormatting.AQUA + "Refresh");
        buttonList.add(backButton);
        buttonList.add(refreshButton);
        ChestRouteStorage.load();
        lines.clear();
        paths = ChestRouteStorage.listPathNames(mapKey);
        effectiveMainPath = ChestRouteStorage.getEffectiveMainPath(mapKey);
        int totalCp = 0;
        for (String p : paths) {
            List<Vec3> pts = ChestRouteStorage.getPath(mapKey, p);
            int n = pts != null ? pts.size() : 0;
            totalCp += n;
        }
        lines.add(EnumChatFormatting.GRAY + "Routes: " + EnumChatFormatting.WHITE + paths.size());
        lines.add(EnumChatFormatting.GRAY + "Total checkpoints (all routes): " + EnumChatFormatting.WHITE + totalCp);
        lines.add("");
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            List<Vec3> pts = ChestRouteStorage.getPath(mapKey, p);
            int n = pts != null ? pts.size() : 0;
            boolean isMain = p.equals(effectiveMainPath);
            lines.add((isMain ? EnumChatFormatting.GOLD + "★ " : EnumChatFormatting.YELLOW + "")
                    + p + EnumChatFormatting.GRAY + " — "
                    + EnumChatFormatting.WHITE + n + EnumChatFormatting.GRAY + " checkpoints");
            buttonList.add(new GuiButton(BASE_DELETE_ID + i, 0, 0, 44, 18,
                    EnumChatFormatting.RED + "Del"));
            buttonList.add(new GuiButton(BASE_TOGGLE_ID + i, 0, 0, 60, 18,
                    ChestRouteManager.isPathLoaded(p)
                            ? EnumChatFormatting.GREEN + "Shown"
                            : EnumChatFormatting.RED + "Hidden"));
            buttonList.add(new GuiButton(BASE_MAIN_ID + i, 0, 0, 56, 18,
                    isMain ? EnumChatFormatting.GOLD + "Main" : EnumChatFormatting.GRAY + "Set Main"));
        }
        listScroll = 0;
        repositionRowButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        String title = EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + prettifyMapName(mapKey);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        int y = 44;
        int lineH = ROUTE_ROW_HEIGHT;
        int maxY = height - 40;
        int idx = 0;
        for (String line : lines) {
            int drawY = y + idx * lineH - listScroll;
            if (drawY + lineH >= 40 && drawY <= maxY) {
                int textY = drawY + Math.max(0, (lineH - fontRendererObj.FONT_HEIGHT) / 2 - 1);
                fontRendererObj.drawString(line, 24, textY, 0xFFFFFF);
            }
            idx++;
        }

        int contentH = lines.size() * lineH;
        int viewH = maxY - 44;
        int maxListScroll = Math.max(0, contentH - viewH);
        if (listScroll > maxListScroll) {
            listScroll = maxListScroll;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (maxListScroll > 0) {
            String hint = EnumChatFormatting.DARK_GRAY + "Scroll for more";
            fontRendererObj.drawString(hint, width - fontRendererObj.getStringWidth(hint) - 8, height - 42, 0xFFFFFF);
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int lineH = ROUTE_ROW_HEIGHT;
        int contentH = lines.size() * lineH;
        int viewH = height - 40 - 44;
        int maxListScroll = Math.max(0, contentH - Math.max(20, viewH));
        int d = org.lwjgl.input.Mouse.getEventDWheel();
        if (d != 0 && maxListScroll > 0) {
            listScroll = Math.max(0, Math.min(maxListScroll, listScroll - Integer.signum(d) * SCROLL_STEP));
            repositionRowButtons();
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 1) {
            rebuild();
            return;
        }
        if (button.id >= BASE_DELETE_ID && button.id < BASE_DELETE_ID + paths.size()) {
            int idx = button.id - BASE_DELETE_ID;
            String p = paths.get(idx);
            String err = ChestRouteManager.deleteSavedPath(p, mapKey);
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Deleted route \"" + p + "\" for " + mapKey + ".");
            }
            rebuild();
            return;
        }
        if (button.id >= BASE_TOGGLE_ID && button.id < BASE_TOGGLE_ID + paths.size()) {
            int idx = button.id - BASE_TOGGLE_ID;
            String p = paths.get(idx);
            if (ChestRouteManager.isPathLoaded(p)) {
                ChestRouteManager.unloadPath(p);
            } else {
                String err = ChestRouteManager.loadRouteCagesOrExplicit(ChestRouteManager.getCagesSpawnFeet(), mapKey, p);
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                }
            }
            rebuild();
            return;
        }
        if (button.id >= BASE_MAIN_ID && button.id < BASE_MAIN_ID + paths.size()) {
            int idx = button.id - BASE_MAIN_ID;
            String p = paths.get(idx);
            String err = ChestRouteManager.setMainPathForMap(mapKey, p);
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Set main route for " + mapKey + ": " + p);
            }
            rebuild();
        }
    }

    private void repositionRowButtons() {
        int lineH = ROUTE_ROW_HEIGHT;
        int y = 44;
        int maxY = height - 40;
        for (int i = 0; i < paths.size(); i++) {
            GuiButton del = findButton(BASE_DELETE_ID + i);
            GuiButton toggle = findButton(BASE_TOGGLE_ID + i);
            GuiButton main = findButton(BASE_MAIN_ID + i);
            if (del == null || toggle == null || main == null) {
                continue;
            }
            int drawY = y + (3 + i) * lineH - listScroll;
            int buttonY = drawY + (lineH - del.height) / 2;
            boolean vis = buttonY + del.height >= 40 && buttonY <= maxY;
            del.visible = vis;
            del.enabled = vis;
            toggle.visible = vis;
            toggle.enabled = vis;
            main.visible = vis;
            main.enabled = vis;
            if (vis) {
                main.xPosition = width - 60;
                main.yPosition = buttonY;
                toggle.xPosition = width - 124;
                toggle.yPosition = buttonY;
                del.xPosition = width - 172;
                del.yPosition = buttonY;
            }
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
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1).toLowerCase());
        }
        return out.length() == 0 ? slug : out.toString();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
