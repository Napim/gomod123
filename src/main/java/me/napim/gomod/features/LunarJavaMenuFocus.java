package me.napim.gomod.features;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import net.minecraft.client.Minecraft;

/** Reads the inspected Java GuiScreen bridge. Never queries a native Browser or field content. */
public final class LunarJavaMenuFocus {
    public static final String SCREEN = "com.moonsworth.lunar.legacy.wrapper.ROOCCIHRCCHORHCCOIIRIHHHIIHHHO."
            + "IOCIHHIRIROCOIROOCORCCIOOIIIRR.CIIHHRHHHIOIIHHRROOHRHICIHOOOC";
    /** Current Lunar wrapper class; the older mapped class remains supported for older clients. */
    public static final String CURRENT_SCREEN = "com.moonsworth.lunar.legacy.wrapper.ORCOIHCCICICIHRCCCCHHHRICHHICI."
            + "CICOHIIRRHHIIHRROHOICOCRHHHHOO.RCHICCCIHRCRCHCIRRIIRRHICORHHO";
    private static final ClassValue<Optional<Method>> ACCESSORS = new ClassValue<Optional<Method>>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            String name = type.getName();
            if (!name.startsWith("com.moonsworth.lunar.")) return Optional.empty();
            try {
                Method method = type.getMethod("bridge$hasTextFieldFocused");
                if (method.getReturnType() != boolean.class
                        || method.getParameterTypes().length != 0
                        || Modifier.isNative(method.getModifiers())
                        || Modifier.isStatic(method.getModifiers())
                        || !method.getDeclaringClass().getName().startsWith("com.moonsworth.lunar.")) {
                    return Optional.empty();
                }
                return Optional.of(method);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return Optional.empty();
            }
        }
    };
    private static boolean failureReported;
    private LunarJavaMenuFocus() { }

    public static boolean supports(Object screen) {
        return screen != null && GomodFixes.isWeaveRuntime() && ACCESSORS.get(screen.getClass()).isPresent();
    }

    public static boolean isEditing(Object screen) {
        if (!supports(screen)) return false;
        try {
            // Installed legacy wrapper -> Java GUI component list -> isEditing(), verified offline.
            return Boolean.TRUE.equals(ACCESSORS.get(screen.getClass()).get().invoke(screen));
        } catch (ReflectiveOperationException error) {
            if (!failureReported) {
                failureReported = true;
                LunarConnectionDiagnostics.record("lunar.java_menu_focus_failed", "exception=" + error.getClass().getSimpleName());
            }
            return false;
        }
    }

    /** Preserve explicit clicks; only bypass the Mods/Staff keyboard handler's implicit focus. */
    public static boolean shouldSkipAutomaticSearch(boolean alreadyEditing) {
        if (alreadyEditing) return true;
        Minecraft mc = Minecraft.getMinecraft();
        return GomodFixes.isWeaveRuntime() && mc != null && mc.thePlayer != null && mc.theWorld != null
                && GomodMenuSettings.isLunarMenuControlsEnabled() && LunarUiInputState.isOverlayContext(mc);
    }
}
