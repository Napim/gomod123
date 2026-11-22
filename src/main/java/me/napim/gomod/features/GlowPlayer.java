package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.mixins.RenderManagerAccessor;
import me.napim.gomod.render.GomodEntityOutlineFallback;
import me.napim.gomod.render.LunarGlowArrowMarkers;
import me.napim.gomod.util.GlowTrailRenderer;
import me.napim.gomod.util.GomodPaths;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains a local list of players to glow and persists them across sessions.
 * Used by the /glowplayer command and the renderer mixin to draw outlines.
 * Both lists draw the same textured entity-outline pass as Player Queuer (full armor/boots silhouette).
 * {@link #glowPlayers} uses {@link #getColorRgb()} as a forced tint and stays depth-off (through walls).
 * {@link #glowPlayers2} uses vanilla team-tinted chameleon colors, also through walls.
 */
public final class GlowPlayer {
    private static File glowPlayerConfigFile() {
        return new File(GomodPaths.getGomod123Dir(), "glowplayer.json");
    }

    private static final Set<String> glowPlayers = new HashSet<String>();
    /** Vanilla-style outline pass; mutually exclusive with {@link #glowPlayers} per name. */
    private static final Set<String> glowPlayers2 = new HashSet<String>();
    private static volatile boolean debug = false;
    private static int colorRgb = 0x66FFFF; // default flat cyan (silhouette pass; not skin-tinted)

    private GlowPlayer() {}

    /** Reset outline FBO helper flags and vanilla {@code RenderManager.renderOutlines} (e.g. after chameleon pass). */
    private static void resetOutlinePipelineClientState() {
        OutlineRenderingHelper.resetLeakProneOutlineClientState();
    }

    public static void loadConfig() {
        try {
            File dir = GomodPaths.getGomod123Dir();
            if (!dir.exists()) dir.mkdirs();
            File file = glowPlayerConfigFile();
            if (!file.exists()) {
                saveConfig();
                return;
            }
            try (FileReader reader = new FileReader(file)) {
                com.google.gson.JsonObject obj = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
                glowPlayers.clear();
                glowPlayers2.clear();
                if (obj.has("players") && obj.get("players").isJsonArray()) {
                    com.google.gson.JsonArray arr = obj.getAsJsonArray("players");
                    for (int i = 0; i < arr.size(); i++) {
                        String name = arr.get(i).getAsString();
                        if (name != null && !name.trim().isEmpty()) {
                            glowPlayers.add(name.trim().toLowerCase());
                        }
                    }
                }
                if (obj.has("players2") && obj.get("players2").isJsonArray()) {
                    com.google.gson.JsonArray arr2 = obj.getAsJsonArray("players2");
                    for (int i = 0; i < arr2.size(); i++) {
                        String name = arr2.get(i).getAsString();
                        if (name != null && !name.trim().isEmpty()) {
                            String n = name.trim().toLowerCase();
                            glowPlayers2.add(n);
                            glowPlayers.remove(n);
                        }
                    }
                }
                if (obj.has("color")) {
                    try {
                        colorRgb = Integer.parseInt(obj.get("color").getAsString(), 16) & 0xFFFFFF;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[GlowPlayer] Failed to load config: " + e.getMessage());
        }
    }

    private static void saveConfig() {
        try {
            File dir = GomodPaths.getGomod123Dir();
            if (!dir.exists()) dir.mkdirs();
            File file = glowPlayerConfigFile();
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (String p : new ArrayList<String>(glowPlayers)) {
                arr.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("players", arr);
            com.google.gson.JsonArray arr2 = new com.google.gson.JsonArray();
            for (String p : new ArrayList<String>(glowPlayers2)) {
                arr2.add(new com.google.gson.JsonPrimitive(p));
            }
            obj.add("players2", arr2);
            String hex = String.format("%06X", colorRgb & 0xFFFFFF);
            obj.addProperty("color", hex);
            try (FileWriter writer = new FileWriter(file)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj, writer);
            }
        } catch (Exception e) {
            System.err.println("[GlowPlayer] Failed to save config: " + e.getMessage());
        }
    }

    public static List<String> getPlayers() {
        return new ArrayList<String>(glowPlayers);
    }

    public static boolean isGlowing(String name) {
        if (glowPlayers.isEmpty() || name == null || name.trim().isEmpty()) return false;
        return glowPlayers.contains(name.trim().toLowerCase());
    }

    public static boolean isGlowing2(String name) {
        if (glowPlayers2.isEmpty() || name == null || name.trim().isEmpty()) return false;
        return glowPlayers2.contains(name.trim().toLowerCase());
    }

    /**
     * True if the manual glow list matches this player using either {@link EntityPlayer#getName()}
     * or {@link com.mojang.authlib.GameProfile#getName()} (necessary when the client shows a nick
     * / tab-style name that differs from the profile name you typed in {@code /glowplayer}).
     */
    public static boolean isGlowingPlayer(EntityPlayer player) {
        return resolveMatchedGlowName(player) != null;
    }

    /** Non-null if this player is in the manual glow set under any known name variant. */
    public static String resolveMatchedGlowName(EntityPlayer player) {
        if (player == null || glowPlayers.isEmpty()) {
            return null;
        }
        try {
            com.mojang.authlib.GameProfile gp = player.getGameProfile();
            if (gp != null && gp.getName() != null && isGlowing(gp.getName())) {
                return gp.getName();
            }
        } catch (Throwable ignored) {
        }
        String vis = player.getName();
        if (vis != null && isGlowing(vis)) {
            return vis;
        }
        return null;
    }

    /**
     * True for /glowplayer2 (vanilla-style outline). Mutually exclusive with {@link #isGlowingPlayer}.
     */
    public static boolean isGlowingPlayer2(EntityPlayer player) {
        return resolveMatchedGlowName2(player) != null;
    }

    public static String resolveMatchedGlowName2(EntityPlayer player) {
        if (player == null || glowPlayers2.isEmpty()) {
            return null;
        }
        try {
            com.mojang.authlib.GameProfile gp = player.getGameProfile();
            if (gp != null && gp.getName() != null && isGlowing2(gp.getName())) {
                return gp.getName();
            }
        } catch (Throwable ignored) {
        }
        String vis = player.getName();
        if (vis != null && isGlowing2(vis)) {
            return vis;
        }
        return null;
    }

    public static List<String> getPlayers2() {
        return new ArrayList<String>(glowPlayers2);
    }

    /**
     * True if any player entity in the current world (including you) uses this name — required before
     * adding {@code /glowplayer2} targets so we never leave a list entry that never resolves and can confuse
     * outline passes with teammate/chameleon modes.
     */
    /** True if a player in render distance matches this name (profile or visible TAB name). */
    public static boolean isAnyLoadedPlayerMatchingGlowArg(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        return isAnyPlayerLoadedWithNormalizedName(name.trim().toLowerCase());
    }

    private static boolean isAnyPlayerLoadedWithNormalizedName(String norm) {
        if (norm == null || norm.isEmpty()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return false;
            }
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == null) {
                    continue;
                }
                try {
                    com.mojang.authlib.GameProfile gp = p.getGameProfile();
                    if (gp != null && gp.getName() != null && norm.equalsIgnoreCase(gp.getName().trim())) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                String vis = p.getName();
                if (vis != null && norm.equalsIgnoreCase(vis.trim())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String toggle(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /glowplayer <player>";
        }
        String norm = name.trim().toLowerCase();
        boolean nowOn;
        if (glowPlayers.contains(norm)) {
            glowPlayers.remove(norm);
            nowOn = false;
            GlowTrailRenderer.removeTrailForPlayer(norm);
        } else {
            if (glowPlayers2.contains(norm)) {
                GlowTrailRenderer.removeTrailForPlayer(norm);
            }
            glowPlayers2.remove(norm);
            glowPlayers.add(norm);
            nowOn = true;
        }
        saveConfig();
        resetOutlinePipelineClientState();
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + EnumChatFormatting.RESET + " glow " + (nowOn ? (EnumChatFormatting.GREEN + "enabled") : (EnumChatFormatting.RED + "disabled")) + EnumChatFormatting.RESET;
    }

    /** /glowplayer2 — vanilla team-tinted outline; cannot stack with /glowplayer on the same name. */
    public static String toggle2(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /glowplayer2 <player>";
        }
        String norm = name.trim().toLowerCase();
        boolean nowOn;
        if (glowPlayers2.contains(norm)) {
            glowPlayers2.remove(norm);
            nowOn = false;
            GlowTrailRenderer.removeTrailForPlayer(norm);
        } else {
            if (!isAnyPlayerLoadedWithNormalizedName(norm)) {
                return Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "No loaded player matches \"" + name.trim() + "\" (they must be in render distance). "
                        + EnumChatFormatting.GRAY + "Move closer, then run the command again.";
            }
            if (glowPlayers.contains(norm)) {
                GlowTrailRenderer.removeTrailForPlayer(norm);
            }
            glowPlayers.remove(norm);
            glowPlayers2.add(norm);
            nowOn = true;
        }
        saveConfig();
        resetOutlinePipelineClientState();
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + EnumChatFormatting.RESET + " glow2 "
                + (nowOn ? (EnumChatFormatting.GREEN + "enabled") : (EnumChatFormatting.RED + "disabled"))
                + EnumChatFormatting.GRAY + " (vanilla-style)" + EnumChatFormatting.RESET;
    }

    public static String add(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        String norm = name.trim().toLowerCase();
        if (glowPlayers.add(norm)) {
            glowPlayers2.remove(norm);
            GlowTrailRenderer.removeTrailForPlayer(norm);
            saveConfig();
            resetOutlinePipelineClientState();
            return Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Glowing " + name;
        }
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + " is already glowing.";
    }

    public static String remove(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Provide a player name.";
        }
        String norm = name.trim().toLowerCase();
        if (glowPlayers.remove(norm)) {
            GlowTrailRenderer.removeTrailForPlayer(norm);
            saveConfig();
            resetOutlinePipelineClientState();
            return Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Stopped glowing " + name;
        }
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + name + " was not glowing.";
    }

    public static void clear() {
        glowPlayers.clear();
        glowPlayers2.clear();
        saveConfig();
        GlowTrailRenderer.clear();
        resetOutlinePipelineClientState();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Cleared glowing players list (/glowplayer + /glowplayer2).");
    }

    /** Clears every name from the flat /glowplayer list only. */
    public static String clearFlatGlowList() {
        for (String p : new ArrayList<String>(glowPlayers)) {
            GlowTrailRenderer.removeTrailForPlayer(p);
        }
        glowPlayers.clear();
        saveConfig();
        resetOutlinePipelineClientState();
        return Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Cleared /glowplayer list (flat glow for all names)." + EnumChatFormatting.RESET;
    }

    /**
     * Bare /glowplayer2: clears all manual vanilla-style outlines ({@code players2}) and turns off
     * Teammate Tracker glow (Green or Chameleon), since both use the same entity-outline pipeline.
     * Does not clear {@code /glowplayer} flat list — use {@link #clear()} or {@link #clearFlatGlowList()}.
     */
    public static String clearChameleonGlowList() {
        for (String p : new ArrayList<String>(glowPlayers2)) {
            GlowTrailRenderer.removeTrailForPlayer(p);
        }
        glowPlayers2.clear();
        saveConfig();
        resetOutlinePipelineClientState();
        boolean hadTeammateGlow = PlayerTarget.isGlowTeammateEnabled();
        if (hadTeammateGlow) {
            PlayerTarget.setTeammateGlowMode(PlayerTarget.TeammateGlowMode.DISABLED);
        }
        StringBuilder msg = new StringBuilder();
        msg.append(Main.CHAT_PREFIX).append(EnumChatFormatting.GREEN)
                .append("Vanilla-style glow off: cleared /glowplayer2 list.");
        if (hadTeammateGlow) {
            msg.append(' ').append(EnumChatFormatting.YELLOW)
                    .append("Teammate glow was on — set to Disabled.");
        }
        if (!glowPlayers.isEmpty()) {
            msg.append(' ').append(EnumChatFormatting.GRAY)
                    .append("Flat /glowplayer list still has ")
                    .append(glowPlayers.size())
                    .append(" name(s); run ")
                    .append(EnumChatFormatting.WHITE).append("/glowplayer").append(EnumChatFormatting.GRAY)
                    .append(" with no args to clear.");
        }
        msg.append(EnumChatFormatting.RESET);
        return msg.toString();
    }

    public static boolean isDebug() { return debug; }

    public static String setDebug(boolean value) {
        debug = value;
        return Main.CHAT_PREFIX + (value ? (EnumChatFormatting.GREEN + "Glow debug enabled") : (EnumChatFormatting.RED + "Glow debug disabled")) + EnumChatFormatting.RESET;
    }

    public static int getColorRgb() { return colorRgb; }

    public static String setColorHex(String hex) {
        if (hex == null || hex.trim().isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /glowplayer color <hex>";
        }
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid hex. Use 6 digits, e.g. #FF00AA";
        }
        try {
            int val = Integer.parseInt(s, 16) & 0xFFFFFF;
            colorRgb = val;
            saveConfig();
            return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Glow color set to #" + String.format("%06X", colorRgb);
        } catch (Exception e) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid hex. Use 6 digits, e.g. #FF00AA";
        }
    }

    /**
     * Gets detailed debug information about why a player is glowing.
     * Used by /glowplayer why <player> command.
     */
    public static String getGlowDebugInfo(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid player name";
        }
        
        String normalized = playerName.trim().toLowerCase();
        boolean inManualList = glowPlayers.contains(normalized);
        boolean inManualList2 = glowPlayers2.contains(normalized);
        
        StringBuilder info = new StringBuilder();
        info.append(Main.CHAT_PREFIX).append(EnumChatFormatting.YELLOW).append("=== Glow Debug for: ").append(EnumChatFormatting.WHITE).append(playerName).append(EnumChatFormatting.RESET).append("\n");
        
        // Check manual glow list
        info.append("/glowplayer (flat outline): ").append(inManualList ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
        info.append("/glowplayer2 (vanilla outline): ").append(inManualList2 ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
        
        // Check teammate glow
        me.napim.gomod.features.PlayerTarget.TeammateGlowMode teammateMode = me.napim.gomod.features.PlayerTarget.getTeammateGlowMode();
        boolean isTeammate = me.napim.gomod.features.PlayerTarget.isNameCurrentTeammate(playerName);
        boolean teammateGlowActive = teammateMode != me.napim.gomod.features.PlayerTarget.TeammateGlowMode.DISABLED;
        
        info.append("Teammate glow: ").append(EnumChatFormatting.WHITE).append(teammateMode.getDisplayLabel()).append(EnumChatFormatting.RESET).append("\n");
        info.append("Is Detected as Teammate: ").append(isTeammate ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
        
        // Determine why they're glowing
        boolean shouldGlow = isGlowing(playerName) || isGlowing2(playerName) || (teammateGlowActive && isTeammate);
        info.append("\n").append("Should Glow: ").append(shouldGlow ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
        
        if (shouldGlow) {
            info.append("\n").append(EnumChatFormatting.GREEN).append("Reasons:").append(EnumChatFormatting.RESET).append("\n");
            if (inManualList2) {
                info.append("  ").append(EnumChatFormatting.GREEN).append("✓ ").append(EnumChatFormatting.WHITE).append("Manually added via /glowplayer2").append(EnumChatFormatting.RESET).append("\n");
            }
            if (inManualList) {
                info.append("  ").append(EnumChatFormatting.GREEN).append("✓ ").append(EnumChatFormatting.WHITE).append("Manually added via /glowplayer").append(EnumChatFormatting.RESET).append("\n");
            }
            if (teammateGlowActive && isTeammate) {
                info.append("  ").append(EnumChatFormatting.GREEN).append("✓ ").append(EnumChatFormatting.WHITE).append("Teammate glow (").append(teammateMode.getDisplayLabel()).append(")").append(EnumChatFormatting.RESET).append("\n");
            }
        }
        
        // Add teammate detection details if teammate glow may apply
        if (teammateGlowActive) {
            info.append("\n").append(EnumChatFormatting.YELLOW).append("--- Teammate Detection Details ---").append(EnumChatFormatting.RESET).append("\n");
            String teammateDebug = me.napim.gomod.features.PlayerTarget.getTeammateDetectionDebug(playerName);
            // Split by newlines and add proper formatting
            String[] lines = teammateDebug.split("\n");
            for (int i = 1; i < lines.length; i++) { // Skip first line (header) as we already have one
                info.append(lines[i]).append("\n");
            }
        }
        
        return info.toString();
    }

    /**
     * Multi-line client snapshot for {@code /glowplayer outlinebug} / {@code /glowplayer2 outlinebug} —
     * outline helper flags, lists, and which loaded players match /glowplayer2.
     */
    public static String buildOutlinePipelineBugReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(Main.CHAT_PREFIX).append(EnumChatFormatting.YELLOW)
                .append("=== gomod outline / glowplayer2 bug dump ===").append(EnumChatFormatting.RESET).append('\n');
        sb.append(EnumChatFormatting.GRAY).append(OutlineRenderingHelper.debugDumpCompact()).append(EnumChatFormatting.RESET).append('\n');
        sb.append(EnumChatFormatting.GRAY).append(LunarGlowArrowMarkers.debugDumpCompact()).append(EnumChatFormatting.RESET).append('\n');
        sb.append(EnumChatFormatting.GRAY).append(GomodEntityOutlineFallback.debugDumpCompact()).append(EnumChatFormatting.RESET).append('\n');
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getRenderManager() != null) {
                boolean ro = ((RenderManagerAccessor) mc.getRenderManager()).gomod$getRenderOutlines();
                sb.append("RenderManager.renderOutlines=").append(ro)
                        .append(" (must be false when not inside our outline draw)").append('\n');
            }
        } catch (Throwable t) {
            sb.append("RenderManager.renderOutlines: <read failed: ").append(t.getMessage()).append(">\n");
        }
        sb.append("Flat /glowplayer names: ").append(glowPlayers.size());
        if (!glowPlayers.isEmpty()) {
            sb.append(" ").append(new ArrayList<>(glowPlayers));
        }
        sb.append('\n');
        sb.append("/glowplayer2 names: ").append(glowPlayers2.size());
        if (!glowPlayers2.isEmpty()) {
            sb.append(" ").append(new ArrayList<>(glowPlayers2));
        }
        sb.append('\n');
        sb.append("Teammate glow: ").append(PlayerTarget.getTeammateGlowMode().getDisplayLabel())
                .append(" on=").append(PlayerTarget.isGlowTeammateEnabled()).append('\n');
        List<String> queuerTargets = PlayerQueuer.getTargets();
        sb.append("PlayerQueuer on=").append(PlayerQueuer.isEnabled())
                .append(" targetCount=").append(queuerTargets.size());
        if (!queuerTargets.isEmpty()) {
            sb.append(" targets=").append(queuerTargets);
        }
        sb.append('\n');

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.theWorld != null) {
                int others = 0, nGp2 = 0, nGp1 = 0, nTm = 0, nPq = 0;
                List<String> gp2Lines = new ArrayList<>();
                for (EntityPlayer p : mc.theWorld.playerEntities) {
                    if (p == null || p == mc.thePlayer) {
                        continue;
                    }
                    others++;
                    if (GlowPlayer.isGlowingPlayer2(p)) {
                        nGp2++;
                        String vis = p.getName();
                        String prof = "";
                        try {
                            com.mojang.authlib.GameProfile gp = p.getGameProfile();
                            if (gp != null && gp.getName() != null) {
                                prof = gp.getName();
                            }
                        } catch (Throwable ignored) {
                        }
                        gp2Lines.add(vis + (prof.isEmpty() || prof.equals(vis) ? "" : (" (profile:" + prof + ")")));
                    }
                    if (GlowPlayer.isGlowingPlayer(p)) {
                        nGp1++;
                    }
                    if (PlayerTarget.isGlowTeammateEnabled() && PlayerTarget.isEntityCurrentTeammate(p)) {
                        nTm++;
                    }
                    if (PlayerQueuer.isQueuedTargetPlayer(p)) {
                        nPq++;
                    }
                }
                sb.append("Other players loaded: ").append(others)
                        .append(" | flatMatch=").append(nGp1)
                        .append(" chameleonMatch=").append(nGp2)
                        .append(" teammateGlowMatch=").append(nTm)
                        .append(" queuerMatch=").append(nPq).append('\n');
                if (!queuerTargets.isEmpty() && nPq == 0 && others > 0) {
                    List<String> samples = new ArrayList<>();
                    int shown = 0;
                    for (EntityPlayer p : mc.theWorld.playerEntities) {
                        if (p == null || p == mc.thePlayer) {
                            continue;
                        }
                        if (shown++ >= 6) {
                            break;
                        }
                        String vis = p.getName();
                        String prof = "";
                        try {
                            com.mojang.authlib.GameProfile gp = p.getGameProfile();
                            if (gp != null && gp.getName() != null) {
                                prof = gp.getName();
                            }
                        } catch (Throwable ignored) {
                        }
                        samples.add((vis != null ? vis : "?") + " |profile:" + prof);
                    }
                    sb.append(EnumChatFormatting.GRAY).append("Queuer: no in-world match yet; sample display|profile: ")
                            .append(String.join(" ; ", samples)).append(EnumChatFormatting.RESET).append('\n');
                }
                if (!gp2Lines.isEmpty()) {
                    sb.append(EnumChatFormatting.GOLD).append("Clientside /glowplayer2 targets in world NOW: ")
                            .append(EnumChatFormatting.WHITE).append(String.join(", ", gp2Lines)).append(EnumChatFormatting.RESET).append('\n');
                }
                sb.append(EnumChatFormatting.GRAY)
                        .append("If \"everyone\" has a halo: clear lists (/glowplayer, /glowplayer2), disable teammate glow, check RenderManager.renderOutlines above, relog.")
                        .append(EnumChatFormatting.RESET).append('\n');
            }
        } catch (Throwable t) {
            sb.append("World scan failed: ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}


