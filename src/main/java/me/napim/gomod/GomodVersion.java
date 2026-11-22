package me.napim.gomod;

import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Installed mod version (from {@code gomod.version.properties} on the classpath).
 * Format: {@code MAJOR.MINOR.PATCH} (e.g. 0.3.0, 0.3.1, 0.4.0).
 * Do not bump on every build — see {@code docs/GOMOD_VERSIONING.md}.
 */
public final class GomodVersion {
    private static final String VERSION = loadVersion();

    private GomodVersion() {}

    public static String get() {
        return VERSION;
    }

    /**
     * @return negative if {@code installed} &lt; {@code latest}, positive if greater, 0 if equal
     */
    public static int compare(String installed, String latest) {
        if (installed == null) {
            installed = "";
        }
        if (latest == null) {
            latest = "";
        }
        String[] a = installed.trim().split("\\.");
        String[] b = latest.trim().split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int av = i < a.length ? parsePart(a[i]) : 0;
            int bv = i < b.length ? parsePart(b[i]) : 0;
            if (av != bv) {
                return av < bv ? -1 : 1;
            }
        }
        return 0;
    }

    public static boolean isOlderThan(String latest) {
        return compare(VERSION, latest) < 0;
    }

    private static int parsePart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        String digits = part.replaceAll("[^0-9].*", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String loadVersion() {
        try (InputStream in = GomodVersion.class.getClassLoader().getResourceAsStream("gomod.version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return "0.0";
    }
}
