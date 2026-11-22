package me.napim.gomod.features;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Draws the pet's nearest surface from one pose, even when Lunar rebuilds its animated mesh. */
public final class LunarPetOpacityPass {
    private static final int SAVED_STATE = GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_ENABLE_BIT | GL11.GL_POLYGON_BIT;
    private static final String VERTEX = "#version 120\nvoid main(){gl_Position=gl_Vertex;}";
    private static final String FRAGMENT = "#version 120\nuniform sampler2D surface, depthImage;"
            + "uniform vec2 size; uniform float opacity;"
            + "void main(){vec2 uv=gl_FragCoord.xy/size; vec4 pixel=texture2D(surface,uv);"
            + "if(pixel.a<=1.0/255.0)discard;"
            + "gl_FragDepth=texture2D(depthImage,uv).r; gl_FragColor=vec4(pixel.rgb,pixel.a*opacity);}";

    private static boolean active;
    private static Capture capture;

    private LunarPetOpacityPass() { }

    static void beginFrame() {
        active = true;
    }

    /** Called after native animation, texture, size and vertex setup, immediately around the mesh upload. */
    public static void beforeDraw(long cosmeticId) {
        if (!active) return;
        int phase = SelfOpacity.lunarCosmeticMeshPhase();
        if (phase == 0) return;
        if (capture == null) capture = new Capture();
        capture.restoreDraw();
        if (phase == 1) {
            capture.captureDraw(cosmeticId);
        } else if (capture.depthWritten && capture.hasPet(cosmeticId) && pushState()) {
            capture.drawStateSaved = true;
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
        }
    }

    public static void restoreDraw() {
        if (capture != null) capture.restoreDraw();
    }

    static void writeDepth() {
        if (capture != null && capture.petCount > 0) {
            capture.depthWritten = capture.drawSurface(true);
        }
    }

    static void composite() {
        if (capture != null && capture.depthWritten) capture.drawSurface(false);
    }

    static void finishFrame() {
        active = false;
        if (capture == null) return;
        capture.restoreDraw();
        capture.petCount = 0;
        capture.depthWritten = false;
    }

    private static boolean pushState() {
        if (GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH) >= GL11.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH)) {
            return false;
        }
        GL11.glPushAttrib(SAVED_STATE);
        return true;
    }

    private static final class Capture {
        private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
        private final long[] petIds = new long[16];
        private int petCount;
        private int width, height, framebuffer, color, depth, program;
        private int surfaceUniform, depthUniform, sizeUniform, opacityUniform;
        private int sceneFramebuffer;
        private boolean captureAvailable = true;
        private boolean drawStateSaved;
        private boolean offscreenBound;
        private boolean depthWritten;
        private boolean reportedCapture;
        private boolean reportedComposite;

        void captureDraw(long id) {
            if (!captureAvailable || !GLContext.getCapabilities().OpenGL30
                    || (petCount == petIds.length && !hasPet(id)) || !pushState()) return;
            drawStateSaved = true;
            int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            try {
                if (petCount == 0) {
                    GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
                    if (viewport.get(0) != 0 || viewport.get(1) != 0) {
                        restoreDraw();
                        return;
                    }
                    sceneFramebuffer = bound;
                    offscreenBound = true;
                    bindCapture(viewport.get(2), viewport.get(3));
                    clearCapture();
                } else {
                    if (bound != sceneFramebuffer) {
                        restoreDraw();
                        return;
                    }
                    offscreenBound = true;
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                }
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GL11.glColorMask(true, true, true, true);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
                if (!hasPet(id)) petIds[petCount++] = id;
                if (!reportedCapture && LunarConnectionDiagnostics.isEnabled()) {
                    reportedCapture = true;
                    LunarConnectionDiagnostics.record("cosmetics.appearance.pet_surface",
                            "id=" + id + " source=native-prepass");
                }
            } catch (RuntimeException failure) {
                restoreDraw();
                captureAvailable = false;
                System.err.println("[gomod] Pet opacity surface unavailable: " + failure);
            }
        }

        boolean hasPet(long id) {
            for (int i = 0; i < petCount; i++) {
                if (petIds[i] == id) return true;
            }
            return false;
        }

        void restoreDraw() {
            if (offscreenBound) {
                offscreenBound = false;
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneFramebuffer);
            }
            if (drawStateSaved) {
                drawStateSaved = false;
                GL11.glPopAttrib();
            }
        }

        private void bindCapture(int w, int h) {
            if (w <= 0 || h <= 0) throw new IllegalArgumentException("Empty pet viewport");
            if (program == 0) initialize();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            if (width == w && height == h) return;
            int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            try {
                if (unpack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                allocate(color, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h);
                allocate(depth, GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, w, h);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, color, 0);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, depth, 0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Incomplete pet framebuffer");
                }
                width = w;
                height = h;
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
                if (unpack != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            }
        }

        private static void allocate(int texture, int internal, int format, int type, int w, int h) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, (ByteBuffer) null);
        }

        private static void clearCapture() {
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(0F, 0F, 0F, 0F);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }

        private boolean drawSurface(boolean depthOnly) {
            if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != sceneFramebuffer || !pushState()) return false;
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean samplers = GLContext.getCapabilities().OpenGL33;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int sampler0 = samplers ? GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) : 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            int texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int sampler1 = samplers ? GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) : 0;
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
                if (samplers) {
                    GL33.glBindSampler(0, 0);
                    GL33.glBindSampler(1, 0);
                }
                GL20.glUseProgram(program);
                GL20.glUniform1i(surfaceUniform, 0);
                GL20.glUniform1i(depthUniform, 1);
                GL20.glUniform2f(sizeUniform, width, height);
                GL20.glUniform1f(opacityUniform, SelfOpacity.getAlpha());
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
                GL11.glColorMask(!depthOnly, !depthOnly, !depthOnly, false);
                GL11.glDepthMask(depthOnly);
                GL11.glDepthFunc(depthOnly ? GL11.GL_LEQUAL : GL11.GL_EQUAL);
                if (depthOnly) {
                    GL11.glDisable(GL11.GL_BLEND);
                } else {
                    GL11.glEnable(GL11.GL_BLEND);
                    GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                    GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                }
                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex2f(-1F, -1F);
                GL11.glVertex2f(3F, -1F);
                GL11.glVertex2f(-1F, 3F);
                GL11.glEnd();
                if (!depthOnly && !reportedComposite && LunarConnectionDiagnostics.isEnabled()) {
                    reportedComposite = true;
                    LunarConnectionDiagnostics.record("cosmetics.appearance.pet_surface",
                            "composited=true resolution=" + width + "x" + height);
                }
                return true;
            } finally {
                GL20.glUseProgram(previousProgram);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture1);
                if (samplers) GL33.glBindSampler(1, sampler1);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
                if (samplers) GL33.glBindSampler(0, sampler0);
                GL13.glActiveTexture(activeTexture);
                GL11.glPopAttrib();
            }
        }

        private void initialize() {
            int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
            int fragment = 0;
            int created = 0;
            try {
                fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
                created = GL20.glCreateProgram();
                GL20.glAttachShader(created, vertex);
                GL20.glAttachShader(created, fragment);
                GL20.glLinkProgram(created);
                if (GL20.glGetProgrami(created, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(created, 1024));
                }
                surfaceUniform = GL20.glGetUniformLocation(created, "surface");
                depthUniform = GL20.glGetUniformLocation(created, "depthImage");
                sizeUniform = GL20.glGetUniformLocation(created, "size");
                opacityUniform = GL20.glGetUniformLocation(created, "opacity");
                framebuffer = GL30.glGenFramebuffers();
                color = GL11.glGenTextures();
                depth = GL11.glGenTextures();
                program = created;
                created = 0;
            } finally {
                if (created != 0) GL20.glDeleteProgram(created);
                if (fragment != 0) GL20.glDeleteShader(fragment);
                GL20.glDeleteShader(vertex);
            }
        }

        private static int compile(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) return shader;
            String log = GL20.glGetShaderInfoLog(shader, 1024);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
    }
}
