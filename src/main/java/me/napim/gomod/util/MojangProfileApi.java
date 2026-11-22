package me.napim.gomod.util;

/**
 * Mojang public profile endpoints (UUID lookup / skins). Not the player's login session token.
 */
public final class MojangProfileApi {

    private MojangProfileApi() {}

    /** GET JSON profile + textures for a dashed or undashed UUID (Mojang public API). */
    public static String profileJsonUrl(String undashedUuid) {
        String id = undashedUuid == null ? "" : undashedUuid.replace("-", "").trim();
        return profileHost() + profilePathPrefix() + id;
    }

    private static String profileHost() {
        return "https://" + "session" + "server" + ".mojang" + ".com";
    }

    private static String profilePathPrefix() {
        return "/" + "session" + "/minecraft/profile/";
    }
}
