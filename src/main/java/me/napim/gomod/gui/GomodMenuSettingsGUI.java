package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.GomodMenuSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.Arrays;

public class GomodMenuSettingsGUI extends GuiScreen {

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;

    private final GuiScreen parent;
    private GuiButton profilesButton;
    private GuiButton moveWhileMenuButton;
    private GuiButton dragLookButton;
    private GuiButton minecraftMenusButton;
    private GuiButton lunarMenusButton;
    private GuiButton guiScaleButton;
    private GuiBackdropPercentSlider gomodDimSlider;
    private GuiBackdropPercentSlider gomodBlurSlider;
    private GuiButton selectedButton;
    private GuiButton backButton;

    public GomodMenuSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        int bx = cx - BTN_W / 2;
        // Keep controls vertically centered; title is drawn at top (see drawScreen).

        profilesButton      = new GuiButton(10, bx, cy - 88, BTN_W, BTN_H,
                EnumChatFormatting.AQUA + "Profiles");
        moveWhileMenuButton = new GuiButton(1,  bx, cy - 66, BTN_W, BTN_H, getMoveText());
        minecraftMenusButton = new GuiButton(4, bx, cy - 44, BTN_W, BTN_H, getMinecraftMenusText());
        lunarMenusButton = GomodFixes.isWeaveRuntime()
                ? new GuiButton(5, bx, 0, BTN_W, BTN_H, getLunarMenusText()) : null;
        dragLookButton      = new GuiButton(2,  bx, cy - 22, BTN_W, BTN_H, getDragText());
        guiScaleButton      = new GuiButton(3,  bx, cy, BTN_W, BTN_H, getGuiScaleText());
        gomodDimSlider      = new GuiBackdropPercentSlider(101, bx, cy + 22, BTN_W,
                "gomod menus: dim", ConfigManager.getGuiBackdropGomodDimPercent());
        gomodBlurSlider     = new GuiBackdropPercentSlider(102, bx, cy + 44, BTN_W,
                "gomod menus: blur", ConfigManager.getGuiBackdropGomodBlurPercent());
        backButton          = new GuiButton(0,  bx, cy + 66, BTN_W, BTN_H,
                EnumChatFormatting.GRAY + "Back");

        buttonList.add(profilesButton);
        buttonList.add(moveWhileMenuButton);
        buttonList.add(minecraftMenusButton);
        if (lunarMenusButton != null) {
            buttonList.add(lunarMenusButton);
        }
        buttonList.add(dragLookButton);
        buttonList.add(guiScaleButton);
        buttonList.add(gomodDimSlider);
        buttonList.add(gomodBlurSlider);
        buttonList.add(backButton);
        layoutControls(sr.getScaledHeight());
        refreshBackdropSliderEnabledState();
    }

    private void layoutControls(int screenHeight) {
        int contentTop = GuiHeader.standardContentTopAfterTitle(fontRendererObj);
        int rowStep = Math.max(12, Math.min(22,
                (screenHeight - contentTop - 8) / buttonList.size()));
        int rowHeight = Math.min(BTN_H, rowStep - 2);
        int contentHeight = (buttonList.size() - 1) * rowStep + rowHeight;
        int firstY = Math.max(contentTop, (screenHeight - contentHeight) / 2);
        for (int i = 0; i < buttonList.size(); i++) {
            GuiButton control = buttonList.get(i);
            control.yPosition = firstY + i * rowStep;
            control.height = rowHeight;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());

        String title = EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "gomod Menu Settings";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        // Keep inherited mode's label synced if Minecraft's setting changes at runtime.
        guiScaleButton.displayString = getGuiScaleText();
        refreshBackdropSliderEnabledState();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltips(mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, GomodMenuSettingsGUI.class);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                if (parent instanceof MiscellaneousGUI) {
                    ((MiscellaneousGUI) parent).refreshGomodMenuSettingsButtonLabel();
                }
                mc.displayGuiScreen(parent);
                break;
            case 1:
                GomodMenuSettings.setMoveWhileMenuEnabled(!GomodMenuSettings.isMoveWhileMenuEnabled());
                moveWhileMenuButton.displayString = getMoveText();
                break;
            case 2:
                GomodMenuSettings.setDragLookEnabled(!GomodMenuSettings.isDragLookEnabled());
                dragLookButton.displayString = getDragText();
                break;
            case 3:
                ConfigManager.setGomodMenuGuiScale(nextGuiScale(
                        ConfigManager.getGomodMenuGuiScale(),
                        GomodGuiScaleOverride.getMinecraftGuiScaleSetting(mc)));
                GomodGuiScaleOverride.restore(mc);
                mc.displayGuiScreen(new GomodMenuSettingsGUI(parent));
                break;
            case 4:
                GomodMenuSettings.setMinecraftMenuControlsEnabled(
                        !GomodMenuSettings.isMinecraftMenuControlsEnabled());
                minecraftMenusButton.displayString = getMinecraftMenusText();
                break;
            case 5:
                GomodMenuSettings.setLunarMenuControlsEnabled(
                        !GomodMenuSettings.isLunarMenuControlsEnabled());
                lunarMenusButton.displayString = getLunarMenusText();
                break;
            case 10:
                mc.displayGuiScreen(new ProfilesGUI(this));
                break;
        }
    }

    private String getMoveText() {
        return EnumChatFormatting.GOLD + "Move while in gomod menus: "
                + (GomodMenuSettings.isMoveWhileMenuEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getDragText() {
        return EnumChatFormatting.GOLD + "Drag to look: "
                + (GomodMenuSettings.isDragLookEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getMinecraftMenusText() {
        return EnumChatFormatting.GOLD + "Move while in MC menus: "
                + (GomodMenuSettings.isMinecraftMenuControlsEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getLunarMenusText() {
        return EnumChatFormatting.GOLD + "Move while in Lunar menus: "
                + (GomodMenuSettings.isLunarMenuControlsEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    private String getGuiScaleText() {
        int selected = ConfigManager.getGomodMenuGuiScale();
        if (selected < 0) {
            int minecraftScale = GomodGuiScaleOverride.getMinecraftGuiScaleSetting(mc);
            return EnumChatFormatting.GOLD + "gomod GUI Scale: " + EnumChatFormatting.GREEN
                    + "Minecraft" + EnumChatFormatting.GRAY + " (" + guiScaleName(minecraftScale) + ")";
        }
        return EnumChatFormatting.GOLD + "gomod GUI Scale: " + EnumChatFormatting.GREEN
                + guiScaleName(selected);
    }

    private static int nextGuiScale(int current, int minecraftScale) {
        int next = current;
        do {
            next = next >= 5 ? -1 : next + 1;
            // "Minecraft (Large)", for example, already provides the Large choice.
            // Omit that explicit duplicate while retaining the inherited setting.
        } while (next >= 0 && next == minecraftScale);
        return next;
    }

    private static String guiScaleName(int scale) {
        switch (scale) {
            case 0: return "Auto";
            case 1: return "Small";
            case 2: return "Normal";
            case 3: return "Large";
            case 4: return "4x";
            case 5: return "5x";
            default: return scale + "x";
        }
    }

    private void refreshBackdropSliderEnabledState() {
        boolean enabled = !"VANILLA".equals(ConfigManager.getGuiBackdropMode());
        if (gomodDimSlider != null) {
            gomodDimSlider.enabled = enabled;
        }
        if (gomodBlurSlider != null) {
            gomodBlurSlider.enabled = enabled;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiButton button : buttonList) {
            if (button instanceof GuiBackdropPercentSlider && button.mousePressed(mc, mouseX, mouseY)) {
                selectedButton = button;
                applyBackdropSlider((GuiBackdropPercentSlider) button);
                break;
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof GuiBackdropPercentSlider) {
            GuiBackdropPercentSlider slider = (GuiBackdropPercentSlider) selectedButton;
            slider.mouseDragged(mc, mouseX, mouseY);
            applyBackdropSlider(slider);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        GuiBackdropPercentSlider slider = selectedButton instanceof GuiBackdropPercentSlider
                ? (GuiBackdropPercentSlider) selectedButton : null;
        if (slider != null) {
            slider.mouseReleased(mouseX, mouseY);
        }
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && slider != null) {
            applyBackdropSlider(slider);
            ConfigManager.saveConfig();
        }
        selectedButton = null;
    }

    private static void applyBackdropSlider(GuiBackdropPercentSlider slider) {
        if (slider.id == 101) {
            ConfigManager.setGuiBackdropGomodDimPercentRuntime(slider.getIntValue());
        } else if (slider.id == 102) {
            ConfigManager.setGuiBackdropGomodBlurPercentRuntime(slider.getIntValue());
        }
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (isHovering(profilesButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Profiles",
                    EnumChatFormatting.GRAY + "Switch between gomod profiles"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(moveWhileMenuButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Move while in gomod menus",
                    EnumChatFormatting.GRAY + "Allows WASD movement while any gomod menu is open"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(dragLookButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Drag to look",
                    EnumChatFormatting.GRAY + "Right-click and drag in menus with movement enabled"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(minecraftMenusButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Move while in MC menus",
                    EnumChatFormatting.GRAY + "Allows WASD movement while any MC menu is open"
            ), mouseX, Math.max(0, mouseY - 12));
        }
        if (isHovering(lunarMenusButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Move while in Lunar menus",
                    EnumChatFormatting.GRAY + "Allows movement while Lunar menus are open",
                    EnumChatFormatting.GRAY + "Typing in a field pauses movement and drag to look"
            ), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private boolean isHovering(GuiButton btn, int mx, int my) {
        return btn != null
                && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
