package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.features.ScoreboardDateHider;
import me.napim.gomod.features.SidebarBlitzHourReminder;
import me.napim.gomod.features.SidebarTauntWastedMutator;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScoreboardDebugCommand extends Command {
    public ScoreboardDebugCommand() {
        super("sdebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                sendMessage(EnumChatFormatting.RED + "No scoreboard available");
                return;
            }

            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);

            if (objective == null) {
                sendMessage(EnumChatFormatting.RED + "No scoreboard objective found");
                return;
            }

            StringBuilder fileBody = new StringBuilder(4096);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            fileBody.append("gomod scoreboard debug — ").append(ts).append("\n");
            fileBody.append("hideScoreboardValues (master): ").append(ConfigManager.isHideScoreboardValuesEnabled()).append("\n");
            fileBody.append("hideDateGameId (sub): ").append(ConfigManager.isHideDateGameIdEnabled()).append("\n");
            fileBody.append("hideKitScoreboard (sub): ").append(ConfigManager.isHideKitScoreboardEnabled()).append("\n");
            fileBody.append("hideHypixelFooter (sub): ").append(ConfigManager.isHideHypixelFooterEnabled()).append("\n");
            fileBody.append("showBlitzHourLine (sub): ").append(ConfigManager.isSidebarBlitzHourLineEnabled()).append("\n");
            fileBody.append("rememberedBlitzHourGame: ").append(SidebarBlitzHourReminder.isRememberedBlitzHourGame()).append("\n\n");

            sendMessage(EnumChatFormatting.GOLD + "=== SCOREBOARD DEBUG ===");
            sendMessage(EnumChatFormatting.YELLOW + "Objective: " + objective.getDisplayName());
            sendMessage(EnumChatFormatting.YELLOW + "Total entries: " + scoreboard.getSortedScores(objective).size());
            sendMessage("");

            int entryNum = 1;
            for (Score score : scoreboard.getSortedScores(objective)) {
                ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String raw = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                String clean = raw != null ? raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "") : "null";
                String lowerClean = clean.toLowerCase(Locale.ROOT);

                sendMessage(EnumChatFormatting.WHITE + "Entry " + entryNum + ":");
                sendMessage(EnumChatFormatting.GRAY + "  Raw: " + (raw != null ? raw : "null"));
                sendMessage(EnumChatFormatting.GRAY + "  Clean: " + clean);
                sendMessage(EnumChatFormatting.GRAY + "  Lower: " + lowerClean);
                sendMessage(EnumChatFormatting.GRAY + "  Contains 'team': " + lowerClean.contains("team"));
                sendMessage(EnumChatFormatting.GRAY + "  Player name: " + score.getPlayerName());
                sendMessage("");

                fileBody.append("--- Entry ").append(entryNum).append(" ---\n");
                fileBody.append("  Raw: ").append(raw != null ? raw : "null").append("\n");
                fileBody.append("  Clean: ").append(clean).append("\n");
                fileBody.append("  score player name: ").append(score.getPlayerName()).append("\n");
                fileBody.append("  hide date/gameID (mod): ").append(ScoreboardDateHider.debugIsDateGameIdText(raw)).append("\n");
                fileBody.append("  hide kit (mod): ").append(ScoreboardDateHider.debugIsKitText(raw)).append("\n");
                fileBody.append("  hide www.hypixel.net (mod): ").append(ScoreboardDateHider.debugIsHypixelFooterText(raw)).append("\n\n");

                entryNum++;
            }

            appendTeamsSection(fileBody, scoreboard);

            appendTauntWastedSection(fileBody);

            appendDisguiseSidebarSection(fileBody);

            sendMessage(EnumChatFormatting.GOLD + "=== END DEBUG ===");

            String written = writeDebugFile(fileBody.toString());
            if (written != null) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved: " + EnumChatFormatting.WHITE + written);
            } else {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Could not write debug .txt (see log).");
            }

        } catch (Exception e) {
            sendMessage(EnumChatFormatting.RED + "Error: " + e.getMessage());
        }
    }

    /**
     * Dumps the state driving {@link SidebarTauntWastedMutator} so we can tell exactly why
     * {@code Taunt: USED → WASTED} rewrite is / isn't happening on a given tick.
     */
    private static void appendTauntWastedSection(StringBuilder sb) {
        sb.append("\n=== TAUNT WASTED (sidebar USED→WASTED rewrite) ===\n");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            boolean master = ConfigManager.isHideScoreboardValuesEnabled();
            boolean sub = ConfigManager.isSidebarTauntWastedEnabled();
            boolean ctx = ScoreboardDateHider.isHypixelSidebarTweaksContext(mc);
            boolean hypixel = GameContext.isOnHypixel(mc);
            boolean tauntRow = BlitzTeamsDetector.sidebarHasTaunt(mc);
            boolean featureTickEnabled = ClientTickHub.isFeatureEnabled("SidebarTauntWastedMutator");

            long tauntStart = PlayerTarget.debugLastOwnTauntWallclockMs();
            boolean hadBonus = PlayerTarget.debugLocalTauntHadBonus();
            long windowMs = PlayerTarget.debugTauntBonusWindowMs();
            long now = System.currentTimeMillis();
            long elapsed = tauntStart > 0L ? (now - tauntStart) : -1L;
            boolean wasted = PlayerTarget.isLocalTauntWastedState();
            boolean successGreen = PlayerTarget.isLocalTauntSuccessSidebarState();
            boolean appliedNow = SidebarTauntWastedMutator.isApplied();
            String appliedTeam = SidebarTauntWastedMutator.debugAppliedTeamName();

            sb.append("Config:\n");
            sb.append("  hideScoreboardValuesEnabled (master): ").append(master).append("\n");
            sb.append("  sidebarTauntWastedEnabled (sub):      ").append(sub).append("\n");
            sb.append("Tick feature enabled: ").append(featureTickEnabled).append("\n");
            sb.append("Context:\n");
            sb.append("  isOnHypixel: ").append(hypixel).append("\n");
            sb.append("  isHypixelSidebarTweaksContext: ").append(ctx).append("\n");
            sb.append("  BlitzTeamsDetector.sidebarHasTaunt: ").append(tauntRow).append("\n");
            sb.append("Local taunt tracking:\n");
            if (mc != null && mc.thePlayer != null) {
                sb.append("  player name: ").append(mc.thePlayer.getName()).append("\n");
                try {
                    sb.append("  profile name: ").append(mc.thePlayer.getGameProfile().getName()).append("\n");
                } catch (Throwable ignored) {
                }
            } else {
                sb.append("  player: <null>\n");
            }
            sb.append("  lastOwnTauntWallclockMs: ").append(tauntStart)
                    .append(tauntStart > 0L ? "" : " (no taunt chat parsed since last reset)").append("\n");
            sb.append("  ms since taunt: ").append(elapsed).append("\n");
            sb.append("  bonus window (ms): ").append(windowMs).append("\n");
            sb.append("  localPlayerTauntHadBonus: ").append(hadBonus).append("\n");
            sb.append("  isLocalTauntWastedState: ").append(wasted).append("\n");
            sb.append("  isLocalTauntSuccessSidebarState: ").append(successGreen).append("\n");
            sb.append("Mutator:\n");
            sb.append("  applied now: ").append(appliedNow).append("\n");
            sb.append("  applied team: ").append(appliedTeam == null ? "<none>" : appliedTeam).append("\n");
            sb.append("Reason rewrite not happening (first true wins):\n");
            if (!master) sb.append("  -> master \"Sidebar tweaks\" (hideScoreboardValues) is OFF.\n");
            else if (!sub) sb.append("  -> sub toggle \"Taunt details\" is OFF.\n");
            else if (!featureTickEnabled) sb.append("  -> tick feature SidebarTauntWastedMutator disabled.\n");
            else if (!hypixel) sb.append("  -> not on Hypixel.\n");
            else if (!ctx) sb.append("  -> isHypixelSidebarTweaksContext false.\n");
            else if (!tauntRow) sb.append("  -> sidebar has no 'Taunt' row visible.\n");
            else if (tauntStart <= 0L) sb.append("  -> taunt chat never parsed (no 'performed the ... Taunt!' for local player).\n");
            else if (hadBonus) sb.append("  -> successful taunt (Taunt Bonus); sidebar should show green USED if row still says USED.\n");
            else if (elapsed < windowMs) sb.append("  -> still inside 17s bonus window (ms left: ").append(windowMs - elapsed).append(").\n");
            else if (!wasted) sb.append("  -> isLocalTauntWastedState false (unexpected).\n");
            else if (!appliedNow) sb.append("  -> mutator should apply WASTED but hasn't this tick (row finder / prefix rewrite).\n");
            else sb.append("  -> mutator IS applied (WASTED or green USED rewrite active).\n");
        } catch (Throwable t) {
            sb.append("Error: ").append(t.getMessage()).append("\n");
        }
    }

    /**
     * {@code /sdebug} lists raw Hypixel scoreboard rows above. Sidebar disguise rewrites use the target player's API
     * stats — this section explains that split and dumps {@link LocalDisguise#buildDisguiseSidebarDebugLines()}.
     */
    private static void appendDisguiseSidebarSection(StringBuilder sb) {
        sb.append("\n=== DISGUISE SIDEBAR (mod rewrites — NOT the raw rows above) ===\n");
        try {
            if (!LocalDisguise.isEnabled()) {
                sb.append("Disguise: off (no target stats applied to sidebar).\n");
                return;
            }
            sb.append("Disguise: on. Raw entries are still YOUR account's scoreboard strings from the server.\n");
            sb.append("The client replaces stat values using the disguise target's Blitz API stats when override is active.\n\n");
            List<String> lines = LocalDisguise.buildDisguiseSidebarDebugLines();
            if (lines == null || lines.isEmpty()) {
                sb.append("(no disguise sidebar debug lines)\n");
                return;
            }
            for (String line : lines) {
                sb.append(line).append("\n");
            }
        } catch (Throwable t) {
            sb.append("Error: ").append(t.getMessage()).append("\n");
        }
    }

    private static void appendTeamsSection(StringBuilder sb, Scoreboard scoreboard) {
        sb.append("\n=== TEAMS (string fields — hide date/game ID uses these) ===\n");
        try {
            Collection<ScorePlayerTeam> teams = scoreboard.getTeams();
            if (teams == null || teams.isEmpty()) {
                sb.append("(no teams)\n");
                return;
            }
            for (ScorePlayerTeam team : teams) {
                if (team == null) {
                    continue;
                }
                sb.append("--- Team registered: ").append(team.getRegisteredName()).append(" ---\n");
                appendStringFields(sb, team);
                sb.append("\n");
            }
        } catch (Throwable t) {
            sb.append("Error listing teams: ").append(t.getMessage()).append("\n");
        }
    }

    private static void appendStringFields(StringBuilder sb, Object target) {
        try {
            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (!(val instanceof String)) {
                        continue;
                    }
                    String s = (String) val;
                    sb.append("  ").append(f.getName()).append(": ").append(s).append("\n");
                    sb.append("    hide date/gameID: ").append(ScoreboardDateHider.debugIsDateGameIdText(s)).append("\n");
                    sb.append("    hide kit: ").append(ScoreboardDateHider.debugIsKitText(s)).append("\n");
                }
            }
        } catch (Throwable t) {
            sb.append("  (reflection error: ").append(t.getMessage()).append(")\n");
        }
    }

    /**
     * Writes {@code <gameDir>/gomod123/sdebug_logs/scoreboard_debug_yyyyMMdd_HHmmss.txt}
     * and mirrors the same content to {@code <gameDir>/gomod123/scoreboard_debug_latest.txt}.
     */
    private static String writeDebugFile(String text) {
        File base = GomodPaths.getGomod123Dir();
        File dir = new File(base, "sdebug_logs");
        String name = "scoreboard_debug_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        File timestamped = new File(dir, name);
        File latest = new File(base, "scoreboard_debug_latest.txt");
        if (!tryWrite(timestamped, text)) {
            return null;
        }
        tryWrite(latest, text);
        return timestamped.getAbsolutePath();
    }

    private static boolean tryWrite(File f, String text) {
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists() && !p.mkdirs()) {
                return false;
            }
            writeUtf8(f, text);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeUtf8(File f, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
            try {
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void sendMessage(String message) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(message));
        }
    }
}
