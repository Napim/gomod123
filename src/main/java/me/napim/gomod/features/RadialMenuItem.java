package me.napim.gomod.features;

/**
 * Represents one slice of the Radial Menu wheel.
 * Each slot has a display label, a color, an action type, and an action value.
 */
public class RadialMenuItem {

    public enum ActionType {
        NONE,
        TOGGLE_FEATURE,
        CHAT_MESSAGE,
        COMMAND
    }

    /**
     * Human-readable list of toggle-able features: {internal key, display name}.
     * Order groups related modules together (BestArmor + %, teammate trio, team spam pair, …).
     */
    public static final String[][] TOGGLE_FEATURE_OPTIONS = {
        {"BEST_ARMOR",         "BestArmor"},
        {"BEST_ARMOR_PERCENTAGE", "BestArmor %"},
        {"PLAYER_TARGET",      "Teammate tracker"},
        {"TEAMMATE_GLOW",      "Teammate skin glow"},
        {"TEAMMATE_TRAIL",     "Teammate travel trail"},
        {"TEAM_SPAM",          "Team Spam"},
        {"TEAM_SPAM_REFRESH",  "Refresh player list"},
        {"VISIBLE_SPECS",      "Spectator settings"},
        {"SEE_SPECTATORS",     "See spectators"},
        {"WATER_CLARITY",      "Water Clarity"},
        {"MOB_NAMETAGS",       "Mob Nametags"},
        {"INVENTORY_HUD",      "Inv HUD"},
        {"PERSPECTIVE",        "Custom perspective"},
        {"CHEST_ROUTES",       "Chestroutes"},
        {"CAGE_TRACERS",       "Cage tracers"},
        {"LOBBY_PING",         "Lobby Ping"},
        {"HIDE_DATE_GAME_ID",  "Hide date & game ID"},
        {"KILL_COUNTER",       "Kill Counter"},
        {"MOB_HEALTH",         "Mob Health"},
        {"ITEM_ESP",           "Useless Items Overlay"},
        {"TAB_KILLERS",        "Tab victims"},
        {"SELF_OPACITY",       "Self opacity"},
        {"BARRIER_VISIBILITY", "Barrier blocks"},
        {"MOB_DISGUISE_LAST",  "Mob Disguise"},
    };

    private String label;
    /** RGB (no alpha). */
    private int color;
    private boolean slotEnabled;
    private ActionType actionType;
    /** For TOGGLE_FEATURE: one of the keys in {@link #TOGGLE_FEATURE_OPTIONS}.
     *  For CHAT_MESSAGE: message text.
     *  For COMMAND: command string without leading slash. */
    private String actionValue;

    public RadialMenuItem() {
        this("", 0x5588FF, true, ActionType.NONE, "");
    }

    public RadialMenuItem(String label, int color, boolean slotEnabled, ActionType actionType, String actionValue) {
        this.label = label;
        this.color = color;
        this.slotEnabled = slotEnabled;
        this.actionType = actionType;
        this.actionValue = actionValue;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public boolean isSlotEnabled() { return slotEnabled; }
    public void setSlotEnabled(boolean slotEnabled) { this.slotEnabled = slotEnabled; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public String getActionValue() { return actionValue; }
    public void setActionValue(String actionValue) { this.actionValue = actionValue; }

    /** Returns the display name for the current TOGGLE_FEATURE action value, or the raw value otherwise. */
    public String getActionDisplay() {
        if (actionType == ActionType.TOGGLE_FEATURE) {
            for (String[] pair : TOGGLE_FEATURE_OPTIONS) {
                if (pair[0].equals(actionValue)) return pair[1];
            }
        }
        return actionValue;
    }

    /** Returns a short human-readable summary of this slot's action, used in the settings list. */
    public String getActionSummary() {
        switch (actionType) {
            case NONE:           return "None";
            case TOGGLE_FEATURE: return "Toggle: " + getActionDisplay();
            case CHAT_MESSAGE:   return "Say: " + (actionValue.length() > 24 ? actionValue.substring(0, 24) + "..." : actionValue);
            case COMMAND:        return "/" + (actionValue.length() > 24 ? actionValue.substring(0, 24) + "..." : actionValue);
            default:             return "";
        }
    }

    /** Deep copy. */
    public RadialMenuItem copy() {
        return new RadialMenuItem(label, color, slotEnabled, actionType, actionValue);
    }
}
