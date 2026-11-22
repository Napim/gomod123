package me.napim.gomod.features;

import java.lang.reflect.Method;

/** Keeps the mapped Minecraft cape mixin independent of the Weave-only appearance renderer. */
public final class LunarCosmeticAppearanceBridge {
    private static volatile Method beginNoClothCapeLayer;
    private static volatile Method endNoClothCapeLayer;
    private static volatile boolean resolved;

    private LunarCosmeticAppearanceBridge() { }

    public static boolean beginNoClothCapeLayer(Object player) {
        if(player==null)return false;
        resolve();
        Method begin=beginNoClothCapeLayer;
        if(begin==null)return false;
        try { return Boolean.TRUE.equals(begin.invoke(null,player)); }
        catch(Throwable unavailable) { return false; }
    }

    public static void endNoClothCapeLayer() {
        resolve();
        Method end=endNoClothCapeLayer;
        if(end==null)return;
        try { end.invoke(null); } catch(Throwable ignored) { }
    }

    private static void resolve() {
        if(resolved)return;
        synchronized(LunarCosmeticAppearanceBridge.class) {
            if(resolved)return;
            try {
                Class<?> renderer=Class.forName("me.napim.gomod.features.LunarCosmeticAppearanceRenderer",
                        false,LunarCosmeticAppearanceBridge.class.getClassLoader());
                beginNoClothCapeLayer=renderer.getMethod("beginNoClothCapeLayer",Object.class);
                endNoClothCapeLayer=renderer.getMethod("endNoClothCapeLayer");
            } catch(Throwable unavailable) {
                // A Forge/public source set can omit the internal Weave appearance renderer.
            } finally {
                resolved=true;
            }
        }
    }
}
