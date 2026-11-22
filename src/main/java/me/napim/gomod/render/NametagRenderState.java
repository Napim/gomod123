package me.napim.gomod.render;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/** Keep the dim label pass independent of an earlier model's alpha cutoff and raw depth state. */
public final class NametagRenderState {
    private static final ThreadLocal<State> CURRENT=ThreadLocal.withInitial(State::new);
    private static final ThreadLocal<FloatBuffer> COLOR_READBACK = new ThreadLocal<FloatBuffer>() {
        // LWJGL 2's glGetFloat buffer overload requires space for a 4x4 matrix for any enum.
        @Override protected FloatBuffer initialValue() { return BufferUtils.createFloatBuffer(16); }
    };
    private NametagRenderState() { }

    /** True while a native label is drawing, so opacity hooks do not dim its glyphs. */
    public static boolean isActive() {
        try {
            return CURRENT.get().nesting > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void begin(String source) {
        State state=CURRENT.get();
        Frame frame=state.push();
        try {
            frame.depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            frame.mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            frame.depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            frame.alpha=GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            frame.alphaFunction=GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            frame.alphaReference=GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            frame.texture=GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            frame.textureValid=true;
            FloatBuffer color = COLOR_READBACK.get();
            color.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            frame.colorRed=color.get(0);
            frame.colorGreen=color.get(1);
            frame.colorBlue=color.get(2);
            frame.colorAlpha=color.get(3);
            frame.colorValid=true;
            frame.valid=true;
            // Lunar's raw GL calls and attribute-stack restores bypass Minecraft's cached state.
            // Reapply this for every nested label too: a parent draw can change GL state
            // between rows, and a nested label must not inherit that intermediate state.
            depth(frame.depth,frame.mask,GL11.GL_LEQUAL);
            GlStateManager.enableAlpha();GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.alphaFunc(GL11.GL_GREATER,1.0F/255.0F);
            GL11.glAlphaFunc(GL11.GL_GREATER,1.0F/255.0F);
            // Native Minecraft/Lunar labels draw a solid background quad before their textured glyphs.
            // If an earlier entity pass left texturing off, the quad survives but the label becomes an
            // empty gray rectangle. Re-enable texturing only for this label scope, then restore the caller.
            texture(true);
            // Lunar's label components provide their own text colors. A gray current GL color left by the
            // preceding entity can multiply those colors and make the countdown look dim or disappear.
            setColor(1.0F,1.0F,1.0F,1.0F);
            if(frame.alpha&&frame.alphaReference>=32.0F/255.0F)
                LunarConnectionDiagnostics.recordOnce("nametag.render_state_repaired",source,
                        "source="+source+" inheritedAlphaCutoff="+frame.alphaReference+" dimPassAlpha=0.1255");
        } catch(Throwable unavailable) { }
    }

    public static void end() {
        State state=CURRENT.get();
        if(state.nesting<=0)return;
        Frame frame=state.frames[state.nesting-1];
        try {
            if(!frame.valid)return;
            try { depth(frame.depth,frame.mask,frame.depthFunction); } catch(Throwable ignored) { }
            try {
                GlStateManager.alphaFunc(frame.alphaFunction,frame.alphaReference);
                GL11.glAlphaFunc(frame.alphaFunction,frame.alphaReference);
                if(frame.alpha){GlStateManager.enableAlpha();GL11.glEnable(GL11.GL_ALPHA_TEST);}
                else {GlStateManager.disableAlpha();GL11.glDisable(GL11.GL_ALPHA_TEST);}
            } catch(Throwable ignored) { }
            if(frame.textureValid) {
                try { texture(frame.texture); } catch(Throwable ignored) { }
            }
            if(frame.colorValid) {
                try { setColor(frame.colorRed,frame.colorGreen,frame.colorBlue,frame.colorAlpha); }
                catch(Throwable ignored) { }
            }
        } finally {
            frame.valid=false;
            frame.colorValid=false;
            state.pop();
        }
    }

    private static void setColor(float red,float green,float blue,float alpha) {
        GlStateManager.color(red,green,blue,alpha);
        // GlStateManager may skip its GL call when Lunar changed raw GL state behind its cache.
        // Keep outline rendering owned by the outline color hook, which intentionally overrides this value.
        if(!OutlineRenderingHelper.isInOutlineMode()&&!OutlineRenderingHelper.hasForcedChameleonOutlineTint())
            GL11.glColor4f(red,green,blue,alpha);
    }

    private static void texture(boolean enabled) {
        if(enabled){GlStateManager.enableTexture2D();GL11.glEnable(GL11.GL_TEXTURE_2D);}
        else {GlStateManager.disableTexture2D();GL11.glDisable(GL11.GL_TEXTURE_2D);}
    }

    private static void depth(boolean enabled,boolean mask,int function) {
        if(enabled){GlStateManager.enableDepth();GL11.glEnable(GL11.GL_DEPTH_TEST);}
        else {GlStateManager.disableDepth();GL11.glDisable(GL11.GL_DEPTH_TEST);}
        GlStateManager.depthMask(mask);GL11.glDepthMask(mask);
        GlStateManager.depthFunc(function);GL11.glDepthFunc(function);
    }
    private static final class State {
        private Frame[] frames=new Frame[4];
        private int nesting;

        private Frame push() {
            if(nesting==frames.length) {
                Frame[] expanded=new Frame[frames.length*2];
                System.arraycopy(frames,0,expanded,0,frames.length);
                frames=expanded;
            }
            Frame frame=frames[nesting];
            if(frame==null)frames[nesting]=frame=new Frame();
            nesting++;
            return frame;
        }

        private Frame pop() { return frames[--nesting]; }
    }

    private static final class Frame {
        int depthFunction,alphaFunction;
        boolean valid,depth,mask,alpha,texture,textureValid;
        float alphaReference;
        boolean colorValid;
        float colorRed,colorGreen,colorBlue,colorAlpha;
    }
}
