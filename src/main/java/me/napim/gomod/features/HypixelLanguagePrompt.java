package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Passively checks once per session on Hypixel whether the player's Hypixel language is non-English.
 * <p>
 * As soon as an English Hypixel sidebar is observed OR a single clickable prompt has been shown,
 * this shuts itself off for the remainder of the session so there is zero ongoing tick overhead.
 */
public final class HypixelLanguagePrompt {

    enum LanguageState {
        ENGLISH,
        NON_ENGLISH,
        UNKNOWN
    }

    private static final int CHECK_INTERVAL_TICKS = 60; // ~3 seconds while unsettled

    private static final String[] ENGLISH_SIDEBAR_MARKERS = {
            "coins:",
            "kills:",
            "wins:",
            "players left:",
            "next event:",
            "blitz star:",
            "deathmatch:",
            "team kills:",
            "solo kills:",
            "time left:",
            "players:",
            "lobby:",
            "server:",
            "level:",
            "karma:",
            "rank:",
            "mode:",
            "map:",
            "kit:"
    };

    private static final String[] NON_ENGLISH_SIDEBAR_MARKERS = {
            // French
            "pi\u00e8ces:", "tu\u00e9s:", "victoires:", "joueurs restants:",
            "\u00e9v\u00e9nement suivant:", "niveau:", "serveur:", "carte:",
            "joueurs:", "temps restant:",
            // Spanish
            "monedas:", "asesinatos:", "victorias:", "jugadores restantes:",
            "siguiente evento:", "nivel:", "servidor:", "mapa:",
            "jugadores:", "tiempo restante:",
            // German
            "m\u00fcnzen:", "t\u00f6tungen:", "siege:", "verbleibende spieler:",
            "n\u00e4chstes ereignis:", "spieler:", "zeit \u00fcbrig:",
            // Portuguese
            "moedas:", "abates:", "vit\u00f3rias:", "jogadores restantes:",
            "pr\u00f3ximo evento:", "jogadores:",
            // Italian
            "monete:", "uccisioni:", "vittorie:", "giocatori rimasti:",
            "prossimo evento:", "giocatori:",
            // Dutch
            "munten:", "overwinningen:", "spelers over:", "spelers:",
            // Polish
            "monety:", "zab\u00f3jstwa:", "wygrane:", "pozostali gracze:", "gracze:",
            // Russian
            "\u043c\u043e\u043d\u0435\u0442\u044b:", "\u0443\u0431\u0438\u0439\u0441\u0442\u0432\u0430:",
            "\u043f\u043e\u0431\u0435\u0434\u044b:", "\u043e\u0441\u0442\u0430\u043b\u043e\u0441\u044c \u0438\u0433\u0440\u043e\u043a\u043e\u0432:",
            "\u0438\u0433\u0440\u043e\u043a\u0438:", "\u0443\u0440\u043e\u0432\u0435\u043d\u044c:",
            // Chinese (Simplified & Traditional)
            "\u786c\u5e01:", "\u786c\u5e63:", "\u51fb\u6740:", "\u64ca\u6bba:",
            "\u80dc\u5229:", "\u52dd\u5229:", "\u5269\u4f59\u73a9\u5bb6:", "\u5269\u9918\u73a9\u5bb6:",
            "\u73a9\u5bb6:", "\u7b49\u7ea7:", "\u7b49\u7d1a:",
            // Japanese
            "\u30b3\u30a4\u30f3:", "\u30ad\u30eb:", "\u52dd\u5229:",
            "\u6b8b\u308a\u30d7\u30ec\u30a4\u30e4\u30fc:", "\u30d7\u30ec\u30a4\u30e4\u30fc:", "\u30ec\u30d9\u30eb:",
            // Korean
            "\ucf54\uc778:", "\ud0ac:", "\uc2b9\ub9ac:",
            "\ub0a8\uc740 \ud50c\ub808\uc774\uc5b4:", "\ud50c\ub808\uc774\uc5b4:", "\ub808\ubca8:",
            // Turkish
            "jeton:", "\u00f6ld\u00fcrme:", "galibiyet:", "kalan oyuncular:",
            // Pirate Speak
            "doubloons:", "mateys:"
    };

    private static volatile boolean doneForSession = false;
    private static int tickCounter = 0;

    private HypixelLanguagePrompt() {
    }

    public static void onClientTick(Minecraft mc) {
        if (doneForSession) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        List<String> sidebarLines = readSidebarLines(mc);
        if (sidebarLines.isEmpty()) {
            return;
        }
        if (!isHypixelContext(extractServerIp(mc), sidebarLines)) {
            return;
        }

        LanguageState state = classifySidebarLines(sidebarLines);
        if (state == LanguageState.ENGLISH) {
            doneForSession = true;
        } else if (state == LanguageState.NON_ENGLISH) {
            doneForSession = true;
            sendEnglishPrompt(mc);
        }
    }

    private static String extractServerIp(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        ServerData server = mc.getCurrentServerData();
        return server == null ? null : server.serverIP;
    }

    static boolean isHypixelContext(String serverIp, List<String> sidebarLines) {
        if (serverIp != null && serverIp.toLowerCase(Locale.ROOT).contains("hypixel.net")) {
            return true;
        }
        if (sidebarLines != null) {
            for (String raw : sidebarLines) {
                if (normalizeLine(raw).contains("hypixel.net")) {
                    return true;
                }
            }
        }
        return false;
    }

    static LanguageState classifySidebarLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return LanguageState.UNKNOWN;
        }
        boolean sawNonEnglish = false;
        for (String raw : lines) {
            String normalized = normalizeLine(raw);
            if (normalized.isEmpty()) {
                continue;
            }
            for (String eng : ENGLISH_SIDEBAR_MARKERS) {
                if (normalized.contains(eng)) {
                    return LanguageState.ENGLISH;
                }
            }
            if (!sawNonEnglish) {
                for (String nonEng : NON_ENGLISH_SIDEBAR_MARKERS) {
                    if (normalized.contains(nonEng)) {
                        sawNonEnglish = true;
                        break;
                    }
                }
            }
        }
        return sawNonEnglish ? LanguageState.NON_ENGLISH : LanguageState.UNKNOWN;
    }

    static String normalizeLine(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            // Strip zero-width / bidi / private-use formatting chars Hypixel sometimes injects
            if (c == '\u200b' || c == '\u200c' || c == '\u200d' || c == '\ufeff') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> readSidebarLines(Minecraft mc) {
        List<String> result = new ArrayList<String>();
        try {
            Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) {
                return result;
            }
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return result;
            }
            if (objective.getDisplayName() != null) {
                result.add(objective.getDisplayName());
            }
            Collection<Score> scores = scoreboard.getSortedScores(objective);
            if (scores != null) {
                for (Score score : scores) {
                    if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                        continue;
                    }
                    ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                    String formatted = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                    if (formatted != null) {
                        result.add(formatted);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void sendEnglishPrompt(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        try {
            ChatComponentText notice = new ChatComponentText(
                    Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                            + "Your Hypixel language is not set to English. "
                            + EnumChatFormatting.GRAY + "Some Blitz features require English chat."
            );
            ChatComponentText actionLine = new ChatComponentText("  ");
            ChatComponentText clickButton = new ChatComponentText(
                    EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "[Click here to run /language english]"
            );
            ChatStyle style = new ChatStyle()
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/language english"))
                    .setChatHoverEvent(new HoverEvent(
                            HoverEvent.Action.SHOW_TEXT,
                            new ChatComponentText(EnumChatFormatting.GREEN + "Click to switch Hypixel to English")
                    ));
            clickButton.setChatStyle(style);
            actionLine.appendSibling(clickButton);

            mc.thePlayer.addChatMessage(notice);
            mc.thePlayer.addChatMessage(actionLine);
        } catch (Throwable ignored) {
        }
    }

    static void resetForTest() {
        doneForSession = false;
        tickCounter = 0;
    }

    static boolean isDoneForSession() {
        return doneForSession;
    }
}
