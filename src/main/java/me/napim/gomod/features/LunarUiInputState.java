package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Lunar menu lifecycle and text-input state for movement and drag-look. */
public final class LunarUiInputState {
    private static volatile boolean overlayOpen;
    private static volatile boolean lockerTextInputFocused;
    private static final Set<TextInputFocusSource> INPUT_FOCUS_SOURCES =
            Collections.newSetFromMap(new WeakHashMap<TextInputFocusSource, Boolean>());
    private static boolean lastTextInputFocused;

    /** Legacy source contract; the crashed native Browser source remains unregistered. */
    public interface TextInputFocusSource {
        boolean gomod$hasTextInputFocus();
    }

    private LunarUiInputState() {
    }

    public static void setOverlayOpen(boolean open) {
        // A WebOSR input can stay focused when Lunar replaces its route without delivering the
        // input's blur callback. Do not carry that focus into the next Lunar menu or wheel; the
        // document-level focus listener will set it again when a field receives focus.
        if (open) {
            clearLockerTextInputFocus("overlay-open-reset");
        }
        if (overlayOpen != open) {
            overlayOpen = open;
            LunarConnectionDiagnostics.record("lunar.overlay_lifecycle", "overlayOpen=" + open);
        }
        if (!open) {
            clearLockerTextInputFocus("overlay-close-reset");
            synchronized (INPUT_FOCUS_SOURCES) {
                recordTextInputFocus(false);
            }
        }
    }

    /** Receives text-focus events from Lunar's WebOSR document; no Browser query is made. */
    public static void setLockerTextInputFocused(boolean focused) {
        if (lockerTextInputFocused == focused) return;
        lockerTextInputFocused = focused;
        LunarConnectionDiagnostics.record("lunar.locker_text_focus",
                "focused=" + focused + " source=locker-input-event");
    }

    /** Clear WebOSR input focus when Lunar opens another overlay without sending a blur event. */
    public static void resetLockerTextInputFocusForMenuNavigation() {
        clearLockerTextInputFocus("menu-navigation-reset");
    }

    /** Clear WebOSR input focus once the game leaves Lunar's overlay context. */
    public static void resetLockerTextInputFocusOutsideOverlay() {
        clearLockerTextInputFocus("non-lunar-overlay-reset");
    }

    private static void clearLockerTextInputFocus(String source) {
        if (!lockerTextInputFocused) return;
        lockerTextInputFocused = false;
        LunarConnectionDiagnostics.record("lunar.locker_text_focus",
                "focused=false source=" + source);
    }

    public static void registerTextInputFocusSource(TextInputFocusSource source) {
        synchronized (INPUT_FOCUS_SOURCES) {
            INPUT_FOCUS_SOURCES.add(source);
            LunarConnectionDiagnostics.record("lunar.text_input_source",
                    "registered=true browsers=" + INPUT_FOCUS_SOURCES.size());
        }
    }

    public static void unregisterTextInputFocusSource(TextInputFocusSource source) {
        synchronized (INPUT_FOCUS_SOURCES) {
            INPUT_FOCUS_SOURCES.remove(source);
        }
    }

    /** The active Java menu owns this state. Dormant native Browser sources are never queried. */
    public static boolean isTextInputFocused() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean javaFocused = mc != null && LunarJavaMenuFocus.isEditing(mc.currentScreen);
        boolean lockerFocused = lockerTextInputFocused && isOverlayContext(mc);
        boolean focused = javaFocused || lockerFocused;
        synchronized (INPUT_FOCUS_SOURCES) {
            recordTextInputFocus(focused, lockerFocused ? "locker-input-event" : "java-screen-editing");
            return focused;
        }
    }

    private static void recordTextInputFocus(boolean focused) {
        recordTextInputFocus(focused, "java-screen-editing");
    }

    private static void recordTextInputFocus(boolean focused, String source) {
        if (lastTextInputFocused != focused) {
            lastTextInputFocused = focused;
            LunarConnectionDiagnostics.record("lunar.text_input_focus", "focused=" + focused
                    + " source=" + source);
        }
    }

    /** Diagnostic metadata only: neither accessor invokes a focus source or Browser method. */
    public static boolean getLastTextInputFocused() {
        synchronized (INPUT_FOCUS_SOURCES) { return lastTextInputFocused; }
    }

    public static int getTextInputFocusSourceCount() {
        Minecraft mc = Minecraft.getMinecraft();
        return (mc != null && LunarJavaMenuFocus.supports(mc.currentScreen) ? 1 : 0)
                + (lockerTextInputFocused && isOverlayContext(mc) ? 1 : 0);
    }

    public static boolean isOverlayOpen() {
        return overlayOpen;
    }

    /** Recognize Lunar's menu without mistaking Minecraft inventory/screens for the overlay. */
    public static boolean isOverlayContext(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        if (mc.currentScreen != null) {
            // Prefer the live screen identity when Lunar exposes one. The lifecycle callback
            // target is obfuscated and can silently miss after a Lunar remap/update; requiring
            // its flag here disables movement even for an unambiguous active Lunar screen.
            return isLunarScreen(mc.currentScreen.getClass().getName());
        }
        // WebOSR sometimes owns the overlay while Minecraft has no GuiScreen instance. In that
        // case retain the lifecycle flag and mouse-grab guard to avoid treating ordinary play as UI.
        return overlayOpen && !Mouse.isGrabbed();
    }

    private static boolean isLunarScreen(String screenName) {
        return screenName.startsWith("com.moonsworth.lunar.")
                || screenName.startsWith("com.moonsworth.webosr.");
    }
}
