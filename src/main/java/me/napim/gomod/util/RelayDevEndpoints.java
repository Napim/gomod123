package me.napim.gomod.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Local ShareInv / global-chat relay URLs for self-hosted dev (not shipped as string literals).
 */
public final class RelayDevEndpoints {
    public static final int DEFAULT_RELAY_PORT = 8765;
    public static final int LEGACY_GLOBAL_CHAT_PORT = 8080;

    private RelayDevEndpoints() {}

    public static String loopbackHost() {
        try {
            return InetAddress.getLoopbackAddress().getHostAddress();
        } catch (Exception ignored) {
            return String.valueOf(127) + '.' + 0 + '.' + 0 + '.' + 1;
        }
    }

    public static String httpRelay(int port) {
        return "http://" + loopbackHost() + ':' + port + "/relay";
    }

    public static String wsRelay(int port) {
        return "ws://" + loopbackHost() + ':' + port;
    }

    public static String defaultHttpRelay() {
        return httpRelay(DEFAULT_RELAY_PORT);
    }

    public static String defaultWsRelay() {
        return wsRelay(DEFAULT_RELAY_PORT);
    }

    /** True for saved configs that still point at a loopback global-chat relay on port 8080. */
    public static boolean isLegacyGlobalChatOverride(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return false;
        }
        try {
            URI u = new URI(endpoint.trim());
            if (u.getPort() != LEGACY_GLOBAL_CHAT_PORT) {
                return false;
            }
            String path = u.getPath();
            if (path == null || !path.endsWith("/relay")) {
                return false;
            }
            return isLoopbackHost(u.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
