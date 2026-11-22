package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.Denicker;
import me.napim.gomod.features.KillCounter;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;

/**
 * Denicker Tools settings: TAB/nametag denicker, real skin, Nick Renamed Items, Death [SEARCH NICK].
 */
public final class DenickerSettingsGUI extends GuiScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int TAG_MODE_BUTTON_PAD = 16;
    private static final int ROW_GAP = 5;
    private static final int COL_GAP = 8;
    private static final int PREVIEW_BAND = 88;
    private static final int PREVIEW_SCALE = 34;

    /**
     * Preview layout tuning — edit these in {@code DenickerSettingsGUI.java} if nametags sit wrong.
     * {@link #PREVIEW_MODEL_Y}: model feet anchor from top of preview band (higher = model lower).
     * {@link #PREVIEW_RESOLVED_TAG_ABOVE_MODEL} / {@link #PREVIEW_VISIBLE_NICK_ABOVE_MODEL}: added to model
     * anchor Y; more negative = higher on screen (above the head).
     * {@link #PREVIEW_SCALE}: model size (affects how close text can sit to the head).
     */
    private static final int PREVIEW_MODEL_Y = PREVIEW_BAND - 8;
    private static final int PREVIEW_RESOLVED_TAG_ABOVE_MODEL = -96;
    private static final int PREVIEW_VISIBLE_NICK_ABOVE_MODEL = -86;

    /** Vanilla nameplate background alpha (~25% black), same as in-world nametags. */
    private static final int NAMETAG_PLATE_BG = 0x40000000;

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton tagModeButton;
    private GuiButton realSkinButton;
    private GuiButton showRealIgnTabButton;
    private GuiButton nickRenamedItemsButton;
    private GuiButton deathSearchNickButton;
    private GuiButton helpButton;
    private GuiButton backButton;
    private EntityOtherPlayerMP previewPlayer;
    private int layoutPreviewBaseY;
    private int layoutToggleY;
    private int layoutBackY;
    private int layoutHelpY;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    public DenickerSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        previewPlayer = MountPreviewHelper.createDenickerPreviewPlayer(mc.theWorld, mc);
        if (Denicker.isRevealRealSkinEnabled()) {
            me.napim.gomod.features.DenickerRealSkinCache.ensurePrefetchMojangUuid(
                    Denicker.PREVIEW_SKIN_UUID, Denicker.PREVIEW_REAL);
        }

        layoutBackY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        layoutHelpY = layoutBackY - BUTTON_HEIGHT - 4;
        int totalControlsHeight = BUTTON_HEIGHT * 4 + ROW_GAP * 3;
        int totalContentHeight = PREVIEW_BAND + 4 + totalControlsHeight;
        int centerY = sh / 2;
        layoutPreviewBaseY = Math.max(GuiHeader.STANDARD_SUBTITLE_Y, centerY - totalContentHeight / 2);
        layoutToggleY = layoutPreviewBaseY + PREVIEW_BAND + 4;
        if (layoutToggleY + totalControlsHeight > layoutHelpY - 8) {
            int shift = layoutToggleY + totalControlsHeight - (layoutHelpY - 8);
            layoutPreviewBaseY = Math.max(GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj),
                    layoutPreviewBaseY - shift);
            layoutToggleY = layoutPreviewBaseY + PREVIEW_BAND + 4;
        }
        int row2Y = layoutToggleY + BUTTON_HEIGHT + ROW_GAP;
        int row3Y = row2Y + BUTTON_HEIGHT + ROW_GAP;
        int row4Y = row3Y + BUTTON_HEIGHT + ROW_GAP;

        toggleButton = new GuiButton(1, 0, layoutToggleY, 0, BUTTON_HEIGHT, getToggleButtonText());
        realSkinButton = new GuiButton(4, 0, layoutToggleY, 0, BUTTON_HEIGHT,
                Denicker.getRevealRealSkinButtonLabel());
        tagModeButton = new GuiButton(2, 0, row2Y, 0, BUTTON_HEIGHT,
                Denicker.getTagModeButtonLabel());
        showRealIgnTabButton = new GuiButton(5, 0, row3Y, 0, BUTTON_HEIGHT,
                Denicker.getShowRealIgnInTabButtonLabel());
        nickRenamedItemsButton = new GuiButton(6, 0, row3Y, 0, BUTTON_HEIGHT,
                Denicker.getNickRenamedItemsButtonLabel());
        deathSearchNickButton = new GuiButton(7, 0, row4Y, 0, BUTTON_HEIGHT,
                Denicker.getDeathSearchNickButtonLabel());
        helpButton = new GuiButton(3, 0, layoutHelpY, 0, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "Help");
        backButton = new GuiButton(99, 0, layoutBackY, 0, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back to Menu");
        applyButtonLayouts(cx);

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(realSkinButton);
        buttonList.add(tagModeButton);
        buttonList.add(showRealIgnTabButton);
        buttonList.add(nickRenamedItemsButton);
        buttonList.add(deathSearchNickButton);
        buttonList.add(helpButton);
        buttonList.add(backButton);
        enabledOverlays.clear();
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return Denicker.isEnabled();
            }
        });
        enabledOverlays.trackFeatureToggle("nickRenamed", nickRenamedItemsButton,
                new ModuleSettingsEnabledOverlays.EnabledCheck() {
                    @Override
                    public boolean isOn() {
                        return Denicker.isNickRenamedItemsEnabled();
                    }
                });
        enabledOverlays.trackFeatureToggle("deathSearchNick", deathSearchNickButton,
                new ModuleSettingsEnabledOverlays.EnabledCheck() {
                    @Override
                    public boolean isOn() {
                        return Denicker.isDeathSearchNickEnabled();
                    }
                });
    }

    @Override
    public void onGuiClosed() {
        MountPreviewHelper.discardDenickerPreviewPlayer(mc);
        previewPlayer = null;
        super.onGuiClosed();
    }

    private String getToggleButtonText() {
        return "Denicker: " + (Denicker.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        int cx = width / 2;
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Denicker Tools";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawPlayerModel(cx);

        super.drawScreen(mouseX, mouseY, partialTicks);
        MountPreviewHelper.refreshDenickerPreviewSkinIfNeeded(mc, previewPlayer);
        drawPreviewNametags(cx);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, DenickerSettingsGUI.class);
        drawFeatureTooltips(mouseX, mouseY);
    }

    private void drawPlayerModel(int cx) {
        if (previewPlayer == null) {
            return;
        }
        int modelBaseY = layoutPreviewBaseY + PREVIEW_MODEL_Y;
        MountPreviewHelper.renderDenickerPreviewPlayer(mc, previewPlayer, cx, modelBaseY, PREVIEW_SCALE, 1.0f);
    }

    private void drawPreviewNametags(int cx) {
        int modelBaseY = layoutPreviewBaseY + PREVIEW_MODEL_Y;
        int tagY = modelBaseY + PREVIEW_RESOLVED_TAG_ABOVE_MODEL;
        int nickY = modelBaseY + PREVIEW_VISIBLE_NICK_ABOVE_MODEL;

        String visibleNick = EnumChatFormatting.RED + Denicker.PREVIEW_NICK;
        String resolvedTag = Denicker.isEnabled()
                ? Denicker.formatPreviewResolvedTag()
                : EnumChatFormatting.DARK_GRAY + "Denicker off";
        drawCenteredNametagPlate(cx, tagY, resolvedTag);
        drawCenteredNametagPlate(cx, nickY, visibleNick);
    }

    /** 2D GUI version of the vanilla nameplate: faint dark box + centered text. */
    private void drawCenteredNametagPlate(int centerX, int textY, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int textW = fontRendererObj.getStringWidth(text);
        int textX = centerX - textW / 2;
        int left = textX - 1;
        int right = textX + textW + 1;
        int top = textY - 1;
        int bottom = textY + 8;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        Gui.drawRect(left, top, right, bottom, NAMETAG_PLATE_BG);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();

        fontRendererObj.drawString(text, textX, textY, 0xFFFFFFFF);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1: {
                boolean now = !Denicker.isEnabled();
                Denicker.setEnabled(now);
                toggleButton.displayString = getToggleButtonText();
                enabledOverlays.onToggled("main", now);
                refreshMiscellaneousButtonIfNeeded();
                break;
            }
            case 2:
                ConfigManager.cycleDenickerTagDisplayMode();
                KillCounter.forceTabRefresh();
                applyButtonLayouts(new ScaledResolution(mc).getScaledWidth() / 2);
                break;
            case 4: {
                Denicker.setRevealRealSkinEnabled(!Denicker.isRevealRealSkinEnabled());
                realSkinButton.displayString = Denicker.getRevealRealSkinButtonLabel();
                MountPreviewHelper.discardDenickerPreviewPlayer(mc);
                previewPlayer = MountPreviewHelper.createDenickerPreviewPlayer(mc.theWorld, mc);
                if (Denicker.isRevealRealSkinEnabled()) {
                    me.napim.gomod.features.DenickerRealSkinCache.ensurePrefetchMojangUuid(
                            Denicker.PREVIEW_SKIN_UUID, Denicker.PREVIEW_REAL);
                }
                applyButtonLayouts(new ScaledResolution(mc).getScaledWidth() / 2);
                break;
            }
            case 5: {
                Denicker.setShowRealIgnInTabEnabled(!Denicker.isShowRealIgnInTabEnabled());
                showRealIgnTabButton.displayString = Denicker.getShowRealIgnInTabButtonLabel();
                KillCounter.forceTabRefresh();
                applyButtonLayouts(new ScaledResolution(mc).getScaledWidth() / 2);
                break;
            }
            case 6: {
                boolean now = !Denicker.isNickRenamedItemsEnabled();
                Denicker.setNickRenamedItemsEnabled(now);
                nickRenamedItemsButton.displayString = Denicker.getNickRenamedItemsButtonLabel();
                enabledOverlays.onToggled("nickRenamed", now);
                applyButtonLayouts(new ScaledResolution(mc).getScaledWidth() / 2);
                break;
            }
            case 7: {
                boolean now = !Denicker.isDeathSearchNickEnabled();
                Denicker.setDeathSearchNickEnabled(now);
                deathSearchNickButton.displayString = Denicker.getDeathSearchNickButtonLabel();
                enabledOverlays.onToggled("deathSearchNick", now);
                applyButtonLayouts(new ScaledResolution(mc).getScaledWidth() / 2);
                break;
            }
            case 3:
                mc.displayGuiScreen(new DenickerHelpGUI(this));
                break;
            case 99:
                mc.displayGuiScreen(parent);
                break;
            default:
                break;
        }
    }

    public void refreshNickRenamedItemsButtonLabel() {
        if (nickRenamedItemsButton != null) {
            nickRenamedItemsButton.displayString = Denicker.getNickRenamedItemsButtonLabel();
        }
    }

    private int computeFullWidth() {
        int maxColText = Math.max(
                Math.max(fontRendererObj.getStringWidth("Denicker: OFF"),
                        fontRendererObj.getStringWidth("Reveal real skin: OFF")),
                Math.max(fontRendererObj.getStringWidth("Reveal nick's IGN in TAB: OFF"),
                        fontRendererObj.getStringWidth("Nick Renamed Items: OFF")));
        int colW = Math.max(150, maxColText + 16);
        int twoColTotal = colW * 2 + COL_GAP;
        int parensLabel = fontRendererObj.getStringWidth("Nick nametag: "
                + EnumChatFormatting.GOLD + "(" + Denicker.PREVIEW_REAL + ")");
        int rankLabel = fontRendererObj.getStringWidth("Nick nametag: " + Denicker.formatPreviewRankTag());
        int deathSearchLabel = fontRendererObj.getStringWidth("Death [SEARCH NICK]: OFF");
        int fullRowNeed = Math.max(BUTTON_WIDTH, Math.max(Math.max(parensLabel, rankLabel), deathSearchLabel) + TAG_MODE_BUTTON_PAD);
        return Math.max(twoColTotal, fullRowNeed);
    }

    private void applyButtonLayouts(int cx) {
        int fullWidth = computeFullWidth();
        int colWidth = (fullWidth - COL_GAP) / 2;
        int leftX = cx - fullWidth / 2;
        int rightX = leftX + colWidth + COL_GAP;

        toggleButton.displayString = getToggleButtonText();
        toggleButton.width = colWidth;
        toggleButton.xPosition = leftX;

        realSkinButton.displayString = Denicker.getRevealRealSkinButtonLabel();
        realSkinButton.width = colWidth;
        realSkinButton.xPosition = rightX;

        tagModeButton.displayString = Denicker.getTagModeButtonLabel();
        layoutButton(tagModeButton, cx, fullWidth);

        showRealIgnTabButton.displayString = Denicker.getShowRealIgnInTabButtonLabel();
        showRealIgnTabButton.width = colWidth;
        showRealIgnTabButton.xPosition = leftX;

        nickRenamedItemsButton.displayString = Denicker.getNickRenamedItemsButtonLabel();
        nickRenamedItemsButton.width = colWidth;
        nickRenamedItemsButton.xPosition = rightX;

        deathSearchNickButton.displayString = Denicker.getDeathSearchNickButtonLabel();
        layoutButton(deathSearchNickButton, cx, fullWidth);

        layoutButton(helpButton, cx, fullWidth);
        layoutButton(backButton, cx, fullWidth);
    }

    private void drawFeatureTooltips(int mouseX, int mouseY) {
        if (isMouseOver(nickRenamedItemsButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Nick Renamed Items",
                    EnumChatFormatting.GRAY + "Detects renamed kit items on nicked players.",
                    EnumChatFormatting.GRAY + "Adds clickable [Search item owner] in chat."),
                    mouseX, Math.max(0, mouseY - 12));
        } else if (isMouseOver(deathSearchNickButton, mouseX, mouseY)) {
            drawHoveringText(Arrays.asList(
                    EnumChatFormatting.AQUA + "Death [SEARCH NICK]",
                    EnumChatFormatting.GRAY + "Adds a clickable [SEARCH NICK] on death messages if a",
                    EnumChatFormatting.GRAY + "nick has his Kill Counter enabled.",
                    EnumChatFormatting.GRAY + "Looks up nearby Blitz kill counts on gostats123"),
                    mouseX, Math.max(0, mouseY - 12));
        }
    }

    private static boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    private static void layoutButton(GuiButton button, int cx, int width) {
        button.width = width;
        button.xPosition = cx - width / 2;
    }

    private void refreshMiscellaneousButtonIfNeeded() {
        if (parent instanceof MiscellaneousGUI) {
            ((MiscellaneousGUI) parent).refreshDenickerButtonLabel();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
