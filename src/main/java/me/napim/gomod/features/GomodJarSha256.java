package me.napim.gomod.features;

import java.io.File;
import java.io.FileInputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.MessageDigest;

/** Reads the SHA-256 of the archive that supplied gomod classes, once per process. */
public final class GomodJarSha256 {
    private static volatile boolean initialized;
    private static volatile String cached = "";

    private GomodJarSha256() {}

    public static String current() {
        if (!initialized) {
            synchronized (GomodJarSha256.class) {
                if (!initialized) {
                    cached = calculate();
                    initialized = true;
                }
            }
        }
        return cached;
    }

    private static String calculate() {
        try {
            CodeSource source = GomodJarSha256.class.getProtectionDomain() == null
                    ? null : GomodJarSha256.class.getProtectionDomain().getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) return "";
            if ("jar".equalsIgnoreCase(location.getProtocol())) {
                URLConnection connection = location.openConnection();
                if (!(connection instanceof JarURLConnection)) return "";
                location = ((JarURLConnection) connection).getJarFileURL();
            }
            if (!"file".equalsIgnoreCase(location.getProtocol())) return "";

            File archive = new File(location.toURI());
            if (!archive.isFile()) return "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            try (FileInputStream input = new FileInputStream(archive)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }

            char[] hex = "0123456789abcdef".toCharArray();
            byte[] bytes = digest.digest();
            char[] result = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                result[i * 2] = hex[value >>> 4];
                result[i * 2 + 1] = hex[value & 0x0f];
            }
            return new String(result);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
