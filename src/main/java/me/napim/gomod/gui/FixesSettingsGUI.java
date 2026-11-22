package me.napim.gomod.gui;

import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.ConfigManager;
import org.lwjgl.input.Mouse;
import java.io.IOException;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
import java.util.List;

/**
 * Miscellaneous → Fixes: Hypixel TAB cleanup, nametag depth, and idle GUI entity rendering.
 */
public class FixesSettingsGUI extends GuiScreen {

    private static final int BUTTON_WIDTH = 260;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 8;

    private final GuiScreen parent;
    private GuiButton hypixelDupeTabButton;
    private GuiButton hypixelDeadTabButton;
    private GuiButton nametagsThroughChestsButton;
    private GuiButton thunderRenderFixButton;
    private GuiButton forceReplayHealthTagsButton;
    private GuiButton blitzRektHitboxButton;
    private GuiButton unbotheredTauntButton;
    private int scroll, contentTop, contentBottom, maxScroll;
    private GuiButton backButton;

    public FixesSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int titleBottom = GuiHeader.STANDARD_TITLE_Y + fontRendererObj.FONT_HEIGHT + 14;
        int controlCount = 6 + (GomodFixes.isWeaveRuntime() ? 1 : 0);
        int controlGap = Math.min(GAP, Math.max(2,
                (backY - 10 - titleBottom - BUTTON_HEIGHT * controlCount) / (controlCount - 1)));
        int maxFirstY = backY - 10 - BUTTON_HEIGHT * controlCount - controlGap * (controlCount - 1);
        int y = Math.max(titleBottom, Math.min(sh / 2 - 50, maxFirstY));

        hypixelDupeTabButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getHypixelDupeTabText());
        y += BUTTON_HEIGHT + controlGap;
        hypixelDeadTabButton = new GuiButton(2, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getHypixelDeadTabText());
        y += BUTTON_HEIGHT + controlGap;
        nametagsThroughChestsButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getNametagsThroughChestsText());
        y += BUTTON_HEIGHT + controlGap;
        thunderRenderFixButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getThunderRenderFixText());
        y += BUTTON_HEIGHT + controlGap;
        unbotheredTauntButton = new GuiButton(8, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                toggleText("Unbothered taunt", ConfigManager.isUnbotheredTauntEnabled()));
        y += BUTTON_HEIGHT + controlGap;
        forceReplayHealthTagsButton = null;
        if (GomodFixes.isWeaveRuntime()) {
            forceReplayHealthTagsButton = new GuiButton(5, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                    getForceReplayHealthTagsText());
        }
        blitzRektHitboxButton = new GuiButton(9, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getBlitzRektHitboxText());
        backButton = new GuiButton(0, cx - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(hypixelDupeTabButton);
        buttonList.add(hypixelDeadTabButton);
        buttonList.add(nametagsThroughChestsButton);
        buttonList.add(thunderRenderFixButton);
        buttonList.add(unbotheredTauntButton);
        if (forceReplayHealthTagsButton != null) {
            buttonList.add(forceReplayHealthTagsButton);
        }
        if (blitzRektHitboxButton != null) {
            buttonList.add(blitzRektHitboxButton);
        }
        buttonList.add(backButton);
        contentTop = titleBottom;
        contentBottom = backY - 12;
        maxScroll = Math.max(0, controlCount * (BUTTON_HEIGHT + controlGap) - controlGap - (contentBottom - contentTop));
        if (GomodSearchHighlight.matchesGui(FixesSettingsGUI.class)) {
            int index = 0;
            for (GuiButton b : buttonList) {
                if (b.id == GomodSearchHighlight.getButtonId()) scroll = index * (BUTTON_HEIGHT + controlGap);
                if (b.id != 0) index++;
            }
        }
        layoutControls();
    }

    private void layoutControls() {
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        int count = buttonList.size() - 1;
        int gap = maxScroll > 0 ? 2 : Math.min(GAP, Math.max(2,
                (contentBottom - contentTop - count * BUTTON_HEIGHT) / Math.max(1, count - 1)));
        int total = count * BUTTON_HEIGHT + (count - 1) * gap;
        int y = contentTop + Math.max(0, (contentBottom - contentTop - total) / 2) - scroll;
        for (GuiButton b : buttonList) {
            if (b.id == 0) continue;
            b.yPosition = y;
            b.visible = y >= contentTop && y + BUTTON_HEIGHT <= contentBottom;
            y += BUTTON_HEIGHT + gap;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) { scroll += wheel > 0 ? -22 : 22; layoutControls(); }
    }

    private String toggleText(String label, boolean enabled) {
        return EnumChatFormatting.WHITE + label + ": " + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Fixes" + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (maxScroll > 0) {
            int x = sr.getScaledWidth() / 2 + BUTTON_WIDTH / 2 + 6;
            int track = contentBottom - contentTop;
            int thumb = Math.max(12, track * track / (track + maxScroll));
            int top = contentTop + (track - thumb) * scroll / maxScroll;
            drawRect(x, contentTop, x + 2, contentBottom, 0x55333333);
            drawRect(x, top, x + 2, top + thumb, 0xFF999999);
        }
        GomodSearchHighlight.draw(buttonList, FixesSettingsGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshFixesButtonLabel();
                }
                mc.displayGuiScreen(parent);
                break;
            case 1:
                GomodFixes.setHypixelTabDupeHideEnabled(!GomodFixes.isHypixelTabDupeHideEnabled());
                hypixelDupeTabButton.displayString = getHypixelDupeTabText();
                break;
            case 2:
                GomodFixes.setHypixelTabQuitGhostHideEnabled(!GomodFixes.isHypixelTabQuitGhostHideEnabled());
                hypixelDeadTabButton.displayString = getHypixelDeadTabText();
                break;
            case 3:
                GomodFixes.setNametagThroughChestsEnabled(!GomodFixes.isNametagThroughChestsEnabled());
                nametagsThroughChestsButton.displayString = getNametagsThroughChestsText();
                break;
            case 4:
                GomodFixes.setThunderRenderFixEnabled(!GomodFixes.isThunderRenderFixEnabled());
                thunderRenderFixButton.displayString = getThunderRenderFixText();
                break;
            case 5:
                GomodFixes.setForceReplayPlayerHealthTagsEnabled(
                        !GomodFixes.isForceReplayPlayerHealthTagsEnabled());
                forceReplayHealthTagsButton.displayString = getForceReplayHealthTagsText();
                break;
            case 8:
                ConfigManager.setUnbotheredTauntEnabled(!ConfigManager.isUnbotheredTauntEnabled());
                unbotheredTauntButton.displayString = toggleText("Unbothered taunt", ConfigManager.isUnbotheredTauntEnabled());
                break;
            case 9:
                if (blitzRektHitboxButton != null) {
                    GomodFixes.setBlitzRektHitboxOverlayEnabled(!GomodFixes.isBlitzRektHitboxOverlayEnabled());
                    blitzRektHitboxButton.displayString = getBlitzRektHitboxText();
                }
                break;
            default:
                break;
        }
    }

    private String getHypixelDupeTabText() {
        return EnumChatFormatting.WHITE + "Hide TAB duplicate names: "
                + (GomodFixes.isHypixelTabDupeHideEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getHypixelDeadTabText() {
        return EnumChatFormatting.WHITE + "Force-clean dead players: "
                + (GomodFixes.isHypixelTabQuitGhostHideEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getNametagsThroughChestsText() {
        return EnumChatFormatting.WHITE + "Nametags through chests: "
                + (GomodFixes.isNametagThroughChestsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getThunderRenderFixText() {
        return EnumChatFormatting.WHITE + "Fix thunder render: "
                + (GomodFixes.isThunderRenderFixEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getForceReplayHealthTagsText() {
        return EnumChatFormatting.WHITE + "Force player health tags in replay: "
                + (GomodFixes.isForceReplayPlayerHealthTagsEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getBlitzRektHitboxText() {
        return EnumChatFormatting.WHITE + "Blitz #rekt hitbox: "
                + (GomodFixes.isBlitzRektHitboxOverlayEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (isMouseOver(unbotheredTauntButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY, EnumChatFormatting.AQUA + "Unbothered taunt",
                    EnumChatFormatting.GRAY + "Fixes the bug where the taunt doesn't activate",
                    EnumChatFormatting.GRAY + "when facing other players.");
            return;
        }
        if (isMouseOver(hypixelDupeTabButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Hide TAB duplicate names",
                    EnumChatFormatting.GRAY + "Hides the extra TAB row when Hypixel lists",
                    EnumChatFormatting.GRAY + "the same player twice.");
            return;
        }
        if (isMouseOver(hypixelDeadTabButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Force-clean dead players",
                    EnumChatFormatting.GRAY + "Removes dead/spectator players Hypixel",
                    EnumChatFormatting.GRAY + "leaves on TAB and in games while you are alive.",
                    EnumChatFormatting.GRAY + "TAB victims stay on TAB when enabled.");
            return;
        }
        if (isMouseOver(nametagsThroughChestsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Nametags through chests",
                    EnumChatFormatting.GRAY + "Draws player and mob nametags after tile entities",
                    EnumChatFormatting.GRAY + "so chests do not hide them.");
            return;
        }
        if (isMouseOver(thunderRenderFixButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Fix thunder render",
                    EnumChatFormatting.GRAY + "Draws lightning after entities so players",
                    EnumChatFormatting.GRAY + "stay visible through thunder flashes.");
            return;
        }
        if (isMouseOver(forceReplayHealthTagsButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Force player health tags in replay",
                    EnumChatFormatting.GRAY + "Shows health tags that Lunar doesn't display in replays.");
            return;
        }
        if (blitzRektHitboxButton != null && isMouseOver(blitzRektHitboxButton, mouseX, mouseY)) {
            drawTooltip(mouseX, mouseY,
                    EnumChatFormatting.AQUA + "Blitz #rekt hitbox",
                    EnumChatFormatting.GRAY + "Outlines the aiming and fishing-hook collision area of nearby #rekt holograms.",
                    EnumChatFormatting.GRAY + "Visual only; it does not change combat behavior.");
        }
    }

    private void drawTooltip(int mouseX, int mouseY, String... lines) {
        List<String> tooltip = Arrays.asList(lines);
        drawHoveringText(tooltip, mouseX, Math.max(0, mouseY - 12));
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && button.visible
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
