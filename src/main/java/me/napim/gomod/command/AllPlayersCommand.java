package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ReplayPlayerHealthTagFix;
import me.napim.gomod.features.ReplaySettings;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.SpectatorKillerNametagOverlay;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Command to detect all players in the game including spectators
 * Uses multiple detection methods to find invisible spectators
 * Usage: /allplayers
 */
public class AllPlayersCommand extends Command {
    public AllPlayersCommand() {
        super("allplayers");
    }

    @Override
    public void handle(String[] args) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                sendMessage(EnumChatFormatting.RED + "Not in a game!");
                return;
            }

            // Map to store all detected players with their detection methods
            Map<String, PlayerInfo> allPlayers = new LinkedHashMap<>();

            sendMessage(EnumChatFormatting.GOLD + "=== DETECTING ALL PLAYERS ===");
            sendMessage("");

            // Method 1: Tab List (NetworkPlayerInfo) - Most reliable
            sendMessage(EnumChatFormatting.YELLOW + "[1] Checking Tab List...");
            Collection<NetworkPlayerInfo> tabListPlayers = mc.getNetHandler().getPlayerInfoMap();
            for (NetworkPlayerInfo playerInfo : tabListPlayers) {
                String name = playerInfo.getGameProfile().getName();
                PlayerInfo pInfo = allPlayers.getOrDefault(name, new PlayerInfo(name));
                pInfo.addDetectionMethod("Tab List");
                pInfo.inTabList = true;
                
                // Check if visible in world
                EntityPlayer entityPlayer = mc.theWorld.getPlayerEntityByName(name);
                if (entityPlayer != null) {
                    pInfo.captureEntity(entityPlayer, mc);
                }
                
                allPlayers.put(name, pInfo);
            }
            sendMessage(EnumChatFormatting.GREEN + "  Found " + tabListPlayers.size() + " players in tab list");

            // Method 2: World Player Entities (including invisible)
            sendMessage(EnumChatFormatting.YELLOW + "[2] Checking World Entities...");
            int worldPlayers = 0;
            for (Object obj : mc.theWorld.playerEntities) {
                if (obj instanceof EntityPlayer) {
                    EntityPlayer player = (EntityPlayer) obj;
                    String name = player.getName();
                    if (name.equals(mc.thePlayer.getName())) continue; // Skip self
                    
                    PlayerInfo pInfo = allPlayers.getOrDefault(name, new PlayerInfo(name));
                    pInfo.addDetectionMethod("World Entity");
                    pInfo.inWorld = true;
                    pInfo.captureEntity(player, mc);
                    
                    allPlayers.put(name, pInfo);
                    worldPlayers++;
                }
            }
            sendMessage(EnumChatFormatting.GREEN + "  Found " + worldPlayers + " players in world");

            // Method 3: All Loaded Entities (catches edge cases)
            sendMessage(EnumChatFormatting.YELLOW + "[3] Checking All Entities...");
            int entityPlayers = 0;
            for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
                if (entity instanceof EntityPlayer) {
                    EntityPlayer player = (EntityPlayer) entity;
                    String name = player.getName();
                    if (name.equals(mc.thePlayer.getName())) continue; // Skip self
                    
                    PlayerInfo pInfo = allPlayers.getOrDefault(name, new PlayerInfo(name));
                    pInfo.addDetectionMethod("Entity List");
                    pInfo.inEntityList = true;
                    pInfo.captureEntity(player, mc);
                    
                    if (pInfo.distance == 0) {
                        pInfo.distance = mc.thePlayer.getDistanceToEntity(player);
                    }
                    
                    allPlayers.put(name, pInfo);
                    entityPlayers++;
                }
            }
            sendMessage(EnumChatFormatting.GREEN + "  Found " + entityPlayers + " players in entity list");

            // Method 4: Scoreboard Teams
            sendMessage(EnumChatFormatting.YELLOW + "[4] Checking Scoreboard Teams...");
            int teamPlayers = 0;
            if (mc.theWorld.getScoreboard() != null) {
                Scoreboard scoreboard = mc.theWorld.getScoreboard();
                Collection<ScorePlayerTeam> teams = scoreboard.getTeams();
                for (ScorePlayerTeam team : teams) {
                    for (String memberName : (Collection<String>) team.getMembershipCollection()) {
                        // Clean up the name (remove color codes)
                        String cleanName = memberName.replaceAll("§[0-9a-fk-or]", "");
                        
                        // Check if this looks like a player name (not scoreboard fake player)
                        if (cleanName.length() > 0 && cleanName.length() <= 16 && 
                            cleanName.matches("[a-zA-Z0-9_]+")) {
                            
                            PlayerInfo pInfo = allPlayers.getOrDefault(cleanName, new PlayerInfo(cleanName));
                            pInfo.addDetectionMethod("Scoreboard Team: " + team.getRegisteredName());
                            pInfo.teamName = team.getRegisteredName();
                            
                            allPlayers.put(cleanName, pInfo);
                            teamPlayers++;
                        }
                    }
                }
            }
            sendMessage(EnumChatFormatting.GREEN + "  Found " + teamPlayers + " players in teams");

            // Method 5: Network Manager (EntityOtherPlayerMP specifically)
            sendMessage(EnumChatFormatting.YELLOW + "[5] Checking Network Players...");
            int networkPlayers = 0;
            for (Object obj : mc.theWorld.playerEntities) {
                if (obj instanceof EntityOtherPlayerMP) {
                    EntityOtherPlayerMP player = (EntityOtherPlayerMP) obj;
                    String name = player.getName();
                    
                    PlayerInfo pInfo = allPlayers.getOrDefault(name, new PlayerInfo(name));
                    pInfo.addDetectionMethod("Network Player");
                    pInfo.isNetworkPlayer = true;
                    pInfo.captureEntity(player, mc);
                    
                    allPlayers.put(name, pInfo);
                    networkPlayers++;
                }
            }
            sendMessage(EnumChatFormatting.GREEN + "  Found " + networkPlayers + " network players");

            sendMessage("");
            sendMessage(EnumChatFormatting.GOLD + "=== RESULTS ===");
            sendMessage(EnumChatFormatting.WHITE + "Total Unique Players: " + EnumChatFormatting.GREEN + allPlayers.size());
            sendMessage("");

            // Sort players: visible first, then by name
            List<PlayerInfo> sortedPlayers = new ArrayList<>(allPlayers.values());
            sortedPlayers.sort((a, b) -> {
                if (a.isVisible != b.isVisible) return b.isVisible ? 1 : -1;
                return a.name.compareToIgnoreCase(b.name);
            });

            // Display all players with details
            int visibleCount = 0;
            int invisibleCount = 0;
            int unknownCount = 0;

            for (PlayerInfo pInfo : sortedPlayers) {
                StringBuilder msg = new StringBuilder();
                
                // Player name with color based on visibility
                if (pInfo.isVisible) {
                    msg.append(EnumChatFormatting.GREEN).append("✓ ");
                    visibleCount++;
                } else if (pInfo.inWorld || pInfo.inEntityList) {
                    msg.append(EnumChatFormatting.RED).append("✗ ");
                    invisibleCount++;
                } else {
                    msg.append(EnumChatFormatting.GRAY).append("? ");
                    unknownCount++;
                }
                
                msg.append(EnumChatFormatting.WHITE).append(pInfo.name);

                // Add status indicators
                List<String> indicators = new ArrayList<>();
                
                if (pInfo.inWorld && !pInfo.isVisible) {
                    indicators.add(EnumChatFormatting.RED + "INVISIBLE");
                }
                
                if (pInfo.distance > 0) {
                    indicators.add(EnumChatFormatting.AQUA + String.format("%.1fm", pInfo.distance));
                }
                
                if (pInfo.health > 0) {
                    EnumChatFormatting healthColor = getHealthColor(pInfo.health, pInfo.maxHealth);
                    indicators.add(healthColor + String.format("%.1f", pInfo.health) + "♥");
                }
                
                if (pInfo.gamemode != null) {
                    indicators.add(EnumChatFormatting.YELLOW + pInfo.gamemode);
                }
                
                if (!pInfo.inWorld && !pInfo.inEntityList && pInfo.inTabList) {
                    indicators.add(EnumChatFormatting.LIGHT_PURPLE + "TAB ONLY");
                }
                
                if (pInfo.teamName != null) {
                    indicators.add(EnumChatFormatting.GOLD + "Team: " + pInfo.teamName);
                }

                if (!indicators.isEmpty()) {
                    msg.append(EnumChatFormatting.DARK_GRAY).append(" [");
                    msg.append(String.join(EnumChatFormatting.DARK_GRAY + " | ", indicators));
                    msg.append(EnumChatFormatting.DARK_GRAY).append("]");
                }

                sendMessage(msg.toString());
                
                // Show detection methods in verbose mode
                if (args.length > 0 && args[0].equalsIgnoreCase("verbose")) {
                    sendMessage(EnumChatFormatting.DARK_GRAY + "  Methods: " + 
                               String.join(", ", pInfo.detectionMethods));
                    if (pInfo.hasEntityDebug) {
                        sendMessage(EnumChatFormatting.DARK_GRAY + "  VisibleSpecs: rawInvis="
                                + pInfo.rawInvisible + " renderedInvis=" + pInfo.renderedInvisible
                                + " showRemote=" + pInfo.visibleSpecsShowRemote
                                + " replayTarget=" + pInfo.visibleSpecsReplayTarget
                                + " suppressName=" + pInfo.visibleSpecsSuppressName
                                + " suppressHealth=" + pInfo.visibleSpecsSuppressHealth
                                + " applyOpacity=" + pInfo.visibleSpecsApplyOpacity
                                + " replayStack=" + pInfo.visibleSpecsReplayStack);
                    }
                }
            }

            sendMessage("");
            sendMessage(EnumChatFormatting.GOLD + "=== SUMMARY ===");
            sendMessage(EnumChatFormatting.GREEN + "Visible: " + visibleCount);
            sendMessage(EnumChatFormatting.RED + "Invisible/Spectators: " + invisibleCount);
            sendMessage(EnumChatFormatting.GRAY + "Unknown (Tab only): " + unknownCount);
            sendMessage("");
            sendMessage(EnumChatFormatting.DARK_GRAY + "Tip: Use /allplayers verbose for detailed detection info");

            String written = writeDebugFile(sortedPlayers, visibleCount, invisibleCount, unknownCount,
                    args.length > 0 && args[0].equalsIgnoreCase("verbose"));
            if (written != null) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved: "
                        + EnumChatFormatting.WHITE + written);
            } else {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Could not write allplayers debug .txt.");
            }

        } catch (Exception e) {
            sendMessage(EnumChatFormatting.RED + "Error detecting players: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private EnumChatFormatting getHealthColor(float health, float maxHealth) {
        float percentage = (health / maxHealth) * 100;
        if (percentage >= 75) return EnumChatFormatting.GREEN;
        if (percentage >= 50) return EnumChatFormatting.YELLOW;
        if (percentage >= 25) return EnumChatFormatting.GOLD;
        return EnumChatFormatting.RED;
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    private String writeDebugFile(List<PlayerInfo> players, int visibleCount, int invisibleCount,
            int unknownCount, boolean verbose) {
        try {
            File base = GomodPaths.getGomod123Dir();
            File dir = new File(base, "allplayers_logs");
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
            File timestamped = new File(dir, "allplayers_debug_" + stamp + ".txt");
            File latest = new File(base, "allplayers_debug_latest.txt");

            String text = buildDebugText(players, visibleCount, invisibleCount, unknownCount, verbose);
            if (!tryWrite(timestamped, text)) {
                return null;
            }
            tryWrite(latest, text);
            return timestamped.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String buildDebugText(List<PlayerInfo> players, int visibleCount, int invisibleCount,
            int unknownCount, boolean verbose) {
        Minecraft mc = Minecraft.getMinecraft();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("gomod allplayers debug - ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                .append("\n");
        sb.append("mode: ").append(verbose ? "verbose" : "normal").append("\n");
        if (mc != null && mc.thePlayer != null) {
            sb.append("local player: ").append(mc.thePlayer.getName()).append("\n");
        }
        sb.append("context: hypixel=").append(GameContext.isOnHypixel(mc))
                .append(" replaySettings=").append(ReplaySettings.isReplayActive())
                .append(" replayGameContext=").append(GameContext.isHypixelReplayWatching(mc))
                .append(" replayCombined=").append(VisibleSpecs.isReplayContext())
                .append(" applyReplay=").append(VisibleSpecs.isApplySettingsToReplaysEnabled())
                .append(" nametagMode=").append(VisibleSpecs.getNametagMode())
                .append(" opacity=").append(String.format(Locale.ROOT, "%.3f", VisibleSpecs.getOpacity()))
                .append("\n");
        sb.append("total unique players: ").append(players == null ? 0 : players.size()).append("\n");
        sb.append("visible: ").append(visibleCount).append("\n");
        sb.append("invisible/spectators: ").append(invisibleCount).append("\n");
        sb.append("unknown/tab-only: ").append(unknownCount).append("\n\n");
        sb.append("=== PLAYERS ===\n");
        if (players != null) {
            for (PlayerInfo p : players) {
                if (p == null) {
                    continue;
                }
                sb.append(p.name).append("\n");
                sb.append("  inTabList: ").append(p.inTabList).append("\n");
                sb.append("  inWorld: ").append(p.inWorld).append("\n");
                sb.append("  inEntityList: ").append(p.inEntityList).append("\n");
                sb.append("  isNetworkPlayer: ").append(p.isNetworkPlayer).append("\n");
                sb.append("  isVisible: ").append(p.isVisible).append("\n");
                sb.append("  distance: ").append(String.format(Locale.ROOT, "%.2f", p.distance)).append("\n");
                sb.append("  health: ").append(String.format(Locale.ROOT, "%.2f", p.health))
                        .append(" / ").append(String.format(Locale.ROOT, "%.2f", p.maxHealth)).append("\n");
                sb.append("  gamemode: ").append(p.gamemode == null ? "<unknown>" : p.gamemode).append("\n");
                sb.append("  scoreboardTeam: ").append(p.teamName == null ? "<none>" : p.teamName).append("\n");
                sb.append("  entityDebug: ").append(p.hasEntityDebug).append("\n");
                if (p.hasEntityDebug) {
                    sb.append("  rawInvisible: ").append(p.rawInvisible).append("\n");
                    sb.append("  renderedInvisible: ").append(p.renderedInvisible).append("\n");
                    sb.append("  potionInvisible: ").append(p.potionInvisible).append("\n");
                    sb.append("  visibleSpecs.showRemote: ").append(p.visibleSpecsShowRemote).append("\n");
                    sb.append("  visibleSpecs.replayTarget: ").append(p.visibleSpecsReplayTarget).append("\n");
                    sb.append("  visibleSpecs.suppressName: ").append(p.visibleSpecsSuppressName).append("\n");
                    sb.append("  visibleSpecs.suppressHealth: ").append(p.visibleSpecsSuppressHealth).append("\n");
                    sb.append("  visibleSpecs.applyOpacity: ").append(p.visibleSpecsApplyOpacity).append("\n");
                    sb.append("  visibleSpecs.replayStack: ").append(p.visibleSpecsReplayStack).append("\n");
                    sb.append("  killer.rawReplay: ").append(String.valueOf(p.rawReplayKiller)).append("\n");
                    sb.append("  killer.resolved: ").append(String.valueOf(p.resolvedKiller)).append("\n");
                    sb.append("  killer.overlay: ").append(p.killerOverlay).append("\n");
                    sb.append("  killer.label: ").append(String.valueOf(p.killerLabel)).append("\n");
                }
                sb.append("  detectionMethods: ").append(p.detectionMethods.isEmpty()
                        ? "<none>" : String.join(", ", p.detectionMethods)).append("\n\n");
            }
        }
        appendScoreboardTeams(sb, mc);
        return sb.toString();
    }

    private void appendScoreboardTeams(StringBuilder sb, Minecraft mc) {
        sb.append("=== SCOREBOARD TEAMS ===\n");
        try {
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                sb.append("(no scoreboard)\n");
                return;
            }
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            Collection<ScorePlayerTeam> teams = scoreboard.getTeams();
            if (teams == null || teams.isEmpty()) {
                sb.append("(no teams)\n");
                return;
            }
            List<ScorePlayerTeam> sortedTeams = new ArrayList<>(teams);
            sortedTeams.sort((a, b) -> safeTeamName(a).compareToIgnoreCase(safeTeamName(b)));
            for (ScorePlayerTeam team : sortedTeams) {
                if (team == null) {
                    continue;
                }
                sb.append("--- ").append(safeTeamName(team)).append(" ---\n");
                Collection<String> members = team.getMembershipCollection();
                if (members == null || members.isEmpty()) {
                    sb.append("  (empty)\n");
                    continue;
                }
                List<String> sortedMembers = new ArrayList<>(members);
                Collections.sort(sortedMembers, String.CASE_INSENSITIVE_ORDER);
                for (String member : sortedMembers) {
                    String formatted;
                    try {
                        formatted = ScorePlayerTeam.formatPlayerName(team, member);
                    } catch (Throwable ignored) {
                        formatted = member;
                    }
                    sb.append("  - ").append(member)
                            .append(" | formatted=").append(formatted)
                            .append(" | clean=").append(stripFormatting(formatted))
                            .append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("Error listing teams: ").append(t.getMessage()).append("\n");
        }
    }

    private static String safeTeamName(ScorePlayerTeam team) {
        try {
            String name = team == null ? null : team.getRegisteredName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String stripFormatting(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 167 && i + 1 < value.length() && isFormattingCode(value.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isFormattingCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
                || lower == 'k' || lower == 'l' || lower == 'm' || lower == 'n'
                || lower == 'o' || lower == 'r';
    }

    private static boolean tryWrite(File f, String text) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            try {
                writer.write(text);
            } finally {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRawInvisible(EntityPlayer player) {
        try {
            byte flags = player.getDataWatcher().getWatchableObjectByte(0);
            return (flags & (1 << 5)) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRenderedInvisible(EntityPlayer player) {
        try {
            return player != null && player.isInvisible();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Internal class to store player information
     */
    private static class PlayerInfo {
        String name;
        boolean inTabList = false;
        boolean inWorld = false;
        boolean inEntityList = false;
        boolean isNetworkPlayer = false;
        boolean isVisible = false;
        float distance = 0;
        float health = 0;
        float maxHealth = 0;
        String gamemode = null;
        String teamName = null;
        boolean hasEntityDebug = false;
        boolean rawInvisible = false;
        boolean renderedInvisible = false;
        boolean potionInvisible = false;
        boolean visibleSpecsShowRemote = false;
        boolean visibleSpecsReplayTarget = false;
        boolean visibleSpecsSuppressName = false;
        boolean visibleSpecsSuppressHealth = false;
        boolean visibleSpecsApplyOpacity = false;
        boolean visibleSpecsReplayStack = false;
        String rawReplayKiller = null;
        String resolvedKiller = null;
        boolean killerOverlay = false;
        String killerLabel = null;
        List<String> detectionMethods = new ArrayList<>();

        PlayerInfo(String name) {
            this.name = name;
        }

        void captureEntity(EntityPlayer player, Minecraft mc) {
            if (player == null) {
                return;
            }
            hasEntityDebug = true;
            rawInvisible = isRawInvisible(player);
            renderedInvisible = isRenderedInvisible(player);
            potionInvisible = player.isPotionActive(Potion.invisibility);
            isVisible = !renderedInvisible;
            if (mc != null && mc.thePlayer != null) {
                distance = mc.thePlayer.getDistanceToEntity(player);
            }
            health = player.getHealth();
            maxHealth = player.getMaxHealth();
            try {
                gamemode = player.capabilities.isCreativeMode ? "Creative" : "Survival";
            } catch (Throwable ignored) {
            }
            visibleSpecsShowRemote = VisibleSpecs.shouldShowPlayerRemote(player);
            visibleSpecsReplayTarget = VisibleSpecs.isReplaySpectatorSettingsTarget(player);
            visibleSpecsSuppressName = VisibleSpecs.shouldSuppressNametag(player);
            visibleSpecsSuppressHealth = VisibleSpecs.shouldSuppressHealthNametag(player);
            visibleSpecsApplyOpacity = VisibleSpecs.shouldApplyOpacityToEntity(player);
            visibleSpecsReplayStack = ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player);
            rawReplayKiller = KillCounter.getReplayRecordedKillerForVictim(player.getName());
            resolvedKiller = KillCounter.getKillerForVictim(player.getName());
            killerOverlay = SpectatorKillerNametagOverlay.shouldShowFor(player);
            killerLabel = SpectatorKillerNametagOverlay.buildLabel(player);
        }

        void addDetectionMethod(String method) {
            if (!detectionMethods.contains(method)) {
                detectionMethods.add(method);
            }
        }
    }
}

