package me.napim.gomod.updater;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Standalone, zero-dependency swapper invoked via {@code javaw -cp <stagedJar>} from a JVM
 * shutdown hook after Minecraft begins exiting. Waits for Windows to release the file lock on
 * the old mod JAR, removes it, and atomically places the verified new JAR in its folder so
 * duplicate mod JARs are never left behind.
 */
public final class GomodUpdateSwapper {

    private static final long DEFAULT_TIMEOUT_MS = 45_000L;
    private static final long DEFAULT_POLL_MS = 250L;

    private GomodUpdateSwapper() {}

    public static void main(String[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        File oldJar = new File(args[0]);
        File targetNewJar = new File(args[1]);
        File stagedNewJar = new File(args[2]);
        String expectedSha256 = args.length >= 4 && args[3] != null ? args[3].trim() : "";

        boolean ok = performSwap(oldJar, targetNewJar, stagedNewJar, expectedSha256,
                DEFAULT_TIMEOUT_MS, DEFAULT_POLL_MS);
        writeStatus(stagedNewJar.getParentFile(),
                (ok ? "OK" : "FAILED") + " old=" + oldJar.getName() + " new=" + targetNewJar.getName());
    }

    /**
     * Performs the lock-aware swap. Exposed package/public for unit testing without spawning a JVM.
     */
    public static boolean performSwap(File oldJar,
                                      File targetNewJar,
                                      File stagedNewJar,
                                      String expectedSha256,
                                      long timeoutMs,
                                      long pollMs) {
        if (oldJar == null || targetNewJar == null || stagedNewJar == null) {
            return false;
        }
        if (!stagedNewJar.isFile() || stagedNewJar.length() <= 0L) {
            return false;
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = sha256Hex(stagedNewJar);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                return false;
            }
        }

        File targetDir = targetNewJar.getParentFile();
        if (targetDir == null) {
            return false;
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return false;
        }

        File stagingDir = stagedNewJar.getParentFile();
        File backupFile = stagingDir != null
                ? new File(stagingDir, oldJar.getName() + ".bak")
                : new File(targetDir, oldJar.getName() + ".gomod-bak");

        boolean hadOldJar = oldJar.exists();
        if (hadOldJar) {
            if (!waitAndRemoveOldJar(oldJar, backupFile, Math.max(0L, timeoutMs), Math.max(25L, pollMs))) {
                return false;
            }
        }

        File tempDest = new File(targetDir, targetNewJar.getName() + ".gomod-update.tmp");
        try {
            copyFile(stagedNewJar, tempDest);
            if (tempDest.length() != stagedNewJar.length()) {
                throw new IOException("Copied size mismatch");
            }
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                String actual = sha256Hex(tempDest);
                if (!expectedSha256.equalsIgnoreCase(actual)) {
                    throw new IOException("Copied SHA-256 mismatch");
                }
            }
            moveReplace(tempDest, targetNewJar);
            if (backupFile.exists()) {
                backupFile.delete();
            }
            return true;
        } catch (Throwable t) {
            try {
                if (tempDest.exists()) {
                    tempDest.delete();
                }
            } catch (Throwable ignored) {}
            if (hadOldJar && backupFile.isFile() && !oldJar.exists()) {
                try {
                    copyFile(backupFile, oldJar);
                } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private static boolean waitAndRemoveOldJar(File oldJar, File backupFile, long timeoutMs, long pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean backedUp = false;
        while (true) {
            if (!oldJar.exists()) {
                return true;
            }
            if (!backedUp) {
                try {
                    if (backupFile.getParentFile() != null) {
                        backupFile.getParentFile().mkdirs();
                    }
                    copyFile(oldJar, backupFile);
                    backedUp = backupFile.isFile() && backupFile.length() == oldJar.length();
                } catch (Throwable ignored) {}
            }
            try {
                if (Files.deleteIfExists(oldJar.toPath())) {
                    return true;
                }
            } catch (Throwable ignored) {}
            if (System.currentTimeMillis() >= deadline) {
                return !oldJar.exists();
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return !oldJar.exists();
            }
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    private static void moveReplace(File source, File dest) throws IOException {
        try {
            Files.move(source.toPath(), dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String sha256Hex(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[32768];
            try (FileInputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            char[] hex = "0123456789abcdef".toCharArray();
            byte[] bytes = digest.digest();
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xff;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0x0f];
            }
            return new String(out);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeStatus(File dir, String text) {
        if (dir == null) {
            return;
        }
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "last-swap.txt")), StandardCharsets.UTF_8)) {
                writer.write(text);
                writer.write('\n');
            }
        } catch (Throwable ignored) {}
    }
}
