package me.napim.gomod.features;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fit-owned appearance extensions. Only source-confirmed native Fits select the editing scope. */
public final class LunarFitAppearance {
    private static final int MAX_FITS = 128;
    private static volatile UUID owner;
    private static UUID active;
    private static long generation;
    private static boolean initialized, unreadable, migrateLegacy;
    private static Map<UUID, Style> fits = new LinkedHashMap<>();
    private static Map<UUID, Members> members = Collections.emptyMap();
    private static final Map<String, Style> PREVIEWS = new LinkedHashMap<>();
    private static final ThreadLocal<ModelScope> MODEL = new ThreadLocal<>();
    private LunarFitAppearance() { }

    private static final class Style {
        final Map<Long, GomodCosmeticAppearance.Values> cosmetics, badges;
        Style(Map<Long, GomodCosmeticAppearance.Values> cosmetics, Map<Long, GomodCosmeticAppearance.Values> badges) {
            this.cosmetics = Collections.unmodifiableMap(new TreeMap<>(cosmetics));
            this.badges = Collections.unmodifiableMap(new TreeMap<>(badges));
        }
    }
    private static final Style EMPTY = new Style(Collections.emptyMap(), Collections.emptyMap());
    private static final class Members {
        final Set<Long> cosmetics = new HashSet<>(); long badge;
    }
    private static final class ModelScope {
        final ModelScope parent; final UUID owner, fit; final Style style;
        ModelScope(ModelScope parent, UUID owner, UUID fit, Style style) { this.parent=parent; this.owner=owner; this.fit=fit; this.style=style; }
    }
    private static Path file(UUID account) {
        return ConfigManager.getGomodDataDirectory().toPath().resolve("lunar-fit-appearance/"+account+".json");
    }
    private static void load() {
        UUID account=LunarFitSharing.account(); if(Objects.equals(owner,account))return;
        owner=account; active=null; initialized=false; unreadable=false; migrateLegacy=false; ++generation;
        fits=new LinkedHashMap<>(); members=Collections.emptyMap(); PREVIEWS.clear();
        if(owner==null)return;
        try {
            Path path=file(owner); migrateLegacy=!Files.exists(path); if(migrateLegacy)return;
            if(Files.size(path)>8*1024*1024)throw new IllegalArgumentException("Fit appearance size");
            JsonObject root=new JsonParser().parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("version").getAsInt()!=1||!owner.toString().equals(root.get("account").getAsString()))throw new IllegalArgumentException("Fit appearance account");
            JsonObject records=root.getAsJsonObject("fits"); if(records.entrySet().size()>MAX_FITS)throw new IllegalArgumentException("Fit appearance count");
            for(Map.Entry<String,JsonElement> row:records.entrySet())fits.put(UUID.fromString(row.getKey()),parse(row.getValue().getAsJsonObject(),false));
        } catch(Exception invalid) {
            fits.clear(); unreadable=true;
            System.err.println("[gomod] Preserving unreadable Fit appearance file: "+invalid.getClass().getSimpleName());
        }
    }
    private static Style parse(JsonObject value,boolean shared) {
        if(value==null)return EMPTY;
        JsonArray cosmetics=value.getAsJsonArray("cosmetics"),badges=value.getAsJsonArray("badges");
        if(shared&&(cosmetics==null||cosmetics.size()>64||badges==null||badges.size()>1))throw new IllegalArgumentException("Shared appearance count");
        return new Style(GomodCosmeticAppearance.parseRows(cosmetics),GomodCosmeticAppearance.parseRows(badges));
    }
    private static JsonArray rows(Map<Long,GomodCosmeticAppearance.Values> values) {
        JsonArray result=new JsonArray(); for(Map.Entry<Long,GomodCosmeticAppearance.Values> row:values.entrySet())result.add(row.getValue().json(row.getKey()));return result;
    }
    private static JsonObject json(Style value) {
        JsonObject result=new JsonObject();result.add("cosmetics",rows(value.cosmetics));result.add("badges",rows(value.badges));return result;
    }
    private static void persist(Map<UUID,Style> next) throws Exception {
        if(unreadable)throw new IllegalStateException("Saved Fit colors could not be read. The existing file has been preserved.");
        JsonObject root=new JsonObject(),records=new JsonObject();root.addProperty("version",1);root.addProperty("account",owner.toString());
        for(Map.Entry<UUID,Style> row:next.entrySet())records.add(row.getKey().toString(),json(row.getValue()));root.add("fits",records);
        byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IllegalArgumentException("Saved Fit colors are full.");
        Path path=file(owner);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"fit-appearance-",".tmp");
        try { Files.write(temp,bytes);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);} }
        finally { Files.deleteIfExists(temp); }
        fits=next; migrateLegacy=false;
    }
    private static Object call(Object object,String method)throws Exception {return object.getClass().getMethod(method).invoke(object);}
    private static UUID id(Object proto)throws Exception {return new UUID(((Number)call(proto,"getHigh64")).longValue(),((Number)call(proto,"getLow64")).longValue());}

    /** Called alongside the existing color extension, after native login/edit validation. */
    public static void nativeSnapshot(UUID account,Object snapshot,boolean login)throws Exception {
        if(!Objects.equals(account,LunarFitSharing.account()))return;
        Map<UUID,Members> current=new LinkedHashMap<>();
        for(Object outfit:(List<?>)call(snapshot,"getOutfitsList")) {
            Members items=new Members();for(Object cosmetic:(List<?>)call(outfit,"getCosmeticsList"))items.cosmetics.add(((Number)call(cosmetic,"getCosmeticId")).longValue());
            items.badge=((Number)call(outfit,"getBadgeId")).longValue();if(current.put(id(call(outfit,"getId")),items)!=null)throw new IllegalArgumentException("Duplicate Fit");
        }
        UUID selected=id(call(call(snapshot,"getOutfitTree"),"getDefaultOutfitId"));
        if(current.size()>MAX_FITS||!current.containsKey(selected))return;
        // Read legacy stores before taking this lock. Editors acquire their own lock before this one.
        Map<Long,GomodCosmeticAppearance.Values> legacy=GomodCosmeticAppearance.legacySavedValues();
        Map<Long,GomodCosmeticAppearance.Values> badges=GomodStudioSettings.legacyBadgeValues();
        boolean selectionChanged=false;
        synchronized(LunarFitAppearance.class) {
            load();if(!Objects.equals(owner,account))return;
            Map<UUID,Style> next=new LinkedHashMap<>(fits);Style previous=active==null?EMPTY:fits.getOrDefault(active,EMPTY);
            boolean changed=false;
            for(UUID fit:current.keySet())if(!next.containsKey(fit)) {
                Style value=!initialized&&migrateLegacy&&fit.equals(selected)?new Style(legacy,badges)
                        :initialized&&!login&&!members.containsKey(fit)?previous:EMPTY;
                next.put(fit,value);changed=true;
            }
            if(initialized&&!login&&next.keySet().retainAll(current.keySet()))changed=true;
            if(!Objects.equals(active,selected)){active=selected;++generation;selectionChanged=true;}
            members=current;initialized=true;
            if(changed&&!unreadable)persist(next);else fits=next;
        }
        if(selectionChanged&&me.napim.gomod.Main.getGlobalChatClient()!=null)me.napim.gomod.Main.getGlobalChatClient().requestImmediatePoll();
    }
    public static synchronized long scope() {load();return generation;}
    public static synchronized boolean active() {load();return active!=null;}
    /** True only while the currently selected local Fit mannequin is rendering. */
    public static boolean isActiveFitModelContext() {
        ModelScope scope=MODEL.get();if(scope==null||scope.fit==null)return false;
        synchronized(LunarFitAppearance.class){load();return owner!=null&&Objects.equals(scope.owner,owner)&&Objects.equals(scope.fit,active);}
    }
    /** True while the native preview for this exact Fit is being rendered. */
    public static boolean isFitModelContext(UUID fit) {
        ModelScope scope=MODEL.get();if(scope==null||scope.fit==null||fit==null)return false;
        synchronized(LunarFitAppearance.class){load();return owner!=null&&Objects.equals(scope.owner,owner)&&Objects.equals(scope.fit,fit);}
    }
    /**
     * Some Locker detail mannequins render without an identified Fit-document scope. Let those
     * local previews show the currently edited Fit's draft; an explicit Fit scope always takes
     * precedence so another Fit's mannequin cannot inherit the draft.
     */
    public static boolean isUnscopedFitPreview(UUID fit) {
        ModelScope scope=MODEL.get();if(fit==null||scope!=null&&(scope.fit!=null||scope.style!=null))return false;
        synchronized(LunarFitAppearance.class){load();return owner!=null&&Objects.equals(owner,LunarFitSharing.account())&&members.containsKey(fit);}
    }
    public static UUID currentModelFit() {ModelScope scope=MODEL.get();return scope==null?null:scope.fit;}
    /** Native cosmetic models can be submitted now and drawn after this mannequin scope ends. */
    public static Object captureModelContext() {return MODEL.get();}
    public static UUID renderAccount() {return owner;}
    public static void beginCapturedModelContext(Object captured) {
        ModelScope original=captured instanceof ModelScope?(ModelScope)captured:null;
        synchronized(LunarFitAppearance.class) {
            load();UUID fit=null;Style style=null;
            if(original!=null&&Objects.equals(original.owner,owner)) {
                fit=original.fit;
                style=fit==null?original.style:members.containsKey(fit)?fits.getOrDefault(fit,EMPTY):EMPTY;
            }
            MODEL.set(new ModelScope(MODEL.get(),owner,fit,style));
        }
    }
    public static void endCapturedModelContext() {ModelScope scope=MODEL.get();if(scope==null||scope.parent==null)MODEL.remove();else MODEL.set(scope.parent);}
    public static synchronized UUID activeFitId() {load();return active;}
    public static synchronized boolean hasFit(UUID fit) {load();return fit!=null&&members.containsKey(fit);}
    public static synchronized Map<Long,GomodCosmeticAppearance.Values> cosmetics(UUID fit) {
        load();return fit==null||!members.containsKey(fit)?null:fits.getOrDefault(fit,EMPTY).cosmetics;
    }
    /** Offer recovery only when all Fits that wear this item have the same saved style. */
    public static synchronized GomodCosmeticAppearance.Values uniqueCosmeticStyle(long id,UUID targetFit) {
        load();Members target=members.get(targetFit);if(target==null||!target.cosmetics.contains(id))return null;
        GomodCosmeticAppearance.Values found=null;
        for(Map.Entry<UUID,Style> entry:fits.entrySet()) {
            if(Objects.equals(entry.getKey(),targetFit))continue;Members source=members.get(entry.getKey());
            if(source==null||!source.cosmetics.contains(id))continue;
            GomodCosmeticAppearance.Values value=entry.getValue().cosmetics.get(id);if(value==null)continue;
            if(found==null)found=value;
            else if(Double.compare(found.hue,value.hue)!=0||Double.compare(found.saturation,value.saturation)!=0
                    ||Double.compare(found.brightness,value.brightness)!=0||Double.compare(found.scale,value.scale)!=0)return null;
        }
        return found;
    }
    public static synchronized boolean isActiveFit(UUID fit) {load();return fit!=null&&Objects.equals(active,fit);}
    public static synchronized boolean readOnly() {load();return unreadable;}
    public static synchronized Map<Long,GomodCosmeticAppearance.Values> cosmetics() {load();return active==null?null:fits.getOrDefault(active,EMPTY).cosmetics;}
    public static synchronized Map<Long,GomodCosmeticAppearance.Values> badges() {load();return active==null?null:fits.getOrDefault(active,EMPTY).badges;}
    public static synchronized boolean save(long scope,long id,GomodCosmeticAppearance.Values value,boolean badge)throws Exception {
        load();return save(scope,active,id,value,badge);
    }
    /** Save appearance against the Fit selected by the Locker UI, even when it is not the active outfit. */
    public static synchronized boolean save(long scope,UUID fit,long id,GomodCosmeticAppearance.Values value,boolean badge)throws Exception {
        load();if(scope!=generation)throw new IllegalStateException("You switched Fits. Reopen Settings.");if(fit==null||!members.containsKey(fit))return false;
        Style old=fits.getOrDefault(fit,EMPTY);Map<Long,GomodCosmeticAppearance.Values> values=new TreeMap<>(badge?old.badges:old.cosmetics);
        if(value.isDefault())values.remove(id);else values.put(id,value);if(values.size()>GomodCosmeticAppearance.LIMIT)throw new IllegalArgumentException("Too many customized items on this Fit.");
        Style next=new Style(badge?old.cosmetics:values,badge?values:old.badges);Map<UUID,Style> records=new LinkedHashMap<>(fits);records.put(fit,next);persist(records);return true;
    }
    public static JsonObject validateShared(JsonObject value) {return json(parse(value,true));}
    public static synchronized JsonObject exportShared(UUID account,UUID fit) {
        load();if(!Objects.equals(owner,account))return json(EMPTY);Style value=fits.getOrDefault(fit,EMPTY);Members items=members.get(fit);if(items==null)return json(EMPTY);
        Map<Long,GomodCosmeticAppearance.Values> cosmetics=new TreeMap<>(),badges=new TreeMap<>();
        for(Long id:items.cosmetics)if(value.cosmetics.containsKey(id))cosmetics.put(id,value.cosmetics.get(id));
        if(value.badges.containsKey(items.badge))badges.put(items.badge,value.badges.get(items.badge));return json(new Style(cosmetics,badges));
    }
    public static synchronized void importShared(UUID account,UUID fit,JsonObject appearance)throws Exception {
        load();if(!Objects.equals(owner,account)||!members.containsKey(fit))throw new IllegalStateException("Fit is unavailable.");
        Map<UUID,Style> next=new LinkedHashMap<>(fits);next.put(fit,parse(appearance,true));persist(next);
    }
    public static synchronized void registerPreview(String model,JsonObject appearance) {
        load();while(PREVIEWS.size()>=256)PREVIEWS.remove(PREVIEWS.keySet().iterator().next());PREVIEWS.put(model,parse(appearance,true));
    }
    public static void beginModel(Object model) {
        LunarGiftPreviews.beginModel(model);
        Style value=null;UUID account,fit=null;
        synchronized(LunarFitAppearance.class) {
            load();account=owner;
            try {String id=model instanceof String?(String)model:(String)call(model,"getId");
                if(id.startsWith("GOMOD_FIT_")){fit=UUID.fromString(id.substring(10));value=fits.getOrDefault(fit,EMPTY);}
                else if(id.startsWith("OUTFIT_MANNEQUIN_")){fit=UUID.fromString(id.substring("OUTFIT_MANNEQUIN_".length()));value=fits.getOrDefault(fit,EMPTY);}
                else if(id.startsWith("GOMOD_OFFICIAL_LOADOUT_")){value=EMPTY;}
                else value=PREVIEWS.get(id);
            }catch(Exception ignored) { }
        }
        MODEL.set(new ModelScope(MODEL.get(),account,fit,value));
    }
    public static void endModel() {LunarGiftPreviews.endModel();ModelScope scope=MODEL.get();if(scope==null||scope.parent==null)MODEL.remove();else MODEL.set(scope.parent);}
    public static synchronized void removePreview(String model){PREVIEWS.remove(model);}
    public static GomodCosmeticAppearance.Values preview(long id,boolean badge) {
        ModelScope scope=MODEL.get();return scope==null||scope.style==null||!Objects.equals(scope.owner,LunarFitSharing.account())?null
                :(badge?scope.style.badges:scope.style.cosmetics).getOrDefault(id,GomodCosmeticAppearance.DEFAULT);
    }
}
