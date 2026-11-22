package me.napim.gomod.util;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.Buffer;
import java.nio.FloatBuffer;

/**
 * During /glowplayer (forced solid tint), we need the same textured silhouette as /glowplayer2
 * (UV + alpha from skin/armour) but RGB must not be modulated by skin tone.
 * <p>
 * Sets texture unit 0 to {@link GL13#GL_COMBINE}: RGB = {@link GL13#GL_CONSTANT} (glow colour),
 * alpha = texture alpha (hair cutouts, armour masks). Paired {@link #pushRgb(int)} / {@link #pop()}.
 */
public final class ForcedSolidTintTextureEnv {

    private static final FloatBuffer COLOR_BUF = BufferUtils.createFloatBuffer(4);
    private static int pushDepth;

    private ForcedSolidTintTextureEnv() {
    }

    public static boolean isActive() {
        return pushDepth > 0;
    }

    public static void pushRgb(int rgb) {
        if (pushDepth == 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
            float r = ((rgb >> 16) & 0xFF) / 255.0f;
            float g = ((rgb >> 8) & 0xFF) / 255.0f;
            float b = (rgb & 0xFF) / 255.0f;
            // Call clear/flip on java.nio.Buffer so bytecode targets Java 8 (1.8.9 clients); JDK9+ covariant
            // FloatBuffer.clear() breaks under Java 8 with NoSuchMethodError.
            ((Buffer) COLOR_BUF).clear();
            COLOR_BUF.put(r).put(g).put(b).put(1.0f);
            ((Buffer) COLOR_BUF).flip();
            GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, COLOR_BUF);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_CONSTANT);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        }
        pushDepth++;
    }

    public static void pop() {
        if (pushDepth <= 0) {
            return;
        }
        pushDepth--;
        if (pushDepth == 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glPopAttrib();
        }
    }

    /** If an outline pass aborts before {@link #pop()}, restore texture env so the world pass is not stuck in COMBINE mode. */
    public static void forceReset() {
        if (pushDepth > 0) {
            pushDepth = 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glPopAttrib();
        }
    }
}
