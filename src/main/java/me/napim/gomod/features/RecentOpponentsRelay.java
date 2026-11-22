package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Account-bound relay cache and durable offline outbox for Recent Opponents. */
final class RecentOpponentsRelay {
    private static final int MAX_EVENTS_PER_REQUEST = 100;
    private static final int MAX_SCHEDULED_EVENTS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long NORMAL_SYNC_INTERVAL_MS = 5 * 60_000L;
    private static final long RETRY_MIN_MS = 15_000L;
    private static final long RETRY_MAX_MS = 5 * 60_000L;
    private static final JsonParser JSON = new JsonParser();

    private static String activeUuid = "";
    private static AccountState activeState;
    private static boolean legacyOwnerLoaded;
    private static String legacyOwnerUuid = "";
    private static boolean syncInFlight;
    private static long retryAt;
    private static long retryDelayMs = RETRY_MIN_MS;
    private static String status = "local only";
    private static String lastSyncError = "";

    private RecentOpponentsRelay() { }

    static synchronized void tick() {
        ensureLegacyOwnerLoaded();
        AccountState state = currentState();
        if (state == null) {
            status = "local only";
            return;
        }
        long now = System.currentTimeMillis();
        if (releaseDueScheduledEvents(state, now)) {
            saveState(state);
            retryAt = 0L;
        }
        if (syncInFlight || now < retryAt) return;
        String endpoint = syncEndpoint();
        if (endpoint.isEmpty()) {
            status = "relay unavailable";
            lastSyncError = "No Recent Opponents relay endpoint is configured.";
            retryAt = now + NORMAL_SYNC_INTERVAL_MS;
            return;
        }
        if (state.legacyImported && state.pending.size() == 0
                && state.lastSuccessfulSync > 0L
                && now - state.lastSuccessfulSync < NORMAL_SYNC_INTERVAL_MS) {
            status = "synced";
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("version", 1);
        JsonArray batch = new JsonArray();
        for (int i = 0; i < state.pending.size() && i < MAX_EVENTS_PER_REQUEST; i++) {
            batch.add(JSON.parse(state.pending.get(i).toString()));
        }
        payload.add("events", batch);
        if (!state.legacyImported) {
            payload.add("legacyImport", legacyOwnerUuid.isEmpty() || legacyOwnerUuid.equals(state.ownerUuid)
                    ? legacyImportSnapshot() : new JsonArray());
        }
        syncInFlight = true;
        status = "syncing";
        retryAt = now + RETRY_MIN_MS;
        final AccountState syncingState = state;
        final String syncingEndpoint = endpoint;
        final JsonObject syncingPayload = payload;
        Thread worker = new Thread(() -> runSync(syncingState, syncingEndpoint, syncingPayload),
                "gomod-recent-opponents-sync");
        worker.setDaemon(true);
        worker.start();
    }

    static synchronized void enqueue(String matchId, String name, String uuid, String kind, long occurredAt) {
        AccountState state = currentState();
        if (state == null || !validName(name) || !validUuid(matchId)
                || !("encounter".equals(kind) || "win".equals(kind) || "loss".equals(kind))) return;
        JsonObject event = createEvent(matchId, name, uuid, kind, occurredAt);
        appendPendingEvent(state, event);
    }

    /** Persists disguise-related entries locally until their privacy delay has elapsed. */
    static synchronized void enqueueDelayed(String matchId, String name, String uuid, String kind,
                                             long occurredAt, long releaseAt) {
        AccountState state = currentState();
        if (state == null || !validName(name) || !validUuid(matchId)
                || !("encounter".equals(kind) || "win".equals(kind) || "loss".equals(kind))) return;
        long now = System.currentTimeMillis();
        if (releaseAt <= now) {
            enqueue(matchId, name, uuid, kind, occurredAt);
            return;
        }
        if (state.scheduled.size() >= MAX_SCHEDULED_EVENTS) {
            System.err.println("[gomod] Recent Opponents delayed queue is full; skipped one delayed entry.");
            return;
        }
        JsonObject scheduled = new JsonObject();
        scheduled.addProperty("releaseAt", releaseAt);
        scheduled.add("event", createEvent(matchId, name, uuid, kind, occurredAt));
        state.scheduled.add(scheduled);
        saveState(state);
    }

    private static JsonObject createEvent(String matchId, String name, String uuid, String kind, long occurredAt) {
        JsonObject event = new JsonObject();
        event.addProperty("eventId", UUID.randomUUID().toString());
        event.addProperty("matchId", matchId);
        event.addProperty("name", name.trim());
        event.addProperty("uuid", normalizeUuid(uuid));
        event.addProperty("kind", kind);
        event.addProperty("occurredAt", occurredAt > 0L ? occurredAt : System.currentTimeMillis());
        return event;
    }

    private static void appendPendingEvent(AccountState state, JsonObject event) {
        state.pending.add(event);
        accumulatePendingEvent(state.pendingRecordMap, event);
        saveState(state);
        retryAt = 0L;
    }

    /** Move due local entries into the ordinary durable outbox without exposing future entries. */
    private static boolean releaseDueScheduledEvents(AccountState state, long now) {
        if (state.scheduled == null || state.scheduled.size() == 0) return false;
        JsonArray remaining = new JsonArray();
        boolean changed = false;
        for (JsonElement element : state.scheduled) {
            if (element == null || !element.isJsonObject()) {
                changed = true;
                continue;
            }
            JsonObject scheduled = element.getAsJsonObject();
            long releaseAt = number(scheduled, "releaseAt", 0L);
            JsonElement eventElement = scheduled.get("event");
            if (releaseAt > now) {
                remaining.add(JSON.parse(scheduled.toString()));
                continue;
            }
            changed = true;
            if (eventElement == null || !eventElement.isJsonObject()) continue;
            JsonObject event = eventElement.getAsJsonObject();
            String name = string(event, "name", "");
            String matchId = string(event, "matchId", "");
            String kind = string(event, "kind", "");
            if (!validName(name) || !validUuid(matchId)
                    || !("encounter".equals(kind) || "win".equals(kind) || "loss".equals(kind))) continue;
            JsonObject released = JSON.parse(event.toString()).getAsJsonObject();
            state.pending.add(released);
            accumulatePendingEvent(state.pendingRecordMap, released);
        }
        if (changed) state.scheduled = remaining;
        return changed;
    }

    static synchronized List<RecentOpponents.Record> mergeHistory(List<RecentOpponents.Record> legacyRecords) {
        AccountState state = currentState();
        LinkedHashMap<String, RecentOpponents.Record> merged = new LinkedHashMap<String, RecentOpponents.Record>();
        if (state == null) {
            for (RecentOpponents.Record record : legacyRecords) mergeRecord(merged, record);
            return new ArrayList<RecentOpponents.Record>(merged.values());
        }
        for (RecentOpponents.Record record : state.remoteRecords) mergeRecord(merged, record);
        if (!state.legacyImported && legacyRecords != null) {
            for (RecentOpponents.Record record : legacyRecords) mergeRecord(merged, record);
        }
        for (RecentOpponents.Record record : state.pendingRecordMap.values()) mergeRecord(merged, record);
        return new ArrayList<RecentOpponents.Record>(merged.values());
    }

    static synchronized String getStatus() {
        AccountState state = currentState();
        if (state == null) return "local only";
        if (syncInFlight) return "syncing";
        if (state.pending.size() > 0) return state.pending.size() + " pending";
        if (state.lastSuccessfulSync > 0L
                && System.currentTimeMillis() - state.lastSuccessfulSync < NORMAL_SYNC_INTERVAL_MS) return "synced";
        return status;
    }

    static synchronized String getStatusDetail() {
        AccountState state = currentState();
        if (state == null) {
            return "Recent Opponents sync needs a signed-in Minecraft profile. gomod Online status is separate.";
        }
        String detail;
        if (state.pending.size() > 0) {
            detail = state.pending.size() + " Recent Opponents entries are saved locally and have not been acknowledged by the Recent Opponents sync endpoint.";
        } else {
            detail = "Recent Opponents uses a separate sync request.";
        }
        detail += " gomod Online presence is separate.";
        if (lastSyncError != null && !lastSyncError.isEmpty()) {
            detail += " Latest sync error: " + lastSyncError;
        }
        return detail;
    }

    private static void runSync(AccountState state, String endpoint, JsonObject payload) {
        try {
            JsonObject response = post(endpoint, payload);
            synchronized (RecentOpponentsRelay.class) {
                if (response != null && response.has("durable") && response.get("durable").getAsBoolean()) {
                    Set<String> completed = stringSet(response.getAsJsonArray("acceptedEventIds"));
                    completed.addAll(stringSet(response.getAsJsonArray("rejectedEventIds")));
                    removeCompleted(state, completed);
                    rebuildPendingRecordMap(state);
                    state.remoteRecords = parseRecords(response.getAsJsonArray("records"));
                    state.legacyImported = booleanValue(response, "legacyImported", state.legacyImported);
                    if (state.legacyImported) claimLegacyImportOwner(state.ownerUuid);
                    state.lastSuccessfulSync = System.currentTimeMillis();
                    retryDelayMs = RETRY_MIN_MS;
                    lastSyncError = state.pending.size() == 0
                            ? "" : "The relay did not acknowledge every queued entry.";
                    retryAt = state.pending.size() == 0
                            ? state.lastSuccessfulSync + NORMAL_SYNC_INTERVAL_MS
                            : state.lastSuccessfulSync + RETRY_MIN_MS;
                    status = state.pending.size() == 0 ? "synced" : "pending sync";
                    saveState(state);
                } else {
                    retryDelayMs = Math.min(RETRY_MAX_MS, Math.max(RETRY_MIN_MS, retryDelayMs * 2L));
                    retryAt = System.currentTimeMillis() + retryDelayMs;
                    status = state.pending.size() == 0 ? "relay unavailable" : "pending sync";
                    lastSyncError = "The relay did not confirm durable storage.";
                }
            }
        } catch (Exception ignored) {
            synchronized (RecentOpponentsRelay.class) {
                retryDelayMs = Math.min(RETRY_MAX_MS, Math.max(RETRY_MIN_MS, retryDelayMs * 2L));
                retryAt = System.currentTimeMillis() + retryDelayMs;
                status = state.pending.size() == 0 ? "relay unavailable" : "pending sync";
                lastSyncError = conciseError(ignored);
            }
        } finally {
            synchronized (RecentOpponentsRelay.class) {
                syncInFlight = false;
            }
        }
    }

    private static JsonObject post(String endpoint, JsonObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            GomodRelayAuth.authorize(connection, endpoint);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            GomodRelayAuth.noteResponseCode(endpoint, code);
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (body.length() + line.length() > MAX_RESPONSE_BYTES) throw new java.io.IOException("relay response too large");
                        body.append(line);
                    }
                }
            }
            if (code < 200 || code >= 300) throw new java.io.IOException("Recent Opponents relay HTTP " + code);
            JsonElement parsed = JSON.parse(body.toString());
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JsonArray legacyImportSnapshot() {
        JsonArray array = new JsonArray();
        int copied = 0;
        for (RecentOpponents.Record record : RecentOpponents.getLegacyRecordsForSync()) {
            if (copied++ >= 500) break;
            JsonObject item = new JsonObject();
            item.addProperty("name", record.name);
            item.addProperty("uuid", normalizeUuid(record.uuid));
            item.addProperty("matches", record.matches);
            item.addProperty("wins", record.wins);
            item.addProperty("losses", record.losses);
            item.addProperty("lastSeen", record.lastSeen);
            array.add(item);
        }
        return array;
    }

    private static void ensureLegacyOwnerLoaded() {
        if (legacyOwnerLoaded) return;
        legacyOwnerLoaded = true;
        File file = legacyOwnerFile();
        if (!file.isFile()) return;
        try (FileReader reader = new FileReader(file)) {
            StringBuilder value = new StringBuilder();
            char[] buffer = new char[64];
            int read;
            while ((read = reader.read(buffer)) >= 0) value.append(buffer, 0, read);
            legacyOwnerUuid = normalizeUuid(value.toString());
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not read Recent Opponents import marker: " + ex.getMessage());
        }
    }

    private static void claimLegacyImportOwner(String uuid) {
        if (!legacyOwnerUuid.isEmpty() || !validUuid(uuid)) return;
        legacyOwnerUuid = normalizeUuid(uuid);
        File file = legacyOwnerFile();
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileWriter writer = new FileWriter(temp)) { writer.write(legacyOwnerUuid); }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not save Recent Opponents import marker: " + ex.getMessage());
        }
    }

    private static List<RecentOpponents.Record> parseRecords(JsonArray array) {
        if (array == null) return Collections.emptyList();
        Map<String, RecentOpponents.Record> records = new LinkedHashMap<String, RecentOpponents.Record>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name", "");
            if (!validName(name)) continue;
            String uuid = normalizeUuid(string(item, "uuid", ""));
            RecentOpponents.Record record = new RecentOpponents.Record(name, uuid,
                    boundedCount(item, "matches"), boundedCount(item, "wins"), boundedCount(item, "losses"),
                    number(item, "lastSeen", 0L), booleanValue(item, "legacyUnverified", false));
            mergeRecord(records, record);
        }
        return new ArrayList<RecentOpponents.Record>(records.values());
    }

    private static void mergeRecord(Map<String, RecentOpponents.Record> records, RecentOpponents.Record incoming) {
        if (incoming == null || !validName(incoming.name)) return;
        String key = recordKey(incoming.name, incoming.uuid);
        RecentOpponents.Record current = records.get(key);
        if (current == null) {
            records.put(key, incoming);
            return;
        }
        RecentOpponents.Record newest = incoming.lastSeen >= current.lastSeen ? incoming : current;
        records.put(key, new RecentOpponents.Record(newest.name,
                !incoming.uuid.isEmpty() ? incoming.uuid : current.uuid,
                saturatingAdd(current.matches, incoming.matches), saturatingAdd(current.wins, incoming.wins),
                saturatingAdd(current.losses, incoming.losses), Math.max(current.lastSeen, incoming.lastSeen),
                current.legacyUnverified || incoming.legacyUnverified));
    }

    private static synchronized AccountState currentState() {
        Minecraft mc = Minecraft.getMinecraft();
        String uuid = normalizeUuid(GomodSocialIdentity.profileUuidUndashed(mc));
        if (uuid.isEmpty()) return null;
        if (!uuid.equals(activeUuid) || activeState == null) {
            activeUuid = uuid;
            activeState = loadState(uuid);
            retryAt = 0L;
            retryDelayMs = RETRY_MIN_MS;
            status = "connecting";
            lastSyncError = "";
        }
        return activeState;
    }

    private static String conciseError(Throwable error) {
        if (error == null) return "Unknown sync error.";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.length() > 120) message = message.substring(0, 117) + "...";
        return message;
    }

    private static AccountState loadState(String uuid) {
        AccountState state = new AccountState(uuid);
        File file = stateFile(uuid);
        if (!file.isFile()) return state;
        try (FileReader reader = new FileReader(file)) {
            JsonElement root = JSON.parse(reader);
            if (root == null || !root.isJsonObject()) return state;
            JsonObject obj = root.getAsJsonObject();
            state.legacyImported = booleanValue(obj, "legacyImported", false);
            state.lastSuccessfulSync = number(obj, "lastSuccessfulSync", 0L);
            state.pending = copyArray(obj.getAsJsonArray("pending"));
            state.scheduled = copyArray(obj.getAsJsonArray("scheduled"));
            state.remoteRecords = parseRecords(obj.getAsJsonArray("records"));
            rebuildPendingRecordMap(state);
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not read Recent Opponents relay state: " + ex.getMessage());
        }
        return state;
    }

    private static void saveState(AccountState state) {
        try {
            File file = stateFile(state.ownerUuid);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("ownerUuid", state.ownerUuid);
            root.addProperty("legacyImported", state.legacyImported);
            root.addProperty("lastSuccessfulSync", state.lastSuccessfulSync);
            root.add("pending", state.pending);
            root.add("scheduled", state.scheduled);
            JsonArray records = new JsonArray();
            for (RecentOpponents.Record record : state.remoteRecords) {
                JsonObject item = new JsonObject();
                item.addProperty("name", record.name);
                item.addProperty("uuid", record.uuid);
                item.addProperty("matches", record.matches);
                item.addProperty("wins", record.wins);
                item.addProperty("losses", record.losses);
                item.addProperty("lastSeen", record.lastSeen);
                if (record.legacyUnverified) item.addProperty("legacyUnverified", true);
                records.add(item);
            }
            root.add("records", records);
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileWriter writer = new FileWriter(temp)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveUnavailable) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ex) {
            System.err.println("[gomod] Could not save Recent Opponents relay state: " + ex.getMessage());
        }
    }

    private static File stateFile(String uuid) {
        return new File(GomodPaths.getGomod123Dir(), "recent_opponents_relay_" + uuid + ".json");
    }

    private static File legacyOwnerFile() {
        return new File(GomodPaths.getGomod123Dir(), "recent_opponents_relay_legacy_owner.txt");
    }

    private static String syncEndpoint() {
        try {
            URI base = new URI(ConfigManager.getGlobalChatEndpoint());
            if (base.getScheme() == null || base.getHost() == null) return "";
            String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "https" : "http";
            return new URI(scheme, base.getUserInfo(), base.getHost(), base.getPort(),
                    "/recent-opponents/sync", null, null).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JsonArray copyArray(JsonArray source) {
        JsonArray copy = new JsonArray();
        if (source != null) for (JsonElement element : source) copy.add(JSON.parse(element.toString()));
        return copy;
    }

    private static Set<String> stringSet(JsonArray source) {
        Set<String> values = new HashSet<String>();
        if (source != null) for (JsonElement element : source) {
            try { values.add(normalizeUuid(element.getAsString())); } catch (Throwable ignored) { }
        }
        return values;
    }

    private static void removeCompleted(AccountState state, Set<String> ids) {
        if (ids.isEmpty()) return;
        JsonArray remaining = new JsonArray();
        for (JsonElement element : state.pending) {
            String id = element.isJsonObject()
                    ? normalizeUuid(string(element.getAsJsonObject(), "eventId", "")) : "";
            if (!ids.contains(id)) remaining.add(JSON.parse(element.toString()));
        }
        state.pending = remaining;
    }

    private static void rebuildPendingRecordMap(AccountState state) {
        state.pendingRecordMap.clear();
        for (JsonElement element : state.pending) {
            if (element.isJsonObject()) accumulatePendingEvent(state.pendingRecordMap, element.getAsJsonObject());
        }
    }

    private static void accumulatePendingEvent(Map<String, RecentOpponents.Record> records, JsonObject event) {
        String name = string(event, "name", "");
        if (!validName(name)) return;
        String uuid = normalizeUuid(string(event, "uuid", ""));
        String kind = string(event, "kind", "");
        String key = recordKey(name, uuid);
        RecentOpponents.Record old = records.get(key);
        if (old == null) old = new RecentOpponents.Record(name, uuid, 0, 0, 0, 0L);
        records.put(key, new RecentOpponents.Record(name, uuid,
                saturatingAdd(old.matches, "encounter".equals(kind) ? 1 : 0),
                saturatingAdd(old.wins, "win".equals(kind) ? 1 : 0),
                saturatingAdd(old.losses, "loss".equals(kind) ? 1 : 0),
                Math.max(old.lastSeen, number(event, "occurredAt", 0L))));
    }

    private static boolean validUuid(String raw) { return !normalizeUuid(raw).isEmpty(); }

    private static String normalizeUuid(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (value.length() != 32) return "";
        boolean nonZero = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return "";
            if (c != '0') nonZero = true;
        }
        return nonZero ? value : "";
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    private static String recordKey(String name, String uuid) {
        String normalizedUuid = normalizeUuid(uuid);
        return normalizedUuid.isEmpty() ? "name:" + name.toLowerCase(Locale.ROOT) : "uuid:" + normalizedUuid;
    }

    private static int boundedCount(JsonObject object, String key) {
        long value = number(object, key, 0L);
        return (int) Math.max(0L, Math.min(10_000_000L, value));
    }

    private static int saturatingAdd(int first, int second) {
        return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object != null && object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Throwable ignored) { return fallback; }
    }

    private static long number(JsonObject object, String key, long fallback) {
        try { return object != null && object.has(key) ? object.get(key).getAsLong() : fallback; }
        catch (Throwable ignored) { return fallback; }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try { return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Throwable ignored) { return fallback; }
    }

    private static final class AccountState {
        final String ownerUuid;
        JsonArray pending = new JsonArray();
        JsonArray scheduled = new JsonArray();
        final Map<String, RecentOpponents.Record> pendingRecordMap = new LinkedHashMap<String, RecentOpponents.Record>();
        List<RecentOpponents.Record> remoteRecords = new ArrayList<RecentOpponents.Record>();
        boolean legacyImported;
        long lastSuccessfulSync;

        AccountState(String ownerUuid) { this.ownerUuid = ownerUuid; }
    }
}
