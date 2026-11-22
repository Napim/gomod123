package me.napim.gomod.command;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Command to remove shuffleTeam from all players to fix shuffled nametags
 * Usage: /noshuffleteam
 */
public class NoShuffleTeamCommand extends Command {
    public NoShuffleTeamCommand() {
        super("noshuffleteam");
    }

    @Override
    public void handle(String[] args) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                sendMessage(EnumChatFormatting.RED + "Not in a game!");
                return;
            }

            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) {
                sendMessage(EnumChatFormatting.RED + "No scoreboard available!");
                return;
            }

            // Check for different team types that might cause name shuffling
            List<String> problematicTeamNames = new ArrayList<>();
            problematicTeamNames.add("shuffleTeam");
            problematicTeamNames.add("shuffle");
            problematicTeamNames.add("randomTeam");
            problematicTeamNames.add("scrambledTeam");
            
            // Add team_1 through team_32
            for (int i = 1; i <= 32; i++) {
                problematicTeamNames.add("team_" + i);
            }
            
            List<String> foundTeams = new ArrayList<>();
            
            for (String teamName : problematicTeamNames) {
                ScorePlayerTeam team = scoreboard.getTeam(teamName);
                if (team != null) {
                    foundTeams.add(teamName);
                }
            }

            if (foundTeams.isEmpty()) {
                sendMessage(EnumChatFormatting.YELLOW + "No problematic teams found.");
                sendMessage(EnumChatFormatting.GRAY + "Available teams:");
                Collection<ScorePlayerTeam> allTeams = scoreboard.getTeams();
                for (ScorePlayerTeam team : allTeams) {
                    sendMessage(EnumChatFormatting.GRAY + "  - " + team.getRegisteredName());
                }
                return;
            }

            sendMessage(EnumChatFormatting.GOLD + "=== REMOVING PROBLEMATIC TEAMS ===");
            sendMessage(EnumChatFormatting.YELLOW + "Found teams: " + String.join(", ", foundTeams));

            int totalRemoved = 0;
            int totalErrors = 0;

            // Process each problematic team
            for (String teamName : foundTeams) {
                ScorePlayerTeam team = scoreboard.getTeam(teamName);
                if (team == null) continue;

                // Get all members of this team
                List<String> teamMembers = new ArrayList<>();
                for (String memberName : (Collection<String>) team.getMembershipCollection()) {
                    teamMembers.add(memberName);
                }

                if (teamMembers.isEmpty()) {
                    sendMessage(EnumChatFormatting.GRAY + "Team " + teamName + " has no members.");
                    continue;
                }

                sendMessage(EnumChatFormatting.YELLOW + "Processing " + teamName + " (" + teamMembers.size() + " members)...");

                int removedCount = 0;
                int errorCount = 0;

                // Remove each player from the problematic team
                for (String playerName : teamMembers) {
                    try {
                        // Clean the player name (remove color codes)
                        String cleanName = playerName.replaceAll("§[0-9a-fk-or]", "");
                        
                        // Remove from problematic team (don't assign to any new team)
                        scoreboard.removePlayerFromTeam(cleanName, team);
                        
                        removedCount++;
                        sendMessage(EnumChatFormatting.GREEN + "  ✓ " + cleanName);
                        
                    } catch (Exception e) {
                        errorCount++;
                        sendMessage(EnumChatFormatting.RED + "  ✗ " + playerName + ": " + e.getMessage());
                    }
                }

                totalRemoved += removedCount;
                totalErrors += errorCount;
                
                sendMessage(EnumChatFormatting.AQUA + "Team " + teamName + ": " + removedCount + " removed, " + errorCount + " errors");
            }

            sendMessage("");
            sendMessage(EnumChatFormatting.GOLD + "=== FINAL RESULTS ===");
            sendMessage(EnumChatFormatting.GREEN + "Total players fixed: " + totalRemoved);
            if (totalErrors > 0) {
                sendMessage(EnumChatFormatting.RED + "Total errors: " + totalErrors);
            }
            sendMessage(EnumChatFormatting.YELLOW + "All players should now have normal nametags!");
            sendMessage(EnumChatFormatting.GRAY + "Use /allplayers to verify the changes.");

        } catch (Exception e) {
            sendMessage(EnumChatFormatting.RED + "Error removing teams: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }
}
