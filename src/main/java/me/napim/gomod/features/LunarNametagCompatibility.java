package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates gomod-owned rows with Lunar's native multi-line player nametag event. */
public final class LunarNametagCompatibility {
    /** Set false to restore strict Only IGN behavior and hide Lunar's Level Head row again. */
    public static final boolean ONLY_IGN_SHOW_LEVEL_HEAD = true;

    private static final ClassValue<Map<String, Optional<Field>>> DECLARED_FIELDS =
            new ClassValue<Map<String, Optional<Field>>>() {
                @Override protected Map<String, Optional<Field>> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
            };
    private static final ClassValue<Map<String, NoArgLookup>> NO_ARG_METHODS =
            new ClassValue<Map<String, NoArgLookup>>() {
                @Override protected Map<String, NoArgLookup> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
            };
    private static final Map<Class<?>, Method> NAMETAG_ENTITY_GETTERS = new ConcurrentHashMap<>();

    private static final class NoArgLookup {
        final Method method;
        final NoSuchMethodException missing;
        NoArgLookup(Class<?> type, String name) {
            Method found = null; NoSuchMethodException error = null;
            try { found = type.getMethod(name); found.setAccessible(true); }
            catch (NoSuchMethodException absent) { error = absent; }
            method = found; missing = error;
        }
    }
    private static final Set<Integer> LUNAR_STACK_ENTITY_IDS = new HashSet<Integer>();
    private static final Set<Integer> LUNAR_KILLER_ENTITY_IDS = new HashSet<Integer>();
    private static final Map<String, String> NATIVE_EVENT_DEBUG = new java.util.LinkedHashMap<String, String>();
    private static final Map<String, Long> NATIVE_EVENT_DEBUG_CAPTURE_TIMES = new java.util.HashMap<String, Long>();
    private static volatile long eventCalls;
    private static volatile long insertedRows;
    private static volatile long visibleSpecsEventFinalizerCalls;
    private static volatile String lastVisibleSpecsEventFinalizer = "not called";
    private static volatile long nativeLevelHeadCallbacksSkipped;
    private static volatile String lastNativeLevelHeadCallbackSkip = "not skipped";
    private static volatile long lateNativeRowsBlocked;
    private static volatile long nativeLevelHeadRowsSuppressed;
    private static volatile long nativeLevelHeadRowsAllowed;
    private static volatile String lastLevelHeadRowFilter = "not called";
    private static volatile String lastSuppressedLevelHeadRow = "none";
    private static volatile long lastSuppressedLevelHeadRowCaptureAt;
    private static volatile String lastNativeRowGate = "not installed";
    private static volatile String lastResult = "not called";
    private static volatile long lunarSettingsCheckedAt;
    private static volatile int cachedNativeRowsAboveMain;
    private static volatile String lunarSettingsResult = "not checked";
    private static volatile boolean lunarReflectionUnavailable;
    private static volatile long profileSettingsCheckedAt;
    private static volatile LunarProfileSettings cachedProfileSettings;
    private static volatile long levelHeadBindingCheckedAt;
    private static volatile LevelHeadBinding cachedLevelHeadBinding;
    /** The current Lunar build passes its live Hypixel module into the native Level Head handler. */
    private static volatile Object lastLevelHeadModule;
    private static volatile long levelHeadModuleCheckedAt;
    private static volatile boolean cachedLevelHeadModuleEnabled = true;
    private static volatile long levelHeadEventCalls;
    private static volatile String lastDisguisedLevelHeadDecision = "not seen";
    private static final ThreadLocal<Deque<LevelHeadPatch>> LEVEL_HEAD_PATCHES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<LevelHeadRowFilterPatch>> LEVEL_HEAD_ROW_FILTERS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final String CURRENT_HYPIXEL_MODULE_CLASS =
            "com.moonsworth.lunar.client.RROCHCCOCCROCCCHOROHCIHOOHCRHC."
                    + "ROROOCIIRHIOOCHORIOHIHHHOCHRIO.CIHIORCRHRCICRIHRORCCOHCRICRHR."
                    + "CRHRHHCRROICOORCRCOIIIHOICICCI";
    private static final String CURRENT_MODULE_ACCESSOR_CLASS =
            "com.moonsworth.lunar.client.HOHOHOHCHOCRRCCOHCOHCRIRIIIOHC.HIOORCHCOIHRCHRRIIOIICCOHCHOCO";
    private static final String CURRENT_MODULE_ACCESSOR = "ORIRHRIIICHRRCRHCRROOHRRHHOIOH";
    private static final String CURRENT_LEVEL_HEAD_QUEUE_ACCESSOR = "RIRCIORCRRRICRCCOCIRIHOROROCHH";
    private static final String LEGACY_HYPIXEL_MODULE_CLASS =
            "com.moonsworth.lunar.client.CCRCOICHHIIRHHIROIOICIRCIIIOII.CCHCHHCHHROIRICOOOCORIIHHOHCCR."
                    + "IIHCHHCHRHORCRHRRHCCCRCICORROR.IIHOCIRHRCIRHIIOOCHCOCHROHORCO";
    private static final String LEGACY_MODULE_REGISTRY_CLASS =
            "com.moonsworth.lunar.client.CIHCIRRHROHOCORHOCOHCORHHOHCRH.ICHRRCIICOIIRHCIOOICORICHOOCHI."
                    + "COROIRIHIIRCIIIOOCHROCOIOHCHIC";
    private static final String LEVEL_HEAD_FIELD = "RHHOCRIIHIRRICIICCOOOICHCIICCC";
    private static final String LEVEL_ABOVE_FIELD = "IRRHHOHHRIICRRRRRHHORORORIHCHR";
    private static final String LEVEL_HEAD_SOURCE_FIELD = "OIIHOHOCRHIRHICIRRICCRIOHCCIHH";
    private static final String LEGACY_LEVEL_HEAD_FIELD = "OHCCHRHRORHHHHOCCIHOOCHRCIHIRO";
    private static final String LEGACY_LEVEL_ABOVE_FIELD = "CORCHHCCRCORHRIICHICCRRHOIHRHR";
    private static final String LEGACY_LEVEL_HEAD_SOURCE_FIELD = "RRHIOHOOIRIOIRCIHOROOCORCIRHHO";
    private static final String BOOLEAN_SETTING_GETTER = "OHRCCCRIIHRCCIIHCIHRIHIRHRICRI";
    private static final Map<UUID, Long> LEVEL_REQUESTS = new ConcurrentHashMap<>();

    private LunarNametagCompatibility() {
    }

    /**
     * For a live spectator with Only IGN/Off, later Lunar modules cannot append arbitrary rows after cleanup.
     * Only IGN can optionally retain the specific Level Head row through ONLY_IGN_SHOW_LEVEL_HEAD.
     */
    private static final class SpectatorNametagLineGate extends AbstractList<Object> {
        private final List<Object> rows = new ArrayList<Object>(2);
        private Object mainNameRow;
        private Object killerRow;
        private Object levelHeadRow;
        private boolean mainNamePresent;
        private boolean allowLevelHeadRow;

        void replaceRows(Object mainName, Object killer, Object levelHead, boolean allowLevelHead) {
            rows.clear();
            mainNameRow = mainName;
            killerRow = killer;
            mainNamePresent = mainName != null;
            allowLevelHeadRow = allowLevelHead;
            levelHeadRow = allowLevelHead ? levelHead : null;
            rebuildRows();
        }

        private void rebuildRows() {
            rows.clear();
            if (mainNameRow != null) rows.add(mainNameRow);
            // The Level Head sits immediately above the IGN; the optional Killer line remains above both.
            if (levelHeadRow != null && levelHeadRow != mainNameRow) rows.add(levelHeadRow);
            if (killerRow != null && killerRow != mainNameRow && killerRow != levelHeadRow) rows.add(killerRow);
            modCount++;
        }

        Object levelHeadRow() { return levelHeadRow; }

        boolean appendNativeLevelHeadRow(Object component) {
            if (!allowLevelHeadRow || component == null || levelHeadRow != null) return false;
            levelHeadRow = component;
            rebuildRows();
            return true;
        }

        private void rejectLateMutation(Object component) {
            lateNativeRowsBlocked++;
            lastNativeRowGate = "blocked late mutation=" + safeComponentClass(component)
                    + " total=" + lateNativeRowsBlocked;
        }

        @Override public Object get(int index) { return rows.get(index); }
        @Override public int size() { return rows.size(); }

        @Override
        public void add(int index, Object element) {
            // Only the scoped Level Head callback can use its explicit allow path; later modules
            // cannot append health, ping, or other native rows after the spectator policy runs.
            rejectLateMutation(element);
        }

        @Override
        public Object set(int index, Object element) {
            Object previous = rows.get(index);
            if (index == 0 && mainNamePresent) {
                // Keep a later native name-formatting update, while protecting the extra rows.
                mainNameRow = element;
                rebuildRows();
                return previous;
            }
            rejectLateMutation(element);
            return previous;
        }

        @Override public Object remove(int index) {
            Object previous = rows.get(index);
            rejectLateMutation(previous);
            return previous;
        }

        @Override public void clear() { rejectLateMutation(null); }
    }

    /** A short-lived event-list decorator that filters Level Head additions without changing Lunar control flow. */
    private static final class LevelHeadRowFilterList extends AbstractList<Object> {
        private final List<Object> rows;
        private final String targetName;
        private final boolean allowNativeLevelHeadRow;
        private final List<Object> acceptedLevelHeadRows = new ArrayList<Object>(1);
        private int suppressDepth;

        LevelHeadRowFilterList(List<Object> rows, String targetName, boolean allowNativeLevelHeadRow) {
            this.rows = rows;
            this.targetName = targetName;
            this.allowNativeLevelHeadRow = allowNativeLevelHeadRow;
        }

        void beginSuppressingAdds() { suppressDepth++; }

        void endSuppressingAdds() {
            if (suppressDepth > 0) suppressDepth--;
        }

        private boolean rejectsAdds() { return suppressDepth > 0 && !allowNativeLevelHeadRow; }

        Object acceptedLevelHeadRow() {
            return acceptedLevelHeadRows.isEmpty() ? null : acceptedLevelHeadRows.get(0);
        }

        private boolean keepNativeLevelHeadRow(Object component, int index) {
            if (acceptedLevelHeadRows.isEmpty() && allowNativeLevelHeadRow && suppressDepth > 0) {
                boolean added;
                if (rows instanceof SpectatorNametagLineGate) {
                    added = ((SpectatorNametagLineGate) rows).appendNativeLevelHeadRow(component);
                } else {
                    rows.add(Math.max(0, Math.min(index, rows.size())), component);
                    modCount++;
                    added = true;
                }
                if (added) {
                    acceptedLevelHeadRows.add(component);
                    nativeLevelHeadRowsAllowed++;
                    lastLevelHeadRowFilter = "kept native Level Head row for " + targetName
                            + " total=" + nativeLevelHeadRowsAllowed;
                    return true;
                }
            }
            recordSuppressed(component);
            return false;
        }

        private void recordSuppressed(Object component) {
            nativeLevelHeadRowsSuppressed++;
            lastLevelHeadRowFilter = "blocked Level Head row=" + safeComponentClass(component)
                    + " total=" + nativeLevelHeadRowsSuppressed;
            long now = System.currentTimeMillis();
            if (now - lastSuppressedLevelHeadRowCaptureAt >= 500L) {
                lastSuppressedLevelHeadRowCaptureAt = now;
                lastSuppressedLevelHeadRow = targetName + " text=" + safeComponentText(component)
                        + " total=" + nativeLevelHeadRowsSuppressed;
            }
        }

        @Override public Object get(int index) { return rows.get(index); }
        @Override public int size() { return rows.size(); }

        @Override
        public boolean add(Object element) {
            if (rejectsAdds()) {
                recordSuppressed(element);
                return false;
            }
            if (suppressDepth > 0) return keepNativeLevelHeadRow(element, rows.size());
            boolean changed = rows.add(element);
            if (changed) modCount++;
            return changed;
        }

        @Override
        public void add(int index, Object element) {
            if (rejectsAdds()) {
                recordSuppressed(element);
                return;
            }
            if (suppressDepth > 0) {
                keepNativeLevelHeadRow(element, index);
                return;
            }
            rows.add(index, element);
            modCount++;
        }

        @Override public Object set(int index, Object element) { return rows.set(index, element); }

        @Override
        public Object remove(int index) {
            Object removed = rows.remove(index);
            modCount++;
            return removed;
        }

        @Override
        public void clear() {
            if (!rows.isEmpty()) {
                rows.clear();
                modCount++;
            }
        }
    }

    private static final class LevelHeadRowFilterPatch {
        static final LevelHeadRowFilterPatch NOOP = new LevelHeadRowFilterPatch(null, null, null, false);
        final Object event;
        final List<?> originalLines;
        final LevelHeadRowFilterList filteredLines;
        final boolean ownsReplacement;

        LevelHeadRowFilterPatch(Object event, List<?> originalLines,
                LevelHeadRowFilterList filteredLines, boolean ownsReplacement) {
            this.event = event;
            this.originalLines = originalLines;
            this.filteredLines = filteredLines;
            this.ownsReplacement = ownsReplacement;
        }
    }

    private static String safeComponentClass(Object component) {
        return component == null ? "null" : component.getClass().getSimpleName();
    }

    public static void beginWorldNametagFrame() {
        LUNAR_STACK_ENTITY_IDS.clear();
        LUNAR_KILLER_ENTITY_IDS.clear();
    }

    public static boolean isHandledByLunarNametagStack(EntityPlayer player) {
        return player != null && LUNAR_STACK_ENTITY_IDS.contains(Integer.valueOf(player.getEntityId()));
    }

    public static boolean isKillerHandledByLunarNametagStack(EntityPlayer player) {
        return player != null && LUNAR_KILLER_ENTITY_IDS.contains(Integer.valueOf(player.getEntityId()));
    }

    /**
     * ReplayPlayerHealthTagFix owns the complete nameplate for these players, so Lunar must not render its native
     * multi-line event alongside it.
     */
    public static boolean shouldSuppressNativeLunarEvent(Object event) {
        try {
            Object lunarEntity = nametagEventEntity(event);
            Object uuidValue = invokeNoArgs(lunarEntity, "bridge$getUniqueID");
            if (!(uuidValue instanceof UUID)) {
                return false;
            }
            EntityPlayer player = findPlayer((UUID) uuidValue);
            return player != null && ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Temporarily supplies the disguised player's level to Lunar while its Level Head event builds the row.
     * The native cache is restored at the method return, so the real player's saved level remains untouched.
     */
    public static void beginDisguisedLevelHeadEvent(Object module, Object event) {
        levelHeadEventCalls++;
        if (module != null) lastLevelHeadModule = module;
        LevelHeadPatch patch = LevelHeadPatch.NOOP;
        try {
            LevelHeadBinding binding = getLevelHeadBinding(module);
            LunarProfileSettings settings = liveLevelHeadSettings(binding == null ? module : binding.module);
            EntityPlayer player = playerForNametagEvent(event);
            if (player != null) {
                UUID targetUuid = null;
                String targetName = null;
                int networkLevel = -1;
                LocalDisguise.RemoteDisguiseProfile target = player.getGameProfile() == null ? null
                        : RemoteDisguiseManager.getDisguisedTargetProfile(player.getGameProfile());
                Minecraft mc = Minecraft.getMinecraft();
                if (target != null) {
                    targetUuid = target.uuid;
                    targetName = target.name;
                    networkLevel = target.networkLevelKnown ? target.networkLevel : -1;
                } else if (mc != null && mc.thePlayer == player) {
                    targetUuid = LocalDisguise.getDisguiseTargetUuidForNametag();
                    targetName = LocalDisguise.getTargetName();
                    networkLevel = LocalDisguise.getDisguisedNetworkLevelForNametag();
                }
                if (targetUuid != null && !targetUuid.equals(player.getUniqueID())) {
                    String replacement = binding != null && settings != null
                            && settings.moduleEnabled && settings.levelHead
                            ? findDisguisedLevel(player, binding, settings) : null;
                    String sourceCached = binding == null ? null
                            : usableLevel(binding.levels.get(player.getUniqueID()));
                    String targetCached = binding == null ? null
                            : usableLevel(binding.levels.get(targetUuid));
                    lastDisguisedLevelHeadDecision = "source=" + player.getName() + " target=" + targetName
                            + " events=" + levelHeadEventCalls + " module="
                            + (module == null ? "null" : module.getClass().getSimpleName())
                            + " nativeMap=" + (binding == null ? "missing" : "found")
                            + " moduleEnabled=" + (settings != null && settings.moduleEnabled)
                            + " levelHead=" + (settings != null && settings.levelHead)
                            + " sourceMode=" + (settings == null ? "unavailable" : settings.levelHeadSource)
                            + " sourceCache=" + (sourceCached == null ? "missing" : sourceCached)
                            + " targetCache=" + (targetCached == null ? "missing" : targetCached)
                            + " targetNetwork=" + networkLevel + " selected="
                            + (replacement == null ? "none" : replacement.isEmpty() ? "blank" : replacement);
                    if (replacement != null && binding != null) {
                        UUID sourceUuid = player.getUniqueID();
                        boolean hadValue = binding.levels.containsKey(sourceUuid);
                        String previous = binding.levels.get(sourceUuid);
                        if (!replacement.equals(previous)) {
                            binding.levels.put(sourceUuid, replacement);
                            patch = new LevelHeadPatch(binding.levels, sourceUuid, hadValue, previous, replacement);
                            LunarConnectionDiagnostics.recordOnceAfterStart(
                                    "disguise.lunar_level_head_override", binding.module.getClass().getName(),
                                    "map=UUID-to-string substitution=temporary restore=after-event");
                        }
                    }
                }
            }
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnce("disguise.lunar_level_head_error", error.getClass().getName(),
                    "type=" + error.getClass().getSimpleName());
            // Lunar's private Level Head schema is optional; a reflection mismatch must never affect rendering.
        }
        LEVEL_HEAD_PATCHES.get().push(patch);
    }

    /** Restores the Level Head cache after Lunar has copied the row into its native nametag event. */
    public static void endDisguisedLevelHeadEvent() {
        Deque<LevelHeadPatch> patches = LEVEL_HEAD_PATCHES.get();
        if (patches.isEmpty()) return;
        LevelHeadPatch patch = patches.pop();
        try {
            if (patch.levels != null && patch.replacementValue.equals(patch.levels.get(patch.sourceUuid))) {
                if (patch.hadValue) patch.levels.put(patch.sourceUuid, patch.previousValue);
                else patch.levels.remove(patch.sourceUuid);
            }
        } catch (Throwable ignored) {
            // The row is already built; leave Lunar's live render path alone if its cache changed concurrently.
        }
        if (patches.isEmpty()) LEVEL_HEAD_PATCHES.remove();
    }

    private static EntityPlayer playerForNametagEvent(Object event) {
        try {
            Object lunarEntity = nametagEventEntity(event);
            Object uuidValue = invokeNoArgs(lunarEntity, "bridge$getUniqueID");
            return uuidValue instanceof UUID ? findPlayer((UUID) uuidValue) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String findDisguisedLevel(EntityPlayer player, LevelHeadBinding binding,
                                             LunarProfileSettings settings) {
        Map<UUID, String> levels = binding.levels;
        if (player == null || player.getUniqueID() == null || levels == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer == player) {
            UUID targetUuid = LocalDisguise.getDisguiseTargetUuidForNametag();
            if (targetUuid != null && !targetUuid.equals(player.getUniqueID())) {
                int networkLevel = LocalDisguise.getDisguisedNetworkLevelForNametag();
                if (networkLevel >= 1 && settings.isNetworkLevelHead()) return Integer.toString(networkLevel);
                String targetLevel = usableLevel(levels.get(targetUuid));
                if (targetLevel != null) return targetLevel;
                requestTargetLevel(binding, targetUuid);
                return "";
            }
        }

        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguisedTargetProfile(player.getGameProfile());
        if (target == null || target.uuid == null || target.uuid.equals(player.getUniqueID())) return null;
        if (target.networkLevelKnown && settings.isNetworkLevelHead()) return Integer.toString(target.networkLevel);
        String targetLevel = usableLevel(levels.get(target.uuid));
        if (targetLevel != null) return targetLevel;
        requestTargetLevel(binding, target.uuid);
        return "";
    }

    /** Bounded value-only snapshot used by /disguiserdebug while an entry is active. */
    public static String describeDisguisedLevelForDebug(GameProfile sourceProfile) {
        if (sourceProfile == null || sourceProfile.getId() == null) return "source=unavailable";
        String targetName = "";
        UUID targetUuid = null;
        int networkLevel = -1;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null && mc.thePlayer.getGameProfile() != null
                && sourceProfile.getId().equals(mc.thePlayer.getGameProfile().getId())) {
            targetUuid = LocalDisguise.getDisguiseTargetUuidForNametag();
            targetName = LocalDisguise.getTargetName();
            networkLevel = LocalDisguise.getDisguisedNetworkLevelForNametag();
        } else {
            LocalDisguise.RemoteDisguiseProfile target =
                    RemoteDisguiseManager.getDisguisedTargetProfile(sourceProfile);
            if (target != null) {
                targetName = target.name;
                targetUuid = target.uuid;
                networkLevel = target.networkLevelKnown ? target.networkLevel : -1;
            }
        }
        if (targetUuid == null) return "target=none";
        LevelHeadBinding binding = getLevelHeadBinding(null);
        if (binding == null) {
            return "target=" + targetName + " networkLevel=" + networkLevel
                    + " nativeCache=unavailable " + buildLevelHeadDebugSummary();
        }
        LunarProfileSettings settings = liveLevelHeadSettings(binding.module);
        String sourceCached = usableLevel(binding.levels.get(sourceProfile.getId()));
        String targetCached = usableLevel(binding.levels.get(targetUuid));
        String selected;
        if (settings != null && settings.isNetworkLevelHead() && networkLevel >= 1) {
            selected = "network-api:" + networkLevel;
        } else if (targetCached != null) selected = "target-cache:" + targetCached;
        else selected = "waiting/blank";
        return "target=" + targetName
                + " levelHeadEnabled=" + (settings != null && settings.moduleEnabled && settings.levelHead)
                + " levelHeadSource=" + (settings == null ? "unavailable" : settings.levelHeadSource)
                + " sourceCached=" + (sourceCached == null ? "missing" : sourceCached)
                + " targetCached=" + (targetCached == null ? "missing" : targetCached)
                + " targetNetworkLevel=" + networkLevel
                + " selected=" + selected;
    }

    public static String buildLevelHeadDebugSummary() {
        return "eventCalls=" + levelHeadEventCalls + " lastDisguised={" + lastDisguisedLevelHeadDecision + "}";
    }

    private static Object nametagEventEntity(Object event) throws Exception {
        Method cached = NAMETAG_ENTITY_GETTERS.get(event.getClass());
        if (cached != null) return cached.invoke(event);
        try { return invokeNoArgs(event, "COCIORRCCIRIRORHRHOOOIRCIORHRI"); }
        catch (NoSuchMethodException oldVersion) {
            try { return invokeNoArgs(event, "CCCIHOHHOHICHCHHCRHOIOHCOOOIHR"); }
            catch (NoSuchMethodException currentVersion) {
                // Lunar 2.23 renamed the Level Head event's entity accessor. Find the no-arg
                // getter structurally through the stable bridge$getUniqueID contract.
                for (Method method : event.getClass().getMethods()) {
                    if (method.getParameterTypes().length != 0 || method.getReturnType().isPrimitive()
                            || method.getReturnType() == String.class) continue;
                    try {
                        Object candidate = invokeNoArgs(event, method.getName());
                        if (candidate == null) continue;
                        Object uuid = invokeNoArgs(candidate, "bridge$getUniqueID");
                        if (uuid instanceof UUID) {
                            method.setAccessible(true); NAMETAG_ENTITY_GETTERS.put(event.getClass(), method);
                            return candidate;
                        }
                    } catch (Throwable ignored) { }
                }
                for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
                    for (Method method : type.getDeclaredMethods()) {
                        if (method.getParameterTypes().length != 0 || method.getReturnType().isPrimitive()
                                || method.getReturnType() == String.class) continue;
                        try {
                            method.setAccessible(true);
                            Object candidate = method.invoke(event);
                            if (candidate == null) continue;
                            Object uuid = invokeNoArgs(candidate, "bridge$getUniqueID");
                            if (uuid instanceof UUID) {
                                NAMETAG_ENTITY_GETTERS.put(event.getClass(), method);
                                return candidate;
                            }
                        } catch (Throwable ignored) { }
                    }
                }
                throw currentVersion;
            }
        }
    }

    private static LunarProfileSettings liveLevelHeadSettings(Object module) {
        if (module != null) {
            try {
                boolean enabled = readBooleanNoArgs(module, "isEnabled");
                Field enabledField = findDeclaredField(module.getClass(), LEVEL_HEAD_FIELD, LEGACY_LEVEL_HEAD_FIELD);
                Field sourceField = findDeclaredField(module.getClass(), LEVEL_HEAD_SOURCE_FIELD,
                        LEGACY_LEVEL_HEAD_SOURCE_FIELD);
                if (enabledField == null || sourceField == null) throw new NoSuchFieldException("Level Head options");
                enabledField.setAccessible(true); sourceField.setAccessible(true);
                Object value = readOptionValue(enabledField.get(module));
                Object source = readOptionValue(sourceField.get(module));
                return new LunarProfileSettings("live", enabled, Boolean.TRUE.equals(value), true,
                        source instanceof Enum ? ((Enum<?>) source).name() : String.valueOf(source));
            } catch (Exception oldVersion) { }
        }
        return getActiveProfileSettingsCached();
    }

    @SuppressWarnings("unchecked")
    private static void requestTargetLevel(LevelHeadBinding binding, UUID targetUuid) {
        long now = System.currentTimeMillis();
        Long previous = LEVEL_REQUESTS.get(targetUuid);
        if (previous != null && now - previous.longValue() < 10_000L) return;
        if (LEVEL_REQUESTS.size() >= 4096) LEVEL_REQUESTS.clear();
        LEVEL_REQUESTS.put(targetUuid, now);
        try {
            Object pending;
            try {
                pending = invokeNoArgs(binding.module, CURRENT_LEVEL_HEAD_QUEUE_ACCESSOR);
            } catch (NoSuchMethodException oldVersion) {
                pending = invokeNoArgs(binding.module, "HHHOOOOOHOHIHORCCIOHRRRRCIOIRO");
            }
            if (pending instanceof Set) {
                ((Set<UUID>) pending).add(targetUuid);
                LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_level_head_fetch", "native-queue",
                        "targetQueued=true request=existing-Lunar-Level-Head-service");
            }
        } catch (Exception ignored) { }
    }

    private static String usableLevel(String level) {
        return level == null || level.trim().isEmpty() ? null : level;
    }

    private static boolean isLevelHeadModuleEnabled(Object module) {
        if (module == null) return true;
        long now = System.currentTimeMillis();
        if (now - levelHeadModuleCheckedAt < 500L) return cachedLevelHeadModuleEnabled;
        synchronized (LunarNametagCompatibility.class) {
            now = System.currentTimeMillis();
            if (now - levelHeadModuleCheckedAt < 500L) return cachedLevelHeadModuleEnabled;
            try {
                cachedLevelHeadModuleEnabled = readBooleanNoArgs(module, "isEnabled")
                        && readBooleanSetting(module, LEVEL_HEAD_FIELD, LEGACY_LEVEL_HEAD_FIELD);
            } catch (Throwable ignored) {
                cachedLevelHeadModuleEnabled = true; // The active profile's persisted settings are the fallback.
            }
            levelHeadModuleCheckedAt = now;
            return cachedLevelHeadModuleEnabled;
        }
    }

    private static LunarProfileSettings getActiveProfileSettingsCached() {
        long now = System.currentTimeMillis();
        LunarProfileSettings settings = cachedProfileSettings;
        if (settings != null && now - profileSettingsCheckedAt < 500L) return settings;
        synchronized (LunarNametagCompatibility.class) {
            now = System.currentTimeMillis();
            if (cachedProfileSettings != null && now - profileSettingsCheckedAt < 500L) {
                return cachedProfileSettings;
            }
            try {
                cachedProfileSettings = readActiveLunarProfileSettings();
                profileSettingsCheckedAt = now;
            } catch (Throwable ignored) {
                if (cachedProfileSettings == null) profileSettingsCheckedAt = now;
            }
            return cachedProfileSettings;
        }
    }

    private static LevelHeadBinding getLevelHeadBinding(Object suppliedModule) {
        Object requestedModule = suppliedModule != null ? suppliedModule : lastLevelHeadModule;
        LevelHeadBinding cached = cachedLevelHeadBinding;
        if (cached != null && (requestedModule == null || cached.module == requestedModule)) return cached;
        long now = System.currentTimeMillis();
        if (cached != null && now - levelHeadBindingCheckedAt < 1_000L) return cached;
        synchronized (LunarNametagCompatibility.class) {
            cached = cachedLevelHeadBinding;
            requestedModule = suppliedModule != null ? suppliedModule : lastLevelHeadModule;
            if (cached != null && (requestedModule == null || cached.module == requestedModule)) return cached;
            now = System.currentTimeMillis();
            if (cached != null && now - levelHeadBindingCheckedAt < 1_000L) return cached;
            Object module = requestedModule;
            Map<UUID, String> levels = findLevelHeadMap(module);
            if (levels == null) {
                try {
                    module = findHypixelModuleInstance();
                    levels = findLevelHeadMap(module);
                } catch (Throwable ignored) {
                    module = null;
                }
            }
            cachedLevelHeadBinding = levels == null || module == null ? null : new LevelHeadBinding(module, levels);
            levelHeadBindingCheckedAt = now;
            return cachedLevelHeadBinding;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, String> findLevelHeadMap(Object module) {
        for (Class<?> type = module == null ? null : module.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(module);
                    if (value instanceof Map && (isUuidStringMap(field.getGenericType())
                            || hasUuidStringEntry((Map<?, ?>) value))) return (Map<UUID, String>) value;
                } catch (Throwable ignored) { }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length != 0 || !Map.class.isAssignableFrom(method.getReturnType())
                        || !isUuidStringMap(method.getGenericReturnType())) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(module);
                    if (value instanceof Map) return (Map<UUID, String>) value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static boolean isUuidStringMap(Type type) {
        if (!(type instanceof ParameterizedType)) return false;
        Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
        return arguments.length == 2 && UUID.class.equals(arguments[0]) && String.class.equals(arguments[1]);
    }

    private static boolean hasUuidStringEntry(Map<?, ?> map) {
        if (map == null || map.isEmpty()) return false;
        int inspected = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof UUID && (entry.getValue() == null || entry.getValue() instanceof String)) return true;
            if (++inspected >= 16) break;
        }
        return false;
    }

    /** Called at the tail of Lunar Hypixel's native nametag-event handler. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addDenickLineToLunarEvent(Object event) {
        eventCalls++;
        EntityPlayer debugPlayer = null;
        List debugLines = null;
        int debugRowsBefore = -1;
        String debugMode = "unresolved";
        try {
            if (event == null) {
                lastResult = "event was null";
                return;
            }
            Object lunarEntity = nametagEventEntity(event);
            Object uuidValue = invokeNoArgs(lunarEntity, "bridge$getUniqueID");
            if (!(uuidValue instanceof UUID)) {
                lastResult = "Lunar entity UUID unavailable";
                return;
            }
            EntityPlayer player = findPlayer((UUID) uuidValue);
            if (player == null) {
                lastResult = "world player unavailable for " + uuidValue;
                return;
            }
            debugPlayer = player;
            // ReplayPlayerHealthTagFix renders the complete replay nameplate stack itself. Lunar's native event is
            // still invoked for those players, but adding the denick row here would make it appear a second time
            // beside the replay stack (and can put the killer row on top of it).
            if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)) {
                lastResult = "replay stack owns spectator rows for " + player.getName();
                return;
            }
            Object linesValue = invokeNoArgs(event, "getLines");
            if (!(linesValue instanceof List)) {
                lastResult = "event lines unavailable for " + player.getName();
                return;
            }
            List lines = (List) linesValue;
            debugLines = lines;
            debugRowsBefore = lines.size();
            boolean liveSpectator = VisibleSpecs.isLiveSpectatorSettingsTarget(player);
            String mode = liveSpectator ? VisibleSpecs.getNametagMode() : VisibleSpecs.NAMETAG_MODE_ON;
            debugMode = mode;
            boolean handledNativeStack = false;

            if (liveSpectator && isNativeSpectatorNametagMode(mode)) {
                rewriteLiveSpectatorNametagRows(event, player, lines, mode, true);
                Object rewrittenLines = invokeNoArgs(event, "getLines");
                if (rewrittenLines instanceof List) debugLines = (List) rewrittenLines;
                return;
            }

            boolean denickEnabled = ConfigManager.isDenickerEnabled()
                    && ConfigManager.isNickRealNametagOverlayEnabled();
            String denickLabel = denickEnabled
                    ? NickRealNametagOverlay.buildRealIgnOverlayLabel(player) : null;
            if (denickLabel != null && !denickLabel.isEmpty()) {
                Object denickComponent = deserializeLegacyComponent(denickLabel, event.getClass().getClassLoader());
                if (denickComponent != null) {
                    int insertAt = findImmediatelyAboveMainNameIndex(event, lines);
                    lines.add(insertAt, denickComponent);
                    LUNAR_STACK_ENTITY_IDS.add(Integer.valueOf(player.getEntityId()));
                    insertedRows++;
                    handledNativeStack = true;
                }
            }

            if (SpectatorKillerNametagOverlay.shouldShowFor(player)) {
                String killerLabel = SpectatorKillerNametagOverlay.buildLabel(player);
                Object killerComponent = killerLabel == null || killerLabel.isEmpty()
                        ? null : deserializeLegacyComponent(killerLabel, event.getClass().getClassLoader());
                if (killerComponent != null) {
                    lines.add(killerComponent);
                    LUNAR_KILLER_ENTITY_IDS.add(Integer.valueOf(player.getEntityId()));
                    LUNAR_STACK_ENTITY_IDS.add(Integer.valueOf(player.getEntityId()));
                    insertedRows++;
                    handledNativeStack = true;
                }
            }

            if (handledNativeStack) {
                lastResult = "native spectator stack handled for " + player.getName()
                        + " mode=" + mode + " rows=" + lines.size();
            } else if (!denickEnabled) {
                lastResult = "denicker/world overlay disabled; no spectator row for " + player.getName();
            } else {
                lastResult = "no denick or killer row for " + player.getName();
            }
        } catch (Throwable t) {
            lastResult = "failed: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        } finally {
            recordNativeEventDebug(debugPlayer, debugMode, lastResult, debugRowsBefore, debugLines);
        }
    }

    /**
     * Lunar dispatches the same entity nametag event through several modules. The Hypixel module can add Level Head,
     * and later listeners may append their own rows after its callback returns. Run the live spectator policy again
     * after those listeners so ONLY_IGN/OFF applies to the final stack, with the configured Level Head exception.
     */
    public static void finalizeLiveSpectatorNametagEvent(Object event) {
        if (event == null) return;
        try {
            String mode = VisibleSpecs.getNametagMode();
            if (!isNativeSpectatorNametagMode(mode)
                    || !VisibleSpecs.isLiveSpectatorSettingsRuntimeActive()) return;

            Object lunarEntity = nametagEventEntity(event);
            Object uuidValue = invokeNoArgs(lunarEntity, "bridge$getUniqueID");
            if (!(uuidValue instanceof UUID)) return;
            EntityPlayer player = findPlayer((UUID) uuidValue);
            if (player == null || !VisibleSpecs.isLiveSpectatorSettingsTarget(player)) return;

            Object linesValue = invokeNoArgs(event, "getLines");
            if (!(linesValue instanceof List)) return;
            List lines = (List) linesValue;
            int rowsBefore = lines.size();
            visibleSpecsEventFinalizerCalls++;
            rewriteLiveSpectatorNametagRows(event, player, lines, mode, false);
            Object rewrittenValue = invokeNoArgs(event, "getLines");
            int rewrittenRows = rewrittenValue instanceof List ? ((List) rewrittenValue).size() : -1;
            lastVisibleSpecsEventFinalizer = player.getName() + " mode=" + mode
                    + " rows=" + rowsBefore + "->" + rewrittenRows;
        } catch (Throwable error) {
            lastVisibleSpecsEventFinalizer = "failed: " + error.getClass().getSimpleName();
        }
    }

    /**
     * Called before Lunar's Level Head callback. The filter is scoped to this callback: it blocks additions for Off,
     * and in Only IGN it permits Lunar's own Level Head row only when ONLY_IGN_SHOW_LEVEL_HEAD is enabled.
     */
    @SuppressWarnings("unchecked")
    public static void beginNativeLevelHeadRowFilter(Object event) {
        LevelHeadRowFilterPatch patch = LevelHeadRowFilterPatch.NOOP;
        try {
            EntityPlayer target = findNativeLevelHeadFilterTarget(event);
            if (target != null) {
                boolean keepLevelHead = ONLY_IGN_SHOW_LEVEL_HEAD
                        && VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(VisibleSpecs.getNametagMode());
                Object linesValue = invokeNoArgs(event, "getLines");
                if (linesValue instanceof LevelHeadRowFilterList) {
                    LevelHeadRowFilterList filtered = (LevelHeadRowFilterList) linesValue;
                    filtered.beginSuppressingAdds();
                    patch = new LevelHeadRowFilterPatch(event, null, filtered, false);
                    lastLevelHeadRowFilter = keepLevelHead
                            ? "reused; Only IGN Level Head row allowed"
                            : "reused for native spectator row policy";
                } else if (linesValue instanceof List) {
                    List<Object> original = (List<Object>) linesValue;
                    LevelHeadRowFilterList filtered = new LevelHeadRowFilterList(
                            original, target.getName(), keepLevelHead);
                    filtered.beginSuppressingAdds();
                    if (replaceNativeEventLines(event, filtered)) {
                        patch = new LevelHeadRowFilterPatch(event, original, filtered, true);
                        lastLevelHeadRowFilter = keepLevelHead
                                ? "installed; Only IGN Level Head row allowed"
                                : "installed for native spectator row policy";
                    } else {
                        filtered.endSuppressingAdds();
                        lastLevelHeadRowFilter = "event rejected temporary row filter";
                    }
                } else {
                    lastLevelHeadRowFilter = "event lines unavailable";
                }
            } else {
                lastLevelHeadRowFilter = "not needed for current player/mode";
            }
        } catch (Throwable error) {
            lastLevelHeadRowFilter = "failed: " + error.getClass().getSimpleName();
        }
        LEVEL_HEAD_ROW_FILTERS.get().push(patch);
    }

    /** Restores the event's original list after Lunar has finished building the Level Head row. */
    public static void endNativeLevelHeadRowFilter() {
        Deque<LevelHeadRowFilterPatch> patches = LEVEL_HEAD_ROW_FILTERS.get();
        if (patches.isEmpty()) return;
        LevelHeadRowFilterPatch patch = patches.pop();
        if (patch.filteredLines != null) {
            patch.filteredLines.endSuppressingAdds();
            if (patch.ownsReplacement) {
                try {
                    if (invokeNoArgs(patch.event, "getLines") == patch.filteredLines) {
                        if (replaceNativeEventLines(patch.event, patch.originalLines)) {
                            lastLevelHeadRowFilter = "restored original event list";
                        } else {
                            lastLevelHeadRowFilter = "filter inactive; original-list restore unavailable";
                        }
                    }
                } catch (Throwable error) {
                    lastLevelHeadRowFilter = "filter inactive; restore failed: "
                            + error.getClass().getSimpleName();
                }
            }
        }
        if (patches.isEmpty()) LEVEL_HEAD_ROW_FILTERS.remove();
    }

    /** Returns whether the current Level Head callback belongs to a live spectator row policy. */
    public static boolean shouldSuppressNativeLevelHeadRow(Object event) {
        return findNativeLevelHeadFilterTarget(event) != null;
    }

    /**
     * Skip Lunar's Level Head callback for a live dead/spectator target when that row is disabled. Its row list is
     * rewritten first, so the ordinary IGN and optional Gomod Killer line survive even if the native draw path
     * bypasses the event list decorator.
     */
    @SuppressWarnings("rawtypes")
    public static boolean skipNativeLevelHeadCallback(Object event) {
        try {
            EntityPlayer target = findNativeLevelHeadFilterTarget(event);
            if (target == null) return false;
            String mode = VisibleSpecs.getNametagMode();
            if (ONLY_IGN_SHOW_LEVEL_HEAD && VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)) {
                lastNativeLevelHeadCallbackSkip = "kept native callback: Only IGN Level Head enabled for "
                        + target.getName();
                return false;
            }
            Object linesValue = invokeNoArgs(event, "getLines");
            List lines = linesValue instanceof List ? (List) linesValue : null;
            if (VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)
                    && mainNameComponent(event, lines) == null) {
                // Avoid swallowing the only source of the base IGN on an unfamiliar Lunar event shape.
                lastNativeLevelHeadCallbackSkip = "kept native callback: main IGN component unavailable for "
                        + target.getName();
                return false;
            }
            addDenickLineToLunarEvent(event);
            if (!lastResult.startsWith("spectator mode=" + mode + " replaced native stack for ")) {
                lastNativeLevelHeadCallbackSkip = "kept native callback: row rewrite did not complete for "
                        + target.getName() + " result=" + lastResult;
                return false;
            }
            nativeLevelHeadCallbacksSkipped++;
            Object rewrittenValue = invokeNoArgs(event, "getLines");
            lastNativeLevelHeadCallbackSkip = target.getName() + " mode=" + mode
                    + " preservedRows=" + describeComponentRows(
                    rewrittenValue instanceof List ? (List<?>) rewrittenValue : null);
            lastLevelHeadRowFilter = "native callback skipped for spectator target=" + target.getName()
                    + " mode=" + mode;
            return true;
        } catch (Throwable error) {
            lastNativeLevelHeadCallbackSkip = "failed: " + error.getClass().getSimpleName();
            return false;
        }
    }

    private static EntityPlayer findNativeLevelHeadFilterTarget(Object event) {
        try {
            String mode = VisibleSpecs.getNametagMode();
            if (!isNativeSpectatorNametagMode(mode)
                    || !VisibleSpecs.isLiveSpectatorSettingsRuntimeActive()) return null;

            Object lunarEntity = nametagEventEntity(event);
            Object uuidValue = invokeNoArgs(lunarEntity, "bridge$getUniqueID");
            if (!(uuidValue instanceof UUID)) return null;
            EntityPlayer player = findPlayer((UUID) uuidValue);
            if (player == null) return null;

            boolean suppress = VisibleSpecs.shouldSuppressAdditionalNametagRow(player)
                    || VisibleSpecs.shouldSuppressNametag(player);
            return suppress ? player : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isNativeSpectatorNametagMode(String mode) {
        return VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)
                || VisibleSpecs.NAMETAG_MODE_OFF.equals(mode);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void rewriteLiveSpectatorNametagRows(Object event, EntityPlayer player, List lines,
            String mode, boolean countInsertedRows) throws Exception {
        int rowsBefore = lines == null ? -1 : lines.size();
        boolean onlyIgn = VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode);
        Object mainName = onlyIgn ? mainNameComponent(event, lines) : null;
        boolean allowLevelHead = onlyIgn && ONLY_IGN_SHOW_LEVEL_HEAD;
        Object levelHead = allowLevelHead ? preservedNativeLevelHeadRow(lines) : null;
        Object killerComponent = null;
        if (SpectatorKillerNametagOverlay.shouldShowFor(player)) {
            String killerLabel = SpectatorKillerNametagOverlay.buildLabel(player);
            if (killerLabel != null && !killerLabel.isEmpty()) {
                killerComponent = deserializeLegacyComponent(killerLabel, event.getClass().getClassLoader());
            }
        }

        List effectiveLines = replaceAndGateSpectatorRows(
                event, lines, mainName, killerComponent, levelHead, allowLevelHead);
        if (killerComponent != null) {
            LUNAR_KILLER_ENTITY_IDS.add(Integer.valueOf(player.getEntityId()));
            if (countInsertedRows) insertedRows++;
        }
        LUNAR_STACK_ENTITY_IDS.add(Integer.valueOf(player.getEntityId()));
        lastResult = "spectator mode=" + mode + " replaced native stack for " + player.getName()
                + " killer=" + (killerComponent == null ? "none" : "shown")
                + " levelHead=" + (levelHead == null ? "hidden" : "shown")
                + " lateRowGate=" + (effectiveLines instanceof SpectatorNametagLineGate);
        recordNativeEventDebug(player, mode, lastResult, rowsBefore, effectiveLines);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List replaceAndGateSpectatorRows(Object event, List lines,
            Object mainName, Object killerComponent, Object levelHead, boolean allowLevelHead) {
        if (lines instanceof SpectatorNametagLineGate) {
            SpectatorNametagLineGate gate = (SpectatorNametagLineGate) lines;
            gate.replaceRows(mainName, killerComponent, levelHead, allowLevelHead);
            lastNativeRowGate = "installed/reused blocked=" + lateNativeRowsBlocked;
            return gate;
        }

        SpectatorNametagLineGate gate = new SpectatorNametagLineGate();
        gate.replaceRows(mainName, killerComponent, levelHead, allowLevelHead);
        if (replaceNativeEventLines(event, gate)) {
            lastNativeRowGate = "installed blocked=" + lateNativeRowsBlocked;
            return gate;
        }

        // If a future Lunar event changes its list setter, retain the existing cleanup behavior
        // rather than failing the entire nametag callback. The diagnostic makes that fallback clear.
        if (lines != null) {
            lines.clear();
            if (mainName != null) lines.add(mainName);
            if (levelHead != null) lines.add(levelHead);
            if (killerComponent != null) lines.add(killerComponent);
        }
        lastNativeRowGate = "setter unavailable; fallback list rewrite";
        return lines;
    }

    private static Object preservedNativeLevelHeadRow(List<?> lines) {
        if (lines instanceof LevelHeadRowFilterList) {
            Object accepted = ((LevelHeadRowFilterList) lines).acceptedLevelHeadRow();
            if (accepted != null) return accepted;
        }
        if (lines instanceof SpectatorNametagLineGate) {
            return ((SpectatorNametagLineGate) lines).levelHeadRow();
        }
        return null;
    }

    private static boolean replaceNativeEventLines(Object event, List<?> replacement) {
        if (event == null || replacement == null) return false;
        Method setter = null;
        int matches = 0;
        try {
            for (Method method : event.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getReturnType() == Void.TYPE && parameters.length == 1
                        && parameters[0].isAssignableFrom(replacement.getClass())) {
                    setter = method;
                    matches++;
                }
            }
            if (matches == 1) {
                setter.setAccessible(true);
                setter.invoke(event, replacement);
                return invokeNoArgs(event, "getLines") == replacement;
            }
        } catch (Throwable ignored) {
            // Try the structural List field below if the public native setter is unavailable.
        }

        Field linesField = null;
        matches = 0;
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && field.getType().isAssignableFrom(replacement.getClass())) {
                    linesField = field;
                    matches++;
                }
            }
        }
        if (matches != 1 || linesField == null || Modifier.isFinal(linesField.getModifiers())) return false;
        try {
            linesField.setAccessible(true);
            linesField.set(event, replacement);
            return invokeNoArgs(event, "getLines") == replacement;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recordNativeEventDebug(EntityPlayer player, String mode, String result,
            int rowsBefore, List<?> linesAfter) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (NATIVE_EVENT_DEBUG) {
            Long previous = NATIVE_EVENT_DEBUG_CAPTURE_TIMES.get(player.getName());
            if (previous != null && now - previous.longValue() < 1000L) return;
            NATIVE_EVENT_DEBUG_CAPTURE_TIMES.put(player.getName(), Long.valueOf(now));
        }
        int rowsAfter = linesAfter == null ? -1 : linesAfter.size();
        String detail = "mode=" + mode + " rows=" + rowsBefore + "->" + rowsAfter
                + " nativeStack=" + isHandledByLunarNametagStack(player)
                + " killerInserted=" + isKillerHandledByLunarNametagStack(player)
                + " lines=" + describeComponentRows(linesAfter)
                + " result=" + String.valueOf(result);
        synchronized (NATIVE_EVENT_DEBUG) {
            NATIVE_EVENT_DEBUG.put(player.getName(), detail);
            while (NATIVE_EVENT_DEBUG.size() > 40) {
                String oldest = NATIVE_EVENT_DEBUG.keySet().iterator().next();
                NATIVE_EVENT_DEBUG.remove(oldest);
                NATIVE_EVENT_DEBUG_CAPTURE_TIMES.remove(oldest);
            }
        }
    }

    public static String getNativeEventDebugForPlayer(String playerName) {
        if (playerName == null) {
            return "<no player>";
        }
        synchronized (NATIVE_EVENT_DEBUG) {
            String result = NATIVE_EVENT_DEBUG.get(playerName);
            return result == null ? "no native event recorded" : result;
        }
    }

    /**
     * Lunar stores the ordinary player name in the event's line list. Higher indexes render above it. Insert directly
     * after that component so gomod's real-IGN row is always a separate row immediately above the nametag, while a
     * Lunar Network-level row configured as "above" remains above the gomod row.
     */
    private static int findImmediatelyAboveMainNameIndex(Object event, List lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        Object mainName = mainNameComponent(event, lines);
        int mainIndex = mainName == null ? -1 : lines.indexOf(mainName);
        if (mainIndex >= 0) {
            return Math.min(lines.size(), mainIndex + 1);
        }
        // The event constructor starts with the ordinary nametag at index zero on supported Lunar builds.
        return Math.min(1, lines.size());
    }

    private static Object mainNameComponent(Object event, List lines) {
        String[] accessors = {
                "HOOCOIICIHOHOOIHCRORCHHHIRIRHO",
                "HHORHHRRCIOIIIIIIHCROOCOCCHOIO"
        };
        for (String accessor : accessors) {
            try {
                Object value = invokeNoArgs(event, accessor);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return lines != null && !lines.isEmpty() ? lines.get(0) : null;
    }

    /** Runtime counters consumed by the internal /tagdebug support command. */
    public static String buildDebugSummary() {
        return "eventCalls=" + eventCalls + " insertedRows=" + insertedRows
                + " visibleSpecsFinalizers=" + visibleSpecsEventFinalizerCalls
                + " lastVisibleSpecsFinalizer=" + lastVisibleSpecsEventFinalizer
                + " nativeLevelHeadCallbacksSkipped=" + nativeLevelHeadCallbacksSkipped
                + " lastNativeLevelHeadCallbackSkip={" + lastNativeLevelHeadCallbackSkip + "}"
                + " handledThisFrame=" + LUNAR_STACK_ENTITY_IDS.size()
                + " killerRowsThisFrame=" + LUNAR_KILLER_ENTITY_IDS.size()
                + " playersWithNativeEvent=" + NATIVE_EVENT_DEBUG.size()
                + " nativeLevelHeadRowsSuppressed=" + nativeLevelHeadRowsSuppressed
                + " nativeLevelHeadRowsAllowed=" + nativeLevelHeadRowsAllowed
                + " onlyIgnShowsLevelHead=" + ONLY_IGN_SHOW_LEVEL_HEAD
                + " lastSuppressedLevelHeadRow={" + lastSuppressedLevelHeadRow + "}"
                + " lastLevelHeadRowFilter=" + lastLevelHeadRowFilter
                + " lateNativeRowsBlocked=" + lateNativeRowsBlocked
                + " rowGate=" + lastNativeRowGate
                + " lastResult=" + lastResult
                + " lunarSettings={" + getLunarSettingsDebugSummary() + "}";
    }

    private static String describeComponentRows(List<?> rows) {
        if (rows == null) return "<null>";
        StringBuilder result = new StringBuilder("[");
        int limit = Math.min(rows.size(), 4);
        for (int i = 0; i < limit; i++) {
            if (i > 0) result.append(" | ");
            result.append(safeComponentText(rows.get(i)));
        }
        if (rows.size() > limit) result.append(" | ...+").append(rows.size() - limit);
        return result.append(']').toString();
    }

    private static String safeComponentText(Object component) {
        if (component == null) return "<null>";
        for (String methodName : new String[] {"getFormattedText", "getUnformattedText", "content", "getText"}) {
            try {
                Method method = component.getClass().getMethod(methodName);
                if (method.getParameterTypes().length != 0) continue;
                Object value = method.invoke(component);
                if (value instanceof CharSequence) return compactDebugText(value.toString());
            } catch (Throwable ignored) {
            }
        }
        return compactDebugText(String.valueOf(component));
    }

    private static String compactDebugText(String value) {
        if (value == null) return "<null>";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= 120 ? compact : compact.substring(0, 117) + "...";
    }

    /**
     * Number of Lunar-owned rows above the ordinary IGN that gomod's independent renderer must reserve. The Hypixel
     * module, its Level Head switch, and Level Above must all be enabled. Results are briefly cached because this is
     * queried from player rendering paths.
     */
    public static int countNativeRowsAboveMainName() {
        if (!GomodFixes.isWeaveRuntime()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lunarSettingsCheckedAt < 500L) {
            return cachedNativeRowsAboveMain;
        }
        synchronized (LunarNametagCompatibility.class) {
            now = System.currentTimeMillis();
            if (now - lunarSettingsCheckedAt < 500L) {
                return cachedNativeRowsAboveMain;
            }
            int rows = 0;
            try {
                Object module = lunarReflectionUnavailable ? null : findHypixelModuleInstance();
                if (module != null) {
                    boolean moduleEnabled = readBooleanNoArgs(module, "isEnabled");
                    boolean levelHead = readBooleanSetting(module, LEVEL_HEAD_FIELD, LEGACY_LEVEL_HEAD_FIELD);
                    boolean levelAbove = readBooleanSetting(module, LEVEL_ABOVE_FIELD, LEGACY_LEVEL_ABOVE_FIELD);
                    rows = moduleEnabled && levelHead && levelAbove ? 1 : 0;
                    lunarSettingsResult = "source=runtime moduleEnabled=" + moduleEnabled
                            + " levelHead=" + levelHead + " levelAbove=" + levelAbove
                            + " reservedRows=" + rows;
                } else {
                    LunarProfileSettings settings = readActiveLunarProfileSettings();
                    rows = settings.reservedRows();
                    lunarSettingsResult = settings.describe();
                }
            } catch (Throwable t) {
                lunarReflectionUnavailable = true;
                try {
                    LunarProfileSettings settings = readActiveLunarProfileSettings();
                    rows = settings.reservedRows();
                    lunarSettingsResult = settings.describe() + " runtimeLookup="
                            + t.getClass().getSimpleName();
                } catch (Throwable profileFailure) {
                    lunarSettingsResult = "failed: runtime=" + t.getClass().getSimpleName()
                            + " profile=" + profileFailure.getClass().getSimpleName() + ": "
                            + String.valueOf(profileFailure.getMessage());
                }
            }
            cachedNativeRowsAboveMain = rows;
            lunarSettingsCheckedAt = now;
            return rows;
        }
    }

    public static String getLunarSettingsDebugSummary() {
        countNativeRowsAboveMainName();
        return lunarSettingsResult;
    }

    private static Object findHypixelModuleInstance() throws Exception {
        Object observed = lastLevelHeadModule;
        if (observed != null) return observed;
        ClassLoader loader = LunarNametagCompatibility.class.getClassLoader();
        try {
            Class<?> currentModuleClass = Class.forName(CURRENT_HYPIXEL_MODULE_CLASS, false, loader);
            Class<?> currentModuleAccessor = Class.forName(CURRENT_MODULE_ACCESSOR_CLASS, false, loader);
            Method accessor = currentModuleAccessor.getMethod(CURRENT_MODULE_ACCESSOR, Class.class);
            Object currentModule = accessor.invoke(null, currentModuleClass);
            if (currentModuleClass.isInstance(currentModule)) return currentModule;
        } catch (Throwable currentLookupFailed) {
            // Older Lunar versions use a different module registry below.
        }
        Class<?> moduleClass = Class.forName(LEGACY_HYPIXEL_MODULE_CLASS, false, loader);
        Class<?> registryClass = Class.forName(LEGACY_MODULE_REGISTRY_CLASS, false, loader);
        for (Field field : registryClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof Map)) {
                continue;
            }
            for (Object candidate : ((Map<?, ?>) value).values()) {
                if (candidate != null && moduleClass.isInstance(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean readBooleanSetting(Object module, String... fieldNames) throws Exception {
        Field field = findDeclaredField(module.getClass(), fieldNames);
        if (field == null) throw new NoSuchFieldException("Lunar boolean setting");
        field.setAccessible(true);
        Object value = readOptionValue(field.get(module));
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static Object readOptionValue(Object option) throws Exception {
        try {
            return invokeNoArgs(option, "get");
        } catch (NoSuchMethodException oldVersion) {
            return invokeNoArgs(option, BOOLEAN_SETTING_GETTER);
        }
    }

    private static Field findDeclaredField(Class<?> type, String... fieldNames) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String fieldName : fieldNames) {
                final Class<?> owner = current;
                Optional<Field> found = DECLARED_FIELDS.get(owner).computeIfAbsent(fieldName, key -> {
                    try { return Optional.of(owner.getDeclaredField(key)); }
                    catch (NoSuchFieldException absent) { return Optional.empty(); }
                });
                if (found.isPresent()) return found.get();
            }
        }
        return null;
    }

    private static boolean readBooleanNoArgs(Object target, String methodName) throws Exception {
        Object value = invokeNoArgs(target, methodName);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /**
     * Lunar's class names are obfuscated and can change on any client update. Its persisted profile schema uses stable
     * names, so this is the reliable fallback when the runtime bridge no longer matches the installed Lunar build.
     */
    private static LunarProfileSettings readActiveLunarProfileSettings() throws Exception {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            throw new IllegalStateException("user.home unavailable");
        }
        File gameSettings = new File(new File(new File(userHome, ".lunarclient"), "settings"), "game");
        File profileManager = new File(gameSettings, "profile_manager.json");
        String activeProfile = "Default";
        try (FileReader reader = new FileReader(profileManager)) {
            JsonArray profiles = new JsonParser().parse(reader).getAsJsonArray();
            for (JsonElement element : profiles) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject profile = element.getAsJsonObject();
                if (readJsonBoolean(profile, "active", false) && profile.has("name")) {
                    activeProfile = profile.get("name").getAsString();
                    break;
                }
            }
        }

        File modsFile = new File(new File(gameSettings, activeProfile), "mods.json");
        try (FileReader reader = new FileReader(modsFile)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonObject hypixel = root.has("HYPIXEL_MOD") && root.get("HYPIXEL_MOD").isJsonObject()
                    ? root.getAsJsonObject("HYPIXEL_MOD") : null;
            if (hypixel == null) {
                return new LunarProfileSettings(activeProfile, false, false, true, "NETWORK");
            }
            // Lunar omits values equal to their defaults. HYPIXEL_MOD and levelAbove default on; Level Head does not.
            boolean moduleEnabled = readJsonBoolean(hypixel, "enabled", true);
            JsonObject options = hypixel.has("options") && hypixel.get("options").isJsonObject()
                    ? hypixel.getAsJsonObject("options") : null;
            boolean levelHead = options != null && readJsonBoolean(options, "levelHead", false);
            boolean levelAbove = options == null || readJsonBoolean(options, "levelAbove", true);
            String levelSource = readJsonString(options, "levelHeadSource", "NETWORK");
            return new LunarProfileSettings(activeProfile, moduleEnabled, levelHead, levelAbove, levelSource);
        }
    }

    private static String readJsonString(JsonObject object, String key, String fallback) {
        try {
            if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
            JsonElement value = object.get(key);
            if (value.isJsonPrimitive()) return value.getAsString();
            if (value.isJsonObject() && value.getAsJsonObject().has("value")) {
                return value.getAsJsonObject().get("value").getAsString();
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static boolean readJsonBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static final class LunarProfileSettings {
        private final String profile;
        private final boolean moduleEnabled;
        private final boolean levelHead;
        private final boolean levelAbove;
        private final String levelHeadSource;

        private LunarProfileSettings(String profile, boolean moduleEnabled, boolean levelHead, boolean levelAbove,
                                      String levelHeadSource) {
            this.profile = profile;
            this.moduleEnabled = moduleEnabled;
            this.levelHead = levelHead;
            this.levelAbove = levelAbove;
            this.levelHeadSource = levelHeadSource == null || levelHeadSource.trim().isEmpty()
                    ? "NETWORK" : levelHeadSource.trim().toUpperCase(java.util.Locale.ROOT);
        }

        private int reservedRows() {
            return moduleEnabled && levelHead && levelAbove ? 1 : 0;
        }

        private String describe() {
            return "source=profile profile=" + profile + " moduleEnabled=" + moduleEnabled
                    + " levelHead=" + levelHead + " levelAbove=" + levelAbove
                    + " levelHeadSource=" + levelHeadSource
                    + " reservedRows=" + reservedRows();
        }

        private boolean isNetworkLevelHead() {
            return "NETWORK".equals(levelHeadSource);
        }
    }

    private static final class LevelHeadBinding {
        private final Object module;
        private final Map<UUID, String> levels;

        private LevelHeadBinding(Object module, Map<UUID, String> levels) {
            this.module = module;
            this.levels = levels;
        }
    }

    private static final class LevelHeadPatch {
        private static final LevelHeadPatch NOOP = new LevelHeadPatch(null, null, false, null, null);
        private final Map<UUID, String> levels;
        private final UUID sourceUuid;
        private final boolean hadValue;
        private final String previousValue;
        private final String replacementValue;

        private LevelHeadPatch(Map<UUID, String> levels, UUID sourceUuid, boolean hadValue, String previousValue,
                               String replacementValue) {
            this.levels = levels;
            this.sourceUuid = sourceUuid;
            this.hadValue = hadValue;
            this.previousValue = previousValue;
            this.replacementValue = replacementValue;
        }
    }

    private static EntityPlayer findPlayer(UUID uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || uuid == null) {
            return null;
        }
        if (mc.thePlayer != null && uuid.equals(mc.thePlayer.getUniqueID())) return mc.thePlayer;
        for (Object value : mc.theWorld.playerEntities) {
            if (value instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) value;
                if (uuid.equals(player.getUniqueID())) {
                    return player;
                }
            }
        }
        return null;
    }

    private static Object deserializeLegacyComponent(String text, ClassLoader eventLoader) throws Exception {
        String[] serializers = {
                "com.moonsworth.lunar.lib.adventure.text.serializer.legacy.LegacyComponentSerializer",
                "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer"
        };
        ClassLoader[] loaders = {
                eventLoader,
                Thread.currentThread().getContextClassLoader(),
                LunarNametagCompatibility.class.getClassLoader()
        };
        Throwable lastFailure = null;
        for (String serializerName : serializers) {
            for (ClassLoader loader : loaders) {
                if (loader == null) {
                    continue;
                }
                try {
                    Class<?> serializerClass = Class.forName(serializerName, true, loader);
                    Object serializer = serializerClass.getMethod("legacySection").invoke(null);
                    // Lunar's concrete serializer is package-private on 2.23.2. Calling a public method
                    // obtained from that implementation class can still fail Java access checks, so invoke
                    // deserialize through the public serializer interface instead.
                    Method fallback = null;
                    for (Method method : serializerClass.getMethods()) {
                        if (!"deserialize".equals(method.getName()) || method.getParameterTypes().length != 1) {
                            continue;
                        }
                        Class<?> parameter = method.getParameterTypes()[0];
                        if (parameter == String.class) {
                            return method.invoke(serializer, text);
                        }
                        if (parameter == CharSequence.class || parameter.isAssignableFrom(String.class)) {
                            fallback = method;
                        }
                    }
                    if (fallback != null) {
                        return fallback.invoke(serializer, text);
                    }
                    throw new NoSuchMethodException(serializerName + ".deserialize(String)");
                } catch (Throwable failure) {
                    lastFailure = failure;
                }
            }
        }
        if (lastFailure instanceof Exception) {
            throw (Exception) lastFailure;
        }
        throw new IllegalStateException("No compatible Lunar legacy component serializer", lastFailure);
    }

    private static Object invokeNoArgs(Object target, String methodName) throws Exception {
        if (target == null) {
            return null;
        }
        final Class<?> type = target.getClass();
        NoArgLookup lookup = NO_ARG_METHODS.get(type).computeIfAbsent(methodName, key -> new NoArgLookup(type, key));
        if (lookup.method == null) throw lookup.missing;
        return lookup.method.invoke(target);
    }
}
