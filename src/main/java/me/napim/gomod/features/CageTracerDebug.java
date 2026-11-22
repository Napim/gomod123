package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Opt-in, bounded CPU/frame diagnostics. No GL synchronisation or file writes during sampling. */
public final class CageTracerDebug {
    private static final long LIMIT_NS = 60_000_000_000L;
    private static volatile Capture capture;
    private static volatile String lastReport = "none";

    private CageTracerDebug() {}

    public static String start() {
        if (capture != null) return "Cage Tracer debug is already recording. " + status();
        capture = new Capture(environment());
        return "Cage Tracer debug recording for 60s. Reproduce the lag; toggle Cage tracers OFF and ON for comparison."
                + " Use /cagetracerdebug stop to save sooner.";
    }

    public static String stop() {
        Capture current = capture;
        if (current == null) return "Cage Tracer debug is not recording. Last report: " + lastReport;
        capture = null;
        save(current, "manual");
        return "Cage Tracer debug stopped; saving the report.";
    }

    public static String status() {
        Capture current = capture;
        if (current == null) return "Cage Tracer debug is off. Last report: " + lastReport;
        return String.format(Locale.ROOT, "Recording %.1fs / 60s; %d active and %d inactive frames; %.3fms average tracer CPU.",
                (System.nanoTime() - current.started) / 1.0E9, current.active.frames, current.inactive.frames,
                (current.active.total + current.inactive.total) / (double) Math.max(1, current.frames) / 1.0E6);
    }

    static Frame beginFrame() {
        Capture current = capture;
        if (current == null) return null;
        Frame frame = current.frame;
        frame.reset();
        frame.started = System.nanoTime();
        return frame;
    }

    static void endFrame(Frame frame) {
        Capture current = capture;
        if (frame == null || current == null || current.frame != frame) return;
        long now = System.nanoTime();
        current.record(frame, now);
        if (now - current.started >= LIMIT_NS) {
            capture = null;
            save(current, "60-second limit");
        }
    }

    static void clientTick() {
        Capture current = capture;
        if (current != null && System.nanoTime() - current.started >= LIMIT_NS) {
            capture = null;
            save(current, "60-second limit (client tick)");
        }
    }

    private static String environment() {
        Minecraft mc = Minecraft.getMinecraft();
        String gpu;
        try {
            gpu = GL11.glGetString(GL11.GL_VENDOR) + " / " + GL11.glGetString(GL11.GL_RENDERER)
                    + " / " + GL11.glGetString(GL11.GL_VERSION);
        } catch (Throwable ignored) {
            gpu = "unavailable";
        }
        return "java=" + System.getProperty("java.version") + " os=" + System.getProperty("os.name")
                + " processors=" + Runtime.getRuntime().availableProcessors() + "\ngpu=" + gpu
                + (mc == null ? "" : "\ndisplay=" + mc.displayWidth + "x" + mc.displayHeight
                + " fps=" + Minecraft.getDebugFPS() + " renderDistance=" + mc.gameSettings.renderDistanceChunks)
                + "\nmanual=" + PlayerTracers.isEnabled() + " cagesAuto=" + PlayerTracers.isCagesAutoEnabled()
                + " range=" + PlayerTracers.getMaxRange() + " overlay=" + PlayerTracers.getOverlayStyle()
                + "/" + PlayerTracers.getOverlayDepthMode() + "/" + PlayerTracers.getOverlayStrength()
                + " labels=" + ConfigManager.isPtracersTargetNametagEnabled();
    }

    private static void save(Capture completed, String reason) {
        final String report = completed.report(reason);
        Thread writer = new Thread(() -> {
            try {
                File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
                Files.createDirectories(directory.toPath());
                File file = Files.createTempFile(directory.toPath(), "cage_tracer_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()) + "_", ".log").toFile();
                Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
                lastReport = file.getAbsolutePath();
                announce(EnumChatFormatting.GREEN + "Cage Tracer debug saved: " + EnumChatFormatting.WHITE + lastReport);
            } catch (Exception error) {
                announce(EnumChatFormatting.RED + "Could not save Cage Tracer debug: " + error.getMessage());
            }
        }, "gomod-cage-tracer-debug-save");
        writer.setDaemon(true);
        writer.start();
    }

    private static void announce(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX + text));
    }

    static final class Frame {
        long started, gateNs, rayNs, geometryNs, labelNs;
        int loadedPlayers, targets, rayCasts, cacheHits, worldRays, transparentSkips, rayLimits;
        int draws, vertices, labels, gateCacheHits;
        boolean active, cages;
        void reset() {
            gateNs = rayNs = geometryNs = labelNs = 0;
            loadedPlayers = targets = rayCasts = cacheHits = worldRays = transparentSkips = rayLimits = 0;
            draws = vertices = labels = gateCacheHits = 0;
            active = cages = false;
        }
    }

    private static class Totals {
        long frames, total, max, gate, rays, geometry, labelsNs;
        long targets, loaded, casts, hits, worldRays, skips, limits, draws, vertices, labels, gateHits;
        void add(Frame frame, long elapsed) {
            frames++; total += elapsed; max = Math.max(max, elapsed);
            gate += frame.gateNs; rays += frame.rayNs; geometry += frame.geometryNs; labelsNs += frame.labelNs;
            loaded += frame.loadedPlayers; targets += frame.targets; casts += frame.rayCasts; hits += frame.cacheHits;
            worldRays += frame.worldRays; skips += frame.transparentSkips; limits += frame.rayLimits;
            draws += frame.draws; vertices += frame.vertices; labels += frame.labels; gateHits += frame.gateCacheHits;
        }
        double ms(long ns) { return ns / (double) Math.max(1, frames) / 1.0E6; }
        String summary(String name) {
            return String.format(Locale.ROOT,
                    "%s frames=%d mean_cpu_ms=%.4f max_cpu_ms=%.4f gate_ms=%.4f ray_ms=%.4f geometry_ms=%.4f labels_ms=%.4f\n"
                    + "  avg_loaded=%.1f avg_targets=%.1f ray_casts=%d cache_hits=%d world_ray_calls=%d transparent_skips=%d ray_limit_hits=%d gate_cache_hits=%d\n"
                    + "  avg_geometry_draws=%.2f avg_vertices=%.1f avg_labels=%.1f\n",
                    name, frames, ms(total), max / 1.0E6, ms(gate), ms(rays), ms(geometry), ms(labelsNs),
                    loaded / (double) Math.max(1, frames), targets / (double) Math.max(1, frames),
                    casts, hits, worldRays, skips, limits, gateHits,
                    draws / (double) Math.max(1, frames), vertices / (double) Math.max(1, frames),
                    labels / (double) Math.max(1, frames));
        }
    }

    private static final class Bucket extends Totals {
        int activeFrames, cagesFrames, fps, namesFrames, styleBits;
    }

    private static final class Capture {
        final long started = System.nanoTime();
        final String environment;
        final Frame frame = new Frame();
        final Totals active = new Totals(), inactive = new Totals();
        final Bucket[] seconds = new Bucket[60];
        final long[] frameIntervals = new long[12000], cpuSamples = new long[12000];
        int frames, intervalSamples, cpuCount;
        long previousFrame, longestInterval;
        Capture(String environment) {
            this.environment = environment;
            for (int i = 0; i < seconds.length; i++) seconds[i] = new Bucket();
        }
        void record(Frame frame, long now) {
            long elapsed = now - frame.started;
            if (previousFrame != 0) {
                long interval = frame.started - previousFrame;
                frameIntervals[intervalSamples++ % frameIntervals.length] = interval;
                longestInterval = Math.max(longestInterval, interval);
            }
            previousFrame = frame.started;
            cpuSamples[cpuCount++ % cpuSamples.length] = elapsed;
            frames++;
            (frame.active ? active : inactive).add(frame, elapsed);
            Bucket bucket = seconds[(int) Math.min(seconds.length - 1, (now - started) / 1_000_000_000L)];
            bucket.add(frame, elapsed);
            if (frame.active) bucket.activeFrames++;
            if (frame.cages) bucket.cagesFrames++;
            if (ConfigManager.isPtracersTargetNametagEnabled()) bucket.namesFrames++;
            bucket.styleBits |= 1 << PlayerTracers.getOverlayStyle().ordinal();
            bucket.fps = Minecraft.getDebugFPS();
        }
        String report(String reason) {
            StringBuilder output = new StringBuilder(16000);
            output.append("gomod Cage Tracer performance capture\nstop=").append(reason).append('\n')
                    .append(environment).append('\n')
                    .append("Timings measure CPU ray work and OpenGL submission, not GPU completion. No glFinish is used.\n")
                    .append("Frame intervals include the rest of the game; compare active/inactive seconds in the same scene.\n")
                    .append("Geometry draw counts include label backgrounds but exclude the native font renderer.\n")
                    .append("Percentiles use at most the latest 12000 samples.\n")
                    .append(String.format(Locale.ROOT,
                            "elapsed_s=%.2f frames=%d frame_p50_ms=%.3f frame_p95_ms=%.3f frame_max_ms=%.3f cpu_p95_ms=%.4f\n",
                            (System.nanoTime() - started) / 1.0E9, frames,
                            percentile(frameIntervals, intervalSamples, 0.5) / 1.0E6,
                            percentile(frameIntervals, intervalSamples, 0.95) / 1.0E6, longestInterval / 1.0E6,
                            percentile(cpuSamples, cpuCount, 0.95) / 1.0E6))
                    .append(active.summary("active")).append(inactive.summary("inactive"))
                    .append("\nPer-second samples; stage times are average milliseconds per frame.\n")
                    .append("sec frames active cages fps cpu gate rays geometry labels maxCpu targets casts cacheHits worldRays glassSkips rayCaps draws vertices nameCount namesOn styleBits\n");
            for (int i = 0; i < seconds.length; i++) {
                Bucket s = seconds[i];
                if (s.frames == 0) continue;
                output.append(String.format(Locale.ROOT,
                        "%d %d %d %d %d %.4f %.4f %.4f %.4f %.4f %.4f %.2f %d %d %d %d %d %.2f %.1f %.2f %d %d\n",
                        i, s.frames, s.activeFrames, s.cagesFrames, s.fps, s.ms(s.total), s.ms(s.gate),
                        s.ms(s.rays), s.ms(s.geometry), s.ms(s.labelsNs), s.max / 1.0E6,
                        s.targets / (double) s.frames, s.casts, s.hits, s.worldRays, s.skips, s.limits,
                        s.draws / (double) s.frames, s.vertices / (double) s.frames, s.labels / (double) s.frames,
                        s.namesFrames, s.styleBits));
            }
            return output.toString();
        }
    }

    private static long percentile(long[] samples, int count, double fraction) {
        int size = Math.min(count, samples.length);
        if (size == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        return sorted[Math.min(size - 1, (int) Math.ceil(size * fraction) - 1)];
    }
}
