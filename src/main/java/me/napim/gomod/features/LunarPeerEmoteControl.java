package me.napim.gomod.features;

import java.util.UUID;

/** Calls the live Lunar dispatcher without loading a Mixin definition as a Java class. */
public final class LunarPeerEmoteControl {
    private static volatile Object dispatcher;
    private static volatile String stopMethod;

    private LunarPeerEmoteControl() { }

    public static void captureDispatcher(Object liveDispatcher, String nativeStopMethod) {
        if (liveDispatcher == null || nativeStopMethod == null) return;
        stopMethod = nativeStopMethod;
        dispatcher = liveDispatcher;
    }

    public static void stopFromPush(Object push) {
        if (push == null) return;
        try {
            Object uuid = push.getClass().getMethod("getPlayerUuid").invoke(push);
            long high = ((Number) uuid.getClass().getMethod("getHigh64").invoke(uuid)).longValue();
            long low = ((Number) uuid.getClass().getMethod("getLow64").invoke(uuid)).longValue();
            stop(new UUID(high, low));
        } catch (ReflectiveOperationException ignored) { }
    }

    public static void stop(UUID playerId) {
        Object liveDispatcher = dispatcher;
        String methodName = stopMethod;
        if (liveDispatcher == null || playerId == null || methodName == null) return;
        try {
            ClassLoader loader = liveDispatcher.getClass().getClassLoader();
            Class<?> uuidType = Class.forName("com.lunarclient.common.v1.Uuid", false, loader);
            Object uuidBuilder = uuidType.getMethod("newBuilder").invoke(null);
            uuidBuilder.getClass().getMethod("setHigh64", long.class).invoke(uuidBuilder, playerId.getMostSignificantBits());
            uuidBuilder.getClass().getMethod("setLow64", long.class).invoke(uuidBuilder, playerId.getLeastSignificantBits());
            Object uuid = uuidBuilder.getClass().getMethod("build").invoke(uuidBuilder);
            Class<?> stopType = Class.forName("com.lunarclient.websocket.emote.v1.StopEmotePush", false, loader);
            Object builder = stopType.getMethod("newBuilder").invoke(null);
            builder.getClass().getMethod("setPlayerUuid", uuidType).invoke(builder, uuid);
            Object push = builder.getClass().getMethod("build").invoke(builder);
            liveDispatcher.getClass().getMethod(methodName, stopType).invoke(liveDispatcher, push);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnce("fullaccess.peer_emote_stop", error.getClass().getName(),
                    "nativeStopFailed=" + error.getClass().getSimpleName());
        }
    }
}
