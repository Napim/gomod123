package me.napim.gomod.features;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Saved local tuning for the kill taunt; each new taunt snapshots these values. */
public final class GoparkyTauntSettings {
    /** Default native path speed in blocks per second. */
    public static final double DEFAULT_TRAVEL_BLOCKS_PER_SECOND = 1000.0D / 210.0D;
    public static final Values DEFAULTS = new Values(1.7D, 40, 90,
            DEFAULT_TRAVEL_BLOCKS_PER_SECOND, 1.0D, false);
    private static Values current = DEFAULTS;
    private static boolean loaded;

    private GoparkyTauntSettings() {}

    public static synchronized Values get() {
        if (!loaded) {
            Path file = file();
            if (file == null) return current;
            loaded = true;
            if (Files.isRegularFile(file)) {
                try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject json = new JsonParser().parse(input).getAsJsonObject();
                    current = new Values(json.get("speed").getAsDouble(),
                            json.get("minimumGapMs").getAsInt(), json.get("maximumGapMs").getAsInt(),
                            json.has("travelBlocksPerSecond")
                                    ? json.get("travelBlocksPerSecond").getAsDouble()
                                    : DEFAULTS.travelBlocksPerSecond,
                            json.has("tauntRadiusBlocks")
                                    ? json.get("tauntRadiusBlocks").getAsDouble()
                                    : DEFAULTS.tauntRadiusBlocks,
                            json.has("randomPlayerInteractions")
                                    && json.get("randomPlayerInteractions").getAsBoolean());
                } catch (Exception invalid) {
                    System.err.println("[gomod] Goparky taunt settings could not be loaded; using defaults.");
                }
            }
        }
        return current;
    }

    public static synchronized Values save(double speed, int minimumGapMs, int maximumGapMs) throws IOException {
        Values existing = get();
        return save(speed, minimumGapMs, maximumGapMs,
                existing.travelBlocksPerSecond, existing.tauntRadiusBlocks,
                existing.randomPlayerInteractions);
    }

    public static synchronized Values save(double speed, int minimumGapMs, int maximumGapMs,
                                           double travelBlocksPerSecond, double tauntRadiusBlocks) throws IOException {
        Values existing = get();
        return save(speed, minimumGapMs, maximumGapMs, travelBlocksPerSecond, tauntRadiusBlocks,
                existing.randomPlayerInteractions);
    }

    public static synchronized Values save(double speed, int minimumGapMs, int maximumGapMs,
                                           double travelBlocksPerSecond, double tauntRadiusBlocks,
                                           boolean randomPlayerInteractions) throws IOException {
        Values next = new Values(speed, minimumGapMs, maximumGapMs,
                travelBlocksPerSecond, tauntRadiusBlocks, randomPlayerInteractions);
        Path file = file();
        if (file == null) throw new IOException("Minecraft data directory is unavailable.");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "goparky-taunt-", ".tmp");
        try {
            JsonObject json = new JsonObject();
            json.addProperty("speed", next.speed);
            json.addProperty("minimumGapMs", next.minimumGapMs);
            json.addProperty("maximumGapMs", next.maximumGapMs);
            json.addProperty("travelBlocksPerSecond", next.travelBlocksPerSecond);
            json.addProperty("tauntRadiusBlocks", next.tauntRadiusBlocks);
            json.addProperty("randomPlayerInteractions", next.randomPlayerInteractions);
            Files.write(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json)
                    .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        current = next;
        loaded = true;
        return next;
    }

    /** Persist all controls submitted by the Locker's native Cosmetic Settings panel. */
    public static synchronized Values saveFromJson(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 1024) {
            throw new IllegalArgumentException("Settings payload is missing or too large.");
        }
        JsonObject json = new JsonParser().parse(encoded).getAsJsonObject();
        Values existing = get();
        return save(json.has("speed") ? json.get("speed").getAsDouble() : existing.speed,
                json.has("minimumGapMs") ? json.get("minimumGapMs").getAsInt() : existing.minimumGapMs,
                json.has("maximumGapMs") ? json.get("maximumGapMs").getAsInt() : existing.maximumGapMs,
                json.has("travelBlocksPerSecond")
                        ? json.get("travelBlocksPerSecond").getAsDouble() : existing.travelBlocksPerSecond,
                json.has("tauntRadiusBlocks")
                        ? json.get("tauntRadiusBlocks").getAsDouble() : existing.tauntRadiusBlocks,
                json.has("randomPlayerInteractions")
                        ? json.get("randomPlayerInteractions").getAsBoolean()
                        : existing.randomPlayerInteractions);
    }

    public static String toJson(Values values) {
        JsonObject json = new JsonObject();
        json.addProperty("speed", values.speed);
        json.addProperty("minimumGapMs", values.minimumGapMs);
        json.addProperty("maximumGapMs", values.maximumGapMs);
        json.addProperty("travelBlocksPerSecond", values.travelBlocksPerSecond);
        json.addProperty("tauntRadiusBlocks", values.tauntRadiusBlocks);
        json.addProperty("randomPlayerInteractions", values.randomPlayerInteractions);
        return new GsonBuilder().create().toJson(json);
    }

    private static Path file() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.mcDataDir == null ? null
                : mc.mcDataDir.toPath().resolve("gomod123").resolve("goparky-taunt.json");
    }

    public static final class Values {
        public final double speed;
        public final int minimumGapMs;
        public final int maximumGapMs;
        public final double travelBlocksPerSecond;
        public final double tauntRadiusBlocks;
        public final boolean randomPlayerInteractions;

        public Values(double speed, int minimumGapMs, int maximumGapMs) {
            this(speed, minimumGapMs, maximumGapMs,
                    DEFAULT_TRAVEL_BLOCKS_PER_SECOND, DEFAULTS.tauntRadiusBlocks,
                    DEFAULTS.randomPlayerInteractions);
        }

        public Values(double speed, int minimumGapMs, int maximumGapMs,
                      double travelBlocksPerSecond, double tauntRadiusBlocks) {
            this(speed, minimumGapMs, maximumGapMs, travelBlocksPerSecond, tauntRadiusBlocks,
                    DEFAULTS.randomPlayerInteractions);
        }

        public Values(double speed, int minimumGapMs, int maximumGapMs,
                      double travelBlocksPerSecond, double tauntRadiusBlocks,
                      boolean randomPlayerInteractions) {
            if (!Double.isFinite(speed) || speed < 0.25D || speed > 4.0D
                    || minimumGapMs < 0 || maximumGapMs > 2000 || maximumGapMs < minimumGapMs) {
                throw new IllegalArgumentException("Speed must be 0.25-4; gaps must be 0-2000ms, minimum <= maximum.");
            }
            if (!Double.isFinite(travelBlocksPerSecond)
                    || travelBlocksPerSecond < 0.5D || travelBlocksPerSecond > 12.0D) {
                throw new IllegalArgumentException("Travel speed must be 0.5-12 blocks per second.");
            }
            if (!Double.isFinite(tauntRadiusBlocks)
                    || tauntRadiusBlocks < 0.25D || tauntRadiusBlocks > 4.0D) {
                throw new IllegalArgumentException("Taunt radius must be 0.25-4 blocks.");
            }
            this.speed = speed;
            this.minimumGapMs = minimumGapMs;
            this.maximumGapMs = maximumGapMs;
            this.travelBlocksPerSecond = travelBlocksPerSecond;
            this.tauntRadiusBlocks = tauntRadiusBlocks;
            this.randomPlayerInteractions = randomPlayerInteractions;
        }

        public String command() {
            return "/goparkytaunt set " + java.math.BigDecimal.valueOf(speed).stripTrailingZeros().toPlainString()
                    + " " + minimumGapMs + " " + maximumGapMs;
        }

        public String fullCommand() {
            return command()
                    + " " + java.math.BigDecimal.valueOf(travelBlocksPerSecond).setScale(2,
                    java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + " " + java.math.BigDecimal.valueOf(tauntRadiusBlocks).stripTrailingZeros().toPlainString();
        }
    }
}
