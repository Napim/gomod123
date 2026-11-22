package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Persists the GoStats leaderboard's rows-per-page selection separately from the shared config writer. */
public final class GoStatsLeaderboardPageSizeStore {
    private static final File SETTINGS_FILE = new File(GomodPaths.getGomod123Dir(), "leaderboard-page-size.txt");

    private GoStatsLeaderboardPageSizeStore() {
    }

    public static synchronized int getPageSize() {
        if (!SETTINGS_FILE.isFile()) return 100;
        try {
            String value = new String(Files.readAllBytes(SETTINGS_FILE.toPath()), StandardCharsets.UTF_8).trim();
            return normalize(Integer.parseInt(value));
        } catch (Throwable ignored) {
            return 100;
        }
    }

    public static synchronized void setPageSize(int rows) {
        int normalized = normalize(rows);
        File parent = SETTINGS_FILE.getParentFile();
        File temporary = new File(parent, SETTINGS_FILE.getName() + ".tmp");
        try {
            Files.createDirectories(parent.toPath());
            Files.write(temporary.toPath(), String.valueOf(normalized).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary.toPath(), SETTINGS_FILE.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), SETTINGS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            System.err.println("[gostats leaderboard] Could not save rows-per-page: " + failure.getMessage());
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static int normalize(int rows) {
        return rows == 200 || rows == 500 ? rows : 100;
    }
}
