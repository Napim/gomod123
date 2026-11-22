package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.PlayerTracers;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
import java.util.List;

public class CageSettingsGUI extends GuiScreen {
    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 26;

    private final GuiScreen parent;

    private GuiButton cageTracersButton;
    private GuiButton chestOverlayButton;
    private GuiButton tracerNametagButton;
    private GuiButton cageOpeningSoundButton;
    private GuiButton gameTeleporterSoundButton;
    private GuiButton blitzHourSfxButton;
    private GuiButton cageBarrierBlocksButton;
    private GuiButton middleLootEstimateButton;
    private GuiButton backButton;

    public CageSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(this.mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int titleBottom = 52;
        int marginAboveBack = 12;
        boolean showMiddleLootEstimate = GomodInternalFeatures.isEnabled();
        int backRow = showMiddleLootEstimate ? 8 : 7;
        int stackH = BUTTON_HEIGHT + backRow * ROW;
        int y = titleBottom + Math.max(8, (backY - marginAboveBack - titleBottom - stackH) / 2);

        cageTracersButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT, getCageTracersText());
        tracerNametagButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, y + ROW, BUTTON_WIDTH, BUTTON_HEIGHT, getTracerNametagText());
        chestOverlayButton = new GuiButton(2, cx - BUTTON_WIDTH / 2, y + ROW * 2, BUTTON_WIDTH, BUTTON_HEIGHT, getChestOverlayText());
        cageOpeningSoundButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, y + ROW * 3, BUTTON_WIDTH, BUTTON_HEIGHT, getCageOpeningSoundText());
        gameTeleporterSoundButton = new GuiButton(8, cx - BUTTON_WIDTH / 2, y + ROW * 4, BUTTON_WIDTH, BUTTON_HEIGHT, getGameTeleporterSoundText());
        blitzHourSfxButton = new GuiButton(5, cx - BUTTON_WIDTH / 2, y + ROW * 5, BUTTON_WIDTH, BUTTON_HEIGHT, getBlitzHourCageSfxText());
        cageBarrierBlocksButton = new GuiButton(6, cx - BUTTON_WIDTH / 2, y + ROW * 6, BUTTON_WIDTH, BUTTON_HEIGHT, getCageBarrierBlocksText());
        if (showMiddleLootEstimate) {
            middleLootEstimateButton = new GuiButton(7, cx - BUTTON_WIDTH / 2, y + ROW * 7,
                    BUTTON_WIDTH, BUTTON_HEIGHT, getMiddleLootEstimateText());
        }
        backButton = new GuiButton(0, cx - BUTTON_WIDTH / 2, y + ROW * backRow,
                BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back");

        buttonList.add(cageTracersButton);
        buttonList.add(tracerNametagButton);
        buttonList.add(chestOverlayButton);
        buttonList.add(cageOpeningSoundButton);
        buttonList.add(gameTeleporterSoundButton);
        buttonList.add(blitzHourSfxButton);
        buttonList.add(cageBarrierBlocksButton);
        if (middleLootEstimateButton != null) {
            buttonList.add(middleLootEstimateButton);
        }
        buttonList.add(backButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                break;
            case 1:
                PlayerTracers.toggleCagesAuto();
                ConfigManager.setPtracersCagesEnabled(PlayerTracers.isCagesAutoEnabled());
                cageTracersButton.displayString = getCageTracersText();
                break;
            case 4:
                ConfigManager.setCageOpeningSoundEnabled(!ConfigManager.isCageOpeningSoundEnabled());
                cageOpeningSoundButton.displayString = getCageOpeningSoundText();
                break;
            case 5:
                ConfigManager.setBlitzHourCageSfxEnabled(!ConfigManager.isBlitzHourCageSfxEnabled());
                blitzHourSfxButton.displayString = getBlitzHourCageSfxText();
                break;
            case 8:
                ConfigManager.setGameTeleporterSoundEnabled(!ConfigManager.isGameTeleporterSoundEnabled());
                gameTeleporterSoundButton.displayString = getGameTeleporterSoundText();
                break;
            case 2:
                ConfigManager.setCageChestOverlayEnabled(!ConfigManager.isCageChestOverlayEnabled());
                chestOverlayButton.displayString = getChestOverlayText();
                break;
            case 3:
                ConfigManager.setPtracersTargetNametagEnabled(!ConfigManager.isPtracersTargetNametagEnabled());
                tracerNametagButton.displayString = getTracerNametagText();
                break;
            case 6:
                BarrierVisibility.setBarrierBlocksShownInCagesWithRendererReload(
                        !ConfigManager.isBarrierBlocksShownInCages());
                cageBarrierBlocksButton.displayString = getCageBarrierBlocksText();
                break;
            case 7:
                if (!GomodInternalFeatures.isEnabled()) {
                    return;
                }
                ConfigManager.setCageMiddleLootEstimateEnabled(!ConfigManager.isCageMiddleLootEstimateEnabled());
                middleLootEstimateButton.displayString = getMiddleLootEstimateText();
                break;
            default:
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(this.mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Cage features"
                + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltip(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, CageSettingsGUI.class);
    }

    private String getCageTracersText() {
        return "Cage tracers: "
                + (PlayerTracers.isCagesAutoEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getChestOverlayText() {
        return "Chest overlays: "
                + (ConfigManager.isCageChestOverlayEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getTracerNametagText() {
        return "Tracer target nametag: "
                + (ConfigManager.isPtracersTargetNametagEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getCageOpeningSoundText() {
        return "Cages opening sound: "
                + (ConfigManager.isCageOpeningSoundEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getBlitzHourCageSfxText() {
        return "BLITZ HOUR cage SFX: "
                + (ConfigManager.isBlitzHourCageSfxEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getGameTeleporterSoundText() {
        return "Game teleporter sound: "
                + (ConfigManager.isGameTeleporterSoundEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getCageBarrierBlocksText() {
        return "Barrier blocks: "
                + (ConfigManager.isBarrierBlocksShownInCages()
                ? EnumChatFormatting.GREEN + "Shown"
                : EnumChatFormatting.RED + "Hidden");
    }

    private String getMiddleLootEstimateText() {
        if (!GomodInternalFeatures.isEnabled()) {
            return "Middle loot estimates: " + EnumChatFormatting.DARK_GRAY + "Not ready";
        }
        return "Middle loot estimates: "
                + (ConfigManager.isCageMiddleLootEstimateEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (isMouseOver(cageTracersButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Cage tracers",
                    EnumChatFormatting.GRAY + "Shows where players are aiming during cages and briefly after opening",
                    EnumChatFormatting.DARK_GRAY + "Performance capture: /cagetracerdebug start");
            return;
        }
        if (isMouseOver(cageOpeningSoundButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Cages opening sound",
                    EnumChatFormatting.GRAY + "Toggle the enderdragon growl/BLITZ HOUR SFX when cages open");
            return;
        }
        if (isMouseOver(blitzHourSfxButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "BLITZ HOUR cage SFX",
                    EnumChatFormatting.GRAY + "Plays an alternative cage opening sound when it's a BLITZ HOUR game");
            return;
        }
        if (isMouseOver(gameTeleporterSoundButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Game teleporter sound",
                    EnumChatFormatting.GRAY + "Plays a sound effect when you get teleported from pregame to the cages");
            return;
        }
        if (isMouseOver(chestOverlayButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Chest overlays",
                    EnumChatFormatting.GRAY + "After cages open: opened chests are red for 15s",
                    EnumChatFormatting.GRAY + "During deathmatch: opened middle chests are red for 15s",
                    EnumChatFormatting.GRAY + "During Blitz Star spawn: opened chests are red, Blitz Star chest is green (if it's yours)");
            return;
        }
        if (isMouseOver(tracerNametagButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Tracer target nametag",
                    EnumChatFormatting.GRAY + "Shows player name above the tracer");
            return;
        }
        if (isMouseOver(cageBarrierBlocksButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Barrier blocks in cages",
                    EnumChatFormatting.GRAY + "When Hidden, hides barriers during cage phase",
                    EnumChatFormatting.GRAY + "(if Barrier Visibility module is enabled)");
            return;
        }
        if (isMouseOver(middleLootEstimateButton, mouseX, mouseY)) {
            if (!GomodInternalFeatures.isEnabled()) {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.GRAY + "Middle loot estimates",
                        EnumChatFormatting.DARK_GRAY + "Not available in public builds yet");
            } else {
                drawTooltip(mouseX, mouseY,
                        EnumChatFormatting.AQUA + "Middle loot estimates",
                        EnumChatFormatting.GRAY + "In cages + 5s after open: nametags above",
                        EnumChatFormatting.GRAY + "the nearby chest pile with expected totals",
                        EnumChatFormatting.GRAY + "from Blitz Chest Items stats");
            }
        }
    }

    private void drawTooltip(int mouseX, int mouseY, String... lines) {
        List<String> tooltip = Arrays.asList(lines);
        drawHoveringText(tooltip, mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
