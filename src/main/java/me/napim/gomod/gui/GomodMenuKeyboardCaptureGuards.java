package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiTextField;

/** Helpers for {@link GomodMenuKeyboardCaptureGuard} implementations. */
public final class GomodMenuKeyboardCaptureGuards {

    private GomodMenuKeyboardCaptureGuards() {}

    public static boolean anyTextFieldFocused(GuiTextField... fields) {
        if (fields == null) {
            return false;
        }
        for (GuiTextField f : fields) {
            if (f != null && f.isFocused()) {
                return true;
            }
        }
        return false;
    }
}
