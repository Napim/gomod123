package me.napim.gomod.features;

import net.minecraft.util.EnumChatFormatting;

import java.util.Locale;

/**
 * Helpers for recognizing Hypixel's Blitz Teams sidebar layout.
 *
 * <p>Teams mode intentionally has no {@code Kit: ...} row. The {@code Team} row is only treated as a
 * layout anchor for optional synthetic rows such as KE and Blitz Hour; it must never be rewritten as a kit row.</p>
 */
public final class SidebarTeamsLayout {

    private SidebarTeamsLayout() {
    }

    /** True for Hypixel Teams {@code Team} anchor rows ({@code Team🍭}), not {@code Teams: 2} stat lines. */
    public static boolean isTeamAnchorLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (ScoreboardDateHider.isKitSidebarTextOrFragment(text)) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim();
        String lower = plain.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("team")) {
            return false;
        }
        if (lower.startsWith("teams:") || lower.startsWith("teams ")) {
            return false;
        }
        String rest = plain.substring(4).trim();
        if (rest.isEmpty()) {
            return true;
        }
        for (int i = 0; i < rest.length(); ) {
            int cp = rest.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }
}
