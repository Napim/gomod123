package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Forwards keyboard Controls while Lunar owns a menu screen and Minecraft does not receive its events. */
public final class LunarMenuKeybindForwarder {
    private static final Set<Integer> forwarded = new HashSet<Integer>();
    private static volatile Field[] registryFields;
    private static String lastStatus;

    private LunarMenuKeybindForwarder() {
    }

    public static void poll(Minecraft mc) {
        boolean hasSettings = mc != null && mc.gameSettings != null;
        boolean menuControls = hasSettings && GomodMenuSettings.isLunarMenuControlsEnabled();
        boolean lunarOverlay = mc != null && LunarUiInputState.isOverlayContext(mc);
        boolean inWorld = mc != null && mc.thePlayer != null && mc.theWorld != null;
        boolean hasScreen = mc != null && mc.currentScreen != null;
        if (!lunarOverlay) {
            // Lunar can close/replace the Locker screen without React delivering onBlur.
            // Do not let the old Locker-only focus block controls on the next Lunar screen.
            LunarUiInputState.resetLockerTextInputFocusOutsideOverlay();
        }
        if (lunarOverlay && LunarUiInputState.isTextInputFocused()) {
            release(mc, false);
            record("text-input-focused screen=" + screenName(mc));
            return;
        }
        boolean active = hasSettings && menuControls && lunarOverlay && hasScreen && inWorld;
        if (!active) {
            int heldMovementRestored = release(mc, !lunarOverlay);
            record("inactive screen=" + screenName(mc)
                    + " menuControls=" + menuControls
                    + " lunarOverlay=" + lunarOverlay
                    + " overlayOpen=" + LunarUiInputState.isOverlayOpen()
                    + " mouseGrabbed=" + Mouse.isGrabbed()
                    + " inWorld=" + inWorld
                    + " heldMovementRestored=" + heldMovementRestored);
            return;
        }

        Set<Integer> registered = getRegisteredKeyCodes();
        if (registered.isEmpty()) {
            int heldMovementRestored = release(mc);
            record("unavailable screen=" + screenName(mc)
                    + " heldMovementRestored=" + heldMovementRestored);
            return;
        }

        Set<Integer> downNow = new HashSet<Integer>();
        int perspectiveKey = mc.gameSettings.keyBindTogglePerspective.getKeyCode();
        int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
        for (Integer boxedCode : registered) {
            int keyCode = boxedCode.intValue();
            // Mouse buttons and sprint stay owned by Lunar; synthetic sprint releases can disable
            // Lunar Toggle Sprint when the overlay closes. F5 has its own edge-triggered handler.
            if (keyCode <= 0 || keyCode == perspectiveKey || keyCode == sprintKey) {
                continue;
            }
            boolean down = Keyboard.isKeyDown(keyCode);
            KeyBinding.setKeyBindState(keyCode, down);
            if (down) {
                downNow.add(boxedCode);
                if (forwarded.add(boxedCode)) {
                    KeyBinding.onTick(keyCode);
                }
            }
        }

        for (Integer oldCode : new HashSet<Integer>(forwarded)) {
            if (!downNow.contains(oldCode)) {
                KeyBinding.setKeyBindState(oldCode.intValue(), false);
                forwarded.remove(oldCode);
            }
        }
        record("active screen=" + screenName(mc) + " registered=" + registered.size()
                + " sprintInput=lunar");
    }

    private static int release(Minecraft mc) {
        return release(mc, true);
    }

    private static int release(Minecraft mc, boolean restoreHeldMovement) {
        int heldMovementRestored = 0;
        for (Integer keyCode : new HashSet<Integer>(forwarded)) {
            int code = keyCode.intValue();
            if (restoreHeldMovement && isMovementKey(mc, code)) {
                // Lunar may consume the physical key-down event. Restore the still-held movement
                // key when returning control so closing the overlay does not cancel the run.
                boolean down = Keyboard.isKeyDown(code);
                KeyBinding.setKeyBindState(code, down);
                if (down) {
                    heldMovementRestored++;
                }
            } else {
                KeyBinding.setKeyBindState(code, false);
            }
        }
        forwarded.clear();
        return heldMovementRestored;
    }

    private static boolean isMovementKey(Minecraft mc, int keyCode) {
        if (mc == null || mc.gameSettings == null) {
            return false;
        }
        KeyBinding[] movementBindings = {
                mc.gameSettings.keyBindForward,
                mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft,
                mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump,
                mc.gameSettings.keyBindSneak
        };
        for (KeyBinding binding : movementBindings) {
            if (binding != null && binding.getKeyCode() == keyCode) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> getRegisteredKeyCodes() {
        Set<Integer> result = new HashSet<Integer>();
        try {
            Field[] fields = registryFields;
            if (fields == null) {
                ArrayList<Field> found = new ArrayList<Field>();
                for (Field field : KeyBinding.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Class<?> type = field.getType();
                    boolean bindingArray = type.isArray()
                            && KeyBinding.class.isAssignableFrom(type.getComponentType());
                    if (bindingArray || Map.class.isAssignableFrom(type)
                            || Collection.class.isAssignableFrom(type)) {
                        field.setAccessible(true);
                        found.add(field);
                    }
                }
                fields = found.toArray(new Field[found.size()]);
                registryFields = fields;
            }

            for (Field field : fields) {
                Object value = field.get(null);
                if (value instanceof KeyBinding[]) {
                    for (KeyBinding binding : (KeyBinding[]) value) {
                        addKeyCode(result, binding);
                    }
                } else if (value instanceof Map<?, ?>) {
                    for (Object binding : ((Map<?, ?>) value).values()) {
                        if (binding instanceof KeyBinding) {
                            addKeyCode(result, (KeyBinding) binding);
                        }
                    }
                } else if (value instanceof Iterable<?>) {
                    for (Object binding : (Iterable<?>) value) {
                        if (binding instanceof KeyBinding) {
                            addKeyCode(result, (KeyBinding) binding);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            result.clear();
        }
        return result;
    }

    private static void addKeyCode(Set<Integer> result, KeyBinding binding) {
        if (binding != null && binding.getKeyCode() > 0) {
            result.add(Integer.valueOf(binding.getKeyCode()));
        }
    }

    private static String screenName(Minecraft mc) {
        return mc == null || mc.currentScreen == null
                ? "none" : mc.currentScreen.getClass().getName();
    }

    private static void record(String state) {
        if (!state.equals(lastStatus)) {
            lastStatus = state;
            LunarConnectionDiagnostics.record("menu.keybind_forwarder", state);
        }
    }
}
