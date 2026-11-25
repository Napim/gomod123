package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.napim.gomod.updater.GomodUpdateSwapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class GomodAutoUpdaterTest {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Test
    public void parseGitHubReleasePicksMatchingLoaderAssetAndSha256() {
        JsonObject release = new JsonObject();
        release.addProperty("tag_name", "v0.3.6");
        release.addProperty("draft", false);
        release.addProperty("prerelease", false);
        release.addProperty("html_url", "https://github.com/Napim/gomod123/releases/tag/v0.3.6");
        release.addProperty("body", "- Added self-updater");

        JsonArray assets = new JsonArray();
        assets.add(makeAsset("gomod123-forge-0.3.6.jar",
                "https://github.com/Napim/gomod123/releases/download/v0.3.6/gomod123-forge-0.3.6.jar",
                12345L,
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assets.add(makeAsset("gomod123-lunar-0.3.6.jar",
                "https://github.com/Napim/gomod123/releases/download/v0.3.6/gomod123-lunar-0.3.6.jar",
                23456L,
                "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"));
        assets.add(makeAsset("gomod123-forge-0.3.6-sources.jar",
                "https://github.com/Napim/gomod123/releases/download/v0.3.6/gomod123-forge-0.3.6-sources.jar",
                1000L, ""));
        release.add("assets", assets);

        GomodAutoUpdater.ReleaseInfo forge = GomodAutoUpdater.parseGitHubRelease(release, false);
        assertNotNull(forge);
        assertEquals("0.3.6", forge.version);
        assertEquals("gomod123-forge-0.3.6.jar", forge.assetName);
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", forge.sha256);
        assertEquals(12345L, forge.expectedSize);

        GomodAutoUpdater.ReleaseInfo lunar = GomodAutoUpdater.parseGitHubRelease(release, true);
        assertNotNull(lunar);
        assertEquals("0.3.6", lunar.version);
        assertEquals("gomod123-lunar-0.3.6.jar", lunar.assetName);
        assertEquals("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", lunar.sha256);
        assertEquals(23456L, lunar.expectedSize);
    }

    @Test
    public void parseGitHubReleaseSkipsDraftAndPrerelease() {
        JsonObject draft = new JsonObject();
        draft.addProperty("tag_name", "v0.4.0");
        draft.addProperty("draft", true);
        assertNull(GomodAutoUpdater.parseGitHubRelease(draft, false));

        JsonObject pre = new JsonObject();
        pre.addProperty("tag_name", "v0.4.0-rc1");
        pre.addProperty("prerelease", true);
        assertNull(GomodAutoUpdater.parseGitHubRelease(pre, true));
    }

    @Test
    public void parseRelayReleaseSupportsLoaderSpecificAndFallbackUrls() {
        JsonObject rel = new JsonObject();
        rel.addProperty("version", "0.3.7");
        rel.addProperty("forgeDownloadUrl", "https://example.com/gomod123-forge-0.3.7.jar");
        rel.addProperty("lunarDownloadUrl", "https://example.com/gomod123-lunar-0.3.7.jar");
        rel.addProperty("downloadUrl", "https://example.com/fallback.jar");

        GomodAutoUpdater.ReleaseInfo forge = GomodAutoUpdater.parseRelayRelease(rel, false);
        GomodAutoUpdater.ReleaseInfo lunar = GomodAutoUpdater.parseRelayRelease(rel, true);
        assertNotNull(forge);
        assertNotNull(lunar);
        assertEquals("https://example.com/gomod123-forge-0.3.7.jar", forge.downloadUrl);
        assertEquals("gomod123-forge-0.3.7.jar", forge.assetName);
        assertEquals("https://example.com/gomod123-lunar-0.3.7.jar", lunar.downloadUrl);
        assertEquals("gomod123-lunar-0.3.7.jar", lunar.assetName);
    }

    @Test
    public void verifyDownloadedJarChecksZipEntrySizeAndSha256() throws Exception {
        File validJar = tempDir.newFile("valid.jar");
        writeFakeGomodJar(validJar, "0.3.6");
        String sha = GomodUpdateSwapper.sha256Hex(validJar);

        GomodAutoUpdater.verifyDownloadedJar(validJar, validJar.length(), sha);

        try {
            GomodAutoUpdater.verifyDownloadedJar(validJar, validJar.length() + 1, sha);
            fail("Expected IOException on size mismatch");
        } catch (IOException expected) {}

        try {
            GomodAutoUpdater.verifyDownloadedJar(
                    validJar, validJar.length(), "0000000000000000000000000000000000000000000000000000000000000000");
            fail("Expected IOException on SHA-256 mismatch");
        } catch (IOException expected) {}

        File htmlError = tempDir.newFile("error.jar");
        Files.write(htmlError.toPath(), "<html>404 Not Found</html>".getBytes(StandardCharsets.UTF_8));
        try {
            GomodAutoUpdater.verifyDownloadedJar(htmlError, -1L, "");
            fail("Expected IOException on non-ZIP file");
        } catch (IOException expected) {}
    }

    @Test
    public void swapperReplacesOldJarWithoutLeavingDuplicates() throws Exception {
        File modsDir = tempDir.newFolder("mods");
        File updatesDir = tempDir.newFolder("updates");

        File oldJar = new File(modsDir, "gomod123-lunar-0.3.4.jar");
        File newTargetJar = new File(modsDir, "gomod123-lunar-0.3.5.jar");
        File stagedJar = new File(updatesDir, "gomod123-lunar-0.3.5.jar");

        writeFakeGomodJar(oldJar, "0.3.4");
        writeFakeGomodJar(stagedJar, "0.3.5");
        String stagedSha = GomodUpdateSwapper.sha256Hex(stagedJar);

        boolean ok = GomodUpdateSwapper.performSwap(oldJar, newTargetJar, stagedJar, stagedSha, 5000L, 25L);
        assertTrue(ok);
        assertFalse("Old JAR must be removed to avoid duplicate mod loading", oldJar.exists());
        assertTrue("New JAR must be installed", newTargetJar.isFile());
        assertEquals(stagedSha, GomodUpdateSwapper.sha256Hex(newTargetJar));
    }

    @Test
    public void swapperReplacesSameFilenameInPlace() throws Exception {
        File modsDir = tempDir.newFolder("modsSame");
        File updatesDir = tempDir.newFolder("updatesSame");

        File modJar = new File(modsDir, "gomod123.jar");
        File stagedJar = new File(updatesDir, "gomod123.jar");

        writeFakeGomodJar(modJar, "0.3.4");
        writeFakeGomodJar(stagedJar, "0.3.5");
        String stagedSha = GomodUpdateSwapper.sha256Hex(stagedJar);

        boolean ok = GomodUpdateSwapper.performSwap(modJar, modJar, stagedJar, stagedSha, 5000L, 25L);
        assertTrue(ok);
        assertTrue(modJar.isFile());
        assertEquals(stagedSha, GomodUpdateSwapper.sha256Hex(modJar));
    }

    @Test
    public void swapperAbortsSafelyOnShaMismatch() throws Exception {
        File modsDir = tempDir.newFolder("modsFail");
        File updatesDir = tempDir.newFolder("updatesFail");

        File oldJar = new File(modsDir, "gomod123-forge-0.3.4.jar");
        File newTargetJar = new File(modsDir, "gomod123-forge-0.3.5.jar");
        File stagedJar = new File(updatesDir, "gomod123-forge-0.3.5.jar");

        writeFakeGomodJar(oldJar, "0.3.4");
        writeFakeGomodJar(stagedJar, "0.3.5");

        boolean ok = GomodUpdateSwapper.performSwap(
                oldJar, newTargetJar, stagedJar,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                2000L, 25L);
        assertFalse(ok);
        assertTrue("Old JAR must stay untouched when verification fails", oldJar.isFile());
        assertFalse("New JAR must not be installed when verification fails", newTargetJar.exists());
    }

    @Test
    public void extractedSwapperClassRunsInSeparateJvm() throws Exception {
        File modsDir = tempDir.newFolder("modsProc");
        File updatesDir = tempDir.newFolder("updatesProc");

        File oldJar = new File(modsDir, "gomod123-lunar-0.3.4.jar");
        File newTargetJar = new File(modsDir, "gomod123-lunar-1.0.0.jar");
        File stagedJar = new File(updatesDir, "gomod123-lunar-1.0.0.jar");

        writeFakeGomodJar(oldJar, "0.3.4");
        writeFakeGomodJar(stagedJar, "1.0.0");
        String stagedSha = GomodUpdateSwapper.sha256Hex(stagedJar);

        File cpDir = GomodAutoUpdater.extractSwapperClass(updatesDir);
        Process p = new ProcessBuilder(
                GomodAutoUpdater.resolveHeadlessJavaExecutable(),
                "-cp", cpDir.getAbsolutePath(),
                GomodUpdateSwapper.class.getName(),
                oldJar.getAbsolutePath(),
                newTargetJar.getAbsolutePath(),
                stagedJar.getAbsolutePath(),
                stagedSha
        ).directory(updatesDir).start();
        assertEquals(0, p.waitFor());
        assertFalse("Old JAR should be removed by spawned swapper", oldJar.exists());
        assertTrue("New JAR should be installed by spawned swapper", newTargetJar.isFile());
        assertEquals(stagedSha, GomodUpdateSwapper.sha256Hex(newTargetJar));
    }

    @Test
    public void findUniqueModJarInDirResolvesVersionMatchedWeaveJar() throws Exception {
        File modsDir = tempDir.newFolder("weaveMods");
        File otherMod = new File(modsDir, "other-mod.jar");
        File gomodJar = new File(modsDir, "gomod123-lunar-0.1.0.jar");
        writeFakeGomodJarWithMain(gomodJar, "0.1.0");
        writeFakeGomodJar(otherMod, "0.1.0"); // missing me/napim/gomod/Main.class

        File found = GomodAutoUpdater.findUniqueModJarInDir(modsDir, "0.1.0");
        assertNotNull(found);
        assertEquals(gomodJar.getAbsolutePath(), found.getAbsolutePath());
    }

    private static void writeFakeGomodJarWithMain(File out, String version) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.putNextEntry(new ZipEntry("gomod.version.properties"));
            zos.write(("version=" + version + "\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("me/napim/gomod/Main.class"));
            zos.write(new byte[]{0, 1, 2, 3});
            zos.closeEntry();
        }
    }

    private static JsonObject makeAsset(String name, String url, long size, String digest) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("browser_download_url", url);
        o.addProperty("size", size);
        if (digest != null && !digest.isEmpty()) {
            o.addProperty("digest", digest);
        }
        return o;
    }

    private static void writeFakeGomodJar(File out, String version) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            zos.putNextEntry(new ZipEntry("gomod.version.properties"));
            zos.write(("version=" + version + "\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
