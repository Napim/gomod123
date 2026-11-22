package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Opt-in bounded trace of GoMod's Hypixel HTTP attempts and dev-build lookup decisions. */
public final class HypixelApiRequestDebug {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 12_000;
    private static final int MAX_TRACKED_TARGETS = 2048;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final Pattern ENDPOINT = Pattern.compile("/[a-zA-Z0-9/]{1,96}");
    private static final Pattern UUID = Pattern.compile(
            "[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_]{1,16}");
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<String>();
    private static final Map<String, Request> LAST_ATTEMPT = new LinkedHashMap<String, Request>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Request> eldest) {
            return size() > MAX_TRACKED_TARGETS;
        }
    };

    private static volatile long activeCapture;
    private static long captureSequence;
    private static long droppedEvents;
    private static long requestCount;
    private static long captureStartedAt;

    private HypixelApiRequestDebug() {
    }

    public static void start() {
        synchronized (LOCK) {
            EVENTS.clear();
            LAST_ATTEMPT.clear();
            droppedEvents = 0L;
            requestCount = 0L;
            captureStartedAt = System.currentTimeMillis();
            activeCapture = ++captureSequence;
        }
    }

    public static boolean isEnabled() {
        return activeCapture != 0L;
    }

    public static boolean isDetailedCaptureEnabled() {
        return !GomodReleaseBuild.PUBLIC_RELEASE && isEnabled();
    }

    public static String getStatus() {
        synchronized (LOCK) {
            return (isEnabled() ? "ON" : "OFF") + ", " + requestCount + " HTTP attempts, "
                    + EVENTS.size() + " trace events, " + droppedEvents + " dropped";
        }
    }

    /**
     * Begins tracing one real HTTP attempt. Returns {@code null} when capture is off or the request is not
     * addressed to Hypixel's public API host. Only the endpoint and validated lookup target are retained.
     */
    public static Request begin(String method, String urlString) {
        long capture = activeCapture;
        if (capture == 0L) {
            return null;
        }

        SanitizedRequest sanitized = sanitizeRequest(urlString);
        if (sanitized == null) {
            return null;
        }

        String requester = getRequester();
        String caller = getCaller();
        String thread = getThread();
        synchronized (LOCK) {
            if (activeCapture != capture) {
                return null;
            }
            Request request = new Request(capture, ++requestCount, System.currentTimeMillis());
            String repeat = "";
            if (!GomodReleaseBuild.PUBLIC_RELEASE) {
                // Hypixel's legacy and v2 routes can serve the same lookup.
                String path = sanitized.path;
                String endpoint = path.startsWith("/v2/") ? path.substring(3) : path;
                Request previous = LAST_ATTEMPT.put(method + " " + endpoint + " " + sanitized.target, request);
                if (previous != null) {
                    repeat = " previousAttempt=#" + previous.id
                            + " sincePreviousMs=" + Math.max(0L, request.startedAt - previous.startedAt);
                }
            }
            addEventLocked(formatTime(request.startedAt) + " START #" + request.id + " " + clean(method)
                    + " api.hypixel.net" + sanitized.path + " target=" + sanitized.target + repeat
                    + " thread=" + thread + " requester=" + requester + " caller=" + caller);
            return request;
        }
    }

    /** Records the response or failure for a previously captured attempt. */
    public static void finish(Request request, int statusCode, String retryAfter,
                              HttpURLConnection connection, Throwable failure) {
        if (request == null || activeCapture != request.capture) {
            return;
        }
        long finished = System.currentTimeMillis();
        long elapsed = Math.max(0L, finished - request.startedAt);
        String outcome = failure == null ? "response" : "error=" + failure.getClass().getName();
        StringBuilder headers = new StringBuilder();
        appendRetryAfter(headers, retryAfter);
        if (!GomodReleaseBuild.PUBLIC_RELEASE && statusCode >= 100 && connection != null) {
            try {
                appendNumericHeader(headers, "ratelimit-limit", connection.getHeaderField("RateLimit-Limit"));
                appendNumericHeader(headers, "ratelimit-remaining", connection.getHeaderField("RateLimit-Remaining"));
                appendNumericHeader(headers, "ratelimit-reset", connection.getHeaderField("RateLimit-Reset"));
            } catch (RuntimeException ignored) {
                // The HTTP result is still available if the transport cannot expose the optional headers.
            }
        }
        synchronized (LOCK) {
            if (activeCapture == request.capture) {
                addEventLocked(formatTime(finished) + " END   #" + request.id + " status=" + statusCode
                        + " elapsedMs=" + elapsed + " " + outcome + headers);
            }
        }
    }

    /** Records internal decisions using only code-generated fields and validated identifiers. */
    public static void record(String event, String fields) {
        if (isDetailedCaptureEnabled()) {
            record(activeCapture, event, fields);
        }
    }

    /** Adds a sanitized endpoint and target to a decision about a particular URL. */
    public static void recordForUrl(String event, String url, String fields) {
        if (!isDetailedCaptureEnabled()) return;
        long capture = activeCapture;
        SanitizedRequest request = sanitizeRequest(url);
        if (request != null) {
            record(capture, event, "endpoint=" + request.path + " target=" + request.target + " " + fields);
        }
    }

    private static void record(long capture, String event, String fields) {
        if (capture == 0L) return;
        String context = " thread=" + getThread() + " caller=" + getCaller();
        synchronized (LOCK) {
            if (activeCapture == capture) {
                addEventLocked(formatTime(System.currentTimeMillis()) + " " + event + " " + fields + context);
            }
        }
    }

    /** Stops capture and writes the bounded trace to gomod123/debug. */
    public static File stopAndSave() throws Exception {
        return writeSnapshot(snapshot(true));
    }

    /** Writes the current bounded trace without changing capture state. */
    public static File saveSnapshot() throws Exception {
        return writeSnapshot(snapshot(false));
    }

    private static List<String> snapshot(boolean stop) {
        final List<String> lines = new ArrayList<String>();
        synchronized (LOCK) {
            if (stop) activeCapture = 0L;
            lines.add("Gomod Hypixel API request trace");
            lines.add("Capture started: " + (captureStartedAt == 0L ? "<not started>" : formatTime(captureStartedAt)));
            lines.add("Capture written: " + formatTime(System.currentTimeMillis()));
            lines.add("HTTP attempts captured: " + requestCount);
            lines.add("Trace events dropped because the bounded buffer filled: " + droppedEvents);
            lines.add("HTTP START and END lines share an attempt ID. An attempt can still be in flight when the report is saved.");
            lines.add("The trace includes lookup identifiers and selected rate-limit headers. It omits credentials and response bodies.");
            lines.add("");
            lines.addAll(EVENTS);
        }
        return lines;
    }

    private static File writeSnapshot(List<String> lines) throws Exception {
        File debugDir = new File(GomodPaths.getGomod123Dir(), "debug");
        if (!debugDir.isDirectory() && !debugDir.mkdirs() && !debugDir.isDirectory()) {
            throw new IllegalStateException("Could not create debug folder");
        }
        File output = new File(debugDir, "hypixel-api-requests-" + FILE_TIME.format(Instant.now()) + ".log");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(output), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
        return output.getAbsoluteFile();
    }

    private static void addEventLocked(String event) {
        if (EVENTS.size() >= MAX_EVENTS) {
            EVENTS.removeFirst();
            droppedEvents++;
        }
        EVENTS.addLast(event);
    }

    private static SanitizedRequest sanitizeRequest(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !"api.hypixel.net".equalsIgnoreCase(url.getHost())) {
                return null;
            }
            String path = url.getPath();
            return new SanitizedRequest(ENDPOINT.matcher(path).matches() ? path : "/<unknown>",
                    lookupTarget(url.getQuery()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String lookupTarget(String query) {
        if (query == null || query.isEmpty()) return "<unspecified>";
        String name = null;
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator < 0) continue;
            String field = decode(part.substring(0, separator)).toLowerCase(Locale.ROOT);
            switch (field) {
                case "uuid":
                case "player":
                case "name":
                case "username":
                case "target":
                    String value = decode(part.substring(separator + 1));
                    if (UUID.matcher(value).matches()) return value.replace("-", "").toLowerCase(Locale.ROOT);
                    if (!"uuid".equals(field) && NAME.matcher(value).matches()) {
                        name = value.toLowerCase(Locale.ROOT);
                    }
                    break;
                default:
                    break;
            }
        }
        return name == null ? "<unspecified>" : name;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void appendRetryAfter(StringBuilder fields, String value) {
        if (value == null || appendNumericHeader(fields, "retry-after", value)) return;
        try {
            ZonedDateTime date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            fields.append(" retry-after=").append(date.toInstant());
        } catch (DateTimeParseException ignored) {
        }
    }

    private static boolean appendNumericHeader(StringBuilder fields, String name, String value) {
        if (value == null) return false;
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0L) return false;
            fields.append(' ').append(name).append('=').append(parsed);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String getRequester() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Session session = mc == null ? null : mc.getSession();
            if (session == null) return "<unknown>";
            return clean(session.getUsername()) + "/" + clean(session.getPlayerID());
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String getCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder caller = new StringBuilder();
            int included = 0;
            for (StackTraceElement frame : stack) {
                String className = frame.getClassName();
                if (!className.startsWith("me.napim.gomod.")) continue;
                if (className.equals(HypixelApiRequestDebug.class.getName())
                        || className.equals(HypixelApiHelper.class.getName())
                        || className.equals(HypixelPlayerRequests.class.getName())) continue;
                if (included > 0) caller.append(" <- ");
                caller.append(className).append('#').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
                if (++included >= 4) break;
            }
            return caller.length() == 0 ? "<unknown>" : clean(caller.toString());
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String getThread() {
        Thread thread = Thread.currentThread();
        return clean(thread.getName()) + "/" + thread.getId();
    }

    private static String formatTime(long time) {
        return LOG_TIME.format(Instant.ofEpochMilli(time));
    }

    private static String clean(String value) {
        if (value == null) return "<null>";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return cleaned.length() <= 512 ? cleaned : cleaned.substring(0, 509) + "...";
    }

    public static final class Request {
        private final long capture;
        private final long id;
        private final long startedAt;

        private Request(long capture, long id, long startedAt) {
            this.capture = capture;
            this.id = id;
            this.startedAt = startedAt;
        }
    }

    private static final class SanitizedRequest {
        private final String path;
        private final String target;

        private SanitizedRequest(String path, String target) {
            this.path = path;
            this.target = target;
        }
    }
}
