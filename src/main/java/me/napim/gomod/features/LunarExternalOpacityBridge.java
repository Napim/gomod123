package me.napim.gomod.features;

import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tiny native-Weave ASM bridge for Lunar cosmetic renderers.
 *
 * <p>The normal mixin path is still preferred when Genesis applies it, but native Lunar/Weave launches can skip or
 * sandbox those mixins. Native cloak and deferred cosmetic mesh draws call this class directly so Lunar cosmetics
 * still follow gomod opacity settings at their actual draw point.</p>
 */
public final class LunarExternalOpacityBridge {
    private static final int NONE = 0;
    private static final int VISIBLE_SPECS = 1;
    private static final int SELF_OPACITY = 2;

    private static final ThreadLocal<Deque<Integer>> ACTIVE = new ThreadLocal<Deque<Integer>>() {
        @Override
        protected Deque<Integer> initialValue() {
            return new ArrayDeque<Integer>();
        }
    };

    private static boolean clothHookLogged = false;
    private static volatile long beginCalls = 0L;
    private static volatile long endCalls = 0L;
    private static volatile long suppressCalls = 0L;
    private static volatile long visibleSpecsBeginCalls = 0L;
    private static volatile long selfOpacityBeginCalls = 0L;
    private static volatile long noneBeginCalls = 0L;
    private static volatile long cosmeticBeginCalls = 0L;
    private static volatile long cosmeticEndCalls = 0L;
    private static volatile long cosmeticSelfOpacityBeginCalls = 0L;
    private static volatile long cosmeticVisibleSpecsBeginCalls = 0L;
    private static volatile long lastBeginMs = 0L;
    private static volatile int lastMode = NONE;
    private static volatile String lastPlayerBridgeClass = "<none>";

    private LunarExternalOpacityBridge() {
    }

    /**
     * @return true when the caller should skip rendering the Lunar cloak entirely.
     */
    public static boolean beginLunarCloak(Object playerBridge) {
        beginCalls++;
        lastBeginMs = System.currentTimeMillis();
        lastPlayerBridgeClass = playerBridge == null ? "<null>" : playerBridge.getClass().getName();
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            suppressCalls++;
            lastMode = NONE;
            return true;
        }
        int mode = beginPlayerOpacity(playerBridge);
        if (mode == NONE) noneBeginCalls++;
        lastMode = mode;
        ACTIVE.get().push(Integer.valueOf(mode));
        if (mode != NONE && !clothHookLogged) {
            clothHookLogged = true;
            System.out.println("[gomod] native Weave Lunar cloth cloak opacity hook active (mode=" + mode + ")");
        }
        return false;
    }

    public static void endLunarCloak() {
        endCalls++;
        endCurrentScope();
    }

    /** Apply the local player's opacity to the actual deferred Lunar cosmetic mesh draw. */
    public static int beginLunarCosmetic(Object playerBridge) {
        cosmeticBeginCalls++;
        int mode = beginPlayerOpacity(playerBridge);
        if (mode == VISIBLE_SPECS) cosmeticVisibleSpecsBeginCalls++;
        else if (mode == SELF_OPACITY) cosmeticSelfOpacityBeginCalls++;
        lastMode = mode;
        ACTIVE.get().push(Integer.valueOf(mode));
        return mode;
    }

    public static void endLunarCosmetic() {
        cosmeticEndCalls++;
        endCurrentScope();
    }

    private static int beginPlayerOpacity(Object playerBridge) {
        try {
            if (VisibleSpecs.beginExternalCosmeticRender(playerBridge)) {
                visibleSpecsBeginCalls++;
                return VISIBLE_SPECS;
            }
            if (SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge)) {
                selfOpacityBeginCalls++;
                return SELF_OPACITY;
            }
        } catch (Throwable ignored) {
        }
        return NONE;
    }

    private static void endCurrentScope() {
        int mode = NONE;
        try {
            Deque<Integer> stack = ACTIVE.get();
            if (!stack.isEmpty()) {
                mode = stack.pop().intValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mode == VISIBLE_SPECS) {
                VisibleSpecs.endExternalLocalPlayerCosmeticRender();
            } else if (mode == SELF_OPACITY) {
                SelfOpacity.endExternalLocalPlayerCosmeticRender();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Called inside Lunar's cloth vertex writer, after it replaces the OpenGL colour with opaque packed ARGB. */
    public static int applyActiveOpacityToPackedColor(int color) {
        int mode = NONE;
        try {
            Deque<Integer> stack = ACTIVE.get();
            if (!stack.isEmpty()) {
                mode = stack.peek().intValue();
            }
        } catch (Throwable ignored) {
        }
        float opacity;
        if (mode == VISIBLE_SPECS) {
            opacity = VisibleSpecs.getRenderOpacity();
        } else if (mode == SELF_OPACITY) {
            if (SelfOpacity.isExternalCosmeticDepthOnly()) return color;
            opacity = SelfOpacity.getAlpha();
        } else {
            return color;
        }
        opacity = Math.max(0.0F, Math.min(1.0F, opacity));
        int originalAlpha = (color >>> 24) & 0xFF;
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(originalAlpha * opacity)));
        return (color & 0x00FFFFFF) | (scaledAlpha << 24);
    }

    /** True while Lunar is drawing a local cloak that must honor a gomod opacity setting. */
    public static boolean isOpacityActive() {
        try {
            Deque<Integer> stack = ACTIVE.get();
            return !stack.isEmpty() && stack.peek().intValue() != NONE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Native Weave fallback for inventory previews whose player renderer resets GL colour to opaque. */
    public static void forceGuiInventoryColor(float red, float green, float blue) {
        try {
            // This callback also runs for ordinary world/HUD color changes. Do not
            // query the driver for glint state unless an inventory preview owns opacity.
            boolean visibleSpecs = VisibleSpecs.isGuiInventoryOpacityRenderActive();
            boolean selfOpacity = SelfOpacity.isGuiInventoryOpacityRenderActive();
            if (!visibleSpecs && !selfOpacity) return;
            SelfOpacity.noteGuiInventoryBridgeColorCall();
            if (EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
                return;
            }
            if (visibleSpecs) {
                GL11.glColor4f(red, green, blue, VisibleSpecs.getRenderOpacity());
            } else if (selfOpacity) {
                SelfOpacity.forceGuiInventoryRawColor(red, green, blue);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String debugSnapshot() {
        long age = lastBeginMs <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - lastBeginMs);
        return "bridgeBegin=" + beginCalls
                + " bridgeEnd=" + endCalls
                + " suppress=" + suppressCalls
                + " visibleSpecsBegin=" + visibleSpecsBeginCalls
                + " selfOpacityBegin=" + selfOpacityBeginCalls
                + " noOpacityBegin=" + noneBeginCalls
                + " cosmeticBegin=" + cosmeticBeginCalls
                + " cosmeticEnd=" + cosmeticEndCalls
                + " cosmeticVisibleSpecsBegin=" + cosmeticVisibleSpecsBeginCalls
                + " cosmeticSelfOpacityBegin=" + cosmeticSelfOpacityBeginCalls
                + " lastMode=" + modeName(lastMode)
                + " lastAgeMs=" + age
                + " lastBridgeClass=" + lastPlayerBridgeClass;
    }

    public static boolean hasObservedLunarCloak() {
        return beginCalls > 0L;
    }

    public static long lastLunarCloakAgeMs() {
        return lastBeginMs <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - lastBeginMs);
    }

    public static long suppressedLunarCloakCount() {
        return suppressCalls;
    }

    private static String modeName(int mode) {
        if (mode == VISIBLE_SPECS) {
            return "visibleSpecs";
        }
        if (mode == SELF_OPACITY) {
            return "selfOpacity";
        }
        return "none";
    }
}
