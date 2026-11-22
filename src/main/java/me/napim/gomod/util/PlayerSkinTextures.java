package me.napim.gomod.util;

import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * Player skin image helpers (legacy 64x32 → modern 64x64 layout for 1.8 rendering).
 */
public final class PlayerSkinTextures {

    private PlayerSkinTextures() {
    }

    /**
     * Upgrades a pre-1.8 {@code 64x32} skin to {@code 64x64} using the same region copies as
     * vanilla {@code SkinManager} so GUI player previews render limbs correctly.
     */
    public static BufferedImage upgradeLegacySkin(BufferedImage image) {
        if (image == null) {
            return null;
        }
        if (image.getHeight() >= 64) {
            return image;
        }
        if (image.getWidth() != 64 || image.getHeight() != 32) {
            return image;
        }

        BufferedImage upgraded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = upgraded.getGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
            copy(graphics, image, 24, 48, 20, 52, 4, 16, 8, 20);
            copy(graphics, image, 28, 48, 24, 52, 8, 16, 12, 20);
            copy(graphics, image, 20, 52, 16, 64, 8, 20, 12, 32);
            copy(graphics, image, 24, 52, 20, 64, 4, 20, 8, 32);
            copy(graphics, image, 28, 52, 24, 64, 0, 20, 4, 32);
            copy(graphics, image, 32, 52, 28, 64, 12, 20, 16, 32);
            copy(graphics, image, 40, 48, 36, 52, 44, 16, 48, 20);
            copy(graphics, image, 44, 48, 40, 52, 48, 16, 52, 20);
            copy(graphics, image, 36, 52, 32, 64, 48, 20, 52, 32);
            copy(graphics, image, 40, 52, 36, 64, 44, 20, 48, 32);
            copy(graphics, image, 44, 52, 40, 64, 40, 20, 44, 32);
            copy(graphics, image, 48, 52, 44, 64, 52, 20, 64, 32);
        } finally {
            graphics.dispose();
        }
        return upgraded;
    }

    private static void copy(Graphics graphics, BufferedImage source,
                             int dx1, int dy1, int dx2, int dy2,
                             int sx1, int sy1, int sx2, int sy2) {
        graphics.drawImage(source, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
    }
}
