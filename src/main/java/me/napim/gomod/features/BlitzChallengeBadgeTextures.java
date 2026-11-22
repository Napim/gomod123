package me.napim.gomod.features;

import me.napim.gomod.features.BlitzChallenges.ChallengeId;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

/**
 * Classpath textures for fully-completed Blitz challenge badges.
 * Path: {@code assets/gomod123/textures/blitz_badges/<icon>.png}
 */
public final class BlitzChallengeBadgeTextures {

    private static final String DOMAIN = "gomod123";
    private static final String BASE = "textures/blitz_badges/";

    private BlitzChallengeBadgeTextures() {
    }

    public static void preloadAll(Minecraft mc) {
        if (mc == null) {
            return;
        }
        for (ChallengeId id : ChallengeId.values()) {
            ensureTextureLoaded(mc, id);
        }
    }

    public static ResourceLocation textureFor(ChallengeId id) {
        if (id == null) {
            return null;
        }
        String file = badgeFileName(id);
        if (file == null) {
            return null;
        }
        return new ResourceLocation(DOMAIN, BASE + file);
    }

    public static boolean ensureTextureLoaded(Minecraft mc, ChallengeId id) {
        ResourceLocation packLoc = textureFor(id);
        if (packLoc == null || mc == null) {
            return false;
        }
        GomodClasspathTextures.ensureLoaded(mc, packLoc);
        return GomodClasspathTextures.getWidth(packLoc, 0) > 0
                && GomodClasspathTextures.getHeight(packLoc, 0) > 0;
    }

    public static ResourceLocation resolveLoaded(Minecraft mc, ChallengeId id) {
        ResourceLocation packLoc = textureFor(id);
        if (packLoc == null || mc == null) {
            return null;
        }
        if (!ensureTextureLoaded(mc, id)) {
            return null;
        }
        return packLoc;
    }

    public static boolean isAvailable(Minecraft mc, ChallengeId id) {
        return ensureTextureLoaded(mc, id);
    }

    /**
     * Draw a badge icon in a GUI slot using the texture's real pixel dimensions (not 256px atlas UVs).
     *
     * @return true if the texture was drawn
     */
    public static boolean drawGuiBadge(Minecraft mc, ChallengeId id, int x, int y, int w, int h, float alpha) {
        ResourceLocation packLoc = textureFor(id);
        if (packLoc == null || mc == null || !ensureTextureLoaded(mc, id)) {
            return false;
        }
        int texW = GomodClasspathTextures.getWidth(packLoc, 0);
        int texH = GomodClasspathTextures.getHeight(packLoc, 0);
        if (texW <= 0 || texH <= 0) {
            return false;
        }
        float fit = Math.min(w / (float) texW, h / (float) texH);
        int drawW = Math.max(1, Math.round(texW * fit));
        int drawH = Math.max(1, Math.round(texH * fit));
        int drawX = x + (w - drawW) / 2;
        int drawY = y + (h - drawH) / 2;
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        GomodClasspathTextures.bindTexture(mc, packLoc);
        Gui.drawScaledCustomSizeModalRect(drawX, drawY, 0, 0, texW, texH, drawW, drawH, texW, texH);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        return true;
    }

    private static String badgeFileName(ChallengeId id) {
        switch (id) {
            case BLOOD_HOUR:
                return "bloodhouricon.png";
            case MASSACRE:
                return "massacreicon.png";
            case RAMPAGE:
                return "deathmatchbeasticon.png";
            case FIRST_BLOOD:
                return "firstbloodstrikericon.png";
            case NO_MERCY:
                return "nomercyicon.png";
            case KILL_STREAK:
                return "killstreakericon.png";
            case HOT_STREAK:
                return "winstreakericon.png";
            default:
                return null;
        }
    }
}
