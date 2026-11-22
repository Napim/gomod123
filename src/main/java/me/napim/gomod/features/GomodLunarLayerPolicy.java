package me.napim.gomod.features;

/** Cached structural classification for Lunar-owned render layers. */
public final class GomodLunarLayerPolicy {
    private static final ClassValue<Boolean> LUNAR_COSMETIC_LAYER_CLASSES = new LunarLayerClassValue();

    private GomodLunarLayerPolicy() {
    }

    public static boolean isLunarCosmeticLayer(Class<?> type) {
        return type != null && LUNAR_COSMETIC_LAYER_CLASSES.get(type).booleanValue();
    }

    private static final class LunarLayerClassValue extends ClassValue<Boolean> {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String normalized = type.getName().toLowerCase(java.util.Locale.ROOT);
            return Boolean.valueOf(normalized.contains("lunar") || normalized.contains("moonsworth")
                    || normalized.contains("apollo") || normalized.contains("cosmetic"));
        }
    }
}
