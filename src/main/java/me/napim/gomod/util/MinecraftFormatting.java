package me.napim.gomod.util;

/** Same ASCII legacy-code rules as Minecraft's formatting regex, without compiling/matching per draw. */
public final class MinecraftFormatting {
    private MinecraftFormatting() { }

    public static String stripSectionCodes(String text) {
        return strip(text, false);
    }

    public static String stripSectionAndAmpersandCodes(String text) {
        return strip(text, true);
    }

    private static String strip(String text, boolean ampersands) {
        if (text == null) return "";
        StringBuilder result = null;
        int copied = 0;
        for (int i = 0; i + 1 < text.length(); i++) {
            char marker = text.charAt(i);
            if (marker != '\u00a7' && (!ampersands || marker != '&')) continue;
            char code = text.charAt(i + 1);
            if (code >= 'A' && code <= 'Z') code = (char) (code + ('a' - 'A'));
            if (!(code >= '0' && code <= '9' || code >= 'a' && code <= 'f'
                    || code >= 'k' && code <= 'o' || code == 'r')) continue;
            if (result == null) result = new StringBuilder(text.length());
            result.append(text, copied, i);
            copied = i + 2;
            i++;
        }
        return result == null ? text : result.append(text, copied, text.length()).toString();
    }
}
