package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Persists the selected number of kit leaderboard players to refresh. */
public final class GoStatsLeaderboardRefreshCountStore {
    private static final int[] OPTIONS = {50, 100, 150, 200, 250, 295, 350, 400, 500, 700, 900, 950};
    private static final int[] PUBLIC_OPTIONS = {50, 100, 150, 200, 250};
    private static final File SETTINGS_FILE = new File(GomodPaths.getGomod123Dir(), "leaderboard-refresh-count.txt");

    private GoStatsLeaderboardRefreshCountStore() {
    }

    public static synchronized int getRefreshCount() {
        if (!SETTINGS_FILE.isFile()) return defaultRefreshCount();
        try {
            return normalize(Integer.parseInt(new String(Files.readAllBytes(SETTINGS_FILE.toPath()),
                    StandardCharsets.UTF_8).trim()));
        } catch (Throwable ignored) {
            return defaultRefreshCount();
        }
    }

    public static synchronized void setRefreshCount(int count) {
        int normalized = normalize(count);
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
            System.err.println("[gostats leaderboard] Could not save batch refresh size: " + failure.getMessage());
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    public static int[] getOptions() {
        return (GomodReleaseBuild.PUBLIC_RELEASE ? PUBLIC_OPTIONS : OPTIONS).clone();
    }

    private static int normalize(int count) {
        // Preserve the user's old Top 300 choice as the new quota-buffered Top 295 option.
        if (!GomodReleaseBuild.PUBLIC_RELEASE && count == 300) return 295;
        for (int option : getOptions()) if (option == count) return option;
        return defaultRefreshCount();
    }

    private static int defaultRefreshCount() {
        return GomodReleaseBuild.PUBLIC_RELEASE ? 250 : 350;
    }
}
