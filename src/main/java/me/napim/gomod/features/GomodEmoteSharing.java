package me.napim.gomod.features;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Numeric emote attachments over the existing authenticated friend DM transport. */
public final class GomodEmoteSharing {
    public static final String PREFIX="[GOMOD_EMOTE_V1]";
    private static final Map<UUID,State> STATES=new LinkedHashMap<>();
    private static final Map<UUID,JsonObject> JOBS=new HashMap<>();
    private static final ExecutorService SEND=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"gomod-emote-send");t.setDaemon(true);return t;});
    private static final class State {JsonObject entries=new JsonObject();LinkedHashSet<String> seen=new LinkedHashSet<>();boolean unreadable;}
    private GomodEmoteSharing(){}
    private static String clean(String text){return text==null?"":text.replaceAll("\u00a7.","").replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]","").trim();}
    private static List<String> shareTargets(JsonObject request){
        JsonArray rows=new JsonArray();if(request.has("targets")&&request.get("targets").isJsonArray())rows=request.getAsJsonArray("targets");else if(request.has("target"))rows.add(request.get("target"));
        if(rows.size()<1)throw new IllegalArgumentException("Choose at least one gomod friend.");
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();for(JsonElement row:rows){if(!row.isJsonPrimitive()||!row.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Choose gomod friends.");String friend=clean(row.getAsString());if(friend.length()>32)friend=friend.substring(0,32);if(friend.isEmpty()||!ConfigManager.isGomodFriend(friend))throw new IllegalArgumentException("Choose only current gomod friends.");String display=ConfigManager.getGomodFriendDisplayName(friend);unique.putIfAbsent(display.toLowerCase(Locale.ROOT),display);}
        return new ArrayList<>(unique.values());
    }
    private static String uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("Emote ID");return value;}
    private static JsonObject copy(JsonObject value){return new JsonParser().parse(value.toString()).getAsJsonObject();}
    private static JsonObject parse(String wire){
        if(wire==null||!wire.startsWith(PREFIX)||wire.length()>65536)throw new IllegalArgumentException("Emote attachment");
        JsonObject raw=GomodStudioSettings.request(wire.substring(PREFIX.length())),result=new JsonObject();
        if(raw.get("version").getAsInt()!=1)throw new IllegalArgumentException("Emote version");result.addProperty("version",1);result.addProperty("id",uuid(raw.get("id").getAsString()));
        String kind=raw.get("kind").getAsString(),name=clean(raw.get("name").getAsString());if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("Emote name");result.addProperty("name",name);result.addProperty("kind",kind);
        if(kind.equals("speed")){
            double id=EmoteSpeedProfile.number(raw,"emoteId",1,Integer.MAX_VALUE);if(id!=Math.floor(id))throw new IllegalArgumentException("Native emote ID");result.addProperty("emoteId",(int)id);result.add("value",new EmoteSpeedProfile(raw.getAsJsonObject("value")).json());
        }else if(kind.equals("custom"))result.add("value",new CustomEmoteClip(raw.getAsJsonObject("value")).json());
        else if(kind.equals("cosmetic")){double id=EmoteSpeedProfile.number(raw,"cosmeticId",1,Integer.MAX_VALUE);if(id!=Math.floor(id))throw new IllegalArgumentException("Cosmetic ID");result.addProperty("cosmeticId",(long)id);result.add("value",GomodCosmeticAppearance.validateSharedValue((long)id,raw.getAsJsonObject("value")));}
        else throw new IllegalArgumentException("Emote kind");
        return result;
    }
    public static String attachment(String wire){try{return PREFIX+parse(wire).toString();}catch(Exception invalid){return "";}}
    static JsonObject giftPreview(String wire){return parse(wire);}
    private static Path file(UUID owner){return ConfigManager.getGomodDataDirectory().toPath().resolve("lunar-shared-emotes/"+owner+".json");}
    private static State state(UUID owner){
        State known=STATES.get(owner);if(known!=null)return known;State value=new State();
        try{Path path=file(owner);if(Files.exists(path)){
            if(Files.size(path)>9*1024*1024)throw new IllegalArgumentException("Emote inbox size");JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("version").getAsInt()!=1||!owner.toString().equals(root.get("account").getAsString()))throw new IllegalArgumentException("Emote inbox account");value.entries=root.getAsJsonObject("entries");if(value.entries.entrySet().size()>128)throw new IllegalArgumentException("Emote inbox count");
            for(Map.Entry<String,JsonElement> row:value.entries.entrySet()){JsonObject entry=row.getValue().getAsJsonObject();JsonObject payload=parse(entry.get("wire").getAsString());if(!row.getKey().equals(key(entry.get("from").getAsString(),payload)))throw new IllegalArgumentException("Emote inbox key");uuid(entry.get("localId").getAsString());}
            JsonArray seen=root.getAsJsonArray("seen");if(seen.size()>512)throw new IllegalArgumentException("Emote seen count");for(JsonElement row:seen)value.seen.add(row.getAsString());
        }}catch(Exception invalid){value=new State();value.unreadable=true;}
        while(STATES.size()>=8)STATES.remove(STATES.keySet().iterator().next());STATES.put(owner,value);return value;
    }
    private static void save(UUID owner,State value)throws Exception{
        if(value.unreadable)throw new IllegalStateException("The emote inbox could not be read; its file was preserved.");JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("account",owner.toString());root.add("entries",value.entries);JsonArray seen=new JsonArray();for(String key:value.seen)seen.add(new JsonPrimitive(key));root.add("seen",seen);
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>9*1024*1024)throw new IllegalStateException("Your emote inbox is full.");Path path=file(owner);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"emotes-",".tmp");
        try{Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
    }
    private static String key(String from,JsonObject payload){return from.toLowerCase(Locale.ROOT)+":"+payload.get("id").getAsString();}
    private static boolean flag(JsonObject entry,String field){return entry.has(field)&&entry.get(field).getAsBoolean();}
    public static synchronized String messageText(String from,String text,long timestamp){
        if(text==null||!text.startsWith(PREFIX))return text;
        try{JsonObject payload=parse(text);String sender=clean(from);UUID owner=LunarFitSharing.account();String self=GomodSocialPresence.sessionPlayerName(Minecraft.getMinecraft());
            if(owner!=null&&!sender.equalsIgnoreCase(self)&&ConfigManager.isGomodFriend(sender)){
                State value=state(owner);String key=key(sender,payload);if(!value.unreadable&&!value.seen.contains(key)){
                    if(value.entries.entrySet().size()>=128)throw new IllegalStateException("Your received emotes list is full.");JsonObject entry=new JsonObject();entry.addProperty("from",sender);entry.addProperty("wire",PREFIX+payload);entry.addProperty("localId",UUID.nameUUIDFromBytes((owner+":"+key).getBytes(StandardCharsets.UTF_8)).toString());entry.addProperty("received",timestamp);value.entries.add(key,entry);value.seen.add(key);while(value.seen.size()>512)value.seen.remove(value.seen.iterator().next());
                    try{save(owner,value);}catch(Exception failure){value.entries.remove(key);value.seen.remove(key);throw failure;}
                    LunarFitNotifications.receivedEmote(owner,key,sender,payload.get("name").getAsString(),PREFIX+payload,timestamp);
                }
            }
            String kind=payload.get("kind").getAsString();return (kind.equals("cosmetic")?"Shared custom Lunar cosmetic: ":kind.equals("custom")?"Shared created emote: ":"Shared edited emote: ")+payload.get("name").getAsString();
        }catch(Exception invalid){return "Could not receive this emote: "+(invalid instanceof IllegalStateException?invalid.getMessage():"invalid attachment.");}
    }
    private static JsonObject description(String key,JsonObject entry){
        JsonObject payload=parse(entry.get("wire").getAsString()),row=new JsonObject();String kind=payload.get("kind").getAsString();boolean cosmetic=kind.equals("cosmetic"),accepted=flag(entry,"accepted"),declined=flag(entry,"declined"),connected=GomodLogoColorSync.isFullAccessColorAvailable(),fitLoaded=cosmetic&&LunarFitAppearance.active();row.addProperty("key",key);row.addProperty("name",payload.get("name").getAsString());row.addProperty("from",entry.get("from").getAsString());row.addProperty("kind",kind);row.addProperty("id",payload.has("emoteId")?payload.get("emoteId").getAsString():cosmetic?payload.get("cosmeticId").getAsString():entry.get("localId").getAsString());row.addProperty("localId",entry.get("localId").getAsString());row.addProperty("accepted",accepted);row.addProperty("canDecide",!accepted&&!declined&&(!cosmetic||connected&&fitLoaded));row.addProperty("emote",true);
        row.addProperty("pending",!accepted&&!declined);
        row.addProperty("status",declined?"Declined":accepted?cosmetic?"Applied to your current Fit":"Saved in My emotes":cosmetic?!connected?"Connect Lunar FullAccess to accept this style.":!fitLoaded?"Load a Fit to accept this style.":"Accept to apply this style to your current Fit.":"Accept to save in My emotes.");return row;
    }
    public static synchronized JsonObject describe(String from,String wire){
        try{JsonObject payload=parse(wire);UUID owner=LunarFitSharing.account();String key=key(clean(from),payload);JsonElement entry=owner==null?null:state(owner).entries.get(key);if(entry!=null)return description(key,entry.getAsJsonObject());JsonObject row=new JsonObject();row.addProperty("name",payload.get("name").getAsString());row.addProperty("kind",payload.get("kind").getAsString());row.addProperty("status","Sent");row.addProperty("canDecide",false);row.addProperty("emote",true);return row;
        }catch(Exception invalid){JsonObject row=new JsonObject();row.addProperty("name","Emote unavailable");row.addProperty("status","Invalid attachment");row.addProperty("canDecide",false);return row;}
    }
    private static UUID owned(JsonObject request){UUID owner=LunarFitSharing.account();if(owner==null||!owner.toString().replace("-","").equals(request.get("account").getAsString().replace("-","").toLowerCase(Locale.ROOT)))throw new IllegalStateException("Account changed");return owner;}
    public static synchronized String decide(String encoded){
        try{JsonObject request=GomodStudioSettings.request(encoded);UUID owner=owned(request);State value=state(owner);String key=request.get("key").getAsString();JsonObject entry=value.entries.getAsJsonObject(key);if(entry==null||flag(entry,"accepted")||flag(entry,"declined"))throw new IllegalStateException("This invitation has already been handled.");String action=request.get("action").getAsString();if(!action.equals("accept")&&!action.equals("decline"))throw new IllegalArgumentException("Decision");
            JsonObject payload=parse(entry.get("wire").getAsString());String kind=payload.get("kind").getAsString();if(action.equals("accept")&&kind.equals("custom"))GomodStudioSettings.importEmote(owner,"custom",entry.get("localId").getAsString(),payload.getAsJsonObject("value"),false);else if(action.equals("accept")&&kind.equals("cosmetic"))GomodCosmeticAppearance.importSharedValue(payload.get("cosmeticId").getAsLong(),payload.getAsJsonObject("value"));
            String flag=action.equals("accept")?"accepted":"declined";entry.addProperty(flag,true);try{save(owner,value);}catch(Exception failure){entry.remove(flag);throw failure;}return action.equals("accept")?(kind.equals("cosmetic")?"Applied to your current Fit.":"Saved in My emotes."):"Declined.";
        }catch(Exception failure){return "Could not save this decision: "+failure.getMessage();}
    }
    public static synchronized String openPreview(String encoded){JsonObject request=GomodStudioSettings.request(encoded);UUID owner=owned(request);JsonObject entry=state(owner).entries.getAsJsonObject(request.get("key").getAsString());if(entry==null||flag(entry,"declined"))throw new IllegalStateException("Emote unavailable");JsonObject payload=parse(entry.get("wire").getAsString());String kind=payload.get("kind").getAsString();return GomodStudioSettings.openSharedEmote(encoded,kind,kind.equals("speed")?payload.get("emoteId").getAsString():entry.get("localId").getAsString(),payload.getAsJsonObject("value"));}
    public static synchronized String apply(String encoded){
        try{GomodStudioSettings.requireConnected();JsonObject request=GomodStudioSettings.request(encoded);UUID owner=owned(request);JsonObject entry=state(owner).entries.getAsJsonObject(request.get("key").getAsString());if(entry==null||!flag(entry,"accepted"))throw new IllegalStateException("Accept this emote first.");JsonObject payload=parse(entry.get("wire").getAsString());if(!payload.get("kind").getAsString().equals("speed"))throw new IllegalArgumentException("Native emote required");GomodStudioSettings.importEmote(owner,"speed",payload.get("emoteId").getAsString(),payload.getAsJsonObject("value"),true);return "Applied. This emote now uses the received timing.";}catch(Exception failure){return "Could not apply: "+failure.getMessage();}
    }
    public static synchronized String snapshot(){
        UUID owner=LunarFitSharing.account();JsonObject result=new JsonObject(),origins=new JsonObject();JsonArray friends=new JsonArray(),received=new JsonArray();result.addProperty("account",owner==null?"":owner.toString());for(String friend:ConfigManager.getGomodFriends())friends.add(new JsonPrimitive(friend));result.add("friends",friends);
        if(owner!=null){State state=state(owner);result.addProperty("readOnly",state.unreadable);for(Map.Entry<String,JsonElement> row:state.entries.entrySet()){JsonObject entry=row.getValue().getAsJsonObject();if(flag(entry,"declined"))continue;JsonObject description=description(row.getKey(),entry);if(description.get("kind").getAsString().equals("cosmetic"))continue;if(flag(entry,"accepted")&&description.get("kind").getAsString().equals("custom"))origins.addProperty(entry.get("localId").getAsString(),entry.get("from").getAsString());else received.add(description);}JsonObject job=JOBS.get(owner);if(job!=null)result.add("job",copy(job));}
        result.add("received",received);result.add("origins",origins);return result.toString();
    }
    public static synchronized String send(String encoded){
        JsonObject answer=new JsonObject();try{JsonObject request=GomodStudioSettings.request(encoded);UUID owner=owned(request);List<String> targets=shareTargets(request);String kind=request.get("kind").getAsString(),id=request.get("id").getAsString(),name=clean(request.get("name").getAsString());if(name.isEmpty()||name.length()>40)throw new IllegalArgumentException("Choose a shorter item name.");
            String label=kind.equals("cosmetic")?"Cosmetic":"Emote";JsonObject payload=new JsonObject();payload.addProperty("version",1);payload.addProperty("id",UUID.randomUUID().toString());payload.addProperty("name",name);payload.addProperty("kind",kind);if(kind.equals("speed"))payload.addProperty("emoteId",Integer.parseInt(id));if(kind.equals("cosmetic")){long cosmeticId=Long.parseLong(id);payload.addProperty("cosmeticId",cosmeticId);payload.add("value",GomodCosmeticAppearance.exportSharedValue(cosmeticId));}else payload.add("value",GomodStudioSettings.exportEmote(owner,kind,id));String wire=PREFIX+parse(PREFIX+payload).toString();
            JsonObject previous=JOBS.get(owner);if(previous!=null&&flag(previous,"busy"))throw new IllegalStateException(label+" is already being sent.");JsonObject job=new JsonObject();job.addProperty("id",UUID.randomUUID().toString());job.addProperty("busy",true);job.addProperty("ok",false);job.addProperty("targetCount",targets.size());job.addProperty("completed",0);job.addProperty("delivered",0);job.add("failedTargets",new JsonArray());job.addProperty("message","Sending "+label.toLowerCase(Locale.ROOT)+" to "+targets.size()+" friends...");JOBS.put(owner,job);
            String sender=GomodSocialPresence.sessionPlayerName(Minecraft.getMinecraft());for(String display:targets)SEND.execute(()->{boolean sent=false;try{if(!owner.equals(LunarFitSharing.account())||!ConfigManager.isGomodFriend(display))throw new IllegalStateException("Account/friend changed");JsonObject body=new JsonObject();body.addProperty("type","friend_dm_send");body.addProperty("channel",ConfigManager.getGlobalChatChannel());body.addProperty("sender",sender);body.addProperty("target",display);body.addProperty("message",wire);GomodSocialRelayKeys.attachDmTarget(body,display);sent=ScreenshotShareService.postRelayPayload(body,owner).ok;}catch(Exception ignored){}synchronized(GomodEmoteSharing.class){int completed=job.get("completed").getAsInt()+1,delivered=job.get("delivered").getAsInt()+(sent?1:0);job.addProperty("completed",completed);job.addProperty("delivered",delivered);JsonArray failures=job.getAsJsonArray("failedTargets");if(!sent)failures.add(new JsonPrimitive(display));job.addProperty("message","Sent to "+delivered+" of "+targets.size()+" friends ("+completed+" attempted)…");if(completed==targets.size()){job.addProperty("busy",false);job.addProperty("ok",failures.size()==0);job.addProperty("message",failures.size()==0?label+" sent to "+delivered+" friends.":label+" sent to "+delivered+" of "+targets.size()+" friends. Retry the remaining "+failures.size()+".");}}});
            answer.addProperty("ok",true);answer.addProperty("requestId",job.get("id").getAsString());
        }catch(Exception failure){answer.addProperty("ok",false);answer.addProperty("message",failure.getMessage()==null?"Save the emote and choose a gomod friend.":failure.getMessage());}return answer.toString();
    }
}
