package me.napim.gomod.features;

import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Preview-scoped vertex alpha for {@link TntPlacementPreview} — patches {@link WorldRenderer}
 * while {@link net.minecraft.client.renderer.BlockRendererDispatcher#renderBlock} builds the ghost TNT.
 */
public final class TntPlacementPreviewGhostVertex {

    private static final ThreadLocal<Integer> patchDepth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Float> patchAlpha = ThreadLocal.withInitial(() -> 1.0f);
    private static final ThreadLocal<Integer> blockMeshStart = ThreadLocal.withInitial(() -> -1);
    private static Field byteBufferField;

    static {
        for (String name : new String[]{"byteBuffer", "field_178999_b", "field_179010_h", "field_78974_h"}) {
            try {
                Field f = WorldRenderer.class.getDeclaredField(name);
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    byteBufferField = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private TntPlacementPreviewGhostVertex() {}

    public static void begin(float alpha) {
        patchAlpha.set(Math.max(0.05f, Math.min(1.0f, alpha)));
        patchDepth.set(patchDepth.get() + 1);
    }

    public static void end() {
        patchDepth.set(Math.max(0, patchDepth.get() - 1));
    }

    public static boolean isActive() {
        return patchDepth.get() > 0;
    }

    static float alpha() {
        return patchAlpha.get();
    }

    /** {@link me.napim.gomod.mixins.TntPlacementPreviewBlockDispatcherMixin} HEAD */
    public static void beginBlockMesh(WorldRenderer wr) {
        if (!isActive() || wr == null) {
            blockMeshStart.set(-1);
            return;
        }
        try {
            blockMeshStart.set(wr.getVertexCount());
        } catch (Throwable ignored) {
            blockMeshStart.set(0);
        }
    }

    /** {@link me.napim.gomod.mixins.TntPlacementPreviewBlockDispatcherMixin} RETURN — catch vertices missed by corner hooks */
    public static void finishBlockMesh(WorldRenderer wr) {
        int startVertex = blockMeshStart.get();
        blockMeshStart.set(-1);
        if (!isActive() || wr == null || startVertex < 0) {
            return;
        }
        float a = alpha();
        if (a >= 0.999f) {
            return;
        }
        try {
            int end = wr.getVertexCount();
            if (end <= startVertex) {
                return;
            }
            patchVertexRange(wr, startVertex, end, a, true);
        } catch (Throwable ignored) {
        }
    }

    /** {@link me.napim.gomod.mixins.TntPlacementPreviewWorldRendererMixin} */
    public static void patchCorner(WorldRenderer wr, int corner) {
        if (!isActive() || wr == null) {
            return;
        }
        float a = alpha();
        if (a >= 0.999f) {
            return;
        }
        try {
            VertexFormat vf = wr.getVertexFormat();
            if (vf == null) {
                return;
            }
            if (colorByteOffset(vf) < 0) {
                return;
            }
            int alphaByte = wr.getColorIndex(corner) * 4 + 3;
            ByteBuffer buf = resolveBuffer(wr);
            if (buf == null || alphaByte < 0 || alphaByte >= buf.capacity()) {
                return;
            }
            int na = targetAlphaByte(a);
            int saved = buf.position();
            try {
                buf.position(alphaByte);
                buf.put((byte) na);
            } finally {
                buf.position(saved);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void patchVertexRange(WorldRenderer wr, int start, int end, float a, boolean onlyIfOpaque) {
        VertexFormat vf = wr.getVertexFormat();
        if (vf == null) {
            return;
        }
        int stride = vf.getNextOffset();
        int colorOff = colorByteOffset(vf);
        if (stride <= 0 || colorOff < 0) {
            return;
        }
        ByteBuffer buf = resolveBuffer(wr);
        if (buf == null) {
            return;
        }
        int target = targetAlphaByte(a);
        int cap = buf.capacity();
        for (int vi = start; vi < end; vi++) {
            int aByte = vi * stride + colorOff + 3;
            if (aByte < 0 || aByte >= cap) {
                continue;
            }
            int existing = buf.get(aByte) & 0xFF;
            int na = onlyIfOpaque
                    ? (existing >= 250 ? target : Math.max(8, Math.min(255, Math.round(existing * alpha()))))
                    : Math.max(8, Math.min(255, Math.round(existing * alpha())));
            int saved = buf.position();
            try {
                buf.position(aByte);
                buf.put((byte) na);
            } finally {
                buf.position(saved);
            }
        }
    }

    private static int targetAlphaByte(float a) {
        return Math.max(16, Math.min(255, Math.round(255f * a)));
    }

    private static int colorByteOffset(VertexFormat vf) {
        int offset = 0;
        for (int i = 0; i < vf.getElementCount(); i++) {
            VertexFormatElement el = vf.getElement(i);
            if (el.getUsage() == VertexFormatElement.EnumUsage.COLOR) {
                return offset;
            }
            offset += el.getType().getSize();
        }
        return -1;
    }

    private static ByteBuffer resolveBuffer(WorldRenderer wr) {
        try {
            ByteBuffer direct = wr.getByteBuffer();
            if (direct != null) {
                return direct;
            }
        } catch (Throwable ignored) {
        }
        if (byteBufferField == null) {
            return null;
        }
        try {
            return (ByteBuffer) byteBufferField.get(wr);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
