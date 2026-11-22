package me.napim.gomod.command;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.MapQueuer;
import me.napim.gomod.Main;
import me.napim.gomod.util.GuiOpener;

public class MapQueuerCommand extends Command {

    public MapQueuerCommand() {
        super("mapqueuer");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            showHelp();
            return;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "start":
                if (args.length < 3) {
                    Main.sendMessage(EnumChatFormatting.RED + "Usage: /mapqueuer start <map> <solo|teams>");
                    Main.sendMessage(EnumChatFormatting.YELLOW + "Example: /mapqueuer start Caelum solo");
                    return;
                }
                
                String map = args[1];
                String mode = args[2].toLowerCase();
                
                if (!mode.equals("solo") && !mode.equals("teams")) {
                    Main.sendMessage(EnumChatFormatting.RED + "Mode must be 'solo' or 'teams'");
                    return;
                }
                
                MapQueuer.startMapQueuer(map, mode);
                break;
                
            case "stop":
                System.out.println("[MapQueuerCommand] Stop command received");
                MapQueuer.stopMapQueuer();
                break;
                
            case "resume":
                System.out.println("[MapQueuerCommand] Resume command received");
                MapQueuer.resumeMapQueuer();
                break;
                
            case "status":
                showStatus();
                break;
                
            case "maps":
                showMaps();
                break;
                
            case "gui":
                GuiOpener.open(
                        "me.napim.gomod.gui.MapQueuerGUI",
                        new Class<?>[]{net.minecraft.client.gui.GuiScreen.class},
                        new Object[]{null}
                );
                break;
                
            case "debug":
                if (!GomodInternalFeatures.requireInternalCommand("mapqueuer debug")) return;
                debugScoreboard();
                break;
                
            case "clearcache":
                MapQueuer.clearGameIdCache();
                Main.sendMessage(EnumChatFormatting.GREEN + "Game ID cache cleared!");
                break;
                
            case "filtermap":
                filterCurrentMap();
                break;
                
            default:
                showHelp();
                break;
        }
    }

    private void showHelp() {
        Main.sendMessage(EnumChatFormatting.GOLD + "=== Map Queuer Commands ===");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer start <map> <solo|teams>" + EnumChatFormatting.WHITE + " - Start queuing for a specific map");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer stop" + EnumChatFormatting.WHITE + " - Stop the map queuer");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer resume" + EnumChatFormatting.WHITE + " - Resume with last used settings");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer status" + EnumChatFormatting.WHITE + " - Show current status");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer maps" + EnumChatFormatting.WHITE + " - List all available maps");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer gui" + EnumChatFormatting.WHITE + " - Open the map selection GUI");
        if (GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer debug" + EnumChatFormatting.WHITE + " - Debug scoreboard reading");
        }
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer clearcache" + EnumChatFormatting.WHITE + " - Clear game ID cache");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/mapqueuer filtermap" + EnumChatFormatting.WHITE + " - Filter current map's game ID");
        Main.sendMessage(EnumChatFormatting.GRAY + "Use /gomod to access the Map Queuer GUI");
    }

    private void showStatus() {
        if (MapQueuer.isRunning()) {
            Main.sendMessage(EnumChatFormatting.GREEN + "Map Queuer is running!");
            Main.sendMessage(EnumChatFormatting.AQUA + "Target Map: " + EnumChatFormatting.WHITE + MapQueuer.getTargetMap());
            Main.sendMessage(EnumChatFormatting.AQUA + "Mode: " + EnumChatFormatting.WHITE + MapQueuer.getGameMode());
            Main.sendMessage(EnumChatFormatting.AQUA + "Blitz Hour only: " + EnumChatFormatting.WHITE + (MapQueuer.isRequireBlitzHour() ? "yes" : "no"));
        } else {
            Main.sendMessage(EnumChatFormatting.RED + "Map Queuer is not running.");
        }
    }

    private void showMaps() {
        Main.sendMessage(EnumChatFormatting.GOLD + "=== Available Blitz Maps ===");
        
        String[] allMaps = MapQueuer.getAllMaps();

        StringBuilder soloMaps = new StringBuilder();
        StringBuilder teamsMaps = new StringBuilder();
        StringBuilder bothMaps = new StringBuilder();

        for (String map : allMaps) {
            String entry = map + " (" + MapQueuer.getMapMaxPlayers(map) + ")";
            if (MapQueuer.isTeamsOnlyMap(map)) {
                teamsMaps.append(EnumChatFormatting.RED).append(entry).append(EnumChatFormatting.WHITE).append(", ");
            } else if (MapQueuer.isSoloOnlyMap(map)) {
                soloMaps.append(EnumChatFormatting.BLUE).append(entry).append(EnumChatFormatting.WHITE).append(", ");
            } else {
                bothMaps.append(EnumChatFormatting.GREEN).append(entry).append(EnumChatFormatting.WHITE).append(", ");
            }
        }
        
        Main.sendMessage(EnumChatFormatting.AQUA + "Solo-Only Maps:");
        Main.sendMessage(soloMaps.toString());
        
        Main.sendMessage(EnumChatFormatting.AQUA + "Teams-Only Maps:");
        Main.sendMessage(teamsMaps.toString());
        
        Main.sendMessage(EnumChatFormatting.AQUA + "Available in Both Modes:");
        Main.sendMessage(bothMaps.toString());
        
        Main.sendMessage(EnumChatFormatting.BLUE + "Blue = Solo-only, " + EnumChatFormatting.RED + "Red = Teams-only, " + EnumChatFormatting.GREEN + "Green = Both modes");
    }
    
    private void debugScoreboard() {
        Main.sendMessage(EnumChatFormatting.GOLD + "=== Scoreboard Debug ===");
        
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                Main.sendMessage(EnumChatFormatting.RED + "No scoreboard available");
                return;
            }
            
            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            
            if (objective == null) {
                Main.sendMessage(EnumChatFormatting.RED + "No sidebar objective found");
                return;
            }
            
            Main.sendMessage(EnumChatFormatting.AQUA + "Scoreboard lines:");
            for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                net.minecraft.scoreboard.ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String line = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                Main.sendMessage(EnumChatFormatting.WHITE + "  " + line);
                // Also show cleaned line used for gameId detection
                String clean = line.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
                clean = clean.replaceAll("§[0-9A-Fa-f]", "");
                clean = clean.replaceAll("§[0-9A-F]", "");
                Main.sendMessage(EnumChatFormatting.GRAY + "    cleaned: " + clean);
            }
            
            String gameId = me.napim.gomod.features.MapQueuer.getCurrentGameId();
            if (gameId != null) {
                Main.sendMessage(EnumChatFormatting.GREEN + "Detected game ID: " + gameId);
            } else {
                Main.sendMessage(EnumChatFormatting.RED + "No game ID detected");
            }

            String currentMap = me.napim.gomod.features.MapQueuer.getCurrentMap();
            if (currentMap != null) {
                Main.sendMessage(EnumChatFormatting.GREEN + "Detected map: " + currentMap);
            } else {
                Main.sendMessage(EnumChatFormatting.RED + "No map detected");
            }

            Main.sendMessage(EnumChatFormatting.AQUA + "Blitz Hour sidebar line: "
                    + (me.napim.gomod.features.MapQueuer.isBlitzHourOnSidebar() ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
            
        } catch (Exception e) {
            Main.sendMessage(EnumChatFormatting.RED + "Error reading scoreboard: " + e.getMessage());
        }
    }
    
    private void filterCurrentMap() {
        try {
            // Get the current game ID using the same logic as MapQueuer
            String currentGameId = me.napim.gomod.features.MapQueuer.getCurrentGameId();
            
            if (currentGameId == null || currentGameId.isEmpty()) {
                Main.sendMessage(EnumChatFormatting.RED + "No game ID detected! Make sure you're in a game or lobby.");
                return;
            }
            
            // Add the game ID to the filter list using the GUI's filter system (reflective to avoid early class loading)
            try {
                Class<?> guiClass = Class.forName("me.napim.gomod.gui.MapQueuerGUI");
                java.lang.reflect.Method addFilter = guiClass.getMethod("addGameFilter", String.class);
                addFilter.invoke(null, currentGameId);
            } catch (Throwable t) {
                Main.sendMessage(EnumChatFormatting.RED + "Failed to add game filter: " + t.getMessage());
                return;
            }
            
            Main.sendMessage(EnumChatFormatting.GREEN + "Added game filter: " + currentGameId + " (5 minutes)");
            Main.sendMessage(EnumChatFormatting.AQUA + "This game will now be filtered for 5 minutes.");
            
        } catch (Exception e) {
            Main.sendMessage(EnumChatFormatting.RED + "Error filtering current map: " + e.getMessage());
        }
    }
}
