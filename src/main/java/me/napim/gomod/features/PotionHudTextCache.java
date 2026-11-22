package me.napim.gomod.features;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Client-thread cache of the strings actually displayed by the effects HUD and their glyph widths. */
public final class PotionHudTextCache {
    private static final int TIMER_SLOTS = 256;
    private static final int MAX_WIDTHS = 512;
    private static final Text[] TIMERS = new Text[TIMER_SLOTS];
    private static final int[] TIMER_KEYS = new int[TIMER_SLOTS];
    private static final Text INFINITE = new Text("**:**", EnumChatFormatting.GRAY);
    private static final Text[] NAMES = new Text[Potion.potionTypes.length * 20];
    private static final Map<String, Integer> WIDTHS = new HashMap<>();
    private static FontRenderer font;
    private static boolean unicode;
    private static boolean bidi;

    private PotionHudTextCache() {}

    public static void onFontReload() {
        Arrays.fill(NAMES, null);
        WIDTHS.clear();
        font = null;
    }

    static Text name(Potion potion, int amplifier, boolean preview) {
        int tier = amplifier > 0 && amplifier < 10 ? amplifier : 0;
        int key = potion.id * 20 + tier * 2 + (preview ? 1 : 0);
        Text text = NAMES[key];
        if (text == null) {
            String name = I18n.format(potion.getName());
            if (preview && potion.id == Potion.moveSpeed.id) {
                name += " II";
            } else if (tier != 0) {
                name += " " + I18n.format("potion.potency." + tier).trim();
            }
            text = new Text(name, EnumChatFormatting.RESET);
            NAMES[key] = text;
        }
        return text;
    }

    static Text duration(int ticks, boolean preview, boolean decimal) {
        if (ticks > 32147) {
            return preview && decimal ? tenths(200) : INFINITE;
        }
        return decimal ? tenths((Math.max(0, ticks) + 1) / 2)
                : seconds((Math.max(0, ticks) + 19) / 20);
    }

    static Text seconds(int seconds) {
        int slot = seconds & (TIMER_SLOTS - 1);
        if (TIMERS[slot] == null || TIMER_KEYS[slot] != seconds) {
            int remainder = seconds % 60;
            saveTimer(slot, seconds, seconds / 60 + (remainder < 10 ? ":0" : ":") + remainder);
        }
        return TIMERS[slot];
    }

    static Text tenths(int tenths) {
        int key = ~tenths;
        int slot = key & (TIMER_SLOTS - 1);
        if (TIMERS[slot] == null || TIMER_KEYS[slot] != key) {
            saveTimer(slot, key, tenths / 10 + "." + tenths % 10);
        }
        return TIMERS[slot];
    }

    private static void saveTimer(int slot, int key, String value) {
        TIMERS[slot] = new Text(value, EnumChatFormatting.GRAY);
        TIMER_KEYS[slot] = key;
    }

    static int width(FontRenderer renderer, String text) {
        boolean currentUnicode = renderer.getUnicodeFlag();
        boolean currentBidi = renderer.getBidiFlag();
        if (renderer != font || currentUnicode != unicode || currentBidi != bidi) {
            WIDTHS.clear();
            font = renderer;
            unicode = currentUnicode;
            bidi = currentBidi;
        }
        Integer cached = WIDTHS.get(text);
        if (cached != null) {
            return cached.intValue();
        }
        int width = renderer.getStringWidth(text);
        if (WIDTHS.size() >= MAX_WIDTHS) {
            WIDTHS.clear();
        }
        WIDTHS.put(text, width);
        return width;
    }

    static final class Text {
        final String plain;
        final String formatted;

        private Text(String plain, EnumChatFormatting color) {
            this.plain = plain;
            this.formatted = color + plain;
        }
    }
}
