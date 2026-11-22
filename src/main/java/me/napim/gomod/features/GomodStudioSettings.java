package me.napim.gomod.features;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local, account-scoped Badge / Emote editor documents. Drafts require an owning panel token. */
public final class GomodStudioSettings {
    private static UUID owner;private static JsonObject data;private static boolean unreadable;
    private static Draft draft;
    private static long openedGeneration;
    private GomodStudioSettings(){}
    public static void requireConnected(){if(!GomodLogoColorSync.isFullAccessColorAvailable())throw new IllegalStateException("Connect Lunar FullAccess to edit emotes or cosmetic colors.");}
    private static final class Draft {
        final UUID owner;final String kind,id,token;final long fitScope;JsonObject value;long sequence,seek;
        double position;boolean playing,receivedPreview;long controlAt=System.nanoTime();
        Draft(UUID owner,String kind,String id,JsonObject value){this.owner=owner;this.kind=kind;this.id=id;this.value=value;fitScope=LunarFitAppearance.scope();token=UUID.randomUUID().toString();}
    }
    public static final class View {
        public final JsonObject value;public final String token;public final long seek;public final double position;public final boolean playing;
        View(Draft value){this.value=value.value;token=value.token;seek=value.seek;playing=value.playing;position=value.kind.equals("custom")&&playing?value.position+(System.nanoTime()-value.controlAt)/1e9:value.position;}
        View(JsonObject value,String token,double position){this.value=value;this.token=token;this.position=position;seek=0;playing=true;}
    }
    private static JsonObject empty(UUID account){JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("account",account==null?"":account.toString());for(String kind:new String[]{"badge","speed","custom"})root.add(kind,new JsonObject());return root;}
    private static Path file(UUID account){return ConfigManager.getGomodDataDirectory().toPath().resolve("lunar-studio/"+account+".json");}
    private static void load(){
        UUID current=LunarFitSharing.account();if(Objects.equals(current,owner)&&data!=null)return;owner=current;data=empty(owner);draft=null;unreadable=false;
        if(owner==null)return;
        try{Path path=file(owner);if(!Files.exists(path))return;if(Files.size(path)>1048576)throw new IllegalArgumentException("Editor file too large");
            JsonObject loaded=new JsonParser().parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();
            if(loaded.get("version").getAsInt()!=1||!owner.toString().equals(loaded.get("account").getAsString()))throw new IllegalArgumentException("Account/version");
            for(String kind:new String[]{"badge","speed","custom"}){JsonObject values=loaded.getAsJsonObject(kind);if(values==null||values.entrySet().size()>(kind.equals("custom")?32:256))throw new IllegalArgumentException("Editor count");for(Map.Entry<String,JsonElement> row:values.entrySet()){id(kind,row.getKey());normalize(kind,row.getValue().getAsJsonObject());}}
            data=loaded;
        }catch(Exception failure){unreadable=true;System.err.println("[gomod] Preserving unreadable Badge/Emote studio file: "+failure.getClass().getSimpleName());}
    }
    private static String kind(String value){if(!value.equals("badge")&&!value.equals("speed")&&!value.equals("custom"))throw new IllegalArgumentException("Editor kind");return value;}
    private static String id(String kind,String value){
        if(kind.equals("custom")){if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException("Emote ID");}
        else{if(!value.matches("[1-9][0-9]{0,9}")||Long.parseLong(value)>Integer.MAX_VALUE)throw new IllegalArgumentException("Item ID");}return value;
    }
    private static JsonObject normalize(String kind,JsonObject value){
        if(kind.equals("speed"))return new EmoteSpeedProfile(value).json();if(kind.equals("custom"))return new CustomEmoteClip(value).json();
        GomodCosmeticAppearance.Values colors=new GomodCosmeticAppearance.Values(EmoteSpeedProfile.number(value,"hue",-180,180),EmoteSpeedProfile.number(value,"saturation",0,2),EmoteSpeedProfile.number(value,"brightness",0,2));
        JsonObject result=new JsonObject();result.addProperty("hue",colors.hue);result.addProperty("saturation",colors.saturation);result.addProperty("brightness",colors.brightness);return result;
    }
    private static JsonObject defaults(String kind){if(kind.equals("speed"))return EmoteSpeedProfile.defaults();if(kind.equals("custom"))return CustomEmoteClip.defaults();JsonObject colors=new JsonObject();colors.addProperty("hue",0);colors.addProperty("saturation",1);colors.addProperty("brightness",1);return colors;}
    public static synchronized Map<Long,GomodCosmeticAppearance.Values> legacyBadgeValues(){
        load();Map<Long,GomodCosmeticAppearance.Values> values=new TreeMap<>();
        for(Map.Entry<String,JsonElement> row:data.getAsJsonObject("badge").entrySet()){
            JsonObject color=row.getValue().getAsJsonObject();values.put(Long.parseLong(row.getKey()),new GomodCosmeticAppearance.Values(color.get("hue").getAsDouble(),color.get("saturation").getAsDouble(),color.get("brightness").getAsDouble()));
        }return values;
    }
    private static JsonObject badges(){
        Map<Long,GomodCosmeticAppearance.Values> fit=LunarFitAppearance.badges();if(fit==null)return data.getAsJsonObject("badge");
        JsonObject values=new JsonObject();for(Map.Entry<Long,GomodCosmeticAppearance.Values> row:fit.entrySet())values.add(Long.toString(row.getKey()),row.getValue().json(row.getKey()));return values;
    }
    private static JsonObject values(String kind){return kind.equals("badge")?badges():data.getAsJsonObject(kind);}
    public static JsonObject request(String encoded){
        if(encoded==null||encoded.length()>131072)throw new IllegalArgumentException("Editor request too large");
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<encoded.length();i++){char c=encoded.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='\"')quoted=false;}else if(c=='\"')quoted=true;else if(c=='{'||c=='['){if(++depth>16)throw new IllegalArgumentException("Editor request too nested");}else if(c=='}'||c==']')depth--;}
        return new JsonParser().parse(encoded).getAsJsonObject();
    }
    private static long integer(JsonObject request,String key,double max){double value=EmoteSpeedProfile.number(request,key,1,max);if(value!=Math.floor(value))throw new IllegalArgumentException("Invalid "+key);return (long)value;}
    private static Draft owned(JsonObject request){
        load();if(owner==null||draft==null||!draft.owner.equals(owner)||!owner.toString().equals(request.get("account").getAsString())||!draft.token.equals(request.get("session").getAsString())
                ||!draft.id.equals(request.get("id").getAsString())||!draft.kind.equals(request.get("kind").getAsString())
                ||draft.kind.equals("badge")&&draft.fitScope!=LunarFitAppearance.scope())throw new IllegalArgumentException("This editor has been replaced. Reopen Settings.");return draft;
    }
    public static synchronized String open(String encoded){
        requireConnected();load();if(owner==null)throw new IllegalStateException("No Minecraft account");JsonObject request=request(encoded);
        String expected=request.get("account").getAsString().replace("-","");if(!owner.toString().replace("-","").equalsIgnoreCase(expected))throw new IllegalArgumentException("Account changed");
        long generation=integer(request,"generation",9007199254740991D);if(generation<=openedGeneration)throw new IllegalArgumentException("This editor has been replaced.");openedGeneration=generation;
        String kind=kind(request.get("kind").getAsString());String id=request.get("id").getAsString();
        if(kind.equals("custom")&&id.equals("new"))id=UUID.randomUUID().toString();id(kind,id);
        long scope=LunarFitAppearance.scope();JsonElement saved=values(kind).get(id);draft=new Draft(owner,kind,id,saved==null?defaults(kind):normalize(kind,saved.getAsJsonObject()));
        if(kind.equals("badge")&&scope!=draft.fitScope)throw new IllegalStateException("You switched Fits. Reopen Settings.");return snapshot();
    }
    private static String snapshot(){
        JsonObject answer=new JsonObject();answer.addProperty("account",owner.toString());answer.addProperty("id",draft.id);answer.addProperty("kind",draft.kind);answer.addProperty("session",draft.token);answer.add("value",draft.value);
        View view=new View(draft);answer.addProperty("position",view.position);answer.addProperty("playing",view.playing);answer.addProperty("readOnly",unreadable||draft.kind.equals("badge")&&LunarFitAppearance.readOnly());answer.addProperty("saved",values(draft.kind).has(draft.id));
        if(draft.receivedPreview)answer.addProperty("readOnly",true);
        answer.addProperty("fitScoped",draft.kind.equals("badge")&&LunarFitAppearance.active());
        if(draft.kind.equals("speed"))answer.addProperty("duration",LunarEmoteStudioRuntime.duration(Integer.parseInt(draft.id)));
        else if(draft.kind.equals("custom"))answer.addProperty("duration",draft.value.get("duration").getAsDouble());return answer.toString();
    }
    public static synchronized String poll(String encoded){owned(request(encoded));return snapshot();}
    public static synchronized String preview(String encoded){
        try{requireConnected();JsonObject request=request(encoded);Draft target=owned(request);long sequence=integer(request,"sequence",1e9);if(sequence<=target.sequence)throw new IllegalArgumentException("An older change was ignored.");
            JsonObject value=normalize(target.kind,request.getAsJsonObject("value"));
            double position=request.has("position")?EmoteSpeedProfile.number(request,"position",0,3600):new View(target).position;
            boolean playing=request.has("playing")&&request.get("playing").getAsBoolean(),seek=request.has("seek")&&request.get("seek").getAsBoolean();
            if(playing!=target.playing||seek){target.position=position;target.controlAt=System.nanoTime();target.seek++;}
            target.value=value;target.sequence=sequence;target.playing=playing;return "";
        }catch(Exception error){return error.getMessage()==null?"Could not preview this change.":error.getMessage();}
    }
    public static synchronized String save(String encoded){
        try{requireConnected();JsonObject request=request(encoded);Draft target=owned(request);if(unreadable)throw new IllegalStateException("The saved studio file could not be read; it has been preserved.");
            if(target.receivedPreview)throw new IllegalStateException("Accept this emote from its invitation to save it.");
            long sequence=integer(request,"sequence",1e9);if(sequence<target.sequence)throw new IllegalArgumentException("Save your latest changes.");JsonObject value=normalize(target.kind,request.getAsJsonObject("value"));
            if(target.kind.equals("badge")&&LunarFitAppearance.save(target.fitScope,Long.parseLong(target.id),new GomodCosmeticAppearance.Values(value.get("hue").getAsDouble(),value.get("saturation").getAsDouble(),value.get("brightness").getAsDouble()),true)){
                target.value=value;target.sequence=sequence;GomodStudioSharing.changed();return "Saved. These badge colors belong to this Fit.";
            }
            JsonObject next=new JsonParser().parse(data.toString()).getAsJsonObject(),values=next.getAsJsonObject(target.kind);
            if(!values.has(target.id)&&values.entrySet().size()>=(target.kind.equals("custom")?32:256))throw new IllegalArgumentException("Your saved studio list is full.");values.add(target.id,value);
            byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1048576)throw new IllegalArgumentException("Your saved studio file is full.");
            Path path=file(owner);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"studio-",".tmp");
            try{Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
            data=next;target.value=value;target.sequence=sequence;GomodStudioSharing.changed();return "Saved.";
        }catch(Exception error){return error.getMessage()==null?"Could not save this change.":error.getMessage();}
    }
    public static synchronized void close(String encoded){try{owned(request(encoded));draft=null;}catch(Exception ignored){}}
    public static synchronized View view(String kind,String id){load();return GomodLogoColorSync.isFullAccessColorAvailable()&&draft!=null&&draft.kind.equals(kind)&&draft.id.equals(id)&&(!kind.equals("badge")||draft.fitScope==LunarFitAppearance.scope())?new View(draft):null;}
    public static synchronized View customView(){load();return GomodLogoColorSync.isFullAccessColorAvailable()&&draft!=null&&draft.kind.equals("custom")?new View(draft):null;}
    public static synchronized JsonObject saved(String kind,String id){load();JsonElement value=values(kind).get(id);return value==null?defaults(kind):value.getAsJsonObject();}
    public static synchronized JsonObject badge(int id){GomodCosmeticAppearance.Values model=LunarFitAppearance.preview(id,true);if(model!=null)return model.json(id);View preview=view("badge",Integer.toString(id));return preview==null?saved("badge",Integer.toString(id)):preview.value;}
    public static synchronized void progress(String token,double position){load();if(draft!=null&&draft.token.equals(token)&&Double.isFinite(position))draft.position=Math.max(0,position);}
    public static synchronized String library(){load();JsonObject answer=new JsonObject();answer.addProperty("account",owner==null?"":owner.toString());JsonArray rows=new JsonArray();for(Map.Entry<String,JsonElement> row:data.getAsJsonObject("custom").entrySet()){JsonObject entry=new JsonObject();entry.addProperty("id",row.getKey());entry.addProperty("name",row.getValue().getAsJsonObject().get("name").getAsString());rows.add(entry);}answer.add("emotes",rows);return answer.toString();}
    public static synchronized String badgeStyles(){load();JsonObject answer=new JsonObject(),styles=new JsonParser().parse(badges().toString()).getAsJsonObject();if(draft!=null&&draft.kind.equals("badge")&&draft.fitScope==LunarFitAppearance.scope())styles.add(draft.id,draft.value);answer.addProperty("account",owner==null?"":owner.toString());answer.add("styles",styles);return answer.toString();}
    public static synchronized JsonArray sharedBadgeColors(){load();JsonArray rows=new JsonArray();for(Map.Entry<String,JsonElement> entry:badges().entrySet()){JsonObject row=entry.getValue().getAsJsonObject();GomodCosmeticAppearance.Values value=new GomodCosmeticAppearance.Values(row.get("hue").getAsDouble(),row.get("saturation").getAsDouble(),row.get("brightness").getAsDouble());if(!value.isDefault())rows.add(value.json(Long.parseLong(entry.getKey())));}return rows;}
    public static synchronized Set<Integer> editedBadgeIds(){load();Set<Integer> ids=new TreeSet<>();for(Map.Entry<String,JsonElement> entry:badges().entrySet()){JsonObject row=entry.getValue().getAsJsonObject();GomodCosmeticAppearance.Values value=new GomodCosmeticAppearance.Values(row.get("hue").getAsDouble(),row.get("saturation").getAsDouble(),row.get("brightness").getAsDouble());if(!value.isDefault())ids.add(Integer.parseInt(entry.getKey()));}return Collections.unmodifiableSet(ids);}
    public static synchronized Set<Integer> editedEmoteIds(){load();Set<Integer> ids=new TreeSet<>();for(Map.Entry<String,JsonElement> entry:data.getAsJsonObject("speed").entrySet()){EmoteSpeedProfile value=new EmoteSpeedProfile(entry.getValue().getAsJsonObject());if(value.speed!=1||!value.sections.isEmpty())ids.add(Integer.parseInt(entry.getKey()));}return Collections.unmodifiableSet(ids);}
    /** Remove one saved badge color or emote speed profile and invalidate any stale editor draft. */
    public static synchronized boolean resetEdited(String kind,String rawId)throws Exception{
        requireConnected();load();if(unreadable)throw new IllegalStateException("The saved studio file could not be read; it has been preserved.");
        kind=kind(kind);if(!kind.equals("badge")&&!kind.equals("speed"))throw new IllegalArgumentException("Only edited badges and emotes can be reset.");
        String key=id(kind,rawId);boolean removed;
        if(kind.equals("badge")&&LunarFitAppearance.save(LunarFitAppearance.scope(),Long.parseLong(key),GomodCosmeticAppearance.DEFAULT,true))removed=true;
        else{
            JsonObject next=new JsonParser().parse(data.toString()).getAsJsonObject(),values=kind.equals("badge")?next.getAsJsonObject("badge"):next.getAsJsonObject("speed");
            removed=values.remove(key)!=null;if(!removed)return false;
            byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);Path path=file(owner);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"studio-",".tmp");
            try{Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}data=next;
        }
        if(draft!=null&&draft.kind.equals(kind)&&draft.id.equals(key))draft=null;
        GomodStudioSharing.changed();return removed;
    }
    public static synchronized String customDocument(String account,String id){load();if(owner==null||!owner.toString().equals(account)||!data.getAsJsonObject("custom").has(id))throw new IllegalArgumentException("Saved emote unavailable");return data.getAsJsonObject("custom").get(id).toString();}
    public static synchronized JsonObject exportEmote(UUID account,String kind,String key){
        load();if(owner==null||!owner.equals(account)||unreadable||!kind.equals("speed")&&!kind.equals("custom"))throw new IllegalArgumentException("Saved emote unavailable");
        id(kind,key);JsonElement value=data.getAsJsonObject(kind).get(key);if(value==null)throw new IllegalArgumentException("Save the emote first.");return normalize(kind,value.getAsJsonObject());
    }
    public static synchronized String openSharedEmote(String encoded,String kind,String key,JsonObject value){
        requireConnected();JsonObject request=request(encoded);load();if(owner==null||!owner.toString().replace("-","").equalsIgnoreCase(request.get("account").getAsString().replace("-","")))throw new IllegalArgumentException("Account changed");
        long generation=integer(request,"generation",9007199254740991D);if(generation<=openedGeneration)throw new IllegalArgumentException("This editor has been replaced.");openedGeneration=generation;
        if(!kind.equals("speed")&&!kind.equals("custom"))throw new IllegalArgumentException("Emote kind");draft=new Draft(owner,kind,id(kind,key),normalize(kind,value));draft.receivedPreview=true;return snapshot();
    }
    public static synchronized void importEmote(UUID account,String kind,String key,JsonObject value,boolean replace)throws Exception{
        load();if(owner==null||!owner.equals(account)||unreadable)throw new IllegalStateException("Saved emotes are unavailable; existing files were preserved.");
        if(!kind.equals("speed")&&!kind.equals("custom"))throw new IllegalArgumentException("Emote kind");id(kind,key);JsonObject normalized=normalize(kind,value),next=new JsonParser().parse(data.toString()).getAsJsonObject(),rows=next.getAsJsonObject(kind);
        if(rows.has(key)&&!replace){if(rows.get(key).equals(normalized))return;throw new IllegalStateException("An emote already uses this ID.");}
        if(!rows.has(key)&&rows.entrySet().size()>=(kind.equals("custom")?32:256))throw new IllegalStateException("Your saved emote list is full.");rows.add(key,normalized);
        byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1048576)throw new IllegalStateException("Your saved studio file is full.");Path path=file(owner);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"studio-",".tmp");
        try{Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unavailable){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
        data=next;if(draft!=null&&!draft.receivedPreview&&draft.kind.equals(kind)&&draft.id.equals(key))draft=null;GomodStudioSharing.changed();
    }
}
