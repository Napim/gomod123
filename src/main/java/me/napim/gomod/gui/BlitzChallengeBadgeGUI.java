package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzChallengeBadgeEquip;
import me.napim.gomod.features.BlitzChallengeBadgeEquip.BadgeAttachPart;
import me.napim.gomod.features.BlitzChallengeBadgeEquip.BadgeSkinLayer;
import me.napim.gomod.features.BlitzChallengeBadgeTextures;
import me.napim.gomod.features.BlitzChallenges.ChallengeId;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Equip unlocked Blitz challenge badges on the local player skin with aim-to-place positioning.
 */
public class BlitzChallengeBadgeGUI extends GuiScreen {

    private static final int BTN_BACK = 0;
    private static final int BTN_APPLY = 1;
    private static final int BTN_NONE = 2;
    private static final int BTN_SCALE_DOWN = 3;
    private static final int BTN_SCALE_UP = 4;
    private static final int BTN_PLACE = 5;
    private static final int BTN_LAYER = 6;

    private static final ChallengeId[] ALL_BADGES = ChallengeId.values();

    private static final int ROW_HEIGHT = 26;
    private static final int ROW_GAP = 2;
    private static final int THUMB_SIZE = 22;
    private static final int PANEL_BG = 0xAA070B10;
    private static final int PANEL_BORDER = 0x664A6278;
    private static final int ROW_BG = 0x44202A34;
    private static final int ROW_LOCKED_BG = 0x33181E26;
    private static final int ROW_HOVER_BG = 0x55354858;
    private static final int ROW_SELECTED_BG = 0x88466B82;
    private static final int ROW_APPLIED_BORDER = 0xAA66FF99;

    private static final float PREVIEW_DRAG_SENSITIVITY = 1.35F;
    private static final float PREVIEW_PITCH_SENSITIVITY = 0.85F;
    private static final float PREVIEW_YAW_SMOOTH = 0.32F;
    private static final float PREVIEW_PITCH_SMOOTH = 0.32F;
    /** Must match {@link MountPreviewHelper#renderEntityPreview} cape-preview orbit pivot. */
    private static final float PREVIEW_ORBIT_PIVOT_Y = 1.05F;
    private static final float PREVIEW_CAPE_TILT_DEG = 18.0F;
    /** Must match {@link #updateLayout()} entity preview height factor. */
    private static final float PREVIEW_MODEL_HEIGHT = 1.95F;
    private static final float BADGE_SCALE_WHEEL_SENS = 0.06F;
    private static final float AIM_PREVIEW_ALPHA = 0.45F;

    private final GuiScreen parent;
    private final ChallengeId initialSelection;

    private GuiButton backButton;
    private GuiButton applyButton;
    private GuiButton noneButton;
    private GuiButton scaleDownButton;
    private GuiButton scaleUpButton;
    private GuiButton placeButton;
    private GuiButton layerButton;

    private int previewIndex;
    private int scrollIndex;
    private EntityOtherPlayerMP previewPlayer;

    private float previewYaw = 155.0F;
    private float previewDisplayYaw = 155.0F;
    private float previewPitch = 14.0F;
    private float previewDisplayPitch = 14.0F;
    private boolean draggingPreview;
    private boolean placingBadge;
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
    private boolean stackedLayout;

    public BlitzChallengeBadgeGUI(GuiScreen parent) {
        this(parent, null);
    }

    public BlitzChallengeBadgeGUI(GuiScreen parent, ChallengeId initialSelection) {
        this.parent = parent;
        this.initialSelection = initialSelection;
    }

    @Override
    public void initGui() {
        BlitzChallengeBadgeEquip.beginGuiDraft();
        BlitzChallengeBadgeTextures.preloadAll(mc);
        super.initGui();
        buttonList.clear();
        updateLayout();

        backButton = new GuiButton(BTN_BACK, 0, 0, 80, 20, EnumChatFormatting.GRAY + "Cancel");
        applyButton = new GuiButton(BTN_APPLY, 0, 0, 80, 20, EnumChatFormatting.GREEN + "Apply");
        noneButton = new GuiButton(BTN_NONE, 0, 0, 80, 20, EnumChatFormatting.RED + "Unequip");
        scaleDownButton = new GuiButton(BTN_SCALE_DOWN, 0, 0, 28, 20, "-");
        scaleUpButton = new GuiButton(BTN_SCALE_UP, 0, 0, 28, 20, "+");
        placeButton = new GuiButton(BTN_PLACE, 0, 0, 100, 20, "Place on skin");
        layerButton = new GuiButton(BTN_LAYER, 0, 0, 100, 20, "Layer: Inner");
        buttonList.add(backButton);
        buttonList.add(applyButton);
        buttonList.add(noneButton);
        buttonList.add(scaleDownButton);
        buttonList.add(scaleUpButton);
        buttonList.add(placeButton);
        buttonList.add(layerButton);

        previewIndex = resolveInitialIndex();
        BlitzChallengeBadgeEquip.selectBadgeForEditing(selectedBadge().getKey(), false);
        scrollIndex = Math.max(0, previewIndex - 3);
        ensureScrollInRange();
        updateButtonPositions();
        refreshPreviewPlayer();
        refreshButtonState();
    }

    @Override
    public void onGuiClosed() {
        draggingPreview = false;
        placingBadge = false;
        BlitzChallengeBadgeEquip.resetRenderAlpha();
        MountPreviewHelper.discardCapePreviewPlayer(mc);
        previewPlayer = null;
        BlitzChallengeBadgeEquip.endGuiDraft(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int resolveInitialIndex() {
        String equipped = BlitzChallengeBadgeEquip.getEquippedKey();
        if (!equipped.isEmpty()) {
            for (int i = 0; i < ALL_BADGES.length; i++) {
                if (equipped.equals(ALL_BADGES[i].getKey())) {
                    return i;
                }
            }
        }
        if (initialSelection != null) {
            for (int i = 0; i < ALL_BADGES.length; i++) {
                if (ALL_BADGES[i] == initialSelection) {
                    return i;
                }
            }
        }
        return 0;
    }

    private ChallengeId selectedBadge() {
        return ALL_BADGES[clamp(previewIndex, 0, ALL_BADGES.length - 1)];
    }

    private boolean isSelectedUnlocked() {
        return BlitzChallengeBadgeEquip.isUnlocked(selectedBadge());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        updateLayout();
        updateButtonPositions();

        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Blitz Challenge Badges",
                width / 2, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        previewIndex = clamp(previewIndex, 0, ALL_BADGES.length - 1);
        clampScrollToRange();
        drawBadgeList(mouseX, mouseY);
        drawPreview(partialTicks, mouseX, mouseY);
        if (placingBadge && isSelectedUnlocked()) {
            updateBadgePlacementFromMouse(mouseX, mouseY);
        }
        refreshButtonState();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) {
            return;
        }
        if (isInsidePreview(Mouse.getEventX() * width / mc.displayWidth,
                height - Mouse.getEventY() * height / mc.displayHeight - 1)
                && isSelectedUnlocked()
                && (placingBadge || isShiftDown())) {
            BlitzChallengeBadgeEquip.nudgeScale(dWheel > 0 ? BADGE_SCALE_WHEEL_SENS : -BADGE_SCALE_WHEEL_SENS);
            return;
        }
        scrollByRows(dWheel > 0 ? -3 : 3);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && handleListRowClick(mouseX, mouseY)) {
            return;
        }
        if (mouseButton == 0 && isInsidePreview(mouseX, mouseY)) {
            if (placingBadge && isSelectedUnlocked()) {
                updateBadgePlacementFromMouse(mouseX, mouseY);
                placingBadge = false;
                BlitzChallengeBadgeEquip.resetRenderAlpha();
                return;
            }
            if (!placingBadge) {
                draggingPreview = true;
            }
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && draggingPreview && !placingBadge) {
            previewYaw += (mouseX - lastDragMouseX) * PREVIEW_DRAG_SENSITIVITY;
            previewPitch += (lastDragMouseY - mouseY) * PREVIEW_PITCH_SENSITIVITY;
            previewPitch = Math.max(-55.0F, Math.min(55.0F, previewPitch));
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            draggingPreview = false;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        switch (button.id) {
            case BTN_BACK:
                mc.displayGuiScreen(parent);
                break;
            case BTN_APPLY:
                applySelection();
                break;
            case BTN_NONE:
                BlitzChallengeBadgeEquip.removeBadge(selectedBadge().getKey());
                BlitzChallengeBadgeEquip.selectBadgeForEditing(selectedBadge().getKey(), false);
                refreshButtonState();
                break;
            case BTN_SCALE_DOWN:
                if (isSelectedUnlocked()) {
                    BlitzChallengeBadgeEquip.nudgeScale(-0.08f);
                }
                break;
            case BTN_SCALE_UP:
                if (isSelectedUnlocked()) {
                    BlitzChallengeBadgeEquip.nudgeScale(0.08f);
                }
                break;
            case BTN_PLACE:
                if (isSelectedUnlocked()) {
                    if (!BlitzChallengeBadgeEquip.isEquipped(selectedBadge().getKey())) {
                        BlitzChallengeBadgeEquip.setEquippedKey(selectedBadge().getKey());
                        BlitzChallengeBadgeEquip.setEnabled(true);
                    }
                    placingBadge = !placingBadge;
                    if (placingBadge) {
                        BlitzChallengeBadgeEquip.setRenderAlpha(AIM_PREVIEW_ALPHA);
                    } else {
                        BlitzChallengeBadgeEquip.resetRenderAlpha();
                    }
                }
                break;
            case BTN_LAYER:
                if (isSelectedUnlocked()) {
                    BlitzChallengeBadgeEquip.toggleSkinLayer();
                    refreshButtonState();
                }
                break;
            default:
                break;
        }
    }

    private void applySelection() {
        BlitzChallengeBadgeEquip.endGuiDraft(true);
        mc.displayGuiScreen(parent);
    }

    private void refreshPreviewPlayer() {
        if (previewPlayer == null && mc.theWorld != null) {
            previewPlayer = MountPreviewHelper.createCapePreviewPlayer(mc.theWorld, mc, null);
        } else if (previewPlayer != null) {
            MountPreviewHelper.updateCapePreviewPlayer(mc, previewPlayer, null);
        }
    }

    private void drawBadgeList(int mouseX, int mouseY) {
        Gui.drawRect(listLeft, listTop, listLeft + listWidth, listBottom, PANEL_BG);
        drawBorder(listLeft, listTop, listWidth, listBottom - listTop, PANEL_BORDER);
        int unlocked = BlitzChallengeBadgeEquip.getUnlockedBadgeCount();
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Badges",
                listLeft + 8, listTop + 8, 0xFFFFFF);
        String count = EnumChatFormatting.GRAY + String.valueOf(unlocked) + "/" + ALL_BADGES.length + " unlocked";
        fontRendererObj.drawStringWithShadow(count, listLeft + 8, listTop + 19, 0x888888);

        int visibleRows = getVisibleRows();
        int right = listLeft + listWidth;
        for (int row = 0; row < visibleRows; row++) {
            int idx = scrollIndex + row;
            if (idx >= ALL_BADGES.length) {
                break;
            }
            int rowInnerH = ROW_HEIGHT - ROW_GAP;
            int y = rowsTop + row * ROW_HEIGHT;
            int rowBottom = y + rowInnerH;
            boolean hovered = mouseX >= listLeft + 5 && mouseX < right - 5 && mouseY >= y && mouseY < rowBottom;
            boolean selected = idx == previewIndex;
            ChallengeId id = ALL_BADGES[idx];
            boolean unlockedRow = BlitzChallengeBadgeEquip.isUnlocked(id);

            int bg = selected ? ROW_SELECTED_BG : (hovered ? ROW_HOVER_BG : (unlockedRow ? ROW_BG : ROW_LOCKED_BG));
            Gui.drawRect(listLeft + 5, y, right - 5, rowBottom, bg);
            boolean equipped = BlitzChallengeBadgeEquip.isEquipped(id.getKey());
            if (equipped) {
                drawBorder(listLeft + 5, y, listWidth - 10, rowBottom - y, ROW_APPLIED_BORDER);
            }

            int thumbY = y + (rowInnerH - THUMB_SIZE) / 2;
            int textY = y + (rowInnerH - fontRendererObj.FONT_HEIGHT) / 2;
            drawBadgeThumb(id, listLeft + 10, thumbY, THUMB_SIZE, THUMB_SIZE, unlockedRow);

            String label = fitText(id.getDisplayName(), listWidth - 88);
            int color = unlockedRow ? (selected ? 0xFFFFFF : 0xD8E0E8) : 0x8899AA;
            fontRendererObj.drawStringWithShadow(label, listLeft + 38, textY, color);
            if (equipped) {
                fontRendererObj.drawStringWithShadow("equipped", right - 58, textY, 0x88FFAA);
            } else if (!unlockedRow) {
                fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + "locked", right - 42, textY, 0x666666);
            }
        }
        drawScrollbar(ALL_BADGES.length, visibleRows);
    }

    private void drawPreview(float partialTicks, int mouseX, int mouseY) {
        Gui.drawRect(previewLeft, previewTop, previewLeft + previewWidth, previewBottom, PANEL_BG);
        drawBorder(previewLeft, previewTop, previewWidth, previewBottom - previewTop, PANEL_BORDER);

        ChallengeId id = selectedBadge();
        boolean unlocked = isSelectedUnlocked();
        String label = fitText(id.getDisplayName(), previewWidth - 28);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                (unlocked ? EnumChatFormatting.AQUA : EnumChatFormatting.GRAY) + label,
                previewCenterX, previewTop + 12, 0xFFFFFF);
        String indexText = (previewIndex + 1) + "/" + ALL_BADGES.length;
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + indexText,
                previewCenterX - fontRendererObj.getStringWidth(indexText) / 2, previewTop + 25, 0xFFFFFF);

        if (placingBadge && unlocked) {
            BlitzChallengeBadgeEquip.setRenderAlpha(AIM_PREVIEW_ALPHA);
        } else if (!placingBadge) {
            BlitzChallengeBadgeEquip.resetRenderAlpha();
        }

        refreshPreviewPlayer();
        if (previewPlayer != null) {
            float renderYaw = tickPreviewDisplayYaw(partialTicks);
            float renderPitch = tickPreviewDisplayPitch(partialTicks);
            MountPreviewHelper.renderCapePreviewPlayer(mc, previewPlayer, previewCenterX,
                    previewBaseY, previewScale, 1.0F, renderYaw, renderPitch, 1.0F);
        }

        if (!unlocked) {
            String lockMsg = "Complete all tiers to unlock";
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.RED + lockMsg,
                    previewCenterX - fontRendererObj.getStringWidth(lockMsg) / 2,
                    previewBottom - 40, 0xFF8888);
        } else if (placingBadge) {
            String hint = "Aim at skin, click to place  |  Scroll to scale  |  Add badges from the list";
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + hint,
                    previewCenterX - fontRendererObj.getStringWidth(hint) / 2,
                    previewBottom - 26, 0xFFFFAA);
            String scaleText = String.format("%s · %s  Pos: %.2f, %.2f   Scale: %.2f",
                    BlitzChallengeBadgeEquip.getAttachPart().getDisplayName(),
                    BlitzChallengeBadgeEquip.getSkinLayer().getDisplayName(),
                    BlitzChallengeBadgeEquip.getOffsetX(), BlitzChallengeBadgeEquip.getOffsetY(),
                    BlitzChallengeBadgeEquip.getBadgeScale());
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + scaleText,
                    previewCenterX - fontRendererObj.getStringWidth(scaleText) / 2,
                    previewBottom - 14, 0x888888);
        } else {
            String hint = "Drag to orbit  |  Place on skin  |  Select more badges to equip several";
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + hint,
                    previewCenterX - fontRendererObj.getStringWidth(hint) / 2,
                    previewBottom - 26, 0x888888);
            String scaleText = String.format("%s · %s  Pos: %.2f, %.2f   Scale: %.2f",
                    BlitzChallengeBadgeEquip.getAttachPart().getDisplayName(),
                    BlitzChallengeBadgeEquip.getSkinLayer().getDisplayName(),
                    BlitzChallengeBadgeEquip.getOffsetX(), BlitzChallengeBadgeEquip.getOffsetY(),
                    BlitzChallengeBadgeEquip.getBadgeScale());
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + scaleText,
                    previewCenterX - fontRendererObj.getStringWidth(scaleText) / 2,
                    previewBottom - 14, 0x888888);
        }

        drawBadgeThumb(id, previewLeft + previewWidth - THUMB_SIZE - 10, previewTop + 8, THUMB_SIZE, THUMB_SIZE, unlocked);
    }

    private void drawBadgeThumb(ChallengeId id, int x, int y, int w, int h, boolean fullBrightness) {
        float alpha = fullBrightness ? 1.0F : 0.38F;
        if (BlitzChallengeBadgeTextures.drawGuiBadge(mc, id, x, y, w, h, alpha)) {
            return;
        }
        Gui.drawRect(x, y, x + w, y + h, fullBrightness ? 0xFF2A3440 : 0xFF1A2028);
        String initial = id.getDisplayName().substring(0, 1);
        fontRendererObj.drawStringWithShadow(initial, x + w / 2 - 2, y + h / 2 - 4,
                fullBrightness ? 0xFFFFFF : 0x8899AA);
    }

    private boolean handleListRowClick(int mouseX, int mouseY) {
        if (mouseX < listLeft || mouseX >= listLeft + listWidth
                || mouseY < rowsTop || mouseY >= rowsBottom) {
            return false;
        }
        int row = (mouseY - rowsTop) / ROW_HEIGHT;
        int idx = scrollIndex + row;
        if (idx < 0 || idx >= ALL_BADGES.length) {
            return false;
        }
        previewIndex = idx;
        BlitzChallengeBadgeEquip.selectBadgeForEditing(selectedBadge().getKey(), false);
        placingBadge = false;
        BlitzChallengeBadgeEquip.resetRenderAlpha();
        ensureScrollInRange();
        refreshPreviewPlayer();
        refreshButtonState();
        return true;
    }

    private void refreshButtonState() {
        boolean unlocked = isSelectedUnlocked();
        applyButton.enabled = true;
        noneButton.enabled = BlitzChallengeBadgeEquip.isEnabled()
                && BlitzChallengeBadgeEquip.isEquipped(selectedBadge().getKey());
        boolean equipped = unlocked && BlitzChallengeBadgeEquip.isEquipped(selectedBadge().getKey());
        scaleDownButton.enabled = equipped;
        scaleUpButton.enabled = equipped;
        placeButton.enabled = unlocked;
        layerButton.enabled = equipped;
        layerButton.displayString = "Layer: "
                + (BlitzChallengeBadgeEquip.getSkinLayer() == BadgeSkinLayer.OUTER ? "Outer" : "Inner");
        if (placingBadge) {
            placeButton.displayString = EnumChatFormatting.GREEN + "Placing...";
        } else if (!equipped) {
            placeButton.displayString = EnumChatFormatting.YELLOW + "Add & place";
        } else {
            placeButton.displayString = EnumChatFormatting.YELLOW + "Place on skin";
        }
    }

    private void updateLayout() {
        int margin = 14;
        int gap = 12;
        int minListW = 220;
        int minPreviewW = 200;
        int contentTop = GuiHeader.STANDARD_TITLE_Y + 28;
        int contentBottom = height - 52;
        int contentH = Math.max(160, contentBottom - contentTop);

        stackedLayout = width < minListW + minPreviewW + gap + margin * 2;
        if (stackedLayout) {
            listLeft = margin;
            listWidth = width - margin * 2;
            listTop = contentTop;
            listBottom = contentTop + contentH / 2 - gap / 2;
            previewLeft = margin;
            previewWidth = width - margin * 2;
            previewTop = listBottom + gap;
            previewBottom = contentBottom;
        } else {
            listLeft = margin;
            listWidth = Math.max(minListW, (width - margin * 2 - gap) * 5 / 12);
            previewWidth = width - margin - listLeft - listWidth - gap;
            listTop = contentTop;
            listBottom = contentBottom;
            previewLeft = listLeft + listWidth + gap;
            previewTop = contentTop;
            previewBottom = contentBottom;
        }

        rowsTop = listTop + 36;
        rowsBottom = listBottom - 8;
        previewCenterX = previewLeft + previewWidth / 2;
        int modelTop = previewTop + 42;
        int modelBottom = previewBottom - 40;
        int modelAreaHeight = Math.max(80, modelBottom - modelTop);
        previewScale = clamp(modelAreaHeight / 2, 48, 92);
        int modelHeight = (int) (previewScale * PREVIEW_MODEL_HEIGHT);
        previewBaseY = modelTop + (modelAreaHeight + modelHeight) / 2 - 4;
    }

    private void updateButtonPositions() {
        int y = height - 36;
        int center = width / 2;
        backButton.xPosition = center - 260;
        backButton.yPosition = y;
        applyButton.xPosition = center - 176;
        applyButton.yPosition = y;
        noneButton.xPosition = center - 92;
        noneButton.yPosition = y;
        layerButton.xPosition = center - 8;
        layerButton.yPosition = y;
        placeButton.xPosition = center + 96;
        placeButton.yPosition = y;
        scaleDownButton.xPosition = center + 200;
        scaleDownButton.yPosition = y;
        scaleUpButton.xPosition = center + 232;
        scaleUpButton.yPosition = y;
    }

    private float tickPreviewDisplayYaw(float partialTicks) {
        previewDisplayYaw += (previewYaw - previewDisplayYaw) * PREVIEW_YAW_SMOOTH;
        return previewDisplayYaw;
    }

    private float tickPreviewDisplayPitch(float partialTicks) {
        previewDisplayPitch += (previewPitch - previewDisplayPitch) * PREVIEW_PITCH_SMOOTH;
        return previewDisplayPitch;
    }

    private boolean isShiftDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    /**
     * Project a point in preview-player model space (blocks, feet at y=0) to GUI pixels.
     * Mirrors {@link MountPreviewHelper#renderEntityPreview} orbit + {@code scale(-scale,…)} X flip.
     */
    private int[] projectModelToScreen(float modelX, float modelY, float modelZ) {
        float s = previewScale;
        float yawRad = (float) Math.toRadians(previewDisplayYaw);
        float pitchRad = (float) Math.toRadians(PREVIEW_CAPE_TILT_DEG - previewDisplayPitch);
        float cosY = (float) Math.cos(yawRad);
        float sinY = (float) Math.sin(yawRad);
        float cosP = (float) Math.cos(pitchRad);
        float sinP = (float) Math.sin(pitchRad);

        float ly = modelY - PREVIEW_ORBIT_PIVOT_Y;
        float rotX = modelX * cosY + modelZ * sinY;
        float rotZ = -modelX * sinY + modelZ * cosY;
        float rotY = PREVIEW_ORBIT_PIVOT_Y + ly * cosP - rotZ * sinP;

        int screenX = previewCenterX - Math.round(s * rotX);
        int screenY = previewBaseY - Math.round(s * rotY);
        return new int[]{screenX, screenY};
    }

    private float[] partModelPoint(BadgeAttachPart part) {
        switch (part) {
            case HEAD:
                return new float[]{0.0F, 1.62F, 0.0F};
            case RIGHT_ARM:
                return new float[]{0.36F, 1.08F, 0.0F};
            case LEFT_ARM:
                return new float[]{-0.36F, 1.08F, 0.0F};
            case RIGHT_LEG:
                return new float[]{0.12F, 0.38F, 0.0F};
            case LEFT_LEG:
                return new float[]{-0.12F, 0.38F, 0.0F};
            case BODY:
            default:
                return new float[]{0.0F, PREVIEW_ORBIT_PIVOT_Y, 0.0F};
        }
    }

    private static float partHalfWidth(BadgeAttachPart part) {
        switch (part) {
            case HEAD:
                return 0.25F;
            case RIGHT_ARM:
            case LEFT_ARM:
            case RIGHT_LEG:
            case LEFT_LEG:
                return 0.125F;
            case BODY:
            default:
                return 0.25F;
        }
    }

    private static float partHalfHeight(BadgeAttachPart part) {
        switch (part) {
            case HEAD:
                return 0.25F;
            case BODY:
            case RIGHT_ARM:
            case LEFT_ARM:
            case RIGHT_LEG:
            case LEFT_LEG:
                return 0.375F;
            default:
                return 0.375F;
        }
    }

    /**
     * Map the cursor through each limb's projected surface axes. This remains correct when the preview is
     * orbited: screen-left may represent either positive or negative model X, and arm badges use model Z.
     */
    private void updateBadgePlacementFromMouse(int mouseX, int mouseY) {
        PlacementProjection best = null;
        for (BadgeAttachPart part : BadgeAttachPart.values()) {
            PlacementProjection candidate = projectPlacement(part, mouseX, mouseY);
            if (candidate != null && (best == null || candidate.score < best.score)) best = candidate;
        }
        if (best == null) return;
        BlitzChallengeBadgeEquip.setPlacementFromSkinDrag(
                best.part,
                clampSkinOffsetX(best.offsetX),
                clampSkinOffsetY(best.offsetY));
    }

    private PlacementProjection projectPlacement(BadgeAttachPart part, int mouseX, int mouseY) {
        int[] origin = projectPlacementPoint(part, 0.0F, 0.0F);
        int[] xPoint = projectPlacementPoint(part, 1.0F, 0.0F);
        int[] yPoint = projectPlacementPoint(part, 0.0F, 1.0F);
        float ax = xPoint[0] - origin[0];
        float ay = xPoint[1] - origin[1];
        float bx = yPoint[0] - origin[0];
        float by = yPoint[1] - origin[1];
        float det = ax * by - ay * bx;
        if (Math.abs(det) < 1.0F) return null;

        float dx = mouseX - origin[0];
        float dy = mouseY - origin[1];
        float ox = (dx * by - dy * bx) / det;
        float oy = (ax * dy - ay * dx) / det;
        float cx = clampSkinOffsetX(ox);
        float cy = clampSkinOffsetY(oy);
        float projectedX = origin[0] + ax * cx + bx * cy;
        float projectedY = origin[1] + ay * cx + by * cy;
        float missX = mouseX - projectedX;
        float missY = mouseY - projectedY;
        float score = (missX * missX + missY * missY) * 100.0F + ox * ox + oy * oy;
        return new PlacementProjection(part, ox, oy, score);
    }

    private int[] projectPlacementPoint(BadgeAttachPart part, float ox, float oy) {
        float[] center = partModelPoint(part);
        float outer = BlitzChallengeBadgeEquip.getSkinLayer() == BadgeSkinLayer.OUTER
                ? (part == BadgeAttachPart.HEAD ? 0.03125F : 0.015625F) : 0.0F;
        float hw = partHalfWidth(part) + outer;
        float hh = partHalfHeight(part) + outer;
        float x = center[0];
        float y = center[1] - oy * hh * 2.0F;
        float z = center[2];
        switch (part) {
            case RIGHT_ARM:
                x += hw;
                z += ox * hw * 2.0F;
                break;
            case LEFT_ARM:
                x -= hw;
                z -= ox * hw * 2.0F;
                break;
            case BODY:
                x += ox * hw * 2.0F;
                z -= 0.125F + outer;
                break;
            case HEAD:
            case RIGHT_LEG:
            case LEFT_LEG:
            default:
                x += ox * hw * 2.0F;
                z -= hw;
                break;
        }
        return projectModelToScreen(x, y, z);
    }

    private static final class PlacementProjection {
        final BadgeAttachPart part;
        final float offsetX;
        final float offsetY;
        final float score;

        PlacementProjection(BadgeAttachPart part, float offsetX, float offsetY, float score) {
            this.part = part;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.score = score;
        }
    }

    private static float clampSkinOffsetX(float v) {
        return Math.max(-0.55F, Math.min(0.55F, v));
    }

    private static float clampSkinOffsetY(float v) {
        return Math.max(-0.55F, Math.min(0.55F, v));
    }

    private boolean isInsidePreview(int mouseX, int mouseY) {
        return mouseX >= previewLeft && mouseX < previewLeft + previewWidth
                && mouseY >= previewTop && mouseY < previewBottom;
    }

    private void drawBorder(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        Gui.drawRect(x, y, x + 1, y + h, color);
        Gui.drawRect(x + w - 1, y, x + w, y + h, color);
    }

    private int getVisibleRows() {
        return Math.max(1, (rowsBottom - rowsTop + ROW_GAP) / ROW_HEIGHT);
    }

    private void clampScrollToRange() {
        int visibleRows = getVisibleRows();
        int maxScroll = Math.max(0, ALL_BADGES.length - visibleRows);
        scrollIndex = clamp(scrollIndex, 0, maxScroll);
    }

    private void ensureScrollInRange() {
        clampScrollToRange();
        int visibleRows = getVisibleRows();
        int maxScroll = Math.max(0, ALL_BADGES.length - visibleRows);
        if (previewIndex < scrollIndex) {
            scrollIndex = previewIndex;
        } else if (previewIndex >= scrollIndex + visibleRows) {
            scrollIndex = clamp(previewIndex - visibleRows + 1, 0, maxScroll);
        }
    }

    private void scrollByRows(int rows) {
        scrollIndex += rows;
        clampScrollToRange();
    }

    private void drawScrollbar(int size, int visibleRows) {
        if (size <= visibleRows || visibleRows <= 0) {
            return;
        }
        int trackX = listLeft + listWidth - 4;
        int trackTop = rowsTop;
        int trackBottom = rowsTop + visibleRows * ROW_HEIGHT - ROW_GAP;
        int trackHeight = Math.max(1, trackBottom - trackTop);
        Gui.drawRect(trackX, trackTop, trackX + 2, trackBottom, 0x44202A34);
        int thumbHeight = Math.max(18, trackHeight * visibleRows / size);
        int maxScroll = Math.max(1, size - visibleRows);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scrollIndex / maxScroll;
        Gui.drawRect(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0xAA7AA6C2);
    }

    private String fitText(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String ell = "...";
        String s = text;
        while (s.length() > 1 && fontRendererObj.getStringWidth(s + ell) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + ell;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
