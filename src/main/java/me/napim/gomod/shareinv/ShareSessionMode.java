package me.napim.gomod.shareinv;

import java.util.Locale;

/**
 * Share-session feature selector carried through relay invites/session state.
 */
public enum ShareSessionMode {
    SHARE_INV("shareinv", "ShareInv", "inventories", "inventory");

    private final String wireName;
    private final String displayName;
    private final String invitePlural;
    private final String sessionNoun;

    ShareSessionMode(String wireName, String displayName, String invitePlural, String sessionNoun) {
        this.wireName = wireName;
        this.displayName = displayName;
        this.invitePlural = invitePlural;
        this.sessionNoun = sessionNoun;
    }

    public String wireName() {
        return wireName;
    }

    public String commandName() {
        return wireName;
    }

    public String displayName() {
        return displayName;
    }

    public String invitePlural() {
        return invitePlural;
    }

    public String sessionNoun() {
        return sessionNoun;
    }

    public static ShareSessionMode fromWire(String raw) {
        return SHARE_INV;
    }

    /** Legacy ShareView wire values — feature removed; invites should be declined. */
    public static boolean isObsoleteShareViewWire(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return "shareview".equals(normalized) || "view".equals(normalized) || "views".equals(normalized);
    }
}
