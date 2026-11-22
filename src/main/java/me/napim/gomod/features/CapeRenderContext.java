package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Tracks the entity whose living layers are currently being rendered. */
public final class CapeRenderContext {

    private static final ThreadLocal<Integer> LOCAL_PLAYER_RENDER_DEPTH =
            new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return Integer.valueOf(0);
                }
            };
    private static final ThreadLocal<Deque<EntityLivingBase>> RENDER_STACK =
            new ThreadLocal<Deque<EntityLivingBase>>() {
                @Override
                protected Deque<EntityLivingBase> initialValue() {
                    return new ArrayDeque<EntityLivingBase>();
                }
            };

    private CapeRenderContext() {
    }

    public static void begin(EntityLivingBase entity) {
        if (entity != null) {
            RENDER_STACK.get().push(entity);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null && entity == mc.thePlayer) {
            LOCAL_PLAYER_RENDER_DEPTH.set(Integer.valueOf(LOCAL_PLAYER_RENDER_DEPTH.get().intValue() + 1));
        }
    }

    public static void end(EntityLivingBase entity) {
        Deque<EntityLivingBase> stack = RENDER_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity != mc.thePlayer) {
            return;
        }
        int depth = LOCAL_PLAYER_RENDER_DEPTH.get().intValue();
        LOCAL_PLAYER_RENDER_DEPTH.set(Integer.valueOf(Math.max(0, depth - 1)));
    }

    public static boolean isRenderingLocalPlayer() {
        return LOCAL_PLAYER_RENDER_DEPTH.get().intValue() > 0;
    }

    /** UUID of the vanilla player whose render layers currently own the Lunar cloak invocation. */
    public static UUID getRenderingPlayerUuid() {
        Deque<EntityLivingBase> stack = RENDER_STACK.get();
        EntityLivingBase current = stack.isEmpty() ? null : stack.peek();
        return current instanceof EntityPlayer ? ((EntityPlayer) current).getUniqueID() : null;
    }

    /** Exact player whose native Lunar layers are currently being rendered, including render-only proxies. */
    public static EntityPlayer getRenderingPlayer() {
        Deque<EntityLivingBase> stack = RENDER_STACK.get();
        EntityLivingBase current = stack.isEmpty() ? null : stack.peek();
        return current instanceof EntityPlayer ? (EntityPlayer) current : null;
    }

    /** Name of the vanilla player whose render layers currently own the Lunar cloak invocation. */
    public static String getRenderingPlayerName() {
        Deque<EntityLivingBase> stack = RENDER_STACK.get();
        EntityLivingBase current = stack.isEmpty() ? null : stack.peek();
        return current instanceof EntityPlayer ? ((EntityPlayer) current).getName() : null;
    }
}
