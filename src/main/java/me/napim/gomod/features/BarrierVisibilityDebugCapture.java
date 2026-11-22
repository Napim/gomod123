package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Short, opt-in comparison capture for Barrier Visibility and the first-person hand GL baseline. */
public final class BarrierVisibilityDebugCapture {
    private static final Object LOCK = new Object();
    private static final long MAX_DURATION_MS = 3L * 60L * 1000L;
    private static final long MIN_SAMPLE_INTERVAL_MS = 500L;
    private static final long HEARTBEAT_MS = 1500L;
    private static final int MAX_RECORDS = 3500;

    private static volatile boolean active;
    private static File output;
    private static long startedMs;
    private static String phase = "unmarked";
    private static final List<String> records = new ArrayList<String>();
    private static final Map<String, String> lastSamples = new LinkedHashMap<String, String>();
    private static final Map<String, Long> lastSampleMs = new LinkedHashMap<String, Long>();

    private BarrierVisibilityDebugCapture() { }

    public static File start() throws IOException {
        if (!GomodFixes.isWeaveRuntime() || !GomodInternalFeatures.isEnabled()) {
            throw new IllegalStateException("Internal Weave capture only");
        }
        synchronized (LOCK) {
            if (active) return output;
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Could not create capture directory");
            }
            output = File.createTempFile("barrier_visibility_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + "_", ".log", directory);
            startedMs = System.currentTimeMillis();
            phase = "unmarked";
            records.clear();
            lastSamples.clear();
            lastSampleMs.clear();
            active = true;
            addLocked("capture.start version=1 maxDurationSeconds=180 maxRecords=" + MAX_RECORDS
                    + " playerNames=false coordinates=false");
            addLocked("capture.settings " + settings());
            return output;
        }
    }

    public static File stop() {
        synchronized (LOCK) {
            if (active) stopLocked("command");
            return output;
        }
    }

    public static boolean mark(String label) {
        if (!"issue".equals(label) && !"normal".equals(label)) return false;
        synchronized (LOCK) {
            if (!active) return false;
            if (expiredLocked()) {
                stopLocked("duration-limit");
                return false;
            }
            phase = label;
            lastSamples.clear();
            lastSampleMs.clear();
            addLocked("phase.mark label=" + label + " " + settings());
            return active;
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static String status() {
        synchronized (LOCK) {
            if (!active) return "inactive";
            if (expiredLocked()) {
                stopLocked("duration-limit");
                return "inactive (time limit reached); saved " + (output == null ? "<unknown>" : output.getAbsolutePath());
            }
            return "active phase=" + phase + " records=" + records.size();
        }
    }

    /** Called only at render-thread checkpoints while an explicit capture is active. */
    public static void sample(String stage, String details) {
        if (!active) return;
        long now = System.currentTimeMillis();
        String key = clean(stage);
        synchronized (LOCK) {
            if (!active) return;
            if (now - startedMs >= MAX_DURATION_MS) {
                stopLocked("duration-limit");
                return;
            }
            Long lastAt = lastSampleMs.get(key);
            if (lastAt != null && now - lastAt.longValue() < MIN_SAMPLE_INTERVAL_MS) return;
        }
        String sample;
        try {
            sample = settings() + " " + readGlState() + " " + clean(details);
        } catch (Throwable error) {
            sample = "snapshotError=" + error.getClass().getSimpleName() + " " + clean(details);
        }
        synchronized (LOCK) {
            if (!active) return;
            if (now - startedMs >= MAX_DURATION_MS) {
                stopLocked("duration-limit");
                return;
            }
            String previous = lastSamples.get(key);
            Long lastAt = lastSampleMs.get(key);
            if (sample.equals(previous) && lastAt != null && now - lastAt.longValue() < HEARTBEAT_MS) return;
            lastSamples.put(key, sample);
            lastSampleMs.put(key, now);
            addLocked("sample elapsedMs=" + (now - startedMs) + " phase=" + phase
                    + " stage=" + key + " " + sample);
            if (records.size() >= MAX_RECORDS) stopLocked("record-limit");
        }
    }

    private static String settings() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            int rgb = ConfigManager.getBarrierOutlineColorArgb() & 0x00FFFFFF;
            String view = mc == null || mc.gameSettings == null ? "unknown"
                    : String.valueOf(mc.gameSettings.thirdPersonView);
            String screen = mc == null || mc.currentScreen == null ? "none"
                    : mc.currentScreen.getClass().getSimpleName();
            boolean chatOpen = "GuiChat".equals(screen);
            return "settings[enabled=" + ConfigManager.isBarrierVisibilityEnabled()
                    + " effective=" + BarrierVisibility.isEffectivelyEnabled()
                    + " style=" + BarrierVisibilityStyles.normalize(ConfigManager.getBarrierVisibilityStyle())
                    + " rgb=#" + String.format(Locale.ROOT, "%06X", rgb)
                    + " opacity=" + ConfigManager.getBarrierVisibilityOpacityPercent()
                    + " rangeEnabled=" + ConfigManager.isBarrierVisibilityRangeEnabled()
                    + " range=" + ConfigManager.getBarrierVisibilityRange()
                    + " aimedOnly=" + ConfigManager.isBarrierVisibilityAimedOnly()
                    + " cursorReveal=" + ConfigManager.isBarrierVisibilityShowAtCursor()
                    + " cageBarriers=" + ConfigManager.isBarrierBlocksShownInCages()
                    + " view=" + view + " screen=" + screen + " chatOpen=" + chatOpen
                    + " atlas={" + BarrierVisibilityAtlasHook.debugStatus() + "}]";
        } catch (Throwable error) {
            return "settingsError=" + error.getClass().getSimpleName();
        }
    }

    private static String readGlState() {
        try {
            return "gl[alpha=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    + " alphaFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC))
                    + " alphaRef=" + String.format(Locale.ROOT, "%.4f", GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF))
                    + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + " blendFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_SRC))
                    + "/0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_DST))
                    + " cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE)
                    + " lighting=" + GL11.glIsEnabled(GL11.GL_LIGHTING)
                    + " fog=" + GL11.glIsEnabled(GL11.GL_FOG)
                    + " depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    + " depthFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC))
                    + " depthWrite=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + " texture2D=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                    + " activeTexture=0x" + Integer.toHexString(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE))
                    + " boundTexture=" + GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                    + " lineWidth=" + String.format(Locale.ROOT, "%.2f", GL11.glGetFloat(GL11.GL_LINE_WIDTH)) + "]";
        } catch (Throwable error) {
            return "gl[unavailable=" + error.getClass().getSimpleName() + "]";
        }
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) return "-";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean expiredLocked() {
        return System.currentTimeMillis() - startedMs >= MAX_DURATION_MS;
    }

    private static void addLocked(String line) {
        if (records.size() < MAX_RECORDS) records.add(line);
    }

    private static void stopLocked(String reason) {
        addLocked("capture.stop reason=" + reason + " elapsedMs=" + (System.currentTimeMillis() - startedMs));
        active = false;
        if (output == null) return;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output, false), StandardCharsets.UTF_8))) {
            for (String line : records) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException error) {
            System.err.println("[gomod] Barrier visibility debug save failed: " + error.getClass().getSimpleName());
        }
    }
}
