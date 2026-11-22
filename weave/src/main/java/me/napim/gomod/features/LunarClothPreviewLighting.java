package me.napim.gomod.features;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.FloatBuffer;

/** Neutral fixed-function lighting for a Locker cloth draw, with exact outer-state restoration. */
public final class LunarClothPreviewLighting {
    private final boolean lighting;
    private final float[] lightmap=new float[4];
    private boolean touched;
    private LunarClothPreviewLighting() {
        lighting=GL11.glIsEnabled(GL11.GL_LIGHTING);
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try { select(GL13.GL_TEXTURE1);read(GL11.GL_CURRENT_TEXTURE_COORDS,lightmap); }
        finally { select(active); }
    }
    public static LunarClothPreviewLighting capture() {
        try {return new LunarClothPreviewLighting();}
        catch(Throwable noContext){return null;}
    }
    private static void read(int name,float[] result) {
        FloatBuffer buffer=BufferUtils.createFloatBuffer(16);GL11.glGetFloat(name,buffer);
        for(int i=0;i<4;i++)result[i]=buffer.get(i);
    }
    private static void select(int unit){GlStateManager.setActiveTexture(unit);GL13.glActiveTexture(unit);}
    /** Native material setup happens after scope entry, so apply immediately before the actual draw. */
    public void apply() {
        if(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)!=0)return;
        touched=true;
        GlStateManager.disableLighting();GL11.glDisable(GL11.GL_LIGHTING);
        // Legacy cloth uses fixed-function texture unit 1 for the lightmap. Leave its native
        // cached coordinates untouched; only this draw uses full light, then the raw value returns.
        GL13.glMultiTexCoord4f(GL13.GL_TEXTURE1,240,240,0,1);
        LunarConnectionDiagnostics.recordOnce("cosmetics.cloth.preview_lighting","neutral",
                "fixedFunction=true scope=locker-cloth worldUnchanged=true");
    }
    public void close() {
        if(!touched)return;
        touched=false;
        try {
            if(lighting){GlStateManager.enableLighting();GL11.glEnable(GL11.GL_LIGHTING);}
            else{GlStateManager.disableLighting();GL11.glDisable(GL11.GL_LIGHTING);}
            GL13.glMultiTexCoord4f(GL13.GL_TEXTURE1,lightmap[0],lightmap[1],lightmap[2],lightmap[3]);
        }catch(Throwable lostContext){/* A closing/lost OpenGL context cannot be restored. */}
    }
}
