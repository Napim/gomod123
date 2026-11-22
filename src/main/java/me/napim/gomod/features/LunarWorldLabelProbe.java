package me.napim.gomod.features;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Locale;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/** Session-only isolation switches for Lunar's native world-label row, including its background. */
public final class LunarWorldLabelProbe {
    private enum Mode { OFF, ALL, LIGHTING, TEXTURES, SHADER, BLEND }
    private static final boolean FIX_NATIVE_WORLD_LABEL_LIGHTING = true;
    private static volatile Mode mode = Mode.OFF;
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<State>() {
        @Override protected State initialValue() { return new State(); }
    };

    private LunarWorldLabelProbe() { }

    public static String selectMode(String value) {
        mode = Mode.valueOf(value.toUpperCase(Locale.ROOT));
        return "Native world-label probe=" + modeName() + "; scoped lighting correction remains active.";
    }

    public static String modeName() { return mode.name().toLowerCase(Locale.ROOT); }

    public static void begin() {
        State state = CURRENT.get();
        Frame frame = state.push();
        if (!NametagRenderDebugCapture.isActive()) mode = Mode.OFF;
        frame.mode = mode;
        frame.fixLighting = FIX_NATIVE_WORLD_LABEL_LIGHTING;
        frame.valid = false;
        if (frame.mode == Mode.OFF && !NametagRenderDebugCapture.isActive()) {
            try {
                frame.lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
                frame.valid = true;
                lighting(false);
            } catch (Throwable failure) {
                NametagRenderDebugCapture.recordNativeWorldLabelState("probe_error",
                        "phase=lighting-fix type=" + failure.getClass().getSimpleName());
            }
            return;
        }
        try {
            frame.read(state);
            frame.valid = true;
            capture("row_before", state, frame);
            apply(frame);
        } catch (Throwable failure) {
            NametagRenderDebugCapture.recordNativeWorldLabelState("probe_error",
                    "phase=begin type=" + failure.getClass().getSimpleName());
        }
    }

    /** Reapply at the actual font color call in case the native background draw changed state. */
    public static void onGlyphColor() {
        if (mode == Mode.OFF && !NametagRenderDebugCapture.isActive()) return;
        State state = CURRENT.get();
        if (state.depth == 0) return;
        Frame frame = state.frames[state.depth - 1];
        if (!frame.valid) return;
        try {
            capture("glyph_before", state, frame);
            apply(frame);
            if (frame.mode != Mode.OFF) capture("glyph_after", state, frame);
        } catch (Throwable failure) {
            NametagRenderDebugCapture.recordNativeWorldLabelState("probe_error",
                    "phase=glyph type=" + failure.getClass().getSimpleName());
        }
    }

    public static void end() {
        State state = CURRENT.get();
        if (state.depth == 0) return;
        Frame frame = state.frames[--state.depth];
        try {
            if (!frame.valid || (frame.mode == Mode.OFF && !frame.fixLighting)) return;
            if (frame.fixLighting || lighting(frame.mode)) lighting(frame.lighting);
            if (textures(frame.mode)) {
                for (int i = 0; i < 3; i++) {
                    activeTexture(GL13.GL_TEXTURE0 + i);
                    texture(frame.textures[i]);
                    if (i == 0) GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, frame.envMode);
                }
                activeTexture(frame.activeTexture);
            }
            if (shader(frame.mode) && frame.programSupported) GL20.glUseProgram(frame.program);
            if (blend(frame.mode)) {
                GlStateManager.tryBlendFuncSeparate(frame.srcRgb, frame.dstRgb, frame.srcAlpha, frame.dstAlpha);
                GL14.glBlendFuncSeparate(frame.srcRgb, frame.dstRgb, frame.srcAlpha, frame.dstAlpha);
                blendEnabled(frame.blend);
                GlStateManager.alphaFunc(frame.alphaFunc, frame.alphaRef);
                GL11.glAlphaFunc(frame.alphaFunc, frame.alphaRef);
                if (frame.alpha) { GlStateManager.enableAlpha(); GL11.glEnable(GL11.GL_ALPHA_TEST); }
                else { GlStateManager.disableAlpha(); GL11.glDisable(GL11.GL_ALPHA_TEST); }
                if (frame.fog) { GlStateManager.enableFog(); GL11.glEnable(GL11.GL_FOG); }
                else { GlStateManager.disableFog(); GL11.glDisable(GL11.GL_FOG); }
                rawEnable(GL11.GL_COLOR_LOGIC_OP, frame.logicOp);
                colorMask(frame.mask);
            }
            capture("row_restored", state, frame);
        } catch (Throwable failure) {
            NametagRenderDebugCapture.recordNativeWorldLabelState("probe_error",
                    "phase=restore type=" + failure.getClass().getSimpleName());
        } finally { frame.valid = false; }
    }

    private static boolean lighting(Mode value) { return value == Mode.ALL || value == Mode.LIGHTING; }
    private static boolean textures(Mode value) { return value == Mode.ALL || value == Mode.TEXTURES; }
    private static boolean shader(Mode value) { return value == Mode.ALL || value == Mode.SHADER; }
    private static boolean blend(Mode value) { return value == Mode.ALL || value == Mode.BLEND; }

    private static void apply(Frame frame) {
        if (frame.fixLighting || lighting(frame.mode)) lighting(false);
        if (textures(frame.mode)) {
            for (int i = 1; i < 3; i++) {
                activeTexture(GL13.GL_TEXTURE0 + i);
                texture(false);
            }
            activeTexture(GL13.GL_TEXTURE0);
            texture(true);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        }
        if (shader(frame.mode) && frame.programSupported) GL20.glUseProgram(0);
        if (blend(frame.mode)) {
            blendEnabled(true);
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableAlpha(); GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
            GL11.glAlphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
            GlStateManager.disableFog(); GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            colorMask(15);
        }
    }

    private static void capture(String phase, State state, Frame frame) {
        if (!NametagRenderDebugCapture.isActive()) return;
        StringBuilder out = new StringBuilder("probe=").append(frame.mode.name().toLowerCase(Locale.ROOT))
                .append(" permanentLightingFix=").append(frame.fixLighting)
                .append(" lighting=").append(GL11.glIsEnabled(GL11.GL_LIGHTING))
                .append(" colorMaterial=").append(GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL))
                .append(" program=").append(frame.programSupported ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : -1)
                .append(" blend=").append(GL11.glIsEnabled(GL11.GL_BLEND))
                .append(" blendRgb=").append(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)).append('/')
                .append(GL11.glGetInteger(GL14.GL_BLEND_DST_RGB))
                .append(" alpha=").append(GL11.glIsEnabled(GL11.GL_ALPHA_TEST))
                .append(" alphaRef=").append(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF))
                .append(" fog=").append(GL11.glIsEnabled(GL11.GL_FOG))
                .append(" logicOp=").append(GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP))
                .append(" colorMask=0x").append(Integer.toHexString(readMask(state)))
                .append(" depth=").append(GL11.glIsEnabled(GL11.GL_DEPTH_TEST))
                .append(" depthFunc=0x").append(Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        out.append(" activeUnit=").append(active - GL13.GL_TEXTURE0);
        try {
            for (int i = 0; i < 3; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                out.append(" unit").append(i).append("{texture2d=").append(GL11.glIsEnabled(GL11.GL_TEXTURE_2D))
                        .append(",binding=").append(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
                ((Buffer) state.env).clear();
                GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, state.env);
                out.append(",env=0x").append(Integer.toHexString(state.env.get(0))).append('}');
            }
        } finally { GL13.glActiveTexture(active); }
        NametagRenderDebugCapture.recordNativeWorldLabelState(phase, out.toString());
    }

    private static int readMask(State state) {
        ((Buffer) state.mask).clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, state.mask);
        int bits = 0;
        for (int i = 0; i < 4; i++) if (state.mask.get(i) != 0) bits |= 1 << i;
        return bits;
    }

    private static void activeTexture(int unit) { GlStateManager.setActiveTexture(unit); GL13.glActiveTexture(unit); }
    private static void texture(boolean enabled) {
        if (enabled) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
        rawEnable(GL11.GL_TEXTURE_2D, enabled);
    }
    private static void lighting(boolean enabled) {
        if (enabled) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
        rawEnable(GL11.GL_LIGHTING, enabled);
    }
    private static void blendEnabled(boolean enabled) {
        if (enabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
        rawEnable(GL11.GL_BLEND, enabled);
    }
    private static void rawEnable(int flag, boolean enabled) { if (enabled) GL11.glEnable(flag); else GL11.glDisable(flag); }
    private static void colorMask(int bits) {
        boolean r = (bits & 1) != 0, g = (bits & 2) != 0, b = (bits & 4) != 0, a = (bits & 8) != 0;
        GlStateManager.colorMask(r, g, b, a); GL11.glColorMask(r, g, b, a);
    }

    private static final class State {
        Frame[] frames = new Frame[4];
        int depth;
        final ByteBuffer mask = BufferUtils.createByteBuffer(16);
        final java.nio.IntBuffer env = BufferUtils.createIntBuffer(16);
        Frame push() {
            if (depth == frames.length) frames = java.util.Arrays.copyOf(frames, frames.length * 2);
            if (frames[depth] == null) frames[depth] = new Frame();
            return frames[depth++];
        }
    }

    private static final class Frame {
        Mode mode;
        boolean valid, fixLighting, lighting, blend, alpha, fog, logicOp, programSupported;
        final boolean[] textures = new boolean[3];
        int activeTexture, envMode, program, srcRgb, dstRgb, srcAlpha, dstAlpha, alphaFunc, mask;
        float alphaRef;
        void read(State state) {
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            fog = GL11.glIsEnabled(GL11.GL_FOG);
            logicOp = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
            mask = readMask(state);
            programSupported = GLContext.getCapabilities().OpenGL20;
            program = programSupported ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
            srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB); dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA); dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC); alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            try {
                for (int i = 0; i < 3; i++) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                    textures[i] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
                    if (i == 0) {
                        ((Buffer) state.env).clear();
                        GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, state.env);
                        envMode = state.env.get(0);
                    }
                }
            } finally { GL13.glActiveTexture(activeTexture); }
        }
    }
}
