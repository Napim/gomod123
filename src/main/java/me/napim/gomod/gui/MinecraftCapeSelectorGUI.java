package me.napim.gomod.gui;

import me.napim.gomod.features.CapePickerCatalog;
import me.napim.gomod.features.CapeVisibilityReminder;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.CustomCapeShareService;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.PlayerCapeRatings;
import me.napim.gomod.features.PlayerCustomCapes;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.HypixelApiHelper;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Cape picker shared by nick disguise and standalone cape wear.
 */
public class MinecraftCapeSelectorGUI extends GuiScreen {

    private static final int BTN_BACK_ID = 0;
    private static final int BTN_APPLY_ID = 1;
    private static final int BTN_EDIT_ID = 2;
    private static final int BTN_SHARE_ID = 3;
    private static final int BTN_PEER_BROADCAST_ID = 4;
    private static final int BTN_CAPE_SOURCE_ID = 5;
    private static final int ROW_DELETE_ICON_SIZE = 10;
    private static final int ROW_DELETE_ICON_PAD = 6;
    private static final int ROW_ACTION_ICON_SIZE = 12;
    private static final int ROW_ACTION_ICON_PAD = 4;
    private static final ResourceLocation DELETE_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/deleteicon10.png");
    private static final ResourceLocation EDIT_ROW_ICON_TEXTURE =
            new ResourceLocation("gomod123", "textures/edithudicon.png");
    private static final ResourceLocation HEART_LIKED_TEXTURE =
            new ResourceLocation("gomod123", "textures/heart.png");
    private static final ResourceLocation HEART_NOT_TEXTURE =
            new ResourceLocation("gomod123", "textures/heartnot.png");
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_GAP = 2;
    private static final int ICON_W = 38;
    private static final int ICON_H = 19;
    private static final int PANEL_BG = 0xAA070B10;
    private static final int PANEL_BORDER = 0x664A6278;
    private static final int ROW_OWNED_BG = 0x55336644;
    private static final int ROW_UNOWNED_BG = 0x33182024;
    private static final int ROW_OWNED_HOVER_BG = 0x66447755;
    private static final int ROW_UNOWNED_HOVER_BG = 0x44283038;
    private static final int ROW_SELECTED_BG = 0x88466B82;
    private static final int ROW_APPLIED_BG = 0x88406F4C;
    private static final int ROW_APPLIED_HOVER_BG = 0x99528661;
    private static final int ROW_APPLIED_BORDER = 0xDD7BE397;
    private static final int ROW_PEER_VISIBLE_BG = 0x99542E73;
    private static final int ROW_PEER_VISIBLE_HOVER_BG = 0xAA704291;
    private static final int ROW_PEER_VISIBLE_BORDER = 0xE0D79AFF;
    private static final int PREVIEW_BTN_H = 20;
    private static final int PREVIEW_BTN_GAP = 4;
    private static final int PREVIEW_FOOTER_PAD = 6;

    public interface CapeListSource {
        List<LocalDisguise.CapeOptionInfo> getOptions();

        int getSelectedIndex();

        void setSelectedIndex(int index);
    }

    public enum Mode {
        DISGUISE,
        /** Local player — same cape list as a real-player disguise, worn via {@link PlayerStandaloneCape}. */
        SELF,
        /** @deprecated use {@link #SELF} */
        STANDALONE
    }

    private final GuiScreen parent;
    private final CapeListSource source;
    private final String titleText;
    private final boolean selfCapeCatalogPreload;
    /** {@code true} when picking a disguise cape (client-only view). */
    private final boolean disguiseMode;
    /** Edit/delete/favorite/+Create row actions and preview footer tools (self + player disguise). */
    private final boolean customCapeTools;
    private GuiButton backButton;
    private GuiButton applyButton;
    private GuiButton editButton;
    private GuiButton shareButton;
    private GuiButton peerBroadcastButton;
    private GuiButton capeSourceButton;
    private EntityOtherPlayerMP previewPlayer;
    private int previewIndex = 0;
    private int scrollIndex = 0;
    private static final float PREVIEW_DRAG_SENSITIVITY = 1.35F;
    private static final float PREVIEW_PITCH_SENSITIVITY = 0.85F;
    private static final float PREVIEW_YAW_SMOOTH = 0.32F;
    private static final float PREVIEW_PITCH_SMOOTH = 0.32F;

    private float previewYaw = 155.0F;
    private float previewDisplayYaw = 155.0F;
    private float previewPitch = 14.0F;
    private float previewDisplayPitch = 14.0F;
    private boolean draggingPreview;
    private final CapeListScrollbar scrollbar = new CapeListScrollbar();
    private int lastDragMouseX;
    private int lastDragMouseY;

    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listBottom;
    private int rowsTop;
    private int rowsBottom;
    private int previewLeft;
    private int previewTop;
    private int previewWidth;
    private int previewBottom;
    private int previewCenterX;
    private int previewBaseY;
    private int previewScale;
    private int previewModelTop;
    private int previewModelBottom;
    private int previewDragHintY;
    private boolean stackedLayout;
    /** List row under cursor ({@code scrollIndex + row}), or -1. */
    private int hoveredListIndex = -1;
    private boolean selfCatalogRefreshPending;
    private int lastSelfOptionCount = -1;
    private long lastListClickMs;
    private int lastListClickIndex = -1;
    private boolean initializedOnce;
    private String focusOptionKeyAfterEditor;
    private static final long LIST_DOUBLE_CLICK_MS = 400L;

    public MinecraftCapeSelectorGUI(GuiScreen parent, Mode mode) {
        this(parent,
                mode == Mode.DISGUISE ? disguiseSource() : PlayerStandaloneCape.asCapeListSource(),
                mode == Mode.DISGUISE
                        ? EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Cape selector"
                        : EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Minecraft capes",
                mode != Mode.DISGUISE,
                mode == Mode.DISGUISE);
    }

    public MinecraftCapeSelectorGUI(GuiScreen parent, CapeListSource source, String titleText, boolean selfCapeCatalogPreload) {
        this(parent, source, titleText, selfCapeCatalogPreload, !selfCapeCatalogPreload);
    }

    public MinecraftCapeSelectorGUI(GuiScreen parent, CapeListSource source, String titleText,
                                    boolean selfCapeCatalogPreload, boolean disguiseMode) {
        this.parent = parent;
        this.source = source;
        this.titleText = titleText;
        this.selfCapeCatalogPreload = selfCapeCatalogPreload;
        this.disguiseMode = disguiseMode;
        this.customCapeTools = true;
    }

    private static CapeListSource disguiseSource() {
        return new CapeListSource() {
            @Override
            public List<LocalDisguise.CapeOptionInfo> getOptions() {
                return LocalDisguise.getCapeOptionsForGui();
            }

            @Override
            public int getSelectedIndex() {
                return LocalDisguise.getSelectedCapeGuiIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                LocalDisguise.setCapeSelectionGuiIndex(index);
            }
        };
    }

    @Override
    public void initGui() {
        this.scrollbar.release();
        super.initGui();
        PlayerCustomCapes.ensureLoaded();
        PlayerCapeRatings.ensureLoaded();
        if (selfCapeCatalogPreload) {
            LocalDisguise.prepareSelfCapeCatalogForPicker();
        }
        this.buttonList.clear();
        updateLayout();
        this.applyButton = new GuiButton(BTN_APPLY_ID, 0, 0, 92, 20, EnumChatFormatting.GREEN + "Select");
        this.backButton = new GuiButton(BTN_BACK_ID, 0, 0, 92, 20, EnumChatFormatting.GRAY + "Back");
        this.buttonList.add(this.applyButton);
        this.buttonList.add(this.backButton);
        if (customCapeTools) {
            this.editButton = new GuiButton(BTN_EDIT_ID, 0, 0, 92, 20, EnumChatFormatting.AQUA + "Edit design");
            this.shareButton = new GuiButton(BTN_SHARE_ID, 0, 0, 92, 20,
                    EnumChatFormatting.LIGHT_PURPLE + "Share to friend");
            this.buttonList.add(this.editButton);
            if (CustomCapeShareService.isSharingEnabled()) {
                this.buttonList.add(this.shareButton);
            } else {
                this.shareButton = null;
            }
        }
        if (!disguiseMode) {
            this.peerBroadcastButton = new GuiButton(BTN_PEER_BROADCAST_ID, 0, 0, 176, PREVIEW_BTN_H,
                    peerBroadcastButtonLabel());
            this.buttonList.add(this.peerBroadcastButton);
        }
        if (!disguiseMode) {
            this.capeSourceButton = new GuiButton(BTN_CAPE_SOURCE_ID, 0, 0, 176, PREVIEW_BTN_H,
                    capeSourceButtonLabel());
            this.buttonList.add(this.capeSourceButton);
        }
        // The source toggle is created after the first layout pass; recalculate before positioning its controls.
        updateLayout();
        updateButtonPositions();
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        restoreListPosition(opts);
        this.initializedOnce = true;
        refreshPreviewPlayer();
        refreshButtonState();
        if (selfCapeCatalogPreload && LocalDisguise.isLocalSelfCapeCatalogLoading()) {
            selfCatalogRefreshPending = true;
        }
        lastSelfOptionCount = opts.size();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        int count = opts == null ? 0 : opts.size();
        if (selfCapeCatalogPreload) {
            if (LocalDisguise.isLocalSelfCapeCatalogLoading()) {
                selfCatalogRefreshPending = true;
                return;
            }
        } else if (LocalDisguise.isEnabled()) {
            if (count != lastSelfOptionCount) {
                selfCatalogRefreshPending = true;
            }
        } else {
            return;
        }
        if (!selfCatalogRefreshPending && count == lastSelfOptionCount) {
            return;
        }
        selfCatalogRefreshPending = false;
        lastSelfOptionCount = count;
        restoreListPosition(opts);
        refreshPreviewPlayer();
        refreshButtonState();
    }

    void focusCapeAfterEditorSave(String savedId) {
        if (savedId == null || savedId.trim().isEmpty()) {
            this.focusOptionKeyAfterEditor = null;
            return;
        }
        this.focusOptionKeyAfterEditor = PlayerCustomCapes.KEY_PREFIX + savedId.trim();
    }

    private void restoreListPosition(List<LocalDisguise.CapeOptionInfo> opts) {
        if (opts == null || opts.isEmpty()) {
            this.previewIndex = 0;
            this.scrollIndex = 0;
            return;
        }
        String focusKey = this.focusOptionKeyAfterEditor;
        this.focusOptionKeyAfterEditor = null;
        int focusIndex = focusKey == null || focusKey.isEmpty() ? -1 : findOptionIndexByKey(opts, focusKey);
        if (focusIndex >= 0) {
            this.previewIndex = focusIndex;
            scrollToFocusOnlyIfNeeded(opts, focusIndex);
            return;
        }
        this.previewIndex = clamp(source.getSelectedIndex(), 0, Math.max(0, opts.size() - 1));
        if (initializedOnce && this.previewIndex < capeListDataVisibleRows()) {
            this.scrollIndex = 0;
            clampScrollToRange(opts);
        } else {
            this.scrollIndex = Math.max(0, this.previewIndex - 3);
            ensureScrollInRange(opts);
        }
    }

    private int findOptionIndexByKey(List<LocalDisguise.CapeOptionInfo> opts, String optionKey) {
        if (opts == null || optionKey == null || optionKey.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < opts.size(); i++) {
            LocalDisguise.CapeOptionInfo opt = opts.get(i);
            if (opt != null && optionKey.equals(opt.optionKey)) {
                return i;
            }
        }
        return -1;
    }

    private void scrollToFocusOnlyIfNeeded(List<LocalDisguise.CapeOptionInfo> opts, int focusIndex) {
        int visibleRows = capeListDataVisibleRows();
        if (focusIndex >= 0 && focusIndex < visibleRows) {
            this.scrollIndex = 0;
            clampScrollToRange(opts);
            return;
        }
        int maxScroll = Math.max(0, opts.size() - visibleRows);
        this.scrollIndex = clamp(focusIndex, 0, maxScroll);
        ensureScrollInRange(opts);
    }

    @Override
    public void onGuiClosed() {
        this.draggingPreview = false;
        this.scrollbar.release();
        MountPreviewHelper.discardCapePreviewPlayer(this.mc);
        this.previewPlayer = null;
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            this.scrollbar.release();
            scrollByRows(dWheel > 0 ? -3 : 3);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        updateLayout();
        updateButtonPositions();

        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        this.previewIndex = clamp(this.previewIndex, 0, Math.max(0, opts.size() - 1));
        clampScrollToRange(opts);

        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj, titleText, this.width / 2,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        if (disguiseMode) {
            drawVisibilityInfo();
        }

        if (opts.isEmpty()) {
            drawCapeListPanelBackground();
            drawCapeListEmptyMessage();
            refreshButtonState();
            super.drawScreen(mouseX, mouseY, partialTicks);
            drawHoverTooltips(mouseX, mouseY);
            return;
        }

        drawCapeList(opts, mouseX, mouseY);
        drawPreview(opts, partialTicks);
        refreshButtonState();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHoverTooltips(mouseX, mouseY);
    }

    private void drawVisibilityInfo() {
        int y = GuiHeader.STANDARD_SUBTITLE_Y;
        int cx = this.width / 2;
        for (String line : visibilityInfoLines()) {
            int w = this.fontRendererObj.getStringWidth(line);
            this.fontRendererObj.drawStringWithShadow(line, cx - w / 2, y, 0xAAAAAA);
            y += this.fontRendererObj.FONT_HEIGHT + 2;
        }
    }

    private List<String> visibilityInfoLines() {
        if (me.napim.gomod.features.GomodPeerDisguise.isSharingLocalDisguise()) {
            return Arrays.asList(EnumChatFormatting.GRAY
                    + "* gomod users see the cape selected for your disguise.");
        }
        return Arrays.asList(
                EnumChatFormatting.GRAY + "* While disguised, gomod users will see you wearing the cape highlighted in "
                        + EnumChatFormatting.LIGHT_PURPLE + "purple,",
                EnumChatFormatting.GRAY + "not the cape you selected for your local disguise."
        );
    }

    private void drawHoverTooltips(int mouseX, int mouseY) {
        if (disguiseMode && hoveredListIndex >= 0) {
            List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
            if (hoveredListIndex < opts.size()
                    && PlayerStandaloneCape.isCapeOptionShownToPeers(opts.get(hoveredListIndex))) {
                drawHoveringText(Arrays.asList(
                        EnumChatFormatting.LIGHT_PURPLE + "gomod users see you wearing this cape."),
                        mouseX, Math.max(0, mouseY - 12));
                return;
            }
        }
        if (peerBroadcastButton != null && isHovering(peerBroadcastButton, mouseX, mouseY)) {
            drawHoveringText(peerBroadcastTooltipLines(), mouseX, Math.max(0, mouseY - 12));
        } else if (capeSourceButton != null && isHovering(capeSourceButton, mouseX, mouseY)) {
            drawHoveringText(capeSourceTooltipLines(), mouseX, Math.max(0, mouseY - 12));
        }
    }

    private List<String> peerBroadcastTooltipLines() {
        ArrayList<String> lines = new ArrayList<String>(4);
        if (ConfigManager.isPlayerStandaloneCapeBroadcastToPeers()) {
            lines.add(EnumChatFormatting.GREEN + "gomod players see: gomod cape"
                    + EnumChatFormatting.GRAY + " selected here.");
            if (!GomodFixes.isWeaveRuntime()) {
                lines.add(EnumChatFormatting.GRAY + "With Default selected, gomod viewers see your normal cape:");
                lines.add(EnumChatFormatting.GRAY + "each viewer's Forge client sees OptiFine first, then Mojang;");
                lines.add(EnumChatFormatting.GRAY + "if neither is available, no cape is shown.");
            }
        } else {
            if (GomodFixes.isWeaveRuntime()) {
                lines.add(EnumChatFormatting.AQUA + "gomod players see: Default cape");
                lines.add(EnumChatFormatting.GRAY + "Gomod players see your Default/Lunar cape instead of your gomod cape.");
            } else {
                lines.add(EnumChatFormatting.AQUA + "gomod players see: Default cape");
                lines.add(EnumChatFormatting.GRAY + "gomod viewers see your normal cape:");
                lines.add(EnumChatFormatting.GRAY + "each viewer's Forge client sees OptiFine first, then Mojang;");
                lines.add(EnumChatFormatting.GRAY + "if neither is available, no cape is shown.");
            }
        }
        return lines;
    }

    private static boolean isHovering(GuiButton btn, int mx, int my) {
        return btn != null && btn.visible
                && mx >= btn.xPosition && mx < btn.xPosition + btn.width
                && my >= btn.yPosition && my < btn.yPosition + btn.height;
    }

    private static String peerBroadcastButtonLabel() {
        return ConfigManager.isPlayerStandaloneCapeBroadcastToPeers()
                ? EnumChatFormatting.GREEN + "gomod players see: gomod cape"
                : EnumChatFormatting.AQUA + "gomod players see: Default cape";
    }

    private List<String> capeSourceTooltipLines() {
        if (ConfigManager.isPlayerStandaloneCapePreferGomod()) {
            if (GomodFixes.isWeaveRuntime()) {
                return Arrays.asList(EnumChatFormatting.GREEN + "gomod cape selected:",
                        EnumChatFormatting.GRAY + "hides your Default/Lunar cloak and renders the cape selected here.");
            }
            return Arrays.asList(EnumChatFormatting.GREEN + "gomod cape selected:",
                    EnumChatFormatting.GRAY + "Shows the cape selected here instead of your Default cape.",
                    EnumChatFormatting.GRAY + "Default means OptiFine if available, otherwise Mojang.",
                    EnumChatFormatting.GRAY + "If neither is available, no cape is shown.");
        }
        if (GomodFixes.isWeaveRuntime()) {
            return Arrays.asList(EnumChatFormatting.AQUA + "Default cape selected:",
                    EnumChatFormatting.GRAY + "Uses your Default/Lunar cape instead of your gomod cape.");
        }
        return Arrays.asList(EnumChatFormatting.AQUA + "Default cape selected:",
                EnumChatFormatting.GRAY + "Uses your normal Forge cape: OptiFine takes priority over Mojang.",
                EnumChatFormatting.GRAY + "If neither is available, no cape is shown.");
    }

    private static String capeSourceButtonLabel() {
        if (ConfigManager.isPlayerStandaloneCapePreferGomod()) {
            return EnumChatFormatting.GREEN + "You see: gomod cape";
        }
        return EnumChatFormatting.AQUA + "You see: Default cape";
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (mouseButton == 0) {
            CapeListScrollbar.Geometry geometry = scrollbarGeometry(source.getOptions().size(), capeListDataVisibleRows());
            if (this.scrollbar.press(mouseX, mouseY, geometry)) {
                this.draggingPreview = false;
                this.scrollIndex = this.scrollbar.drag(mouseY, geometry);
                return;
            }
        }
        if (mouseButton == 0 && handleListRowClick(mouseX, mouseY)) {
            return;
        }
        if (mouseButton == 0 && isInsidePreview(mouseX, mouseY)) {
            this.draggingPreview = true;
            this.lastDragMouseX = mouseX;
            this.lastDragMouseY = mouseY;
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && this.scrollbar.isDragging()) {
            this.scrollIndex = this.scrollbar.drag(mouseY,
                    scrollbarGeometry(source.getOptions().size(), capeListDataVisibleRows()));
            return;
        }
        if (this.draggingPreview && clickedMouseButton == 0) {
            this.previewYaw += (mouseX - this.lastDragMouseX) * PREVIEW_DRAG_SENSITIVITY;
            this.previewPitch += (this.lastDragMouseY - mouseY) * PREVIEW_PITCH_SENSITIVITY;
            this.previewPitch = Math.max(-55.0F, Math.min(55.0F, this.previewPitch));
            this.lastDragMouseX = mouseX;
            this.lastDragMouseY = mouseY;
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            this.draggingPreview = false;
            this.scrollbar.release();
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button == null) {
            return;
        }
        if (button.id == BTN_BACK_ID) {
            this.mc.displayGuiScreen(parent);
        } else if (button.id == BTN_APPLY_ID) {
            applySelection();
        } else if (button.id == BTN_EDIT_ID) {
            openEditorForSelection();
        } else if (button.id == BTN_SHARE_ID) {
            openShareForSelection();
        } else if (button.id == BTN_PEER_BROADCAST_ID) {
            ConfigManager.setPlayerStandaloneCapeBroadcastToPeers(!ConfigManager.isPlayerStandaloneCapeBroadcastToPeers());
            if (this.peerBroadcastButton != null) {
                this.peerBroadcastButton.displayString = peerBroadcastButtonLabel();
            }
            PlayerStandaloneCape.notifyWearChanged();
        } else if (button.id == BTN_CAPE_SOURCE_ID) {
            ConfigManager.setPlayerStandaloneCapePreferGomod(
                    !ConfigManager.isPlayerStandaloneCapePreferGomod());
            if (this.capeSourceButton != null) {
                this.capeSourceButton.displayString = capeSourceButtonLabel();
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == Keyboard.KEY_UP) {
            movePreview(-1);
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            movePreview(1);
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            movePreview(-Math.max(1, getVisibleRows() - 1));
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            movePreview(Math.max(1, getVisibleRows() - 1));
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            this.previewYaw -= 18.0F;
            return;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            this.previewYaw += 18.0F;
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            applySelection();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void updateLayout() {
        int infoLines = disguiseMode ? visibilityInfoLines().size() : 0;
        int infoBottom = GuiHeader.STANDARD_SUBTITLE_Y + infoLines * (this.fontRendererObj.FONT_HEIGHT + 2) + 6;
        int top = Math.max(48, infoBottom);
        int bottom = Math.max(top + 80, this.height - 28);
        int sidePad = 18;
        int gap = 12;
        int contentWidth = Math.max(280, Math.min(this.width - sidePad * 2, 700));
        int left = (this.width - contentWidth) / 2;
        this.stackedLayout = this.width < 570 || this.height < 310;

        if (this.stackedLayout) {
            int previewHeight = Math.max(96, Math.min(150, (bottom - top) / 2));
            this.previewLeft = left;
            this.previewTop = top;
            this.previewWidth = contentWidth;
            this.previewBottom = top + previewHeight;
            this.listLeft = left;
            this.listTop = this.previewBottom + gap;
            this.listWidth = contentWidth;
            this.listBottom = bottom;
        } else {
            this.listWidth = Math.min(294, Math.max(238, contentWidth * 43 / 100));
            this.previewWidth = contentWidth - this.listWidth - gap;
            this.listLeft = left;
            this.listTop = top;
            this.listBottom = bottom;
            this.previewLeft = this.listLeft + this.listWidth + gap;
            this.previewTop = top;
            this.previewBottom = bottom;
        }

        this.rowsTop = this.listTop + 6;
        int listPad = 6;
        int listInnerH = Math.max(ROW_HEIGHT, this.listBottom - this.listTop - listPad * 2);
        int fittedRows = Math.max(1, (listInnerH + ROW_GAP) / ROW_HEIGHT);
        this.rowsBottom = this.rowsTop + fittedRows * ROW_HEIGHT - ROW_GAP;
        this.listBottom = this.rowsBottom + listPad;
        this.previewCenterX = this.previewLeft + this.previewWidth / 2;
        int previewHeaderH = disguiseMode
                ? 34
                : PREVIEW_BTN_H + 6 + this.fontRendererObj.FONT_HEIGHT * 2 + 10;
        this.previewModelTop = this.previewTop + previewHeaderH + 8;
        this.previewModelBottom = this.previewBottom - 8;

        int modelAreaHeight = Math.max(80, this.previewModelBottom - this.previewModelTop);
        this.previewScale = clamp(modelAreaHeight / 2, 54, 84);
        int modelHeight = (int) (this.previewScale * 1.95F);
        this.previewBaseY = this.previewModelTop + (modelAreaHeight + modelHeight) / 2 - 4;
        updatePreviewFooterLayout();
    }

    /** Buttons and drag hint overlay the bottom of the preview panel — panel size unchanged. */
    private void updatePreviewFooterLayout() {
        int footerBottom = this.previewBottom - PREVIEW_FOOTER_PAD;
        int bottomRowY = footerBottom - PREVIEW_BTN_H;
        if (customCapeTools) {
            this.previewDragHintY = bottomRowY - PREVIEW_BTN_H - PREVIEW_BTN_GAP - 10;
        } else {
            this.previewDragHintY = bottomRowY - 10;
        }
    }

    private int previewOrbitDragBottom() {
        int footerBottom = this.previewBottom - PREVIEW_FOOTER_PAD;
        int bottomRowY = footerBottom - PREVIEW_BTN_H;
        if (customCapeTools) {
            return bottomRowY - PREVIEW_BTN_H - PREVIEW_BTN_GAP - 2;
        }
        return bottomRowY - 2;
    }

    private void updateButtonPositions() {
        if (this.applyButton == null || this.backButton == null) {
            return;
        }
        int cx = this.previewCenterX;
        int footerBottom = this.previewBottom - PREVIEW_FOOTER_PAD;
        int bottomRowY = footerBottom - PREVIEW_BTN_H;
        if (customCapeTools && this.editButton != null) {
            int topRowY = bottomRowY - PREVIEW_BTN_H - PREVIEW_BTN_GAP;
            this.editButton.xPosition = cx - this.editButton.width - 5;
            this.editButton.yPosition = topRowY;
            if (this.shareButton != null) {
                this.shareButton.xPosition = cx + 5;
                this.shareButton.yPosition = topRowY;
            }
        }
        this.applyButton.xPosition = cx - this.applyButton.width - 5;
        this.applyButton.yPosition = bottomRowY;
        this.backButton.xPosition = cx + 5;
        this.backButton.yPosition = bottomRowY;
        if (!disguiseMode && this.peerBroadcastButton != null) {
            this.peerBroadcastButton.xPosition = this.previewLeft + (this.previewWidth - this.peerBroadcastButton.width) / 2;
            this.peerBroadcastButton.yPosition = this.previewTop + 30;
        }
        if (!disguiseMode && this.capeSourceButton != null) {
            this.capeSourceButton.xPosition = this.previewLeft + (this.previewWidth - this.capeSourceButton.width) / 2;
            this.capeSourceButton.yPosition = this.previewTop + 6;
        }
    }

    private void drawCapeListPanelBackground() {
        Gui.drawRect(this.listLeft, this.listTop, this.listLeft + this.listWidth, this.listBottom, PANEL_BG);
        drawBorder(this.listLeft, this.listTop, this.listWidth, this.listBottom - this.listTop, PANEL_BORDER);
    }

    private void drawCapeListEmptyMessage() {
        if (isPlayerCapesLoading()) {
            drawPlayerCapesLoadingBanner();
            return;
        }
        String emptyLine = EnumChatFormatting.RED + "No capes available";
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(emptyLine);
        int w = this.fontRendererObj.getStringWidth(plain);
        int x = this.listLeft + (this.listWidth - w) / 2;
        int y = this.rowsTop + (ROW_HEIGHT - this.fontRendererObj.FONT_HEIGHT) / 2;
        this.fontRendererObj.drawStringWithShadow(emptyLine, x, y, 0xFFFFFF);
    }

    private boolean isPlayerCapesLoading() {
        return selfCapeCatalogPreload && LocalDisguise.isLocalSelfCapeCatalogLoading();
    }

    private int capeListLoadingBannerHeight() {
        return isPlayerCapesLoading() ? ROW_HEIGHT : 0;
    }

    private int capeListDataRowsTop() {
        return this.rowsTop + capeListLoadingBannerHeight();
    }

    private int capeListDataVisibleRows() {
        int total = getVisibleRows();
        if (!isPlayerCapesLoading()) {
            return total;
        }
        return Math.max(1, total - 1);
    }

    private void drawPlayerCapesLoadingBanner() {
        String loadingText = getPlayerCapesLoadingText();
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(loadingText);
        int w = this.fontRendererObj.getStringWidth(plain);
        int x = this.listLeft + (this.listWidth - w) / 2;
        int y = this.rowsTop + (ROW_HEIGHT - ROW_GAP - this.fontRendererObj.FONT_HEIGHT) / 2;
        this.fontRendererObj.drawStringWithShadow(loadingText, x, y, 0xFFFFFF);
        int lineY = this.rowsTop + ROW_HEIGHT - ROW_GAP;
        Gui.drawRect(this.listLeft + 8, lineY, this.listLeft + this.listWidth - 8, lineY + 1, 0x44334455);
    }

    private String getPlayerCapesLoadingText() {
        String username = null;
        if (this.mc != null && this.mc.getSession() != null) {
            username = this.mc.getSession().getUsername();
        }
        if ((username == null || username.trim().isEmpty()) && this.mc != null && this.mc.thePlayer != null) {
            username = this.mc.thePlayer.getName();
        }
        if (username == null || username.trim().isEmpty()) {
            username = "player";
        }
        return EnumChatFormatting.YELLOW + "Loading " + username + "'s capes...";
    }

    private void drawCapeList(List<LocalDisguise.CapeOptionInfo> opts, int mouseX, int mouseY) {
        drawCapeListPanelBackground();
        if (isPlayerCapesLoading()) {
            drawPlayerCapesLoadingBanner();
        }

        int applied = clamp(source.getSelectedIndex(), 0, Math.max(0, opts.size() - 1));
        int visibleRows = capeListDataVisibleRows();
        int right = this.listLeft + this.listWidth;
        this.hoveredListIndex = -1;
        for (int row = 0; row < visibleRows; row++) {
            int idx = this.scrollIndex + row;
            if (idx >= opts.size()) {
                break;
            }
            int y = capeListDataRowsTop() + row * ROW_HEIGHT;
            int rowBottom = y + ROW_HEIGHT - ROW_GAP;
            boolean hovered = mouseX >= this.listLeft + 5 && mouseX < right - 5 && mouseY >= y && mouseY < rowBottom;
            if (hovered) {
                this.hoveredListIndex = idx;
            }
            LocalDisguise.CapeOptionInfo opt = opts.get(idx);
            boolean selected = idx == this.previewIndex;
            boolean appliedRow = idx == applied;
            boolean peerVisible = disguiseMode && PlayerStandaloneCape.isCapeOptionShownToPeers(opt);
            int bg;
            if (appliedRow) {
                bg = hovered ? ROW_APPLIED_HOVER_BG : ROW_APPLIED_BG;
            } else if (peerVisible) {
                bg = hovered ? ROW_PEER_VISIBLE_HOVER_BG : ROW_PEER_VISIBLE_BG;
            } else if (selected) {
                bg = ROW_SELECTED_BG;
            } else if (!opt.selectable) {
                bg = hovered ? ROW_UNOWNED_HOVER_BG : ROW_UNOWNED_BG;
            } else if (opt.owned && CapePickerCatalog.shouldHighlightOwnedRow(opt.optionKey, opt.createEntry)) {
                bg = hovered ? ROW_OWNED_HOVER_BG : ROW_OWNED_BG;
            } else {
                bg = hovered ? ROW_UNOWNED_HOVER_BG : ROW_UNOWNED_BG;
            }
            Gui.drawRect(this.listLeft + 5, y, right - 5, rowBottom, bg);
            if (appliedRow) {
                drawBorder(this.listLeft + 5, y, this.listWidth - 10, rowBottom - y, ROW_APPLIED_BORDER);
            } else if (peerVisible) {
                drawBorder(this.listLeft + 5, y, this.listWidth - 10, rowBottom - y, ROW_PEER_VISIBLE_BORDER);
            }

            drawCapeThumb(opt, this.listLeft + 10, y + 3, ICON_W, ICON_H);

            boolean liked = opt.optionKey != null && PlayerCapeRatings.isLiked(opt.optionKey);
            String statusMark = rowStatusMark(appliedRow);
            RowActionLayout layout = computeRowActionLayout(opt, statusMark, right, hovered, liked);
            int labelMaxW = Math.max(40, layout.labelRight - (this.listLeft + 58) - 4);

            int color;
            if (!opt.selectable) {
                color = 0x66889999;
            } else if (appliedRow) {
                color = 0xE8FFEE;
            } else if (peerVisible) {
                color = 0xF3DEFF;
            } else if (selected) {
                color = 0xFFFFFF;
            } else if (opt.owned && CapePickerCatalog.shouldHighlightOwnedRow(opt.optionKey, opt.createEntry)) {
                color = 0xE0FFE8;
            } else {
                color = 0xD8E0E8;
            }
            drawCapeLabel(opt, this.listLeft + 58, y + 7, labelMaxW, color, false);
            if (layout.showHeart) {
                drawRowIcon(heartTextureFor(opt), layout.heartX, y + 7, mouseX, mouseY,
                        layout.heartX, layout.heartX + ROW_ACTION_ICON_SIZE, y + 7, y + 7 + ROW_ACTION_ICON_SIZE);
            }
            if (layout.showEditOnHover && hovered) {
                drawRowIcon(EDIT_ROW_ICON_TEXTURE, layout.editX, y + 7, mouseX, mouseY,
                        layout.editX, layout.editX + ROW_ACTION_ICON_SIZE, y + 7, y + 7 + ROW_ACTION_ICON_SIZE);
            }
            if (layout.showDelete) {
                drawDeleteIcon(layout.deleteX, y + 8, mouseX, mouseY);
            }
            if (statusMark != null) {
                this.fontRendererObj.drawStringWithShadow(statusMark,
                        layout.rowInnerRight - layout.statusMarkW, y + 7, 0xBFFFD0);
            }
        }

        drawScrollbar(opts.size(), visibleRows);
    }

    private void drawPreview(List<LocalDisguise.CapeOptionInfo> opts, float partialTicks) {
        Gui.drawRect(this.previewLeft, this.previewTop, this.previewLeft + this.previewWidth, this.previewBottom, PANEL_BG);
        drawBorder(this.previewLeft, this.previewTop, this.previewWidth, this.previewBottom - this.previewTop, PANEL_BORDER);

        LocalDisguise.CapeOptionInfo opt = opts.get(this.previewIndex);
        int labelY = disguiseMode
                ? this.previewTop + 12
                : this.previewTop + 6 + PREVIEW_BTN_H + 6
                + (this.capeSourceButton == null ? 0 : PREVIEW_BTN_H + PREVIEW_BTN_GAP);
        drawCapeLabel(opt, this.previewLeft + 14, labelY, this.previewWidth - 28, 0xFFFFFF, true);
        String indexText = (this.previewIndex + 1) + "/" + opts.size();
        int indexY = labelY + this.fontRendererObj.FONT_HEIGHT + 3;
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + indexText,
                this.previewCenterX - this.fontRendererObj.getStringWidth(indexText) / 2, indexY, 0xFFFFFF);

        refreshPreviewPlayer();
        if (this.previewPlayer != null) {
            float renderYaw = tickPreviewDisplayYaw(partialTicks);
            float renderPitch = tickPreviewDisplayPitch(partialTicks);
            float savedPlayerViewY = this.mc.getRenderManager().playerViewY;
            try {
                MountPreviewHelper.renderCapePreviewPlayer(this.mc, this.previewPlayer, this.previewCenterX,
                        this.previewBaseY, this.previewScale, 1.0F, renderYaw, renderPitch, 1.0F);
            } finally {
                this.mc.getRenderManager().setPlayerViewY(savedPlayerViewY);
                // Lunar cape layers can alter alpha/cull/lightmap state. Since this GUI does not pause the
                // world, leaked state is visible on the next terrain frame (especially cutout leaves).
                me.napim.gomod.util.HudGlState.restoreForOverlayText();
            }
        }

        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "Drag to orbit",
                this.previewCenterX - this.fontRendererObj.getStringWidth("Drag to orbit") / 2,
                this.previewDragHintY, 0x888888);

        drawCapeThumb(opt, this.previewLeft + this.previewWidth - ICON_W - 12, this.previewTop + 10, ICON_W, ICON_H);
    }

    private void drawScrollbar(int size, int visibleRows) {
        CapeListScrollbar.Geometry g = scrollbarGeometry(size, visibleRows);
        if (!g.isVisible()) return;
        Gui.drawRect(g.x, g.top, g.x + 2, g.top + g.height, 0x44202A34);
        Gui.drawRect(g.x - 1, g.thumbTop, g.x + 3, g.thumbTop + g.thumbHeight, 0xAA7AA6C2);
    }

    private CapeListScrollbar.Geometry scrollbarGeometry(int size, int visibleRows) {
        return new CapeListScrollbar.Geometry(this.listLeft + this.listWidth - 4,
                capeListDataRowsTop(), visibleRows * ROW_HEIGHT - ROW_GAP,
                size, visibleRows, this.scrollIndex);
    }

    private void drawBorder(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        Gui.drawRect(x, y, x + 1, y + h, color);
        Gui.drawRect(x + w - 1, y, x + w, y + h, color);
    }

    private int getVisibleRows() {
        return Math.max(1, (this.rowsBottom - this.rowsTop + ROW_GAP) / ROW_HEIGHT);
    }

    private void clampScrollToRange(List<LocalDisguise.CapeOptionInfo> opts) {
        int visibleRows = capeListDataVisibleRows();
        int maxScroll = Math.max(0, opts.size() - visibleRows);
        this.scrollIndex = clamp(this.scrollIndex, 0, maxScroll);
    }

    private void ensureScrollInRange(List<LocalDisguise.CapeOptionInfo> opts) {
        clampScrollToRange(opts);
        if (opts.isEmpty()) {
            return;
        }
        int visibleRows = capeListDataVisibleRows();
        int maxScroll = Math.max(0, opts.size() - visibleRows);
        if (this.previewIndex < this.scrollIndex) {
            this.scrollIndex = this.previewIndex;
        } else if (this.previewIndex >= this.scrollIndex + visibleRows) {
            this.scrollIndex = clamp(this.previewIndex - visibleRows + 1, 0, maxScroll);
        }
    }

    private void scrollByRows(int rows) {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        if (opts.isEmpty()) {
            return;
        }
        this.scrollIndex += rows;
        clampScrollToRange(opts);
    }

    private boolean handleListRowClick(int mouseX, int mouseY) {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        if (opts.isEmpty() || mouseX < this.listLeft || mouseX >= this.listLeft + this.listWidth
                || mouseY < this.rowsTop || mouseY >= this.rowsBottom) {
            return false;
        }
        if (isPlayerCapesLoading() && mouseY < capeListDataRowsTop()) {
            return false;
        }
        int row = (mouseY - capeListDataRowsTop()) / ROW_HEIGHT;
        int idx = this.scrollIndex + row;
        if (idx < 0 || idx >= opts.size()) {
            return false;
        }
        LocalDisguise.CapeOptionInfo opt = opts.get(idx);
        int y = capeListDataRowsTop() + row * ROW_HEIGHT;
        int applied = clamp(source.getSelectedIndex(), 0, Math.max(0, opts.size() - 1));
        boolean liked = opt.optionKey != null && PlayerCapeRatings.isLiked(opt.optionKey);
        RowActionLayout layout = computeRowActionLayout(opt, rowStatusMark(idx == applied),
                this.listLeft + this.listWidth, true, liked);

        if (layout.showDelete && hitIcon(mouseX, mouseY, layout.deleteX, y + 8, ROW_DELETE_ICON_SIZE)) {
            confirmDeleteCustomCape(opt);
            return true;
        }
        if (layout.showHeart && hitIcon(mouseX, mouseY, layout.heartX, y + 7, ROW_ACTION_ICON_SIZE)) {
            if (opt.optionKey != null) {
                PlayerCapeRatings.toggleLiked(opt.optionKey);
                PlayerStandaloneCape.refreshCapeListSortOrder();
                List<LocalDisguise.CapeOptionInfo> opts2 = source.getOptions();
                String key = opt.optionKey;
                for (int i = 0; i < opts2.size(); i++) {
                    if (opts2.get(i) != null && key.equals(opts2.get(i).optionKey)) {
                        this.previewIndex = i;
                        break;
                    }
                }
                ensureScrollInRange(opts2);
            }
            return true;
        }
        if (layout.showEditOnHover && hitIcon(mouseX, mouseY, layout.editX, y + 7, ROW_ACTION_ICON_SIZE)) {
            openEditorForRow(opt);
            return true;
        }
        if (customCapeTools && opt.createEntry) {
            this.mc.displayGuiScreen(new CustomCapeEditorGUI(this));
            return true;
        }
        if (!opt.selectable) {
            this.previewIndex = idx;
            ensureScrollInRange(opts);
            refreshPreviewPlayer();
            return true;
        }
        this.previewIndex = idx;
        ensureScrollInRange(opts);
        refreshPreviewPlayer();
        long now = System.currentTimeMillis();
        if (idx == this.lastListClickIndex && (now - this.lastListClickMs) <= LIST_DOUBLE_CLICK_MS) {
            this.lastListClickIndex = -1;
            this.lastListClickMs = 0L;
            applySelection();
        } else {
            this.lastListClickIndex = idx;
            this.lastListClickMs = now;
        }
        return true;
    }

    private void confirmDeleteCustomCape(final LocalDisguise.CapeOptionInfo opt) {
        if (opt == null || opt.optionKey == null) {
            return;
        }
        final String id = PlayerCustomCapes.idFromOptionKey(opt.optionKey);
        if (id == null) {
            return;
        }
        String title = EnumChatFormatting.RED + "Delete custom cape?";
        String body = EnumChatFormatting.YELLOW + "Remove "
                + EnumChatFormatting.WHITE + EnumChatFormatting.getTextWithoutFormattingCodes(getLabel(opt))
                + EnumChatFormatting.YELLOW + " from your saved capes?";
        this.mc.displayGuiScreen(new GomodConfirmDialog((confirmed, id2) -> {
            if (confirmed) {
                PlayerCustomCapes.delete(id);
                List<LocalDisguise.CapeOptionInfo> opts2 = source.getOptions();
                this.previewIndex = clamp(this.previewIndex, 0, Math.max(0, opts2.size() - 1));
                ensureScrollInRange(opts2);
                refreshPreviewPlayer();
            }
            this.mc.displayGuiScreen(this);
        }, title, body, 0));
    }

    private void openEditorForSelection() {
        LocalDisguise.CapeOptionInfo opt = getPreviewOption();
        if (opt == null || !opt.customUserCape || opt.optionKey == null) {
            return;
        }
        String id = PlayerCustomCapes.idFromOptionKey(opt.optionKey);
        if (id == null) {
            return;
        }
        this.mc.displayGuiScreen(new CustomCapeEditorGUI(this, id));
    }

    /** Row edit icon: always forks to a new saved cape (name required on save). */
    private void openEditorForRow(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null || opt.createEntry || !canEditCapeOption(opt)) {
            return;
        }
        if (opt.customUserCape && opt.optionKey != null) {
            String id = PlayerCustomCapes.idFromOptionKey(opt.optionKey);
            if (id != null) {
                this.mc.displayGuiScreen(new CustomCapeEditorGUI(this, id, true));
                return;
            }
        }
        ResourceLocation cape = getPreviewCapeLocation(opt);
        BufferedImage img = cape != null ? PlayerCustomCapes.loadImageFromCapeTexture(cape) : null;
        if (img == null && opt.imageUrl != null && !opt.imageUrl.trim().isEmpty()) {
            img = loadEditorCanvasFromImageUrl(opt.imageUrl);
        }
        if (img == null) {
            img = PlayerCustomCapes.blankCanvas();
        }
        String base = EnumChatFormatting.getTextWithoutFormattingCodes(getLabel(opt));
        this.mc.displayGuiScreen(new CustomCapeEditorGUI(this, img, "Copy of " + base, isOptifineCapeOption(opt)));
    }

    private static boolean canEditCapeOption(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null || opt.createEntry) {
            return false;
        }
        if (opt.optionKey != null && PlayerStandaloneCape.isNonCapeCatalogKey(opt.optionKey)) {
            return false;
        }
        return opt.customUserCape || opt.location != null || (opt.imageUrl != null && !opt.imageUrl.trim().isEmpty());
    }

    private static boolean isOptifineCapeOption(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null) {
            return false;
        }
        String label = opt.label == null ? "" : opt.label.toLowerCase(java.util.Locale.ROOT);
        String url = opt.imageUrl == null ? "" : opt.imageUrl.toLowerCase(java.util.Locale.ROOT);
        String key = opt.optionKey == null ? "" : opt.optionKey.toLowerCase(java.util.Locale.ROOT);
        return label.contains("optifine") || url.contains("optifine") || key.contains("optifine");
    }

    private static BufferedImage loadEditorCanvasFromImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = HypixelApiHelper.fetchCapeImageBytes(imageUrl.trim());
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img == null ? null : PlayerCustomCapes.normalizeCapeImage(img);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void openShareForSelection() {
        LocalDisguise.CapeOptionInfo opt = getPreviewOption();
        if (opt == null || !opt.customUserCape || opt.optionKey == null) {
            return;
        }
        String id = PlayerCustomCapes.idFromOptionKey(opt.optionKey);
        if (id == null) {
            return;
        }
        java.awt.image.BufferedImage img = PlayerCustomCapes.loadImageForId(id);
        if (img == null) {
            return;
        }
        this.mc.displayGuiScreen(new CustomCapeShareGUI(this, img, getLabel(opt)));
    }

    private boolean isInsidePreview(int mouseX, int mouseY) {
        return mouseX >= this.previewLeft && mouseX < this.previewLeft + this.previewWidth
                && mouseY >= this.previewModelTop && mouseY < previewOrbitDragBottom();
    }

    private float tickPreviewDisplayYaw(float partialTicks) {
        float delta = wrapYawDelta(this.previewYaw - this.previewDisplayYaw);
        float blend = PREVIEW_YAW_SMOOTH * (0.35F + partialTicks * 0.65F);
        this.previewDisplayYaw += delta * Math.min(1.0F, blend);
        return this.previewDisplayYaw;
    }

    private float tickPreviewDisplayPitch(float partialTicks) {
        float delta = this.previewPitch - this.previewDisplayPitch;
        float blend = PREVIEW_PITCH_SMOOTH * (0.35F + partialTicks * 0.65F);
        this.previewDisplayPitch += delta * Math.min(1.0F, blend);
        return this.previewDisplayPitch;
    }

    private static float wrapYawDelta(float degrees) {
        float d = degrees % 360.0F;
        if (d > 180.0F) {
            d -= 360.0F;
        } else if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    private void movePreview(int delta) {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        if (opts.isEmpty()) {
            return;
        }
        this.previewIndex = clamp(this.previewIndex + delta, 0, opts.size() - 1);
        ensureScrollInRange(opts);
        refreshPreviewPlayer();
    }

    private void applySelection() {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        if (opts.isEmpty()) {
            return;
        }
        this.previewIndex = clamp(this.previewIndex, 0, opts.size() - 1);
        LocalDisguise.CapeOptionInfo opt = opts.get(this.previewIndex);
        if (opt != null && opt.createEntry) {
            this.mc.displayGuiScreen(new CustomCapeEditorGUI(this));
            return;
        }
        if (opt == null || !opt.selectable) {
            return;
        }
        source.setSelectedIndex(this.previewIndex);
        List<LocalDisguise.CapeOptionInfo> updated = source.getOptions();
        LocalDisguise.CapeOptionInfo selected = updated.isEmpty()
                ? null : updated.get(clamp(source.getSelectedIndex(), 0, updated.size() - 1));
        if (disguiseMode) {
            CapeVisibilityReminder.onLocalDisguiseCapeSelected(selected);
        } else {
            CapeVisibilityReminder.onStandaloneGomodCapeSelected(selected);
        }
        this.previewIndex = updated.isEmpty()
                ? 0
                : clamp(source.getSelectedIndex(), 0, updated.size() - 1);
        ensureScrollInRange(updated);
        refreshPreviewPlayer();
        refreshButtonState();
    }

    private void refreshButtonState() {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        boolean hasOptions = !opts.isEmpty();
        LocalDisguise.CapeOptionInfo selected = hasOptions ? opts.get(clamp(this.previewIndex, 0, opts.size() - 1)) : null;
        boolean customSelected = selected != null && selected.customUserCape;
        boolean createSelected = selected != null && selected.createEntry;
        if (this.applyButton != null) {
            this.applyButton.enabled = hasOptions && selected != null && selected.selectable && !createSelected;
        }
        if (this.editButton != null) {
            this.editButton.enabled = customSelected;
            this.editButton.visible = customSelected;
        }
        if (this.shareButton != null) {
            this.shareButton.enabled = customSelected;
            this.shareButton.visible = customSelected;
        }
    }

    private void refreshPreviewPlayer() {
        if (this.mc == null || this.mc.theWorld == null) {
            return;
        }
        LocalDisguise.CapeOptionInfo opt = getPreviewOption();
        ResourceLocation cape = getPreviewCapeLocation(opt);
        if (this.previewPlayer == null) {
            this.previewPlayer = MountPreviewHelper.createCapePreviewPlayer(this.mc.theWorld, this.mc, cape);
        } else {
            MountPreviewHelper.updateCapePreviewPlayer(this.mc, this.previewPlayer, cape);
        }
    }

    private LocalDisguise.CapeOptionInfo getPreviewOption() {
        List<LocalDisguise.CapeOptionInfo> opts = source.getOptions();
        if (opts.isEmpty()) {
            return null;
        }
        this.previewIndex = clamp(this.previewIndex, 0, opts.size() - 1);
        return opts.get(this.previewIndex);
    }

    private ResourceLocation getPreviewCapeLocation(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null) {
            return null;
        }
        if (opt.location != null) {
            return opt.location;
        }
        if (isAccountCapeOption(opt)) {
            return getLocalAccountCapeLocation();
        }
        return null;
    }

    private boolean isAccountCapeOption(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null || opt.label == null) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(opt.label).trim();
        return "account cape".equalsIgnoreCase(plain) || "active mojang cape".equalsIgnoreCase(plain);
    }

    private ResourceLocation getLocalAccountCapeLocation() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null
                    || mc.thePlayer.getGameProfile() == null || mc.thePlayer.getGameProfile().getId() == null) {
                return null;
            }
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getGameProfile().getId());
            return info == null ? null : info.getLocationCape();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getLabel(LocalDisguise.CapeOptionInfo opt) {
        return opt == null || opt.label == null || opt.label.trim().isEmpty() ? "Cape" : opt.label;
    }

    private void drawCapeLabel(LocalDisguise.CapeOptionInfo opt, int x, int y, int maxWidth, int color, boolean centered) {
        String label = fitText(getLabel(opt), maxWidth);
        if (centered) {
            this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.LIGHT_PURPLE + label,
                    x + maxWidth / 2 - this.fontRendererObj.getStringWidth(label) / 2, y, 0xFFFFFF);
        } else {
            this.fontRendererObj.drawStringWithShadow(label, x, y, color);
        }
    }

    private SharedCapeLabel sharedCapeLabel(LocalDisguise.CapeOptionInfo opt) {
        if (opt == null) {
            return null;
        }
        String from = opt.sharedFrom == null ? "" : opt.sharedFrom.trim();
        String label = EnumChatFormatting.getTextWithoutFormattingCodes(getLabel(opt)).trim();
        if (from.isEmpty() && label.startsWith("From ")) {
            int colon = label.indexOf(": ", 5);
            if (colon > 5 && colon < label.length() - 2) {
                from = label.substring(5, colon).trim();
                label = label.substring(colon + 2).trim();
            }
        }
        if (from.isEmpty()) {
            return null;
        }
        if (label.isEmpty()) {
            label = "Shared cape";
        }
        return new SharedCapeLabel(from, label);
    }

    private void drawPlayerHead(String playerName, int x, int y, int size) {
        try {
            String key = sanitizeTextureKey(playerName);
            if (key.isEmpty()) {
                return;
            }
            ResourceLocation rl = new ResourceLocation("gomod123", "heads/" + key);
            TextureManager tm = Minecraft.getMinecraft().getTextureManager();
            ITextureObject existing = tm.getTexture(rl);
            if (existing == null) {
                String url = "https://minotar.net/avatar/" + key + "/16";
                tm.loadTexture(rl, new ThreadDownloadImageData(null, url, null, null));
            }
            tm.bindTexture(rl);
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        } catch (Throwable ignored) {
        }
    }

    private static String sanitizeTextureKey(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class SharedCapeLabel {
        final String sender;
        final String label;

        SharedCapeLabel(String sender, String label) {
            this.sender = sender;
            this.label = label;
        }
    }

    private String fitText(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        String out = text;
        while (out.length() > 3 && this.fontRendererObj.getStringWidth(out) > maxWidth) {
            out = out.substring(0, out.length() - 4) + "...";
        }
        return out;
    }

    private void drawCapeThumb(LocalDisguise.CapeOptionInfo opt, int x, int y, int w, int h) {
        ResourceLocation cape = getPreviewCapeLocation(opt);
        if (cape != null) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.getTextureManager().bindTexture(cape);
                if (opt != null && !opt.selectable) {
                    GlStateManager.pushMatrix();
                    GlStateManager.enableBlend();
                    GlStateManager.color(1.0F, 1.0F, 1.0F, 0.42F);
                    GlStateManagerPushHelper.bindAndDrawCapeThumb(x, y, w, h);
                    GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                    GlStateManager.disableBlend();
                    GlStateManager.popMatrix();
                    return;
                }
                GlStateManagerPushHelper.bindAndDrawCapeThumb(x, y, w, h);
                return;
            }
        }
        Gui.drawRect(x, y, x + w, y + h, 0x33000000);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, 0x22FFFFFF);
    }

    private void drawDeleteIcon(int x, int y, int mouseX, int mouseY) {
        drawRowIcon(DELETE_ICON_TEXTURE, x, y, mouseX, mouseY,
                x, x + ROW_DELETE_ICON_SIZE, y, y + ROW_DELETE_ICON_SIZE);
    }

    private ResourceLocation heartTextureFor(LocalDisguise.CapeOptionInfo opt) {
        return opt != null && opt.optionKey != null && PlayerCapeRatings.isLiked(opt.optionKey)
                ? HEART_LIKED_TEXTURE
                : HEART_NOT_TEXTURE;
    }

    private static boolean hitIcon(int mouseX, int mouseY, int x, int y, int size) {
        return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
    }

    private void drawRowIcon(ResourceLocation texture, int x, int y, int mouseX, int mouseY,
            int hitX0, int hitX1, int hitY0, int hitY1) {
        boolean hover = mouseX >= hitX0 && mouseX < hitX1 && mouseY >= hitY0 && mouseY < hitY1;
        float alpha = hover ? 1.0F : 0.82F;
        int fallbackSize = texture == DELETE_ICON_TEXTURE ? ROW_DELETE_ICON_SIZE : ROW_ACTION_ICON_SIZE;
        if (GomodClasspathTextures.ensureLoaded(this.mc, texture) == null) {
            if (texture == DELETE_ICON_TEXTURE) {
                this.fontRendererObj.drawString(EnumChatFormatting.RED + "x", x + 2, y + 1, 0xFFFFFFFF);
            }
            return;
        }
        int texW = GomodClasspathTextures.getWidth(texture, fallbackSize);
        int texH = GomodClasspathTextures.getHeight(texture, fallbackSize);
        float scale = Math.min(fallbackSize / (float) texW, fallbackSize / (float) texH);
        int drawW = Math.max(1, Math.round(texW * scale));
        int drawH = Math.max(1, Math.round(texH * scale));
        int drawX = x + (fallbackSize - drawW) / 2;
        int drawY = y + (fallbackSize - drawH) / 2;
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        GomodClasspathTextures.bindTexture(this.mc, texture);
        Gui.drawScaledCustomSizeModalRect(drawX, drawY, 0, 0, texW, texH, drawW, drawH, texW, texH);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private static String rowStatusMark(boolean applied) {
        return applied ? "selected" : null;
    }

    private RowActionLayout computeRowActionLayout(LocalDisguise.CapeOptionInfo opt, String statusMark,
            int listRight, boolean rowHovered, boolean liked) {
        RowActionLayout layout = new RowActionLayout();
        layout.rowInnerRight = listRight - 12;
        layout.statusMarkW = statusMark == null ? 0 : this.fontRendererObj.getStringWidth(statusMark);
        layout.showDelete = customCapeTools && opt != null && opt.customUserCape;
        layout.showHeart = customCapeTools && PlayerCapeRatings.canRate(opt) && (liked || rowHovered);
        layout.showEditOnHover = customCapeTools && canEditCapeOption(opt);
        int x = layout.rowInnerRight - layout.statusMarkW - (layout.statusMarkW > 0 ? 6 : 0);
        if (layout.showDelete) {
            layout.deleteX = x - ROW_DELETE_ICON_SIZE;
            x = layout.deleteX - ROW_DELETE_ICON_PAD;
        }
        if (layout.showHeart) {
            layout.heartX = x - ROW_ACTION_ICON_SIZE;
            x = layout.heartX - ROW_ACTION_ICON_PAD;
        }
        if (layout.showEditOnHover) {
            layout.editX = x - ROW_ACTION_ICON_SIZE;
            x = layout.editX - ROW_ACTION_ICON_PAD;
        }
        layout.labelRight = x;
        return layout;
    }

    private static final class RowActionLayout {
        int rowInnerRight;
        int statusMarkW;
        int labelRight;
        int deleteX;
        int heartX;
        int editX;
        boolean showDelete;
        boolean showHeart;
        boolean showEditOnHover;
    }

    private static final class GlStateManagerPushHelper {
        private static void bindAndDrawCapeThumb(int x, int y, int w, int h) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            Gui.drawScaledCustomSizeModalRect(x, y, 0.0f, 0.0f, 64, 32, w, h, 64.0f, 32.0f);
            GlStateManager.disableBlend();
        }
    }
}
