package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.util.GameContext;

import java.util.List;
import java.util.Locale;

/**
 * Relay presence location for gomod Social (where a friend is playing).
 */
public final class GomodSocialPresence {
    /** Relay {@code server} value while not in a world (title / multiplayer menus). */
    public static final String SERVER_MAIN_MENU = "mainmenu";
    public static final String SERVER_SINGLEPLAYER = "singleplayer";
    /** Match relay usage online window ({@link shareinv-relay} USAGE_ONLINE_MS). */
    public static final long PRESENCE_TTL_MS = 90_000L;

    // TAB asks for a player's profile/name many times per frame. Re-resolving the Hypixel
    // map for each lookup walks and normalizes the entire sidebar, so memoize that result
    // briefly while still noticing world, player, and server changes immediately.
    private static final long LOCAL_SERVER_CACHE_NS = 100_000_000L;
    private static volatile LocalServerResolution cachedLocalServerResolution;

    private GomodSocialPresence() {}

    /** True when the client is not in a world (main menu, server list, etc.). */
    public static boolean isNotInWorld(Minecraft mc) {
        return mc != null && mc.theWorld == null && mc.thePlayer == null;
    }

    /** Relay poll is useful in-world or on menus (for presence + update checks). */
    public static boolean shouldRelayPoll(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        return mc.thePlayer != null || isNotInWorld(mc);
    }

    /**
     * Value sent on relay poll {@code server} field, or {@code null} when unknown (loading world).
     */
    public static String resolveLocalServer(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        long now = System.nanoTime();
        LocalServerResolution cached = cachedLocalServerResolution;
        if (cached != null && cached.matches(mc, now)) {
            return cached.server;
        }
        synchronized (GomodSocialPresence.class) {
            now = System.nanoTime();
            cached = cachedLocalServerResolution;
            if (cached != null && cached.matches(mc, now)) {
                return cached.server;
            }

            Object world = mc.theWorld;
            Object player = mc.thePlayer;
            ServerData serverData = mc.getCurrentServerData();
            String serverIp = serverData == null ? null : normalizeServerIp(serverData.serverIP);
            String resolved = resolveLocalServerUncached(mc);
            cachedLocalServerResolution = new LocalServerResolution(
                    mc, world, player, serverData, serverIp, System.nanoTime(), resolved);
            return resolved;
        }
    }

    private static String resolveLocalServerUncached(Minecraft mc) {
        if (mc.thePlayer != null && mc.theWorld != null) {
            if (mc.getCurrentServerData() != null && mc.getCurrentServerData().serverIP != null) {
                String ip = mc.getCurrentServerData().serverIP.trim();
                if (!ip.isEmpty()) {
                    if (GameContext.isOnHypixel(mc)) {
                        return resolveHypixelRelayPlace(mc, ip);
                    }
                    return ip;
                }
            }
            return SERVER_SINGLEPLAYER;
        }
        if (isNotInWorld(mc)) {
            return SERVER_MAIN_MENU;
        }
        return null;
    }

    private static String normalizeServerIp(String ip) {
        return ip == null ? null : ip.trim().toLowerCase(Locale.ROOT);
    }

    private static final class LocalServerResolution {
        private final Minecraft minecraft;
        private final Object world;
        private final Object player;
        private final ServerData serverData;
        private final String serverIp;
        private final long resolvedAtNanos;
        private final String server;

        private LocalServerResolution(Minecraft minecraft, Object world, Object player, ServerData serverData,
                                      String serverIp, long resolvedAtNanos, String server) {
            this.minecraft = minecraft;
            this.world = world;
            this.player = player;
            this.serverData = serverData;
            this.serverIp = serverIp;
            this.resolvedAtNanos = resolvedAtNanos;
            this.server = server;
        }

        private boolean matches(Minecraft mc, long nowNanos) {
            if (nowNanos < resolvedAtNanos || nowNanos - resolvedAtNanos >= LOCAL_SERVER_CACHE_NS
                    || minecraft != mc || world != mc.theWorld || player != mc.thePlayer) {
                return false;
            }
            ServerData currentServerData = mc.getCurrentServerData();
            String currentIp = currentServerData == null ? null : normalizeServerIp(currentServerData.serverIP);
            return serverData == currentServerData
                    && (serverIp == null ? currentIp == null : serverIp.equals(currentIp));
        }
    }

    private static String resolveHypixelRelayPlace(Minecraft mc, String ip) {
        String map = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        if (map != null && !map.trim().isEmpty() && !"unknown".equalsIgnoreCase(map.trim())) {
            return "hypixel:" + map.trim().toLowerCase(Locale.ROOT);
        }
        List<String> lines = HypixelScoreboardMapResolver.collectSidebarTextLines(mc);
        for (String line : lines) {
            String lower = line == null ? "" : EnumChatFormatting.getTextWithoutFormattingCodes(line).toLowerCase(Locale.ROOT);
            if (lower.contains("limbo")) {
                return "hypixel:limbo";
            }
            if (lower.contains("lobby")) {
                return "hypixel:lobby";
            }
        }
        String cleanIp = ip == null ? "" : ip.trim().toLowerCase(Locale.ROOT);
        return cleanIp.isEmpty() ? "hypixel:unknown" : "hypixel:" + cleanIp.replaceAll("[^a-z0-9_.-]", "_");
    }

    public static String sessionPlayerName(Minecraft mc) {
        if (mc == null) {
            return "";
        }
        if (mc.thePlayer != null && mc.thePlayer.getName() != null) {
            return mc.thePlayer.getName().trim();
        }
        try {
            if (mc.getSession() != null && mc.getSession().getUsername() != null) {
                return mc.getSession().getUsername().trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static boolean isPresenceFresh(long ts) {
        return ts > 0L && System.currentTimeMillis() - ts <= PRESENCE_TTL_MS;
    }

    public static String formatServerLabel(String serverRaw) {
        return formatServerLabel(serverRaw, true);
    }

    public static String formatServerLabel(String serverRaw, boolean truncate) {
        if (serverRaw == null || serverRaw.trim().isEmpty()) {
            return "";
        }
        String s = serverRaw.trim();
        if (SERVER_MAIN_MENU.equalsIgnoreCase(s)) {
            return "Main menu";
        }
        if (SERVER_SINGLEPLAYER.equalsIgnoreCase(s)) {
            return "Singleplayer";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("hypixel")) {
            return "Hypixel";
        }
        if (truncate && s.length() > 28) {
            return s.substring(0, 25) + "...";
        }
        return s;
    }

    /** Subtitle fragment: {@code · Main menu}, {@code · Hypixel}, etc. */
    public static String locationSuffix(String serverRaw) {
        String label = formatServerLabel(serverRaw);
        if (label.isEmpty()) {
            return "";
        }
        return EnumChatFormatting.DARK_GRAY + " \u00b7 " + EnumChatFormatting.GRAY + label;
    }
}
