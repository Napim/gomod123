package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;

/** A render scope owns its copied textures; native texture objects and animation/emissive metadata stay intact. */
public final class LunarCosmeticAppearanceRenderer {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Boolean>> IMPLICIT_MODEL_DRAWS = new ThreadLocal<ArrayDeque<Boolean>>() {
        @Override protected ArrayDeque<Boolean> initialValue() { return new ArrayDeque<Boolean>(); }
    };
    private static final ThreadLocal<CloakFrame> CLOAK = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> FILTERING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final LinkedHashMap<String, Texture> TEXTURES = new LinkedHashMap<>(32, .75f, true);
    private static final Map<Integer, Integer> SOURCE_TEXTURES = new HashMap<>();
    private static final Object NO_PLAYER_COSMETIC_MATCH = new Object();
    private static final Map<Object,DrawBinding> DRAW_BINDINGS=Collections.synchronizedMap(new WeakHashMap<Object,DrawBinding>());
    private static final Map<NativeRendererEntryKey,Object> NATIVE_RENDER_ENTRIES=
            Collections.synchronizedMap(new LinkedHashMap<NativeRendererEntryKey,Object>(32,.75f,true) {
                @Override protected boolean removeEldestEntry(Map.Entry<NativeRendererEntryKey,Object> eldest) {
                    return size()>256;
                }
            });
    private static final Set<String> REPORTED_SCOPES = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final java.util.concurrent.ConcurrentHashMap<UUID,HatStackLayout> HAT_STACK_LAYOUTS=
            new java.util.concurrent.ConcurrentHashMap<UUID,HatStackLayout>();
    private static final long HAT_STACK_LAYOUT_REFRESH_NANOS=100_000_000L;
    private static long pixels, retryAt, nextCleanup;
    private static final ClassValue<java.lang.reflect.Method> OWNER = new ClassValue<java.lang.reflect.Method>() {
        @Override protected java.lang.reflect.Method computeValue(Class<?> type) {
            try { return type.getMethod("bridge$getUniqueID"); }
            catch (Exception failure) { throw new IllegalArgumentException("Missing player bridge", failure); }
        }
    };
    private static final ClassValue<Optional<java.lang.reflect.Method>> DUMMY = new ClassValue<Optional<java.lang.reflect.Method>>() {
        @Override protected Optional<java.lang.reflect.Method> computeValue(Class<?> type) {
            try { return Optional.of(type.getMethod("getDummyPlayerType")); }
            catch (Exception unavailable) { return Optional.empty(); }
        }
    };
    private static final ClassValue<java.lang.reflect.Method> WORN = new ClassValue<java.lang.reflect.Method>() {
        @Override protected java.lang.reflect.Method computeValue(Class<?> type) {
            try { return type.getMethod("bridge$getWornCosmetics"); }
            catch (Exception failure) { throw new IllegalArgumentException("Missing worn cosmetics bridge", failure); }
        }
    };
    private static final ClassValue<Optional<Method>> COSMETIC_PROFILE_GETTERS = new ClassValue<Optional<Method>>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try { return Optional.of(type.getMethod("bridge$getCosmetics")); }
            catch (ReflectiveOperationException | SecurityException unavailable) { return Optional.empty(); }
        }
    };
    private static final ClassValue<Optional<Class<?>>> CONSUMER_ENTRY_TYPES = new ClassValue<Optional<Class<?>>>() {
        @Override protected Optional<Class<?>> computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("forEach", Consumer.class);
                Type signature = method.getGenericParameterTypes()[0];
                if (!(signature instanceof ParameterizedType)) return Optional.empty();
                Type entry = ((ParameterizedType) signature).getActualTypeArguments()[0];
                if (entry instanceof ParameterizedType) entry = ((ParameterizedType) entry).getRawType();
                if (entry instanceof Class<?>) return Optional.of((Class<?>) entry);
            } catch (RuntimeException | ReflectiveOperationException | LinkageError unavailable) { }
            return Optional.empty();
        }
    };
    private LunarCosmeticAppearanceRenderer() { }

    /** Retain identity and Fit context across Lunar's model submission/flush boundary, never frozen slider values. */
    public static void rememberDraw(Object draw,Object cosmetic,Object player) {
        rememberDraw(draw,cosmetic,player,false);
    }
    public static void rememberPreviewDraw(Object draw,Object cosmetic,Object player) {
        rememberDraw(draw,cosmetic,player,true);
    }
    private static void rememberDraw(Object draw,Object cosmetic,Object player,boolean preview) {
        if(draw==null||cosmetic==null)return;
        synchronized(DRAW_BINDINGS) {
            if(DRAW_BINDINGS.size()>=4096)DRAW_BINDINGS.clear();
            DRAW_BINDINGS.put(draw,new DrawBinding(cosmetic,player,LunarFitAppearance.captureModelContext(),LunarFitAppearance.renderAccount(),preview));
        }
    }
    public static void beginDeferredDraw(Object draw) {
        beginDeferredDraw(draw,null,null);
    }
    /** Lunar applies transforms in a short matrix-dispatch callback, then draws this retained model afterward. */
    public static void beginDeferredDraw(Object draw,Object matrixContext) {
        beginDeferredDraw(draw,matrixContext,null);
    }
    /** The native draw entry is an identity fallback for renderer paths whose factory cannot retain the wrapper. */
    public static void beginDeferredDraw(Object draw,Object matrixContext,Object nativeEntry) {
        beginDeferredDraw(draw,matrixContext,nativeEntry,false);
    }
    /** Current Lunar's deferred matrix path uses native cosmetic placement without the optional stack offset. */
    public static void beginCurrentDeferredDraw(Object draw,Object matrixContext,Object nativeEntry) {
        beginDeferredDraw(draw,matrixContext,nativeEntry,true);
    }
    private static void beginDeferredDraw(Object draw,Object matrixContext,Object nativeEntry,boolean suppressStackOffset) {
        DrawBinding binding=DRAW_BINDINGS.get(draw);
        if(binding!=null&&Objects.equals(binding.account,LunarFitAppearance.renderAccount())) {
            LunarFitAppearance.beginCapturedModelContext(binding.modelContext);
            Scope before=CURRENT.get();
            try {
                begin(binding.cosmetic,binding.player,binding.preview);Scope scope=CURRENT.get();scope.deferredModel=true;scope.draw=draw;
                scope.suppressStackOffset=suppressStackOffset;
                scope.deferredSizeContext=LunarCosmeticSizeRenderer.beginDeferredDrawContext(matrixContext);
                if (!scope.preview && !"CLOAK".equalsIgnoreCase(scope.category)) {
                    String hostOpacityPhase=SelfOpacity.renderPhaseForDiagnostics();
                    int opacityMode=LunarExternalOpacityBridge.beginLunarCosmetic(binding.player);
                    scope.externalOpacityScope=true;
                    if(opacityMode!=0&&scope.id>0)LunarConnectionDiagnostics.recordOnce(
                            "cosmetics.appearance.deferred_opacity",scope.id+":"+hostOpacityPhase+":"+opacityMode,
                            "id="+scope.id+" category="+scope.category+" hostPhase="+hostOpacityPhase
                                    +" opacityMode="+opacityMode+" deferred=true");
                }
                if(scope.id>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_draw",scope.id+":"+scope.owner,
                        "id="+scope.id+" category="+scope.category+" owner="+(scope.owner==null?"local-display":"player")
                                +" fitId="+LunarFitAppearance.currentModelFit()+" customized="+!scope.values.isDefault()
                                +" matrixContext="+(matrixContext==null?"none":matrixContext.getClass().getName())
                                +" sizeContextRetained="+scope.deferredSizeContext
                                +" stackOffsetSuppressed="+scope.suppressStackOffset);
                return;
            } catch(Throwable failure) {
                Scope opened=CURRENT.get();
                if(opened!=null&&opened!=before&&opened.deferredModel)end();
                else LunarFitAppearance.endCapturedModelContext();
                throw failure;
            }
        }
        EntityPlayer renderPlayer=LunarCosmeticRenderContext.currentPlayer();
        Object stackedHat=findStackedHat(nativeEntry,renderPlayer);
        if(stackedHat!=null&&beginDeferredEntryDraw(stackedHat,renderPlayer,matrixContext,suppressStackOffset))return;
        Scope parent=CURRENT.get();refreshDraft(parent);
        Scope scope=parent==null?new Scope(null,null,0,"unknown",GomodCosmeticAppearance.DEFAULT,0L)
                :new Scope(parent,parent.owner,parent.id,parent.category,parent.values,parent.previewRevision);
        scope.suppressStackOffset=suppressStackOffset;
        CURRENT.set(scope);
        LunarCosmeticSizeRenderer.beginAppearanceScope();
        scope.deferredSizeContext=LunarCosmeticSizeRenderer.beginDeferredDrawContext(matrixContext);
    }

    /**
     * Lunar's renderer bundle keeps only the first cosmetic in each slot. The renderer callsite
     * supplies the actual player, so additional equipped hats can use its native callback too.
     */
    @SuppressWarnings("unchecked")
    public static Consumer<?> wrapMultiSameTypeHatConsumer(Object collection,Consumer<?> consumer,Object player) {
        boolean enabled=ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics();
        if (!enabled && !LunarConnectionDiagnostics.isEnabled()) return consumer;
        boolean renderingName=LunarCosmeticRenderContext.isRenderingName();
        if(collection==null||consumer==null||!enabled||renderingName) {
            String reason=collection==null?"missing_collection":consumer==null?"missing_consumer":!enabled?"disabled":"name_render";
            String collectionName=collection==null?"null":collection.getClass().getName();
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                    collectionName+":"+reason,"result=skipped reason="+reason+" enabled="+enabled
                            +" renderingName="+renderingName);
            return consumer;
        }
        if(player==null) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                    collection.getClass().getName()+":missing-renderer-player",
                    "result=skipped reason=missing_renderer_player_argument");
            return consumer;
        }
        boolean displayPlayer=isDisplayPlayer(player);
        Class<?> entryType=consumerEntryType(collection);
        if(entryType==null) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                    collection.getClass().getName(),"result=skipped reason=consumer_entry_type_unavailable");
            return consumer;
        }
        try {
            UUID owner=null;
            try {
                Object nativeOwner=OWNER.get(player.getClass()).invoke(player);
                if(nativeOwner instanceof UUID)owner=(UUID)nativeOwner;
            } catch(Exception unavailableOwner) { }
            if(owner==null&&displayPlayer)owner=localPlayerId();
            Object wornPlayer=player;
            String wornSource="renderer_player_local";
            Object raw;
            try { raw=WORN.get(wornPlayer.getClass()).invoke(wornPlayer); }
            catch(Exception unavailableWornList) {
                EntityPlayer renderContext=LunarCosmeticRenderContext.currentPlayer();
                if(renderContext==null) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                            collection.getClass().getName()+":worn_list_unavailable:"+player.getClass().getName(),
                            "result=skipped reason=worn_list_unavailable playerClass="+player.getClass().getName()
                                    +" source="+wornSource+" exception="+unavailableWornList.getClass().getSimpleName());
                    return consumer;
                }
                wornPlayer=renderContext;raw=WORN.get(wornPlayer.getClass()).invoke(wornPlayer);
                if(owner==null)owner=renderContext.getUniqueID();
                displayPlayer=isDisplayPlayer(wornPlayer);wornSource="render_context_fallback";
            }
            if(!(raw instanceof List)) {
                String rawClass=raw==null?"null":raw.getClass().getName();
                LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                        collection.getClass().getName()+":worn_list_not_list:"+rawClass,
                        "result=skipped reason=worn_list_not_list source="+wornSource
                                +" listClass="+rawClass);
                return consumer;
            }
            List<?> worn=(List<?>)raw;
            if(worn.size()<2||worn.size()>256) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                        collection.getClass().getName()+":worn_list_size:"+displayPlayer+":"+worn.size(),
                        "result=skipped reason=worn_list_size count="+worn.size()+" display="+displayPlayer
                                +" source="+wornSource+" playerClass="+wornPlayer.getClass().getName());
                return consumer;
            }
            Optional<Method> profileGetter = COSMETIC_PROFILE_GETTERS.get(player.getClass());
            if (!profileGetter.isPresent()) return consumer;
            Object profile=profileGetter.get().invoke(player);
            if(profile==null)return consumer;
            LinkedHashMap<String,LinkedHashMap<Long,Object>> bySlot=new LinkedHashMap<String,LinkedHashMap<Long,Object>>();
            for(Object candidate:worn) {
                if(candidate==null)continue;
                try {
                    String slot=wearableSlotKey(LunarCosmeticRuntime.category(candidate));
                    if(slot==null)continue;
                    long id=LunarCosmeticRuntime.id(candidate);
                    if(id<=0L)continue;
                    LinkedHashMap<Long,Object> slotItems=bySlot.get(slot);
                    if(slotItems==null) {
                        slotItems=new LinkedHashMap<Long,Object>();
                        bySlot.put(slot,slotItems);
                    }
                    if(!slotItems.containsKey(Long.valueOf(id)))slotItems.put(Long.valueOf(id),candidate);
                } catch(ThreadDeath fatal) { throw fatal; }
                catch(VirtualMachineError fatal) { throw fatal; }
                catch(Throwable ignored) { }
            }
            LinkedHashMap<Long,Object> overflowRenderables=new LinkedHashMap<Long,Object>();
            List<Long> overflowCandidateIds=new ArrayList<Long>();
            List<Long> conversionFailedIds=new ArrayList<Long>();
            List<String> overflowSlots=new ArrayList<String>();
            for(Map.Entry<String,LinkedHashMap<Long,Object>> slotEntry:bySlot.entrySet()) {
                String slot=slotEntry.getKey();
                LinkedHashMap<Long,Object> slotItems=slotEntry.getValue();
                if(slotItems.size()<=wearableSlotCapacity(slot))continue;
                overflowSlots.add(slot);
                for(Map.Entry<Long,Object> itemEntry:slotItems.entrySet()) {
                    long id=itemEntry.getKey().longValue();
                    Object candidate=itemEntry.getValue();
                    if(!overflowCandidateIds.contains(Long.valueOf(id)))overflowCandidateIds.add(Long.valueOf(id));
                    if(overflowRenderables.containsKey(Long.valueOf(id)))continue;
                    try {
                        Object renderable=nativeRendererEntry(entryType,profile.getClass(),candidate,owner,id);
                        if(entryType.isInstance(renderable))overflowRenderables.put(Long.valueOf(id),renderable);
                        else conversionFailedIds.add(Long.valueOf(id));
                    } catch(ThreadDeath fatal) { throw fatal; }
                    catch(VirtualMachineError fatal) { throw fatal; }
                    catch(Throwable unsupported) {
                        if(!conversionFailedIds.contains(Long.valueOf(id)))conversionFailedIds.add(Long.valueOf(id));
                        Throwable cause=unsupported.getCause();
                        LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                                collection.getClass().getName()+":entry_conversion:"+id+":"+unsupported.getClass().getName(),
                                "result=skipped reason=renderer_entry_conversion id="+id+" slot="+slot+" itemClass="
                                        +candidate.getClass().getName()+" exception="+unsupported.getClass().getSimpleName()
                                        +(cause==null?"":" cause="+cause.getClass().getSimpleName()));
                    }
                }
            }
            if(overflowSlots.isEmpty()||overflowRenderables.size()<2) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                        collection.getClass().getName()+":no_overflow_wearables:"+displayPlayer,
                        "result=skipped reason=no_overflow_wearables rawCount="+worn.size()
                                +" overflowSlots="+overflowSlots+" overflowCandidateIds="+overflowCandidateIds
                                +" usableIds="+overflowRenderables.keySet()
                                +" conversionFailedIds="+conversionFailedIds+" entryType="+entryType.getName()
                                +" display="+displayPlayer+" source="+wornSource);
                return consumer;
            }
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_armed",
                    (owner==null?player.getClass().getName():owner.toString())+":"+displayPlayer+":"+overflowSlots,
                    "owner="+owner+" display="+displayPlayer+" source="
                            +wornSource+" wornCount="+worn.size()+" overflowSlots="+overflowSlots
                            +" equippedIds="+overflowRenderables.keySet());
            return new MultiSameTypeHatConsumer((Consumer<Object>)consumer,owner,displayPlayer,
                    new ArrayList<Object>(overflowRenderables.values()));
        } catch(ThreadDeath fatal) { throw fatal; }
        catch(VirtualMachineError fatal) { throw fatal; }
        catch(Throwable unavailable) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_probe",
                    collection.getClass().getName()+":"+unavailable.getClass().getName(),
                    "result=skipped reason=renderer_player_bridge_unavailable playerClass="+player.getClass().getName()
                            +" exception="+unavailable.getClass().getSimpleName());
            return consumer;
        }
    }

    public static void finishMultiSameTypeHatConsumer(Consumer<?> consumer) {
        if(consumer instanceof MultiSameTypeHatConsumer)((MultiSameTypeHatConsumer)consumer).finish();
    }

    private static Object nativeRendererEntry(Class<?> entryType,Class<?> profileType,Object candidate,UUID owner,long id) throws Exception {
        if(entryType.isInstance(candidate))return candidate;
        Object definition=LunarCosmeticRuntime.definition(candidate);
        if(entryType.isInstance(definition))return definition;
        if(owner==null)throw new IllegalStateException("Missing cosmetic owner UUID");
        NativeRendererEntryKey key=new NativeRendererEntryKey(entryType,owner,id,candidate);
        synchronized(NATIVE_RENDER_ENTRIES) {
            Object cached=NATIVE_RENDER_ENTRIES.get(key);
            if(cached!=null)return cached;
        }
        Method factory=null;
        for(Method method:profileType.getDeclaredMethods()) {
            Class<?>[] parameters=method.getParameterTypes();
            if(!java.lang.reflect.Modifier.isStatic(method.getModifiers())||parameters.length!=2
                    ||parameters[1]!=UUID.class||!entryType.isAssignableFrom(method.getReturnType()))continue;
            if(factory!=null)throw new IllegalStateException("Ambiguous native cosmetic entry factory");
            factory=method;
        }
        if(factory==null)throw new NoSuchMethodException("Missing native cosmetic entry factory");
        Class<?> modelType=factory.getParameterTypes()[0];
        Object model=modelType.isInstance(candidate)?candidate:null;
        if(model==null)for(java.lang.reflect.Constructor<?> modelConstructor:modelType.getDeclaredConstructors()) {
                Class<?>[] modelParameters=modelConstructor.getParameterTypes();
                if(modelParameters.length!=2||!modelParameters[0].isInstance(definition)
                        ||!modelParameters[1].getName().equals("com.google.gson.JsonObject"))continue;
                modelConstructor.setAccessible(true);
                model=modelConstructor.newInstance(definition,null);break;
        }
        if(model==null)throw new NoSuchMethodException("Missing native cosmetic model constructor");
        factory.setAccessible(true);
        Object rendered=factory.invoke(null,model,owner);
        if(!entryType.isInstance(rendered))throw new IllegalStateException("Native entry factory returned an incompatible value");
        synchronized(NATIVE_RENDER_ENTRIES) {
            Object existing=NATIVE_RENDER_ENTRIES.get(key);
            if(existing!=null)return existing;
            NATIVE_RENDER_ENTRIES.put(key,rendered);
        }
        LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.renderer_entry",owner+":"+id,
                "id="+id+" entryClass="+rendered.getClass().getName()+" sourceModelPreserved="+(model==candidate)
                        +" strategy=native_category_factory");
        return rendered;
    }

    private static final class NativeRendererEntryKey {
        final Class<?> entryType;final UUID owner;final long id;final Object source;
        NativeRendererEntryKey(Class<?> entryType,UUID owner,long id,Object source){this.entryType=entryType;this.owner=owner;this.id=id;this.source=source;}
        @Override public int hashCode(){return 31*(31*(31*entryType.hashCode()+owner.hashCode())+Long.valueOf(id).hashCode())+System.identityHashCode(source);}
        @Override public boolean equals(Object value) {
            if(this==value)return true;
            if(!(value instanceof NativeRendererEntryKey))return false;
            NativeRendererEntryKey other=(NativeRendererEntryKey)value;
            return entryType==other.entryType&&owner.equals(other.owner)&&id==other.id&&source==other.source;
        }
    }

    private static Class<?> consumerEntryType(Object collection) {
        return CONSUMER_ENTRY_TYPES.get(collection.getClass()).orElse(null);
    }

    static String wearableSlotKey(String category) {
        if(category==null||category.isEmpty())return null;
        String upper=category.toUpperCase(Locale.ROOT);
        if("CLOAK".equals(upper)||"COMPANION".equals(upper)||"DEV_COSMETICS".equals(upper)
                ||"ITEM".equals(upper)||"SWORD".equals(upper)||"PICKAXE".equals(upper)
                ||"AXE".equals(upper)||"SHOVEL".equals(upper)||"HOE".equals(upper)
                ||"HAND".equals(upper)||"EMOTE".equals(upper)||"SPRAY".equals(upper)
                ||"BADGE".equals(upper))return null;
        if("HEADWEAR".equals(upper))return "HAT";
        if("BODYWEAR_UI".equals(upper))return "BODYWEAR";
        return upper;
    }

    static int wearableSlotCapacity(String slotKey) {
        return "PET".equals(slotKey)?2:1;
    }

    private static String wearableCategory(Object item) {
        if(item==null)return "UNKNOWN";
        try {
            String cat=LunarCosmeticRuntime.category(item);
            if(cat!=null&&!cat.isEmpty())return cat;
        } catch(Exception unavailable) { }
        return "UNKNOWN";
    }

    private static long hatId(Object item) {
        if(item==null)return 0L;
        try {return wearableSlotKey(LunarCosmeticRuntime.category(item))!=null
                ?LunarCosmeticRuntime.id(item):0L;} catch(Exception unavailable) { }
        // Native category subclasses can add another path to the same definition. Their common
        // base exposes the source model directly, which also preserves its customization data.
        try {
            for(Class<?> type=item.getClass();type!=null&&type!=Object.class;type=type.getSuperclass())
                for(java.lang.reflect.Constructor<?> constructor:type.getDeclaredConstructors()) {
                    Class<?>[] parameters=constructor.getParameterTypes();
                    if(parameters.length!=2||parameters[1]!=UUID.class)continue;
                    Method getter=null;
                    for(Method method:item.getClass().getMethods())if(!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            &&method.getParameterTypes().length==0&&method.getReturnType()==parameters[0]) {
                        if(getter!=null)throw new IllegalStateException("Ambiguous cosmetic source model getter");
                        getter=method;
                    }
                    if(getter!=null) {
                        Object model=getter.invoke(item);
                        return wearableSlotKey(LunarCosmeticRuntime.category(model))!=null?LunarCosmeticRuntime.id(model):0L;
                    }
                }
        } catch(Exception unavailable) { }
        return 0L;
    }

    private static final class MultiSameTypeHatConsumer implements Consumer<Object> {
        private final Consumer<Object> delegate;
        private final UUID owner;
        private final boolean displayPlayer;
        private final List<Object> extras;
        private final Set<Long> seen=new HashSet<Long>();
        private boolean finished;
        MultiSameTypeHatConsumer(Consumer<Object> delegate,UUID owner,boolean displayPlayer,List<Object> extras) {
            this.delegate=delegate;this.owner=owner;this.displayPlayer=displayPlayer;this.extras=extras;
        }
        @Override public void accept(Object cosmetic) {
            long id=hatId(cosmetic);
            if(id>0L)seen.add(Long.valueOf(id));
            delegate.accept(cosmetic);
        }
        void finish() {
            if(finished)return;
            finished=true;
            if(!ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics())return;
            for(Object extra:extras) {
                long id=hatId(extra);
                if(id<=0L||!seen.add(Long.valueOf(id)))continue;
                try {
                    delegate.accept(extra);
                    LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra",
                            String.valueOf(owner)+":"+displayPlayer+":"+id,
                            "id="+id+" category="+wearableCategory(extra)+" owner="+owner+" display="+displayPlayer
                                    +" entryClass="+extra.getClass().getName()+" result=native_consumer_returned");
                }
                catch(ThreadDeath fatal) {throw fatal;}
                catch(VirtualMachineError fatal) {throw fatal;}
                catch(Throwable failure) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_extra_failed",
                            String.valueOf(owner)+":"+id+":"+failure.getClass().getName(),
                            "id="+id+" exception="+failure.getClass().getSimpleName());
                }
            }
        }
    }
    /** Resolve only an exact, multiply equipped wearable entry from this player's current render frame. */
    private static Object findStackedHat(Object nativeEntry,EntityPlayer player) {
        if(!ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics()||nativeEntry==null)return null;
        long entryId;
        String entrySlot;
        try {
            entrySlot=wearableSlotKey(LunarCosmeticRuntime.category(nativeEntry));
            if(entrySlot==null)return null;
            entryId=LunarCosmeticRuntime.id(nativeEntry);if(entryId<=0L)return null;
        } catch(Throwable unavailable) { return null; }
        if(player==null) {
            recordStackedHatEntryProbe(entryId,"missing_player_render_context",0);
            return null;
        }
        try {
            Object raw=WORN.get(player.getClass()).invoke(player);
            if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>256) {
                recordStackedHatEntryProbe(entryId,"worn_list_unavailable",0);return null;
            }
            Object match=null;int sameSlotCount=0;
            for(Object candidate:(List<?>)raw) {
                if(candidate==null||!entrySlot.equals(wearableSlotKey(LunarCosmeticRuntime.category(candidate))))continue;
                sameSlotCount++;
                if(LunarCosmeticRuntime.id(candidate)==entryId) {
                    if(match!=null) {recordStackedHatEntryProbe(entryId,"duplicate_worn_id",sameSlotCount);return null;}
                    match=candidate;
                }
            }
            if(sameSlotCount<=wearableSlotCapacity(entrySlot)) {recordStackedHatEntryProbe(entryId,"not_stacked",sameSlotCount);return null;}
            if(match==null) {recordStackedHatEntryProbe(entryId,"entry_not_in_worn_list",sameSlotCount);return null;}
            return match;
        } catch(Throwable unavailable) {
            recordStackedHatEntryProbe(entryId,"worn_list_inspection_failed",0);return null;
        }
    }
    private static void recordStackedHatEntryProbe(long id,String result,int hats) {
        LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_entry_probe",id+":"+result,
                "id="+id+" category=HAT result="+result+" wornHatCount="+hats);
    }
    private static boolean beginDeferredEntryDraw(Object cosmetic,EntityPlayer player,Object matrixContext,boolean suppressStackOffset) {
        long expectedId;
        try { expectedId=LunarCosmeticRuntime.id(cosmetic); }
        catch(Throwable unavailable) { return false; }
        Object modelContext=LunarFitAppearance.captureModelContext();
        LunarFitAppearance.beginCapturedModelContext(modelContext);
        Scope before=CURRENT.get();
        try {
            begin(cosmetic,player);
            Scope scope=CURRENT.get();
            if(scope==null||scope==before||scope.id!=expectedId||!"HAT".equalsIgnoreCase(scope.category)) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_entry_probe",
                        expectedId+":scope_mismatch","id="+expectedId+" category=HAT result=scope_mismatch"
                                +" resolvedId="+(scope==null?0:scope.id));
                if(scope!=null&&scope!=before) { scope.deferredModel=true;end(); }
                else LunarFitAppearance.endCapturedModelContext();
                return false;
            }
            scope.suppressStackOffset=suppressStackOffset;
            scope.deferredModel=true;
            scope.deferredSizeContext=LunarCosmeticSizeRenderer.beginDeferredDrawContext(matrixContext);
            if (!scope.preview) {
                String hostOpacityPhase=SelfOpacity.renderPhaseForDiagnostics();
                int opacityMode=LunarExternalOpacityBridge.beginLunarCosmetic(player);
                scope.externalOpacityScope=true;
                if(opacityMode!=0&&scope.id>0)LunarConnectionDiagnostics.recordOnce(
                        "cosmetics.appearance.deferred_opacity",scope.id+":"+hostOpacityPhase+":"+opacityMode,
                        "id="+scope.id+" category=HAT hostPhase="+hostOpacityPhase
                                +" opacityMode="+opacityMode+" deferred=true entryFallback=true");
            }
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_entry_fallback",
                    Long.toString(scope.id),"id="+scope.id+" category=HAT result=exact_current_worn_match");
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_draw",scope.id+":"+scope.owner,
                    "id="+scope.id+" category=HAT owner="+(scope.owner==null?"local-display":"player")
                            +" fitId="+LunarFitAppearance.currentModelFit()+" customized="+!scope.values.isDefault()
                            +" matrixContext="+(matrixContext==null?"none":matrixContext.getClass().getName())
                            +" sizeContextRetained="+scope.deferredSizeContext
                            +" stackOffsetSuppressed="+scope.suppressStackOffset+" entryFallback=true");
            return true;
        } catch(Throwable failure) {
            Scope opened=CURRENT.get();
            if(opened!=null&&opened!=before) { opened.deferredModel=true;try{end();}catch(Throwable ignored){ } }
            else LunarFitAppearance.endCapturedModelContext();
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_entry_fallback_failed",
                    expectedId+":"+failure.getClass().getName(),
                    "id="+expectedId+" exception="+failure.getClass().getSimpleName());
            return false;
        }
    }
    private static final class DrawBinding {
        final Object cosmetic,player,modelContext;final UUID account;final boolean preview;
        DrawBinding(Object cosmetic,Object player,Object modelContext,UUID account,boolean preview){this.cosmetic=cosmetic;this.player=player;this.modelContext=modelContext;this.account=account;this.preview=preview;}
    }

    /** The cloth renderer selects its cosmetic internally, outside the ordinary cosmetic model scopes. */
    public static boolean beginCloakRender(Object player) {
        boolean external = false;
        String localOwner = localClothOwner(player);
        if (!isDisplayPlayer(player)) {
            if (LunarExternalOpacityBridge.beginLunarCloak(player)) {
                if (localOwner != null) {
                    GomodLunarPlusLocker.updateLocalWornClothCloaks(localOwner, Collections.<Integer>emptySet());
                }
                LunarConnectionDiagnostics.recordOnce("cosmetics.cloak.source_suppressed", "gomod",
                        "source=gomod nativeClothDrawSkipped=true");
                return true;
            }
            external = true;
        }
        CLOAK.set(new CloakFrame(CLOAK.get(), player, external, localOwner));
        return false;
    }

    /**
     * The non-cloth cape path is rendered by Minecraft's LayerCape and does not enter Lunar's
     * cloth texture hooks. Open a Fit-scoped color scope around that layer, selecting the exact
     * cloak from the active Lunar frame or the player's worn list.
     */
    public static boolean beginNoClothCapeLayer(Object player) {
        if(player==null||ManualGomodCapeRenderer.isNativeLayerPass())return false;
        Object cosmetic=null;
        CloakFrame frame=CLOAK.get();
        if(frame!=null&&frame.player==player&&frame.cosmetic!=null)cosmetic=frame.cosmetic;
        if(cosmetic==null) {
            try {
                Object raw=WORN.get(player.getClass()).invoke(player);
                if(!(raw instanceof List)||((List<?>)raw).isEmpty()||((List<?>)raw).size()>256)return false;
                long selectedId=0L;
                for(Object candidate:(List<?>)raw) {
                    if(candidate==null)continue;
                    try {
                        if(!"CLOAK".equalsIgnoreCase(LunarCosmeticRuntime.category(candidate)))continue;
                        long id=LunarCosmeticRuntime.id(candidate);
                        if(id<=0L)continue;
                        if(cosmetic!=null&&selectedId!=id)return false;
                        cosmetic=candidate;selectedId=id;
                    } catch(Throwable unsupportedCosmetic) { }
                }
            } catch(Throwable unavailable) {
                return false;
            }
        }
        if(cosmetic==null)return false;
        long id;
        try {
            if(!"CLOAK".equalsIgnoreCase(LunarCosmeticRuntime.category(cosmetic)))return false;
            id=LunarCosmeticRuntime.id(cosmetic);
        } catch(Throwable unsupportedCosmetic) { return false; }
        if(id<=0L)return false;
        Scope parent=CURRENT.get();
        if(parent!=null&&parent.id==id&&"CLOAK".equalsIgnoreCase(parent.category)
                &&sameCloakOwner(parent,player))return false;
        try {
            begin(cosmetic,player);
            Scope opened=CURRENT.get();
            if(opened==null||opened==parent||opened.id!=id||!"CLOAK".equalsIgnoreCase(opened.category)) {
                if(opened!=null&&opened!=parent)end();
                return false;
            }
            UUID fitContext=opened.owner==null?LunarFitAppearance.currentModelFit()
                    :opened.owner.equals(localPlayerId())?LunarFitAppearance.activeFitId():null;
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.noncloth_cape_scope",
                    opened.owner+":"+fitContext+":"+id,
                    "id="+id+" category=CLOAK owner="+(opened.owner==null?"local-display":"player")
                            +" fitId="+fitContext+" result=active");
            return true;
        } catch(Throwable failure) {
            Scope opened=CURRENT.get();if(opened!=null&&opened!=parent)try{end();}catch(Throwable ignored){ }
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.noncloth_cape_scope_failed",
                    id+":"+failure.getClass().getName(),
                    "id="+id+" exception="+failure.getClass().getSimpleName());
            return false;
        }
    }

    public static void endNoClothCapeLayer() {
        end();
    }

    public static void selectCloak(Object cosmetic, Object player) {
        CloakFrame frame = CLOAK.get();
        if (frame == null || frame.player != player || cosmetic == null) return;
        frame.cosmetic = null;
        try {
            if ("CLOAK".equals(LunarCosmeticRuntime.category(cosmetic))) {
                frame.cosmetic = cosmetic;
                if (frame.localOwner != null) {
                    long id = LunarCosmeticRuntime.id(cosmetic);
                    if (id > 0L && id <= Integer.MAX_VALUE) {
                        GomodLunarPlusLocker.rememberLocalClothCloak((int) id);
                        frame.localWornCloaks.add(Integer.valueOf((int) id));
                    }
                }
            }
        } catch (Exception ignored) { }
    }
    public static void endCloakRender() {
        CloakFrame frame = CLOAK.get(); if (frame == null) return;
        if (frame.localOwner != null) {
            if (frame.parent != null && frame.localOwner.equals(frame.parent.localOwner)) {
                frame.parent.localWornCloaks.addAll(frame.localWornCloaks);
            } else {
                GomodLunarPlusLocker.updateLocalWornClothCloaks(frame.localOwner, frame.localWornCloaks);
            }
        }
        if (frame.parent == null) CLOAK.remove(); else CLOAK.set(frame.parent);
        if (frame.external) LunarExternalOpacityBridge.endLunarCloak();
    }
    /** Scopes the native cloth draw and the ordinary cloak texture/shader helper to the exact worn item. */
    public static void beginCloakTexture(Object player, Object resource) {
        Scope parent = CURRENT.get(); CloakFrame frame = CLOAK.get();
        if (!ManualGomodCapeRenderer.isNativeLayerPass()) {
            if (frame != null && frame.player == player && frame.cosmetic != null) {
                begin(frame.cosmetic, player); return;
            }
            if (parent != null && parent.id > 0 && "CLOAK".equals(parent.category) && sameCloakOwner(parent, player)) {
                CURRENT.set(new Scope(parent, parent.owner, parent.id, parent.category, parent.values,parent.previewRevision));
                LunarCosmeticSizeRenderer.beginAppearanceScope(); return;
            }
            try {
                Object worn = player == null ? null : WORN.get(player.getClass()).invoke(player);
                if (worn instanceof List<?> && ((List<?>)worn).size() <= 256) {
                    UUID owner = (UUID)OWNER.get(player.getClass()).invoke(player);
                    Object selected = null; long selectedId = 0;
                    for (Object cosmetic : (List<?>)worn) {
                        try {
                            if (cosmetic == null || !"CLOAK".equals(LunarCosmeticRuntime.category(cosmetic))
                                    || !Objects.equals(resource, LunarCosmeticRuntime.resource(cosmetic, owner))) continue;
                            long id = LunarCosmeticRuntime.id(cosmetic);
                            if (selected != null && selectedId != id) { selected = null; break; }
                            selected = cosmetic; selectedId = id;
                        } catch (Exception unsupportedItem) { }
                    }
                    if (selected != null) { begin(selected, player); return; }
                }
            } catch (Exception unavailable) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.cloak.resource_lookup", unavailable.getClass().getName(),
                        "result=unavailable exception=" + unavailable.getClass().getSimpleName());
            }
        }
        begin(null, player);
    }
    /** Only the cloth mesh on a Locker display player gets neutral preview lighting. */
    public static void beginClothTexture(Object player,Object resource) {
        beginCloakTexture(player,resource);
        captureClothPreviewLighting(player);
    }
    /** Current Lunar cloth draws receive the selected cloak wrapper instead of a resource. */
    public static void beginClothCosmetic(Object cosmetic,Object player) {
        begin(cosmetic,player);
        captureClothPreviewLighting(player);
    }
    private static void captureClothPreviewLighting(Object player) {
        Scope scope=CURRENT.get();
        if(scope!=null&&isDisplayPlayer(player)&&!ManualGomodCapeRenderer.isNativeLayerPass())
            scope.previewLighting=LunarClothPreviewLighting.capture();
    }
    private static boolean sameCloakOwner(Scope scope, Object player) {
        if (player == null) return true;
        try {
            return scope.owner == null ? isDisplayPlayer(player)
                    : scope.owner.equals(OWNER.get(player.getClass()).invoke(player));
        } catch (Exception unavailable) { return false; }
    }
    private static boolean isDisplayPlayer(Object player) {
        if (player == null) return false;
        try {
            Optional<java.lang.reflect.Method> getter = DUMMY.get(player.getClass());
            Object kind = getter.isPresent() ? getter.get().invoke(player) : null;
            return kind instanceof Enum<?> && ("SELF".equals(((Enum<?>)kind).name())
                    || "MANNEQUIN".equals(((Enum<?>)kind).name()));
        } catch (Exception unavailable) { return false; }
    }
    private static String localClothOwner(Object player) {
        if (player == null || isDisplayPlayer(player)) return null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return null;
            UUID owner = (UUID) OWNER.get(player.getClass()).invoke(player);
            return owner != null && owner.equals(mc.thePlayer.getUniqueID()) ? owner.toString() : null;
        } catch (Exception unavailable) { return null; }
    }

    public static void beginBadge(Object badge) {
        long id=0;GomodCosmeticAppearance.Values colors=GomodCosmeticAppearance.DEFAULT;
        try {id=((Number)badge.getClass().getMethod("id").invoke(badge)).longValue();
            if(id>0&&ConfigManager.isLunarTweaksEnabled())colors=GomodStudioSharing.badge(GomodStudioSharing.badgeOwner(badge),(int)id);
        }catch(Throwable unavailable){}
        CURRENT.set(new Scope(CURRENT.get(),null,-id,"BADGE",colors,0L));
        LunarCosmeticSizeRenderer.beginAppearanceScope();
    }
    public static void begin(Object cosmetic, Object player) {
        begin(cosmetic,player,false);
    }
    private static void begin(Object cosmetic,Object player,boolean forcePreview) {
        Scope parent = CURRENT.get(); UUID owner = null,stackPlayerId=null; long id = 0; String category = "unknown"; boolean mannequin = forcePreview;
        GomodCosmeticAppearance.Values values = GomodCosmeticAppearance.DEFAULT;
        long draftRevision = 0L;
        try {
            if (player != null&&!forcePreview) {
                owner = (UUID) OWNER.get(player.getClass()).invoke(player);
                stackPlayerId=owner;
                Optional<java.lang.reflect.Method> dummy = DUMMY.get(player.getClass());
                Object kind = dummy.isPresent() ? dummy.get().invoke(player) : null;
                if (kind instanceof Enum<?> && ("SELF".equals(((Enum<?>)kind).name()) || "MANNEQUIN".equals(((Enum<?>)kind).name()))) {
                    owner = null; mannequin = true;
                }
            }
            else if(!forcePreview)owner = GomodCompanionPositionSync.cosmeticAppearanceOwner(cosmetic);
            if (owner == null && parent != null && !mannequin) owner = parent.owner;
            try { id = LunarCosmeticRuntime.id(cosmetic); }
            catch (Exception unavailable) { id = GomodCompanionPositionSync.cosmeticAppearanceId(cosmetic); }
            if (id > 0) try { category = LunarCosmeticRuntime.category(cosmetic); }
            catch (Exception unavailable) { category = "unknown"; }
            if(id>0&&category.equals("unknown")&&GomodCompanionPositionSync.cosmeticAppearanceId(cosmetic)>0)category="COMPANION";
            if(player!=null&&(mannequin||owner!=null&&owner.equals(localPlayerId())))recordLocalWornList(player,mannequin?"local-display":"local-player");
            GomodCosmeticAppearance.rememberCategory(id,category);
            if (id > 0) {
                if(parent != null && parent.id == id && Objects.equals(owner,parent.owner)) {
                    values=parent.values;draftRevision=parent.previewRevision;
                } else {
                    draftRevision=GomodCosmeticAppearance.previewRevision(owner,id);
                    values=GomodCosmeticAppearance.get(owner,id);
                }
            }
            if (LunarConnectionDiagnostics.isEnabled() && id > 0) {
                UUID fitContext=mannequin?LunarFitAppearance.currentModelFit()
                        :owner!=null&&owner.equals(localPlayerId())?LunarFitAppearance.activeFitId():null;
                String reportKey = String.valueOf(owner) + ":" + fitContext + ":" + id + ":"
                        +values.hue+":"+values.saturation+":"+values.brightness+":"+values.scale;
                if (REPORTED_SCOPES.add("scope:" + reportKey))
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.render_scope", reportKey,
                            "id=" + id + " category=" + category
                                    + " owner=" + (owner == null ? "local-display" : "player")
                                    + " fitId=" + fitContext
                                    + " customized=" + !values.isDefault()+" hue="+values.hue
                                    +" saturation="+values.saturation+" brightness="+values.brightness+" scale="+values.scale);
            }
            if (LunarConnectionDiagnostics.isEnabled() && id <= 0 && cosmetic != null)
                LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.scope_unresolved",
                        cosmetic.getClass().getName(), "cosmeticClass=" + cosmetic.getClass().getName()
                                + " parentScope=" + (parent != null));
        } catch (Throwable ignored) { }
        Scope opened=new Scope(parent, owner, id, category, values,draftRevision);
        opened.cosmetic=cosmetic;
        opened.player=player;
        opened.preview=mannequin;
        // Locker/mannequin draws often expose a one-item synthetic worn list. It is not the
        // equipped player's layout and must not replace (or consume) the real stack snapshot.
        opened.sameTypeHatOffsetY=mannequin?0.0:sameTypeHatOffsetY(player,stackPlayerId,id,category);
        if(opened.sameTypeHatOffsetY>0&&LunarConnectionDiagnostics.isEnabled())
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.stack_offset",
                    id+":"+opened.sameTypeHatOffsetY,
                    "id="+id+" category=HAT laterSameTypeItem=true offsetY="+opened.sameTypeHatOffsetY);
        try {
            Object resource=LunarCosmeticRuntime.resource(cosmetic,owner);
            if(resource instanceof ResourceLocation)opened.expectedResource=(ResourceLocation)resource;
        } catch(Throwable unavailable) { }
        CURRENT.set(opened);
        LunarCosmeticSizeRenderer.beginAppearanceScope();
        if ("PET".equalsIgnoreCase(category) && SelfOpacity.isActive()) {
            String phase = SelfOpacity.renderPhaseForDiagnostics();
            String parentCategory = parent == null ? "none" : parent.category;
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.self_opacity_pet_pass",
                    id + ":" + phase + ":" + parentCategory,
                    "id=" + id + " category=PET selfOpacityPhase=" + phase
                            + " parentCategory=" + parentCategory);
        }
        if (!isUneditedPet(opened) && opened.values.colorsDefault()
                && opened.parent != null && opened.parent.filtered > 0) {
            detachNeutralChildFromFilteredParent(opened);
        }
        if(id>0&&!values.colorsDefault())bindSelectedCosmeticTexture(cosmetic,owner,id,category);
    }

    /** A nested unedited cosmetic must not sample the edited parent cosmetic's temporary GL texture. */
    private static void detachNeutralChildFromFilteredParent(Scope scope) {
        int active = GL13.GL_TEXTURE0;
        boolean selected = false;
        try {
            active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            selectTextureUnit(GL13.GL_TEXTURE0);
            selected = true;
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (bound != scope.parent.filtered) return;
            bindExact(scope.parent.original);
            scope.original=scope.parent.original;
            scope.lastBound=scope.parent.original;
            scope.touched=true;
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.neutral_child_detached",
                    scope.parent.id + ":" + scope.id,
                    "parentId=" + scope.parent.id + " childId=" + scope.id
                            + " source=" + scope.parent.original + " filteredParent=" + scope.parent.filtered);
        } catch (Throwable unavailable) {
        } finally {
            if (selected) try { selectTextureUnit(active); } catch (Throwable ignored) { }
        }
    }

    private static boolean isUneditedPet(Scope scope) {
        return scope != null && "PET".equalsIgnoreCase(scope.category) && scope.values.colorsDefault();
    }

    /** Rebind an already-loaded cosmetic resource; never force a texture registration inside a render scope. */
    private static void bindSelectedCosmeticTexture(Object cosmetic,UUID owner,long id,String category) {
        int active=GL13.GL_TEXTURE0;boolean selected=false;
        try {
            Scope scope=CURRENT.get();
            Minecraft mc=Minecraft.getMinecraft();if(mc==null||mc.getTextureManager()==null)return;
            ITextureObject texture=resolveCosmeticTexture(mc,scope,cosmetic,owner,scope!=null?scope.draw:null);
            if(texture==null){
                LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.resource_bind",id+":not-loaded",
                        "id="+id+" category="+category+" result=not_loaded_native_bind_will_handle");
                return;
            }
            int textureId=texture.getGlTextureId();if(textureId<=0)return;
            if(scope!=null&&scope.cosmetic==cosmetic) {
                scope.expectedSource=textureId;
                scope.expectedTexture=texture;
            }
            active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);selectTextureUnit(GL13.GL_TEXTURE0);selected=true;
            bindExact(textureId);afterTextureBind();
            Scope activeScope=CURRENT.get();boolean filtered=activeScope!=null&&activeScope.id==id
                    &&activeScope.original==textureId&&activeScope.filtered>0;
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.resource_bind",id+":"+(filtered?"filtered":"native"),
                    "id="+id+" category="+category+" result="+(filtered?"filtered":"native_bind"));
        } catch(Throwable failure) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.resource_bind",Long.toString(id),
                    "id="+id+" category="+category+" result=failed exception="+failure.getClass().getSimpleName());
        } finally {if(selected)try{selectTextureUnit(active);}catch(Throwable ignored){ }}
    }
    private static UUID localPlayerId(){try{Minecraft mc=Minecraft.getMinecraft();return mc==null||mc.thePlayer==null?null:mc.thePlayer.getUniqueID();}catch(Throwable unavailable){return null;}}
    private static void recordLocalWornList(Object player,String ownerKind){
        try{
            Object raw=WORN.get(player.getClass()).invoke(player);
            if(!(raw instanceof List)||((List<?>)raw).size()>256)return;
            List<?> worn=(List<?>)raw;
            // Only a real local-player list is authoritative. Locker display/mannequin wrappers
            // can contain one cosmetic at a time even while several are equipped.
            if("local-player".equals(ownerKind)){
                UUID playerId=(UUID)OWNER.get(player.getClass()).invoke(player);
                if(playerId!=null&&playerId.equals(localPlayerId())){
                    HatStackLayout layout=readHatStackLayout(worn,System.nanoTime());
                    HAT_STACK_LAYOUTS.put(playerId,layout);
                    if(HAT_STACK_LAYOUTS.size()>512)HAT_STACK_LAYOUTS.clear();
                }
            }
            if(!LunarConnectionDiagnostics.isEnabled())return;
            StringBuilder ids=new StringBuilder();int count=0;
            for(Object item:worn){if(item==null)continue;long id=LunarCosmeticRuntime.id(item);if(id<=0)continue;
                if(count>0)ids.append(',');ids.append(id);count++;if(count>=64)break;}
            String list=ids.toString();LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.worn_list",ownerKind+":"+list,
                    "owner="+ownerKind+" count="+count+" ids="+list);
        }catch(Throwable unavailable){ }
    }
    public static double sizeForDraw(){if(LunarCosmeticRenderContext.isRenderingName())return 1;Scope scope=CURRENT.get();if(scope==null||scope.id<=0||"CLOAK".equalsIgnoreCase(scope.category))return 1;refreshDraft(scope);return scope.values.scale;}
    public static double sameTypeHatOffsetYForDraw(){Scope scope=CURRENT.get();if(scope==null)return 0.0;for(Scope frame=scope;frame!=null;frame=frame.parent)if(frame.suppressStackOffset)return 0.0;return scope.sameTypeHatOffsetY;}
    public static String sizeDiagnosticKey(){Scope scope=CURRENT.get();if(scope==null)return "none";refreshDraft(scope);return scope.id+":"+scope.owner+":"+scope.values.scale;}
    public static void end() {
        Scope scope = CURRENT.get(); if (scope == null) return;
        LunarPetOpacityPass.restoreDraw();
        int active=0; boolean selected=false;
        try {
            if(scope.touched||!scope.alternateBindings.isEmpty()) {
                active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);selected=true;
                for(Map.Entry<Integer,Integer> entry:scope.alternateBindings.entrySet()) {
                    selectTextureUnit(entry.getKey());
                    if(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==scope.filtered)bindExact(entry.getValue());
                }
                if(scope.touched) {
                    selectTextureUnit(GL13.GL_TEXTURE0);
                    int current=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    bindExact(current==scope.lastBound?(scope.resume>=0?scope.resume:scope.original):current);
                }
            }
        } catch (Throwable ignored) { }
        finally {
            if(selected)try{selectTextureUnit(active);}catch(Throwable ignored){ }
            if(scope.externalOpacityScope) {
                scope.externalOpacityScope=false;
                try{LunarExternalOpacityBridge.endLunarCosmetic();}catch(Throwable ignored){ }
            }
            if(scope.previewLighting!=null)scope.previewLighting.close();
            if(scope.deferredSizeContext)try{LunarCosmeticSizeRenderer.end();}catch(Throwable ignored){ }
            LunarCosmeticSizeRenderer.endAppearanceScope();
            if(scope.deferredModel)LunarFitAppearance.endCapturedModelContext();
            if (scope.parent == null) CURRENT.remove(); else CURRENT.set(scope.parent);
        }
    }

    /** Native render-state setup may rebind a texture after the earlier resource bind callback. */
    public static void beforeModelDraw() {
        beforeModelDraw(null);
    }

    /** The Minecraft uploader supplies its pending mesh so cosmetic size can pivot around its visible center. */
    public static void beforeModelDraw(Object worldRenderer) {
        Scope enclosing=CURRENT.get();
        // A deferred pet can upload several meshes after Lunar has ended the selected-item callback.
        // Do not open a different player's color/size scope from a texture-only fallback in that draw.
        boolean namePass=LunarCosmeticRenderContext.isRenderingName();
        boolean exactDeferredDraw = enclosing != null && enclosing.deferredModel
                && enclosing.cosmetic != null && enclosing.id > 0;
        boolean implicit = !namePass && !exactDeferredDraw && !hasActivePetScope(enclosing)
                && beginPlayerCosmeticDraw();
        IMPLICIT_MODEL_DRAWS.get().push(Boolean.valueOf(implicit));
        if(namePass){LunarCosmeticSizeRenderer.beginDraw(worldRenderer);return;}
        Scope scope=CURRENT.get();
        if(scope==null)return;
        SelfOpacity.applyExternalCosmeticDepthMasks();
        refreshDraft(scope);
        UUID fitContext=scope.owner==null?LunarFitAppearance.currentModelFit()
                :scope.owner.equals(localPlayerId())?LunarFitAppearance.activeFitId():null;
        if(scope.id>0&&LunarConnectionDiagnostics.isEnabled())
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.model_draw",scope.id+":"+fitContext+":"+(scope.owner==null?"display":"player")
                            +":"+scope.values.hue+":"+scope.values.saturation+":"+scope.values.brightness+":"+scope.values.scale,
                    "id="+scope.id+" category="+scope.category+" owner="+(scope.owner==null?"local-display":"player")
                            +" fitId="+fitContext+" customized="+!scope.values.isDefault()+" hue="+scope.values.hue
                            +" saturation="+scope.values.saturation+" brightness="+scope.values.brightness+" scale="+scope.values.scale+" effectiveStackOffsetY="+sameTypeHatOffsetYForDraw());
        if(scope.previewLighting!=null)try{scope.previewLighting.apply();}catch(Throwable noContext){ }
        if(scope.values.colorsDefault()){
            beginScopedMeshDraw(scope, worldRenderer);
            return;
        }
        int active=0;boolean selected=false;
        try {
            active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);selectTextureUnit(GL13.GL_TEXTURE0);selected=true;
            afterTextureBind();
                if(LunarConnectionDiagnostics.isEnabled()&&scope.filtered>0
                    &&REPORTED_SCOPES.add("draw:"+scope.owner+":"+fitContext+":"+scope.id+":"+scope.values.hue
                            +":"+scope.values.saturation+":"+scope.values.brightness+":"+scope.values.scale))
                {
                    int unit0=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),unit1=-1;
                    selectTextureUnit(GL13.GL_TEXTURE1);unit1=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    selectTextureUnit(GL13.GL_TEXTURE0);
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.final_draw",fitContext+":"+scope.id+":"
                                    +scope.values.hue+":"+scope.values.saturation+":"+scope.values.brightness+":"+scope.values.scale,
                            "id="+scope.id+" category="+scope.category+" fitId="+fitContext
                                    +" copyBound="+(unit0==scope.filtered)+" unit0="+unit0+" unit1="+unit1
                                    +" source="+scope.original+" copy="+scope.filtered
                                    +" hue="+scope.values.hue+" saturation="+scope.values.saturation
                                    +" brightness="+scope.values.brightness+" scale="+scope.values.scale
                                    +" alternateUnits="+scope.alternateBindings.keySet());
                }
        } catch(Throwable ignored){ }
        finally{if(selected)try{selectTextureUnit(active);}catch(Throwable ignored){ }}
        beginScopedMeshDraw(scope, worldRenderer);
    }

    private static void beginScopedMeshDraw(Scope scope, Object mesh) {
        LunarCosmeticSizeRenderer.beginDraw(mesh);
        if (!scope.preview && scope.id > 0 && "PET".equalsIgnoreCase(scope.category)) {
            LunarPetOpacityPass.beforeDraw(scope.id);
        }
    }

    private static boolean hasActivePetScope(Scope scope) {
        for(Scope current=scope;current!=null;current=current.parent)
            if("PET".equalsIgnoreCase(current.category))return true;
        return false;
    }

    /** Nested fallback scopes must not apply another cosmetic's tint inside a deferred pet model. */
    private static boolean isNestedUnderPet(Scope scope) {
        for(Scope parent=scope==null?null:scope.parent;parent!=null;parent=parent.parent)
            if("PET".equalsIgnoreCase(parent.category))return true;
        return false;
    }

    /** Ends a draw-time scope opened by texture-to-cosmetic matching in beforeModelDraw. */
    public static void finishNativeMeshDraw() {
        LunarCosmeticSizeRenderer.endDraw();
        endModelDraw();
    }

    public static void endModelDraw() {
        ArrayDeque<Boolean> draws = IMPLICIT_MODEL_DRAWS.get();
        if (draws.isEmpty()) return;
        boolean implicit = draws.pop().booleanValue();
        // Retain the empty, bounded-by-nesting deque. Removing it here allocated
        // a new deque on every mesh upload, including ordinary world/HUD meshes.
        if (implicit) end();
    }

    private static boolean samePlayer(Object candidate, EntityPlayer player) {
        if (candidate == player) return true;
        if (candidate == null || player == null || isDisplayPlayer(candidate) != isDisplayPlayer(player)) return false;
        try {
            UUID candidateId = (UUID) OWNER.get(candidate.getClass()).invoke(candidate);
            return candidateId != null && candidateId.equals(player.getUniqueID());
        } catch (Throwable unavailable) { return false; }
    }

    /**
     * Some Lunar body cosmetics bypass the retained-model factory used by pets and Locker previews.
     * At the final mesh upload, match the bound texture against the current player's exact worn
     * cosmetic resources, then apply the same Fit-scoped appearance path for this draw only.
     */
    private static boolean beginPlayerCosmeticDraw() {
        if(LunarCosmeticRenderContext.isRenderingName())return false;
        Scope parent = CURRENT.get();
        EntityPlayer player = LunarCosmeticRenderContext.currentPlayer();
        CloakFrame cloakFrame = CLOAK.get();
        if (cloakFrame != null && cloakFrame.cosmetic != null
                && (player == null || samePlayer(cloakFrame.player, player))) {
            long cloakId = -1L;
            try { cloakId = LunarCosmeticRuntime.id(cloakFrame.cosmetic); }
            catch (Throwable unsupportedCosmetic) { }
            if (cloakId > 0L) {
                if (parent != null && parent.id == cloakId && "CLOAK".equalsIgnoreCase(parent.category)
                        && sameCloakOwner(parent, cloakFrame.player)) return false;
                Scope before = CURRENT.get();
                try {
                    begin(cloakFrame.cosmetic, cloakFrame.player);
                    Scope opened = CURRENT.get();
                    if (opened != null && opened != before && opened.id == cloakId
                            && "CLOAK".equalsIgnoreCase(opened.category)) {
                        LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.cloak_frame_fallback",
                                String.valueOf(opened.owner) + ":" + opened.id,
                                "id=" + opened.id + " category=CLOAK result=exact_cloak_frame_match"
                                        + " owner=" + (opened.owner == null ? "local-display" : "player"));
                        return true;
                    }
                    if (opened != null && opened != before) end();
                } catch (Throwable failure) {
                    Scope opened = CURRENT.get();
                    if (opened != null && opened != before) try { end(); } catch (Throwable ignored) { }
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.cloak_frame_fallback_failed",
                            cloakId + ":" + failure.getClass().getName(),
                            "id=" + cloakId + " exception=" + failure.getClass().getSimpleName());
                }
            }
        }
        if (player == null) return false;
        List<?> worn = LunarCosmeticRenderContext.cachedWornCosmetics();
        if (worn == null) {
            worn = Collections.emptyList();
            try {
                Object raw = WORN.get(player.getClass()).invoke(player);
                if (raw instanceof List && ((List<?>) raw).size() <= 256) worn = (List<?>) raw;
            } catch (Throwable unavailable) { }
            LunarCosmeticRenderContext.cacheWornCosmetics(worn);
        }
        if (worn.isEmpty()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) return false;
        // A fixed-function background/outline can retain a cosmetic texture binding while texturing is off.
        // That binding does not establish ownership of the untextured geometry.
        if(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==0&&!GL11.glIsEnabled(GL11.GL_TEXTURE_2D))return false;

        int active = GL13.GL_TEXTURE0;
        boolean selected = false;
        int bound;
        try {
            active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            selectTextureUnit(GL13.GL_TEXTURE0);
            selected = true;
            bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            bound = SOURCE_TEXTURES.getOrDefault(bound, bound);
        } catch (Throwable unavailable) {
            return false;
        } finally {
            if (selected) try { selectTextureUnit(active); } catch (Throwable ignored) { }
        }
        if (bound <= 0) return false;

        java.util.UUID owner = player.getUniqueID();
        Object match = null;
        if (LunarCosmeticRenderContext.hasCachedTextureMatch(bound)) {
            match = LunarCosmeticRenderContext.cachedTextureMatch(bound);
            if (match == NO_PLAYER_COSMETIC_MATCH) return false;
        } else {
            long matchId = -1L;
            for (Object cosmetic : worn) {
                if (cosmetic == null) continue;
                try {
                    long id = LunarCosmeticRuntime.id(cosmetic);
                    if (id <= 0) continue;
                    ITextureObject texture = resolveCosmeticTexture(mc, null, cosmetic, owner, null);
                    if (texture == null || texture.getGlTextureId() != bound) continue;
                    if (match != null && matchId != id) {
                        match = NO_PLAYER_COSMETIC_MATCH;
                        break;
                    }
                    match = cosmetic;
                    matchId = id;
                } catch (Throwable unsupportedCosmetic) {
                    // Skip unsupported native cosmetic wrappers; only an exact resource match may start a scope.
                }
            }
            LunarCosmeticRenderContext.cacheTextureMatch(bound,
                    match == null ? NO_PLAYER_COSMETIC_MATCH : match);
            if (match == null || match == NO_PLAYER_COSMETIC_MATCH) return false;
        }
        if (match == null) return false;
        long matchId;
        try { matchId = LunarCosmeticRuntime.id(match); }
        catch (Throwable unsupportedCosmetic) { return false; }

        Scope before = CURRENT.get();
        try {
            begin(match, player);
            Scope opened = CURRENT.get();
            if (opened == null || opened == before || opened.id != matchId) {
                if (opened != null && opened != before) end();
                return false;
            }
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.player_draw_fallback",
                    owner + ":" + matchId,
                    "id=" + matchId + " category=" + opened.category + " result=exact_texture_match"
                            + " nested=" + (parent != null && parent.id > 0));
            if (!opened.preview && !"CLOAK".equalsIgnoreCase(opened.category)) {
                String hostOpacityPhase = SelfOpacity.renderPhaseForDiagnostics();
                int opacityMode = LunarExternalOpacityBridge.beginLunarCosmetic(player);
                opened.externalOpacityScope = true;
                if (opacityMode != 0) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.immediate_opacity",
                            opened.id + ":" + hostOpacityPhase + ":" + opacityMode,
                            "id=" + opened.id + " category=" + opened.category
                                    + " hostPhase=" + hostOpacityPhase
                                    + " opacityMode=" + opacityMode + " deferred=false");
                }
            }
            return true;
        } catch (Throwable failure) {
            Scope opened = CURRENT.get();
            if (opened != null && opened != before) try { end(); } catch (Throwable ignored) { }
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.player_draw_fallback_failed",
                    owner + ":" + matchId,
                    "id=" + matchId + " exception=" + failure.getClass().getSimpleName());
            return false;
        }
    }

    /** Slightly lift later same-type hats so Lunar's retained meshes do not completely cover each other. */
    private static double sameTypeHatOffsetY(Object player,UUID playerId,long id,String category) {
        if(player==null||id<=0||!"HAT".equalsIgnoreCase(category)
                ||!ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics())return 0.0;
        if(playerId!=null) {
            long now=System.nanoTime();
            HatStackLayout cached=HAT_STACK_LAYOUTS.get(playerId);
            if(cached!=null&&now-cached.checkedAtNanos<HAT_STACK_LAYOUT_REFRESH_NANOS)
                return cached.offsets.getOrDefault(id,Double.valueOf(0.0));
            HatStackLayout refreshed=readHatStackLayout(player,now);
            HAT_STACK_LAYOUTS.put(playerId,refreshed);
            if(HAT_STACK_LAYOUTS.size()>512)HAT_STACK_LAYOUTS.clear();
            return refreshed.offsets.getOrDefault(id,Double.valueOf(0.0));
        }
        return readHatStackLayout(player,System.nanoTime()).offsets.getOrDefault(id,Double.valueOf(0.0));
    }

    private static HatStackLayout readHatStackLayout(Object player,long checkedAtNanos) {
        try {
            Object raw=WORN.get(player.getClass()).invoke(player);
            if(raw instanceof List)return readHatStackLayout((List<?>)raw,checkedAtNanos);
        } catch(Throwable unavailable) { }
        return new HatStackLayout(checkedAtNanos,new HashMap<Long,Double>());
    }

    private static HatStackLayout readHatStackLayout(List<?> worn,long checkedAtNanos) {
        Map<Long,Double> offsets=new HashMap<Long,Double>();
        if(worn==null||worn.isEmpty()||worn.size()>256)return new HatStackLayout(checkedAtNanos,offsets);
        int hatOrdinal=0;
        for(Object candidate:worn) {
            if(candidate==null)continue;
            try {
                long candidateId=LunarCosmeticRuntime.id(candidate);
                if(candidateId<=0||!"HAT".equalsIgnoreCase(LunarCosmeticRuntime.category(candidate)))continue;
                offsets.putIfAbsent(candidateId,hatOrdinal==0?Double.valueOf(0.0):Double.valueOf(Math.min(hatOrdinal,4)*0.18));
                hatOrdinal++;
            } catch(Throwable unsupportedItem) { }
        }
        return new HatStackLayout(checkedAtNanos,offsets);
    }

    private static void selectTextureUnit(int unit) {
        GlStateManager.setActiveTexture(unit);GL13.glActiveTexture(unit);
    }
    private static void refreshDraft(Scope scope) {
        if(scope==null||scope.id<=0)return;
        long revision=GomodCosmeticAppearance.previewRevision(scope.owner,scope.id);
        if(revision==scope.previewRevision)return;
        scope.values=GomodCosmeticAppearance.get(scope.owner,scope.id);
        scope.previewRevision=revision;
    }
    /** A native/raw bind can leave the Minecraft cache ahead of the actual OpenGL binding. */
    private static void bindExact(int texture) {
        // The live bindTexture @RETURN callback also sees our cleanup/nested restoration binds.
        // Re-filtering there changes Minecraft's cache to the copy before the raw source restore.
        boolean filtering=Boolean.TRUE.equals(FILTERING.get());
        FILTERING.set(Boolean.TRUE);
        try { GlStateManager.bindTexture(texture);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture); }
        finally { FILTERING.set(filtering); }
    }

    /** Runs after the native manager has bound the current animation frame, including its emissive pass. */
    public static void afterMinecraftTextureBind(int requested){
        if(Boolean.TRUE.equals(FILTERING.get())||LunarCosmeticRenderContext.isRenderingName())return;Scope scope=CURRENT.get();if(scope==null||isUneditedPet(scope))return;
        try{if(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0){int bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if(bound!=requested&&!(bound==scope.filtered&&scope.original==requested))GL11.glBindTexture(GL11.GL_TEXTURE_2D,requested);
        }}catch(Throwable unavailable){}
        afterTextureBind();
    }
    public static void afterTextureBind() {
        if (Boolean.TRUE.equals(FILTERING.get())||LunarCosmeticRenderContext.isRenderingName()) return;
        Scope scope=CURRENT.get();if(scope==null||isUneditedPet(scope))return;
        // A native raw bind also has to update Minecraft's cache for neutral cosmetics.
        // Otherwise its next bind(A) can be skipped while the GPU is still sampling B.
        FILTERING.set(Boolean.TRUE);
        try {
            int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GlStateManager.setActiveTexture(active);
            GlStateManager.bindTexture(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        } catch(Throwable unavailable) { }
        finally { FILTERING.set(Boolean.FALSE); }
        if(isNestedUnderPet(scope))return;
        if (scope.values.colorsDefault()
                || (!scope.values.colorsDefault() && System.currentTimeMillis() < retryAt)) return;
        int source = 0;
        FILTERING.set(Boolean.TRUE);
        try {
            int activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            if (activeTexture != GL13.GL_TEXTURE0) {
                int bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if(scope.original>0&&scope.filtered>0&&bound==scope.original) {
                    scope.alternateBindings.put(activeTexture,bound);bindExact(scope.filtered);
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_filtered_unit",
                            scope.id+":"+activeTexture,"id="+scope.id+" category="+scope.category
                                    +" unit="+activeTexture+" source="+scope.original+" copy="+scope.filtered);
                } else if(bound!=scope.filtered) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_skip",
                            scope.id + ":unit:" + activeTexture,
                            "id=" + scope.id + " category=" + scope.category
                                    + " reason=unmatched_texture_unit unit=" + activeTexture+" bound="+bound
                                    +" source="+scope.original+" copy="+scope.filtered);
                }
                return;
            }
            selectTextureUnit(GL13.GL_TEXTURE0);
            int bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            source = SOURCE_TEXTURES.getOrDefault(bound,bound);
            if (source <= 0) return;
            if(!scope.values.colorsDefault()&&scope.id>0&&scope.cosmetic!=null) {
                int expected=expectedCosmeticTexture(scope);
                if(expected<=0||source!=expected) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_skip",
                            scope.id+":scope-resource:"+source,
                            "id="+scope.id+" category="+scope.category
                                    +" reason="+(expected<=0?"selected_resource_unavailable":"unmatched_cosmetic_resource")
                                    +" source="+source+" expected="+expected);
                    return;
                }
                scope.expectedSource=expected;
            }
            if(scope.values.colorsDefault()) {
                if(bound!=source){bindExact(source);scope.original=source;scope.lastBound=source;scope.touched=true;}
                return;
            }
            long now = System.currentTimeMillis();
            if (now >= nextCleanup) {
                evict(now, source);
                bound = source;
                nextCleanup = now + 1000;
            }
            String key = String.valueOf(scope.owner) + ":" + scope.id + ":" + source;
            Texture texture = TEXTURES.get(key);
            // SimpleTexture is immutable between loadTexture calls. Native/dynamic textures
            // that can update pixels in place keep the existing animation refresh cadence.
            ITextureObject staticSource = scope.expectedSource == source && scope.expectedTexture != null
                    && scope.expectedTexture.getClass() == SimpleTexture.class ? scope.expectedTexture : null;
            if (texture != null && (texture.sourceChanged || texture.staticSource != staticSource)) {
                if (bound != source) bindExact(source);
                bound = source;
                TEXTURES.remove(key);
                dispose(texture);
                texture = null;
            }

            boolean currentTint = texture != null && texture.matchesTint(scope.values);
            if (currentTint && (staticSource != null || (bound == texture.id && bound == scope.filtered))) {
                texture.used = now;
                if (bound != texture.id) bindExact(texture.id);
                scope.original = source;
                scope.filtered = texture.id;
                scope.lastBound = texture.id;
                scope.touched = true;
                return;
            }

            if (bound != source) bindExact(source);
            int width = texture != null && staticSource != null ? texture.width
                    : GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = texture != null && staticSource != null ? texture.height
                    : GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width < 1 || height < 1 || width > 2048 || height > 2048) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_skip",
                        scope.id + ":size:" + width + "x" + height,
                        "id=" + scope.id + " category=" + scope.category
                                + " reason=unsupported_texture_size size=" + width + "x" + height);
                return;
            }
            if (texture != null && (texture.width != width || texture.height != height)) {
                TEXTURES.remove(key);
                dispose(texture);
                texture = null;
                currentTint = false;
            }
            if (texture == null) {
                while (!TEXTURES.isEmpty() && (TEXTURES.size() >= 96 || pixels + (long) width * height > 4194304L))
                    removeOldest(source);
                texture = new Texture(source, width, height, staticSource);
                TEXTURES.put(key, texture);
            }
            texture.used=now;
            boolean firstFilter=Double.isNaN(texture.hue);
            if (!currentTint || (staticSource == null && now - texture.updated >= 8L)) {
                if(LunarConnectionDiagnostics.isEnabled()) {
                    int depth=GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
                    LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.filter_entry_state",scope.id+":"+depth,
                            "id="+scope.id+" category="+scope.category+" nativeAttributeDepth="+depth
                                    +" maxAttributeDepth="+GL11.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH)
                                    +" filterUsesAttributeStack=false");
                }
                LunarCosmeticColorFilter.render(source,texture.id,width,height,
                        scope.values.hue,scope.values.saturation,scope.values.brightness,scope.id<0);
                texture.updated=now; texture.hue=scope.values.hue;
                texture.saturation=scope.values.saturation; texture.brightness=scope.values.brightness;
            }
            bindExact(texture.id); scope.original=source; scope.filtered=texture.id;scope.lastBound=texture.id;scope.touched=true;
            if(firstFilter)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_filtered",Long.toString(scope.id),
                    "id="+scope.id+" category="+scope.category
                            +" owner="+(scope.owner==null?"local-display":"player")+" size="+width+"x"+height);
        } catch (Throwable failure) {
            if (source > 0) try { bindExact(source); } catch (Throwable ignored) { }
            retryAt=System.currentTimeMillis()+30000L;
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.render", failure.getClass().getName(),
                    "result=failed exception="+failure.getClass().getSimpleName());
        } finally { FILTERING.set(Boolean.FALSE); }
    }
    /** Resolve the selected item's current account-specific texture; an unrelated nested cosmetic must never inherit its tint. */
    private static int expectedCosmeticTexture(Scope scope) {
        if(scope==null||scope.cosmetic==null||scope.id<=0)return 0;
        try {
            Minecraft mc=Minecraft.getMinecraft();
            if(mc==null||mc.getTextureManager()==null)return 0;
            ITextureObject texture=resolveCosmeticTexture(mc,scope,scope.cosmetic,scope.owner,scope.draw);
            if(texture==null)return 0;
            int id=texture.getGlTextureId();
            if(id>0) {
                scope.expectedSource=id;
                scope.expectedTexture=texture;
            }
            return id;
        } catch(Throwable unavailable) { return 0; }
    }
    private static ITextureObject resolveCosmeticTexture(Minecraft mc,Scope scope,Object cosmetic,UUID owner,Object draw) {
        if(mc==null||mc.getTextureManager()==null)return null;
        if(scope!=null&&scope.cosmetic==cosmetic&&scope.expectedResource!=null) {
            ITextureObject cached=resolveLoadedTexture(mc,scope,scope.expectedResource);
            if(cached!=null)return cached;
        }
        try {
            Object raw=LunarCosmeticRuntime.resource(cosmetic,owner);
            ITextureObject texture=resolveLoadedTexture(mc,scope,raw);
            if(texture!=null)return texture;
        } catch(Throwable unavailable) { }
        try {
            Object modelRaw=LunarCosmeticRuntime.modelTextureResource(cosmetic);
            ITextureObject texture=resolveLoadedTexture(mc,scope,modelRaw);
            if(texture!=null)return texture;
        } catch(Throwable unavailable) { }
        try {
            if(draw!=null) {
                Object drawRaw=LunarCosmeticRuntime.drawTextureResource(draw,ResourceLocation.class);
                ITextureObject texture=resolveLoadedTexture(mc,scope,drawRaw);
                if(texture!=null)return texture;
            }
        } catch(Throwable unavailable) { }
        return null;
    }
    private static ITextureObject resolveLoadedTexture(Minecraft mc,Scope scope,Object raw) {
        if(mc==null||mc.getTextureManager()==null||!(raw instanceof ResourceLocation))return null;
        ResourceLocation location=(ResourceLocation)raw;
        ITextureObject texture=mc.getTextureManager().getTexture(location);
        if(texture!=null) {
            if(scope!=null)scope.expectedResource=location;
            return texture;
        }
        String jitPath=LunarCosmeticRuntime.jitAssetPath(location.getResourceDomain(),location.getResourcePath());
        if(jitPath!=null) {
            ResourceLocation jit=new ResourceLocation("lunar-jit",jitPath);
            ITextureObject jitTexture=mc.getTextureManager().getTexture(jit);
            if(jitTexture!=null) {
                if(scope!=null)scope.expectedResource=jit;
                return jitTexture;
            }
        }
        return null;
    }
    private static void removeOldest(int source) {
        Iterator<Texture> entries=TEXTURES.values().iterator();
        if (!entries.hasNext()) return; Texture texture=entries.next(); entries.remove(); dispose(texture);
        bindExact(source);
    }
    private static void evict(long now,int source) {
        Iterator<Texture> entries=TEXTURES.values().iterator();
        while (entries.hasNext()) { Texture texture=entries.next();
            if (now-texture.used > 30000) { entries.remove(); dispose(texture); }
        }
        bindExact(source);
    }
    public static void clearTextures() {
        for (Texture texture:TEXTURES.values()) dispose(texture);
        TEXTURES.clear(); SOURCE_TEXTURES.clear(); pixels=0; retryAt=0;
        DRAW_BINDINGS.clear();
    }

    public static void onSimpleTextureLoaded(SimpleTexture source) {
        for (Texture texture : TEXTURES.values()) {
            if (texture.staticSource == source) texture.sourceChanged = true;
        }
    }

    private static void dispose(Texture texture) {
        GlStateManager.deleteTexture(texture.id); SOURCE_TEXTURES.remove(texture.id); pixels-=(long)texture.width*texture.height;
    }
    private static final class Texture {
        final int id,width,height; long used,updated; double hue=Double.NaN,saturation,brightness;
        final ITextureObject staticSource;
        boolean sourceChanged;

        boolean matchesTint(GomodCosmeticAppearance.Values values) {
            return hue == values.hue && saturation == values.saturation && brightness == values.brightness;
        }

        Texture(int source,int width,int height,ITextureObject staticSource) {
            int min=GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER);
            int mag=GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER);
            int wrapS=GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S);
            int wrapT=GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T);
            this.width=width;this.height=height;this.staticSource=staticSource;id=GL11.glGenTextures();
            try {
                bindExact(id);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,min==GL11.GL_NEAREST?GL11.GL_NEAREST:GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,mag);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,wrapS);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,wrapT);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
                pixels+=(long)width*height;SOURCE_TEXTURES.put(id,source);
            } catch (Throwable failure) { GlStateManager.deleteTexture(id); throw failure; }
        }
    }
    private static final class Scope {
        final Scope parent; final UUID owner; final long id; final String category;
        Object cosmetic,player,draw; ResourceLocation expectedResource; int expectedSource; double sameTypeHatOffsetY;
        ITextureObject expectedTexture;
        GomodCosmeticAppearance.Values values; long previewRevision;
        final Map<Integer,Integer> alternateBindings=new LinkedHashMap<>();
        int original,filtered,lastBound,resume=-1;boolean touched,deferredModel,deferredSizeContext,externalOpacityScope,suppressStackOffset;
        boolean preview;
        LunarClothPreviewLighting previewLighting;
        Scope(Scope parent,UUID owner,long id,String category,GomodCosmeticAppearance.Values values,long previewRevision) {
            this.parent=parent;this.owner=owner;this.id=id;this.category=category;this.values=values;this.previewRevision=previewRevision;
            if(parent!=null&&parent.filtered>0) {
                int active=0;boolean selected=false;
                try{active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);selectTextureUnit(GL13.GL_TEXTURE0);selected=true;resume=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);}
                catch(Throwable ignored){ }
                finally{if(selected)try{selectTextureUnit(active);}catch(Throwable ignored){ }}
            }
        }
    }
    private static final class HatStackLayout {
        final long checkedAtNanos; final Map<Long,Double> offsets;
        HatStackLayout(long checkedAtNanos,Map<Long,Double> offsets) {
            this.checkedAtNanos=checkedAtNanos;this.offsets=Collections.unmodifiableMap(offsets);
        }
    }
    private static final class CloakFrame {
        final CloakFrame parent; final Object player; final boolean external; final String localOwner;
        final LinkedHashSet<Integer> localWornCloaks = new LinkedHashSet<Integer>();
        Object cosmetic;
        CloakFrame(CloakFrame parent, Object player, boolean external, String localOwner) {
            this.parent = parent; this.player = player; this.external = external;
            this.localOwner = localOwner;
        }
    }
}
