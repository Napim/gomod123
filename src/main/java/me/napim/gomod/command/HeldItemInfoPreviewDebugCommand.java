package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.gui.HeldItemInfoPreviewDebugTuning;
import me.napim.gomod.gui.HeldItemInfoSettingsGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Session-only tuning command for the held-item preview box in settings GUIs.
 */
public final class HeldItemInfoPreviewDebugCommand extends Command {

    public HeldItemInfoPreviewDebugCommand() {
        super("helditempreview", new String[] { "hipreview", "helditembox" });
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
            HeldItemInfoPreviewDebugTuning.toggleOverlay();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Held-item preview overlay: "
                    + (HeldItemInfoPreviewDebugTuning.isOverlayEnabled()
                    ? EnumChatFormatting.AQUA + "ON"
                    : EnumChatFormatting.RED + "OFF"));
            return;
        }
        if ("dump".equals(cmd) || "export".equals(cmd)) {
            dumpForDev();
            return;
        }
        if ("resetall".equals(cmd)) {
            HeldItemInfoPreviewDebugTuning.resetAll();
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Held-item preview offsets reset.");
            sendAllStatus();
            return;
        }
        if ("parts".equals(cmd) || "list".equals(cmd)) {
            sendPartList();
            return;
        }
        if ("gui".equals(cmd) || "open".equals(cmd)) {
            openSettingsGui();
            return;
        }
        if ("colors".equals(cmd) || "colours".equals(cmd)) {
            openColorsGui();
            return;
        }

        HeldItemInfoPreviewDebugTuning.Part part = HeldItemInfoPreviewDebugTuning.parsePart(args[0]);
        if (part == null) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown part: " + args[0]);
            sendPartList();
            return;
        }

        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            sendStatus(part);
            return;
        }
        if ("reset".equalsIgnoreCase(args[1])) {
            HeldItemInfoPreviewDebugTuning.reset(part);
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + part.getLabel() + " reset to x=0 y=0.");
            sendStatus(part);
            return;
        }

        if (args.length < 3) {
            sendHelp(part);
            sendStatus(part);
            return;
        }

        HeldItemInfoPreviewDebugTuning.Axis axis = HeldItemInfoPreviewDebugTuning.parseAxis(args[1]);
        if (axis == null) {
            send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown axis: " + args[1]
                    + EnumChatFormatting.GRAY + " (use x or y)");
            return;
        }

        String token = joinValueToken(args, 2);
        if ("reset".equalsIgnoreCase(token)) {
            HeldItemInfoPreviewDebugTuning.set(part, axis, 0);
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + part.getLabel() + " " + axis.getId() + " reset.");
            sendStatus(part);
            return;
        }

        Integer absolute = parseAbsolute(token);
        if (absolute != null) {
            HeldItemInfoPreviewDebugTuning.set(part, axis, absolute);
            send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + part.getLabel() + " " + axis.getId()
                    + " = " + EnumChatFormatting.WHITE + absolute + " px");
            sendStatus(part);
            return;
        }

        Integer delta = parseDelta(token);
        if (delta == null) {
            sendHelp(part);
            return;
        }

        HeldItemInfoPreviewDebugTuning.adjust(part, axis, delta);
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + part.getLabel() + " " + axis.getId()
                + " " + EnumChatFormatting.WHITE + (delta >= 0 ? "+" + delta : String.valueOf(delta)) + " px");
        sendStatus(part);
    }

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

    private static Integer parseAbsolute(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String t = token.trim().toLowerCase();
        if (t.startsWith("set")) {
            try {
                return Integer.parseInt(t.substring(3).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (t.startsWith("+") || "-".equals(t) || "+".equals(t)) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseDelta(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String t = token.trim().replace(" ", "");
        if (!t.startsWith("+") && !t.startsWith("-")) {
            return null;
        }
        if ("+".equals(t)) {
            return 1;
        }
        if ("-".equals(t)) {
            return -1;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void sendAllStatus() {
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[Held-item preview offsets]");
        for (HeldItemInfoPreviewDebugTuning.Part part : HeldItemInfoPreviewDebugTuning.Part.values()) {
            sendStatusLine(part);
        }
    }

    private static void sendStatus(HeldItemInfoPreviewDebugTuning.Part part) {
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[" + part.getLabel() + "]");
        sendStatusLine(part);
        send(EnumChatFormatting.DARK_GRAY + "Use /helditempreview " + part.getId()
                + " x +1  |  /helditempreview " + part.getId() + " y -1  |  /helditempreview dump");
    }

    private static void sendStatusLine(HeldItemInfoPreviewDebugTuning.Part part) {
        HeldItemInfoPreviewDebugTuning.Offset offset = HeldItemInfoPreviewDebugTuning.get(part);
        send(EnumChatFormatting.AQUA + part.getId() + EnumChatFormatting.GRAY + ": "
                + EnumChatFormatting.WHITE + offset.format());
    }

    private static void dumpForDev() {
        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[Held-item preview dump]");
        send(EnumChatFormatting.GRAY + "Copy these lines back to me:");
        for (HeldItemInfoPreviewDebugTuning.Part part : HeldItemInfoPreviewDebugTuning.Part.values()) {
            HeldItemInfoPreviewDebugTuning.Offset offset = HeldItemInfoPreviewDebugTuning.get(part);
            send(EnumChatFormatting.WHITE + part.getId() + ": x=" + offset.x + " y=" + offset.y);
        }
        send(EnumChatFormatting.DARK_GRAY + "Overlay: /helditempreview overlay  |  Open GUI: /helditempreview gui");
    }

    private static void sendPartList() {
        StringBuilder sb = new StringBuilder(EnumChatFormatting.GRAY + "Parts: ");
        boolean first = true;
        for (HeldItemInfoPreviewDebugTuning.Part part : HeldItemInfoPreviewDebugTuning.Part.values()) {
            if (!first) {
                sb.append(EnumChatFormatting.DARK_GRAY).append(", ");
            }
            sb.append(EnumChatFormatting.AQUA).append(part.getId());
            first = false;
        }
        send(sb.toString());
    }

    private static void sendHelp(HeldItemInfoPreviewDebugTuning.Part part) {
        String p = part == null ? "<part>" : part.getId();
        send(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage:");
        send(EnumChatFormatting.GRAY + "/helditempreview gui" + EnumChatFormatting.DARK_GRAY + " — open Held item info GUI");
        send(EnumChatFormatting.GRAY + "/helditempreview colors" + EnumChatFormatting.DARK_GRAY + " — open Held item info settings GUI");
        send(EnumChatFormatting.GRAY + "/helditempreview overlay" + EnumChatFormatting.DARK_GRAY + " — guide boxes + labels");
        send(EnumChatFormatting.GRAY + "/helditempreview " + p + EnumChatFormatting.DARK_GRAY + " — status");
        send(EnumChatFormatting.GRAY + "/helditempreview " + p + " x|y +N|-N" + EnumChatFormatting.DARK_GRAY + " — nudge");
        send(EnumChatFormatting.GRAY + "/helditempreview " + p + " x|y N" + EnumChatFormatting.DARK_GRAY + " — set absolute");
        send(EnumChatFormatting.GRAY + "/helditempreview " + p + " x|y reset" + EnumChatFormatting.DARK_GRAY + " — zero one axis");
        send(EnumChatFormatting.GRAY + "/helditempreview " + p + " reset" + EnumChatFormatting.DARK_GRAY + " — zero both axes");
        send(EnumChatFormatting.GRAY + "/helditempreview dump" + EnumChatFormatting.DARK_GRAY + " — values to send dev");
        send(EnumChatFormatting.GRAY + "/helditempreview resetall");
        sendPartList();
    }

    private static void openSettingsGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen parent = mc.currentScreen;
        mc.displayGuiScreen(new HeldItemInfoSettingsGUI(parent));
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Opened Held item info settings GUI.");
    }

    private static void openColorsGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen parent = mc.currentScreen;
        mc.displayGuiScreen(new HeldItemInfoSettingsGUI(parent));
        send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Opened Held item info settings GUI.");
    }

    private static void send(String msg) {
        Main.sendMessage(msg);
    }
}
