/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.entity.EntityLivingBase
 *  net.minecraft.entity.player.EntityPlayer
 *  net.minecraft.scoreboard.Score
 *  net.minecraft.scoreboard.ScoreObjective
 *  net.minecraft.scoreboard.ScorePlayerTeam
 *  net.minecraft.scoreboard.Scoreboard
 *  net.minecraft.scoreboard.Team
 *  net.minecraft.util.EnumChatFormatting
 */
package me.napim.gomod.features;

import java.util.Locale;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

public class ReplaySettings {
    private static final String HEART_SYMBOL = "\u2764";
    private static final String HALF_HEART_SYMBOL = "\u2765";
    private static boolean healthDisplayEnabled = false;
    private static boolean showInvisiblePlayersEnabled = false;
    private static HealthStyle currentStyle = HealthStyle.SIMPLE;
    private static long lastReplayCheckMs = 0L;
    private static boolean lastReplayActive = false;
    private static final long REPLAY_CHECK_COOLDOWN_MS = 250L;

    public static void loadSettings() {
        healthDisplayEnabled = false;
        showInvisiblePlayersEnabled = false;
        try {
            String styleName = ConfigManager.getReplayHealthDisplayStyle();
            if (styleName != null) {
                currentStyle = HealthStyle.valueOf(styleName);
            }
        }
        catch (Exception e) {
            currentStyle = HealthStyle.SIMPLE;
        }
    }

    public static void toggleHealthDisplay() {
        boolean next;
        boolean current = ConfigManager.isReplayHealthDisplayEnabled();
        healthDisplayEnabled = next = !current;
        ConfigManager.setReplayHealthDisplayEnabled(next);
    }

    public static void toggleShowInvisible() {
        boolean next;
        boolean current = ConfigManager.isReplayShowInvisibleEnabled();
        showInvisiblePlayersEnabled = next = !current;
        ConfigManager.setReplayShowInvisibleEnabled(next);
    }

    public static void setStyle(HealthStyle style) {
        currentStyle = style;
        try {
            ConfigManager.setReplayHealthDisplayStyle(style.name());
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    public static HealthStyle getCurrentStyle() {
        return currentStyle;
    }

    public static HealthStyle[] getAllStyles() {
        return HealthStyle.values();
    }

    public static boolean isHealthDisplayEnabled() {
        return false;
    }

    public static boolean isShowInvisibleEnabled() {
        return false;
    }

    public static boolean isReplayActive() {
        try {
            long now = Minecraft.getSystemTime();
            if (now - lastReplayCheckMs < REPLAY_CHECK_COOLDOWN_MS) {
                return lastReplayActive;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                lastReplayCheckMs = now;
                lastReplayActive = false;
                return false;
            }
            Scoreboard sb = mc.theWorld.getScoreboard();
            ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
            if (obj != null) {
                String clean;
                String name = obj.getDisplayName();
                // The title is the cheap path. Hypixel replay sidebars often put the real marker in a line:
                // "Replay from <server>". Keep line scanning specific so random names containing "replay" do not match.
                if (name != null) {
                    clean = stripColorCodes(name);
                    String lower = clean.toLowerCase(Locale.ROOT);
                    if (lower.contains("replay")) {
                        lastReplayCheckMs = now;
                        lastReplayActive = true;
                        return true;
                    }
                }
                for (Score score : sb.getSortedScores(obj)) {
                    ScorePlayerTeam team = sb.getPlayersTeam(score.getPlayerName());
                    String raw = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                    if (raw == null) {
                        continue;
                    }
                    clean = stripColorCodes(raw);
                    String lower = clean.toLowerCase(Locale.ROOT);
                    if (lower.contains("replay from") || lower.equals("replay")) {
                        lastReplayCheckMs = now;
                        lastReplayActive = true;
                        return true;
                    }
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        try {
            lastReplayCheckMs = Minecraft.getSystemTime();
        } catch (Throwable ignored) {}
        lastReplayActive = false;
        return false;
    }

    public static void drawForEntity(EntityLivingBase entity, double x, double y, double z, float partialTicks) {
    }

    public static String buildVanillaHealthRowLabel(EntityLivingBase entity) {
        return null;
    }

    private static boolean shouldShowHealthInVanillaNametag(EntityLivingBase entity) {
        if (!ConfigManager.isReplayHealthDisplayEnabled()) {
            return false;
        }
        if (!ReplaySettings.isReplayActive()) {
            return false;
        }
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        try {
            // Keep health labels suppressed behind GUIs to avoid clutter and unstable render paths.
            if (mc.currentScreen != null) {
                return false;
            }
        }
        catch (Throwable throwable) {
            return false;
        }
        try {
            if (entity == mc.thePlayer && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0) {
                return false;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return true;
    }

    private static String buildVanillaHealthSuffix(float health, float maxHealth) {
        switch (currentStyle) {
            case CLASSIC: {
                return ReplaySettings.buildClassicNametagRow(health, maxHealth);
            }
        }
        return EnumChatFormatting.WHITE.toString() + (int)Math.ceil(health) + EnumChatFormatting.GRAY + " " + EnumChatFormatting.RED + HEART_SYMBOL;
    }

    private static String buildClassicNametagRow(float health, float maxHealth) {
        int maxHearts = Math.max(1, Math.min(10, (int)Math.ceil(maxHealth / 2.0f)));
        int fullHearts = Math.max(0, Math.min(maxHearts, (int)health / 2));
        boolean halfHeart = health % 2.0f >= 1.0f && fullHearts < maxHearts;
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < maxHearts; ++i) {
            if (i < fullHearts) {
                suffix.append(EnumChatFormatting.RED).append(HEART_SYMBOL);
                continue;
            }
            if (i == fullHearts && halfHeart) {
                suffix.append(EnumChatFormatting.RED).append(HALF_HEART_SYMBOL);
                continue;
            }
            suffix.append(EnumChatFormatting.DARK_GRAY).append(HEART_SYMBOL);
        }
        return suffix.toString();
    }

    public static float getYOffset() {
        return ConfigManager.getReplayHealthDisplayYOffset();
    }

    public static void setYOffset(float offset) {
        ConfigManager.setReplayHealthDisplayYOffset(offset);
    }

    public static enum HealthStyle {
        SIMPLE("Numbers", "Simple text with heart"),
        CLASSIC("Hearts", "Classic Minecraft hearts");

        private final String name;
        private final String description;

        private HealthStyle(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return this.name;
        }

        public String getDescription() {
            return this.description;
        }
    }

    /** Button label preview: {@code 20❤} vs a row of red hearts (uses {@link #HEART_SYMBOL}). */
    public static String getHealthStyleGuiButtonSuffix() {
        return getHealthStyleGuiButtonSuffixFor(currentStyle);
    }

    public static String getHealthStyleGuiButtonSuffixFor(HealthStyle style) {
        if (style == HealthStyle.CLASSIC) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                b.append(EnumChatFormatting.RED).append(HEART_SYMBOL);
            }
            return b.toString();
        }
        return EnumChatFormatting.WHITE + "20" + EnumChatFormatting.RED + HEART_SYMBOL;
    }

    private static String stripColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                        || code == 'k' || code == 'l' || code == 'm'
                        || code == 'n' || code == 'o' || code == 'r') {
                    i++; // skip formatting code
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
