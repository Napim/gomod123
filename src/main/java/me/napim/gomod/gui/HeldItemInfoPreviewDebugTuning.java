package me.napim.gomod.gui;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Session-only debug tuning for held-item preview layout inside settings GUIs.
 * Values are intentionally not persisted; use the debug command dump output to
 * report final offsets that should become new shipped defaults.
 */
public final class HeldItemInfoPreviewDebugTuning {

    public enum Part {
        HOTBAR("hotbar", "Hotbar"),
        XP_BAR("xpbar", "XP bar"),
        XP_LEVEL("xplevel", "XP level"),
        ARMOR("armor", "Armor row"),
        HEARTS("hearts", "Hearts row"),
        FOOD("food", "Food row"),
        NAME("name", "Item name"),
        ENCHANTS("enchants", "Enchant lines");

        private final String id;
        private final String label;

        Part(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }
    }

    public enum Axis {
        X("x"),
        Y("y");

        private final String id;

        Axis(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    public static final class Offset {
        public int x;
        public int y;

        private Offset() {
        }

        private Offset copy() {
            Offset o = new Offset();
            o.x = x;
            o.y = y;
            return o;
        }

        public String format() {
            return "x=" + x + " y=" + y;
        }
    }

    private static final Map<Part, Offset> OFFSETS = new EnumMap<>(Part.class);
    private static boolean overlay;

    static {
        resetAll();
    }

    private HeldItemInfoPreviewDebugTuning() {
    }

    public static Offset get(Part part) {
        Offset offset = OFFSETS.get(part);
        return offset == null ? new Offset() : offset.copy();
    }

    public static int getX(Part part) {
        Offset offset = OFFSETS.get(part);
        return offset == null ? 0 : offset.x;
    }

    public static int getY(Part part) {
        Offset offset = OFFSETS.get(part);
        return offset == null ? 0 : offset.y;
    }

    public static void set(Part part, Axis axis, int value) {
        Offset offset = OFFSETS.get(part);
        if (offset == null) {
            offset = new Offset();
            OFFSETS.put(part, offset);
        }
        if (axis == Axis.X) {
            offset.x = value;
        } else if (axis == Axis.Y) {
            offset.y = value;
        }
    }

    public static void adjust(Part part, Axis axis, int delta) {
        set(part, axis, (axis == Axis.X ? getX(part) : getY(part)) + delta);
    }

    public static void reset(Part part) {
        OFFSETS.put(part, shippedDefault(part));
    }

    public static void resetAll() {
        OFFSETS.clear();
        for (Part part : Part.values()) {
            OFFSETS.put(part, shippedDefault(part));
        }
        overlay = false;
    }

    public static boolean isOverlayEnabled() {
        return overlay;
    }

    public static void setOverlayEnabled(boolean enabled) {
        overlay = enabled;
    }

    public static void toggleOverlay() {
        overlay = !overlay;
    }

    public static Part parsePart(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (Part part : Part.values()) {
            String id = part.id.replace("_", "");
            if (id.equals(key)) {
                return part;
            }
        }
        if ("xp".equals(key) || "bar".equals(key)) {
            return Part.XP_BAR;
        }
        if ("level".equals(key) || "xpleveltext".equals(key)) {
            return Part.XP_LEVEL;
        }
        if ("health".equals(key) || "hp".equals(key)) {
            return Part.HEARTS;
        }
        if ("itemname".equals(key)) {
            return Part.NAME;
        }
        if ("enchant".equals(key) || "enchanttext".equals(key) || "sharpness".equals(key)) {
            return Part.ENCHANTS;
        }
        return null;
    }

    public static Axis parseAxis(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if ("x".equals(key)) {
            return Axis.X;
        }
        if ("y".equals(key)) {
            return Axis.Y;
        }
        return null;
    }

    private static Offset shippedDefault(Part part) {
        Offset offset = new Offset();
        if (part == null) {
            return offset;
        }
        switch (part) {
            case XP_BAR:
                offset.y = 3;
                break;
            case XP_LEVEL:
                offset.y = 7;
                break;
            case ARMOR:
                offset.y = 2;
                break;
            case HEARTS:
                offset.y = 2;
                break;
            case FOOD:
                offset.y = 2;
                break;
            case NAME:
                offset.y = 13;
                break;
            case ENCHANTS:
                offset.y = -1;
                break;
            case HOTBAR:
            default:
                break;
        }
        return offset;
    }
}
