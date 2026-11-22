package me.napim.gomod.features;

/**
 * Shipped default for TAB duplicate crowd denick (see {@link DenickCrowdSync}).
 * <p>
 * When the player clears the custom origin in config, {@link ConfigManager#getDenickCrowdSyncEffectiveBaseUrl()}
 * falls back to this HTTPS origin so reports and pulls share one public map. Maintainers deploy
 * {@code GET /gomod-denick-crowd.json} and {@code POST /gomod-denick-report} on this host (or set the constant empty
 * and rely only on {@link ConfigManager#getDenickCrowdSyncBaseUrl()} for self-hosted builds).
 */
public final class DenickCrowdSyncConstants {
    private DenickCrowdSyncConstants() {}

    /** Same VPS family as {@link me.napim.gomod.shareinv.ShareInvConstants#GOMOD_HOSTED_RELAY_WSS}; HTTPS crowd API lives here. */
    public static final String GOMOD_HOSTED_DENICK_CROWD_BASE_HTTPS = "https://gomod-service.onrender.com";

    /** Default crowd origin when config override is empty. Clear for forks that ship no public service. */
    public static final String PUBLIC_DENICK_CROWD_BASE_URL = GOMOD_HOSTED_DENICK_CROWD_BASE_HTTPS;

    public static String publicCrowdOriginTrimmed() {
        if (PUBLIC_DENICK_CROWD_BASE_URL == null) {
            return "";
        }
        return PUBLIC_DENICK_CROWD_BASE_URL.trim();
    }
}
