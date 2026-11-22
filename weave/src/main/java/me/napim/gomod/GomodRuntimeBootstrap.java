package me.napim.gomod;

/** Native-Weave lifecycle state used by shared fallback mixins. */
public final class GomodRuntimeBootstrap {
    private static volatile boolean started;

    private GomodRuntimeBootstrap() {}

    public static boolean isStarted() {
        return started;
    }

    public static void completeWeaveBootstrap() {
        started = true;
        System.out.println("[gomod] Runtime bootstrap complete (native Weave 1.3.4).");
    }

    public static void ensureStarted() {
        if (!started && !Main.isPreInitComplete()) {
            new Main().preInit();
        }
        started = Main.isPreInitComplete();
    }

    public static void ensureStartGamePosted() {
        // Real Weave StartGameEvent is translated by WeaveEventBridge.
    }
}
