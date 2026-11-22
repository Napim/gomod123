package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.ChestRouteStorage;
import me.napim.gomod.features.HypixelScoreboardMapResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /ce …} — checkpoint editor for chest routes.
 */
public class ChestEditCommand extends Command {

    public ChestEditCommand() {
        super("ce");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            help();
            return;
        }
        String a = args[0].trim().toLowerCase();
        switch (a) {
            case "on":
            case "true":
            case "1":
                ChestRouteManager.setEditMode(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Checkpoint edit ON — RMB blocks to add.");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "LMB on a checkpoint number = remove. Hold LMB = drag (release LMB to place).");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Then /ce save <name>");
                break;
            case "off":
            case "false":
            case "0":
                ChestRouteManager.setEditMode(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Checkpoint edit OFF.");
                break;
            case "clear":
                ChestRouteManager.clearDraft();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Draft cleared.");
                break;
            case "checkpoints":
            case "draft":
            case "cp":
                ChestRouteManager.printDraftCheckpoints();
                break;
            case "saved":
            case "maps":
                java.util.List<String> keys = ChestRouteStorage.listMapKeys();
                if (keys.isEmpty()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "No saved routes.");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Saved maps & paths:");
                    for (String k : keys) {
                        java.util.List<String> paths = ChestRouteStorage.listPathNames(k);
                        Main.sendMessage(EnumChatFormatting.YELLOW + "  " + k + EnumChatFormatting.GRAY + " → "
                                + EnumChatFormatting.WHITE + String.join(", ", paths));
                    }
                }
                break;
            case "remove":
            case "delete":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /ce remove <#>  (1-based draft index)");
                    return;
                }
                try {
                    int n = Integer.parseInt(args[1].trim());
                    String err = ChestRouteManager.removeDraftCheckpoint(n);
                    if (err != null) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Removed checkpoint #" + n + ".");
                    }
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid number.");
                }
                break;
            case "drag":
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /ce drag <#>");
                    return;
                }
                try {
                    int n = Integer.parseInt(args[1].trim());
                    String err = ChestRouteManager.commandDragCheckpoint(n);
                    if (err != null) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Dragging checkpoint #" + n
                                + EnumChatFormatting.GRAY + " — look at a block and run /ce drop.");
                    }
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid number.");
                }
                break;
            case "drop": {
                String err = ChestRouteManager.commandDropDrag();
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Checkpoint dropped.");
                }
                break;
            }
            case "add":
            case "place":
            case "far": {
                String msg = ChestRouteManager.commandAddCheckpointFromLook();
                if (msg != null && msg.startsWith("Checkpoint #")) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + msg);
                } else if (msg != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + msg);
                }
                break;
            }
            case "swap":
                if (args.length < 3) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /ce swap <#> <#>");
                    return;
                }
                try {
                    int i = Integer.parseInt(args[1].trim());
                    int j = Integer.parseInt(args[2].trim());
                    String err = ChestRouteManager.swapDraftCheckpoints(i, j);
                    if (err != null) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Swapped #" + i + " ↔ #" + j + ".");
                    }
                } catch (NumberFormatException e) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid numbers.");
                }
                break;
            case "undo": {
                String err = ChestRouteManager.undoDraft();
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Undid last draft edit.");
                }
                break;
            }
            case "save": {
                if (args.length < 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Usage: /ce save <name> [map_key]  — name is required.");
                    return;
                }
                String pathName = args[1].trim();
                String explicitMap = args.length >= 3 ? args[2].trim() : null;
                if (explicitMap != null && explicitMap.isEmpty()) {
                    explicitMap = null;
                }
                int before = ChestRouteManager.getDraftSize();
                String err = ChestRouteManager.saveDraft(explicitMap, pathName);
                if (err != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                } else {
                    String key = explicitMap != null
                            ? HypixelScoreboardMapResolver.slugify(explicitMap)
                            : HypixelScoreboardMapResolver.tryResolveCurrentMapKey(Minecraft.getMinecraft());
                    String pslug = HypixelScoreboardMapResolver.slugify(pathName);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved path "
                            + EnumChatFormatting.WHITE + pslug + EnumChatFormatting.GREEN + " on map "
                            + EnumChatFormatting.WHITE + key + EnumChatFormatting.GREEN + " ("
                            + before + " checkpoints). /cro load [path]");
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                            + "Edit mode OFF, draft cleared — use /ce on to place more checkpoints.");
                }
                break;
            }
            case "list":
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Use /ce checkpoints (draft) or /ce saved (file).");
                break;
            default:
                help();
        }
    }

    private void help() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "/ce on"
                + EnumChatFormatting.GRAY + " — right-click blocks as ordered checkpoints");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "While ON: LMB checkpoint number = remove, hold LMB = drag.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                + "/ce checkpoints  |  remove <#>  |  swap <#> <#>  |  add  |  undo");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                + "/ce drag <#>  |  drop  |  save <name> [map_key]");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                + "/ce saved  |  clear  |  off");
    }
}
