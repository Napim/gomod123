package me.napim.gomod.features;

import java.util.Locale;
import java.util.regex.*;

/** Only public match and spectator player speech; private/team/global-shout/system traffic is excluded. */
public final class BlitzGameChatLine {
    private static final Pattern SPEECH = Pattern.compile("^(?:\\[[A-Za-z0-9_+ ,.-]{1,48}\\]\\s*)*([A-Za-z0-9_]{1,16})(?:\\s+\\[[A-Za-z0-9_+ -]{1,32}\\])?\\s*:\\s*(\\S.*)$");
    public final String channel, speaker, formatted, key;
    private BlitzGameChatLine(String channel, String speaker, String formatted, String body) {
        this.channel = channel; this.speaker = speaker; this.formatted = formatted;
        this.key = channel + "\n" + speaker.toLowerCase(Locale.ROOT) + "\n" + body;
    }
    public static BlitzGameChatLine parse(String formatted) {
        if (formatted == null || formatted.length() > 512 || formatted.isEmpty()) return null;
        for (int i=0;i<formatted.length();i++) if (Character.isISOControl(formatted.charAt(i))) return null;
        String plain = formatted.replaceAll("(?i)\u00a7[0-9a-fk-or]", "").trim();
        if (plain.indexOf('\u00a7') >= 0) return null;
        String channel = "alive";
        if (plain.regionMatches(true, 0, "[SPECTATOR]", 0, 11)) {
            channel = "spectator"; plain = plain.substring(11).trim();
        }
        int colon = plain.indexOf(':');
        if (colon < 0) return null;
        String header = plain.substring(0, colon).toUpperCase(Locale.ROOT);
        for (String blocked : new String[]{"[TEAM]", "[SHOUT]", "[PARTY]", "[GUILD]", "[OFFICER]", "[SPECTATOR]"}) {
            if (header.contains(blocked)) return null;
        }
        Matcher matcher = SPEECH.matcher(plain);
        if (!matcher.matches()) return null;
        return new BlitzGameChatLine(channel, matcher.group(1), formatted, matcher.group(2));
    }
}
