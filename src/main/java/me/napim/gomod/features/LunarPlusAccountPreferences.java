package me.napim.gomod.features;

import me.napim.gomod.util.MinecraftAccountUuid;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local Plus choices, keyed by the authenticated account rather than its disguise. */
public final class LunarPlusAccountPreferences {
    private static final Map<UUID, Integer> CHOICES = new ConcurrentHashMap<UUID, Integer>();
    private static final Map<UUID, Integer> PENDING = new HashMap<UUID, Integer>();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gomod-lunar-plus-preferences"); t.setDaemon(true); return t;
    });
    private static boolean writeScheduled;
    private static final int MAX_ACCOUNTS = 256;
    private LunarPlusAccountPreferences() { }

    /** Session UUID stays stable through /disguise and is also available in the main menu. */
    private static UUID account() {
        return MinecraftAccountUuid.current();
    }

    private static Path directory() {
        return new File(ConfigManager.getGomodDataDirectory(), "lunar-plus-preferences").toPath();
    }

    private static Path file(UUID id) { return directory().resolve(id.toString() + ".txt"); }

    private static int load(UUID id) {
        try {
            Path path = file(id);
            if (!Files.isRegularFile(path) || Files.size(path) > 64) return -1;
            String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            if (!raw.matches("-1|[0-9]{1,8}")) return -1;
            int rgb = Integer.parseInt(raw);
            return rgb >= -1 && rgb <= 0xFFFFFF ? rgb : -1;
        } catch (Exception invalid) { return -1; }
    }

    private static int choice(UUID id) {
        if (id == null) return -1;
        Integer rgb = CHOICES.get(id);
        if (rgb != null) return rgb.intValue();
        int value = load(id);
        if (CHOICES.size() < MAX_ACCOUNTS) CHOICES.put(id, value);
        return value;
    }

    public static synchronized int getSelectedRgb() { return choice(account()); }

    static int getCachedSelectedRgb(UUID id) {
        Integer rgb = CHOICES.get(id);
        return rgb == null ? -1 : rgb.intValue();
    }

    public static synchronized void clearSelection() {
        UUID id = account();
        if (id != null) remember(id, -1);
    }

    /** Only the first account may adopt the old global No+ setting; never copy it to every login. */
    public static synchronized boolean isHidden(boolean legacyHidden) {
        UUID id = account();
        int selected = choice(id);
        if (id != null && selected < 0 && legacyHidden && claimLegacy(id)) {
            remember(id, 0);
            selected = 0;
        }
        return selected == 0;
    }

    public static synchronized void setHidden(boolean hidden) {
        UUID id = account();
        if (id == null) return;
        claimLegacy(id);
        int previous = choice(id);
        if (hidden) remember(id, 0);
        else if (previous == 0) remember(id, -1);
    }

    public static synchronized void select(int rgb) {
        UUID id = account();
        if (id == null || rgb < 0 || rgb > 0xFFFFFF) return;
        claimLegacy(id);
        remember(id, rgb);
    }

    private static boolean claimLegacy(UUID id) {
        try {
            Path directory = directory();
            Files.createDirectories(directory);
            Path marker = directory.resolve("legacy-account.txt");
            try {
                Files.write(marker, id.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                return true;
            } catch (FileAlreadyExistsException claimed) {
                return Files.size(marker) <= 64 && id.toString().equals(
                        new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim());
            }
        } catch (Exception unavailable) { return false; }
    }

    private static void remember(UUID id, int rgb) {
        if (!CHOICES.containsKey(id) && CHOICES.size() >= MAX_ACCOUNTS) return;
        CHOICES.put(id, rgb);
        PENDING.put(id, rgb);
        if (writeScheduled) return;
        writeScheduled = true;
        WRITER.schedule(LunarPlusAccountPreferences::flush, 100, TimeUnit.MILLISECONDS);
    }

    private static void flush() {
        Map<UUID, Integer> pending;
        synchronized (LunarPlusAccountPreferences.class) {
            pending = new HashMap<UUID, Integer>(PENDING);
            PENDING.clear(); writeScheduled = false;
        }
        for (Map.Entry<UUID, Integer> entry : pending.entrySet()) {
            Path temporary = null;
            try {
                Path directory = directory();
                Files.createDirectories(directory);
                temporary = Files.createTempFile(directory, entry.getKey().toString(), ".tmp");
                Files.write(temporary, (entry.getValue().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                try { Files.move(temporary, file(entry.getKey()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file(entry.getKey()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception error) {
                System.err.println("[gomod] Could not save the account's Lunar+ preference: " + error.getClass().getSimpleName());
            } finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }
}
