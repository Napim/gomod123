package me.napim.gomod.util;

import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Gives Forge GUI player mannequins a deterministic fixed-function state. Forge RenderWorldLast/overlay handlers
 * may leave blend, cull or depth state unlike native Weave; vanilla GuiInventory assumes those states are sane.
 */
public final class ForgeGuiPlayerRenderState {
    private static final ThreadLocal<Deque<State>> STACK = new ThreadLocal<Deque<State>>() {
        @Override
        protected Deque<State> initialValue() {
            return new ArrayDeque<State>();
        }
    };

    private ForgeGuiPlayerRenderState() {
    }

    public static void begin(Entity entity, double x, double y, double z, float partialTicks) {
        boolean active = shouldGuard(entity, x, y, z, partialTicks);
        State state = new State(active);
        STACK.get().push(state);
        if (!active) {
            return;
        }
        state.depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        state.cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        state.alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        state.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        state.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        state.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        state.alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        state.alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        state.blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        state.blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);

        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void end() {
        Deque<State> stack = STACK.get();
        if (stack.isEmpty()) {
            return;
        }
        State state = stack.pop();
        if (stack.isEmpty()) {
            STACK.remove();
        }
        if (!state.active) {
            return;
        }
        GlStateManager.depthMask(state.depthMask);
        GlStateManager.depthFunc(state.depthFunc);
        setDepth(state.depth);
        setCull(state.cull);
        setAlpha(state.alpha);
        if (state.alpha) {
            GlStateManager.alphaFunc(state.alphaFunc, state.alphaRef);
        }
        setBlend(state.blend);
        GlStateManager.blendFunc(state.blendSrc, state.blendDst);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static boolean shouldGuard(Entity entity, double x, double y, double z, float partialTicks) {
        if (GomodFixes.isWeaveRuntime() || !(entity instanceof EntityPlayer)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean vanillaInventoryMannequin = mc != null
                && mc.currentScreen instanceof GuiInventory
                && x == 0.0D && y == 0.0D && z == 0.0D && partialTicks == 1.0F;
        return vanillaInventoryMannequin
                || MountPreviewHelper.isRenderingGuiEntityPreview();
    }

    private static void setDepth(boolean enabled) {
        if (enabled) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
    }

    private static void setCull(boolean enabled) {
        if (enabled) GlStateManager.enableCull(); else GlStateManager.disableCull();
    }

    private static void setAlpha(boolean enabled) {
        if (enabled) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
    }

    private static void setBlend(boolean enabled) {
        if (enabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
    }

    private static final class State {
        final boolean active;
        boolean depth;
        boolean cull;
        boolean alpha;
        boolean blend;
        boolean depthMask;
        int depthFunc;
        int alphaFunc;
        float alphaRef;
        int blendSrc;
        int blendDst;

        State(boolean active) {
            this.active = active;
        }
    }
}
