package me.napim.gomod.mixins;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.CapeVisibilityReminder;
import me.napim.gomod.features.LunarCosmeticRuntime;
import me.napim.gomod.features.LunarUiInputState;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Filters Lunar's replacement conflicts when multi-cosmetic support is enabled. */
public final class LunarMultiSameTypeCosmeticSupport {
    private static volatile boolean warned;
    private static volatile boolean reportedPreserve;
    private static volatile boolean reportedRemovalWrapper;
    private static volatile boolean reportedRemovalPredicate;
    private static volatile boolean reportedResolver;
    private static final ThreadLocal<Object> ACTIVE_EQUIP_SELECTION = new ThreadLocal<Object>();

    private LunarMultiSameTypeCosmeticSupport() {
    }

    public static List<?> preserveSameCategory(Object selected, List<?> conflicts, boolean adding) {
        if (!adding || selected == null || conflicts == null
                || !ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics()) {
            return conflicts;
        }

        try {
            List<Object> filtered = new ArrayList<Object>(conflicts.size());
            boolean preserved = false;
            for (Object conflict : conflicts) {
                if (conflict == null) {
                    filtered.add(null);
                    continue;
                }
                if (isDifferentCosmetic(selected, conflict)) {
                    preserved = true;
                } else {
                    // Keep Lunar's normal same-item click-to-unequip behavior and native
                    // handling when the cosmetic IDs cannot be inspected.
                    filtered.add(conflict);
                }
            }
            if (preserved) reportPreserved();
            return filtered;
        } catch (Throwable error) {
            warnOnce(error);
            return conflicts;
        }
    }

    /** Preview requests use adding=false because they do not equip; their conflict list still hides worn items. */
    public static List<?> preservePreviewConflicts(Object selected,List<?> conflicts){
        List<?> filtered=preserveSameCategory(selected,conflicts,true);
        if(filtered!=conflicts&&filtered.size()!=conflicts.size()) {
            String selectedId=cosmeticId(selected),before=cosmeticIds(conflicts),after=cosmeticIds(filtered);
            LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.preview",selectedId+":"+before,
                    "wornConflictsRetained=true selectedId="+selectedId+" beforeIds="+before+" afterIds="+after);
        }
        return filtered;
    }

    private static String cosmeticId(Object cosmetic){try{return String.valueOf(LunarCosmeticRuntime.id(cosmetic));}catch(Throwable unavailable){return "unknown";}}
    private static String cosmeticIds(List<?> cosmetics){
        if(cosmetics==null)return "none";StringBuilder ids=new StringBuilder();int count=0;
        for(Object cosmetic:cosmetics){if(count>=32)break;if(count>0)ids.append(',');ids.append(cosmeticId(cosmetic));count++;}
        if(cosmetics.size()>count)ids.append(",+").append(cosmetics.size()-count);return ids.length()==0?"none":ids.toString();
    }

    /** Called at the start of Lunar's equip resolver; used by its later removal callback. */
    public static void beginEquipResolution(Object selected, boolean adding) {
        if (adding && selected != null && LunarUiInputState.isOverlayContext(Minecraft.getMinecraft())) {
            try {
                if ("CLOAK".equals(LunarCosmeticRuntime.category(selected))) {
                    CapeVisibilityReminder.onLunarCapeSelected();
                }
            } catch (Throwable ignored) {
                // A reminder must never interfere with Lunar's native Locker selection.
            }
        }
        if (adding && selected != null
                && ConfigManager.isLunarTweaksAllowMultipleSameTypeCosmetics()) {
            ACTIVE_EQUIP_SELECTION.set(selected);
            if (!reportedResolver) {
                reportedResolver = true;
                LunarConnectionDiagnostics.record("cosmetics.multi_same_type", "resolver=active");
            }
        } else {
            ACTIVE_EQUIP_SELECTION.remove();
        }
    }

    /**
     * Wraps Lunar's conflict-removal action before it runs. Its resolver mutates the equipped
     * list before returning, so filtering only the returned conflicts list is too late.
     */
    @SuppressWarnings("unchecked")
    public static Consumer<?> preserveEquippedSameCategory(Consumer<?> removalAction) {
        final Object selected = ACTIVE_EQUIP_SELECTION.get();
        if (selected == null || removalAction == null) {
            return removalAction;
        }
        if (!reportedRemovalWrapper) {
            reportedRemovalWrapper = true;
            LunarConnectionDiagnostics.record("cosmetics.multi_same_type", "removalWrapper=active");
        }

        return conflict -> {
            try {
                if (isDifferentCosmetic(selected, conflict)) {
                    reportPreserved();
                    return;
                }
            } catch (Throwable error) {
                warnOnce(error);
            }
            ((Consumer<Object>) removalAction).accept(conflict);
        };
    }

    /** Wraps Lunar 2.23.1's in-place List.removeIf before it drops a conflicting cosmetic. */
    @SuppressWarnings("unchecked")
    public static java.util.function.Predicate<?> preserveSameCategoryRemovalPredicate(
            java.util.function.Predicate<?> removalPredicate) {
        final Object selected = ACTIVE_EQUIP_SELECTION.get();
        if (selected == null || removalPredicate == null) {
            return removalPredicate;
        }
        if (!reportedRemovalPredicate) {
            reportedRemovalPredicate = true;
            LunarConnectionDiagnostics.record("cosmetics.multi_same_type", "removalPredicate=active");
        }

        return conflict -> {
            try {
                if (isDifferentCosmetic(selected, conflict)) {
                    reportPreserved();
                    return false;
                }
            } catch (Throwable error) {
                warnOnce(error);
            }
            return ((java.util.function.Predicate<Object>) removalPredicate).test(conflict);
        };
    }

    public static void endEquipResolution() {
        ACTIVE_EQUIP_SELECTION.remove();
    }

    private static boolean isDifferentCosmetic(Object selected, Object conflict) throws Exception {
        if (selected == null || conflict == null) {
            return false;
        }
        if ("CLOAK".equals(me.napim.gomod.features.LunarCosmeticRuntime.category(selected))
                || "CLOAK".equals(me.napim.gomod.features.LunarCosmeticRuntime.category(conflict))) return false;
        return me.napim.gomod.features.LunarCosmeticRuntime.id(selected)
                != me.napim.gomod.features.LunarCosmeticRuntime.id(conflict);
    }

    private static void reportPreserved() {
        if (!reportedPreserve) {
            reportedPreserve = true;
            System.out.println("[gomod] Local multi-cosmetic support preserved a conflicting "
                    + "different cosmetic.");
            LunarConnectionDiagnostics.record("cosmetics.multi_same_type", "existingItemPreserved=true");
            LunarConnectionDiagnostics.record("cosmetics.multi_same_type",
                    "incompatibleCosmeticPreserved=true");
        }
    }

    private static void warnOnce(Throwable error) {
        if (!warned) {
            warned = true;
            System.err.println("[gomod] Multiple Lunar cosmetic support could not inspect "
                    + "the active Lunar cosmetic model; keeping Lunar's default equip rules: "
                    + error.getClass().getSimpleName());
            LunarConnectionDiagnostics.record("cosmetics.multi_same_type",
                    "modelInspectionFailed=" + error.getClass().getSimpleName());
        }
    }

}
