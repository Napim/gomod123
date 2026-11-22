package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.FeatureProfiler;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.GomodPerformanceWatch;
import me.napim.gomod.features.GomodLagHud;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics for TAB compose, coarse live FPS/world stats, and kill/death tracking.
 * {@code /gomodlag render} samples major client render paths (mixins + Forge world-last). {@code /gomodlag live} is only a coarse hint.
 */
public class GomodLagCommand extends Command {

    private static volatile int tabComposeSamplesRemaining;
    private static volatile long tabComposeSumNs;
    private static volatile int tabComposeCount;
    private static volatile long tabComposeMaxNs;

    public GomodLagCommand() {
        super("gomodlag");
    }

    public static boolean isTabComposeProfiling() {
        return tabComposeSamplesRemaining > 0;
    }

    public static void onTabComposeSample(long durationNs) {
        if (tabComposeSamplesRemaining <= 0) {
            return;
        }
        tabComposeSamplesRemaining--;
        tabComposeSumNs += durationNs;
        tabComposeCount++;
        if (durationNs > tabComposeMaxNs) {
            tabComposeMaxNs = durationNs;
        }
        if (tabComposeSamplesRemaining == 0) {
            double avgMs = tabComposeCount > 0 ? (tabComposeSumNs / 1_000_000.0) / tabComposeCount : 0.0;
            double maxMs = tabComposeMaxNs / 1_000_000.0;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB compose profile done: "
                    + EnumChatFormatting.WHITE + tabComposeCount + EnumChatFormatting.GRAY + " runs, avg "
                    + EnumChatFormatting.YELLOW + String.format("%.2f", avgMs) + " ms"
                    + EnumChatFormatting.GRAY + ", max "
                    + EnumChatFormatting.YELLOW + String.format("%.2f", maxMs) + " ms"
                    + EnumChatFormatting.GRAY + " (KillCounter.updateAllTabNames → composeAllPlayerTabDisplayNames)");
        }
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            sendHelp();
            return;
        }
        String a0 = args[0].toLowerCase();
        if ("tab".equals(a0)) {
            KillCounter.dumpTabListDebugToChat();
            return;
        }
        if ("compose".equals(a0)) {
            int n = 40;
            if (args.length >= 2) {
                try {
                    n = Math.max(5, Math.min(200, Integer.parseInt(args[1])));
                } catch (NumberFormatException ignored) {
                }
            }
            tabComposeSamplesRemaining = n;
            tabComposeSumNs = 0L;
            tabComposeCount = 0;
            tabComposeMaxNs = 0L;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Profiling next "
                    + EnumChatFormatting.WHITE + n + EnumChatFormatting.GRAY
                    + " TAB compose runs — play normally; summary prints when done.");
            return;
        }
        if ("live".equals(a0) || "watch".equals(a0)) {
            boolean next;
            if (args.length >= 2) {
                String a1 = args[1].toLowerCase();
                if ("on".equals(a1) || "true".equals(a1) || "1".equals(a1)) {
                    next = true;
                } else if ("off".equals(a1) || "false".equals(a1) || "0".equals(a1)) {
                    next = false;
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Use on or off (or no arg to toggle).");
                    return;
                }
            } else {
                next = !GomodPerformanceWatch.isEnabled();
            }
            GomodPerformanceWatch.setEnabled(next);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Live perf chat "
                    + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + " (~every 2s: FPS, entities, a few gomod toggles).");
            if (next) {
                GomodPerformanceWatch.sendSnapshotChat();
            }
            return;
        }
        if ("snap".equals(a0) || "snapshot".equals(a0)) {
            GomodPerformanceWatch.sendSnapshotChat();
            return;
        }
        if ("hud".equals(a0)) {
            handleHud(args);
            return;
        }
        if ("perf".equals(a0)) {
            handlePerf(args);
            return;
        }
        if ("render".equals(a0)) {
            handleRender(args);
            return;
        }
        if ("tick".equals(a0)) {
            handleTick(args);
            return;
        }
        if ("help".equals(a0)) {
            sendHelp();
            return;
        }
        sendHelp();
    }

    private void handlePerf(String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "report";
        switch (sub) {
            case "start":
                FeatureProfiler.startRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Tick profiler started. "
                        + EnumChatFormatting.GRAY + "Play normally, then: "
                        + EnumChatFormatting.YELLOW + "/gomodlag perf report");
                break;
            case "stop":
                FeatureProfiler.stopRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tick profiler stopped.");
                break;
            case "reset":
                FeatureProfiler.resetAll();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tick profiler counters reset.");
                break;
            case "dump":
            case "file":
            case "save":
                writePerfReportToFile();
                break;
            case "report":
            default:
                sendPerfReport();
                break;
        }
    }

    private void handleRender(String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "report";
        switch (sub) {
            case "start":
                RenderProfiler.startRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Render profiler started. "
                        + EnumChatFormatting.GRAY + "Play normally (look at crowds), then "
                        + EnumChatFormatting.YELLOW + "/gomodlag render dump"
                        + EnumChatFormatting.GRAY + " or "
                        + EnumChatFormatting.YELLOW + "/gomodlag render report");
                break;
            case "gpu":
            case "gpustart":
                RenderProfiler.startGpuSynchronizedRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "GPU-synchronized profiler started. "
                        + EnumChatFormatting.GRAY + "Lower FPS during this test is intentional. Look at the crowd "
                        + "for 5-10 seconds, then run "
                        + EnumChatFormatting.YELLOW + "/gomodlag render stop"
                        + EnumChatFormatting.GRAY + " and "
                        + EnumChatFormatting.YELLOW + "/gomodlag render dump");
                break;
            case "baseline":
            case "vanilla":
                RenderProfiler.startPlayerBaselineRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player-render baseline started. "
                        + EnumChatFormatting.GRAY + "GPU synchronization and temporary gomod player-hook bypass are active. "
                        + "Look at the crowd for 5-10 seconds, then run "
                        + EnumChatFormatting.YELLOW + "/gomodlag render stop"
                        + EnumChatFormatting.GRAY + " and "
                        + EnumChatFormatting.YELLOW + "/gomodlag render dump");
                break;
            case "ab":
            case "compare":
                RenderProfiler.startPlayerHooksAlternatingRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Same-scene player-render A/B started. "
                        + EnumChatFormatting.GRAY + "Hooks alternate automatically; keep looking at the same crowd "
                        + "for 10-15 seconds, then run "
                        + EnumChatFormatting.YELLOW + "/gomodlag render stop"
                        + EnumChatFormatting.GRAY + " and "
                        + EnumChatFormatting.YELLOW + "/gomodlag render dump");
                break;
            case "deep":
                RenderProfiler.startPlayerHooksDeepRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Deep player-render A/B started. "
                        + EnumChatFormatting.GRAY + "This adds boundary timings; keep looking at the same crowd "
                        + "for 10-15 seconds, then run "
                        + EnumChatFormatting.YELLOW + "/gomodlag render stop"
                        + EnumChatFormatting.GRAY + " and "
                        + EnumChatFormatting.YELLOW + "/gomodlag render dump");
                break;
            case "stop":
                RenderProfiler.stopRecording();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Render profiler stopped.");
                break;
            case "reset":
                RenderProfiler.resetAll();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Render profiler counters reset.");
                break;
            case "dump":
            case "file":
            case "save":
                writeRenderReportToFile();
                break;
            case "report":
            default:
                sendRenderReport();
                break;
        }
    }

    private void sendRenderReport() {
        List<RenderProfiler.ProfileEntry> entries = RenderProfiler.buildReport();
        long frames = RenderProfiler.getRenderEntitiesFrames();
        long secs = RenderProfiler.getElapsedSeconds();
        if (entries.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "No render samples yet. Run: " + EnumChatFormatting.YELLOW + "/gomodlag render start"
                    + EnumChatFormatting.RED + " then look around in-game.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "=== Render Profiler ==="
                + EnumChatFormatting.GRAY + " renderEntities_frames≈" + frames + " elapsed=" + secs + "s"
                + (RenderProfiler.isRecording() ? EnumChatFormatting.GREEN + " [LIVE]" : EnumChatFormatting.GRAY + " [stopped]"));
        for (RenderProfiler.ProfileEntry e : entries) {
            String avgColor = e.avgUs > 500 ? EnumChatFormatting.RED.toString()
                    : e.avgUs > 100 ? EnumChatFormatting.YELLOW.toString()
                    : EnumChatFormatting.GREEN.toString();
            Main.sendMessage(EnumChatFormatting.GRAY + "  " + e.name + ": "
                    + avgColor + String.format("%.1f", e.avgUs) + "µs avg "
                    + EnumChatFormatting.DARK_GRAY + "("
                    + String.format("%.1f", e.lastUs) + "µs last, "
                    + String.format("%.0f", e.totalMs) + "ms total, "
                    + e.samples + " samples)");
        }
    }

    private void writeRenderReportToFile() {
        List<RenderProfiler.ProfileEntry> entries = RenderProfiler.buildReport();
        long frames = RenderProfiler.getRenderEntitiesFrames();
        long secs = RenderProfiler.getElapsedSeconds();
        if (entries.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "No render samples yet. Run: " + EnumChatFormatting.YELLOW + "/gomodlag render start"
                    + EnumChatFormatting.RED + " then look around in-game.");
            return;
        }

        File base = GomodPaths.getGomod123Dir();
        if (!base.exists() && !base.mkdirs()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create folder: "
                    + EnumChatFormatting.WHITE + base.getAbsolutePath());
            return;
        }
        File dir = new File(base, "debugall");
        if (!dir.exists() && !dir.mkdirs()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create report folder: "
                    + EnumChatFormatting.WHITE + dir.getAbsolutePath());
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File out = new File(dir, "gomodlag_render_" + stamp + ".txt");
        File chart = new File(dir, "gomodlag_render_" + stamp + ".svg");

        StringBuilder sb = new StringBuilder(4096);
        sb.append("gomod123 render profiler report").append("\r\n");
        sb.append("generated_at=").append(stamp).append("\r\n");
        sb.append("render_entities_frames_counter=").append(frames).append("\r\n");
        sb.append("elapsed_seconds=").append(secs).append("\r\n");
        sb.append("recording=").append(RenderProfiler.isRecording()).append("\r\n");
        sb.append("gpu_synchronized=").append(RenderProfiler.isGpuSynchronizedMode()).append("\r\n");
        sb.append("player_hooks_bypassed=").append(RenderProfiler.isPlayerHooksBypassedMode()).append("\r\n");
        sb.append("player_hooks_ab=").append(RenderProfiler.isPlayerHooksAlternatingMode()).append("\r\n");
        sb.append("deep_player_hooks=").append(RenderProfiler.isDeepPlayerHookMode()).append("\r\n");
        sb.append("note=nested_sections_are_not_mutually_exclusive (e.g. deferGate includes vanilla sub-timing)\r\n");
        sb.append("\r\n");
        sb.append(String.format("%-48s %12s %12s %12s %10s", "section", "avg_us", "last_us", "total_ms", "samples"))
                .append("\r\n");
        sb.append("----------------------------------------------------------------------------------------------------")
                .append("\r\n");
        for (RenderProfiler.ProfileEntry e : entries) {
            sb.append(String.format(Locale.ROOT, "%-48s %12.1f %12.1f %12.1f %10d",
                    e.name, e.avgUs, e.lastUs, e.totalMs, e.samples)).append("\r\n");
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(out.toPath(), bytes);
            Files.write(chart.toPath(), buildRenderChart(entries, frames, secs).getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved render report: "
                    + EnumChatFormatting.WHITE + out.getAbsolutePath());
            ChatComponentText chartMessage = new ChatComponentText(
                    Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved render chart: "
                            + EnumChatFormatting.WHITE);
            ChatComponentText chartLink = new ChatComponentText(chart.getAbsolutePath());
            chartLink.getChatStyle().setColor(EnumChatFormatting.WHITE);
            chartLink.getChatStyle().setUnderlined(true);
            chartLink.getChatStyle().setChatClickEvent(
                    new ClickEvent(ClickEvent.Action.OPEN_FILE, chart.getAbsolutePath()));
            chartLink.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText(EnumChatFormatting.YELLOW + "Click to open the SVG chart")));
            chartMessage.appendSibling(chartLink);
            Main.sendChatMessage(chartMessage);
        } catch (IOException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to write render report: "
                    + EnumChatFormatting.WHITE + e.getMessage());
        }
    }

    private void handleHud(String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        switch (sub) {
            case "on":
                GomodLagHud.setEnabled(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Lag HUD on. "
                        + EnumChatFormatting.GRAY + "Use /gomodlag hud capture to catch the next >100 ms spike.");
                break;
            case "off":
                GomodLagHud.setEnabled(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Lag HUD off.");
                break;
            case "capture":
                GomodLagHud.armCapture();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Lag HUD armed. The next >100 ms frame gap will freeze after 2 more seconds.");
                break;
            case "freeze":
                GomodLagHud.toggleFreeze();
                Main.sendMessage(Main.CHAT_PREFIX + (GomodLagHud.isFrozen()
                        ? EnumChatFormatting.YELLOW + "Lag HUD frozen."
                        : EnumChatFormatting.GREEN + "Lag HUD resumed."));
                break;
            case "reset":
                GomodLagHud.resetView();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Lag HUD history cleared.");
                break;
            case "toggle":
                GomodLagHud.setEnabled(!GomodLagHud.isEnabled());
                Main.sendMessage(Main.CHAT_PREFIX + (GomodLagHud.isEnabled()
                        ? EnumChatFormatting.GREEN + "Lag HUD on."
                        : EnumChatFormatting.YELLOW + "Lag HUD off."));
                break;
            default:
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Usage: /gomodlag hud [on|off|capture|freeze|reset]");
                break;
        }
    }

    private static String buildRenderChart(List<RenderProfiler.ProfileEntry> entries, long frames, long secs) {
        int count = Math.min(18, entries.size());
        int width = 1200;
        int rowHeight = 34;
        int top = 105;
        int height = top + count * rowHeight + 55;
        int labelX = 28;
        int barX = 470;
        int barMax = 520;
        double max = count == 0 ? 1.0 : Math.max(1.0, entries.get(0).avgUs);
        StringBuilder svg = new StringBuilder(8192);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
                .append(width).append(' ').append(height).append("\">\n")
                .append("<rect width=\"100%\" height=\"100%\" fill=\"#10131a\"/>\n")
                .append("<style>text{font-family:Segoe UI,Arial,sans-serif;fill:#e8edf5}.title{font-size:24px;font-weight:700}.sub{font-size:14px;fill:#9aa7b8}.label{font-size:13px}.value{font-size:13px;font-weight:600}</style>\n")
                .append("<text class=\"title\" x=\"28\" y=\"38\">gomod123 Render Profile</text>\n")
                .append("<text class=\"sub\" x=\"28\" y=\"65\">Average time per sampled call — lower is better</text>\n")
                .append("<text class=\"sub\" x=\"28\" y=\"87\">render frames: ").append(frames)
                .append(" · elapsed: ").append(secs).append("s · 1000µs = 1ms</text>\n");
        for (int i = 0; i < count; i++) {
            RenderProfiler.ProfileEntry e = entries.get(i);
            int y = top + i * rowHeight;
            int barWidth = (int) Math.max(2, Math.round((e.avgUs / max) * barMax));
            String color = e.avgUs >= 500.0 ? "#ff5d6c" : e.avgUs >= 100.0 ? "#f5b84b" : "#50d890";
            svg.append("<text class=\"label\" x=\"").append(labelX).append("\" y=\"").append(y + 19)
                    .append("\">").append(escapeXml(e.name)).append("</text>\n")
                    .append("<rect x=\"").append(barX).append("\" y=\"").append(y + 5)
                    .append("\" width=\"").append(barWidth).append("\" height=\"20\" rx=\"4\" fill=\"")
                    .append(color).append("\"/>\n")
                    .append("<text class=\"value\" x=\"").append(barX + barWidth + 10).append("\" y=\"")
                    .append(y + 20).append("\">").append(String.format(Locale.ROOT, "%.1fµs", e.avgUs))
                    .append(" (n=").append(e.samples).append(")</text>\n");
        }
        return svg.append("</svg>\n").toString();
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void sendPerfReport() {
        List<FeatureProfiler.ProfileEntry> entries = FeatureProfiler.buildReport();
        long ticks = FeatureProfiler.getTotalTicksRecorded();
        long secs = FeatureProfiler.getElapsedSeconds();
        if (entries.isEmpty() || ticks == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "No data yet. Run: " + EnumChatFormatting.YELLOW + "/gomodlag perf start"
                    + EnumChatFormatting.RED + " then play a bit.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "=== Tick Profiler ==="
                + EnumChatFormatting.GRAY + " ticks=" + ticks + " elapsed=" + secs + "s"
                + (FeatureProfiler.isRecording() ? EnumChatFormatting.GREEN + " [LIVE]" : EnumChatFormatting.GRAY + " [stopped]"));
        for (FeatureProfiler.ProfileEntry e : entries) {
            String avgColor = e.avgUs > 500 ? EnumChatFormatting.RED.toString()
                    : e.avgUs > 100 ? EnumChatFormatting.YELLOW.toString()
                    : EnumChatFormatting.GREEN.toString();
            Main.sendMessage(EnumChatFormatting.GRAY + "  " + e.name + ": "
                    + avgColor + String.format("%.1f", e.avgUs) + "µs avg "
                    + EnumChatFormatting.DARK_GRAY + "("
                    + String.format("%.1f", e.lastUs) + "µs last, "
                    + String.format("%.0f", e.totalMs) + "ms total, "
                    + e.samples + " samples)");
        }
    }

    private void writePerfReportToFile() {
        List<FeatureProfiler.ProfileEntry> entries = FeatureProfiler.buildReport();
        long ticks = FeatureProfiler.getTotalTicksRecorded();
        long secs = FeatureProfiler.getElapsedSeconds();
        if (entries.isEmpty() || ticks == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "No data yet. Run: " + EnumChatFormatting.YELLOW + "/gomodlag perf start"
                    + EnumChatFormatting.RED + " then play a bit.");
            return;
        }

        File base = GomodPaths.getGomod123Dir();
        if (!base.exists() && !base.mkdirs()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create folder: "
                    + EnumChatFormatting.WHITE + base.getAbsolutePath());
            return;
        }
        File dir = new File(base, "gomodlag_reports");
        if (!dir.exists() && !dir.mkdirs()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create report folder: "
                    + EnumChatFormatting.WHITE + dir.getAbsolutePath());
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File out = new File(dir, "gomodlag_perf_" + stamp + ".txt");
        File latest = new File(base, "gomodlag_perf_latest.txt");

        StringBuilder sb = new StringBuilder(4096);
        sb.append("gomod123 tick profiler report").append("\r\n");
        sb.append("generated_at=").append(stamp).append("\r\n");
        sb.append("ticks=").append(ticks).append("\r\n");
        sb.append("elapsed_seconds=").append(secs).append("\r\n");
        sb.append("recording=").append(FeatureProfiler.isRecording()).append("\r\n");
        sb.append("\r\n");
        sb.append(String.format("%-38s %12s %12s %12s %10s", "feature", "avg_us", "last_us", "total_ms", "samples"))
                .append("\r\n");
        sb.append("----------------------------------------------------------------------------------------------------")
                .append("\r\n");
        for (FeatureProfiler.ProfileEntry e : entries) {
            sb.append(String.format(Locale.ROOT, "%-38s %12.1f %12.1f %12.1f %10d",
                    e.name, e.avgUs, e.lastUs, e.totalMs, e.samples)).append("\r\n");
        }

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(out.toPath(), bytes);
            Files.write(latest.toPath(), bytes);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved perf report: "
                    + EnumChatFormatting.WHITE + out.getAbsolutePath());
            Main.sendMessage(EnumChatFormatting.GRAY + "Latest mirror: "
                    + EnumChatFormatting.WHITE + latest.getAbsolutePath());
        } catch (IOException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to write perf report: "
                    + EnumChatFormatting.WHITE + e.getMessage());
        }
    }

    private void handleTick(String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";
        switch (sub) {
            case "off":
            case "disable":
                if (args.length >= 3) {
                    // disable specific feature
                    String feat = args[2];
                    ClientTickHub.disableFeature(feat);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Disabled tick feature: "
                            + EnumChatFormatting.WHITE + feat);
                } else {
                    // disable ALL
                    ClientTickHub.setAllTickFeaturesDisabled(true);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "ALL tick features disabled."
                            + EnumChatFormatting.GRAY + " Re-enable one by one: "
                            + EnumChatFormatting.YELLOW + "/gomodlag tick enable <name>"
                            + EnumChatFormatting.GRAY + " or re-enable all: "
                            + EnumChatFormatting.YELLOW + "/gomodlag tick on");
                }
                break;
            case "on":
            case "enable":
                if (args.length >= 3) {
                    // enable specific feature
                    String feat = args[2];
                    ClientTickHub.enableFeature(feat);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Enabled tick feature: "
                            + EnumChatFormatting.WHITE + feat);
                } else {
                    // enable ALL
                    ClientTickHub.setAllTickFeaturesDisabled(false);
                    ClientTickHub.getDisabledFeatures().clear();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "All tick features re-enabled.");
                }
                break;
            case "list":
            default:
                boolean masterOff = ClientTickHub.isAllTickFeaturesDisabled();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tick features "
                        + (masterOff ? EnumChatFormatting.RED + "[ALL DISABLED]" : EnumChatFormatting.GREEN + "[master on]") + ":");
                for (String name : ClientTickHub.TICK_FEATURE_NAMES) {
                    boolean on = ClientTickHub.isFeatureEnabled(name);
                    Main.sendMessage(EnumChatFormatting.GRAY + "  " + name + ": "
                            + (on ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "OFF"));
                }
                break;
        }
    }

    private void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/tablagdebug start [seconds] | stop"
                + EnumChatFormatting.WHITE + " — TAB open/closed frame times, font/layout timings, sampled client stacks; saves a log");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/gomodlag tab"
                + EnumChatFormatting.WHITE + " — kill counter / dead tracking + sample TAB rows");
        Main.sendMessage(EnumChatFormatting.GRAY + "/gomodlag compose [5-200]"
                + EnumChatFormatting.WHITE + " — time KillCounter full TAB recompose (avg/max ms)");
        Main.sendMessage(EnumChatFormatting.GRAY + "/gomodlag live [on|off]"
                + EnumChatFormatting.WHITE + " — periodic FPS / entity / chunk hint (toggle if no arg)");
        Main.sendMessage(EnumChatFormatting.GRAY + "/gomodlag snap"
                + EnumChatFormatting.WHITE + " — one-shot FPS snapshot");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag hud [on|off|capture|freeze|reset]"
                + EnumChatFormatting.WHITE + " — on-screen frame graph and recent render/tick hotspots");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag perf start|stop|report|reset"
                + EnumChatFormatting.WHITE + " — per-feature tick profiler (avg µs, worst first)");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag perf dump"
                + EnumChatFormatting.WHITE + " — save tick report to <gameDir>/gomod123/gomodlag_reports/");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag render start|gpu|baseline|ab|deep|stop|report|reset"
                + EnumChatFormatting.WHITE + " — render-thread sections (entity pass, HUD, world-last)");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag render dump"
                + EnumChatFormatting.WHITE + " — save render report next to perf dumps");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag tick list"
                + EnumChatFormatting.WHITE + " — list tick features on/off");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag tick off"
                + EnumChatFormatting.WHITE + " — disable ALL tick features");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag tick on"
                + EnumChatFormatting.WHITE + " — re-enable ALL tick features");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/gomodlag tick enable|disable <name>"
                + EnumChatFormatting.WHITE + " — toggle individual tick feature");
    }
}
