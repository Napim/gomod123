package me.napim.gomod.features;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.*;
import net.minecraft.entity.Entity;
import java.util.*;
import java.lang.reflect.Method;

/** Local player pose playback and isolated native SELF mannequin previews. */
public final class GomodCustomEmotes {
    private static UUID playingOwner;private static Entity playingEntity;private static CustomEmoteClip playing;private static long started;private static String playingId,playingToken;
    private static String previewToken,previewDocument;private static CustomEmoteClip preview;
    private static final Map<ModelBiped,float[][]> ORIGINAL=Collections.synchronizedMap(new WeakHashMap<ModelBiped,float[][]>());
    private static final ClassValue<Optional<Method>> DUMMY_PLAYER_TYPE_METHOD=new ClassValue<Optional<Method>>(){
        @Override protected Optional<Method> computeValue(Class<?> type){
            try{return Optional.of(type.getMethod("getDummyPlayerType"));}
            catch(Exception unavailable){return Optional.empty();}
        }
    };
    private GomodCustomEmotes(){}
    public static String play(String encoded){
        try{JsonObject request=GomodStudioSettings.request(encoded);String account=request.get("account").getAsString();CustomEmoteClip clip=new CustomEmoteClip(new JsonParser().parse(GomodStudioSettings.customDocument(account,request.get("id").getAsString())).getAsJsonObject());
            UUID owner=LunarFitSharing.account();if(owner==null||!owner.toString().equals(account))throw new IllegalStateException("Account changed");
            Minecraft mc=Minecraft.getMinecraft();if(mc==null||mc.thePlayer==null)throw new IllegalStateException("Join a world to play your emote.");
            Entity target=mc.thePlayer;String id=request.get("id").getAsString();mc.addScheduledTask(()->{if(!owner.equals(LunarFitSharing.account())||mc.thePlayer!=target)return;LunarPeerEmoteControl.stop(owner);synchronized(GomodCustomEmotes.class){playingOwner=owner;playingEntity=target;playing=clip;playingId=id;playingToken=UUID.randomUUID().toString();started=System.nanoTime();}GomodStudioSharing.changed();});return "Playing on your player. Move or sneak to stop.";
        }catch(Exception error){return error.getMessage()==null?"Could not play this emote.":error.getMessage();}
    }
    public static synchronized void stop(){boolean active=playing!=null;playing=null;playingOwner=null;playingEntity=null;playingToken=null;playingId=null;if(active)GomodStudioSharing.changed();}
    public static synchronized void tick(){
        if(playing==null)return;Minecraft mc=Minecraft.getMinecraft();
        if(!Objects.equals(LunarFitSharing.account(),playingOwner)||mc==null||mc.thePlayer!=playingEntity||playingEntity.isSneaking()
                ||Math.abs(playingEntity.posX-playingEntity.prevPosX)+Math.abs(playingEntity.posY-playingEntity.prevPosY)+Math.abs(playingEntity.posZ-playingEntity.prevPosZ)>.035
                ||!playing.loop&&(System.nanoTime()-started)/1e9>playing.duration)stop();
    }
    public static synchronized JsonObject sharingState(){tick();if(playing==null)return null;JsonObject row=new JsonObject();row.addProperty("id",playingId);row.addProperty("token",playingToken);row.add("clip",playing.json());row.addProperty("position",Math.min(86400,(System.nanoTime()-started)/1e9));return row;}
    private static ModelRenderer[] parts(ModelBiped model){
        if(model instanceof ModelPlayer){ModelPlayer player=(ModelPlayer)model;return new ModelRenderer[]{model.bipedHead,model.bipedBody,model.bipedRightArm,model.bipedLeftArm,model.bipedRightLeg,model.bipedLeftLeg,model.bipedHeadwear,player.bipedBodyWear,player.bipedRightArmwear,player.bipedLeftArmwear,player.bipedRightLegwear,player.bipedLeftLegwear};}
        return new ModelRenderer[]{model.bipedHead,model.bipedBody,model.bipedRightArm,model.bipedLeftArm,model.bipedRightLeg,model.bipedLeftLeg,model.bipedHeadwear};
    }
    public static void restore(ModelBiped model){float[][] saved=ORIGINAL.remove(model);if(saved==null)return;ModelRenderer[] parts=parts(model);for(int i=0;i<parts.length;i++)set(parts[i],saved[i]);}
    private static void set(ModelRenderer part,float[] value){part.rotateAngleX=value[0];part.rotateAngleY=value[1];part.rotateAngleZ=value[2];}
    private static boolean display(Entity player){
        if(player==null)return false;
        Method method=DUMMY_PLAYER_TYPE_METHOD.get(player.getClass()).orElse(null);
        if(method==null)return false;
        try{Object type=method.invoke(player);return type instanceof Enum<?> && ("SELF".equals(((Enum<?>)type).name())||"MANNEQUIN".equals(((Enum<?>)type).name()));}catch(Exception ignored){return false;}
    }
    public static synchronized void pose(ModelBiped model,Entity entity){
        try{
            if(entity==null||!ConfigManager.isLunarTweaksEnabled())return;CustomEmoteClip clip=null;double time=0;boolean looping=true;
            if(display(entity)){
                GomodStudioSettings.View view=LunarGiftPreviews.inGiftModel()?LunarGiftPreviews.customView():GomodStudioSettings.customView();if(view==null)return;
                String document=view.value.toString();if(!view.token.equals(previewToken)||!document.equals(previewDocument)){preview=new CustomEmoteClip(view.value);previewToken=view.token;previewDocument=document;}
                clip=preview;time=view.position;looping=view.playing&&clip.loop;
            }else{
                Minecraft mc=Minecraft.getMinecraft();tick();
                if(mc!=null&&entity==mc.thePlayer){if(playing==null)return;clip=playing;looping=clip.loop;time=(System.nanoTime()-started)/1e9;}
                else{GomodStudioSharing.Custom shared=GomodStudioSharing.customEmote(entity.getUniqueID());if(shared==null)return;clip=shared.clip;looping=clip.loop;time=shared.positionSeconds();}
            }
            if(clip==null)return;ModelRenderer[] parts=parts(model);
            if(!ORIGINAL.containsKey(model)){float[][] original=new float[parts.length][3];for(int i=0;i<parts.length;i++)original[i]=new float[]{parts[i].rotateAngleX,parts[i].rotateAngleY,parts[i].rotateAngleZ};ORIGINAL.put(model,original);}
            float[] pose=clip.sample(time,looping);for(int i=0;i<6;i++)set(parts[i],new float[]{pose[i*3],pose[i*3+1],pose[i*3+2]});
            ModelBiped.copyModelAngles(model.bipedHead,model.bipedHeadwear);
            if(model instanceof ModelPlayer){ModelPlayer player=(ModelPlayer)model;ModelBiped.copyModelAngles(model.bipedBody,player.bipedBodyWear);ModelBiped.copyModelAngles(model.bipedRightArm,player.bipedRightArmwear);ModelBiped.copyModelAngles(model.bipedLeftArm,player.bipedLeftArmwear);ModelBiped.copyModelAngles(model.bipedRightLeg,player.bipedRightLegwear);ModelBiped.copyModelAngles(model.bipedLeftLeg,player.bipedLeftLegwear);}
        }catch(Throwable unavailable){/* Model/preview availability changes during account/world transitions. */}
    }
}
