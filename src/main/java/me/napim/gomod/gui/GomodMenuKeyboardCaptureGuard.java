package me.napim.gomod.gui;

/**
 * Implemented by gomod {@link net.minecraft.client.gui.GuiScreen}s where keyboard input must reach
 * a {@link net.minecraft.client.gui.GuiTextField} or a keybind-capture flow without WASD moving the player.
 * {@link me.napim.gomod.features.GomodMenuSettings} "move while in menu" and drag-look consult this each tick/frame.
 */
public interface GomodMenuKeyboardCaptureGuard {

    /**
     * When {@code true}, do not sync movement keys from hardware or apply drag-look for this screen.
     */
    boolean shouldBlockGomodMenuMovementAndDragLook();
}
