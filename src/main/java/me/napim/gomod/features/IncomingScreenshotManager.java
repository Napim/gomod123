package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.gui.ScreenshotViewerGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class IncomingScreenshotManager {
    private IncomingScreenshotManager() {}

    /** Maximum accepted decoded image size: 4 MiB. */
    private static final long MAX_IMAGE_SIZE = 4L * 1024L * 1024L;
    /** Maximum Base64 payload length corresponding to {@link #MAX_IMAGE_SIZE} (plus padding slack). */
    private static final long MAX_BASE64_LENGTH = ((MAX_IMAGE_SIZE + 2L) / 3L) * 4L + 64L;
    /** Maximum decoded pixel count (e.g. ~8K x 4K) to guard against decompression bombs. */
    private static final long MAX_IMAGE_PIXELS = 33_554_432L;

    /** Allowed image file extensions. */
    private static final Set<String> ALLOWED_EXTENSIONS =
            new HashSet<String>(Arrays.asList(".png", ".jpg", ".jpeg", ".gif", ".bmp"));

    /** Last decoded incoming image for chat-line {@code /gcopenimg} (same bytes as toast). */
    private static volatile IncomingScreenshot lastIncomingForChatClick;

    public static final class IncomingScreenshot {
        public final String sender;
        public final String fileName;
        public final String mimeType;
        public final byte[] bytes;
        public final long ts;

        public IncomingScreenshot(String sender, String fileName, String mimeType, byte[] bytes, long ts) {
            this.sender = sender == null ? "unknown" : sender;
            this.fileName = sanitizeFileName(fileName);
            this.mimeType = mimeType == null ? "image/png" : mimeType;
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.ts = ts;
        }
    }

    /**
     * Sanitizes a filename: strips path separators and directory traversal,
     * restricts the stem to {@code [A-Za-z0-9_-]}, and forces a valid image extension.
     */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "screenshot.png";
        }
        String name = raw.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : ".png";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            ext = ".png";
        }
        stem = stem.replaceAll("[^A-Za-z0-9_-]", "_");
        while (stem.startsWith("_") && stem.length() > 1) {
            stem = stem.substring(1);
        }
        if (stem.isEmpty()) {
            stem = "screenshot";
        }
        if (stem.length() > 80) {
            stem = stem.substring(0, 80);
        }
        return stem + ext;
    }

    private static String sanitizeSenderTag(String sender) {
        if (sender == null) {
            return "friend";
        }
        String clean = sender.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (clean.isEmpty()) {
            return "friend";
        }
        if (clean.length() > 32) {
            clean = clean.substring(0, 32);
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    private static void validateDecodedImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Empty image payload");
        }
        if (bytes.length > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Image payload exceeds 4 MB limit");
        }
        final BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Image decode failed", ex);
        }
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            throw new IllegalArgumentException("Payload is not a valid image");
        }
        long pixels = (long) img.getWidth() * (long) img.getHeight();
        if (pixels <= 0L || pixels > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("Image dimensions exceed safe bounds");
        }
    }

    public static void handleIncoming(String sender, String fileName, String mimeType, String imageBase64, long ts) {
        if (!ConfigManager.isAcceptIncomingFriendScreenshots()) {
            return;
        }
        if (imageBase64 == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Received screenshot notice without image data.");
            return;
        }
        String trimmedBase64 = imageBase64.trim();
        if (trimmedBase64.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Received screenshot notice without image data.");
            return;
        }
        if (trimmedBase64.length() > MAX_BASE64_LENGTH) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Incoming screenshot exceeds 4 MB limit — rejected.");
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(trimmedBase64);
            if (bytes.length == 0) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Received empty screenshot data.");
                return;
            }
            if (bytes.length > MAX_IMAGE_SIZE) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Incoming screenshot exceeds 4 MB limit — rejected.");
                return;
            }
            validateDecodedImageBytes(bytes);

            final IncomingScreenshot screenshot = new IncomingScreenshot(sender, fileName, mimeType, bytes, ts);
            registerLatestIncomingForChatClick(screenshot);
            String savedName = screenshot.fileName;
            try {
                File saved = saveToSharedScreenshots(screenshot);
                if (saved != null) {
                    savedName = saved.getName();
                }
            } catch (Exception ignored) {
            }
            GomodSocialChat.handleIncomingScreenshot(sender, ts, savedName);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        Minecraft m = Minecraft.getMinecraft();
                        if (m != null && ConfigManager.isIncomingScreenshotOverlayEnabled()) {
                            IncomingScreenshotOverlay.show(screenshot);
                        }
                    }
                });
            }
        } catch (IllegalArgumentException invalid) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Rejected invalid incoming screenshot.");
        } catch (Exception e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to decode incoming screenshot.");
        }
    }

    public static void registerLatestIncomingForChatClick(IncomingScreenshot screenshot) {
        if (screenshot != null && screenshot.bytes != null && screenshot.bytes.length > 0
                && screenshot.bytes.length <= MAX_IMAGE_SIZE) {
            lastIncomingForChatClick = screenshot;
        }
    }

    public static void openLatestIncomingFromChat(Minecraft mc) {
        if (mc == null) {
            return;
        }
        IncomingScreenshot s = lastIncomingForChatClick;
        if (s == null || s.bytes == null || s.bytes.length == 0) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "No incoming friend screenshot to open.");
            return;
        }
        mc.displayGuiScreen(new ScreenshotViewerGUI(s, mc.currentScreen));
    }

    public static File saveToSharedScreenshots(IncomingScreenshot screenshot) throws Exception {
        if (screenshot == null) {
            throw new IllegalArgumentException("screenshot is null");
        }
        validateDecodedImageBytes(screenshot.bytes);

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.mcDataDir == null) {
            throw new IllegalStateException("minecraft data dir unavailable");
        }
        File dir = new File(new File(mc.mcDataDir, "gomod123"), "shared_screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create " + dir.getAbsolutePath());
        }
        Path normalizedDir = dir.toPath().toAbsolutePath().normalize();

        String safeName = sanitizeFileName(screenshot.fileName);
        String senderTag = sanitizeSenderTag(screenshot.sender);
        File out = new File(dir, senderTag + "_" + safeName);
        verifyPathInsideDirectory(out, dir, normalizedDir);

        int dot = safeName.lastIndexOf('.');
        String base = dot > 0 ? safeName.substring(0, dot) : safeName;
        String ext = dot > 0 ? safeName.substring(dot) : ".png";

        int i = 1;
        while (out.exists()) {
            out = new File(dir, senderTag + "_" + base + "_" + i + ext);
            verifyPathInsideDirectory(out, dir, normalizedDir);
            i++;
        }

        Files.write(out.toPath(), screenshot.bytes);
        return out;
    }

    private static void verifyPathInsideDirectory(File candidate, File dir, Path normalizedDir) throws Exception {
        Path normalizedOut = candidate.toPath().toAbsolutePath().normalize();
        if (!normalizedOut.startsWith(normalizedDir) || normalizedOut.equals(normalizedDir)) {
            throw new SecurityException("Resolved path escapes safe directory: " + normalizedOut);
        }
        String canonicalDir = dir.getCanonicalPath();
        String canonicalPrefix = canonicalDir.endsWith(File.separator) ? canonicalDir : canonicalDir + File.separator;
        if (!candidate.getCanonicalPath().startsWith(canonicalPrefix)) {
            throw new SecurityException("Canonical path escapes safe directory: " + candidate.getCanonicalPath());
        }
    }
}
