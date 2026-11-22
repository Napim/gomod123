package me.napim.gomod.gui;

import me.napim.gomod.features.LunarFitSharing;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/** Per-message pointer capture backed by Lunar's native animated mannequin textures. */
public final class LunarFitChatPreview {
    private static final LunarFitPreviewInteraction INTERACTION=new LunarFitPreviewInteraction();
    private LunarFitChatPreview(){}
    public static boolean isPreview(Entity entity){return false;}
    static void beginFrame(String peer){INTERACTION.beginFrame(LunarFitSharing.account(),peer);LunarGiftNativeModels.begin();}
    static void endFrame(){INTERACTION.endFrame();LunarGiftNativeModels.end();}
    static void addHit(String key,int left,int top,int right,int bottom,int clipLeft,int clipTop,int clipRight,int clipBottom){INTERACTION.add(key,left,top,right,bottom,clipLeft,clipTop,clipRight,clipBottom);}
    public static boolean startDrag(String peer,int x,int y){return INTERACTION.start(LunarFitSharing.account(),peer,x,y,System.currentTimeMillis());}
    public static boolean drag(String peer,int x){return INTERACTION.move(LunarFitSharing.account(),peer,x);}
    public static void releaseDrag(){INTERACTION.release();}
    static boolean drawGift(Minecraft mc,String wire,String key,boolean fit,String receiver,int x,int y,int width,int height){return LunarGiftNativeModels.draw(mc,wire,fit,receiver,x,y,width,height,INTERACTION.yaw(key,System.currentTimeMillis()));}
    public static void clear(){LunarGiftNativeModels.clear();INTERACTION.clear();}
}
