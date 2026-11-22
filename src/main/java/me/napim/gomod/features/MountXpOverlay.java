package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;

/**
 * While riding a horse, donkey, or mule, draws the green XP fill (with configurable opacity) over the mount jump
 * bar and shows the XP level above it. Pigs retain Minecraft's complete vanilla XP bar because they have no jump bar.
 */
public final class MountXpOverlay {

    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;
    private static final int FILL_V = 69;

    /** Render-frame serial shared by the mixin and Forge fallback paths. */
    private static long hudFrameSerial;
    private static long lastRenderedHudFrameSerial = Long.MIN_VALUE;

    private MountXpOverlay() {
    }

    /** Called once at HUD-frame start; unlike {@code GuiIngame#getUpdateCounter}, this advances every rendered frame. */
    public static void beginHudFrame() {
        hudFrameSerial++;
    }

    public static boolean isEnabled() {
        return ConfigManager.isMountXpOverlayEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setMountXpOverlayEnabled(enabled);
    }

    public static int getOpacity() {
        return ConfigManager.getMountXpOverlayOpacity();
    }

    public static void setOpacity(int opacity) {
        ConfigManager.setMountXpOverlayOpacity(opacity);
    }

    public static float getOpacityNormalized() {
        return getOpacity() / 255.0F;
    }

    public static boolean shouldRender(Minecraft mc) {
        if (!isEnabled() || mc == null || mc.thePlayer == null) {
            return false;
        }
        if (PlayersPlayMode.isEnabled()) {
            return false;
        }
        return isSupportedMount(mc.thePlayer.ridingEntity);
    }

    public static boolean isSupportedMount(Entity entity) {
        return entity instanceof EntityHorse;
    }

    /**
     * Forge {@link net.minecraftforge.client.GuiIngameForge#renderJumpBar} hook (width/height args).
     */
    public static void renderAfterHud(GuiIngame gui, int scaledWidth, int scaledHeight) {
        render(gui, scaledWidth, scaledHeight);
    }

    /** Vanilla {@link GuiIngame#renderHorseJumpBar} / pig overlay hook. */
    public static void renderAfterHud(GuiIngame gui, ScaledResolution sr) {
        if (sr == null) {
            return;
        }
        render(gui, sr.getScaledWidth(), sr.getScaledHeight());
    }

    /**
     * Fallback when Forge mixins are skipped (Lunar / mapping issues): call from overlay post event.
     */
    public static void renderFallbackAfterOverlay() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.ingameGUI == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        render(mc.ingameGUI, sr.getScaledWidth(), sr.getScaledHeight());
    }

    private static void render(GuiIngame gui, int scaledWidth, int scaledHeight) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!shouldRender(mc) || gui == null || scaledWidth <= 0 || scaledHeight <= 0 || mc.fontRendererObj == null) {
            return;
        }

        // All primary/fallback hooks share this guard. The old update-counter guard advanced at 20 TPS rather than
        // render FPS, which made fallback-only pig overlays blink between rendered frames.
        long frameId = hudFrameSerial;
        if (frameId != 0L && frameId == lastRenderedHudFrameSerial) {
            return;
        }

        EntityPlayer player = mc.thePlayer;
        int barX = (scaledWidth - BAR_WIDTH) / 2;
        int barY = scaledHeight - 32 + 3;
        float alpha = getOpacityNormalized();
        if (alpha <= 0.01F) {
            return;
        }

        XpSnapshot xp = resolveXpSnapshot(player);
        int fillWidth = (int) (xp.progress * (BAR_WIDTH + 1));
        if (fillWidth <= 0 && xp.level <= 0) {
            return;
        }

        HudGlState.restoreForOverlayText();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        if (fillWidth > 0) {
            mc.getTextureManager().bindTexture(Gui.icons);
            GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
            gui.drawTexturedModalRect(barX, barY, 0, FILL_V, fillWidth, BAR_HEIGHT);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        if (xp.level > 0) {
            drawXpLevel(mc.fontRendererObj, scaledWidth, scaledHeight, xp.level);
        }
        if (frameId != 0L) {
            lastRenderedHudFrameSerial = frameId;
        }
        HudGlState.restoreForOverlayText();
    }

    private static void drawXpLevel(FontRenderer fr, int scaledWidth, int scaledHeight, int level) {
        String text = Integer.toString(level);
        int textX = (scaledWidth - fr.getStringWidth(text)) / 2;
        int textY = scaledHeight - 31 - 4;
        fr.drawString(text, textX + 1, textY, 0x000000, false);
        fr.drawString(text, textX - 1, textY, 0x000000, false);
        fr.drawString(text, textX, textY + 1, 0x000000, false);
        fr.drawString(text, textX, textY - 1, 0x000000, false);
        fr.drawString(text, textX, textY, 0x80FF20, false);
    }

    private static XpSnapshot resolveXpSnapshot(EntityPlayer player) {
        int level = player.experienceLevel;
        float progress = player.experience;
        if (LocalDisguise.shouldOverrideHypixelLobbyXpBar()) {
            level = LocalDisguise.getDisguisedNetworkLevel();
            progress = LocalDisguise.getDisguisedNetworkProgress();
        }
        return new XpSnapshot(level, progress, computeXpBarCap(level));
    }

    private static int computeXpBarCap(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 7;
    }

    private static final class XpSnapshot {
        final int level;
        final float progress;
        final int barCap;

        XpSnapshot(int level, float progress, int barCap) {
            this.level = level;
            this.progress = progress;
            this.barCap = barCap;
        }
    }
}
