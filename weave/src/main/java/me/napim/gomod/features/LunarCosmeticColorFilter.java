package me.napim.gomod.features;

import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.*;

/** Recolors a copy of a cosmetic texture. The source, alpha, game shaders and framebuffer are preserved. */
public final class LunarCosmeticColorFilter {
    private static int program, framebuffer;
    private static final String VERTEX = "#version 120\nvarying vec2 uv;void main(){gl_Position=gl_Vertex;uv=gl_MultiTexCoord0.xy;}";
    private static final String FRAGMENT = "#version 120\nuniform sampler2D image;uniform vec3 adjustment;uniform int css;varying vec2 uv;"
            + "vec3 rgb2hsv(vec3 c){vec4 K=vec4(0.,-1./3.,2./3.,-1.);"
            + "vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));"
            + "vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));"
            + "float d=q.x-min(q.w,q.y);float e=1.e-10;return vec3(abs(q.z+(q.w-q.y)/(6.*d+e)),d/(q.x+e),q.x);}"
            + "vec3 hsv2rgb(vec3 c){vec3 p=abs(fract(c.xxx+vec3(0.,2./3.,1./3.))*6.-3.);"
            + "return c.z*mix(vec3(1.),clamp(p-1.,0.,1.),c.y);}"
            + "void main(){vec4 pixel=texture2D(image,uv);if(css==1){float c=cos(adjustment.x*6.2831853);float s=sin(adjustment.x*6.2831853);"
            + "vec3 color=clamp(vec3(dot(pixel.rgb,vec3(.213+.787*c-.213*s,.715-.715*c-.715*s,.072-.072*c+.928*s)),"
            + "dot(pixel.rgb,vec3(.213-.213*c+.143*s,.715+.285*c+.140*s,.072-.072*c-.283*s)),"
            + "dot(pixel.rgb,vec3(.213-.213*c-.787*s,.715-.715*c+.715*s,.072+.928*c+.072*s))),0.,1.);"
            + "float luminance=dot(color,vec3(.213,.715,.072));color=clamp(mix(vec3(luminance),color,adjustment.y),0.,1.);"
            + "gl_FragColor=vec4(clamp(color*adjustment.z,0.,1.),pixel.a);return;}vec3 hsv=rgb2hsv(pixel.rgb);"
            + "hsv.x=fract(hsv.x+adjustment.x);hsv.y=(abs(adjustment.x)>1.e-5&&hsv.y<1.e-3)?clamp(adjustment.y,0.,1.):adjustment.y<=1.?hsv.y*adjustment.y:mix(hsv.y,1.,clamp(adjustment.y-1.,0.,1.));"
            + "hsv.z=clamp(hsv.z*adjustment.z,0.,1.);gl_FragColor=vec4(hsv2rgb(hsv),pixel.a);}";
    private LunarCosmeticColorFilter() { }

    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        try {
            GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0)
                throw new IllegalStateException("Cosmetic color shader: " + GL20.glGetShaderInfoLog(shader, 2048));
            return shader;
        } catch (RuntimeException error) { GL20.glDeleteShader(shader); throw error; }
    }
    private static void initialize() {
        if (program != 0 && GL20.glIsProgram(program)) return;
        if (!GLContext.getCapabilities().OpenGL20
                || !(GLContext.getCapabilities().OpenGL30 || GLContext.getCapabilities().GL_EXT_framebuffer_object))
            throw new IllegalStateException("Cosmetic colors require OpenGL shaders and framebuffer objects.");
        int vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX), fragment = 0, next = 0;
        try {
            fragment = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT); next = GL20.glCreateProgram();
            GL20.glAttachShader(next, vertex); GL20.glAttachShader(next, fragment); GL20.glLinkProgram(next);
            if (GL20.glGetProgrami(next, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException("Cosmetic color program: " + GL20.glGetProgramInfoLog(next, 2048));
            int nextFramebuffer = GLContext.getCapabilities().OpenGL30 ? GL30.glGenFramebuffers() : EXTFramebufferObject.glGenFramebuffersEXT();
            if (nextFramebuffer == 0) throw new IllegalStateException("Cosmetic framebuffer is unavailable.");
            program = next; next = 0; framebuffer = nextFramebuffer;
        } finally {
            GL20.glDeleteShader(vertex); if (fragment != 0) GL20.glDeleteShader(fragment);
            if (next != 0) GL20.glDeleteProgram(next);
        }
    }

    public static void render(int source, int destination, int width, int height,
                              double hue, double saturation, double brightness) {
        render(source,destination,width,height,hue,saturation,brightness,false);
    }
    public static void render(int source, int destination, int width, int height,
                              double hue, double saturation, double brightness,boolean css) {
        if (source <= 0 || destination <= 0 || source == destination || width < 1 || height < 1)
            throw new IllegalArgumentException("Invalid cosmetic texture.");
        initialize();
        State state = new State();
        try {
            if (state.separate) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,destination,0);
            } else {
                EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
                EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                        EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, destination, 0);
            }
            if ((state.separate ? GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                    : EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT))
                    != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) throw new IllegalStateException("Incomplete cosmetic framebuffer.");
            GL11.glDrawBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT);
            GL11.glReadBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT);
            GL11.glViewport(0, 0, width, height);
            for (int capability : State.CAPABILITIES) GL11.glDisable(capability);
            if (state.separate) GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            if (state.hasSrgb)
                GL11.glDisable(0x8DB9); // FRAMEBUFFER_SRGB
            GL11.glColorMask(true,true,true,true); GL11.glDepthMask(false); GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);
            GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, source);
            if (state.hasSamplers) GL33.glBindSampler(0,0);
            GL20.glUseProgram(program); GL20.glUniform1i(GL20.glGetUniformLocation(program,"image"),0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"css"),css?1:0);
            GL20.glUniform3f(GL20.glGetUniformLocation(program,"adjustment"),(float)(hue/360.0),(float)saturation,(float)brightness);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0,0); GL11.glVertex2f(-1,-1); GL11.glTexCoord2f(1,0); GL11.glVertex2f(1,-1);
            GL11.glTexCoord2f(1,1); GL11.glVertex2f(1,1); GL11.glTexCoord2f(0,1); GL11.glVertex2f(-1,1);
            GL11.glEnd();
        } finally {
            state.restore();
        }
    }

    /** The native UI can already occupy every attribute-stack slot. Save only state this pass changes. */
    private static final class State {
        static final int[] CAPABILITIES={GL11.GL_BLEND,GL11.GL_DEPTH_TEST,GL11.GL_ALPHA_TEST,GL11.GL_CULL_FACE,
                GL11.GL_SCISSOR_TEST,GL11.GL_STENCIL_TEST,GL11.GL_LIGHTING,GL11.GL_FOG,GL11.GL_COLOR_LOGIC_OP,
                GL11.GL_DITHER,GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,GL13.GL_SAMPLE_ALPHA_TO_ONE,GL13.GL_SAMPLE_COVERAGE,
                GL11.GL_CLIP_PLANE0,GL11.GL_CLIP_PLANE1,GL11.GL_CLIP_PLANE2,
                GL11.GL_CLIP_PLANE3,GL11.GL_CLIP_PLANE4,GL11.GL_CLIP_PLANE5};
        final boolean separate=GLContext.getCapabilities().OpenGL30;
        final boolean hasSrgb=separate||GLContext.getCapabilities().GL_EXT_framebuffer_sRGB;
        final boolean hasSamplers=GLContext.getCapabilities().OpenGL33||GLContext.getCapabilities().GL_ARB_sampler_objects;
        final boolean srgb=hasSrgb&&GL11.glIsEnabled(0x8DB9);
        final boolean discard=separate&&GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        final boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final boolean[] enabled=new boolean[CAPABILITIES.length];
        final int draw=GL11.glGetInteger(separate?GL30.GL_DRAW_FRAMEBUFFER_BINDING:EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        final int read=separate?GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING):draw;
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int texture,sampler;
        final int[] viewport=new int[4],polygonMode=new int[2];
        final boolean[] colorMask=new boolean[4];
        final float[] coordinates=new float[4];
        State() {
            for(int i=0;i<CAPABILITIES.length;i++)enabled[i]=GL11.glIsEnabled(CAPABILITIES[i]);
            IntBuffer ints=BufferUtils.createIntBuffer(16);GL11.glGetInteger(GL11.GL_VIEWPORT,ints);
            for(int i=0;i<4;i++)viewport[i]=ints.get(i);
            ints.clear();GL11.glGetInteger(GL11.GL_POLYGON_MODE,ints);
            polygonMode[0]=ints.get(0);polygonMode[1]=ints.get(1);
            ByteBuffer mask=BufferUtils.createByteBuffer(16);GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK,mask);
            for(int i=0;i<4;i++)colorMask[i]=mask.get(i)!=0;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            try {
                texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                sampler=hasSamplers?GL11.glGetInteger(GL33.GL_SAMPLER_BINDING):0;
                FloatBuffer floats=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS,floats);
                for(int i=0;i<4;i++)coordinates[i]=floats.get(i);
            } finally {GL13.glActiveTexture(active);}
        }
        static void enable(int capability,boolean value){if(value)GL11.glEnable(capability);else GL11.glDisable(capability);}
        void restore() {
            if(separate){GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);}
            else EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,draw);
            GL20.glUseProgram(program);
            for(int i=0;i<CAPABILITIES.length;i++)enable(CAPABILITIES[i],enabled[i]);
            if(hasSrgb)enable(0x8DB9,srgb);
            if(separate)enable(GL30.GL_RASTERIZER_DISCARD,discard);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL11.glColorMask(colorMask[0],colorMask[1],colorMask[2],colorMask[3]);GL11.glDepthMask(depthMask);
            GL11.glPolygonMode(GL11.GL_FRONT,polygonMode[0]);GL11.glPolygonMode(GL11.GL_BACK,polygonMode[1]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            if(hasSamplers)GL33.glBindSampler(0,sampler);
            GL13.glMultiTexCoord4f(GL13.GL_TEXTURE0,coordinates[0],coordinates[1],coordinates[2],coordinates[3]);
            GL13.glActiveTexture(active);
        }
    }
}
