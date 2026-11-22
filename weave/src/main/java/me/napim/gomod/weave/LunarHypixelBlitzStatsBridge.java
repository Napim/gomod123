package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.ScoreObjective;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime bridge into Lunar's native Hypixel stats module and session counter. */
public final class LunarHypixelBlitzStatsBridge {
    private static final String BLITZ_LABEL = "Blitz Survival Games";
    private static final String BEDWARS_CHILD_ID = "HYPIXEL_STATS_BEDWARS";
    private static volatile String statsChildFactoryClassName;
    private static volatile String dataModelClassName;
    private static volatile String statsOptionGroupClassName;
    private static volatile String eventEnumClassName;
    private static volatile String hypixelStatsClassName;
    private static volatile String detailsNameSetterMethod;
    private static volatile String modelBaseModeGetterMethod;
    private static volatile String modelBaseGameIdField;
    private static volatile String modelBaseChildField;
    private static volatile String hypixelStatsInLobbyField;
    private static volatile String hypixelStatsGameIdField;
    private static volatile String hypixelStatsChildParentMethod;
    private static final Pattern COLOR = Pattern.compile("(?i)\u00a7[0-9a-fk-or]");
    private static final Pattern LOCAL_KILL = Pattern.compile(
            "^(?:you (?:have )?killed (\\w{1,16})\\b.*|you damaged (\\w{1,16}) for \\d+(?:\\.\\d+)?% before killing them|"
                    + "(\\w{1,16}) was (?:killed|slain|shot|eliminated) by you)!?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLIC_KILL = Pattern.compile(
            "^(\\w{1,16}) was (?:killed|slain|shot|eliminated) by (\\w{1,16})!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_DEATH = Pattern.compile(
            "^(?:you (?:were|was|have been) (?:killed|slain|shot|eliminated)(?: by .+)?|"
                    + "you died(?:[,!.].*)?|you have been eliminated(?:[!.].*)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WIN_LINE = Pattern.compile(
            "^(?:victory!?|you win!?|you won(?: the game)?!?|you are the winner!?|you came in first place!?|"
                    + "you won!?\\s+want to play again\\b.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WIN_BONUS_LINE = Pattern.compile(
            "^\\+\\d+(?:,\\d{3})* coins! \\(win bonus\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEMENT_LINE = Pattern.compile(
            "^you came in (\\d+)(?:st|nd|rd|th) place!?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINNER_LINE = Pattern.compile(
            "^(?:the\\s+)?winner\\s*(?:is\\s*|was\\s*|[-:\\u2013\\u2014]\\s*)?(.+?)\\s*[.!]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINNING_TEAM_LINE = Pattern.compile(
            "^winning team\\s*[-:\\u2013\\u2014]\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOSS_LINE = Pattern.compile(
            "^(?:you did not win!?|you lost(?: the game)?!?)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_CONTEXT_LINE = Pattern.compile(
            "^(?:(?:you are |now )?(?:playing |in )?)?blitz(?: survival)? games?(?: game)?(?:\\s*[-:|].*)?[!.]?$"
                    + "|^blitz\\s*sg(?:\\s*[-:|].*)?[!.]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_GAME_CONTEXT_LINE = Pattern.compile(
            "^(?:(?:you are |now )?(?:playing |in )?)?(?:bed ?wars|sky ?wars|duels|murder mystery|"
                    + "uhc champions|skyblock|wool games|the pit)(?: .*)?(?: game)?[!.]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCRAW_GAMETYPE = Pattern.compile(
            "\"gametype\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCRAW_MODE = Pattern.compile(
            "\"mode\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCRAW_SERVER = Pattern.compile(
            "\"server\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCRAW_LOBBY = Pattern.compile(
            "\"lobbyname\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_ID_SUFFIX = Pattern.compile("\\d+[A-Za-z]*$");

    private static final ThreadLocal<Boolean> constructingBlitzModel = new ThreadLocal<>();
    private static final Map<Object, Object> blitzChildByParent = new WeakHashMap<>();
    private static final Map<Object, Boolean> blitzChildren = new WeakHashMap<>();
    private static final Map<Object, Boolean> blitzModels = new WeakHashMap<>();
    private static final Map<Object, String> blitzStatsGroups = new WeakHashMap<>();
    private static final Map<Object, Boolean> blitzParserInstances = new WeakHashMap<>();
    private static final Map<Object, Object> modelByParser = new WeakHashMap<>();
    private static final Map<Object, Match> blitzMatches = new WeakHashMap<>();
    private static final long RESULT_CONTEXT_GRACE_MS = 8 * 60_000L;
    private static final String SOLO_RULE = "teaming is not allowed on solo maps!";
    private static volatile WeakReference<Object> explicitBlitzWorld = new WeakReference<>(null);
    private static volatile WeakReference<Object> nonBlitzWorld = new WeakReference<>(null);
    private static volatile long lastExplicitBlitzContextMs;

    private static final class Match {
        final WeakReference<Object> world;
        final Set<String> kills = new HashSet<>();
        String serverId;
        long contextMs;
        boolean explicitBlitz;
        boolean inLobby;
        boolean solo;
        boolean modeKnown;
        boolean started;
        boolean died;
        boolean soloLossPending;
        boolean finished;

        Match(Object world) {
            this.world = new WeakReference<>(world);
        }
    }

    private LunarHypixelBlitzStatsBridge() { }

    /** Marks the copied native tracker so its game-presence gate can accept Blitz. */
    public static void registerBlitzModel(Object model) {
        if (!Boolean.TRUE.equals(constructingBlitzModel.get()) || model == null) return;
        synchronized (blitzModels) {
            if (Boolean.TRUE.equals(blitzModels.get(model))) return;
            blitzModels.put(model, Boolean.TRUE);
        }
        linkBlitzParser(model);
        markBlitzStatsGroups(model);
    }

    private static void linkBlitzParser(Object model) {
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value != null && isBlitzParser(value)) {
                        synchronized (modelByParser) {
                            modelByParser.put(value, model);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void registerStatsChildMetadata(String factoryInternalName, String parentGetterMethod) {
        if (factoryInternalName != null) {
            statsChildFactoryClassName = factoryInternalName.replace('/', '.');
        }
        if (parentGetterMethod != null) {
            hypixelStatsChildParentMethod = parentGetterMethod;
        }
    }

    public static void registerDataModelClass(String internalName) {
        if (internalName != null) {
            dataModelClassName = internalName.replace('/', '.');
        }
    }

    public static void registerStatsOptionGroupClass(String internalName) {
        if (internalName != null) {
            statsOptionGroupClassName = internalName.replace('/', '.');
        }
    }

    public static void registerEventEnumClass(String internalName) {
        if (internalName != null) {
            eventEnumClassName = internalName.replace('/', '.');
        }
    }

    public static void registerDetailsNameSetter(String methodName) {
        if (methodName != null) {
            detailsNameSetterMethod = methodName;
        }
    }

    public static void registerModelBaseMetadata(String modeGetterMethod, String gameIdField, String childField) {
        if (modeGetterMethod != null) {
            modelBaseModeGetterMethod = modeGetterMethod;
        }
        if (gameIdField != null) {
            modelBaseGameIdField = gameIdField;
        }
        if (childField != null) {
            modelBaseChildField = childField;
        }
    }

    public static void registerHypixelStatsMetadata(String moduleInternalName, String gameIdField, String inLobbyField) {
        if (moduleInternalName != null) {
            hypixelStatsClassName = moduleInternalName.replace('/', '.');
        }
        if (gameIdField != null) {
            hypixelStatsGameIdField = gameIdField;
        }
        if (inLobbyField != null) {
            hypixelStatsInLobbyField = inLobbyField;
        }
    }

    private static void markBlitzStatsGroups(Object model) {
        int marked = 0;
        try {
            Class<?> groupType = resolveStatsOptionGroupClass(model.getClass().getClassLoader());
            for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(model);
                        if (value == null) continue;
                        if (groupType != null ? !groupType.isInstance(value) : !looksLikeStatsOptionGroup(value.getClass())) {
                            continue;
                        }
                        synchronized (blitzStatsGroups) {
                            blitzStatsGroups.put(value, optionId(value));
                        }
                        marked++;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable failure) {
            System.err.println("[gomod] Could not isolate Lunar Blitz stat groups: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_option_groups", model.getClass().getName(),
                "modelRegistered=true markedGroups=" + marked);
        System.out.println("[gomod] Isolated " + marked + " Lunar Blitz stat option groups");
    }

    private static Class<?> resolveStatsOptionGroupClass(ClassLoader loader) {
        String name = statsOptionGroupClassName;
        if (name != null) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean looksLikeStatsOptionGroup(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            boolean hasIsPersistent = false;
            boolean hasGetList = false;
            for (Method method : current.getDeclaredMethods()) {
                if ("isPersistent".equals(method.getName()) && method.getParameterCount() == 0
                        && method.getReturnType() == boolean.class) {
                    hasIsPersistent = true;
                } else if ("get".equals(method.getName()) && method.getParameterCount() == 0
                        && List.class.isAssignableFrom(method.getReturnType())) {
                    hasGetList = true;
                }
            }
            if (hasIsPersistent && hasGetList) {
                statsOptionGroupClassName = current.getName();
                return true;
            }
        }
        return false;
    }

    /** Removes Bed Wars-only choices from this Blitz model's native lifetime/session stat groups. */
    public static List<?> filterBlitzStats(List<?> nativeOptions, Object group) {
        if (nativeOptions == null || group == null) return nativeOptions;
        String groupId;
        synchronized (blitzStatsGroups) {
            if (!blitzStatsGroups.containsKey(group)) return nativeOptions;
            groupId = blitzStatsGroups.get(group);
        }

        List<Object> filtered = new ArrayList<>(nativeOptions.size());
        for (Object option : nativeOptions) {
            String id = optionId(option);
            if (isBlitzStat(id)) filtered.add(option);
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_option_filter",
                group.getClass().getName() + ":" + groupId,
                "group=" + groupId + " sourceCount=" + nativeOptions.size() + " kept=" + filtered.size());
        return Collections.unmodifiableList(filtered);
    }

    private static boolean isBlitzStat(String id) {
        if (id == null) return false;
        switch (id) {
            case "kills":
            case "deaths":
            case "wins":
            case "losses":
            case "winstreak":
            case "games":
            case "coins":
                return true;
            default:
                return false;
        }
    }

    static String optionId(Object option) {
        if (option == null) return null;
        String id = callString(option, "getId");
        if (id != null) return id;
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = option.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(current);
        }
        // Check from the root base class (StatsItem / StatsOptionGroup) downward so subclass
        // formatValue() methods never shadow the base ID field.
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> current = hierarchy.get(i);
            Field singleStringField = null;
            int stringFieldCount = 0;
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    singleStringField = field;
                    stringFieldCount++;
                }
            }
            if (stringFieldCount == 1) {
                try {
                    singleStringField.setAccessible(true);
                    Object value = singleStringField.get(option);
                    if (value instanceof String) return (String) value;
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            Class<?> current = hierarchy.get(i);
            Method singleStringMethod = null;
            int stringMethodCount = 0;
            for (Method method : current.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 0
                        && method.getReturnType() == String.class
                        && !"toString".equals(method.getName())) {
                    singleStringMethod = method;
                    stringMethodCount++;
                }
            }
            if (stringMethodCount == 1) {
                try {
                    singleStringMethod.setAccessible(true);
                    Object value = singleStringMethod.invoke(option);
                    if (value instanceof String) return (String) value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    public static boolean isBlitzModel(Object model) {
        synchronized (blitzModels) {
            return Boolean.TRUE.equals(blitzModels.get(model));
        }
    }

    /** The module's translation key is the same string used for the saved toggle. */
    public static boolean isBlitzChild(Object child) {
        if (child == null) return false;
        synchronized (blitzChildren) {
            if (Boolean.TRUE.equals(blitzChildren.get(child))) return true;
        }
        String factoryClass = statsChildFactoryClassName;
        if (factoryClass != null && !child.getClass().getName().equals(factoryClass + "$1")) {
            return false;
        }
        return BLITZ_LABEL.equals(callString(child, "getId"));
    }

    /** Supplies the one stable display name for this child in Lunar's Mods and HUD render paths. */
    public static String fixBlitzHudLabel(String renderedLabel, Object child) {
        if (child == null || !isBlitzChild(child)) return renderedLabel;
        if (!BLITZ_LABEL.equals(renderedLabel)) {
            String childId = callString(child, "getId");
            String labelKind = "name".equalsIgnoreCase(renderedLabel) ? "missing-name"
                    : renderedLabel != null && renderedLabel.toLowerCase(Locale.ROOT).endsWith(" game")
                    ? "generic-game-suffix" : "other-fallback";
            LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_hud_label",
                    child.getClass().getName() + ":" + labelKind,
                    "childTracked=true childIdResolved=" + (childId != null) + " source=" + labelKind
                            + " forcedCanonicalLabel=true");
        }
        return BLITZ_LABEL;
    }

    /** The parent has no Lunar catalog entry for the custom child. */
    public static String translateBlitzLabel(String rendered, Object owner, String key) {
        if (("name".equals(key) && isBlitzChild(owner))
                || (BLITZ_LABEL.equals(key) && "HYPIXEL_STATS".equals(callString(owner, "getId")))) {
            return BLITZ_LABEL;
        }
        return rendered;
    }

    public static String blitzModuleName(String rendered, Object child) {
        if (!isBlitzChild(child)) return rendered;
        LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_module_label", "native-name",
                "source=native-name label=" + BLITZ_LABEL);
        return BLITZ_LABEL;
    }

    public static com.google.gson.JsonElement blitzModuleMetadata(com.google.gson.JsonElement metadata, Object child) {
        if (metadata != null && metadata.isJsonObject() && isBlitzChild(child)) {
            metadata.getAsJsonObject().addProperty("name", BLITZ_LABEL);
            LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_module_label", "metadata",
                    "source=metadata label=" + BLITZ_LABEL);
        }
        return metadata;
    }

    /** Give the Blitz child a native Lunar details name supplier so Mods/Settings/HUD read "Blitz Survival Games". */
    public static Object configureBlitzDetailsBuilder(Object builder, Object child) {
        if (builder == null || !isBlitzChild(child)) return builder;
        try {
            Method nameSetter = resolveDetailsNameSetter(builder.getClass());
            if (nameSetter == null) {
                throw new NoSuchMethodException("Details name(Supplier) setter on " + builder.getClass().getName());
            }
            nameSetter.setAccessible(true);
            nameSetter.invoke(builder, (Supplier<String>) () -> BLITZ_LABEL);
            LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_module_label", "details-builder",
                    "source=details-builder label=" + BLITZ_LABEL);
        } catch (Throwable failure) {
            System.err.println("[gomod] Could not set Lunar Blitz module display name: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return builder;
    }

    private static Method resolveDetailsNameSetter(Class<?> builderClass) {
        String methodName = detailsNameSetterMethod;
        if (methodName != null) {
            try {
                return builderClass.getMethod(methodName, Supplier.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        String discovered = LunarHypixelBlitzStatsTransform.discoverDetailsNameSetter(builderClass);
        if (discovered != null) {
            detailsNameSetterMethod = discovered;
            try {
                return builderClass.getMethod(discovered, Supplier.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        Method unique = null;
        for (Method method : builderClass.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Supplier.class) {
                if (unique != null) return null;
                unique = method;
            }
        }
        return unique;
    }

    /** Feed the native widget and its settings only the session section for Blitz. */
    public static List<?> blitzHudSections(List<?> sections, Object child) {
        if (sections != null && isBlitzChild(child)) {
            synchronized (blitzStatsGroups) {
                for (Object section : sections) {
                    if ("session".equals(blitzStatsGroups.get(section))) {
                        LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_hud_sections", "session",
                                "sourceCount=" + sections.size() + " retained=session");
                        return Collections.singletonList(section);
                    }
                }
            }
        }
        return sections;
    }

    public static boolean blitzGamePresence(boolean nativePresence, Object model) {
        if (!isBlitzModel(model)) return nativePresence;
        if (isExplicitlyNonBlitz()) return false;
        return nativePresence || isBlitzContext();
    }

    public static boolean shouldStartBlitzSession(Object model, String raw, Enum<?> nativeState) {
        if (!isBlitzModel(model) || raw == null || nativeState == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) return false;
        boolean started = shouldStartBlitzSessionInternal(
                model, raw, nativeState.name(), mc.theWorld, currentScoreboardTitle(mc),
                isNativeModuleInLobby(model), GameContext.isHypixelLobby(mc),
                nativeModelMode(model), System.currentTimeMillis());
        if (started) {
            syncNativeGameIdIfMissing(model);
        }
        return started;
    }

    static boolean shouldStartBlitzSessionInternal(Object model, String raw, String nativeStateName, Object world,
            String scoreboardTitle, boolean nativeLobby, boolean sidebarLobby, String nativeMode, long now) {
        if (model == null || raw == null || nativeStateName == null || world == null) return false;
        String message = stripFormatting(raw).trim().replaceAll("\\s+", " ");
        if (message.isEmpty()) return false;

        Match match = observeChat(model, message, world, scoreboardTitle, now);
        if ("RUNNING".equals(nativeStateName)) return false;
        if (match == null || nonBlitzWorld.get() == world) return false;

        boolean inLobby = match.inLobby || (!match.explicitBlitz && (nativeLobby || sidebarLobby));
        if (inLobby) return false;

        // One WorldClient runs at most one Blitz match. The end-of-game summary repeats "Blitz Survival Games"
        // before "Winner - ...", so never restart the native session once this world's match has started or ended.
        if (match.started || match.finished || match.died) return false;

        boolean strictBlitz = match.explicitBlitz || isBlitzContextInternal(world, scoreboardTitle, now);
        if (!strictBlitz) return false;

        boolean explicitBanner = BLITZ_CONTEXT_LINE.matcher(message).matches();
        boolean start = explicitBanner;
        if (!start && !"ENDED".equals(nativeStateName)) {
            start = LOCAL_KILL.matcher(message).matches()
                    || LOCAL_DEATH.matcher(message).matches()
                    || WIN_LINE.matcher(message).matches()
                    || WINNER_LINE.matcher(message).matches()
                    || WINNING_TEAM_LINE.matcher(message).matches()
                    || LOSS_LINE.matcher(message).matches()
                    || message.regionMatches(true, 0, SOLO_RULE, 0, SOLO_RULE.length());
        }
        if (!start) return false;

        match.started = true;
        match.contextMs = now;
        applyModeIfUnknown(match, nativeMode);
        return true;
    }

    /** Appends a normal Lunar stats child, built through Lunar's own generic-child factory. */
    public static List<?> withBlitzChild(List<?> nativeChildren, Object parent) {
        if (nativeChildren == null || parent == null) return nativeChildren;
        for (Object child : nativeChildren) {
            if (BLITZ_LABEL.equals(callString(child, "getId"))) return nativeChildren;
        }

        Object blitzChild;
        synchronized (blitzChildByParent) {
            blitzChild = blitzChildByParent.get(parent);
            if (blitzChild == null) {
                blitzChild = newNativeStatsChild(nativeChildren, parent);
                if (blitzChild == null) return nativeChildren;
                blitzChildByParent.put(parent, blitzChild);
            }
        }
        synchronized (blitzChildren) {
            blitzChildren.put(blitzChild, Boolean.TRUE);
        }

        List<Object> result = new ArrayList<>(nativeChildren.size() + 1);
        result.addAll(nativeChildren);
        result.add(blitzChild);
        return result;
    }

    private static Object newNativeStatsChild(List<?> nativeChildren, Object parent) {
        try {
            Object bedwarsChild = null;
            for (Object child : nativeChildren) {
                if (BEDWARS_CHILD_ID.equals(callString(child, "getId"))) {
                    bedwarsChild = child;
                    break;
                }
            }
            if (bedwarsChild == null) {
                System.err.println("[gomod] Lunar Blitz stats: native Bed Wars exemplar was not in the module list");
                return null;
            }

            Object bedwarsModel = findDataModel(bedwarsChild);
            if (bedwarsModel == null) {
                System.err.println("[gomod] Lunar Blitz stats: could not find the current Bed Wars data model");
                return null;
            }

            Constructor<?> modelConstructor = null;
            for (Constructor<?> constructor : bedwarsModel.getClass().getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 1 && parameters[0].isAssignableFrom(bedwarsChild.getClass())) {
                    modelConstructor = constructor;
                    break;
                }
            }
            if (modelConstructor == null) {
                System.err.println("[gomod] Lunar Blitz stats: no native data-model constructor matched");
                return null;
            }
            modelConstructor.setAccessible(true);
            final Constructor<?> nativeModelConstructor = modelConstructor;

            Class<?> factoryClass = resolveStatsChildFactoryClass(bedwarsChild, parent.getClass().getClassLoader());
            Method factory = null;
            for (Method method : factoryClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && parameters.length == 3
                        && parameters[0].isInstance(parent) && parameters[1] == String.class
                        && Function.class.isAssignableFrom(parameters[2])) {
                    factory = method;
                    break;
                }
            }
            if (factory == null) {
                System.err.println("[gomod] Lunar Blitz stats: native generic-child factory was not found");
                return null;
            }
            factory.setAccessible(true);

            Function<Object, Object> modelFactory = statsChild -> {
                Boolean previous = constructingBlitzModel.get();
                constructingBlitzModel.set(Boolean.TRUE);
                try {
                    return nativeModelConstructor.newInstance(statsChild);
                } catch (Throwable failure) {
                    throw new IllegalStateException("Could not construct Lunar Blitz stats data model", failure);
                } finally {
                    if (previous == null) constructingBlitzModel.remove();
                    else constructingBlitzModel.set(previous);
                }
            };
            Object child = factory.invoke(null, parent, BLITZ_LABEL, modelFactory);
            if (child != null) System.out.println("[gomod] Added native Lunar Hypixel Stats child: " + BLITZ_LABEL);
            return child;
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            System.err.println("[gomod] Could not create Lunar-native Blitz stats child: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return null;
        }
    }

    private static Class<?> resolveStatsChildFactoryClass(Object bedwarsChild, ClassLoader loader)
            throws ClassNotFoundException {
        String registered = statsChildFactoryClassName;
        if (registered != null) {
            return Class.forName(registered, true, loader);
        }
        return bedwarsChild.getClass().getSuperclass();
    }

    private static Object findDataModel(Object child) {
        String targetClassName = dataModelClassName;
        Object fallback = null;
        for (Class<?> type = child == null ? null : child.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(child);
                    if (value == null) continue;
                    if (targetClassName != null) {
                        if (targetClassName.equals(value.getClass().getName())) return value;
                    } else if (hasSingleConstructorAccepting(value.getClass(), child.getClass())) {
                        fallback = value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (fallback != null) {
            dataModelClassName = fallback.getClass().getName();
        }
        return fallback;
    }

    private static boolean hasSingleConstructorAccepting(Class<?> candidate, Class<?> argType) {
        if (candidate.isPrimitive() || candidate == String.class) return false;
        for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(argType)) {
                return true;
            }
        }
        return false;
    }

    public static String dataMode(String originalMode) {
        return Boolean.TRUE.equals(constructingBlitzModel.get()) && "BEDWARS".equals(originalMode)
                ? "SURVIVAL_GAMES" : originalMode;
    }

    /** The native message parser is per-model; mark only the one constructed for the Blitz child. */
    public static void registerBlitzParser(Object parser) {
        if (!Boolean.TRUE.equals(constructingBlitzModel.get()) || parser == null) return;
        synchronized (blitzParserInstances) {
            blitzParserInstances.put(parser, Boolean.TRUE);
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_parser_registered",
                parser.getClass().getName(), "isolatedParser=true");
    }

    public static boolean isBlitzParser(Object parser) {
        synchronized (blitzParserInstances) {
            return Boolean.TRUE.equals(blitzParserInstances.get(parser));
        }
    }

    /** Returns the same native event enum consumed by Lunar's existing session-stat model. */
    public static Object classifyBlitz(Object parser, String rawMessage) {
        if (parser == null || !isBlitzParser(parser)) return event(parser, "NONE");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || !GameContext.isOnHypixel(mc)) return event(parser, "NONE");
            Object key = matchKeyForParser(parser);
            String eventName = classifyBlitzEventInternal(
                    key, rawMessage, mc.theWorld, currentScoreboardTitle(mc), localPlayerName(),
                    nativeModelMode(key), System.currentTimeMillis());
            return event(parser, eventName);
        } catch (Throwable ignored) {
            return event(parser, "NONE");
        }
    }

    static String classifyBlitzEventInternal(Object matchKey, String rawMessage, Object world,
            String scoreboardTitle, String localName, String nativeMode, long now) {
        if (matchKey == null || rawMessage == null || world == null) return "NONE";
        String message = stripFormatting(rawMessage).trim().replaceAll("\\s+", " ");
        if (message.isEmpty()) return "NONE";

        Match match = observeChat(matchKey, message, world, scoreboardTitle, now);
        if (match == null || nonBlitzWorld.get() == world || match.inLobby) return "NONE";

        boolean inBlitzContext = match.explicitBlitz || isBlitzContextInternal(world, scoreboardTitle, now);
        if (inBlitzContext) match.contextMs = now;
        boolean recentBlitzContext = inBlitzContext
                || (match.contextMs > 0L && now - match.contextMs <= RESULT_CONTEXT_GRACE_MS);

        Matcher kill = LOCAL_KILL.matcher(message);
        String victim = kill.matches() ? firstCapture(kill) : null;
        boolean localDeath = LOCAL_DEATH.matcher(message).matches();
        Matcher publicKill = PUBLIC_KILL.matcher(message);
        if (publicKill.matches() && localName != null && !localName.isEmpty()) {
            if (publicKill.group(2).equalsIgnoreCase(localName)) victim = publicKill.group(1);
            if (publicKill.group(1).equalsIgnoreCase(localName)) localDeath = true;
        }
        if (!recentBlitzContext) {
            if (victim != null || localDeath) {
                LunarConnectionDiagnostics.record("hypixel_stats.blitz_event_ignored",
                        "candidate=" + (localDeath ? "DEATH" : "KILL") + " strictContext=" + inBlitzContext
                                + " recentContext=false");
            }
            return "NONE";
        }

        if (localDeath) {
            if (match.died || match.finished) return "NONE";
            match.died = true;
            applyModeIfUnknown(match, nativeMode);
            match.soloLossPending = match.solo;
            LunarConnectionDiagnostics.record("hypixel_stats.blitz_session_event",
                    "event=DEATH source=local-death-chat");
            return "DEATH";
        }
        if (victim != null) {
            if (match.finished || !match.kills.add(victim.toLowerCase(Locale.ROOT))) return "NONE";
            LunarConnectionDiagnostics.record("hypixel_stats.blitz_session_event",
                    "event=KILL source=local-kill-chat");
            return "KILL";
        }
        if (message.regionMatches(true, 0, SOLO_RULE, 0, SOLO_RULE.length())) {
            match.solo = true;
            match.modeKnown = true;
        }

        String result = null;
        String resultSource = null;
        if (match.started && WIN_BONUS_LINE.matcher(message).matches()) {
            result = "WIN";
            resultSource = "win-bonus-chat";
        } else if (WIN_LINE.matcher(message).matches()) {
            result = "WIN";
            resultSource = "explicit-win-chat";
        } else {
            Matcher placement = PLACEMENT_LINE.matcher(message);
            if (placement.matches()) {
                result = "1".equals(placement.group(1)) ? "WIN" : "LOSS";
                resultSource = "placement-chat";
            }

            Matcher winner = WINNER_LINE.matcher(message);
            if (result == null && winner.matches()) {
                String winnerName = winner.group(1).trim();
                if (!"draw".equalsIgnoreCase(winnerName)) {
                    if (localName != null && !localName.isEmpty()) {
                        result = containsPlayerName(winnerName, localName) ? "WIN" : "LOSS";
                        resultSource = "winner-announcement-chat";
                    }
                }
            }
            Matcher winningTeam = WINNING_TEAM_LINE.matcher(message);
            if (result == null && winningTeam.matches()) {
                if (localName != null && !localName.isEmpty()) {
                    result = containsPlayerName(winningTeam.group(1), localName) ? "WIN" : "LOSS";
                    resultSource = "winning-team-chat";
                }
            }
            if (result == null && LOSS_LINE.matcher(message).matches()) {
                result = "LOSS";
                resultSource = "explicit-loss-chat";
            }
        }

        if (result != null && !match.finished) {
            match.finished = true;
            LunarConnectionDiagnostics.record("hypixel_stats.blitz_session_event",
                    "event=" + result + " source=" + resultSource);
            return result;
        }
        return "NONE";
    }

    private static Match observeChat(Object matchKey, String message, Object world, String scoreboardTitle, long now) {
        if (message.startsWith("{") && message.endsWith("}") && message.contains("\"gametype\"")) {
            Matcher gameTypeMatcher = LOCRAW_GAMETYPE.matcher(message);
            if (gameTypeMatcher.find()) {
                String gameType = gameTypeMatcher.group(1).trim();
                if ("SURVIVAL_GAMES".equalsIgnoreCase(gameType)) {
                    if (nonBlitzWorld.get() == world) nonBlitzWorld = new WeakReference<>(null);
                    explicitBlitzWorld = new WeakReference<>(world);
                    lastExplicitBlitzContextMs = now;
                    Match match = getOrCreateMatch(matchKey, world);
                    Matcher lobbyMatcher = LOCRAW_LOBBY.matcher(message);
                    if (lobbyMatcher.find()) {
                        match.inLobby = true;
                        match.explicitBlitz = false;
                    } else {
                        match.inLobby = false;
                        match.explicitBlitz = true;
                        match.contextMs = now;
                        Matcher serverMatcher = LOCRAW_SERVER.matcher(message);
                        if (serverMatcher.find()) {
                            match.serverId = normalizeServerId(serverMatcher.group(1));
                        }
                        Matcher modeMatcher = LOCRAW_MODE.matcher(message);
                        if (modeMatcher.find()) {
                            applyModeIfUnknown(match, modeMatcher.group(1));
                        }
                    }
                    return match;
                }
                nonBlitzWorld = new WeakReference<>(world);
                explicitBlitzWorld = new WeakReference<>(null);
                lastExplicitBlitzContextMs = 0L;
                synchronized (blitzMatches) {
                    blitzMatches.remove(matchKey);
                }
                return null;
            }
        }

        if (OTHER_GAME_CONTEXT_LINE.matcher(message).matches()) {
            nonBlitzWorld = new WeakReference<>(world);
            explicitBlitzWorld = new WeakReference<>(null);
            lastExplicitBlitzContextMs = 0L;
            synchronized (blitzMatches) {
                blitzMatches.remove(matchKey);
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_chat_context",
                    matchKey.getClass().getName(), "source=explicit-other-game-line cleared=true");
            return null;
        }

        Match match = getOrCreateMatch(matchKey, world);
        if (BLITZ_CONTEXT_LINE.matcher(message).matches() && nonBlitzWorld.get() != world
                && !match.inLobby && !isExplicitlyNonBlitzTitle(scoreboardTitle)) {
            explicitBlitzWorld = new WeakReference<>(world);
            lastExplicitBlitzContextMs = now;
            match.explicitBlitz = true;
            match.contextMs = now;
            LunarConnectionDiagnostics.recordOnceAfterStart("hypixel_stats.blitz_chat_context",
                    matchKey.getClass().getName(), "source=explicit-blitz-game-line");
        } else if (message.regionMatches(true, 0, SOLO_RULE, 0, SOLO_RULE.length())) {
            match.solo = true;
            match.modeKnown = true;
        }
        return match;
    }

    private static Match getOrCreateMatch(Object matchKey, Object world) {
        synchronized (blitzMatches) {
            Match match = blitzMatches.get(matchKey);
            if (match == null || match.world.get() != world) {
                match = new Match(world);
                blitzMatches.put(matchKey, match);
            }
            return match;
        }
    }

    private static Object matchKeyForParser(Object parser) {
        synchronized (modelByParser) {
            Object model = modelByParser.get(parser);
            return model != null ? model : parser;
        }
    }

    private static void applyModeIfUnknown(Match match, String mode) {
        if (match == null || match.modeKnown || mode == null || mode.isEmpty()) return;
        String lower = mode.toLowerCase(Locale.ROOT);
        if (lower.startsWith("solo")) {
            match.solo = true;
            match.modeKnown = true;
        } else if (lower.startsWith("team")) {
            match.solo = false;
            match.modeKnown = true;
        }
    }

    private static String normalizeServerId(String rawServer) {
        if (rawServer == null || rawServer.isEmpty()) return null;
        Matcher matcher = SERVER_ID_SUFFIX.matcher(rawServer.trim());
        return matcher.find() ? matcher.group() : rawServer.trim();
    }

    private static String firstCapture(Matcher kill) {
        for (int i = 1; i <= kill.groupCount(); i++) {
            if (kill.group(i) != null) return kill.group(i);
        }
        return null;
    }

    /** The native callback counts the death before applying the solo loss to its session totals. */
    public static boolean takeBlitzSoloLoss(Object parser) {
        return takeBlitzSoloLossInternal(matchKeyForParser(parser));
    }

    static boolean takeBlitzSoloLossInternal(Object matchKey) {
        synchronized (blitzMatches) {
            Match match = blitzMatches.get(matchKey);
            if (match == null || !match.soloLossPending || match.finished) return false;
            match.soloLossPending = false;
            match.finished = true;
        }
        LunarConnectionDiagnostics.record("hypixel_stats.blitz_session_event", "event=LOSS source=solo-elimination");
        return true;
    }

    public static boolean keepNativeElimination(boolean nativeElimination, Object model) {
        return nativeElimination && !isBlitzModel(model);
    }

    private static Object event(Object parser, String name) {
        try {
            Class<?> type = resolveEventEnumClass(parser);
            if (type == null || !Enum.class.isAssignableFrom(type)) return null;
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> resolveEventEnumClass(Object parser) {
        String enumName = eventEnumClassName;
        if (enumName != null) {
            try {
                return Class.forName(enumName, false, parser.getClass().getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        for (Method method : parser.getClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && method.getReturnType().isEnum()) {
                eventEnumClassName = method.getReturnType().getName();
                return method.getReturnType();
            }
        }
        return null;
    }

    public static boolean isBlitzContext() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || !GameContext.isOnHypixel(minecraft)) return false;
            return isBlitzContextInternal(minecraft.theWorld, currentScoreboardTitle(minecraft),
                    System.currentTimeMillis());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isBlitzContextInternal(Object world, String scoreboardTitle, long now) {
        if (world == null) return false;
        if (nonBlitzWorld.get() == world) return false;
        if (scoreboardTitle != null && !scoreboardTitle.isEmpty()) {
            if (isBlitzScoreboardTitle(scoreboardTitle)) return true;
            if (!scoreboardTitle.contains("gameover")) return false;
        }
        long blitzContext = lastExplicitBlitzContextMs;
        long age = now - blitzContext;
        return blitzContext > 0L && age >= 0L && age <= RESULT_CONTEXT_GRACE_MS
                && explicitBlitzWorld.get() == world;
    }

    private static boolean isExplicitlyNonBlitz() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null || minecraft.theWorld == null) return false;
            if (nonBlitzWorld.get() == minecraft.theWorld) return true;
            return isExplicitlyNonBlitzTitle(currentScoreboardTitle(minecraft));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isExplicitlyNonBlitzTitle(String normalizedTitle) {
        return normalizedTitle != null && !normalizedTitle.isEmpty()
                && !isBlitzScoreboardTitle(normalizedTitle)
                && !normalizedTitle.contains("gameover");
    }

    private static boolean isBlitzScoreboardTitle(String normalizedTitle) {
        return normalizedTitle.contains("blitzsg")
                || normalizedTitle.contains("blitzsurvivalgames")
                || normalizedTitle.equals("blitz");
    }

    private static String currentScoreboardTitle(Minecraft minecraft) {
        if (minecraft == null || minecraft.theWorld == null) return "";
        ScoreObjective objective = minecraft.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        if (objective == null) return "";
        return stripFormatting(objective.getDisplayName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String nativeModelMode(Object model) {
        String methodName = modelBaseModeGetterMethod;
        if (methodName != null) {
            String value = callString(model, methodName);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean isNativeModuleInLobby(Object model) {
        try {
            Object module = nativeHypixelStatsModule(model);
            String fieldName = hypixelStatsInLobbyField;
            if (module == null || fieldName == null) return false;
            Field field = findField(module.getClass(), fieldName);
            return field != null && Boolean.TRUE.equals(field.get(module));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void syncNativeGameIdIfMissing(Object model) {
        try {
            String fieldName = modelBaseGameIdField;
            if (fieldName == null) return;
            Field gameIdField = findField(model.getClass(), fieldName);
            if (gameIdField == null || gameIdField.get(model) != null) return;
            String gameId = null;
            synchronized (blitzMatches) {
                Match match = blitzMatches.get(model);
                if (match != null) gameId = match.serverId;
            }
            if (gameId == null && hypixelStatsGameIdField != null) {
                Object module = nativeHypixelStatsModule(model);
                if (module != null) {
                    Field moduleGameId = findField(module.getClass(), hypixelStatsGameIdField);
                    if (moduleGameId != null) {
                        Object value = moduleGameId.get(module);
                        if (value instanceof String) gameId = (String) value;
                    }
                }
            }
            if (gameId != null && !gameId.isEmpty()) {
                gameIdField.set(model, gameId);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object nativeHypixelStatsModule(Object model) {
        try {
            String childFieldName = modelBaseChildField;
            Object child = null;
            if (childFieldName != null) {
                Field childField = findField(model.getClass(), childFieldName);
                if (childField != null) child = childField.get(model);
            }
            if (child == null) return null;
            String parentMethodName = hypixelStatsChildParentMethod;
            if (parentMethodName != null) {
                Method parentMethod = child.getClass().getMethod(parentMethodName);
                parentMethod.setAccessible(true);
                return parentMethod.invoke(child);
            }
            String moduleClassName = hypixelStatsClassName;
            if (moduleClassName != null) {
                for (Method method : child.getClass().getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                            && method.getReturnType().getName().equals(moduleClassName)) {
                        method.setAccessible(true);
                        return method.invoke(child);
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    static void resetTestState() {
        synchronized (blitzMatches) {
            blitzMatches.clear();
        }
        explicitBlitzWorld = new WeakReference<>(null);
        nonBlitzWorld = new WeakReference<>(null);
        lastExplicitBlitzContextMs = 0L;
    }

    private static String localPlayerName() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            return minecraft == null || minecraft.thePlayer == null ? null : minecraft.thePlayer.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String callString(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return (String) method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsPlayerName(String text, String playerName) {
        if (text == null || text.isEmpty() || playerName == null || playerName.isEmpty()) return false;
        playerName = stripFormatting(playerName).trim();
        if (playerName.isEmpty()) return false;
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(playerName)
                + "(?![A-Za-z0-9_])").matcher(stripFormatting(text)).find();
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : COLOR.matcher(text).replaceAll("");
    }
}
