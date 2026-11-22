package me.napim.gomod.features;

/** Neutral Forge bridge for the public Lunar account-ownership guard. */
public final class LunarPublicLoginGuard {
    /** Returned by the Weave guard when an official login confirms there is no selected Plus color. */
    public static final int OFFICIAL_PLUS_UNSELECTED = -2;

    private LunarPublicLoginGuard() {}

    public static boolean isOfficialOwnershipOnly() {
        return me.napim.gomod.GomodReleaseBuild.PUBLIC_RELEASE;
    }

    public static int officialPlusRgb() {
        return -1;
    }

    public static int officialLogoRgbForRestore() {
        return -1;
    }

    public static int officialPlusRgbForRestore() {
        return -1;
    }

    public static boolean isOfficialCosmeticLoginResponse(Object response) {
        return false;
    }

    public static String officialCosmeticLoginAccountKey(Object response) {
        return "";
    }

    public static String officialEmoteLoginAccountKey(Object response) {
        return "";
    }

    public static Object currentOfficialEmoteLoginResponse() {
        return null;
    }

    public static String currentAccountKey() {
        return "";
    }

    public static boolean shouldHideLocalPlus(java.util.UUID playerId) {
        return false;
    }

    public static boolean isOfficialOwnershipReady() {
        return true;
    }
}
