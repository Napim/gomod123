package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * Miscellaneous -> Pre-game tools: toggles and an interactive 9-slot hotbar
 * preview (drag-and-drop or click-to-place across slots 3-8; slots 1, 2, 9 reserved).
 */
public class PregameItemsSettingsGUI extends GuiScreen {

    private enum PregameItem {
        VISIBILITY("Player visibility button", new ItemStack(Items.dye, 1, 10)),
        QSTATUS("Queue status button", BlitzPregamePlayerToggle.createQueueStatusIcon(false)),
        REQUEUE("Requeue button", new ItemStack(Items.paper, 1, 0)),
        STATUS("Status changer button", null);

        private static final ItemStack QSTATUS_TEAMS_ICON = BlitzPregamePlayerToggle.createQueueStatusIcon(true);

        final String label;
        final ItemStack icon;

        PregameItem(String label, ItemStack icon) {
            this.label = label;
            this.icon = icon;
        }

        ItemStack getDisplayIcon(net.minecraft.client.Minecraft mc) {
            if (this == QSTATUS && BlitzPregamePlayerToggle.isTeamsPregame(mc)) {
                return QSTATUS_TEAMS_ICON;
            }
            if (this == STATUS) {
                return BlitzPregamePlayerToggle.createStatusIcon(
                        BlitzPregamePlayerToggle.getCurrentHypixelStatus());
            }
            return icon;
        }

        boolean isEnabled() {
            switch (this) {
                case VISIBILITY: return ConfigManager.isBlitzPregamePlayerToggleEnabled();
                case QSTATUS: return ConfigManager.isBlitzPregameQueueStatusItemEnabled();
                case REQUEUE: return ConfigManager.isBlitzPregameRequeueItemEnabled();
                case STATUS: return ConfigManager.isBlitzPregameStatusItemEnabled();
            }
            return false;
        }

        int getSlot() {
            switch (this) {
                case VISIBILITY: return ConfigManager.getBlitzPregamePlayerToggleSlot();
                case QSTATUS: return ConfigManager.getBlitzPregameQueueStatusSlot();
                case REQUEUE: return ConfigManager.getBlitzPregameRequeueSlot();
                case STATUS: return ConfigManager.getBlitzPregameStatusSlot();
            }
            return 3;
        }

        void setSlot(int slot) {
            switch (this) {
                case VISIBILITY:
                    ConfigManager.setBlitzPregamePlayerToggleSlot(slot);
                    break;
                case QSTATUS:
                    ConfigManager.setBlitzPregameQueueStatusSlot(slot);
                    break;
                case REQUEUE:
                    ConfigManager.setBlitzPregameRequeueSlot(slot);
                    break;
                case STATUS:
                    ConfigManager.setBlitzPregameStatusSlot(slot);
                    break;
            }
            BlitzPregamePlayerToggle.onModuleSettingChanged();
        }
    }

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 8;

    private static final ResourceLocation WIDGETS_TEX = new ResourceLocation("textures/gui/widgets.png");
    private static final int HOTBAR_SLOTS = 9;
    private static final int SLOT_STEP = 20;
    private static final int HOTBAR_WIDTH = 182;
    private static final int HOTBAR_HEIGHT = 22;

    private static final ItemStack RESERVED_SLOT_1_ICON = new ItemStack(Items.bow, 1, 0);
    private static final ItemStack RESERVED_SLOT_2_ICON = new ItemStack(Items.golden_sword, 1, 0);
    private static final ItemStack RESERVED_SLOT_9_ICON = new ItemStack(Items.bed, 1, 0);

    private final GuiScreen parent;
    private GuiButton playerVisibilityButton;
    private GuiButton queueStatusButton;
    private GuiButton requeueButton;
    private GuiButton statusButton;
    private GuiButton backButton;

    private int hotbarLeftX;
    private int hotbarTopY;

    private PregameItem selectedItem;
    private PregameItem draggingItem;
    private int dragStartSlot = -1;
    private int dragStartX;
    private int dragStartY;
    private boolean dragMoved;

    public PregameItemsSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();
        int backY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);
        int contentTop = GuiHeader.standardContentTopAfterSubtitle(fontRendererObj) + 6;
        int contentBottom = backY - 12;

        int togglesHeight = 4 * BUTTON_HEIGHT + 3 * GAP;
        int hotbarSectionHeight = 16 + HOTBAR_HEIGHT;
        int sectionGap = 18;
        int totalHeight = togglesHeight + sectionGap + hotbarSectionHeight;
        int y = contentTop + Math.max(0, (contentBottom - contentTop - totalHeight) / 2);

        playerVisibilityButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getPlayerVisibilityText());
        y += BUTTON_HEIGHT + GAP;

        queueStatusButton = new GuiButton(2, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getQueueStatusText());
        y += BUTTON_HEIGHT + GAP;

        requeueButton = new GuiButton(3, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getRequeueText());
        y += BUTTON_HEIGHT + GAP;

        statusButton = new GuiButton(4, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                getStatusText());
        y += BUTTON_HEIGHT + sectionGap;

        hotbarLeftX = cx - HOTBAR_WIDTH / 2;
        hotbarTopY = y + 14;

        backButton = new GuiButton(0, cx - BUTTON_WIDTH / 2, backY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Back");

        buttonList.clear();
        buttonList.add(playerVisibilityButton);
        buttonList.add(queueStatusButton);
        buttonList.add(requeueButton);
        buttonList.add(statusButton);
        buttonList.add(backButton);
    }

    private String getPlayerVisibilityText() {
        return toggleText("Player visibility button", ConfigManager.isBlitzPregamePlayerToggleEnabled());
    }

    private String getQueueStatusText() {
        return toggleText("Queue status button", ConfigManager.isBlitzPregameQueueStatusItemEnabled());
    }

    private String getRequeueText() {
        return toggleText("Requeue button", ConfigManager.isBlitzPregameRequeueItemEnabled());
    }

    private String getStatusText() {
        return toggleText("Status changer button", ConfigManager.isBlitzPregameStatusItemEnabled());
    }

    private String toggleText(String label, boolean enabled) {
        return EnumChatFormatting.WHITE + label + ": "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
    }

    private static boolean isReservedSlot(int slot) {
        return slot < ConfigManager.BLITZ_PREGAME_MIN_SLOT || slot > ConfigManager.BLITZ_PREGAME_MAX_SLOT;
    }

    private static PregameItem itemAtSlot(int slot) {
        for (PregameItem item : PregameItem.values()) {
            if (item.getSlot() == slot) {
                return item;
            }
        }
        return null;
    }

    private int slotX(int slot) {
        return hotbarLeftX + 1 + (slot - 1) * SLOT_STEP;
    }

    private int slotUnderMouse(int mouseX, int mouseY) {
        if (mouseY < hotbarTopY || mouseY > hotbarTopY + HOTBAR_HEIGHT) {
            return -1;
        }
        for (int slot = 1; slot <= HOTBAR_SLOTS; slot++) {
            int sx = slotX(slot);
            if (mouseX >= sx && mouseX < sx + SLOT_STEP) {
                return slot;
            }
        }
        return -1;
    }

    private void playClickSound() {
        if (mc != null && mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(
                    PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        ScaledResolution sr = new ScaledResolution(mc);

        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Pre-game tools" + EnumChatFormatting.RESET;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, sr.getScaledWidth(), GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        String subtitle = EnumChatFormatting.GRAY + "Configure your Blitz pre-game hotbar buttons:";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, subtitle, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHotbarPicker(sr.getScaledWidth(), mouseX, mouseY);
        GomodSearchHighlight.draw(buttonList, PregameItemsSettingsGUI.class);
        drawHoverTooltip(mouseX, mouseY);
    }

    private void drawHotbarPicker(int scaledWidth, int mouseX, int mouseY) {
        PregameItem carried = (draggingItem != null && dragMoved) ? draggingItem : selectedItem;
        String prompt;
        if (carried != null) {
            prompt = EnumChatFormatting.YELLOW + "Place " + EnumChatFormatting.WHITE + carried.label;
        } else {
            prompt = EnumChatFormatting.GRAY + "Hotbar layout "
                    + EnumChatFormatting.DARK_GRAY + "(drag or click to move between slots)";
        }
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, prompt, scaledWidth, hotbarTopY - 13, 0xFFFFFF);

        int hoveredSlot = slotUnderMouse(mouseX, mouseY);

        // Draw the active resource pack's real vanilla hotbar (textures/gui/widgets.png)
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        mc.getTextureManager().bindTexture(WIDGETS_TEX);
        drawTexturedModalRect(hotbarLeftX, hotbarTopY, 0, 0, HOTBAR_WIDTH, HOTBAR_HEIGHT);

        int selectorSlot = (hoveredSlot > 0 && !isReservedSlot(hoveredSlot))
                ? hoveredSlot
                : (carried != null ? carried.getSlot() : -1);
        if (selectorSlot >= 1 && selectorSlot <= HOTBAR_SLOTS) {
            drawTexturedModalRect(hotbarLeftX - 1 + (selectorSlot - 1) * SLOT_STEP, hotbarTopY - 1, 0, 22, 24, 22);
        }
        GlStateManager.disableBlend();

        for (int slot = 1; slot <= HOTBAR_SLOTS; slot++) {
            int sx = slotX(slot);
            int sy = hotbarTopY + 1;
            boolean reserved = isReservedSlot(slot);
            PregameItem occupant = reserved ? null : itemAtSlot(slot);
            boolean isCarriedOrigin = carried != null && occupant == carried;

            if (isCarriedOrigin && slot != selectorSlot) {
                drawRect(sx + 1, sy + 1, sx + SLOT_STEP - 1, sy + SLOT_STEP - 1, 0x55FFB84D);
            }

            int iconX = hotbarLeftX + 3 + (slot - 1) * SLOT_STEP;
            int iconY = hotbarTopY + 3;

            if (reserved) {
                ItemStack reservedIcon = slot == 1 ? RESERVED_SLOT_1_ICON
                        : (slot == 2 ? RESERVED_SLOT_2_ICON : RESERVED_SLOT_9_ICON);
                renderItemIcon(reservedIcon, iconX, iconY);
                // Subtle dim veil to signal reserved/locked Blitz slot
                drawRect(sx + 1, sy + 1, sx + SLOT_STEP - 1, sy + SLOT_STEP - 1, 0x66101014);
            } else if (occupant != null && !isCarriedOrigin) {
                renderItemIcon(occupant.getDisplayIcon(mc), iconX, iconY);
                if (!occupant.isEnabled()) {
                    drawRect(sx + 1, sy + 1, sx + SLOT_STEP - 1, sy + SLOT_STEP - 1, 0x77141418);
                }
            }
        }

        if (carried != null) {
            renderItemIcon(carried.getDisplayIcon(mc), mouseX - 8, mouseY - 8);
        }
    }

    private void renderItemIcon(ItemStack stack, int x, int y) {
        if (stack == null || mc == null || mc.getRenderItem() == null) {
            return;
        }
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableDepth();
            RenderHelper.enableGUIStandardItemLighting();
            mc.getRenderItem().zLevel = 200.0F;
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
            mc.getRenderItem().zLevel = 0.0F;
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableDepth();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1 && (selectedItem != null || draggingItem != null)) {
            selectedItem = null;
            draggingItem = null;
            dragStartSlot = -1;
            dragMoved = false;
            return;
        }

        if (mouseButton == 0) {
            int slot = slotUnderMouse(mouseX, mouseY);
            if (slot > 0) {
                if (isReservedSlot(slot)) {
                    return;
                }
                if (selectedItem != null) {
                    if (selectedItem.getSlot() != slot) {
                        selectedItem.setSlot(slot);
                        playClickSound();
                    }
                    selectedItem = null;
                    return;
                }
                PregameItem occupant = itemAtSlot(slot);
                if (occupant != null) {
                    draggingItem = occupant;
                    dragStartSlot = slot;
                    dragStartX = mouseX;
                    dragStartY = mouseY;
                    dragMoved = false;
                    return;
                }
            } else if (selectedItem != null) {
                selectedItem = null;
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingItem != null && clickedMouseButton == 0) {
            int dx = mouseX - dragStartX;
            int dy = mouseY - dragStartY;
            if (dx * dx + dy * dy >= 9) {
                dragMoved = true;
            }
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (draggingItem != null && state == 0) {
            if (dragMoved) {
                int dropSlot = slotUnderMouse(mouseX, mouseY);
                if (dropSlot > 0 && !isReservedSlot(dropSlot) && dropSlot != dragStartSlot) {
                    draggingItem.setSlot(dropSlot);
                    playClickSound();
                }
            } else {
                // Quick click without dragging: enter click-to-place mode
                selectedItem = draggingItem;
                playClickSound();
            }
            draggingItem = null;
            dragStartSlot = -1;
            dragMoved = false;
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                break;
            case 1:
                ConfigManager.setBlitzPregamePlayerToggleEnabled(
                        !ConfigManager.isBlitzPregamePlayerToggleEnabled());
                BlitzPregamePlayerToggle.onModuleSettingChanged();
                playerVisibilityButton.displayString = getPlayerVisibilityText();
                break;
            case 2:
                ConfigManager.setBlitzPregameQueueStatusItemEnabled(
                        !ConfigManager.isBlitzPregameQueueStatusItemEnabled());
                BlitzPregamePlayerToggle.onModuleSettingChanged();
                queueStatusButton.displayString = getQueueStatusText();
                break;
            case 3:
                ConfigManager.setBlitzPregameRequeueItemEnabled(
                        !ConfigManager.isBlitzPregameRequeueItemEnabled());
                BlitzPregamePlayerToggle.onModuleSettingChanged();
                requeueButton.displayString = getRequeueText();
                break;
            case 4:
                ConfigManager.setBlitzPregameStatusItemEnabled(
                        !ConfigManager.isBlitzPregameStatusItemEnabled());
                BlitzPregamePlayerToggle.onModuleSettingChanged();
                statusButton.displayString = getStatusText();
                break;
        }
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (draggingItem != null && dragMoved) {
            return;
        }
        if (isMouseOver(playerVisibilityButton, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Toggle players' visibility"
            ), mouseX, mouseY);
            return;
        }
        if (isMouseOver(queueStatusButton, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Display queue status"
            ), mouseX, mouseY);
            return;
        }
        if (isMouseOver(requeueButton, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Requeue a new blitz pre-game lobby"
            ), mouseX, mouseY);
            return;
        }
        if (isMouseOver(statusButton, mouseX, mouseY)) {
            drawHoveringText(Collections.singletonList(
                    EnumChatFormatting.GRAY + "Switch your Hypixel online status (/status)"
            ), mouseX, mouseY);
            return;
        }
        int hoveredSlot = slotUnderMouse(mouseX, mouseY);
        if (hoveredSlot > 0) {
            if (isReservedSlot(hoveredSlot)) {
                String reservedLabel;
                if (hoveredSlot == 1) {
                    reservedLabel = EnumChatFormatting.GREEN + "Kit Selector"
                            + EnumChatFormatting.GRAY + " (Right Click)";
                } else if (hoveredSlot == 2) {
                    reservedLabel = EnumChatFormatting.GREEN + "Kit Effect Selector"
                            + EnumChatFormatting.GRAY + " (Right Click)";
                } else {
                    reservedLabel = EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "Return to Lobby"
                            + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + " (Right Click)";
                }
                drawHoveringText(Collections.singletonList(reservedLabel), mouseX, mouseY);
                return;
            }
            PregameItem occupant = itemAtSlot(hoveredSlot);
            if (selectedItem != null) {
                String action = occupant != null && occupant != selectedItem
                        ? "Swap with " + occupant.label
                        : "Place " + selectedItem.label + " here";
                drawHoveringText(Collections.singletonList(
                        EnumChatFormatting.YELLOW + "Slot " + hoveredSlot + ": " + EnumChatFormatting.GRAY + action
                ), mouseX, mouseY);
            } else if (occupant != null) {
                drawHoveringText(Arrays.asList(
                        EnumChatFormatting.AQUA + occupant.label
                                + (occupant.isEnabled() ? "" : EnumChatFormatting.RED + " (OFF)"),
                        EnumChatFormatting.GRAY + "Drag or click to move to another slot"
                ), mouseX, mouseY);
            } else {
                drawHoveringText(Collections.singletonList(
                        EnumChatFormatting.GRAY + "Slot " + hoveredSlot + " (Empty)"
                ), mouseX, mouseY);
            }
        }
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX <= button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY <= button.yPosition + button.height;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
