package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;

/** Account-scoped private wheel snapshots, independent of the API's in-memory repository. */
public final class LunarEmoteWheelStore {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private final File directory;
    private final int maxRequestBytes;

    public LunarEmoteWheelStore(File directory) { this(directory, MAX_REQUEST_BYTES); }
    public LunarEmoteWheelStore(File directory,int maxRequestBytes) {
        if(maxRequestBytes<1 || maxRequestBytes>512*1024)throw new IllegalArgumentException("Invalid snapshot limit");
        this.directory=directory;this.maxRequestBytes=maxRequestBytes;
    }

    /** Null means never saved; a zero-length request means the player deliberately emptied it. */
    public synchronized byte[] load(UUID account) throws IOException {
        JsonObject data = loadEnvelope(account);
        return data == null ? null : Base64.getDecoder().decode(data.get("request").getAsString());
    }

    /** Reads payload and optional provenance together from one validated atomic file. */
    public synchronized JsonObject loadEnvelope(UUID account) throws IOException {
        Path file = path(account);
        if (!Files.exists(file)) return null;
        if (Files.size(file) > Math.max(100*1024,((long)maxRequestBytes+2)/3*4+1024)) throw new IOException("Wheel snapshot too large");
        try {
            JsonObject data = new JsonParser().parse(new String(Files.readAllBytes(file),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            if (data.get("version").getAsInt() != 1) throw new IOException("Unsupported wheel snapshot");
            byte[] request = Base64.getDecoder().decode(data.get("request").getAsString());
            if (request.length > maxRequestBytes) throw new IOException("Wheel request too large");
            return data;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid wheel snapshot", invalid);
        }
    }

    public synchronized void save(UUID account, byte[] request) throws IOException {
        save(account, request, null);
    }

    public synchronized void save(UUID account, byte[] request, String snapshotSource) throws IOException {
        if (request == null || request.length > maxRequestBytes) throw new IOException("Invalid wheel request");
        Path file = path(account);
        Files.createDirectories(file.getParent());
        JsonObject data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("request", Base64.getEncoder().encodeToString(request));
        if (snapshotSource != null) data.addProperty("snapshotSource", snapshotSource);
        Path temporary = Files.createTempFile(file.getParent(), "wheel-", ".tmp");
        try {
            Files.write(temporary, data.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path path(UUID account) {
        if (account == null) throw new IllegalArgumentException("Account required");
        return new File(directory, account.toString() + ".json").toPath();
    }
}
