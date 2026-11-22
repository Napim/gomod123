package me.napim.gomod.shareinv;

/**
 * ShareInv defaults that ship inside the client JAR.
 * <p>
 * <b>Why not run the relay inside this JAR in Minecraft?</b> The mod is a client: turning your game into a
 * public WebSocket host hits NAT, firewalls, and changing IPs, so two random players cannot reliably connect
 * to each other. A small relay on the internet (same protocol as {@code shareinv-relay-java}) fixes that.
 * <p>
 * <b>Mainstream flow:</b> Set {@link #PUBLIC_RELAY_WSS} to your deployed relay (e.g. {@code wss://shareinv.yourdomain.com})
 * before you build a release. Players then only use {@code /shareinv connect} and invite commands — no extra downloads.
 * Self-hosters can still run the relay JAR on a VPS and either ship that URL here or let users {@code /shareinv url ws://...}.
 */
public final class ShareInvConstants {
    private ShareInvConstants() {}

    /**
     * When non-empty, clients use this relay unless the player set a custom URL with {@code /shareinv url}.
     * Must be {@code ws://} or {@code wss://}. Put TLS termination (nginx, Caddy, etc.) in front of the Java relay for {@code wss://}.
     */
    /** Default hosted relay (also used by the Friend Inventory HUD connect button). */
    public static final String GOMOD_HOSTED_RELAY_WSS = "wss://gomod-service.onrender.com";

    /** Shipped default; clear with {@code /shareinv url clear} after setting a custom URL if needed. */
    public static final String PUBLIC_RELAY_WSS = GOMOD_HOSTED_RELAY_WSS;

    public static String publicRelayTrimmed() {
        if (PUBLIC_RELAY_WSS == null) {
            return "";
        }
        return PUBLIC_RELAY_WSS.trim();
    }
}
