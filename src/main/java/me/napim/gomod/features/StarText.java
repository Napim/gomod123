package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.Locale;
import java.util.regex.Pattern;

public class StarText {

    // Regex modified to exclude colons in the player name part ([^:]+) to prevent players from spoofing via chat
    // Chat messages usually follow "Name: Message" format, so detecting a colon prevents triggering on chat.
    private static final Pattern STAR_PATTERN_1 = Pattern.compile("^([^:]+)! BLITZ STAR! (.+)!$");
    private static final Pattern STAR_PATTERN_2 = Pattern.compile("^([^:]+) BLITZ! (.+)$");
    private static final Pattern STAR_PATTERN_FOUND = Pattern.compile("^([^:]+) found the Blitz Star!$");

    private static String currentText = null;
    private static long displayEndTime = 0;
    private static long fadeInEndTime = 0;
    private static long fadeOutStartTime = 0;

    // Stars whose usage should NOT trigger the on-screen overlay.
    private static final java.util.Set<String> SUPPRESSED_STARS = new java.util.HashSet<>(java.util.Arrays.asList(
            "sweg move", "roulette",
            "stasis", "stasis star",
            "apocalypse", "apocalypse star",
            "nuke", "nuke star"
    ));

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        if (!ConfigManager.isStarTextEnabled()) return;

        String unformatted = event.getMessage().getUnformattedText().trim();

        java.util.regex.Matcher m1 = STAR_PATTERN_1.matcher(unformatted);
        if (m1.matches()) {
            if (!isSuppressedStar(m1.group(2))) showTitle(event.getMessage());
            return;
        }

        java.util.regex.Matcher m2 = STAR_PATTERN_2.matcher(unformatted);
        if (m2.matches()) {
            if (!isSuppressedStar(m2.group(2))) showTitle(event.getMessage());
            return;
        }

        if (STAR_PATTERN_FOUND.matcher(unformatted).matches()) {
            showTitle(event.getMessage());
        }
    }

    /** The center-screen Blitz Star title must use the same local and remote disguise mapping as chat. */
    private static void showTitle(IChatComponent message) {
        if (message == null) return;
        IChatComponent display = LocalDisguise.rewriteIncomingChatComponentForDisplay(message);
        display = GomodPeerDisguiseDisplay.rewriteIncomingChat(display);
        showTitle(display == null ? message.getFormattedText() : display.getFormattedText());
    }

    /** Returns true if the star name should not produce an on-screen overlay. */
    private static boolean isSuppressedStar(String starName) {
        if (starName == null) return false;
        String normalized = starName
                .replaceAll("(?i)\u00a7[0-9A-FK-OR]", "")
                .replaceAll("\\s+", " ")
                .trim();
        while (normalized.endsWith("!")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return SUPPRESSED_STARS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    private static void showTitle(String text) {
        String display = text;
        try {
            if (LocalDisguise.fastRenderTextRewriteFlag) {
                display = LocalDisguise.replaceRenderedText(text);
            }
        } catch (Throwable ignored) {
        }
        currentText = display;
        long currentTime = System.currentTimeMillis();
        long duration = (long) (ConfigManager.getStarTextDuration() * 1000);
        long fadeIn = 500;
        long fadeOut = 1000;
        
        // Ensure duration is at least fadeIn + fadeOut
        if (duration < fadeIn + fadeOut) {
            duration = fadeIn + fadeOut + 1000;
        }

        displayEndTime = currentTime + duration;
        fadeInEndTime = currentTime + fadeIn;
        fadeOutStartTime = currentTime + duration - fadeOut;
    }
    
    public static void testDisplay() {
        showTitle(EnumChatFormatting.GREEN + "Test Player" + EnumChatFormatting.RESET + " BLITZ! " + EnumChatFormatting.RED + "Test Star");
    }

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (currentText == null) return;
        
        long currentTime = System.currentTimeMillis();
        if (currentTime >= displayEndTime) {
            currentText = null;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution scaledResolution = new ScaledResolution(mc);
        int width = scaledResolution.getScaledWidth();
        int height = scaledResolution.getScaledHeight();

        // Calculate opacity
        int alpha = 255;
        if (currentTime < fadeInEndTime) {
            alpha = (int) (255 * (currentTime - (fadeInEndTime - 500)) / 500.0f);
        } else if (currentTime > fadeOutStartTime) {
            alpha = (int) (255 * (displayEndTime - currentTime) / (displayEndTime - fadeOutStartTime));
        }
        
        // Clamp alpha
        alpha = Math.max(0, Math.min(255, alpha));
        
        // Don't render if invisible
        if (alpha <= 5) return;

        // Render
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        
        // Scale up like a title (default 4x, user configurable)
        float scale = ConfigManager.getStarTextScale();
        GlStateManager.scale(scale, scale, scale);
        
        // Calculate position
        int stringWidth = mc.fontRendererObj.getStringWidth(currentText);
        float x = (width / 2.0f) / scale - (stringWidth / 2.0f);
        float y = (height / 2.0f) / scale - (mc.fontRendererObj.FONT_HEIGHT / 2.0f);
        
        // Add Y offset
        y += ConfigManager.getStarTextYOffset() / scale;

        // Draw with shadow and color (color is embedded in text, but alpha needs to be applied)
        // To apply alpha to formatted text, we might need a custom renderer or just rely on the fact that font renderer handles alpha if color has alpha?
        // Minecraft 1.8.9 FontRenderer usually doesn't handle alpha well with color codes unless we modify the color codes or use a specific method.
        // However, standard displayTitle does it. 
        // Let's use drawing with color | alpha << 24
        
        // Since the text has color codes, we should respect them but modulate alpha.
        // The standard font renderer in 1.8.9 doesn't easily support alpha modulation on colored strings without hacks.
        // For simplicity, we'll just render it. If fading is ugly (popping), it's acceptable for now or we can improve later.
        // But let's try to pass alpha in the color param.
        int color = 0xFFFFFF | (alpha << 24);
        
        mc.fontRendererObj.drawStringWithShadow(currentText, x, y, color);
        
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }
}
