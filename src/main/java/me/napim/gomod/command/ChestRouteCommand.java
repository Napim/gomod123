package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRouteStorage;
import me.napim.gomod.features.HypixelScoreboardMapResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /cro …} — chest routes (cages-open or stand-here start).
 */
public class ChestRouteCommand extends Command {

    public ChestRouteCommand() {
        super("cro");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        String a = args[0].trim().toLowerCase();
        if ("help".equals(a) || "?".equals(a)) {
            help();
            return;
        }
        switch (a) {
            case "load":
            case "on":
                handleLoad(args);
                break;
            case "loadall":
                handleLoadAll(args);
                break;
            case "delete":
            case "del":
            case "rm": {
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Usage: /cro delete <path> [map_key]");
                    break;
                }
                String pathDel = args[1].trim();
                String mapDel = args.length >= 3 ? args[2].trim() : null;
                if (mapDel != null && mapDel.isEmpty()) {
                    mapDel = null;
                }
                String errDel = ChestRouteManager.deleteSavedPath(pathDel, mapDel);
                if (errDel != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + errDel);
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Deleted route \"" + pathDel + "\" from disk.");
                }
                break;
            }
            case "unload":
            case "off":
            case "clear":
                if (args.length >= 2) {
                    String path = args[1].trim();
                    String err = ChestRouteManager.unloadPath(path);
                    if (err != null) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Unloaded path \"" + path + "\".");
                    }
                } else {
                    ChestRouteManager.unloadRoute();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "All chest route trails hidden.");
                }
                break;
            case "autoload":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Autoload on cages open: "
                            + (ChestRouteManager.isAutoloadOnCagesOpen() ? EnumChatFormatting.GREEN + "ON"
                            : EnumChatFormatting.RED + "OFF"));
                    return;
                }
                String v = args[1].trim().toLowerCase();
                boolean on = v.equals("on") || v.equals("true") || v.equals("1") || v.equals("yes");
                boolean off = v.equals("off") || v.equals("false") || v.equals("0") || v.equals("no");
                if (on) {
                    ChestRouteManager.setAutoloadOnCagesOpen(true);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Autoload ON (all paths when cages open).");
                } else if (off) {
                    ChestRouteManager.setAutoloadOnCagesOpen(false);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Autoload OFF.");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Use: /cro autoload on|off");
                }
                break;
            case "list":
                ChestRouteManager.printSavedRoutesChat();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Load: /cro load [path] [map]  |  legacy: /cro load <x> <y> <z> …");
                break;
            case "map":
            case "detect":
            case "here": {
                String k = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(Minecraft.getMinecraft());
                if (k == null || "unknown".equals(k)) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Could not read map from scoreboard. Check for \"Map: ...\" (/sdebug).");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Detected map key: "
                            + EnumChatFormatting.WHITE + k);
                }
                break;
            }
            default:
                help();
        }
    }

    private void handleLoad(String[] args) {
        if (args.length >= 4) {
            try {
                double x = Double.parseDouble(args[1].trim());
                double y = Double.parseDouble(args[2].trim());
                double z = Double.parseDouble(args[3].trim());
                String mapKey = args.length >= 5 ? args[4].trim() : null;
                if (mapKey != null && mapKey.isEmpty()) {
                    mapKey = null;
                }
                String path = args.length >= 6 ? args[5].trim() : "";
                if (path.isEmpty()) {
                    path = null;
                }
                String err = ChestRouteManager.loadRouteWithStart(null, x, y, z, true, mapKey, path);
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + "Chest route active from XYZ. /cro unload to hide.");
                }
                return;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        String path = null;
        String mapKey = null;
        if (args.length >= 2) {
            path = args[1].trim();
            if (path.isEmpty()) {
                path = null;
            }
        }
        if (args.length >= 3) {
            mapKey = args[2].trim();
            if (mapKey.isEmpty()) {
                mapKey = null;
            }
        }
        Vec3 cages = ChestRouteManager.getCagesSpawnFeet();
        String err = ChestRouteManager.loadRouteCagesOrExplicit(cages, mapKey, path);
        if (err != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
        } else {
            String shownPath = path;
            if (shownPath == null || shownPath.isEmpty()) {
                shownPath = ChestRouteManager.getDefaultPathForMap(mapKey);
                if (shownPath == null || shownPath.isEmpty()) {
                    shownPath = "<default>";
                }
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Chest route \"" + shownPath + "\" active. /cro unload to hide.");
        }
    }

    private void handleLoadAll(String[] args) {
        String mapKey = args.length >= 2 ? args[1].trim() : null;
        if (mapKey != null && mapKey.isEmpty()) {
            mapKey = null;
        }
        String err = ChestRouteManager.loadAllPathsForMap(mapKey, ChestRouteManager.getCagesSpawnFeet());
        if (err != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Loaded all paths for map.");
        }
    }

    private void help() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "/cro load [path] [map]"
                + EnumChatFormatting.GRAY + " — start from cages capture or where you stand");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "/cro load <x> <y> <z> …"
                + EnumChatFormatting.GRAY + " — exact start");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "loadall  unload  delete  autoload  list  map");
    }
}
