package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.NickRenamedItemScanner;
import me.napim.gomod.gui.DenickerSettingsGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Clickable DB lookup and toggle/manual query command for {@link NickRenamedItemScanner}.
 * Usage: {@code /nickitemdb <id|query>} or {@code /nickitems on|off|toggle|<query>}.
 */
public final class NickRenamedItemCommand extends Command {

    public NickRenamedItemCommand() {
        super("nickitemdb", "nickitems", "nickrenamed");
    }

    @Override
    public void handle(String[] args) {
        if (args == null || args.length == 0) {
            boolean enabled = ConfigManager.isNickRenamedItemScannerEnabled();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Nick Renamed Items: "
                    + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + " | Usage: /nickitems on|off|toggle or /nickitemdb <item name>");
            return;
        }

        if (args.length == 1) {
            String first = args[0] == null ? "" : args[0].trim();
            if ("toggle".equalsIgnoreCase(first)) {
                setEnabledAndNotify(!ConfigManager.isNickRenamedItemScannerEnabled());
                return;
            }
            if ("on".equalsIgnoreCase(first) || "enable".equalsIgnoreCase(first)) {
                setEnabledAndNotify(true);
                return;
            }
            if ("off".equalsIgnoreCase(first) || "disable".equalsIgnoreCase(first)) {
                setEnabledAndNotify(false);
                return;
            }
            if (first.matches("\\d{1,9}")) {
                try {
                    int id = Integer.parseInt(first);
                    if (NickRenamedItemScanner.getStoredLookup(id) != null) {
                        NickRenamedItemScanner.lookupByEntryId(id);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        boolean plainExact = false;
        int startIdx = 0;
        if (args.length > 1 && "--plain".equalsIgnoreCase(args[0])) {
            plainExact = true;
            startIdx = 1;
        }
        StringBuilder query = new StringBuilder();
        for (int i = startIdx; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append(arg);
        }
        if (plainExact) {
            NickRenamedItemScanner.lookupRenamedItemInDatabase(query.toString(), null, true, false);
        } else {
            NickRenamedItemScanner.lookupRenamedItemInDatabase(query.toString(), null);
        }
    }

    private static void setEnabledAndNotify(boolean enabled) {
        ConfigManager.setNickRenamedItemScannerEnabled(enabled);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.currentScreen instanceof DenickerSettingsGUI) {
            ((DenickerSettingsGUI) mc.currentScreen).refreshNickRenamedItemsButtonLabel();
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Nick Renamed Items: "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }
}
