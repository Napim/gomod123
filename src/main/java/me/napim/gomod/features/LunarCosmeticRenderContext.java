package me.napim.gomod.features;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/** Identifies the player whose Lunar layers are currently submitting geometry. */
public final class LunarCosmeticRenderContext {
    private static final ThreadLocal<State> STATE = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private LunarCosmeticRenderContext() { }

    public static void begin(EntityLivingBase entity) {
        if (entity == null) return;
        State state = STATE.get();
        int depth = state.frames.size();
        Frame frame;
        if (depth < state.pool.length) {
            frame = state.pool[depth];
            if (frame == null) state.pool[depth] = frame = new Frame();
        } else {
            // Preserve unusually deep nested rendering without retaining its objects.
            frame = new Frame();
        }
        frame.entity = entity;
        frame.player = entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
        state.frames.push(frame);
    }

    public static void end(EntityLivingBase entity) {
        if (entity == null) return;
        ArrayDeque<Frame> frames = STATE.get().frames;
        while (!frames.isEmpty()) {
            Frame frame = frames.pop();
            boolean matched = frame.entity == entity;
            frame.clear();
            if (matched) break;
        }
    }

    public static EntityPlayer currentPlayer() {
        ArrayDeque<Frame> frames = STATE.get().frames;
        return frames.isEmpty() ? null : frames.peek().player;
    }

    public static EntityLivingBase currentEntity() {
        ArrayDeque<Frame> frames = STATE.get().frames;
        return frames.isEmpty() ? null : frames.peek().entity;
    }

    public static void beginName(EntityLivingBase entity) {
        ArrayDeque<Frame> frames=STATE.get().frames;
        if(!frames.isEmpty()&&frames.peek().entity==entity)frames.peek().nameDepth++;
    }

    public static void endName(EntityLivingBase entity) {
        ArrayDeque<Frame> frames=STATE.get().frames;
        if(!frames.isEmpty()&&frames.peek().entity==entity&&frames.peek().nameDepth>0)frames.peek().nameDepth--;
    }

    /** A canceled name callback is bounded by its enclosing entity frame too. */
    public static boolean isRenderingName() {
        ArrayDeque<Frame> frames=STATE.get().frames;
        return !frames.isEmpty()&&frames.peek().nameDepth>0;
    }

    public static boolean hasCachedTextureMatch(int texture) {
        ArrayDeque<Frame> frames = STATE.get().frames;
        return !frames.isEmpty() && frames.peek().player != null
                && frames.peek().textureIndex(texture) >= 0;
    }

    public static List<?> cachedWornCosmetics() {
        Frame frame = STATE.get().frames.peek();
        return frame == null ? null : frame.wornCosmetics;
    }

    public static void cacheWornCosmetics(List<?> cosmetics) {
        Frame frame = STATE.get().frames.peek();
        if (frame != null && frame.player != null) frame.wornCosmetics = cosmetics;
    }

    public static Object cachedTextureMatch(int texture) {
        ArrayDeque<Frame> frames = STATE.get().frames;
        if (frames.isEmpty() || frames.peek().player == null) return null;
        Frame frame = frames.peek();
        int index = frame.textureIndex(texture);
        return index < 0 ? null : frame.textureMatches[index];
    }

    public static void cacheTextureMatch(int texture, Object cosmeticOrSentinel) {
        ArrayDeque<Frame> frames = STATE.get().frames;
        if (!frames.isEmpty() && frames.peek().player != null && cosmeticOrSentinel != null) {
            Frame frame = frames.peek();
            int index = frame.textureIndex(texture);
            if (index >= 0) {
                frame.textureMatches[index] = cosmeticOrSentinel;
                return;
            }
            // An uncached texture is re-evaluated normally. Bound retained scratch space
            // even if a mod submits an unusual number of textures within one entity.
            if (frame.textureCount == 256) return;
            if (frame.textures == null) {
                frame.textures = new int[8];
                frame.textureMatches = new Object[8];
            } else if (frame.textureCount == frame.textures.length) {
                frame.textures = Arrays.copyOf(frame.textures, frame.textures.length * 2);
                frame.textureMatches = Arrays.copyOf(frame.textureMatches, frame.textureMatches.length * 2);
            }
            frame.textures[frame.textureCount] = texture;
            frame.textureMatches[frame.textureCount++] = cosmeticOrSentinel;
        }
    }

    /** Only empty bookkeeping survives an entity draw; no player/world references are retained. */
    private static final class State {
        final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();
        final Frame[] pool = new Frame[16];
    }

    private static final class Frame {
        EntityLivingBase entity;
        EntityPlayer player;
        int nameDepth;
        int[] textures;
        Object[] textureMatches;
        int textureCount;
        List<?> wornCosmetics;

        int textureIndex(int texture) {
            for (int i = 0; i < textureCount; i++) if (textures[i] == texture) return i;
            return -1;
        }

        void clear() {
            entity = null;
            player = null;
            nameDepth = 0;
            wornCosmetics = null;
            if (textureMatches != null) Arrays.fill(textureMatches, 0, textureCount, null);
            textureCount = 0;
        }
    }
}
