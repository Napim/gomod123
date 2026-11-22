package me.napim.gomod.features;

import com.google.gson.*;
import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Keeps the emote lifetime and native pose-state clocks at the same source-time rate. */
public final class LunarEmoteStudioRuntime {
    private static final String ROOT="com.moonsworth.lunar.client.OICROIOCCHRCHRIRIRRIHOIRRCCRCH.RHOICRHRRHCICCRORRHHIRIIHHIIHI.IOOCCRIRHHROHIHRCHHRRIOICHIOIR.";
    private static final String REGISTRY=ROOT+"COIOIRHRRORRHHOIIORORRHOOCRHII.IOOCCRIRHHROHIHRCHHRRIOICHIOIR.CIOHOIORRIOCHOHCIIIRRRCCOIOIHC";
    private static final String ACTIVE_CONFIG="CHOHRORCOHHHICHIIICROIIICOHOIC",ANIMATION_STATE="RCRRRIOCCRHRHCRRRIRRHOHORIRHHH";
    private static final Map<String,Integer> IDS=new HashMap<>();
    private static final Map<Integer,Double> DURATIONS=new HashMap<>();
    private static final Map<Object,Clock> CLOCKS=Collections.synchronizedMap(new WeakHashMap<Object,Clock>());
    private static final class Clock {UUID owner;String name,preview,peerRevision;GomodStudioSharing.Native peerObservation;final String token=UUID.randomUUID().toString();long seek;double source;int nativeTick;JsonObject value;EmoteSpeedProfile profile;Animation animation;}
    private static final ClassValue<StateAccess> STATE_ACCESS=new ClassValue<StateAccess>() {
        @Override protected StateAccess computeValue(Class<?> type) {
            try{return new StateAccess(type);}catch(ReflectiveOperationException unavailable){throw new IllegalArgumentException("Unsupported native animation state",unavailable);}
        }
    };
    private static final class StateAccess {
        final Field position,rate,loop,length;final Method clearFade;
        StateAccess(Class<?> type)throws ReflectiveOperationException {
            position=privateField(type,"HROCRHIOIRICIIOROHOOOIHIHOCHOO",float.class);
            rate=privateField(type,"ICCIICOIHICCHHHRRCROHCCHRRHCIO",float.class);
            loop=privateField(type,"RRCHCCRORCCIRIIIICHRORCHIHHHOI",boolean.class);
            length=privateField(type,"OOIOCHOOOIROHOHCORCRIRRCCIIIIH",int.class);
            clearFade=type.getMethod("CHCCOIHHHRICCHRHOHCHOHCICRRROH");
        }
    }
    private static Field privateField(Class<?> type,String name,Class<?> expected)throws ReflectiveOperationException {
        Field field=type.getDeclaredField(name);if(field.getType()!=expected)throw new NoSuchFieldException(name);field.setAccessible(true);return field;
    }
    private static final class Animation {
        final WeakReference<Object> state;final StateAccess access;final float rate;final boolean loop;final int length;
        Animation(Object state)throws ReflectiveOperationException {
            this.state=new WeakReference<>(state);access=STATE_ACCESS.get(state.getClass());rate=access.rate.getFloat(state);loop=access.loop.getBoolean(state);length=access.length.getInt(state);
            if(!Float.isFinite(rate)||rate<=0||length<=0)throw new IllegalArgumentException("Unsupported native animation rate");
        }
        void restore() {
            Object target=state.get();if(target==null)return;
            try{access.rate.setFloat(target,rate);access.loop.setBoolean(target,loop);}catch(ReflectiveOperationException ignored){}
        }
    }
    private LunarEmoteStudioRuntime(){}
    public static void definition(Object definition){
        try{int id=((Number)definition.getClass().getMethod("getId").invoke(definition)).intValue();if(id<=0)return;
            String name=(String)definition.getClass().getMethod("ROIIHIOCHCHHCOOHCICCOIRHCHOCOR").invoke(definition);if(name==null||name.isEmpty())return;
            synchronized(IDS){IDS.put(name,id);}
            Object configs=Class.forName(REGISTRY,false,definition.getClass().getClassLoader()).getField("COCRROHRROOOHIRIHCHIHCOCIORIHO").get(null);
            Object config=((Map<?,?>)configs).get(name);if(config!=null){double ticks=((Number)config.getClass().getField("duration").get(config)).doubleValue();if(ticks>0)synchronized(IDS){DURATIONS.put(id,ticks/20);}}
        }catch(Throwable unavailable){/* An unrecognized native catalog remains unmodified. */}
    }
    public static double duration(int id){synchronized(IDS){return DURATIONS.getOrDefault(id,0D);}}
    public static void reset(Object controller,Object player){
        clear(controller);
        try{if(!display(player)&&Objects.equals(LunarFitSharing.account(),player.getClass().getMethod("bridge$getUniqueID").invoke(player)))GomodCustomEmotes.stop();}catch(Exception unavailable){}
    }
    private static Object field(Object target,String name)throws Exception{return target.getClass().getField(name).get(target);}
    private static void clear(Object controller){Clock clock=CLOCKS.remove(controller);if(clock!=null&&clock.animation!=null)clock.animation.restore();GomodStudioSharing.nativeStopped(controller);}
    public static void stopped(Object controller){clear(controller);}
    /** The controller invokes this before its native animatable tick updates and samples the pose state. */
    private static void animate(Clock clock,Object controller,double from,double to,int ticks,double duration,boolean seek,boolean preview)throws Exception {
        Object state=field(controller,ANIMATION_STATE);if(state==null)throw new IllegalStateException("Native emote state unavailable");
        Animation animation=clock.animation;boolean replaced=animation==null||animation.state.get()!=state;
        if(replaced){if(animation!=null)animation.restore();animation=new Animation(state);clock.animation=animation;}
        if(seek||replaced){
            double source=from%duration,position=source/duration*animation.length;
            animation.access.position.setFloat(state,(float)Math.max(0,Math.min(position,animation.length-.0001)));
            if(preview)animation.access.clearFade.invoke(state);
        }
        animation.access.loop.setBoolean(state,preview||animation.loop);
        // Native update() adds this rate, and its pose sampler uses it for partial-frame interpolation.
        animation.access.rate.setFloat(state,(float)(animation.rate*(to-from)/ticks));
    }
    private static boolean display(Object player){try{Object type=player.getClass().getMethod("getDummyPlayerType").invoke(player);return type instanceof Enum<?> && ("SELF".equals(((Enum<?>)type).name())||"MANNEQUIN".equals(((Enum<?>)type).name()));}catch(Exception ignored){return false;}}
    public static int step(Object controller,Object player,int nativeStep,String tickField){
        try{
            if(!ConfigManager.isLunarTweaksEnabled()||nativeStep<=0){clear(controller);return nativeStep;}
            UUID owner=LunarFitSharing.account();if(owner==null){clear(controller);return nativeStep;}
            boolean display=display(player);UUID playerId=(UUID)player.getClass().getMethod("bridge$getUniqueID").invoke(player);
            boolean self=owner.equals(playerId);
            Object config=field(controller,ACTIVE_CONFIG);if(config==null){clear(controller);return nativeStep;}
            String name=(String)config.getClass().getMethod("getKey").invoke(config);Integer id;synchronized(IDS){id=IDS.get(name);}if(id==null){clear(controller);return nativeStep;}
            GomodStudioSharing.Native shared=!display&&!self?GomodStudioSharing.nativeEmote(playerId,id):null;
            if(!display&&!self&&shared==null){clear(controller);return nativeStep;}
            double duration=((Number)field(config,"duration")).doubleValue();if(duration<=0){clear(controller);return nativeStep;}
            synchronized(IDS){DURATIONS.put(id,duration/20);}
            int tick=((Number)field(controller,tickField)).intValue();
            boolean gift=display&&LunarGiftPreviews.isGiftPlayer(playerId);
            GomodStudioSettings.View view=display?(gift?LunarGiftPreviews.speed(playerId,id):GomodStudioSettings.view("speed",Integer.toString(id))):null;
            if(gift&&view==null){clear(controller);return nativeStep;}
            JsonObject value=shared!=null?shared.value:view==null?GomodStudioSettings.saved("speed",Integer.toString(id)):view.value;
            if(view==null&&value.get("speed").getAsDouble()==1&&value.getAsJsonArray("sections").size()==0){clear(controller);return nativeStep;}
            Clock clock=CLOCKS.get(controller);
            if(clock==null||!owner.equals(clock.owner)||!name.equals(clock.name)||clock.nativeTick!=tick){clear(controller);clock=new Clock();clock.owner=owner;clock.name=name;clock.source=tick;clock.nativeTick=tick;clock.seek=-1;CLOCKS.put(controller,clock);}
            if(clock.value!=value){clock.profile=new EmoteSpeedProfile(value);clock.value=value;}EmoteSpeedProfile profile=clock.profile;
            boolean seek=view!=null&&(!view.token.equals(clock.preview)||view.seek!=clock.seek);
            if(seek){clock.preview=view.token;clock.seek=view.seek;clock.source=Math.min(Math.max(0,view.position*20),duration-.0001);}
            if(shared!=null){String revision=shared.token+value.toString();double expected=shared.positionTicks();if(!revision.equals(clock.peerRevision)||clock.peerObservation!=shared&&Math.abs(expected-clock.source)>4){clock.peerRevision=revision;clock.source=expected;seek=true;}clock.peerObservation=shared;}
            boolean loop=view!=null||(Boolean)field(config,"RRCHCCRORCCIRIIIICHRORCHIHHHOI");
            double from=clock.source,to=view==null||view.playing?profile.advanceAbsolute(from,nativeStep,duration,loop):from;
            animate(clock,controller,from,to,nativeStep,duration,seek,view!=null);
            clock.source=to;
            if(view!=null){clock.source%=duration;GomodStudioSettings.progress(view.token,clock.source/20);}
            if(!display&&self)GomodStudioSharing.nativeTick(controller,clock.token,id,value,clock.source,duration,loop);
            int next=(int)Math.floor(Math.min(Integer.MAX_VALUE-1,clock.source));clock.nativeTick=next;return next-tick;
        }catch(Throwable unavailable){
            clear(controller);LunarConnectionDiagnostics.recordOnceAfterStart("studio.animation_binding_failed",controller==null?"null":controller.getClass().getName(),"exception="+unavailable.getClass().getSimpleName());return nativeStep;
        }
    }
}
