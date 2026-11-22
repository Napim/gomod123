package me.napim.gomod.features;

import java.lang.reflect.Field;

/**
 * Compatibility with the separate AntiShuffle Weave mod.
 *
 * AntiShuffle strips Hypixel's obfuscation markers from TAB text, but gomod can compose the final TAB line after
 * AntiShuffle's mixins have already run. Keep this tiny bridge render-time only so toggling AntiShuffle does not
 * require rebuilding gomod's TAB cache.
 */
public final class AntiShuffleCompat {
    private static final String ANTISHUFFLE_MAIN = "me.ballmc.AntiShuffle.Main";
    private static volatile Field antiShuffleEnabledField;
    private static volatile Boolean antiShuffleClassMissing;

    private AntiShuffleCompat() {
    }

    public static String cleanTabTextIfExternalAntiShuffleEnabled(String text) {
        if (text == null || text.isEmpty() || !isExternalAntiShuffleEnabled()) {
            return text;
        }
        return stripAntiShuffleArtifacts(text);
    }

    private static boolean isExternalAntiShuffleEnabled() {
        try {
            Field field = antiShuffleEnabledField;
            if (field == null) {
                if (Boolean.TRUE.equals(antiShuffleClassMissing)) {
                    return false;
                }
                Class<?> cls = findAntiShuffleMainClass();
                if (cls == null) {
                    antiShuffleClassMissing = Boolean.TRUE;
                    return false;
                }
                field = cls.getField("enabled");
                field.setAccessible(true);
                antiShuffleEnabledField = field;
                antiShuffleClassMissing = Boolean.FALSE;
            }
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> findAntiShuffleMainClass() {
        ClassLoader ownLoader = AntiShuffleCompat.class.getClassLoader();
        ClassLoader contextLoader = Thread.currentThread() == null ? null : Thread.currentThread().getContextClassLoader();
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();

        Class<?> cls = tryFind(ownLoader);
        if (cls != null) {
            return cls;
        }
        cls = tryFind(contextLoader);
        if (cls != null) {
            return cls;
        }
        return tryFind(systemLoader);
    }

    private static Class<?> tryFind(ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(ANTISHUFFLE_MAIN, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stripAntiShuffleArtifacts(String text) {
        String withoutObfuscation = stripObfuscationCodes(text);
        return withoutObfuscation.indexOf("XXXX") >= 0
                ? withoutObfuscation.replace("XXXX", "")
                : withoutObfuscation;
    }

    private static String stripObfuscationCodes(String text) {
        int marker = text.indexOf('\u00A7');
        if (marker < 0) {
            return text;
        }

        StringBuilder out = null;
        int start = 0;
        for (int i = marker; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '\u00A7') {
                continue;
            }
            char code = text.charAt(i + 1);
            if (code != 'k' && code != 'K') {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, start, i);
            i++;
            start = i + 1;
        }

        if (out == null) {
            return text;
        }
        out.append(text, start, text.length());
        return out.toString();
    }
}
