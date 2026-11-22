package me.napim.gomod.gui;

import com.google.gson.*;
import me.napim.gomod.features.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import java.lang.reflect.*;
import java.util.*;

/** Displays the very same native model textures consumed by Lunar's G0 Locker component. */
final class LunarGiftNativeModels {
    private static final String ROOT="com.moonsworth.lunar.client.RROHOCCHCHCCCICORCCIRCIHHRCICC.RHOICRHRRHCICCRORRHHIRIIHHIIHI.";
    private static final Map<String,String> ACTIVE=new HashMap<>();
    private static final Set<String> VISIBLE=new HashSet<>();
    private static UUID owner;
    private static Object manager;
    private LunarGiftNativeModels(){}
    private static Object call(Object object,String name)throws Exception{Method method=object.getClass().getMethod(name);method.setAccessible(true);return method.invoke(object);}
    private static Method callback(Class<?> type,String name)throws ReflectiveOperationException{
        for(Method method:type.getMethods()){
            if(!Arrays.equals(method.getParameterTypes(),new Class<?>[]{String.class}))continue;
            for(java.lang.annotation.Annotation annotation:method.getAnnotations()){
                if(annotation.annotationType().getName().equals("com.moonsworth.webosr.javascript.CallbackJS")
                        &&name.equals(annotation.annotationType().getMethod("value").invoke(annotation)))return method;
            }
        }
        throw new NoSuchMethodException("Lunar model callback "+name);
    }
    private static Object models()throws Exception{
        Class<?> uiType=Class.forName(ROOT+"CIOHOIORRIOCHOHCIIIRRRCCOIOIHC");
        Object ui=null;
        for(Method method:uiType.getDeclaredMethods()){
            if(!Modifier.isStatic(method.getModifiers())||method.getParameterTypes().length!=0
                    ||!uiType.isAssignableFrom(method.getReturnType()))continue;
            method.setAccessible(true);ui=method.invoke(null);if(ui!=null)break;
        }
        if(ui==null)throw new NoSuchMethodException("Lunar UI singleton");
        for(Class<?> type=ui.getClass();type!=null;type=type.getSuperclass()){
            for(Field field:type.getDeclaredFields()){
                if(!isSceneManager(field.getType()))continue;
                field.setAccessible(true);Object value=field.get(ui);if(value==null)continue;
                LunarConnectionDiagnostics.recordOnceAfterStart("gift.preview_native_access","scene-manager",
                        "ui="+uiType.getName()+" manager="+field.getType().getName());
                return value;
            }
        }
        throw new NoSuchFieldException("Lunar UI scene manager");
    }
    private static boolean isSceneManager(Class<?> type){
        try{
            Method queue=type.getMethod("processQueue"),models=type.getMethod("CCOOICRHIRCRCOOOIHHIIRCHHIOROC");
            Method parser=type.getMethod("HIROOICIROHRCCOOCIHIRCIHHCHRCO");
            Method add=callback(parser.getReturnType(),"addModel"),read=callback(parser.getReturnType(),"updateModel");
            return queue.getParameterTypes().length==0&&queue.getReturnType()==void.class
                    &&models.getParameterTypes().length==0&&Map.class.isAssignableFrom(models.getReturnType())
                    &&add.getReturnType()==void.class&&read.getReturnType()==void.class;
        }catch(ReflectiveOperationException unavailable){return false;}
    }
    static void begin(){if(!Objects.equals(owner,LunarFitSharing.account())){clear();owner=LunarFitSharing.account();}VISIBLE.clear();}
    static void end(){for(String id:new ArrayList<>(ACTIVE.keySet()))if(!VISIBLE.contains(id))remove(id);}
    static void clear(){for(String id:new ArrayList<>(ACTIVE.keySet()))remove(id);VISIBLE.clear();manager=null;}
    private static void remove(String id){
        if(manager!=null)try{
            JsonArray ids=new JsonArray();ids.add(new JsonPrimitive(id));
            Object parser=call(manager,"HIROOICIROHRCCOOCIHIRCIHHCHRCO");
            callback(parser.getClass(),"removeModel").invoke(parser,ids.toString());
        }catch(Exception ignored){}
        ACTIVE.remove(id);LunarGiftPreviews.remove(id);
    }
    static boolean draw(Minecraft mc,String wire,boolean fit,String receiver,int x,int y,int width,int height,float yaw){
        if(mc==null||LunarFitSharing.account()==null)return false;
        String id=LunarGiftPreviews.modelId("CHAT",wire,receiver);VISIBLE.add(id);
        String stage="preview descriptor";
        try{
            JsonObject descriptor=LunarGiftPreviews.request(id,wire,fit,receiver);if(descriptor==null)return false;
            stage="native model manager";Object current=models();if(current!=manager){ACTIVE.clear();manager=current;}
            JsonObject position=new JsonObject();position.addProperty("width",192);position.addProperty("height",224);descriptor.add("position",position);
            String document=descriptor.toString();
            stage="native model registration";
            Map<?,?> cache=(Map<?,?>)call(manager,"CCOOICRHIRCRCOOOIHHIIRCHHIOROC");
            boolean missing=!cache.containsKey(id)&&manager.getClass().getMethod("OIRCICOHHIHCRRHRIOHHHICIRHIROR",String.class).invoke(manager,id)==null;
            if(missing||!document.equals(ACTIVE.get(id))){Object parser=call(manager,"HIROOICIROHRCCOOCIHIRCIHHCHRCO");callback(parser.getClass(),missing?"addModel":"updateModel").invoke(parser,"["+document+"]");ACTIVE.put(id,document);}
            stage="native model queue";call(manager,"processQueue");Object model=((Map<?,?>)call(manager,"CCOOICRHIRCRCOOOIHHIIRCHHIOROC")).get(id);if(model==null){LunarConnectionDiagnostics.recordOnceAfterStart("gift.chat_preview_waiting","queue","stage=queue model=false pending="+(manager.getClass().getMethod("OIRCICOHHIHCRRHRIOHHHICIRHIROR",String.class).invoke(manager,id)!=null));return false;}
            stage="native model rotation";
            model.getClass().getMethod("HRIIIOOCIRHHOORCORHCCHHRCIHCCC",float.class).invoke(model,yaw);
            stage="native model framebuffer";
            Object source=call(model,"RHHRRORORRRRCCCOOHHHCOOHHRCRRO");if(source==null){LunarConnectionDiagnostics.recordOnceAfterStart("gift.chat_preview_waiting","source","stage=framebuffer source=false");return false;}Object framebuffer=call(source,"OOIOCIRCHOICHRCCICIOCCCORHIOHR");if(framebuffer==null){LunarConnectionDiagnostics.recordOnceAfterStart("gift.chat_preview_waiting","framebuffer","stage=framebuffer target=false");return false;}
            int texture=((Number)call(framebuffer,"bridge$getFramebufferTexture")).intValue();if(texture<=0)return false;
            boolean topDown=(Boolean)call(framebuffer,"bridge$isTopDown");
            drawTexture(texture,topDown,x,y,width,height);
            LunarConnectionDiagnostics.recordOnceAfterStart("gift.chat_preview_ready","native-player","source=LunarModelManager texture=true alpha=source-over");
            return true;
        }catch(Throwable unavailable){String detail="stage="+stage+" exception="+unavailable.getClass().getSimpleName();if(unavailable.getMessage()!=null&&!unavailable.getMessage().isEmpty())detail+=" detail="+unavailable.getMessage();LunarConnectionDiagnostics.recordOnceAfterStart("gift.chat_preview_unavailable","native-model",detail);return false;}
    }
    static void drawTexture(int texture,boolean topDown,int x,int y,int width,int height){
        // Lunar renders source-over into a transparent target: its RGB is already alpha weighted.
        GlStateManager.enableTexture2D();GlStateManager.enableBlend();GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);GlStateManager.color(1,1,1,1);GlStateManager.bindTexture(texture);
        Tessellator tess=Tessellator.getInstance();WorldRenderer buffer=tess.getWorldRenderer();double top=topDown?0:1,bottom=topDown?1:0;
        try{buffer.begin(GL11.GL_QUADS,DefaultVertexFormats.POSITION_TEX);buffer.pos(x,y+height,0).tex(0,bottom).endVertex();buffer.pos(x+width,y+height,0).tex(1,bottom).endVertex();buffer.pos(x+width,y,0).tex(1,top).endVertex();buffer.pos(x,y,0).tex(0,top).endVertex();tess.draw();}
        finally{GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);}
    }
}
