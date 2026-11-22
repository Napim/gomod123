package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Publishes an explicit server activity through Lunar's normal friend-location service. */
public final class LunarServerPresenceOverride {
    // Lunar renames its client singleton between releases. Keep the current mapping first,
    // followed by the 2.23.1 mapping for older installs.
    private static final String[][] LUNAR_CLIENT_SINGLETONS = {
            {"com.moonsworth.lunar.client.IOOCCRIRHHROHIHRCHHRRIOICHIOIR",
                    "OORHHHIIOHCRCIRICOIORCHHHRHORI"},
            {"com.moonsworth.lunar.client.CRRHRIRIHOHRHRCCHRCOICHOOIHOIC",
                    "RCOHHCOHRHHOHORCICRICHICRHHCII"}
    };
    private static final String INBOUND_LOCATION = "com.lunarclient.common.v1.InboundLocation";
    private static final String INBOUND_SERVER = "com.lunarclient.common.v1.InboundServer";
    private static final String LOCATION = "com.lunarclient.common.v1.Location";
    private static final String RICH_STATUS = "com.lunarclient.common.v1.ServerRichStatus";
    private static final String LOCATION_TRIGGER =
            "com.lunarclient.websocket.friend.v1.BroadcastLocationChangeRequest$Trigger";

    private LunarServerPresenceOverride() {
    }

    /** Sends the official Lunar friend status: "Playing Blitz Survival Games on Hypixel". */
    public static String publishHypixelBlitz() {
        return publish("mc.hypixel.net", true);
    }

    /** Restores normal server address reporting and clears the manually supplied game label. */
    public static String restoreDetectedServer() {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc == null ? null : mc.getCurrentServerData();
        if (server == null || server.serverIP == null || server.serverIP.trim().isEmpty()) {
            return "Join a multiplayer server before using /server auto.";
        }
        return publish(server.serverIP.trim(), false);
    }

    /** Reads the native inbound rich status and Lunar's last server response without changing either. */
    public static String debugPresence() {
        try {
            ClassLoader lunarLoader = findLunarClassLoader();
            Class<?> inboundLocationType = Class.forName(INBOUND_LOCATION, true, lunarLoader);
            Class<?> triggerType = Class.forName(LOCATION_TRIGGER, true, lunarLoader);
            LocationEndpoint endpoint = findConnectedLocationEndpoint(
                    lunarLoader, inboundLocationType, triggerType);
            if (endpoint == null) return "Lunar Online is not connected; presence state is unavailable.";

            Field inboundField = findCurrentInboundLocationField(endpoint.client.getClass(), inboundLocationType);
            if (inboundField == null) return "Lunar has no active server presence to inspect.";
            Object inbound = inboundField.get(null);
            Object inboundServer = inbound.getClass().getMethod("getServer").invoke(inbound);
            Object inboundStatus = inboundServer.getClass().getMethod("getRichStatus").invoke(inboundServer);
            String inboundSummary = "inboundIp="
                    + safeText(inboundServer.getClass().getMethod("getServerIp").invoke(inboundServer))
                    + " " + formatRichStatus(inboundStatus);

            Class<?> locationType = Class.forName(LOCATION, true, lunarLoader);
            Field responseField = findCurrentPublicLocationField(endpoint.client.getClass(), locationType);
            String responseSummary = "serviceResponse=unavailable";
            if (responseField != null) {
                Object response = responseField.get(null);
                Object publicServer = response.getClass().getMethod("getPublicServer").invoke(response);
                Object responseStatus = publicServer.getClass().getMethod("getRichStatus").invoke(publicServer);
                responseSummary = "serviceServer="
                        + safeText(publicServer.getClass().getMethod("getName").invoke(publicServer))
                        + " " + formatRichStatus(responseStatus);
            }
            return "Presence debug: " + inboundSummary + " | " + responseSummary;
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return "Could not read Lunar presence (" + cause.getClass().getSimpleName() + ").";
        }
    }

    private static String publish(String serverIp, boolean blitzStatus) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.getCurrentServerData() == null) {
            return "Join a multiplayer server before using /server.";
        }

        try {
            ClassLoader lunarLoader = findLunarClassLoader();
            Class<?> locationType = Class.forName(INBOUND_LOCATION, true, lunarLoader);
            Class<?> triggerType = Class.forName(LOCATION_TRIGGER, true, lunarLoader);
            LocationEndpoint endpoint = findConnectedLocationEndpoint(
                    lunarLoader, locationType, triggerType);
            if (endpoint == null) {
                return "Lunar Online is not connected; the presence update was not sent.";
            }

            if (blitzStatus) {
                Class<?> richStatusType = Class.forName(RICH_STATUS, true, lunarLoader);
                Object statusBuilder = richStatusType.getMethod("newBuilder").invoke(null);
                statusBuilder.getClass().getMethod("setGameName", String.class)
                        .invoke(statusBuilder, "Blitz Survival Games");
                // SOURCE_LUNAR_CLIENT_PARSED in Lunar 2.23.1's ServerRichStatus schema.
                statusBuilder.getClass().getMethod("setSourceValue", Integer.TYPE)
                        .invoke(statusBuilder, 2);
                Object status = statusBuilder.getClass().getMethod("build").invoke(statusBuilder);

                Field currentLocationField = findCurrentInboundLocationField(endpoint.client.getClass(), locationType);
                if (currentLocationField == null) {
                    return "Lunar does not have an active server location to override.";
                }
                Object currentLocation = currentLocationField.get(null);
                Object currentServer = currentLocation.getClass().getMethod("getServer").invoke(currentLocation);
                Object currentStatus = currentServer.getClass().getMethod("getRichStatus").invoke(currentServer);
                Object routedLocation = withServerIp(currentLocation, currentServer, serverIp);
                currentLocationField.set(null, routedLocation);

                Method nativeRichStatus = findNativeRichStatusMethod(endpoint.client.getClass(), richStatusType);
                if (nativeRichStatus == null) {
                    return "This Lunar version does not expose its native server rich-status handler.";
                }
                // Point Lunar's cached inbound location at Hypixel first. Its native rich-status
                // handler then updates that active server and broadcasts both fields together.
                if (currentStatus.equals(status)) {
                    // Lunar's native handler is a no-op when the label matches, even if the
                    // requested server address changed. Send the routed current location once.
                    Object routedServer = routedLocation.getClass().getMethod("getServer").invoke(routedLocation);
                    Object serverBuilder = routedServer.getClass().getMethod("toBuilder").invoke(routedServer);
                    serverBuilder.getClass().getMethod("setRichStatus", richStatusType)
                            .invoke(serverBuilder, status);
                    Object serverWithStatus = serverBuilder.getClass().getMethod("build").invoke(serverBuilder);
                    routedLocation = withServer(routedLocation, serverWithStatus);
                    currentLocationField.set(null, routedLocation);
                    endpoint.broadcast.invoke(endpoint.client, routedLocation,
                            enumConstant(triggerType, "TRIGGER_SERVER_RICH_STATUS"), null);
                } else {
                    nativeRichStatus.invoke(endpoint.client, status);
                }
                return "Lunar rich status requested: Blitz Survival Games on Hypixel.";
            }

            Object serverBuilder = Class.forName(INBOUND_SERVER, true, lunarLoader)
                    .getMethod("newBuilder").invoke(null);
            serverBuilder.getClass().getMethod("setServerIp", String.class)
                    .invoke(serverBuilder, serverIp);
            Object serverValue = serverBuilder.getClass().getMethod("build").invoke(serverBuilder);

            Object locationBuilder = locationType.getMethod("newBuilder").invoke(null);
            locationBuilder.getClass().getMethod("setServer", Class.forName(INBOUND_SERVER, true, lunarLoader))
                    .invoke(locationBuilder, serverValue);
            Object location = locationBuilder.getClass().getMethod("build").invoke(locationBuilder);

            Object trigger = enumConstant(triggerType, "TRIGGER_SERVER_RICH_STATUS");
            endpoint.broadcast.invoke(endpoint.client, location, trigger, null);
            return "Lunar presence restored to your current server.";
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return "Could not publish Lunar presence (" + cause.getClass().getSimpleName() + ").";
        }
    }

    /**
     * The Lunar service getter is obfuscated in the shipped client. Find its active socket
     * through the current client singleton, then identify the typed broadcast method from
     * the stable protobuf request classes rather than relying on the decompiler's names.
     */
    private static LocationEndpoint findConnectedLocationEndpoint(
            ClassLoader lunarLoader, Class<?> locationType, Class<?> triggerType) throws Exception {
        for (String[] singleton : LUNAR_CLIENT_SINGLETONS) {
            Class<?> clientType;
            try {
                clientType = Class.forName(singleton[0], true, lunarLoader);
            } catch (ClassNotFoundException unavailableInThisLunarVersion) {
                continue;
            }

            Method getClient;
            try {
                getClient = clientType.getMethod(singleton[1]);
            } catch (NoSuchMethodException unavailableInThisLunarVersion) {
                continue;
            }
            if (!Modifier.isStatic(getClient.getModifiers())) continue;
            Object client = getClient.invoke(null);
            if (client == null) continue;

            for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    try {
                        field.setAccessible(true);
                        Object candidate = field.get(client);
                        if (candidate == null) continue;
                        Method broadcast = findBroadcastMethod(candidate.getClass(), locationType, triggerType);
                        if (broadcast == null) continue;
                        Method isOpen = candidate.getClass().getMethod("isOpen");
                        if (!Boolean.TRUE.equals(isOpen.invoke(candidate))) continue;
                        return new LocationEndpoint(candidate, broadcast);
                    } catch (ReflectiveOperationException | SecurityException ignored) {
                        // Ignore unrelated client fields and keep scanning for the friend socket.
                    }
                }
            }
        }
        return null;
    }

    private static Method findBroadcastMethod(Class<?> clientType, Class<?> locationType, Class<?> triggerType) {
        for (Method method : clientType.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3
                    && parameters[0] == locationType
                    && parameters[1] == triggerType
                    && parameters[2] == Runnable.class) {
                return method;
            }
        }
        return null;
    }

    private static Method findNativeRichStatusMethod(Class<?> clientType, Class<?> richStatusType) {
        for (Method method : clientType.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == richStatusType) {
                return method;
            }
        }
        return null;
    }

    private static Field findCurrentInboundLocationField(Class<?> clientType, Class<?> locationType)
            throws IllegalAccessException {
        for (Class<?> type = clientType; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != locationType) continue;
                try {
                    field.setAccessible(true);
                    Object location = field.get(null);
                    if (location != null && Boolean.TRUE.equals(
                            locationType.getMethod("hasServer").invoke(location))) {
                        return field;
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Keep searching other fields/classes for Lunar's active inbound location.
                }
            }
        }
        return null;
    }

    private static Field findCurrentPublicLocationField(Class<?> clientType, Class<?> locationType) {
        for (Class<?> type = clientType; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != locationType) continue;
                try {
                    field.setAccessible(true);
                    Object location = field.get(null);
                    if (location != null && Boolean.TRUE.equals(
                            locationType.getMethod("hasPublicServer").invoke(location))) {
                        return field;
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Ignore other cached location values and keep searching.
                }
            }
        }
        return null;
    }

    private static String formatRichStatus(Object status) throws Exception {
        return "source=" + readValue(status, "getSource")
                + ",game=" + readValue(status, "getGameName")
                + ",variant=" + readValue(status, "getGameVariantName")
                + ",subServer=" + readValue(status, "getSubServer")
                + ",map=" + readValue(status, "getMapName")
                + ",gameState=" + readValue(status, "getGameState")
                + ",playerState=" + readValue(status, "getPlayerState");
    }

    private static String readValue(Object target, String methodName) throws Exception {
        return safeText(target.getClass().getMethod(methodName).invoke(target));
    }

    private static String safeText(Object value) {
        if (value == null) return "";
        String text = value.toString().replace('\r', ' ').replace('\n', ' ').replace('|', '/').trim();
        return text.length() <= 100 ? text : text.substring(0, 100);
    }

    private static Object withServerIp(Object location, Object server, String serverIp) throws Exception {
        Object serverBuilder = server.getClass().getMethod("toBuilder").invoke(server);
        serverBuilder.getClass().getMethod("setServerIp", String.class).invoke(serverBuilder, serverIp);
        Object updatedServer = serverBuilder.getClass().getMethod("build").invoke(serverBuilder);
        return withServer(location, updatedServer);
    }

    private static Object withServer(Object location, Object server) throws Exception {
        Object locationBuilder = location.getClass().getMethod("toBuilder").invoke(location);
        locationBuilder.getClass().getMethod("setServer", server.getClass()).invoke(locationBuilder, server);
        return locationBuilder.getClass().getMethod("build").invoke(locationBuilder);
    }

    private static ClassLoader findLunarClassLoader() throws ClassNotFoundException {
        ClassLoader[] candidates = new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                Minecraft.class.getClassLoader(),
                LunarServerPresenceOverride.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) continue;
            try {
                Class.forName(INBOUND_LOCATION, false, candidate);
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            for (String[] singleton : LUNAR_CLIENT_SINGLETONS) {
                try {
                    Class.forName(singleton[0], false, candidate);
                    return candidate;
                } catch (ClassNotFoundException ignored) {
                    // Try another Lunar version mapping in this loader.
                }
            }
        }
        throw new ClassNotFoundException("Lunar location classes are not visible to this client");
    }

    private static Object enumConstant(Class<?> enumType, String name) {
        Object[] values = enumType.getEnumConstants();
        if (values != null) {
            for (Object value : values) {
                if (value != null && name.equals(((Enum<?>) value).name())) return value;
            }
        }
        throw new IllegalArgumentException("Missing Lunar location trigger " + name);
    }

    private static final class LocationEndpoint {
        final Object client;
        final Method broadcast;

        LocationEndpoint(Object client, Method broadcast) {
            this.client = client;
            this.broadcast = broadcast;
        }
    }
}
