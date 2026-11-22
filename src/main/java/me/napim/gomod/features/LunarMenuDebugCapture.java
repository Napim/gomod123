package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in movement trace. Browser hooks receive only action enums, never text or browser handles. */
public final class LunarMenuDebugCapture {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 2000;
    private static final long MAX_DURATION_MS = 300000L;
    private static final String[] ROLES = {"forward", "back", "left", "right", "jump", "sneak"};
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "gomod-lunar-menu-debug-timeout"); thread.setDaemon(true); return thread;
    });
    private static final Map<String, String> LAST = new HashMap<String, String>();
    private static final Map<String, Long> WRITERS = new LinkedHashMap<String, Long>();
    private static final Set<String> SEEN = new HashSet<String>();
    private static volatile boolean active;
    private static BufferedWriter writer;
    private static File output;
    private static ScheduledFuture<?> timeout;
    private static long startedMs, tickSamples, inputSamples, keyWrites, browserKeys, browserClicks, javaCaptureCalls, bridgeCalls;
    private static int phase, events;

    private LunarMenuDebugCapture() { }

    public static File start() throws Exception {
        if (!GomodFixes.isWeaveRuntime() || !GomodInternalFeatures.isEnabled())
            throw new IllegalStateException("Internal Weave capture only");
        synchronized (LOCK) {
            if (active) return output;
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("Could not create capture directory");
            output = File.createTempFile("lunar_menu_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                    .format(new Date()) + "_", ".log", directory);
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8));
            startedMs = System.currentTimeMillis(); phase = events = 0;
            tickSamples = inputSamples = keyWrites = browserKeys = browserClicks = javaCaptureCalls = bridgeCalls = 0L;
            LAST.clear(); WRITERS.clear(); SEEN.clear(); active = true;
            appendLocked("capture.start version=1 maxDurationSeconds=300 maxEvents=2000 typedText=omitted"
                    + " browserKeyCodes=omitted noBrowserFocusQueries=true noDomProbes=true diagnosticOnly=true");
            appendLocked("phase.mark label=baseline");
            try { writer.flush(); } catch (IOException error) { stopLocked("write-error"); throw error; }
            final File session = output;
            timeout = TIMER.schedule(() -> { synchronized (LOCK) {
                if (active && session.equals(output)) stopLocked("duration-limit");
            } }, MAX_DURATION_MS, TimeUnit.MILLISECONDS);
            return output;
        }
    }

    public static File stop() { synchronized (LOCK) { if (active) stopLocked("command"); return output; } }
    public static boolean isActive() { return active; }

    public static boolean mark(String label) {
        if (!Arrays.asList("baseline", "typing", "blurred").contains(label)) return false;
        synchronized (LOCK) {
            if (!active) return false;
            summarizeLocked(); if (!active) return false;
            phase++; LAST.clear(); SEEN.clear(); WRITERS.clear();
            appendLocked("phase.mark label=" + label);
            try { if (writer != null) writer.flush(); } catch (IOException error) { stopLocked("write-error"); }
            return active;
        }
    }

    public static void sampleTick(boolean completed) {
        if (!active) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            String state = state(mc);
            synchronized (LOCK) {
                if (!active) return; tickSamples++;
                changedLocked(completed ? "tick.return" : "tick.head", state);
            }
        } catch (Throwable error) { error("tick", error); }
    }

    public static void sampleMovement(Object value, boolean completed) {
        if (!active || !(value instanceof MovementInput)) return;
        try {
            MovementInput input = (MovementInput) value;
            String state = state(Minecraft.getMinecraft()) + " moveForward=" + input.moveForward
                    + " moveStrafe=" + input.moveStrafe + " jump=" + input.jump + " sneak=" + input.sneak;
            synchronized (LOCK) {
                if (!active) return; inputSamples++;
                changedLocked(completed ? "movement.return" : "movement.head", state);
            }
        } catch (Throwable error) { error("movement", error); }
    }

    /** Called from the existing setKeyBindState invocation; non-movement keys are immediately ignored. */
    public static void recordKeyWrite(int keyCode, boolean down) {
        if (!active) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            KeyBinding[] keys = bindings(mc); String role = null;
            for (int i = 0; i < keys.length; i++) if (keys[i] != null && keys[i].getKeyCode() != 0
                    && keys[i].getKeyCode() == keyCode) { role = ROLES[i]; break; }
            if (role == null) return;
            String path = stack();
            synchronized (LOCK) {
                if (!active) return; keyWrites++;
                String source = role + " down=" + down + " stack=" + path;
                if (WRITERS.size() < 256 || WRITERS.containsKey(source))
                    WRITERS.put(source, WRITERS.containsKey(source) ? WRITERS.get(source) + 1L : 1L);
                uniqueLocked("write:" + source, "key.write role=" + source);
            }
        } catch (Throwable error) { error("key-write", error); }
    }

    public static void recordBrowserKey(Object action) { recordBrowserAction(action, false); }
    public static void recordBrowserMouse(Object action) { recordBrowserAction(action, true); }
    private static void recordBrowserAction(Object action, boolean mouse) {
        if (!active) return;
        // Enum.name() is Java metadata. Never call Browser methods or an arbitrary object's toString().
        String name = action instanceof Enum<?> ? ((Enum<?>) action).name() : "unknown";
        if (!name.matches("[A-Z_]{1,32}")) name = "unknown";
        synchronized (LOCK) {
            if (!active) return;
            if (mouse) browserClicks++; else browserKeys++;
            uniqueLocked("browser:" + mouse + ":" + name,
                    (mouse ? "browser.mouse" : "browser.key") + " action=" + name + " stack=" + stack());
        }
    }

    /** General Java overlay capture is observed, not treated as editable-field focus. */
    public static void recordJavaCapture(boolean captured) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return; javaCaptureCalls++;
            changedLocked("lunar.java_overlay_capture", "captured=" + captured + " editableFocusProof=false");
        }
    }

    public static void recordBridgeCapture(boolean captured) {
        if (!active) return;
        synchronized (LOCK) {
            if (!active) return; bridgeCalls++;
            changedLocked("lunar.bridge_capture", "captured=" + captured + " editableFocusProof=false");
        }
    }

    private static KeyBinding[] bindings(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) return new KeyBinding[0];
        return new KeyBinding[]{mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak};
    }

    private static String state(Minecraft mc) {
        StringBuilder physical = new StringBuilder(), pressed = new StringBuilder();
        for (KeyBinding binding : bindings(mc)) {
            int code = binding == null ? 0 : binding.getKeyCode();
            boolean down = code > 0 && Keyboard.isCreated() && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code)
                    || code < 0 && Mouse.isCreated() && code + 100 >= 0 && code + 100 < Mouse.getButtonCount() && Mouse.isButtonDown(code + 100);
            physical.append(down ? '1' : '0'); pressed.append(binding != null && binding.isKeyDown() ? '1' : '0');
        }
        String playerState = "player=unavailable";
        if (mc != null && mc.thePlayer != null) {
            net.minecraft.client.entity.EntityPlayerSP player = mc.thePlayer;
            MovementInput movement = player.movementInput;
            playerState = "playerSprinting=" + player.isSprinting()
                    + " horizontalCollision=" + player.isCollidedHorizontally
                    + " onGround=" + player.onGround
                    + " sneaking=" + player.isSneaking()
                    + " riding=" + player.isRiding()
                    + " usingItem=" + player.isUsingItem()
                    + " moveForward=" + (movement == null ? "na" : movement.moveForward)
                    + " moveStrafe=" + (movement == null ? "na" : movement.moveStrafe)
                    + " jump=" + (movement != null && movement.jump);
        }
        return "screen=" + (mc == null || mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName())
                + " inWorld=" + (mc != null && mc.theWorld != null && mc.thePlayer != null)
                + " overlayOpen=" + LunarUiInputState.isOverlayOpen()
                + " overlayContext=" + LunarUiInputState.isOverlayContext(mc)
                + " mouseGrabbed=" + (Mouse.isCreated() && Mouse.isGrabbed())
                + " cachedTextFocus=" + LunarUiInputState.getLastTextInputFocused()
                + " focusSources=" + LunarUiInputState.getTextInputFocusSourceCount()
                + " lunarMovement=" + GomodMenuSettings.isLunarMenuControlsEnabled()
                + " mcMovement=" + GomodMenuSettings.isMinecraftMenuControlsEnabled()
                + " gomodMovement=" + GomodMenuSettings.isMoveWhileMenuEnabled()
                + " physicalFBLRJS=" + physical + " pressedFBLRJS=" + pressed
                + " " + playerState;
    }

    private static String stack() {
        StringBuilder out = new StringBuilder(); int count = 0;
        for (StackTraceElement entry : new Throwable().getStackTrace()) {
            String cls = entry.getClassName();
            if (cls.equals(LunarMenuDebugCapture.class.getName()) || !(cls.startsWith("com.moonsworth.")
                    || cls.startsWith("net.minecraft.") || cls.startsWith("me.napim.gomod."))) continue;
            if (out.length() > 0) out.append('>');
            out.append(cls).append('.').append(entry.getMethodName()).append(':').append(entry.getLineNumber());
            if (++count >= 8) break;
        }
        return out.length() == 0 ? "unavailable" : out.toString();
    }

    private static void changedLocked(String stage, String state) {
        if (!state.equals(LAST.get(stage))) { LAST.put(stage, state); appendLocked(stage + " " + state); }
    }
    private static void uniqueLocked(String key, String event) { if (SEEN.size() < MAX_EVENTS && SEEN.add(key)) appendLocked(event); }
    private static void error(String stage, Throwable error) {
        synchronized (LOCK) { if (active) uniqueLocked("error:" + stage, "capture.error stage=" + stage + " type=" + error.getClass().getSimpleName()); }
    }
    private static String summary() {
        return "tickSamples=" + tickSamples + " inputSamples=" + inputSamples + " movementKeyWrites=" + keyWrites
                + " browserKeys=" + browserKeys + " browserMouseEvents=" + browserClicks
                + " javaCaptureCalls=" + javaCaptureCalls + " bridgeCaptureCalls=" + bridgeCalls;
    }
    private static void summarizeLocked() {
        appendLocked("phase.summary " + summary());
        for (Map.Entry<String, Long> entry : new LinkedHashMap<String, Long>(WRITERS).entrySet()) {
            if (!active) break;
            appendLocked("key.writer_summary count=" + entry.getValue() + " role=" + entry.getKey());
        }
    }
    private static void appendLocked(String line) {
        if (writer == null) return;
        try {
            writer.write("elapsedMs=" + Math.max(0L, System.currentTimeMillis() - startedMs) + " phase=" + phase + " " + line);
            writer.newLine();
            if (++events >= MAX_EVENTS && active) stopLocked("event-limit");
        } catch (IOException error) { stopLocked("write-error"); }
    }
    private static void stopLocked(String reason) {
        active = false;
        if (timeout != null) { timeout.cancel(false); timeout = null; }
        BufferedWriter closing = writer; writer = null;
        if (closing != null) {
            try {
                closing.write("elapsedMs=" + Math.max(0L, System.currentTimeMillis() - startedMs)
                        + " phase=" + phase + " capture.stop reason=" + reason + " " + summary()); closing.newLine();
                for (Map.Entry<String, Long> entry : WRITERS.entrySet()) {
                    closing.write("key.writer_summary count=" + entry.getValue() + " role=" + entry.getKey()); closing.newLine();
                }
            } catch (IOException ignored) { }
            finally { try { closing.close(); } catch (IOException ignored) { } }
        }
        LAST.clear(); SEEN.clear(); WRITERS.clear();
    }
}
