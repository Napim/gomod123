package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Internal-build-only, payload-free diagnostics for Lunar service connectivity. */
public final class LunarConnectionDiagnostics {
    private static final long SOCKET_ATTEMPT_THROTTLE_MS = 5_000L;
    private static final long FRAME_HEARTBEAT_LOG_MS = 30_000L;
    private static final long STATUS_HEARTBEAT_LOG_MS = 30_000L;

    private static final Map<Object, Long> LAST_ATTEMPT_LOG_MS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> LAST_FRAME_LOG_MS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> FRAME_COUNTS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> LAST_PONG_LOG_MS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> PONG_COUNTS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> LAST_INBOUND_LOG_MS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> INBOUND_BYTE_COUNTS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> LAST_TRANSPORT_READ_LOG_MS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Long> TRANSPORT_READ_BYTE_COUNTS = new WeakHashMap<Object, Long>();
    private static final Map<Object, Map<String, Long>> OUTBOUND_FRAME_STATE =
            new WeakHashMap<Object, Map<String, Long>>();
    private static final Set<String> RECORDED_EVENTS = new HashSet<String>();
    private static final Map<String, PendingDiagnosticEvent> PENDING_PRESTART_EVENTS =
            new LinkedHashMap<String, PendingDiagnosticEvent>();
    private static final String SHARED_TRANSFORM_PREFIX = "gomod.qa.transform.";

    private static volatile boolean enabled;
    private static volatile File logFile;
    private static volatile String lastServiceSnapshot;
    private static volatile String lastLunarStatus;
    private static volatile String lastFullAccessStatus;
    private static volatile long lastFullAccessStatusLogMs;
    private static final AtomicLong DROPPED_LOG_LINES = new AtomicLong();
    private static final ThreadPoolExecutor LOG_WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(512), task -> {
                Thread thread = new Thread(task, "gomod-lunar-diagnostics");
                thread.setDaemon(true);
                return thread;
            }, (task, executor) -> DROPPED_LOG_LINES.incrementAndGet());
    // Accessed only by the diagnostics writer. Property scans never run on a render/socket caller.
    private static long nextSharedTransformScanMs;

    private static final class PendingDiagnosticEvent {
        private final String event;
        private final String details;

        private PendingDiagnosticEvent(String event, String details) {
            this.event = event;
            this.details = details;
        }
    }

    private LunarConnectionDiagnostics() {
    }

    /** Starts a fresh per-launch log only for an internal Weave build. */
    public static synchronized void start(String version, boolean internalQaBuild) {
        enabled = internalQaBuild;
        if (!enabled) {
            PENDING_PRESTART_EVENTS.clear();
            return;
        }
        if (logFile != null) {
            return;
        }

        File directory = new File(GomodPaths.getGomod123Dir(), "lunar-connection-debug");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            enabled = false;
            System.err.println("[gomod][lunar-debug] Could not create " + directory.getAbsolutePath());
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        logFile = new File(directory, "lunar-connection-debug-" + timestamp + ".log");
        writeLine("session.start", "build=internal version=" + safe(version)
                + " java=" + safe(System.getProperty("java.version", "unknown"))
                + " gameDir=" + safe(GomodPaths.getMinecraftGameDir().getAbsolutePath()));
        for (Map.Entry<String, PendingDiagnosticEvent> entry : PENDING_PRESTART_EVENTS.entrySet()) {
            if (RECORDED_EVENTS.add(entry.getKey())) {
                PendingDiagnosticEvent pending = entry.getValue();
                writeLine(pending.event, pending.details);
            }
        }
        PENDING_PRESTART_EVENTS.clear();
        flushSharedTransformEvents();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG_WRITER.shutdown();
            try {
                LOG_WRITER.awaitTermination(1L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "gomod-lunar-diagnostics-shutdown"));
        System.out.println("[gomod][lunar-debug] Writing internal Lunar diagnostics to "
                + logFile.getAbsolutePath());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void record(String event, String details) {
        if (enabled) {
            writeLine(event, details);
        }
    }

    /** Emits a payload-free diagnostic event only once per per-launch key. */
    public static synchronized void recordOnce(String event, String key, String details) {
        if (!enabled || event == null || key == null) {
            return;
        }
        if (RECORDED_EVENTS.add(event + "|" + key)) {
            record(event, details);
        }
    }

    /**
     * Records a one-time diagnostic even when a Mixin callback runs before the QA log starts.
     * The short queue is flushed after session.start and discarded for public builds.
     */
    public static synchronized void recordOnceAfterStart(String event, String key, String details) {
        if (event == null || key == null || me.napim.gomod.GomodReleaseBuild.PUBLIC_RELEASE) {
            return;
        }
        if (enabled) {
            recordOnce(event, key, details);
            return;
        }
        String signature = event + "|" + key;
        if (RECORDED_EVENTS.contains(signature) || PENDING_PRESTART_EVENTS.containsKey(signature)) {
            return;
        }
        if (PENDING_PRESTART_EVENTS.size() >= 32) {
            String oldest = PENDING_PRESTART_EVENTS.keySet().iterator().next();
            PENDING_PRESTART_EVENTS.remove(oldest);
        }
        PENDING_PRESTART_EVENTS.put(signature, new PendingDiagnosticEvent(event, details));
        // Weave's plugin runs in SandboxedMixinLoader, not the game's loader. Its static queue is
        // a separate class instance; process properties carry only these payload-free transform reports.
        System.setProperty(SHARED_TRANSFORM_PREFIX + signature, safe(event) + "\n" + safe(details));
    }

    private static synchronized void flushSharedTransformEvents() {
        if (!enabled || logFile == null) return;
        java.util.Properties properties = System.getProperties();
        for (String property : properties.stringPropertyNames()) {
            if (!property.startsWith(SHARED_TRANSFORM_PREFIX)) continue;
            String value = properties.getProperty(property);
            if (value == null || !properties.remove(property, value)) continue;
            int separator = value.indexOf('\n');
            if (separator < 0) continue;
            if (RECORDED_EVENTS.add(property.substring(SHARED_TRANSFORM_PREFIX.length()))) {
                writeLine(value.substring(0, separator), value.substring(separator + 1));
            }
        }
    }

    /** Called from the Lunar settings/status screens; duplicate frame polls are suppressed. */
    public static void recordServiceSnapshot(boolean gomodOnline, String lunarState,
                                             boolean fullAccessEnabled, String fullAccessState) {
        if (!enabled) {
            return;
        }
        String snapshot = "gomod=" + state(gomodOnline)
                + " lunar=" + safe(lunarState)
                + " fullAccessEnabled=" + fullAccessEnabled
                + " fullAccess=" + (fullAccessEnabled ? safe(fullAccessState) : "disabled")
                + " lunarStatusSource=official-or-relayed-assets-socket"
                + " fullAccessStatusSource=dedicated-relay-game-socket";
        if (!snapshot.equals(lastServiceSnapshot)) {
            lastServiceSnapshot = snapshot;
            record("service.status_snapshot", snapshot);
        }
    }

    public static void recordLunarStatus(String state, String reason, int openGameSockets,
                                         long newestResponseAgeMs) {
        if (!enabled) {
            return;
        }
        String normalizedState = safe(state);
        String signature = normalizedState + "|" + reason + "|" + openGameSockets;
        if (!signature.equals(lastLunarStatus)) {
            lastLunarStatus = signature;
            String responseAge = newestResponseAgeMs == Long.MAX_VALUE
                    ? "none" : String.valueOf(newestResponseAgeMs);
            record("lunar.status", "state=" + normalizedState
                    + " reason=" + safe(reason)
                    + " openGameSockets=" + openGameSockets
                    + " newestSocketActivityAgeMs=" + responseAge);
        }
    }

    public static void recordFullAccessStatus(String state, String reason, int openSockets,
                                              long newestResponseAgeMs) {
        if (!enabled) {
            return;
        }
        String responseAge = newestResponseAgeMs == Long.MAX_VALUE
                ? "none" : String.valueOf(newestResponseAgeMs);
        String signature = state + "|" + reason + "|" + openSockets;
        long now = System.currentTimeMillis();
        if (!signature.equals(lastFullAccessStatus)
                || now - lastFullAccessStatusLogMs >= STATUS_HEARTBEAT_LOG_MS) {
            lastFullAccessStatus = signature;
            lastFullAccessStatusLogMs = now;
            record("fullaccess.status", "state=" + safe(state)
                    + " reason=" + safe(reason)
                    + " openRelaySockets=" + openSockets
                    + " newestSocketActivityAgeMs=" + responseAge
                    + " source=dedicated-relay-game-socket");
        }
    }

    /** Returns a path-safe endpoint label for the instrumented Lunar WebSocket, or null for other clients. */
    public static String lunarSocketEndpoint(Object client) {
        if (client == null || !client.getClass().getName().startsWith("com.moonsworth.lunar.")) {
            return null;
        }
        try {
            Object value = client.getClass().getMethod("getURI").invoke(client);
            if (!(value instanceof URI)) {
                return "host=unknown path=unknown";
            }
            URI uri = (URI) value;
            String host = uri.getHost();
            String path = uri.getPath();
            return "host=" + safe(host == null ? "unknown" : host)
                    + " path=" + safe(path == null || path.isEmpty() ? "/" : path)
                    + " role=" + socketRole(path, host);
        } catch (Throwable error) {
            return "host=unknown path=unknown endpointReadError=" + error.getClass().getSimpleName();
        }
    }

    public static void onSocketConnectAttempt(Object client, String method) {
        if (!enabled) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LAST_ATTEMPT_LOG_MS) {
            Long previous = LAST_ATTEMPT_LOG_MS.get(client);
            if (previous != null && now - previous < SOCKET_ATTEMPT_THROTTLE_MS) {
                return;
            }
            LAST_ATTEMPT_LOG_MS.put(client, now);
        }
        record("websocket.connect_attempt", "method=" + safe(method) + " " + endpoint);
    }

    public static void onSocketOpened(Object client) {
        if (!enabled) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint != null) {
            record("websocket.open", endpoint + " handshake=accepted");
        }
    }

    public static void onSocketClosed(Object client, int code, String reason, boolean remote) {
        if (!enabled) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint != null) {
            record("websocket.close", endpoint + " code=" + code + " remote=" + remote
                    + " reason=" + sanitizeMessage(reason));
        }
    }

    public static void onSocketError(Object client, Throwable error) {
        if (!enabled) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint == null) {
            return;
        }
        StringBuilder details = new StringBuilder(endpoint);
        if (error != null) {
            details.append(" exception=").append(error.getClass().getName())
                    .append(" message=").append(sanitizeMessage(error.getMessage()));
            StackTraceElement[] stack = error.getStackTrace();
            int count = Math.min(stack == null ? 0 : stack.length, 4);
            for (int i = 0; i < count; i++) {
                details.append(" frame").append(i).append('=').append(safe(stack[i].toString()));
            }
        }
        record("websocket.error", details.toString());
    }

    public static void onSocketFrame(Object client) {
        if (!enabled) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint == null || !isRelevantServicePath(endpoint)) {
            return;
        }
        long now = System.currentTimeMillis();
        long count;
        boolean log;
        synchronized (FRAME_COUNTS) {
            Long previousCount = FRAME_COUNTS.get(client);
            count = previousCount == null ? 1L : previousCount + 1L;
            FRAME_COUNTS.put(client, count);
            Long previousLog = LAST_FRAME_LOG_MS.get(client);
            log = previousCount == null || previousLog == null || now - previousLog >= FRAME_HEARTBEAT_LOG_MS;
            if (log) {
                LAST_FRAME_LOG_MS.put(client, now);
            }
        }
        if (log) {
            record("websocket.server_frame", endpoint + " totalFrames=" + count
                    + " payload=omitted");
        }
    }

    /**
     * Records bytes returned by WebSocketClient's TLS/input-stream reader, before the
     * Java-WebSocket protocol decoder runs. The read itself is payload-free and is used
     * only to distinguish downstream delivery from protocol/callback handling.
     */
    public static void onWebSocketTransportRead(Object client, int bytes) {
        if (!enabled || client == null) {
            return;
        }
        String endpoint = lunarSocketEndpoint(client);
        if (endpoint == null || !isRelevantServicePath(endpoint)) {
            return;
        }

        long now = System.currentTimeMillis();
        long totalBytes;
        boolean log;
        synchronized (TRANSPORT_READ_BYTE_COUNTS) {
            Long previousTotal = TRANSPORT_READ_BYTE_COUNTS.get(client);
            totalBytes = (previousTotal == null ? 0L : previousTotal) + Math.max(bytes, 0);
            TRANSPORT_READ_BYTE_COUNTS.put(client, totalBytes);
            Long previousLog = LAST_TRANSPORT_READ_LOG_MS.get(client);
            log = previousLog == null || now - previousLog >= FRAME_HEARTBEAT_LOG_MS || bytes < 0;
            if (log) {
                LAST_TRANSPORT_READ_LOG_MS.put(client, now);
            }
        }
        if (log) {
            record("websocket.transport_read", endpoint
                    + " chunkBytes=" + bytes + " totalBytes=" + totalBytes
                    + " payload=omitted");
        }
    }

    /** Records a PONG that Java-WebSocket actually received, without logging its payload. */
    public static void onWebSocketImplPong(Object socketImpl) {
        if (!enabled || socketImpl == null) {
            return;
        }
        try {
            Object client = socketImpl.getClass().getMethod("getWebSocketListener").invoke(socketImpl);
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return;
            }

            // A PONG proves the remote WebSocket endpoint is responsive even when Lunar's
            // application protocol has no new payloads to send for several minutes.
            LunarOnlineStatus.onWebSocketPong(client);

            long now = System.currentTimeMillis();
            long count;
            boolean log;
            synchronized (PONG_COUNTS) {
                Long previousCount = PONG_COUNTS.get(client);
                count = previousCount == null ? 1L : previousCount + 1L;
                PONG_COUNTS.put(client, count);
                Long previousLog = LAST_PONG_LOG_MS.get(client);
                log = previousCount == null || previousLog == null
                        || now - previousLog >= FRAME_HEARTBEAT_LOG_MS;
                if (log) {
                    LAST_PONG_LOG_MS.put(client, now);
                }
            }
            if (log) {
                record("websocket.pong_received", endpoint + " totalPongs=" + count
                        + " payload=omitted");
            }
        } catch (Throwable error) {
            record("websocket.pong_tracking_error", "exception=" + error.getClass().getSimpleName());
        }
    }

    /** Records outbound Java-WebSocket frame attempts without inspecting their payloads. */
    public static void onWebSocketImplOutboundFrames(Object socketImpl, Object frames) {
        if (!enabled || !(frames instanceof Iterable<?>)) {
            return;
        }
        for (Object frame : (Iterable<?>) frames) {
            onWebSocketImplOutboundFrame(socketImpl, frame);
        }
    }

    /** Records one outbound Java-WebSocket frame attempt without inspecting its payload. */
    public static void onWebSocketImplOutboundFrame(Object socketImpl, Object frame) {
        if (!enabled || socketImpl == null || frame == null) {
            return;
        }
        try {
            Object client = socketImpl.getClass().getMethod("getWebSocketListener").invoke(socketImpl);
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return;
            }

            Object opcodeValue = frame.getClass().getMethod("getOpcode").invoke(frame);
            String opcode = opcodeValue == null ? "unknown" : opcodeValue.toString();
            Object payload = frame.getClass().getMethod("getPayloadData").invoke(frame);
            int payloadBytes = payload instanceof ByteBuffer ? ((ByteBuffer) payload).remaining() : -1;
            long now = System.currentTimeMillis();
            long count;
            boolean log;
            synchronized (OUTBOUND_FRAME_STATE) {
                Map<String, Long> frameState = OUTBOUND_FRAME_STATE.get(socketImpl);
                if (frameState == null) {
                    frameState = new HashMap<String, Long>();
                    OUTBOUND_FRAME_STATE.put(socketImpl, frameState);
                }
                Long previousCount = frameState.get("count:" + opcode);
                count = previousCount == null ? 1L : previousCount + 1L;
                frameState.put("count:" + opcode, count);
                Long previousLog = frameState.get("lastLog:" + opcode);
                log = previousCount == null || previousLog == null
                        || now - previousLog >= FRAME_HEARTBEAT_LOG_MS;
                if (log) {
                    frameState.put("lastLog:" + opcode, now);
                }
            }
            if (log) {
                record("websocket.outbound_frame_attempt", endpoint + " opcode=" + safe(opcode)
                        + " totalOpcodeFrames=" + count + " payloadBytes=" + payloadBytes
                        + " payload=omitted delivery=not-confirmed");
            }
        } catch (Throwable error) {
            record("websocket.outbound_tracking_error",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    /** Records a PING when Java-WebSocket's client writer is about to write its encoded frame. */
    public static int onClientWriterPingWriteAttempt(Object client, ByteBuffer frameBuffer) {
        if (!enabled || client == null || frameBuffer == null || frameBuffer.limit() < 2) {
            return -1;
        }
        try {
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return -1;
            }

            // WebSocketClient$WebsocketWriteThread writes the backing array from offset zero.
            int firstFrameByte = frameBuffer.get(0) & 0xFF;
            if ((firstFrameByte & 0x0F) != 0x09) {
                return -1;
            }

            int frameBytes = frameBuffer.limit();
            record("websocket.transport_write_attempt", endpoint + " opcode=PING frameBytes=" + frameBytes
                    + " stage=WebSocketClient.OutputStream.write payload=omitted delivery=not-confirmed");
            return frameBytes;
        } catch (Throwable error) {
            record("websocket.transport_write_tracking_error",
                    "exception=" + error.getClass().getSimpleName());
            return -1;
        }
    }

    /** Records successful completion of OutputStream.write for an encoded PING frame. */
    public static void onClientWriterPingWriteCompleted(Object client, int pingFrameBytes,
                                                         int sourceBytesWritten) {
        if (!enabled || client == null || pingFrameBytes < 0) {
            return;
        }
        try {
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return;
            }
            record("websocket.transport_write_result", endpoint + " opcode=PING frameBytes=" + pingFrameBytes
                    + " sourceBytesWritten=" + sourceBytesWritten
                    + " stage=WebSocketClient.OutputStream.write returned=true payload=omitted");
        } catch (Throwable error) {
            record("websocket.transport_write_tracking_error",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    /** Records successful OutputStream.flush completion for an encoded PING frame. */
    public static void onClientWriterPingFlushCompleted(Object client, int pingFrameBytes) {
        if (!enabled || client == null || pingFrameBytes < 0) {
            return;
        }
        try {
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return;
            }
            record("websocket.transport_flush_result", endpoint + " opcode=PING frameBytes=" + pingFrameBytes
                    + " stage=WebSocketClient.OutputStream.flush returned=true payload=omitted");
        } catch (Throwable error) {
            record("websocket.transport_write_tracking_error",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    /** Records raw decoded socket bytes, without inspecting or persisting their contents. */
    public static void onWebSocketImplDecode(Object socketImpl, int bytes) {
        if (!enabled || socketImpl == null || bytes <= 0) {
            return;
        }
        try {
            Object client = socketImpl.getClass().getMethod("getWebSocketListener").invoke(socketImpl);
            String endpoint = lunarSocketEndpoint(client);
            if (endpoint == null || !isRelevantServicePath(endpoint)) {
                return;
            }

            Object readyState = socketImpl.getClass().getMethod("getReadyState").invoke(socketImpl);
            String phase = readyState == null ? "unknown" : readyState.toString();
            long now = System.currentTimeMillis();
            long totalBytes;
            boolean log;
            synchronized (INBOUND_BYTE_COUNTS) {
                Long previousTotal = INBOUND_BYTE_COUNTS.get(client);
                totalBytes = (previousTotal == null ? 0L : previousTotal) + bytes;
                INBOUND_BYTE_COUNTS.put(client, totalBytes);
                Long previousLog = LAST_INBOUND_LOG_MS.get(client);
                log = previousTotal == null || previousLog == null
                        || now - previousLog >= FRAME_HEARTBEAT_LOG_MS;
                if (log) {
                    LAST_INBOUND_LOG_MS.put(client, now);
                }
            }
            if (log) {
                record("websocket.inbound_bytes", endpoint + " phase=" + safe(phase)
                        + " chunkBytes=" + bytes + " totalBytes=" + totalBytes
                        + " contents=omitted");
            }
        } catch (Throwable error) {
            record("websocket.inbound_tracking_error", "exception=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isRelevantServicePath(String endpoint) {
        return endpoint.contains("path=/ws role=gomod-fullaccess-auth")
                || endpoint.contains("path=/game role=auth")
                || endpoint.contains("path=/game role=lunar-game-presence")
                || endpoint.contains("path=/ws/game role=gomod-fullaccess")
                || endpoint.contains("path=/ws/game role=gomod-fullaccess-direct-api");
    }

    private static String socketRole(String path, String host) {
        if ("gomod-service.onrender.com".equalsIgnoreCase(host)
                && "/ws".equals(path)) {
            return "gomod-fullaccess-auth";
        }
        if ("lunar-api-dev.onrender.com".equalsIgnoreCase(host)
                && "/ws/game".equals(path)) {
            return "gomod-fullaccess-direct-api";
        }
        if ("/game".equals(path) && "authenticator.lunarclientprod.com".equalsIgnoreCase(host)) {
            return "auth";
        }
        if ("/game".equals(path)) {
            return "lunar-game-presence";
        }
        if ("gomod-service.onrender.com".equalsIgnoreCase(host)
                && "/ws/game".equals(path)) {
            return "gomod-fullaccess";
        }
        return "other-lunar-websocket";
    }

    private static String state(boolean connected) {
        return connected ? "connected" : "disconnected";
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "none";
        }
        String sanitized = message
                .replaceAll("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/-]+=*", "$1 [REDACTED]")
                .replaceAll("(?i)\\b(authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|session(?:[_-]?id)?|cookie|password)(\\s*[:=]\\s*)[^\\s,;&]+", "$1$2[REDACTED]")
                .replaceAll("(?i)((?:https?|wss?)://[^\\s?#]+)\\?[^\\s]+", "$1?[REDACTED]");
        return safe(sanitized);
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        String clean = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return clean.length() > 500 ? clean.substring(0, 500) + "…" : clean;
    }

    private static void writeLine(String event, String details) {
        File destination = logFile;
        if (!enabled || destination == null) {
            return;
        }
        long recordedAtMs = System.currentTimeMillis();
        // Never use CallerRunsPolicy: a full queue must drop diagnostics rather than stall a frame.
        LOG_WRITER.execute(() -> writeLineOnWorker(destination, recordedAtMs, event, details));
    }

    private static void writeLineOnWorker(File destination, long recordedAtMs, String event, String details) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now >= nextSharedTransformScanMs && !LOG_WRITER.isShutdown()) {
            nextSharedTransformScanMs = now + 1_000L;
            flushSharedTransformEvents();
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(destination, true), StandardCharsets.UTF_8))) {
            String time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT)
                    .format(new Date(recordedAtMs));
            long dropped = DROPPED_LOG_LINES.getAndSet(0L);
            if (dropped > 0L) {
                writer.write(time + " [gomod-lunar] diagnostics.overflow dropped=" + dropped);
                writer.newLine();
            }
            writer.write(time);
            writer.write(" [gomod-lunar] ");
            writer.write(safe(event));
            writer.write(' ');
            writer.write(sanitizeMessage(details));
            writer.newLine();
        } catch (Throwable error) {
            enabled = false;
            System.err.println("[gomod][lunar-debug] Could not write diagnostics: "
                    + error.getClass().getSimpleName());
        }
    }
}
