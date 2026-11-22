package me.napim.gomod.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

import java.io.IOException;

/**
 * Side-by-side Position X / Y fields using vanilla {@link GuiTextField} (blinking caret like Teams Nick Finder).
 */
public final class HudPositionFieldPair {

    public interface Applier {
        void setPosition(int x, int y);
    }

    private static final int MAX_LEN = 5;
    /** Horizontal inset inside the frame (matches {@link GuiHeader#drawHudPositionField}). */
    private static final int TEXT_PAD_X = 4;
    /** With background drawing off, vanilla draws at {@code yPosition} — use centered row in a 20px box. */
    private static final int TEXT_PAD_Y = (GuiHeader.HUD_POSITION_FIELD_HEIGHT - 8) / 2;

    private final int idX;
    private final int idY;
    private final Applier applier;
    private final boolean allowNegative;

    private GuiTextField fieldX;
    private GuiTextField fieldY;
    private int frameX;
    private int frameY;
    private int frameHalfW;
    private int scrollOffset;

    public HudPositionFieldPair(int idX, int idY, Applier applier) {
        this(idX, idY, applier, false);
    }

    public HudPositionFieldPair(int idX, int idY, Applier applier, boolean allowNegative) {
        this.idX = idX;
        this.idY = idY;
        this.applier = applier;
        this.allowNegative = allowNegative;
    }

    public void init(FontRenderer fr, int posX, int posY, int x, int y) {
        frameX = x;
        frameY = y;
        frameHalfW = GuiHeader.HUD_POSITION_FIELD_HALF_W;
        scrollOffset = 0;
        fieldX = createInnerField(fr, idX, String.valueOf(posX));
        fieldY = createInnerField(fr, idY, String.valueOf(posY));
        applyLayout();
    }

    /** Full-width single row (e.g. Kill Counter stacked X / Y). */
    public static GuiTextField createFullWidthField(FontRenderer fr, int id, int outerX, int outerY, String text,
            boolean allowNegative) {
        GuiTextField field = createInnerField(fr, id, text);
        layoutTextInFrame(field, outerX, outerY, GuiHeader.HUD_POSITION_FIELDS_TOTAL_W);
        if (allowNegative) {
            field.setMaxStringLength(MAX_LEN);
        }
        return field;
    }

    private static GuiTextField createInnerField(FontRenderer fr, int id, String text) {
        GuiTextField field = new GuiTextField(id, fr, 0, 0, 8, 8);
        field.setMaxStringLength(MAX_LEN);
        field.setEnableBackgroundDrawing(false);
        field.setText(text);
        return field;
    }

    /** Places caret/text inside a {@link GuiHeader#HUD_POSITION_FIELD_HEIGHT} frame. */
    public static void layoutTextInFrame(GuiTextField field, int outerX, int outerY, int outerW) {
        if (field == null) {
            return;
        }
        field.xPosition = outerX + TEXT_PAD_X;
        field.yPosition = outerY + TEXT_PAD_Y;
        field.width = Math.max(8, outerW - TEXT_PAD_X * 2);
        field.height = 8;
    }

    /** Updates field anchors when the layout moves (resize, scroll, or dynamic sync). */
    public void syncLayout(int x, int y) {
        frameX = x;
        frameY = y;
        applyLayout();
    }

    public void setScrollOffset(int offset) {
        scrollOffset = offset;
        applyLayout();
    }

    private void applyLayout() {
        if (fieldX == null || fieldY == null) {
            return;
        }
        int y = frameY - scrollOffset;
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        layoutTextInFrame(fieldX, frameX, y, frameHalfW);
        layoutTextInFrame(fieldY, frameX + frameHalfW + gap, y, frameHalfW);
    }

    public void sync(int posX, int posY) {
        if (fieldX != null) {
            fieldX.setText(String.valueOf(posX));
        }
        if (fieldY != null) {
            fieldY.setText(String.valueOf(posY));
        }
    }

    public void draw() {
        if (fieldX == null || fieldY == null) {
            return;
        }
        int y = frameY - scrollOffset;
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        drawInsetFrame(frameX, y, frameHalfW);
        fieldX.drawTextBox();
        drawInsetFrame(frameX + frameHalfW + gap, y, frameHalfW);
        fieldY.drawTextBox();
    }

    public static void drawInsetFrame(int x, int y, int width) {
        int h = GuiHeader.HUD_POSITION_FIELD_HEIGHT;
        int borderColor = 0xFFFFFFFF;
        int fillColor = 0xFF000000;
        Gui.drawRect(x, y, x + width, y + h, fillColor);
        Gui.drawRect(x, y, x + width, y + 1, borderColor);
        Gui.drawRect(x, y + h - 1, x + width, y + h, borderColor);
        Gui.drawRect(x, y, x + 1, y + h, borderColor);
        Gui.drawRect(x + width - 1, y, x + width, y + h, borderColor);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + h - 1, fillColor);
    }

    public static void drawField(GuiTextField field, int outerX, int outerY, int outerW) {
        if (field == null) {
            return;
        }
        layoutTextInFrame(field, outerX, outerY, outerW);
        drawInsetFrame(outerX, outerY, outerW);
        field.drawTextBox();
    }

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        int y = frameY - scrollOffset;
        int gap = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;
        int yFrameX = frameX + frameHalfW + gap;
        boolean hitX = mouseInFrame(mouseX, mouseY, frameX, y, frameHalfW);
        boolean hitY = mouseInFrame(mouseX, mouseY, yFrameX, y, frameHalfW);
        if (hitX) {
            unfocus(fieldY);
            layoutTextInFrame(fieldX, frameX, y, frameHalfW);
            fieldX.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (hitY) {
            unfocus(fieldX);
            layoutTextInFrame(fieldY, yFrameX, y, frameHalfW);
            fieldY.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (isFocused()) {
            tryApply();
            unfocusAll();
        }
    }

    public static void mouseClicked(GuiTextField field, int mouseX, int mouseY, int mouseButton,
            int outerX, int outerY, int outerW) {
        if (field == null) {
            return;
        }
        if (mouseInFrame(mouseX, mouseY, outerX, outerY, outerW)) {
            layoutTextInFrame(field, outerX, outerY, outerW);
            field.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (field.isFocused()) {
            field.setFocused(false);
        }
    }

    /**
     * Two full-width rows (e.g. Kill Counter HUD X then Y). Only one field focused; click elsewhere unfocuses both.
     */
    public static void mouseClickedStacked(GuiTextField fieldX, int outerX, int outerYx,
            GuiTextField fieldY, int outerYy, int outerW,
            int mouseX, int mouseY, int mouseButton) {
        boolean hitX = fieldX != null && mouseInFrame(mouseX, mouseY, outerX, outerYx, outerW);
        boolean hitY = fieldY != null && mouseInFrame(mouseX, mouseY, outerX, outerYy, outerW);
        if (hitX) {
            unfocus(fieldY);
            layoutTextInFrame(fieldX, outerX, outerYx, outerW);
            fieldX.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (hitY) {
            unfocus(fieldX);
            layoutTextInFrame(fieldY, outerX, outerYy, outerW);
            fieldY.mouseClicked(mouseX, mouseY, mouseButton);
        } else {
            unfocus(fieldX);
            unfocus(fieldY);
        }
    }

    public static void unfocus(GuiTextField field) {
        if (field != null) {
            field.setFocused(false);
        }
    }

    private void unfocusAll() {
        unfocus(fieldX);
        unfocus(fieldY);
    }

    private static boolean mouseInFrame(int mouseX, int mouseY, int outerX, int outerY, int outerW) {
        return mouseX >= outerX && mouseX < outerX + outerW
                && mouseY >= outerY && mouseY < outerY + GuiHeader.HUD_POSITION_FIELD_HEIGHT;
    }

    /**
     * @return true if the event was consumed by a focused field
     */
    public boolean keyTyped(char typedChar, int keyCode, Runnable onEscape) throws IOException {
        if (fieldX != null && fieldX.isFocused()) {
            if (handleFieldKey(fieldX, typedChar, keyCode, onEscape)) {
                return true;
            }
        }
        if (fieldY != null && fieldY.isFocused()) {
            if (handleFieldKey(fieldY, typedChar, keyCode, onEscape)) {
                return true;
            }
        }
        return false;
    }

    public static boolean keyTyped(GuiTextField field, char typedChar, int keyCode, boolean allowNegative,
            Runnable onEnter, Runnable onEscape) throws IOException {
        if (field == null || !field.isFocused()) {
            return false;
        }
        if (keyCode == 1 && onEscape != null) {
            onEscape.run();
            return true;
        }
        if (keyCode == 28) {
            if (onEnter != null) {
                onEnter.run();
            }
            return true;
        }
        if (typedChar != 0 && typedChar != '\b' && typedChar != '\t' && !isAllowedNumericChar(typedChar, allowNegative)) {
            return true;
        }
        field.textboxKeyTyped(typedChar, keyCode);
        return true;
    }

    private boolean handleFieldKey(GuiTextField field, char typedChar, int keyCode, Runnable onEscape)
            throws IOException {
        return keyTyped(field, typedChar, keyCode, allowNegative, this::tryApply, onEscape);
    }

    private static boolean isAllowedNumericChar(char c, boolean allowNegative) {
        return Character.isDigit(c) || (allowNegative && c == '-');
    }

    public void tryApply() {
        try {
            int x = Integer.parseInt(fieldX.getText().trim());
            int y = Integer.parseInt(fieldY.getText().trim());
            applier.setPosition(x, y);
        } catch (NumberFormatException ignored) {
        }
    }

    public int parseXOrDefault(int fallback) {
        return parseFieldOrDefault(fieldX, fallback);
    }

    public int parseYOrDefault(int fallback) {
        return parseFieldOrDefault(fieldY, fallback);
    }

    private static int parseFieldOrDefault(GuiTextField field, int fallback) {
        if (field == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void updateCursorCounter() {
        if (fieldX != null) {
            fieldX.updateCursorCounter();
        }
        if (fieldY != null) {
            fieldY.updateCursorCounter();
        }
    }

    public static void updateCursorCounter(GuiTextField field) {
        if (field != null) {
            field.updateCursorCounter();
        }
    }

    public boolean isFocused() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(fieldX, fieldY);
    }

    public static boolean isFocused(GuiTextField field) {
        return field != null && field.isFocused();
    }
}
