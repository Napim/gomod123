package me.napim.gomod.features;

import com.google.gson.*;
import me.napim.gomod.util.MinecraftAccountUuid;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Versioned Fit attachments carried by the existing authenticated, friend-only DM transport. */
public final class LunarFitSharing {
    public static final String PREFIX = "[GOMOD_LUNAR_FIT_V1]";
    private static final int MAX_WIRE = 32768, MAX_ENTRIES = 256, MAX_SHARE_REQUEST_CHARS = 1_048_576;
    private static final Map<UUID,State> STATES = new LinkedHashMap<>();
    private static final Map<UUID,Job> JOBS = new HashMap<>();
    private static final Set<UUID> IMPORTING = new HashSet<>();
    private static final Map<String,String> SENDER_HEADS = new ConcurrentHashMap<>();
    private static final Map<String,Long> HEAD_LOOKUPS = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> daemon(r,"gomod-shared-fits-save"));
    private static final ExecutorService SENDER = Executors.newFixedThreadPool(4,r -> daemon(r,"gomod-shared-fits-send"));
    static {
        Runtime.getRuntime().addShutdownHook(daemon(() -> {
            WRITER.shutdown();SENDER.shutdown();
            try { WRITER.awaitTermination(2,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },"gomod-shared-fits-shutdown"));
    }
    private LunarFitSharing() { }
    private static Thread daemon(Runnable task,String name){Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;}
    private static final class State {
        final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
        final LinkedHashSet<String> seen=new LinkedHashSet<>();boolean unreadable;long nextNativeAttempt;
    }
    private static final class Entry {
        String key,from,name,wire;UUID fit;boolean imported,rejected,accepted,declined,viewed;long received;JsonObject preview;
    }
    private static final class Job {
        final String id=UUID.randomUUID().toString();String fit,message;List<String> targets=new ArrayList<>(),failedTargets=new ArrayList<>();int completed,delivered;boolean busy=true,ok;
    }
    public static UUID account() {
        return MinecraftAccountUuid.currentForFitSharing();
    }
    private static UUID uuid(String text){
        if(text==null)return null;String h=text.replace("-","");if(!h.matches("(?i)[a-f0-9]{32}"))return null;
        return UUID.fromString(h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20));
    }
    private static String clean(String text,int limit){
        if(text==null)return "";
        String value=text.replaceAll("\u00a7.","").replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]","").trim();
        return value.substring(0,Math.min(limit,value.length()));
    }
    private static List<String> shareTargets(JsonObject request){
        JsonArray rows=new JsonArray();if(request.has("targets")&&request.get("targets").isJsonArray())rows=request.getAsJsonArray("targets");else if(request.has("target"))rows.add(request.get("target"));
        if(rows.size()<1)throw new IllegalArgumentException("Choose at least one gomod friend.");
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();for(JsonElement row:rows){if(!row.isJsonPrimitive()||!row.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Choose gomod friends.");String friend=clean(row.getAsString(),32);if(friend.isEmpty()||!ConfigManager.isGomodFriend(friend))throw new IllegalArgumentException("Choose only current gomod friends.");String display=ConfigManager.getGomodFriendDisplayName(friend);unique.putIfAbsent(display.toLowerCase(Locale.ROOT),display);}
        return new ArrayList<>(unique.values());
    }
    private static Path file(UUID owner){return new File(ConfigManager.getGomodDataDirectory(),"lunar-shared-fits/"+owner+".json").toPath();}
    private static State state(UUID owner){
        State found=STATES.get(owner);if(found!=null)return found;
        State result=new State();
        try {
            Path path=file(owner);
            if(Files.exists(path)){
                if(Files.size(path)>8*1024*1024)throw new IllegalArgumentException("size");
                JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();
                if(root.get("version").getAsInt()!=1||!owner.toString().equals(root.get("account").getAsString()))throw new IllegalArgumentException("account/version");
                JsonArray entries=root.getAsJsonArray("received");if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("count");
                for(JsonElement element:entries){
                    JsonObject value=element.getAsJsonObject();Entry entry=new Entry();
                    entry.key=value.get("key").getAsString();entry.from=clean(value.get("from").getAsString(),32);
                    entry.name=clean(value.get("name").getAsString(),32);entry.fit=uuid(value.get("fit").getAsString());
                    entry.wire=value.get("wire").getAsString();parse(entry.wire);
                    entry.imported=value.get("imported").getAsBoolean();entry.received=value.get("received").getAsLong();
                    entry.rejected=value.has("rejected")&&value.get("rejected").getAsBoolean();
                    entry.accepted=entry.imported||value.has("accepted")&&value.get("accepted").getAsBoolean();
                    entry.declined=value.has("declined")&&value.get("declined").getAsBoolean();
                    entry.viewed=value.has("viewed")?value.get("viewed").getAsBoolean():entry.imported;
                    if(entry.fit==null||entry.from.isEmpty()||entry.key.length()>80)throw new IllegalArgumentException("entry");
                    result.entries.put(entry.key,entry);
                }
                JsonArray seen=root.getAsJsonArray("seen");if(seen.size()>2048)throw new IllegalArgumentException("seen");
                for(JsonElement value:seen){String key=value.getAsString();if(key.length()>80)throw new IllegalArgumentException("key");result.seen.add(key);}
            }
        }catch(Exception error){result=new State();result.unreadable=true;System.err.println("[gomod] Shared Fits could not be read; preserving the existing file: "+error.getClass().getSimpleName());}
        while(STATES.size()>=8){UUID oldest=STATES.keySet().iterator().next();STATES.remove(oldest);JOBS.remove(oldest);}
        STATES.put(owner,result);return result;
    }
    private static void save(UUID owner,State value){
        if(value.unreadable)return;
        JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("account",owner.toString());
        JsonArray entries=new JsonArray(),seen=new JsonArray();
        for(Entry entry:value.entries.values()){
            JsonObject item=new JsonObject();item.addProperty("key",entry.key);item.addProperty("from",entry.from);item.addProperty("name",entry.name);
            item.addProperty("fit",entry.fit.toString());item.addProperty("wire",entry.wire);item.addProperty("imported",entry.imported);item.addProperty("rejected",entry.rejected);item.addProperty("received",entry.received);entries.add(item);
            item.addProperty("accepted",entry.accepted);item.addProperty("declined",entry.declined);item.addProperty("viewed",entry.viewed);
        }
        for(String key:value.seen)seen.add(new JsonPrimitive(key));root.add("received",entries);root.add("seen",seen);
        byte[] bytes=(root.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        WRITER.execute(() -> {
            Path temporary=null;
            try {Path target=file(owner);Files.createDirectories(target.getParent());temporary=Files.createTempFile(target.getParent(),owner.toString(),".tmp");Files.write(temporary,bytes);
                try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            }catch(Exception error){System.err.println("[gomod] Could not save shared Fits: "+error.getClass().getSimpleName());}
            finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
        });
    }
    private static JsonObject parse(String message){
        if(message==null||!message.startsWith(PREFIX)||message.length()>MAX_WIRE)throw new IllegalArgumentException("Fit attachment");
        int depth=0;boolean quoted=false,escaped=false;
        for(int index=PREFIX.length();index<message.length();index++){
            char c=message.charAt(index);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}
            else if(c=='"')quoted=true;
            else if(c=='{'||c=='['){if(++depth>8)throw new IllegalArgumentException("Fit nesting");}
            else if(c=='}'||c==']')depth--;
        }
        JsonObject value=new JsonParser().parse(message.substring(PREFIX.length())).getAsJsonObject();
        if(value.get("version").getAsInt()!=1||uuid(value.get("id").getAsString())==null)throw new IllegalArgumentException("version/id");
        String data=value.get("outfit").getAsString();
        if(data.length()>22000||!data.matches("[A-Za-z0-9+/]+={0,2}"))throw new IllegalArgumentException("data");
        byte[] bytes=Base64.getDecoder().decode(data);if(bytes.length==0||bytes.length>16384)throw new IllegalArgumentException("size");
        if(clean(value.get("name").getAsString(),32).isEmpty())throw new IllegalArgumentException("name");
        JsonObject colors=value.getAsJsonObject("colors");
        for(Map.Entry<String,JsonElement> item:colors.entrySet()){
            String key=item.getKey();if(!key.equals("plus")&&!key.equals("logo"))throw new IllegalArgumentException("color key");
            JsonPrimitive number=item.getValue().getAsJsonPrimitive();if(!number.isNumber()||!number.getAsString().matches("-?[0-9]{1,8}"))throw new IllegalArgumentException("color");
            int rgb=number.getAsInt(),min=key.equals("plus")?-1:0,max=key.equals("plus")?0xFFFFFF:0x1000000;
            if(rgb<min||rgb>max)throw new IllegalArgumentException("color range");
        }
        if(value.has("appearance"))LunarFitAppearance.validateShared(value.getAsJsonObject("appearance"));
        return value;
    }
    /** Receipt records an invitation. Import requires a separate, explicit acceptance. */
    public static String messageText(String from,String text,long timestamp){
        if(text==null||!text.startsWith(PREFIX))return text;
        try{
            JsonObject payload=parse(text);String name=clean(payload.get("name").getAsString(),32),sender=clean(from,32);
            UUID owner=account();Minecraft mc=Minecraft.getMinecraft();String self=GomodSocialPresence.sessionPlayerName(mc);
            if(owner!=null&&!sender.equalsIgnoreCase(self)){
                String friend=FriendListScanner.matchFriendSender(sender);
                if(friend==null&&ConfigManager.isGomodFriend(sender))friend=ConfigManager.getGomodFriendDisplayName(sender);
                if(friend!=null){
                    boolean added=false;
                    synchronized(LunarFitSharing.class){
                        State value=state(owner);String key=sender.toLowerCase(Locale.ROOT)+":"+payload.get("id").getAsString();
                        if(!value.unreadable&&!value.seen.contains(key)){
                            if(value.entries.size()>=MAX_ENTRIES){String oldest=null;for(Entry entry:value.entries.values())if(entry.imported||entry.declined||entry.rejected){oldest=entry.key;break;}if(oldest==null)return "Your received Fits list is full.";value.entries.remove(oldest);}
                            Entry entry=new Entry();entry.key=key;entry.fit=UUID.randomUUID();entry.from=sender;entry.name=name;entry.wire=text;entry.received=timestamp>0?timestamp:System.currentTimeMillis();
                            value.entries.put(key,entry);value.seen.add(key);while(value.seen.size()>2048)value.seen.remove(value.seen.iterator().next());save(owner,value);added=true;
                        }
                    }
                    if(added){nativeReady();LunarFitNotifications.received(owner,key(sender,payload),sender,name,text,timestamp>0?timestamp:System.currentTimeMillis());} // Only accepted invitations may import.
                }
            }
            return "Shared Lunar Fit: "+name;
        }catch(Exception invalid){return "Received an invalid Lunar Fit. Ask your friend to send it again.";}
    }
    private static Class<?> nativeApi()throws Exception{return Class.forName("me.napim.gomod.features.LunarOutfitPersistence");}
    public static String attachment(String text){if(text==null||!text.startsWith(PREFIX))return "";try{parse(text);return text;}catch(Exception invalid){return "";}}
    private static String key(String from,JsonObject payload){return clean(from,32).toLowerCase(Locale.ROOT)+":"+payload.get("id").getAsString();}
    /** Read-only description for both sent history and received invitations. Never trusts a payload sender. */
    public static JsonObject describe(String from,String wire){
        JsonObject result=new JsonObject();
        try{
            JsonObject payload=parse(wire);result.addProperty("name",clean(payload.get("name").getAsString(),32));
            UUID owner=account();String id=key(from,payload);result.addProperty("key",id);
            synchronized(LunarFitSharing.class){Entry entry=owner==null?null:state(owner).entries.get(id);
                result.addProperty("status",entry==null?"Sent":entry.imported?"Added to Fits":entry.declined?"Declined":entry.rejected?"Fit unavailable":entry.accepted?"Accepted · Waiting for Fits":"Awaiting your decision");
                result.addProperty("canDecide",entry!=null&&!entry.imported&&!entry.accepted&&!entry.declined&&!entry.rejected);
            }
        }catch(Exception invalid){result.addProperty("status","Fit unavailable");result.addProperty("canDecide",false);}
        return result;
    }
    /** Account-bound consent is durable even while FullAccess is reconnecting. */
    public static boolean decide(String encoded){
        try{
            if(encoded==null||encoded.length()>512)return false;
            JsonObject request=new JsonParser().parse(encoded).getAsJsonObject();UUID owner=account();
            if(owner==null||!owner.equals(uuid(request.get("account").getAsString())))return false;
            String action=request.get("action").getAsString();if(!action.equals("accept")&&!action.equals("decline"))return false;
            synchronized(LunarFitSharing.class){State value=state(owner);Entry entry=value.entries.get(request.get("key").getAsString());
                if(value.unreadable||entry==null||entry.declined||entry.rejected||entry.imported||entry.accepted)return false;
                entry.accepted=action.equals("accept");entry.declined=!entry.accepted;entry.viewed=true;save(owner,value);
            }
            nativeReady();return true;
        }catch(Exception invalid){return false;}
    }
    public static void viewed(String encoded){
        try{
            if(encoded==null||encoded.length()>32768)return;
            JsonObject request=new JsonParser().parse(encoded).getAsJsonObject();UUID owner=account();
            if(owner==null||!owner.equals(uuid(request.get("account").getAsString())))return;
            synchronized(LunarFitSharing.class){State value=state(owner);boolean changed=false;
                for(JsonElement id:request.getAsJsonArray("keys")){Entry entry=value.entries.get(id.getAsString());if(entry!=null&&!entry.viewed){entry.viewed=true;changed=true;}}
                if(changed)save(owner,value);
            }
        }catch(Exception ignored){}
    }
    public static boolean preview(String wire,UUID previewId){
        try{JsonObject payload=parse(wire);boolean ok=(Boolean)nativeApi().getMethod("previewSharedFit",UUID.class,byte[].class)
                .invoke(null,previewId,Base64.getDecoder().decode(payload.get("outfit").getAsString()));
            if(ok)LunarFitAppearance.registerPreview("GOMOD_CHAT_FIT",payload.getAsJsonObject("appearance"));return ok;}
        catch(Throwable unavailable){return false;}
    }
    public static void clearPreview(UUID id){try{nativeApi().getMethod("clearSharedFitPreview",UUID.class).invoke(null,id);}catch(Throwable ignored){}}
    /** Same native model descriptor as the Locker invitation, without importing/equipping the Fit. */
    static JsonObject giftPreview(String wire,String model)throws Exception {
        JsonObject payload=parse(wire);UUID owner=account();if(owner==null)return null;
        JsonObject preview=(JsonObject)nativeApi().getMethod("sharedFitPreviewData",UUID.class,byte[].class)
                .invoke(null,owner,Base64.getDecoder().decode(payload.get("outfit").getAsString()));
        if(preview!=null)LunarFitAppearance.registerPreview(model,payload.getAsJsonObject("appearance"));return preview;
    }
    public static void nativeReady(){
        final UUID owner=account();Minecraft mc=Minecraft.getMinecraft();
        if(owner==null||mc==null||!GomodLogoColorSync.isFullAccessColorAvailable())return;
        synchronized(LunarFitSharing.class){if(!IMPORTING.add(owner))return;}
        mc.addScheduledTask(() -> {
            try{
                List<Entry> pending=new ArrayList<>();
                List<Entry> previews=new ArrayList<>();
                synchronized(LunarFitSharing.class){for(Entry entry:state(owner).entries.values())if(entry.preview==null&&!entry.rejected&&!entry.declined)previews.add(entry);}
                for(Entry entry:previews){
                    if(!owner.equals(account()))break;
                    try{JsonObject payload=parse(entry.wire);JsonObject preview=(JsonObject)nativeApi().getMethod("sharedFitPreviewData",UUID.class,byte[].class)
                            .invoke(null,owner,Base64.getDecoder().decode(payload.get("outfit").getAsString()));
                        LunarFitAppearance.registerPreview("GOMOD_RECEIVED_FIT_"+entry.key,payload.getAsJsonObject("appearance"));
                        synchronized(LunarFitSharing.class){entry.preview=preview;}
                    }catch(Exception unavailable){/* Missing catalog/model data must not reject a valid invitation. */}
                }
                synchronized(LunarFitSharing.class){for(Entry entry:state(owner).entries.values())if(entry.accepted&&!entry.imported&&!entry.rejected&&!entry.declined)pending.add(entry);}
                for(Entry entry:pending){
                    if(!owner.equals(account())||!GomodLogoColorSync.isFullAccessColorAvailable())break;
                    try{
                        JsonObject payload=parse(entry.wire);byte[] bytes=Base64.getDecoder().decode(payload.get("outfit").getAsString());
                        boolean imported=(Boolean)nativeApi().getMethod("importSharedFit",UUID.class,UUID.class,String.class,byte[].class,JsonObject.class)
                                .invoke(null,owner,entry.fit,entry.name,bytes,payload.getAsJsonObject("colors"));
                        if(!imported)break;
                        LunarFitAppearance.importShared(owner,entry.fit,payload.getAsJsonObject("appearance"));
                        synchronized(LunarFitSharing.class){entry.imported=true;entry.viewed=false;save(owner,state(owner));}
                    }catch(java.lang.reflect.InvocationTargetException invalid){
                        synchronized(LunarFitSharing.class){entry.rejected=true;save(owner,state(owner));}
                    }
                }
            }catch(Throwable unavailable){System.err.println("[gomod] Received Fit is waiting for native Fits: "+unavailable.getClass().getSimpleName());}
            finally{synchronized(LunarFitSharing.class){IMPORTING.remove(owner);}}
        });
    }
    /** Starts one explicit send. The WebOSR callback stays fast while HTTP runs in the background. */
    public static String send(String encoded){
        JsonObject answer=new JsonObject();
        try{
            if(encoded==null||encoded.length()>MAX_SHARE_REQUEST_CHARS)throw new IllegalArgumentException();
            JsonObject request=new JsonParser().parse(encoded).getAsJsonObject();final UUID owner=account();
            if(owner==null||!owner.equals(uuid(request.get("account").getAsString()))||!GomodLogoColorSync.isFullAccessColorAvailable())throw new IllegalArgumentException();
            final String fit=request.get("fit").getAsString(),name=clean(request.get("name").getAsString(),32);
            final List<String> targets=shareTargets(request);
            if(uuid(fit)==null||name.isEmpty())throw new IllegalArgumentException();
            final byte[] bytes=(byte[])nativeApi().getMethod("exportSharedFit",UUID.class,String.class).invoke(null,owner,fit);
            if(bytes==null)throw new IllegalArgumentException();
            JsonObject payload=new JsonObject();payload.addProperty("version",1);payload.addProperty("id",UUID.randomUUID().toString());payload.addProperty("name",name);
            payload.addProperty("outfit",Base64.getEncoder().encodeToString(bytes));payload.add("colors",LunarFitColors.exportShared(owner,fit));
            payload.add("appearance",LunarFitAppearance.exportShared(owner,uuid(fit)));
            final String wire=PREFIX+payload.toString();parse(wire);
            final Job job=new Job();job.fit=fit;job.targets=new ArrayList<>(targets);job.message="Sending Fit to "+targets.size()+" friends...";
            synchronized(LunarFitSharing.class){
                Job previous=JOBS.get(owner);if(previous!=null&&previous.busy){answer.addProperty("ok",false);answer.addProperty("message","A Fit is already being sent.");return answer.toString();}
                JOBS.put(owner,job);
            }
            final String sender=GomodSocialPresence.sessionPlayerName(Minecraft.getMinecraft());
            for(String display:targets)SENDER.execute(() -> {boolean delivered=false;try{
                    if(!owner.equals(account())||!ConfigManager.isGomodFriend(display))throw new IllegalStateException("Account/friend changed");
                    JsonObject body=new JsonObject();body.addProperty("type","friend_dm_send");body.addProperty("channel",ConfigManager.getGlobalChatChannel());
                    body.addProperty("sender",sender);body.addProperty("target",display);body.addProperty("message",wire);GomodSocialRelayKeys.attachDmTarget(body,display);
                    delivered=ScreenshotShareService.postRelayPayload(body,owner).ok;
                }catch(Exception failure){/* Continue with the rest of the explicitly selected friends. */}
                    synchronized(LunarFitSharing.class){job.completed++;if(delivered)job.delivered++;else job.failedTargets.add(display);job.message="Sent to "+job.delivered+" of "+targets.size()+" friends ("+job.completed+" attempted)…";if(job.completed==targets.size()){job.busy=false;job.ok=job.failedTargets.isEmpty();job.message=job.ok?"Fit sent to "+job.delivered+" friends.":"Fit sent to "+job.delivered+" of "+targets.size()+" friends. Retry the remaining "+job.failedTargets.size()+".";}}
            });
            answer.addProperty("ok",true);answer.addProperty("requestId",job.id);
        }catch(Exception invalid){answer.addProperty("ok",false);answer.addProperty("message","Select a saved Fit and a Gomod friend while FullAccess is connected.");}
        return answer.toString();
    }
    public static String senderHead(String from){
        String key=from.toLowerCase(Locale.ROOT),cached=SENDER_HEADS.get(key);if(cached!=null)return cached;
        long now=System.currentTimeMillis();Long next=HEAD_LOOKUPS.get(key);
        if((next==null||next<=now)&&(next==null?HEAD_LOOKUPS.putIfAbsent(key,now+120000)==null:HEAD_LOOKUPS.replace(key,next,now+120000))){
            MojangUsernameResolver.resolveProfileAsync(from,profile->{UUID id=profile==null?null:uuid(profile.id);if(id!=null)SENDER_HEADS.put(key,id.toString());});
        }return SENDER_HEADS.get(key);
    }
    public static JsonObject uiSnapshot(){
        JsonObject result=new JsonObject(),origins=new JsonObject(),heads=new JsonObject();JsonArray friends=new JsonArray(),pending=new JsonArray(),unseen=new JsonArray();UUID owner=account();
        result.addProperty("account",owner==null?"":owner.toString());result.addProperty("available",owner!=null&&GomodLogoColorSync.isFullAccessColorAvailable());
        List<String> names=new ArrayList<>(ConfigManager.getGomodFriends());Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
        for(String name:names)friends.add(new JsonPrimitive(name));
        synchronized(LunarFitSharing.class){if(owner!=null){
            for(Entry entry:state(owner).entries.values()){
                origins.addProperty(entry.fit.toString(),entry.from);
                String head=senderHead(entry.from);if(head!=null)heads.addProperty(entry.from,head);
                if(!entry.viewed&&!entry.declined&&!entry.rejected)unseen.add(new JsonPrimitive(entry.key));
                if(!entry.imported&&!entry.declined){JsonObject value=new JsonObject();value.addProperty("key",entry.key);value.addProperty("name",entry.name);value.addProperty("from",entry.from);if(head!=null)value.addProperty("fromUuid",head);value.addProperty("rejected",entry.rejected);value.addProperty("accepted",entry.accepted);if(entry.preview!=null)value.add("preview",entry.preview);pending.add(value);}
            }
            Job job=JOBS.get(owner);if(job!=null){JsonObject value=new JsonObject();value.addProperty("id",job.id);value.addProperty("fit",job.fit);value.add("targets",new Gson().toJsonTree(job.targets));value.add("failedTargets",new Gson().toJsonTree(job.failedTargets));value.addProperty("completed",job.completed);value.addProperty("delivered",job.delivered);value.addProperty("busy",job.busy);value.addProperty("ok",job.ok);value.addProperty("message",job.message);result.add("job",value);}
        }}
        result.add("friends",friends);result.add("origins",origins);result.add("heads",heads);result.add("pending",pending);result.add("unseen",unseen);result.addProperty("unread",unseen.size()>0);
        boolean retry=false;
        synchronized(LunarFitSharing.class){if(owner!=null&&System.currentTimeMillis()>=state(owner).nextNativeAttempt){
            for(Entry entry:state(owner).entries.values())if(!entry.rejected&&!entry.declined&&(entry.preview==null||entry.accepted&&!entry.imported)){retry=true;break;}
            if(retry)state(owner).nextNativeAttempt=System.currentTimeMillis()+5000;
        }}
        if(retry)nativeReady();return result;
    }
}
