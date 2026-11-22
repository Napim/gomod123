package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import java.awt.Graphics;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/** Downloads cape PNGs into {@code gomod/capes/} textures. */
public final class CapeTextureLoader {
    private static final Set<String> PENDING_DOWNLOADS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, BufferedImage> DOWNLOADED_IMAGES =
            new ConcurrentHashMap<String, BufferedImage>();

    private CapeTextureLoader() {
    }

    public static ResourceLocation loadExternalCapeTexture(String imageUrl) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || imageUrl == null || imageUrl.isEmpty()) {
                return null;
            }
            String textureId = UUID.nameUUIDFromBytes(imageUrl.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            ResourceLocation resourceLocation = new ResourceLocation("gomod/capes/" + textureId);
            TextureManager textureManager = mc.getTextureManager();
            if (textureManager == null) {
                return null;
            }
            ITextureObject existingTexture = textureManager.getTexture(resourceLocation);
            if (existingTexture == null) {
                if (shouldUseRobustCapeDownload(imageUrl)) {
                    startRobustCapeDownload(mc, textureManager, resourceLocation, imageUrl);
                } else {
                    ThreadDownloadImageData texture = new ThreadDownloadImageData(null, imageUrl, null,
                            new CapeImageBuffer(resourceLocation));
                    textureManager.loadTexture(resourceLocation, texture);
                }
            }
            return resourceLocation;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldUseRobustCapeDownload(String imageUrl) {
        if (imageUrl == null) {
            return false;
        }
        String lower = imageUrl.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("optifine.net")
                || lower.contains("api.capes.dev/")
                || lower.contains("minecraftcapes.net")
                || lower.contains("minecraftcapes.co");
    }

    private static void startRobustCapeDownload(final Minecraft mc, final TextureManager textureManager,
                                                final ResourceLocation resourceLocation, final String imageUrl) {
        final String key = resourceLocation.toString();
        if (!PENDING_DOWNLOADS.add(key)) {
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes = HypixelApiHelper.fetchCapeImageBytes(imageUrl);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                final BufferedImage normalized = normalizeCapeImage(image);
                if (normalized == null) {
                    return;
                }
                DOWNLOADED_IMAGES.put(resourceLocation.toString(), copyImage(normalized));
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            textureManager.loadTexture(resourceLocation, new DynamicTexture(normalized));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            } finally {
                PENDING_DOWNLOADS.remove(key);
            }
        });
    }

    private static BufferedImage normalizeCapeImage(BufferedImage source) {
        if (source == null) {
            return null;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int targetWidth = 64;
        int targetHeight = 32;
        while (targetWidth < sourceWidth || targetHeight < sourceHeight) {
            targetWidth *= 2;
            targetHeight *= 2;
        }
        BufferedImage normalized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = normalized.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    /** CPU-side copy used when the selected downloaded cape must be sent to gomod peers. */
    public static BufferedImage getCachedCapeImage(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        BufferedImage image = DOWNLOADED_IMAGES.get(location.toString());
        return image == null ? null : copyImage(image);
    }

    private static BufferedImage copyImage(BufferedImage source) {
        if (source == null) {
            return null;
        }
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = copy.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static final class CapeImageBuffer implements IImageBuffer {
        private final ResourceLocation location;

        CapeImageBuffer(ResourceLocation location) {
            this.location = location;
        }

        @Override
        public BufferedImage parseUserSkin(BufferedImage image) {
            BufferedImage normalized = normalizeCapeImage(image);
            if (normalized != null && location != null) {
                DOWNLOADED_IMAGES.put(location.toString(), copyImage(normalized));
            }
            return normalized;
        }

        @Override
        public void skinAvailable() {
        }
    }
}
