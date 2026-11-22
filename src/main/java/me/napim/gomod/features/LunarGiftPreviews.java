package me.napim.gomod.features;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, isolated native mannequin documents. Never changes the active Fit or studio draft. */
public final class LunarGiftPreviews {
    private static volatile UUID owner;
    private static final Map<String,Preview> MODELS=new ConcurrentHashMap<>();
    private static final Map<UUID,String> PLAYERS=new ConcurrentHashMap<>();
    private static final Map<UUID,JsonObject> SKINS=new ConcurrentHashMap<>();
    private static final Map<UUID,Long> SKIN_REQUESTS=new ConcurrentHashMap<>();
    private static final ExecutorService IO=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"gomod-gift-skins");t.setDaemon(true);return t;});
    private static final ThreadLocal<Deque<String>> SCOPE=ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Preview {
        final UUID owner;final String id,wire,person;final boolean fit;final long started=System.nanoTime();
        volatile JsonObject model,payload;volatile long touched=System.currentTimeMillis(),retry;volatile boolean queued;
        Preview(UUID owner,String id,String wire,boolean fit,String person){this.owner=owner;this.id=id;this.wire=wire;this.fit=fit;this.person=person;}
    }
    private LunarGiftPreviews(){}
    private static JsonObject copy(JsonObject value){return new JsonParser().parse(value.toString()).getAsJsonObject();}
    public static String modelId(String surface,String wire,String person){
        return "GOMOD_GIFT_"+surface+"_"+UUID.nameUUIDFromBytes((LunarFitSharing.account()+":"+wire+":"+person.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
    }
    private static synchronized void account(){
        UUID current=LunarFitSharing.account();if(Objects.equals(current,owner))return;
        for(String id:MODELS.keySet())LunarFitAppearance.removePreview(id);
        MODELS.clear();PLAYERS.clear();owner=current;
    }
    public static void refreshAccount(){account();long now=System.currentTimeMillis();for(Preview old:MODELS.values())if(now-old.touched>45000)remove(old.id);}
    /** Safe from either callback or client thread; native descriptor construction runs on the client thread. */
    public static JsonObject request(String id,String wire,boolean fit,String person){
        refreshAccount();UUID current=owner;if(current==null||!id.startsWith("GOMOD_GIFT_"))return null;
        long now=System.currentTimeMillis();
        Preview preview=MODELS.get(id);
        if(preview==null){if(MODELS.size()>=64)remove(MODELS.keySet().iterator().next());Preview created=new Preview(current,id,wire,fit,person);Preview existing=MODELS.putIfAbsent(id,created);preview=existing==null?created:existing;}
        if(!current.equals(preview.owner)||!wire.equals(preview.wire)||!person.equals(preview.person))return null;
        preview.touched=now;Preview target=preview;Minecraft mc=Minecraft.getMinecraft();
        if(target.model==null&&!target.queued&&now>=target.retry&&mc!=null){target.queued=true;mc.addScheduledTask(()->{
            try{if(!target.owner.equals(LunarFitSharing.account())||MODELS.get(id)!=target)return;target.model=build(target);}
            catch(Throwable unavailable){target.retry=System.currentTimeMillis()+1500;LunarConnectionDiagnostics.recordOnceAfterStart("gift.preview_unavailable",target.fit?"fit":"emote","exception="+unavailable.getClass().getSimpleName());}
            finally{target.queued=false;}
        });}
        if(target.model==null)return null;
        JsonObject result=copy(target.model),skin=skin(person);
        boolean self=GomodSocialPresence.sessionPlayerName(mc).equalsIgnoreCase(person);
        result.addProperty("skin",self?"SELF":"WHITE");if(skin!=null)result.add("skinOverride",skin);return result;
    }
    private static JsonObject build(Preview preview)throws Exception {
        JsonObject result=new JsonObject(),appearance=new JsonObject();appearance.add("cosmetics",new JsonArray());appearance.add("badges",new JsonArray());
        result.addProperty("id",preview.id);result.addProperty("type","PLAYER");result.addProperty("display","DEFAULT");result.addProperty("zoom",.78);
        result.addProperty("renderContext","IN_COSMETIC_PLAYER_MODEL");result.addProperty("renderNametag",false);result.addProperty("showCompanions",true);result.addProperty("canRotateHorizontal",true);result.add("cosmetics",new JsonArray());
        if(preview.fit){JsonObject fit=LunarFitSharing.giftPreview(preview.wire,preview.id);if(fit==null)return null;if(fit.has("cosmetics"))result.add("cosmetics",fit.get("cosmetics"));}
        else{
            JsonObject payload=GomodEmoteSharing.giftPreview(preview.wire);preview.payload=payload;String kind=payload.get("kind").getAsString();
            if(kind.equals("speed")){result.addProperty("emoteId",payload.get("emoteId").getAsInt());result.addProperty("emoteOnHover",false);}
            if(kind.equals("cosmetic")){JsonObject item=new JsonObject();item.addProperty("id",payload.get("cosmeticId").getAsInt());item.add("metadata",new JsonObject());result.getAsJsonArray("cosmetics").add(item);appearance.getAsJsonArray("cosmetics").add(payload.get("value"));}
            LunarFitAppearance.registerPreview(preview.id,appearance);
        }
        return result;
    }
    private static JsonObject skin(String person){
        Minecraft mc=Minecraft.getMinecraft();if(mc==null)return null;String resolved=LunarFitSharing.senderHead(person);
        UUID id=null;try{id=resolved==null?null:UUID.fromString(resolved);}catch(Exception ignored){}
        if(GomodSocialPresence.sessionPlayerName(mc).equalsIgnoreCase(person))id=LunarFitSharing.account();if(id==null)return null;
        JsonObject cached=SKINS.get(id);if(cached!=null)return copy(cached);long now=System.currentTimeMillis();Long next=SKIN_REQUESTS.get(id);
        boolean claimed=next==null?SKIN_REQUESTS.putIfAbsent(id,now+60000)==null:next<=now&&SKIN_REQUESTS.replace(id,next,now+60000);
        if(claimed){UUID player=id;if(SKIN_REQUESTS.size()>256)for(UUID stale:SKIN_REQUESTS.keySet())if(!stale.equals(player)){SKIN_REQUESTS.remove(stale);break;}IO.execute(()->{
            try{GameProfile profile=mc.getSessionService().fillProfileProperties(new GameProfile(player,person),false);
                MinecraftProfileTexture texture=mc.getSessionService().getTextures(profile,false).get(MinecraftProfileTexture.Type.SKIN);if(texture==null)return;
                JsonObject value=new JsonObject();value.addProperty("url",texture.getUrl());value.addProperty("hash",texture.getHash());value.addProperty("type","slim".equals(texture.getMetadata("model"))?"slim":"default");
                if(SKINS.size()>=128)SKINS.remove(SKINS.keySet().iterator().next());SKINS.put(player,value);
            }catch(Throwable unavailable){/* Retain a neutral remote mannequin until the resolved skin is available. */}
        });}return null;
    }
    public static void remove(String id){MODELS.remove(id);PLAYERS.values().removeIf(id::equals);LunarFitAppearance.removePreview(id);}
    /** Native model render scope also associates its native dummy UUID with the gift's independent clock. */
    public static void beginModel(Object model){
        String id="";try{id=model instanceof String?(String)model:(String)model.getClass().getMethod("getId").invoke(model);
            if(MODELS.containsKey(id)&&!(model instanceof String)){UUID uuid=(UUID)model.getClass().getMethod("CIIICCIRRRIIOOOICICOCIORHCRHHR").invoke(model);if(uuid!=null)PLAYERS.put(uuid,id);}
        }catch(Exception ignored){}SCOPE.get().push(id);
    }
    public static void endModel(){Deque<String> stack=SCOPE.get();if(!stack.isEmpty())stack.pop();if(stack.isEmpty())SCOPE.remove();}
    public static boolean inGiftModel(){Deque<String> stack=SCOPE.get();return !stack.isEmpty()&&stack.peek().startsWith("GOMOD_GIFT_");}
    public static boolean isGiftPlayer(UUID player){return PLAYERS.containsKey(player);}
    public static GomodStudioSettings.View speed(UUID player,int emote){Preview preview=MODELS.get(PLAYERS.getOrDefault(player,""));return view(preview,"speed",emote);}
    public static GomodStudioSettings.View customView(){Deque<String> stack=SCOPE.get();return stack.isEmpty()?null:view(MODELS.get(stack.peek()),"custom",0);}
    private static GomodStudioSettings.View view(Preview preview,String kind,int emote){
        if(preview==null||!preview.owner.equals(LunarFitSharing.account())||preview.payload==null||!kind.equals(preview.payload.get("kind").getAsString())||kind.equals("speed")&&preview.payload.get("emoteId").getAsInt()!=emote)return null;
        return new GomodStudioSettings.View(preview.payload.getAsJsonObject("value"),preview.id,kind.equals("custom")?(System.nanoTime()-preview.started)/1e9:0);
    }
}
