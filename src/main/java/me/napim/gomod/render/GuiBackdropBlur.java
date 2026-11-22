package me.napim.gomod.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFragmentShader;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;

/**
 * Separable Gaussian blur of the current main framebuffer, drawn under GUI dim gradients.
 * Uses half-resolution ping-pong FBOs and ARB shaders (OpenGL 2.0+).
 */
public final class GuiBackdropBlur {

    private static final String VS = "#version 120\n"
            + "void main() {\n"
            + "  gl_TexCoord[0] = gl_MultiTexCoord0;\n"
            + "  gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
            + "}\n";

    private static final String FS = "#version 120\n"
            + "uniform sampler2D u_tex;\n"
            + "uniform vec2 u_texel;\n"
            + "uniform vec2 u_dir;\n"
            + "void main() {\n"
            + "  vec2 t = gl_TexCoord[0].st;\n"
            + "  vec4 c = texture2D(u_tex, t) * 0.2270270270;\n"
            + "  c += texture2D(u_tex, t + u_dir * u_texel * 1.0) * 0.1945945946;\n"
            + "  c += texture2D(u_tex, t - u_dir * u_texel * 1.0) * 0.1945945946;\n"
            + "  c += texture2D(u_tex, t + u_dir * u_texel * 2.0) * 0.1216216216;\n"
            + "  c += texture2D(u_tex, t - u_dir * u_texel * 2.0) * 0.1216216216;\n"
            + "  c += texture2D(u_tex, t + u_dir * u_texel * 3.0) * 0.0540540541;\n"
            + "  c += texture2D(u_tex, t - u_dir * u_texel * 3.0) * 0.0540540541;\n"
            + "  c += texture2D(u_tex, t + u_dir * u_texel * 4.0) * 0.0162162162;\n"
            + "  c += texture2D(u_tex, t - u_dir * u_texel * 4.0) * 0.0162162162;\n"
            + "  gl_FragColor = vec4(c.rgb, 1.0);\n"
            + "}\n";

    private static Framebuffer fboHalf;
    private static Framebuffer fboPing;
    private static Framebuffer fboPong;
    private static int halfW;
    private static int halfH;
    private static int program;
    private static int locTex;
    private static int locTexel;
    private static int locDir;
    private static boolean initAttempted;
    private static boolean initOk;

    private GuiBackdropBlur() {}

    public static boolean isAvailable() {
        ensureProgram();
        return initOk;
    }

    /**
     * Blurs the main framebuffer snapshot and draws it as a fullscreen layer in GUI coordinates (0..guiW, 0..guiH).
     *
     * @param excludeBottomGuiPixels if &gt; 0, the bottom strip (scaled GUI pixels) is not composited so chat stays sharp
     */
    public static void render(Minecraft mc, int guiW, int guiH, int blurPercent, int excludeBottomGuiPixels) {
        render(mc, guiW, guiH, blurPercent, excludeBottomGuiPixels, 1.0f);
    }

    public static void render(Minecraft mc, int guiW, int guiH, int blurPercent, int excludeBottomGuiPixels,
            float alpha) {
        if (mc == null || blurPercent <= 0 || guiW <= 0 || guiH <= 0) {
            return;
        }
        Framebuffer main = mc.getFramebuffer();
        if (main == null) {
            return;
        }
        ensureProgram();
        if (!initOk) {
            return;
        }

        IntBuffer vpSave = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vpSave);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            renderBlurInternal(main, guiW, guiH, blurPercent, excludeBottomGuiPixels, alpha);
        } finally {
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GL11.glViewport(vpSave.get(0), vpSave.get(1), vpSave.get(2), vpSave.get(3));
        }
    }

    /** Same as {@link #render(Minecraft, int, int, int, int)} with no bottom exclusion. */
    public static void render(Minecraft mc, int guiW, int guiH, int blurPercent) {
        render(mc, guiW, guiH, blurPercent, 0, 1.0f);
    }

    private static void renderBlurInternal(Framebuffer main, int guiW, int guiH, int blurPercent,
            int excludeBottomGuiPixels, float alpha) {
        int mw = main.framebufferTextureWidth;
        int mh = main.framebufferTextureHeight;
        if (mw <= 0 || mh <= 0) {
            return;
        }

        int bw = Math.max(1, mw / 2);
        int bh = Math.max(1, mh / 2);
        ensureFboSize(bw, bh);

        GlStateManager.disableLighting();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

        // Downsample copy: main -> fboHalf (linear filter softens)
        fboHalf.framebufferClear();
        fboHalf.bindFramebuffer(false);
        GlStateManager.viewport(0, 0, bw, bh);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, bw, bh, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        main.bindFramebufferTexture();
        drawTexQuad(0, 0, bw, bh, 0, 1, 1, 0);

        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);

        // Low slider values were too strong (even 1% ran a full kernel). Scale pass count and sample radius nonlinearly.
        float t = blurPercent / 100f;
        float texelScale = 0.04f + 0.96f * (float) Math.pow(t, 1.35);
        int pairs = Math.max(1, (int) Math.floor(1.0 + t * t * 7.0));
        Framebuffer src = fboHalf;
        for (int i = 0; i < pairs; i++) {
            applyBlurPass(src, fboPing, bw, bh, 1.0f, 0.0f, texelScale);
            applyBlurPass(fboPing, fboPong, bw, bh, 0.0f, 1.0f, texelScale);
            src = fboPong;
        }

        // Composite blurred result onto main framebuffer in GUI space
        main.bindFramebuffer(false);
        GlStateManager.viewport(0, 0, main.framebufferWidth, main.framebufferHeight);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, guiW, guiH, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, Math.max(0.0F, Math.min(1.0F, alpha)));

        ARBShaderObjects.glUseProgramObjectARB(0);
        src.bindFramebufferTexture();
        boolean clipBottom = excludeBottomGuiPixels > 0;
        if (clipBottom) {
            Minecraft mc = Minecraft.getMinecraft();
            GuiBackdropChatReserve.enableBottomChatClearScissor(mc, excludeBottomGuiPixels);
        }
        try {
            // Main framebuffer texture is bottom-origin; flip V so the composite matches on-screen orientation.
            drawTexQuad(0, 0, guiW, guiH, 0, 1, 1, 0);
        } finally {
            if (clipBottom) {
                GuiBackdropChatReserve.disableBottomChatClearScissor();
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);

        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void applyBlurPass(Framebuffer from, Framebuffer to, int bw, int bh, float dirX, float dirY,
            float texelScale) {
        to.framebufferClear();
        to.bindFramebuffer(false);
        GlStateManager.viewport(0, 0, bw, bh);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, bw, bh, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);

        ARBShaderObjects.glUseProgramObjectARB(program);
        from.bindFramebufferTexture();
        float ts = Math.max(0.01f, texelScale);
        ARBShaderObjects.glUniform2fARB(locTexel, (1.0f / bw) * ts, (1.0f / bh) * ts);
        ARBShaderObjects.glUniform2fARB(locDir, dirX, dirY);
        ARBShaderObjects.glUniform1iARB(locTex, 0);

        drawTexQuadRaw(0, 0, bw, bh, 0, 1, 1, 0);

        ARBShaderObjects.glUseProgramObjectARB(0);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
    }

    private static void drawTexQuad(int x, int y, int w, int h, float u0, float v0, float u1, float v1) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0.0D).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0.0D).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0.0D).tex(u1, v0).endVertex();
        wr.pos(x, y, 0.0D).tex(u0, v0).endVertex();
        tess.draw();
    }

    private static void drawTexQuadRaw(int x, int y, int w, int h, float u0, float v0, float u1, float v1) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + h, 0.0D).tex(u0, v1).endVertex();
        wr.pos(x + w, y + h, 0.0D).tex(u1, v1).endVertex();
        wr.pos(x + w, y, 0.0D).tex(u1, v0).endVertex();
        wr.pos(x, y, 0.0D).tex(u0, v0).endVertex();
        tess.draw();
    }

    private static void ensureFboSize(int bw, int bh) {
        if (fboHalf != null && halfW == bw && halfH == bh) {
            return;
        }
        deleteFbos();
        halfW = bw;
        halfH = bh;
        fboHalf = new Framebuffer(bw, bh, false);
        fboHalf.setFramebufferFilter(9729);
        fboPing = new Framebuffer(bw, bh, false);
        fboPing.setFramebufferFilter(9729);
        fboPong = new Framebuffer(bw, bh, false);
        fboPong.setFramebufferFilter(9729);
    }

    private static void deleteFbos() {
        if (fboHalf != null) {
            fboHalf.deleteFramebuffer();
            fboHalf = null;
        }
        if (fboPing != null) {
            fboPing.deleteFramebuffer();
            fboPing = null;
        }
        if (fboPong != null) {
            fboPong.deleteFramebuffer();
            fboPong = null;
        }
        halfW = halfH = 0;
    }

    private static void ensureProgram() {
        if (initAttempted) {
            return;
        }
        initAttempted = true;
        try {
            if (!OpenGlHelper.shadersSupported) {
                return;
            }
            int vs = ARBShaderObjects.glCreateShaderObjectARB(ARBVertexShader.GL_VERTEX_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(vs, VS);
            ARBShaderObjects.glCompileShaderARB(vs);
            if (ARBShaderObjects.glGetObjectParameteriARB(vs, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) == GL11.GL_FALSE) {
                return;
            }
            int fs = ARBShaderObjects.glCreateShaderObjectARB(ARBFragmentShader.GL_FRAGMENT_SHADER_ARB);
            ARBShaderObjects.glShaderSourceARB(fs, FS);
            ARBShaderObjects.glCompileShaderARB(fs);
            if (ARBShaderObjects.glGetObjectParameteriARB(fs, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) == GL11.GL_FALSE) {
                return;
            }
            program = ARBShaderObjects.glCreateProgramObjectARB();
            ARBShaderObjects.glAttachObjectARB(program, vs);
            ARBShaderObjects.glAttachObjectARB(program, fs);
            ARBShaderObjects.glLinkProgramARB(program);
            if (ARBShaderObjects.glGetObjectParameteriARB(program, ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB) == GL11.GL_FALSE) {
                return;
            }
            locTex = ARBShaderObjects.glGetUniformLocationARB(program, "u_tex");
            locTexel = ARBShaderObjects.glGetUniformLocationARB(program, "u_texel");
            locDir = ARBShaderObjects.glGetUniformLocationARB(program, "u_dir");
            if (locTex < 0 || locTexel < 0 || locDir < 0) {
                return;
            }
            initOk = true;
        } catch (Throwable ignored) {
            initOk = false;
        }
    }
}
