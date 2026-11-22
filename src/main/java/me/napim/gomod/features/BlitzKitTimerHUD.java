package me.napim.gomod.features;



import me.napim.gomod.gui.HudEditorModuleTransition;
import me.napim.gomod.gui.HudOpacitySliderBoxInsets;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.features.MoreHudBackgroundColors;
import me.napim.gomod.gui.MoreHudSettingsGUI;
import me.napim.gomod.util.GameContext;

import net.minecraft.client.Minecraft;

import net.minecraft.client.network.NetworkPlayerInfo;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.client.gui.Gui;

import net.minecraft.client.renderer.GlStateManager;

import net.minecraft.util.EnumChatFormatting;

import net.minecraft.world.World;

import net.weavemc.loader.api.event.ChatReceivedEvent;

import net.weavemc.loader.api.event.RenderGameOverlayEvent;

import net.weavemc.loader.api.event.SubscribeEvent;



import java.util.Locale;



public class BlitzKitTimerHUD {

    /**
     * Fallback kit delay after cages open when the sidebar {@code Get kit: MM:SS} row is not visible yet
     * (Hypixel normally shows that row; {@link GameContext#parseBlitzSidebarGetKitRemainingMs} overrides this).
     */
    private static final long KIT_DELAY_MS = 60_000L;

    private static final long PREVIEW_REMAINING_MS = 45_000L;

    private static final long KIT_READY_HOLD_MS = 5_000L;

    /** Above this remaining time, show {@code M:SS}; at and below, show tenths (e.g. {@code 9.9}). */

    private static final long COUNTDOWN_DECIMAL_THRESHOLD_MS = 10_000L;

    /** Overlay frames to wait for {@link GameContext#isBlitzGameActive} after cage sidebar drops (avoids /hub false positives). */

    private static final int CAGE_OPEN_CONFIRM_FRAMES = 12;

    private static long kitReadyAtMs = -1L;

    private static long readyTextUntilMs = -1L;



    private static boolean lastBlitzCagePhase = false;

    private static int cageOpenConfirmFrames = 0;

    private static World lastSeenWorld = null;

    /** Last time {@link #syncKitTimerFromScoreboard} applied a countdown from the sidebar (for row-disappear snap). */
    private static long lastSuccessfulSidebarKitSyncMs = 0L;

    /**
     * Wall-clock instant when the cage sidebar row dropped (see {@link #tickCageSidebarRelease}). Used so
     * {@link #startCountdown(boolean)} stays aligned with Hypixel's 60s kit delay even when {@code isBlitzGameActive}
     * confirms a few frames late.
     */
    private static long lastCageOpenAnchorMs = -1L;

    private static final long CAGE_OPEN_ANCHOR_VALID_MS = 30_000L;

    /**
     * Pull kit deadlines slightly earlier than raw scoreboard {@code M:SS} so the HUD hits ~{@code 0.0s} when the
     * item lands. A fixed offset was wrong for everyone: no bias read ~{@code 0.2s} late, 220ms read ~{@code 0.1s}
     * early. This uses ~110ms as a midpoint plus a small TAB-list ping term — ping is not a perfect model (different
     * packets, same tick), but it tracks latency a bit better than a single global constant.
     */
    private static long kitDeliveryEarlyBiasMs(Minecraft mc) {

        int pingMs = 0;

        try {

            if (mc != null && mc.getNetHandler() != null && mc.thePlayer != null) {

                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());

                if (info != null) {

                    pingMs = info.getResponseTime();

                    if (pingMs < 0) {

                        pingMs = 0;

                    }

                }

            }

        } catch (Throwable ignored) {

        }

        long fromPing = Math.min(40L, pingMs / 5L);

        return 90L + fromPing;

    }



    @SubscribeEvent

    public void onChatReceived(ChatReceivedEvent event) {

        if (!ConfigManager.isBlitzKitTimerHudEnabled()) {

            return;

        }



        String raw = event.getMessage().getUnformattedText();

        if (raw == null) {

            return;

        }

        String normalized = raw.replaceAll("\\s+", " ").trim();

        if (normalized.contains("The Blitz Star will be released in 3 minutes")) {

            startCountdown(false);

        }

    }



    @SubscribeEvent

    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {

        if (!ConfigManager.isBlitzKitTimerHudEnabled()) {

            return;

        }

        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.fontRendererObj == null) {

            return;

        }

        if (HudEditorModuleTransition.shouldHideWorldHud(MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER)) {

            return;

        }

        maybeCancelForDeathHubOrDisconnect(mc);

        tickCageSidebarRelease(mc);

        syncKitTimerFromScoreboard(mc);

        render(mc, false);

    }



    public static void render(Minecraft mc, boolean preview) {

        if (mc == null || mc.fontRendererObj == null) {

            return;

        }

        if (!preview && !isVisibleNow()) {

            return;

        }



        String line = preview ? previewLine() : liveLine();

        if (line == null || line.isEmpty()) {

            return;

        }



        float scale = ConfigManager.getBlitzKitTimerHudScale();

        ScaledResolution sr = new ScaledResolution(mc);

        int anchorX = sr.getScaledWidth() / 2 + ConfigManager.getBlitzKitTimerHudOffsetCenterX();

        int anchorY = ConfigManager.getBlitzKitTimerHudPosY();



        GlStateManager.pushMatrix();

        GlStateManager.enableBlend();

        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GlStateManager.translate(anchorX, anchorY, 0.0F);

        GlStateManager.scale(scale, scale, 1.0F);

        GlStateManager.translate(-anchorX, -anchorY, 0.0F);
        HudEditorBackgroundMask.pushTransform(anchorX, anchorY, scale);



        int textWidth = mc.fontRendererObj.getStringWidth(line);

        int x = anchorX - (textWidth / 2);

        boolean shadow = ConfigManager.isBlitzKitTimerHudTextShadow();

        int bgAlpha = ConfigManager.getBlitzKitTimerHudBg();

        if (bgAlpha > 0) {

            int a = bgAlpha & 0xFF;

            int fh = mc.fontRendererObj.FONT_HEIGHT;

            HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.symmetricCenteredTextRowPad(
                    HudOpacitySliderBoxInsets.Module.BLITZ_KIT_TIMER, shadow);

            int boxTop = anchorY - pad.up;

            int boxBottom = anchorY + fh + pad.down;

            HudEditorBackgroundMask.drawRect(x - pad.left, boxTop, x + textWidth + pad.right, boxBottom,
                    MoreHudBackgroundColors.blitzKitTimerPixel(a));

        }

        if (shadow) {

            mc.fontRendererObj.drawStringWithShadow(line, x, anchorY, 0xFFFFFF);

        } else {

            mc.fontRendererObj.drawString(line, x, anchorY, 0xFFFFFF);

        }



        GlStateManager.disableBlend();

        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();

    }



    public static void getHudBounds(Minecraft mc, boolean preview, int[] outBounds) {

        if (outBounds == null || outBounds.length < 4 || mc == null || mc.fontRendererObj == null) {

            return;

        }

        String line = preview ? previewLine() : liveLine();

        if (line == null || line.isEmpty()) {

            line = previewLine();

        }

        ScaledResolution sr = new ScaledResolution(mc);

        int anchorX = sr.getScaledWidth() / 2 + ConfigManager.getBlitzKitTimerHudOffsetCenterX();

        int anchorY = ConfigManager.getBlitzKitTimerHudPosY();

        float scale = ConfigManager.getBlitzKitTimerHudScale();

        kitTimerBoxToScreen(mc, anchorX, anchorY, scale, line, outBounds);

    }



    private static void kitTimerBoxToScreen(Minecraft mc, int anchorX, int anchorY, float scale, String line,

                                            int[] outBounds) {

        int tw = mc.fontRendererObj.getStringWidth(line);

        int fh = mc.fontRendererObj.FONT_HEIGHT;

        HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.symmetricCenteredTextRowPad(
                HudOpacitySliderBoxInsets.Module.BLITZ_KIT_TIMER, ConfigManager.isBlitzKitTimerHudTextShadow());

        int uLeft = anchorX - tw / 2 - pad.left;

        int uTop = anchorY - pad.up;

        int uRight = anchorX + tw / 2 + pad.right;

        int uBottom = anchorY + fh + pad.down;

        pivotRectToScreen(anchorX, anchorY, scale, uLeft, uTop, uRight, uBottom, outBounds);

    }



    private static void pivotRectToScreen(int pivotX, int pivotY, float scale,

                                          int uLeft, int uTop, int uRight, int uBottom, int[] out) {

        int sxL = (int) Math.round(pivotX + (uLeft - (double) pivotX) * (double) scale);

        int sxR = (int) Math.round(pivotX + (uRight - (double) pivotX) * (double) scale);

        int syT = (int) Math.round(pivotY + (uTop - (double) pivotY) * (double) scale);

        int syB = (int) Math.round(pivotY + (uBottom - (double) pivotY) * (double) scale);

        int left = Math.min(sxL, sxR);

        int top = Math.min(syT, syB);

        out[0] = left;

        out[1] = top;

        out[2] = Math.max(1, Math.abs(sxR - sxL));

        out[3] = Math.max(1, Math.abs(syB - syT));

    }



    private static boolean isVisibleNow() {

        long now = System.currentTimeMillis();

        return (kitReadyAtMs > 0 && now < kitReadyAtMs) || now < readyTextUntilMs;

    }



    private static void clearKitCountdown() {

        kitReadyAtMs = -1L;

        readyTextUntilMs = -1L;

        lastSuccessfulSidebarKitSyncMs = 0L;

        lastCageOpenAnchorMs = -1L;

    }



    /**

     * Stop the kit timer when the local player dies, disconnects, leaves for a Hypixel lobby hub, or is no longer

     * on Hypixel — so a previous round does not keep showing after /hub or respawn elsewhere.

     */

    private static void maybeCancelForDeathHubOrDisconnect(Minecraft mc) {

        if (!isVisibleNow()) {

            return;

        }

        if (mc.theWorld == null) {

            clearKitCountdown();

            return;

        }

        if (!GameContext.isOnHypixel(mc)) {

            clearKitCountdown();

            return;

        }

        if (GameContext.isHypixelLobby(mc)) {

            clearKitCountdown();

            return;

        }

        if (mc.thePlayer != null && (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0f)) {

            clearKitCountdown();

        }

    }



    private static String liveLine() {

        long now = System.currentTimeMillis();

        if (kitReadyAtMs > 0 && now < kitReadyAtMs) {

            long remainingMs = kitReadyAtMs - now;

            return EnumChatFormatting.GOLD + "Kit in " + EnumChatFormatting.RED + formatCountdownDisplay(remainingMs);

        }

        if (now < readyTextUntilMs) {

            return EnumChatFormatting.GREEN + "Kit available!";

        }

        return null;

    }



    private static String previewLine() {

        return EnumChatFormatting.GOLD + "Kit in " + EnumChatFormatting.RED + formatCountdownDisplay(PREVIEW_REMAINING_MS);

    }



    /** {@code M:SS} while above 10s; {@code X.X} (one decimal) at 10.0 down to 0.0. */

    private static String formatCountdownDisplay(long remainingMs) {

        if (remainingMs > COUNTDOWN_DECIMAL_THRESHOLD_MS) {

            return formatMmSs(remainingMs);

        }

        return formatTenthsSeconds(remainingMs);

    }



    private static String formatMmSs(long millis) {

        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);

        long minutes = totalSeconds / 60L;

        long seconds = totalSeconds % 60L;

        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);

    }



    private static String formatTenthsSeconds(long remainingMs) {

        double secs = Math.max(0.0, remainingMs / 1000.0);

        secs = Math.round(secs * 10.0) / 10.0;

        return String.format(Locale.ROOT, "%.1f", secs);

    }



    /**
     * @param useCageOpenAnchor when {@code true}, align the 60s fallback with {@link #lastCageOpenAnchorMs} (cage
     *                          sidebar drop). When {@code false} (e.g. Blitz Star chat), use wall clock only.
     */
    private static void startCountdown(boolean useCageOpenAnchor) {

        long now = System.currentTimeMillis();

        Minecraft mc = Minecraft.getMinecraft();

        long early = kitDeliveryEarlyBiasMs(mc);

        if (useCageOpenAnchor && lastCageOpenAnchorMs > 0L && now - lastCageOpenAnchorMs <= CAGE_OPEN_ANCHOR_VALID_MS) {

            kitReadyAtMs = lastCageOpenAnchorMs + KIT_DELAY_MS - early;

        } else {

            kitReadyAtMs = now + KIT_DELAY_MS - early;

        }

        readyTextUntilMs = kitReadyAtMs + KIT_READY_HOLD_MS;

    }



    /**

     * Hypixel signals cage phase on the sidebar ("Cages open:" countdown). When that row disappears and the

     * match sidebar is active, cages have opened — same signal {@link KillCounter} uses for TAB cache invalidation.

     */

    private static void tickCageSidebarRelease(Minecraft mc) {

        if (mc.theWorld == null) {

            lastBlitzCagePhase = false;

            cageOpenConfirmFrames = 0;

            lastSeenWorld = null;

            lastCageOpenAnchorMs = -1L;

            clearKitCountdown();

            return;

        }

        if (mc.theWorld != lastSeenWorld) {

            if (lastSeenWorld != null && isVisibleNow()) {

                clearKitCountdown();

            }

            lastSeenWorld = mc.theWorld;

            lastBlitzCagePhase = false;

            cageOpenConfirmFrames = 0;

            lastCageOpenAnchorMs = -1L;

        }

        if (!GameContext.isOnHypixel(mc)) {

            lastBlitzCagePhase = false;

            cageOpenConfirmFrames = 0;

            return;

        }



        boolean cageNow = GameContext.isBlitzCagePhaseSidebar(mc);

        if (lastBlitzCagePhase && !cageNow) {

            lastCageOpenAnchorMs = System.currentTimeMillis();

            if (GameContext.isBlitzGameActive(mc)) {

                startCountdown(true);

            } else {

                cageOpenConfirmFrames = CAGE_OPEN_CONFIRM_FRAMES;

            }

        }

        lastBlitzCagePhase = cageNow;



        if (cageOpenConfirmFrames > 0) {

            cageOpenConfirmFrames--;

            if (GameContext.isBlitzGameActive(mc)) {

                cageOpenConfirmFrames = 0;

                startCountdown(true);

            }

        }

    }



    /**

     * Drives the HUD from the sidebar {@code Get kit: MM:SS} row (authoritative on Hypixel). Falls back to

     * {@link #startCountdown(boolean)} until that row appears. When the row disappears while a sidebar-synced deadline was

     * still in the future, treat kits as delivered and show the ready line.

     */

    private static void syncKitTimerFromScoreboard(Minecraft mc) {

        if (!ConfigManager.isBlitzKitTimerHudEnabled()) {

            return;

        }

        if (mc == null || mc.theWorld == null) {

            return;

        }

        if (!GameContext.isOnHypixel(mc) || GameContext.isHypixelLobby(mc)) {

            return;

        }

        long parsed = GameContext.parseBlitzSidebarGetKitRemainingMs(mc);

        if (!GameContext.isBlitzGameActive(mc) && parsed < 0L) {

            return;

        }



        long now = System.currentTimeMillis();

        if (parsed >= 0L) {

            kitReadyAtMs = now + parsed - kitDeliveryEarlyBiasMs(mc);

            readyTextUntilMs = kitReadyAtMs + KIT_READY_HOLD_MS;

            lastSuccessfulSidebarKitSyncMs = now;

            return;

        }



        if (kitReadyAtMs > now

                && lastSuccessfulSidebarKitSyncMs > 0L

                && now - lastSuccessfulSidebarKitSyncMs < 180_000L

                && !GameContext.blitzSidebarShowsGetKitCountdown(mc)) {

            kitReadyAtMs = now - 1L;

            readyTextUntilMs = now + KIT_READY_HOLD_MS;

        }

    }

    /**
     * Draws the kit timer preview line at an explicit anchor (More HUD settings gallery; does not read HUD position from config).
     */
    public static void renderPreviewAt(Minecraft mc, int anchorX, int anchorY, float scale, boolean shadow) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        String line = previewLine();
        if (line == null || line.isEmpty()) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.translate(anchorX, anchorY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-anchorX, -anchorY, 0.0F);
        int textWidth = mc.fontRendererObj.getStringWidth(line);
        int x = anchorX - (textWidth / 2);
        int bgAlpha = ConfigManager.getBlitzKitTimerHudBg();
        if (bgAlpha > 0) {
            int a = bgAlpha & 0xFF;
            int fh = mc.fontRendererObj.FONT_HEIGHT;
            HudOpacitySliderBoxInsets.TextBackgroundPad pad = HudOpacitySliderBoxInsets.symmetricCenteredTextRowPad(
                    HudOpacitySliderBoxInsets.Module.BLITZ_KIT_TIMER, shadow);
            int boxTop = anchorY - pad.up;
            int boxBottom = anchorY + fh + pad.down;
            Gui.drawRect(x - pad.left, boxTop, x + textWidth + pad.right, boxBottom,
                    MoreHudBackgroundColors.blitzKitTimerPixel(a));
        }
        if (shadow) {
            mc.fontRendererObj.drawStringWithShadow(line, x, anchorY, 0xFFFFFF);
        } else {
            mc.fontRendererObj.drawString(line, x, anchorY, 0xFFFFFF);
        }
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** Screen bounds for {@link #renderPreviewAt} (same geometry as {@link #getHudBounds} with custom anchor). */
    public static void getPreviewBoundsAt(Minecraft mc, int anchorX, int anchorY, float scale, int[] outBounds) {
        if (outBounds == null || outBounds.length < 4 || mc == null || mc.fontRendererObj == null) {
            return;
        }
        String line = previewLine();
        if (line == null || line.isEmpty()) {
            return;
        }
        kitTimerBoxToScreen(mc, anchorX, anchorY, scale, line, outBounds);
    }

}

