package me.napim.gomod.gui;

import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRouteStorage;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

/**
 * After picking a map: view routes or delete the whole map entry.
 */
public class ChestRoutesMapActionsGUI extends GuiScreen {

    private final ChestRoutesMapsGUI mapsParent;
    private final String mapKey;

    public ChestRoutesMapActionsGUI(ChestRoutesMapsGUI mapsParent, String mapKey) {
        this.mapsParent = mapsParent;
        this.mapKey = mapKey;
    }

    @Override
    public void initGui() {
        super.initGui();
        ChestRouteStorage.load();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int w = 220;
        int h = 20;
        int y0 = sr.getScaledHeight() / 2 - 42;
        buttonList.add(new GuiButton(1, cx - w / 2, y0, w, h, EnumChatFormatting.AQUA + "View routes"));
        buttonList.add(new GuiButton(2, cx - w / 2, y0 + 26, w, h, EnumChatFormatting.RED + "Delete this map"));
        buttonList.add(new GuiButton(0, cx - w / 2, y0 + 58, w, h, EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.WHITE + prettifyMapName(mapKey);
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "Choose an action";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(mapsParent);
            return;
        }
        if (button.id == 1) {
            mc.displayGuiScreen(new ChestRoutesMapDetailGUI(mapsParent, mapKey));
            return;
        }
        if (button.id == 2) {
            if (ChestRouteStorage.deleteMap(mapKey)) {
                ChestRouteManager.reloadStorageAndRefreshGlow();
                mapsParent.refreshFromDisk();
                mc.displayGuiScreen(mapsParent);
            }
        }
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
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1).toLowerCase());
        }
        return out.length() == 0 ? slug : out.toString();
    }
}
