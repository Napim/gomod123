package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.gui.FriendInventoryHUDGUI;
import me.napim.gomod.gui.HorseDonkeySettingsGUI;
import me.napim.gomod.gui.HudOpacitySliderBoxInsets;
import me.napim.gomod.gui.InventoryHUDGUI;
import me.napim.gomod.gui.MobHealthDisplayGUI;
import me.napim.gomod.gui.MoreHudSettingsGUI;
import me.napim.gomod.gui.TeammateTrackerGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Debug tuning for background-opacity slider inner box padding (left / up / right / down).
 * Open the module settings GUI, run commands, then {@code dump} and send values to bake as defaults.
 */
public final class HudOpacityBoxDebugCommand extends Command {

    private static final int STEP = 1;
    private static final int MIN_PX = -48;
    private static final int MAX_PX = 48;

    public HudOpacityBoxDebugCommand() {
        super("hudopacitybox", new String[] { "hobox", "opacitybox" });
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            sendHelp(null);
            sendAllStatus();
            return;
        }

        String cmd = args[0].trim().toLowerCase();
        if ("help".equals(cmd) || "?".equals(cmd)) {
            sendHelp(null);
            return;
        }
        if ("overlay".equals(cmd)) {
            HudOpacitySliderBoxInsets.toggleDebugOverlay();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Opacity box debug overlay: "
                    + (HudOpacitySliderBoxInsets.isDebugOverlay()
                    ? EnumChatFormatting.AQUA + "ON" + EnumChatFormatting.GREEN + " (green = inner fill area)"
                    : EnumChatFormatting.RED + "OFF"));
            return;
        }
        if ("resetall".equals(cmd)) {
            HudOpacitySliderBoxInsets.resetAll();
            ConfigManager.saveConfig();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "All opacity box insets reset to shipped defaults.");
            sendAllStatus();
            return;
        }
        if ("dump".equals(cmd) || "export".equals(cmd)) {
            dumpForDev();
            return;
        }
        if ("list".equals(cmd) || "modules".equals(cmd)) {
            sendModuleList();
            return;
        }
        if ("gui".equals(cmd)) {
            if (args.length < 2) {
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /hudopacitybox gui <module>");
                sendModuleList();
                return;
            }
            HudOpacitySliderBoxInsets.Module module = HudOpacitySliderBoxInsets.parseModule(args[1]);
            if (module == null) {
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown module: " + args[1]);
                sendModuleList();
                return;
            }
            openGui(module);
            sendStatus(module);
            return;
        }

        HudOpacitySliderBoxInsets.Module module = HudOpacitySliderBoxInsets.parseModule(args[0]);
        if (module == null) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown module: " + args[0]);
            sendModuleList();
            return;
        }

        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            sendStatus(module);
            return;
        }
        if ("reset".equalsIgnoreCase(args[1])) {
            HudOpacitySliderBoxInsets.reset(module);
            ConfigManager.saveConfig();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel()
                    + " opacity box reset to shipped default.");
            sendStatus(module);
            return;
        }

        // Shorthand: /hudopacitybox fps +7  →  add +7 px to left, up, right, and down (same as four side commands).
        if (args.length == 2) {
            int deltaAll = parseDelta(args[1]);
            if (deltaAll != Integer.MIN_VALUE) {
                for (HudOpacitySliderBoxInsets.Side side : HudOpacitySliderBoxInsets.Side.values()) {
                    HudOpacitySliderBoxInsets.BoxInsets cur = HudOpacitySliderBoxInsets.get(module);
                    int next = clamp(cur.get(side) + deltaAll);
                    HudOpacitySliderBoxInsets.set(module, side, next);
                }
                ConfigManager.saveConfig();
                send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel()
                        + " all sides " + EnumChatFormatting.WHITE + (deltaAll >= 0 ? "+" + deltaAll : String.valueOf(deltaAll))
                        + " px" + EnumChatFormatting.GRAY + " (saved — full form: /hudopacitybox fps left +7)");
                if (!HudOpacitySliderBoxInsets.isDebugOverlay()) {
                    send(EnumChatFormatting.DARK_GRAY + "Tip: /hudopacitybox overlay — yellow = bar, green = inner fill");
                }
                sendStatus(module);
                return;
            }
            Integer setAll = parseSetValue(args[1]);
            if (setAll != null) {
                for (HudOpacitySliderBoxInsets.Side side : HudOpacitySliderBoxInsets.Side.values()) {
                    HudOpacitySliderBoxInsets.set(module, side, setAll);
                }
                ConfigManager.saveConfig();
                send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel()
                        + " all sides = " + EnumChatFormatting.WHITE + setAll + " px" + EnumChatFormatting.GRAY + " (saved)");
                sendStatus(module);
                return;
            }
        }

        if (args.length < 3) {
            sendHelp(module);
            sendStatus(module);
            return;
        }

        HudOpacitySliderBoxInsets.Side side = HudOpacitySliderBoxInsets.parseSide(args[1]);
        if (side == null) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown side: " + args[1]
                    + EnumChatFormatting.GRAY + " (use left, up, right, down)");
            return;
        }

        String valueToken = joinValueToken(args, 2);
        if ("reset".equalsIgnoreCase(valueToken)) {
            HudOpacitySliderBoxInsets.set(module, side, shippedDefault(module, side));
            ConfigManager.saveConfig();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel() + " "
                    + side.getId() + " reset to " + shippedDefault(module, side) + " px.");
            sendStatus(module);
            return;
        }

        Integer absolute = parseSetValue(valueToken);
        if (absolute != null) {
            HudOpacitySliderBoxInsets.set(module, side, absolute);
            ConfigManager.saveConfig();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel() + " "
                    + side.getId() + " = " + EnumChatFormatting.WHITE + absolute + " px"
                    + EnumChatFormatting.GRAY + " (saved)");
            sendStatus(module);
            return;
        }

        int delta = parseDelta(valueToken);
        if (delta == Integer.MIN_VALUE) {
            sendHelp(module);
            return;
        }

        HudOpacitySliderBoxInsets.BoxInsets current = HudOpacitySliderBoxInsets.get(module);
        int next = clamp(current.get(side) + delta);
        HudOpacitySliderBoxInsets.set(module, side, next);
        ConfigManager.saveConfig();
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + module.getLabel() + " "
                + side.getId() + ": " + EnumChatFormatting.WHITE + next + " px"
                + EnumChatFormatting.GRAY + " (saved — use /hudopacitybox dump when done)");
        if (!HudOpacitySliderBoxInsets.isDebugOverlay()) {
            send(EnumChatFormatting.DARK_GRAY + "Tip: /hudopacitybox overlay — yellow = slider, green = inner fill box");
        }
        sendStatus(module);
    }

    /** Joins args from index onward; strips spaces so \"+ 10\" and split \"+\" \"10\" both work. */
    private static String joinValueToken(String[] args, int from) {
        if (from >= args.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    private static int shippedDefault(HudOpacitySliderBoxInsets.Module module, HudOpacitySliderBoxInsets.Side side) {
        HudOpacitySliderBoxInsets.BoxInsets d = HudOpacitySliderBoxInsets.getShippedDefault(module);
        return d.get(side);
    }

    private static void sendStatus(HudOpacitySliderBoxInsets.Module module) {
        HudOpacitySliderBoxInsets.BoxInsets ins = HudOpacitySliderBoxInsets.get(module);
        HudOpacitySliderBoxInsets.BoxInsets shipped = HudOpacitySliderBoxInsets.getShippedDefault(module);
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[" + module.getLabel() + " opacity box]");
        send(EnumChatFormatting.GRAY + "Current: " + EnumChatFormatting.WHITE + ins.format()
                + EnumChatFormatting.GRAY + "  shipped " + shipped.format());
        send(EnumChatFormatting.DARK_GRAY + "Step " + STEP + " px. Shorthand: /hudopacitybox " + module.getId()
                + " +3  |  Per-side: /hudopacitybox " + module.getId() + " left +1  |  /hudopacitybox gui "
                + module.getId() + "  |  /hudopacitybox overlay");
    }

    private static void sendAllStatus() {
        send(EnumChatFormatting.GRAY + "Opacity box insets (current) — " + EnumChatFormatting.WHITE
                + "/hudopacitybox dump" + EnumChatFormatting.GRAY + " for copy-paste:");
        for (HudOpacitySliderBoxInsets.Module m : HudOpacitySliderBoxInsets.Module.values()) {
            HudOpacitySliderBoxInsets.BoxInsets ins = HudOpacitySliderBoxInsets.get(m);
            send(EnumChatFormatting.AQUA + m.getId() + EnumChatFormatting.GRAY + ": "
                    + EnumChatFormatting.WHITE + ins.format());
        }
    }

    private static void dumpForDev() {
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[Opacity box — send this block to dev]");
        for (HudOpacitySliderBoxInsets.Module m : HudOpacitySliderBoxInsets.Module.values()) {
            if (m == HudOpacitySliderBoxInsets.Module.DEFAULT) {
                continue;
            }
            HudOpacitySliderBoxInsets.BoxInsets ins = HudOpacitySliderBoxInsets.get(m);
            HudOpacitySliderBoxInsets.BoxInsets shipped = HudOpacitySliderBoxInsets.getShippedDefault(m);
            if (ins.left == shipped.left && ins.up == shipped.up && ins.right == shipped.right
                    && ins.down == shipped.down) {
                continue;
            }
            send(EnumChatFormatting.WHITE + m.getId() + ": left=" + ins.left + " up=" + ins.up + " right=" + ins.right
                    + " down=" + ins.down);
        }
        send(EnumChatFormatting.DARK_GRAY + "Only non-default modules are listed.");
    }

    private static void sendModuleList() {
        StringBuilder sb = new StringBuilder(EnumChatFormatting.GRAY + "Modules: ");
        boolean first = true;
        for (HudOpacitySliderBoxInsets.Module m : HudOpacitySliderBoxInsets.Module.values()) {
            if (m == HudOpacitySliderBoxInsets.Module.DEFAULT) {
                continue;
            }
            if (!first) {
                sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
            }
            sb.append(EnumChatFormatting.AQUA).append(m.getId());
            first = false;
        }
        send(sb.toString());
    }

    private static void sendHelp(HudOpacitySliderBoxInsets.Module module) {
        String mod = module != null ? module.getId() : "<module>";
        send(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage:");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox " + mod + " — status");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox " + mod + " +N|-N|setN — all sides (shorthand)");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox " + mod + " left|up|right|down [+N|-N|N|setN|reset]");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox " + mod + " reset — all sides to default");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox gui " + mod + " — open settings screen");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox overlay — green inner-fill guide");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox dump — values to send dev");
        send(EnumChatFormatting.GRAY + "/" + "hudopacitybox resetall");
        send(EnumChatFormatting.DARK_GRAY + "Tune with the module GUI open. Adjusts the opacity slider inner fill; "
                + EnumChatFormatting.DARK_GRAY + "for " + EnumChatFormatting.AQUA + "fps"
                + EnumChatFormatting.DARK_GRAY + " it also resizes the in-game FPS text background. "
                + "Use /hudopacitybox overlay to see yellow/green guides.");
    }

    private static void openGui(HudOpacitySliderBoxInsets.Module module) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen parent = mc.currentScreen;
        switch (module) {
            case FPS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.FPS));
                break;
            case CPS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.CPS));
                break;
            case PING:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.PING));
                break;
            case POTIONS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.POTIONS));
                break;
            case ARROWS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.ARROWS));
                break;
            case KILL_EFFECTS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.KILL_EFFECTS));
                break;
            case FLAME:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.FLAME));
                break;
            case COORDS:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.COORDS));
                break;
            case BLITZ_KIT_TIMER:
                mc.displayGuiScreen(new MoreHudSettingsGUI(parent, MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER));
                break;
            case MOB_HEALTH:
                mc.displayGuiScreen(new MobHealthDisplayGUI(parent));
                break;
            case INVENTORY_HUD:
                mc.displayGuiScreen(new InventoryHUDGUI(parent));
                break;
            case FRIEND_INV_HUD:
                mc.displayGuiScreen(new FriendInventoryHUDGUI(parent));
                break;
            case TEAMMATE_TRACKER:
                mc.displayGuiScreen(new TeammateTrackerGUI(parent));
                break;
            case HORSE_DONKEY:
                mc.displayGuiScreen(new HorseDonkeySettingsGUI(parent));
                break;
            default:
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No settings GUI mapped for " + module.getId());
                break;
        }
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
        String t = token.trim().replace(" ", "");
        if (t.isEmpty() || "+".equals(t) || "-".equals(t)) {
            return Integer.MIN_VALUE;
        }
        try {
            if (t.startsWith("+")) {
                String num = t.substring(1);
                if (num.isEmpty()) {
                    return STEP;
                }
                int n = Integer.parseInt(num);
                return n == 0 ? STEP : n;
            }
            if (t.startsWith("-")) {
                int n = Integer.parseInt(t);
                return n == 0 ? -STEP : n;
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static int clamp(int px) {
        return Math.max(MIN_PX, Math.min(MAX_PX, px));
    }

    private static void send(String msg) {
        Main.sendMessage(msg);
    }
}
