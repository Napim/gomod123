package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GomodPaths;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** Account-local Fits backup. Native login/default/profile echoes never constitute user edits. */
public final class LunarOutfitPersistence {
    private static final String PROTO="com.lunarclient.websocket.cosmetic.v2.";
    private static final Map<UUID,byte[]> SNAPSHOTS=new LinkedHashMap<UUID,byte[]>(),PENDING=new LinkedHashMap<UUID,byte[]>();
    private static final Set<UUID> LOADED=new LinkedHashSet<UUID>();
    private static final Set<UUID> BOOTSTRAP=new LinkedHashSet<UUID>(),PENDING_BOOTSTRAP=new LinkedHashSet<UUID>();
    private static final Map<UUID,Object> EXPECTED_LOGIN=new LinkedHashMap<UUID,Object>(),EXPECTED_SOCKETS=new LinkedHashMap<UUID,Object>(),READY_SOCKETS=new LinkedHashMap<UUID,Object>();
    private static final ThreadLocal<Boolean> INITIALIZING=new ThreadLocal<Boolean>();
    private static final ThreadLocal<UUID> INITIALIZING_ACCOUNT=new ThreadLocal<UUID>();
    private static final ThreadLocal<Object> INITIALIZING_SOCKET=new ThreadLocal<Object>();
    private static final ThreadLocal<Object> INITIALIZING_MANAGER=new ThreadLocal<Object>();
    private static final Map<UUID,Object> MANAGERS=new LinkedHashMap<UUID,Object>();
    private static final ScheduledExecutorService IO=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gomod-lunar-fits");t.setDaemon(true);return t;});
    private static boolean writerQueued; private static long generation;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            IO.shutdown();try{IO.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        },"gomod-lunar-fits-save"));
    }
    private LunarOutfitPersistence(){}
    private static boolean enabled(){return ConfigManager.isLunarTweaksEnabled() && LunarOnlineStatus.isFullAccessPreferenceEnabled();}
    private static UUID account(){
        Minecraft mc=Minecraft.getMinecraft();if(mc==null||mc.getSession()==null)return null;
        String raw=mc.getSession().getPlayerID();if(raw==null)return null;String h=raw.replace("-","");
        return !h.matches("(?i)[a-f0-9]{32}")?null:UUID.fromString(h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20));
    }
    private static LunarEmoteWheelStore store(){return new LunarEmoteWheelStore(new File(GomodPaths.getGomod123Dir(),"lunar-outfits"),512*1024);}
    public static void beginLogin(){INITIALIZING.set(Boolean.TRUE);}
    public static void beginLogin(Object manager,List<?> outfits,Object tree){
        beginLogin(outfits,tree);INITIALIZING_MANAGER.set(manager);
    }
    public static void beginLogin(List<?> outfits,Object tree){
        beginLogin();INITIALIZING_ACCOUNT.remove();INITIALIZING_SOCKET.remove();
        UUID owner=account();if(owner==null||!enabled())return;
        try{
            Object expected,socket;
            synchronized(LunarOutfitPersistence.class){READY_SOCKETS.remove(owner);expected=EXPECTED_LOGIN.get(owner);socket=EXPECTED_SOCKETS.get(owner);}
            if(expected!=null&&socket!=null&&socket==LunarColorRuntime.cosmeticSocket()
                    &&outfits.equals(call(expected,"getOutfitsList"))&&tree.equals(call(expected,"getOutfitTree"))){
                INITIALIZING_ACCOUNT.set(owner);INITIALIZING_SOCKET.set(socket);
            }
        }catch(Throwable e){event("initialization_unconfirmed","exception="+e.getClass().getSimpleName());}
    }
    public static void endLogin(){
        UUID owner=INITIALIZING_ACCOUNT.get();Object socket=INITIALIZING_SOCKET.get();
        Object manager=INITIALIZING_MANAGER.get();boolean ready=false;
        if(owner!=null&&owner.equals(account())&&socket==LunarColorRuntime.cosmeticSocket()){
            synchronized(LunarOutfitPersistence.class){READY_SOCKETS.put(owner,socket);if(manager!=null)MANAGERS.put(owner,manager);}
            ready=true;
            event("native_ready","scope=account-local");
            try { notifyColors(owner,EXPECTED_LOGIN.get(owner),true); } catch(Throwable ignored) { }
        }
        abortLogin();
        if(ready)try{LunarFitSharing.nativeReady();}catch(Throwable ignored){}
    }
    public static void abortLogin(){INITIALIZING.remove();INITIALIZING_ACCOUNT.remove();INITIALIZING_SOCKET.remove();INITIALIZING_MANAGER.remove();}
    /** Native Fits publisher runs after edits, even when its connection Optional is empty. */
    public static void rememberNative(Object manager){
        if(!enabled()||manager==null||Boolean.TRUE.equals(INITIALIZING.get()))return;
        // While FullAccess is waiting for API readiness, Lunar may publish the official
        // route's fallback/default Fits. Never let that route replace this account's
        // FullAccess snapshot; once the relay route is active, explicit edits can save
        // locally even if its socket is temporarily disconnected.
        if(!LunarOnlineStatus.isFullAccessRouteActive()){
            event("save_skipped","reason=fullaccess-route-inactive");
            return;
        }
        UUID owner=account();if(owner==null)return;
        // Route selection precedes the V2 login. A native publisher can still contain the
        // official one-Fit fallback until the source-confirmed response has been applied.
        synchronized(LunarOutfitPersistence.class){
            Object socket=LunarColorRuntime.cosmeticSocket();
            if(socket==null||READY_SOCKETS.get(owner)!=socket){
                event("save_skipped","reason=fullaccess-fits-not-initialized");return;
            }
        }
        try{
            Object builder=Class.forName(PROTO+"LoginResponse",false,manager.getClass().getClassLoader()).getMethod("newBuilder").invoke(null);
            Object tree=null;List<Object> outfits=null;
            for(Field f:manager.getClass().getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object value=f.get(manager);if(value==null)continue;
                Method writeTree=serializer(value.getClass(),PROTO+"OutfitTree");
                if(writeTree!=null){if(tree!=null)throw new IllegalStateException("Ambiguous outfit tree");tree=writeTree.invoke(value);}
                if(!(value instanceof List)||!(f.getGenericType() instanceof ParameterizedType))continue;
                Type element=((ParameterizedType)f.getGenericType()).getActualTypeArguments()[0];if(!(element instanceof Class))continue;
                Method encode=serializer((Class<?>)element,PROTO+"Outfit");if(encode==null)continue;
                if(outfits!=null)throw new IllegalStateException("Ambiguous outfit list");outfits=new ArrayList<Object>();
                for(Object outfit:(List<?>)value){if(outfits.size()>=128)throw new IllegalStateException("Too many Fits");outfits.add(encode.invoke(outfit));}
            }
            if(outfits==null||tree==null||outfits.isEmpty())return;
            builder.getClass().getMethod("addAllOutfits",Iterable.class).invoke(builder,outfits);set(builder,"setOutfitTree",tree);
            Object snapshot=call(builder,"build");validate(snapshot);remember(owner,(byte[])call(snapshot,"toByteArray"));
            notifyColors(owner,snapshot,false);
        }catch(Throwable failure){event("save_failed","exception="+failure.getClass().getSimpleName());}
    }
    private static Method serializer(Class<?> type,String result){
        Method found=null;for(Method m:type.getMethods())if(!Modifier.isStatic(m.getModifiers())&&m.getParameterTypes().length==0&&m.getReturnType().getName().equals(result)){if(found!=null)return null;found=m;}
        if(found!=null)found.setAccessible(true);return found;
    }
    private static void notifyColors(UUID owner,Object snapshot,boolean login) {
        try { LunarFitColors.nativeSnapshot(owner,snapshot,login); }
        catch(Throwable failure) { event("colors_unavailable","exception="+failure.getClass().getSimpleName()); }
        try { LunarFitAppearance.nativeSnapshot(owner,snapshot,login); }
        catch(Throwable failure) { event("appearance_unavailable","exception="+failure.getClass().getSimpleName()); }
    }
    private static synchronized Object sharingManager(UUID owner){
        Object socket=LunarColorRuntime.cosmeticSocket();
        return enabled()&&owner!=null&&owner.equals(account())&&LunarOnlineStatus.isFullAccessRouteActive()
                &&socket!=null&&READY_SOCKETS.get(owner)==socket?MANAGERS.get(owner):null;
    }
    /** Immutable native record, exported without changing the selected Fit. */
    public static byte[] exportSharedFit(UUID owner,String fitId)throws Exception{
        Object manager=sharingManager(owner);if(manager==null)return null;
        byte[] bytes=load(owner);if(bytes==null)return null;
        Object snapshot=Class.forName(PROTO+"LoginResponse",false,manager.getClass().getClassLoader()).getMethod("parseFrom",byte[].class).invoke(null,new Object[]{bytes});
        for(Object outfit:(List<?>)call(snapshot,"getOutfitsList"))if(protoId(call(outfit,"getId")).toString().equals(fitId)){
            byte[] encoded=(byte[])call(outfit,"toByteArray");validateShared(outfit,encoded);return encoded;
        }
        return null;
    }
    private static UUID protoId(Object value)throws Exception{return new UUID(((Number)call(value,"getHigh64")).longValue(),((Number)call(value,"getLow64")).longValue());}
    private static final UUID SHARED_PREVIEW_ID=UUID.nameUUIDFromBytes("gomod.shared-fit.preview".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static Object sharedPreviewManager;
    /** Native model data for an invitation, without inserting it into the recipient's Fits. */
    public static JsonObject sharedFitPreviewData(UUID owner,byte[] bytes)throws Exception{
        if(owner==null||!owner.equals(account()))return null;
        Object manager=sharingManager(owner);
        // Rendering a received invitation is read-only and also works while FullAccess is disconnected.
        if(manager==null){
            ClassLoader loader=LunarOutfitPersistence.class.getClassLoader();
            Class<?> proto=Class.forName(PROTO+"Outfit",false,loader);
            Object outfit=proto.getMethod("parseFrom",byte[].class).invoke(null,new Object[]{bytes});validateShared(outfit,bytes);
            Class<?> record=Class.forName("com.moonsworth.lunar.client.IOIOOCICIIHCIRIRRHHOIOOOICIRRC.RHOICRHRRHCICCRORRHHIRIIHHIIHI.ICCOCOHOCCOROROIRHCCRHICOOOHHC.RHOICRHRRHCICCRORRHHIRIIHHIIHI",false,loader);
            for(Method method:record.getMethods())if(Modifier.isStatic(method.getModifiers())&&method.getReturnType()==record&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{proto})){
                Object data=call(method.invoke(null,outfit),"provide");return data instanceof JsonObject?(JsonObject)data:null;
            }
            return null;
        }
        Class<?> proto=Class.forName(PROTO+"Outfit",false,manager.getClass().getClassLoader());
        Object outfit=proto.getMethod("parseFrom",byte[].class).invoke(null,new Object[]{bytes});validateShared(outfit,bytes);
        for(Field field:manager.getClass().getDeclaredFields()){
            if(!(field.getGenericType() instanceof ParameterizedType))continue;
            ParameterizedType type=(ParameterizedType)field.getGenericType();
            if(type.getRawType()!=List.class||!(type.getActualTypeArguments()[0] instanceof Class))continue;
            Class<?> record=(Class<?>)type.getActualTypeArguments()[0];if(serializer(record,PROTO+"Outfit")==null)continue;
            Method factory=null;
            for(Method method:record.getMethods())if(Modifier.isStatic(method.getModifiers())&&method.getReturnType()==record&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{proto})){
                if(factory!=null)throw new IllegalStateException("Ambiguous Fit preview factory");factory=method;
            }
            if(factory==null)return null;factory.setAccessible(true);
            Object model=factory.invoke(null,outfit);Object data=call(model,"provide");
            return data instanceof JsonObject?(JsonObject)data:null;
        }
        return null;
    }
    /** Feed the ordinary native cosmetic profile handler for one GUI-only UUID. No login/tree edits. */
    public static boolean previewSharedFit(UUID id,byte[] bytes)throws Exception{
        if(!SHARED_PREVIEW_ID.equals(id)||id.equals(account()))return false;
        Object manager=sharingManager(account());if(manager==null)return false;
        ClassLoader loader=manager.getClass().getClassLoader();
        Object outfit=Class.forName(PROTO+"Outfit",false,loader).getMethod("parseFrom",byte[].class).invoke(null,new Object[]{bytes});validateShared(outfit,bytes);
        Object uuid=Class.forName("com.lunarclient.common.v1.Uuid",false,loader).getMethod("newBuilder").invoke(null);
        uuid.getClass().getMethod("setHigh64",long.class).invoke(uuid,id.getMostSignificantBits());uuid.getClass().getMethod("setLow64",long.class).invoke(uuid,id.getLeastSignificantBits());
        Class<?> pushType=Class.forName(PROTO+"PlayerCosmeticsPushV2",false,loader);Object builder=pushType.getMethod("newBuilder").invoke(null);
        set(builder,"setPlayerUuid",call(uuid,"build"));set(builder,"setOutfitUuid",call(outfit,"getId"));
        builder.getClass().getMethod("addAllDefaultCosmetics",Iterable.class).invoke(builder,call(outfit,"getCosmeticsList"));
        builder.getClass().getMethod("setBadgeId",int.class).invoke(builder,call(outfit,"getBadgeId"));
        Object push=call(builder,"build");Method handler=null;
        for(Method method:manager.getClass().getMethods())if(!Modifier.isStatic(method.getModifiers())&&!method.isBridge()&&method.getReturnType()==void.class&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{pushType})){
            if(handler!=null)throw new IllegalStateException("Ambiguous preview profile handler");handler=method;
        }
        if(handler==null)return false;
        if(sharedPreviewManager!=null&&sharedPreviewManager!=manager)clearSharedFitPreview(id);
        handler.setAccessible(true);sharedPreviewManager=manager;handler.invoke(manager,push);return true;
    }
    public static void clearSharedFitPreview(UUID id){
        if(!SHARED_PREVIEW_ID.equals(id))return;
        Object manager=sharedPreviewManager;sharedPreviewManager=null;
        if(manager!=null)try{manager.getClass().getMethod("removePlayer",UUID.class).invoke(manager,id);}catch(Exception ignored){}
    }
    private static void validateShared(Object outfit,byte[] bytes)throws Exception{
        if(bytes.length==0||bytes.length>16384)throw new IllegalArgumentException("Fit size");
        String name=(String)call(outfit,"getName");if(name.length()>128)throw new IllegalArgumentException("Fit name");
        List<?> cosmetics=(List<?>)call(outfit,"getCosmeticsList");if(cosmetics.size()>64)throw new IllegalArgumentException("Fit items");
        Set<Integer> ids=new HashSet<Integer>();for(Object c:cosmetics){int id=((Number)call(c,"getCosmeticId")).intValue();if(id<=0||!ids.add(id))throw new IllegalArgumentException("Cosmetic id");}
        String skin=(String)call(outfit,"getSkinHash");if(!skin.isEmpty()&&!skin.matches("(?i)[0-9a-f]{32,64}"))throw new IllegalArgumentException("Skin hash");
    }
    /** Reinitializes the native indexes with one extra Fit while retaining the entire selected tree. */
    public static boolean importSharedFit(UUID owner,UUID localId,String name,byte[] bytes,JsonObject colors)throws Exception{
        Object manager=sharingManager(owner);if(manager==null)return false;
        ClassLoader loader=manager.getClass().getClassLoader();
        Class<?> responseType=Class.forName(PROTO+"LoginResponse",false,loader),outfitType=Class.forName(PROTO+"Outfit",false,loader);
        Object outfit=outfitType.getMethod("parseFrom",byte[].class).invoke(null,new Object[]{bytes});validateShared(outfit,bytes);
        byte[] saved=load(owner);if(saved==null)return false;
        Object previous=responseType.getMethod("parseFrom",byte[].class).invoke(null,new Object[]{saved});validate(previous);
        List<?> existing=(List<?>)call(previous,"getOutfitsList");
        for(Object fit:existing)if(localId.equals(protoId(call(fit,"getId"))))return true;
        if(existing.size()>=128)return false;
        Object key=call(call(outfit,"getId"),"toBuilder");
        key.getClass().getMethod("setHigh64",long.class).invoke(key,localId.getMostSignificantBits());key.getClass().getMethod("setLow64",long.class).invoke(key,localId.getLeastSignificantBits());
        Object edit=call(outfit,"toBuilder");set(edit,"setId",call(key,"build"));
        edit.getClass().getMethod("setName",String.class).invoke(edit,name);edit.getClass().getMethod("setFavorite",boolean.class).invoke(edit,false);
        outfit=call(edit,"build");Object builder=call(previous,"toBuilder");builder.getClass().getMethod("addOutfits",outfitType).invoke(builder,outfit);
        Object merged=call(builder,"build");validate(merged);
        Method initializer=null;for(Method method:manager.getClass().getMethods())if(!Modifier.isStatic(method.getModifiers())&&method.getReturnType()==void.class
                &&Arrays.equals(method.getParameterTypes(),new Class<?>[]{List.class,Class.forName(PROTO+"OutfitTree",false,loader)})){
            if(initializer!=null)throw new IllegalStateException("Ambiguous native initializer");initializer=method;
        }
        if(initializer==null)throw new NoSuchMethodException("Native Fits initializer");initializer.setAccessible(true);
        try{
            LunarFitColors.importShared(owner,localId,colors);
            expectLogin(owner,merged);initializer.invoke(manager,call(merged,"getOutfitsList"),call(merged,"getOutfitTree"));
            // The initializer's publisher is intentionally excluded from normal edit saving.
            // Explicitly save the complete, validated local merge after it finishes.
            rememberNative(manager);
            synchronized(LunarOutfitPersistence.class){if(READY_SOCKETS.get(owner)!=LunarColorRuntime.cosmeticSocket())return false;}
            queueReplay(owner,merged,previous);return true;
        }catch(Throwable failure){
            try{expectLogin(owner,previous);initializer.invoke(manager,call(previous,"getOutfitsList"),call(previous,"getOutfitTree"));}catch(Throwable ignored){}
            event("share_import_failed","exception="+failure.getClass().getSimpleName());return false;
        }
    }
    private static synchronized byte[] load(UUID owner)throws Exception{
        if(!LOADED.contains(owner)){
            JsonObject envelope=store().loadEnvelope(owner);LOADED.add(owner);
            if(envelope!=null){
                SNAPSHOTS.put(owner,Base64.getDecoder().decode(envelope.get("request").getAsString()));
                if(envelope.has("snapshotSource")&&envelope.get("snapshotSource").isJsonPrimitive()
                        &&"login".equals(envelope.get("snapshotSource").getAsString()))BOOTSTRAP.add(owner);
                else BOOTSTRAP.remove(owner);
            }
            trim();
        }
        return SNAPSHOTS.get(owner);
    }
    private static void trim(){while(LOADED.size()>8){UUID old=LOADED.iterator().next();LOADED.remove(old);SNAPSHOTS.remove(old);BOOTSTRAP.remove(old);EXPECTED_LOGIN.remove(old);EXPECTED_SOCKETS.remove(old);READY_SOCKETS.remove(old);MANAGERS.remove(old);}}
    private static synchronized void remember(UUID owner,byte[] bytes){
        remember(owner,bytes,false);
    }
    private static synchronized void remember(UUID owner,byte[] bytes,boolean bootstrap){
        if(bytes.length>512*1024)throw new IllegalArgumentException("Fits snapshot too large");
        if(Arrays.equals(SNAPSHOTS.get(owner),bytes))return;
        generation++;SNAPSHOTS.put(owner,bytes);LOADED.add(owner);trim();PENDING.put(owner,bytes);
        if(bootstrap){BOOTSTRAP.add(owner);PENDING_BOOTSTRAP.add(owner);}else{BOOTSTRAP.remove(owner);PENDING_BOOTSTRAP.remove(owner);}
        while(PENDING.size()>8){UUID old=PENDING.keySet().iterator().next();PENDING.remove(old);PENDING_BOOTSTRAP.remove(old);}
        if(!writerQueued){writerQueued=true;IO.execute(()->flush());}
    }
    private static void flush(){for(;;){UUID owner;byte[] bytes;boolean bootstrap;
        synchronized(LunarOutfitPersistence.class){if(PENDING.isEmpty()){writerQueued=false;return;}owner=PENDING.keySet().iterator().next();bytes=PENDING.remove(owner);bootstrap=PENDING_BOOTSTRAP.remove(owner);}
        try{store().save(owner,bytes,bootstrap?"login":"native-edit");event("saved","scope=account-local source="+(bootstrap?"login":"native-edit")+" payload=omitted");}catch(Exception e){event("save_failed","exception="+e.getClass().getSimpleName());}
    }}
    /** Invoked only for a source-confirmed FullAccess login, after retaining its raw snapshot. */
    public static Object restoreLogin(Object response){
        if(!enabled()||response==null||!response.getClass().getName().equals(PROTO+"LoginResponse"))return response;
        UUID owner=account();if(owner==null)return response;
        try{
            byte[] saved=load(owner);
            if(saved==null){validate(response);Object snapshot=reduced(response);remember(owner,(byte[])call(snapshot,"toByteArray"),true);expectLogin(owner,snapshot);return response;}
            Object snapshot=response.getClass().getMethod("parseFrom",byte[].class).invoke(null,new Object[]{saved});validate(snapshot);
            boolean bootstrap;synchronized(LunarOutfitPersistence.class){bootstrap=BOOTSTRAP.contains(owner);}
            if(bootstrap&&((List<?>)call(response,"getOutfitsList")).size()>((List<?>)call(snapshot,"getOutfitsList")).size()){
                validate(response);Object fuller=reduced(response);remember(owner,(byte[])call(fuller,"toByteArray"),true);expectLogin(owner,fuller);
                event("bootstrap_updated","fits="+((List<?>)call(fuller,"getOutfitsList")).size());return response;
            }
            Object builder=call(response,"toBuilder");call(builder,"clearOutfits");builder.getClass().getMethod("addAllOutfits",Iterable.class).invoke(builder,call(snapshot,"getOutfitsList"));set(builder,"setOutfitTree",call(snapshot,"getOutfitTree"));
            Object restored=call(builder,"build");expectLogin(owner,snapshot);event("restored","scope=account-local fits="+((List<?>)call(snapshot,"getOutfitsList")).size());queueReplay(owner,snapshot,response);return restored;
        }catch(Throwable e){event("restore_failed","exception="+e.getClass().getSimpleName());return response;}
    }
    private static synchronized void expectLogin(UUID owner,Object snapshot){
        READY_SOCKETS.remove(owner);EXPECTED_LOGIN.put(owner,snapshot);EXPECTED_SOCKETS.put(owner,LunarColorRuntime.cosmeticSocket());
    }
    private static Object reduced(Object response)throws Exception{
        Object b=response.getClass().getMethod("newBuilder").invoke(null);b.getClass().getMethod("addAllOutfits",Iterable.class).invoke(b,call(response,"getOutfitsList"));set(b,"setOutfitTree",call(response,"getOutfitTree"));return call(b,"build");
    }
    private static void validate(Object response)throws Exception{
        List<?> outfits=(List<?>)call(response,"getOutfitsList");if(outfits.isEmpty()||outfits.size()>128)throw new IllegalArgumentException("Invalid Fits count");
        Object tree=call(response,"getOutfitTree");Set<Object> ids=new HashSet<Object>();
        for(Object outfit:outfits)if(!Boolean.TRUE.equals(call(outfit,"hasId"))||!ids.add(call(outfit,"getId"))||((List<?>)call(outfit,"getCosmeticsList")).size()>256)throw new IllegalArgumentException("Invalid Fit");
        if(!ids.contains(call(tree,"getDefaultOutfitId")))throw new IllegalArgumentException("Default Fit missing");
        for(Object rule:(List<?>)call(tree,"getConditionalOutfitsList"))if(!ids.contains(call(rule,"getOutfitId")))throw new IllegalArgumentException("Conditional Fit missing");
    }
    private static void queueReplay(UUID owner,Object snapshot,Object incoming)throws Exception{
        if(call(snapshot,"getOutfitsList").equals(call(incoming,"getOutfitsList"))&&call(snapshot,"getOutfitTree").equals(call(incoming,"getOutfitTree")))return;
        final long request;synchronized(LunarOutfitPersistence.class){request=++generation;}
        final List<Object> outfits=new ArrayList<Object>((List<?>)call(snapshot,"getOutfitsList"));IO.schedule(()->replay(owner,snapshot,outfits,0,request,null),250,TimeUnit.MILLISECONDS);
    }
    /** One acknowledged request at a time restores server Fits too, without replaying ownership. */
    private static void replay(UUID owner,Object snapshot,List<Object> outfits,int index,long request,Object expectedSocket){
        try{
            synchronized(LunarOutfitPersistence.class){if(request!=generation)return;}if(!enabled()||!owner.equals(account()))return;
            Object socket=LunarColorRuntime.cosmeticSocket();if(socket==null||(expectedSocket!=null&&socket!=expectedSocket)||!Boolean.TRUE.equals(call(socket,"isOpen")))return;
            URI uri=(URI)call(socket,"getURI");if(!"gomod-service.onrender.com".equalsIgnoreCase(uri.getHost())||!"/ws/game".equals(uri.getPath()))return;
            Object service=LunarColorRuntime.cosmeticService();ClassLoader loader=service.getClass().getClassLoader();boolean update=index<outfits.size();
            Class<?> type=Class.forName(PROTO+(update?"UpdateOutfitRequest":"SelectOutfitRequest"),false,loader);Object b=type.getMethod("newBuilder").invoke(null);
            set(b,update?"setOutfit":"setOutfitTree",update?outfits.get(index):call(snapshot,"getOutfitTree"));Object payload=call(b,"build");
            Class<?> callback=Class.forName("com.google.protobuf.RpcCallback",false,loader);
            Object receiver=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(p,m,args)->{
                if(!m.getName().equals("run"))return null;Object response=args==null?null:args[0];
                synchronized(LunarOutfitPersistence.class){if(request!=generation)return null;}
                if(response==null||!"STATUS_OK".equals(String.valueOf(call(response,"getStatus")))){event("sync_failed","reason=server-rejected");return null;}
                if(update)IO.schedule(()->replay(owner,snapshot,outfits,index+1,request,socket),250,TimeUnit.MILLISECONDS);else event("synced","fits="+outfits.size());return null;
            });
            Method send=service.getClass().getMethod(update?"updateOutfit":"selectOutfit",Class.forName("com.google.protobuf.RpcController",false,loader),type,callback);send.setAccessible(true);send.invoke(service,null,payload,receiver);
        }catch(Throwable e){event("sync_failed","exception="+e.getClass().getSimpleName());}
    }
    private static Object call(Object value,String method)throws Exception{Method m=value.getClass().getMethod(method);m.setAccessible(true);return m.invoke(value);}
    private static void set(Object b,String method,Object value)throws Exception{b.getClass().getMethod(method,value.getClass()).invoke(b,value);}
    private static void event(String name,String detail){LunarConnectionDiagnostics.record("lunar_fits."+name,detail);}
}
