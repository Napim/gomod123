package me.napim.gomod.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-module padding inside background-opacity slider rows (left / up / right / down).
 * Tuned via {@link me.napim.gomod.command.HudOpacityBoxDebugCommand} until defaults are baked in.
 */
public final class HudOpacitySliderBoxInsets {

    public enum Module {
        DEFAULT("default", "Default"),
        FPS("fps", "FPS"),
        CPS("cps", "CPS"),
        PING("ping", "Ping"),
        POTIONS("potions", "Potions"),
        ARROWS("arrows", "Arrows"),
        ARMOR_STATUS("armor_status", "Armor status"),
        KILL_EFFECTS("kill_effects", "Kill effects"),
        FLAME("flame", "Flame"),
        COORDS("coords", "Coords"),
        BLITZ_KIT_TIMER("blitz_kit_timer", "Blitz kit timer"),
        MOB_HEALTH("mob_health", "Mob health"),
        INVENTORY_HUD("inventory_hud", "Inventory HUD"),
        FRIEND_INV_HUD("friend_inv_hud", "Friend inventory HUD"),
        TEAMMATE_TRACKER("teammate_tracker", "Teammate tracker"),
        HORSE_DONKEY("horse_donkey", "Horse tracker");

        private final String id;
        private final String label;

        Module(String id, String label) {
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

    public enum Side {
        LEFT("left", "l"),
        UP("up", "u"),
        RIGHT("right", "r"),
        DOWN("down", "d");

        private final String id;
        private final String alias;

        Side(String id, String alias) {
            this.id = id;
            this.alias = alias;
        }

        public String getId() {
            return id;
        }
    }

    public static final class BoxInsets {
        public int left = 1;
        public int up = 1;
        public int right = 1;
        public int down = 1;

        public BoxInsets copy() {
            BoxInsets c = new BoxInsets();
            c.left = left;
            c.up = up;
            c.right = right;
            c.down = down;
            return c;
        }

        public void set(Side side, int px) {
            switch (side) {
                case LEFT:
                    left = px;
                    break;
                case UP:
                    up = px;
                    break;
                case RIGHT:
                    right = px;
                    break;
                case DOWN:
                    down = px;
                    break;
                default:
                    break;
            }
        }

        public int get(Side side) {
            switch (side) {
                case LEFT:
                    return left;
                case UP:
                    return up;
                case RIGHT:
                    return right;
                case DOWN:
                default:
                    return down;
            }
        }

        public String format() {
            return "L=" + left + " U=" + up + " R=" + right + " D=" + down;
        }
    }

    private static final int MIN_INSET = -48;
    /** Debug tuning range via {@link me.napim.gomod.command.HudOpacityBoxDebugCommand}. */
    private static final int MAX_INSET = 48;
    private static final BoxInsets SHIPPED_DEFAULT = insets(1, 1, 1, 1);
    private static final Map<Module, BoxInsets> SHIPPED_DEFAULTS = new EnumMap<>(Module.class);
    private static final Map<Module, BoxInsets> overrides = new EnumMap<>(Module.class);
    private static boolean debugOverlay;

    static {
        SHIPPED_DEFAULTS.put(Module.FPS, insets(2, 3, 1, 2));
        SHIPPED_DEFAULTS.put(Module.CPS, insets(2, 3, 0, 2));
        SHIPPED_DEFAULTS.put(Module.PING, insets(2, 2, 0, 2));
        SHIPPED_DEFAULTS.put(Module.FLAME, insets(1, 1, 1, 1));
        SHIPPED_DEFAULTS.put(Module.COORDS, insets(3, 1, 1, 1));
    }

    private HudOpacitySliderBoxInsets() {
    }

    /**
     * Pixel padding around HUD text used when drawing a background rect (in-game), separate from the
     * settings-slider inner box.
     */
    public static final class TextBackgroundPad {
        public final int left;
        public final int up;
        public final int right;
        public final int down;

        public TextBackgroundPad(int left, int up, int right, int down) {
            this.left = left;
            this.up = up;
            this.right = right;
            this.down = down;
        }
    }

    public static BoxInsets get(Module module) {
        Module key = module != null ? module : Module.DEFAULT;
        BoxInsets override = overrides.get(key);
        if (override == null) {
            return getShippedDefault(key);
        }
        return override.copy();
    }

    /**
     * Background padding for a text row from shipped/debug {@link BoxInsets}.
     * {@code legacy*} is the padding when all inset sides are {@code 1}.
     */
    public static TextBackgroundPad textRowBackgroundPad(Module module, int legacyLeft, int legacyUp, int legacyRight,
            int legacyDown) {
        BoxInsets ins = get(module);
        return new TextBackgroundPad(
                legacyLeft + Math.max(0, ins.left - 1),
                legacyUp + Math.max(0, ins.up - 1),
                legacyRight + Math.max(0, ins.right - 1),
                legacyDown + Math.max(0, ins.down - 1));
    }

    /**
     * Same as {@link #textRowBackgroundPad(Module, int, int, int, int)} but preserves negative deltas too.
     * Use only for modules where tighter-than-legacy HUD backgrounds are intentional.
     */
    public static TextBackgroundPad textRowBackgroundPadSigned(Module module, int legacyLeft, int legacyUp, int legacyRight,
            int legacyDown) {
        BoxInsets ins = get(module);
        return new TextBackgroundPad(
                legacyLeft + (ins.left - 1),
                legacyUp + (ins.up - 1),
                legacyRight + (ins.right - 1),
                legacyDown + (ins.down - 1));
    }

    /**
     * FPS / CPS / ping split rows. Shadow-off uses a tighter box; shadow-on keeps the historical {@code 3,1,3,0}
     * tuning (do not stack extra shadow pixels on top — that was double-counting).
     */
    public static TextBackgroundPad splitCenteredTextRowPad(Module module, boolean textShadow) {
        if (textShadow) {
            return textRowBackgroundPad(module, 3, 1, 3, 0);
        }
        return textRowBackgroundPad(module, 2, 1, 2, 0);
    }

    public static TextBackgroundPad splitCenteredTextRowPadSigned(Module module, boolean textShadow) {
        if (textShadow) {
            return textRowBackgroundPadSigned(module, 3, 1, 3, 0);
        }
        return textRowBackgroundPadSigned(module, 2, 1, 2, 0);
    }

    /** Centered single-line rows (blitz kit timer). */
    public static TextBackgroundPad symmetricCenteredTextRowPad(Module module, boolean textShadow) {
        if (textShadow) {
            return textRowBackgroundPad(module, 3, 3, 3, 3);
        }
        return textRowBackgroundPad(module, 2, 3, 2, 3);
    }

    /** Coords HUD block ({@link me.napim.gomod.features.MoreHudCoordsFeature} PAD_X/PAD_Y). */
    public static TextBackgroundPad coordsHudBackgroundPad(boolean textShadow) {
        return textRowBackgroundPad(Module.COORDS, 2, 6, 2, 3);
    }

    public static BoxInsets getShippedDefault() {
        return SHIPPED_DEFAULT.copy();
    }

    public static BoxInsets getShippedDefault(Module module) {
        Module key = module != null ? module : Module.DEFAULT;
        BoxInsets shipped = SHIPPED_DEFAULTS.get(key);
        return (shipped != null ? shipped : SHIPPED_DEFAULT).copy();
    }

    public static void set(Module module, Side side, int px) {
        Module key = module != null ? module : Module.DEFAULT;
        BoxInsets box = overrides.computeIfAbsent(key, k -> getShippedDefault(k));
        box.set(side, clamp(px));
    }

    public static void reset(Module module) {
        if (module == null) {
            overrides.remove(Module.DEFAULT);
            return;
        }
        overrides.remove(module);
    }

    public static void resetAll() {
        overrides.clear();
    }

    public static boolean isDebugOverlay() {
        return debugOverlay;
    }

    public static void setDebugOverlay(boolean on) {
        debugOverlay = on;
    }

    public static void toggleDebugOverlay() {
        debugOverlay = !debugOverlay;
    }

    public static Module parseModule(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (Module m : Module.values()) {
            if (m.id.equals(t) || m.name().toLowerCase(Locale.ROOT).equals(t)) {
                return m;
            }
        }
        if ("ke".equals(t) || "killeffects".equals(t)) {
            return Module.KILL_EFFECTS;
        }
        if ("blitz".equals(t) || "bkt".equals(t)) {
            return Module.BLITZ_KIT_TIMER;
        }
        if ("friend".equals(t) || "friendinv".equals(t) || "shareinv".equals(t)) {
            return Module.FRIEND_INV_HUD;
        }
        if ("inv".equals(t) || "inventory".equals(t)) {
            return Module.INVENTORY_HUD;
        }
        if ("mob".equals(t) || "mobhealth".equals(t)) {
            return Module.MOB_HEALTH;
        }
        if ("horse".equals(t) || "donkey".equals(t)) {
            return Module.HORSE_DONKEY;
        }
        if ("tracker".equals(t) || "teammate".equals(t)) {
            return Module.TEAMMATE_TRACKER;
        }
        return null;
    }

    public static Side parseSide(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (Side s : Side.values()) {
            if (s.id.equals(t) || s.alias.equals(t)) {
                return s;
            }
        }
        return null;
    }

    public static void loadFromConfig(JsonObject root) {
        overrides.clear();
        if (root == null || !root.has("hudOpacityBoxInsetsDebug")) {
            return;
        }
        JsonElement el = root.get("hudOpacityBoxInsetsDebug");
        if (el == null || !el.isJsonObject()) {
            return;
        }
        JsonObject obj = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            Module module = parseModule(e.getKey());
            if (module == null || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject sides = e.getValue().getAsJsonObject();
            BoxInsets box = getShippedDefault(module);
            if (sides.has("left")) {
                box.left = clamp(sides.get("left").getAsInt());
            }
            if (sides.has("up")) {
                box.up = clamp(sides.get("up").getAsInt());
            }
            if (sides.has("right")) {
                box.right = clamp(sides.get("right").getAsInt());
            }
            if (sides.has("down")) {
                box.down = clamp(sides.get("down").getAsInt());
            }
            overrides.put(module, box);
        }
    }

    public static void addToConfig(JsonObject root) {
        if (root == null) {
            return;
        }
        if (overrides.isEmpty()) {
            return;
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<Module, BoxInsets> e : overrides.entrySet()) {
            BoxInsets b = e.getValue();
            BoxInsets shipped = getShippedDefault(e.getKey());
            if (b.left == shipped.left && b.up == shipped.up && b.right == shipped.right && b.down == shipped.down) {
                continue;
            }
            JsonObject sides = new JsonObject();
            sides.addProperty("left", b.left);
            sides.addProperty("up", b.up);
            sides.addProperty("right", b.right);
            sides.addProperty("down", b.down);
            obj.add(e.getKey().id, sides);
        }
        if (obj.entrySet().isEmpty()) {
            return;
        }
        root.add("hudOpacityBoxInsetsDebug", obj);
    }

    public static void draw(FontRenderer fr, Module module, int x, int y, int width, int height, float normalized,
            int accentArgb, String label) {
        normalized = clamp01(normalized);
        BoxInsets ins = get(module);

        Gui.drawRect(x, y, x + width, y + height, 0xFF1A1A1A);

        int innerL = x + ins.left;
        int innerT = y + ins.up;
        int innerR = x + width - ins.right;
        int innerB = y + height - ins.down;
        if (innerR > innerL && innerB > innerT) {
            Gui.drawRect(innerL, innerT, innerR, innerB, 0xFF0F0F0F);
            int innerW = innerR - innerL;
            int fillWidth = Math.max(0, Math.min(innerW, (int) (normalized * innerW)));
            if (fillWidth > 0) {
                Gui.drawRect(innerL, innerT, innerL + fillWidth, innerB, accentArgb);
                if (innerT + 1 < innerB) {
                    Gui.drawRect(innerL, innerT, innerL + fillWidth, innerT + 1, 0x40FFFFFF);
                }
            }
        }

        Gui.drawRect(x, y, x + width, y + 1, 0xFF404040);
        Gui.drawRect(x, y + height - 1, x + width, y + height, 0xFF202020);
        Gui.drawRect(x, y, x + 1, y + height, 0xFF404040);
        Gui.drawRect(x + width - 1, y, x + width, y + height, 0xFF202020);

        if (debugOverlay) {
            Gui.drawRect(x, y, x + width, y + 1, 0xFFFFFF00);
            Gui.drawRect(x, y + height - 1, x + width, y + height, 0xFFFFFF00);
            Gui.drawRect(x, y, x + 1, y + height, 0xFFFFFF00);
            Gui.drawRect(x + width - 1, y, x + width, y + height, 0xFFFFFF00);
            if (innerR > innerL && innerB > innerT) {
                Gui.drawRect(innerL, innerT, innerR, innerT + 1, 0xFF00FF00);
                Gui.drawRect(innerL, innerB - 1, innerR, innerB, 0xFF00FF00);
                Gui.drawRect(innerL, innerT, innerL + 1, innerB, 0xFF00FF00);
                Gui.drawRect(innerR - 1, innerT, innerR, innerB, 0xFF00FF00);
            }
            if (fr != null) {
                String hint = ins.format();
                fr.drawString(hint, x, Math.max(0, y - 10), 0xFF55FF55, false);
            }
        }

        drawCenteredLabel(fr, label, x, y, width, height);
    }

    public static void drawValueBar(FontRenderer fr, Module module, int x, int y, int width, int height, int value0to255,
            int accentArgb, String label) {
        float normalized = value0to255 / 255.0f;
        draw(fr, module, x, y, width, height, normalized, accentArgb, label);
    }

    /** Inset-aware mapping (inner fill box). Used only for debug alignment, not dragging. */
    public static float normalizedFromMouseX(int mouseX, int x, int width, Module module) {
        BoxInsets ins = get(module);
        int innerL = x + ins.left;
        int innerW = Math.max(1, width - ins.left - ins.right);
        return clamp01((float) ((mouseX - (double) innerL) / (double) innerW));
    }

    public static int opacityFromMouseX(int mouseX, int x, int width, Module module) {
        return Math.round(normalizedFromMouseX(mouseX, x, width, module) * 255.0f);
    }

    /** Full slider row — same feel as legacy {@link HudSliderStyle#FILL_BAR} drag (edge to edge). */
    public static float normalizedFromMouseXOuter(int mouseX, int x, int width) {
        return clamp01((mouseX - (x + 1)) / (float) Math.max(1, width - 2));
    }

    public static int opacityFromMouseXOuter(int mouseX, int x, int width) {
        return Math.round(normalizedFromMouseXOuter(mouseX, x, width) * 255.0f);
    }

    private static void drawCenteredLabel(FontRenderer fr, String label, int x, int y, int width, int height) {
        if (label == null || label.isEmpty() || fr == null) {
            return;
        }
        int textWidth = fr.getStringWidth(label);
        int textX = x + (width - textWidth) / 2;
        int textY = y + (height - 8) / 2;
        fr.drawString(label, textX + 1, textY + 1, 0x80000000, false);
        fr.drawString(label, textX, textY, 0xFFFFFFFF, false);
    }

    private static int clamp(int px) {
        return Math.max(MIN_INSET, Math.min(MAX_INSET, px));
    }

    private static BoxInsets insets(int left, int up, int right, int down) {
        BoxInsets box = new BoxInsets();
        box.left = left;
        box.up = up;
        box.right = right;
        box.down = down;
        return box;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
