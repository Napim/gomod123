/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.event.RenderGameOverlayEvent$Post
 *  net.weavemc.loader.api.event.SubscribeEvent
 *  net.weavemc.loader.api.event.TickEvent
 */
package me.napim.gomod.features;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.napim.gomod.command.NoHitDelayState;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.CpsLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

public final class NoHitDelay {
    private static boolean enabled = true;
    private static boolean hudEnabled = false;
    private static Field leftClickCounterField;
    private static boolean reflectionFailed;
    private static volatile String counterFieldName;
    private static volatile int lastObservedValue;
    private static volatile long lastResetAtMs;
    private static List<Field> fallbackIntFields;
    private static Map<String, Integer> fallbackSnapshot;
    private static List<Field> controllerIntFields;
    private static Map<String, Integer> controllerSnapshot;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setNoHitDelayEnabled(value);
    }

    public static void toggle() {
        NoHitDelay.setEnabled(!enabled);
    }

    public static boolean isHudEnabled() {
        return hudEnabled;
    }

    public static void setHudEnabled(boolean value) {
        hudEnabled = value;
    }

    public static void toggleHud() {
        hudEnabled = !hudEnabled;
    }

    public static void loadSetting() {
        enabled = ConfigManager.isNoHitDelayEnabled();
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("NoHitDelay")) return;
        long _profT = FeatureProfiler.begin("NoHitDelay");
        try {
        if (reflectionFailed) {
            return;
        }
        if (!enabled && !hudEnabled) {
            return;
        }
        try {
            int current;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            if (leftClickCounterField == null) {
                leftClickCounterField = NoHitDelay.findField(mc.getClass(), "leftClickCounter", "field_71429_W", "field_71429_w");
                if (leftClickCounterField == null) {
                    if (fallbackIntFields == null) {
                        fallbackIntFields = new ArrayList<Field>();
                        fallbackSnapshot = new HashMap<String, Integer>();
                        for (Class<?> current2 = mc.getClass(); current2 != null; current2 = current2.getSuperclass()) {
                            for (Field f : current2.getDeclaredFields()) {
                                if (f.getType() != Integer.TYPE) continue;
                                try {
                                    f.setAccessible(true);
                                    int val = f.getInt(mc);
                                    fallbackIntFields.add(f);
                                    fallbackSnapshot.put(f.getName(), val);
                                }
                                catch (Exception exception) {
                                    // empty catch block
                                }
                            }
                        }
                        System.out.println("[gomod] NoHitDelay: leftClickCounter name not found, using behavioral detection (" + fallbackIntFields.size() + " candidates)");
                    }
                } else {
                    leftClickCounterField.setAccessible(true);
                    counterFieldName = leftClickCounterField.getName();
                    System.out.println("[gomod] NoHitDelay: Found field '" + leftClickCounterField.getName() + "' â€” active");
                }
            }
            if (leftClickCounterField != null) {
                int value;
                lastObservedValue = value = leftClickCounterField.getInt(mc);
                if (!enabled) {
                    return;
                }
                if (value > 0) {
                    leftClickCounterField.setInt(mc, 0);
                    NoHitDelayState.recordReset(value, 0);
                    lastResetAtMs = System.currentTimeMillis();
                }
                return;
            }
            if (fallbackIntFields != null && enabled) {
                for (Field f : fallbackIntFields) {
                    current = f.getInt(mc);
                    Integer prev = fallbackSnapshot.get(f.getName());
                    if (prev != null && (current == 10 && prev != 10 || current >= 1 && current <= 10 && prev == 0)) {
                        leftClickCounterField = f;
                        counterFieldName = f.getName();
                        fallbackIntFields = null;
                        fallbackSnapshot = null;
                        f.setInt(mc, 0);
                        NoHitDelayState.recordReset(current, 0);
                        lastResetAtMs = System.currentTimeMillis();
                        System.out.println("[gomod] NoHitDelay: active (detected field: " + f.getName() + ")");
                        break;
                    }
                    fallbackSnapshot.put(f.getName(), current);
                }
            }
            if (enabled && mc.playerController != null) {
                NoHitDelay.ensureControllerFields(mc.playerController);
                if (controllerIntFields != null) {
                    for (Field f : controllerIntFields) {
                        current = f.getInt(mc.playerController);
                        Integer prev = controllerSnapshot.get(f.getName());
                        boolean looksLikeDelay = NoHitDelay.isLikelyDelayField(f.getName(), current, prev);
                        if (looksLikeDelay) {
                            f.setInt(mc.playerController, 0);
                            NoHitDelayState.recordReset(current, 0);
                            lastResetAtMs = System.currentTimeMillis();
                        }
                        controllerSnapshot.put(f.getName(), current);
                    }
                }
            }
        }
        catch (Exception e) {
            System.out.println("[gomod] NoHitDelay: Reflection error â€” " + e.getMessage());
            reflectionFailed = true;
        }
        } finally { FeatureProfiler.end("NoHitDelay", _profT); }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!hudEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (leftClickCounterField != null) {
            try {
                lastObservedValue = leftClickCounterField.getInt(mc);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        String status = enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
        String line1 = EnumChatFormatting.GOLD + "NoHitDelay HUD";
        String line2 = EnumChatFormatting.YELLOW + "Feature: " + status;
        String line3 = EnumChatFormatting.YELLOW + "Field: " + EnumChatFormatting.WHITE + counterFieldName;
        String line4 = EnumChatFormatting.YELLOW + "Value: " + EnumChatFormatting.WHITE + lastObservedValue;
        String line5 = EnumChatFormatting.YELLOW + "Resets(all): " + EnumChatFormatting.WHITE + NoHitDelayState.resetCount;
        String line6 = EnumChatFormatting.YELLOW + "HitDelay timer sets(1-10): " + EnumChatFormatting.WHITE + NoHitDelayState.hitDelayDetectedCount;
        String line7 = EnumChatFormatting.YELLOW + "GUI guard resets(>=1000): " + EnumChatFormatting.WHITE + NoHitDelayState.guiGuardResetCount;
        String line8 = EnumChatFormatting.YELLOW + "Lingering cooldowns(1-9): " + EnumChatFormatting.WHITE + NoHitDelayState.lingeringCooldownCount;
        String lastReset = NoHitDelayState.lastBefore >= 0 ? NoHitDelayState.lastBefore + " -> " + NoHitDelayState.lastAfter : "n/a";
        String line9 = EnumChatFormatting.YELLOW + "Last reset: " + EnumChatFormatting.WHITE + lastReset + EnumChatFormatting.GRAY + " (" + NoHitDelayState.lastCategory + ")";
        long age = lastResetAtMs > 0L ? System.currentTimeMillis() - lastResetAtMs : -1L;
        String line10 = EnumChatFormatting.YELLOW + "Since reset: " + EnumChatFormatting.WHITE + (age >= 0L ? age + "ms" : "n/a");
        long hitAge = NoHitDelayState.lastHitDelayAtMs > 0L ? System.currentTimeMillis() - NoHitDelayState.lastHitDelayAtMs : -1L;
        String hitLast = NoHitDelayState.lastHitDelayBefore >= 0 ? NoHitDelayState.lastHitDelayBefore + " -> 0" : "n/a";
        String line11 = EnumChatFormatting.YELLOW + "Last hit-delay timer: " + EnumChatFormatting.WHITE + hitLast;
        String line12 = EnumChatFormatting.YELLOW + "Since hit-delay timer: " + EnumChatFormatting.WHITE + (hitAge >= 0L ? hitAge + "ms" : "n/a");
        long lingeringAge = NoHitDelayState.lastLingeringAtMs > 0L ? System.currentTimeMillis() - NoHitDelayState.lastLingeringAtMs : -1L;
        String lingeringLast = NoHitDelayState.lastLingeringBefore >= 0 ? NoHitDelayState.lastLingeringBefore + " ticks" : "n/a";
        String line13 = EnumChatFormatting.YELLOW + "Last lingering cooldown: " + EnumChatFormatting.WHITE + lingeringLast;
        String line14 = EnumChatFormatting.YELLOW + "Since lingering cooldown: " + EnumChatFormatting.WHITE + (lingeringAge >= 0L ? lingeringAge + "ms" : "n/a");
        boolean recentLingering = lingeringAge >= 0L && lingeringAge <= 900L;
        String line15 = recentLingering ? EnumChatFormatting.RED + "POSSIBLE HIT-DELAY WINDOW DETECTED" : EnumChatFormatting.GREEN + "No lingering hit-delay window detected recently";
        ArrayList<String> lines = new ArrayList<String>();
        lines.add(line1);
        lines.add(line2);
        lines.add(line3);
        lines.add(line4);
        lines.add(line5);
        lines.add(line6);
        lines.add(line7);
        lines.add(line8);
        lines.add(line9);
        lines.add(line10);
        lines.add(line11);
        lines.add(line12);
        lines.add(line13);
        lines.add(line14);
        lines.add(line15);
        double cpsLimit = CpsLimiter.getCpsLimit();
        if (cpsLimit <= 0.0) {
            lines.add(EnumChatFormatting.YELLOW + "CPS limiter: " + EnumChatFormatting.RED + "disabled");
        } else {
            lines.add(EnumChatFormatting.YELLOW + "CPS limiter: " + EnumChatFormatting.WHITE + NoHitDelay.trimDouble(cpsLimit) + EnumChatFormatting.YELLOW + " (" + EnumChatFormatting.WHITE + NoHitDelay.trimDouble(CpsLimiter.getMinimumIntervalMs()) + "ms" + EnumChatFormatting.YELLOW + ")");
            lines.add(EnumChatFormatting.YELLOW + "CPS allowed/blocked: " + EnumChatFormatting.WHITE + CpsLimiter.getAllowedAttackCount() + "/" + CpsLimiter.getBlockedAttackCount());
            lines.add(EnumChatFormatting.YELLOW + "Last CPS block: " + EnumChatFormatting.WHITE + NoHitDelay.formatAge(CpsLimiter.getLastBlockedAttackAtMs()));
            lines.add(EnumChatFormatting.RED + "CPS limiter can still cancel hits");
        }
        int x = 4;
        int y = 4;
        int pad = 3;
        int lineHeight = mc.fontRendererObj.FONT_HEIGHT + 2;
        int maxWidth = 0;
        for (String string : lines) {
            maxWidth = Math.max(maxWidth, mc.fontRendererObj.getStringWidth(string));
        }
        int lineCount = lines.size();
        ScaledResolution scaledResolution = new ScaledResolution(mc);
        int boxRight = Math.min(scaledResolution.getScaledWidth() - 4, x + maxWidth + pad * 2);
        int boxHeight = lineCount * lineHeight + 2;
        int boxBottom = Math.min(scaledResolution.getScaledHeight() - 4, y + boxHeight + pad);
        Gui.drawRect((int)(x - pad), (int)(y - pad), (int)boxRight, (int)boxBottom, (int)-1879048192);
        int drawY = y;
        for (String string : lines) {
            mc.fontRendererObj.drawStringWithShadow(string, (float)x, (float)drawY, 0xFFFFFF);
            drawY += lineHeight;
        }
    }

    private static Field findField(Class<?> clazz, String ... names) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    return c.getDeclaredField(name);
                }
                catch (NoSuchFieldException noSuchFieldException) {
                }
            }
        }
        return null;
    }

    private static void ensureControllerFields(Object controller) {
        if (controllerIntFields != null) {
            return;
        }
        controllerIntFields = new ArrayList<Field>();
        controllerSnapshot = new HashMap<String, Integer>();
        for (Class<?> current = controller.getClass(); current != null; current = current.getSuperclass()) {
            for (Field f : current.getDeclaredFields()) {
                String name;
                if (f.getType() != Integer.TYPE || !(name = f.getName().toLowerCase(Locale.ROOT)).contains("delay") && !name.contains("hit") && !name.contains("click")) continue;
                try {
                    f.setAccessible(true);
                    int val = f.getInt(controller);
                    controllerIntFields.add(f);
                    controllerSnapshot.put(f.getName(), val);
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
    }

    private static boolean isLikelyDelayField(String name, int current, Integer prev) {
        if (current <= 0) {
            return false;
        }
        if (current > 10) {
            return false;
        }
        if (prev == null) {
            return true;
        }
        return prev == 0 || prev == 10 || prev < current;
    }

    private static String formatAge(long timestampMs) {
        if (timestampMs <= 0L) {
            return "n/a";
        }
        return System.currentTimeMillis() - timestampMs + "ms ago";
    }

    private static String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static {
        reflectionFailed = false;
        counterFieldName = "(unresolved)";
        lastObservedValue = -1;
        lastResetAtMs = 0L;
        fallbackIntFields = null;
        fallbackSnapshot = null;
        controllerIntFields = null;
        controllerSnapshot = null;
    }
}
