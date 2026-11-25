package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import me.napim.gomod.updater.GomodUpdateSwapper;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.util.EnumChatFormatting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-updater for gomod123 (Forge 1.8.9 and Lunar/Weave).
 * Checks GitHub Releases ({@code Napim/gomod123}) and/or the gomod relay, downloads and verifies
 * the matching loader JAR on user opt-in, and schedules a headless swap on JVM shutdown.
 */
public final class GomodAutoUpdater {

    public static final String GITHUB_REPO = "Napim/gomod123";
    public static final String GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    public static final String GITHUB_RELEASES_PAGE_URL =
            "https://github.com/" + GITHUB_REPO + "/releases/latest";
    public static final String DEFAULT_CHANGELOG_URL =
            "https://discord.com/channels/1254469815918133318/1506276042980065410";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_REDIRECTS = 6;
    private static final long MIN_GITHUB_CHECK_INTERVAL_MS = 60_000L;

    public enum State {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        READY_ON_EXIT,
        ERROR
    }

    public static final class ReleaseInfo {
        public final String version;
        public final String downloadUrl;
        public final String assetName;
        public final String sha256;
        public final long expectedSize;
        public final String changelogUrl;
        public final String releaseNotes;
        public final String source;

        public ReleaseInfo(String version,
                           String downloadUrl,
                           String assetName,
                           String sha256,
                           long expectedSize,
                           String changelogUrl,
                           String releaseNotes,
                           String source) {
            this.version = safe(version);
            this.downloadUrl = safe(downloadUrl);
            this.assetName = safe(assetName);
            this.sha256 = normalizeSha256(sha256);
            this.expectedSize = expectedSize;
            this.changelogUrl = safe(changelogUrl);
            this.releaseNotes = releaseNotes == null ? "" : releaseNotes.trim();
            this.source = safe(source);
        }

        public boolean hasDirectJarDownload() {
            return !downloadUrl.isEmpty() && (downloadUrl.startsWith("https://") || downloadUrl.startsWith("http://"));
        }

        public String effectiveChangelogUrl() {
            return !changelogUrl.isEmpty() ? changelogUrl : GITHUB_RELEASES_PAGE_URL;
        }
    }

    private static final Object LOCK = new Object();
    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean downloadInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    private static volatile State state = State.IDLE;
    private static volatile ReleaseInfo latestRelease;
    private static volatile long lastGitHubCheckMs;
    private static volatile boolean initialGitHubCheckCompleted;
    private static volatile int downloadPercent;
    private static volatile long downloadedBytes;
    private static volatile long totalBytes = -1L;
    private static volatile String statusMessage = "";
    private static volatile String lastError = "";

    private static volatile File stagedJarFile;
    private static volatile File stagedTargetJarFile;
    private static volatile File stagedOldJarFile;
    private static volatile String stagedExpectedSha256 = "";
    private static volatile String stagedVersion = "";

    private GomodAutoUpdater() {}

    public static State getState() {
        return state;
    }

    public static ReleaseInfo getLatestRelease() {
        return latestRelease;
    }

    public static int getDownloadPercent() {
        return downloadPercent;
    }

    public static long getDownloadedBytes() {
        return downloadedBytes;
    }

    public static long getTotalBytes() {
        return totalBytes;
    }

    public static String getStatusMessage() {
        return statusMessage;
    }

    public static String getLastError() {
        return lastError;
    }

    public static String getStagedVersion() {
        return stagedVersion;
    }

    public static boolean isUpdateAvailable() {
        ReleaseInfo info = latestRelease;
        return info != null && !info.version.isEmpty() && GomodVersion.isOlderThan(info.version);
    }

    public static boolean isStagedForExit() {
        return state == State.READY_ON_EXIT && stagedJarFile != null && stagedJarFile.isFile();
    }

    public static boolean hasCompletedInitialGitHubCheck() {
        return initialGitHubCheckCompleted;
    }

    public static boolean isWeaveLoader() {
        return GomodFixes.isWeaveRuntime();
    }

    public static String getLoaderDisplayName() {
        return isWeaveLoader() ? "Lunar / Weave" : "Forge 1.8.9";
    }

    public static boolean isDevOrInternalBuild() {
        return !GomodReleaseBuild.PUBLIC_RELEASE;
    }

    public static File getUpdatesDir() {
        return new File(GomodPaths.getGomod123Dir(), "updates");
    }

    /** Cleans up old staged JARs/backups from previous launches. Safe to call once on startup. */
    public static void cleanupStaleCache() {
        try {
            File dir = getUpdatesDir();
            if (!dir.isDirectory()) {
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".jar") || name.endsWith(".part") || name.endsWith(".bak")) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Resolves the currently running gomod JAR file on disk, or {@code null} if running from an
     * unpacked classes directory (IDE/dev).
     * Tries CodeSource first, then classpath resource URLs (Weave/Lunar often has null CodeSource),
     * then a unique version-matched JAR scan in the active loader's mods folder.
     */
    public static File resolveCurrentModJar() {
        File fromCodeSource = resolveJarFromCodeSource();
        if (fromCodeSource != null) {
            return fromCodeSource;
        }
        File fromPropsResource = resolveJarFromResource("gomod.version.properties");
        if (fromPropsResource != null) {
            return fromPropsResource;
        }
        File fromClassResource = resolveJarFromResource("me/napim/gomod/features/GomodAutoUpdater.class");
        if (fromClassResource != null) {
            return fromClassResource;
        }
        File modsDir = isWeaveLoader()
                ? new File(new File(System.getProperty("user.home", "."), ".weave"), "mods")
                : new File(GomodPaths.getMinecraftGameDir(), "mods");
        return findUniqueModJarInDir(modsDir, GomodVersion.get());
    }

    private static File resolveJarFromCodeSource() {
        try {
            CodeSource source = GomodAutoUpdater.class.getProtectionDomain() == null
                    ? null : GomodAutoUpdater.class.getProtectionDomain().getCodeSource();
            return extractJarFileFromUrl(source == null ? null : source.getLocation());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File resolveJarFromResource(String resourcePath) {
        try {
            ClassLoader cl = GomodAutoUpdater.class.getClassLoader();
            if (cl == null) {
                return null;
            }
            return extractJarFileFromUrl(cl.getResource(resourcePath));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static File extractJarFileFromUrl(URL location) {
        if (location == null) {
            return null;
        }
        try {
            URL resolved = location;
            if ("jar".equalsIgnoreCase(resolved.getProtocol())) {
                URLConnection conn = resolved.openConnection();
                if (conn instanceof JarURLConnection) {
                    resolved = ((JarURLConnection) conn).getJarFileURL();
                } else {
                    String spec = resolved.getFile();
                    int bang = spec.indexOf("!/");
                    if (bang > 0) {
                        resolved = new URL(spec.substring(0, bang));
                    }
                }
            }
            if (!"file".equalsIgnoreCase(resolved.getProtocol())) {
                return null;
            }
            File file = new File(resolved.toURI()).getAbsoluteFile();
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            return file;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Scans {@code modsDir} for a unique gomod JAR matching {@code expectedVersion}.
     * Fails closed (returns {@code null}) if zero or multiple matching JARs exist.
     */
    static File findUniqueModJarInDir(File modsDir, String expectedVersion) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return null;
        }
        File[] files = modsDir.listFiles();
        if (files == null) {
            return null;
        }
        File exactMatch = null;
        int exactCount = 0;
        File anyGomodMatch = null;
        int anyGomodCount = 0;
        for (File f : files) {
            if (f == null || !f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            String ver = readGomodVersionFromJar(f);
            if (ver == null) {
                continue;
            }
            anyGomodMatch = f.getAbsoluteFile();
            anyGomodCount++;
            if (expectedVersion != null && !expectedVersion.isEmpty() && expectedVersion.equals(ver)) {
                exactMatch = f.getAbsoluteFile();
                exactCount++;
            }
        }
        if (exactCount == 1) {
            return exactMatch;
        }
        if (exactCount == 0 && anyGomodCount == 1) {
            return anyGomodMatch;
        }
        return null;
    }

    private static String readGomodVersionFromJar(File jarFile) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            if (zip.getEntry("me/napim/gomod/Main.class") == null) {
                return null;
            }
            ZipEntry entry = zip.getEntry("gomod.version.properties");
            if (entry == null) {
                return null;
            }
            java.util.Properties props = new java.util.Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            return props.getProperty("version", "").trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Checks GitHub Releases in a background thread. */
    public static void checkForUpdatesAsync(final boolean force, final boolean notifyChatIfFound) {
        long now = System.currentTimeMillis();
        if (!force && lastGitHubCheckMs > 0L && (now - lastGitHubCheckMs) < MIN_GITHUB_CHECK_INTERVAL_MS) {
            if (notifyChatIfFound && isUpdateAvailable()) {
                GomodUpdateChecker.tryNotifyInWorld();
            }
            return;
        }
        if (!checkInFlight.compareAndSet(false, true)) {
            return;
        }
        if (state != State.DOWNLOADING && state != State.READY_ON_EXIT) {
            state = State.CHECKING;
            statusMessage = "Checking GitHub Releases...";
            lastError = "";
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lastGitHubCheckMs = System.currentTimeMillis();
                    JsonObject json = fetchJsonObject(GITHUB_RELEASES_API_URL);
                    ReleaseInfo parsed = parseGitHubRelease(json, isWeaveLoader());
                    initialGitHubCheckCompleted = true;
                    if (parsed != null) {
                        mergeCandidateRelease(parsed, notifyChatIfFound);
                    } else if (state == State.CHECKING) {
                        state = isUpdateAvailable() ? State.AVAILABLE : State.UP_TO_DATE;
                        statusMessage = isUpdateAvailable()
                                ? "Update " + latestRelease.version + " available"
                                : "You are on the latest version (" + GomodVersion.get() + ")";
                        if (notifyChatIfFound && isUpdateAvailable()) {
                            GomodUpdateChecker.tryNotifyInWorld();
                        }
                    }
                } catch (Throwable ex) {
                    initialGitHubCheckCompleted = true;
                    if (state == State.CHECKING) {
                        if (isUpdateAvailable()) {
                            state = State.AVAILABLE;
                            statusMessage = "Update " + latestRelease.version + " available";
                            if (notifyChatIfFound) {
                                GomodUpdateChecker.tryNotifyInWorld();
                            }
                        } else {
                            state = State.ERROR;
                            lastError = describeError(ex);
                            statusMessage = "Update check failed";
                        }
                    }
                } finally {
                    checkInFlight.set(false);
                }
            }
        }, "GomodUpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    /** Merges release metadata advertised by the gomod relay's {@code modRelease} payload. */
    public static void onRelayRelease(JsonObject rel) {
        ReleaseInfo parsed = parseRelayRelease(rel, isWeaveLoader());
        if (parsed != null) {
            mergeCandidateRelease(parsed, true);
        }
    }

    private static void mergeCandidateRelease(ReleaseInfo candidate, boolean triggerNotifications) {
        if (candidate == null || candidate.version.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ReleaseInfo existing = latestRelease;
            if (existing == null) {
                latestRelease = candidate;
            } else {
                int cmp = GomodVersion.compare(existing.version, candidate.version);
                if (cmp < 0) {
                    latestRelease = candidate;
                } else if (cmp == 0) {
                    // Combine fields: prefer GitHub direct asset/sha/notes when available
                    String dl = !existing.downloadUrl.isEmpty() ? existing.downloadUrl : candidate.downloadUrl;
                    String name = !existing.assetName.isEmpty() ? existing.assetName : candidate.assetName;
                    String sha = !existing.sha256.isEmpty() ? existing.sha256 : candidate.sha256;
                    long sz = existing.expectedSize > 0 ? existing.expectedSize : candidate.expectedSize;
                    String cl = !existing.changelogUrl.isEmpty() ? existing.changelogUrl : candidate.changelogUrl;
                    String notes = !existing.releaseNotes.isEmpty() ? existing.releaseNotes : candidate.releaseNotes;
                    if ("GitHub".equals(candidate.source) && !candidate.downloadUrl.isEmpty()) {
                        dl = candidate.downloadUrl;
                        name = !candidate.assetName.isEmpty() ? candidate.assetName : name;
                        if (!candidate.sha256.isEmpty()) sha = candidate.sha256;
                        if (candidate.expectedSize > 0) sz = candidate.expectedSize;
                        if (!candidate.changelogUrl.isEmpty()) cl = candidate.changelogUrl;
                        if (!candidate.releaseNotes.isEmpty()) notes = candidate.releaseNotes;
                    }
                    latestRelease = new ReleaseInfo(existing.version, dl, name, sha, sz, cl, notes, existing.source);
                }
            }

            if (state != State.DOWNLOADING && state != State.READY_ON_EXIT) {
                if (isUpdateAvailable()) {
                    state = State.AVAILABLE;
                    statusMessage = "Update " + latestRelease.version + " available";
                } else {
                    state = State.UP_TO_DATE;
                    statusMessage = "Up to date (" + GomodVersion.get() + ")";
                }
            }
        }

        if (triggerNotifications && isUpdateAvailable()) {
            if (ConfigManager.isAutoUpdateEnabled() && !isDevOrInternalBuild()
                    && latestRelease != null && latestRelease.hasDirectJarDownload()
                    && !isStagedForExit()) {
                startDownloadAndStageAsync(true);
            } else {
                GomodUpdateChecker.tryNotifyInWorld();
            }
        }
    }

    /** Downloads, verifies, and stages the latest release to swap cleanly when Minecraft closes. */
    public static void startDownloadAndStageAsync(final boolean automatic) {
        final ReleaseInfo info = latestRelease;
        if (info == null || !info.hasDirectJarDownload()) {
            state = State.ERROR;
            lastError = "No direct JAR download URL available for " + getLoaderDisplayName();
            statusMessage = "Download unavailable";
            return;
        }
        if (automatic && isDevOrInternalBuild()) {
            return;
        }
        final File currentJar = resolveCurrentModJar();
        if (currentJar == null) {
            state = State.ERROR;
            lastError = "Cannot auto-update outside a packaged JAR (IDE/dev environment)";
            statusMessage = "Manual update required";
            return;
        }
        if (!downloadInFlight.compareAndSet(false, true)) {
            return;
        }

        state = State.DOWNLOADING;
        downloadPercent = 0;
        downloadedBytes = 0L;
        totalBytes = info.expectedSize;
        lastError = "";
        statusMessage = "Downloading gomod " + info.version + "...";

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File partFile = null;
                try {
                    File updatesDir = getUpdatesDir();
                    if (!updatesDir.exists() && !updatesDir.mkdirs()) {
                        throw new IOException("Could not create updates folder: " + updatesDir.getAbsolutePath());
                    }

                    String targetFileName = chooseTargetFileName(info, isWeaveLoader());
                    partFile = new File(updatesDir, targetFileName + ".part");
                    File stagedFile = new File(updatesDir, targetFileName);

                    downloadWithRedirects(info.downloadUrl, partFile, info.expectedSize);

                    statusMessage = "Verifying downloaded JAR...";
                    verifyDownloadedJar(partFile, info.expectedSize, info.sha256);

                    Files.move(partFile.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    String verifiedSha = !info.sha256.isEmpty()
                            ? info.sha256
                            : GomodUpdateSwapper.sha256Hex(stagedFile);

                    File targetJar = new File(currentJar.getParentFile(), targetFileName);
                    synchronized (LOCK) {
                        stagedOldJarFile = currentJar;
                        stagedTargetJarFile = targetJar;
                        stagedJarFile = stagedFile;
                        stagedExpectedSha256 = verifiedSha;
                        stagedVersion = info.version;
                        downloadPercent = 100;
                        state = State.READY_ON_EXIT;
                        statusMessage = "Ready! Will install " + info.version + " when you close Minecraft.";
                    }
                    registerShutdownHookOnce();
                    ConfigManager.setLastNotifiedModVersion(info.version);

                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + "gomod " + EnumChatFormatting.WHITE + info.version
                            + EnumChatFormatting.GREEN + " downloaded and verified! It will install automatically when you close Minecraft.");
                } catch (Throwable ex) {
                    if (partFile != null && partFile.exists()) {
                        partFile.delete();
                    }
                    state = State.ERROR;
                    lastError = describeError(ex);
                    statusMessage = "Update failed";
                    if (!automatic) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Update failed: " + lastError);
                    }
                } finally {
                    downloadInFlight.set(false);
                }
            }
        }, "GomodUpdateDownload");
        t.setDaemon(true);
        t.start();
    }

    private static void registerShutdownHookOnce() {
        if (!shutdownHookRegistered.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                launchSwapperHelper();
            }
        }, "GomodUpdateShutdownHook"));
    }

    private static void launchSwapperHelper() {
        File oldJar;
        File targetJar;
        File stagedJar;
        String sha;
        synchronized (LOCK) {
            oldJar = stagedOldJarFile;
            targetJar = stagedTargetJarFile;
            stagedJar = stagedJarFile;
            sha = stagedExpectedSha256;
        }
        if (oldJar == null || targetJar == null || stagedJar == null || !stagedJar.isFile()) {
            return;
        }
        try {
            File swapperCpDir = extractSwapperClass(stagedJar.getParentFile());
            String javaExec = resolveHeadlessJavaExecutable();
            ProcessBuilder pb = new ProcessBuilder(
                    javaExec,
                    "-cp", swapperCpDir.getAbsolutePath(),
                    GomodUpdateSwapper.class.getName(),
                    oldJar.getAbsolutePath(),
                    targetJar.getAbsolutePath(),
                    stagedJar.getAbsolutePath(),
                    sha == null ? "" : sha
            );
            pb.directory(stagedJar.getParentFile());
            pb.start();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Extracts {@link GomodUpdateSwapper}'s own bytecode into {@code <updatesDir>/swapper/...} so the
     * detached swapper JVM neither locks the old/staged JARs nor depends on the target JAR containing
     * {@code GomodUpdateSwapper}.
     */
    static File extractSwapperClass(File updatesDir) throws IOException {
        File cpRoot = new File(updatesDir, "swapper");
        String relPath = GomodUpdateSwapper.class.getName().replace('.', '/') + ".class";
        File classFile = new File(cpRoot, relPath);
        File parent = classFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create swapper directory: " + parent.getAbsolutePath());
        }
        try (InputStream in = GomodUpdateSwapper.class.getClassLoader().getResourceAsStream(relPath)) {
            if (in == null) {
                throw new IOException("Could not locate " + relPath + " on classpath");
            }
            Files.copy(in, classFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return cpRoot;
    }

    static String resolveHeadlessJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!javaHome.isEmpty()) {
            File bin = new File(javaHome, "bin");
            if (isWindows) {
                File javaw = new File(bin, "javaw.exe");
                if (javaw.isFile()) {
                    return javaw.getAbsolutePath();
                }
                File java = new File(bin, "java.exe");
                if (java.isFile()) {
                    return java.getAbsolutePath();
                }
            } else {
                File java = new File(bin, "java");
                if (java.isFile()) {
                    return java.getAbsolutePath();
                }
            }
        }
        return isWindows ? "javaw" : "java";
    }

    /** Parses a GitHub Releases API JSON object ({@code /releases/latest}). */
    public static ReleaseInfo parseGitHubRelease(JsonObject release, boolean weaveRuntime) {
        if (release == null) {
            return null;
        }
        if (jsonBoolean(release, "draft") || jsonBoolean(release, "prerelease")) {
            return null;
        }
        String tag = normalizeVersionTag(jsonString(release, "tag_name"));
        if (tag.isEmpty()) {
            return null;
        }
        String htmlUrl = jsonString(release, "html_url");
        String body = jsonString(release, "body");

        JsonObject bestAsset = null;
        int bestScore = Integer.MIN_VALUE;
        if (release.has("assets") && release.get("assets").isJsonArray()) {
            JsonArray assets = release.getAsJsonArray("assets");
            for (JsonElement el : assets) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject asset = el.getAsJsonObject();
                String name = jsonString(asset, "name");
                String dl = jsonString(asset, "browser_download_url");
                if (name.isEmpty() || dl.isEmpty()) {
                    continue;
                }
                int score = scoreAssetForLoader(name, weaveRuntime);
                if (score > bestScore) {
                    bestScore = score;
                    bestAsset = asset;
                }
            }
        }

        String dlUrl = bestAsset != null ? jsonString(bestAsset, "browser_download_url") : "";
        String assetName = bestAsset != null ? jsonString(bestAsset, "name") : "";
        String sha256 = bestAsset != null ? jsonString(bestAsset, "digest") : "";
        long size = bestAsset != null ? jsonLong(bestAsset, "size", -1L) : -1L;

        return new ReleaseInfo(tag, dlUrl, assetName, sha256, size, htmlUrl, body, "GitHub");
    }

    /** Parses the gomod relay's {@code modRelease} JSON object. */
    public static ReleaseInfo parseRelayRelease(JsonObject rel, boolean weaveRuntime) {
        if (rel == null) {
            return null;
        }
        String version = normalizeVersionTag(jsonString(rel, "version"));
        if (version.isEmpty()) {
            return null;
        }
        String dlUrl = weaveRuntime
                ? firstNonEmpty(jsonString(rel, "lunarDownloadUrl"), jsonString(rel, "downloadUrl"))
                : firstNonEmpty(jsonString(rel, "forgeDownloadUrl"), jsonString(rel, "downloadUrl"));
        String sha256 = weaveRuntime
                ? firstNonEmpty(jsonString(rel, "lunarSha256"), jsonString(rel, "sha256"))
                : firstNonEmpty(jsonString(rel, "forgeSha256"), jsonString(rel, "sha256"));
        String changelogUrl = jsonString(rel, "changelogUrl");
        String assetName = extractJarFileNameFromUrl(dlUrl);
        return new ReleaseInfo(version, dlUrl, assetName, sha256, -1L, changelogUrl, "", "Relay");
    }

    static int scoreAssetForLoader(String assetName, boolean weaveRuntime) {
        String lower = assetName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) {
            return Integer.MIN_VALUE;
        }
        if (lower.contains("sources") || lower.contains("javadoc") || lower.contains("dev")) {
            return Integer.MIN_VALUE;
        }
        boolean isLunar = lower.contains("lunar") || lower.contains("weave");
        boolean isForge = lower.contains("forge");
        if (weaveRuntime) {
            if (isLunar && !isForge) return 100;
            if (!isForge) return 20;
            return Integer.MIN_VALUE;
        } else {
            if (isForge && !isLunar) return 100;
            if (!isLunar) return 20;
            return Integer.MIN_VALUE;
        }
    }

    static String chooseTargetFileName(ReleaseInfo info, boolean weaveRuntime) {
        String name = info != null ? safe(info.assetName) : "";
        if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(".jar")
                && !name.contains("/") && !name.contains("\\")) {
            return name;
        }
        String fromUrl = info != null ? extractJarFileNameFromUrl(info.downloadUrl) : "";
        if (!fromUrl.isEmpty()) {
            return fromUrl;
        }
        String flavor = weaveRuntime ? "lunar" : "forge";
        String ver = info != null && !info.version.isEmpty() ? info.version : "latest";
        return "gomod123-" + flavor + "-" + ver + ".jar";
    }

    static String normalizeVersionTag(String raw) {
        String s = safe(raw);
        if ((s.startsWith("v") || s.startsWith("V")) && s.length() > 1 && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        return s;
    }

    static String normalizeSha256(String raw) {
        String s = safe(raw).toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256:")) {
            s = s.substring("sha256:".length()).trim();
        }
        return s.length() == 64 && s.matches("[0-9a-f]{64}") ? s : "";
    }

    static void verifyDownloadedJar(File file, long expectedSize, String expectedSha256) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            throw new IOException("Downloaded file is empty");
        }
        if (expectedSize > 0L && file.length() != expectedSize) {
            throw new IOException("Size mismatch (expected " + expectedSize + ", got " + file.length() + ")");
        }
        String normSha = normalizeSha256(expectedSha256);
        if (!normSha.isEmpty()) {
            String actual = GomodUpdateSwapper.sha256Hex(file);
            if (!normSha.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 verification failed");
            }
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry versionProps = zip.getEntry("gomod.version.properties");
            if (versionProps == null) {
                throw new IOException("Archive is not a valid gomod123 JAR (missing gomod.version.properties)");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Corrupt JAR archive: " + t.getMessage(), t);
        }
    }

    private static JsonObject fetchJsonObject(String urlStr) throws IOException {
        HttpURLConnection conn = openWithRedirects(urlStr, "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            JsonElement el = new JsonParser().parse(sb.toString());
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadWithRedirects(String urlStr, File dest, long fallbackTotalSize) throws IOException {
        HttpURLConnection conn = openWithRedirects(urlStr, "application/octet-stream");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Download HTTP " + code);
            }
            long contentLength = conn.getContentLengthLong();
            long expectedTotal = contentLength > 0L ? contentLength : fallbackTotalSize;
            totalBytes = expectedTotal;
            downloadedBytes = 0L;
            downloadPercent = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[32768];
                int n;
                long totalRead = 0L;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    totalRead += n;
                    downloadedBytes = totalRead;
                    if (expectedTotal > 0L) {
                        downloadPercent = (int) Math.min(99L, (totalRead * 100L) / expectedTotal);
                    }
                }
                out.getFD().sync();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openWithRedirects(String urlStr, String accept) throws IOException {
        String current = urlStr;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "gomod123/" + GomodVersion.get());
            if (accept != null && !accept.isEmpty()) {
                conn.setRequestProperty("Accept", accept);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = new URL(url, location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static String extractJarFileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        int slash = clean.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= clean.length()) {
            return "";
        }
        String candidate = clean.substring(slash + 1).trim();
        return candidate.toLowerCase(Locale.ROOT).endsWith(".jar") ? candidate : "";
    }

    private static String describeError(Throwable ex) {
        if (ex == null) {
            return "Unknown error";
        }
        String msg = ex.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? ex.getClass().getSimpleName() : msg.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : safe(b);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String jsonString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean jsonBoolean(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return false;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long jsonLong(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return def;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Throwable ignored) {
            return def;
        }
    }
}
