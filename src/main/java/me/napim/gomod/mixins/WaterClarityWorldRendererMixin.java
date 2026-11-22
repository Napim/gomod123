package me.napim.gomod.mixins;

import me.napim.gomod.features.WaterClarity;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Block models that use {@code putColorMultiplier}: patch alpha after each call. Water from {@link net.minecraft.client.renderer.BlockFluidRenderer}
 * is handled separately by buffering {@link me.napim.gomod.features.WaterClarity#applyFluidMeshVertexAlphaAfterRender}.
 */
@Mixin(WorldRenderer.class)
public abstract class WaterClarityWorldRendererMixin {

    private static Field gomod$byteBufferField;

    static {
        for (String n : new String[]{"byteBuffer", "field_178999_b", "field_179010_h"}) {
            try {
                Field f = WorldRenderer.class.getDeclaredField(n);
                if (ByteBuffer.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    gomod$byteBufferField = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Inject(method = {"putColorMultiplier", "func_178978_a"}, at = @At("RETURN"), require = 0)
    private void gomod$waterVertexAlphaAfterPut(float red, float green, float blue, int corner, CallbackInfo ci) {
        if (!WaterClarity.isSurfaceChunkTrackingActive()) {
            return;
        }
        if (!WaterClarity.shouldPatchWaterVertexAlpha()) {
            return;
        }
        WorldRenderer wr = (WorldRenderer) (Object) this;
        try {
            VertexFormat vf = wr.getVertexFormat();
            if (vf == null || gomod$byteBufferField == null) {
                return;
            }
            int stride = vf.getNextOffset();
            int colorOff = gomod$colorByteOffset(vf);
            if (stride <= 0 || colorOff < 0) {
                return;
            }
            int baseByte = wr.getColorIndex(corner) * stride + colorOff;
            ByteBuffer buf = WaterClarity.resolveWorldRendererByteBufferPublic(wr);
            if (buf == null) {
                buf = gomod$byteBufferField != null ? (ByteBuffer) gomod$byteBufferField.get(wr) : null;
            }
            if (buf == null || baseByte < 0 || baseByte + 4 > buf.capacity()) {
                return;
            }
            float scale = WaterClarity.getSurfaceTintAlphaScale();
            int na = Math.max(16, Math.min(255, Math.round(255f * scale)));
            int saved = buf.position();
            try {
                buf.position(baseByte + 3);
                buf.put((byte) na);
            } finally {
                buf.position(saved);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int gomod$colorByteOffset(VertexFormat vf) {
        int offset = 0;
        for (int i = 0; i < vf.getElementCount(); i++) {
            VertexFormatElement el = vf.getElement(i);
            if (el.getUsage() == VertexFormatElement.EnumUsage.COLOR) {
                return offset;
            }
            offset += el.getSize();
        }
        return -1;
    }
}
