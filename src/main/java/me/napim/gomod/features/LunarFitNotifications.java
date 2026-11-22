package me.napim.gomod.features;

import com.google.gson.*;
import java.util.*;

/** Fresh friend gifts on Lunar's native notification surface. Callback names retain Fit compatibility. */
public final class LunarFitNotifications {
    private static UUID account;
    private static final LinkedHashMap<String,JsonObject> PENDING=new LinkedHashMap<>();
    private static final Map<String,List<Hit>> BUTTONS=new HashMap<>();
    private static boolean consumedLeft;
    private static final class Hit {
        final JsonObject request;final double x,y,right,bottom;final long expires;
        Hit(JsonObject request,double x,double y,double right,double bottom){this.request=request;this.x=x;this.y=y;this.right=right;this.bottom=bottom;expires=System.currentTimeMillis()+2000;}
        boolean contains(double px,double py){return px>=x&&px<right&&py>=y&&py<bottom;}
    }
    private LunarFitNotifications(){}
    public static void received(UUID owner,String key,String from,String name,String wire,long timestamp){received(owner,key,from,name,wire,timestamp,false);}
    public static void receivedEmote(UUID owner,String key,String from,String name,String wire,long timestamp){received(owner,key,from,name,wire,timestamp,true);}
    private static synchronized void received(UUID owner,String key,String from,String name,String wire,long timestamp,boolean emote){
        if(owner==null||!owner.equals(LunarFitSharing.account())||Math.abs(System.currentTimeMillis()-timestamp)>120000)return;
        if(!owner.equals(account)){PENDING.clear();BUTTONS.clear();consumedLeft=false;account=owner;}
        String notice=(emote?"emote:":"fit:")+key;if(PENDING.containsKey(notice))return;
        JsonObject row=new JsonObject();row.addProperty("account",owner.toString());row.addProperty("key",key);row.addProperty("notice",notice);row.addProperty("from",from);row.addProperty("name",name);row.addProperty("wire",wire);row.addProperty("emote",emote);row.addProperty("expires",System.currentTimeMillis()+30000);
        while(PENDING.size()>=8){String oldest=PENDING.keySet().iterator().next();PENDING.remove(oldest);BUTTONS.remove(oldest);}PENDING.put(notice,row);
        try{net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getMinecraft();if(mc!=null)mc.addScheduledTask(()->{if(owner.equals(LunarFitSharing.account()))me.napim.gomod.shareinv.ShareInvManager.playIncomingInvitePling();});}catch(Throwable unavailable){}
    }
    public static String poll(){
        LunarGiftPreviews.refreshAccount();
        UUID current=LunarFitSharing.account();JsonObject result=new JsonObject();result.addProperty("account",current==null?"":current.toString());JsonArray rows=new JsonArray();List<JsonObject> snapshot;
        synchronized(LunarFitNotifications.class){if(!Objects.equals(account,current)){PENDING.clear();BUTTONS.clear();consumedLeft=false;account=current;}snapshot=new ArrayList<>(PENDING.values());}
        // Do not hold this lock while entering a sharing store; receipts enter in the opposite order.
        for(JsonObject row:snapshot){
            boolean emote=row.get("emote").getAsBoolean();String from=row.get("from").getAsString(),wire=row.get("wire").getAsString(),notice=row.get("notice").getAsString();
            JsonObject info=emote?GomodEmoteSharing.describe(from,wire):LunarFitSharing.describe(from,wire);
            boolean pending=info.has("pending")?info.get("pending").getAsBoolean():info.get("canDecide").getAsBoolean();
            if(row.get("expires").getAsLong()<System.currentTimeMillis()||!pending||!ConfigManager.isGomodFriend(from)){
                synchronized(LunarFitNotifications.class){PENDING.remove(notice,row);BUTTONS.remove(notice);}continue;
            }
            JsonObject out=new JsonObject();for(String key:new String[]{"account","key","notice","from","name","emote"})out.add(key,row.get(key));
            String head=LunarFitSharing.senderHead(from);if(head!=null)out.addProperty("fromUuid",head);
            String kind=emote&&info.has("kind")?info.get("kind").getAsString():"fit";out.addProperty("kind",kind);out.add("canDecide",info.get("canDecide"));out.add("status",info.get("status"));
            String model=LunarGiftPreviews.modelId("NOTIFY",wire,from);JsonObject preview=LunarGiftPreviews.request(model,wire,!emote,from);if(preview!=null)out.add("preview",preview);rows.add(out);
        }
        if(!Objects.equals(current,LunarFitSharing.account()))rows=new JsonArray();result.add("items",rows);return result.toString();
    }
    public static synchronized void dismiss(String encoded){
        try{JsonObject row=GomodStudioSettings.request(encoded);if(account!=null&&account.toString().equals(row.get("account").getAsString())&&account.equals(LunarFitSharing.account())){
            String key=row.get("key").getAsString();boolean emote=row.has("emote")&&row.get("emote").getAsBoolean();String notice=(emote?"emote:":"fit:")+key;PENDING.remove(notice);BUTTONS.remove(notice);
        }}catch(Exception ignored){}
    }
    /** DOM rectangles use viewport ratios, independent of Minecraft's GUI scale or window size. */
    public static synchronized void bounds(String encoded){
        try{
            JsonObject row=GomodStudioSettings.request(encoded);UUID current=LunarFitSharing.account();
            if(current==null||!current.equals(account)||!current.toString().equals(row.get("account").getAsString()))return;
            String key=row.get("key").getAsString();boolean emote=row.get("emote").getAsBoolean();String notice=(emote?"emote:":"fit:")+key;
            JsonObject pending=PENDING.get(notice);if(pending==null||pending.get("expires").getAsLong()<System.currentTimeMillis())return;
            JsonArray buttons=row.getAsJsonArray("buttons");if(buttons.size()>2)return;
            List<Hit> hits=new ArrayList<>();
            for(JsonElement item:buttons){JsonObject button=item.getAsJsonObject();String action=button.get("action").getAsString();
                if(!action.equals("accept")&&!action.equals("decline"))continue;
                double x=button.get("x").getAsDouble(),y=button.get("y").getAsDouble(),right=button.get("right").getAsDouble(),bottom=button.get("bottom").getAsDouble();
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(right)||!Double.isFinite(bottom)||x<0||y<0||right>1||bottom>1||right<=x||bottom<=y)continue;
                JsonObject request=new JsonObject();request.addProperty("account",current.toString());request.addProperty("key",key);request.addProperty("emote",emote);request.addProperty("action",action);
                hits.add(new Hit(request,x,y,right,bottom));
            }
            BUTTONS.put(notice,hits);
            if(!hits.isEmpty())LunarConnectionDiagnostics.recordOnceAfterStart("gift.notification_bounds","dom","actions="+hits.size()+" coordinates=viewport-ratios");
        }catch(Exception invalid){/* A stale or hidden notification must never capture unrelated clicks. */}
    }
    public static boolean handleMouseEvent(int rawX,int rawY,int button,boolean down){
        if(button!=0)return false;
        net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getMinecraft();
        if(mc==null||mc.displayWidth<=0||mc.displayHeight<=0||org.lwjgl.input.Mouse.isGrabbed())return false;
        JsonObject selected=null;long now=System.currentTimeMillis();
        synchronized(LunarFitNotifications.class){
            if(!Objects.equals(account,LunarFitSharing.account())){BUTTONS.clear();consumedLeft=false;return false;}
            if(!down){boolean consumed=consumedLeft;consumedLeft=false;return consumed;}
            double x=(rawX+.5)/mc.displayWidth,y=1-(rawY+.5)/mc.displayHeight;
            for(Map.Entry<String,List<Hit>> entry:BUTTONS.entrySet()){
                JsonObject pending=PENDING.get(entry.getKey());if(pending==null||pending.get("expires").getAsLong()<now)continue;
                for(Hit hit:entry.getValue())if(hit.expires>=now&&hit.contains(x,y)){selected=hit.request;break;}
                if(selected!=null)break;
            }
            if(selected==null)return false;consumedLeft=true;
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("gift.notification_pointer",selected.get("action").getAsString(),"source=java-mouse hit=true");
        decide(selected.toString());return true;
    }
    public static String decide(String encoded){
        JsonObject result=new JsonObject();String action="unknown",kind="gift";try{JsonObject request=GomodStudioSettings.request(encoded);UUID current=LunarFitSharing.account();if(current==null||!current.toString().equals(request.get("account").getAsString()))throw new IllegalStateException("Account changed.");
            action=request.has("action")?request.get("action").getAsString():"unknown";boolean emote=request.has("emote")&&request.get("emote").getAsBoolean();kind=emote?"emote":"fit";
            if(emote){String answer=GomodEmoteSharing.decide(encoded);if(answer.startsWith("Could not"))throw new IllegalStateException(answer);}
            else if(!LunarFitSharing.decide(encoded))throw new IllegalStateException("This Fit could not be accepted. Open Fits to try again.");
            LunarConnectionDiagnostics.recordOnceAfterStart("gift.notification_decision",kind+":"+action,"result=accepted");
            result.addProperty("ok",true);dismiss(encoded);
        }catch(Exception failure){LunarConnectionDiagnostics.recordOnceAfterStart("gift.notification_decision",kind+":"+action,"result=failed exception="+failure.getClass().getSimpleName());result.addProperty("ok",false);result.addProperty("message",failure.getMessage()==null?"Could not save your decision.":failure.getMessage());}return result.toString();
    }
}
