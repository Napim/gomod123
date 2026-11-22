package me.napim.gomod.features;

import java.nio.ByteBuffer;

/** Applies opacity after baked-quad colors have been finalized, without changing the source model. */
public final class FirstPersonItemVertexAlpha {
    private FirstPersonItemVertexAlpha() {}

    public static int scale(ByteBuffer buffer, int count, int stride, int colorOffset, float opacity) {
        if (buffer == null || buffer.isReadOnly() || count <= 0 || stride < 4
                || colorOffset < 0 || colorOffset > stride - 4
                || (long) count * stride > buffer.limit()
                || Float.isNaN(opacity) || opacity >= 1.0F) return 0;
        float alpha = Math.max(0.0F, opacity);
        // OpenGL's unsigned-byte color attribute is RGBA regardless of the buffer's byte order.
        for (int vertex = 0; vertex < count; vertex++) {
            int index = vertex * stride + colorOffset + 3;
            int original = buffer.get(index) & 255;
            buffer.put(index, (byte) Math.round(original * alpha));
        }
        return count;
    }
}
