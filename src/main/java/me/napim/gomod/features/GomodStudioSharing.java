package me.napim.gomod.features;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Saved badge colors and current emote playback on the authenticated appearance channel. */
public final class GomodStudioSharing {
    private static final Map<UUID,Peer> PEERS=new ConcurrentHashMap<>();
    private static volatile String server="";private static volatile UUID account;
    private static volatile LocalNative localNative;
    private static final class LocalNative {
        final UUID owner;final WeakReference<Object> controller;final String token;final int id;final JsonObject value;final EmoteSpeedProfile profile;
        final double position,duration;final boolean loop;final long at=System.nanoTime();
        LocalNative(Object controller,String token,int id,JsonObject value,double position,double duration,boolean loop){owner=LunarFitSharing.account();this.controller=new WeakReference<>(controller);this.token=token;this.id=id;profile=new EmoteSpeedProfile(value);this.value=profile.json();this.position=position;this.duration=duration;this.loop=loop;}
        JsonObject json(){JsonObject row=new JsonObject();row.addProperty("token",token);row.addProperty("id",id);row.add("profile",value);row.addProperty("duration",duration/20);row.addProperty("loop",loop);row.addProperty("position",profile.advanceAbsolute(position,(System.nanoTime()-at)/5e7,duration,loop)/20);return row;}
    }
    private static final class Peer {
        final Map<Long,GomodCosmeticAppearance.Values> badges;final Native nativeEmote;final Custom custom;volatile long received=System.currentTimeMillis();
        Peer(JsonObject row){badges=GomodCosmeticAppearance.parseRows(row.getAsJsonArray("badgeAppearance"));nativeEmote=row.has("emotePlayback")&&!row.get("emotePlayback").isJsonNull()?new Native(row.getAsJsonObject("emotePlayback")):null;custom=row.has("customEmotePlayback")&&!row.get("customEmotePlayback").isJsonNull()?new Custom(row.getAsJsonObject("customEmotePlayback")):null;}
    }
    public static final class Native {
        public final String token;public final int id;public final JsonObject value;private final EmoteSpeedProfile profile;private final double position,duration,age;private final boolean loop;private final long at=System.nanoTime();
        Native(JsonObject row){token=uuid(row.get("token").getAsString()).toString();double raw=EmoteSpeedProfile.number(row,"id",1,Integer.MAX_VALUE);if(raw!=Math.floor(raw))throw new IllegalArgumentException("Emote ID");id=(int)raw;profile=new EmoteSpeedProfile(row.getAsJsonObject("profile"));value=profile.json();duration=EmoteSpeedProfile.number(row,"duration",.01,3600)*20;position=EmoteSpeedProfile.number(row,"position",0,86400)*20;age=row.has("age")?EmoteSpeedProfile.number(row,"age",0,120):0;loop=row.get("loop").getAsBoolean();}
        public double positionTicks(){return profile.advanceAbsolute(position,(age+(System.nanoTime()-at)/1e9)*20,duration,loop);}
    }
    public static final class Custom {
        public final String token;public final CustomEmoteClip clip;private final double position,age;private final long at=System.nanoTime();
        Custom(JsonObject row){token=uuid(row.get("token").getAsString()).toString();uuid(row.get("id").getAsString());clip=new CustomEmoteClip(row.getAsJsonObject("clip"));position=EmoteSpeedProfile.number(row,"position",0,86400);age=row.has("age")?EmoteSpeedProfile.number(row,"age",0,120):0;}
        public double positionSeconds(){return position+age+(System.nanoTime()-at)/1e9;}
    }
    private GomodStudioSharing(){}
    private static UUID uuid(String value){return UUID.fromString(value.replaceFirst("^(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})$","$1-$2-$3-$4-$5"));}
    public static void changed(){if(me.napim.gomod.Main.getGlobalChatClient()!=null)me.napim.gomod.Main.getGlobalChatClient().requestImmediatePoll();}
    public static void nativeTick(Object controller,String token,int id,JsonObject value,double position,double duration,boolean loop){
        LocalNative old=localNative;LocalNative next=new LocalNative(controller,token,id,value,position,duration,loop);localNative=next;
        if(old==null||!old.token.equals(token)||!old.value.equals(next.value))changed();
    }
    public static void nativeStopped(Object controller){LocalNative old=localNative;if(old!=null&&old.controller.get()==controller){localNative=null;changed();}}
    public static void attachLocalState(JsonObject payload){
        UUID owner=LunarFitSharing.account();boolean enabled=owner!=null&&GomodFixes.isWeaveRuntime()&&ConfigManager.isLunarTweaksShowToOtherPlayers();
        payload.addProperty("studioAppearanceStatePresent",enabled);payload.add("badgeAppearance",enabled?GomodStudioSettings.sharedBadgeColors():new JsonArray());
        JsonObject nativeState=null,custom=null;
        if(enabled){LocalNative state=localNative;if(state!=null&&owner.equals(state.owner)&&System.nanoTime()-state.at<2000000000L&&state.controller.get()!=null)nativeState=state.json();custom=GomodCustomEmotes.sharingState();}
        payload.add("emotePlayback",nativeState==null?JsonNull.INSTANCE:nativeState);payload.add("customEmotePlayback",custom==null?JsonNull.INSTANCE:custom);
    }
    public static void acceptPeers(JsonArray rows){
        UUID current=LunarFitSharing.account();if(!Objects.equals(current,account)){PEERS.clear();account=current;}
        String room=GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());if(!Objects.equals(room,server)){PEERS.clear();server=room==null?"":room;}
        Set<UUID> retained=new HashSet<>();if(rows!=null)for(JsonElement element:rows)try{
            JsonObject row=element.getAsJsonObject();UUID owner=uuid(row.get("playerUuid").getAsString());
            if(owner.equals(current)||!row.has("studioAppearanceStatePresent")||!row.get("studioAppearanceStatePresent").getAsBoolean()||!row.get("lunarFullAccessShareEnabled").getAsBoolean())continue;
            Peer next=new Peer(row),previous=PEERS.put(owner,next);retained.add(owner);
            if(next.custom!=null&&(previous==null||previous.custom==null||!previous.custom.token.equals(next.custom.token))){
                Minecraft mc=Minecraft.getMinecraft();if(mc!=null)mc.addScheduledTask(()->{if(peer(owner)==next)LunarPeerEmoteControl.stop(owner);});
            }
        }catch(Exception ignored){}
        PEERS.keySet().retainAll(retained);
    }
    public static void refreshPeerTimestamps(long now){for(Peer peer:PEERS.values())if(peer!=null)peer.received=now;}
    private static Peer peer(UUID owner) {
        // Model pose hooks also run for every player who has no shared appearance or playback.
        if (owner == null || !PEERS.containsKey(owner) || !Objects.equals(account, LunarFitSharing.account())
                || !ConfigManager.isLunarTweaksSeeGomodPlayers()
                || GomodPeerLunarFullAccess.shouldHideLunarCosmetics(owner)) return null;
        String room = GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());
        if (room == null || !room.equalsIgnoreCase(server)) return null;
        Peer value = PEERS.get(owner);
        return value == null || System.currentTimeMillis() - value.received > GomodSocialPresence.PRESENCE_TTL_MS
                ? null : value;
    }
    public static Native nativeEmote(UUID owner,int id){Peer peer=peer(owner);return peer==null||peer.nativeEmote==null||peer.nativeEmote.id!=id?null:peer.nativeEmote;}
    public static Custom customEmote(UUID owner){Peer peer=peer(owner);return peer==null||peer.custom==null||!peer.custom.clip.loop&&peer.custom.positionSeconds()>peer.custom.clip.duration?null:peer.custom;}
    public static GomodCosmeticAppearance.Values badge(UUID owner,int id){
        if(owner==null||owner.equals(LunarFitSharing.account())){JsonObject row=GomodStudioSettings.badge(id);return new GomodCosmeticAppearance.Values(row.get("hue").getAsDouble(),row.get("saturation").getAsDouble(),row.get("brightness").getAsDouble());}
        Peer peer=peer(owner);return peer==null?GomodCosmeticAppearance.DEFAULT:peer.badges.getOrDefault((long)id,GomodCosmeticAppearance.DEFAULT);
    }

    // A badge definition is shared by the whole native catalog. Display-only record copies retain wearer identity.
    private static final ReferenceQueue<Object> COLLECTED=new ReferenceQueue<>();
    private static final Map<IdentityRef,UUID> BADGE_OWNERS=new HashMap<>();
    private static final LinkedHashMap<UUID,BadgeCopy> BADGE_COPIES=new LinkedHashMap<>(32,.75f,true);
    private static final ClassValue<Boolean> NATIVE_BADGE_RECORD=new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if(type==null||!type.getName().startsWith("com.moonsworth.lunar."))return Boolean.FALSE;
            try{
                if(type.getMethod("id").getReturnType()!=int.class)return Boolean.FALSE;
                RecordLayout layout=RECORDS.get(type);
                if(layout.fields.length!=7)return Boolean.FALSE;
                return layout.fields[0].getType()==int.class
                        &&layout.fields[1].getType()==String.class
                        &&layout.fields[2].getType()==String.class
                        &&layout.fields[3].getType()==layout.fields[4].getType()
                        &&layout.fields[5].getType()==java.time.Instant.class
                        &&layout.fields[6].getType()==boolean.class;
            }catch(Throwable unavailable){return Boolean.FALSE;}
        }
    };
    private static boolean isNativeBadgeRecord(Object value){return value!=null&&NATIVE_BADGE_RECORD.get(value.getClass());}
    private static final class IdentityRef extends WeakReference<Object> {
        final int hash;IdentityRef(Object value,ReferenceQueue<Object> queue){super(value,queue);hash=System.identityHashCode(value);}
        @Override public int hashCode(){return hash;}@Override public boolean equals(Object other){return this==other||other instanceof IdentityRef&&get()!=null&&get()==((IdentityRef)other).get();}
    }
    private static final class BadgeCopy {final Object original,copy;BadgeCopy(Object original,Object copy){this.original=original;this.copy=copy;}}
    private static final class RecordLayout {
        final Field[] fields;final Constructor<?> constructor;
        RecordLayout(Class<?> type)throws ReflectiveOperationException {
            if(!type.getName().startsWith("com.moonsworth.lunar."))throw new IllegalArgumentException("Native record required");
            List<Field> values=new ArrayList<>();for(Field field:type.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!field.isSynthetic()){field.setAccessible(true);values.add(field);}
            fields=values.toArray(new Field[0]);Class<?>[] types=new Class<?>[fields.length];for(int i=0;i<types.length;i++)types[i]=fields[i].getType();constructor=type.getDeclaredConstructor(types);constructor.setAccessible(true);
        }
        Object[] values(Object record)throws ReflectiveOperationException{Object[] values=new Object[fields.length];for(int i=0;i<values.length;i++)values[i]=fields[i].get(record);return values;}
    }
    private static final ClassValue<RecordLayout> RECORDS=new ClassValue<RecordLayout>(){@Override protected RecordLayout computeValue(Class<?> type){try{return new RecordLayout(type);}catch(ReflectiveOperationException invalid){throw new IllegalArgumentException(invalid);}}};
    public static synchronized Object bindBadgeProfile(Object profile,UUID owner){
        if(profile==null||owner==null)return profile;
        try{
            RecordLayout layout=RECORDS.get(profile.getClass());if(layout.fields.length!=3&&layout.fields.length!=7)return profile;
            Object[] values=layout.values(profile);int slot=-1;for(int i=0;i<values.length;i++)if(isNativeBadgeRecord(values[i])){if(slot>=0)return profile;slot=i;}
            if(slot<0)return profile;Object original=values[slot];BadgeCopy cached=BADGE_COPIES.get(owner);
            if(cached==null||!cached.original.equals(original)){
                RecordLayout badge=RECORDS.get(original.getClass());Object copy=badge.constructor.newInstance(badge.values(original));cached=new BadgeCopy(original,copy);
                while(BADGE_COPIES.size()>=512)BADGE_COPIES.remove(BADGE_COPIES.keySet().iterator().next());BADGE_COPIES.put(owner,cached);
                Reference<?> expired;while((expired=COLLECTED.poll())!=null)BADGE_OWNERS.remove(expired);BADGE_OWNERS.put(new IdentityRef(copy,COLLECTED),owner);
            }
            values[slot]=cached.copy;return layout.constructor.newInstance(values);
        }catch(Exception unsupported){return profile;}
    }
    public static synchronized UUID badgeOwner(Object badge){Reference<?> expired;while((expired=COLLECTED.poll())!=null)BADGE_OWNERS.remove(expired);return BADGE_OWNERS.get(new IdentityRef(badge,null));}
    public static String badgeSignature(Object badge){UUID owner=badgeOwner(badge);if(owner==null)return "";try{int id=((Number)badge.getClass().getMethod("id").invoke(badge)).intValue();GomodCosmeticAppearance.Values value=badge(owner,id);return owner+":"+value.hue+":"+value.saturation+":"+value.brightness;}catch(Exception ignored){return "";}}
}
