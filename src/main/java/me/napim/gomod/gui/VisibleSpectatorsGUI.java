package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DenickerRealSkinCache;
import me.napim.gomod.features.ForceGameChat;
import me.napim.gomod.features.SpectatorKillerNametagOverlay;
import me.napim.gomod.features.SpectatorRelay;
import me.napim.gomod.features.VisibleSpecs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;

/**
 * Settings hub for spectator-related options (opacity, nametags, spectator relay, game chat; live preview).
 */
public class VisibleSpectatorsGUI extends GuiScreen {
    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW = 24;
    private static final int PREVIEW_BAND = 88;
    private static final int PREVIEW_SCALE = 34;
    private static final int PREVIEW_MODEL_Y = PREVIEW_BAND - 8;
    /** Bottom nametag row in preview (hearts row when ON; same anchor as vanilla tab-health foot). */
    private static final int PREVIEW_NAMETAG_STACK_BASE_ABOVE_MODEL = -86;

    private final MiscellaneousGUI parent;
    private GuiButton nametagsButton;
    private GuiButton killerNametagButton;
    private GuiButton applyToReplaysButton;
    private GuiButton seeSpectatorsButton;
    private GuiButton forceGameChatsButton;
    private GuiButton backButton;
    private MountOpacitySlider opacitySlider;
    private GuiButton selectedButton;
    private EntityOtherPlayerMP previewPlayer;
    private int layoutPreviewBaseY;
    private int layoutControlsStartY;
    private int layoutBackY;

    public VisibleSpectatorsGUI(MiscellaneousGUI parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        previewPlayer = MountPreviewHelper.createVisibleSpecsPreviewPlayer(mc.theWorld, mc);
        DenickerRealSkinCache.ensurePrefetchMojangUuid(
                VisibleSpecs.PREVIEW_SPECTATOR_UUID, VisibleSpecs.PREVIEW_SPECTATOR_NAME);

        layoutBackY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int controlRows = 6;
        int controlsHeight = ROW * (controlRows - 1) + BUTTON_HEIGHT;
        int totalContentHeight = PREVIEW_BAND + 8 + controlsHeight;
        int centerY = sh / 2;
        layoutPreviewBaseY = Math.max(GuiHeader.STANDARD_SUBTITLE_Y + 4, centerY - totalContentHeight / 2);
        layoutControlsStartY = layoutPreviewBaseY + PREVIEW_BAND + 8;
        if (layoutControlsStartY + controlsHeight > layoutBackY - 8) {
            int shift = layoutControlsStartY + controlsHeight - (layoutBackY - 8);
            layoutPreviewBaseY = Math.max(GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj),
                    layoutPreviewBaseY - shift);
            layoutControlsStartY = layoutPreviewBaseY + PREVIEW_BAND + 8;
        }

        nametagsButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, layoutControlsStartY, BUTTON_WIDTH, BUTTON_HEIGHT,
                getNametagsText());
        killerNametagButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, layoutControlsStartY + ROW, BUTTON_WIDTH,
                BUTTON_HEIGHT, getKillerNametagText());
        opacitySlider = new MountOpacitySlider(2, cx - BUTTON_WIDTH / 2, layoutControlsStartY + ROW * 2, BUTTON_WIDTH,
                "Spectator opacity:", VisibleSpecs.getOpacity());
        applyToReplaysButton = new GuiButton(5, cx - BUTTON_WIDTH / 2, layoutControlsStartY + ROW * 3,
                BUTTON_WIDTH, BUTTON_HEIGHT, getApplyToReplaysText());
        seeSpectatorsButton = new GuiButton(6, cx - BUTTON_WIDTH / 2, layoutControlsStartY + ROW * 4,
                BUTTON_WIDTH, BUTTON_HEIGHT, getSeeSpectatorsText());
        forceGameChatsButton = new GuiButton(7, cx - BUTTON_WIDTH / 2, layoutControlsStartY + ROW * 5,
                BUTTON_WIDTH, BUTTON_HEIGHT, getForceGameChatsText());
        backButton = new GuiButton(99, cx - BUTTON_WIDTH / 2, layoutBackY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");

        buttonList.clear();
        buttonList.add(nametagsButton);
        buttonList.add(killerNametagButton);
        buttonList.add(opacitySlider);
        buttonList.add(applyToReplaysButton);
        buttonList.add(seeSpectatorsButton);
        buttonList.add(forceGameChatsButton);
        buttonList.add(backButton);
    }

    @Override
    public void onGuiClosed() {
        if (opacitySlider != null) {
            VisibleSpecs.setOpacityRuntime(opacitySlider.getValue());
            VisibleSpecs.persistOpacity();
        }
        MountPreviewHelper.discardVisibleSpecsPreviewPlayer(mc);
        previewPlayer = null;
        super.onGuiClosed();
    }

    private String getKillerNametagText() {
        return "Killer nametag: "
                + (SpectatorKillerNametagOverlay.isEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getNametagsText() {
        String mode = VisibleSpecs.getNametagMode();
        String label;
        if (VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)) {
            label = EnumChatFormatting.YELLOW + "Only IGN";
        } else if (VisibleSpecs.NAMETAG_MODE_OFF.equals(mode)) {
            label = EnumChatFormatting.RED + "OFF";
        } else {
            label = EnumChatFormatting.GREEN + "ON";
        }
        return "Spectator nametags: " + label;
    }

    private String getApplyToReplaysText() {
        return "Apply settings to Replays: "
                + (VisibleSpecs.isApplySettingsToReplaysEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getSeeSpectatorsText() {
        return "See spectators while alive: "
                + (SpectatorRelay.isEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private String getForceGameChatsText() {
        return "Force game chats: "
                + (ConfigManager.isForceGameChatsEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private float previewOpacity() {
        if (opacitySlider != null) {
            return opacitySlider.getValue();
        }
        return VisibleSpecs.getOpacity();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (opacitySlider != null) {
            // Keep the live world render in sync throughout the drag, not only after leaving this screen.
            VisibleSpecs.setOpacityRuntime(opacitySlider.getValue());
        }
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        int cx = width / 2;
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Spectator Settings";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawPreview(cx);
        super.drawScreen(mouseX, mouseY, partialTicks);
        MountPreviewHelper.refreshVisibleSpecsPreviewSkinIfNeeded(mc, previewPlayer);
        drawPreviewNametags(cx);
        GomodSearchHighlight.draw(buttonList, VisibleSpectatorsGUI.class);
        drawReplayApplyTooltip(mouseX, mouseY);
    }

    private void drawReplayApplyTooltip(int mouseX, int mouseY) {
        if (isButtonHovered(applyToReplaysButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.GRAY + "Applies " + EnumChatFormatting.YELLOW + "Spectator opacity"
                            + EnumChatFormatting.GRAY + " and "
                            + EnumChatFormatting.YELLOW + "Killer nametag" + EnumChatFormatting.GRAY + " in replays."
            ), mouseX, mouseY);
            return;
        }
        if (isButtonHovered(seeSpectatorsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "See spectators while alive",
                    EnumChatFormatting.GRAY + "Shows spectators in your current Blitz game.",
                    EnumChatFormatting.GRAY + "Spectating gomod users with this enabled share",
                    EnumChatFormatting.GRAY + "the spectators loaded around them through gomod Service.",
                    EnumChatFormatting.GRAY + "Uses your Spectator Settings for opacity and nametags."
            ), mouseX, mouseY);
            return;
        }
        if (isButtonHovered(forceGameChatsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Force game chats",
                    EnumChatFormatting.GRAY + "Shares public alive and spectator chat in this Blitz game.",
                    EnumChatFormatting.GRAY + "Needs another gomod user on the other side with this ON."
            ), mouseX, mouseY);
        }
    }

    private static boolean isButtonHovered(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    private void drawPreview(int cx) {
        if (previewPlayer == null) {
            return;
        }
        int modelBaseY = layoutPreviewBaseY + PREVIEW_MODEL_Y;
        MountPreviewHelper.renderVisibleSpecsPreviewPlayer(mc, previewPlayer, cx, modelBaseY, PREVIEW_SCALE,
                previewOpacity());
    }

    private void drawPreviewNametags(int cx) {
        int modelBaseY = layoutPreviewBaseY + PREVIEW_MODEL_Y;
        VisibleSpecs.drawSettingsPreviewNametags(fontRendererObj, cx, modelBaseY, PREVIEW_NAMETAG_STACK_BASE_ABOVE_MODEL);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 3:
                VisibleSpecs.cycleNametagMode();
                nametagsButton.displayString = getNametagsText();
                break;
            case 4:
                SpectatorKillerNametagOverlay.setEnabled(!SpectatorKillerNametagOverlay.isEnabled());
                killerNametagButton.displayString = getKillerNametagText();
                break;
            case 5:
                VisibleSpecs.setApplySettingsToReplaysEnabled(!VisibleSpecs.isApplySettingsToReplaysEnabled());
                applyToReplaysButton.displayString = getApplyToReplaysText();
                break;
            case 6:
                SpectatorRelay.setEnabled(!SpectatorRelay.isEnabled());
                seeSpectatorsButton.displayString = getSeeSpectatorsText();
                break;
            case 7:
                ForceGameChat.setEnabled(!ConfigManager.isForceGameChatsEnabled());
                forceGameChatsButton.displayString = getForceGameChatsText();
                break;
            case 99:
                mc.displayGuiScreen(parent);
                break;
            default:
                break;
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (opacitySlider.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
            selectedButton = opacitySlider;
            VisibleSpecs.setOpacityRuntime(opacitySlider.getValue());
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (selectedButton instanceof MountOpacitySlider) {
            MountOpacitySlider slider = (MountOpacitySlider) selectedButton;
            slider.mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
            VisibleSpecs.setOpacityRuntime(slider.getValue());
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && selectedButton instanceof MountOpacitySlider) {
            VisibleSpecs.setOpacityRuntime(((MountOpacitySlider) selectedButton).getValue());
            VisibleSpecs.persistOpacity();
        }
        selectedButton = null;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
