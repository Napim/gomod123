package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.RadialMenuWheel;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;

/**
 * Transparent full-screen overlay shown while the radial-menu keybind is held.
 *
 * <ul>
 *   <li>Game continues running ({@link #doesGuiPauseGame()} = false).</li>
 *   <li>Key release activates immediately, then collapses the wheel; left-click executes
 *       immediately and keeps the wheel open.</li>
 *   <li>Right-click opens settings for the selected module.</li>
 *   <li>Pressing Escape dismisses without executing anything.</li>
 * </ul>
 */
public class RadialMenuOverlayScreen extends GuiScreen {

    /** Currently hovered slice (-1 = centre / cancel). */
    private int hoveredSlot = -1;
    /** Slices already activated by click, so key release won't activate one twice. */
    private int clickedSlotsMask;
    /** Timestamp when current hovered slice started. */
    private long hoveredSinceMs = 0L;
    private boolean cursorInitialized;
    private final RadialMenuWheel.VisualState visual = new RadialMenuWheel.VisualState();
    private final RadialMenuWheel.Transition transition = new RadialMenuWheel.Transition();
    private int settingsSlot = -1;
    private int assignmentSlot = -1;
    private boolean reopenAfterClose;

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        // Let movement input continue while this lightweight overlay is open.
        this.allowUserInput = true;
        transition.open(System.nanoTime());
        if (!cursorInitialized) {
            Mouse.setCursorPosition(mc.displayWidth / 2, mc.displayHeight / 2);
            cursorInitialized = true;
        }
        // No buttons — purely rendered via drawScreen.
    }

    @Override
    public void updateScreen() {
        syncMovementKeys();
        if (transition.isClosing()) {
            observeReopenRequest();
            finishClose(System.nanoTime());
            return;
        }
        int keyCode = ConfigManager.getRadialMenuKeyCode();
        if (!isKeyCurrentlyDown(keyCode)) {
            // Sample at release, including quick flicks/return-to-center between rendered frames.
            ScaledResolution sr = new ScaledResolution(mc);
            int x = Mouse.getX() * sr.getScaledWidth() / mc.displayWidth;
            int y = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / mc.displayHeight - 1;
            updateHovered(x, y);
            int releasedSlot = hoveredSlot;
            boolean alreadyClicked = releasedSlot >= 0 && (clickedSlotsMask & (1 << releasedSlot)) != 0;
            clickedSlotsMask = 0;
            transition.close(System.nanoTime());
            if (releasedSlot >= 0 && !alreadyClicked) {
                if (RadialMenuWheel.isSlotAvailableForAssignment(releasedSlot)) {
                    assignmentSlot = releasedSlot;
                } else {
                    // Toggle now, without waiting for the animation. An action that opens
                    // another GUI replaces us immediately; finishClose never dismisses it.
                    RadialMenuWheel.executeSlot(releasedSlot);
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long nowNs = System.nanoTime();
        observeReopenRequest();
        if (finishClose(nowNs)) return;
        if (!transition.isClosing()) updateHovered(mouseX, mouseY);
        long hoveredForMs = hoveredSlot >= 0 ? Math.max(0L, System.currentTimeMillis() - hoveredSinceMs) : 0L;
        RadialMenuWheel.renderWheel(mouseX, mouseY, hoveredSlot, hoveredForMs, visual, transition.value(nowNs));
        // intentionally NOT calling super.drawScreen — no vanilla button rendering needed
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (transition.isClosing()) {
            int keyCode = ConfigManager.getRadialMenuKeyCode();
            if (keyCode < 0 && keyCode == -(mouseButton + 1)) reopenAfterClose = true;
            return;
        }
        if (mouseButton != 0 && mouseButton != 1) return;
        updateHovered(mouseX, mouseY);
        if (hoveredSlot < 0) return;

        if (mouseButton == 1) {
            settingsSlot = hoveredSlot;
            transition.close(System.nanoTime());
        } else if (RadialMenuWheel.isSlotAvailableForAssignment(hoveredSlot)) {
            assignmentSlot = hoveredSlot;
            transition.close(System.nanoTime());
        } else {
            // Activate immediately but leave the overlay open for more selections.
            // The matching key release is suppressed to avoid toggling this slice twice.
            clickedSlotsMask |= 1 << hoveredSlot;
            RadialMenuWheel.executeSlot(hoveredSlot);
        }
    }

    private void updateHovered(int mouseX, int mouseY) {
        int newHovered = RadialMenuWheel.computeHovered(mouseX, mouseY, hoveredSlot);
        if (newHovered != hoveredSlot) {
            hoveredSlot = newHovered;
            hoveredSinceMs = System.currentTimeMillis();
        } else if (hoveredSinceMs == 0L) {
            hoveredSinceMs = System.currentTimeMillis();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (transition.isClosing() && keyCode > 0
                && keyCode == ConfigManager.getRadialMenuKeyCode()) {
            reopenAfterClose = true;
        }
        // Escape cancels the wheel without executing
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Escape may also cancel a settings transition during the closing tail.
            reopenAfterClose = false;
            settingsSlot = assignmentSlot = -1;
            transition.close(System.nanoTime());
        }
        // Swallow all other key events so they don't affect the game
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        // Swallow
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private boolean finishClose(long nowNs) {
        if (!transition.isFinished(nowNs)) return false;
        if (mc.currentScreen == this) {
            boolean reopen = reopenAfterClose && settingsSlot < 0 && assignmentSlot < 0
                    && RadialMenuWheel.isEnabled() && mc.theWorld != null && mc.thePlayer != null;
            mc.displayGuiScreen(null);
            if (settingsSlot >= 0) RadialMenuWheel.openModuleSettings(settingsSlot);
            else if (assignmentSlot >= 0) mc.displayGuiScreen(new RadialMenuWheelSlotsGUI(null, assignmentSlot));
            else if (reopen) mc.displayGuiScreen(new RadialMenuOverlayScreen());
        }
        return true;
    }

    /** Remember a key press that arrives during the closing tail; reopening waits for its animation to finish. */
    private void observeReopenRequest() {
        if (!transition.isClosing() || reopenAfterClose) return;
        if (isKeyCurrentlyDown(ConfigManager.getRadialMenuKeyCode())) reopenAfterClose = true;
    }

    private static boolean isKeyCurrentlyDown(int keyCode) {
        if (keyCode == 0) return false;
        if (keyCode > 0)  return Keyboard.isKeyDown(keyCode);
        return Mouse.isButtonDown(-(keyCode + 1));
    }

    /**
     * Keep movement key states updated while a GUI is open so players can still move
     * while holding the radial menu keybind.
     */
    private void syncMovementKeys() {
        if (mc == null || mc.gameSettings == null) return;
        KeyBinding[] movementKeys = new KeyBinding[] {
                mc.gameSettings.keyBindForward,
                mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft,
                mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump,
                mc.gameSettings.keyBindSneak,
                mc.gameSettings.keyBindSprint
        };
        for (KeyBinding bind : movementKeys) {
            if (bind == null) continue;
            int code = bind.getKeyCode();
            KeyBinding.setKeyBindState(code, isKeyCurrentlyDown(code));
        }
    }
}
