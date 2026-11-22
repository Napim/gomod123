package me.napim.gomod.features;

import java.util.ArrayDeque;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.renderer.GlStateManager;

/** Billboard particle colors must not inherit the world's fixed-function material lighting. */
public final class LunarEmoteParticleLighting {
    private static final ThreadLocal<ArrayDeque<Boolean>> LIGHTING = ThreadLocal.withInitial(ArrayDeque::new);
    private LunarEmoteParticleLighting() { }
    public static void begin() {
        boolean enabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        LIGHTING.get().push(enabled);
        // Native raw GL calls can disagree with Minecraft's BooleanState cache.
        GlStateManager.disableLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
    }
    public static void end() {
        ArrayDeque<Boolean> stack = LIGHTING.get();
        if (stack.isEmpty()) return;
        boolean enabled = stack.pop();
        if (stack.isEmpty()) LIGHTING.remove();
        if (enabled) { GlStateManager.enableLighting(); GL11.glEnable(GL11.GL_LIGHTING); }
        else { GlStateManager.disableLighting(); GL11.glDisable(GL11.GL_LIGHTING); }
    }
}
