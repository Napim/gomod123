package me.napim.gomod.features;

import java.util.ArrayDeque;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.renderer.GlStateManager;

/** Lighting for the native spray mesh only; tint, alpha, texture and surface placement stay native. */
public final class LunarSprayLighting {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final ThreadLocal<ArrayDeque<Boolean>> LIGHTING =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LunarSprayLighting() { }

    /** Disable fixed-function material lighting while Lunar submits its flat spray quad. */
    public static void begin() {
        boolean enabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        LIGHTING.get().push(Boolean.valueOf(enabled));
        LunarConnectionDiagnostics.recordOnce("lunar.spray.fixed_lighting", "native-mesh",
                "wasEnabled=" + enabled + " disabledForDraw=true");
        // Keep Minecraft's state cache and raw OpenGL state aligned.
        GlStateManager.disableLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    /** Restore the caller's fixed-function lighting state, including when draws are nested. */
    public static void end() {
        ArrayDeque<Boolean> stack = LIGHTING.get();
        if (stack.isEmpty()) return;
        boolean enabled = stack.pop().booleanValue();
        if (stack.isEmpty()) LIGHTING.remove();
        if (enabled) {
            GlStateManager.enableLighting();
            GL11.glEnable(GL11.GL_LIGHTING);
        } else {
            GlStateManager.disableLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
        }
    }

    public static int fullBright(int nativePackedLight) {
        LunarConnectionDiagnostics.recordOnce("lunar.spray.fullbright_draw", "native-mesh",
                "nativePackedLight=" + nativePackedLight + " appliedPackedLight=" + FULL_BRIGHT);
        return FULL_BRIGHT;
    }
}
