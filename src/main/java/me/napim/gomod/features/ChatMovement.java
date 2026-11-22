package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Opens chat while preserving a snapshot of the player's current movement.
 *
 * <p>The snapshot is fixed for the lifetime of that chat screen unless the configured gameplay
 * modifier temporarily passes live movement through. This lets the movement keys be released for
 * typing. No packets, positions, rotations, or timers are modified.</p>
 */
public final class ChatMovement {
    private static boolean active;
    private static boolean forward;
    private static boolean back;
    private static boolean left;
    private static boolean right;
    private static boolean jump;
    private static boolean sneak;
    private static boolean perspectiveKeyWasDown;
    private static boolean normalChatTracked;

    private ChatMovement() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Whether this chat screen currently supplies movement input to the native player update. */
    public static boolean isMovementInputAllowedNow(Minecraft mc) {
        if (mc == null || mc.gameSettings == null || !(mc.currentScreen instanceof GuiChat)) {
            return false;
        }
        if (active) {
            return true;
        }
        int modifierCode = getPassthroughKeyCode();
        return modifierCode != 0 && isRawKeyDown(modifierCode)
                && anyLiveMovementKeyDown(mc, modifierCode);
    }

    public static boolean isDragLookEnabled() {
        return ConfigManager.isChatMovementDragLookEnabled();
    }

    public static void setDragLookEnabled(boolean enabled) {
        ConfigManager.setChatMovementDragLookEnabled(enabled);
    }

    public static int getPassthroughKeyCode() {
        return ConfigManager.getChatMovementPassthroughKeyCode();
    }

    public static void setPassthroughKeyCode(int keyCode) {
        ConfigManager.setChatMovementPassthroughKeyCode(keyCode);
    }

    /**
     * Consume gameplay chords before {@link GuiChat} inserts their characters.
     * The live actions themselves are applied from hardware state in {@link #applyActiveInputs(Minecraft)}.
     */
    public static boolean shouldConsumeChatKey(int keyCode) {
        Minecraft mc = Minecraft.getMinecraft();
        if (keyCode == 0 || mc == null || !(mc.currentScreen instanceof GuiChat)) {
            return false;
        }
        int modifierCode = getPassthroughKeyCode();
        if (modifierCode == 0) {
            return false;
        }
        // A letter can be used as the modifier too; never insert the modifier itself.
        if (keyCode == modifierCode) {
            return true;
        }
        if (!isRawKeyDown(modifierCode)) {
            return false;
        }
        if (mc.gameSettings == null) {
            return false;
        }
        boolean perspective = matches(keyCode, mc.gameSettings.keyBindTogglePerspective);
        if (perspective && !perspectiveKeyWasDown) {
            cyclePerspective(mc);
            perspectiveKeyWasDown = true;
        }
        return matches(keyCode, mc.gameSettings.keyBindForward)
                || matches(keyCode, mc.gameSettings.keyBindBack)
                || matches(keyCode, mc.gameSettings.keyBindLeft)
                || matches(keyCode, mc.gameSettings.keyBindRight)
                || matches(keyCode, mc.gameSettings.keyBindJump)
                || matches(keyCode, mc.gameSettings.keyBindSprint)
                || matches(keyCode, mc.gameSettings.keyBindSneak)
                || perspective
                || matches(keyCode, mc.gameSettings.keyBindPlayerList);
    }

    /**
     * Called after normal keyboard event processing so the activation key is not typed into chat.
     */
    public static void onClientTickEnd() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.thePlayer == null || mc.theWorld == null) {
            stopSession(mc);
            return;
        }

        if (active) {
            if (!(mc.currentScreen instanceof GuiChat)) {
                stopSession(mc);
            } else {
                applyActiveInputs(mc);
            }
            return;
        }

        if (mc.currentScreen instanceof GuiChat) {
            normalChatTracked = true;
            applyNormalChatInputs(mc);
            return;
        }
        restoreAfterNormalChatIfNeeded(mc);

        if (mc.currentScreen != null) {
            return;
        }

        KeyBinding openKey = GomodKeybinds.getChatMovementKeyBinding();
        if (openKey != null && openKey.getKeyCode() != 0 && openKey.isPressed()) {
            startSession(mc);
        }
    }

    /** Keep the captured states applied before the player movement update runs. */
    public static void onRunTickHead() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active) {
            if (mc != null && mc.gameSettings != null && mc.currentScreen instanceof GuiChat) {
                normalChatTracked = true;
                applyNormalChatInputs(mc);
            } else {
                restoreAfterNormalChatIfNeeded(mc);
            }
            return;
        }
        if (mc == null || !(mc.currentScreen instanceof GuiChat)) {
            stopSession(mc);
            return;
        }
        applyActiveInputs(mc);
    }

    private static void applyNormalChatInputs(Minecraft mc) {
        int modifierCode = getPassthroughKeyCode();
        // With chat movement unbound, gomod must not rewrite vanilla movement keys at all.
        // Lunar Toggle Sprint treats an external setKeyBindState(sprint, false) as a real toggle-off.
        if (modifierCode == 0) {
            normalChatTracked = false;
            perspectiveKeyWasDown = false;
            return;
        }
        boolean modifierDown = modifierCode != 0 && isRawKeyDown(modifierCode);
        if (modifierDown && anyLiveMovementKeyDown(mc, modifierCode)) {
            applyLiveMovement(mc);
        } else {
            releaseMovementKeys(mc);
        }
        syncPlayerList(mc, modifierDown, modifierCode);
        updatePerspective(mc, modifierDown, modifierCode);
    }

    private static void startSession(Minecraft mc) {
        forward = isHardwareDown(mc.gameSettings.keyBindForward);
        back = isHardwareDown(mc.gameSettings.keyBindBack);
        left = isHardwareDown(mc.gameSettings.keyBindLeft);
        right = isHardwareDown(mc.gameSettings.keyBindRight);
        jump = isHardwareDown(mc.gameSettings.keyBindJump);
        sneak = isSneakHeldBeforeChat(mc);
        perspectiveKeyWasDown = false;

        active = true;
        mc.displayGuiScreen(new GuiChat());
        applyActiveInputs(mc);
    }

    private static void applyActiveInputs(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        int modifierCode = getPassthroughKeyCode();
        boolean modifierDown = modifierCode != 0 && isRawKeyDown(modifierCode);
        boolean liveMovement = modifierDown && anyLiveMovementKeyDown(mc, modifierCode);

        if (liveMovement) {
            applyLiveMovement(mc);
        } else {
            applySnapshot(mc);
        }

        syncPlayerList(mc, modifierDown, modifierCode);
        updatePerspective(mc, modifierDown, modifierCode);
    }

    private static void applySnapshot(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        set(mc.gameSettings.keyBindForward, forward);
        set(mc.gameSettings.keyBindBack, back);
        set(mc.gameSettings.keyBindLeft, left);
        set(mc.gameSettings.keyBindRight, right);
        set(mc.gameSettings.keyBindJump, jump);
        set(mc.gameSettings.keyBindSneak, sneak);
    }

    private static void applyLiveMovement(Minecraft mc) {
        // Once another movement key forms a chord, let the modifier double as its normal bind too
        // (notably LCONTROL remains the vanilla sprint key for LCONTROL + W).
        set(mc.gameSettings.keyBindForward, isHardwareDown(mc.gameSettings.keyBindForward));
        set(mc.gameSettings.keyBindBack, isHardwareDown(mc.gameSettings.keyBindBack));
        set(mc.gameSettings.keyBindLeft, isHardwareDown(mc.gameSettings.keyBindLeft));
        set(mc.gameSettings.keyBindRight, isHardwareDown(mc.gameSettings.keyBindRight));
        set(mc.gameSettings.keyBindJump, isHardwareDown(mc.gameSettings.keyBindJump));
        set(mc.gameSettings.keyBindSneak, isHardwareDown(mc.gameSettings.keyBindSneak));
    }

    private static boolean anyLiveMovementKeyDown(Minecraft mc, int modifierCode) {
        return isHardwareDownExcept(mc.gameSettings.keyBindForward, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindBack, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindLeft, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindRight, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindJump, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindSprint, modifierCode)
                || isHardwareDownExcept(mc.gameSettings.keyBindSneak, modifierCode);
    }

    private static void syncPlayerList(Minecraft mc, boolean modifierDown, int modifierCode) {
        KeyBinding playerList = mc.gameSettings.keyBindPlayerList;
        boolean down = modifierDown && isHardwareDownExcept(playerList, modifierCode);
        set(playerList, down);
    }

    private static void updatePerspective(Minecraft mc, boolean modifierDown, int modifierCode) {
        KeyBinding perspective = mc.gameSettings.keyBindTogglePerspective;
        boolean down = modifierDown && isHardwareDownExcept(perspective, modifierCode);
        if (down && !perspectiveKeyWasDown) {
            cyclePerspective(mc);
        }
        perspectiveKeyWasDown = down;
    }

    private static void cyclePerspective(Minecraft mc) {
        ++mc.gameSettings.thirdPersonView;
        if (mc.gameSettings.thirdPersonView > 2) {
            mc.gameSettings.thirdPersonView = 0;
        }
    }

    private static void stopSession(Minecraft mc) {
        if (!active) {
            return;
        }
        active = false;
        forward = false;
        back = false;
        left = false;
        right = false;
        jump = false;
        sneak = false;
        perspectiveKeyWasDown = false;
        restorePhysicalKeys(mc);
    }

    private static void restoreAfterNormalChatIfNeeded(Minecraft mc) {
        if (!normalChatTracked) {
            return;
        }
        normalChatTracked = false;
        perspectiveKeyWasDown = false;
        restorePhysicalKeys(mc);
    }

    private static void releaseMovementKeys(Minecraft mc) {
        set(mc.gameSettings.keyBindForward, false);
        set(mc.gameSettings.keyBindBack, false);
        set(mc.gameSettings.keyBindLeft, false);
        set(mc.gameSettings.keyBindRight, false);
        set(mc.gameSettings.keyBindJump, false);
        set(mc.gameSettings.keyBindSneak, false);
    }

    /**
     * Modern-style GUI close handling: restore what is physically held instead of leaving stale false states.
     */
    private static void restorePhysicalKeys(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        set(mc.gameSettings.keyBindForward, isHardwareDown(mc.gameSettings.keyBindForward));
        set(mc.gameSettings.keyBindBack, isHardwareDown(mc.gameSettings.keyBindBack));
        set(mc.gameSettings.keyBindLeft, isHardwareDown(mc.gameSettings.keyBindLeft));
        set(mc.gameSettings.keyBindRight, isHardwareDown(mc.gameSettings.keyBindRight));
        set(mc.gameSettings.keyBindJump, isHardwareDown(mc.gameSettings.keyBindJump));
        set(mc.gameSettings.keyBindSneak, isHardwareDown(mc.gameSettings.keyBindSneak));
        set(mc.gameSettings.keyBindPlayerList, isHardwareDown(mc.gameSettings.keyBindPlayerList));
    }

    private static boolean matches(int keyCode, KeyBinding binding) {
        return binding != null && binding.getKeyCode() == keyCode;
    }

    private static boolean isHardwareDownExcept(KeyBinding binding, int excludedCode) {
        return binding != null && binding.getKeyCode() != excludedCode && isHardwareDown(binding);
    }

    private static boolean isHardwareDown(KeyBinding binding) {
        return binding != null && isRawKeyDown(binding.getKeyCode());
    }

    /**
     * Screen opening can clear LWJGL/KeyBinding state. Snapshot the actual sneak state as well as the raw key so
     * Movement Chat does not send a one-tick stop-sneaking transition when that happens.
     */
    private static boolean isSneakHeldBeforeChat(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return false;
        }
        KeyBinding sneakBinding = mc.gameSettings.keyBindSneak;
        if (isHardwareDown(sneakBinding) || (sneakBinding != null && sneakBinding.isKeyDown())) {
            return true;
        }
        if (mc.thePlayer == null) {
            return false;
        }
        return mc.thePlayer.isSneaking()
                || (mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.sneak);
    }

    private static boolean isRawKeyDown(int keyCode) {
        if (keyCode == 0) {
            return false;
        }
        try {
            if (keyCode < 0) {
                return Mouse.isButtonDown(keyCode + 100);
            }
            return Keyboard.isKeyDown(keyCode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void set(KeyBinding binding, boolean pressed) {
        if (binding != null && binding.getKeyCode() != 0) {
            KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
        }
    }

}
