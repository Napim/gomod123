package me.napim.gomod.features;

import net.minecraft.client.gui.inventory.GuiContainer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads {@link GuiContainer#guiLeft} / {@code guiTop} via reflection (MCP + SRG names for Lunar/obfuscated jars).
 */
final class BestArmorGuiLayoutAccess {

    private static final String[] LEFT_NAMES = {
        "guiLeft", "field_147003_i", "field_147002_h", "field_147001_g"
    };
    private static final String[] TOP_NAMES = {
        "guiTop", "field_147009_r", "field_147008_q", "field_147010_s"
    };

    private static final Field GUI_LEFT = resolveFirst(LEFT_NAMES);
    private static final Field GUI_TOP = resolveFirst(TOP_NAMES);

    private static volatile boolean loggedFieldDump;

    private BestArmorGuiLayoutAccess() {}

    private static Field resolveFirst(String[] names) {
        for (String n : names) {
            try {
                Field f = GuiContainer.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static boolean canRead() {
        return GUI_LEFT != null && GUI_TOP != null;
    }

    static void logDiagnosticOnce() {
        if (loggedFieldDump) {
            return;
        }
        loggedFieldDump = true;
        try {
            List<String> lines = new ArrayList<>();
            for (Field f : GuiContainer.class.getDeclaredFields()) {
                lines.add(f.getName() + ":" + f.getType().getSimpleName());
            }
            Collections.sort(lines);
            System.err.println("[gomod] BestArmor: GuiContainer fields (guiLeft/guiTop not found): " + lines);
        } catch (Throwable t) {
            System.err.println("[gomod] BestArmor: could not list GuiContainer fields: " + t.getMessage());
        }
    }

    static int readGuiLeft(GuiContainer c) throws IllegalAccessException {
        return GUI_LEFT.getInt(c);
    }

    static int readGuiTop(GuiContainer c) throws IllegalAccessException {
        return GUI_TOP.getInt(c);
    }
}
