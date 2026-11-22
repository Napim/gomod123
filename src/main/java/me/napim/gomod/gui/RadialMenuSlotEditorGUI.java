package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.RadialMenuItem;
import me.napim.gomod.features.RadialMenuWheel;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * Per-slot editor opened from {@link RadialMenuWheelSlotsGUI}.
 *
 * <p>Lets the player configure:
 * <ul>
 *   <li>Enable/disable the slot</li>
 *   <li>Display label</li>
 *   <li>Slice color (cycle through presets)</li>
 *   <li>Action type (NONE / TOGGLE_FEATURE / CHAT_MESSAGE / COMMAND)</li>
 *   <li>Action value (cycles for TOGGLE_FEATURE, free-text for the others)</li>
 * </ul>
 *
 * <p>Changes are saved immediately to the in-memory slot and persisted to
 * {@code radialMenu.json} on Back / Save.</p>
 */
public class RadialMenuSlotEditorGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    // ── preset colors (RGB) ──────────────────────────────────────────────────
    private static final int[] PRESET_COLORS = {
        0xFF4444, 0xFF8844, 0xFFDD44, 0xAADD44,
        0x44DD44, 0x44DDAA, 0x44DDFF, 0x4488FF,
        0x8844FF, 0xDD44FF, 0xFF44AA, 0xFFAAFF,
        0xFFFFFF, 0xAAAAAA, 0x555555, 0x222222,
    };

    private static final String[] ACTION_TYPE_LABELS = {
        "None",
        "Toggle Feature",
        "Chat Message",
        "Command (/)"
    };

    private static final int BTN_W  = 220;
    private static final int BTN_H  = 20;
    private static final int ROW    = BTN_H + 7;
    private static final int TF_H   = 20;

    // IDs
    private static final int ID_BACK        = 0;
    private static final int ID_ENABLED     = 1;
    private static final int ID_COLOR       = 2;
    private static final int ID_ACTION_TYPE = 3;
    private static final int ID_ACTION_VAL  = 4; // only for TOGGLE_FEATURE cycling

    private final RadialMenuWheelSlotsGUI parent;
    private final int slotIndex;

    // Working copy – committed on save/back
    private final RadialMenuItem working;

    private int colorIndex = 0;
    private int actionTypeIndex = 0;
    private int toggleFeatureIndex = 0;

    // Buttons we need to refresh
    private GuiButton enabledButton;
    private GuiButton colorButton;
    private GuiButton actionTypeButton;
    private GuiButton actionValueButton;

    // Text fields (shown only for CHAT_MESSAGE / COMMAND)
    private GuiTextField labelField;
    private GuiTextField valueField;

    private boolean valueFieldActive = false;

    public RadialMenuSlotEditorGUI(RadialMenuWheelSlotsGUI parent, int slotIndex) {
        this.parent     = parent;
        this.slotIndex  = slotIndex;
        this.working    = RadialMenuWheel.getSlot(slotIndex).copy();
        syncIndicesFromWorking();
    }

    // ── init ──────────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();

        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        int totalH = ROW * 6 + TF_H * 2 + 12 + BTN_H + 6;
        int y = Math.max(GuiHeader.standardContentTopAfterSubtitle(fontRendererObj), (sh - totalH) / 2 + 40);

        // ── Enabled toggle ────────────────────────────────────────────────────
        enabledButton = new GuiButton(ID_ENABLED, cx - BTN_W / 2, y, BTN_W, BTN_H, getEnabledText());
        y += ROW;

        // ── Label text field ──────────────────────────────────────────────────
        int tfX = cx - BTN_W / 2;
        labelField = new GuiTextField(0, fontRendererObj, tfX, y, BTN_W, TF_H);
        labelField.setMaxStringLength(30);
        labelField.setFocused(true);
        labelField.setText(working.getLabel());
        y += TF_H + 7;

        // ── Color picker ──────────────────────────────────────────────────────
        colorButton = new GuiButton(ID_COLOR, cx - BTN_W / 2, y, BTN_W, BTN_H, getColorText());
        y += ROW;

        // ── Action type ───────────────────────────────────────────────────────
        actionTypeButton = new GuiButton(ID_ACTION_TYPE, cx - BTN_W / 2, y, BTN_W, BTN_H, getActionTypeText());
        y += ROW;

        // ── Action value (depends on type) ────────────────────────────────────
        actionValueButton = new GuiButton(ID_ACTION_VAL, cx - BTN_W / 2, y, BTN_W, BTN_H, getActionValueCycleText());
        valueField = new GuiTextField(1, fontRendererObj, cx - BTN_W / 2, y, BTN_W, TF_H);
        valueField.setMaxStringLength(128);
        valueField.setText(working.getActionValue());
        y += Math.max(ROW, TF_H + 7);

        // ── Back / Save ───────────────────────────────────────────────────────
        GuiButton backButton = new GuiButton(ID_BACK, cx - BTN_W / 2, y + 2, BTN_W, BTN_H,
            EnumChatFormatting.GREEN + "Save & Back");

        buttonList.clear();
        buttonList.add(enabledButton);
        buttonList.add(colorButton);
        buttonList.add(actionTypeButton);
        buttonList.add(actionValueButton);
        buttonList.add(backButton);

        refreshValueWidgets();
    }

    // ── draw ──────────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        int cx = width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
            EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Edit Slot " + (slotIndex + 1),
            cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
            EnumChatFormatting.GRAY + "Configure what this wheel slice does.",
            cx, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);

        // Labels above text fields
        if (labelField != null) {
            drawString(fontRendererObj,
                EnumChatFormatting.GRAY + "Display label:",
                labelField.xPosition, labelField.yPosition - 10, 0xAAAAAA);
        }

        RadialMenuItem.ActionType type = working.getActionType();
        if (valueFieldActive && valueField != null) {
            String hint = (type == RadialMenuItem.ActionType.CHAT_MESSAGE)
                ? "Chat message:" : "Command (without /):";
            drawString(fontRendererObj,
                EnumChatFormatting.GRAY + hint,
                valueField.xPosition, valueField.yPosition - 10, 0xAAAAAA);
        }

        // Color swatch next to color button
        drawColorSwatch(cx + BTN_W / 2 + 5, colorButton.yPosition + 2);

        // Draw text fields
        labelField.drawTextBox();
        if (valueFieldActive) valueField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawColorSwatch(int x, int y) {
        int rgb = PRESET_COLORS[colorIndex];
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        net.minecraft.client.renderer.GlStateManager.disableTexture2D();
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        org.lwjgl.opengl.GL11.glColor4f(r, g, b, 1f);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        org.lwjgl.opengl.GL11.glVertex2f(x,      y + 16);
        org.lwjgl.opengl.GL11.glVertex2f(x + 16, y + 16);
        org.lwjgl.opengl.GL11.glVertex2f(x + 16, y);
        org.lwjgl.opengl.GL11.glVertex2f(x,      y);
        org.lwjgl.opengl.GL11.glEnd();
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
    }

    // ── actions ───────────────────────────────────────────────────────────────

    @Override
    public void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_BACK:
                commitAndClose();
                break;
            case ID_ENABLED:
                working.setSlotEnabled(!working.isSlotEnabled());
                enabledButton.displayString = getEnabledText();
                break;
            case ID_COLOR:
                colorIndex = (colorIndex + 1) % PRESET_COLORS.length;
                working.setColor(PRESET_COLORS[colorIndex]);
                colorButton.displayString = getColorText();
                break;
            case ID_ACTION_TYPE:
                actionTypeIndex = (actionTypeIndex + 1) % ACTION_TYPE_LABELS.length;
                working.setActionType(indexToActionType(actionTypeIndex));
                if (working.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
                    working.setActionValue(RadialMenuItem.TOGGLE_FEATURE_OPTIONS[toggleFeatureIndex][0]);
                }
                actionTypeButton.displayString = getActionTypeText();
                refreshValueWidgets();
                break;
            case ID_ACTION_VAL:
                // Only shown for TOGGLE_FEATURE
                if (GomodInternalFeatures.isEnabled()) {
                    toggleFeatureIndex = (toggleFeatureIndex + 1) % RadialMenuItem.TOGGLE_FEATURE_OPTIONS.length;
                } else {
                    int count = GomodInternalFeatures.publicToggleFeatureOptionCount();
                    int cur = GomodInternalFeatures.publicCycleIndexForToggleFeatureOption(toggleFeatureIndex);
                    if (cur < 0) {
                        cur = 0;
                    }
                    cur = (cur + 1) % Math.max(1, count);
                    toggleFeatureIndex = GomodInternalFeatures.toggleFeatureOptionIndexForPublicCycle(cur);
                }
                working.setActionValue(RadialMenuItem.TOGGLE_FEATURE_OPTIONS[toggleFeatureIndex][0]);
                actionValueButton.displayString = getActionValueCycleText();
                break;
            default:
                break;
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // ESC
            commitAndClose();
            return;
        }
        if (labelField.isFocused()) {
            labelField.textboxKeyTyped(typedChar, keyCode);
            working.setLabel(labelField.getText());
        } else if (valueFieldActive && valueField.isFocused()) {
            valueField.textboxKeyTyped(typedChar, keyCode);
            working.setActionValue(valueField.getText());
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        labelField.mouseClicked(mouseX, mouseY, mouseButton);
        if (valueFieldActive) valueField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        labelField.updateCursorCounter();
        if (valueFieldActive) valueField.updateCursorCounter();
    }

    // ── commit ────────────────────────────────────────────────────────────────

    private void commitAndClose() {
        working.setLabel(labelField.getText().trim());
        if (valueFieldActive) {
            working.setActionValue(valueField.getText().trim());
        }
        RadialMenuWheel.setSlot(slotIndex, working);
        RadialMenuWheel.saveToFile();
        parent.refreshAfterSlotEditor();
        mc.displayGuiScreen(parent);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void syncIndicesFromWorking() {
        // color index
        colorIndex = 0;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == working.getColor()) { colorIndex = i; break; }
        }
        // action type index
        actionTypeIndex = actionTypeToIndex(working.getActionType());
        // toggle feature index
        toggleFeatureIndex = 0;
        String val = working.getActionValue();
        for (int i = 0; i < RadialMenuItem.TOGGLE_FEATURE_OPTIONS.length; i++) {
            if (RadialMenuItem.TOGGLE_FEATURE_OPTIONS[i][0].equals(val)) {
                toggleFeatureIndex = i;
                break;
            }
        }
        if (!GomodInternalFeatures.isEnabled()
                && GomodInternalFeatures.isRestrictedToggleFeatureKey(val)) {
            toggleFeatureIndex = GomodInternalFeatures.toggleFeatureOptionIndexForPublicCycle(0);
            working.setActionValue(RadialMenuItem.TOGGLE_FEATURE_OPTIONS[toggleFeatureIndex][0]);
        }
    }

    /**
     * Show/hide the right value widget based on the current action type.
     * TOGGLE_FEATURE → cycle button visible, text field hidden.
     * CHAT_MESSAGE / COMMAND → text field visible, cycle button hidden.
     * NONE → both hidden.
     */
    private void refreshValueWidgets() {
        RadialMenuItem.ActionType type = working.getActionType();
        boolean isToggle = type == RadialMenuItem.ActionType.TOGGLE_FEATURE;
        boolean isText   = type == RadialMenuItem.ActionType.CHAT_MESSAGE
                        || type == RadialMenuItem.ActionType.COMMAND;

        actionValueButton.visible = isToggle;
        actionValueButton.enabled = isToggle;
        valueFieldActive = isText;

        if (isText && valueField != null) {
            // Pre-populate the field if it looks like a leftover toggle key
            String cur = working.getActionValue();
            boolean isToggleKey = false;
            for (String[] kv : RadialMenuItem.TOGGLE_FEATURE_OPTIONS) {
                if (kv[0].equals(cur)) { isToggleKey = true; break; }
            }
            if (isToggleKey) {
                working.setActionValue("");
                valueField.setText("");
            } else {
                valueField.setText(cur);
            }
        }
    }

    private static int actionTypeToIndex(RadialMenuItem.ActionType t) {
        switch (t) {
            case NONE:           return 0;
            case TOGGLE_FEATURE: return 1;
            case CHAT_MESSAGE:   return 2;
            case COMMAND:        return 3;
            default:             return 0;
        }
    }

    private static RadialMenuItem.ActionType indexToActionType(int i) {
        switch (i) {
            case 1: return RadialMenuItem.ActionType.TOGGLE_FEATURE;
            case 2: return RadialMenuItem.ActionType.CHAT_MESSAGE;
            case 3: return RadialMenuItem.ActionType.COMMAND;
            default: return RadialMenuItem.ActionType.NONE;
        }
    }

    private String getEnabledText() {
        return EnumChatFormatting.GOLD + "Slot enabled: "
            + (working.isSlotEnabled() ? EnumChatFormatting.GREEN + "YES" : EnumChatFormatting.RED + "NO");
    }

    private String getColorText() {
        return EnumChatFormatting.GOLD + "Color: "
            + EnumChatFormatting.WHITE + "#" + String.format("%06X", PRESET_COLORS[colorIndex] & 0xFFFFFF)
            + "  " + EnumChatFormatting.GRAY + "[\u2190 \u2192 to cycle]";
    }

    private String getActionTypeText() {
        return EnumChatFormatting.GOLD + "Action: "
            + EnumChatFormatting.WHITE + ACTION_TYPE_LABELS[actionTypeIndex];
    }

    private String getActionValueCycleText() {
        if (working.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String display = RadialMenuItem.TOGGLE_FEATURE_OPTIONS[toggleFeatureIndex][1];
            return EnumChatFormatting.GOLD + "Feature: " + EnumChatFormatting.WHITE + display;
        }
        return EnumChatFormatting.GOLD + "Feature: " + EnumChatFormatting.GRAY + "(n/a)";
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(labelField, valueField);
    }
}
