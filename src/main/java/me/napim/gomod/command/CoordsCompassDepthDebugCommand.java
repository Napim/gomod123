package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MoreHudCoordsFeature;
import me.napim.gomod.gui.MoreHudCoordsColorsGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Debug screen-space offsets for the coords compass (Compass / Compass-only layouts).
 * Positive values move parts away from the center on screen — not draw-order / Z depth.
 */
public final class CoordsCompassDepthDebugCommand {

    private static final int STEP = 5;
    private static final int MIN_PX = -64;
    private static final int MAX_PX = 64;

    private CoordsCompassDepthDebugCommand() {
    }

    public static final class Dot extends Command {
        public Dot() {
            super("coordscompassdot", new String[] { "ccdot", "coordsdotz" });
        }

        @Override
        public void handle(String[] args) {
            handleLayer(Layer.DOT, args);
        }
    }

    public static final class Ring extends Command {
        public Ring() {
            super("coordscompassring", new String[] { "ccring", "coordsringz" });
        }

        @Override
        public void handle(String[] args) {
            handleLayer(Layer.RING, args);
        }
    }

    public static final class Cardinals extends Command {
        public Cardinals() {
            super("coordscompasscard", new String[] { "cccard", "coordscardz", "coordscompasscardinals" });
        }

        @Override
        public void handle(String[] args) {
            handleLayer(Layer.CARDINALS, args);
        }
    }

    private enum Layer {
        RING("Compass ring (circle radius)", "coordscompassring",
                "+ = bigger circle, − = smaller") {
            @Override
            int get() {
                return ConfigManager.getMoreHudCoordsCompassRingRadiusOffset();
            }

            @Override
            void set(int px) {
                ConfigManager.setMoreHudCoordsCompassRingRadiusOffset(px);
            }

            @Override
            int defaultPx() {
                return MoreHudCoordsFeature.DEFAULT_COMPASS_RING_RADIUS_OFFSET;
            }
        },
        DOT("Compass dot (heading marker on ring)", "coordscompassdot",
                "+ = farther from center along the ring") {
            @Override
            int get() {
                return ConfigManager.getMoreHudCoordsCompassDotOrbitOffset();
            }

            @Override
            void set(int px) {
                ConfigManager.setMoreHudCoordsCompassDotOrbitOffset(px);
            }

            @Override
            int defaultPx() {
                return MoreHudCoordsFeature.DEFAULT_COMPASS_DOT_ORBIT_OFFSET;
            }
        },
        CARDINALS("Compass cardinals (N/E/S/W)", "coordscompasscard",
                "+ = N up, S down, E right, W left (away from center)") {
            @Override
            int get() {
                return ConfigManager.getMoreHudCoordsCompassCardinalOutset();
            }

            @Override
            void set(int px) {
                ConfigManager.setMoreHudCoordsCompassCardinalOutset(px);
            }

            @Override
            int defaultPx() {
                return MoreHudCoordsFeature.DEFAULT_COMPASS_CARDINAL_OUTSET;
            }
        };

        final String label;
        final String commandName;
        final String effectHint;

        Layer(String label, String commandName, String effectHint) {
            this.label = label;
            this.commandName = commandName;
            this.effectHint = effectHint;
        }

        abstract int get();

        abstract void set(int px);

        abstract int defaultPx();
    }

    private static void handleLayer(Layer layer, String[] args) {
        if (args.length > 0 && "resetall".equalsIgnoreCase(args[0])) {
            resetAllLayers();
            sendStatus(layer);
            return;
        }

        if (!MoreHudCoordsFeature.usesCompassRingLayout()) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Compass position tweaks only apply to the "
                    + EnumChatFormatting.AQUA + "Compass"
                    + EnumChatFormatting.RED + " or "
                    + EnumChatFormatting.AQUA + "Compass only"
                    + EnumChatFormatting.RED + " coords layout.");
            send(EnumChatFormatting.GRAY + "More HUD → Coords → Layout (yours is "
                    + EnumChatFormatting.WHITE + layoutName()
                    + EnumChatFormatting.GRAY + "). Then use "
                    + EnumChatFormatting.WHITE + "/" + layer.commandName + " gui"
                    + EnumChatFormatting.GRAY + " to preview.");
            if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
                sendStatus(layer);
            }
            return;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(layer);
            return;
        }
        if ("reset".equalsIgnoreCase(args[0])) {
            layer.set(layer.defaultPx());
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + layer.label + " offset reset to "
                    + layer.defaultPx() + " px (saved).");
            sendStatus(layer);
            return;
        }
        if ("gui".equalsIgnoreCase(args[0])) {
            openCoordsColorsGui();
            sendStatus(layer);
            return;
        }

        if (args[0].toLowerCase().startsWith("set")) {
            Integer absolute = parseSetValue(args[0]);
            if (absolute == null) {
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid value. Example: /" + layer.commandName
                        + " set 5");
                return;
            }
            layer.set(absolute);
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + layer.label + " offset set to "
                    + EnumChatFormatting.WHITE + absolute + " px" + EnumChatFormatting.GRAY + " (saved)");
            sendStatus(layer);
            return;
        }

        int delta = parseDelta(args[0]);
        if (delta == Integer.MIN_VALUE) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /" + layer.commandName
                    + " [+N|-N|set N|status|reset|resetall|gui]");
            send(EnumChatFormatting.GRAY + layer.effectHint + ". Example: "
                    + EnumChatFormatting.WHITE + "/" + layer.commandName + " +5"
                    + EnumChatFormatting.GRAY + " (step " + STEP + "). Preview: "
                    + EnumChatFormatting.WHITE + "/" + layer.commandName + " gui");
            return;
        }

        int next = clamp(layer.get() + delta);
        layer.set(next);
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + layer.label + ": "
                + EnumChatFormatting.WHITE + next + " px"
                + EnumChatFormatting.GRAY + " (saved — tell dev this value to keep it)");
        sendStatus(layer);
    }

    private static void resetAllLayers() {
        ConfigManager.setMoreHudCoordsCompassRingRadiusOffset(MoreHudCoordsFeature.DEFAULT_COMPASS_RING_RADIUS_OFFSET);
        ConfigManager.setMoreHudCoordsCompassDotOrbitOffset(MoreHudCoordsFeature.DEFAULT_COMPASS_DOT_ORBIT_OFFSET);
        ConfigManager.setMoreHudCoordsCompassCardinalOutset(MoreHudCoordsFeature.DEFAULT_COMPASS_CARDINAL_OUTSET);
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "All compass position offsets reset to defaults.");
    }

    private static String layoutName() {
        switch (ConfigManager.getMoreHudCoordsLayout()) {
            case ConfigManager.COORDS_LAYOUT_COMPASS:
                return "Vertical + Compass";
            case ConfigManager.COORDS_LAYOUT_COMPASS_ONLY:
                return "Compass only";
            case ConfigManager.COORDS_LAYOUT_VERTICAL:
            default:
                return "Vertical";
        }
    }

    private static void sendStatus(Layer layer) {
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[Coords compass position]");
        send(EnumChatFormatting.GRAY + "Layout: " + EnumChatFormatting.WHITE + layoutName()
                + (MoreHudCoordsFeature.usesCompassRingLayout()
                ? EnumChatFormatting.GREEN + " — commands apply"
                : EnumChatFormatting.RED + " — switch to Compass layout"));
        send(EnumChatFormatting.GRAY + layer.label + " (saved): " + EnumChatFormatting.WHITE + layer.get()
                + EnumChatFormatting.GRAY + " px  default " + layer.defaultPx());
        send(EnumChatFormatting.DARK_GRAY + layer.effectHint);
        send(EnumChatFormatting.GRAY + "All offsets — ring "
                + EnumChatFormatting.WHITE + ConfigManager.getMoreHudCoordsCompassRingRadiusOffset()
                + EnumChatFormatting.GRAY + " px, dot "
                + EnumChatFormatting.WHITE + ConfigManager.getMoreHudCoordsCompassDotOrbitOffset()
                + EnumChatFormatting.GRAY + " px, cardinals "
                + EnumChatFormatting.WHITE + ConfigManager.getMoreHudCoordsCompassCardinalOutset()
                + EnumChatFormatting.GRAY + " px");
        send(EnumChatFormatting.DARK_GRAY + "Step " + STEP + ". Examples: /" + layer.commandName + " +5 | /"
                + layer.commandName + " resetall | /" + layer.commandName + " gui");
    }

    private static Integer parseSetValue(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase();
        if (!t.startsWith("set")) {
            return null;
        }
        try {
            return clamp(Integer.parseInt(t.substring(3).trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseDelta(String token) {
        if (token == null || token.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        String t = token.trim();
        try {
            if (t.startsWith("+")) {
                return Integer.parseInt(t.substring(1));
            }
            if (t.startsWith("-")) {
                return Integer.parseInt(t);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static int clamp(int px) {
        return Math.max(MIN_PX, Math.min(MAX_PX, px));
    }

    private static void openCoordsColorsGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen parent = mc.currentScreen;
        mc.displayGuiScreen(new MoreHudCoordsColorsGUI(parent));
    }

    private static void send(String msg) {
        Main.sendMessage(msg);
    }
}
