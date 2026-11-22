package me.napim.gomod.util;

import net.minecraft.client.Minecraft;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Native OS file/folder dialogs for gomod GUIs.
 * <p>
 * On Windows, PNG import uses PowerShell + {@code System.Windows.Forms.OpenFileDialog} (same native
 * picker as Explorer). AWT {@link FileDialog} is avoided on Windows because it can open inside the
 * mod JAR when the process cwd is wrong. Folder open uses {@code explorer.exe} like Patcher's
 * {@code UDesktop.open}.
 */
public final class GomodFileDialogs {

    private static final long DIALOG_WAIT_MINUTES = 10L;
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private GomodFileDialogs() {
    }

    /**
     * Safe from any thread, including the Minecraft client thread.
     */
    public static File pickOpenPngFile(String title, File initialDir) {
        if (IS_WINDOWS) {
            return pickOpenPngFileWithWorker(title, initialDir, true);
        }
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return pickOpenPngFileWithWorker(title, initialDir, false);
    }

    private static File pickOpenPngFileWithWorker(String title, File initialDir, boolean windowsNative) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.isCallingFromMinecraftThread()) {
                final File[] picked = new File[1];
                final CountDownLatch latch = new CountDownLatch(1);
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            picked[0] = pickOpenPngFileBlocking(title, initialDir, windowsNative);
                        } finally {
                            latch.countDown();
                        }
                    }
                }, "gomod-file-dialog");
                worker.setDaemon(true);
                worker.start();
                try {
                    latch.await(DIALOG_WAIT_MINUTES, TimeUnit.MINUTES);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return picked[0];
            }
        } catch (Throwable ignored) {
        }
        return pickOpenPngFileBlocking(title, initialDir, windowsNative);
    }

    private static File pickOpenPngFileBlocking(String title, File initialDir, boolean windowsNative) {
        if (windowsNative) {
            // Windows only: native OpenFileDialog — never fall back to AWT (opens the mod JAR).
            return pickOpenPngFileWindows(title, initialDir);
        }
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        if (EventQueue.isDispatchThread()) {
            return pickOpenPngFileAwt(title, initialDir);
        }
        final File[] picked = new File[1];
        try {
            EventQueue.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    picked[0] = pickOpenPngFileAwt(title, initialDir);
                }
            });
        } catch (Throwable ignored) {
        }
        return picked[0];
    }

    private static File pickOpenPngFileWindows(String title, File initialDir) {
        File start = resolveInitialDirectory(initialDir);
        String dialogTitle = title == null ? "Choose PNG" : title;
        String startPath = "";
        if (start != null) {
            try {
                startPath = start.getCanonicalPath();
            } catch (Throwable ignored) {
                startPath = start.getAbsolutePath();
            }
        }
        String escapedTitle = escapePowerShellSingleQuoted(dialogTitle);
        String escapedDir = escapePowerShellSingleQuoted(startPath);
        StringBuilder ps = new StringBuilder();
        ps.append("Add-Type -AssemblyName System.Windows.Forms; ");
        ps.append("$d = New-Object System.Windows.Forms.OpenFileDialog; ");
        ps.append("$d.Filter = 'PNG files (*.png)|*.png'; ");
        ps.append("$d.Title = '").append(escapedTitle).append("'; ");
        if (!startPath.isEmpty()) {
            ps.append("$d.InitialDirectory = '").append(escapedDir).append("'; ");
        }
        ps.append("if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $d.FileName }");
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-STA", "-Command", ps.toString()
            ).redirectErrorStream(true).start();
            String line = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String read;
                while ((read = reader.readLine()) != null) {
                    read = read.trim();
                    if (read.isEmpty()) {
                        continue;
                    }
                    File candidate = new File(read);
                    if (candidate.isFile() && read.toLowerCase(Locale.ROOT).endsWith(".png")) {
                        line = read;
                    }
                }
            }
            if (!process.waitFor(DIALOG_WAIT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return null;
            }
            if (line == null || line.isEmpty()) {
                return null;
            }
            File file = new File(line);
            return file.isFile() ? file : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String escapePowerShellSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private static File pickOpenPngFileAwt(String title, File initialDir) {
        try {
            FileDialog fd = new FileDialog((Frame) null, title == null ? "Choose PNG" : title, FileDialog.LOAD);
            File start = resolveInitialDirectory(initialDir);
            if (start != null) {
                try {
                    fd.setDirectory(start.getCanonicalPath());
                } catch (Throwable ignored) {
                    fd.setDirectory(start.getAbsolutePath());
                }
            }
            fd.setFilenameFilter(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name != null && name.toLowerCase(Locale.ROOT).endsWith(".png");
                }
            });
            fd.setVisible(true);
            String fileName = fd.getFile();
            String dir = fd.getDirectory();
            if (fileName != null && dir != null) {
                File file = new File(dir, fileName);
                if (file.isFile()) {
                    return file;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Opens a folder in the OS file manager (Patcher screenshot FOLDER / {@code UDesktop.open} style). */
    public static boolean openFolderInExplorer(File folder) {
        if (folder == null) {
            return false;
        }
        try {
            if (!folder.isDirectory()) {
                folder.mkdirs();
            }
            if (!folder.isDirectory()) {
                return false;
            }
            String path = folder.getAbsolutePath();
            if (IS_WINDOWS) {
                new ProcessBuilder("explorer.exe", path).start();
                return true;
            }
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(folder);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static File resolveInitialDirectory(File preferred) {
        if (preferred != null) {
            if (preferred.isDirectory()) {
                return preferred;
            }
            File parent = preferred.getParentFile();
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.mcDataDir != null && mc.mcDataDir.isDirectory()) {
                return mc.mcDataDir;
            }
        } catch (Throwable ignored) {
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            File downloads = new File(home, "Downloads");
            if (downloads.isDirectory()) {
                return downloads;
            }
            File homeDir = new File(home);
            if (homeDir.isDirectory()) {
                return homeDir;
            }
        }
        return null;
    }
}
