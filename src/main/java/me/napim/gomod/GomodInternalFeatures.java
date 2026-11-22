package me.napim.gomod;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.RadialMenuItem;
import net.minecraft.util.EnumChatFormatting;

import java.util.Locale;

/**
 * Internal-only mod capabilities (disguise, DM mirror, spectate commands, client give, etc.).
 * Disabled when {@link GomodReleaseBuild#PUBLIC_RELEASE} is true.
 */
public final class GomodInternalFeatures {

    private static final String RESTRICTED_TOGGLE_KEY = "MOB_DISGUISE_LAST";

    private GomodInternalFeatures() {
    }

    public static boolean isEnabled() {
        return !GomodReleaseBuild.PUBLIC_RELEASE;
    }

    /** Returns whether an internal-only command may run; reports clearly on public builds. */
    public static boolean requireInternalCommand(String command) {
        if (isEnabled()) {
            return true;
        }
        String name = command == null || command.trim().isEmpty() ? "command" : command.trim();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                + "/" + name + " is only available in dev builds.");
        return false;
    }

    /** Blitz Chest Items browser (Miscellaneous) — available on public and internal builds. */
    public static boolean isBlitzChestItemsBrowserEnabled() {
        return true;
    }

    /** Blitz Challenges GUI, HUD, badges, and /blitzchallenges — internal builds only. */
    public static boolean isBlitzChallengesEnabled() {
        return isEnabled();
    }

    /** Standalone cape wear is available to public builds through the cape-only Disguise GUI. */
    public static boolean isStandaloneCapeEnabled() {
        return true;
    }

    /**
     * Clears disguise/cape state left over from an internal build config when running a public release.
     * Safe to call every startup; no-op on internal builds.
     */
    public static void sanitizePersistedStateForPublicBuild() {
        if (isEnabled()) {
            return;
        }
        if (ConfigManager.isDisguiseRestoreOnReconnect() || !"OFF".equals(ConfigManager.getDisguiseReconnectKind())) {
            ConfigManager.setDisguiseRestoreOnReconnect(false);
        }
        if (LocalDisguise.isEnabled()) {
            LocalDisguise.disable();
        }
    }

    /** Search settings entries that must not appear or navigate on public builds. */
    public static boolean isRestrictedSearchEntry(String label) {
        if (isEnabled() || label == null) {
            return false;
        }
        return label.startsWith("Blitz Challenges");
    }

    public static boolean isRestrictedToggleFeatureKey(String key) {
        if (isEnabled() || key == null) {
            return false;
        }
        return RESTRICTED_TOGGLE_KEY.equals(key);
    }

    /**
     * @param commandWithoutSlash first token is checked (e.g. {@code disguise} or {@code mobdisguise zombie})
     */
    public static boolean isRestrictedWheelCommand(String commandWithoutSlash) {
        if (isEnabled() || commandWithoutSlash == null) {
            return false;
        }
        String c = commandWithoutSlash.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty()) {
            return false;
        }
        int sp = c.indexOf(' ');
        if (sp > 0) {
            c = c.substring(0, sp);
        }
        switch (c) {
            case "disguise":
            case "disguisefull":
            case "mobdisguise":
            case "disguiser":
            case "disguisesidebardebug":
            case "disguiserenameddebug":
            case "roleplay":
            case "playersplay":
            case "playersshow":
            case "gogive":
            case "dctalk":
            case "rd":
            case "ddm":
            case "kitspreview":
            case "blitzchallenges":
            case "bchallenges":
            case "bch":
            case "bchdebug":
            case "blitzchallengesdebug":
                return true;
            default:
                return false;
        }
    }

    public static boolean isRestrictedRadialSlot(RadialMenuItem slot) {
        if (isEnabled() || slot == null || !slot.isSlotEnabled()) {
            return false;
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            return isRestrictedToggleFeatureKey(slot.getActionValue());
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND) {
            return isRestrictedWheelCommand(slot.getActionValue());
        }
        return false;
    }

    /** Count of {@link RadialMenuItem#TOGGLE_FEATURE_OPTIONS} entries allowed on public builds. */
    public static int publicToggleFeatureOptionCount() {
        if (isEnabled()) {
            return RadialMenuItem.TOGGLE_FEATURE_OPTIONS.length;
        }
        int n = 0;
        for (String[] pair : RadialMenuItem.TOGGLE_FEATURE_OPTIONS) {
            if (!isRestrictedToggleFeatureKey(pair[0])) {
                n++;
            }
        }
        return n;
    }

    /** Maps a public-build cycle index to {@link RadialMenuItem#TOGGLE_FEATURE_OPTIONS} index. */
    public static int toggleFeatureOptionIndexForPublicCycle(int publicCycleIndex) {
        int seen = 0;
        for (int i = 0; i < RadialMenuItem.TOGGLE_FEATURE_OPTIONS.length; i++) {
            if (isRestrictedToggleFeatureKey(RadialMenuItem.TOGGLE_FEATURE_OPTIONS[i][0])) {
                continue;
            }
            if (seen == publicCycleIndex) {
                return i;
            }
            seen++;
        }
        return 0;
    }

    /** Maps a {@link RadialMenuItem#TOGGLE_FEATURE_OPTIONS} index to public-build cycle index, or {@code -1}. */
    public static int publicCycleIndexForToggleFeatureOption(int optionIndex) {
        if (isEnabled()) {
            return optionIndex;
        }
        int seen = 0;
        for (int i = 0; i < RadialMenuItem.TOGGLE_FEATURE_OPTIONS.length; i++) {
            if (isRestrictedToggleFeatureKey(RadialMenuItem.TOGGLE_FEATURE_OPTIONS[i][0])) {
                continue;
            }
            if (i == optionIndex) {
                return seen;
            }
            seen++;
        }
        return -1;
    }
}
