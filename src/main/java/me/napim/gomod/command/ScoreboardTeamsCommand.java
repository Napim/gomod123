package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScoreboardTeamsCommand extends Command {
    private static final int CHAT_TEAM_LIMIT = 80;

    public ScoreboardTeamsCommand() {
        super("scoreboardteams");
    }

    @Override
    public void handle(String[] args) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No scoreboard available.");
                return;
            }

            String filter = args != null && args.length > 0 ? args[0].trim().toLowerCase(Locale.ROOT) : "";
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            List<ScorePlayerTeam> teams = new ArrayList<ScorePlayerTeam>();
            Collection<ScorePlayerTeam> rawTeams = scoreboard.getTeams();
            if (rawTeams != null) {
                teams.addAll(rawTeams);
            }
            Collections.sort(teams, new Comparator<ScorePlayerTeam>() {
                @Override
                public int compare(ScorePlayerTeam a, ScorePlayerTeam b) {
                    return safeTeamName(a).compareToIgnoreCase(safeTeamName(b));
                }
            });

            StringBuilder dump = new StringBuilder(8192);
            dump.append("gomod scoreboard teams debug - ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                    .append("\n");
            if (!filter.isEmpty()) {
                dump.append("filter: ").append(filter).append("\n");
            }
            dump.append("team count: ").append(teams.size()).append("\n\n");
            dump.append("=== TEAM -> MEMBERS ===\n");

            sendMessage(EnumChatFormatting.GOLD + "=== SCOREBOARD TEAMS ===");
            if (!filter.isEmpty()) {
                sendMessage(EnumChatFormatting.GRAY + "Filter: " + EnumChatFormatting.WHITE + filter);
            }

            int matched = 0;
            int chatShown = 0;
            for (ScorePlayerTeam team : teams) {
                if (team == null) {
                    continue;
                }
                TeamSnapshot snap = snapshot(team);
                if (!matchesFilter(snap, filter)) {
                    continue;
                }
                matched++;
                appendTeamDump(dump, snap);
                if (chatShown < CHAT_TEAM_LIMIT) {
                    sendMessage(formatChatTeamLine(matched, snap));
                    chatShown++;
                }
            }

            appendPlayerToTeamDump(dump, teams, filter);
            if (matched == 0) {
                sendMessage(EnumChatFormatting.YELLOW + "No scoreboard teams matched.");
            } else if (chatShown < matched) {
                sendMessage(EnumChatFormatting.GRAY + "Chat showed " + chatShown + "/" + matched
                        + " teams. Full dump saved below.");
            }

            String path = writeLatestDump(dump.toString());
            if (path != null) {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved: "
                        + EnumChatFormatting.WHITE + path);
            } else {
                sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Could not write scoreboard teams dump.");
            }
        } catch (Throwable t) {
            sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Scoreboard teams error: " + t.getMessage());
        }
    }

    private static TeamSnapshot snapshot(ScorePlayerTeam team) {
        TeamSnapshot snap = new TeamSnapshot();
        snap.name = safeTeamName(team);
        try {
            snap.prefix = team.getColorPrefix();
        } catch (Throwable ignored) {
            snap.prefix = "";
        }
        try {
            snap.suffix = team.getColorSuffix();
        } catch (Throwable ignored) {
            snap.suffix = "";
        }
        try {
            Collection<String> members = team.getMembershipCollection();
            if (members != null) {
                snap.members.addAll(members);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(snap.members, String.CASE_INSENSITIVE_ORDER);
        for (String member : snap.members) {
            try {
                snap.formattedMembers.add(ScorePlayerTeam.formatPlayerName(team, member));
            } catch (Throwable ignored) {
                snap.formattedMembers.add(member);
            }
        }
        return snap;
    }

    private static boolean matchesFilter(TeamSnapshot snap, String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (containsIgnoreCase(snap.name, filter)
                || containsIgnoreCase(stripFormatting(snap.prefix), filter)
                || containsIgnoreCase(stripFormatting(snap.suffix), filter)) {
            return true;
        }
        for (String member : snap.members) {
            if (containsIgnoreCase(member, filter) || containsIgnoreCase(stripFormatting(member), filter)) {
                return true;
            }
        }
        for (String member : snap.formattedMembers) {
            if (containsIgnoreCase(stripFormatting(member), filter)) {
                return true;
            }
        }
        return false;
    }

    private static void appendTeamDump(StringBuilder dump, TeamSnapshot snap) {
        dump.append("--- Team: ").append(snap.name).append(" ---\n");
        dump.append("prefix raw: ").append(nullToEmpty(snap.prefix)).append("\n");
        dump.append("prefix clean: ").append(stripFormatting(snap.prefix)).append("\n");
        dump.append("suffix raw: ").append(nullToEmpty(snap.suffix)).append("\n");
        dump.append("suffix clean: ").append(stripFormatting(snap.suffix)).append("\n");
        dump.append("members (").append(snap.members.size()).append("):\n");
        for (int i = 0; i < snap.members.size(); i++) {
            String member = snap.members.get(i);
            String formatted = i < snap.formattedMembers.size() ? snap.formattedMembers.get(i) : member;
            dump.append("  - ").append(member)
                    .append(" | formatted=").append(formatted)
                    .append(" | clean=").append(stripFormatting(formatted))
                    .append("\n");
        }
        dump.append("\n");
    }

    private static void appendPlayerToTeamDump(StringBuilder dump, List<ScorePlayerTeam> teams, String filter) {
        dump.append("=== MEMBER -> TEAM ===\n");
        for (ScorePlayerTeam team : teams) {
            if (team == null) {
                continue;
            }
            TeamSnapshot snap = snapshot(team);
            if (!matchesFilter(snap, filter)) {
                continue;
            }
            for (String member : snap.members) {
                dump.append(member).append(" -> ").append(snap.name).append("\n");
            }
        }
    }

    private static String formatChatTeamLine(int index, TeamSnapshot snap) {
        String members = joinMembersForChat(snap.members);
        String prefixClean = stripFormatting(snap.prefix);
        String suffixClean = stripFormatting(snap.suffix);
        StringBuilder out = new StringBuilder();
        out.append(EnumChatFormatting.GRAY).append("#").append(index).append(" ")
                .append(EnumChatFormatting.YELLOW).append(snap.name)
                .append(EnumChatFormatting.DARK_GRAY).append(" [")
                .append(EnumChatFormatting.WHITE).append(snap.members.size())
                .append(EnumChatFormatting.DARK_GRAY).append("] ")
                .append(EnumChatFormatting.WHITE).append(members);
        if (!prefixClean.isEmpty() || !suffixClean.isEmpty()) {
            out.append(EnumChatFormatting.DARK_GRAY).append(" prefix='")
                    .append(EnumChatFormatting.GRAY).append(prefixClean)
                    .append(EnumChatFormatting.DARK_GRAY).append("' suffix='")
                    .append(EnumChatFormatting.GRAY).append(suffixClean)
                    .append(EnumChatFormatting.DARK_GRAY).append("'");
        }
        return out.toString();
    }

    private static String joinMembersForChat(List<String> members) {
        if (members == null || members.isEmpty()) {
            return EnumChatFormatting.DARK_GRAY + "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members.get(i));
            if (sb.length() > 120) {
                sb.append(", ...");
                break;
            }
        }
        return sb.toString();
    }

    private static String safeTeamName(ScorePlayerTeam team) {
        if (team == null) {
            return "";
        }
        try {
            String name = team.getRegisteredName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && needleLower != null
                && value.toLowerCase(Locale.ROOT).contains(needleLower.toLowerCase(Locale.ROOT));
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String writeLatestDump(String text) {
        try {
            File base = GomodPaths.getGomod123Dir();
            File latest = new File(base, "scoreboard_teams_latest.txt");
            File parent = latest.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return null;
            }
            FileOutputStream fos = new FileOutputStream(latest);
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
            return latest.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void sendMessage(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    private static final class TeamSnapshot {
        private String name = "";
        private String prefix = "";
        private String suffix = "";
        private final List<String> members = new ArrayList<String>();
        private final List<String> formattedMembers = new ArrayList<String>();
    }
}
